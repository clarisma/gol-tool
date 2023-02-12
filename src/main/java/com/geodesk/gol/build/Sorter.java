/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.index.DenseInt16Index;
import com.clarisma.common.index.DensePackedIntIndex;
import com.clarisma.common.index.IntIndex;
import com.clarisma.common.io.PileFile;
import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.text.Format;
import com.clarisma.common.util.Log;
import com.geodesk.feature.FeatureId;
import com.geodesk.core.Mercator;
import com.geodesk.core.Tile;
import com.geodesk.core.TileQuad;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.Tags;
import com.geodesk.io.osm.Members;
import com.geodesk.io.osm.Nodes;
import com.geodesk.io.osm.OsmPbfReader;
import com.clarisma.common.io.MappedFile;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Possible errors
// - Missing feature

// TODO: if node coordinates are 0/0 ("Null Island"), reject the node
//  --> No, "null island" is a real OSM node (a buoy)
//  Possible compromise: a way may not contain 0/0 coordinates
//  Or: use another value that signifies "missing node"

// TODO: If a way has a missing node that is also a relation member,
//  we would need to write a ghost way to the Purgatory, so that
//  the Purgatory can export the node for the Linker.
//  (In reality, ways with missing nodes don't really happen)

public class Sorter extends OsmPbfReader
{
    private final int verbosity;
    private final Path workPath;
    private final PileFile pileFile;
    private final IntIndex nodeIndex;
    private final IntIndex wayIndex;
    private final IntIndex relationIndex;
    private List<RelationData> superRelations = new ArrayList<>();
    private final List<RelationData> emptyRelations = new ArrayList<>();
    private final ObjectIntMap<String> keyStrings;
    private final ObjectIntMap<String> valueStrings;
    private final ObjectIntMap<String> roleStrings;
    private final TileCatalog tileCatalog;
    private long totalNodeCount;
    private long totalWayCount;
    private long totalRelationCount;
    private long totalBytesProcessed;
    private final int batchSize = 8192; // TODO: configurable

    private static final int DEFAULT_SORT_DB_PAGE_SIZE = 1 << 16; // TODO: configurable

    public Sorter(Path workPath, int verbosity, TileCatalog tileCatalog, PileFile pileFile) throws IOException
    {
        this.workPath = workPath;
        this.verbosity = verbosity;
        this.tileCatalog = tileCatalog;
        this.pileFile = pileFile;
        nodeIndex = createIndex("nodes.idx", 0);
        wayIndex = createIndex("ways.idx", 2);
        relationIndex = createIndex("relations.idx", 2);
        keyStrings = loadStringMap(workPath.resolve("keys.txt"));
        valueStrings = loadStringMap(workPath.resolve("values.txt"));
        roleStrings = loadStringMap(workPath.resolve("roles.txt"));
    }

    private IntIndex createIndex(String fileName, int extraBits) throws IOException
    {
        int tileCount = tileCatalog.tileCount();
        int bits = 32 - Integer.numberOfLeadingZeros(tileCount) + extraBits;
        Path path = workPath.resolve(fileName);
        Files.deleteIfExists(path);
        return bits == 16 ? new DenseInt16Index(path) : new DensePackedIntIndex(path, bits);
    }

    private ObjectIntMap<String> loadStringMap(Path path) throws IOException
    {
        List<String> strings = Files.readAllLines(path);
        MutableObjectIntMap<String> map = ObjectIntMaps.mutable.empty();
        for(int i=0; i<strings.size(); i++)
        {
            map.put(strings.get(i), i);
        }
        return map;
    }

    @Override protected WorkerThread createWorker()
    {
        return new ImportThread();
    }

    protected void reportProgress()
    {
        double percentageCompleted = (double)(totalBytesProcessed * 100) / fileSize();

        System.out.format(Locale.US,
            "Sorting... %3d%%: %,d nodes / %,d ways / %,d relations\r",
            (int)percentageCompleted, totalNodeCount, totalWayCount,
            totalRelationCount);
    }

    protected void reportCompleted()
    {
        System.out.format(Locale.US,
            "Sorted %,d nodes / %,d ways / %,d relations in %s\n",
            totalNodeCount, totalWayCount, totalRelationCount,
            Format.formatTimespan(timeElapsed()));
    }

    private static final byte START_NODE_GROUP = 1;
    private static final byte START_WAY_GROUP = 9;
    private static final byte START_RELATION_GROUP = 17;
    private static final byte END_GROUP = 0;

    // TODO: decide when to reset body encoder
    private static class GroupEncoder extends PbfOutputStream
    {
        final int pile;
        long prevId;
        int prevX;
        int prevY;

        public GroupEncoder(int pile, int startMarker)
        {
            this.pile = pile;
            write(startMarker);
        }

