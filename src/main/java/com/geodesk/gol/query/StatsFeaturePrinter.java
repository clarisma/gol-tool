/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.geodesk.feature.Feature;

import java.io.PrintStream;
import java.util.*;

public class StatsFeaturePrinter extends AbstractFeaturePrinter
{
    private final Map<String, Counter> counters = new HashMap<>();

    private static class Counter implements Comparable<Counter>
    {
        String perm;
        long count;

        @Override public int compareTo(Counter other)
        {
            return Long.compare(other.count, count);
        }
    }

    public StatsFeaturePrinter(PrintStream out)
    {
        super(out);
    }

    @Override public void print(Feature feature)
    {
        extractProperties(feature.tags());
        StringBuilder buf = new StringBuilder();
        for (Column col : columns)
        {
            String value = col.value;
            buf.append(value == null ? "---" : value);
            buf.append('\t');
        }
        String perm = buf.toString();
        Counter counter = counters.get(perm);
        if (counter == null)
        {
            counter = new Counter();
            counter.perm = perm;
            counters.put(perm, counter);
        }
        counter.count++;
        resetProperties();
    }

    @Override public void printFooter()
    {
        List<Counter> list = new ArrayList<>(counters.values());
        Collections.sort(list);
        for(Counter c: list)
        {
            out.append(c.perm);
            out.append(Long.toString(c.count));
            out.append("\n");
        }
    }
}
