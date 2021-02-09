/* Copyright 2015, 2018 hbz. Licensed under the EPL 2.0 */

package org.lobid.resources.run;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

import org.lobid.resources.ElasticsearchIndexer;
import org.lobid.resources.JsonLdEtikett;
import org.lobid.resources.JsonToElasticsearchBulkMap;
import org.lobid.resources.RdfGraphToJsonLd;
import org.metafacture.biblio.marc21.MarcXmlHandler;
import org.metafacture.flowcontrol.ObjectThreader;
import org.metafacture.io.FileOpener;
import org.metafacture.io.RecordReader;
import org.metafacture.io.TarReader;
import org.metafacture.json.JsonEncoder;
import org.metafacture.metamorph.Metamorph;
import org.metafacture.monitoring.ObjectBatchLogger;
import org.metafacture.monitoring.StreamBatchLogger;
import org.metafacture.strings.StringReader;
import org.metafacture.xml.XmlDecoder;

/**
 * Transform hbz01 Aleph Mab XML catalog data into lobid elasticsearch ready
 * JSON-LD and index that into elasticsearch. Using proper parameters the aleph
 * "Loeschsaetze" will be etl'ed into an index of its own.
 * 
 * @author Pascal Christoph (dr0i)
 * 
 */
@SuppressWarnings("javadoc")
public class AlmaMarcXml2lobidJsonEs {
	private static final String MORPH_FN_PREFIX = "src/main/resources/alma/";
	public static final String CONTEXT_URI =
			"http://lobid.org/resources/context.jsonld";
	static RdfGraphToJsonLd rdfGraphToJsonLd = new RdfGraphToJsonLd(CONTEXT_URI);
	private static String indexAliasSuffix;
	private static String node;
	private static String cluster;
	private static String indexName;
	private static boolean updateDonotCreateIndex;
	private static String indexConfig;
	private static boolean lookupMabxmlDeletion;
  MabXml2lobidJsonEs mabXml2lobidJsonEs = new MabXml2lobidJsonEs();
  final static HashMap<String, String> morphVariables = new HashMap<>();