        public int pile()
        {
            return pile;
        }

        private void writeBody(PbfOutputStream body)
        {
            writeString(body.buffer(), 0, body.size());
        }

        public void writeEnd()
        {
            write(END_GROUP);
        }
    }

    private static int getPile(IntIndex index, long id)
    {
        try
        {
            return index.get(id);
        }
        catch (IOException ex)
        {
            // TODO
            return 0;
        }
    }

    public int getPileQuad(FeatureType type, long id)
    {
        switch(type)
        {
        case NODE:
            // since nodes only live in a single tile, we need to turn the pile number
            // into a quad by adding bottom 2 bits (always 0)
            return getPile(nodeIndex, id) << 2;
        case WAY:
            return getPile(wayIndex, id);
        case RELATION:
            return getPile(relationIndex, id);
        default:
            assert false;
            return 0;
        }
    }

    private static class RelationData
    {
        long id;
        int tileQuad;
        long[] members;
        int[] memberTiles;
        byte[] body;
        boolean resolved;

        public RelationData(long id)
        {
            this.id = id;
        }
        public boolean isEmptyRelation()
        {
            return members.length == 0;
        }
    }

    private class Batch implements Runnable
    {
        IntIndex index;
        long[] indexedIds;
        int[] indexedPiles;
        int indexedFeatureCount;
        MutableIntObjectMap<GroupEncoder> encoders;

        Batch(IntIndex index)
        {
            this.index = index;
            indexedIds = new long[batchSize];
            indexedPiles = new int[batchSize];
            encoders = new IntObjectHashMap<>();
        }

        void setIndex(IntIndex index)
        {
            assert indexedFeatureCount == 0;
            this.index = index;
        }

        GroupEncoder getEncoder(int pile, int startMarker)
        {
            assert pile > 0 && pile <= tileCatalog.tileCount(): String.format("Invalid pile: %d", pile);
            // TODO: assert that startMarker matches index
            GroupEncoder encoder = encoders.get(pile);
            if(encoder == null)
            {
                encoder = new GroupEncoder(pile, startMarker);
                encoders.put(pile, encoder);
            }
            return encoder;
        }

        void indexFeature(long id, int pile)
        {
            indexedIds[indexedFeatureCount] = id;
            indexedPiles[indexedFeatureCount] = pile;
            indexedFeatureCount++;
        }

        boolean isEmpty()
        {
            return indexedFeatureCount == 0;
        }

        boolean isFull()
        {
            return indexedFeatureCount == indexedIds.length;
        }

        @Override public void run()
        {
            try
            {
                for (GroupEncoder encoder : encoders.values())
                {
                    encoder.writeEnd();
                    pileFile.append(encoder.pile(), encoder.buffer(), 0, encoder.size());
                }
                for (int i = 0; i < indexedFeatureCount; i++)
                {
                    index.put(indexedIds[i], indexedPiles[i]);
                }
                // log(String.format("Batch %s: Indexed %d features", this, indexedFeatureCount));
            }
            catch (IOException ex)
            {
                fail(ex);
            }
        }
    }

    private class ImportThread extends WorkerThread
    {
        private final PbfOutputStream body = new PbfOutputStream();
        private final MutableLongList memberIds = new LongArrayList();
        private final MutableIntList memberTiles = new IntArrayList();
        private final List<String> tagsOrRoles = new ArrayList<>();
        private final List<RelationData> deferredRelations = new ArrayList<>();
        private Batch batch;
        private int nodeCount;
        private int wayCount;
        private int relationCount;

        public ImportThread ()
        {
            batch = new Batch(null);
        }

        private void encodePackedString(String val, ObjectIntMap<String> dictionary)
        {
            if(val == null)
            {
                body.write(0);
                return;
            }
            int entry = dictionary.getIfAbsent(val, -1);
            if(entry >= 0)
            {
                body.writeVarint((entry << 1) | 1);
            }
            else
            {
                try
                {
                    // TODO: clean string (remove CR/LF etc.)

                    byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
                    body.writeVarint(bytes.length << 1);
                    body.write(bytes);
                }
                catch (Exception ex)
                {
                    // TODO
                    throw new RuntimeException("Unable to write string: " + val, ex);
                }
            }
        }

        private void encodeTags(Tags tags)
        {
            assert tagsOrRoles.isEmpty();
            while(tags.next())
            {
                tagsOrRoles.add(tags.key());
                tagsOrRoles.add(tags.stringValue());
            }
            body.writeVarint(tagsOrRoles.size() / 2);
            for(int i=0; i<tagsOrRoles.size(); i+=2)
            {
                encodePackedString(tagsOrRoles.get(i), keyStrings);
                encodePackedString(tagsOrRoles.get(i+1), valueStrings);
            }
            tagsOrRoles.clear();
        }

