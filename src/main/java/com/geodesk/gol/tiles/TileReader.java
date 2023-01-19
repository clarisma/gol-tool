/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.Struct;
import com.geodesk.feature.match.TypeBits;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.StoredFeature;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.nio.ByteBuffer;

public class TileReader
{
    private final TTile tile;
    private final FeatureStore store;
    private final ByteBuffer buf;
    private final int pTile;
    private final int pTileEnd;
    // private final MutableIntObjectMap<Struct> structs = new IntObjectHashMap<>();
    private final MutableIntIntMap localStringsByPointer = new IntIntHashMap();
    private final MutableIntObjectMap<TFeature> features = new IntObjectHashMap<>();
    private final MutableLongObjectMap<TFeature> foreignFeatures = new LongObjectHashMap<>();
    private final MutableIntObjectMap<TTagTable> tagTables = new IntObjectHashMap<>();

    public TileReader(TTile tile, FeatureStore store, ByteBuffer buf, int pTile)
    {
        this.tile = tile;
        this.store = store;
        this.buf = buf;
        this.pTile = pTile;
        int tileLength = (buf.getInt(pTile) & 0x3fff_ffff) + 4;  // TODO: generalize
        pTileEnd = pTile + tileLength;
    }

    public TTile tile()
    {
        return tile;
    }

    public ByteBuffer buf()
    {
        return buf;
    }

    public void checkPointer(int p)
    {
        if(p < pTile || p >= pTileEnd)
        {
            throw new InvalidTileException(
                "Pointer out of range (%d): Must be >= %d and < %d"
                    .formatted(p, pTile, pTileEnd));
        }
    }

    private void typeError(int p, Struct struct, Class<?> expectedType)
    {
        throw new InvalidTileException(p - pTile,
            "Invalid type %s (Expected %s)".formatted(
                struct.getClass().getSimpleName(), expectedType.getSimpleName()));
    }

    /**
     * Gets the local-key code for the string at the given position.
     * If the Tile does not contain this string yet, it is created and
     * added to the local-string index.
     *
     * @param p     pointer to the string (absolute buffer position)
     * @return      the code of the local string
     *              // TODO: is 0 valid? (it is for globals = "")
     */
    public int readString(int p)
    {
        int code = localStringsByPointer.getIfAbsent(p, -1);
        if(code < 0)
        {
            checkPointer(p);
            SString str = SString.read(buf, p);
            code = tile.addLocalString(str);
            localStringsByPointer.put(p, code);
            // TODO: store in structs as well?
        }
        return code;
    }

    public void read()
    {
        readIndex(pTile + 8, TypeBits.NODES);
        readIndex(pTile + 12, TypeBits.NONAREA_WAYS);
        readIndex(pTile + 16, TypeBits.AREAS);
        readIndex(pTile + 20, TypeBits.NONAREA_RELATIONS);
    }

    private void readIndex(int ppIndex, int allowedTypes)
    {
        int p = buf.getInt(ppIndex);
        if(p == 0) return;
        if((p & 1) == 0)
        {
            readRoot(ppIndex, allowedTypes);
            return;
        }
        p = ppIndex + (p ^ 1);
        for(;;)
        {
            int last = buf.getInt(p) & 1;
            readRoot(p, allowedTypes);
            if(last != 0) break;
            p += 8;
        }
    }

    private void readRoot(int ppRoot, int allowedTypes)
    {
        int ptr = buf.getInt(ppRoot);
        if (ptr != 0)
        {
            if ((ptr & 2) != 0)
            {
                readLeaf(ppRoot + (ptr & 0xffff_fffc), allowedTypes);
            }
            else
            {
                readBranch(ppRoot + (ptr & 0xffff_fffc), allowedTypes);
            }
        }
    }

    private void readBranch(int p, int allowedTypes)
    {
        for (;;)
        {
            int ptr = buf.getInt(p);
            int last = ptr & 1;
            if ((ptr & 2) != 0)
            {
                readLeaf(p + (ptr ^ 2 ^ last), allowedTypes);
            }
            else
            {
                readBranch(p + (ptr ^ last), allowedTypes);
            }
            if (last != 0) break;
            p += 20;
        }
    }

    private void readLeaf(int p, int allowedTypes)
    {
        p += allowedTypes == TypeBits.NODES ? 8 : 16;
        for(;;)
        {
            long headerBits = buf.getLong(p);
            try
            {
                TFeature feature = getFeature(headerBits, allowedTypes);
                feature.readStub(this, p);
                features.put(p, feature);
            }
            catch(InvalidTileException ex)
            {
                throw new InvalidTileException(p, ex.getMessage());
            }
            int flags = (int)headerBits;
            if((flags & 1) != 0) break;
            p += 20 + (flags & 4);
            // If Node is member of relation (flag bit 2), add
            // extra 4 bytes for the relation table pointer
        }
    }

    public TFeature getFeature(long headerBits, int allowedTypes)
    {
        int typeMask = TypeBits.fromFeatureFlags((int)headerBits);
        if((typeMask & allowedTypes) == 0)
        {
            throw new InvalidTileException("Unexpected feature type");
        }
        long id = ((headerBits & 0xffff_ff00L) << 24) | (headerBits >>> 32);
        TFeature feature;
        if((typeMask & TypeBits.NODES) != 0)
        {
            feature = tile.getNode(id);
        }
        else
        {
            if((typeMask & TypeBits.WAYS) != 0)
            {
                feature = tile.getWay(id);
            }
            else
            {
                assert (typeMask & TypeBits.RELATIONS) != 0;
                feature = tile.getRelation(id);
            }
        }
        return feature;
    }

    protected TTagTable readTagsIndirect(int ppTags)
    {
        int ptr = buf.getInt(ppTags);
        int uncommonKeysFlag = ptr & 1;
        int pTags = (ptr ^ uncommonKeysFlag) + ppTags;
        checkPointer(pTags);
        TTagTable tags = tagTables.get(pTags);
        if(tags == null)
        {
            tags = new TTagTable(this, pTags, uncommonKeysFlag);
            tags = tile.addTags(tags);
            tagTables.put(pTags, tags);
        }
        return tags;
    }

    protected TFeature getForeignFeature(int tip, int ofs, int acceptedTypes)
    {
        long key = ((long)tip << 32) | ofs;
        TFeature feature = foreignFeatures.get(key);
        if(feature != null) return feature;
        int tilePage = store.fetchTile(tip);
        ByteBuffer foreignBuf = store.bufferOfPage(tilePage);
        int pFeature = store.offsetOfPage(tilePage) + ofs;
        feature = getFeature(foreignBuf.getLong(pFeature), acceptedTypes);
        // TODO: store TIP/quad in foreign feature
        foreignFeatures.put(key, feature);
        return feature;
    }
}
