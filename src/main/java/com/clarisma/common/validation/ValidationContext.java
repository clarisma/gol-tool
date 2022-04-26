package com.clarisma.common.validation;

import java.util.ArrayList;
import java.util.List;

public class ValidationContext
{
    private String file;
    private int line;
    private int column;
    private final List<Problem> problems = new ArrayList<>();
    private boolean hasErrors;

    public void addError(String msg)
    {
        problems.add(new Problem(msg, Severity.ERROR, file, line, column));
        hasErrors = true;
    }

    public void addWarning(String msg)
    {
        problems.add(new Problem(msg, Severity.WARNING, file, line, column));
    }

    public boolean hasErrors()
    {
        return hasErrors;
    }
}
