package com.clarisma.common.validation;

import com.clarisma.common.util.FileLocation;

public class Problem implements FileLocation, Comparable<Problem>
{
    private final String msg;
    private final Severity severity;
    private final String file;
    private final int line;
    private final int column;

    public Problem(String msg, Severity severity, String file, int line, int column)
    {
        this.msg = msg;
        this.severity = severity;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    @Override public String getFile()
    {
        return file;
    }

    @Override public int getLine()
    {
        return line;
    }

    @Override public int getColumn()
    {
        return column;
    }

    // TODO
    @Override public int compareTo(Problem other)
    {
        int rank = severity.ordinal();
        int otherRank = other.severity.ordinal();
        if(rank < otherRank) return -1;
        if(otherRank > rank) return 1;
        return 0;
    }
}
