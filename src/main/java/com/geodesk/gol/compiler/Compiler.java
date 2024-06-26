/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

import com.clarisma.common.io.PileFile;
import com.clarisma.common.pbf.PbfBuffer;
import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.store.BlobStoreConstants;
import com.clarisma.common.text.Format;
import com.geodesk.geom.Tile;
import com.geodesk.geom.TileQuad;
import com.geodesk.geom.Box;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.Tip;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.*;
import com.geodesk.gol.build.*;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.geodesk.gol.build.ProtoGol.*;

public class Compiler extends Processor<Compiler.Task>
{
    private final FeatureStore featureStore;
    private final Path rootPath;
    private final TileCatalog tileCatalog;
    private final List<String> keyStrings;
    private final List<String> valueStrings;
    private final List<String> roleStrings;
    private final ObjectIntMap<String> globalStrings;
    private final Project project;
    private final PileFile pileFile;
    private final PileFile linkerExportFile;
    private final PbfOutputStream linkerExportHeader = new PbfOutputStream();
    private final RandomAccessFile linkerImportFile;
    private Path debugPath;
    private final Path wayNodeIndexPath;
    private final boolean debug = false; // true;

    private static final int DEFAULT_LINK_DB_PAGE_SIZE = 1 << 13; // TODO: configurable

    public Compiler(BuildContext ctx) throws IOException
    {
        this.featureStore = ctx.getFeatureStore();
        this.rootPath = ctx.workPath();
        this.project = ctx.project();
        this.tileCatalog = ctx.getTileCatalog();
        this.pileFile = ctx.getPileFile();
        linkerImportFile = ctx.createLinkerImportFile();
        linkerExportFile = ctx.createLinkerExportFile();

        if(debug)
        {
            debugPath = rootPath.resolve("debug");
        }
        keyStrings = Files.readAllLines(rootPath.resolve("keys.txt"));
        valueStrings = Files.readAllLines(rootPath.resolve("values.txt"));
        roleStrings = Files.readAllLines(rootPath.resolve("roles.txt"));

        // TODO: check if we need to have "" in the table
        //  (currently not included)
        globalStrings = featureStore.stringsToCodes();
        wayNodeIndexPath = ctx.project().isUpdatable() ?
            ctx.indexPath().resolve("waynodes") : null;
    }

    protected class Task implements Runnable
    {
        private int pile;
        private int sourceTile;
        private int tip;
        private PbfBuffer sourceData;
        private FeatureTile archive;

        public Task(int pile, byte[] data)
        {
            this.pile = pile;
            sourceTile = tileCatalog.tileOfPile(pile);
            sourceData = new PbfBuffer(data);
        }

        // TODO: don't create a new String for empty string
        public String readPackedString(List<String> dictionary)
        {
            int n = (int) sourceData.readVarint();
            if ((n & 1) == 1)
            {
                return dictionary.get(n >>> 1);
            }
            int len = n >>> 1;
            return sourceData.readString(len);
        }

        public String[] readTags()
        {
            int len = (int) sourceData.readVarint();
            String[] tags = new String[len * 2];
            for (int i = 0; i < tags.length; i += 2)
            {
                tags[i] = readPackedString(keyStrings);
                tags[i + 1] = readPackedString(valueStrings);
            }
            return tags;
        }

        public String readRole()
        {
            return readPackedString(roleStrings);
        }

        // even though 0 is a valid tile (0/0/0, the root tile),
        // there will never be foreign nodes from this tile, because all
        // referring features would live there as well
        private void readNodes(int foreignTile)
        {
            long prevId = 0;
            int prevX = 0;
            int prevY = 0;
            for (; ; )
            {
                long id = sourceData.readVarint();
                if (id == 0) break;
                int featureFlag = (int) id & 1;
                id = prevId + (id >> 1);
                int x = (int) sourceData.readSignedVarint() + prevX;
                int y = (int) sourceData.readSignedVarint() + prevY;
                if (featureFlag != 0)
                {
                    if (foreignTile != 0)
                    {
                        archive.addForeignNode(foreignTile, id, x, y);
                    }
                    else
                    {
                        sourceData.readVarint();    // skip the payload size
                        String[] tags = readTags();
                        archive.addNode(id, tags, x, y);
                    }
                }
                else
                {
                    archive.setCoordinates(id, x, y);
                }
                prevId = id;
                prevX = x;
                prevY = y;
            }
        }