	public static void main(String... args) {
		String usage =
				"<input path>%s<index name>%s<index alias suffix ('NOALIAS' sets it empty)>%s<node>%s<cluster>%s<'update' (will take latest index), 'exact' (will take ->'index name' even when no timestamp is suffixed) , else create new index with actual timestamp>%s<optional: filename of index-config>%s<optional: filename of morph>%s<optional: jsonld-context-uri>%s";
    args=new String[]{"src/test/resources/alma/HT012734833_etAl.xml.tar.bz2","lobid-almaresources","test","weywot4.hbz-nrw.de","weywot","exact"};
        String inputPath = args[0];
    


		System.out.println("inputFile=" + inputPath);
		indexName = args[1];
		String date = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
		indexName =
				indexName.matches(".*-20.*") || args[5].toLowerCase().equals("exact")
						? indexName
						: indexName + "-" + date;
		indexAliasSuffix = args[2];
		node = args[3];
		cluster = args[4];
		updateDonotCreateIndex = args[5].toLowerCase().equals("update");
		if (args.length < 6) {
			System.err.println("Usage: AlmaMarcXml2lobidJsonEs"
					+ String.format(usage, " ", " ", " ", " ", " ", " ", " ", " ", " "));
			return;
		}
    indexConfig = args.length >= 7 ? args[6] : "index-config.json";
    System.out.println("using indexName: " + indexName);
		System.out.println("using indexConfig: " + indexConfig);
		String morphFileName = args.length >= 8 ? MORPH_FN_PREFIX + args[7]
				: MORPH_FN_PREFIX + "alma/alma.xml";
		System.out.println("using morph: " + morphFileName);
		rdfGraphToJsonLd.setContextLocationFilname(
				System.getProperty("contextFilename", "web/conf/context.jsonld"));
		System.out.println(
				"contextFilename:" + rdfGraphToJsonLd.getContextLocationFilename());
		if (args.length >= 9) {
			rdfGraphToJsonLd.setContextUri(args[8]);
		}
		System.out
				.println("using jsonLdContextUri: " + rdfGraphToJsonLd.getContextUri());

		// hbz catalog transformation
		final FileOpener opener = new FileOpener();
		if (inputPath.toLowerCase().endsWith("bz2")) {
			opener.setCompression("BZIP2");
		} else if (inputPath.toLowerCase().endsWith("gz"))
			opener.setCompression("GZIP");

		lookupMabxmlDeletion = Boolean
				.parseBoolean(System.getProperty("lookupMabxmlDeletion", "false"));

        morphVariables.put("isil", "DE-632");
        morphVariables.put("member", "DE-605");
        morphVariables.put("catalogid", "DE-605");

		opener.setReceiver(new TarReader()).setReceiver(new RecordReader())
				.setReceiver(new ObjectThreader<String>())//
				.addReceiver(receiverThread());//
    //		.addReceiver(receiverThread())//
				// .addReceiver(receiverThread())//
				// .addReceiver(receiverThread())//
				// .addReceiver(receiverThread())//
				// .addReceiver(receiverThread());
		try {
			opener.process(new File(inputPath).getAbsolutePath());
			opener.closeStream();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static ElasticsearchIndexer getElasticsearchIndexer() {
		ElasticsearchIndexer esIndexer = new ElasticsearchIndexer();
		esIndexer.setClustername(cluster);
		esIndexer.setHostname(node);
		esIndexer.setIndexName(indexName);
		esIndexer.setIndexAliasSuffix(indexAliasSuffix);
		esIndexer.setUpdateNewestIndex(updateDonotCreateIndex);
		esIndexer.setIndexConfig(indexConfig);
		esIndexer.lookupMabxmlDeletion = lookupMabxmlDeletion;
		System.out
				.println("lookupMabxmlDeletion: " + esIndexer.lookupMabxmlDeletion);
		esIndexer.lookupWikidata =
				Boolean.parseBoolean(esIndexer.lookupMabxmlDeletion ? "false"
						: System.getProperty("lookupWikidata", "true"));
		System.out.println("lookupWikidata: " + esIndexer.lookupWikidata);
		if (esIndexer.lookupMabxmlDeletion)
			esIndexer.lookupWikidata = false;
		esIndexer.onSetReceiver();
		return esIndexer;
	}

	private static StringReader receiverThread() {
		StreamBatchLogger batchLogger = new StreamBatchLogger();
		batchLogger.setBatchSize(100000);
		final String KEY_TO_GET_MAIN_ID =
				System.getProperty("keyToGetMainId", "almaIdMMS"); //pchbz hbzId
		System.out.println("using keyToGetMainId:" + KEY_TO_GET_MAIN_ID);
		System.out.println("using etikettLablesDirectory: "
				+ JsonLdEtikett.getLabelsDirectoryName());
		ObjectBatchLogger<HashMap<String, String>> objectBatchLogger =
				new ObjectBatchLogger<>();
		objectBatchLogger.setBatchSize(500000);
		StringReader sr = new StringReader();
    final String MORPH = MORPH_FN_PREFIX+"/alma.xml";
    MarcXmlHandler marcXmlHandler = new MarcXmlHandler();
    marcXmlHandler.setNamespace(null);
    JsonEncoder jsonEncoder = new JsonEncoder();

		sr.setReceiver(new XmlDecoder()).setReceiver(marcXmlHandler)
				.setReceiver(new Metamorph(MORPH, morphVariables)).setReceiver(batchLogger)//
				.setReceiver(jsonEncoder)//
				.setReceiver(
						new JsonToElasticsearchBulkMap(KEY_TO_GET_MAIN_ID,"resource","lobid-almaresources"))//
        //    .setReceiver(new ObjectStdoutWriter())
            	.setReceiver(getElasticsearchIndexer());
		return sr;
	}
}
