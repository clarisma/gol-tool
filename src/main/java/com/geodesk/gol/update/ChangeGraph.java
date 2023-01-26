/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.index.IntIndex;
import com.clarisma.common.util.Log;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.Features;
import com.geodesk.feature.query.WorldView;
import com.geodesk.feature.store.*;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.Project;
import com.geodesk.gol.build.TileCatalog;
import org.eclipse.collections.api.list.primitive.LongList;
import org.eclipse.collections.api.map.primitive.*;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO: rename ChangeInventory?
//  Better name: RevisionSet, or just Revision


public class ChangeGraph
{
    /**
     * - All explicitly changed nodes (tags, x/y)
     * - All implicitly changed or created nodes (promoted to feature,
     *   demoted to anonymous, purgatory node)
     *   - An anonymous node is promoted to feature if in the future:
     *     - it has tags OR
     *     - it is a relation member OR
     *     - it is a "duplicate" OR
     *     - it is an "orphan"
     *   - A feature node is demoted to anonymous if in the future:
     *     - it has no tags AND
     *     - it isn't referenced by any relations AND
     *     - it is not a "duplicate" AND
     *     - it is not an "orphan"
     * - All nodes (potentially) referenced by relations
     */
    private final MutableLongObjectMap<CNode> nodes = new LongObjectHashMap<>();
    /**
     * - All explicitly changed ways (tags, node IDs)
     * - All implicitly changed ways (a way-node moved, was promoted to feature
     *   or demoted to anonymous)
     * - All ways (potentially) referenced by relations
     */
    private final MutableLongObjectMap<CWay> ways = new LongObjectHashMap<>();
    /**
     * - All explicitly changed relations (tags, member IDs, roles)
     * - All implicitly changed relations (member feature's geometry change
     *   caused the relation's bbox or quad to change)
     * - All relations (potentially) referenced by other relations
     */
    private final MutableLongObjectMap<CRelation> relations = new LongObjectHashMap<>();
    /**
     * A mapping of node IDs to past locations. Contains the locations of all
     * modified or deleted nodes, as well as all future nodes referenced by
     * new/modified ways.
     */
    private final MutableLongLongMap locations = new LongLongHashMap();
    /**
     * A mapping of x/y to one of the nodes (non-deterministic) located there
     * in the future. This helps us discover potential duplicate nodes.
     * (If more than one node lives at a location, we promote those that are
     * anonymous nodes to feature status, tagged with geodesk:duplicate)
     * // TODO: also orphans can be duplicate
     */
    private final MutableLongObjectMap<CNode> futureLocationToNode = new LongObjectHashMap<>();
    private final ObjectIntMap<String> globalStringsToCodes;
    private final String[] globalStrings;
    private final Map<String,String> localStrings = new HashMap<>();


    public ChangeGraph(FeatureStore store) throws IOException
    {
        globalStringsToCodes = store.stringsToCodes();
        globalStrings = store.codesToStrings();
    }

    public LongObjectMap<CNode> nodes()
    {
        return nodes;
    }

    public LongObjectMap<CWay> ways()
    {
        return ways;
    }

    public LongObjectMap<CRelation> relations()
    {
        return relations;
    }

    public LongLongMap locations()
    {
        return locations;
    }

    private CNode getNode(long id)
    {
        CNode node = nodes.get(id);
        if(node == null)
        {
            node = new CNode(id);
            nodes.put(id, node);
        }
        return node;
    }

    private CWay getWay(long id)
    {
        CWay way = ways.get(id);
        if(way == null)
        {
            way = new CWay(id);
            ways.put(id, way);
        }
        return way;
    }

    private CRelation getRelation(long id)
    {
        CRelation rel = relations.get(id);
        if(rel == null)
        {
            rel = new CRelation(id);
            relations.put(id, rel);
        }
        return rel;
    }

    private CFeature<?> getFeature(long typedId)
    {
        FeatureType type = FeatureId.type(typedId);
        long id = FeatureId.id(typedId);
        if(type == FeatureType.WAY)
        {
            return getWay(id);
        }
        if(type == FeatureType.NODE)
        {
            return getNode(id);
        }
        assert(type == FeatureType.RELATION);
        return getRelation(id);
    }

    // The strings delivered by the XML parser are all unique; looking them
    // up in the global string table and creating unique local strings
    // reduces required memory for strings by 95%
    private String getString(String s)
    {
        int code = globalStringsToCodes.getIfAbsent(s, -1);
        if(code >= 0)
        {
            s = globalStrings[code];
        }
        else
        {
            String exisiting = localStrings.putIfAbsent(s, s);
            if(exisiting != null) s = exisiting;
        }
        return s;
    }

    private String[] getTags(List<String> kv)
    {
        String[] tags = new String[kv.size()];
        for(int i=0; i<tags.length; i++)
        {
            tags[i] = getString(kv.get(i));
        }
        return tags;
    }

    public void changeNode(int version, long id, List<String> tags, int x, int y)
    {
        getNode(id).change(new CNode.Change(version, 0, getTags(tags), x, y));
    }

    public void deleteNode(int version, long id)
    {
        getNode(id).change(new CNode.Change(version, CFeature.DELETE, null, 0,0));
    }

    public void changeWay(int version, long id, List<String> tags, LongList nodes)
    {
        getWay(id).change(new CWay.Change(version, 0, getTags(tags), nodes.toArray()));
    }

    public void deleteWay(int version, long id)
    {
        getWay(id).change(new CWay.Change(version, CFeature.DELETE, null, null));
    }

    public void changeRelation(int version, long id, List<String> tags,
        LongList memberList, List<String> roleList)
    {
        CFeature<?>[] members = new CFeature[memberList.size()];
        for(int i=0; i<members.length; i++)
        {
            members[i] = getFeature(memberList.get(i));
        }
        getRelation(id).change(new CRelation.Change(version, 0, getTags(tags),
            members, getTags(roleList)));
            // TODO: We can use same method for tags and roles for now;
            //  either change approach or use more appropriate method name
    }

    public void deleteRelation(int version, long id)
    {
        getRelation(id).change(new CRelation.Change(version, CFeature.DELETE,
            null, null, null));
    }

    public void dump()
    {
        /*
        Log.debug("Total strings used: %d", globalStringUseCount + localStringUseCount);
        Log.debug("  %d global", globalStringUseCount);
        Log.debug("  %d local", localStringUseCount);
        Log.debug("    %d unique", localStrings.size());
         */
    }

    // TODO
    private void gatherLocations()
    {
        nodes.forEach(node -> locations.put(node.id(), -1));
        ways.forEach(way ->
        {
            CWay.Change change = way.change;
            if(change != null && change.nodeIds != null)
            {
                for(long nodeId: change.nodeIds) locations.put(nodeId, -1);
            }
        });
    }

    public void prepare()
    {
        gatherLocations();
    }
}
