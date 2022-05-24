package com.clarisma.common.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestCommand extends BasicCommand
{
    @Option
    boolean x;

    @Option
    String a;

    @Option("verbosity-level=valueXXX")
    boolean verbose;

    @Override public int perform()
    {
        System.out.format("Test a=%s x=%s verbosity=%d\n", a, x, verbosity);
        return 0;
    }

    public static void main(String[] args) throws Exception
    {
        long start = System.currentTimeMillis();
        Application app = new Application()
        {
            @Override public String version() { return "test"; }
        };
        app.run(new TestCommand(), args);
        System.out.format("Elapsed time: %d ms", System.currentTimeMillis() - start);
    }
}
