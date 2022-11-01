/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.geodesk.feature.Feature;
import com.geodesk.util.MapMaker;

import java.io.IOException;
import java.io.PrintStream;

public class MapFeaturePrinter extends AbstractFeaturePrinter
{
    private MapMaker map;
    private StringBuffer tagsBuf = new StringBuffer();

    public MapFeaturePrinter(PrintStream out)
    {
        super(out);
        map = new MapMaker();
    }

    @Override public void printHeader()
    {
    }

    @Override protected void printProperty(String key, String value)
    {
        tagsBuf.append(key);
        tagsBuf.append('=');
        tagsBuf.append(value);
        tagsBuf.append('\n');
    }

    @Override public void print(Feature feature)
    {
        extractProperties(feature.tags());
        tagsBuf.append("<pre>\n");
        printProperties();
        tagsBuf.append("</pre>");
        map.add(feature).tooltip(tagsBuf.toString());
        tagsBuf.setLength(0);
    }

    @Override public void printFooter()
    {
        try
        {
            map.write(out);
        }
        catch(IOException ex)
        {
            throw new RuntimeException("Error writing map: " + ex.getMessage(), ex);
        }
    }
}
