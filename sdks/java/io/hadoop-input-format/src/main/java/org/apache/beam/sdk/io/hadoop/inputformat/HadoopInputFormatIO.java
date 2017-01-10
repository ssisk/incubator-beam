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

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
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
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * A bounded source for HadoopInputFormat.
 *
 * <p>
 * The HadoopInputFormat source returns a set of {@link org.apache.beam.sdk.values.KV} key-value
 * pairs returning a {@code PCollection<KV>}.
 * 
 * 
 * HadoopConfiguration is mandatory for reading the data using HadoopInputFormat source which is set
 * using HadoopInputFormatIO.Read#withConfiguration(HadoopConfiguration). Key/value translation is a
 * simple function used to translate input formats keys/values to some other user provided class for
 * which coder is available. Key/value translation function is optional and can be
 * set using
 * HadoopInputFormatIO.Read#withKeyTranslation/HadoopInputFormatIO.Read#withValueTranslation.
 * 
 * Example simple functions:
 *  
   SimpleFunction<Text, String> myKeyTranslate = new SimpleFunction<Text, String>() {
      private static final long serialVersionUID = 1L;

      @Override
      public String apply(Text input) {
        return input.toString();
      }
    };
    SimpleFunction<Employee, String> myValueTranslate = new SimpleFunction<Employee, String>() {
      private static final long serialVersionUID = 1L;

      @Override
      public String apply(Employee input) {
        return input.getEmpName() + "_" + input.getEmpAddress();
      }
    };
 *           
 * Example: HadoopInputFormatIO.Read<MyKey, MyValue> read =
 *          HadoopInputFormatIO.<MyKey, MyValue>read() 
 *              .withConfiguration(myConfiguration);
 * 
 * Example: HadoopInputFormatIO.Read<MyKey, MyValue> read = HadoopInputFormatIO.<MyKey,
 *          MyValue>read() 
 *            .withConfiguration(myConfiguration) 
 *            .withKeyTranslation(myKeyTranslate);
 * 
 * Example : HadoopInputFormatIO.Read<MyKey, MyValue> read = 
 *           HadoopInputFormatIO.<MyKey,MyValue>read() 
 *             .withConfiguration(myConfiguration) 
 *             .withKeyTranslation(myKeyTranslate);
 * 
 * Example : HadoopInputFormatIO.Read<MyKey, MyValue> read = 
 *           HadoopInputFormatIO.<MyKey,MyValue>read() 
 *              .withConfiguration(myConfiguration) 
 *              .withKeyTranslation(myKeyTranslate)
 *              .withValueTranslation(myValueTranslate);
 * 
 */
public class HadoopInputFormatIO {
  private static final Logger LOG = LoggerFactory.getLogger(HadoopInputFormatIO.class);

  /**
   * Creates an uninitialized HadoopInputFormatIO.Read. Before use, the {@code Read} must be
   * initialized with a HadoopInputFormatIO.Read#withConfiguration(HadoopConfiguration) that
   * specifies the source. A key/value translation may also optionally be specified using
   * HadoopInputFormatIO.Read#withKeyTranslation/HadoopInputFormatIO.Read#withValueTranslation.
   */
  public static <K, V> Read<K, V> read() {
    return new AutoValue_HadoopInputFormatIO_Read.Builder<K, V>().build();
  }

  /**
   * A Ptransform to read data from any source which supports HadoopInputFormat.
   *
   * @param <K> The type of PCollection output keys
   * @param <V> The type of PCollection output values
   */
  @AutoValue
  public abstract static class Read<K, V> extends PTransform<PBegin, PCollection<KV<K, V>>> {

    private static final long serialVersionUID = 1L;

    @Nullable
    public abstract SimpleFunction<?, K> getSimpleFuncForKeyTranslation();

    @Nullable
    public abstract SimpleFunction<?, V> getSimpleFuncForValueTranslation();

    @Nullable
    public abstract SerializableConfiguration getConfiguration();

    @Nullable
    public abstract TypeDescriptor<K> getKeyClass();

    @Nullable
    public abstract TypeDescriptor<V> getValueClass();

