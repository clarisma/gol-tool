/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.soar.Archive;
import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.feature.FeatureId;
import com.geodesk.geom.Tile;
import com.geodesk.geom.TileQuad;
import com.geodesk.geom.XY;
import com.geodesk.feature.FeatureType;
import com.geodesk.geom.Box;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.build.Project;
import com.geodesk.gol.build.TileCatalog;
import org.eclipse.collections.api.map.primitive.*;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.*;

// TODO: split this class
//  - This class should only serve as a container for the data structures
//    needed for a Tile.
//  - The export mechanism for the Linker is messy and cuts across several classes
//  - Writing waynode indexes requires lists of lineal ways and area ways,
//    which are temporary (created in build() method), but ideally we should
//    let the Compiler class write these indexes
//    - Idea: let FeatureTile create the encoded indexes; Compiler zips and writes them
//      - but memory consumption would be lower if we create indexes, write them,
//        then free the memory

// TODO: Call it TileInventory?
//  But we also have TileCatalog
//  or make it an STile?
//  or derive from Archive?

// TODO: Put exports into a separate class; pass this class to SFeature.export()

public class FeatureTile
{
    private final int tile;
    private final TileCatalog tileCatalog;
    private final Project project;
    private final ObjectIntMap<String> globalStrings;
    private final Box tileBounds;
    private final MutableLongLongMap coordinates = new LongLongHashMap();
    private final MutableLongObjectMap<SNode> nodes = new LongObjectHashMap<>();
    private final MutableLongObjectMap<SWay> ways = new LongObjectHashMap<>();
    private final MutableLongObjectMap<SRelation> relations = new LongObjectHashMap<>();
    private final Map<String, SString> localStrings = new HashMap<>();
    private final Map<STagTable, STagTable> tagTables = new HashMap<>();
    private final Map<SRelationTable, SRelationTable> relationTables = new HashMap<>();
    private final MutableIntObjectMap<PbfOutputStream> exports = new IntObjectHashMap<>();
    private Archive archive;

    // public static final int PURGATORY_TILE = 0x0f00_0000;

    // TODO: move to Tip class?
    public static boolean isWideTipDelta(int tipDelta)
    {
        return (short)(tipDelta << 1) != (tipDelta << 1);
        // TODO: The above is more efficient, but there may be a sign bug??
        // return tipDelta < -16384 || tipDelta > 16383;
        // TIP delta is wide if it cannot be expressed in 15 bits
    }

    // TODO: incorporate this class into the parent class?
    private class SHeader extends Struct
    {
        SIndexTree nodeIndex;
        SIndexTree wayIndex;
        SIndexTree areaIndex;
        SIndexTree relationIndex;

        SHeader()
        {
            setSize(28); // excludes blob header
            setLocation(4);
        }

        public void writeTo(StructOutputStream out) throws IOException
        {
            // Don't write the header (Fix for #96)
            // out.writeInt(payloadSize);    // payload size (excluding first 4 bytes)
            out.writeInt(0);    // TODO
            SIndexTree.writeIndexPointer(out, nodeIndex);
            SIndexTree.writeIndexPointer(out, wayIndex);
            SIndexTree.writeIndexPointer(out, areaIndex);
            SIndexTree.writeIndexPointer(out, relationIndex);
            out.writeInt(0);    // TODO
            out.writeInt(0);    // TODO
        }

        public String toString()
        {
            return String.format("TILE %s", Tile.toString(tile));
        }
    }


    public FeatureTile(int tile, ObjectIntMap<String> globalStrings,
        TileCatalog tileCatalog, Project project)
    {
        this.tile = tile;
        this.globalStrings = globalStrings;
        this.tileCatalog = tileCatalog;
        this.project = project;
        if(tile != TileCatalog.PURGATORY_TILE)
        {
            tileBounds = Tile.bounds(tile);
        }
        else
        {
            tileBounds = Box.ofWorld();
        }
    }

    public int tile()
    {
        return tile;
    }

    public TileCatalog tileCatalog()
    {
        return tileCatalog;
    }

    public Bounds bounds()
    {
        return tileBounds;
    }

    // TODO: should this be the payload size?
    public int size()
    {
        assert archive != null: "Tile must be built first";
        return archive.size();
    }

    // TODO: naming
    public Archive structs()
    {
        return archive;
    }

