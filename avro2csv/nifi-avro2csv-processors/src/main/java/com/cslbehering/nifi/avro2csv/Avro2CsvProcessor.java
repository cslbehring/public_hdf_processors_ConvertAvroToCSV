/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cslbehering.nifi.avro2csv;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.commons.csv.CSVPrinter;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import com.cslbehering.nifi.avro2csv.CsvProcessor.Column;
import com.cslbehering.nifi.avro2csv.CsvProcessor;

/**
 * 
 * @author saniam
 * 
 *
 */

@SideEffectFree
@SupportsBatching
@Tags({ "avro", "convert", "csv", "v4" })
@InputRequirement(Requirement.INPUT_ALLOWED)
@CapabilityDescription("Converts a Binary Avro record into a CSV object. This processor provides a direct mapping of an Avro field to a CSV row. ")
@WritesAttribute(attribute = "mime.type", description = "Sets the mime type to text/csv")
public class Avro2CsvProcessor extends AbstractProcessor {

	protected static final String CONTAINER_ARRAY = "array";
	protected static final String CONTAINER_NONE = "none";
	protected static final String MIME_TYPE = "text/csv";
	public static final AllowableValue CSV_FORMAT_Default = new AllowableValue("Default", "Default/Basic",
			"Default Format");
	public static final AllowableValue CSV_FORMAT_Excel = new AllowableValue("Excel", "Excel",
			"The Microsoft Excel CSV format.");
	public static final AllowableValue CSV_FORMAT_InformixUnload = new AllowableValue("InformixUnload",
			"InformixUnload", "Informix UNLOAD format used by the UNLOAD TO file_name operation.");
	public static final AllowableValue CSV_FORMAT_InformixUnloadCsv = new AllowableValue("InformixUnloadCsv",
			"InformixUnloadCsv",
			"Informix CSV UNLOAD format used by the UNLOAD TO file_name operation (escaping is disabled.)");
	public static final AllowableValue CSV_FORMAT_MySQL = new AllowableValue("MySQL", "MySQL",
			"The Oracle MySQL CSV format.");
	public static final AllowableValue CSV_FORMAT_RFC4180 = new AllowableValue("RFC4180", "Standard/RFC4180",
			"The RFC-4180 format defined by RFC-4180");
	public static final AllowableValue CSV_FORMAT_TDF = new AllowableValue("TDF", "TDF", "A tab delimited Format");

	public static final AllowableValue CSV_DELIMITER_DEFAULT = new AllowableValue("\n", "LF", "New line per record");

	// static final PropertyDescriptor SCHEMA = new
	// PropertyDescriptor.Builder().name("Avro schema")
	// .description("If the Avro records do not contain the schema (datum only), it
	// must be specified here.")
	// .addValidator(StandardValidators.NON_EMPTY_VALIDATOR).required(false).build();

	static final PropertyDescriptor CSV_COMPATIBILITY = new PropertyDescriptor.Builder().name("CSV Compatibility")
			.description(
					"The Compatibility of the CSV file {Default,Excel ,InformixUnload ,InformixUnloadCsv ,MySQL ,RFC4180 ,TDF}")
			.allowableValues(CSV_FORMAT_Default, CSV_FORMAT_Excel, CSV_FORMAT_InformixUnload,
					CSV_FORMAT_InformixUnloadCsv, CSV_FORMAT_MySQL, CSV_FORMAT_RFC4180, CSV_FORMAT_TDF)
			.required(true).build();

