/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.geodesk.feature.Feature;
import com.geodesk.util.MapMaker;
import com.geodesk.util.Marker;

import java.io.IOException;
import java.io.PrintStream;

public class MapFeaturePrinter extends AbstractFeaturePrinter
{
    private MapMaker map;
    private StringBuffer tagsBuf = new StringBuffer();
    private String attribution;
    private String basemap;
    private LinkSchema linkSchema = new OsmLinkSchema();
    private String color;

    private static class LinkSchema
    {
        public String format(Feature feature)
        {
            return "";      // TODO
        }
    }

    private static class OsmLinkSchema extends LinkSchema
    {
        @Override public String format(Feature feature)
        {
            return "https://www.openstreetmap.org/" + feature;
        }
    }


    public MapFeaturePrinter(PrintStream out)
    {
        super(out);
        map = new MapMaker();
    }

    @Override public boolean setOption(String name, String value)
    {
        switch(name)
        {
        case "attribution":
            checkValue(value);
            attribution = value;
            return true;
        case "basemap":
            checkValue(value);
            basemap = value;
            return true;
        case "color":
            checkValue(value);
            color = value;
            return true;
        case "link":
            checkValue(value);
            switch(value)
            {
            case "none":
                linkSchema = null;
                break;
            case "osm":
                linkSchema = new OsmLinkSchema();
                break;
            default:
                throw new IllegalArgumentException("Custom links are not yet supported.");
            }
            // TODO: custom link schemes
            return true;
        }
        return false;
    }


    @Override public void printHeader()
    {
        if(attribution != null) map.attribution(attribution);
        if(basemap != null) map.tiles(basemap);
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
        tagsBuf.append("<h3>");
        tagsBuf.append(feature);
        tagsBuf.append("</h3>");
        tagsBuf.append("<pre>\n");
        printProperties();
        tagsBuf.append("</pre>");
        Marker marker = map.add(feature).tooltip(tagsBuf.toString());
        if(color != null) marker.color(color);
        if(linkSchema != null) marker.url(linkSchema.format(feature));
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
