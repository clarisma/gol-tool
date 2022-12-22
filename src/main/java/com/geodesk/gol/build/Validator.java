/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.io.PileFile;
import com.clarisma.common.pbf.PbfBuffer;
import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.util.Log;
import com.clarisma.common.util.ProgressReporter;
import com.geodesk.feature.FeatureId;
import com.geodesk.core.Tile;
import com.geodesk.core.TileQuad;
import com.geodesk.core.XY;
import com.geodesk.feature.FeatureType;
import com.geodesk.core.Box;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.map.primitive.*;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import static com.geodesk.gol.build.ProtoGol.*;


// TODO:
//  - To support `tag-orphan-nodes`, need to read the node IDs of *all* ways
//    (not just multi-tile or ghosts) and set each node's NODE_USED_IN_WAY flag
//  - Later, we need to scan the node table for nodes without this flag, and
//    generate local-node records with a synthetic tag (geodesk:orphan=yes)
//    for those nodes that are not tagged or a relation member
//  - To support `tag-duplicate-nodes`, we need to build a map that tracks
//    which x/y is used more than once, then check each *untagged* node against
//    this map and generate a synthetic tag (geodesk:duplicate=yes)
//  - We need to combine synthetic tag generation, because a node can be both
//    orphan and duplicate
//  - We need to generate re-tagged nodes as local nodes, written back into the
//    source tile -- does this violate constraints on proto-gol rules?
//    (same node written more than once, nodes come after ways)
//  - We need to perform orphahn/duplicate check before exporting foreign nodes,
//    because the feature flag changes due to re-tagging (only matters for
//    duplicate nodes, as orphan nodes by definition are never exported,
//    because they are not referenced by a way or relation)

// TODO: Issue #76
//  - Relations that live in a sparse quad and have missing members can lead
//    to linker errors, because the features in the purgatory may back-link
//    to an unoccupied tile in the dense quad (because membership records
//    only contain dense quads). In this case, we need to calculate the
//    relation's true quad and export it to the purgatory tile.
//  - Need to maintain a list of relations (pointers into `relations`) that
//    have missing features (tracked during call to `readRelation()`)
//  - before call to writeForeignRelations(), need to calculate bounds of
//    these relations and add purgatory tile as a foreign tile for any
//    sparse-quad relation
//  - Better approach: always add purgatory pile to relation in readRelation()
//    if it has missing members AND its dense quad is 2x2; we'll write more
//    proxies than strictly necessary but processing is simplified

/**
 * The Validator prepares the piles for the Compiler.
 *
 * Since ways and relations can span more than one tile, we face two
 * fundamental problems: Way nodes and relation members may be in another tile.
 * The compiler needs all way nodes in order to calculate the way's geometry;
 * for relations, we need the bounding boxes of all members, in order to
 * calculate the relation's bounds.
 *
 * The Compiler could perform index lookups and load features from "foreign"
 * tiles, which is expensive. Alternatively, we could index the coordinates
 * of nodes and bounding boxes of ways and relations, but such indexes would
 * consume much more storage than the simple pile indexes.
 *
 * Instead, we're using a counter-intuitive approach: We read through the entire
 * import database and write the geometries of "foreign" features into the tiles
 * where they are needed. We proceed by zoom level, from highest to lowest.
 * Within each tile, features are always written in this order: nodes, ways,
 * relations, super-relations, 2nd-order super-relations, etc.
 *
 * Thus, we first read the node data and create an in-memory coordinate index.
 * Then we read all ways and build an in-memory index that refers back to its
 * encoded data. If a way appears in multiple tiles, we add all of its foreign
 * tiles to a set attached to each of its local nodes; in a later step, we
 * copy the way's local nodes into the other tiles. Next, we read relations
 * and memberships. For each membership, we add the tiles of the parent relation
 * to the feature's foreign-tile set. If a relation appears in tiles where a
 * member feature does not appear, we also add these tiles to the member's
 * foreign-tile set.
 *
 * Next, we read the foreign-feature data written from other tiles, and use
 * the coordinates, bounding boxes and tile quads to calculate the geometries
 * of features that are, in turn, required in other tiles. Since tiles on the
 * same zoom level are processed concurrently, how can we ensure that we have
 * the complete geometry for a multi-tile feature? We process each level in
 * four batches: even-column/even-row ... odd-column/odd-row. Based on a
 * feature's tile quad, we can tell during which pass its geometry is fully
 * known; at this point, we add the feature to the internal index.
 *
 * In the final step for each tile, we check which local features are required
 * in foreign tiles. For nodes, we copy their coordinates. For ways and
 * relations, we calculate their bounding boxes and sparse quads, and write
 * them into these foreign tiles.
 *
 * Internal Feature Indexes
 * ========================
 *
 * In order to track the features and their foreign tiles, we would potentially
 * need billions of objects. Instead of brutalizing the garbage collector this
 * wey, we use four IntList-based tables. We store four types of records:
 *
 * Node
 * ----
 * Each Node occupies a record that is five ints in length:
 *
 * 		[0]		Bits 0-23: 	high word of id
 * 	            Bits 24-28: unused
 * 				Bit 29: 	1 = node has tags
 * 				Bit 30: 	1 = node belongs to one or more ways
 * 				Bit 31: 	1 = node belongs to one or more relations
 * 		[1]	    low word of id
 * 		[2]   	x
 * 		[3]  	y
 * 		[4]   	index of first tile in the Tiles and Bounds
 *
 * Local nodes are stored in `nodes` and indexed in `nodeIndex`; foreign
 * nodes use the hashtable `foreignNodes` to track only their coordinates.
 *
 * Way / Relation
 * --------------
 * Each way or relation has a record of four ints:
 *
 * 		[0]		Bits 0-23:	high word of id
 * 				Bits 24-31: locator
 * 		[1]   	low word of id
 * 		[2]   	pointer to Bounds (or 0 if not needed)
 * 		[3]   	position of body in the pile data
 *
 * Local features are stored in `ways` / `relations`, and indexed in `wayIndex`
 * and `relationIndex`. Foreign features are indexed as well, but their index
 * value refers directly to their Bounds record (see below). We use a negative
 * value to differentiate pointers to Bounds from pointers to Way/Relation.
 *
 * TODO: to indicate that the quad/bbox of a Relation are still pending, we could
 *  store the bounds pointer as a negative value
 *
 * Bounds
 * ------
 * The Bounds record contains the sparse tile quad and bounding box of a way
 * or relation. For a local feature, it also contains a pointer to the first
 * foreign-tile reference.
 *
 * 		[0]		sparse tile quad (0 = tiles/bounds have not yet been calculated)
 * 	            // TODO: need marker for "relation currently calculating"
 * 	            // to enable resolution of circular refs
 * 	            // Can't put marker here, we need the quad for calculating interim
 * 	            // quad until we reach a stable state
 * 		[1]		minX
 * 		[2]		minY
 * 		[3]		maxX
 * 		[4]		maxY
 *		(5)		pointer to first TileRef
 *				(only for local features)
 *
 * Stored in `tilesAndBounds`; referenced from Way/Relation records or indexed
 * directly in wayIndex` / `relationIndex`.
 * Note that entry 0 must always be empty, because we use a negative value to
 * differentiate between pointers to `ways`/`relations` and `tilesAndBounds`,
 * so entries must start at position 1.
 *
 * TileRef
 * -------
 * We use a linked list to track into which tiles we need to write a feature's
 * geometry. Each entry is a two-int record:
 *
 * 		[0] 	tile
 * 		[1]		pointer to next TileRef (or 0)
 *
 * 	Stored in `tilesAndBounds`; referenced from Bounds or Node records
 */

// TODO: When calculating sparse quads, make sure that feature stays on its
//  original zoom level (i.e. zoom out)

