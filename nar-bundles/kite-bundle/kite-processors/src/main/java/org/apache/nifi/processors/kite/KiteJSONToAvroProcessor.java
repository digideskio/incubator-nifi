/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.nifi.processors.kite;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.io.DatumWriter;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.annotation.OnScheduled;
import org.apache.nifi.processor.exception.FlowFileAccessException;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.kitesdk.data.DatasetException;
import org.kitesdk.data.DatasetIOException;
import org.kitesdk.data.spi.DefaultConfiguration;
import org.kitesdk.data.spi.filesystem.CSVProperties;
import org.kitesdk.data.spi.filesystem.JSONFileReader;

public class KiteJSONToAvroProcessor extends AbstractKiteProcessor {
  private static CSVProperties DEFAULTS = new CSVProperties.Builder().build();

  private static Relationship SUCCESS = new Relationship.Builder()
      .name("success")
      .description("FlowFile content has been successfully saved")
      .build();

  private static Relationship FAILURE = new Relationship.Builder()
      .name("failure")
      .description("FlowFile content could not be processed")
      .build();

  private static final PropertyDescriptor SCHEMA =
      new PropertyDescriptor.Builder()
          .name("Record schema")
          .description(
              "Outgoing Avro schema for each record created from a JSON object")
          .addValidator(SCHEMA_VALIDATOR)
          .required(true)
          .build();

  private static final PropertyDescriptor CHARSET =
      new PropertyDescriptor.Builder()
          .name("JSON charset")
          .description("Character set for JSON files")
          .addValidator(StandardValidators.CHARACTER_SET_VALIDATOR)
          .defaultValue(DEFAULTS.charset)
          .build();

  private static final List<PropertyDescriptor> PROPERTIES =
      ImmutableList.<PropertyDescriptor>builder()
          .addAll(AbstractKiteProcessor.getProperties())
          .add(SCHEMA)
          .build();

  private static final Set<Relationship> RELATIONSHIPS =
      ImmutableSet.<Relationship>builder()
          .add(SUCCESS)
          .add(FAILURE)
          .build();

  @Override
  protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
    return PROPERTIES;
  }

  @Override
  public Set<Relationship> getRelationships() {
    return RELATIONSHIPS;
  }

  @OnScheduled
  public void createCSVProperties(ProcessContext context) throws IOException {
    super.setDefaultConfiguration(context);
  }

  @Override
  public void onTrigger(ProcessContext context, final ProcessSession session)
      throws ProcessException {
    FlowFile flowFile = session.get();
    if (flowFile == null) {
      return;
    }

    final Schema schema = getSchema(
        context.getProperty(SCHEMA).getValue(),
        DefaultConfiguration.get());

    final DataFileWriter<Record> writer = new DataFileWriter<>(
        newDatumWriter(schema));
    writer.setCodec(CodecFactory.snappyCodec());

    try {
      flowFile = session.write(flowFile, new StreamCallback() {
        @Override
        public void process(InputStream in, OutputStream out) throws IOException {
          try (JSONFileReader<Record> reader = new JSONFileReader<>(
              in, schema, Record.class)) {
            reader.initialize();
            try (DataFileWriter<Record> w = writer.create(schema, out)) {
              for (Record record : reader) {
                w.append(record);
              }
            }
          }
        }
      });
      session.transfer(flowFile, SUCCESS);

      //session.getProvenanceReporter().send(flowFile, target.getUri().toString());
    } catch (FlowFileAccessException | DatasetIOException e) {
      getLogger().error("Failed reading or writing", e);
      session.transfer(flowFile, FAILURE);

    } catch (DatasetException e) {
      getLogger().error("Failed to read FlowFile", e);
      session.transfer(flowFile, FAILURE);

    } catch (Throwable t) {
      session.rollback(true); // penalize just in case
      context.yield();
    }
  }

  @SuppressWarnings("unchecked")
  private static DatumWriter<Record> newDatumWriter(Schema schema) {
    return (DatumWriter<Record>) GenericData.get().createDatumWriter(schema);
  }

}
