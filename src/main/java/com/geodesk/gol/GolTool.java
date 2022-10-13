package com.geodesk.gol;

import com.clarisma.common.cli.Application;
import com.clarisma.common.util.Log;

import java.io.PrintWriter;

public class GolTool extends Application
{
    public static final String VERSION = "0.1.1";

    @Override public String version()
    {
        return "gol " + VERSION;
    }

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
