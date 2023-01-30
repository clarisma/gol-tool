/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.util.Log;
import com.geodesk.feature.match.TypeBits;
import com.geodesk.feature.store.FeatureConstants;
import com.geodesk.feature.store.FeatureStore;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

// TODO: Pointers are relative to buffer start, NOT tile start
//  Will need to be adjusted if we ever use a keep-in-place approach
//  (Currently, the TileCompiler will recalculate the layout, so this
//  is not an issue)

// TODO: Consider making this class more general; move the compiler-specific
//  parts into TTile?
//  Idea: general part is TileReader, specific is TileStructReader

public class TileReader
{
    private final TTile tile;
    private final FeatureStore store;
    private final ByteBuffer buf;
    private final int pTile;
    private final int pTileEnd;
    private final MutableIntIntMap localStringsByPointer = new IntIntHashMap();
    private final MutableIntObjectMap<TFeature> features = new IntObjectHashMap<>();
    private final MutableLongObjectMap<TFeature> foreignFeatures = new LongObjectHashMap<>();
    private final MutableIntObjectMap<TTagTable> tagTables = new IntObjectHashMap<>();
    private final MutableIntObjectMap<TRelationTable> relTables = new IntObjectHashMap<>();

    private final List<TFeature> currentFeatures = new ArrayList<>();
    private final MutableIntList currentTips = new IntArrayList();
    private final MutableIntList currentRoles = new IntArrayList();
    private int currentTip = FeatureConstants.START_TIP;

    // TODO: remove
    public TileReader(TTile tile, FeatureStore store, ByteBuffer buf, int pTile)
    {
        this.tile = tile;
        this.store = store;
        this.buf = buf;
        this.pTile = pTile;
        int tileLength = (buf.getInt(pTile) & 0x3fff_ffff) + 4;  // TODO: generalize
        pTileEnd = pTile + tileLength;
    }

