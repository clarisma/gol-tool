/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

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
