package com.geodesk.gol;

import com.geodesk.feature.Feature;
import com.geodesk.feature.Tags;
import com.geodesk.feature.Way;
import com.geodesk.feature.store.StoredWay;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

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
