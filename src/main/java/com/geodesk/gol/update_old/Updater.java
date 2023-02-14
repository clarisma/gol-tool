/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import com.clarisma.common.util.Log;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public class Updater
{
    public Updater()
    {
    }

    public void read(String file, boolean zipped) throws IOException
    {
        long start = System.currentTimeMillis();
        try (FileInputStream fin = new FileInputStream(file))
        {
            InputStream in = fin;
            if (zipped) in = new GZIPInputStream(fin);

            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser parser = factory.newSAXParser();
            // reader.read(in);
            parser.parse(in, new DefaultHandler());
            in.close();
        }
        catch(SAXException | ParserConfigurationException ex)
        {
            throw new IOException("%s: Invalid file (%s)".formatted(file, ex.getMessage()));
        }

        Log.debug("Read %s in %,d ms", file, System.currentTimeMillis() - start);
    }

    public static void main(String[] args) throws Exception
    {
        Updater updater = new Updater();

        updater.read("c:\\geodesk\\research\\de-3530.osc", false);
        updater.read("c:\\geodesk\\research\\de-3530.osc.gz", true);
        updater.read("c:\\geodesk\\research\\de-3530.osc", false);
        updater.read("c:\\geodesk\\research\\de-3530.osc.gz", true);
        updater.read("c:\\geodesk\\research\\de-3530.osc", false);
        updater.read("c:\\geodesk\\research\\de-3530.osc.gz", true);
        updater.read("c:\\geodesk\\research\\world-3795.osc.gz", true);
        updater.read("c:\\geodesk\\research\\world-3795.osc.gz", true);
    }
}
