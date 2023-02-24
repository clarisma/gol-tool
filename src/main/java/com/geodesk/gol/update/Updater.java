/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.text.Format;
import com.clarisma.common.util.Log;
import com.geodesk.gol.build.BuildContext;
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
    private final BuildContext context;

    public Updater(BuildContext context)
    {
        this.context = context;
    }

    public void update() throws IOException, InterruptedException
    {
        // TODO
        // String oscFile = "c:\\geodesk\\research\\world-3803.osc.gz";
        String oscFile = "c:\\geodesk\\research\\de-3530.osc.gz";

        long start = System.currentTimeMillis();
        TileFinder tileFinder = new TileFinder(context);
        ChangeReader reader = new ChangeReader(context, tileFinder);
        reader.read(oscFile, true); // TODO
        tileFinder.finish();
        reader.dump();

        int fileCount = 1;  // TODO
        System.err.format("Read %,d file%s in %s\n", fileCount, fileCount==1 ? "" : "s",
            Format.formatTimespan(System.currentTimeMillis() - start));

        FeatureFinder featureFinder = new FeatureFinder(context,
            reader.nodes(), reader.ways(), reader.relations());
        featureFinder.search(tileFinder);
    }
}