        private void readWays()
        {
            long prevId = 0;
            for (; ; )
            {
                long id = sourceData.readVarint();
                if (id == 0) break;
                int multiTileFlag = (int) id & 1;
                id = prevId + (id >> 1);
                int tileQuad = -1;
                if (multiTileFlag != 0)
                {
                    byte locator = sourceData.readByte();
                    tileQuad = TileQuad.fromDenseParentLocator(locator, sourceTile);
                }
                // TODO: we could determine if this way is a "ghost"
                //  by looking at any difference in zoom level
                int bodyLen = (int) sourceData.readVarint();
                int nodeCount = (int) sourceData.readVarint();
                int partialFlag = nodeCount & 1;

                long prevNodeId = 0;
                nodeCount >>>= 1;
                long[] nodeIds = new long[nodeCount];
                for (int i = 0; i < nodeCount; i++)
                {
                    long nodeId = sourceData.readSignedVarint() + prevNodeId;
                    nodeIds[i] = nodeId;
                    prevNodeId = nodeId;
                }
                if (partialFlag == 0)
                {
                    String[] tags = readTags();
                    archive.addWay(id, tags, nodeIds);
                }
                else
                {
                    archive.addForeignWay(tileQuad, id, nodeIds);
                }
                prevId = id;
            }
        }

        private void readRelation(long id)
        {
            byte tileLocator = sourceData.readByte();
            // TODO: full locator: readQuadLocator() ???
            int quad = TileQuad.fromDenseParentLocator(tileLocator, sourceTile);
            int bodyLen = (int) sourceData.readVarint();
            int memberCount = (int) sourceData.readVarint();
            long[] members = new long[memberCount];
            String[] roles = new String[memberCount];
            for (int i = 0; i < memberCount; i++)
            {
                // TODO: why not create members here, no need for temp arrays
                long member = sourceData.readVarint();
                String role = readRole();
                members[i] = member;
                roles[i] = role;
            }
            String[] tags = readTags();
            archive.addRelation(id, tags, members, roles);
        }

        /**
         * Processes proxies for ways (feature type 1) or relations (feature type 2). Node proxies are handled by
         * readForeignNodes()
         *
         * @param featureType 1 (ways) or 2 (relations)
         */
        private void readProxies(int featureType)
        {
            int donorPile = (int) sourceData.readVarint();
            int donorTile = tileCatalog.tileOfPile(donorPile);
            assert Tile.isValid(donorTile);
            assert donorTile != sourceTile : "Donor tile must be different from current tile";
            long prevId = 0;
            int prevX = 0;
            int prevY = 0;
            for (; ; )
            {
                long id = sourceData.readVarint();
                if (id == 0) break;
                int multiTileFlag = (int) id & 1;
                id = prevId + (id >> 1);

                int tileQuad;
                if (multiTileFlag != 0)
                {
                    byte locator = sourceData.readByte();
                    tileQuad = TileQuad.fromSparseSiblingLocator(locator, donorTile);
                }
                else
                {
                    tileQuad = TileQuad.fromSingleTile(donorTile);
                }

                int x1 = (int) sourceData.readSignedVarint() + prevX;
                int y1 = (int) sourceData.readSignedVarint() + prevY;
                int x2 = (int) sourceData.readVarint() + x1;
                int y2 = (int) sourceData.readVarint() + y1;
                // careful, always check signed vs. unsigned
                // x1/y1 are signed, x2/y2 unsigned (because they are always greater)
                prevId = id;
                prevX = x1;
                prevY = y1;

                Bounds bounds = new Box(x1, y1, x2, y2);
                if (featureType == 1)    // ways
                {
                    SWay way = archive.getWay(id);
                    way.setBounds(bounds);
                    way.setTileQuad(tileQuad);
                }
                else
                {
                    assert featureType == 2;    // relations
                    SRelation rel = archive.getRelation(id);
                    rel.setBounds(bounds);
                    rel.setTileQuad(tileQuad);
                }
            }
        }