    abstract Builder<K, V> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<K, V> {
      abstract Builder<K, V> setConfiguration(SerializableConfiguration configuration);

      abstract Builder<K, V> setSimpleFuncForKeyTranslation(
          SimpleFunction<?, K> simpleFuncForKeyTranslation);

      abstract Builder<K, V> setSimpleFuncForValueTranslation(
          SimpleFunction<?, V> simpleFuncForValueTranslation);

      abstract Builder<K, V> setKeyClass(TypeDescriptor<K> keyClass);

      abstract Builder<K, V> setValueClass(TypeDescriptor<V> valueClass);

      abstract Read<K, V> build();
    }

    /**
     * Set the configuration provided by the user. Also set key and value classes to input format's
     * key and value classes if no key or value translation is provided. If key translation is
     * applied then key class is not changed. If value translation is applied then vale class is not
     * changed.
     */
    public Read<K, V> withConfiguration(Configuration configuration) {
      checkNotNull(configuration, "Configuration cannot be null.");
      TypeDescriptor<K> inputFormatKeyClass =
          (TypeDescriptor<K>) TypeDescriptor.of(configuration.getClass("key.class", Object.class));
      TypeDescriptor<V> inputFormatValueClass = (TypeDescriptor<V>) TypeDescriptor
          .of(configuration.getClass("value.class", Object.class));
      if (this.getKeyClass() != null && this.getValueClass() != null) {
        return toBuilder().setConfiguration(new SerializableConfiguration(configuration)).build();
      }
      if (this.getKeyClass() == null && this.getValueClass() != null) {
        return toBuilder().setConfiguration(new SerializableConfiguration(configuration))
            .setKeyClass(inputFormatKeyClass).build();
      }
      if (this.getKeyClass() != null && this.getValueClass() == null) {
        return toBuilder().setConfiguration(new SerializableConfiguration(configuration))
            .setValueClass(inputFormatValueClass).build();
      }
      return toBuilder().setConfiguration(new SerializableConfiguration(configuration))
          .setKeyClass(inputFormatKeyClass).setValueClass(inputFormatValueClass).build();

    }

    /**
     * Set the key translation simple function provided by the user. Also set key class to
     * simpleFuncForKeyTranslation's output class type.
     */
    public Read<K, V> withKeyTranslation(SimpleFunction<?, K> simpleFuncForKeyTranslation) {
      checkNotNull(simpleFuncForKeyTranslation,
          "Simple function for key translation cannot be null.");
      return toBuilder().setSimpleFuncForKeyTranslation(simpleFuncForKeyTranslation)
          .setKeyClass((TypeDescriptor<K>) simpleFuncForKeyTranslation.getOutputTypeDescriptor())
          .build();
    }

    /**
     * Set the value translation simple function provided by the user. Also set value class to
     * simpleFuncForValueTranslation's output class type.
     */
    public Read<K, V> withValueTranslation(SimpleFunction<?, V> simpleFuncForValueTranslation) {
      checkNotNull(simpleFuncForValueTranslation,
          "Simple function for value translation cannot be null.");
      return toBuilder().setSimpleFuncForValueTranslation(simpleFuncForValueTranslation)
          .setValueClass(
              (TypeDescriptor<V>) simpleFuncForValueTranslation.getOutputTypeDescriptor())
          .build();
    }

    @Override
    public PCollection<KV<K, V>> expand(PBegin input) {
      HadoopInputFormatBoundedSource<K, V> source = new HadoopInputFormatBoundedSource<K, V>(
          this.getConfiguration(), this.getKeyCoder(), this.getValueCoder(),
          this.getSimpleFuncForKeyTranslation(), this.getSimpleFuncForValueTranslation(), null);
      return input.getPipeline().apply(org.apache.beam.sdk.io.Read.from(source));
    }

    /**
     * Validates inputs provided by the pipeline user before reading the data.
     */
    public void validate(PBegin input) {
      checkNotNull(this.getConfiguration(),
          "Need to set the configuration of a HadoopInputFormatIO "
              + "Read using method Read.withConfiguration().");
      validateConfiguration();
      validateTranslationFunctions();
      getKeyAndValueCoder(input);
    }