public class Validator
{
    private ExecutorService executor;
    private final PileFile pileFile;
    private final TileCatalog tileCatalog;
    private final ProgressReporter reporter;
    private final boolean tagOrphanNodes;
    private final boolean tagDuplicateNodes;
    private final byte[] KEY_ORPHAN;
    private final byte[] KEY_DUPLICATE;
    private final byte[] VALUE_YES;

    private static final int N_X = 2;
    private static final int N_Y = 3;
    private static final int N_TILE_PTR = 4;
    private static final int N_LENGTH = 5;

    private static final int F_BOUNDS_PTR = 2;
    private static final int F_DATA_PTR = 3;
    private static final int F_LENGTH = 4;

    private static final int B_SPARSE_QUAD = 0;
    private static final int B_MIN_X = 1;
    private static final int B_MIN_Y = 2;
    private static final int B_MAX_X = 3;
    private static final int B_MAX_Y = 4;
    private static final int B_TILE_PTR = 5;
    private static final int B_LENGTH_LOCAL = 6;
    private static final int B_LENGTH_FOREIGN = 5;

    // flags for nodes

    private static final int NODE_IS_FEATURE_BIT	= 28;
    private static final int NODE_IS_FEATURE  		= (1 << NODE_IS_FEATURE_BIT);
    private static final int NODE_HAS_TAGS_BIT 		= 29;
    private static final int NODE_HAS_TAGS     		= (1 << NODE_HAS_TAGS_BIT);
    private static final int NODE_USED_IN_WAY  		= (1 << 30);
    private static final int NODE_IN_RELATION_BIT   = 31;
    private static final int NODE_IN_RELATION       = (1 << NODE_IN_RELATION_BIT);

    /**
     * A bounding box that also tracks a feature's sparse tile quad.
     */
    private static class FeatureBounds extends Box
    {
        int quad;

        void addQuad(int q)
        {
            quad = TileQuad.addQuad(quad, q);
        }
    }

    private static void skipPackedString(PbfBuffer decoder)
    {
        int len = (int)decoder.readVarint();
        if((len & 1) == 0) decoder.skip(len >> 1);
    }

    private static byte[] createPackedString(String s) throws IOException
    {
        byte[] encodedChars = s.getBytes(StandardCharsets.UTF_8);
        int len = encodedChars.length;
        assert len < 64;
            // one bit used for code/string discriminator,
            // another bit is used for the varint continuation marker
        byte[] ba = new byte[len + 1];
        ba[0] = (byte)(len << 1);
        System.arraycopy(encodedChars, 1, ba, 0, len);
        return ba;
    }

    public Validator(BuildContext ctx, int verbosity) throws IOException
    {
        this.tileCatalog = ctx.getTileCatalog();
        this.pileFile = ctx.getPileFile();
        tagDuplicateNodes = ctx.project().tagDuplicateNodes();
        tagOrphanNodes = ctx.project().tagOrphanNodes();
        reporter = new ProgressReporter(
            tileCatalog.tileCount(), "tiles",
            verbosity >= Verbosity.NORMAL ? "Validating" : null,
            verbosity >= Verbosity.QUIET ? "Validated" : null);

        // We could use the string tables for these keys and values, but
        // this would require loading them (the Validator does not use tags
        // in any other way). Since problem nodes are so rare, we can use the
        // less efficient way of storing these tags and values as strings
        // (Unlike GOLs, the proto-gol format does not mandate use of a string
        // code if a string is in a string table)

        KEY_DUPLICATE = createPackedString("geodesk:duplicate");
        KEY_ORPHAN    = createPackedString("geodesk:orphan");
        VALUE_YES     = createPackedString("yes");
    }

    private void processBatch(int batchCode, List<Task> tasks) throws Throwable
    {
        if(tasks.size() == 0) return;
        try
        {
            List<Future<Boolean>> results = executor.invokeAll(tasks);
            for(Future<Boolean> result: results) result.get();
        }
        catch (InterruptedException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch (ExecutionException ex)
        {
            throw ex.getCause();
        }
    }

    private void batchTasks() throws Throwable
    {
        int tileCount = tileCatalog.tileCount();
        long[] sorted = new long[tileCount-2];

        // Sort piles by zoom level (highest level first), then by quadrant
        // (odd/even row/column)
        // We start with pile #3, because:
        //  - 0 is not used
        //  - 1 contains the Purgatory
        //  - 2 is the root tile (no validation needed)

        assert Tile.zoom(tileCatalog.tileOfPile(2)) == 0:
            "Expected Pile #2 to be the root tile";

        for(int pile=3; pile <= tileCount; pile++)
        {
            int tile = tileCatalog.tileOfPile(pile);
            int zoom = Tile.zoom(tile);
            int col = Tile.column(tile);
            int row = Tile.row(tile);
            int quadrant = (col & 1) | ((row & 1) << 1);
            // TODO: why bother including row & col?
            int sortKey = ((15-zoom) << 26) | (quadrant << 24) |
                (row << 12) | col;
            sorted[pile-3] = ((long)sortKey << 32) | pile;
        }
        Arrays.sort(sorted);

        List<Task> tasks = new ArrayList<>();

        int currentBatchCode = 0;
        for(int i=0; i<sorted.length; i++)
        {
            int newBatchCode = (int)(sorted[i] >>> 56);
            if(newBatchCode != currentBatchCode)
            {
                processBatch(currentBatchCode, tasks);
                tasks.clear();
                currentBatchCode = newBatchCode;
            }
            tasks.add(new Task((int)sorted[i]));
        }
        processBatch(currentBatchCode, tasks);
    }

    public void validate() throws Throwable
    {
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());	// TODO
        batchTasks();
        executor.shutdown();
        reporter.finished();
    }

    // move to db
    private synchronized byte[] loadTileData(int pile)
    {
        try
        {
            byte[] data = pileFile.load(pile);
            if(data.length > 0)
            {
                //log.info("Loading tile {}: {}",  pile, Tile.toString(tileResolver.tileOfPile(pile)));
            }
            return data;
        }
        catch(IOException ex)
        {
            System.out.println(ex.getMessage());
            ex.printStackTrace();
            return null;
        }
    }


    private class Task implements Callable<Boolean>
    {
        private int sourcePile;
        private int sourceTile;
        private int zoom;
        private int quadrant;
        private PbfBuffer sourceData;
        private MutableIntList nodes;
        private MutableLongIntMap nodeIndex;
        private MutableLongLongMap foreignNodes;
        private MutableIntList ways;
        private MutableLongIntMap wayIndex;
        private MutableIntList relations;
        private MutableLongIntMap relationIndex;
        private MutableIntList tilesAndBounds;
        private MutableIntObjectMap<Encoder> encoders;

        // TODO: should we consolidate the various tables?
        //  No, need to scan each type

        public Task(int pile)
        {
            this.sourcePile = pile;
        }

        private void init()
        {
            sourceTile = tileCatalog.tileOfPile(sourcePile);
            zoom = Tile.zoom(sourceTile);
            quadrant = (Tile.column(sourceTile) & 1) | ((Tile.row(sourceTile) & 1) << 1);
            int nodeDensity = 1024; // 128 * 1024;
            nodes = new IntArrayList(nodeDensity * 4);
            nodeIndex = new LongIntHashMap(nodeDensity * 2);
            foreignNodes = new LongLongHashMap(nodeDensity / 4);
            ways = new IntArrayList(nodeDensity / 8 * 4);
            wayIndex = new LongIntHashMap(nodeDensity / 4);
            relations = new IntArrayList(nodeDensity / 512 * 4);
            relationIndex = new LongIntHashMap(nodeDensity / 256);
            tilesAndBounds = new IntArrayList(nodeDensity);
            encoders = new IntObjectHashMap<>();

            // Occupy the first slot of each table, since we never use position
            // zero in the index

            nodes.add(0);
            ways.add(0);
            relations.add(0);
            tilesAndBounds.add(0);
        }

