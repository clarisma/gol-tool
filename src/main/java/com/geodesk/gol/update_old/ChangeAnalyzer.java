/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import com.clarisma.common.pbf.PbfDecoder;
import com.clarisma.common.util.Log;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.Features;
import com.geodesk.feature.query.WorldView;
import com.geodesk.feature.store.*;
import com.geodesk.gol.TaskEngine;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.util.TileReaderTask;
import org.eclipse.collections.api.map.primitive.LongLongMap;
import org.eclipse.collections.api.map.primitive.LongObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static com.geodesk.gol.update_old.SearchTile.*;

public class ChangeAnalyzer extends TaskEngine<SearchTile>
{
    private final ChangeModel model;
    private final FeatureStore store;
    private final Path wayNodeIndexPath;
    private final Features duplicateNodes;
    private final SearchScope searchScope;

    private long testLocationUpdateCount;
    private AtomicInteger debugGlobalTilesScannedCount = new AtomicInteger();
    private AtomicLong debugGlobalWaynodesScannedCount = new AtomicLong();
    private AtomicLong debugFeaturesFoundCount = new AtomicLong();

    public ChangeAnalyzer(ChangeModel model, BuildContext ctx) throws IOException
    {
        super(new SearchTile(-1), 2, true);
        this.model = model;
        store = ctx.getFeatureStore();
        wayNodeIndexPath = ctx.indexPath().resolve("waynodes");
        duplicateNodes = new WorldView(store).select("n[geodesk:duplicate]");
        searchScope = new SearchScope(ctx);
    }

    @Override protected TaskEngine<SearchTile>.WorkerThread createWorker()
    {
        return new Worker();
    }

    public void analyze() throws IOException, InterruptedException
    {
        model.prepare();

        Log.debug("Starting analysis...");
        model.prepare();
        prepareSearch();
        Log.debug("Need to analyze %,d tiles", searchScope.size());
        start();
        submitTasks();
        awaitCompletionOfGroup(0);
        model.readRelations();
        model.reportMissing();
        prepareSecondSearch();
        submitTasks();
        awaitCompletionOfGroup(1);

        model.reportMissing();
        Log.debug("%,d locations updated", testLocationUpdateCount);

        Log.debug("%,d tiles scanned", debugGlobalTilesScannedCount.get());
        Log.debug("%,d waynodes scanned", debugGlobalWaynodesScannedCount.get());
        Log.debug("%,d features found", debugFeaturesFoundCount.get());
    }

    private void prepareSearch() throws IOException
    {
        for (CNode node : model.nodes())
        {
            CNode.Change change = node.change;
            if (change != null)
            {
                searchScope.findDuplicates(change.x, change.y);
                searchScope.findNode(node.id());
            }
        }
        Log.debug("Find nodes:  %,d tiles", searchScope.size());
        for(CWay way: model.ways())
        {
            if (way.change != null) searchScope.findWay(way.id());
        }
        Log.debug("+ ways:      %,d tiles", searchScope.size());
        for(CRelation rel: model.relations())
        {
            if(rel.change != null) searchScope.findRelation(rel.id());
        }
        Log.debug("+ relations: %,d tiles", searchScope.size());
    }

    private void prepareSecondSearch() throws IOException
    {
        int prevScopeSize = searchScope.size();;
        for(CNode node: model.nodes())
        {
            if(node.tip() == CFeature.TIP_NOT_FOUND && node.change == null)
            {
                searchScope.findNode(node.id());
            }
        }
        for(CWay way: model.ways())
        {
            if(way.tip() == CFeature.TIP_NOT_FOUND && way.change == null)
            {
                searchScope.findWay(way.id());
            }
        }
        for(CRelation rel: model.relations())
        {
            if (rel.tip() == CFeature.TIP_NOT_FOUND && rel.change == null)
            {
                searchScope.findRelation(rel.id());
            }
        }
        Log.debug("Analyzing %,d additional tiles", searchScope.size() - prevScopeSize);
    }

    private void submitTasks()
    {
        for(SearchTile tile: searchScope)
        {
            if (tile.hasTasks()) submit(tile);
        }
    }


    protected class Worker extends WorkerThread
    {
        private final LongObjectMap<CNode> nodes;
        private final LongObjectMap<CWay> ways;
        private final LongObjectMap<CRelation> relations;
        private final LongLongMap locations;
        private final TileScanner tileReader = new TileScanner();

        private int currentTip;
        private int pTile;
        private boolean findDuplicateLocations;

        private int debugTileProcessedCount;

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

        private static final int BATCH_SIZE = 8192;

        /**
         * An array of all the features for which we've found a past version.
         * Even positions contain the typed feature ID, odd positions contain
         * the TIP (upper part) and offset (lower part) of the feature.
         */
        private long[]  featuresFound;
        private long[][] wayNodesFound;
        private int foundFeatureCount;

        // TODO: Looking up CFeature objects multiple times is inefficient;
        //  it would be better to pass references to the CFeature instead
        //  of typed IDs. However, in the case of ways that are implicitly
        //  changed, or ways that are merely "donors" of anonymous nodes,
        //  there is no existing CWay object in the ChangeModel. For implicitly
        //  changed ways, we need to create a CWay' for donor ways, we don't
        //  create a CWay (we're only interested in the node coordinates)

        Worker()
        {
            nodes = model.nodes();
            ways = model.ways();
            relations = model.relations();
            locations = model.locations();
            nextBatch();
        }