        private void addIndexed(long id, int pile)
        {
            batch.indexFeature(id, pile);
            if(batch.isFull()) flush();
        }

        // protected void nodexxx(long id, int lon, int lat, Tags tags)
        @Override protected void node(long id, int lon, int lat, Tags tags)
        {
            int x = Mercator.xFromLon100nd(lon);
            int y = Mercator.yFromLat100nd(lat);
            int pile = tileCatalog.resolvePileOfXY(x, y);

            GroupEncoder encoder = batch.getEncoder(pile, START_NODE_GROUP);
            int tagsFlag = tags.isEmpty() ? 0 : 1;
            encoder.writeVarint(((id - encoder.prevId) << 1) | tagsFlag);
            encoder.writeSignedVarint(x - encoder.prevX);
            encoder.writeSignedVarint(y - encoder.prevY);
            if(tagsFlag != 0)
            {
                encodeTags(tags);
                encoder.writeBody(body);
                body.reset();
            }
            encoder.prevId = id;
            encoder.prevX = x;
            encoder.prevY = y;
            addIndexed(id, pile);
            nodeCount++;
        }

        private void encodeWayNodes()
        {
            body.writeVarint(memberIds.size() << 1);
            long prevNodeId = 0;
            for(int i = 0; i< memberIds.size(); i++)
            {
                long nodeId = memberIds.get(i);
                body.writeSignedVarint(nodeId-prevNodeId);
                prevNodeId = nodeId;
            }
        }

        /**
         * Encodes the node list of a ghost way. The pile of each encoded
         * node is set to 0.
         *
         * @param startIndex    the index of the first possible node
         * @param pile          the pile for which to include nodes
         */
        private void encodeGhostWayNodes(int startIndex, int pile)
        {
            int count = 0;
            for(int i = startIndex; i< memberTiles.size(); i++)
            {
                if(memberTiles.get(i) == pile) count++;
            }
            body.writeVarint((count << 1) | 1);
            long prevNodeId = 0;
            for(int i = startIndex; i< memberTiles.size(); i++)
            {
                if(memberTiles.get(i) == pile)
                {
                    long nodeId = memberIds.get(i);
                    body.writeSignedVarint(nodeId-prevNodeId);
                    prevNodeId = nodeId;
                    memberTiles.set(i, 0);
                }
            }
        }

        private void writeGhostWays(long id, int wayTQ)
        {
            int wayZoom = TileQuad.zoom(wayTQ);
            for(int i = 0; i< memberTiles.size(); i++)
            {
                // TODO: can we assume pile=0 -> tile = 0?
                int nodePile = memberTiles.get(i);
                if(nodePile == 0) continue;
                int nodeTile = tileCatalog.tileOfPile(nodePile);
                if(Tile.zoom(nodeTile) > wayZoom)
                {
                    GroupEncoder encoder = batch.getEncoder(nodePile, START_WAY_GROUP);
                    encoder.writeVarint(((id - encoder.prevId) << 1) | 1);
                    byte locator = TileQuad.toDenseParentLocator(wayTQ, nodeTile);
                    encoder.write(locator);
                    encodeGhostWayNodes(i, nodePile);
                    encoder.writeBody(body);
                    body.reset();
                    encoder.prevId = id;
                }
            }
        }

        private void writeWay(long id, int pile, byte locator)
        {
            GroupEncoder encoder = batch.getEncoder(pile, START_WAY_GROUP);
            if(locator == -1)
            {
                encoder.writeVarint((id - encoder.prevId) << 1);
            }
            else
            {
                encoder.writeVarint(((id - encoder.prevId) << 1) | 1);
                encoder.write(locator);
            }
            encoder.writeBody(body);
            encoder.prevId = id;
        }

        private void writeMultiTileWay(long id, int wayTileQuad)
        {
            TileQuad.forEach(wayTileQuad, tile ->
            {
                int pile = tileCatalog.resolvePileOfTile(tile);
                assert pile > 0: String.format("way/%d: Cannot resolve tile %s",
                    id, Tile.toString(tile));
                byte locator = TileQuad.toDenseParentLocator(wayTileQuad, tile);
                writeWay(id, pile, locator);
            });
        }