        /**
         * Explicitly nulls out the task's heavy data structures, so the
         * garbage collector can do its job. Since the method that batches
         * the tasks holds on to all of them until the entire batch completes,
         * we would otherwise needlessly take up memory.
         */
        private void clear()
        {
            sourceData = null;
            nodes = null;
            nodeIndex = null;
            foreignNodes = null;
            ways = null;
            wayIndex = null;
            relations = null;
            relationIndex = null;
            tilesAndBounds = null;
            encoders = null;
        }

        private void readTile()
        {
            while(sourceData.hasMore())
            {
                int groupMarker = sourceData.readByte();
                int groupType = groupMarker & 7;
                int featureType = groupMarker >>> 3;
                if(groupType == LOCAL_FEATURES)
                {
                    if(featureType==NODES)
                    {
                        readNodes();
                    }
                    else if(featureType==WAYS)
                    {
                        readWays();
                    }
                    else if(featureType==RELATIONS)
                    {
                        readRelations();
                    }
                    else
                    {
                        Log.error("Unknown marker %d in tile %s (Pile %d)", groupMarker,
                            Tile.toString(sourceTile), sourcePile);
                        break;
                    }
                }
                else if(groupType == FOREIGN_FEATURES)
                {
                    switch(featureType)
                    {
                    case NODES:
                        readForeignNodes();
                        break;
                    case WAYS:
                        readForeignFeatures(wayIndex);
                        break;
                    case RELATIONS:
                        readForeignFeatures(relationIndex);
                        break;
                    }
                }
                else
                {
                    Log.error("Unknown marker %d in tile %s (Pile %d)", groupMarker,
                        Tile.toString(sourceTile), sourcePile);
                    break;
                }
            }
        }

        private void readNodes()
        {
            long prevId = 0;
            int prevX = 0;
            int prevY = 0;
            for(;;)
            {
                long id = sourceData.readVarint();
                if(id==0) break;
                int tagsFlag = (int)id & 1;
                id = prevId + (id >> 1);
                int x = (int)sourceData.readSignedVarint() + prevX;
                int y = (int)sourceData.readSignedVarint() + prevY;
                int pos = nodes.size();
                int nodeFlags = (NODE_HAS_TAGS | NODE_IS_FEATURE) * tagsFlag;
                    // since tagsFlags is either 1 or 0, sets or clears both flags
                    // without need for branching
                nodes.add((int)(id >> 32) | nodeFlags);
                nodes.add((int)id);
                nodes.add(x);
                nodes.add(y);
                nodes.add(0);
                if(tagsFlag != 0)
                {
                    int tagsLen = (int)sourceData.readVarint();
                    sourceData.skip(tagsLen);
                }
                assertDoesNotExist(nodeIndex, "node", id);
                nodeIndex.put(id, pos);
                prevId = id;
                prevX = x;
                prevY = y;
            }
        }

        /**
         * Checks if a linked list of tiles contains the given tile.
         *
         * @param ptr	pointer to the first tile entry in tilesAndBounds
         * @param tile	the tile to look for
         * @return
         */
        private boolean hasTile(int ptr, int tile)
        {
            while(ptr != 0)
            {
                if((tilesAndBounds.get(ptr) & 0x7fff_ffff) == tile) return true;
                ptr = tilesAndBounds.get(ptr+1);
            }
            return false;
        }

        private void addNodeTiles(int pNode, int tileQuad)
        {
            assert pNode > 0;
            int ppFirstTile = pNode + N_TILE_PTR;
            int prevFirstTile = nodes.get(ppFirstTile);
            TileQuad.forEach(tileQuad, tile ->
            {
                if(!hasTile(prevFirstTile, tile))
                {
                    int p = tilesAndBounds.size();
                    tilesAndBounds.add(tile);
                    tilesAndBounds.add(nodes.get(ppFirstTile));
                    nodes.set(ppFirstTile, p);
                }
            });
        }

        /**
         * Retrieves a feature's Bounds record, creating a blank one if it doesn't exist.
         *
         * @param features  `ways` or `relations`
         * @param pFeature	pointer to the Feature record
         * @return 			pointer to the Bounds record
         */
        // TODO: rename
        // TODO: Check: this always creates bounds for a local feature
        private int getBounds(MutableIntList features, int pFeature)
        {
            assert features == ways || features == relations;
            int pBounds = features.get(pFeature + F_BOUNDS_PTR);
            if(pBounds == 0)
            {
                pBounds = tilesAndBounds.size();
                tilesAndBounds.addAll(0,0,0,0,0,0);
                    // TODO: maybe this should be a bbox that can
                    //  be added to another bbox without enlarging it
                    //  (ie. minX = maxInt, maxX = minInt)
                features.set(pFeature + F_BOUNDS_PTR, pBounds);
            }
            return pBounds;
        }

        // TODO: scanning the linked list could be expensive in some cases,
        //  maybe use a Bloom-filter-like hashed bit set in order
        //  to determine which tiles are definitely not in the list yet
        //  this would take up an extra word in tilesAndBounds
        //  But measure first if this is really an issue!
        private void addTiles(MutableIntList features, int pFeature, int tileQuad)
        {
            assert features == ways || features == relations;
            if(pFeature <= 0) return;       // TODO: don't assert, allow this
                // TODO: but make consistent with node, where we assert
            int pBounds = getBounds(features, pFeature);
            int ppFirstTile = pBounds + B_TILE_PTR;
            int prevFirstTile = tilesAndBounds.get(ppFirstTile);
            TileQuad.forEach(tileQuad, tile ->
            {
                if(!hasTile(prevFirstTile, tile))
                {
                    int p = tilesAndBounds.size();
                    tilesAndBounds.add(tile);
                    tilesAndBounds.add(tilesAndBounds.get(ppFirstTile));
                    tilesAndBounds.set(ppFirstTile, p);
                }
            });
        }

        /**
         * Determines whether a feature's geometry will be known when
         * it is processed by the current task. At each zoom level, tiles
         * are processed in batches according to this order:
         *
         * 		- even column / even row [00]
         * 		- odd  column / even row [01]
         * 		- even column / odd  row [10]
         * 		- odd  column / odd  row [11]
         *
         * When we process a tile, we check which of its nodes are part of
         * ways that have copies in other tiles, and copy (or proxy) the
         * nodes to those tiles. Likewise, we proxy all features that are
         * used by relations in other tiles. Hence, the geometry of a way
         * or relation are only known once all of its other tiles have been
         * processed.
         *
         * Therefore, we can apply this rule:
         *
         * - If the feature only occupies a single tile, its geometry is
         *   always known
         *
         * - If the feature occupies a 2x2 quad, its geometry will be known
         *   only once we are processing the odd/odd [11] batch (since it
         *   is the last)
         *
         * - If the feature occupies a tile pair, its geometry is complete:
         * 		- For a west-east pair: if we are processing odd-column tiles
         * 		- For a north-south pair: if we are processing odd-row tiles
         *
         * @param quad		the (dense) tile quad occupied by the feature
         * @return			true if we can determine its geometry; false if
         * 					the feature's other tiles have not yet been
         * 					processed
         */
        private boolean hasCompleteGeometry(int quad)
        {
            int tileCount = TileQuad.tileCount(quad);
            if(tileCount == 2)
            {
                if(TileQuad.width(quad) == 2)
                {
                    return (quadrant & 1) != 0;
                }
                else
                {
                    return (quadrant >> 1) != 0;
                }
            }
            else if(tileCount == 4)
            {
                return quadrant == 3;
            }
            assert tileCount == 1; // should only be 1,2, or 4
            return true;
        }

