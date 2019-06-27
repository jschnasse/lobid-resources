/* Copyright 2017  hbz, Pascal Christoph. Licensed under the EPL 2.0 */

package org.lobid.resources;

import java.io.File;

import org.junit.Test;
import org.metafacture.biblio.pica.PicaDecoder;
import org.metafacture.formeta.FormetaEncoder;
import org.metafacture.io.FileOpener;
import org.metafacture.io.LineReader;
import org.metafacture.metamorph.Metamorph;
import org.metafacture.statistics.Histogram;
import org.metafacture.strings.LineRecorder;

/**
 * *
 * 
 * @author Pascal Christoph (dr0i)
 * 
 */
public final class ThkoelnPicaToSolrXmlTest {
	static final String PATH_TO_TEST = "src/test/resources/";
	static final String THKOELN_PICA_FN = PATH_TO_TEST + "nonNormalized.pica";

	// static final String THKOELN_NFC_XML_FN =
	// "/home/pc/thkoeln/thkoeln-korpus_simple_nfc.xml";

	/**
	 */
	@SuppressWarnings("static-method")
	@Test
	public void etlOutputAsNtriples2Filesystem() {
		final FileOpener opener = new FileOpener();
		PicaDecoder picaDecoder = new PicaDecoder();
		picaDecoder.setNormalizedSerialization(false);
		picaDecoder.setIgnoreMissingIdn(true);
		Histogram histogram = new Histogram("subject_vlb_ss"); //
		// "subject-intel.value"
		histogram.setCountEntities(true);
		histogram.setCountLiterals(false);
		FormetaEncoder formeta = new FormetaEncoder();
		// formeta.setStyle(FormatterStyle.MULTILINE);
		opener.setReceiver(new LineReader())//
				.setReceiver(new LineRecorder())//
				.setReceiver(picaDecoder)
				.setReceiver(new Metamorph("src/test/resources/morph-thkoeln_pica.xml"))//
				.setReceiver(histogram);//
		// .setReceiver(formeta)//
		// .setReceiver(new ObjectWriter("stdout"));
		opener.process(new File(THKOELN_PICA_FN).getAbsolutePath());
		opener.closeStream();
		// System.out.println(histogram.getHistogram().keySet());
		// System.out.println(histogram.getHistogram().values());
		System.out.println(histogram.getHistogram().entrySet().toString());

	}
}
