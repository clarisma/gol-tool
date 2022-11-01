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
import com.geodesk.gol.Processor;

import org.eclipse.collections.api.map.primitive.LongIntMap;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

import java.io.IOException;
import java.io.RandomAccessFile;

public class Linker extends Processor<Linker.Task>
{
    // private static final Logger log = LogManager.getLogger();

    private final ServerFeatureStore featureStore;
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
            featureStore.fixTileLinks(importingTip, imports, sourceTiles);
            completed(1);
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