        private void nextBatch()
        {
            featuresFound = new long[BATCH_SIZE * 2];
            wayNodesFound = new long[BATCH_SIZE][];
            foundFeatureCount = 0;
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

        private void foundFeature(long typedId, int p)
        {
            int i = foundFeatureCount << 1;
            featuresFound[i] = typedId;
            featuresFound[i+1] = (((long)currentTip) << 32) | (long)(p - pTile);
            foundFeatureCount++;
            if(foundFeatureCount == BATCH_SIZE) flush();
        }

        private void flush()
        {
            // Log.debug("Dispatching %d features", foundFeatureCount);
            try
            {
                // Need to assign the structures that we want to pass to the
                // ChangeModel via the output thread to local variables first,
                // in order for lambda capture to work properly
                // This does not work: output(() -> model.processScanResults(featuresFound, wayNodesFound));

                final long[] dispatchFeaturesFound = featuresFound;
                final long[][] dispatchWayNodesFound = wayNodesFound;
                output(() -> model.processScanResults(dispatchFeaturesFound, dispatchWayNodesFound));
                // output(new FoundFeaturesTask(model, featuresFound, wayNodesFound));
                debugFeaturesFoundCount.addAndGet(foundFeatureCount);
            }
            catch(InterruptedException ex)
            {
                // TODO
            }
            nextBatch();
        }

        private void foundWay(long id, int p)
        {
            long[] nodeIds = currentTileWayNodes.get(id);
            wayNodesFound[foundFeatureCount] = nodeIds;
            foundFeature(FeatureId.ofWay(id), p);
        }

        @Override protected void process(SearchTile tile) throws Exception
        {
            currentTip = tile.tip;
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
            debugTileProcessedCount++;
            tile.done();
        }

        @Override protected void postProcess()
        {
            if(foundFeatureCount > 0) flush();
            if(currentPhase() == 3)
            {
                Log.debug("%,d tiles scanned.", debugTileProcessedCount);
                Log.debug("%,d way-node coordinates scanned.", tileReader.nodesScanned);

                debugGlobalTilesScannedCount.addAndGet(debugTileProcessedCount);
                debugGlobalWaynodesScannedCount.addAndGet(tileReader.nodesScanned);
            }
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
                    long wayId = pbf.readSignedVarint() + prevWayId;
                    int savedPos = pbf.pos();
                    CWay way = ways.get(wayId);
                    if(way != null && way.change != null)       // TODO: concurrency!!!
                    {
                        // In 99.9% of cases, we could merely check if a way's node
                        // is contained in the ChangeModel in order to determine
                        // if we should pick up this way's nodeIDs. However,
                        // it is possible that a way changed completely, retaining
                        // none of its past nodes -- therefore, we check if the way
                        // is modified explicitly

                        // TODO: concurrent access to way.change!

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
                            if (locations.containsKey(nodeId))
                            {
                                extract = true;
                                break;
                            }
                            prevNodeId = nodeId;
                        }
                    }
                    if(extract)
                    {
                        pbf.seek(savedPos);
                        long[] nodeIds = readWayNodes(pbf);
                        currentTileWayNodes.put(wayId, nodeIds);
                    }
                    prevWayId = wayId;
                }
            }
        }

        // TODO: fold functionality into the Worker
        private class TileScanner extends TileReaderTask
        {
            long nodesScanned;

            @Override public void node(int p)
            {
                // StoredNode node = new StoredNode(store, buf, p);
                long id = StoredNode.id(buf, p);
                if(nodes.containsKey(id))
                {
                    foundFeature(FeatureId.ofNode(id), p);
                }
                // if(duplicateNodes.contains(node)) foundDupes++;
            }

            @Override public void way(int p)
            {
                long id = StoredWay.id(buf, p);
                if(ways.containsKey(id) || currentTileWayNodes.containsKey(id))
                {
                    // We have to check *both* maps, because an unmodified way
                    // could be referenced by a relation (It is in `ways` but
                    // not in `currentTileWayNodes` -- we only need a reference
                    // to its past version), or it could be implicitly changed
                    // (It is in `currentTileWayNodes`, but not in `ways`)
                    foundWay(id, p);
                }
                if(findDuplicateLocations)
                {
                    StoredWay way = new StoredWay(store, buf, p);
                    StoredWay.XYIterator iter = way.iterXY(0);
                    while (iter.hasNext())
                    {
                        long xy = iter.nextXY();
                        /*
                        if(futureLocationToNode.containsKey(xy))
                        {
                            Log.debug("Potential duplicate node in way/%d", way.id());
                        }
                        */
                        nodesScanned++;
                    }
                }
            }

            @Override public void relation(int p)
            {
                long id = StoredFeature.id(buf, p);
                if (relations.containsKey(id))
                {
                    foundFeature(FeatureId.ofRelation(id), p);
                }
            }

        }

    }

    /*
    protected static class FoundFeaturesTask implements Runnable
    {
        private final ChangeModel model;
        private final long[]  featuresFound;
        private final long[][] wayNodesFound;

        public FoundFeaturesTask(ChangeModel model, long[] featuresFound, long[][] wayNodesFound)
        {
            this.model = model;
            this.featuresFound = featuresFound;
            this.wayNodesFound = wayNodesFound;
        }

        @Override public void run()
        {
            model.processScanResults(featuresFound, wayNodesFound);
        }
    }
     */
}
