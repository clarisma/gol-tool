/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.cli;

/**
 * A {@link Command} base class that supports setting of verbosity levels
 * (silent, quiet, verbose, debug)
 */
public abstract class BasicCommand implements Command
{
    protected int verbosity;
    public void verbosity(int level)
    {
        verbosity = level;
    }

    @Option("silent,s: Suppress all output")
    public void silent()
    {
        verbosity(Verbosity.SILENT);
    }

    @Option("quiet,q: Minimal output")
    public void quiet()
    {
        verbosity(Verbosity.QUIET);
    }

    @Option("verbose,v: Display additional output")
    public void verbose()
    {
        verbosity(Verbosity.VERBOSE);
    }

    @Option("debug,d: Display debug information")
    public void debug()
    {
        verbosity(Verbosity.DEBUG);
    }
}
