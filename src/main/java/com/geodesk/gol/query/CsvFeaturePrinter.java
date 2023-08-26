/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.clarisma.common.text.Strings;
import com.geodesk.feature.Feature;

import java.io.PrintStream;

public class CsvFeaturePrinter extends AbstractFeaturePrinter
{
    private char colSeparator = ','; // '\t';
    private final StringBuilder buf = new StringBuilder();
    private Column currentColumn;

    public CsvFeaturePrinter(PrintStream out)
    {
        super(out);
    }


    @Override public void printHeader()
    {
        out.print("t");
        out.print(colSeparator);
        out.print("id");

        for(Column col: columns)
        {
            out.print(colSeparator);
            out.print(col.key);
        }
        out.println();
    }

    @Override public void print(Feature feature)
    {
        out.print(switch(feature.type())
        {
            case NODE -> "N";
            case WAY -> "W";
            case RELATION -> "R";
        });
        out.print(colSeparator);
        out.print(feature.id());
        setCoordinateProperties(feature);
        extractProperties(feature.tags());
        printProperties();
        out.println();
    }

    @Override protected void beginColumn(Column column)
    {
        currentColumn = column;
    }

    @Override protected void endColumn(Column column)
    {
        if(buf.length() > 0)
        {
            out.print(colSeparator);
            out.print(escaped(buf.toString()));
        }
        buf.setLength(0);
    }

    private String escaped(String s)
    {
        s = Strings.cleanString(s);
        if(s.indexOf('\"') < 0 && s.indexOf(',') <0 && s.indexOf(' ') < 0) return s;
        StringBuffer buf = new StringBuffer();
        buf.append('\"');
        for(int i=0; i<s.length(); i++)
        {
            char ch = s.charAt(i);
            if(ch == '\"') buf.append('\"');
            buf.append(ch);
        }
        buf.append('\"');
        return buf.toString();
    }

    @Override protected void printProperty(String key, String value)
    {
        if(currentColumn.properties != null)
        {
            if(buf.length() > 0) buf.append(',');
            buf.append(key);
            buf.append('=');
            buf.append(value);  // will be escaped later
            return;
        }
        out.print(colSeparator);
        out.print(escaped(value));
    }



    /*
    @Override public void print(PrintStream out, Feature feature)
    {
        columns[0] = switch(feature.type())
        {
            case NODE -> "N";
            case WAY -> "W";
            case RELATION -> "R";
        };
        columns[1] = String.valueOf(feature.id());
        Tags tags = feature.tags();
        while(tags.next())
        {
            String key = tags.key();
            String value = tags.stringValue();
            Integer col = keyToColumn.get(key);
            if(col == null)
            {
                if(extraTagsCol != 0)
                {
                    if(!extraTags.isEmpty()) extraTags.append(',');
                    extraTags.append(key);
                    extraTags.append('=');
                    extraTags.append(value);        // TODO: escape
                }
            }
            else
            {
                columns[col] = value;
            }
        }
        if(extraTagsCol != 0)
        {
            columns[extraTagsCol] = extraTags.toString();
            extraTags.setLength(0);
        }

        if(needsCenter)
        {
            // TODO: centroid, inside

            int x = feature.x();
            int y = feature.y();
            if (xCol != 0) columns[xCol] = String.valueOf(x);
            if (yCol != 0) columns[yCol] = String.valueOf(y);
            if (lonCol != 0) columns[lonCol] = String.valueOf(Mercator.lonFromX(x));
            if (latCol != 0) columns[latCol] = String.valueOf(Mercator.latFromY(y));
        }

        // TODO: geom

        printRow(out);
        resetColumns();
    }
     */
}