        /**
         * Reads a FeatureGroup filled with ways from sourceData (the buffer's
         * current position must be the byte just after the group marker).
         * Afterwards, the buffer position will be at the next group marker
         * (or at the end of the buffer).
         *
         * For every way that lives in multiple tiles (or uses nodes that are
         * located in a higher-zoom tile), we add all the way's tiles (except
         * the one that is currently being read) to the tile list of each
         * of the way's nodes that live in the current tile (In a later step,
         * these nodes will be copied to those tiles as foreign nodes).
         *
         * TODO: We also mark each local node that is used as a way-node.
         *
         * A way will only be stored (and indexed) if its geometry is complete.
         */

        private void readWays()
        {
            long prevId = 0;
            for(;;)
            {
                long id = sourceData.readVarint();
                if(id==0) break;

                int multiTileFlag = (int)id & 1;
                id = prevId + (id >> 1);
                prevId = id;
                int bodyLen;
                int ptr;
                byte tileLocator;

                if(multiTileFlag != 0)
                {
                    tileLocator = sourceData.readByte();
                    bodyLen = (int)sourceData.readVarint();
                    ptr = sourceData.pos();

                    int quad = TileQuad.fromDenseParentLocator(tileLocator, sourceTile);
                    assert TileQuad.isValid(quad);
                    int foreignQuad;

                    int nodeCount = (int)sourceData.readVarint();
                    int partialFlag = nodeCount & 1;
                    if(partialFlag == 0)
                    {
                        assert Tile.zoom(quad) == Tile.zoom(sourceTile) :
                            "All copies of a Way (except Ghost Ways) must live " +
                                "at the same zoom level";
                        foreignQuad = TileQuad.subtractQuad(quad,
                            TileQuad.fromSingleTile(sourceTile));
                    }
                    else
                    {
                        assert Tile.zoom(quad) < Tile.zoom(sourceTile) :
                            "A Ghost Way must live at a lower zoom level than " +
                                "the actual Way";
                        foreignQuad = quad;
                    }
                    nodeCount >>>= 1;
                    long prevNodeId = 0;
                    for(;nodeCount>0; nodeCount--)
                    {
                        long nodeId = sourceData.readSignedVarint() + prevNodeId;
                        int pNode = nodeIndex.get(nodeId);
                        if(pNode != 0)
                        {
                            addNodeTiles(pNode, foreignQuad);
                            markNode(pNode, NODE_USED_IN_WAY);
                        }
                        prevNodeId = nodeId;
                    }
                    sourceData.seek(ptr + bodyLen);
                    if(partialFlag != 0 || !hasCompleteGeometry(quad))
                    {
                        // If this is a "ghost", or a feature for which we are not able
                        // to determine the complete geometry yet, don't create a way record
                        continue;
                    }
                }
                else
                {
                    tileLocator = TileQuad.toDenseParentLocator(sourceTile | TileQuad.NW, sourceTile);
                    bodyLen = (int)sourceData.readVarint();
                    ptr = sourceData.pos();
                    if(tagOrphanNodes)
                    {
                        // If the option to identify and retain orphan nodes is enabled,
                        // we need to scan the node IDs of *all* ways, and set the flag
                        // that indicates that a node is used in a way

                        int nodeCount = (int)sourceData.readVarint();
                        assert (nodeCount & 1) == 0: "Single-tile way: Partial flag must be clear";
                        nodeCount >>>= 1;
                        long prevNodeId = 0;
                        for(;nodeCount>0; nodeCount--)
                        {
                            long nodeId = sourceData.readSignedVarint() + prevNodeId;
                            int pNode = nodeIndex.get(nodeId);
                            if(pNode != 0) markNode(pNode, NODE_USED_IN_WAY);
                            prevNodeId = nodeId;
                        }
                    }
                    else
                    {
                        sourceData.skip(bodyLen);
                    }
                }
                int pWay = ways.size();

                // put the tile locator into the flag byte (the upper byte of the id)
                ways.add((int)(id >> 32) | (tileLocator << 24));
                ways.add((int)id);
                ways.add(0);        // bounds pointer (filled in later, if needed)
                ways.add(ptr);      // offset of body data
                assertDoesNotExist(wayIndex, "way", id);
                wayIndex.put(id, pWay);
            }
        }

        /**
         * Checks if a way or relation that is a member of a relation
         * lives in fewer tiles than the relation. If so, we add the
         * tiles where the relation lives, but the member feature does
         * not, to the feature's tile list. In a later step, we will
         * write the bounds to those tiles, so that the full bounding
         * box of the relation can be calculated. This only applies
         * to members that live at the same zoom level as the relation.
         * If a member feature lives at a higher zoom level, we add
         * all of the relation's tiles to the member's tile list based
         * on the membership record (in a different method).
         *
         * @param features  	    `ways` or `relations`
         * @param pFeature		    pointer to the member feature
         * @param relQuad	        the tile quad of the relation
         */
        private void addTilesToMember(MutableIntList features,
            int pFeature, int relQuad)
        {
            int memberQuad = TileQuad.fromDenseParentLocator(
                (byte)(features.get(pFeature) >>> 24), sourceTile);
            assert TileQuad.zoom(memberQuad) == TileQuad.zoom(relQuad);
            int missingQuad = TileQuad.subtractQuad(relQuad, memberQuad);
            addTiles(features, pFeature, missingQuad);
        }


        /**
         * For a relation that covers multiple tiles, adds the
         * relation's tiles where the node does not live to
         * the node's tile list. In a later step, we will
         * write the node's coordinates to those tiles, so that
         * the full bounding box of the relation can be calculated.
         * This only applies to nodes that live at the same zoom
         * level as the relation.
         * If a node lives at a higher zoom level, we add all of
         * the relation's tiles to the member's tile list based
         * on the membership record (in a different method).
         *
         * @param pNode			pointer to the node
         * @param relQuad	    the tile quad of the relation
         */
        private void addTilesToMemberNode(int pNode, int relQuad)
        {
            int nodeQuad = TileQuad.fromSingleTile(sourceTile);
            assert TileQuad.zoom(nodeQuad) == TileQuad.zoom(relQuad);
            int missingQuad = TileQuad.subtractQuad(relQuad, nodeQuad);
            addNodeTiles(pNode, missingQuad);
        }

        /**
         * Reads an individual relation within a FeatureGroup from sourceData.
         * The buffer's current position must be the byte just after the
         * relation's id (the tile locator). Afterwards, the buffer position
         * will be at the ID of the next relation (or membership), or at the
         * group's end marker.
         *
         * If the relation lives in multiple tiles, we need to check if it has
         * members that live only in a subset of these tiles (for example,
         * a relation may occupy a 2x2 quad, but one of its ways only spans
         * the NW and NE quadrants). For these features, we need to write
         * the bounds to the tiles where the member does not appear, so the
         * relation's bounding box can be fully calculated in each of its
         * individual tiles. If a member itself spans multiple tiles,
         * we can perform this step only once the member's geometry is fully
         * known, i.e. when we process tiles with odd-numbered columns (for
         * east-west features) or odd-numbered rows (for north-south) features.
         *
         * A relation will only be stored (and indexed) if its geometry is complete.
         */
        private void readRelation(long id)
        {
            byte tileLocator = sourceData.readByte();
            int relQuad = TileQuad.fromDenseParentLocator(tileLocator, sourceTile);
            assert relQuad != -1;

            int bodyLen = (int)sourceData.readVarint();
            int ptr = sourceData.pos();
            int memberCount = (int)sourceData.readVarint();
            for(int i=0; i<memberCount; i++)
            {
                long m = sourceData.readVarint();
                skipPackedString(sourceData);
                FeatureType memberType = FeatureId.type(m);
                long memberId = FeatureId.id(m);
                if(memberType == FeatureType.NODE)
                {
                    int pNode = nodeIndex.get(memberId);
                    if(pNode != 0)
                    {
                        markNode(pNode, NODE_IN_RELATION | NODE_IS_FEATURE);
                        if(TileQuad.tileCount(relQuad) > 1)
                        {
                            // For a multi-tile relation, add node proxies to all
                            // tiles (except the one containing the node)
                            addTilesToMemberNode(pNode, relQuad);
                        }
                    }
                }
                else if(TileQuad.tileCount(relQuad) > 1)
                {
                    if(memberType == FeatureType.WAY)
                    {
                        int pWay = wayIndex.get(memberId);
                        assert pWay >= 0: "Cannot be a proxy -- are we running Validator more than once?";
						if(pWay > 0)
                        {
                            addTilesToMember(ways, pWay, relQuad);
                        }
                    }
                    else
                    {
                        int pChildRel = relationIndex.get(memberId);
                        if(pChildRel > 0)
                        {
                            addTilesToMember(relations, pChildRel, relQuad);
                        }
                    }
                }
            }
            sourceData.seek(ptr + bodyLen);

            if(hasCompleteGeometry(relQuad))
            {
                int pRelation = relations.size();
                // put tile locator into the highest byte of the id
                relations.add((int)(id >> 32) | (tileLocator << 24));
                relations.add((int)id);
                relations.add(0);
                relations.add(ptr);
                assertDoesNotExist(relationIndex, "relation", id);
                relationIndex.put(id, pRelation);
            }
        }

