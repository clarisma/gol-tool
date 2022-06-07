package com.geodesk.gol;

import com.geodesk.feature.Tags;

import java.io.PrintStream;
import java.util.*;

public abstract class AbstractFeaturePrinter implements FeaturePrinter
{
    protected final PrintStream out;
    protected List<Column> columns;
    private Map<String,Column> keyToColumn;
    private List<Column> wildcardColumns;
    private Column anyColumn;
    private Column geomColumn;
    private Column xColumn;
    private Column yColumn;
    private Column lonColumn;
    private Column latColumn;
    protected int rowNumber;
    protected int columnNumber;
    protected int propertyNumber;

    protected AbstractFeaturePrinter(PrintStream out)
    {
        this.out = out;
    }

    static class Property implements Comparable<Property>
    {
        final String key;
        String value;

        Property(String k)
        {
            key = k;
        }

        @Override public int compareTo(Property other)
        {
            return key.compareTo(other.key);
        }
    }

    static class Column extends Property
    {
        final List<Property> properties;
        final String startsWith;
        final String endsWith;

        Column(String key, String startsWith, String endsWith)
        {
            super(key);
            properties = (startsWith != null || endsWith != null) ? new ArrayList<>() : null;
            this.startsWith = startsWith;
            this.endsWith = endsWith;
        }

        void addProperty(String k, String v)
        {
            Property p = new Property(k);
            p.value = v;
            properties.add(p);
        }
    }

    public void columns(String[] colSpecs)
    {
        columns = new ArrayList<>();
        wildcardColumns = new ArrayList<>();
        keyToColumn = new HashMap<>();

        if(colSpecs == null || colSpecs.length == 0)
        {
            anyColumn = new Column("*", "", "");
            columns.add(anyColumn);
            return;
        }

        for(String colSpec: colSpecs)
        {
            int wildcard = colSpec.indexOf('*');
            Column col;
            if(wildcard >= 0)
            {
                col = new Column(colSpec,
                    colSpec.substring(0, wildcard), colSpec.substring( wildcard+1));
                if(colSpec.length() == 1)   // "*"?
                {
                    anyColumn = col;
                }
                else
                {
                    wildcardColumns.add(col);
                }
            }
            else
            {
                col = new Column(colSpec, null, null);
                switch(colSpec)
                {
                case "geom":
                    geomColumn = col;
                    break;
                case "x":
                    xColumn = col;
                    break;
                case "y":
                    yColumn = col;
                    break;
                case "lon":
                    lonColumn = col;
                    break;
                case "lat":
                    latColumn = col;
                    break;
                default:
                    keyToColumn.put(colSpec, col);
                    break;
                }
            }
            columns.add(col);
        }
    }

    protected void extractProperties(Tags tags)
    {
    loop:
        while(tags.next())
        {

            String k = tags.key();
            Column keyCol = keyToColumn.get(k);
            if(keyCol != null)
            {
                keyCol.value = tags.stringValue();
                continue;
            }
            for(Column c: wildcardColumns)
            {
                if(k.startsWith(c.startsWith) && k.endsWith(c.endsWith))
                {
                    c.addProperty(k, tags.stringValue());
                    continue loop;
                }
            }
            if(anyColumn != null) anyColumn.addProperty(k, tags.stringValue());
        }
    }

    protected void beginColumn(Column column)
    {
        // do nothing
    }

    protected void endColumn(Column column)
    {
        // do nothing
    }

    protected void printProperty(String key, String value)
    {
        // do nothing
    }

    protected void printProperties()
    {
        columnNumber = 0; // TODO: reset in printFeature
        propertyNumber = 0;
        for(Column col: columns)
        {
            beginColumn(col);
            if(col.properties != null)
            {
                Collections.sort(col.properties);
                for (Property p : col.properties)
                {
                    printProperty(p.key, p.value);
                    propertyNumber++;
                }
                col.properties.clear();
            }
            else
            {
                if(col.value != null)
                {
                    printProperty(col.key, col.value);
                    propertyNumber++;
                }
                col.value = null;
            }
            endColumn(col);
            columnNumber++;
        }
    }

    protected void printNumber(double value)
    {
        long longValue = (long)value;
        out.print((value == longValue) ? Long.toString(longValue) : Double.toString(value));
    }

    protected void printX(double x)
    {
        // TODO: projection
        printNumber(x);
    }

    protected void printY(double y)
    {
        // TODO: projection
        printNumber(y);
    }
}
