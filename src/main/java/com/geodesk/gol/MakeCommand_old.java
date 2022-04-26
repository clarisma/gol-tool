package com.geodesk.gol;

import com.clarisma.common.cli.BasicCommand;
import com.clarisma.common.cli.Option;
import com.clarisma.common.cli.Parameter;
import com.clarisma.common.make.Target;
import com.clarisma.common.validation.NumberValidator;
import com.geodesk.gol.build.Analyzer_old;
import com.geodesk.gol.build.Settings;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.build.Utils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static com.geodesk.gol.build.Utils.SETTINGS_FILE;
import static com.geodesk.gol.build.Utils.delete;

public class MakeCommand_old extends BasicCommand
{
    private static final int ANALYZE  = 0;
    private static final int PREPARE  = 1;
    private static final int SORT     = 2;
    private static final int VALIDATE = 3;
    private static final int COMPILE  = 4;
    private static final int LINK     = 5;

    private static final int NO_REBUILD = -1;

    private static final String[] TASKS =
    {
        "analyze", "prepare", "sort", "validate", "compile", "link"
    };

    // TODO: this is a config setting, remove
    // TODO: but need a way to keep the "-k" short form
    @Option("keep-work,k")
    protected boolean keepWork;
    private Path golPath;
    private Path sourcePath;
    private Path configPath;
    private Path workPath;
    private Path statePath;

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

    @Override public int perform() throws Exception
    {
        Settings config = createSettings();
        if(sourcePath != null)
        {
            config.set("source", sourcePath);
        }
        else
        {
            sourcePath = (Path)config.get("source");
            if(sourcePath == null)
            {
                throw new IllegalArgumentException(
                    "Must specify a source file (either as an argument or as " +
                        "\"source property\" in the configuration file)");
            }
        }

        if(configPath != null)
        {
            // config.read()
        }

        createWorkPath();
        Path binaryConfigPath = workPath.resolve(SETTINGS_FILE);
        if(Files.exists(binaryConfigPath))
        {
            Map<String,Object> prevSettings = Utils.readSettings(binaryConfigPath);
        }
        Utils.writeSettings(binaryConfigPath, config.toMap());

        return 0;
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
                throw new IOException(
                    "Failed to create work directory: " + workPath, ex);
            }
        }
        statePath = workPath.resolve("state.txt");
    }

    private Settings createSettings()
    {
        Settings s = new Settings();

        s.property(NO_REBUILD, "keep-work", false);

        s.property(ANALYZE, "source", null);
        s.property(ANALYZE, "source-timestamp", null);

        s.property(PREPARE, "max-tiles",
            NumberValidator.ofInteger(1, 16 * 1024 * 1024), 16 * 1024);
        s.property(PREPARE, "min-tile-density",
            NumberValidator.ofInteger(1, 10_000_000), 10_000);
        s.property(PREPARE, "max-strings",
            NumberValidator.ofInteger(256, 64 * 1024), 16 * 1024);
        s.property(PREPARE, "min-string-usage",
            NumberValidator.ofInteger(1, 100_000_000), 1000);
        s.property(PREPARE, "category-keys", String[].class);

        // TODO: We should store the page size in the pile file,
        //  so we don't have to rebuild

        // No! Page size must be power of 2, not modulo!
        s.property(SORT, "sort-page-size",
            new NumberValidator(4096, 1024 * 1024).modulo(4096), 64 * 1024);

        s.property(COMPILE, "rtree-bucket-size",
            NumberValidator.ofInteger(4, 256), 16);
        s.property(COMPILE, "max-key-indexes",
            NumberValidator.ofInteger(0, 32), 8);
        s.property(COMPILE, "key-index-min-features",
            NumberValidator.ofInteger(1, 1_000_000), 300);


        return s;
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
        Analyzer_old analyzer = new Analyzer_old();
    }

    private void prepare() throws Exception
    {
        // TODO

        if(!keepWork)
        {
            delete(workPath, "node-counts.txt", "string-counts.txt");
        }
    }

    private void sort() throws Exception
    {
        // TODO

        if(!keepWork)
        {
            delete(workPath,
                "nodes.idx", "ways.idx", "relations.idx");
        }
    }


    private void compile() throws Exception
    {
        // TODO

        if(!keepWork)
        {
            delete(workPath,
                "features.bin", "tile-catalog.txt",
                "global.txt", "keys.txt", "values.txt", "roles.txt");
        }
    }

    private class Source extends Target
    {
        Source()
        {
            super("source");
            dependsOn("source");
        }
    }

    private class Analyzed extends Target
    {
        Analyzed(Source source)
        {
            super("analyze");
            dependsOn(source);
        }

        @Override public void clean()
        {
            deleteWorkFile("node-counts.txt");
            deleteWorkFile("string-counts.txt");
            deleteWorkFile("stats.txt");
        }
    }

    private class Metadata extends Target
    {
        private TileCatalog tileCatalog;

        Metadata(Analyzed analyzed)
        {
            super("metadata");
            dependsOn(analyzed);
            dependsOn(
                "max-tiles", "max-tile-density", "zoom-levels",
                "max-strings", "min-string-usage",
                "excluded-keys", "category-keys");
        }

        @Override public void clean()
        {
            deleteWorkFile("global.txt");
            deleteWorkFile("keys.txt");
            deleteWorkFile("values.txt");
            deleteWorkFile("roles.txt");
            deleteWorkFile("tile-catalog.txt");
        }

        public TileCatalog getTileCatalog() throws IOException
        {
            if(tileCatalog == null)
            {
                tileCatalog = new TileCatalog(workPath().resolve("tile-catalog.txt"));
            }
            return tileCatalog;
        }
    }

    private class Sorted extends Target
    {
        Sorted(Metadata metadata)
        {
            super("sort");
            dependsOn(metadata);
            dependsOn("import-page-size");
        }

        @Override public boolean make(Map<String,Object> properties)
        {
            return false;   // TODO
        }

        @Override public void clean()
        {
            deleteWorkFile("nodes.idx");
            deleteWorkFile("ways.idx");
            deleteWorkFile("relations.idx");
        }
    }

    private class Validated extends Target
    {
        Validated(Sorted sorted)
        {
            super("sort");
            dependsOn(sorted);      // TODO: modifies
        }

        @Override public void clean()
        {
            deleteWorkFile("features.bin");
        }
    }

    private class Compiled extends Target
    {
        Compiled(Metadata metadata, Validated validated)
        {
            super("compile");
            dependsOn(metadata);
            dependsOn(validated);
        }

        @Override public void clean()
        {
            deleteWorkFile("imports.bin");
            deleteWorkFile("exports.bin");
        }
    }

    private class Linked extends Target
    {
        Linked(Compiled compiled)
        {
            super("link");
            dependsOn(compiled);      // TODO: modifies
        }
    }
}
