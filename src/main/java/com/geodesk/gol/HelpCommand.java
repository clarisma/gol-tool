/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.BasicCommand;
import com.clarisma.common.cli.Command;
import com.clarisma.common.cli.Parameter;
import com.clarisma.common.cli.Verbosity;

import java.awt.*;
import java.net.URI;

public class HelpCommand extends BasicCommand
{
    @Parameter("0=?command")
    protected String command;

    @Override public int perform() throws Exception
    {
        String url = "https://docs1.geodesk.com/gol";
        if(command != null) url += "/" + command;
        Desktop.getDesktop().browse(new URI(url));
        return 0;
    }

    @Override public int error(Throwable ex)
    {
        return ErrorReporter.report(ex, Verbosity.NORMAL);
    }
}
