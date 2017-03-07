/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.beam.sdk.io.hadoop.inputformat;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AtomicDouble;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.hadoop.inputformat.coders.WritableCoder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.ObjectWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link HadoopInputFormatIO} is a Transform for reading data from any source which
 * implements Hadoop {@link InputFormat}. For example- Cassandra, Elasticsearch, HBase, Redis,
 * Postgres etc. {@link HadoopInputFormatIO} has to make several performance trade-offs in
 * connecting to {@link InputFormat}, so if there is another Beam IO Transform specifically for
 * connecting to your data source of choice, we would recommend using that one, but this IO
 * Transform allows you to connect to many data sources that do not yet have a Beam IO Transform.
 *
 * <p>You will need to pass a Hadoop {@link Configuration} with parameters specifying how the read
 * will occur. Many properties of the Configuration are optional, and some are required for certain
 * {@link InputFormat} classes, but the following properties must be set for all InputFormats:
 * <ul>
 * <li>{@code mapreduce.job.inputformat.class}: The {@link InputFormat} class used to connect to
 * your data source of choice.</li>
 * <li>{@code key.class}: The key class returned by the {@link InputFormat} in
 * {@code mapreduce.job.inputformat.class}.</li>
 * <li>{@code value.class}: The value class returned by the {@link InputFormat} in
 * {@code mapreduce.job.inputformat.class}.</li>
 * </ul>
 * For example:
 *
 * <pre>
 * {
 *   Configuration myHadoopConfiguration = new Configuration(false);
 *   // Set Hadoop InputFormat, key and value class in configuration
 *   myHadoopConfiguration.setClass(&quot;mapreduce.job.inputformat.class&quot;,
 *      MyDbInputFormatClass, InputFormat.class);
 *   myHadoopConfiguration.setClass(&quot;key.class&quot;, MyDbInputFormatKeyClass, Object.class);
 *   myHadoopConfiguration.setClass(&quot;value.class&quot;,
 *      MyDbInputFormatValueClass, Object.class);
 * }
 * </pre>
 *
 * <p>You will need to check to see if the key and value classes output by the {@link InputFormat}
 * have a Beam {@link Coder} available. If not, you can use withKeyTranslation/withValueTranslation
 * to specify a method transforming instances of those classes into another class that is supported
 * by a Beam {@link Coder}. These settings are optional and you don't need to specify translation
 * for both key and value. If you specify a translation, you will need to make sure the K or V of
 * the read transform match the output type of the translation.
 *
 * <h3>Reading using {@link HadoopInputFormatIO}</h3>
 *
 * <pre>
 * {@code
 * Pipeline p = ...; // Create pipeline.
 * // Read data only with Hadoop configuration.
 * p.apply("read",
 *     HadoopInputFormatIO.<InputFormatKeyClass, InputFormatKeyClass>read()
 *              .withConfiguration(myHadoopConfiguration);
 * }
 * // Read data with configuration and key translation (Example scenario: Beam Coder is not
 * available for key class hence key translation is required.).
 * SimpleFunction&lt;InputFormatKeyClass, MyKeyClass&gt; myOutputKeyType =
 *       new SimpleFunction&lt;InputFormatKeyClass, MyKeyClass&gt;() {
 *         public MyKeyClass apply(InputFormatKeyClass input) {
 *           // ...logic to transform InputFormatKeyClass to MyKeyClass
 *         }
 * };
 * </pre>
 *
 * <pre>
 * {@code
 * p.apply("read",
 *     HadoopInputFormatIO.<MyKeyClass, InputFormatKeyClass>read()
 *              .withConfiguration(myHadoopConfiguration)
 *              .withKeyTranslation(myOutputKeyType);
 * }
 * </pre>
 *
 * <p>// Read data with configuration and value translation (Example scenario: Beam Coder is not
 * available for value class hence value translation is required.).
 *
 * <pre>
 * {@code
 * SimpleFunction&lt;InputFormatValueClass, MyValueClass&gt; myOutputValueType =
 *      new SimpleFunction&lt;InputFormatValueClass, MyValueClass&gt;() {
 *          public MyValueClass apply(InputFormatValueClass input) {
 *            // ...logic to transform InputFormatValueClass to MyValueClass
 *          }
 *  };
 * }
 * </pre>
 *
 * <pre>
 * {@code
 * p.apply("read",
 *     HadoopInputFormatIO.<InputFormatKeyClass, MyValueClass>read()
 *              .withConfiguration(myHadoopConfiguration)
 *              .withValueTranslation(myOutputValueType);
 * }
 * </pre>
 */

public class HadoopInputFormatIO {
  private static final Logger LOG = LoggerFactory.getLogger(HadoopInputFormatIO.class);

  /**
   * Creates an uninitialized {@link HadoopInputFormatIO.Read}. Before use, the {@code Read} must
   * be initialized with a HadoopInputFormatIO.Read#withConfiguration(HadoopConfiguration) that
   * specifies the source. A key/value translation may also optionally be specified using
   * {@link HadoopInputFormatIO.Read#withKeyTranslation}/
   * {@link HadoopInputFormatIO.Read#withValueTranslation}.
   */
  public static <K, V> Read<K, V> read() {
    return new AutoValue_HadoopInputFormatIO_Read.Builder<K, V>().build();
  }

  /**
   * A {@link PTransform} that reads from any data source which implements Hadoop InputFormat. For
   * e.g. Cassandra, Elasticsearch, HBase, Redis, Postgres, etc. See the class-level Javadoc on
   * {@link HadoopInputFormatIO} for more information.
   * @param <K> Type of keys to be read.
   * @param <V> Type of values to be read.
   * @see HadoopInputFormatIO
   */
  @AutoValue
  public abstract static class Read<K, V> extends PTransform<PBegin, PCollection<KV<K, V>>> {

    // Returns the Hadoop Configuration which contains specification of source.
    @Nullable
    public abstract SerializableConfiguration getConfiguration();

    @Nullable public abstract SimpleFunction<?, K> getKeyTranslationFunction();
    @Nullable public abstract SimpleFunction<?, V> getValueTranslationFunction();
    @Nullable public abstract TypeDescriptor<K> getKeyTypeDescriptor();
    @Nullable public abstract TypeDescriptor<V> getValueTypeDescriptor();
    @Nullable public abstract TypeDescriptor<?> getinputFormatClass();
    @Nullable public abstract TypeDescriptor<?> getinputFormatKeyClass();
    @Nullable public abstract TypeDescriptor<?> getinputFormatValueClass();

    abstract Builder<K, V> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<K, V> {
      abstract Builder<K, V> setConfiguration(SerializableConfiguration configuration);
      abstract Builder<K, V> setKeyTranslationFunction(SimpleFunction<?, K> function);
      abstract Builder<K, V> setValueTranslationFunction(SimpleFunction<?, V> function);
      abstract Builder<K, V> setKeyClass(TypeDescriptor<K> keyClass);
      abstract Builder<K, V> setValueClass(TypeDescriptor<V> valueClass);
      abstract Builder<K, V> setInputFormatClass(TypeDescriptor<?> inputFormatClass);
      abstract Builder<K, V> setInputFormatKeyClass(TypeDescriptor<?> inputFormatKeyClass);
      abstract Builder<K, V> setInputFormatValueClass(TypeDescriptor<?> inputFormatValueClass);
      abstract Read<K, V> build();
    }

    /**
     * Returns a new {@link HadoopInputFormatIO.Read} that will read from the source using the
     * options provided by the given configuration.
     *
     * <p>Does not modify this object.
     */
    public Read<K, V> withConfiguration(Configuration configuration) {
      validateConfiguration(configuration);
      TypeDescriptor<?> inputFormatClass =
          TypeDescriptor.of(configuration.getClass("mapreduce.job.inputformat.class", null));
      TypeDescriptor<?> inputFormatKeyClass =
          TypeDescriptor.of(configuration.getClass("key.class", null));
      TypeDescriptor<?> inputFormatValueClass =
          TypeDescriptor.of(configuration.getClass("value.class", null));
      Builder<K, V> builder =
          toBuilder().setConfiguration(new SerializableConfiguration(configuration));
      builder.setInputFormatClass(inputFormatClass);
      builder.setInputFormatKeyClass(inputFormatKeyClass);
      builder.setInputFormatValueClass(inputFormatValueClass);
      /*
       * Sets the output key class to InputFormat key class if withKeyTranslation() is not called
       * yet.
       */
      if (getKeyTranslationFunction() == null) {
        builder.setKeyClass((TypeDescriptor<K>) inputFormatKeyClass);
      }
      /*
       * Sets the output value class to InputFormat value class if withValueTranslation() is not
       * called yet.
       */
      if (getValueTranslationFunction() == null) {
        builder.setValueClass((TypeDescriptor<V>) inputFormatValueClass);
      }
      return builder.build();
    }

    /**
     * Returns a new {@link HadoopInputFormatIO.Read} that will transform the keys read from the
     * source using the given key translation function.
     *
     * <p>Does not modify this object.
     */
    public Read<K, V> withKeyTranslation(SimpleFunction<?, K> function) {
      checkNotNull(function, "function");
      // Sets key class to key translation function's output class type.
      return toBuilder().setKeyTranslationFunction(function)
          .setKeyClass((TypeDescriptor<K>) function.getOutputTypeDescriptor()).build();
    }

    /**
     * Returns a new {@link HadoopInputFormatIO.Read} that will transform the values read from the
     * source using the given value translation function.
     *
     * <p>Does not modify this object.
     */
    public Read<K, V> withValueTranslation(SimpleFunction<?, V> function) {
      checkNotNull(function, "function");
      // Sets value class to value translation function's output class type.
      return toBuilder().setValueTranslationFunction(function)
          .setValueClass((TypeDescriptor<V>) function.getOutputTypeDescriptor()).build();
    }

    @Override
    public PCollection<KV<K, V>> expand(PBegin input) {
      // Get the key and value coders based on the key and value classes.
      CoderRegistry coderRegistry = input.getPipeline().getCoderRegistry();
      Coder<K> keyCoder = getDefaultCoder(getKeyTypeDescriptor(), coderRegistry);
      Coder<V> valueCoder = getDefaultCoder(getValueTypeDescriptor(), coderRegistry);
      HadoopInputFormatBoundedSource<K, V> source = new HadoopInputFormatBoundedSource<K, V>(
          getConfiguration(),
          keyCoder,
          valueCoder,
          getKeyTranslationFunction(),
          getValueTranslationFunction());
      return input.getPipeline().apply(org.apache.beam.sdk.io.Read.from(source));
    }

    /**
     * Validates that the mandatory configuration properties such as InputFormat class, InputFormat
     * key and value classes are provided in the Hadoop configuration.
     */
    private void validateConfiguration(Configuration configuration) {
      checkNotNull(configuration, "configuration");
      checkNotNull(configuration.get("mapreduce.job.inputformat.class"),
          "configuration.get(\"mapreduce.job.inputformat.class\")");
      checkNotNull(configuration.get("key.class"), "configuration.get(\"key.class\")");
      checkNotNull(configuration.get("value.class"),
          "configuration.get(\"value.class\")");
    }

    /**
     * Validates inputs provided by the pipeline user before reading the data.
     */
    @Override
    public void validate(PBegin input) {
      checkNotNull(getConfiguration(), "getConfiguration()");
      // Validate that the key translation input type must be same as key class of InputFormat.
      validateTranslationFunction(getinputFormatKeyClass(), getKeyTranslationFunction(),
          "Key translation's input type is not same as hadoop InputFormat : %s key class : %s");
      // Validate that the value translation input type must be same as value class of InputFormat.
      validateTranslationFunction(getinputFormatValueClass(), getValueTranslationFunction(),
          "Value translation's input type is not same as hadoop InputFormat :  "
              + "%s value class : %s");
    }

    /**
     * Validates translation function given for key/value translation.
     */
    private void validateTranslationFunction(TypeDescriptor<?> inputType,
        SimpleFunction<?, ?> simpleFunction, String errorMsg) {
      if (simpleFunction != null) {
        if (!simpleFunction.getInputTypeDescriptor().equals(inputType)) {
          throw new IllegalArgumentException(
              String.format(errorMsg, getinputFormatClass().getRawType(), inputType.getRawType()));
        }
      }
    }

    /**
     * Returns the default coder for a given type descriptor. Coder Registry is queried for correct
     * coder, if not found in Coder Registry, then check if the type descriptor provided is of type
     * Writable, then WritableCoder is returned, else exception is thrown "Cannot find coder".
     */
    @VisibleForTesting
    public <T> Coder<T> getDefaultCoder(TypeDescriptor<?> typeDesc, CoderRegistry coderRegistry) {
      Class classType = typeDesc.getRawType();
      try {
        return (Coder<T>) coderRegistry.getCoder(typeDesc);
      } catch (CannotProvideCoderException e) {
        if (Writable.class.isAssignableFrom(classType)) {
          return (Coder<T>) WritableCoder.of(classType);
        }
        throw new IllegalStateException(String.format("Cannot find coder for %s  : ", typeDesc)
            + e.getMessage(), e);
      }
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      if (getConfiguration().getHadoopConfiguration() != null) {
        Iterator<Entry<String, String>> configProperties = getConfiguration()
            .getHadoopConfiguration().iterator();
        while (configProperties.hasNext()) {
          Entry<String, String> property = configProperties.next();
          builder.addIfNotNull(DisplayData.item(property.getKey(), property.getValue())
              .withLabel(property.getKey()));
        }
      }
    }
  }

  /**
   * Bounded source implementation for {@link HadoopInputFormatIO}.
   * @param <K> Type of keys to be read.
   * @param <V> Type of values to be read.
   */
  public static class HadoopInputFormatBoundedSource<K, V> extends BoundedSource<KV<K, V>>
      implements Serializable {
    private final SerializableConfiguration conf;
    private final Coder<K> keyCoder;
    private final Coder<V> valueCoder;
    @Nullable private final SimpleFunction<?, K> keyTranslationFunction;
    @Nullable private final SimpleFunction<?, V> valueTranslationFunction;
    private final SerializableSplit inputSplit;
    private transient List<SerializableSplit> inputSplits;
    private long boundedSourceEstimatedSize = 0;
    private transient InputFormat<?, ?> inputFormatObj;
    private transient TaskAttemptContext taskAttemptContext;
    HadoopInputFormatBoundedSource(
        SerializableConfiguration conf,
        Coder<K> keyCoder,
        Coder<V> valueCoder,
        @Nullable SimpleFunction<?, K> keyTranslationFunction,
        @Nullable SimpleFunction<?, V> valueTranslationFunction) {
      this(conf,
          keyCoder,
          valueCoder,
          keyTranslationFunction,
          valueTranslationFunction,
          null);
    }

    protected HadoopInputFormatBoundedSource(
        SerializableConfiguration conf,
        Coder<K> keyCoder,
        Coder<V> valueCoder,
        @Nullable SimpleFunction<?, K> keyTranslationFunction,
        @Nullable SimpleFunction<?, V> valueTranslationFunction,
        SerializableSplit inputSplit) {
      this.conf = conf;
      this.inputSplit = inputSplit;
      this.keyCoder = keyCoder;
      this.valueCoder = valueCoder;
      this.keyTranslationFunction = keyTranslationFunction;
      this.valueTranslationFunction = valueTranslationFunction;
    }

    public SerializableConfiguration getConfiguration() {
      return conf;
    }

    @Override
    public void validate() {
      checkNotNull(conf, "conf");
      checkNotNull(keyCoder, "keyCoder");
      checkNotNull(valueCoder, "valueCoder");
    }

    @Override
    public List<BoundedSource<KV<K, V>>> splitIntoBundles(long desiredBundleSizeBytes,
        PipelineOptions options) throws Exception {
      // desiredBundleSizeBytes is not being considered as splitting based on this
      // value is not supported by inputFormat getSplits() method.
      if (inputSplit != null) {
        LOG.info("Not splitting source {} because source is already split.", this);
        return ImmutableList.of((BoundedSource<KV<K, V>>) this);
      }
      computeSplitsIfNecessary();
      LOG.info("Generated {} splits. Size of first split is {} ", inputSplits.size(), inputSplits
          .get(0).getSplit().getLength());
      return Lists.transform(inputSplits,
          new Function<SerializableSplit, BoundedSource<KV<K, V>>>() {
            @Override
            public BoundedSource<KV<K, V>> apply(SerializableSplit serializableInputSplit) {
              HadoopInputFormatBoundedSource<K, V> hifBoundedSource =
                  new HadoopInputFormatBoundedSource<K, V>(conf, keyCoder, valueCoder,
                      keyTranslationFunction, valueTranslationFunction, serializableInputSplit);
              return hifBoundedSource;
            }
          });
    }

    @Override
    public long getEstimatedSizeBytes(PipelineOptions po) throws Exception {
      if (inputSplit == null) {
        // If there are no splits computed yet, then retrieve the splits.
        computeSplitsIfNecessary();
        return boundedSourceEstimatedSize;
      }
      return inputSplit.getSplit().getLength();
    }

    /**
     * This is a helper function to compute splits. This method will also calculate size of the
     * data being read. Note: This method is executed exactly once and the splits are retrieved
     * and cached in this. These splits are further used by splitIntoBundles() and
     * getEstimatedSizeBytes().
     */
    @VisibleForTesting
    void computeSplitsIfNecessary() throws IOException, InterruptedException {
      if (inputSplits != null) {
        return;
      }
      createInputFormatInstance();
      List<InputSplit> splits =
          inputFormatObj.getSplits(Job.getInstance(conf.getHadoopConfiguration()));
      if (splits == null) {
        throw new IOException("Error in computing splits, getSplits() returns null.");
      }
      if (splits.isEmpty()) {
        throw new IOException("Error in computing splits, getSplits() returns a empty list");
      }
      boundedSourceEstimatedSize = 0;
      inputSplits = new ArrayList<SerializableSplit>();
      for (InputSplit inputSplit : splits) {
        if (inputSplit == null) {
          throw new IOException("Error in computing splits, split is null in InputSplits list "
              + "populated by getSplits() : ");
        }
        boundedSourceEstimatedSize += inputSplit.getLength();
        inputSplits.add(new SerializableSplit(inputSplit));
      }
      validateUserInputForKeyAndValue();
    }

    /**
     * Creates instance of InputFormat class. The InputFormat class name is specified in the Hadoop
     * configuration.
     */
    protected void createInputFormatInstance() throws IOException {
      if (inputFormatObj == null) {
        try {
          taskAttemptContext =
              new TaskAttemptContextImpl(conf.getHadoopConfiguration(), new TaskAttemptID());
          inputFormatObj =
              (InputFormat<?, ?>) conf
                  .getHadoopConfiguration()
                  .getClassByName(
                      conf.getHadoopConfiguration().get("mapreduce.job.inputformat.class"))
                  .newInstance();
          /*
           * If InputFormat explicitly implements interface {@link Configurable}, then setConf()
           * method of {@link Configurable} needs to be explicitly called to set all the
           * configuration parameters. For example: InputFormat classes which implement Configurable
           * are {@link org.apache.hadoop.mapreduce.lib.db.DBInputFormat DBInputFormat}, {@link
           * org.apache.hadoop.hbase.mapreduce.TableInputFormat TableInputFormat}, etc.
           */
          if (Configurable.class.isAssignableFrom(inputFormatObj.getClass())) {
            ((Configurable) inputFormatObj).setConf(conf.getHadoopConfiguration());
          }
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
          throw new IOException("Unable to create InputFormat object: ", e);
        }
      }
    }

    /**
     * Throws exception if you set different InputFormat key or value class than InputFormat's
     * actual key or value class. If you set incorrect classes then, it may result in an error like
     * "unexpected extra bytes after decoding" while the decoding process happens. Hence this
     * validation is required.
     */
    private void validateUserInputForKeyAndValue() throws IOException, InterruptedException {
      ParameterizedType genericClassType = determineGenericType();
      RecordReader<?, ?> reader = fetchFirstRecordReader();
      boolean isCorrectKeyClassSet =
          validateKeyClass(genericClassType.getActualTypeArguments()[0].getTypeName(), keyCoder,
              reader.getCurrentKey());
      boolean isCorrectValueClassSet =
          validateValueClass(genericClassType.getActualTypeArguments()[1].getTypeName(),
              valueCoder, reader.getCurrentValue());
      if (!isCorrectKeyClassSet) {
        Class<?> actualClass = conf.getHadoopConfiguration().getClass("key.class", Object.class);
        throw new IllegalArgumentException(String.format(
            "Wrong InputFormat key class in configuration : Expected key.class is %s but was %s.",
            reader.getCurrentKey().getClass().getName(), actualClass.getName()));
      }
      if (!isCorrectValueClassSet) {
        Class<?> actualClass = conf.getHadoopConfiguration().getClass("value.class", Object.class);
        throw new IllegalArgumentException(String.format("Wrong InputFormat value class in "
            + "configuration : Expected value.class is %s but was %s.", reader.getCurrentValue()
            .getClass().getName(), actualClass.getName()));
      }
    }

    /**
     * Returns true if key class set by the user is compatible with the key class of a pair returned
     * by RecordReader. User provided key class is validated against the parameterized type's type
     * arguments of InputFormat. If parameterized type has any type arguments such as T, K, V, etc
     * then validation is done by encoding and decoding key object of first pair returned by
     * RecordReader.
     */
    private <T> boolean validateKeyClass(String inputFormatGenericClassName, Coder<K> coder,
        Object keyObject) {
      try {
        Class<?> inputClass = Class.forName(inputFormatGenericClassName);
        /*
         * Validates key class with InputFormat's parameterized type.
         */
        return (conf.getHadoopConfiguration().getClass("key.class", Object.class))
            .isAssignableFrom(inputClass);
      } catch (ClassNotFoundException e) {
        /*
         * Given inputFormatGenericClassName is a type parameter i.e. T, K, V, etc. In such cases
         * class validation for user provided input key will not work correctly. Therefore the need
         * to validate key class by encoding and decoding key object with the given coder.
         */
        return checkEncodingAndDecoding((Coder<T>) coder, (T) keyObject);
      }
    }

    /**
     * Returns true if value class set by the user is compatible with the value class of a pair
     * returned by RecordReader. User provided value class is validated against the parameterized
     * type's type arguments of InputFormat. If parameterized type has any type arguments such as T,
     * K, V, etc then validation is done by encoding and decoding value object of first pair
     * returned by RecordReader.
     */
    private <T> boolean validateValueClass(String inputFormatGenericClassName, Coder coder,
        Object valueObject) {
      try {
        Class<?> inputClass = Class.forName(inputFormatGenericClassName);
        /*
         * Validates value class with InputFormat's parameterized type.
         */
        return (conf.getHadoopConfiguration().getClass("value.class", Object.class))
            .isAssignableFrom(inputClass);
      } catch (Exception e) {
        /*
         * Given inputFormatGenericClassName is a type parameter i.e. T, K, V, etc. In such cases
         * class validation for user provided input value will not work correctly. Therefore the
         * need to validate value class by encoding and decoding value object with the given coder.
         */
        return checkEncodingAndDecoding((Coder<T>) coder, (T) valueObject);
      }
    }

    /**
     * Validates whether the input gets encoded or decoded correctly using the provided coder.
     */
    private <T> boolean checkEncodingAndDecoding(Coder<T> coder, T input) {
      try {
        CoderUtils.clone(coder, input);
      } catch (CoderException e) {
        return false;
      }
      return true;
    }

    /**
     * Returns parameterized type of the InputFormat class.
     */
    private ParameterizedType determineGenericType() {
      Class<?> inputFormatClass = inputFormatObj.getClass();
      Type genericSuperclass = null;
      for (;;) {
        genericSuperclass = inputFormatClass.getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType) {
          break;
        }
        inputFormatClass = inputFormatClass.getSuperclass();
      }
      return (ParameterizedType) genericSuperclass;
    }

    /**
     * Returns RecordReader object of the first split to read first record for validating key/value
     * classes.
     */
    private RecordReader fetchFirstRecordReader() throws IOException, InterruptedException {
      RecordReader<?, ?> reader =
          inputFormatObj.createRecordReader(inputSplits.get(0).getSplit(), taskAttemptContext);
      if (reader == null) {
        throw new IOException(String.format("Null RecordReader object returned by %s",
            inputFormatObj.getClass()));
      }
      reader.initialize(inputSplits.get(0).getSplit(), taskAttemptContext);
      // First record is read to get the InputFormat's key and value classes.
      reader.nextKeyValue();
      return reader;
    }

    @VisibleForTesting
    InputFormat<?, ?> getInputFormat(){
      return inputFormatObj;
    }

    @VisibleForTesting
    void setInputFormatObj(InputFormat<?, ?> inputFormatObj) {
      this.inputFormatObj = inputFormatObj;
    }

    @Override
    public Coder<KV<K, V>> getDefaultOutputCoder() {
      return KvCoder.of(keyCoder, valueCoder);
    }

    @Override
    public BoundedReader<KV<K, V>> createReader(PipelineOptions options) throws IOException {
      this.validate();
      if (inputSplit == null) {
        throw new IOException("Cannot create reader as source is not split yet.");
      } else {
        createInputFormatInstance();
        return new HadoopInputFormatReader<>(
            this,
            keyTranslationFunction,
            valueTranslationFunction,
            inputSplit,
            inputFormatObj,
            taskAttemptContext);
      }
    }

    /**
     * BoundedReader for Hadoop InputFormat source.
     *
     * @param <K> Type of keys RecordReader emits.
     * @param <V> Type of values RecordReader emits.
     */
    class HadoopInputFormatReader<T1, T2> extends BoundedSource.BoundedReader<KV<K, V>> {

      private final HadoopInputFormatBoundedSource<K, V> source;
      @Nullable private final SimpleFunction<T1, K> keyTranslationFunction;
      @Nullable private final SimpleFunction<T2, V> valueTranslationFunction;
      private final SerializableSplit split;
      private RecordReader<T1, T2> recordReader;
      private volatile boolean doneReading = false;
      private volatile long recordsReturned = 0L;
      // Tracks the progress of the RecordReader.
      private AtomicDouble progressValue = new AtomicDouble();
      private transient InputFormat<T1, T2> inputFormatObj;
      private transient TaskAttemptContext taskAttemptContext;

      private HadoopInputFormatReader(HadoopInputFormatBoundedSource<K, V> source,
          @Nullable SimpleFunction keyTranslationFunction,
          @Nullable SimpleFunction valueTranslationFunction,
          SerializableSplit split,
          InputFormat inputFormatObj,
          TaskAttemptContext taskAttemptContext) {
        this.source = source;
        this.keyTranslationFunction = keyTranslationFunction;
        this.valueTranslationFunction = valueTranslationFunction;
        this.split = split;
        this.inputFormatObj = inputFormatObj;
        this.taskAttemptContext = taskAttemptContext;
      }

      @Override
      public HadoopInputFormatBoundedSource<K, V> getCurrentSource() {
        return source;
      }

      @Override
      public boolean start() throws IOException {
        try {
          recordsReturned = 0;
          recordReader =
              (RecordReader<T1, T2>) inputFormatObj.createRecordReader(split.getSplit(),
                  taskAttemptContext);
          if (recordReader != null) {
            recordReader.initialize(split.getSplit(), taskAttemptContext);
            if (recordReader.nextKeyValue()) {
              recordsReturned++;
              return true;
            }
          } else {
            throw new IOException(String.format("Null RecordReader object returned by %s",
                inputFormatObj.getClass()));
          }
          recordReader = null;
        } catch (InterruptedException e) {
          throw new IOException(
              "Could not read because the thread got interrupted while "
              + "reading the records with an exception: ",
              e);
        }
        doneReading = true;
        return false;
      }

      @Override
      public boolean advance() throws IOException {
        try {
          progressValue.set(getProgress());
          if (recordReader.nextKeyValue()) {
            recordsReturned++;
            return true;
          }
          doneReading = true;
        } catch (InterruptedException e) {
          throw new IOException("Unable to read data: ", e);
        }
        return false;
      }

      @Override
      public KV<K, V> getCurrent() {
        K key = null;
        V value = null;
        try {
          // Transform key if translation function is provided.
          key =
              transformKeyOrValue((T1) recordReader.getCurrentKey(), keyTranslationFunction,
                  keyCoder);
          // Transform value if translation function is provided.
          value =
              transformKeyOrValue((T2) recordReader.getCurrentValue(), valueTranslationFunction,
                  valueCoder);
        } catch (IOException | InterruptedException e) {
          LOG.error("Unable to read data: " + "{}", e);
          return null;
        }
        return KV.of(key, value);
      }

      /**
       * Returns the serialized output of transformed key or value object.
       * @throws ClassCastException
       * @throws CoderException
       */
      private <T, T3> T3 transformKeyOrValue(T input,
          @Nullable SimpleFunction<T, T3> simpleFunction, Coder<T3> coder) throws CoderException,
          ClassCastException {
        T3 output;
        if (null != simpleFunction) {
          output = simpleFunction.apply(input);
        } else {
          output = (T3) input;
        }
        return cloneIfPossiblyMutable((T3) output, coder);
      }

      /**
       * Beam expects immutable objects, but the Hadoop InputFormats tend to re-use the same object
       * when returning them. Hence mutable objects returned by Hadoop InputFormats are cloned., but
       * the Hadoop InputFormats tend to re-use the same
       */
      private <T> T cloneIfPossiblyMutable(T input, Coder<T> coder) throws CoderException,
          ClassCastException {
        // If the input object is not of known immutable type, clone the object.
        if (!isKnownImmutable(input)) {
          input = CoderUtils.clone(coder, input);
        }
        return input;
      }

      /**
       * Utility method to check if the passed object is of a known immutable type.
       */
      private boolean isKnownImmutable(Object o) {
        Set<Class<?>> immutableTypes = new HashSet<Class<?>>(
            Arrays.asList(
                String.class,
                Byte.class,
                Short.class,
                Integer.class,
                Long.class,
                Float.class,
                Double.class,
                Boolean.class,
                BigInteger.class,
                BigDecimal.class));
        return immutableTypes.contains(o.getClass());
      }

      @Override
      public void close() throws IOException {
        LOG.info("Closing reader after reading {} records.", recordsReturned);
        if (recordReader != null) {
          recordReader.close();
          recordReader = null;
        }
      }

      @Override
      public Double getFractionConsumed() {
        if (doneReading) {
          progressValue.set(1.0);
        } else if (recordReader == null || recordsReturned == 0) {
          progressValue.set(0.0);
        }
        return progressValue.doubleValue();
      }

      /**
       * Returns RecordReader's progress.
       * @throws IOException
       */
      private Double getProgress() throws IOException {
        try {
          return (double) recordReader.getProgress();
        } catch (IOException | InterruptedException e) {
          LOG.error(
              "Error in computing the fractions consumed as RecordReader.getProgress() throws an "
              + "exception : " + "{}", e);
          throw new IOException(
              "Error in computing the fractions consumed as RecordReader.getProgress() throws an "
              + "exception : " + e.getMessage(), e);
        }
      }

      @Override
      public final long getSplitPointsRemaining() {
        if (doneReading) {
          return 0;
        }
        /**
         * This source does not currently support dynamic work rebalancing, so remaining parallelism
         * is always 1.
         */
        return 1;
      }
    }
  }

  /**
   * A wrapper to allow Hadoop {@link org.apache.hadoop.mapreduce.InputSplit} to be serialized using
   * Java's standard serialization mechanisms.
   */
  public static class SerializableSplit implements Serializable {

    InputSplit inputSplit;

    public SerializableSplit() {}

    public SerializableSplit(InputSplit split) {
      checkArgument(split instanceof Writable,
          String.format("Split is not of type Writable: %s", split));
      this.inputSplit = split;
    }

    public InputSplit getSplit() {
      return inputSplit;
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
      ObjectWritable ow = new ObjectWritable();
      ow.setConf(new Configuration(false));
      ow.readFields(in);
      this.inputSplit = (InputSplit) ow.get();
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
      new ObjectWritable(inputSplit).write(out);
    }
  }

  /**
   * A wrapper to allow Hadoop {@link org.apache.hadoop.conf.Configuration} to be serialized using
   * Java's standard serialization mechanisms. Note that the org.apache.hadoop.conf.Configuration
   * is Writable.
   */
  public static class SerializableConfiguration implements Externalizable {

    private Configuration conf;

    public SerializableConfiguration() {}

    public SerializableConfiguration(Configuration conf) {
      this.conf = conf;
    }

    public Configuration getHadoopConfiguration() {
      return conf;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeUTF(conf.getClass().getCanonicalName());
      ((Writable) conf).write(out);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      String className = in.readUTF();
      try {
        conf = (Configuration) Class.forName(className).newInstance();
        conf.readFields(in);
      } catch (InstantiationException | IllegalAccessException e) {
        throw new IOException("Unable to create configuration: " + e);
      }
    }
  }
}
