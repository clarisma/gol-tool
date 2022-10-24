package com.geodesk.gol.build;

import com.clarisma.common.io.PileFile;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class BuildContext
{
    private Path golPath;
    private Path workPath;
    private Project project;
    private ServerFeatureStore featureStore;
    private PileFile pileFile;
    private ObjectIntMap<String> globalStringMap;
    private PileFile linkerExportFile;
    private RandomAccessFile linkerImportFile;
    private TileCatalog tileCatalog;

    private static final int IMPORT_DB_PAGE_SIZE = 1 << 16; // TODO: configurable
    private static final int LINKER_EXPORTS_PAGE_SIZE = 1 << 13; // TODO: configurable

    public BuildContext(Path golPath, Path workPath, Project project)
    {
        this.golPath = golPath;
        this.workPath = workPath;
        this.project = project;
    }

    public Path golPath()
    {
        return golPath;
    }

    public Path workPath()
    {
        return workPath;
    }

    public Project project()
    {
        return project;
    }

    public ServerFeatureStore getFeatureStore() throws IOException
    {
        if(featureStore == null)
        {
            featureStore = new ServerFeatureStore();
            featureStore.setPath(golPath);
            featureStore.openExclusive();
        }
        return featureStore;
    }

    public TileCatalog getTileCatalog() throws IOException
    {
        if(tileCatalog == null)
        {
            tileCatalog = new TileCatalog(workPath.resolve("tile-catalog.txt"));
        }
        return tileCatalog;
    }

    public PileFile createPileFile() throws IOException
    {
        assert pileFile == null;
        int pageSize = IMPORT_DB_PAGE_SIZE; // TODO: tkae form config
        pileFile = PileFile.create(workPath.resolve("features.bin"),
            getTileCatalog().tileCount(), pageSize);
        return pileFile;
    }


    public PileFile getPileFile() throws IOException
    {
        if(pileFile == null)
        {
            pileFile = PileFile.openExisiting(workPath.resolve("features.bin"));
        }
        return pileFile;
    }

    public ObjectIntMap<String> getGlobalStringMap() throws IOException
    {
        if(globalStringMap == null)
        {
            List<String> strings = Files.readAllLines(workPath.resolve("global.txt"));
            MutableObjectIntMap<String> map = new ObjectIntHashMap<>(strings.size());
            for (int i = 0; i < strings.size(); i++)
            {
                map.put(strings.get(i), i + 1);    // 1-based index
            }
            map.put("", 0);       // TODO: is this correct?
            globalStringMap = map;
        }
        return globalStringMap;
    }

    public RandomAccessFile createLinkerImportFile() throws IOException
    {
        assert linkerImportFile == null;
        Path path = workPath.resolve("imports.bin");
        Files.deleteIfExists(path);
        linkerImportFile = new RandomAccessFile(path.toFile(), "rw");
        return linkerImportFile;
    }

    public RandomAccessFile getLinkerImportFile() throws IOException
    {
        if(linkerImportFile == null)
        {
            linkerImportFile = new RandomAccessFile(
                workPath.resolve("imports.bin").toFile(), "rw");
        }
        return linkerImportFile;
    }

    public PileFile createLinkerExportFile() throws IOException
    {
        assert linkerExportFile == null;
        linkerExportFile = PileFile.create(workPath.resolve("exports.bin"),
            getTileCatalog().tileCount(), LINKER_EXPORTS_PAGE_SIZE);
        return linkerExportFile;
    }

    public PileFile getLinkerExportFile() throws IOException
    {
        if(linkerExportFile == null)
        {
            linkerExportFile = PileFile.openExisiting(workPath.resolve("exports.bin"));
        }
        return linkerExportFile;
    }

    public void closeLinkerFiles() throws IOException
    {
        if(linkerImportFile != null)
        {
            linkerImportFile.close();
            linkerImportFile = null;
        }
        if(linkerExportFile != null)
        {
            linkerExportFile.close();
            linkerExportFile = null;
        }
    }

    /*
    public void deleteSorterFiles() throws IOException
    {
        closePileFile();
        Files.deleteIfExists(workPath.resolve("features.bin"));
        Files.deleteIfExists(workPath.resolve("nodes.idx"));
        Files.deleteIfExists(workPath.resolve("ways.idx"));
        Files.deleteIfExists(workPath.resolve("relations.idx"));
    }

     */

    public void disposeLinkerFiles() throws IOException
    {
        closeLinkerFiles();
        if(!project.keepWork())
        {
            Files.deleteIfExists(workPath.resolve("imports.bin"));
            Files.deleteIfExists(workPath.resolve("exports.bin"));
        }
    }

    private void closePileFile() throws IOException
    {
        if(pileFile != null)
        {
            pileFile.close();
            pileFile = null;
        }
    }

    public void close() throws IOException
    {
        tileCatalog = null;
        closePileFile();
        if(featureStore != null)
        {
            featureStore.close();
            featureStore = null;
        }
        closeLinkerFiles();
    }
}
