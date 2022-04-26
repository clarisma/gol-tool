package com.clarisma.common.cli;

import java.util.List;
import java.util.Map;

public abstract class Command_old implements Runnable
{
    private final int outputLevel;

    public static final int OUTPUT_SILENT = -2;
    public static final int OUTPUT_QUIET = -1;
    public static final int OUTPUT_NORMAL = 0;
    public static final int OUTPUT_VERBOSE = 1;
    public static final int OUTPUT_DEBUG = 2;

    public Command_old(List<String> arguments, Map<String,Object> options)
    {
        int outputLevel = Integer.MIN_VALUE;
        for(String option: options.keySet())
        {
            int level = Integer.MIN_VALUE;
            switch(option)
            {
            case "silent":
                level = OUTPUT_SILENT;
                break;
            case "quiet":
                level = OUTPUT_QUIET;
                break;
            case "verbose":
                level = OUTPUT_VERBOSE;
                break;
            case "debug":
                level = OUTPUT_DEBUG;
                break;
            }
            if(level > outputLevel) outputLevel = level;
        }
        this.outputLevel = outputLevel;
    }

    public int outputLevel()
    {
        return outputLevel;
    }
}
