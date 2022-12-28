/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import java.util.HashSet;
import java.util.Set;

public class TagFilter
{
    public static final int ACCEPT_ALL = 0;
    public static final int ACCEPT_SOME = 1;
    public static final int REJECT_SOME = -1;
    public static final String[] NONE = new String[0];

    private final Set<String> exact = new HashSet<>();
    //private final String[] prefixes;
    //private final String[] suffixes;


    public static class Clause
    {
        private final int accept;
        private final Set<String> exact;
        private final String[] prefixes;
        private final String[] suffixes;

        Clause(int accept, Set<String> exact, String[] prefixes, String[] suffixes)
        {
            this.accept = accept;
            this.exact = exact;
            this.prefixes = prefixes;
            this.suffixes = suffixes;
        }
    }
}
