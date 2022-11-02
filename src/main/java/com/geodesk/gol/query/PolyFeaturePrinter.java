/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

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
        writer = new PolyWriter(out, transformer);
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