        // protected void wayxxx(long id, Tags tags, Nodes nodes)
        @Override protected void way(long id, Tags tags, Nodes nodes)
        {
            int wayTQ = 0;
            int prevNodePile = 0;
            int highestZoom = 0;
            int uniqueNodeTileCount = 0;
                // Note: uniqueNodeTileCount only tells me if a way has
                // zero, one or more tiles; it does not track the real
                // number of unique tiles (maybe should rename?)
            while(nodes.next())
            {
                long nodeId = nodes.id();
                int nodePile = getPile(nodeIndex, nodeId);
                if(nodePile != prevNodePile)
                {
                    uniqueNodeTileCount++;
                    int nodeTile = tileCatalog.tileOfPile(nodePile);
                    int nodeZoom = Tile.zoom(nodeTile);
                    if(nodeZoom > highestZoom) highestZoom = nodeZoom;
                    wayTQ = TileQuad.addTile(wayTQ, nodeTile);
                    prevNodePile = nodePile;
                }
                memberIds.add(nodeId);
                memberTiles.add(nodePile);
            }

            if(memberIds.size() < 2)
            {
                if(verbosity >= Verbosity.NORMAL)
                {
                    // Added extra spaces to overwrite progress message
                    // TODO: Find better way to address #23
                    Log.warn("Rejected way/%d because of invalid node count (%d)          ",
                        id, memberIds.size());
                }
                memberIds.clear();
                memberTiles.clear();
                // TODO: inc rejected feature count
                return;
            }

            encodeWayNodes();
            encodeTags(tags);

            if(uniqueNodeTileCount == 1)
            {
                // single-tile way
                writeWay(id, prevNodePile, (byte) -1);
                addIndexed(id, prevNodePile << 2);   // turn pile into pile quad
            }
            else if (uniqueNodeTileCount == 0)
            {
                // TODO: ERROR: All of the way's nodes are missing
                if(verbosity >= Verbosity.NORMAL)
                {
                    Log.warn("way/%d is missing all nodes", id);
                }
            }
            else
            {
                // Way has nodes in multiple tiles

                wayTQ = tileCatalog.validateTileQuad(wayTQ);

                writeMultiTileWay(id, wayTQ);
                body.reset();
                if(TileQuad.zoom(wayTQ) < highestZoom)
                {
                    writeGhostWays(id, wayTQ);
                }
                addIndexed(id, tileCatalog.pileQuadFromTileQuad(wayTQ));
            }
            body.reset();
            memberIds.clear();
            memberTiles.clear();
            wayCount++;
        }

        /**
         * Adds a relation's member IDs and their roles to `body`.
         *
         * - `memberIds` must contain the (typed) member IDs
         * - `tagsOrRoles` must contain the members' roles
         *
         * This method clears `tagsOrRoles`; it leaves `memberIds` unchanged.
         */
        private void encodeMembers()
        {
            assert memberIds.size() == tagsOrRoles.size();
            body.writeVarint(memberIds.size());
            for(int i = 0; i< memberIds.size(); i++)
            {
                body.writeVarint(memberIds.get(i));
                encodePackedString(tagsOrRoles.get(i), roleStrings);
            }
            tagsOrRoles.clear();
        }

        /**
         * Writes relation records to the pile(s) of a relation.
         *
         * - `body` must contain the encoded members/roles and the relation's tags
         *
         * @param id        the ID of the relation
         * @param relTQ     the tile quad of the relation
         */
        private void writeRelation(long id, int relTQ)
        {
            TileQuad.forEach(relTQ, tile ->
            {
                int pile = tileCatalog.resolvePileOfTile(tile);
                assert pile > 0: String.format("relation/%d: Cannot resolve tile %s",
                    id, Tile.toString(tile));
                GroupEncoder encoder = batch.getEncoder(pile, START_RELATION_GROUP);
                byte locator = TileQuad.toDenseParentLocator(relTQ, tile);
                encoder.writeVarint((id - encoder.prevId) << 1);
                encoder.write(locator);
                encoder.writeBody(body);
                encoder.prevId = id;
            });
        }

        private void deferRelation(long id, int relTQ)
        {
            RelationData rel = new RelationData(id);
            rel.tileQuad = relTQ;
            rel.body = body.toByteArray();
            rel.members = memberIds.toArray();
            rel.memberTiles = memberTiles.toArray();
            deferredRelations.add(rel);
        }