        private void assertDoesNotExist(MutableLongIntMap index, String type, long id)
        {
            assert index.get(id) == 0: String.format("%s/%d already exists", type, id);
        }

        // TODO: id not needed
        private void readMembership(long relId)
        {
            // TODO: locator could be complex, refer to non-related
            //  tile, 0xFF + tile number
            byte tileLocator = sourceData.readByte();
            int relQuad = TileQuad.fromDenseParentLocator(tileLocator, sourceTile);
            assert relQuad != -1;

            long member = sourceData.readVarint();
            FeatureType type = FeatureId.type(member);
            long memberId = FeatureId.id(member);
            switch(type)
            {
            case NODE:
                int pNode = nodeIndex.get(memberId);
                if(pNode != 0)
                {
                    markNode(pNode, NODE_IN_RELATION | NODE_IS_FEATURE);
                    addNodeTiles(pNode, relQuad);
                }
                break;
            case WAY:
                int p = wayIndex.get(memberId);
                addTiles(ways, p, relQuad);
                break;
            case RELATION:
                p = relationIndex.get(memberId);
                addTiles(relations, p, relQuad);
                break;
            }
        }

        /**
         * Reads a FeatureGroup filled with relations (or relation memberships)
         * from sourceData (the buffer's current position must be the byte
         * just after the group marker). Afterwards, the buffer position will
         * be at the next group marker (or at the end of the buffer).
         *
         * During this step, we determine which other tiles will need to receive
         * the bounds of a relation or its members.
         */
        private void readRelations()
        {
            long prevId = 0;
            for(;;)
            {
                long id = sourceData.readVarint();
                if(id==0) break;
                int membershipFlag = (int)id & 1;
                id = prevId + (id >> 1);
                if(membershipFlag == 0)
                {
                    readRelation(id);
                }
                else
                {
                    readMembership(id);
                }
                prevId = id;
            }
        }

        private int readDonorTile()
        {
            int donorPile = (int)sourceData.readVarint();
            int donorTile = tileCatalog.tileOfPile(donorPile);
            assert donorTile != sourceTile: "Donor tile must be different from current tile";
            assert Tile.isValid(donorTile);
            return donorTile;
        }

        /**
         * Reads a ForeignFeatures group filled with nodes from sourceData
         * (the buffer's current position must be the byte just after the
         * group marker: the varint that encodes the tile from which the
         * nodes originate). Afterwards, the buffer position will
         * be at the next group marker (or at the end of the buffer).
         *
         * We place the coordinates of the nodes into the index of foreign
         * nodes, so that the dimensions of the ways and relations which
         * contain these nodes can be calculated.
         */
        private void readForeignNodes()
        {
            int donorTile = readDonorTile();    // keep this
            // log.debug("Reading foreign nodes from Tile {}", Tile.toString(donorTile));

			long prevId = 0;
            int prevX = 0;
            int prevY = 0;
            for(;;)
            {
                long id = sourceData.readVarint();
                if(id==0) break;
                id = (id >> 1) + prevId;
                int x = (int)sourceData.readSignedVarint() + prevX;
                int y = (int)sourceData.readSignedVarint() + prevY;
                foreignNodes.put(id, XY.of(x, y));
                prevId = id;
                prevX = x;
                prevY = y;
            }
        }

        /**
         * Reads a Foreign Features group filled with ways or relations from
         * sourceData (the buffer's current position must be the byte just
         * after the group marker: the varint that encodes the tile from which
         * the features originate). Afterwards, the buffer position will be at
         * the next group marker (or at the end of the buffer).
         *
         * We place the sparse quad of the way or relation, along with its
         * bounding box, into tilesAndBounds, and place a pointer to this
         * structure into wayIndex or relationIndex (a sequence of 5 integers).
         * To differentiate pointers to bounds (in tilesAndBounds) from the
         * record for a local feature (in ways or relations), we store a bounds
         * pointer as a negative value.
         *
         * @param featureIndex `wayIndex` or `relationIndex`
         */
        private void readForeignFeatures(MutableLongIntMap featureIndex)
        {
            assert featureIndex == wayIndex || featureIndex == relationIndex;
            int donorTile = readDonorTile();
            long prevId = 0;
            int prevX = 0;
            int prevY = 0;
            for(;;)
            {
                long id = sourceData.readVarint();
                if(id==0) break;
                int multiTileFlag = (int)id & 1;
                id = prevId + (id >> 1);
                int pBounds = tilesAndBounds.size();
                if(multiTileFlag != 0)
                {
                    byte locator = sourceData.readByte();
                    tilesAndBounds.add(TileQuad.fromSparseSiblingLocator(locator, donorTile));
                }
                else
                {
                    tilesAndBounds.add(TileQuad.fromSingleTile(donorTile));
                }
                int x1 = (int)sourceData.readSignedVarint() + prevX;
                int y1 = (int)sourceData.readSignedVarint() + prevY;
                tilesAndBounds.add(x1);
                tilesAndBounds.add(y1);
                tilesAndBounds.add((int)sourceData.readVarint() + x1);
                tilesAndBounds.add((int)sourceData.readVarint() + y1);
                    // careful, always check signed vs. unsigned
                    // x1/y1 are signed, x2/y2 unsigned (because they are always greater)
                prevId = id;
                prevX = x1;
                prevY = y1;
                featureIndex.put(id, -pBounds);
                    // We enter a negative value in the index to indicate that this
                    // is a pointer to the bounds (instead of to the actual feature)
            }
        }

        /**
         * Retrieves the coordinates of a node, which can be local or foreign.
         *
         * @param id	the id of the node
         * @return the node's coordinates (y stored in upper 32 bits,
         *   	x in lower), or 0 if the node's tile has not been
         *      processed yet(or the node is missing)
         *
         */
        private long getNodeXY(long id)
        {
            int p = nodeIndex.get(id);
            if(p == 0) return foreignNodes.get(id);
            return XY.of(nodes.get(p + N_X), nodes.get(p + N_Y));
        }

        private void markNode(int pNode, int flags)
        {
            nodes.set(pNode, nodes.get(pNode) | flags);
        }

