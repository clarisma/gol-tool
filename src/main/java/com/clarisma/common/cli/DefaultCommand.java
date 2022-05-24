package com.clarisma.common.cli;

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
        return 0;
    }
}
