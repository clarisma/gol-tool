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
import com.geodesk.core.Tile;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.Features;
import com.geodesk.feature.query.WorldView;
import com.geodesk.feature.store.*;
import com.geodesk.gol.TaskEngine;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.util.TileReaderTask;
import org.eclipse.collections.api.map.primitive.LongLongMap;
import org.eclipse.collections.api.map.primitive.LongObjectMap;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

public class ChangeAnalyzer extends TaskEngine<ChangeAnalyzer.CTile>
{
    private final ChangeGraph graph;
    private final FeatureStore store;
    private final IntIndex nodeIndex;
    private final IntIndex wayIndex;
    private final IntIndex relationIndex;
    private final boolean useIdIndexes;
    private final TileCatalog tileCatalog;
    private final Path wayNodeIndexPath;
    private final Features<?> duplicateNodes;
    private final MutableIntObjectMap<CTile> tiles = new IntObjectHashMap<>();

    private long testLocationUpdateCount;

    public ChangeAnalyzer(ChangeGraph graph, BuildContext ctx) throws IOException
    {
        super(new CTile(-1), 2, true);
        this.graph = graph;
        store = ctx.getFeatureStore();
        nodeIndex = ctx.getNodeIndex();
        wayIndex = ctx.getWayIndex();
        relationIndex = ctx.getRelationIndex();
        useIdIndexes = nodeIndex != null & wayIndex != null & relationIndex != null;
        tileCatalog = ctx.getTileCatalog();
        wayNodeIndexPath = ctx.indexPath().resolve("waynodes");
        duplicateNodes = new WorldView<>(store).select("n[geodesk:duplicate]");
    }

    @Override protected TaskEngine<CTile>.WorkerThread createWorker()
    {
        return new Worker();
    }

    public void analyze() throws IOException, InterruptedException
    {
        Log.debug("Starting analysis...");
        graph.prepare();
        gatherFutureNodeTiles();
        Log.debug("Future locations: %,d tiles", tiles.size());
        gatherTilesForNodes();
        Log.debug("+ nodes/waynodes: %,d tiles", tiles.size());
        gatherTilesForFeatures(graph.ways(), wayIndex, CTile.FIND_WAYS);
        Log.debug("+ ways:           %,d tiles", tiles.size());
        gatherTilesForFeatures(graph.relations(), relationIndex, CTile.FIND_RELATIONS);
        Log.debug("+ relations:      %,d tiles", tiles.size());
        Log.debug("Need to analyze %,d tiles", tiles.size());
        start();
        submitTiles();
        awaitCompletionOfGroup(0);
        awaitCompletionOfGroup(1);
        int count = 0;
        for(CTile tile: tiles) if(tile.isProcessed()) count++;
        Log.debug("Analyzed %,d tiles", count);
        graph.readRelations();
        graph.reportMissing();
        Log.debug("%,d locations updated", testLocationUpdateCount);
    }

    private CTile getTile(int tile)
    {
        CTile t = tiles.get(tile);
        if (t == null)
        {
            t = new CTile(tileCatalog.tipOfTile(tile));
            tiles.put(tile, t);
        }
        return t;
    }


    private void markParentTiles(int childTile, int flags)
    {
        if (Tile.zoom(childTile) == 0) return;
        int parentTile = tileCatalog.parentTile(childTile);
        CTile tile = getTile(parentTile);
        if ((tile.flags & flags) == flags) return;
        tile.addFlags(flags);
        markParentTiles(parentTile, flags);
    }

    private void gatherFutureNodeTiles()
    {
        for (CNode node : graph.nodes())
        {
            CNode.Change change = node.change;
            if (change != null)
            {
                int x = change.x;
                int y = change.y;
                // TODO: this is inefficnet, could look up tile directly
                //  without mapping pile <--> tile
                int pile = tileCatalog.resolvePileOfXY(x, y);
                CTile tile = getTile(tileCatalog.tileOfPile(pile));
                tile.addFlags(CTile.FIND_EVERYTHING);
            }
        }
    }

    private void gatherTilesForFeatures(
        LongObjectMap<? extends CFeature> features,
        IntIndex index, int flags) throws IOException
    {
        for(CFeature f: features)
        {
            if(f.change != null)
            {
                int pileQuad = index.get(f.id());
                if(pileQuad != 0)
                {
                    // TODO: For features with > 2 tiles, we need to
                    //  also scan the adjacent tile (in case feature's
                    //  quad is sparse)
                    int tile = tileCatalog.tileOfPile(pileQuad >>> 2);
                    CTile t = getTile(tile);
                    t.addFlags(flags);
                }
            }
        }
    }

    private void gatherTilesForNodes() throws IOException
    {
        final int FLAGS = CTile.FIND_NODES | CTile.FIND_WAY_NODES;
        for(CNode node: graph.nodes())
        {
            if(node.change != null)
            {
                int pile = nodeIndex.get(node.id());
                if(pile != 0)
                {
                    int tile = tileCatalog.tileOfPile(pile);
                    CTile t = getTile(tile);
                    t.addFlags(FLAGS);
                    markParentTiles(tile, FLAGS);   // TODO: waynodes only!
                }
            }
        }
    }