        private int readQuadLocator()
        {
            byte locator = sourceData.readByte();
            if (locator == (byte) 0xff)
            {
                // complex locator that refers to an unrelated tile
                // (e.g. reference from missing member in the Purgatory
                // to the parent relation in a normal tile)
                return sourceData.readFixed32();
            }
            else
            {
                return TileQuad.fromDenseParentLocator(locator, sourceTile);
            }
        }

        private void readMembership(long relationId)
        {
            assert relationId > 0;
            int relQuad = readQuadLocator();
            long typedMemberId = sourceData.readVarint();

            assert TileQuad.isValid(relQuad) : "Invalid quad for relation/" + relationId;
            SRelation rel = archive.addForeignRelation(relQuad, relationId);
            SFeature member = archive.getFeature(typedMemberId);
            // member.markAsLocal();   // TODO: see below
            member.addParentRelation(rel);

            // A node that only serves as way coordinates
            // needs to be upgraded to a feature node once
            // it is a member of a relation
        }

        private void readRelations()
        {
            long prevId = 0;
            for (; ; )
            {
                long id = sourceData.readVarint();
                if (id == 0) break;
                int membershipFlag = (int) id & 1;
                id = prevId + (id >> 1);    // TODO: >> ?
                if (membershipFlag == 0)
                {
                    readRelation(id);
                }
                else
                {
                    readMembership(id);
                }
                prevId = id;
            }
        }

        private void readForeignNodes()
        {
            int donorPile = (int) sourceData.readVarint();
            int donorTile = tileCatalog.tileOfPile(donorPile);
            assert Tile.isValid(donorTile);
            assert donorTile != sourceTile : "Donor tile must be different from current tile";
            readNodes(donorTile);
        }

        private void readTile()
        {
            while (sourceData.hasMore())
            {
                int groupMarker = sourceData.readByte();
                int groupType = groupMarker & 7;
                int featureType = groupMarker >>> 3;
                if (groupType == LOCAL_FEATURES)
                {
                    if (featureType == NODES)
                    {
                        readNodes(0);
                    }
                    else if (featureType == WAYS)
                    {
                        readWays();
                    }
                    else if (featureType == RELATIONS)
                    {
                        readRelations();
                    }
                    else
                    {
                        // log.error("Unknown marker: {}", groupMarker);
                        break;
                    }
                }
                else if (groupType == FOREIGN_FEATURES)
                {
                    switch (featureType)
                    {
                    case NODES:
                        readForeignNodes();
                        break;
                    case WAYS:
                        readProxies(WAYS);
                        break;
                    case RELATIONS:
                        readProxies(RELATIONS);
                        break;
                    }
                }
                else
                {
                    // log.error("Unknown marker: {}", groupMarker);
                    break;
                }
            }
        }

        private void writeExports(IntObjectMap<PbfOutputStream> exports)
        {
            int sourceTile = tileCatalog.tileOfPile(pile);
            int sourceTip = tileCatalog.tipOfTile(sourceTile);
            synchronized (linkerExportFile)
            {
                exports.forEachKeyValue((targetTile, buf) ->
                {
                    int targetPile = tileCatalog.resolvePileOfTile(targetTile);
                    // TODO: use simpler method, 1:1 mapping without resolving

                    int exportDataSize = buf.size();
                    linkerExportHeader.writeFixed32(sourceTip);
                    linkerExportHeader.writeFixed32(exportDataSize / 12);
                    try
                    {
                        linkerExportFile.append(targetPile, linkerExportHeader.buffer(), 0, 8);
                        linkerExportFile.append(targetPile, buf.buffer(), 0, exportDataSize);
                    }
                    catch (IOException ex)
                    {
                        throw new RuntimeException(ex); // TODO
                    }
                    linkerExportHeader.reset();
                });
            }
        }

