package com.geodesk.gol;

import com.clarisma.common.text.Strings;
import com.geodesk.feature.Feature;
import com.geodesk.feature.Tags;
import com.geodesk.geom.Bounds;
import org.locationtech.jts.geom.*;

import java.io.PrintStream;
import java.io.PrintWriter;

// TODO: remember, polygons must have proper winding order!

public class GeoJsonFeaturePrinter extends AbstractFeaturePrinter
{
    private boolean perLine = false;
    private boolean firstFeature = true;

    public GeoJsonFeaturePrinter(PrintStream out, boolean perLine)
    {
        super(out);
        this.perLine = perLine;
    }

    /*
    public void perLine(boolean enabled)
    {
        perLine = enabled;
    }
     */

    @Override public void printHeader()
    {
        if(perLine) return;
        out.println("{");
        out.println("\t\"type\": \"FeatureCollection\",");
        out.print("\t\"generator\": \"geodesk gol/");
        out.print(GolTool.VERSION);
        out.println("\",\n\t\"features\": [");
    }

    protected void printLineString(LineString g)
    {
        CoordinateSequence seq = g.getCoordinateSequence();
        out.print('[');
        for(int i=0; i<seq.size(); i++)
        {
            if(i>0) out.print(',');
            out.print('[');
            printX(seq.getOrdinate(i, 0));
            out.print(',');
            printY(seq.getOrdinate(i, 1));
            out.print(']');
        }
        out.print(']');
    }

    protected void printPolygon(Polygon g)
    {
        out.print('[');
        printLineString(g.getExteriorRing());
        for(int i=0; i<g.getNumInteriorRing(); i++)
        {
            out.print(',');
            printLineString(g.getInteriorRingN(i));
        }
        out.print(']');
    }


    protected void printGeometry(Geometry g)
    {
        out.print("\"geometry\":");
        if(!perLine) out.print(" ");
        out.print("{\"type\":");
        if(g instanceof Point)
        {
            out.print("\"Point\",\"coordinates\":[");
            Point pt= (Point)g;
            printX(pt.getX());
            out.print(',');
            printY(pt.getY());
            out.print(']');
        }
        if(g instanceof LineString)
        {
            out.print("\"LineString\",\"coordinates\":");
            printLineString((LineString)g);
        }
        else if(g instanceof Polygon)
        {
            out.print("\"Polygon\",\"coordinates\":");
            printPolygon((Polygon)g);
        }
        else if(g instanceof MultiPolygon)
        {
            out.print("\"MultiPolygon\",\"coordinates\":[");
            for(int i=0; i<g.getNumGeometries(); i++)
            {
                if(i > 0) out.print(',');
                printPolygon((Polygon)g.getGeometryN(i));
            }
            out.print(']');
        }
        else if(g instanceof GeometryCollection)
        {
            out.print("\"GeometryCollection\",\"geometries\":[");
            for(int i=0; i< g.getNumGeometries(); i++)
            {
                if(i > 0) out.print(',');
                printGeometry(g.getGeometryN(i));
            }
            out.print(']');
        }
        out.print('}');
    }

    // TODO: should string escaping happen in baseclass?

    @Override protected void printProperty(String key, String value)
    {
        if(perLine)
        {
            out.print(propertyNumber > 0 ? ",\"" : "\"");
            out.print(key);
            out.print("\":\"");
            out.print(Strings.escape(value));
            out.print('\"');
            return;
        }
        if(propertyNumber > 0) out.print(",\n");
        out.print("\t\t\t\t\"");
        out.print(key);
        out.print("\": \"");
        out.print(Strings.escape(value));
        out.print('\"');
    }

    private void printBBox(Bounds bbox)
    {
        out.print(perLine ? "\"bbox\":[" : "\"bbox\": [");
        printX(bbox.minX());
        out.print(',');
        printY(bbox.minY());
        out.print(',');
        printX(bbox.maxX());
        out.print(',');
        printY(bbox.maxY());
        out.print("],");
    }

    @Override public void print(Feature feature)
    {
        if(perLine)
        {
            out.print("{\"type\": \"Feature\",");
            if(bboxColumn != null) printBBox(feature.bounds());
            printGeometry(feature.toGeometry());
            out.print(",");
            extractProperties(feature.tags());
            out.print("\"properties\":{");
            printProperties();
            out.println("}}");
            return;
        }
        if(!firstFeature) out.println("\t\t},");
        out.println("\t\t{");
        out.println("\t\t\t\"type\": \"Feature\",");
        if(bboxColumn != null)
        {
            out.print("\t\t\t");
            printBBox(feature.bounds());
            out.println();
        }
        out.print("\t\t\t");
        printGeometry(feature.toGeometry());
        out.println(",");
        extractProperties(feature.tags());

        out.println("\t\t\t\"properties\": {");
        printProperties();
        out.println();
        out.println("\t\t\t}");
        firstFeature = false;
    }

    @Override public void printFooter()
    {
        if(perLine) return;
        if(!firstFeature) out.println("\t\t}");
        out.println("\t]");
        out.println("}");
    }
}