    /**
     * Validates configuration
     */
    private void validateConfiguration() {
      String inputFormatClassProperty =
          this.getConfiguration().getConfiguration().get("mapreduce.job.inputformat.class");
      if (inputFormatClassProperty == null) {
        throw new IllegalArgumentException("Hadoop InputFormat class property "
            + "\"mapreduce.job.inputformat.class\" is not set in configuration.");
      }
      String keyClassProperty = this.getConfiguration().getConfiguration().get("key.class");
      if (keyClassProperty == null) {
        throw new IllegalArgumentException("Configuration property \"key.class\" is not set.");
      }
      String valueClassProperty = this.getConfiguration().getConfiguration().get("value.class");
      if (valueClassProperty == null) {
        throw new IllegalArgumentException("Configuration property \"value.class\" is not set.");
      }
    }

    /**
     * Validates translation function's of key/value
     */
    private void validateTranslationFunctions() {
      if (this.getSimpleFuncForKeyTranslation() != null) {
        TypeDescriptor<?> inputFormatKeyClass = TypeDescriptor
            .of(this.getConfiguration().getConfiguration().getClass("key.class", Object.class));
        /**
         * key translation input type must be same as key class of input format
         */
        if (!this.getSimpleFuncForKeyTranslation().getInputTypeDescriptor()
            .equals(inputFormatKeyClass)) {
          String inputFormatClass =
              getConfiguration().getConfiguration().get("mapreduce.job.inputformat.class");
          String keyClass = getConfiguration().getConfiguration().get("key.class");
          throw new IllegalArgumentException(
              "Key translation's input type is not same as hadoop input format : "
                  + inputFormatClass + " key class : " + keyClass);
        }
      }
      if (this.getSimpleFuncForValueTranslation() != null) {
        TypeDescriptor<?> inputFormatValueClass = TypeDescriptor
            .of(this.getConfiguration().getConfiguration().getClass("value.class", Object.class));
        /**
         * value translation input type must be same as value class of input format
         */
        if (!this.getSimpleFuncForValueTranslation().getInputTypeDescriptor()
            .equals(inputFormatValueClass)) {
          String inputFormatClass =
              getConfiguration().getConfiguration().get("mapreduce.job.inputformat.class");
          String valueClass = getConfiguration().getConfiguration().get("value.class");
          throw new IllegalArgumentException(
              "Value translation's input type is not same as hadoop input format : "
                  + inputFormatClass + " value class : " + valueClass);
        }
      }
    }

    private Coder<K> keyCoder;
    private Coder<V> valueCoder;

    /**
     * Set the key and value coder based on the key class and value class
     */
    protected void getKeyAndValueCoder(PBegin input) {
      keyCoder = getDefaultCoder(this.getKeyClass(), input.getPipeline().getCoderRegistry());
      valueCoder = getDefaultCoder(this.getValueClass(), input.getPipeline().getCoderRegistry());
    }

    /**
     * Returns the default coder for a given type descriptor. If type descriptor class is of type
     * Writable, then WritableCoder is returned, else CoderRegistry is queried for the correct coder
     */
    public <T> Coder<T> getDefaultCoder(TypeDescriptor<?> typeDesc, CoderRegistry coderRegistry) {
      Class classType = typeDesc.getRawType();
      if (Writable.class.isAssignableFrom(classType)) {
        return (Coder<T>) WritableCoder.of(classType);
      } else {
        try {
          return (Coder<T>) coderRegistry.getCoder(typeDesc);
        } catch (CannotProvideCoderException e) {
          throw new IllegalStateException("Cannot find coder for " + typeDesc + e.getMessage());
        }
      }
    }

    public Coder<K> getKeyCoder() {
      return keyCoder;
    }