	static final PropertyDescriptor RECORD_DELIMITER = new PropertyDescriptor.Builder().name("Record Delimiter")
			.description("The record delimiter. (by default is LF (\\n)")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).defaultValue("\n").required(false).build();

	static final PropertyDescriptor CSV_HEADER_SORTING = new PropertyDescriptor.Builder()
			.name("CSV Header Sorting Direction").description("A for Ascending, D for Descending")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).defaultValue("A").required(false).build();

	static final PropertyDescriptor CSV_HEADER_SORT_FIELD = new PropertyDescriptor.Builder()
			.name("CSV Header Sorting Field").description("F for Field Name or P for Place attribute")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).defaultValue("F").required(false).build();
	/////////////////////
	static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
			.description("A FlowFile is routed to this relationship after it has been converted to CSV").build();
	static final Relationship REL_FAILURE = new Relationship.Builder().name("failure").description(
			"A FlowFile is routed to this relationship if it cannot be parsed as Avro or cannot be converted to CSV for any reason")
			.build();

	private List<PropertyDescriptor> properties;
	private volatile Schema schema = null;

	@Override
	protected void init(ProcessorInitializationContext context) {
		super.init(context);

		final List<PropertyDescriptor> properties = new ArrayList<>();

		// properties.add(SCHEMA);

		properties.add(CSV_COMPATIBILITY);
		properties.add(RECORD_DELIMITER);
		properties.add(CSV_HEADER_SORT_FIELD);
		properties.add(CSV_HEADER_SORTING);
		this.properties = Collections.unmodifiableList(properties);
	}

	@Override
	protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
		return properties;
	}

	@Override
	public Set<Relationship> getRelationships() {
		final Set<Relationship> rels = new HashSet<>();
		rels.add(REL_SUCCESS);
		rels.add(REL_FAILURE);
		return rels;
	}

	@Override
	public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
		convertAvroToCsv(context, session);
	}

	private void convertAvroToCsv(ProcessContext context, ProcessSession session) {
		FlowFile flowFile = session.get();
		if (flowFile == null) {
			return;
		}

		// final String stringSchema = context.getProperty(SCHEMA).getValue();

		final String csvCompatibility = context.getProperty(CSV_COMPATIBILITY).getValue();
		final String csvRecordDelimiter = context.getProperty(RECORD_DELIMITER).getValue();
		final String csvSortDirection = context.getProperty(CSV_HEADER_SORTING).getValue();
		final String csvSortFIELD = context.getProperty(CSV_HEADER_SORT_FIELD).getValue();

		// final boolean schemaLess = stringSchema != null;

		try {
			/*
			 * It can be replaced by direct printing mechanism {we need to have a PROPER
			 * Strategy to take care of CSV broken/invalid records}. Currently this approach
			 * is using a buffered CharArrayWriter.
			 */

			flowFile = session.write(flowFile, (final InputStream rawIn, final OutputStream rawOut) -> {

				// if (schemaLess) {
				// if (schema == null) {
				// schema = new Schema.Parser().parse(stringSchema);
				// }
				//
				// List<Column> columns = CsvProcessor.extractColumns(schema,
				// csvSortDirection.equals("D"),
				// csvSortFIELD.equals("F"));
				//
				// try (final InputStream in = new BufferedInputStream(rawIn);
				// final OutputStream out = new BufferedOutputStream(rawOut)) {
				// final DatumReader<GenericRecord> reader = new
				// GenericDatumReader<GenericRecord>(schema);
				// final BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(in, null);
				// final GenericRecord record = reader.read(null, decoder);
				//
				// CsvProcessor.processRecord(out, record, columns);
				// out.write(bundle.getWriter().toString().getBytes());
				// }
				// } else {

				try (final InputStream in = new BufferedInputStream(rawIn);
						final OutputStream out = new BufferedOutputStream(rawOut);
						final DataFileStream<GenericRecord> reader = new DataFileStream<>(in,
								new GenericDatumReader<GenericRecord>())) {
					CSVPrinter p = CsvProcessor.generateCsvPrinter(csvRecordDelimiter, csvCompatibility, out);
					List<Column> columns = CsvProcessor.extractColumns(reader.getSchema(), csvSortDirection.equals("D"),
							csvSortFIELD.equals("F"));

					GenericRecord currRecord = null;
					long counter = 0;
					while (reader.hasNext()) {
						currRecord = reader.next(currRecord);

						CsvProcessor.processRecordY(p, currRecord, columns);
						// out.write(l.toString().getBytes());
						// out.flush();
						// getLogger().info("FLUSHED2");
						counter++;
					}
					getLogger().info("++++++++++++++++++++++ Total Record Count: " + counter);
				} catch (Exception e) {
					getLogger().error(
							"Failed to convert  from Avro to CSV due to Printer error {}; transferring to failure",
							new Object[] { e.getMessage() });
				}
			}
			// }
			);
		} catch (final Exception pe) {
			getLogger().error("Failed to convert {} from Avro to CSV due to {}; transferring to failure",
					new Object[] { flowFile, pe });
			session.transfer(flowFile, REL_FAILURE);
			return;
		}

		flowFile = session.putAttribute(flowFile, CoreAttributes.MIME_TYPE.key(), "text/csv");
		session.transfer(flowFile, REL_SUCCESS);
	}

	public static <T> T getLastElement(final Iterable<T> elements) {
		final Iterator<T> itr = elements.iterator();
		T lastElement = itr.next();

		while (itr.hasNext()) {
			lastElement = itr.next();
		}

		return lastElement;
	}

	@Override
	protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
		return new PropertyDescriptor.Builder().name(propertyDescriptorName).required(false)
				.addValidator(StandardValidators
						.createAttributeExpressionLanguageValidator(AttributeExpression.ResultType.STRING, true))
				.addValidator(StandardValidators.ATTRIBUTE_KEY_PROPERTY_NAME_VALIDATOR)
				.expressionLanguageSupported(true).dynamic(true).build();
	}

}