    public IntObjectMap<PbfOutputStream> getExports()
    {
        return exports;
    }

    public long getCoordinates(long nodeId)
    {
        return coordinates.get(nodeId);
    }

    // TODO: establish uniform "string code" that expresses both global & local

    public int getGlobalString(String s)
    {
        return globalStrings.get(s);
    }

    public SString getLocalString(String s)
    {
        SString str = localStrings.get(s);
        if (str == null)
        {
            str = new SString(s);
            localStrings.put(s, str);
        }
        return str;
    }

    public STagTable getTags(String[] tags)
    {
        STagTable tagTable = new STagTable(tags, globalStrings, localStrings);
        STagTable existing = tagTables.get(tagTable);
        if (existing != null) return existing;
        tagTables.put(tagTable, tagTable);
        return tagTable;
    }

    public SRelationTable getRelationTable(SRelationTable rt)
    {
        SRelationTable existing = relationTables.get(rt);
        if (existing != null) return existing;
        relationTables.put(rt, rt);
        return rt;
    }

    public SNode peekNode(long id)
    {
        return nodes.get(id);
    }

    public SNode getNode(long id)
    {
        SNode node = nodes.get(id);
        if (node == null)
        {
            node = new SNode(id);
            nodes.put(id, node);
        }
        return node;
    }

    public SWay getWay(long id)
    {
        SWay way = ways.get(id);
        if (way == null)
        {
            way = new SWay(id);
            ways.put(id, way);
        }
        return way;
    }

    public SRelation getRelation(long id)
    {
        SRelation rel = relations.get(id);
        if (rel == null)
        {
            rel = new SRelation(id);
            relations.put(id, rel);
        }
        return rel;
    }

    public SFeature getFeature(long typedId)
    {
        FeatureType type = FeatureId.type(typedId);
        long id = FeatureId.id(typedId);
        switch (type)
        {
        case NODE:
            return getNode(id);
        case WAY:
            return getWay(id);
        case RELATION:
            return getRelation(id);
        default:
            assert false;
            return null;
        }
    }

    public void setCoordinates(long id, int x, int y)
    {
        coordinates.put(id, XY.of(x, y));
    }

    public SNode addNode(long id, String[] tags, int x, int y)
    {
        SNode node = getNode(id);
        assert !node.isLocal(): String.format("node/%d added more than once", id);
        node.setXY(x, y);
        node.setTags(getTags(tags));
        node.markAsLocal();
        return node;
    }

    public void addWay(long id, String[] tags, long[] nodeIds)
    {
        SWay way = getWay(id);
        assert !way.isLocal(): String.format("way/%d added more than once", id);
        way.setTags(getTags(tags));
        way.setNodeIds(nodeIds);
        way.markAsLocal();
    }

    // TODO: move to TagValues: MAX_COMMON_ROLE
    private static final int MAX_ROLE_KEY = (1 << 15) - 1;

    public void addRelation(long id, String[] tags, long[] memberIds, String[] roles)
    {
        SRelation rel = getRelation(id);
        assert !rel.isLocal(): String.format("relation/%d added more than once", id);
        rel.setTags(getTags(tags));
        assert memberIds.length == roles.length;
        SRelation.Member[] members = new SRelation.Member[memberIds.length];
        for (int i = 0; i < members.length; i++)
        {
            SRelation.Member m = new SRelation.Member();
            m.member = getFeature(memberIds[i]);

            // TODO: what signifies "no role" -- make consistent!
            m.role = roles[i];
            m.roleCode = globalStrings.getIfAbsent(m.role, -1);
            if (m.roleCode == -1 || m.roleCode > MAX_ROLE_KEY)
            {
                m.roleString = getLocalString(m.role);
            }
            members[i] = m;
        }
        rel.setMembers(members);
        rel.markAsLocal();
    }


    public void addForeignNode(int foreignTile, long id, int x, int y)
    {
        SNode node = getNode(id);
        assert node.isForeign();
        node.setXY(x, y);
        node.setTileQuad(TileQuad.fromSingleTile(foreignTile));
    }

    public void addForeignWay(int tileQuad, long id, long[] nodeIds)
    {
        SWay way = getWay(id);
        assert way.isForeign();
        way.setNodeIds(nodeIds);
        way.setTileQuad(tileQuad);
    }

