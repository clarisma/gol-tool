/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.collect.Linked;
import com.clarisma.common.text.Format;
import com.clarisma.common.util.Log;
import com.geodesk.core.Mercator;
import com.geodesk.core.Tile;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.Tags;
import com.geodesk.io.osm.Members;
import com.geodesk.io.osm.Nodes;
import com.geodesk.io.osm.OsmPbfReader;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * In this first phase of the import process, we read the entire
 * planet file and gather statistics about it. Specifically:
 *
 *    - the most commonly used strings, and their usage
 *      (key, value, or role), in order to build the
 *      string tables
 *
 *    - the densities of tiles at zoom level 12, so that
 *      we can determine the appropriate tile structure
 *
 *    - overall statistical information, such as the
 *      number of nodes, ways, and relations
 *
 *  Inputs needed:
 *
 *    - Planet file (in .osm.pbf format)
 *
 *  Outputs generated:
 *
 *    - String Summary: A text file that contains a list of strings
 *      in descending order of their total use, with usage broken
 *      out by keys, values, and roles (tab-separated, with header)
 *
 *    - Statistics: A text file with "key: value" pairs of
 *      various counters.
 *
 *    - Tile Densities: A comma-separated text file of the density
 *      (node count) of each tile at zoom level 12. Empty tiles
 *      are omitted.
 *
 *  TODO: Filter strings
 *  TODO: Make formats more uniform? Tab, colon, comma?
 *  TODO: trim strings as we encounter them? Right now, we only trim them
 *    when writing the summary
 */
public class Analyzer extends OsmPbfReader
{
    // private static final Logger log = LogManager.getLogger();
    private final Project project;

    //
    // Global counters for general statistics.
    //
    private long totalBytesProcessed;
    private long totalNodeCount;
    private long totalTaggedNodeCount;
    private long totalWayCount;
    private long totalWayNodeCount;
    private long totalRelationCount;
    private long totalSuperRelationCount;
    private long totalEmptyRelationCount;
    private long totalMemberCount;
    private long totalTagCount;
    private long globalMaxNodeId;
    private long globalMaxWayId;
    private long globalMaxRelationId;
    private int percentageReported;

    //
    // Node counters
    //
    private int[] globalNodesPerTile;

    //
    // String-Table Construction
    //
    private final Map<String, StringCounter> globalStrings = new HashMap<>();

    /**
     * The minimum number of times a string must be used in order
     * for it to remain in the internal string table. As the
     * internal table fills up, we keep increasing this number.
     */
    private int minLocalStringCount = 2;

    /**
     * The counter for the most recently encountered string,
     * forming the head of a linked list of counters.
     *
     * As the internal table fills up, we toss out the strings
     * that are least likely to end up in the final string table.
     * The most obvious indicator is low frequency of occurrence.
     * Since strings are not evenly distributed, we might miss
     * clusters of frequently-used strings that occur after the
     * internal table has already reached capacity. In order to
     * prevent this, we start eliminating low-usage strings that
     * we haven't seen in a long time, in order to allow later
     * arrivals to "catch up."
     */
    private StringCounter mostRecentString;

    /**
     * The next string counter to consider evicting if it does
     * not meet the minimum occurrence count.
     */
    private StringCounter evictionCandidate;

    /**
     * The maximum number of strings to hold in the internal
     * table. Once we reach this threshold, we start culling
     * strings below `minStringcount`, starting with the least
     * recently encountered string.
     */
    private final int maxInternalStringTableSize = 1_000_000;

    /**
     * The minimum number of occurrences a string must have in order
     * to be written to the string summary
     */
    private final int minFinalStringCount = 100;

    /**
     * The maximum number of strings a worker thread will accumulate
     * before handing the set of string counts to the output thread.
     */
    private final int stringBatchSize = 64 * 1024;  // TODO: was 64

    public Analyzer(Project project)
    {
        this.project = project;
    }


    /**
     * A counter that keeps track of how many times a string was used.
     * Counts of key use and value use are broken out separately; the
     * (much rarer) use of a string as a role can be inferred by
     * subtracting `keys` and `value` from `total`, eliminating the
     * need for this field.
     *
     * By default, `StringCounter` objects are sorted in descending
     * order of the string's total use.
     *
     */
    private static class StringCounter extends Linked<StringCounter> implements Comparable<StringCounter>
    {
        String string;
        long keys;
        long values;
        long total;