        private void writeImports(PbfOutputStream imports) throws IOException
        {
            synchronized (linkerImportFile)
            {
                linkerImportFile.writeInt(pile);
                // TODO: write start page?
                linkerImportFile.writeInt(imports.size());
                linkerImportFile.write(imports.buffer(), 0, imports.size());
            }
        }

        private void writeWayNodeIndex(PbfOutputStream out) throws IOException
        {
            // TODO: save to temp file first, then rename
            Path path = Tip.path(wayNodeIndexPath, tip, ".wnx");
            // TODO: make more efficient, avoid buffer copy
            //  (can use PbfOutputStream qwith ofs/len)
            Files.write(path, out.toByteArray());
        }

        private void dump(FeatureTile ft) throws IOException
        {
            Path folder = Tip.folder(debugPath, tip);
            Path path = Tip.path(debugPath, tip, ".txt");
            Files.createDirectories(folder);
            PrintWriter out = new PrintWriter(new FileWriter(path.toFile()));
            ft.dump(out);
            out.close();
        }

        public void run()
        {
            tip = tileCatalog.tipOfTile(sourceTile);
            archive = new FeatureTile(sourceTile, globalStrings, tileCatalog, project);
            readTile();
            archive.build();
            try
            {
                PbfOutputStream imports = writeTile();
                writeImports(imports);
                writeExports(archive.getExports());
                if (wayNodeIndexPath != null && sourceTile != TileCatalog.PURGATORY_TILE)
                {
                    writeWayNodeIndex(archive.createWayNodeIndex());
                }
                if (debug) dump(archive);
            }
            catch (IOException ex)
            {
                fail(ex);
            }
            completed(1);
        }

        private PbfOutputStream writeTile() throws IOException
        {
            FeatureStore store = featureStore;
            int payloadSize = archive.size() - 4;   // don't include 4-byte header
            int page = store.createTile(tip, payloadSize);
            PbfOutputStream imports = new PbfOutputStream();

            ByteBuffer buf = store.bufferOfPage(page);
            int ofs = store.offsetOfPage(page);

            // preserve the prev_blob_free flag in the blob's header word,
            // because Archive.writeToBuffer() will clobber it
            int oldHeader = buf.getInt(ofs);
            int prevBlobFreeFlag = oldHeader & BlobStoreConstants.PRECEDING_BLOB_FREE_FLAG;
            archive.structs().writeToBuffer(buf, ofs, imports);
            // put the flag back in
            int newHeader = buf.getInt(ofs);
            buf.putInt(ofs, newHeader | prevBlobFreeFlag);
            assert (oldHeader & ~BlobStoreConstants.PRECEDING_BLOB_FREE_FLAG) ==
                (newHeader & ~BlobStoreConstants.PRECEDING_BLOB_FREE_FLAG);
            return imports;
        }
    }

    @Override protected void feed() throws IOException
    {
        int tileCount = tileCatalog.tileCount();
        setTotalWork("Compiling", tileCount);
        for (int pile = 1; pile <= tileCount; pile++)
        {
            if(failed()) break;
            byte[] data = pileFile.load(pile);
            // TODO: empty tiles
            submit(new Task(pile, data));
        }
    }

    public void compileAll() throws IOException
    {
        // linkerImportFile.seek(0);       // TODO: not really needed?
        run();
        // TODO: flush linker file
        // TODO: close linker files if we split this part into separate Process

        // TODO: verbosity
        pileFile.close();
        System.err.format("Compiled %d tiles in %s\n",
            tileCatalog.tileCount(), Format.formatTimespan(timeElapsed()));
    }
}
