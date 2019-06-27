/* Copyright 2019  hbz, Pascal Christoph. Licensed under the EPL 2.0 */

package org.lobid.resources;

import java.io.File;

import org.junit.Test;
import org.metafacture.biblio.marc21.Marc21Decoder;
import org.metafacture.biblio.marc21.Marc21Encoder;
import org.metafacture.biblio.marc21.MarcXmlEncoder;
import org.metafacture.biblio.pica.PicaDecoder;
import org.metafacture.formeta.FormetaEncoder;
import org.metafacture.io.FileOpener;
import org.metafacture.io.LineReader;
import org.metafacture.io.ObjectWriter;
import org.metafacture.metamorph.Metamorph;
import org.metafacture.statistics.Histogram;
import org.metafacture.strings.LineRecorder;

/**
 * Runs some jobs on non normalized pica+: just outputs parsed pica+, creates
 * statistics and etls to marcxml.
 * 
 * @author Pascal Christoph (dr0i)
 * 
 */
public final class ThkoelnPicaToSolrXmlTest {
	private static final String PATH_TO_TEST = "src/test/resources/";
	private static final String THKOELN_PICA_FN =
			PATH_TO_TEST + "nonNormalized.pica";
	private static FileOpener opener = new FileOpener();
	private static PicaDecoder picaDecoder = new PicaDecoder();

	private static void setup() {
		opener = new FileOpener();
		opener.setReceiver(new LineReader())//
				.setReceiver(new LineRecorder())//
				.setReceiver(picaDecoder);
		picaDecoder = new PicaDecoder();
		picaDecoder.setNormalizedSerialization(false);
		picaDecoder.setIgnoreMissingIdn(true);
	}

	/**
	 * ETL non normalized pica+ to marcxml.
	 */
	@SuppressWarnings("static-method")
	@Test
	public void picaToMarcxml() {
		setup();
		MarcXmlEncoder marcXmlEncoder = new MarcXmlEncoder();
		Marc21Decoder marc21Decoder = new Marc21Decoder();
		marc21Decoder.setEmitLeaderAsWhole(true);
		marc21Decoder.setIgnoreMissingId(true);
		opener.setReceiver(new LineReader())//
				.setReceiver(new LineRecorder())//
				.setReceiver(picaDecoder)//
				.setReceiver(new Metamorph("src/test/resources/morph-thkoeln_pica.xml"))//
				.setReceiver(new Marc21Encoder())//
				.setReceiver(marc21Decoder)//
				.setReceiver(marcXmlEncoder)//
				.setReceiver(new ObjectWriter<String>("stdout"));
		opener.process(new File(THKOELN_PICA_FN).getAbsolutePath());
		opener.closeStream();
	}

	/**
	 * Extracts non normalized pica+.
	 */
	@SuppressWarnings("static-method")
	@Test
	public void picaOutput() {
		setup();
		FormetaEncoder formeta = new FormetaEncoder();
		opener.setReceiver(new LineReader())//
				.setReceiver(new LineRecorder())//
				.setReceiver(picaDecoder)//
				.setReceiver(formeta)//
				.setReceiver(new ObjectWriter<String>("stdout"));
		opener.process(new File(THKOELN_PICA_FN).getAbsolutePath());
		opener.closeStream();
	}

	/**
	 * Extracts non normalized pica+ and generates some statistics.
	 */
	@SuppressWarnings("static-method")
	@Test
	public void picaStatistics() {
		setup();
		Histogram histogram = new Histogram("subject_044Na");
		histogram.setCountEntities(true);
		histogram.setCountLiterals(true);
		opener.setReceiver(new LineReader())//
				.setReceiver(new LineRecorder())//
				.setReceiver(picaDecoder) //
				.setReceiver(
						new Metamorph("src/test/resources/morph-thkoeln_pica_stats.xml"))//
				.setReceiver(histogram);
		opener.process(new File(THKOELN_PICA_FN).getAbsolutePath());
		opener.closeStream();
		// show the amount of occurrence of "subject_044Na"
		// show all values of "subject_044Na" and the amount of occurrence
		System.out.println(histogram.getHistogram().entrySet().toString());
	}
}
