/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.index.IntIndex;
import com.geodesk.geom.Heading;
import com.geodesk.geom.Tile;
import com.geodesk.feature.FeatureId;
import com.geodesk.gol.TaskEngine;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import org.eclipse.collections.api.set.primitive.IntSet;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

import java.io.IOException;

public class TileFinder extends TaskEngine<TileFinder.Task>
{
    private BuildContext context;
    private long[] featureIds;
    private int featureCount;

    private MutableIntSet nodeTiles;
    private MutableIntSet wayTiles;
    private MutableIntSet relationTiles;

    private static int BATCH_SIZE = 8192;

    public TileFinder(BuildContext ctx) throws IOException
    {
        super(new Task(null), 1, true);
        this.context = ctx;
        newBatch();
        start();
    }

    public void finish() throws InterruptedException
    {
        flush();
        awaitCompletionOfGroup(0);
        /*
        Log.debug("%,d node tiles", nodeTiles.size());
        Log.debug("%,d way tiles", wayTiles.size());
        Log.debug("%,d relation tiles", relationTiles.size());
         */
    }

    public IntSet nodeTiles()
    {
        return nodeTiles;
    }

    public IntSet wayTiles()
    {
        return wayTiles;
    }

    public IntSet relationTiles()
    {
        return relationTiles;
    }

    @Override protected WorkerThread createWorker() throws Exception
    {
        return new Worker();
    }

    private void newBatch()
    {
        featureIds = new long[BATCH_SIZE];
        featureCount = 0;
    }

    private void flush()
    {
        submit(new Task(featureIds));
        newBatch();
    }

    public void addFeature(long typedId)
    {
        featureIds[featureCount++] = typedId;
        if (featureCount == BATCH_SIZE) flush();
    }

    protected static class Task
    {
        private final long[] featureIds;

        public Task(long[] featureIds)
        {
            this.featureIds = featureIds;
        }
    }

    protected class Worker extends WorkerThread
    {
        private final IntIndex nodeIndex;
        private final IntIndex wayIndex;
        private final IntIndex relationIndex;
        private final TileCatalog tileCatalog;
        private final MutableIntSet nodeTiles = new IntHashSet();
        private final MutableIntSet wayTiles = new IntHashSet();
        private final MutableIntSet relationTiles = new IntHashSet();

        Worker() throws IOException
        {
            nodeIndex = context.getNodeIndex();
            wayIndex = context.getWayIndex();
            relationIndex = context.getRelationIndex();
            tileCatalog = context.getTileCatalog();
        }

        private void addNodeTile(long id) throws IOException
        {
            int pile = nodeIndex.get(id);
            if(pile != 0) nodeTiles.add(tileCatalog.tileOfPile(pile));
        }

        private void addFeatureTiles(MutableIntSet tiles, IntIndex index, long id) throws IOException
        {
            int pileQuad = index.get(id);
            if(pileQuad != 0)
            {
                int tile = tileCatalog.tileOfPile(pileQuad >>> 2);
                if ((pileQuad & 3) == 3)
                {
                    // For features with > 2 tiles, we need to also scan
                    // the adjacent tile (in case `tile` refers to the
                    // empty quadrant of a sparse quad)
                    tiles.add(tile);
                    tile = Tile.neighbor(tile, Heading.EAST);
                }
                tiles.add(tile);
            }
        }


        @Override protected void process(Task task) throws Exception
        {
            long[] ids = task.featureIds;
            for(int i=0; i<ids.length; i++)
            {
                long typedId = ids[i];
                int type = FeatureId.typeCode(typedId);
                long id = FeatureId.id(typedId);
                switch(type)
                {
                case 0:
                    addNodeTile(id);
                    break;
                case 1:
                    addFeatureTiles(wayTiles, wayIndex, id);
                    break;
                case 2:
                    addFeatureTiles(relationTiles, relationIndex, id);
                    break;
                }
            }
        }

        @Override protected void postProcess() throws Exception
        {
            mergeTiles(nodeTiles, wayTiles, relationTiles);
        }
    }

    private synchronized void mergeTiles(IntSet partialNodeTiles,
        IntSet partialWayTiles, IntSet partialRelationTiles)
    {
        if(nodeTiles == null)
        {
            assert wayTiles == null;
            assert relationTiles == null;
            nodeTiles = new IntHashSet(partialNodeTiles.size() * 2);
            wayTiles = new IntHashSet(partialWayTiles.size() * 2);
            relationTiles = new IntHashSet(partialRelationTiles.size() * 2);
        }
        nodeTiles.addAll(partialNodeTiles);
        wayTiles.addAll(partialWayTiles);
        relationTiles.addAll(partialRelationTiles);
    }
}
