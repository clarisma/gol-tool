/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.*;
import com.clarisma.common.io.FileUtils;
import com.clarisma.common.io.PileFile;
import com.clarisma.common.text.Format;
import com.clarisma.common.util.Log;
import com.geodesk.core.Tile;
import com.geodesk.core.TileQuad;
import com.geodesk.gol.build.*;
import com.geodesk.gol.compiler.Compiler;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.geodesk.gol.build.Utils.*;

public class BuildCommand extends BasicCommand
{
    private static final int ANALYZE  = 0;
    private static final int PREPARE  = 1;
    private static final int SORT     = 2;
    private static final int VALIDATE = 3;
    private static final int COMPILE  = 4;
    private static final int LINK     = 5;
    private static final int DONE     = 6;

    private static final int NO_REBUILD = -1;

    private static final String[] TASKS =
    {
        "analyze", "prepare", "sort", "validate", "compile", "link", "done"
    };

    private static final int DEFAULT_SORT_DB_PAGE_SIZE = 1 << 16; // TODO: configurable

    // TODO: this is a config setting, remove
    // TODO: but need a way to keep the "-k" short form
    @Option("keep-work,k")
    protected boolean keepWork;
    private Path golPath;
    private Path sourcePath;
    private Path configPath;
    private Path workPath;
    private Path statePath;
    private Project project;
    private BuildContext context;
    private List<String> buildOptions;

    @Parameter("0=gol")
    public void library(String filename)
    {
        golPath = Utils.golPath(filename);
    }

    // TODO: make optional
    @Parameter("1=source")
    public void source(String filename)
    {
        sourcePath = Utils.pathWithExtension(filename, ".osm.pbf");
    }

    @Option("config,c=file")
    public void configFile(String filename)
    {
        configPath = Utils.pathWithExtension(filename, ".fab");
    }

    @Override public int perform() throws Throwable
    {
        if (configPath == null)
        {
            configPath = Path.of(FileUtils.replaceExtension(golPath.toString(), ".fab"));
            if (!Files.exists(configPath)) configPath = null;
        }
        InputStream configStream;
        if (configPath == null)
        {
            configStream = getClass().getResourceAsStream(
                "/com/geodesk/gol/default-config.fab");
        }
        else
        {
            configStream = new FileInputStream(configPath.toFile());
        }

        // TODO: unify & simplify
        ProjectReader projectReader = new ProjectReader();
        projectReader.read(configStream);
        configStream.close();
        project = projectReader.project();

        // Set the verbosity level specified on the command line
        project.verbosity(verbosity);

        if(buildOptions != null) applyBuildOptions();

        if(sourcePath != null)
        {
            project.sourcePath(sourcePath);
        }
        else
        {
            sourcePath = project.sourcePath();
            if(sourcePath == null)
            {
                throw new IllegalArgumentException(
                    "Must specify a source file (either as an argument or as " +
                        "\"source\" property in the configuration file)");
            }
        }

        System.out.format("Building %s from %s using %s...\n",
            golPath, sourcePath, configPath==null ?
                "default settings" : configPath);

        // TODO: respect config setting
        createWorkPath();
        project.workPath(workPath);

        // Path projectPath = workPath.resolve("project.bin");
        // project.write(projectPath);
            // TODO: can't serialize Path objects
            //  use another serialization method

        context = new BuildContext(golPath, workPath, project);

        int startTask = readState();

        long start = System.currentTimeMillis();
        if (startTask <= ANALYZE) analyze();
        if (startTask <= PREPARE) prepare();
        if (startTask <= VALIDATE) sort();
            // If Validator fails, restart Sorter, because the Validator
            // may leave features.bin in inconsistent state
        if (startTask <= VALIDATE) validate();
        if (startTask <= COMPILE) compile();
        if (startTask <= LINK) link();
        writeState(DONE);
        context.close();

        if(!keepWork)
        {
            delete(workPath, "state.txt", "stats.txt", "tile-map.html", "tile-index.bin");
            try
            {
                Files.delete(workPath);
            }
            catch(IOException ex)
            {
                if(verbosity >= Verbosity.NORMAL)
                {
                    Log.warn("Unable to remove work folder: %s\n", ex.getMessage());
                }
            }
        }

        if(verbosity >= Verbosity.QUIET)
        {
            System.err.format("Built %s in %s\n", golPath, Format.formatTimespan(
                System.currentTimeMillis() - start));
        }
        return 0;
    }

    @Override public int error(Throwable ex)
    {
        return ErrorReporter.report(ex, verbosity);
    }

