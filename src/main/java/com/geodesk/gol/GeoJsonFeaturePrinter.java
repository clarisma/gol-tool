package com.geodesk.gol;

import com.geodesk.feature.Feature;
import com.geodesk.feature.Tags;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.io.PrintWriter;

public class GeoJsonFeaturePrinter implements FeaturePrinter
{
    private boolean firstFeature = true;

    @Override public void printHeader(PrintWriter out)
    {
        out.println("{");
        out.println("\t\"type\": \"FeatureCollection\",");
        out.println("\t\"generator\": \"geodesk gol/0.1.0\",");     // TODO: version
        out.println("\t\"features\": [");
    }

    protected void printGeometry(PrintWriter out, Geometry geom)
    {
        out.println("\t\t\t\"geometry\": {");
        out.print("\t\t\t\t\"type\": \"");
        if(geom instanceof Point)
        {
            out.println("Point\",");
        }

        // TODO

        out.println("\t\t\t},");
    }

    @Override public void print(PrintWriter out, Feature feature)
    {
        if(!firstFeature) out.println("\t\t},");
        out.println("\t\t{");
        out.println("\t\t\t\"type\": \"Feature\",");

        printGeometry(out, feature.toGeometry());

        out.println("\t\t\t\"properties\": {");
        Tags tags = feature.tags();
        boolean firstTag = true;
        while(tags.next())
        {
            if(!firstTag)
            {
                out.println("\",");
            }
            else
            {
                firstTag = false;
            }
            String key = tags.key();
            out.print("\t\t\t\t\"");
            out.print(key);
            out.print("\": \"");
            out.print(tags.value());
        }
        out.println("\"");
        out.println("\t\t\t}");
        firstFeature = false;
    }

    @Override public void printFooter(PrintWriter out)
    {
        if(!firstFeature) out.println("\t\t}");
        out.println("\t]");
        out.println("}");
    }
}
