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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericData.Record;
import org.apache.nifi.annotation.behavior.TriggerWhenEmpty;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.StopWatch;
import org.kitesdk.data.Dataset;
import org.kitesdk.data.DatasetIOException;
import org.kitesdk.data.DatasetWriter;
import org.kitesdk.data.Datasets;
import org.kitesdk.data.IncompatibleSchemaException;
import org.kitesdk.data.Syncable;
import org.kitesdk.data.ValidationException;
import org.kitesdk.data.View;
import org.kitesdk.data.spi.SchemaValidationUtil;

@TriggerWhenEmpty
@Tags({"kite", "avro", "parquet", "hadoop", "hive", "hdfs", "hbase"})
@CapabilityDescription("Stores Avro records in a Kite dataset")
public class StoreInKiteDataset extends AbstractKiteProcessor {

    private static final Relationship SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFile content has been successfully saved")
            .build();

    private static final Relationship INCOMPATIBLE = new Relationship.Builder()
            .name("incompatible")
            .description("FlowFile content is not compatible with the target dataset")
            .build();

    private static final Relationship FAILURE = new Relationship.Builder()
            .name("failure")
            .description("FlowFile content could not be processed")
            .build();

    public static final PropertyDescriptor KITE_DATASET_URI
            = new PropertyDescriptor.Builder()
            .name("Target dataset URI")
            .description("URI that identifies a Kite dataset where data will be stored")
            .addValidator(RECOGNIZED_URI)
            .expressionLanguageSupported(true)
            .required(true)
            .build();

    public static final PropertyDescriptor ROLL_INTERVAL = new PropertyDescriptor.Builder()
            .name("Roll interval")
            .description("Maximum time interval before finishing an in-progress output file")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .defaultValue("30 secs")
            .required(true)
            .build();

    private static final List<PropertyDescriptor> PROPERTIES
            = ImmutableList.<PropertyDescriptor>builder()
            .addAll(AbstractKiteProcessor.getProperties())
            .add(KITE_DATASET_URI)
            .build();

    private static final Set<Relationship> RELATIONSHIPS
            = ImmutableSet.<Relationship>builder()
            .add(SUCCESS)
            .add(INCOMPATIBLE)
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

    /**
     * A shared cache for datasets by URI. The datasets in this cache expire and are
     * reloaded every 5 minutes to pick up configuration updates. If the schema changes,
     * this is detected using the cached schema and will invalidate the writer.
     */
    private static final LoadingCache<String, View<Record>> DATASETS = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build(new CacheLoader<String, View<Record>>() {
                @Override
                public Dataset<Record> load(String uri) throws Exception {
                    return Datasets.load(uri, Record.class);
                }
            });

    private static final ThreadLocal<Map<String, DatasetWriter<Record>>> WRITERS =
            new ThreadLocal<Map<String, DatasetWriter<Record>>>() {
                @Override
                protected Map<String, DatasetWriter<Record>> initialValue() {
                    return Maps.newHashMap();
                }
            };

    private static final ThreadLocal<Map<String, Schema>> SCHEMAS =
            new ThreadLocal<Map<String, Schema>>() {
                @Override
                protected Map<String, Schema> initialValue() {
                    return Maps.newHashMap();
                }
            };

    private static final ThreadLocal<Multimap<Long, String>> EXPIRATIONS =
            new ThreadLocal<Multimap<Long, String>>() {
                @Override
                protected Multimap<Long, String> initialValue() {
                    return MultimapBuilder.hashKeys().arrayListValues().build();
                }
            };

    private volatile long rollIntervalMillis = -1;

    @OnScheduled
    public void onScheduled(ProcessContext context) {
        this.rollIntervalMillis = context.getProperty(ROLL_INTERVAL)
                .asTimePeriod(TimeUnit.MILLISECONDS);
    }

    @OnStopped
    public void onStopped(ProcessContext context) {
        closeAllWriters();
    }

