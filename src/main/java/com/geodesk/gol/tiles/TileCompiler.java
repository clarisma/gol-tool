/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.io.PileFile;
import com.clarisma.common.pbf.PbfBuffer;
import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.store.BlobStoreConstants;
import com.clarisma.common.text.Format;
import com.geodesk.core.Box;
import com.geodesk.core.Tile;
import com.geodesk.core.TileQuad;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.TileIndexWalker;
import com.geodesk.feature.store.Tip;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.Processor;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.Project;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.compiler.FeatureTile;
import com.geodesk.gol.compiler.SFeature;
import com.geodesk.gol.compiler.SRelation;
import com.geodesk.gol.compiler.SWay;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.geodesk.gol.build.ProtoGol.*;

public class TileCompiler extends Processor<TileCompiler.Task>
{
    private final FeatureStore store;
    private final TileCatalog tileCatalog;
    private final ObjectIntMap<String> globalStrings;
    private final IndexSettings indexSettings;

    private static final int DEFAULT_LINK_DB_PAGE_SIZE = 1 << 13; // TODO: configurable

    public TileCompiler(BuildContext ctx) throws IOException
    {
        store = ctx.getFeatureStore();
        tileCatalog = ctx.getTileCatalog();
        globalStrings = store.stringsToCodes();
        indexSettings = new IndexSettings(store, ctx.project());
    }

    protected class Task implements Runnable
    {
        private final int tip;

        public Task(int tip)
        {
            this.tip = tip;
        }

        public void run()
        {
            try
            {
                TTile tile = new TTile(tip, globalStrings, tileCatalog, indexSettings);
                TileReader reader = new TileReader(tile, store, tip);
                reader.read();
                tile.build();
            }
            catch (Throwable ex)
            {
                fail(ex);
            }
            completed(1);
        }
    }

    @Override protected void feed() throws IOException
    {
        int tileCount = tileCatalog.tileCount();
        setTotalWork("Compiling", tileCount);
        TileIndexWalker walker = new TileIndexWalker(store);
        walker.start(Box.ofWorld());
        while(walker.next())
        {
            submit(new Task(walker.tip()));
        }
    }

    public void compileAll() throws IOException
    {
        run();
    }
}
