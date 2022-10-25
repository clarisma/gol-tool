package com.geodesk.gol.query;

import com.geodesk.feature.Feature;

import java.io.PrintStream;

public class FabFeaturePrinter extends AbstractFeaturePrinter
{
    public FabFeaturePrinter(PrintStream out)
    {
        super(out);
    }

    @Override protected void printProperty(String key, String value)
    {
        out.print('\t');
        out.print(key);
        out.print(": ");
        out.println(value);       // TODO: escape
    }

    @Override public void print(Feature feature)
    {
        switch(feature.type())
        {
        case NODE:
            out.print("node/");
            break;
        case WAY:
            out.print("way/");
            break;
        case RELATION:
            out.print("relation/");
            break;
        }
        out.print(feature.id());
        out.println(':');
        extractProperties(feature.tags());
        printProperties();
        out.println();
    }

}
