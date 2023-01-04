/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.geodesk.core.Mercator;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.ByteBuffer;

// TODO: rename ChangeInventory?

public class ChangeGraph
{
    private final MutableLongObjectMap<CNode> nodes = new LongObjectHashMap<>();
    private final MutableLongObjectMap<CWay> ways = new LongObjectHashMap<>();
    private final MutableLongObjectMap<CRelation> relations = new LongObjectHashMap<>();

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

    private class Reader extends ChangeSetReader
    {
        private boolean hasLaterChange(CFeature<?> f)
        {
            return f.change != null && f.change.version > version;
        }

        @Override protected void node(ChangeType change, long id, double lon, double lat, String[] tags)
        {
            CNode node = getNode(id);
            if(hasLaterChange(node)) return;
            int x = (int)Math.round(Mercator.xFromLon(lon));
            int y = (int)Math.round(Mercator.yFromLat(lat));
            node.change = new CNode.Change(change, version, tags, x, y);
        }

        @Override protected void way(ChangeType change, long id, String[] tags, long[] nodeIds)
        {
            CWay way = getWay(id);
            if(hasLaterChange(way)) return;
            way.change = new CWay.Change(change, version, tags, nodeIds);
        }

        @Override protected void relation(ChangeType change, long id, String[] tags, long[] memberIds, String[] roles)
        {
            CRelation rel = getRelation(id);
            if(hasLaterChange(rel)) return;
            CFeature<?>[] members = new CFeature[memberIds.length];
            for(int i=0; i<memberIds.length; i++)
            {
                members[i] = getFeature(memberIds[i]);
            }
            rel.change = new CRelation.Change(change, version, tags, members, roles);
        }
    }

    public void read(String fileName) throws IOException, SAXException
    {
        new Reader().read(fileName);
    }

    /*
    private class FindFeaturesTask extends TileScanner
    {

        public FindFeaturesTask(ByteBuffer buf, int pTile)
        {
            super(buf, pTile);
        }
    }
     */
}
