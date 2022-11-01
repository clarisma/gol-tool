/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;


import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.TileIndexWalker;
import com.geodesk.core.Box;
import com.geodesk.feature.store.Tip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// TODO: total size, total files, compression level
public class Exporter extends Processor<Exporter.Task>
{
    private final FeatureStore store;
    private final Path exportPath;

    public Exporter(FeatureStore store, Path exportPath)
    {
        this.store = store;
        this.exportPath = exportPath;
    }

    @Override protected void feed() throws Exception
    {
        int currentSuperFolder = -1;
        TileIndexWalker walker = new TileIndexWalker(store);
        walker.start(Box.ofWorld());
        while(walker.next())
        {
            int startPage = walker.tilePage();
            if(startPage != 0)
            {
                int tip = walker.tip();
                int superFolder = tip >>> 12;
                if(superFolder != currentSuperFolder)
                {
                    Path folder = Tip.folder(exportPath, tip);
                    if(!Files.exists(folder))
                    {
                        Files.createDirectories(folder);
                    }
                }
                submit(new Task(walker.tile(), tip, startPage));
            }
        }
    }

    protected class Task implements Runnable
    {
        private final int tile;
        private final int tip;
        private final int startPage;

        public Task(int tile, int tip, int startPage)
        {
            this.tile = tile;
            this.tip = tip;
            this.startPage = startPage;
        }

        @Override public void run()
        {
            System.out.format("Exporting %s\n", Tip.toString(tip));
            Path path = Tip.path(exportPath, tip, ".ftile.gz");
            try
            {
                store.export(startPage, path);
            }
            catch(IOException ex)
            {
                // TODO: respect output level
                System.err.format("Failed to export %s: %s", path, ex.getMessage());
            }
        }
    }

    /*
    public static void main(String[] args)
    {
        FeatureStore store = new FeatureStore(Path.of(args[0]));
        Path exportPath = Path.of(args[1]);
        Exporter exporter = new Exporter(store, exportPath);
        exporter.run();
        store.close();
    }
     */
}
