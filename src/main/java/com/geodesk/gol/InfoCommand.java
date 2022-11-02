/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.Option;
import com.clarisma.common.util.Log;
import com.geodesk.feature.store.FeatureStore;
import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.list.primitive.IntList;

import java.util.*;

public class InfoCommand extends GolCommand
{
    @Option("index,i: display index details")
    protected boolean indexDetails;

    @Override protected void performWithLibrary() throws Exception
    {
        FeatureStore store = features.store();
        UUID guid = store.getGuid();
        IntList tiles = getTiles();
        int loaded = 0;
        IntIterator iter = tiles.intIterator();
        while(iter.hasNext())
        {
            int tip = iter.next();
            if(store.tilePage(tip) != 0) loaded++;
        }

        System.out.format("Tile set ID: %s\n", guid);
        if(loaded == tiles.size())
        {
            System.out.format("%d tiles (all loaded)\n", tiles.size());
        }
        else
        {
            System.out.format("%d tiles (%d loaded)\n", tiles.size(), loaded);
        }
        Map<String,Integer> indexedKeys = store.indexedKeys();
        List<String> keys = new ArrayList<>(indexedKeys.keySet());
        Collections.sort(keys);
        System.out.format("Indexes: %s\n", String.join(", ", keys));

        if(indexDetails) detailedIndexReport(keys);
    }

    private void detailedIndexReport(List<String> keys)
    {
        int widestKey = 0;
        for(String key: keys)
        {
            int len = key.length();
            if(len > widestKey) widestKey = len;
        }
        String padding = " ".repeat(40);
        int pad = Math.max(0, widestKey-14);

        long totalFeatures = features.select("*").count();
        System.out.format("\nTotal features:  %s%,14d\n", padding.substring(0, pad), totalFeatures);
        for(String key: keys)
        {
            String query = String.format("*[%s]", key);
            long count = features.select(query).count();
            pad = Math.max(0, widestKey - key.length());
            System.out.format("  %s  %s%,14d  %5.1f%%\n",
                key, padding.substring(0, pad), count,
                (double)count / totalFeatures * 100);
        }
    }
}