        public StringCounter(String s)
        {
            string = s;
        }

        public int compareTo(StringCounter other)
        {
            if(total > other.total) return -1;
            if(total < other.total) return 1;
            return 0;
        }
    }

    /**
     * Removes the least-used strings in order to trim the size of the in-memory string table
     * to its limit.
     */
    private void evictLeastUsedString()
    {
        for(;;)
        {
            StringCounter c = evictionCandidate;
            evictionCandidate = evictionCandidate.prev();
            if(c == mostRecentString)
            {
                // TODO: check this approach
                minLocalStringCount++;
                // minLocalStringCount <<= 1;
                // Log.debug("Minimum instance count is now %d", minLocalStringCount);
                continue;
            }
            if(c.total < minLocalStringCount)
            {
                globalStrings.remove(c.string);
                if(minLocalStringCount >= 1000 && c.total > 1)
                {
                    Log.debug("Removed %s with count %d", c.string, c.total);
                }
                c.remove();
                return;
            }
        }
    }

    private void evictStrings()
    {
        double evictionRatio = .1;
        int evictionGoal = (int)(globalStrings.size() * evictionRatio);
        StringCounter c = mostRecentString.prev();
        int evictionCount = 0;
        while(c != mostRecentString)
        {
            StringCounter prev = c.prev();
            if(c.total < minLocalStringCount)
            {
                c.remove();
                globalStrings.remove(c.string);
                evictionCount++;
                if(evictionCount == evictionGoal) break;
            }
            c = prev;
        }
        // log.debug("Evicted {} strings", evictionCount);
        if(evictionCount < evictionGoal)
        {
            minLocalStringCount++; //  <<= 1;
            // Log.debug("Minimum instance count is now %d", minLocalStringCount);
        }
    }


    private boolean addStringCounter(StringCounter counter)
    {
        boolean added = false;
        StringCounter counterOld = globalStrings.get(counter.string);
        if(counterOld == null)
        {
            if(globalStrings.size() == maxInternalStringTableSize)
            {
                evictStrings();
            }
            globalStrings.put(counter.string, counter);
            added = true;
        }
        else
        {
            counterOld.total += counter.total;
            counterOld.keys +=  counter.keys;
            counterOld.values +=  counter.values;
            counter = counterOld;
        }
        if(counter != mostRecentString)
        {
            mostRecentString.prepend(counter);
            mostRecentString = counter;
        }
        return added;
    }

    private static final int COUNT_KEYS = 0;
    private static final int COUNT_VALUES = 1;
    private static final int COUNT_ROLES = 2;

    private class Batch implements Runnable
    {
        StringCounter strings;

        Batch(StringCounter strings)
        {
            this.strings = strings;
        }

        @Override public void run()
        {
            StringCounter first = strings;
            StringCounter c = first;
            for(;;)
            {
                StringCounter next = c.next();
                if(addStringCounter(c))
                {
                    if(first == c)
                    {
                        if (next == first) break;
                        first = next;
                        c = next;
                        continue;
                    }
                }
                // if(next == c) break;
                if(next == first) break;
                c = next;
            }
            // log.debug("Stored batch");
        }
    }

    @Override protected WorkerThread createWorker()
    {
        return new AnalyzerThread();
    }

    private class AnalyzerThread extends WorkerThread
    {
        private long nodeCount;
        private long taggedNodeCount;
        private long wayCount;
        private long wayNodeCount;
        private long relationCount;
        private long superRelationCount;
        private long emptyRelationCount;
        private long memberCount;
        private long tagCount;
        private long maxNodeId;
        private long maxWayId;
        private long maxRelationId;
        private final Map<String,StringCounter> strings = new HashMap<>(stringBatchSize);
        private StringCounter counters;
        private final MutableIntIntMap nodesPerTile = new IntIntHashMap();

        AnalyzerThread()
        {
            newBatch();
        }

        private void newBatch()
        {
            strings.clear();
            counters = null;
        }

