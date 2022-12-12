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
import com.geodesk.feature.store.TileExporter;
import com.clarisma.common.util.ProgressReporter;
import org.eclipse.collections.api.list.primitive.IntList;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;

public class SaveCommand extends GolCommand
{
    private Path exportPath;

    @Parameter("1=path")
    public void exportPath(String path)
    {
        exportPath = Paths.get(path);
    }

    private UUID readTilesetManifest(Path tilesetManifest) throws IOException
    {
        if(!Files.exists(tilesetManifest)) return null;
        Properties props = new Properties();
        try(FileInputStream in = new FileInputStream(tilesetManifest.toFile()))
        {
            props.load(in);
        }
        String value = props.getProperty("guid");
        if(value == null) return null;
        return UUID.fromString(value);
    }

    @Override protected void performWithLibrary() throws Exception
    {
        FeatureStore store = features.store();
        Path tilesetManifest = exportPath.resolve("tileset.txt");
        UUID storeId = store.getGuid();
        UUID existingSetId = null;

        if(Files.exists(exportPath))
        {
            if(!Files.isDirectory(exportPath))
            {
                throw new IllegalArgumentException(exportPath + " is not a folder");
            }
            existingSetId = readTilesetManifest(tilesetManifest);
        }
        else
        {
            Files.createDirectories(exportPath);
        }
        if(existingSetId != null)
        {
            if(!existingSetId.equals(storeId))
            {
                throw new IllegalArgumentException(String.format(
                    "%s already contains a different tile set (%s)",
                    exportPath , existingSetId));
            }
        }
        else
        {
            // TODO: write to temp file first, then rename (safer)

            PrintStream out = new PrintStream(
                new FileOutputStream(tilesetManifest.toFile()));
            out.print("# TileSet Manifest\nguid=" + storeId);
            out.close();
        }

        IntList tiles = getTiles();
        ProgressReporter progress = new ProgressReporter(
            tiles.size(), "tiles",
            verbosity >= Verbosity.NORMAL ? "Exporting" : null,
            verbosity >= Verbosity.QUIET ? "Exported" : null);
        TileExporter exporter = new TileExporter(store, exportPath, progress);
        exporter.exportTiles(tiles);
        // TODO: better error handling
        Exception error = exporter.error();
        if(error != null) throw error;
    }
}
