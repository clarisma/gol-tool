package com.geodesk.gol;

import com.clarisma.common.cli.Parameter;
import com.geodesk.core.Box;
import com.geodesk.feature.store.TileExporter;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.build.Utils;

import java.nio.file.Path;
import java.nio.file.Paths;

public class SaveCommand extends GolCommand
{
    private Path exportPath;

    @Parameter("1=path")
    public void exportPath(String path)
    {
        exportPath = Paths.get(path);
    }

    @Override protected void performWithLibrary() throws Exception
    {
        TileExporter exporter = new TileExporter(features.store());
        Bounds bounds = bbox==null ? Box.ofWorld() : bbox;
        exporter.export(exportPath, bounds, null);    // TODO: polygon
    }
}
