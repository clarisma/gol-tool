package com.geodesk.gol;

import com.clarisma.common.cli.Application;
import com.clarisma.common.cli.Command;
import com.clarisma.common.cli.DefaultCommand;
import com.clarisma.common.util.Log;

import java.io.PrintWriter;

public class GolTool extends Application
{
    public static final String VERSION = "0.0.1";

    @Override public String version()
    {
        return "gol " + VERSION;
    }

    @Override public String description()
    {
        return
            "gol - Build, manage and query Geographic Object Libraries\n\n" +
            "Usage: gol <command> [options]\n\n" +
            "Commands:\n\n" +
            "  build - Create a GOL from an OSM data file\n" +
            "  query - Perform a GOQL query\n" +
            "  save  - Export tiles\n" +
            "  check - Verify integrity\n" +
            "\n" +
            "Use \"gol help <command>\" for detailed documentation.";
    }

    /*
    @Override protected Command defaultCommand()
    {
        return new DefaultCommand(this);
    }
     */

    public static void main(String[] args) throws Exception
    {
        // for(String arg: args) Log.debug("Arg: '%s'", arg);
        /*
        PrintWriter out = new PrintWriter(System.out);
        out.println("Bavière");
        out.flush();
         */
        GolTool app = new GolTool();
        System.exit(app.run(null, args));
    }
}
