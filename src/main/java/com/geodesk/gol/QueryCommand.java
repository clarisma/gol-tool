package com.geodesk.gol;

import com.clarisma.common.cli.BasicCommand;
import com.clarisma.common.cli.Option;
import com.clarisma.common.cli.Parameter;
import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.text.Format;
import com.geodesk.feature.Feature;
import com.geodesk.core.Box;
import com.geodesk.feature.FeatureLibrary;
import com.geodesk.gol.build.Utils;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Path;

public class QueryCommand extends GolCommand
{
    private Box bbox = Box.ofWorld();
    // private Path golPath;
    private String query;
    private String[] tags;

    @Option("format,f=csv|xml|geojson|...: output format")
    protected ResultFormat format = ResultFormat.LIST;

    private enum ResultFormat
    {
        LIST, CSV, FAB, GEOJSON, XML, WKT, COUNT;
    }

    /*
    @Option("new,n: create GOL if it does not exist")
    protected boolean createIfMissing;
     */

    @Option("limit,l=number: maximum number of features to return")
    protected long limit = Long.MAX_VALUE;

    /*
    @Parameter("0=gol")
    public void library(String filename)
    {
        golPath = Utils.golPath(filename);
    }
    */

    @Parameter("1=query")
    public void query(String... args)
    {
        query = String.join(" ", args);
    }

    @Option("bbox,b=W,S,E,N: bounding box")
    public void bounds(String bounds)
    {
        bbox = Box.fromWSEN(bounds);
    }

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
        // FeatureLibrary features = new FeatureLibrary(golPath.toString());
        long start = System.currentTimeMillis();
        long count = 0;

        /*
        if(verbosity >= Verbosity.NORMAL)
        {
            System.err.println("file.encoding: " +
                System.getProperty("file.encoding"));
        }
         */

        PrintStream out = System.out;
        AbstractFeaturePrinter printer = switch(format)
        {
            case LIST -> new ListFeaturePrinter(out);
            case CSV -> new CsvFeaturePrinter(out);
            case GEOJSON -> new GeoJsonFeaturePrinter(out);
            case WKT -> new WktFeaturePrinter(out);
            case FAB -> new FabFeaturePrinter(out);
            default -> new NullFeaturePrinter();
        };
        printer.columns(tags);


        /*
        PrintWriter out = new PrintWriter(new PrintStream(System.out, false,
            StandardCharsets.UTF_8));
         */
        /*
        PrintWriter out = new PrintWriter(
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
         */

        printer.printHeader();
        for(Feature f: features.features(query).in(bbox))
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
            System.err.format("\nRetrieved %d features in %s\n", count,
                Format.formatTimespan(System.currentTimeMillis() - start));
        }
        features.close();
    }
}