        /**
         * Stores the given sparse quad and bounding box in `tilesAndBounds`.
         *
         * @param p     	the starting index of the Bounds record
         * 					in `tilesAndBounds`
         * @param bounds	the sparse quad and bounding box
         */
        private void storeBounds(int p, FeatureBounds bounds)
        {
            if(bounds.quad != 0)
            {
                assert (bounds.quad & 0xf000_0000) != 0:
                    String.format("Quad must not be empty: %s", TileQuad.toString(bounds.quad));
                int quadZoom = TileQuad.zoom(bounds.quad);
                if(quadZoom != zoom)
                {
                    // TODO: do we need this?
                    // TODO: do we need to check if all tiles actually exist?
                    if(quadZoom > zoom)
                    {
                        // Ensure that the calculated quad is at the zoom level of
                        // the feature
                        // log.debug("Zooming out {}", TileQuad.toString(bounds.quad));
                        bounds.quad = TileQuad.zoomedOut(bounds.quad, zoom);
                    }
                    else
                    {
                        bounds.quad = TileQuad.ROOT;
                    }
                }
            }
            tilesAndBounds.set(p + B_SPARSE_QUAD, bounds.quad);
            tilesAndBounds.set(p + B_MIN_X, bounds.minX());
            tilesAndBounds.set(p + B_MIN_Y, bounds.minY());
            tilesAndBounds.set(p + B_MAX_X, bounds.maxX());
            tilesAndBounds.set(p + B_MAX_Y, bounds.maxY());
        }

        private void addNodeToBounds(long id, FeatureBounds bounds)
        {
            long xy = getNodeXY(id);
            if(xy == 0) return;     // missing node
            int x = XY.x(xy);
            int y = XY.y(xy);
            bounds.expandToInclude(x,y);
            bounds.quad = TileQuad.addPoint(bounds.quad, x, y, zoom);
        }

        /**
         * Adds an extent from tileAndBounds to the given feature bbox
         *
         * @param p         pointer to bounds data in `tileAndBounds`
         * @param bounds    the feature bbox to which to add
         *
         * TODO: order of params feels awkward, put bounds first
         */
        private void addToBounds(int p, FeatureBounds bounds)
        {
            int tileQuad = getQuad(p);
            if(tileQuad == 0 || tileQuad == -1) return;   // do not add empty or invalid quad
                // careful, valid tileQuad numbers can be negative
                // TODO: check if any of the topmost 4 bits are present
                //  Use marker 0x0fff_ffff to indicate "pending"
                //  do not allow invalid quad -1 to propagate
            int minX = tilesAndBounds.get(p + B_MIN_X);
            int minY = tilesAndBounds.get(p + B_MIN_Y);
            int maxX = tilesAndBounds.get(p + B_MAX_X);
            int maxY = tilesAndBounds.get(p + B_MAX_Y);
            if(maxX < minX) return;
                // don't add an empty relation, which has no bbox
                // TODO: should BoundingBox.expandToInclude guard against this already?
            bounds.expandToInclude(minX, minY, maxX, maxY);
            bounds.addQuad(tileQuad);
        }

        /**
         * Returns the sparse tile quad of a way or relation.
         *
         * @param pBounds   pointer to the feature's bounds
         * @return  the feature's sparse tile quad
         */
        private int getQuad(int pBounds)
        {
            return tilesAndBounds.get(pBounds + B_SPARSE_QUAD);
        }

        /**
         * Returns the area of a feature's bounding box.
         *
         * This could overflow for extremely large features (covering more than 1/2
         * of the world), but we only use this method to determine if the bbox
         * has changed, so this should be ok.
         *
         * @param pBounds   pointer to the feature's bounds
         * @return  area (width * height of bounding box)
         */
        private long getArea(int pBounds)
        {
            return
                (tilesAndBounds.get(pBounds + B_MAX_X) -
                    tilesAndBounds.get(pBounds + B_MIN_X)) *
                    (tilesAndBounds.get(pBounds + B_MAX_Y) -
                        tilesAndBounds.get(pBounds + B_MIN_Y));
        }

        /**
         * Adds a relation's quad and bounding box to the given FeatureBounds.
         *
         * @param id
         * @param bounds
         * @return  true if the relation's bounds have been resolved, or false
         *          if the relation is in a reference cycle
         */
        private boolean addRelationBounds(long id, FeatureBounds bounds)
        {
            int pRelation = relationIndex.get(id);
            if(pRelation == 0) return true;
            if(pRelation < 0)
            {
                addToBounds(-pRelation, bounds);
                return true;
            }
            int dataPtr = relations.get(pRelation + F_DATA_PTR);
            int pBounds = getBounds(relations, pRelation);
            if(dataPtr > 0)
            {
                calculateRelationBounds(pRelation, pBounds);
                dataPtr = relations.get(pRelation + F_DATA_PTR);
            }
            addToBounds(pBounds, bounds);
            return dataPtr < 0;
        }

        /**
         * Calculates the sparse quad and bounding box of a relation.
         *
         * @param pRelation     pointer to relation's Feature record
         *                      (index in `relations`)
         * @param pBounds       pointer to relation's Bounds record
         *                      (index in `tilesAndBounds`)
         *
         * @return true if the relation's bounds have been resolved, or false
         *         if the relation is in a reference cycle
         *
         * TODO: deal with empty relations
         *  bbox should be [ 0,0,-1,-1 ]
         */

        // TODO: bboxes of circular relations are not calculated properly
        //  we need to apply the same stable-state algo as Importer and
        //  Compiler. See relation/1212338, which has ways only in 9/271/165,
        //  but includes relation/3067061 (which in turn refers back to
        //  relation/1212338). relation/3067061 lives in both 9/270/165
        //  and 9/271/165, and should propagate this quad to relation/1212338
        //  Could use high bit of pBounds pointer to indicate that bounds
        //  are not yet final

        private boolean calculateRelationBounds(int pRelation, int pBounds)
        {
            assert pRelation > 0;
            assert pBounds > 0;
            FeatureBounds bounds = new FeatureBounds();
            int startPos = relations.get(pRelation + F_DATA_PTR);
            relations.set(pRelation + F_DATA_PTR, 0);
            sourceData.seek(startPos);
            int memberCount = (int)sourceData.readVarint();
            boolean resolved = true;
            for(int i=0; i<memberCount; i++)
            {
                long m = sourceData.readVarint();
                skipPackedString(sourceData);
                int pos = sourceData.pos();
                    // We have to save the position of the reader, since
                    // calls to getWayBounds/getRelationBounds might move it
                FeatureType memberType = FeatureId.type(m);
                long memberId = FeatureId.id(m);

                switch(memberType)
                {
                case NODE:
                    addNodeToBounds(memberId, bounds);
                    break;
                case WAY:
					int pMemberBounds = getWayBounds(memberId);
                    if(pMemberBounds != 0)
                    {
                        if(tilesAndBounds.get(pMemberBounds) == 0)
                        {
                            Log.warn("Failed to calculate quad for way/%d",  memberId);
                        }
                        else
                        {
                            addToBounds(pMemberBounds, bounds);
                            assert (bounds.quad & 0xf000_0000) != 0:
                                String.format("Quad is Empty (but nonzero) for " +
                                        "relation/%d after adding way/%d: %s",
                                    getId(relations, pRelation), memberId,
                                    TileQuad.toString(bounds.quad));
                        }
                    }
                    break;
                case RELATION:
                    if(!addRelationBounds(memberId, bounds)) resolved = false;
                    break;
                }
                sourceData.seek(pos);
            }
            if(bounds.quad == 0)
            {
                Log.warn("Could not calculate quad for relation/%d [processing %s]",
                    getId(relations, pRelation), Tile.toString(sourceTile));
            }
            assert memberCount > 0 || bounds.isNull();
                // bounds must be null for empty relation
            assert TileQuad.isValid(bounds.quad);
            assert (bounds.quad & 0xf000_0000) != 0 || bounds.quad == 0:
                String.format("Empty (but nonzero) quad for relation/%d: %s",
                    getId(relations, pRelation), TileQuad.toString(bounds.quad));

            storeBounds(pBounds, bounds);
            relations.set(pRelation + F_DATA_PTR, resolved ? -startPos : startPos);

            return resolved;
        }