    public TileReader(TTile tile, FeatureStore store, int tip)
    {
        int tilePage = store.fetchTile(tip);
        this.tile = tile;
        this.store = store;
        this.buf = store.bufferOfPage(tilePage);
        this.pTile = store.offsetOfPage(tilePage);
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
        try
        {
            readIndex(pTile + 8, TypeBits.NODES);
            readIndex(pTile + 12, TypeBits.NONAREA_WAYS);
            readIndex(pTile + 16, TypeBits.AREAS);
            readIndex(pTile + 20, TypeBits.NONAREA_RELATIONS);
            for (TFeature f : features.values())
            {
                f.readBody(this);
            }
            /*
            Log.debug("Read %d local features and %d foreign features.",
                features.size(), foreignFeatures.size());
             */
        }
        catch (Throwable ex)
        {
            Log.debug(ex.getMessage());
            ex.printStackTrace();
        }
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
                TFeature feature = readFeature(headerBits, allowedTypes);
                feature.readStub(this, p);
                features.put(p, feature);
            }
            catch(InvalidTileException ex)
            {
                throw new InvalidTileException(p, ex.getMessage());
            }
            int flags = (int)headerBits;
            if((flags & 1) != 0) break;
            if(allowedTypes == TypeBits.NODES)
            {
                p += 20 + (flags & 4);
                // If Node is member of relation (flag bit 2), add
                // extra 4 bytes for the relation-table pointer
            }
            else
            {
                p += 32;
            }
        }
    }

    private void checkType(int typeMask, int allowedTypes)
    {
        if((typeMask & allowedTypes) == 0)
        {
            throw new InvalidTileException("Unexpected feature type (%08X)".formatted(typeMask));
        }
    }

    public TFeature readFeature(long headerBits, int allowedTypes)
    {
        int typeMask = TypeBits.fromFeatureFlags((int)headerBits);
        checkType(typeMask, allowedTypes);
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

    protected TFeature getFeature(int p, int allowedTypes)
    {
        TFeature feature = features.get(p);
        if(feature == null)
        {
            throw new InvalidTileException(p, "No feature stub located here");
        }
        int typeMask = TypeBits.fromFeatureFlags(feature.flags);
        checkType(typeMask, allowedTypes);
        return feature;
    }

    protected TFeature getForeignFeature(int tip, int ofs, int acceptedTypes)
    {
        long key = ((long)tip << 32) | ofs;
        TFeature feature = foreignFeatures.get(key);
        if(feature != null) return feature;
        int tilePage = store.fetchTile(tip);
        ByteBuffer foreignBuf = store.bufferOfPage(tilePage);
        int pFeature = store.offsetOfPage(tilePage) + ofs;
        feature = readFeature(foreignBuf.getLong(pFeature), acceptedTypes);
        // TODO: store TIP/quad in foreign feature
        foreignFeatures.put(key, feature);
        return feature;
    }

    protected static final int LOCAL_TILE = Integer.MIN_VALUE;
    protected static final int LAST_FLAG = 1;
    protected static final int FOREIGN_FLAG = 2;
    protected static final int DIFFERENT_ROLE_FLAG = 4;
    protected static final int DIFFERENT_TILE_FLAG = 8;

    protected int readTipDelta(int p, int direction)
    {
        int tipDelta = buf.getShort(p);
        if ((tipDelta & 1) != 0)
        {
            // wide TIP delta
            p += direction * 2;
            tipDelta &= 0xffff;
            tipDelta |= ((int)buf.getShort(p)) << 16;
                // Beware of signed/unsigned here
                // The lower part must be treated as unsigned; the
                // upper part contains the sign
            p += 2;
        }
        tipDelta >>= 1;     // signed shift; gets rid of the flag bit
        currentTip += tipDelta;
        return p;
    }

    public void resetTables()
    {
        currentFeatures.clear();
        currentTips.clear();
        currentRoles.clear();
        currentTip = FeatureConstants.START_TIP;
    }

    /**
     * Reads entries from a MemberTable, RelationTable or FeatureNodeTable.
     *
     * Prior to this method:
     * - `currentTableSize` must be 0
     * - `currentTip` must be `FeatureConstants.START_TIP`
     * - `currentFeatures`, `currentTipDeltas` and `currentRoles` must be empty
     * - All local features must be read and indexed in `features`
     *
     * This method fills `currentFeatures` and `currentTips` (and optionally
     * `currentRoles`) with the contents of the encoded table.
     *
     * @param p             pointer to the first 4-byte element in the table
     * @param shift         the shift to apply to obtain a pointer to a local feature
     *                      (3 for MemberTable, 2 for the others)
     *                      TODO: may change in 0.2
     * @param direction     the direction in which to traverse the table
     *                      (backward = -1  for FeatureNodeTable,
     *                       forward  =  1  for the others)
     * @param allowedTypes  a bitmask of the allowed feature types
     * @param roles         indicates whether roles should be retrieved
     *                      (true for MemberTable, false for the others)
     *
     * @return the pointer to the next byte after the table (if step2 is positive),
     *   or the address of the next hypothetical 4-byte entry ahead of the table
     *   (i.e. the absolute difference between `p` and the return equals the
     *   size of the table)
     *
     * @throws InvalidTileException if the local or foreign pointers are invalid,
     *   local pointers don't reference a (previously loaded) local feature,
     *   or the referenced feature's type is not compatible with the table
     */
    public int readFeatureTable(int p, int shift, int direction, int allowedTypes, boolean roles)
    {
        int currentRole = 0;

        // Step to take after local feature ref (or foreign feature ref
        // in same tile as previous):  +4 if forward, -4 if backward
        int stepAfter = 4 * direction;

        // Step to take after foreign feature in a different tile:
        // +4 if forward, -2 if backward
        int stepAfterTileChange = 3 * direction + 1;

        int lowerShift = shift-1;
        int pointerMask = (0xffff_ffff >> lowerShift) << lowerShift;
        for(;;)
        {
            int entry = buf.getInt(p);
            try
            {
                if ((entry & FOREIGN_FLAG) == 0)
                {
                    // local feature
                    int pFeature = (p & pointerMask) + ((entry >> shift) << lowerShift);
                    currentFeatures.add(getFeature(pFeature, allowedTypes));
                    currentTips.add(LOCAL_TILE);
                    // move pointer to next feature ref (+4 if forward, -4 if backward)
                    p += stepAfter;
                }
                else
                {
                    if ((entry & DIFFERENT_TILE_FLAG) != 0)
                    {
                        // If foreign feature is in a different tile, move
                        // pointer to the lower 2 bytes of the TIP delta
                        // (+4 if forward, -2 if backward)
                        p += stepAfterTileChange;
                        // read the TIP; pointer returned points to lower or upper
                        // part; we move the pointer to the next feature ref
                        // (+2 if forward, -4 if backward)
                        p = readTipDelta(p, direction) + stepAfterTileChange - 2;
                    }
                    else
                    {
                        // no change in tile
                        // move pointer to next feature ref (+4 if forward, -4 if backward)
                        p += stepAfter;
                    }
                    currentFeatures.add(getForeignFeature(currentTip,
                        (entry >>> 4) << 2, allowedTypes));
                    currentTips.add(currentTip);
                }
            }
            catch(InvalidTileException ex)
            {
                throw new InvalidTileException(
                    p, "Invalid feature reference: " + ex.getMessage());
            }
            if(roles)
            {
                if((entry & DIFFERENT_ROLE_FLAG) != 0)
                {
                    int rawRole = buf.getChar(p);
                    if ((rawRole & 1) != 0)
                    {
                        // common role
                        currentRole = rawRole ^ 1;
                            // flag bit is reversed internally: 0 = global string
                        p += 2;
                    }
                    else
                    {
                        // uncommon role
                        rawRole = buf.getInt(p);
                        currentRole = (readString(p + (rawRole >> 1)) << 1) | 1;
                            // signed shift (rawRole contains relative pointer)
                            // flag bit is reversed internally: 1 = local string
                        //Log.debug("Read uncommon role: %s", tile.localString(currentRole >>> 1));
                        p += 4;
                    }
                }
                currentRoles.add(currentRole);
            }
            if((entry & LAST_FLAG) != 0) break;
        }
        return p;
    }

    protected TRelationTable readRelationTable(int pTable)
    {
        TRelationTable relTable = relTables.get(pTable);
        if(relTable == null)
        {
            int pEnd = readFeatureTable(pTable, 2, 1, TypeBits.RELATIONS, false);
            List<TRelation> relations = new ArrayList<>(currentFeatures.size());
            for(TFeature f: currentFeatures) relations.add((TRelation)f);
            relTable = new TRelationTable(relations, getCurrentTips(), pEnd - pTable);
            resetTables();
            relTable = tile.getRelationTable(relTable);
            relTables.put(pTable, relTable);
            // TODO: add to tile!
            // TODO: check for duplicates? (error condition)
        }
        // TODO: + ref count
        return relTable;
    }

    protected TRelationTable readRelationTableIndirect(int ppTable)
    {
        int pTable = buf.getInt(ppTable) + ppTable;
        checkPointer(pTable);
        return readRelationTable(pTable);
    }

    public int[] getCurrentTips()
    {
        return currentTips.toArray();
    }

    public TFeature[] getCurrentFeatures()
    {
        return currentFeatures.toArray(new TFeature[0]);
    }

    public int[] getCurrentRoles()
    {
        return currentRoles.toArray();
    }

    public TNode[] getCurrentNodes()
    {
        int nodeCount = currentFeatures.size();
        TNode[] nodes = new TNode[nodeCount];
        for(int i=0; i<nodeCount; i++) nodes[i] = (TNode)currentFeatures.get(i);
        return nodes;
    }


}
