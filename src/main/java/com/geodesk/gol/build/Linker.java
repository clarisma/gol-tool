/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.io.PileFile;
import com.clarisma.common.pbf.PbfBuffer;
import com.clarisma.common.text.Format;
import com.clarisma.common.util.Log;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.gol.Processor;

import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.api.map.primitive.LongIntMap;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public class Linker extends Processor<Linker.Task>
{
    // private static final Logger log = LogManager.getLogger();

    private final FeatureStore featureStore;
    private final TileCatalog tileCatalog;
    private final RandomAccessFile linkerImportFile;
    private final PileFile linkerExportFile;

    public Linker(BuildContext ctx) throws IOException
    {
        featureStore = ctx.getFeatureStore();
        tileCatalog = ctx.getTileCatalog();
        linkerImportFile = ctx.getLinkerImportFile();
        linkerExportFile = ctx.getLinkerExportFile();
    }

    protected class Task implements Runnable
    {
        private final int pile;
        private PbfBuffer imports;
        private PbfBuffer exports;

        public Task(int pile, byte[] importData, byte[] exportData)
        {
            this.pile = pile;
            imports = new PbfBuffer(importData);
            exports = new PbfBuffer(exportData);
        }

        @Override public void run()
        {
            int importingTile = tileCatalog.tileOfPile(pile);
            int importingTip = tileCatalog.tipOfTile(importingTile);
            /*
            log.debug("Linking Tile {} ({})", String.format("%06X",
                importingTip), Tile.toString(importingTile));
             */

            MutableIntObjectMap<LongIntMap> sourceTiles =
                new IntObjectHashMap<>(tileCatalog.tileCount());
            while(exports.hasMore())
            {
                int tip = exports.readFixed32();
                int count = exports.readFixed32();
                // log.debug("Reading {} exports from Tile {}", count, String.format("%06X", tip));
                MutableLongIntMap targets = new LongIntHashMap(count);
                for(int i=0; i<count; i++)
                {
                    long typedId = exports.readFixed64();
                    int pos = exports.readFixed32();
                    targets.put(typedId, pos);
                }
                sourceTiles.put(tip, targets);
            }
            exports = null; // free memory early
            fixTileLinks(importingTip, imports, sourceTiles);
            completed(1);
        }

        // not synchronized, safe as long as each thread works on a different tile
        private void fixTileLinks(int importingTip, PbfBuffer imports, IntObjectMap<LongIntMap> exports)
        {
            FeatureStore store = featureStore;
            int page = store.tilePage(importingTip);
            assert page != 0;
            ByteBuffer buf = store.bufferOfPage(page);
            int ofs = store.offsetOfPage(page);

            while(imports.hasMore())
            {
                int linkPos = imports.readFixed32();
                int tipAndShift = imports.readFixed32();
                int shift = tipAndShift & 0xf;
                int tip = tipAndShift >>> 4;
                long typedId = imports.readFixed64();
                LongIntMap targets = exports.get(tip);
                if(targets == null)
                {
                    if(tip != 0)
                    {
                        Log.warn("No exports for tip %06X, can't resolve %s at %06X/%08X",
                            tip, FeatureId.toString(typedId), importingTip, linkPos);
                    }
                    continue;
                }
                // assert targets != null: "No exports for tip " + tip;
                int targetPos = targets.get(typedId);
                if(targetPos == 0)
                {
                    Log.warn("%s has not been exported by tile %06X, can't resolve at %06X/%08X",
                        FeatureId.toString(typedId), tip, importingTip, linkPos);
                    continue;
                }

                int p = linkPos + ofs;
                int flags = buf.getInt(p);
                buf.putInt(p, (targetPos << shift) | flags);
            }
        }
    }

    @Override protected void feed() throws Exception
    {
        int tileCount = tileCatalog.tileCount();
        setTotalWork("Linking", tileCount); // TODO: file size is smoother
        linkerImportFile.seek(0);
        for (int i=0; i<tileCount; i++)
        {
            if(failed()) break;
            int pile = linkerImportFile.readInt();
            int importDataLen = linkerImportFile.readInt();
            byte[] importData = new byte[importDataLen];
            linkerImportFile.readFully(importData);
            byte[] exportData = linkerExportFile.load(pile);
            // TODO: empty tiles
            submit(new Task(pile, importData, exportData));
        }
    }

    public void linkAll()
    {
        run();
        System.out.format("Linked %d tiles in %s\n",
            tileCatalog.tileCount(), Format.formatTimespan(timeElapsed()));
    }
}