    private void createWorkPath() throws IOException
    {
        workPath = golPath.resolveSibling(golPath.getFileName() + ".work");
        if(Files.notExists(workPath))
        {
            try
            {
                Files.createDirectories(workPath);
            }
            catch (IOException ex)
            {
                throw new IOException(String.format(
                    "Failed to create work directory %s (%s: %s)",
                    workPath, ex.getClass().getSimpleName(), ex.getMessage()), ex);
            }
        }
        statePath = workPath.resolve("state.txt");
    }


    private int readState() throws IOException
    {
        if(Files.exists(statePath))
        {
            Properties props = new Properties();
            props.load(new FileInputStream(statePath.toFile()));
            String task = props.getProperty("task");
            for (int i=0; i<TASKS.length; i++)
            {
                if(task.equals(TASKS[i])) return i;
            }
        }
        return 0;
    }

    private void writeState(int task) throws IOException
    {
        String props = String.format("task=%s\n", TASKS[task]);
        try(FileOutputStream out = new FileOutputStream(statePath.toFile()))
        {
            out.write(props.getBytes(StandardCharsets.UTF_8));
        }
    }


    private void analyze() throws Exception
    {
        writeState(ANALYZE);
        Analyzer analyzer = new Analyzer(project);
        analyzer.analyze();
    }

    private void prepare() throws Exception
    {
        writeState(PREPARE);
        StringTableBuilder stb = new StringTableBuilder();
        stb.readStrings(workPath.resolve("string-counts.txt"));
        stb.writeStringTables(
            workPath.resolve("keys.txt"), workPath.resolve("values.txt"),
            workPath.resolve("roles.txt"), workPath.resolve("global.txt"),
            project.maxStringCount());

        TileIndexBuilder tib = new TileIndexBuilder();
        tib.buildTileTree(
            workPath.resolve("node-counts.txt"),
            project.zoomLevels(),
            project.maxTiles(), project.minTileDensity());
        tib.writeTileIndex(workPath.resolve("tile-index.bin"));
        tib.writeTileCatalog(workPath.resolve("tile-catalog.txt"));
        // TODO: don't write map anymore
        tib.createTileMap(workPath.resolve("tile-map.html"),
            TileQuad.fromSingleTile(Tile.fromString("0/0/0")));

        if(!keepWork)
        {
            delete(workPath, "node-counts.txt", "string-counts.txt");
        }
    }

    private void sort() throws Exception
    {
        writeState(SORT);
        PileFile pileFile = context.createPileFile();
        Sorter sorter = new Sorter(workPath, project.verbosity(),
            context.getTileCatalog(), pileFile);
        sorter.sortFeatures(project.sourcePath().toFile());

        if(!keepWork)
        {
            delete(workPath,
                "nodes.idx", "ways.idx", "relations.idx");
        }
    }

    private void validate() throws Throwable
    {
        writeState(VALIDATE);
        Validator validator = new Validator(context, verbosity);
        validator.validate();
    }

    /**
     * Removes local keys from the KeyIndexSchema.
     *
     * This is done to avoid problems as described in
     * https://github.com/clarisma/gol-tool/issues/9
     *
     * This should be refactored, since we are modifying settings in the
     * Project. We should ideally treat Project as immutable.
     *
     * Make sure this step is performed if we split the Compiler into a
     * separate executable (This will likely read settings in binary form).
     *
     * @throws IOException if global string table fails to load
     */
    private void normalizeIndexedKeys() throws IOException
    {
        KeyIndexSchema schema = context.project().keyIndexSchema();
        schema.removeLocalKeys(context.getGlobalStringMap());
    }

    private void compile() throws Exception
    {
        writeState(COMPILE);
        normalizeIndexedKeys();
        ServerFeatureStore.create(context);
        Compiler compiler = new Compiler(context);
        compiler.compileAll();

        if(!keepWork)
        {
            delete(workPath,
                "features.bin", "tile-catalog.txt",
                "global.txt", "keys.txt", "values.txt", "roles.txt");
        }
    }

    private void link() throws Exception
    {
        writeState(LINK);
        Linker linker = new Linker(context);
        linker.linkAll();
        context.closeLinkerFiles();

        if(!keepWork)
        {
            delete(workPath, "imports.bin", "exports.bin");
        }
    }

    @Override public void setOption(String name, String value)
    {
        if(buildOptions == null) buildOptions = new ArrayList<>();
        buildOptions.add(name);
        buildOptions.add(value);
    }

    /**
     * Overrides the build settings with options specified on the command line.
     */
    private void applyBuildOptions()
    {
        for(int i = 0; i< buildOptions.size(); i+=2)
        {
            String name = buildOptions.get(i);
            String value = buildOptions.get(i+1);
            try
            {
                if(!project.set(name, value))
                {
                    throw new IllegalArgumentException("Unknown option");
                }
            }
            catch(Exception ex)
            {
                throw new IllegalArgumentException(String.format("%s: %s",
                    name, ex.getMessage()));
            }
        }
    }
}
