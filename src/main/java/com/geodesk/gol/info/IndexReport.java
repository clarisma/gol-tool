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
import com.geodesk.gol.util.TileReaderTask;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class IndexReport extends Table
{
    private final FeatureStore store;
    private final boolean calculateIQ;
    private final Map<String, Integer> keyToCategory;
    private final String[] keys;
    private final int[][] categoryToKeys;
    private final Map<Integer,Integer> stringCodeToKey;
    private final int maxKeyStringCode;
    private final int valueNoBits;
    private final Stats[] tables;
    private int totalTileCount;

    public IndexReport(FeatureStore store, TileIndexWalker walker, boolean calculateIQ)
    {
        this.store = store;
        this.calculateIQ = calculateIQ;

        valueNoBits = (store.codeFromString("no") << 16) | 1;

        keyToCategory = store.indexedKeys();
        keys = keyToCategory.keySet().toArray(new String[0]);
        Arrays.sort(keys);

        final int[] EMPTY = new int[0];
        categoryToKeys = new int[32][];     // TODO: max categories
        for (int i = 0; i < categoryToKeys.length; i++) categoryToKeys[i] = EMPTY;

        stringCodeToKey = new HashMap<>(keys.length);
        int maxKeyStringCode = 0;

        // Create mappings of categories to keys

        for (int k = 0; k < keys.length; k++)
        {
            String key = keys[k];
            int category = keyToCategory.get(key);
            assert category > 0;
            category--;     // category is 1-based, but we want 0-based here
            int[] categoryKeys = categoryToKeys[category];
            int n = categoryKeys.length;
            categoryKeys = Arrays.copyOf(categoryKeys, n + 1);
            categoryKeys[n] = k;
            categoryToKeys[category] = categoryKeys;
            int keyStringCode = store.codeFromString(key);
            if(keyStringCode > maxKeyStringCode) maxKeyStringCode = keyStringCode;
            stringCodeToKey.put(keyStringCode, k);
        }
        this.maxKeyStringCode = maxKeyStringCode;

        tables = new Stats[5];
        for(int i=0; i<tables.length; i++) tables[i] = new Stats(keys);

        column();
        column().format("###,###,###,###");
        column().format("##0.00%");
        column().format("##0.00%");
        if (calculateIQ)
        {
            column().format("##0.0%");
            column().format("#,###,##0.0");
            column().format("##0.0%");
        }

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
            tileCount = Math.max(tileCount, other.tileCount);
        }
    }

    /**
     * Statistics for one indexed type (or summary for all)
     */
    private static class Stats
    {
        final Counter[] counters;
        long total;
        long mixedCount;
        long uncatCount;

        public Stats(String[] keys)
        {
            counters = new Counter[keys.length];
            for (int i = 0; i < counters.length; i++)
            {
                counters[i] = new Counter();
                counters[i].key = keys[i];
            }
        }

        public void add(Stats other)
        {
            for(int i=0; i<counters.length; i++)
            {
                counters[i].add(other.counters[i]);
            }
            total += other.total;
            mixedCount += other.mixedCount;
            uncatCount += other.uncatCount;
        }
    }

    private class IndexScanTask extends TileReaderTask
    {
        private final int keyCount;
        private final int[] hitCounts;
        private final int[] scannedCounts;
        private final int[] totalScannedCounts;
        private final int[] mixedCounts;
        private final int[] uncatCounts;
        private int currentType;
        private int currentCategoryBits;
        private int subTotal;

        public IndexScanTask(ByteBuffer buf, int pTile)
        {
            super(buf, pTile);
            keyCount = keys.length;
            hitCounts = new int[keyCount * 4];
            scannedCounts = new int[keyCount * 4];
            totalScannedCounts = new int[4];
            mixedCounts = new int[4];
            uncatCounts = new int[4];
        }

        @Override protected void beginIndex(int type, int indexBits)
        {
            currentType = type;
            currentCategoryBits = indexBits;
            subTotal = 0;
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

        private void tally(int pFeature)
        {
            int indexedKeyCount = 0;
            int ppTags = pFeature + 8;
            int p = (buf.getInt(ppTags) & ~1) + ppTags;
            for(;;)
            {
                int tag = buf.getInt(p);
                int keyStringCodeWithEndFlag = (tag >>> 2) & 0x3fff;
                int keyStringCode = keyStringCodeWithEndFlag & 0x1fff;
                Integer k = stringCodeToKey.get(keyStringCode);
                if(k != null)
                {
                    if((tag & 0xffff_0003) != valueNoBits)  // tag must not be "no"
                    {
                        hitCounts[currentType * keyCount + k]++;
                        indexedKeyCount++;
                    }
                }
                if(keyStringCodeWithEndFlag >= maxKeyStringCode) break;
                p += 4 + (tag & 2);
            }
            if(indexedKeyCount > 1)
            {
                mixedCounts[currentType]++;
            }
            else if (indexedKeyCount == 0)
            {
                uncatCounts[currentType]++;
            }
            subTotal++;
        }

        @Override protected void endIndex()
        {
            for (int i = 0; i < 32; i++)
            {
                if ((currentCategoryBits & (1 << i)) != 0)
                {
                    for(int k: categoryToKeys[i])
                    {
                        scannedCounts[currentType * keyCount + k] += subTotal;
                    }
                }
            }
            totalScannedCounts[currentType] += subTotal;
        }

        @Override public void run()
        {
            super.run();
            for (int type = 0; type < 4; type++)
            {
                Stats table = tables[type];
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
                    table.total += totalScannedCounts[type];
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

        for(int i=0; i<4; i++)
        {
            tables[4].add(tables[i]);
        }
    }

    private void addStats(String title, Stats stats) throws IOException
    {
        long total = stats.total;
        long allTypeTotal = tables[4].total;
        add(title);
        add(total);
        add("");
        add((double) total / allTypeTotal);
        if(calculateIQ)
        {
            add("Hits");
            add("Boost");
            add("Tiles");
        }
        divider("-");
        for(Counter c: stats.counters)
        {
            add("  " + c.key);
            double hits = c.hitCount;
            add(hits);
            add(hits / stats.total);
            add(hits / allTypeTotal);
            if(calculateIQ)
            {
                add(hits / c.scannedCount);
                add((double) total / c.scannedCount);
                add((double)c.tileCount / totalTileCount);
            }
        }

        add("  (Multiple)");
        double mixedCount = stats.mixedCount;
        add(mixedCount);
        add(mixedCount / total);
        add(mixedCount / allTypeTotal);
        newRow();

        add("  (Unindexed)");
        double uncatCount = stats.uncatCount;
        add(uncatCount);
        add(uncatCount / total);
        add(uncatCount / allTypeTotal);
        divider("=");
    }


    public void print(Appendable out) throws IOException
    {
        addStats("Nodes (n)", tables[0]);
        divider("");
        addStats("Ways (w)", tables[1]);
        divider("");
        addStats("Areas (a)", tables[2]);
        divider("");
        addStats("Relations (r)", tables[3]);
        divider("");
        addStats("All", tables[4]);
        super.print(out);
    }
}