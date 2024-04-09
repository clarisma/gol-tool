/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.pbf.PbfDecoder;
import com.clarisma.common.text.Format;
import com.clarisma.common.util.Log;
import com.geodesk.geom.Tile;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.Features;
import com.geodesk.feature.query.WorldView;
import com.geodesk.feature.store.*;
import com.geodesk.gol.TaskEngine;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.tiles.MemberReader;
import com.geodesk.gol.util.TileReaderTask;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.LongObjectMap;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.set.primitive.IntSet;
import org.eclipse.collections.api.set.primitive.LongSet;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class FeatureFinder extends TaskEngine<FeatureFinder.SearchTile>
{
    private final FeatureStore store;
    private final TileCatalog tileCatalog;
    private final LongObjectMap<ChangedNode> changedNodes;
    private final LongObjectMap<ChangedWay> changedWays;
    private final LongObjectMap<ChangedRelation> changedRelations;
    private final LongSet nodesOfInterest;
    private final LongSet waysOfInterest;
    private final LongSet relationsOfInterest;
    private final Path wayNodeIndexPath;
    private final Features duplicateNodes;
    private MutableIntObjectMap<SearchTile> tiles;
    private boolean reportProgress = true; // TODO
    private int tileCount;
    private int tilesSearched;
    private int percentageReported;

    private static final int FIND_NODES = 1;
    private static final int FIND_WAYS = 1 << 1;
    private static final int FIND_RELATIONS = 1 << 2;
    private static final int FIND_WAY_NODES = 1 << 3;
    private static final int FIND_DUPLICATE_XY = 1 << 4;

    public FeatureFinder(BuildContext ctx,
        List<ChangedNode> changedNodeList,
        List<ChangedWay> changedWayList,
        List<ChangedRelation> changedRelationList) throws IOException
    {
        super(new SearchTile(-1), 2, false);
        Log.debug("Creating FeatureFinder...");
        store = ctx.getFeatureStore();
        tileCatalog = ctx.getTileCatalog();
        tileCount = tileCatalog.tileCount();
        wayNodeIndexPath = ctx.indexPath().resolve("waynodes");
        duplicateNodes = new WorldView(store).select("n[geodesk:duplicate]");
        changedNodes = getChangedFeatures(changedNodeList);
        changedWays = getChangedFeatures(changedWayList);
        changedRelations = getChangedFeatures(changedRelationList);
        MutableLongSet nodesOfInterest = new LongHashSet(changedNodes.size() * 2);
        MutableLongSet waysOfInterest = new LongHashSet(changedWays.size() * 2);
        MutableLongSet relationsOfInterest = new LongHashSet(changedRelations.size() * 2);
        nodesOfInterest.addAll(changedNodes.keysView());
        waysOfInterest.addAll(changedWays.keysView());
        relationsOfInterest.addAll(changedRelations.keysView());
        for(ChangedWay way: changedWays)
        {
            long[] nodeIds = way.nodeIds;
            if (nodeIds != null) nodesOfInterest.addAll();
        }
        MutableLongSet[] membersOfInterest = new MutableLongSet[] {
            nodesOfInterest, waysOfInterest, relationsOfInterest };
        for(ChangedRelation rel: changedRelations)
        {
            long[] memberIds = rel.memberIds;
            if(memberIds != null)
            {
                for (long memberId : memberIds)
                {
                    long id = FeatureId.id(memberId);
                    int type = FeatureId.typeCode(memberId);
                    membersOfInterest[type].add(id);
                }
            }
        }
        this.nodesOfInterest = nodesOfInterest;
        this.waysOfInterest = waysOfInterest;
        this.relationsOfInterest = relationsOfInterest;
        Log.debug("Created FeatureFinder.");
    }

    private <T extends ChangedFeature> MutableLongObjectMap<T> getChangedFeatures(List<T> list)
    {
        MutableLongObjectMap<T> map =new LongObjectHashMap<>(list.size());
        for(T f: list)
        {
            T existing = map.get(f.id());
            if(existing == null || existing.version < f.version)
            {
                map.put(f.id(), f);
            }
        }
        return map;
    }

    public void search(TileFinder tileFinder) throws InterruptedException
    {
        long start = System.currentTimeMillis();
        IntSet nodeTiles = tileFinder.nodeTiles();
        tiles = new IntObjectHashMap<>(nodeTiles.size() * 2);
        nodeTiles.forEach(t ->
        {
            markTile(t, FIND_NODES);
            markTileAndParents(t, FIND_WAY_NODES);
        });
        tileFinder.wayTiles().forEach(t -> markTile(t, FIND_WAYS));
        tileFinder.relationTiles().forEach(t -> markTile(t, FIND_RELATIONS));
        for(ChangedNode node: changedNodes)
        {
            // TODO: use tile directly
            int pile = tileCatalog.resolvePileOfXY(node.x, node.y);
            markTile(tileCatalog.tileOfPile(pile), FIND_DUPLICATE_XY);
        }
        start();
        for(SearchTile st: tiles.values()) submit(st);
        awaitCompletionOfGroup(0);
        System.err.format("Analyzed %,d tiles (%d%%) in %s\n",
            tilesSearched, tilesSearched * 100 / tileCount,
            Format.formatTimespan(System.currentTimeMillis() - start));
    }

    private SearchTile getTile(int tile)
    {
        SearchTile st = tiles.get(tile);
        if(st == null)
        {
            st = new SearchTile(tileCatalog.tipOfTile(tile));
            tiles.put(tile, st);
        }
        return st;
    }

    private void markTile(int tile, int flags)
    {
        getTile(tile).flags |= flags;
    }

    private void markTileAndParents(int tile, int flags)
    {
        SearchTile st = getTile(tile);
        if((st.flags & flags) != 0) return;
        st.flags |= flags;
        if(Tile.zoom(tile) > 0)
        {
            markTileAndParents(tileCatalog.parentTile(tile), flags);
        }
    }

    private synchronized void updateProgress()
    {
        tilesSearched++;
        int percentageCompleted = (tilesSearched * 100 / tileCount);
        if (percentageCompleted != percentageReported)
        {
            System.err.format("Analyzing... %d%% (%,d of %,d tiles)\r",
                percentageCompleted, tilesSearched, tileCount);
            percentageReported = percentageCompleted;
        }
    }


    @Override protected WorkerThread createWorker()
    {
        return new Worker();
    }

    // TODO: For nodes, we just need to distinguish between feature nodes
    //  and anonymous nodes; no need to store refs, always store x/y
    //  only time we need ref is for changed nodes

    protected class Worker extends WorkerThread
    {
        private final MutableLongList nodeRefs = new LongArrayList();
        private final MutableLongList wayRefs = new LongArrayList();
        private final MutableLongList relationRefs = new LongArrayList();
        private final MutableLongList locations = new LongArrayList();
        private final TileScanner tileReader = new TileScanner();
        private final MemberReader memberReader = new MemberReader(store);
        private final MutableLongObjectMap<ChangedWay> implicitlyChangedWays =
            new LongObjectHashMap<>();
        private int currentTip;
        private int pTile;
        private boolean findDuplicateLocations;
        private final MutableLongList[] featureRefs;

        /**
         * A collection of all the way-node IDs of relevant ways in the
         * currently scanned tile. A way is considered "relevant" if it
         * - is explicitly changed, OR
         * - contains a node that has been explicitly changed
         *   (therefore implicitly changing the way) OR
         * - contains a node referenced by an explicitly changed way
         *   (in which case we need to extract yhe coordinates of such
         *   nodes)
         */
        private final MutableLongObjectMap<long[]> currentTileWayNodes =
            new LongObjectHashMap<>();

        Worker()
        {
            featureRefs = new MutableLongList[] { nodeRefs, wayRefs, relationRefs};
        }

        @Override protected void process(SearchTile tile) throws Exception
        {
            currentTip = tile.tip;
            // Log.debug("Searching %s...", Tip.toString(currentTip));
            if((tile.flags & FIND_WAY_NODES) != 0) findWayNodes();
            findDuplicateLocations = (tile.flags & FIND_DUPLICATE_XY) != 0;

            int tilePage = store.fetchTile(currentTip);
            pTile = store.offsetOfPage(tilePage);
            tileReader.start(store.bufferOfPage(tilePage), pTile);
            if((tile.flags & FIND_NODES) != 0) tileReader.scanNodes();
            if((tile.flags & (FIND_WAYS | FIND_DUPLICATE_XY)) != 0)
            {
                tileReader.scanLinearWays();
            }
            if((tile.flags & (FIND_WAYS | FIND_RELATIONS | FIND_DUPLICATE_XY)) != 0)
            {
                tileReader.scanAreas();
            }
            currentTileWayNodes.clear();
            if((tile.flags & FIND_RELATIONS) != 0) tileReader.scanNonAreaRelations();
            // tile.done();
            if(reportProgress) updateProgress();
        }

        // TODO: instead of decoding the way-node table, could also just
        //  grab the raw bytes; would require changing count to length;
        //  would also help the TileCompiler skip the node IDs of a way
        //  instead of reading through them
        private static long[] readWayNodes(PbfDecoder pbf)
        {
            int nodeCount = (int) pbf.readVarint();
            long[] nodeIds = new long[nodeCount];
            long prevNodeId = 0;
            for (int i = 0; i < nodeCount; i++)
            {
                long nodeId = pbf.readSignedVarint() + prevNodeId;
                nodeIds[i] = nodeId;
                prevNodeId = nodeId;
            }
            return nodeIds;
        }

        private void findWayNodes() throws IOException
        {
            Path path = Tip.path(wayNodeIndexPath, currentTip, ".wnx");
            try(FileChannel channel = FileChannel.open(path, StandardOpenOption.READ))
            {
                int len = (int) channel.size();
                ByteBuffer buf = ByteBuffer.allocateDirect(len);
                channel.read(buf);
                PbfDecoder pbf = new PbfDecoder(buf, 0);
                long prevWayId = 0;
                while (pbf.pos() < len)
                {
                    boolean extract;
                    boolean implicitlyChanged = false;
                    long wayId = pbf.readSignedVarint() + prevWayId;
                    int savedPos = pbf.pos();
                    ChangedWay way = changedWays.get(wayId);
                    if(way != null)
                    {
                        // In 99.9% of cases, we could merely check if a way's node
                        // is contained in the ChangeModel in order to determine
                        // if we should pick up this way's nodeIDs. However,
                        // it is possible that a way changed completely, retaining
                        // none of its past nodes -- therefore, we check if the way
                        // is modified explicitly

                        extract = true;
                    }
                    else
                    {
                        extract = false;
                        int nodeCount = (int) pbf.readVarint();
                        long prevNodeId = 0;
                        for (int i = 0; i < nodeCount; i++)
                        {
                            long nodeId = pbf.readSignedVarint() + prevNodeId;
                            if (nodesOfInterest.contains(nodeId))
                            {
                                extract = true;
                                if(changedNodes.containsKey(nodeId))
                                {
                                    implicitlyChanged = true;
                                }
                            }
                            prevNodeId = nodeId;
                        }
                    }
                    if(extract)
                    {
                        pbf.seek(savedPos);
                        long[] nodeIds = readWayNodes(pbf);
                        currentTileWayNodes.put(wayId, nodeIds);
                        if(implicitlyChanged)
                        {
                            implicitlyChangedWays.put(wayId, new ChangedWay(wayId,
                                0, Integer.MAX_VALUE, null, nodeIds));
                        }
                    }
                    prevWayId = wayId;
                }
            }
        }

        private void foundFeatureRef(MutableLongList list, long id, int p)
        {
            list.add(id);
            list.add((((long)currentTip) << 32) | (long)(p - pTile));
        }

        private void foundForeignFeatureRef(MutableLongList list, long id, int tip, int ofs)
        {
            list.add(id);
            list.add((((long)tip) << 32) | (long)(ofs));
        }

        // TODO: fold functionality into the Worker
        private class TileScanner extends TileReaderTask
        {
            long nodesScanned;

            @Override public void node(int p)
            {
                long id = StoredNode.id(buf, p);
                if(nodesOfInterest.contains(id))
                {
                    ChangedNode node = changedNodes.get(id);
                    if(node != null)
                    {
                        // TODO: update node
                    }
                    else
                    {
                        foundFeatureRef(nodeRefs, id, p);
                    }
                }
                // if(duplicateNodes.contains(node)) foundDupes++;
            }

            // store tip/ofs for changed way
            // store tip/ofs for referenced way
            // store tip/ofs for implicitly changed way
            // extract way-nodes of way of interest
            // check way-nodes for duplicates

            @Override public void way(int p)
            {
                boolean scanWayNodes = findDuplicateLocations;
                long id = StoredWay.id(buf, p);
                ChangedWay way = implicitlyChangedWays.get(id);
                if (way != null)
                {
                    // Must always check map of implicitly-changed ways first,
                    // since a referenced way (in `waysOfInterest`) may have
                    // been determined to have implicitly changed

                    // TODO: update way
                    // For implicitly changed ways, we don't need to extract
                    // way-nodes
                    scanWayNodes = true;
                }
                else
                {
                    if (waysOfInterest.contains(id))
                    {
                        scanWayNodes = true;
                        way = changedWays.get(id);
                        if (way != null)
                        {
                            // TODO: update way
                        }
                        else
                        {
                            foundFeatureRef(wayRefs, id, p);
                        }
                        // TODO: extract way-nodes (both coordinates and feature nodes)
                    }
                }
                if(scanWayNodes)
                {
                    long[] nodeIds = currentTileWayNodes.get(id);
                    StoredWay pastWay = new StoredWay(store, buf, p);
                    StoredWay.XYIterator iter = pastWay.iterXY(0);
                    int i = 0;
                    while (iter.hasNext())
                    {
                        long xy = iter.nextXY();
                        if(nodeIds != null)
                        {
                            long nodeId = nodeIds[i];
                            if(nodesOfInterest.contains(nodeId))
                            {
                                locations.add(nodeId);
                                locations.add(xy);
                            }
                        }
                        if(findDuplicateLocations)
                        {
                        /*
                        if(futureLocationToNode.containsKey(xy))
                        {
                            Log.debug("Potential duplicate node in way/%d", way.id());
                        }
                        */
                        }
                        nodesScanned++;
                        i++;
                    }
                }
            }

            @Override public void relation(int p)
            {
                long id = StoredFeature.id(buf, p);
                if(relationsOfInterest.contains(id))
                {
                    // Log.debug("relation/%d", id);
                    ChangedRelation rel = changedRelations.get(id);
                    if (rel != null)
                    {
                        // TODO: update relation
                        memberReader.start(buf, StoredRelation.bodyPointer(buf, p));
                        while(memberReader.next())
                        {
                            long typedMemberId = memberReader.typedMemberId();
                            MutableLongList memberRefs = featureRefs[
                                FeatureId.typeCode(typedMemberId)];
                            long memberId = FeatureId.id(typedMemberId);
                            // Log.debug("- %s", FeatureId.toString(typedMemberId));
                            int memberTip = memberReader.tip();
                            if(memberTip == MemberReader.LOCAL_TIP)
                            {
                                foundFeatureRef(memberRefs, memberId,
                                    memberReader.memberPointer());
                            }
                            else
                            {
                                foundForeignFeatureRef(memberRefs, memberId, memberTip,
                                    memberReader.memberOffset());
                            }
                        }
                    }
                    else
                    {
                        foundFeatureRef(relationRefs, id, p);
                    }
                }
            }
        }

    }

    static class SearchTile
    {
        final int tip;
        int flags;

        SearchTile(int tip)
        {
            this.tip = tip;
        }
    }
}

