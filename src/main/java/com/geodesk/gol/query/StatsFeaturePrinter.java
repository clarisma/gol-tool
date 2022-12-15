/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.clarisma.common.text.Table;
import com.clarisma.common.util.Log;
import com.geodesk.feature.Feature;

import java.io.PrintStream;
import java.util.*;

public class StatsFeaturePrinter extends AbstractFeaturePrinter
{
    private final Map<Object, Counter> counters = new HashMap<>();
    private int columnCount;
    private final Counter key = new Counter();
    private boolean splitValues = false;
    private long minTally = Long.MIN_VALUE;
    private double minPercentage = 0;
    private boolean alphaSort;
    private long totalCount;

    private static enum TallyMode
    {
        COUNT, LENGTH, AREA, ROLES;
    }

    @Override public boolean setOption(String name, String value)
    {
        switch(name)
        {
        case "min-tally":
            if(value == null || value.isEmpty())
            {
                throw new IllegalArgumentException("Must provide a value");
            }
            if(value.endsWith("%"))
            {
                minPercentage = Options.parsePercentage(value);
                return true;
            }
            minTally = Math.round(Options.parseDouble(value));
            return true;
        case "sort":
            // TODO: value?
            alphaSort = true;
            return true;
        case "split-values":
            // TODO: value must be null, yes or no
            splitValues = true;
            return true;
        }
        return false;
    }

    private static class Counter implements Comparable<Counter>
    {
        String[] tags;
        long count;
        long relCount;

        @Override public int compareTo(Counter other)
        {
            return Long.compare(other.count, count);
        }
        @Override public boolean equals(Object o)
        {
            if(o instanceof Counter other)
            {
                return Arrays.equals(tags, other.tags);
            }
            return false;
        }

        @Override public int hashCode()
        {
            return Arrays.hashCode(tags);
        }

        Counter copy()
        {
            Counter copy = new Counter();
            copy.tags = Arrays.copyOf(tags, tags.length);
            return copy;
        }
    }

    private static class TagsComparator implements Comparator<Counter>
    {
        @Override public int compare(Counter a, Counter b)
        {
            int tagCount = a.tags.length;
            for(int i=0; i<tagCount; i++)
            {
                int comp = a.tags[i].compareTo(b.tags[i]);
                if (comp != 0) return comp;
            }
            return 0;
                // TODO: In reality, this is an error, as tag permutations
                //  must be unique
        }
    }

    public StatsFeaturePrinter(PrintStream out)
    {
        super(out);
    }

    @Override public void printHeader()
    {
        columnCount = columns.size();
        key.tags = new String[columnCount];
    }

    private void addToCounter()
    {
        Counter counter = counters.get(key);
        if(counter == null)
        {
            counter = key.copy();
            counters.put(counter, counter);
        }
        counter.count++;
    }

    private void tally(int n)
    {
        String value = columns.get(n).value;
        if(value == null) value = "-";
        if(splitValues && value.indexOf(';') >= 0)
        {
            for(String valuePart : value.split(";"))
            {
                key.tags[n] = valuePart.trim();
                if (n + 1 < columnCount)
                {
                    tally(n + 1);
                }
                else
                {
                    addToCounter();
                }
            }
            return;
        }
        key.tags[n] = value;
        if (n + 1 < columnCount)
        {
            tally(n + 1);
        }
        else
        {
            addToCounter();
        }
    }

    @Override public void print(Feature feature)
    {
        extractProperties(feature.tags());
        tally(0);
        totalCount++;
        resetProperties();
    }


    @Override public void printFooter()
    {
        List<Counter> list = new ArrayList<>(counters.values());
        Collections.sort(list);
        long totalOmitted = 0;
        int omittedRowCount = 0;
        int end;
        for(end = list.size(); end > 0; end--)
        {
            Counter c = list.get(end-1);
            long count = c.count;
            double percentage = (double)count / totalCount;
            if(count >= minTally && percentage >= minPercentage) break;
            totalOmitted += count;
            omittedRowCount++;
        }
        list = list.subList(0, end);
        if(alphaSort) list.sort(new TagsComparator());

        Table table = new Table();
        for(Column col: columns)
        {
            table.column();
        }
        table.column().format("###,###,###,###");
        table.column().format("##0.0%");
        for(Column col: columns)
        {
            table.add(col.key);
        }
        table.divider("-");

        for(Counter c: list)
        {
            for(String tag: c.tags)
            {
                table.add(tag);
            }
            table.add(c.count);
            table.add((double)c.count / totalCount);
        }
        if(totalOmitted > 0)
        {
            table.add(String.format("(%d other%s)", omittedRowCount,
                omittedRowCount == 1 ? "" : "s"));
            for(int i=1; i<columnCount; i++) table.add("");
            table.add(totalOmitted);
            table.add((double)totalOmitted / totalCount);
        }
        out.print(table);
    }
}