        private void killWeakestStrings()
        {
            final int minOccurrence = 3;
            int killCount = 0;
            StringCounter c = counters;
            for(;;)
            {
                StringCounter next = c.next();
                if(c.total < minOccurrence)
                {
                    killCount++;
                    c.remove();
                    if(c == counters)
                    {
                        if(next == counters) break;
                        counters = next;
                        c = next;
                        continue;
                    }
                }
                if(next == counters) break;
                c = next;
            }
            // log.debug("Killed off {} of {} strings", killCount, strings.size());
        }

        private void flush()
        {
            if(counters == null)
            {
                assert strings.isEmpty();
                return;
            }
            try
            {
                killWeakestStrings();
                output(new Batch(counters));
            }
            catch(InterruptedException ex)
            {
                // TODO
            }
            // log.debug("Submitted {} strings", strings.size());
            newBatch();
        }

        private void countString(String s, int what)
        {
            StringCounter counter = strings.get(s);
            if(counter == null)
            {
                counter = new StringCounter(s);
                if(counters == null)
                {
                    counters = counter;
                }
                else
                {
                    counters.prepend(counter);
                }
                strings.put(s, counter);
            }
            counter.total++;
            switch(what)
            {
            case COUNT_KEYS:
                counter.keys++;
                break;
            case COUNT_VALUES:
                counter.values++;
                break;
            }
            if(strings.size() == stringBatchSize) flush();
        }

        private int countTagStrings(Tags tags)
        {
            int numberOfTags = 0;
            StringCounter counter;
            while(tags.next())
            {
                countString(tags.key(), COUNT_KEYS);
                countString(tags.stringValue(), COUNT_VALUES);
                numberOfTags++;
            }
            return numberOfTags;
        }

        @Override protected void node(long id, int lon, int lat, Tags tags)
        {
            nodeCount++;
            maxNodeId = id;
            int nodeTagCount = countTagStrings(tags);
            if(nodeTagCount > 0) taggedNodeCount++;
            tagCount += nodeTagCount;

            int x = Mercator.xFromLon100nd(lon);
            int y = Mercator.yFromLat100nd(lat);
            int tile = Tile.rowFromYZ(y, 12) * 4096 + Tile.columnFromXZ(x, 12);
            nodesPerTile.addToValue(tile, 1);
        }

        @Override protected void way(long id, Tags tags, Nodes nodes)
        {
            wayCount++;
            wayNodeCount += nodes.size();
            tagCount += tags.size();
            maxWayId = id;
            countTagStrings(tags);
        }

        @Override protected void relation(long id, Tags tags, Members members)
        {
            relationCount++;
            tagCount += tags.size();
            maxRelationId = id;
            countTagStrings(tags);
            boolean isSuperRelation = false;
            int thisMemberCount = 0;
            while(members.next())
            {
                if(members.type() == FeatureType.RELATION)
                {
                    if (members.id() != id) isSuperRelation = true;
                    // We ignore self-references, since they are
                    // removed by subsequent steps
                }
                countString(members.role(), COUNT_ROLES);
                thisMemberCount++;
            }
            memberCount += thisMemberCount;
            if(thisMemberCount == 0) emptyRelationCount++;
            if(isSuperRelation) superRelationCount++;
        }

        @Override protected void endBlock(Block block)
        {
            // flush(currentPhase());
            if(project.verbosity() >= Verbosity.NORMAL)
            {
                synchronized (Analyzer.this)
                {
                    totalBytesProcessed += block.length;
                    reportProgress();
                }
            }
        }

        @Override protected void postProcess()
        {
            flush();
            synchronized (Analyzer.this)
            {
                totalNodeCount += nodeCount;
                totalTaggedNodeCount += taggedNodeCount;
                totalWayCount += wayCount;
                totalWayNodeCount += wayNodeCount;
                totalRelationCount += relationCount;
                totalSuperRelationCount += superRelationCount;
                totalEmptyRelationCount += emptyRelationCount;
                totalMemberCount += memberCount;
                totalTagCount += tagCount;
                if (maxNodeId > globalMaxNodeId) globalMaxNodeId = maxNodeId;
                if (maxWayId > globalMaxWayId) globalMaxWayId = maxWayId;
                if (maxRelationId > globalMaxRelationId) globalMaxRelationId = maxRelationId;

                nodesPerTile.forEachKeyValue((tile, count) ->
                {
                    globalNodesPerTile[tile] += count;
                });
            }
        }
    }