    // TODO: move to FeatureStore
    private StoredWay getWay(int tip, int ptr)
    {
        int tilePage = store.fetchTile(tip);
        return new StoredWay(store, store.bufferOfPage(tilePage),
            store.offsetOfPage(tilePage) + ptr);
    }

    private void submitTiles()
    {
        for(CTile tile: tiles)
        {
            /*  // possibly unsafe for concurrent access
            if(tile.isSubmitted()) continue;
            */
            tile.addFlags(CTile.SUBMITTED);
            submit(tile);
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

        Worker()
        {
            nodes = graph.nodes();
            ways = graph.ways();
            relations = graph.relations();
            locations = graph.locations();
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
                output(new UpdateGraphTask(featuresFound, wayNodesFound));
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

        @Override protected void process(CTile tile) throws Exception
        {
            currentTip = tile.tip;
            if((tile.flags & CTile.FIND_WAY_NODES) != 0) findWayNodes();


            int tilePage = store.fetchTile(currentTip);
            pTile = store.offsetOfPage(tilePage);
            tileReader.start(store.bufferOfPage(tilePage), pTile);
            if((tile.flags & CTile.FIND_NODES) != 0) tileReader.scanNodes();
            if((tile.flags & CTile.FIND_WAYS) != 0) tileReader.scanLinearWays();
            if((tile.flags & (CTile.FIND_WAYS | CTile.FIND_RELATIONS)) != 0)
            {
                tileReader.scanAreas();
            }
            if((tile.flags & CTile.FIND_RELATIONS) != 0) tileReader.scanNonAreaRelations();
            tile.markProcessed();
        }

        @Override protected void postProcess()
        {
            if(foundFeatureCount > 0) flush();
            if(currentPhase() == 3)
            {
                Log.debug("%,d way-node coordinates scanned.", tileReader.nodesScanned);
            }
        }

        private void findWayNodes()
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
                    if(way != null && way.change != null)
                    {
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
            catch (IOException ex)
            {
                Log.error("Failed to read waynodes from %s: %s", path, ex.getMessage());
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
                if(ways.containsKey(id))
                {
                    foundWay(id, p);
                }
                StoredWay way = new StoredWay(store, buf, p);
                StoredWay.XYIterator iter = way.iterXY(0);
                while(iter.hasNext())
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

    protected static class CTile
    {
        static final int FIND_NODES     = 1;
        static final int FIND_WAYS      = 1 << 1;
        static final int FIND_RELATIONS = 1 << 2;
        static final int FIND_WAY_NODES = 1 << 3;
        static final int FIND_FEATURES  = FIND_NODES | FIND_WAYS | FIND_RELATIONS;
        static final int FIND_EVERYTHING = FIND_FEATURES | FIND_WAY_NODES;
        static final int SUBMITTED      = 1 << 8;
        static final int PROCESSED      = 1 << 9;

        int tip;
        int flags;

        CTile(int tip)
        {
            this.tip = tip;
        }

        boolean isSubmitted()
        {
            return (flags & SUBMITTED) != 0;
        }

        boolean isProcessed()
        {
            return (flags & PROCESSED) != 0;
        }

        void markProcessed()
        {
            assert isSubmitted();
            flags |= PROCESSED;
        }

        void addFlags(int flags)
        {
            assert !isSubmitted();
            this.flags |= flags;
        }
    }

    private class UpdateGraphTask implements Runnable
    {
        private final long[] featuresFound;
        private final long[][] wayNodesFound;

        UpdateGraphTask(long[] featuresFound, long[][] wayNodesFound)
        {
            this.featuresFound = featuresFound;
            this.wayNodesFound = wayNodesFound;
        }

        @Override public void run()
        {
            // Log.debug("Updating the graph...");
            for(int i=0; i<featuresFound.length; i += 2)
            {
                long typedId = featuresFound[i];
                if (typedId == 0) break;
                long tipAndOffset = featuresFound[i + 1];
                // TODO: This should be safe as it never creates features,
                //  therefore does not modify the maps concurrently;
                //  still, we could make this cleaner
                CFeature<?> feature = graph.getFeature(typedId);
                int tip = (int)(tipAndOffset >>> 32);
                int ptr = (int)tipAndOffset;
                feature.found(tip, ptr);
                long[] wayNodes = wayNodesFound[i >>> 1];
                if(wayNodes != null)
                {
                    // TODO

                    assert FeatureId.isWay(typedId);
                    StoredWay way = getWay(tip, ptr);
                    // Log.debug("Adding node coordinates for way/%d...", way.id());
                    StoredWay.XYIterator iter = way.iterXY(0);
                    int n=0;
                    while (iter.hasNext())
                    {
                        long xy = iter.nextXY();
                        long nodeId = wayNodes[n++];
                        if(graph.locations().containsKey(nodeId)) testLocationUpdateCount++;
                        graph.addLocation(nodeId, xy);
                    }
                    assert n == wayNodes.length ||
                        (n == wayNodes.length-1 && wayNodes[n] == wayNodes[0]);
                }
            }
        }
    }

}
