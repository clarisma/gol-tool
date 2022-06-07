package com.geodesk.gol;

import com.clarisma.common.cli.Command;
import com.clarisma.common.cli.Parameter;
import com.clarisma.common.cli.Verbosity;

import java.awt.*;
import java.net.URI;

public class HelpCommand implements Command
{
    @Parameter("0=command")
    protected String command;

    @Override public int perform() throws Exception
    {
        Desktop.getDesktop().browse(new URI("https://docs.geodesk.com/gol/" + command));
        return 0;
    }

    @Override public int error(Throwable ex)
    {
        return ErrorReporter.report(ex, Verbosity.NORMAL);
    }
}