        /**
         * Returns a pointer of the Bounds record of the given way,
         * creating and calculating it if it doesn't yet exist.
         *
         * @param id the way's ID
         * @return index in tilesAndBounds of the way's Bounds record
         */
        private int getWayBounds(long id)
        {
            int pWay = wayIndex.get(id);
            if(pWay <= 0) return -pWay;
            assert getId(ways, pWay) == id;
            int pBounds = getBounds(ways, pWay);
            if(tilesAndBounds.get(pBounds) == 0)
            {
                calculateWayBounds(pWay, pBounds);
            }
            return pBounds;
        }

        /**
         * Calculates the sparse quad and bounding box of a way.
         *
         * @param pWay          pointer to way's Feature record
         *                      (index in `ways`)
         * @param pBounds       pointer to way's Bounds record
         *                      (index in `tilesAndBounds`)
         */
        private void calculateWayBounds(int pWay, int pBounds)
        {
            FeatureBounds bounds = new FeatureBounds();
            int pos = ways.get(pWay + F_DATA_PTR);
            sourceData.seek(pos);
            int nodeCount = ((int)sourceData.readVarint()) >>> 1;
            long prevNodeId = 0;
            int prevX = 0;
            int prevY = 0;
            assert nodeCount > 1: String.format("Invalid node count for way/%d: %d",
                getId(ways, pWay), nodeCount);

            for(int i=0; i<nodeCount; i++)
            {
                long nodeId = sourceData.readSignedVarint() + prevNodeId;
                long xy = getNodeXY(nodeId);
                if(xy != 0)
                {
                    int x = XY.x(xy);
                    int y = XY.y(xy);
                    bounds.expandToInclude(x,y);
                    if(prevX != 0 || prevY != 0)
                    {
                        bounds.quad = TileQuad.addLineSegment(bounds.quad, prevX, prevY, x, y, zoom);
                    }
                    prevX = x;
                    prevY = y;
                }
                prevNodeId = nodeId;
            }
            if(bounds.quad == 0)
            {
                Log.error("Could not calculate bounds of way/%d", getId(ways, pWay));
            }
            storeBounds(pBounds, bounds);
        }

        /**
         * A class that encodes Foreign Feature records for a foreign tile,
         * which are later appended to the tile's pile.
         */
        private class Encoder extends PbfOutputStream
        {
            private long prevId;
            private int prevX;		// TODO: base off tile's minX?
            private int prevY;		// TODO: base off tile's minY?

            /**
             * Writes the ID and coordinates of a node to this Encoder, and also
             * includes a flag that indicates whether the node is a feature.
             * Starts a ForeignFeatures block (for nodes) if needed.
             *
             * @param pNode     pointer to the node's entry in `nodes`
             */
            public void writeForeignNode(int pNode)
            {
                if(prevId==0)
                {
                    write(FOREIGN_FEATURES | (NODES << 3));
                    writeVarint(sourcePile);
                }
                int flags = nodes.get(pNode);
                long id = getId(nodes, pNode);
                assert id != prevId;
                int x = nodes.get(pNode+N_X);
                int y = nodes.get(pNode+N_Y);

                // A node is a feature if it has tags or is a member of
                // a relation

                int featureFlag = (flags >>> NODE_IS_FEATURE_BIT) & 1;
                writeVarint(((id - prevId) << 1) | featureFlag);
                writeSignedVarint(x - prevX);
                writeSignedVarint(y - prevY);
                prevId = id;
                prevX = x;
                prevY = y;
            }

            /**
             * Writes a proxy for way or relation to this Encoder: the feature's
             * ID, sparse quad (encoded as a sparse sibling locator) and bbox.
             *
             * Starts a ForeignFeatures block (for the given type) if needed.
             *
             * TODO: take a pointer to tilesAndBounds instead?
             *
             * @param type      WAYS or RELATIONS
             * @param id        the feature's ID
             * @param quad      sparse quad
             * @param x1
             * @param y1
             * @param x2
             * @param y2
             */
            public void writeForeignFeature(int type, long id, int quad, int x1, int y1, int x2, int y2)
            {
                assert type == WAYS || type == RELATIONS;
                if(prevId==0)
                {
                    write(FOREIGN_FEATURES | (type << 3));
                    writeVarint(sourcePile);
                }
                int tileCount = TileQuad.tileCount(quad);
                assert tileCount > 0;
                if(tileCount > 1)
                {
                    // multi-tile feature
                    writeVarint(((id - prevId) << 1) | 1);
                    if(TileQuad.zoom(quad) != TileQuad.zoom(sourceTile))
                    {
                        Log.error("Quad at wrong zoom level for %s/%d",
                            type==WAYS ? "way" : "relation", id);
                        // TODO: should we fix zoom level here,
                        //  or in the calculate...() methods?
                    }
                    write(TileQuad.toSparseSiblingLocator(quad, sourceTile));
                }
                else
                {
                    writeVarint((id - prevId) << 1);
                }
                writeSignedVarint(x1 - prevX);
                writeSignedVarint(y1 - prevY);
                writeVarint(x2 - x1);
                writeVarint(y2 - y1);
                    // careful, always check signed vs. unsigned
                    // x1/y1 are signed, x2/y2 unsigned (because they are always greater)
                    // TODO: except for empty relations!
                prevId = id;
                prevX = x1;
                prevY = y1;
            }

            /**
             * If the Encoder has an open group, closes it, otherwise does nothing.
             */
            public void endGroup()
            {
                if(prevId != 0)
                {
                    write(0);
                    prevId = 0;
                    prevX = 0;			// TODO: base off source tile bounds?
                    prevY = 0;			// TODO: base off source tile bounds?
                }
            }

            /**
             * Generates geodesk:duplicate or geodesk:orphan tags (or both) for
             * a previously untagged node.
             *
             * @param pNode                 pointer to the node
             * @param tagNodeAsDuplicate
             * @param tagNodeAsOrphan
             */
            public void writeProblemNode(int pNode, boolean tagNodeAsDuplicate, boolean tagNodeAsOrphan)
            {
                assert (nodes.get(pNode) & NODE_HAS_TAGS) == 0:
                    "Node must not have been previously tagged";

                if(prevId==0)
                {
                    write(LOCAL_FEATURES | (NODES << 3));
                    writeVarint(sourcePile);
                }

                long id = getId(nodes, pNode);
                assert id != prevId;
                int x = nodes.get(pNode+N_X);
                int y = nodes.get(pNode+N_Y);

                writeVarint(((id - prevId) << 1) | 1);  // tagged
                writeSignedVarint(x - prevX);
                writeSignedVarint(y - prevY);
                prevId = id;
                prevX = x;
                prevY = y;

                int tagsSize = 1;
                int tagCount = 0;
                if(tagNodeAsDuplicate)
                {
                    tagsSize += KEY_DUPLICATE.length;
                    tagsSize += VALUE_YES.length;
                    tagCount++;
                }
                if(tagNodeAsOrphan)
                {
                    tagsSize += KEY_ORPHAN.length;
                    tagsSize += VALUE_YES.length;
                    tagCount++;
                }
                writeVarint(tagsSize);
                assert tagCount < 128;
                write(tagCount);        // one-byte tag count
                if(tagNodeAsDuplicate)
                {
                    writeBytes(KEY_DUPLICATE);
                    writeBytes(VALUE_YES);
                }
                if(tagNodeAsOrphan)
                {
                    writeBytes(KEY_ORPHAN);
                    writeBytes(VALUE_YES);
                }
            }
        }

        /**
         * Returns an Encoder for the given tile, creating one if necessary.
         *
         * @param targetTile    tile number
         * @return              the Encoder
         */
        private Encoder getEncoder(int targetTile)
        {
            // assert targetTile > 0;		// TODO: ok in theory, root tile 0/0/0
            Encoder encoder = encoders.get(targetTile);
            if(encoder == null)
            {
                encoder = new Encoder();
                encoders.put(targetTile, encoder);
            }
            return encoder;
        }