        /**
         * Writes membership records for all members that live at a zoom
         * level other than the relation's.
         *
         * - `memberIds` must contain the (typed) IDs of the relation's members
         * - `memberTiles` must contain the tile quads of the members
         *
         * @param relId     the ID of the relation
         * @param relTQ     the tile quad of the relation
         */
        private void writeMemberships(long relId, int relTQ)
        {
            int relZoom = TileQuad.zoom(relTQ);
            for (int i = 0; i < memberIds.size(); i++)
            {
                long memberId = memberIds.get(i);   // typed ID
                /*
                if(memberId == FeatureId.ofRelation(1212338))
                {
                    log.debug("Writing memberships for {} in relation/{}",
                        FeatureId.toString(memberId), relId);
                }
                 */
                int memberTQ = memberTiles.get(i);
                if(memberTQ == 0)
                {
                    // write membership to purgatory

                    /*
                    if(memberId == FeatureId.ofWay(711043332))
                    {
                        log.debug("Writing membership for {} into Purgatory...",
                            FeatureId.toString(memberId));
                    }
                     */

                    GroupEncoder encoder = batch.getEncoder(
                        TileCatalog.PURGATORY_PILE, START_RELATION_GROUP);
                    encoder.writeVarint(((relId - encoder.prevId) << 1) | 1);
                        // set bit 0 to 1 to distinguish relations from memberships
                    encoder.write(0xff);    // complex locator
                    encoder.writeFixed32(relTQ);
                    encoder.writeVarint(memberId);
                    encoder.prevId = relId;
                    continue;
                }
                int memberZoom = Tile.zoom(memberTQ);
                if (memberZoom == relZoom) continue;

                // The member lives at a higher zoom level

                TileQuad.forEach(memberTQ, tile ->
                {
                    // Write relationship to each of the member's tiles

                    /*
                    if(memberId == FeatureId.ofRelation(3067061) ||
                        memberId == FeatureId.ofRelation(1212338))
                    {
                        Log.debug("  Member %s (in tile %s) references parent relation/%d (in quad %s)",
                            FeatureId.toString(memberId), Tile.toString(tile),
                            relId, TileQuad.toString(relTQ));
                    }
                     */

                    int pile = tileCatalog.resolvePileOfTile(tile);
                    assert pile > 0;
                    GroupEncoder encoder = batch.getEncoder(pile, START_RELATION_GROUP);
                    byte locator = TileQuad.toDenseParentLocator(relTQ, tile);
                    encoder.writeVarint(((relId - encoder.prevId) << 1) | 1);
                    // set bit 0 to 1 to distinguish relations from memberships
                    // TODO: locator could be complex, refer to non-related
                    //  tile, 0xFF + tile quad (full 4 bytes)
                    encoder.write(locator);
                    encoder.writeVarint(memberId);
                    encoder.prevId = relId;
                });
            }
        }


        /**
         * Writes a relation and any memberships to one or more piles.
         *
         * - `memberIds` must contain the (typed) IDs of the relation's members
         * - `memberTiles` must contain the tile quads of the members
         *
         * @param id            the ID of the relation
         * @param relTQ         the (validated) tile quad of the relation
         * @param highestZoom   the highest zoom level at which members live
         *                      (used as a hint to avoid scanning `memberTiles`;
         *                      can also be set to the highest zoom level)
         */
        // TODO: instead of `highestZoom`, why not use a flag?
        private void addRelation(long id, int relTQ, int highestZoom)
        {

            // TODO: expecting memberIds and memberTiles filled feels hackish,
            //  because super-relation resolution must copy them in
            //  Better to use params?
            writeRelation(id, relTQ);
            addIndexed(id, tileCatalog.pileQuadFromTileQuad(relTQ));
            if (Tile.zoom(relTQ) < highestZoom)
            {
                writeMemberships(id, relTQ);
            }
        }

        @Override protected void relation(long id, Tags tags, Members members)
        {
            assert tagsOrRoles.isEmpty();
            boolean isSuperRelation = false;
            int relTQ = 0;
            int prevMemberPileQuad = 0;
            int prevMemberTQ = 0;
            int highestZoom = 0;
            while(members.next())
            {
                FeatureType memberType = members.type();
                long memberId = members.id();
                long typedMemberId = FeatureId.of(memberType, memberId);
                if (memberType == FeatureType.RELATION)
                {
                    if (memberId == id)
                    {
                        // log.warn("Removed self-reference in relation/{}", id);
                        continue; // ignore self-references
                    }
                    memberTiles.add(0);
                    isSuperRelation = true;
                }
                else
                {
                    int memberPileQuad = getPileQuad(memberType, memberId);
                    if(memberPileQuad == 0)
                    {
                        // TODO: missing member
                        /*
                        log.warn("{} is missing in relation/{}",
                            FeatureId.toString(typedMemberId), id);
                         */
                        // force addRelation to scan the list of memberTiles
                        //  so it can generate a Purgatory memberships for
                        //  the missing feature
                        // highestZoom is passed as a hint, since we won't
                        // need to write memberships if all members live at
                        // the same zoom level as the relation
                        highestZoom = Integer.MAX_VALUE;
                        prevMemberPileQuad = 0;
                        prevMemberTQ = 0;
                    }
                    else if(memberPileQuad != prevMemberPileQuad)
                    {
                        int memberTQ = tileCatalog.tileQuadFromPileQuad(memberPileQuad);
                        relTQ = TileQuad.addQuad(relTQ, memberTQ);
                        prevMemberPileQuad = memberPileQuad;
                        prevMemberTQ = memberTQ;
                        int memberZoom = TileQuad.zoom(memberTQ);
                        if(memberZoom > highestZoom) highestZoom = memberZoom;
                    }
                    memberTiles.add(prevMemberTQ);
                }
                memberIds.add(typedMemberId);
                tagsOrRoles.add(members.role());
            }

            // TODO: don't encode yet; only encode tags for deferred
            // NOOOOO!!!!
            // need to deal with roles, in tagsOrRoles at this point
            encodeMembers();
            encodeTags(tags);

            if(isSuperRelation || memberIds.isEmpty())
            {
                deferRelation(id, relTQ);
            }
            else if(relTQ == 0)
            {
                // TODO: relation is missing all members
                // log.warn("relation/{} is missing all members", id);
            }
            else
            {
                relTQ = tileCatalog.validateTileQuad(relTQ);
                addRelation(id, relTQ, highestZoom);

            }

            body.reset();
            memberIds.clear();
            memberTiles.clear();
            relationCount++;        // TODO: count only actual (non-deferred) relations?
        }

