/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.info;

import com.clarisma.common.text.Table;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.TileIndexWalker;
import com.geodesk.gol.util.TileScanner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class IndexReport
{
    private final FeatureStore store;
    private final boolean calculateIQ;
    private final Map<String, Integer> keyToCategory;
    private final String[] keys;
    private final int[][] categoryToKeys;
    private final StatsTable[] tables;
    private int totalTileCount;

    public IndexReport(FeatureStore store, TileIndexWalker walker, boolean calculateIQ)
    {
        this.store = store;
        this.calculateIQ = calculateIQ;

        keyToCategory = store.indexedKeys();
        keys = keyToCategory.keySet().toArray(new String[0]);
        Arrays.sort(keys);

        final int[] EMPTY = new int[0];
        categoryToKeys = new int[32][];     // TODO: max categories
        for (int i = 0; i < categoryToKeys.length; i++) categoryToKeys[i] = EMPTY;

        // Create mappings of categories to keys

        for (int k = 0; k < keys.length; k++)
        {
            int category = keyToCategory.get(keys[k]);
            assert category > 0;
            category--;     // category is 1-based, but we want 0-based here
            int[] categoryKeys = categoryToKeys[category];
            int n = categoryKeys.length;
            categoryKeys = Arrays.copyOf(categoryKeys, n + 1);
            categoryKeys[n] = k;
            categoryToKeys[category] = categoryKeys;
        }

        tables = new StatsTable[5];
        tables[0] = new StatsTable("Nodes (n)");
        tables[1] = new StatsTable("Ways (w)");
        tables[2] = new StatsTable("Areas (a)");
        tables[3] = new StatsTable("Relations (r)");
        tables[4] = new StatsTable("All");

        createReports(walker);
    }

    private static class Counter
    {
        String key;
        long hitCount;
        long scannedCount;
        int tileCount;

        public void add(Counter other)
        {
            hitCount += other.hitCount;
            scannedCount += other.scannedCount;
            tileCount += other.tileCount;
        }
    }

    private class StatsTable extends Table
    {
        final String title;
        final Counter[] counters;
        long totalHitCount;
        long totalScannedCount;
        long mixedCount;
        long uncatCount;

        public StatsTable(String title)
        {
            this.title = title;
            counters = new Counter[keys.length];
            for (int i = 0; i < counters.length; i++)
            {
                counters[i] = new Counter();
                counters[i].key = keys[i];
            }

            column();
            column().format("###,###,###,###");
            column().format("##0.00%");
            column().format("##0.00%");
            if (calculateIQ)
            {
                column().format("##0.0%");
                column().format("x 0.0");
                column().format("##0.0%");
            }
        }

        @Override public void print(Appendable out) throws IOException
        {
            long allTypeTotalHitCount = tables[4].totalHitCount;
            add(title);
            add(totalHitCount);
            add("");
            add((double)totalHitCount / allTypeTotalHitCount);
            if(calculateIQ)
            {
                add("IQ");
                add("Boost");
                add("Tiles");
            }
            divider("-");
            for(Counter c: counters)
            {
                add("  " + c.key);
                double hits = c.scannedCount; //  c.hitCount;  // TODO !!!!
                add(hits);
                add(hits / totalHitCount);
                add(hits / allTypeTotalHitCount);
                if(calculateIQ)
                {
                    add(hits / c.scannedCount);
                    add((double)totalScannedCount / c.scannedCount);
                    add((double)c.tileCount / totalTileCount);
                }
            }

            super.print(out);
        }
    }

    private class IndexScanTask extends TileScanner
    {
        private final int keyCount;
        private final int[] hitCounts;
        private final int[] scannedCounts;
        private final int[] totalHitCounts;
        private final int[] totalScannedCounts;
        private final int[] mixedCounts;
        private final int[] uncatCounts;
        private int currentType;
        private int currentCategoryBits;
        private int scannedSubTotal;

        public IndexScanTask(ByteBuffer buf, int pTile)
        {
            super(buf, pTile);
            keyCount = keys.length;
            hitCounts = new int[keyCount * 4];
            scannedCounts = new int[keyCount * 4];
            totalHitCounts = new int[4];
            totalScannedCounts = new int[4];
            mixedCounts = new int[4];
            uncatCounts = new int[4];
        }

        @Override protected void beginIndex(int type, int indexBits)
        {
            currentType = type;
            currentCategoryBits = indexBits;
            scannedSubTotal = 0;
        }

        @Override protected void node(int p)
        {
            tally(p);
        }

        @Override protected void way(int p)
        {
            tally(p);
        }

        @Override protected void relation(int p)
        {
            tally(p);
        }

        private void tally(int p)
        {
            scannedSubTotal++;
        }

        @Override protected void endIndex()
        {
            for (int i = 0; i < 32; i++)
            {
                if ((currentCategoryBits & (1 << i)) != 0)
                {
                    for(int k: categoryToKeys[i])
                    {
                        scannedCounts[currentType * keyCount + k] += scannedSubTotal;
                    }
                }
            }
            totalScannedCounts[currentType] += scannedSubTotal;
        }

        @Override public void run()
        {
            super.run();
            for (int type = 0; type < 4; type++)
            {
                StatsTable table = tables[type];
                synchronized (table)
                {
                    for (int i = 0; i < keyCount; i++)
                    {
                        int slot = type * keyCount + i;
                        Counter counter = table.counters[i];
                        counter.hitCount += hitCounts[slot];
                        counter.scannedCount += scannedCounts[slot];
                        if(scannedCounts[slot] != 0) counter.tileCount++;
                    }
                    table.totalHitCount += totalHitCounts[type];
                    table.totalScannedCount += totalScannedCounts[type];
                    table.mixedCount += mixedCounts[type];
                    table.uncatCount += uncatCounts[type];
                }
            }
        }

    }

    private void createReports(TileIndexWalker walker)
    {
        int threadCount = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            threadCount, threadCount, 1, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(threadCount * 4),
            new ThreadPoolExecutor.CallerRunsPolicy());

        while (walker.next())
        {
            int tilePage = walker.tilePage();
            if (tilePage != 0)
            {
                executor.submit(new IndexScanTask(
                    store.bufferOfPage(tilePage),
                    store.offsetOfPage(tilePage)));
                totalTileCount++;
            }
        }
        executor.shutdown();
        try
        {
            executor.awaitTermination(30, TimeUnit.DAYS);
        }
        catch (InterruptedException ex)
        {
            // don't care about being interrupted, we're done anyway
        }
    }

    public void print(Appendable out) throws IOException
    {
        for(Table table: tables)
        {
            table.print(out);
            out.append("\n");
        }
    }
}