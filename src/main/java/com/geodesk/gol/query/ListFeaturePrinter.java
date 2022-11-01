/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.geodesk.feature.Feature;

import java.io.PrintStream;

public class ListFeaturePrinter extends AbstractFeaturePrinter
{
    public ListFeaturePrinter(PrintStream out)
    {
        super(out);
    }
    @Override public void print(Feature feature)
    {
        char letter = switch(feature.type())
        {
        case NODE -> 'N';
        case WAY -> 'W';
        case RELATION -> 'R';
        };
        out.format("%c%d\n", letter, feature.id());
    }
}