        private void addResolvedRelation(RelationData rel)
        {
            /*
            if(rel.id == 2070729 || rel.id == 1212338)
            {
                log.debug("    Adding resolved relation/{}", rel.id);
            }
             */
            
            rel.tileQuad = tileCatalog.validateTileQuad(rel.tileQuad);
            assert memberIds.isEmpty();
            assert memberTiles.isEmpty();
            memberIds.addAll(rel.members);
            memberTiles.addAll(rel.memberTiles);
            try
            {
                body.write(rel.body);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            addRelation(rel.id, rel.tileQuad, Integer.MAX_VALUE);
            memberIds.clear();
            memberTiles.clear();
            body.reset();
            rel.resolved = true;
        }

        @Override protected void postProcess()
        {
            synchronized (Sorter.this)
            {
                processDeferredRelations();
            }
        }

        private void processDeferredRelations()
        {
            // First, place all deferred relations (empty and super) into a map
            // and identify which relations reference empty relations
            // At the same time, we'll attempt to retrieve the pile quad for
            // all member relations. Since the vast majority of super-relations
            // only contain simple relations, we are able to resolve these now.
            // Any higher-level relations (and those in circular-reference
            // relationships) are deferred to the next phase.
            // We flush after each phase, to ensure that child relations are
            // always written before their parents (the Validator requires this)
            // For circular relations, the order does not matter, because all
            // participants live in the same tiles, so the Validator won't need
            // to generate proxies
            // However, that leaves the issue of a relation that references
            // a circular relation, but is not part of the reference cycle
            // itself. For this reason, we sort the last remaining batch
            // (circular relations and their parent relations) by zoom level
            // (higher to lower) to ensure the circular child relations are
            // written before their non-circular parents

            int deferredRelationCount = superRelations.size() + emptyRelations.size();
            if(deferredRelationCount == 0) return;

            if(verbosity >= Verbosity.DEBUG) Log.debug("Resolving complex relations...");

            // In case this thread has never processed relations before,
            // set the index for the batch
            batch.setIndex(relationIndex);

            int resolvedRelationCount = 0;
            List<RelationData> deferred = new ArrayList<>();
            MutableLongObjectMap<RelationData> relationMap =
                new LongObjectHashMap<>(deferredRelationCount + deferredRelationCount / 2);
            List<RelationData> emptyRelationRefs = new ArrayList<>();
            for(RelationData rel: emptyRelations) relationMap.put(rel.id, rel);
            for(RelationData rel: superRelations)
            {
                assert rel.members.length == rel.memberTiles.length;
                relationMap.put(rel.id, rel);
                boolean resolved = true;
                for (int i = 0; i < rel.members.length; i++)
                {
                    long typedMemberId = rel.members[i];
                    if (!FeatureId.isRelation(typedMemberId)) continue;
                    long memberId = FeatureId.id(typedMemberId);
                    RelationData memberRel = relationMap.get(memberId);
                    if(memberRel != null && memberRel.isEmptyRelation())
                    {
                        emptyRelationRefs.add(rel);
                        emptyRelationRefs.add(memberRel);
                        resolved = false;
                        continue;
                    }
                    int memberPileQuad = getPile(relationIndex, memberId);
                    if (memberPileQuad == 0)
                    {
                        resolved = false;
                        continue;
                    }
                    int memberTQ = tileCatalog.tileQuadFromPileQuad(memberPileQuad);
                    rel.memberTiles[i] = memberTQ;
                    rel.tileQuad = TileQuad.addQuad(rel.tileQuad, memberTQ);
                }
                if (resolved)
                {
                    addResolvedRelation(rel);
                    resolvedRelationCount++;
                }
                else
                {
                    deferred.add(rel);
                }
            }
            flush();

            if(verbosity >= Verbosity.DEBUG) Log.debug("Initial Pass: Resolved %d relations", resolvedRelationCount);

            /*
            log.debug("Resolved super-relations: {}", resolvedSuperRelations);
            log.debug("Total super-relation members: {}", totalMemberCount);
            log.debug("  Of these, {} are ways or nodes", nonRelationMemberCount);
            log.debug("Unresolved super-relations: {}", superRelations.size() - resolvedSuperRelations);
            log.debug("Unresolved empty relations: {}", emptyRelations.size());
            */

            // Now, we work through the remaining set of deferred super-relations
            // and determine their tiles. If all of a relation's child relations
            // have been marked as resolved, we can resolve the parent, as well.
            // Circular relations are never considered resolved. To avoid deferring
            // them forever, we continue only as long as the tile quad of at least
            // one relation has changed.

            boolean updatedAnyTiles;
            int passCount = 1;
            while(!deferred.isEmpty())
            {
                resolvedRelationCount = 0;
                superRelations = deferred;
                deferred = new ArrayList<>();
                updatedAnyTiles = false;
                for(RelationData rel: superRelations)
                {
                    int oldRelTQ = rel.tileQuad;
                    boolean resolved = true;
                    for (int i = 0; i < rel.members.length; i++)
                    {
                        long typedMemberId = rel.members[i];
                        if (!FeatureId.isRelation(typedMemberId)) continue;
                        long memberId = FeatureId.id(typedMemberId);
                        RelationData childRel = relationMap.get(memberId);
                        if(childRel == null)
                        {
                            // TODO: If memberTiles[i] == 0, the member is
                            //  missing -- add member to purgatory or remove
                            continue;
                        }
                        if(!childRel.resolved) resolved = false;
                        int memberTQ = childRel.tileQuad;
                        if(memberTQ == 0) continue;
                        rel.memberTiles[i] = memberTQ;
                        rel.tileQuad = TileQuad.addQuad(rel.tileQuad, memberTQ);
                    }
                    if(resolved)
                    {
                        addResolvedRelation(rel);
                        resolvedRelationCount++;
                    }
                    else
                    {
                        deferred.add(rel);
                    }
                    if(rel.tileQuad != oldRelTQ) updatedAnyTiles = true;
                }
                flush();
                if(verbosity >= Verbosity.DEBUG) Log.debug("Pass %d: Resolved %d relations", passCount, resolvedRelationCount);
                if(!updatedAnyTiles) break;
                passCount++;
            }

            // Process any remaining relations
            // TODO: relations with all members missing

            resolvedRelationCount = 0;

            // We sort the remaining relations (relations in a reference cycle,
            // and their non-circular parents) by zoom level (highest to lowest)

            // Before we sort the relations, we need to validate their tile quad,
            // because this may move it down to a lower zoom level
            // TODO: this is duplicated in addResolvedRelation

            for(RelationData rel: deferred)
            {
                rel.tileQuad = tileCatalog.validateTileQuad(rel.tileQuad);

                // 2/11/22: fixed bug caused by member relations that are
                //  in a reference cycle having tile quads that are at a
                //  zoom level for which tiles are not generated; push them
                //  down to a lower level if needed (e.g. from 11 to 9)
                // TODO: clarify when validation should happen

                for(int i=0; i<rel.memberTiles.length; i++)
                {
                    rel.memberTiles[i] = tileCatalog.validateTileQuad(
                        rel.memberTiles[i]);
                }
            }
            deferred.sort((a,b) -> Integer.compare(TileQuad.zoom(b.tileQuad),
                TileQuad.zoom(a.tileQuad)));

            for(RelationData rel: deferred)
            {
                if(verbosity >= Verbosity.VERBOSE)
                {
                    Log.warn(
                        "  relation/%d (%s -- should be sorted) references circular rels",
                        rel.id, TileQuad.toString(rel.tileQuad));
                }
                addResolvedRelation(rel);
                resolvedRelationCount++;
            }
            flush();
            if(verbosity >= Verbosity.DEBUG)
            {
                Log.debug("Final pass: Resolved %d remaining relations", resolvedRelationCount);
            }

            // Resolve the tiles of empty relations. For regular relations, the
            // tiles of the members determine the relation's tile(s). For
            // empty relations, we reverse this: They are placed in the tile
            // quad that is a superset of its parents.
            // TODO: what if a parent is sparse? Is the compiler able to avoid
            //  placing an (unneeded) copy of the empty relation into the
            //  "hole" of the quad?

            for(int i=0; i<emptyRelationRefs.size(); i+=2)
            {
                RelationData referer = emptyRelationRefs.get(i);
                RelationData emptyRel = emptyRelationRefs.get(i+1);
                if(referer.tileQuad > 0)
                {
                    emptyRel.tileQuad = TileQuad.addQuad(emptyRel.tileQuad, referer.tileQuad);
                }
            }

            // Finally, write the empty relations. We skip any empty relations
            // that are not referenced by another relation.

            int rejectedEmptyRelationCount = 0;

            for(RelationData emptyRel: emptyRelations)
            {
                if(emptyRel.tileQuad != 0)
                {
                    addResolvedRelation(emptyRel);
                }
                else
                {
                    rejectedEmptyRelationCount++;
                }
            }
            flush();
            superRelations.clear();
            emptyRelations.clear();

            if(verbosity >= Verbosity.VERBOSE)
            {
                Log.warn("Rejected %d unreferenced empty relations", rejectedEmptyRelationCount);
            }
        }

        @Override protected void beginNodes()
        {
            assert batch.isEmpty();
            batch.setIndex(nodeIndex);
        }

        @Override protected void beginWays()
        {
            assert batch.isEmpty();
            batch.setIndex(wayIndex);
        }

        @Override protected void beginRelations()
        {
            assert batch.isEmpty();
            batch.setIndex(relationIndex);
        }

        @Override protected void endNodes()
        {
            flush();
        }

        @Override protected void endWays()
        {
            flush();
        }

        @Override protected void endRelations()
        {
            flush();
            synchronized (Sorter.this)
            {
                for (RelationData rel : deferredRelations)
                {
                    if (rel.memberTiles.length == 0)
                    {
                        emptyRelations.add(rel);
                    }
                    else
                    {
                        superRelations.add(rel);
                    }
                }
            }
            deferredRelations.clear();
        }

        private void flush()
        {
            Batch nextBatch = new Batch(batch.index);
            try
            {
                Path indexPath = ((MappedFile)batch.index).path();
                // log(String.format("Flushing batch %s to %s", batch, indexPath.getFileName()));
                output(batch);
            }
            catch (InterruptedException ex)
            {
                // TODO
            }
            batch = nextBatch;
        }

        // TODO: do at end (maybe in endRelations, or postProcess)
        @Override protected void endBlock(Block block)
        {
            // flush(currentPhase());
            synchronized(Sorter.this)
            {
                totalNodeCount += nodeCount;
                totalWayCount += wayCount;
                totalRelationCount += relationCount;
                totalBytesProcessed += block.length;
                if(verbosity >= Verbosity.NORMAL) reportProgress();
            }
            nodeCount = 0;
            wayCount = 0;
            relationCount = 0;
        }
    }

