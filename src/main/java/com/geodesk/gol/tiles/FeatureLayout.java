/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.SharedStruct;
import com.clarisma.common.soar.Struct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FeatureLayout extends StructLayout
{
    protected TTile tile;
    protected List<Struct> tempList = new ArrayList<>();

    public FeatureLayout(TTile tile)
    {
        super(tile.header);
        this.tile = tile;
    }

    private static <T extends SharedStruct> List<T> gatherShared(Iterable<T> structs)
    {
        List<T> shared = new ArrayList<>();
        for(T item: structs)
        {
            if(item.userCount() > 2) shared.add(item);
        }
        return shared;
    }


    protected void placeFeatureBody(TFeature f)
    {
        TTagTable tags = f.tags();
        if(tags.location() == 0) place(tags);
        tags.gatherStrings(tempList);
        if(f instanceof TRelation rel) rel.gatherStrings(tempList);
        for(Struct s: tempList) if(s.location() == 0) place(s);
        tempList.clear();
        Struct body = f.body();
        if(body != null) place(body);
        TRelationTable relTable = f.relations();
        if(relTable != null && relTable.location() == 0)
        {
            place(relTable);
        }
    }

    protected void placeFeatureBodies(TIndex.Leaf leaf)
    {
        TFeature f = leaf.firstChild();
        do
        {
            placeFeatureBody(f);
            f = (TFeature) f.next();
        }
        while (f != null);
    }

    protected void placeFeatureBodies(TIndex.Trunk branch)
    {
        TIndex.Branch[] children = branch.children();
        for(TIndex.Branch child: children)
        {
            if (child instanceof TIndex.Leaf leaf)
            {
                placeFeatureBodies(leaf);
            }
            else
            {
                placeFeatureBodies((TIndex.Trunk) child);
            }
        }
    }

    protected void placeFeatureBodies(TIndex index)
    {
        if(index == null) return;
        index.forEachTrunk(this::placeFeatureBodies);
    }

    private void placeSpatialIndex(TIndex.Branch branch)
    {
        place(branch);
        TIndex.Branch[] children = branch.children();
        if(children == null)
        {
            TFeature f = ((TIndex.Leaf)branch).firstChild();
            do
            {
                TTagTable tags = f.tags();
                if(tags.userCount() < 3 && tags.location() == 0) place(tags);
                f = (TFeature)f.next();
            }
            while(f != null);
        }
        else
        {
            for (TIndex.Branch child : children) placeSpatialIndex(child);
        }
    }

    private void placeIndex(TIndex index)
    {
        if(index == null) return;
        place(index);
        index.forEachTrunk(this::placeSpatialIndex);
    }

    public void layout()
    {
        maxDrift(2048);
        placeIndex(tile.header.nodeIndex);
        placeIndex(tile.header.wayIndex);
        placeIndex(tile.header.areaIndex);
        placeIndex(tile.header.relationIndex);
        flush();

        maxDrift(Integer.MAX_VALUE);        // for shared features, drift doesn't matter

        List<TTagTable> sharedTags = gatherShared(tile.tagTables());
        Collections.sort(sharedTags);
        for(TTagTable tags: sharedTags)
        {
            assert tags.location() >= 0:
                String.format("Marked (%d) but not placed: %s", tags.location(), tags);
            if(tags.location() == 0) place(tags);
        }

        List<SString> sharedStrings = gatherShared(tile.localStrings());
        Collections.sort(sharedStrings);

        // place key strings (4-byte aligned) first; better odds of placing without gaps
        //  TODO: does it make a difference?
        for(SString s: sharedStrings)
        {
            if(s.alignment() > 0) place(s);
        }
        for(SString s: sharedStrings)
        {
            if (s.location() == 0) place(s);
        }

        List<TRelationTable> sharedRelTables = gatherShared(tile.relationTables());
        for(TRelationTable rt: sharedRelTables) place(rt);
        flush();

        maxDrift(2048);
        placeFeatureBodies(tile.header.nodeIndex);
        placeFeatureBodies(tile.header.wayIndex);
        placeFeatureBodies(tile.header.areaIndex);
        placeFeatureBodies(tile.header.relationIndex);

        flush();
    }

}



