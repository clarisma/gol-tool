/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.clarisma.common.text.Table;
import com.geodesk.feature.Feature;
import com.geodesk.feature.Relation;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

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
    private double totalTally;
    private long totalRelationCount;
    private MutableObjectIntMap<String> currentRoles;
    private long currentRelationId;
    private TallyMode tallyMode = TallyMode.COUNT;

    private enum TallyMode
    {
        COUNT, LENGTH, AREA, ROLES;
    }

    @Override public boolean setOption(String name, String value)
    {
        switch(name)
        {
        case "min-tally":
            checkValue(value);
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
        case "tally":
            tallyMode = getValue(value, TallyMode.class);
            return true;
        }
        return false;
    }

    private static class Counter implements Comparable<Counter>
    {
        String[] tags;
        double tally;
        long relCount;
        MutableLongSet relations;

        @Override public int compareTo(Counter other)
        {
            return Double.compare(other.tally, tally);
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
        if(tallyMode == TallyMode.ROLES)
        {
            // If no tags are specified on the command line,
            // a single column with "*" is created; get rid of that column
            // and just use a single non-tag column (the one for the role)

            if(columns.size() > 1 || !columns.get(0).key.equals("*"))
            {
                columnCount++;
            }
            else
            {
                columns.clear();
            }
            currentRoles = new ObjectIntHashMap<>();
        }
        key.tags = new String[columnCount];
    }

    private Counter addToCounter(double tally)
    {
        Counter counter = counters.get(key);
        if(counter == null)
        {
            counter = key.copy();
            counters.put(counter, counter);
        }
        counter.tally += tally;
        return counter;
    }

    private void tallyRoles(int n)
    {
        currentRoles.forEachKeyValue((role, count) ->
        {
            key.tags[n] = role;
            Counter counter = addToCounter(count);
            counter.relCount++;
            if(minTally > Long.MIN_VALUE || minPercentage > 0)
            {
                if(counter.relations == null) counter.relations = new LongHashSet();
                counter.relations.add(currentRelationId);
            }
        });
    }

    private void tally(int n, double tally)
    {
        if(n == columns.size())
        {
            tallyRoles(n);
            return;
        }
        String value = columns.get(n).value;
        if(value == null) value = "-";
        if(splitValues && value.indexOf(';') >= 0)
        {
            for(String valuePart : value.split(";"))
            {
                key.tags[n] = valuePart.trim();
                if (n + 1 < columnCount)
                {
                    tally(n + 1, tally);
                }
                else
                {
                    addToCounter(tally);
                }
            }
            return;
        }
        key.tags[n] = value;
        if (n + 1 < columnCount)
        {
            tally(n + 1, tally);
        }
        else
        {
            addToCounter(tally);
        }
    }

    private int countRoles(Relation rel)
    {
        int roleCount = 0;
        currentRoles.clear();
        for(Feature m: rel)
        {
            String role = m.role();
            if(role.isEmpty()) role = "(empty)";
            currentRoles.addToValue(role, 1);
            roleCount++;
        }
        return roleCount;
    }

    @Override public void print(Feature feature)
    {
        extractProperties(feature.tags());
        double tally = 0;
        switch(tallyMode)
        {
        case COUNT:
            tally = 1;
            break;
        case LENGTH:
            tally =  feature.length();
            break;
        case AREA:
            tally =  feature.area();
            break;
        case ROLES:
            if(feature instanceof Relation rel)
            {
                currentRelationId = rel.id();
                tally = countRoles(rel);
                totalRelationCount++;
            }
            break;
        }
        tally(0, tally);
        totalTally += tally;
        resetProperties();
    }


    @Override public void printFooter()
    {
        List<Counter> list = new ArrayList<>(counters.values());
        Collections.sort(list);
        double totalOmitted = 0;
        int omittedRowCount = 0;
        MutableLongSet omittedRelations = new LongHashSet();
        int end;
        for(end = list.size(); end > 0; end--)
        {
            Counter c = list.get(end-1);
            double tally = c.tally;
            double percentage = tally / totalTally;
            if(tally >= minTally && percentage >= minPercentage) break;
            totalOmitted += tally;
            omittedRowCount++;
            if(c.relations != null) omittedRelations.addAll(c.relations);
        }
        list = list.subList(0, end);
        if(alphaSort) list.sort(new TagsComparator());

        Table table = new Table();
        for(Column col: columns)
        {
            table.column();
        }
        if(tallyMode == TallyMode.ROLES) table.column();
        String numberSchema = "###,###,###,###";
        switch(tallyMode)
        {
        case LENGTH:
            numberSchema += " m";
            break;
        case AREA:
            numberSchema += " m²";
            break;
        case ROLES:
            table.column().format(numberSchema + " in").gap(1);
            break;
        }
        table.column().format(numberSchema);
        table.column().format("##0.0%");
        for(Column col: columns)
        {
            table.add(col.key);
        }
        if(tallyMode == TallyMode.ROLES) table.add("role");
        table.divider("=");

        for(Counter c: list)
        {
            for(String tag: c.tags)
            {
                table.add(tag);
            }
            table.add(c.tally);
            if(tallyMode == TallyMode.ROLES) table.add(c.relCount);
            table.add(c.tally / totalTally);
        }
        if(totalOmitted > 0)
        {
            table.add(String.format("(%,d other%s)", omittedRowCount,
                omittedRowCount == 1 ? "" : "s"));
            for(int i=1; i<columnCount; i++) table.add("");
            table.add(totalOmitted);
            if(tallyMode == TallyMode.ROLES) table.add(omittedRelations.size());
            table.add(totalOmitted / totalTally);
        }

        table.divider("-");
        table.add("Total");
        for(int i=1; i<columnCount; i++) table.add("");
        table.add(totalTally);
        if(tallyMode == TallyMode.ROLES) table.add(totalRelationCount);
        table.add(1);

        out.print(table);
    }
}