    public SRelation addForeignRelation(int tileQuad, long id)
    {
        SRelation rel = getRelation(id);

        // TODO: re-enable this assert!
        assert rel.isForeign(): String.format("relation/%d must be foreign", id);
        rel.setTileQuad(tileQuad);
        return rel;
    }

    private void buildRelations()
    {
        List<SRelation> deferredRelations = new ArrayList<>();

        relations.forEach(rel ->
        {
            rel.build(this);
            if(!rel.isBuilt()) deferredRelations.add(rel);
        });

        // We keep attempting to resolve relations until
        // we reach a steady state

        // TODO: check if either quad or bbox changed??
        //  quad can change while bbox stays the same

        List<SRelation> unresolved = deferredRelations;
        while(!unresolved.isEmpty())
        {
            int changedCount = 0;
            List<SRelation> stillUnresolved = new ArrayList<>();
            for (SRelation rel: unresolved)
            {
                long prevArea = rel.areaCovered();
                rel.build(this);
                if(rel.areaCovered() != prevArea)
                {
                    changedCount++;
                }
                if(!rel.isBuilt()) stillUnresolved.add(rel);
            }
            if(changedCount==0) break;
            unresolved = stillUnresolved;
        }
        for(SRelation rel: unresolved) rel.resolve(this);
    }

    // TODO: rename to just "buildIndex" (index is not just "spatial"; we no
    //  longer have ID indexes, so no need to differentiate)
    private SIndexTree buildSpatialIndex(String id, List<SFeature> features)
    {
        return SIndexTree.build(id, features, tileBounds, project);
    }

    // TODO: not really an "index"
    private SIndexTree buildFlatIndex(String id, List<SFeature> features)
    {
        return SIndexTree.buildFlat(id, features);
    }

    private PbfOutputStream getExportList(int targetTile)
    {
        PbfOutputStream buf = exports.get(targetTile);
        if(buf == null)
        {
            buf = new PbfOutputStream();
            exports.put(targetTile, buf);
        }
        return buf;
    }

    private void addExport(PbfOutputStream buf, SFeature f)
    {
        long typedId = FeatureId.of(f.type(), f.id());

        buf.writeFixed64(typedId);
        buf.writeFixed32(f.anchorLocation());
            // always use anchor location, because this is where the
            // pointer points (i.e. not necessarily the start of the object)
    }

    void exportFeature(int targetTile, SFeature f)
    {
        addExport(getExportList(targetTile), f);
    }


    void exportNodes(int targetTile, SNode[] nodes)
    {
        PbfOutputStream buf = getExportList(targetTile);
        for(SNode node: nodes)
        {
            if (node.isLocal()) addExport(buf, node);
        }
    }

    private static final String[] MISSING_TAG_STRINGS = { "geodesk:missing", "yes" };

    private <T extends SFeature> void convertMissing(MutableLongObjectMap<T> features, STagTable tags)
    {
        features.forEach(f ->
        {
            if (f.isMissing())
            {
                f.markAsLocal();
                f.setTags(tags);
            }
        });
    }

    public PbfOutputStream createWayNodeIndex()
    {
        List<SWay> wayList = new ArrayList<>(ways.size());
        ways.forEach(way ->
        {
            if(way.isLocal()) wayList.add(way);
        });
        Collections.sort(wayList);

        PbfOutputStream out = new PbfOutputStream();
        long prevId = 0;
        for(SWay way: wayList)
        {
            long id = way.id();
            out.writeSignedVarint(id - prevId);
                // we could use unsigned delta, but this would require that
                // we always sort the IDs to get efficient compression

            assert way.nodeIds() != null:
                "way/%d in %s has no nodeIds".formatted(id, Tile.toString(tile));
            way.writeNodes(out);
            prevId = id;
        }
        return out;
    }