        /**
         * Closes the open groups of all Encoders.
         */
        private void endEncoderGroups()
        {
            encoders.forEach(encoder -> encoder.endGroup());
        }

        private long getId(IntList features, int p)
        {
            return (((long)(features.get(p) & 0x00ff_ffff)) << 32) |
                ((long)features.get(p+1) & 0xffff_ffffl);
            // Careful, Bit 31 of lower word carries into sign, that's why
            // we need to convert to long first and mask it off the top 32 bits
        }

        /**
         * Writes the quad/bbox of a way or relation into all foreign tiles
         * where this information is needed.
         *
         * @param type      WAYS or RELATIONS
         * @param id        the feature's ID
         * @param pBounds   pointer to the feature's quad/bbox/tilePtr record
         *                  in `tilesAndBounds`
         */
        private void writeForeignFeatures(int type, long id, int pBounds)
        {
            assert type==WAYS || type==RELATIONS;

            int pTile = tilesAndBounds.get(pBounds + B_TILE_PTR);
            while(pTile != 0)
            {
                int targetTile = tilesAndBounds.get(pTile);

                Encoder encoder = getEncoder(targetTile);
                encoder.writeForeignFeature(type, id,
                    tilesAndBounds.get(pBounds),
                    tilesAndBounds.get(pBounds + 1),
                    tilesAndBounds.get(pBounds + 2),
                    tilesAndBounds.get(pBounds + 3),
                    tilesAndBounds.get(pBounds + 4));

                pTile = tilesAndBounds.get(pTile+1);
            }
        }

        private void writeForeignRelations()
        {
            MutableIntList cyclical = null;
            int pRelation = 1;
            while (pRelation < relations.size())
            {
                int pBounds = relations.get(pRelation + F_BOUNDS_PTR);
                if (pBounds != 0)
                {
                    long id = getId(relations, pRelation);
                    boolean resolved = relations.get(pRelation + F_DATA_PTR) < 0;
                    if(!resolved)
                    {
                        resolved = calculateRelationBounds(pRelation, pBounds);
                    }
                    if(resolved)
                    {
                        writeForeignFeatures(RELATIONS, id, pBounds);
                    }
                    else
                    {
                        if(cyclical == null) cyclical = new IntArrayList();
                        cyclical.add(pRelation);
                    }
                }
                pRelation += F_LENGTH;
            }

            if(cyclical != null)
            {
                for (; ; )
                {
                    boolean changes = false;
                    for (int i = 0; i < cyclical.size(); i++)
                    {
                        pRelation = cyclical.get(i);
                        int pBounds = relations.get(pRelation + F_BOUNDS_PTR);
                        int prevQuad = getQuad(pBounds);
                        long prevArea = getArea(pBounds);
                        calculateRelationBounds(pRelation, pBounds);
                        if (getQuad(pBounds) != prevQuad || getArea(pBounds) != prevArea)
                        {
                            changes = true;
                        }
                    }
                    if (!changes) break;
                }
                for (int i = 0; i < cyclical.size(); i++)
                {
                    pRelation = cyclical.get(i);
                    long id = getId(relations, pRelation);
                    int pBounds = relations.get(pRelation + F_BOUNDS_PTR);
                    writeForeignFeatures(RELATIONS, id, pBounds);
                }
            }
            endEncoderGroups();
        }

        private void writeForeignWays()
        {
            int pWay = 1;
            while (pWay < ways.size())
            {
		        int pBounds = ways.get(pWay+F_BOUNDS_PTR);
                if(pBounds != 0)
                {
                    long id = getId(ways, pWay);
		            int quad = tilesAndBounds.get(pBounds);
                    if(quad == 0) calculateWayBounds(pWay, pBounds);
                    writeForeignFeatures(WAYS, id, pBounds);
                }
                pWay += F_LENGTH;
            }
            endEncoderGroups();
        }

        private void writeForeignNodes()
        {
            int pNode = 1;
            while (pNode < nodes.size())
            {
                int pTile = nodes.get(pNode+N_TILE_PTR);
                while(pTile != 0)
                {
                    int targetTile = tilesAndBounds.get(pTile);
                    getEncoder(targetTile).writeForeignNode(pNode);
                    pTile = tilesAndBounds.get(pTile+1);
                }
                pNode = pNode + N_LENGTH;
            }
            endEncoderGroups();
        }

        private long nodeXY(int pNode)
        {
            return XY.of(nodes.get(pNode + N_X), nodes.get(pNode + N_Y));
        }

        private void tagProblemNodes()
        {
            if(!tagDuplicateNodes && !tagOrphanNodes) return;

            Encoder encoder = getEncoder(sourceTile);

            boolean duplicateCoords = false;
            MutableLongIntMap coordsCounts = null;

            if(tagDuplicateNodes)
            {
                coordsCounts = new LongIntHashMap();
                for (int pNode = 1; pNode < nodes.size(); pNode += N_LENGTH)
                {
                    long xy = nodeXY(pNode);
                    if (coordsCounts.addToValue(xy, 1) > 1) duplicateCoords = true;
                }
            }

            for (int pNode = 1; pNode < nodes.size(); pNode += N_LENGTH)
            {
                boolean tagNode = false;
                boolean tagNodeAsDuplicate = false;
                boolean tagNodeAsOrphan = false;
                int flags = nodes.get(pNode);
                if((flags & (NODE_HAS_TAGS | NODE_IN_RELATION | NODE_USED_IN_WAY)) == 0)
                {
                    tagNode = tagNodeAsOrphan = tagOrphanNodes;
                }
                if(duplicateCoords)
                {
                    if((flags & NODE_HAS_TAGS) == 0)
                    {
                        if(coordsCounts.get(nodeXY(pNode)) > 1)
                        {
                            tagNodeAsDuplicate = true;
                            tagNode = true;
                        }
                    }
                }
                if(tagNode)
                {
                    encoder.writeProblemNode(pNode,
                        tagNodeAsDuplicate, tagNodeAsOrphan);
                    markNode(pNode, NODE_IS_FEATURE);
                }
            }
            encoder.endGroup();
        }

        public Boolean call()
        {
            byte[] data = loadTileData(sourcePile);
            if(data.length==0) return Boolean.FALSE;
            sourceData = new PbfBuffer(data);
            init();
            readTile();
            writeForeignRelations();
            writeForeignWays();
            writeForeignNodes();
            flushToPiles(encoders);
            clear();

            // It is important to free all data, because the main thread keeps a list
            // of Task objects that are kept alive until all of them finished

            reporter.progress(1);

            return Boolean.TRUE;
        }
    }

    /**
     * Writes the contents of the provided encoders into the database.
     *
     * @param encoders	a map of tile numbers to Encoders
     */
    private synchronized void flushToPiles(IntObjectMap<Task.Encoder> encoders)
    {
        encoders.forEachKeyValue((tile, encoder) ->
        {
            int pile = tileCatalog.resolvePileOfTile(tile);
            assert pile > 0: String.format("Failed to resolve tile %s", Tile.toString(tile));
            try
            {
                pileFile.append(pile, encoder.buffer(), 0,  encoder.size());
            }
            catch(IOException ex)
            {
                // TODO
                System.out.println(ex);
                ex.printStackTrace();
            }
        });
    }

    // TODO: turn exception into exit code

    /*
    public static void main(String[] args) throws Throwable
    {
        Path workPath = Path.of(args[0]);
        Map<String,Object> settings = readSettings(workPath.resolve(SETTINGS_FILE));
        TileCatalog tileCatalog = new TileCatalog(workPath.resolve("tile-catalog.txt"));
        int verbosity = 0; // TODO
        PileFile pileFile = PileFile.openExisiting(workPath.resolve("features.bin"));
        Validator v = new Validator(tileCatalog, pileFile, verbosity);
        v.validate();
        pileFile.close();
    }
     */
}
