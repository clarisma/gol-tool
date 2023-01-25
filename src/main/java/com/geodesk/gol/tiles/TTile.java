/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.clarisma.common.soar.StructWriter;
import com.geodesk.core.Box;
import com.geodesk.core.Tile;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.build.Project;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.compiler.SIndexTree;
import com.geodesk.gol.compiler.SRelation;
import org.eclipse.collections.api.map.primitive.*;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;

import java.io.IOException;
import java.util.*;

public class TTile
{
    private final int tile;
    private final TileCatalog tileCatalog;
    private final IndexSettings indexSettings;
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
    Header header;

    public class Header extends Struct
    {
        int payloadSize;
        TIndex nodeIndex;
        TIndex wayIndex;
        TIndex areaIndex;
        TIndex relationIndex;

        Header()
        {
            setSize(32);
        }

        @Override public void write(StructWriter out)
        {
            out.writeInt(payloadSize);    // payload size (excluding first 4 bytes)
            out.writeInt(0);    // TODO
            // TODO: We're going to remove flags, pointers are always to a Root
            //  or null
            /*
            out.writePointer(nodeIndex, 1);
            out.writePointer(wayIndex, 1);
            out.writePointer(areaIndex, 1);
            out.writePointer(relationIndex, 1);
             */
            writeIndexPointer(out, nodeIndex);
            writeIndexPointer(out, wayIndex);
            writeIndexPointer(out, areaIndex);
            writeIndexPointer(out, relationIndex);
            out.writeInt(0);    // TODO
            out.writeInt(0);    // TODO
        }

        private static void writeIndexPointer(StructWriter out, TIndex index)
        {
            if(index.isEmpty())
            {
                out.writeInt(0);
                return;
            }
            // TODO: We're going to remove flags, pointers are always to a Root
            //  or null
            out.writePointer(index, 1);
        }
    }

    public TTile(int tile, ObjectIntMap<String> globalStrings,
        TileCatalog tileCatalog, IndexSettings indexSettings)
    {
        this.tile = tile;
        this.globalStrings = globalStrings;
        this.tileCatalog = tileCatalog;
        this.indexSettings = indexSettings;
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

    public Iterable<TTagTable> tagTables()
    {
        return tagTables.values();
    }

    public Iterable<SString> localStrings()
    {
        return localStringList;
    }

    public Iterable<TRelationTable> relationTables()
    {
        return relationTables.values();
    }

    /*
    public String globalString(int code)
    {
        return globalStrings.getIfAbsent(str, -1);
    }
     */

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

    public void build()
    {
        TIndex nodeIndex = new TIndex(indexSettings);
        TIndex wayIndex = new TIndex(indexSettings);
        TIndex areaIndex = new TIndex(indexSettings);
        TIndex relationIndex = new TIndex(indexSettings);

        // relations.forEach(SRelation::addToMembers); // TODO

        nodes.forEach(node ->
        {
            node.setLocation(0);
            node.build(this);
            if (!node.isForeign()) nodeIndex.add(node);
        });

        ways.forEach(way ->
        {
            // While building the way, it may turn foreign

            way.setLocation(0);
            way.build(this);
            if (!way.isForeign())
            {
                way.body().setLocation(0);
                (way.isArea() ? areaIndex : wayIndex).add(way);
            }
        });

        // buildRelations();  // TODO
        relations.forEach(rel ->
        {
            rel.setLocation(0);
            if (!rel.isForeign())
            {
                rel.body().setLocation(0);
                (rel.isArea() ? areaIndex : relationIndex).add(rel);
            }
        });

        tagTables.values().forEach(tt -> tt.setLocation(0));
        localStringList.forEach(s -> s.setLocation(0));
        relationTables.values().forEach(rt -> rt.setLocation(0));

        // for(TRelationTable rt : relationTables.values()) rt.build();

        nodeIndex.build();
        wayIndex.build();
        areaIndex.build();
        relationIndex.build();

        header = new Header();
        header.nodeIndex     = nodeIndex;
        header.wayIndex      = wayIndex;
        header.areaIndex     = areaIndex;
        header.relationIndex = relationIndex;

        FeatureLayout layout = new FeatureLayout(this);
        layout.layout();
        header.payloadSize = layout.size() - 4;
    }
}