    @Override protected void endFile()
    {
        if(verbosity >= Verbosity.QUIET) reportCompleted();
    }

    public void sortFeatures (File file) throws IOException
    {
        /*
        if(verbosity >= Verbosity.VERBOSE)
        {
            System.err.format("Sorting %s...\n", file);
        }
         */
        read(file);

        // TODO: clean this up (avoid ugly cast; we only use MappedFile-based index):
        ((MappedFile)nodeIndex).close();
        ((MappedFile)wayIndex).close();
        ((MappedFile)relationIndex).close();
    }

    /*
    public static void main(String[] args) throws Exception
    {
        // Path path = Paths.get("c:\\geodesk\\ftest");
        // Path path = Paths.get("/home/md/geodesk/f4");
        Path path = Paths.get("c:\\geodesk\\de4");
        // Path path = Paths.get("c:\\geodesk\\sa1");
        Importer importer = new Importer(path, new TileCatalog(path.resolve("tile-catalog.txt")));
        // importer.importFeatures("c:\\geodesk\\mapdata\\planet.osm.pbf");
        // importer.importFeatures("/home/md/geodesk/mapdata/planet.osm.pbf");
        importer.importFeatures("c:\\geodesk\\mapdata\\de-2021-01-29.osm.pbf");
        // importer.importFeatures("c:\\geodesk\\mapdata\\south-america-2021-02-02.osm.pbf");
    }
     */

    // TODO: turn exception into exit code

    public static void main(String[] args) throws Exception
    {
        Path workPath = Path.of(args[0]);

        // TODO
        // Map<String,Object> settings = readSettings(workPath.resolve(SETTINGS_FILE));
        TileCatalog tileCatalog = new TileCatalog(workPath.resolve("tile-catalog.txt"));
        int pageSize = DEFAULT_SORT_DB_PAGE_SIZE; // TODO
        int verbosity = 0; // TODO
        PileFile pileFile = PileFile.create(workPath.resolve("features.bin"),
            tileCatalog.tileCount(), pageSize);
        Sorter sorter = new Sorter(workPath, verbosity, tileCatalog, pileFile);
        // Path sourcePath = ((Path)settings.get("source"));    // TODO
        Path sourcePath = Path.of("c:\\geodesk\\mapdata\\de-2021-01-29.osm.pbf");
        sorter.sortFeatures(sourcePath.toFile());
        pileFile.close();
    }

}