/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

public class Options
{
    public static double parseDouble(String s)
    {
        try
        {
            return Double.parseDouble(s);
        }
        catch(NumberFormatException ex)
        {
            throw new IllegalArgumentException(String.format(
                "Must be a number instead of \"%s\"", s));
        }
    }

    public static double parsePercentage(String s)
    {
        if(s.endsWith("%"))
        {
            return parseDouble(s.substring(0, s.length() - 1)) / 100;
        }
        return parseDouble(s);
    }
}
