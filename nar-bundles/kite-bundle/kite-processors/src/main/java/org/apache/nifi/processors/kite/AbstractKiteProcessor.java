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
import com.google.common.io.Resources;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.Validator;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.annotation.OnScheduled;
import org.kitesdk.data.DatasetNotFoundException;
import org.kitesdk.data.Datasets;
import org.kitesdk.data.SchemaNotFoundException;
import org.kitesdk.data.spi.DefaultConfiguration;

public abstract class AbstractKiteProcessor extends AbstractProcessor {

  protected static Validator DATASET_EXISTS = new Validator() {
    @Override
    public ValidationResult validate(String subject, String uri, ValidationContext context) {
      // TODO: setup DefaultConfiguration
      return new ValidationResult.Builder()
          .subject(subject)
          .input(uri)
          .explanation("Dataset does not exist")
          .valid(Datasets.exists(uri))
          .build();
    }
  };

  /**
   * Resolves a {@link Schema} for the given string, either a URI or a JSON
   * literal.
   */
  protected static Schema getSchema(String uriOrLiteral, Configuration conf) {
    URI uri;
    try {
      uri = new URI(uriOrLiteral);
    } catch (URISyntaxException e) {
      // try to parse the schema as a literal
      return parseSchema(uriOrLiteral);
    }

    try {
      if ("dataset".equals(uri.getScheme()) || "view".equals(uri.getScheme())) {
        return Datasets.load(uri).getDataset().getDescriptor().getSchema();
      } else if ("resource".equals(uri.getScheme())) {
        InputStream in = Resources.getResource(uri.getSchemeSpecificPart())
            .openStream();
        return parseSchema(uri, in);
      } else {
        // try to open the file
        Path schemaPath = new Path(uri);
        FileSystem fs = schemaPath.getFileSystem(conf);
        return parseSchema(uri, fs.open(schemaPath));
      }

    } catch (DatasetNotFoundException e) {
      throw new SchemaNotFoundException(
          "Cannot read schema of missing dataset: " + uri, e);
    } catch (IOException e) {
      throw new SchemaNotFoundException(
          "Failed while reading " + uri + ": " + e.getMessage(), e);
    }
  }

  private static Schema parseSchema(String literal) {
    try {
      return new Schema.Parser().parse(literal);
    } catch (RuntimeException e) {
      throw new SchemaNotFoundException(
          "Failed to parse schema: " + literal, e);
    }
  }

  private static Schema parseSchema(URI uri, InputStream in) throws IOException {
    try {
      return new Schema.Parser().parse(in);
    } catch (RuntimeException e) {
      throw new SchemaNotFoundException("Failed to parse schema at " + uri, e);
    }
  }

  protected static Validator SCHEMA_VALIDATOR = new Validator() {
    @Override
    public ValidationResult validate(String subject, String uri, ValidationContext context) {
      // TODO: setup DefaultConfiguration
      String error = null;
      try {
        getSchema(uri, DefaultConfiguration.get());
      } catch (SchemaNotFoundException e) {
        error = e.getMessage();
      }
      return new ValidationResult.Builder()
          .subject(subject)
          .input(uri)
          .explanation(error)
          .valid(error == null)
          .build();
    }
  };

  protected static final List<PropertyDescriptor> ABSTRACT_KITE_PROPS =
      ImmutableList.<PropertyDescriptor>builder()
          .build();

  static List<PropertyDescriptor> getProperties() {
    return ABSTRACT_KITE_PROPS;
  }

  @OnScheduled
  protected void setDefaultConfiguration(ProcessContext context)
      throws IOException {
    // TODO
  }

  @Override
  protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
    return ABSTRACT_KITE_PROPS;
  }
}
