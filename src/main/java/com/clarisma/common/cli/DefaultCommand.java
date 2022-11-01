/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.cli;

import com.geodesk.gol.ErrorReporter;

public class DefaultCommand extends BasicCommand
{
    protected final Application app;

    @Option("help,?")
    boolean helpRequested;

    @Option("version,V")
    boolean versionRequested;

    public DefaultCommand(Application app)
    {
        this.app = app;
    }

    @Override public int perform() throws Exception
    {
        if(versionRequested)
        {
            System.err.println(app.version());
        }
        else
        {
            System.err.println(app.description());
        }
        return 0;
    }

    // TODO: ErrorReporter is specific to GOL; method should have more general behavior
    @Override public int error(Throwable ex)
    {
        return ErrorReporter.report(ex, verbosity);
    }
}
