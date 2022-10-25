package com.geodesk.gol.query;

import com.geodesk.feature.Feature;

import java.io.PrintStream;

public class WktFeaturePrinter extends AbstractFeaturePrinter
{
    private boolean firstFeature = true;

    public WktFeaturePrinter(PrintStream out)
    {
        super(out);
    }

    @Override public void printHeader()
    {
        out.println("GEOMETRYCOLLECTION (");
    }

    @Override public void print(Feature feature)
    {
        if(!firstFeature) out.print(",\n");
        // out.print("\t");
        out.print(feature.toGeometry());
        firstFeature = false;
    }

    @Override public void printFooter()
    {
        out.println(")");
    }
}