    protected void reportProgress()
    {
        // TODO: verbosity level

        int percentageCompleted = (int)((totalBytesProcessed * 100) / fileSize());
        if(percentageCompleted != percentageReported)
        {
            System.err.format("Analyzing... %d%%\r", percentageCompleted);
            percentageReported = percentageCompleted;
        }
    }

    public static String cleanString(String s)
    {
        s = s.trim();
        return s.replaceAll("\\s", " ");
    }

    public void writeStringSummary(String stringFileName) throws FileNotFoundException
    {
        StringCounter[] counters = globalStrings.values().toArray(new StringCounter[0]);
        Arrays.sort(counters);
        PrintWriter out = new PrintWriter(stringFileName);
        out.println("String\tTotal\tKeys\tValues\tRoles");
        for(StringCounter c: counters)
        {
            if(c.total < minFinalStringCount) continue;
            String s = cleanString(c.string);
            if(s.isEmpty()) continue;
            out.format("%s\t%d\t%d\t%d\t%d\n", s, c.total, c.keys, c.values,
                c.total - c.keys - c.values);
        }
        out.close();
    }

    public void writeNodeDensities(String fileName) throws IOException
    {
        PrintWriter out = new PrintWriter(fileName);
        for(int row=0; row<4096; row++)
        {
            for(int col=0; col<4096; col++)
            {
                int count = globalNodesPerTile[row * 4096 + col];
                if(count > 0)
                {
                    out.format("%d,%d,%d\n", col, row, count);
                }
            }
        }
        out.close();
    }


    public void writeStatistics(String reportFileName) throws IOException
    {
        PrintWriter out = new PrintWriter(reportFileName);
        out.format("nodes:           %d\n", totalNodeCount);
        out.format("tagged-nodes:    %d\n", totalTaggedNodeCount);
        out.format("ways:            %d\n", totalWayCount);
        out.format("way-nodes:       %d\n", totalWayNodeCount);
        out.format("relations:       %d\n", totalRelationCount);
        out.format("super-relations: %d\n", totalSuperRelationCount);
        out.format("empty-relations: %d\n", totalEmptyRelationCount);
        out.format("members:         %d\n", totalMemberCount);
        out.format("tags:            %d\n", totalTagCount);
        out.format("max-node-id:     %d\n", globalMaxNodeId);
        out.format("max-way-id:      %d\n", globalMaxWayId);
        out.format("max-relation-id: %d\n", globalMaxRelationId);
        out.close();
    }

    public void analyze() throws Exception
    {
        analyze(project.workPath(), project.sourcePath());
    }

    public void analyze(Path workPath, Path sourcePath) throws Exception
    {
        globalNodesPerTile = new int[4096 * 4096];      // TODO: delay creation
        mostRecentString = new StringCounter("no");
        mostRecentString.total = mostRecentString.values = 100_000_000_000_000L;
            // Ensures that "no" will always be included in the GST
            //  TODO: This feels ugly; consider assigning fixed entries
            //   to "no" and other common strings
        globalStrings.put(mostRecentString.string, mostRecentString);
        evictionCandidate = mostRecentString;
        read(sourcePath.toFile());
        // writeStatistics(workPath.resolve("stats.txt").toString());
        writeStringSummary(workPath.resolve("string-counts.txt").toString());
        writeNodeDensities(workPath.resolve("node-counts.txt").toString());
        if(project.verbosity() >= Verbosity.QUIET)
        {
            System.err.format("Analyzed %s in %s\n",
                sourcePath, Format.formatTimespan(timeElapsed()));
        }
    }

    /*
    public static void main(String[] args) throws Exception
    {
        Path workPath = Path.of(args[0]);
        Path sourcePath = Path.of(args[1]);

        Project project = new Project();
        // Project project = Project.read(workPath.resolve("project.bin"));
        Analyzer analyzer = new Analyzer(project);
        analyzer.analyze(workPath, sourcePath);
        if(project.verbosity() >= Verbosity.QUIET)
        {
            System.err.println("Analyzed in " + Format.formatTimespan(analyzer.timeElapsed()));
        }
    }
     */
}
