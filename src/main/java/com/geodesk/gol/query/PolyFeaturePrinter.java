package com.geodesk.gol.query;

import com.geodesk.feature.Feature;
import com.geodesk.io.PolyWriter;
import com.geodesk.util.CoordinateTransformer;
import org.locationtech.jts.geom.Geometry;

import java.io.IOException;
import java.io.PrintStream;

public class PolyFeaturePrinter extends AbstractFeaturePrinter
{
    private PolyWriter writer;

    public PolyFeaturePrinter(PrintStream out)
    {
        super(out);
    }

    @Override public void printHeader()
    {
        writer = new PolyWriter(out, new CoordinateTransformer.FromMercator(6));
            // TODO: configurable precision
        out.append("from_query\n");
            // TODO: What should the name be? Make it configurable?
    }

    @Override public void print(Feature feature)
    {
        try
        {
            writer.write(feature.toGeometry());
        }
        catch(IOException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    @Override public void printFooter()
    {
        out.append("END\n");
    }
}
