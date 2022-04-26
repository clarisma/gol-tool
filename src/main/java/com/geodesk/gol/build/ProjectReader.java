package com.geodesk.gol.build;

import com.clarisma.common.fab.FabException;
import com.clarisma.common.fab.FabReader;

import java.io.*;
import java.nio.file.Path;

public class ProjectReader extends FabReader
{
    private Project project;

    public ProjectReader()
    {
        project = new Project();
    }

    public Project project()
    {
        return project;
    }

    @Override protected void keyValue(String key, String value)
    {
        switch(key)
        {
        case "source":
            project.sourcePath(Path.of(value));
            break;
        case "max-tiles":
            project.maxTiles(Integer.parseInt(value));
            break;
        case "min-tile-density":
            project.minTileDensity(Integer.parseInt(value));
            break;
        case "tile-zoom-levels":
            project.zoomLevels(value);
            break;
        case "category-keys":
            project.keyIndexSchema(value);
            break;
        case "min-string-usage":
            project.minStringUsage(Integer.parseInt(value));
            break;
        case "max-strings":
            project.maxStringCount(Integer.parseInt(value));
            break;
        case "rtree-bucket-size":
            project.rtreeBucketSize(Integer.parseInt(value));
            break;
        case "max-key-indexes":
            project.maxKeyIndexes(Integer.parseInt(value));
            break;
        case "key-index-min-features":
            project.keyIndexMinFeatures(Integer.parseInt(value));
            break;
        }
    }

    public static void main(String[] args) throws FabException, IOException
    {
        ProjectReader reader = new ProjectReader();
        reader.readFile("C:\\dev\\deseo2\\core\\data\\foundry-settings.fab");
    }
}

