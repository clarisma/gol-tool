/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.Option;
import com.clarisma.common.cli.Parameter;
import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.text.Format;
import com.geodesk.feature.Feature;
import com.geodesk.feature.Features;
import com.geodesk.feature.Filters;
import com.geodesk.gol.query.*;
import com.geodesk.util.CoordinateTransformer;

import java.io.PrintStream;

public class QueryCommand extends GolCommand
{
    // private Box bbox = Box.ofWorld();
    private String query;
    private String[] tags;

    @Option("format,f=csv|xml|geojson|...: output format")
    protected ResultFormat format = ResultFormat.LIST;

    protected int precision = 6;

    @Option("precision=0-15: coordinate precision")
    public void precision(int v)
    {
        if(v<0 || v > 15) throw new IllegalArgumentException("Must be between 0 and 15");
        precision = v;
    }

    private enum ResultFormat
    {
        LIST, CSV, FAB, GEOJSON, GEOJSONL, XML, WKT, COUNT, MAP, POLY;
    }

    @Option("limit,l=number: maximum number of features to return")
    protected long limit = Long.MAX_VALUE;

    @Parameter("1=query")
    public void query(String... args)
    {
        query = String.join(" ", args);
    }

    /*
    @Option("bbox,b=W,S,E,N: bounding box")
    public void bounds(String bounds)
    {
        bbox = Box.fromWSEN(bounds);
    }
*/

    @Option("tags,t=keys: keys of tags to include")
    public void tags(String s)
    {
        tags = s.split(",");
    }

    public void format(String s)
    {

    }

    @Override public void performWithLibrary()
    {
        long start = System.currentTimeMillis();
        long count = 0;

        PrintStream out = System.out;
        AbstractFeaturePrinter printer = switch(format)
        {
            case LIST -> new ListFeaturePrinter(out);
            case CSV -> new CsvFeaturePrinter(out);
            case FAB -> new FabFeaturePrinter(out);
            case GEOJSON -> new GeoJsonFeaturePrinter(out, false);
            case GEOJSONL -> new GeoJsonFeaturePrinter(out, true);
            case MAP -> new MapFeaturePrinter(out);
            case POLY -> new PolyFeaturePrinter(out);
            case WKT -> new WktFeaturePrinter(out);
            case XML -> new OsmXmlFeaturePrinter(out);
            default -> new NullFeaturePrinter();
        };
        printer.coordinateTransformer(new CoordinateTransformer.FromMercator(precision));
        printer.columns(tags);

        printer.printHeader();
        Features<?> selected = features.select(query);
        if(area != null)
        {
            selected = selected.select(Filters.intersects(area));
        }
        else if(bbox != null)
        {
            selected = selected.in(bbox);
        }
        for(Feature f: selected)
        {
            printer.print(f);
            // out.flush();
            count++;
            if(count == limit) break;
        }
        printer.printFooter();
        out.flush();

        if(format == ResultFormat.COUNT)
        {
            System.out.println(count);
        }
        if(verbosity >= Verbosity.NORMAL)
        {
            System.err.format("\nRetrieved %,d features in %s\n", count,
                Format.formatTimespan(System.currentTimeMillis() - start));
        }
    }
}
