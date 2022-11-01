/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

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
