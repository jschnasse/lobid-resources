/* Copyright 2019  hbz, Pascal Christoph. Licensed under the EPL 2.0 */

package org.lobid.resources.run;

import java.io.File;

import org.metafacture.biblio.AlephMabXmlHandler;
import org.metafacture.formeta.FormetaEncoder;
import org.metafacture.framework.helpers.DefaultObjectReceiver;
import org.metafacture.io.FileOpener;
import org.metafacture.io.TarReader;
import org.metafacture.metamorph.Metamorph;
import org.metafacture.xml.XmlDecoder;

/**
 * *
 * 
 * @author Pascal Christoph (dr0i)
 * 
 */
public final class Hbz01MabXmlEtlGetOneFieldsValue2Filesystem {
	static final String TEST_FILENAME_ALEPHXMLCLOBS =
			"src/test/resources/hbz01XmlClobs.tar.bz2";

	/**
	 * Generates a simple tsv.
	 * 
	 * @param arg path of data file
	 */
	public static void main(String... arg) {
		String pathToData = new File(TEST_FILENAME_ALEPHXMLCLOBS).getAbsolutePath();
		if (arg.length > 0)
			pathToData = arg[0];
		final FileOpener opener = new FileOpener();
		System.out.print("id,publicationDate" + "\n");
		opener.setReceiver(new TarReader()).setReceiver(new XmlDecoder())
				.setReceiver(new AlephMabXmlHandler())
				.setReceiver(
						new Metamorph("src/main/resources/morph-get-one-field.xml"))
				.setReceiver(new FormetaEncoder())
				.setReceiver(new DefaultObjectReceiver<String>() {
					@Override
					public void process(final String obj) {
						String out = obj.replaceFirst(",:", "|").replaceAll(":", "")
								.replaceAll("\\{", "").replaceAll("\\}", "");
						System.out.print(out + "\n");
					}
				});
		opener.process(pathToData);
		opener.closeStream();
	}
}