    public void build()
    {
        List<SFeature> nodeList = new ArrayList<>();
        List<SFeature> wayList = new ArrayList<>();
        List<SFeature> areaList = new ArrayList<>();
        List<SFeature> relationList = new ArrayList<>();

        if(tile != TileCatalog.PURGATORY_TILE)
        {
            relations.forEach(SRelation::addToMembers);

            nodes.forEach(node ->
            {
                node.build(this);
                if (!node.isForeign())
                {
                    nodeList.add(node);
                }
            });

            ways.forEach(way ->
            {
                // While building the way, it may turn foreign

                way.build(this);
                if (!way.isForeign())
                {
                    (way.isArea() ? areaList : wayList).add(way);
                }
            });

            buildRelations();
            relations.forEach(rel ->
            {
                if (!rel.isForeign())
                {
                    (rel.isArea() ? areaList : relationList).add(rel);
                }
            });
        }
        else
        {
            STagTable missingTags = getTags(MISSING_TAG_STRINGS);
            convertMissing(nodes, missingTags);
            convertMissing(ways, missingTags);
            convertMissing(relations, missingTags);

            // we must convert missing features to local features first,
            // because addToMembers won't add a relation to a foreign feature

            relations.forEach(SRelation::addToMembers);

            // In Purgatory tile, only relations can be foreign; nodes and
            // ways by definition must be local

            nodes.forEach(node ->
            {
                assert node.isLocal();
                node.buildInvalid(this);
                nodeList.add(node);
            });
            ways.forEach(way ->
            {
                assert way.isLocal();
                way.buildInvalid(this);
                wayList.add(way);
            });
            relations.forEach(rel ->
            {
                if(rel.isLocal())
                {
                    rel.buildInvalid(this);
                    relationList.add(rel);
                }
            });
        }

        for(SRelationTable rt: relationTables.values()) rt.build(this);

        // Calculate scores for nodes last, as they depend on bonuses
        // awarded based on whether they belong to a way
        // TODO: should we instead iterate through the lists as we build the indexes?
        //  We're only interested in features that are local
        //   but won't work for Purgatory
        relations.forEach(f -> f.calculateUsage());
        ways.forEach(f -> f.calculateUsage());
        nodes.forEach(f -> f.calculateUsage());
        tagTables.values().forEach(tt -> tt.calculateStringUsage());

        SHeader header = new SHeader();

        if(tile != TileCatalog.PURGATORY_TILE)
        {
            header.nodeIndex = buildSpatialIndex("points", nodeList);
            header.wayIndex = buildSpatialIndex("lines", wayList);
            header.areaIndex = buildSpatialIndex("areas", areaList);
            header.relationIndex = buildSpatialIndex("relations", relationList);
            // TODO: labels: nodes, ways
        }
        else
        {
            // For the Purgatory, we build a "fake" spatial index, is simply
            // a single node that contains all the features of the given type
            // This isn't a tree -- after all, the features are in the Purgatory
            // because we can't tell where they are ( a hedge?)
            // We don't need areas, because we can't reliably tell whether
            // a Purgatory way or relation represents an area. It doesn't
            // matter, because they don't represent valid geometries anyway

            header.nodeIndex = buildFlatIndex("nodes", nodeList);
            header.wayIndex = buildFlatIndex("ways", wayList);
            header.relationIndex = buildFlatIndex("relations", relationList);
        }

        // TODO: this is awkward
        SIndexTree[] indexes = new SIndexTree[4];
        indexes[0] = header.nodeIndex;
        indexes[1] = header.wayIndex;
        indexes[2] = header.areaIndex;
        indexes[3] = header.relationIndex;

        archive = new Archive(4);
        archive.setHeader(header);
        new DefaultFeatureLayout(archive)
            .indexes(indexes)
            .tags(tagTables.values())
            .strings(localStrings.values())
            .relationTables(relationTables.values())
            .layout();
        // header.payloadSize = archive.size() - 4;      // payload size (excludes first 4 bytes)

        nodes.forEach(node -> node.export(this));
        ways.forEach(way -> way.export(this));
        relations.forEach(rel -> rel.export(this));
    }

    public PbfOutputStream writeTo(ByteBuffer buf, int pos) throws IOException
    {
        PbfOutputStream imports = new PbfOutputStream();
        archive.writeToBuffer(buf, pos, imports);
        return imports;
    }

    public void dump(PrintWriter out)
    {
        Struct s = archive.header();
        int pos = 0;
        int totalGaps = 0;
        while(s != null)
        {
            int gap = s.location() - pos;
            if(gap > 0)
            {
                out.format("     ---  %d-byte gap\n", gap);
                totalGaps += gap;
            }
            s.dump(out);
            pos = s.location() + s.size();
            s = s.next();
        }
        out.format("\n%d bytes wasted\n", totalGaps);
    }
}