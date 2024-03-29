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
import com.clarisma.common.util.Log;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.Project;
import com.geodesk.gol.build.ProjectReader;
// import com.geodesk.gol.update_old.*;
import com.geodesk.gol.update.Updater;
import org.xml.sax.SAXException;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

        Updater updater = new Updater(context);
        updater.update();

        /*
        TileCompiler compiler = new TileCompiler(context);
        compiler.compileAll();
        */

        /*
        ChangeModel changes = new ChangeModel(features.store());
        String oscFile = "c:\\geodesk\\research\\world-3803.osc.gz";

        TileFinder tileFinder = new TileFinder(context);
        ChangeReader5 reader5 = new ChangeReader5(context, tileFinder);
        reader5.read(oscFile, true);
        tileFinder.finish();
        Log.debug("TileFinder finished.");
        reader5 = null;

        tileFinder = new TileFinder(context);
        reader5 = new ChangeReader5(context, tileFinder);
        reader5.read(oscFile, true);
        reader5 = null;

        ChangeReader3 reader3 = new ChangeReader3(context);
        reader3.read(oscFile, true);
        reader3 = null;

        tileFinder = new TileFinder(context);
        ChangeReader4 reader4 = new ChangeReader4(context, tileFinder);
        reader4.read(oscFile, true);
        Log.debug("Waiting for TileFinder to finish...");
        tileFinder.finish();
        Log.debug("TileFinder finished.");
        reader4 = null;
        tileFinder = null;

        ChangeReader2 reader2 = new ChangeReader2(changes, context);
        reader2.read(oscFile, true);
        reader2 = null;

        Log.debug("CR4 without TF:");
        reader4 = new ChangeReader4(context, null);
        reader4.read(oscFile, true);
        reader4 = null;

        reader2 = new ChangeReader2(changes, context);
        reader2.read(oscFile, true);
        reader2 = null;

        reader3 = new ChangeReader3(context);
        reader3.read(oscFile, true);
        reader3 = null;


        // reader2.read("c:\\geodesk\\research\\de-3530.osc.gz", true);

        readFiles(changes);
        ChangeAnalyzer analyzer = new ChangeAnalyzer(changes, context);
        analyzer.analyze();

         */

        System.err.format("Processed updates in %s\n" , Format.formatTimespan(System.currentTimeMillis() - start));
    }
}
