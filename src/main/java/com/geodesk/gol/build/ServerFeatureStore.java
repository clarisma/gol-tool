/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.pbf.PbfBuffer;
import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.soar.Archive;
import com.clarisma.common.soar.SBytes;
import com.clarisma.common.store.BlobStoreConstants;
import com.clarisma.common.util.Log;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.store.FeatureStore;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.api.map.primitive.LongIntMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

// TODO: better name
public class ServerFeatureStore extends FeatureStore
{
    // this needs to be synchronized

    // TODO: wrong, should use payload size, not archive size !!!!

    /**
     * Allocates a blob to store a tile and updates the tile index.
     *
     * Note that this operation does not use journaling, as it is
     * intended to be used only when building new GOLs.
     *
     * Note that `size` is the payload size, not the total blob size
     * (which includes a 4-byte header).
     *
     * @param tip       the tile's TIP
     * @param size      the payload size of the tile
     * @return          the page of the newly allocated tile
     */
    public synchronized int createTile(int tip, int size)
    {
        int page = allocateBlob(size);
        int p = tileIndexPointer() + tip * 4;
        baseMapping.putInt(p, page << 1);
        return page;
    }

    // not synchronized, safe as long as each thread works on a different tile
    public void writeBlob(int page, Archive structs, PbfOutputStream imports) throws IOException
    {
        ByteBuffer buf = bufferOfPage(page);
        int ofs = offsetOfPage(page);

        // preserve the prev_blob_free flag in the blob's header word,
        // because Archive.writeToBuffer() will clobber it
        int oldHeader = buf.getInt(ofs);
        int prevBlobFreeFlag = oldHeader & BlobStoreConstants.PRECEDING_BLOB_FREE_FLAG;
        structs.writeToBuffer(buf, ofs, imports);
        // put the flag back in
        int newHeader = buf.getInt(ofs);
        buf.putInt(ofs, newHeader | prevBlobFreeFlag);
        assert (oldHeader & ~BlobStoreConstants.PRECEDING_BLOB_FREE_FLAG) ==
            (newHeader & ~BlobStoreConstants.PRECEDING_BLOB_FREE_FLAG);
    }

    // not synchronized, safe as long as each thread works on a different tile
    public void fixTileLinks(int importingTip, PbfBuffer imports, IntObjectMap<LongIntMap> exports)
    {
        int page = tilePage(importingTip);
        assert page != 0;
        ByteBuffer buf = bufferOfPage(page);
        int ofs = offsetOfPage(page);

        while(imports.hasMore())
        {
            int linkPos = imports.readFixed32();
            int tipAndShift = imports.readFixed32();
            int shift = tipAndShift & 0xf;
            int tip = tipAndShift >>> 4;
            long typedId = imports.readFixed64();
            LongIntMap targets = exports.get(tip);
            if(targets == null)
            {
                if(tip != 0)
                {
                    Log.warn("No exports for tip %06X, can't resolve %s at %06X/%08X",
                        tip, FeatureId.toString(typedId), importingTip, linkPos);
                }
                continue;
            }
            // assert targets != null: "No exports for tip " + tip;
            int targetPos = targets.get(typedId);
            if(targetPos == 0)
            {
                Log.warn("%s has not been exported by tile %06X, can't resolve at %06X/%08X",
                    FeatureId.toString(typedId), tip, importingTip, linkPos);
                continue;
            }

            /*
            if(typedId == FeatureId.ofWay(705988446))
            {
                log.debug("Imported {} from tile {}: Position: {} (Shifted: {})",
                    FeatureId.toString(typedId), Tip.toString(tip),
                    targetPos, shift);
            }
             */

            int p = linkPos + ofs;
            int flags = buf.getInt(p);
            buf.putInt(p, (targetPos << shift) | flags);
        }
    }

    public static void create(BuildContext ctx) throws IOException
    {
        Project project = ctx.project();
        Path workPath = ctx.workPath();
        Archive archive = new Archive();
        SFeatureStoreHeader header = new SFeatureStoreHeader(project);
        archive.setHeader(header);

        // TODO: properties

        SBytes tileIndex = new SBytes(Files.readAllBytes(
            workPath.resolve("tile-index.bin")), 2);
        archive.place(tileIndex);
        header.tileIndex = tileIndex;

        SBytes indexSchema = project.keyIndexSchema().encode(ctx.getGlobalStringMap());
        archive.place(indexSchema);
        header.indexSchema = indexSchema;

        SBytes stringTable = StringTableBuilder.encodeStringTable(
            Files.readAllLines(workPath.resolve("global.txt")));
        archive.place(stringTable);
        header.stringTable = stringTable;

        header.setMetadataSize(archive.size());
        archive.writeFile(ctx.golPath());
    }
}
