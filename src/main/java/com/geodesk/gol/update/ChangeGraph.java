/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.index.IntIndex;
import com.geodesk.core.Mercator;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.StoredFeature;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.util.TileReaderTask;
import org.eclipse.collections.api.map.primitive.LongObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.set.primitive.IntSet;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

// TODO: rename ChangeInventory?

// TODO: changes that are created and then deleted?

public class ChangeGraph
{
    private final MutableLongObjectMap<CNode> nodes = new LongObjectHashMap<>();
    private final MutableLongObjectMap<CWay> ways = new LongObjectHashMap<>();
    private final MutableLongObjectMap<CRelation> relations = new LongObjectHashMap<>();

    private final boolean useIdIndexes;
    private final IntIndex nodeIndex;
    private final IntIndex wayIndex;
    private final IntIndex relationIndex;
    private final TileCatalog tileCatalog;

    private Set<String> userIds = new HashSet<>();
    private String earliestTimestamp = "9999";
    private String latestTimestamp = "0000";

    private long proposedChangesCount;
    private long effectiveChangesCount;
    private long createNodeCount;
    private long modifyNodeCount;
    private long deleteNodeCount;
    private long createWayCount;
    private long modifyWayCount;
    private long deleteWayCount;
    private long createRelationCount;
    private long modifyRelationCount;
    private long deletedRelationCount;


    public ChangeGraph(BuildContext ctx) throws IOException
    {
        nodeIndex = ctx.getNodeIndex();
        wayIndex = ctx.getWayIndex();
        relationIndex = ctx.getRelationIndex();
        useIdIndexes = nodeIndex != null & wayIndex != null & relationIndex != null;
        tileCatalog = ctx.getTileCatalog();
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

    private class Reader extends ChangeSetReader
    {
        private ChangeType acceptChange(CFeature<?> f, ChangeType changeType)
        {
            if(timestamp.compareTo(earliestTimestamp) < 0)
            {
                earliestTimestamp = timestamp;
            }
            if(timestamp.compareTo(latestTimestamp) > 0)
            {
                latestTimestamp = timestamp;
            }
            userIds.add(userId);

            proposedChangesCount++;
            if(f.change == null)
            {
                effectiveChangesCount++;
                return changeType;
            }
            if (f.change.version > version) return null;
            if(f.change.changeType == ChangeType.CREATE)
            {
                if(changeType == ChangeType.DELETE)
                {
                    f.change = null;
                    effectiveChangesCount--;
                    return null;
                }

                // TODO: check if CREATE is followed by other CREATE

                return ChangeType.CREATE;
            }
            return changeType;
        }

        @Override protected void node(ChangeType change, long id, double lon, double lat, String[] tags)
        {
            CNode node = getNode(id);
            if((change = acceptChange(node, change)) == null) return;
            int x = (int)Math.round(Mercator.xFromLon(lon));
            int y = (int)Math.round(Mercator.yFromLat(lat));
            node.change = new CNode.Change(change, version, tags, x, y);
        }

        @Override protected void way(ChangeType change, long id, String[] tags, long[] nodeIds)
        {
            CWay way = getWay(id);
            if((change = acceptChange(way, change)) == null) return;
            way.change = new CWay.Change(change, version, tags, nodeIds);
        }

        @Override protected void relation(ChangeType change, long id, String[] tags, long[] memberIds, String[] roles)
        {
            CRelation rel = getRelation(id);
            if((change = acceptChange(rel, change)) == null) return;
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

    public void read(InputStream in) throws IOException, SAXException
    {
        new Reader().read(in);
    }

    private class FeatureFinderTask extends TileReaderTask
    {
        final int tip;

        public FeatureFinderTask(int tip, ByteBuffer buf, int pTile)
        {
            super(buf, pTile);
            this.tip = tip;
        }

        @Override protected void node(int p)
        {
            long id = StoredFeature.id(buf, p);
            CNode node = nodes.get(id);
            if(node == null) return;
            // TODO
        }

        @Override protected void way(int p)
        {
            // do nothing
        }

        @Override protected void relation(int p)
        {
            // do nothing
        }
    }

    public void report() throws Exception
	{
        int userCount = userIds.size();
		System.out.format("%,d change%s (%,d effective) by %,d user%s\n", proposedChangesCount,
            proposedChangesCount==1 ? "" : "s", effectiveChangesCount, userCount,
            userCount==1 ? "" : "s");
        System.out.format("Earliest: %s\n", earliestTimestamp);
        System.out.format("Latest:   %s\n", latestTimestamp);
        System.out.format("Affected tiles: %,d of %,d\n",
            getPiles().size(), tileCatalog.tileCount());
	}

    public IntSet getPiles() throws IOException
    {
        MutableIntSet piles = new IntHashSet();
        for(CNode node: nodes.values())
        {
            if(node.change != null) piles.add(nodeIndex.get(node.id()));
        }
        gatherFeaturePiles(ways, wayIndex, piles);
        gatherFeaturePiles(relations, relationIndex, piles);
        return piles;
    }

    private void gatherFeaturePiles(
        MutableLongObjectMap<? extends CFeature<?>> features, IntIndex index,
        MutableIntSet piles) throws IOException
    {
        for(CFeature<?> f : features)
        {
            if(f.change != null)
            {
                int pileQuad = index.get(f.id());
                piles.add(pileQuad >>> 2);
                if ((pileQuad & 3) == 3)
                {
                    // TODO: add adjacent quad as well;
                }
            }
        }
    }


    private class FeatureFinder
    {
        private int tilesRemaining;

    }
}
