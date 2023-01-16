/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.index.IntIndex;
import com.clarisma.common.pbf.PbfDecoder;
import com.clarisma.common.util.Log;
import com.geodesk.core.Mercator;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.StoredFeature;
import com.geodesk.feature.store.Tip;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.util.TileReaderTask;
import org.eclipse.collections.api.map.primitive.MutableLongLongMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.set.primitive.IntSet;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.READ;

// TODO: rename ChangeInventory?
//  Better name: RevisionSet, or just Revision

// TODO: changes that are created and then deleted?

// TODO: When coalescing changes, need to inspect the order; parser may
//  see delete before create

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
    private final MutableLongLongMap idToPastLocation = new LongLongHashMap();
    /**
     * A mapping of x/y to one of the nodes (non-deterministic) located there
     * in the future. This helps us discover potential duplicate nodes.
     * (If more than one node lives at a location, we promote those that are
     * anonymous nodes to feature status, tagged with geodesk:duplicate)
     * // TODO: also orphans can be duplicate
     */
    private final MutableLongObjectMap<CNode> futureLocationToNode = new LongObjectHashMap<>();


    private final boolean useIdIndexes;
    private final IntIndex nodeIndex;
    private final IntIndex wayIndex;
    private final IntIndex relationIndex;
    private final TileCatalog tileCatalog;
    private final Path wayNodeIndexPath;

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
        wayNodeIndexPath = ctx.indexPath().resolve("waynodes");
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

        IntSet piles = getPiles();
        System.out.format("Affected tiles: %,d of %,d\n",
            piles.size(), tileCatalog.tileCount());

        gatherNodes();
        System.out.format("Relevant nodes: %,d\n", idToPastLocation.size());

        scanTiles(piles);
	}

    private void gatherNodes()
    {
        nodes.forEach(node -> idToPastLocation.put(node.id(), -1));
        ways.forEach(way ->
        {
            CWay.Change change = way.change;
            if(change != null)
            {
                for(long nodeId: change.nodeIds) idToPastLocation.put(nodeId, -1);
            }
        });
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

    public void scanTiles(IntSet piles)
    {
        int threadCount = Runtime.getRuntime().availableProcessors();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            threadCount, threadCount, 1, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(threadCount * 4),
            new ThreadPoolExecutor.CallerRunsPolicy());

        piles.forEach(pile ->
        {
            int tip = tileCatalog.tipOfTile(tileCatalog.tileOfPile(pile));
            executor.submit(new ScanTask(tip));
        });

        executor.shutdown();
        try
        {
            executor.awaitTermination(30, TimeUnit.DAYS);
        }
        catch (InterruptedException ex)
        {
            // don't care about being interrupted, we're done anyway
        }
    }

    private class ScanTask implements Runnable
    {
        private final int tip;

        ScanTask(int tip)
        {
            this.tip = tip;
        }

        private void readWayNodes()
        {
            Path path = Tip.path(wayNodeIndexPath, tip, ".wnx");
            try(FileChannel channel = FileChannel.open(path, READ))
            {
                long count = 0;
                long foundWays = 0;
                long foundNodes = 0;
                int len = (int) channel.size();
                ByteBuffer buf = ByteBuffer.allocateDirect(len);
                channel.read(buf);
                // buf.flip();
                PbfDecoder pbf = new PbfDecoder(buf, 0);
                long prevWayId = 0;
                while (pbf.pos() < len)
                {
                    long wayId = pbf.readSignedVarint() + prevWayId;
                    int nodeCount = (int) pbf.readVarint();
                    long prevNodeId = 0;
                    for (int i = 0; i < nodeCount; i++)
                    {
                        long nodeId = pbf.readSignedVarint() + prevNodeId;
                        if(idToPastLocation.get(nodeId) != 0) foundNodes++;
                        count++;
                        prevNodeId = nodeId;
                    }
                    prevWayId = wayId;
                    if(ways.get(wayId) != null) foundWays++;
                }
                // Log.debug("Read %,d nodes from %s", count, path);
                // Log.debug("Found %,d ways  in %s", foundWays, Tip.toString(tip));
                // Log.debug("Found %,d nodes in %s", foundNodes, Tip.toString(tip));
            }
            catch (IOException ex)
            {
                Log.error("Failed to read waynodes from %s: %s", path, ex.getMessage());
            }
        }

        @Override public void run()
        {
            readWayNodes();
        }
    }

    /**
     * Since we cannot concurrenlty write to `nodes`, `ways`, `relations` and
     * `idToPastLocation` (at least without lots of expensive locking), we
     * stash the results of a scan in unordered circular linked lists. Each
     * entry contains the ID of the feature, and two integer values. For the
     * feature maps, `val1`/'val2` are the TIP/offset of the features; for
     * node locations, `val1`/'val2` are the x/y coordinates.
     */
    private static class ScanResult
    {
        long id;
        int val1;
        int val2;
        ScanResult next;
    }

    /**
     * Adds a single result to an unordered circular linked list.
     *
     * @param first     the first item of the circular linked list
     *                  (can be `null`)
     * @param item      the single result (must not be `null`)
     * @return          the first item of the circular linked list
     */
    static ScanResult addResult(ScanResult first, ScanResult item)
    {
        if(first == null)
        {
            item.next = item;
            return item;
        }
        item.next = first.next;
        first.next = item;
        return first;
    }

    /**
     * Combines two unordered circular linked lists.
     *
     * @param a  the first item of the first linked list (can be `null`)
     * @param b  the first item of the second linked list (can be `null`)
     * @return   the first item of the combined circular linked list
     */
    static ScanResult mergeResults(ScanResult a, ScanResult b)
    {
        if(a == null) return b;
        if(b == null) return a;
        ScanResult aNext = a.next;
        a.next = b.next;
        b.next = aNext;
        return a;
    }

    private class FeatureFinder
    {
        private int tilesRemaining;

    }
}
