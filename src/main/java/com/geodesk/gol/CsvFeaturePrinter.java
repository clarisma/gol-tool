package com.geodesk.gol;

import com.geodesk.core.Mercator;
import com.geodesk.feature.Feature;
import com.geodesk.feature.Tags;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

public class CsvFeaturePrinter implements FeaturePrinter
{
    private final Map<String,Integer> keyToColumn = new HashMap<>();
    private final StringBuilder extraTags = new StringBuilder();
    private String[] columns;
    private int extraTagsCol;
    private int lonCol;
    private int latCol;
    private int xCol;
    private int yCol;
    private int geomCol;
    private boolean needsCenter;

    @Override public void useKeys(String[] keys)
    {
        int colCount = 2;
        for(String key: keys)
        {
            if(key.equals("*")) key="tags";
            if(keyToColumn.containsKey(key)) continue;
            switch(key)
            {
            case "lon":
                lonCol = colCount;
                needsCenter = true;
                break;
            case "lat":
                latCol = colCount;
                needsCenter = true;
                break;
            case "x":
                xCol = colCount;
                needsCenter = true;
                break;
            case "y":
                yCol = colCount;
                needsCenter = true;
                break;
            case "tags":
                extraTagsCol = colCount;
                break;
            case "geom":
                geomCol = colCount;
                break;
            }
            keyToColumn.put(key, colCount);
            colCount++;
        }
        columns = new String[colCount];
    }

    private void resetColumns()
    {
        for(int i=0; i<columns.length; i++) columns[i] = "";
    }

    private void printRow(PrintWriter out)
    {
        for(int i=0; i<columns.length; i++)
        {
            if(i>0) out.print('\t');
            out.print(columns[i]);
        }
        out.print('\n');
    }

    @Override public void printHeader(PrintWriter out)
    {
        columns[0] = "t";
        columns[1] = "id";
        for(Map.Entry<String,Integer> e: keyToColumn.entrySet())
        {
            columns[e.getValue()] = e.getKey();
        }
        printRow(out);
        resetColumns();
    }

    @Override public void print(PrintWriter out, Feature feature)
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
}
