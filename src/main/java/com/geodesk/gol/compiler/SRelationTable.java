/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

import com.clarisma.common.soar.SharedStruct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.feature.FeatureId;
import com.geodesk.core.Tile;
import com.geodesk.core.TileQuad;
import com.geodesk.feature.store.FeatureConstants;
import com.geodesk.feature.store.Tip;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class SRelationTable extends SharedStruct implements Iterable<SRelation>
{
    private List<SRelation> relations = new ArrayList<>();
    private int hashCode;
    private RelationRef[] refs;

    private static final int LOCAL_TILE = Integer.MIN_VALUE;

    private static class RelationRef implements Comparable<RelationRef>
    {
        SRelation relation;
        int tip;

        @Override public int compareTo(RelationRef o)
        {
            int comp = Integer.compare(tip, o.tip);
            if(comp != 0) return comp;
            return Long.compare(relation.id(), o.relation.id());
        }
    }

    public void addRelation(SRelation relation)
    {
        assert hashCode == 0 : "hashcode already calculated, cannot modify reltable afterwards";
        assert size()==0 : "Table has already been built";
        relations.add(relation);
    }

    private void normalize()
    {
        // Sort relations by ID and remove duplicates

        // TODO: make features sortable by id
        relations.sort((a,b) -> Long.compare(a.id(), b.id()));
        long prevId = Long.MIN_VALUE;
        for(int i=relations.size()-1; i>=0; i--)
        {
            long id = relations.get(i).id();
            if(id==prevId)
            {
                relations.remove(i);
                continue;
            }
            prevId = id;
        }
    }

    // TODO: pass localTile, tileCatalog instead of FeatureTile
    //  This would narrow the scope and decouple from FeatureTile
    public void build(FeatureTile ft)
    {
        assert size() == 0: "Table has already been built";
        assert hashCode != 0:   "Table must be normalized";
        assert !relations.isEmpty();
        refs = new RelationRef[relations.size()];
        int localTile = ft.tile();
        for(int i=0; i<refs.length; i++)
        {
            RelationRef ref = new RelationRef();
            SRelation rel = relations.get(i);
            if(rel.isForeign())
            {
                int relQuad = rel.tileQuad();
                int relTile;
                if(TileQuad.coversTile(relQuad, localTile))
                {
                    relTile = Tile.zoomedOut(localTile, Tile.zoom(relQuad));
                }
                else
                {
                    // TODO: Bug: Purgatory items may be referencing tile
                    //  where the parent relation does not live, if its
                    //  tile quad is sparse
                    relTile = TileQuad.blackTile(relQuad);
                }
                ref.tip = ft.tileCatalog().tipOfTile(relTile);
                assert ref.tip != 0: String.format("Invalid TIP for %s (Tile: %s)",
                    rel, Tile.toString(relTile));
            }
            else
            {
                ref.tip = LOCAL_TILE;
            }
            ref.relation = rel;
            refs[i] = ref;
        }
        Arrays.sort(refs);

        int size = relations.size() * 4;

        int prevTip = LOCAL_TILE;

        for(RelationRef ref: refs)
        {
            if(ref.tip != prevTip)
            {
                size += 2;
                if(prevTip == LOCAL_TILE) prevTip = FeatureConstants.START_TIP;
                int tipDelta = ref.tip - prevTip;
                if(FeatureTile.isWideTipDelta(tipDelta)) size += 2;
                prevTip = ref.tip;
            }
        }
        assert size > 0;
        setSize(size);
        setAlignment(1);		// align on 2-byte boundary (1 << 1)
    }

    public int hashCode()
    {
        if(hashCode == 0)
        {
            normalize();
            hashCode = 17;
            for(SFeature rel: relations)
            {
                hashCode = 37 * hashCode + Long.hashCode(rel.id());
            }
            if(hashCode == 0) hashCode = 1; // TODO: needed?
        }
        return hashCode;
    }

    public boolean equals(Object other)
    {
        if(!(other instanceof SRelationTable)) return false;
        return relations.equals(((SRelationTable)other).relations);
    }


    public int countForeignRelations()
    {
        int count = 0;
        for(SRelation r: relations)
        {
            if(r.isForeign()) count++;
        }
        return count;
    }

    public int countUniqueForeignTiles()
    {
        int count = 0;
        int prevTip = LOCAL_TILE;

        for(RelationRef ref: refs)
        {
            if(ref.tip == LOCAL_TILE) continue;
            if(ref.tip == prevTip) continue;
            count++;
            prevTip = ref.tip;
        }
        return count;
    }

    public String toString()
    {
        int foreignRelations = countForeignRelations();
        String strForeign = foreignRelations > 0 ?
            String.format(" (%d foreign in %d tiles)", foreignRelations,
                countUniqueForeignTiles()) : "";
        return String.format("RELTABLE with %d relation%s%s", relations.size(),
            relations.size()==1 ? "" : "s", strForeign);
    }

    public void dump(PrintWriter out)
    {
        super.dump(out);
        int pos = location();
        int prevTip = -1;
        for(RelationRef ref: refs)
        {
            String change = " ";
            String s;
            int extra = 0;
            if(ref.tip == LOCAL_TILE)
            {
                s = "local ";
            }
            else
            {
                if (prevTip != ref.tip)
                {
                    if (prevTip == -1) prevTip = FeatureConstants.START_TIP;
                    int tipDelta = ref.tip - prevTip;
                    change = "*";
                    extra = FeatureTile.isWideTipDelta(tipDelta) ? 4 : 2;
                    prevTip = ref.tip;
                }
                s = Tip.toString(ref.tip);
            }
            out.format("%08X    %s %s %s\n", pos, change, s, ref.relation);
            pos += 4 + extra;
        }
    }

    public void writeTo(StructOutputStream out) throws IOException
    {
        assert size() > 0: "Table has not been built";
        int prevTip = LOCAL_TILE;
        for(int i=0; i< refs.length; i++)
        {
            RelationRef ref = refs[i];
            int flags = (i == refs.length-1) ? 1 : 0;   // last-item flag
            if(ref.tip == LOCAL_TILE)
            {
                out.writeTaggedPointer(ref.relation, 2, flags);
                assert !ref.relation.isForeign();
            }
            else
            {
                assert ref.relation.isForeign();
                flags |= 2;
                if (ref.tip != prevTip) flags |= 8;
                if (prevTip == LOCAL_TILE) prevTip = FeatureConstants.START_TIP;
                long typedId = FeatureId.ofRelation(ref.relation.id());
                out.writeForeignPointer(ref.tip, typedId, 2, flags);
                    // TODO: pointer occupies top 28 bits, but we only
                    //  shift by 2 because it is 4-byte aligned
                    // TODO: unify handling of pointers, this is too confusing
                if ((flags & 8) != 0)
                {
                    int tipDelta = ref.tip - prevTip;
                    if (FeatureTile.isWideTipDelta(tipDelta))
                    {
                        out.writeInt((tipDelta << 1) | 1);
                    }
                    else
                    {
                        out.writeShort(tipDelta << 1);
                    }
                    prevTip = ref.tip;
                }
            }
        }
    }

    public int relationCount()
    {
        assert hashCode != 0: "Table has not been normalized, there may be duplicate entries";
        return relations.size();
    }

    public Iterator<SRelation> iterator()
    {
        return relations.iterator();
    }
}
