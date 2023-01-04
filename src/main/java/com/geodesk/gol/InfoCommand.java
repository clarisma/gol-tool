/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.Option;
import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.util.Log;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.TileIndexWalker;
import com.geodesk.gol.info.FreeBlobReport;
import com.geodesk.gol.info.IndexReport;
import com.geodesk.gol.info.TileReport;
import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.list.primitive.IntList;

import java.util.*;

public class InfoCommand extends GolCommand
{
    @Option("index,i: display index details")
    protected boolean indexDetails;

    @Option("tiles,t: display tile statistics")
    protected boolean tileDetails;

    @Option("free,f: display free-block details")
    protected boolean freeDetails;

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
            System.out.format("%,d tiles (all loaded)\n", tiles.size());
        }
        else
        {
            System.out.format("%,d tiles (%,d loaded)\n", tiles.size(), loaded);
        }
        Map<String,Integer> indexedKeys = store.indexedKeys();
        List<String> keys = new ArrayList<>(indexedKeys.keySet());
        Collections.sort(keys);
        System.out.format("Indexes: %s\n", String.join(", ", keys));

        if(indexDetails)
        {
            System.out.println();
            new IndexReport(features.store(), getTileIndexWalker(),
                verbosity >= Verbosity.VERBOSE).print(System.out);
        }

        if(freeDetails)
        {
            System.out.println();
            new FreeBlobReport(features.store()).print(System.out);
        }

        if(tileDetails)
        {
            System.out.println();
            new TileReport(features.store(), getTileIndexWalker()).print(System.out);
        }
    }
}
