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
import org.xml.sax.SAXException;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;

public class UpdateCommand extends GolCommand
{
    private String[] sourceFiles;

    // TODO: make optional
    @Parameter("1=source")
    public void source(String... sourceFiles)
    {
        this.sourceFiles = sourceFiles;
    }

    private void readFiles(ChangeGraph changes) throws IOException
    {
        if(verbosity >= Verbosity.NORMAL) System.err.print("Reading changes ...\r");
        long start = System.currentTimeMillis();

        for(String file : sourceFiles)
        {
            String ext = FileUtils.getExtension(file);
            if(ext.isEmpty())
            {
                if (readFile(changes, file + ".osc", false, false)) continue;
                if (readFile(changes, file + ".osc.gz", true, false)) continue;
                throw new IllegalArgumentException("No .osc or .osc.gz file found with name " + file);
            }
            switch(ext)
            {
            case "osc":
                readFile(changes, file, false, true);
                break;
            case "gz":
                readFile(changes, file, true, true);
                break;
            default:
                throw new IllegalArgumentException("Unknown change file format: " + file);
            }
        }

        if(verbosity >= Verbosity.NORMAL)
        {
            System.err.format("Read %,d file%s in %s\n" , sourceFiles.length,
                sourceFiles.length==1 ? "" : "s", Format.formatTimespan(
                    System.currentTimeMillis() - start));
        }
    }

    private boolean readFile(ChangeGraph changes, String file,
        boolean zipped, boolean mustExist) throws IOException
    {
        if (!mustExist && !Files.exists(Path.of(file))) return false;

        try (FileInputStream fin = new FileInputStream(file))
        {
            InputStream in = fin;
            if (zipped) in = new GZIPInputStream(fin);
            changes.read(in);
            in.close();
        }
        catch(SAXException ex)
        {
            throw new IOException("%s: Invalid file (%s)".formatted(file, ex.getMessage()));
        }
        return true;
    }


    @Override protected void performWithLibrary() throws Exception
    {
        long start = System.currentTimeMillis();
        InputStream settingsStream;
        // TODO: configurable
        settingsStream = getClass().getResourceAsStream("/com/geodesk/gol/default-config.fab");
        ProjectReader projectReader = new ProjectReader();
        projectReader.read(settingsStream);
        settingsStream.close();
        Project project = projectReader.project();

        // TODO
        project.set("updatable", "yes");

        // TODO: do we need a work path?
        BuildContext context = new BuildContext(features.store(), null, project);
        ChangeGraph changes = new ChangeGraph(context);
        readFiles(changes);
        changes.report();
        System.err.format("Processed updates in %s\n" , Format.formatTimespan(System.currentTimeMillis() - start));
    }
}
