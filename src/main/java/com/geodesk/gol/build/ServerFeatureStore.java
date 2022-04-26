package com.geodesk.gol.build;

import com.clarisma.common.pbf.PbfBuffer;
import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.soar.Archive;
import com.clarisma.common.soar.SBytes;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.store.FeatureStoreBase;
import com.geodesk.gol.compiler.Tip;
import org.eclipse.collections.api.map.primitive.IntObjectMap;
import org.eclipse.collections.api.map.primitive.LongIntMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

// TODO: better name
public class ServerFeatureStore extends FeatureStoreBase
{
    // this needs to be synchronized
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
        // TODO: preserve prev_blob_free flag
        ByteBuffer buf = bufferOfPage(page);
        int ofs = offsetOfPage(page);
        structs.writeToBuffer(buf, ofs, imports);
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
                    log.warn("No exports for tip {}, can't resolve {} at {}",
                        String.format("%06X", tip), FeatureId.toString(typedId),
                        String.format("%06X/%08X", importingTip, linkPos));
                }
                continue;
            }
            // assert targets != null: "No exports for tip " + tip;
            int targetPos = targets.get(typedId);
            if(targetPos == 0)
            {
                log.warn("{} has not been exported by tile {}, can't resolve at {}",
                    FeatureId.toString(typedId), String.format("%06X", tip),
                    String.format("%06X/%08X", importingTip, linkPos));
                continue;
            }
            // assert targetPos != 0: String.format("%s has not been exported by tile %d",
            //    FeatureId.toString(typedId), tip);
        
                /*
                log.debug(String.format("Resolved link at %08X to %s at %06X/%08X",
                    linkPos, FeatureId.toString(typedId), tip, targetPos));

                 */

            if(typedId == FeatureId.ofWay(705988446))
            {
                log.debug("Imported {} from tile {}: Position: {} (Shifted: {})",
                    FeatureId.toString(typedId), Tip.toString(tip),
                    targetPos, shift);
            }

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
