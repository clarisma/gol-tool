/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SString;
import com.geodesk.core.Box;
import com.geodesk.core.Tile;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.build.Project;
import com.geodesk.gol.build.TileCatalog;
import org.eclipse.collections.api.map.primitive.*;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TTile
{
    private final int tile;
    private final TileCatalog tileCatalog;
    private final Project project;
    private final ObjectIntMap<String> globalStrings;
    private final MutableObjectIntMap<String> localStrings = new ObjectIntHashMap<>();
    private final List<SString> localStringList = new ArrayList<>();
    private final Box tileBounds;
    private final MutableLongLongMap coordinates = new LongLongHashMap();
    private final MutableLongObjectMap<TNode> nodes = new LongObjectHashMap<>();
    private final MutableLongObjectMap<TWay> ways = new LongObjectHashMap<>();
    private final MutableLongObjectMap<TRelation> relations = new LongObjectHashMap<>();
    private final Map<TTagTable, TTagTable> tagTables = new HashMap<>();
    private final Map<TRelationTable, TRelationTable> relationTables = new HashMap<>();

    public TTile(int tile, ObjectIntMap<String> globalStrings,
        TileCatalog tileCatalog, Project project)
    {
        this.tile = tile;
        this.globalStrings = globalStrings;
        this.tileCatalog = tileCatalog;
        this.project = project;
        if(tile != TileCatalog.PURGATORY_TILE)
        {
            tileBounds = Tile.bounds(tile);
        }
        else
        {
            tileBounds = Box.ofWorld();
        }
    }

    public int tile()
    {
        return tile;
    }

    public TileCatalog tileCatalog()
    {
        return tileCatalog;
    }

    public Bounds bounds()
    {
        return tileBounds;
    }

    public int globalStringCode(String str)
    {
        return globalStrings.getIfAbsent(str, -1);
    }

    public int addLocalString(SString str)
    {
        int code = localStringList.size();
        localStringList.add(str);
        localStrings.put(str.toString(), code);
        return code;
    }

    public int localStringCode(String str)
    {
        return localStrings.getIfAbsent(str, -1);
    }

    public SString localStringStruct(int code)
    {
        return localStringList.get(code);
    }

    public String localString(int code)
    {
        return localStringStruct(code).toString();
    }

    public void useLocalStringAsKey(int code)
    {
        localStringList.get(code).setAlignment(2);  // 4-byte aligned (1 << 2)
    }

    /**
     * Gets an existing tag table for the given tags, or creates a new one.
     *
     * @param tags  array of strings; keys at odd, values at even positions
     *                  // TODO: can be empty, but can it be null?
     * @return  the tag table
     */
    public TTagTable getTags(String[] tags)
    {
        return addTags(new TTagTable(this, tags));
    }

    public TTagTable addTags(TTagTable tagTable)
    {
        TTagTable existing = tagTables.putIfAbsent(tagTable, tagTable);
        return (existing != null) ? existing : tagTable;
    }

    public TRelationTable getRelationTable(TRelationTable rt)
    {
        TRelationTable existing = relationTables.get(rt);
        if (existing != null) return existing;
        relationTables.put(rt, rt);
        return rt;
    }

    public TNode getNode(long id)
    {
        TNode node = nodes.get(id);
        if (node == null)
        {
            node = new TNode(id);
            nodes.put(id, node);
        }
        return node;
    }

    public TWay getWay(long id)
    {
        TWay way = ways.get(id);
        if (way == null)
        {
            way = new TWay(id);
            ways.put(id, way);
        }
        return way;
    }

    public TRelation getRelation(long id)
    {
        TRelation rel = relations.get(id);
        if (rel == null)
        {
            rel = new TRelation(this, id);
            relations.put(id, rel);
        }
        return rel;
    }

    public TFeature getFeature(long typedId)
    {
        FeatureType type = FeatureId.type(typedId);
        long id = FeatureId.id(typedId);
        switch (type)
        {
        case NODE:
            return getNode(id);
        case WAY:
            return getWay(id);
        case RELATION:
            return getRelation(id);
        default:
            assert false;
            return null;
        }
    }


    // TODO: Don't expose these, cannot iterate while reading tile, because
    //  foreign relations may be added while we are iterating relations
    /*
    public Iterable<TNode> nodes()
    {
        return nodes.values();
    }

    public Iterable<TWay> ways()
    {
        return ways.values();
    }

    public Iterable<TRelation> relations()
    {
        return relations.values();
    }

     */
}
