/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SharedStruct;
import com.clarisma.common.soar.StructOutputStream;

import java.io.IOException;
import java.util.List;

public class TRelationTable extends SharedStruct
{
    /**
     * The list of relations, sorted by ID. Does not contain duplicates.
     */
    private final List<TRelation> relations;
    /**
     * A representation of the Relation Table in canonical form.
     * The upper half of each item contains the TIP of a foreign relation
     * (or -1 if local); the lower half contains the index in `relations`
     * where the actual relation reference is stored.
     * This array is sorted first by TIP (locals first), then by ID.
     * Since `relations` is already sorted by ID, a lower index position
     * represents a lower ID, so we can use default ordering for sorting.
     */
    private int[] tips;
    private int hashCode;

    public TRelationTable(List<TRelation> relations, int[] tips, int size)
    {
        this.relations = relations;
        this.tips = tips;
        setSize(size);
        setAlignment(1);    // 2-byte aligned (1 << 1)
    }

    /**
     * Inserts a new relation into this RelationTable, maintaining sort
     * order by ID. If the table already contains the relation, it will
     * not be added again.
     *
     * @param relation  the relation to add
     */
    public void addRelation(TRelation relation)
    {
        assert hashCode == 0 : "hashcode already calculated, cannot modify reltable afterwards";
        assert size()==0 : "Table has already been built";
        int i = 0;
        long id = relation.id();
        for(; i<relations.size(); i++)
        {
            long otherId = relations.get(i).id();
            if(id < otherId) break;
            if(id == otherId) return;
        }
        relations.add(i, relation);
    }

    @Override public void writeTo(StructOutputStream out) throws IOException
    {
        // TODO
    }

    @Override public int hashCode()
    {
        if(hashCode == 0)
        {
            hashCode = 17;
            for(TRelation rel: relations)
            {
                hashCode = 37 * hashCode + Long.hashCode(rel.id());
            }
            if(hashCode == 0) hashCode = 1; // TODO: needed?
        }
        return hashCode;
    }

    @Override public boolean equals(Object o)
    {
        if(o instanceof TRelationTable other) return relations.equals(other.relations);
        return false;
    }

    /*
    @Override public void write(StructWriter out)
    {
        assert hashCode != 0: "Table has not been built";
        int currentTip = FeatureConstants.START_TIP;

        // TODO: first foreign ref must always indicate tile change even
        //  if its tip is START_TIP
        int count = tipDeltas.length;
        for(int i=0; i< count; i++)
        {
            TRelation rel = relations.get(i);
            int tipDelta = tipDeltas[i];
            int flags = (i == count-1) ? 1 : 0;   // last-item flag
            if(tipDelta == TileReader.LOCAL_TILE)
            {
                assert !rel.isForeign();
                out.writeTaggedPointer(rel, 2, flags);
            }
            else
            {
                assert rel.isForeign();
                flags |= TileReader.FOREIGN_FLAG;
                if (tipDelta != 0) flags |= TileReader.DIFFERENT_TILE_FLAG;
                long typedId = FeatureId.ofRelation(rel.id());
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

     */
}
