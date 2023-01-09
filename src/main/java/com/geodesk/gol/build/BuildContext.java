/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.index.DenseInt16Index;
import com.clarisma.common.index.DensePackedIntIndex;
import com.clarisma.common.index.IntIndex;
import com.clarisma.common.io.MappedFile;
import com.clarisma.common.io.PileFile;
import com.geodesk.feature.store.FeatureStore;
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
    private Path indexPath;
    private Path idIndexPath;
    private Project project;
    private FeatureStore featureStore;
    private PileFile pileFile;
    private ObjectIntMap<String> globalStringMap;
    private PileFile linkerExportFile;
    private RandomAccessFile linkerImportFile;
    private TileCatalog tileCatalog;
    private IntIndex nodeIndex;
    private IntIndex wayIndex;
    private IntIndex relationIndex;

    private static final int IMPORT_DB_PAGE_SIZE = 1 << 16; // TODO: configurable
    private static final int LINKER_EXPORTS_PAGE_SIZE = 1 << 13; // TODO: configurable

    public BuildContext(Path golPath, Path workPath, Project project)
    {
        this.golPath = golPath;
        this.workPath = workPath;
        this.project = project;
        indexPath = Utils.peerFolder(golPath, "-indexes");
        idIndexPath = project.idIndexing() ? indexPath : workPath;
    }

    public BuildContext(FeatureStore store, Path workPath, Project project)
    {
        this.featureStore = store;
        this.golPath = store.path();
        this.workPath = workPath;
        this.project = project;
        indexPath = Utils.peerFolder(golPath, "-indexes");
        idIndexPath = project.idIndexing() ? indexPath : workPath;
    }

    public Path golPath()
    {
        return golPath;
    }

    public Path workPath()
    {
        return workPath;
    }

    public Path indexPath()
    {
        return indexPath;
    }

    public Project project()
    {
        return project;
    }

    public FeatureStore getFeatureStore() throws IOException
    {
        if(featureStore == null)
        {
            featureStore = new FeatureStore();
            featureStore.setPath(golPath);
            featureStore.openExclusive();
        }
        return featureStore;
    }

    public void createIndexes() throws IOException
    {
        assert nodeIndex == null;
        assert wayIndex == null;
        assert relationIndex == null;
        nodeIndex = openIndex("nodes.idx", 0, true);
        wayIndex = openIndex("ways.idx", 2, true);
        relationIndex = openIndex("relations.idx", 2, true);
    }

    public void closeIndexes() throws IOException
    {
        // TODO: clean this up (avoid ugly cast; we only use MappedFile-based index):
        if(nodeIndex != null) ((MappedFile)nodeIndex).close();
        if(wayIndex != null)  ((MappedFile)wayIndex).close();
        if(relationIndex != null)  ((MappedFile)relationIndex).close();
        nodeIndex = null;
        wayIndex = null;
        relationIndex = null;
    }

    public IntIndex getNodeIndex() throws IOException
    {
        if(nodeIndex == null) nodeIndex = openIndex("nodes.idx", 0, false);
        return nodeIndex;
    }

    public IntIndex getWayIndex() throws IOException
    {
        if(wayIndex == null) wayIndex = openIndex("ways.idx", 2, false);
        return wayIndex;
    }

    public IntIndex getRelationIndex() throws IOException
    {
        if(relationIndex == null) relationIndex = openIndex("relations.idx", 2, false);
        return relationIndex;
    }

    private IntIndex openIndex(String fileName, int extraBits, boolean create) throws IOException
    {
        int tileCount = getTileCatalog().tileCount();
        int bits = 32 - Integer.numberOfLeadingZeros(tileCount) + extraBits;
        Path path = idIndexPath.resolve(fileName);
        boolean exists = Files.exists(path);
        if(create)
        {
            if(exists) Files.delete(path);
        }
        else
        {
            if(!exists) return null;
        }
        return bits == 16 ? new DenseInt16Index(path) : new DensePackedIntIndex(path, bits);
    }

    public TileCatalog getTileCatalog() throws IOException
    {
        if(tileCatalog == null)
        {
            // TODO: check this behavior; we should always create the TileCatalog
            //  the same way (from FeatureStore); the text file should only be used
            //  for debugging
            if(featureStore != null)
            {
                tileCatalog = new TileCatalog(featureStore);
            }
            else
            {
                tileCatalog = new TileCatalog(workPath.resolve("tile-catalog.txt"));
            }
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
