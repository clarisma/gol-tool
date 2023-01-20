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
import java.util.ArrayList;
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
    private int[] tipDeltas;
    private int hashCode;

    public TRelationTable(List<TRelation> relations, int[] tipDeltas, int size)
    {
        this.relations = relations;
        this.tipDeltas = tipDeltas;
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
}
