/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.Parameter;
import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.io.FileUtils;
import com.clarisma.common.text.Format;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.Project;
import com.geodesk.gol.build.ProjectReader;
import com.geodesk.gol.build.Utils;
import com.geodesk.gol.update.ChangeGraph;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;

public class UpdateCommand extends GolCommand
{
    private final static int SOURCE_OSC = 1;
    private final static int SOURCE_GZ = 2;

    private int source;
    private String sourceFileName;

    // TODO: make optional
    @Parameter("1=source")
    public void source(String filename)
    {
        String ext = FileUtils.getExtension(filename);
        if(ext.isEmpty())
        {
            if(trySource(filename + ".osc", SOURCE_OSC)) return;
            if(trySource(filename + ".osc.gz", SOURCE_GZ)) return;
            throw new IllegalArgumentException("No .osc or .osc.gz file found with name " + filename);
        }

        // getExtension() gives us only the last extension (".gz" instead of ".osc.gz")
        // TODO: rewrite this

        switch(ext)
        {
        case "osc":
            source = SOURCE_OSC;
            break;
        case "gz":
            source = SOURCE_GZ;
            break;
        default:
            throw new IllegalArgumentException("Unknown change file format: " + filename);
        }
        sourceFileName = filename;
    }

    private boolean trySource(String filename, int sourceType)
    {
        if(Files.exists(Path.of(filename)))
        {
            source = sourceType;
            sourceFileName = filename;
            return true;
        }
        return false;
    }

    @Override protected void performWithLibrary() throws Exception
    {
        InputStream settingsStream;
        // TODO: configurable
        settingsStream = getClass().getResourceAsStream("/com/geodesk/gol/default-config.fab");
        ProjectReader projectReader = new ProjectReader();
        projectReader.read(settingsStream);
        settingsStream.close();
        Project project = projectReader.project();

        // TODO: do we need a work path?
        BuildContext context = new BuildContext(golPath, null, project);
        ChangeGraph changes = new ChangeGraph(context);

        if(verbosity >= Verbosity.NORMAL)
        {
            System.err.format("Reading %s ..." , sourceFileName);
        }
        long start = System.currentTimeMillis();
        try(FileInputStream fin = new FileInputStream(sourceFileName))
        {
            InputStream in = fin;
            if(source == SOURCE_GZ)
            {
                in = new GZIPInputStream(fin);
            }
            changes.read(in);
            in.close();
        }
        long end = System.currentTimeMillis();
        if(verbosity >= Verbosity.NORMAL)
        {
            System.err.format("Read %s in %s" , sourceFileName, Format.formatTimespan(end - start));
        }

    }
}