    public Coder<V> getValueCoder() {
      return valueCoder;
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      if (this.getConfiguration().getConfiguration() != null) {
        Iterator<Entry<String, String>> propertyElement =
            this.getConfiguration().getConfiguration().iterator();
        while (propertyElement.hasNext()) {
          Entry<String, String> element = propertyElement.next();
          builder.add(
              DisplayData.item(element.getKey(), element.getValue()).withLabel(element.getKey()));
        }
      }
      builder
          .addIfNotNull(DisplayData.item("KeyClass", getKeyClass().getRawType())
              .withLabel("Output key class"))
          .addIfNotNull(DisplayData.item("ValueClass", getValueClass().getRawType())
              .withLabel("Output value class"));
      if (getSimpleFuncForKeyTranslation() != null)
        builder.addIfNotNull(DisplayData
            .item("KeyTranslationSimpleFunction", getSimpleFuncForKeyTranslation().toString())
            .withLabel("Key translation simple function"));
      if (getSimpleFuncForValueTranslation() != null)
        builder.addIfNotNull(DisplayData
            .item("ValueTranslationSimpleFunction", getSimpleFuncForValueTranslation().toString())
            .withLabel("Value translation simple function"));

    }
  }

  /**
   * Bounded source implementation for HadoopInputFormat
   * 
   * @param <K> The type of PCollection output keys
   * @param <V> The type of PCollection output values
   */
  public static class HadoopInputFormatBoundedSource<K, V> extends BoundedSource<KV<K, V>>
      implements Serializable {
    private static final long serialVersionUID = 0L;
    protected final SerializableConfiguration conf;
    protected final SerializableSplit serializableSplit;
    protected final Coder<K> keyCoder;
    protected final Coder<V> valueCoder;
    protected final SimpleFunction<?, K> simpleFuncForKeyTranslation;
    protected final SimpleFunction<?, V> simpleFuncForValueTranslation;

    public HadoopInputFormatBoundedSource(SerializableConfiguration conf, Coder<K> keyCoder,
        Coder<V> valueCoder) {
      this(conf, keyCoder, valueCoder, null, null, null);
    }

    public HadoopInputFormatBoundedSource(SerializableConfiguration conf, Coder<K> keyCoder,
        Coder<V> valueCoder, SimpleFunction<?, K> keyTranslation,
        SimpleFunction<?, V> valueTranslation, SerializableSplit serializableSplit) {
      this.conf = conf;
      this.serializableSplit = serializableSplit;
      this.keyCoder = keyCoder;
      this.valueCoder = valueCoder;
      this.simpleFuncForKeyTranslation = keyTranslation;
      this.simpleFuncForValueTranslation = valueTranslation;
    }

    public SerializableConfiguration getConfiguration() {
      return conf;
    }

    @Override
    public void validate() {
      checkNotNull(conf, "Need to set the configuration of a HadoopInputFormatSource");
      checkNotNull(keyCoder, "KeyCoder should not be null in HadoopInputFormatSource");
      checkNotNull(valueCoder, "ValueCoder should not be null in HadoopInputFormatSource");
    }

    private boolean isSourceSplit = false;

    @Override
    public List<BoundedSource<KV<K, V>>> splitIntoBundles(long desiredBundleSizeBytes,
        PipelineOptions options) throws Exception {
      if (serializableSplit == null) {
        if (inputSplits == null) {
          computeSplits();
        }
        LOG.info("Generated {} splits each of size {} ", inputSplits.size(),
            inputSplits.get(0).getSplit().getLength());
        isSourceSplit = true;
        return Lists.transform(inputSplits,
            new Function<SerializableSplit, BoundedSource<KV<K, V>>>() {
              @Override
              public BoundedSource<KV<K, V>> apply(SerializableSplit serializableInputSplit) {
                HadoopInputFormatBoundedSource<K, V> hifBoundedSource =
                    new HadoopInputFormatBoundedSource<K, V>(conf, keyCoder, valueCoder,
                        simpleFuncForKeyTranslation, simpleFuncForValueTranslation,
                        serializableInputSplit);
                return hifBoundedSource;
              }
            });
      } else {
        isSourceSplit = true;
        LOG.info("Not splitting source {} because source is already split.", this);
        return ImmutableList.of((BoundedSource<KV<K, V>>) this);
      }
    }

    @Override
    public long getEstimatedSizeBytes(PipelineOptions po) throws Exception {
      if (inputSplits == null) {
        computeSplits();
      }
      return boundedSourceEstimatedSize;
    }

    private long boundedSourceEstimatedSize = 0;
    private List<SerializableSplit> inputSplits;

    /**
     * Method to get splits of the input format. This method will also calculate the estimated size
     * of bytes. This method is called exactly once, the splits are retrieved and cached for further
     * use by splitIntoBundles() and getEstimatesSizeBytes().
     */
    private void computeSplits() throws IOException, IllegalAccessException, InstantiationException,
        InterruptedException, ClassNotFoundException {
      Job job = Job.getInstance(conf.getConfiguration());
      List<InputSplit> splits = job.getInputFormatClass().newInstance().getSplits(job);
      if (splits == null) {
        throw new IOException("Error in computing splits, getSplits() returns null");
      }
      if (splits.isEmpty()) {
        throw new IOException("Error in computing splits, getSplits() returns a null list");
      }
      boundedSourceEstimatedSize = 0;
      inputSplits = new ArrayList<SerializableSplit>();
      for (InputSplit inputSplit : splits) {
        boundedSourceEstimatedSize = boundedSourceEstimatedSize + inputSplit.getLength();
        inputSplits.add(new SerializableSplit(inputSplit));
      }
    }

    @Override
    public Coder<KV<K, V>> getDefaultOutputCoder() {
      return KvCoder.of(keyCoder, valueCoder);
    }

    @Override
    public BoundedReader<KV<K, V>> createReader(PipelineOptions options) throws IOException {
      this.validate();
      //
      if (serializableSplit == null) {
        if (!isSourceSplit) {
          throw new IOException("Cannot create reader as source is not split yet.");
        } else {
          throw new IOException("Cannot read data as split is null.");
        }
      } else {
        return new HadoopInputFormatReader<Object>(this, simpleFuncForKeyTranslation,
            simpleFuncForValueTranslation, serializableSplit.getSplit());
      }
    }

    /**
     * BoundedReader for HadoopInputFormatSource
     */
    class HadoopInputFormatReader<T extends Object> extends BoundedSource.BoundedReader<KV<K, V>> {

      private final HadoopInputFormatBoundedSource<K, V> source;
      private final InputSplit split;
      private final SimpleFunction<T, K> simpleFuncForKeyTranslation;
      private final SimpleFunction<T, V> simpleFuncForValueTranslation;

      public HadoopInputFormatReader(HadoopInputFormatBoundedSource<K, V> source,
          SimpleFunction keyTranslation, SimpleFunction valueTranslation, InputSplit split) {
        this.source = source;
        this.split = split;
        this.simpleFuncForKeyTranslation = keyTranslation;
        this.simpleFuncForValueTranslation = valueTranslation;
      }

      @Override
      public HadoopInputFormatBoundedSource<K, V> getCurrentSource() {
        return source;
      }

      private RecordReader<T, T> currentReader;
      private KV<K, V> currentRecord;
      private volatile boolean doneReading = false;
      private long recordsReturned = 0L;

      @Override
      public boolean start() throws IOException {
        try {
          TaskAttemptContextImpl attemptContext = new TaskAttemptContextImpl(
              source.getConfiguration().getConfiguration(), new TaskAttemptID());
          Job job = Job.getInstance(source.getConfiguration().getConfiguration());
          InputFormat<?, ?> inputFormatObj =
              (InputFormat<?, ?>) job.getInputFormatClass().newInstance();
          currentReader =
              (RecordReader<T, T>) inputFormatObj.createRecordReader(split, attemptContext);
          if (currentReader != null) {
            currentReader.initialize(split, attemptContext);
            if (currentReader.nextKeyValue()) {
              currentRecord = nextPair();
              return true;
            }
          } else {
            throw new IOException("Null RecordReader object returned by  " + inputFormatObj);
          }
        } catch (Exception e) {
          throw new IOException(e);
        }
        currentReader = null;
        currentRecord = null;
        doneReading = true;
        return false;
      }

      @Override
      public boolean advance() throws IOException {
        try {
          if (currentReader != null && currentReader.nextKeyValue()) {
            currentRecord = nextPair();
            return true;
          }
          currentRecord = null;
          doneReading = true;
          return false;
        } catch (Exception e) {
          throw new IOException(e);
        }
      }

      /**
       * Function evaluates the next pair of key and value of the next record in sequence
       */
      public KV<K, V> nextPair() throws Exception {
        K key =
            transformObject(currentReader.getCurrentKey(), simpleFuncForKeyTranslation, keyCoder);
        V value = transformObject(currentReader.getCurrentValue(), simpleFuncForValueTranslation,
            valueCoder);
        recordsReturned++;
        return KV.of(key, value);
      }

      /**
       * Function checks if key/value needs to be transformed depending on simple function
       * withKeyTranslation()/withValueTranslation() provided by the pipeline user.
       */
      private <T1 extends Object> T1 transformObject(T input, SimpleFunction<T, T1> simpleFunction,
          Coder<T1> coder) throws IOException, InterruptedException {
        T1 output;
        if (null != simpleFunction) {
          output = simpleFunction.apply(input);
        } else {
          output = (T1) input;
        }
        return clone(output, coder);
      }

      /**
       * Cloning/Serialization is required to take care of Hadoop's mutable objects if returned by
       * RecordReader as Beam needs immutable objects
       */
      private <T1 extends Object> T1 clone(T1 input, Coder<T1> coder)
          throws IOException, InterruptedException, CoderException {
        // if the input object is not of known immutable type, clone the object
        if (!isKnownImmutable(input)) {
          input = CoderUtils.clone(coder, input);
        }
        return input;
      }

      /**
       * Utility method to check if the passed object is of a known immutable type
       */
      private boolean isKnownImmutable(Object o) {
        Set<Class<?>> immutableTypes = new HashSet<Class<?>>(
            Arrays.asList(String.class, Byte.class, Short.class, Integer.class, Long.class,
                Float.class, Double.class, Boolean.class, BigInteger.class, BigDecimal.class));
        return immutableTypes.contains(o.getClass());
      }

      @Override
      public KV<K, V> getCurrent() throws NoSuchElementException {
        if (currentRecord == null) {
          throw new NoSuchElementException();
        }
        return currentRecord;
      }

      @Override
      public void close() throws IOException {
        LOG.info("Closing reader after reading {} records.", recordsReturned);
        if (currentReader != null) {
          currentReader.close();
          currentReader = null;
        }
        currentRecord = null;
      }

      @Override
      public Double getFractionConsumed() {
        if (currentReader == null || recordsReturned == 0) {
          return 0.0;
        }
        if (doneReading) {
          return 1.0;
        }
        return getProgress();
      }

      /**
       * Get RecordReader Progress
       */
      private Double getProgress() {
        try {
          return (double) currentReader.getProgress();
        } catch (IOException | InterruptedException e) {
          LOG.error(
              "Error in computing the fractions consumed as RecordReader.getProgress() throws an exception : "
                  + e.getMessage(),
              e);
          return null;
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

    /**
     * A wrapper to allow Hadoop {@link org.apache.hadoop.mapreduce.InputSplit}s to be serialized
     * using Java's standard serialization mechanisms. Note that the InputSplit has to be Writable
     * (which mostly are).
     */
    public class SerializableSplit implements Externalizable {

      private static final long serialVersionUID = 0L;

      private InputSplit split;

      public SerializableSplit() {}

      public SerializableSplit(InputSplit split) {
        checkArgument(split instanceof Writable, "Split is not of type Writable: %s", split);
        this.split = split;
      }

      public InputSplit getSplit() {
        return split;
      }

      @Override
      public void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(split.getClass().getCanonicalName());
        ((Writable) split).write(out);
      }

      @Override
      public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        String className = in.readUTF();
        try {
          split = (InputSplit) Class.forName(className).newInstance();
          ((Writable) split).readFields(in);
        } catch (InstantiationException | IllegalAccessException e) {
          throw new IOException(e);
        }
      }
    }
  }

  /**
   * A wrapper to allow Hadoop {@link org.apache.hadoop.conf.Configuration} to be serialized using
   * Java's standard serialization mechanisms. Note that the org.apache.hadoop.conf.Configuration
   * has to be Writable (which mostly are).
   */
  public static class SerializableConfiguration implements Externalizable {
    private static final long serialVersionUID = 0L;

    private Configuration conf;

    public SerializableConfiguration() {}

    public SerializableConfiguration(Configuration conf) {
      this.conf = conf;
    }

    public Configuration getConfiguration() {
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
        throw new IOException(e);
      }
    }
  }

}
