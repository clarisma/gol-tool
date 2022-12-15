/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

public class Formatting
{
    // We don't build a set of these since they are only used once,
    // to check whether an option exists at all if a particular Formatter
    // didn't accept it, so we can generate an appropriate error message.

    public static final String[] OPTIONS =
    {
        "attribution", "basemap", "color", "id", "link", "min-tally",
        "osm", "sort", "split-values", "tally"
    };

    public static boolean containsOption(String opt)
    {
        for(String valid: OPTIONS)
        {
            if (opt.equals(valid)) return true;
        }
        return false;
    }
}
