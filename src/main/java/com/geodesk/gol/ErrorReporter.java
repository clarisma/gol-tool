package com.geodesk.gol;

import com.clarisma.common.cli.Verbosity;
import com.geodesk.feature.match.QueryException;

import java.io.IOException;

public class ErrorReporter
{
    public static final int BAD_ARGUMENTS = 2;
    public static final int IO_ERROR = 4;
    public static final int OUT_OF_MEMORY = 10;
    public static final int INTERNAL_ERROR = 127;

    public static int report(Throwable ex, int verbosity)
    {
        String msg;
        int result;
        boolean trace = false;

        if(ex instanceof OutOfMemoryError)
        {
            msg = "Out of memory.\n" +
                "Increase heap space settings or simplify command, then try again.";
            result = OUT_OF_MEMORY;
            trace = true;
        }
        else if(
            ex instanceof IllegalArgumentException ||
            ex instanceof QueryException)   // TODO: may change
        {
            msg = ex.getMessage();
            result = BAD_ARGUMENTS;
        }
        else if(ex instanceof IOException)
        {
            msg = ex.getMessage();
            result = IO_ERROR;
        }
        else
        {
            msg = String.format("Internal error: %s", ex.getMessage());
            trace = true;
            result = INTERNAL_ERROR;
        }
        if(verbosity > Verbosity.SILENT)
        {
            System.err.println(msg);
            if(trace) ex.printStackTrace();
        }
        return result;
    }
}
