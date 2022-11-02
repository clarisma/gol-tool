/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.Parameter;
import com.clarisma.common.cli.Verbosity;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.FeatureStoreChecker;
import com.geodesk.gol.build.ProgressReporter;
import org.eclipse.collections.api.iterator.IntIterator;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;

import java.nio.file.Paths;

public class LoadCommand extends GolCommand
{
    @Parameter("1=?path|url")
    public void importPath(String path)
    {
        if(!path.startsWith("http://") &&
            !path.startsWith("file:") &&
            !path.startsWith("https://"))
        {
            path = "file:" + path;
        }
        url = path;
    }

    @Override protected void performWithLibrary() throws Exception
    {
        if(url == null)
        {
            throw new IllegalArgumentException(
                "Usage: gol load <gol> <path|url>\n" +
                "  or   gol load <gol> -u=<url>");
        }
        FeatureStore store = features.store();
        IntList tiles = getTiles();
        MutableIntList tilesToLoad = new IntArrayList();
        tiles.forEach(tip ->
        {
            if(store.tilePage(tip) == 0) tilesToLoad.add(tip);
        });
        if(tilesToLoad.isEmpty())
        {
            if(verbosity >= Verbosity.QUIET)
            {
                System.err.format("All %d tiles already loaded.\n", tiles.size());
            }
            return;
        }
        if(verbosity >= Verbosity.NORMAL)
        {
            System.err.format("Need to load %d of %d tiles...\n",
                tilesToLoad.size(), tiles.size());
        }
        ProgressReporter reporter = new ProgressReporter(
            tilesToLoad.size(), "tiles",
            verbosity >= Verbosity.NORMAL ? "Loading" : null,
            verbosity >= Verbosity.QUIET ? "Loaded" : null);

        tilesToLoad.forEach(tip ->
        {
            store.fetchTile(tip);
            reporter.progress(1);
        });
        reporter.finished();
    }
}