    @Override
    public void onTrigger(ProcessContext context, final ProcessSession session)
            throws ProcessException {
        expireWriters();

        List<String> toClose = Lists.newArrayList();

        for (FlowFile incomingData = session.get(); incomingData != null; incomingData = session.get()) {
            String uri = context.getProperty(KITE_DATASET_URI)
                    .evaluateAttributeExpressions(incomingData)
                    .getValue();

            storeWithKite(session, toClose, uri, incomingData);
        }

        for (String uri : toClose) {
            closeWriter(uri);
        }
    }

    private void storeWithKite(final ProcessSession session, final List<String> toClose,
                               final String uri, FlowFile incomingData) {
        View<Record> target = DATASETS.getUnchecked(uri);

        // check whether the schema has changed
        final Schema schema = target.getDataset().getDescriptor().getSchema();
        final DatasetWriter<Record> writer = getWriter(uri, target, schema);

        try {
            StopWatch timer = new StopWatch(true);
            session.read(incomingData, new InputStreamCallback() {
                @Override
                public void process(InputStream in) throws IOException {
                    try (DataFileStream<Record> stream = new DataFileStream<>(
                            in, AvroUtil.newDatumReader(schema, Record.class))) {
                        IncompatibleSchemaException.check(
                                SchemaValidationUtil.canRead(stream.getSchema(), schema),
                                "Incompatible file schema %s, expected %s",
                                stream.getSchema(), schema);

                        long written = 0L;
                        try {
                            for (Record record : stream) {
                                writer.write(record);
                                written += 1;
                            }

                        } finally {
                            if (writer instanceof Syncable) {
                                // flush and sync the data to ensure durability
                                ((Syncable) writer).sync();
                                session.adjustCounter("Stored records", written,
                                        true /* cannot roll back the write */);

                            } else {
                                // records are durable only on successful close
                                toClose.add(uri);
                                session.adjustCounter("Stored records", written,
                                        false /* could be rolled back */);
                            }
                        }
                    }
                }
            });
            timer.stop();

            session.getProvenanceReporter().send(incomingData,
                    target.getUri().toString(),
                    timer.getDuration(TimeUnit.MILLISECONDS),
                    true /* cannot roll back the write */);

            session.transfer(incomingData, SUCCESS);

        } catch (ProcessException | DatasetIOException e) {
            getLogger().error("Failed to read FlowFile", e);
            session.transfer(incomingData, FAILURE);

        } catch (ValidationException e) {
            getLogger().error(e.getMessage());
            getLogger().debug("Incompatible schema error", e);
            session.transfer(incomingData, INCOMPATIBLE);
        }
    }

    private DatasetWriter<Record> getWriter(String uri, View<Record> target, Schema schema) {
        DatasetWriter<Record> writer = WRITERS.get().get(uri);

        if (!schema.equals(SCHEMAS.get().get(uri))) {
            // the schema has changed or wasn't present
            if (writer != null) {
                // close the current writer and get a new one
                writer.close();
            }

            writer = target.newWriter();

            // if the writer can be synced, then it can be tracked and reused
            if (writer instanceof Syncable) {
                WRITERS.get().put(uri, writer);
                SCHEMAS.get().put(uri, schema);

                if (rollIntervalMillis > 0) {
                    long expiration = System.currentTimeMillis() + rollIntervalMillis;
                    EXPIRATIONS.get().put(expiration, uri);
                }
            }
        }

        return writer;
    }

    private void closeWriter(String uri) {
        DatasetWriter<?> writer = WRITERS.get().remove(uri);
        writer.close();
        SCHEMAS.get().remove(uri);
    }

    private void closeAllWriters() {
        for (DatasetWriter<?> writer : WRITERS.get().values()) {
            writer.close();
        }
        WRITERS.get().clear();
        SCHEMAS.get().clear();
        EXPIRATIONS.get().clear();
    }

    private void expireWriters() {
        long now = System.currentTimeMillis();
        for (Long expiration : EXPIRATIONS.get().keySet()) {
            if (expiration < now) {
                Collection<String> uris = EXPIRATIONS.get().removeAll(expiration);
                for (String uri : uris) {
                    closeWriter(uri);
                }
            }
        }
    }
}
