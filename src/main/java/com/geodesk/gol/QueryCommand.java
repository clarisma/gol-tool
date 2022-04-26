package com.geodesk.gol;

import com.clarisma.common.cli.BasicCommand;
import com.clarisma.common.cli.Option;
import com.clarisma.common.cli.Parameter;
import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.text.Format;
import com.geodesk.feature.Feature;
import com.geodesk.feature.FeatureStore;
import com.geodesk.core.Box;
import com.geodesk.gol.build.Utils;

import java.io.PrintWriter;
import java.nio.file.Path;

public class QueryCommand extends BasicCommand
{
    private Box bbox = Box.ofWorld();
    private Path golPath;
    private String query;
    private String[] tags;

    @Option("format,f=csv|xml|geojson|...: output format")
    protected ResultFormat format = ResultFormat.LIST;

    private enum ResultFormat
    {
        LIST, CSV, GEOJSON, XML, COUNT;
    }

    @Option("new,n: create GOL if it does not exist")
    protected boolean createIfMissing;

    @Option("limit,l=number: maximum number of features to return")
    protected long limit = Long.MAX_VALUE;

    @Parameter("0=gol")
    public void library(String filename)
    {
        golPath = Utils.golPath(filename);
    }

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

    @Override public int perform() throws Exception
    {
        FeatureStore features = new FeatureStore(golPath);
        long start = System.currentTimeMillis();
        long count = 0;

        /*
        if(verbosity >= Verbosity.NORMAL)
        {
            System.err.println("file.encoding: " +
                System.getProperty("file.encoding"));
        }
         */

        FeaturePrinter printer = switch(format)
        {
            case LIST -> new ListFeaturePrinter();
            case CSV -> new CsvFeaturePrinter();
            case GEOJSON -> new GeoJsonFeaturePrinter();
            default -> new NullFeaturePrinter();
        };
        printer.useKeys(tags);

        PrintWriter out = new PrintWriter(System.out);
        /*
        PrintWriter out = new PrintWriter(new PrintStream(System.out, false,
            StandardCharsets.UTF_8));
         */
        /*
        PrintWriter out = new PrintWriter(
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
         */

        printer.printHeader(out);
        for(Feature f: features.features(query).in(bbox))
        {
            printer.print(out, f);
            out.flush();
            count++;
            if(count == limit) break;
        }
        printer.printFooter(out);
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
        return 0;
    }
}
