/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.clarisma.common.text.Strings;
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
        out.println(Strings.cleanString(value));
            // TODO: check escaping rules
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
