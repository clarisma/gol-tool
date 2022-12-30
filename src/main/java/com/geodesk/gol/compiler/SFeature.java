/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

// TODO: create SFeature2D class with explicit bounds
//  saves 20 bytes per way/relation vs. references BBox

import com.clarisma.common.soar.SharedStruct;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.core.TileQuad;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.FeatureFlags;
import com.geodesk.core.Box;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.build.TileCatalog;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;

// TODO: build and assign unqiue reltables in later step

// TODO: should we include ref back to FeatureTile?

// TODO: make comparable to allow easy sorting

// TODO: create SFeature2D clas that includes bbox

public abstract class SFeature extends SharedStruct implements FeatureFlags //, Iterable<SFeature>
{
    protected long id;
    protected STagTable tags;
    /**
     * List of all relations to which this feature belongs (can be {@code null})
     */
    private SRelationTable relations;
    protected int flags;
    /**
     * Tile quad where the feature is located.
     */
    protected int tileQuad = -1;        // TODO: need a way to say "in purgatory"
        // TODO: does -1 (invalid) already mean that?
    protected int group;

    // These flags are NOT stored
    protected static final int LOCAL_FLAG = 1 << 15;
    protected static final int BUILT_FLAG = 1 << 14;
    protected static final int RELATION_IS_MEASURING_FLAG = 1 << 13;
    // protected static final int HAS_FOREIGN_NODES_FLAG = 1 << 12;
    protected static final int LOCAL_ROLES_FLAG = 1 << 11;

    protected abstract class SFeatureBody extends Struct implements Iterable<Struct>
    {
        @Override public Iterator<Struct> iterator()
        {
            return Collections.emptyIterator();
        }
    }

    public SFeature(long id)
    {
        this.id = id;
        setAlignment(2);
    }

    public long id()
    {
        return id;
    }

    public FeatureType type()
    {
        return FeatureType.values()[(flags >> FEATURE_TYPE_BITS) & 3];
    }

    public abstract Bounds bounds();

    public int tileQuad()
    {
        return tileQuad;
    }

    public void setTileQuad(int quad)
    {
        // TODO: re-enable
        /*
        assert (quad & 0xf000_0000) != 0 || quad == TileCatalog.PURGATORY_TILE:
            "Not a valid quad";

         */
        tileQuad = quad;
    }

    /*
    public void setTile(int tile)
    {
        assert (tile & 0xf000_0000) == 0: "This is a quad, not a tile";
        tileQuad = TileQuad.fromSingleTile(tile);
    }
     */

    public STagTable tags()
    {
        return tags;
    }

    public void setTags(STagTable tags)
    {
        this.tags = tags;
    }

    protected void setFlag(int flag)
    {
        flags |= flag;
    }

    protected void clearFlag(int flag)
    {
        flags &= ~flag;
    }

    protected void setFlag(int flag, boolean b)
    {
        flags = b ? (flags | flag) : (flags & ~flag);
    }

    public boolean isArea()
    {
        return (flags & AREA_FLAG) != 0;
    }

    public boolean isLocal()
    {
        return (flags & LOCAL_FLAG) != 0;
    }

    public boolean isForeign()
    {
        return (flags & LOCAL_FLAG) == 0;
    }

    public boolean isMissing()
    {
        return tileQuad == -1;
    }

    public boolean isBuilt()
    {
        return (flags & BUILT_FLAG) != 0;
    }

    protected boolean isMeasuring()
    {
        return (flags & RELATION_IS_MEASURING_FLAG) != 0;
    }

    // TODO: foreign features don't need reltables
    //  However, when we are reading local rels, we don't know which members
    //  will be foreign yet
    //  Can we be sure that we are always reading local ways before local rels?
    //  Then we would know which ways are local
    public void addParentRelation(SRelation relation)
    {
        if (relations == null) relations = new SRelationTable();
        relations.addRelation(relation);
        setFlag(RELATION_MEMBER_FLAG);
    }

    public boolean isRelationMember()
    {
        return relations != null;
    }

    public SRelationTable relations()
    {
        return relations;
    }

    public SFeatureBody body()
    {
        return null;
    }

    public int group()
    {
        return group;
    }

    public void setGroup(int group)
    {
        this.group = group;
    }

    public void markAsLastSpatialItem()
    {
        setFlag(LAST_SPATIAL_ITEM_FLAG);
    }

    public void markAsLocal()
    {
        setFlag(LOCAL_FLAG);
    }

    public void markAsForeign()
    {
        clearFlag(LOCAL_FLAG);
    }

    public abstract void build(FeatureTile ft);

    // TODO: does this work for oversize features?
    // oversize should always set both multitile flags
    protected void calculateMultitileFlags(FeatureTile tile)
    {
        int multitileFlags = 0;
        Bounds bounds = bounds();
        Bounds tileBounds = tile.bounds();
        int n = 0;
        if (bounds.minX() < tileBounds.minX())
        {
            multitileFlags |= MULTITILE_WEST;
            n++;
        }
        if (bounds.maxY() > tileBounds.maxY())
        {
            multitileFlags |= MULTITILE_NORTH;
            n++;
        }
        if (bounds.maxX() > tileBounds.maxX()) n++;
        if (bounds.minY() < tileBounds.minY()) n++;
        if (n > 1) multitileFlags |= MULTITILE_WEST | MULTITILE_NORTH;
        flags = (flags & ~MULTITILE_FLAGS) | multitileFlags;
            // Clear existing flags in case we call this method more than once
    }

    protected void normalize(FeatureTile ft)
    {
        if(isLocal())
        {
            if(tags == null) tags = ft.getTags(null);
            if(relations != null) relations = ft.getRelationTable(relations);
        }
    }

    private void writeId(StructOutputStream out) throws IOException
    {
        out.writeInt(((int) (id >>> 32) << 8) | (flags & 0xff));
        out.writeInt((int) id);
    }

    public void calculateUsage()
    {
        if (!isForeign())
        {
            addUsage(1, UsageScores.BASE_FEATURE_SCORE);
            if (relations != null)
            {
                int relCount = relations.relationCount();
                // Don't use size(), since that returns the size of the struct in bytes
                addUsage(relCount, relCount * UsageScores.RELATION_REFERENCE_SCORE);
                relations.addUsage(1, usage() * UsageScores.RELATIONTABLE_RATIO);
            }
            if (tags != null) tags.addUsage(1, usage() * UsageScores.TAGTABLE_RATIO);
        }
    }

    public void writeTo(StructOutputStream out) throws IOException
    {
        assert !isForeign();
        boolean isNode = this instanceof SNode;
            // TODO: use type flags instead of class check
        Bounds b = bounds();
        out.writeInt(b.minX());
        out.writeInt(b.minY());
        if (!isNode)
        {
            out.writeInt(b.maxX());
            out.writeInt(b.maxY());
        }
        writeId(out);
        out.writePointer(tags, tags.uncommonKeyCount() > 0 ? 1 : 0);
        if (isNode)
        {
            if (isRelationMember()) out.writePointer(relations);
        }
        else
        {
            out.writePointer(body());
        }
    }

    public String typeString()
    {
        if (this instanceof SNode) return "node";
        if (this instanceof SWay) return "way";
        if (this instanceof SRelation) return "relation";
        return null;
    }

    public String toString()
    {
        return String.format("%s/%d", typeString(), id);
    }

    public String dumped()
    {
        return String.format("STUB %s %s%s", this,
            TileQuad.toString(tileQuad),
            isForeign() ? " (foreign)" : "");
        // TODO: should not dump foreign features
    }

    public void export(FeatureTile ft)
    {
        if(relations == null || isForeign()) return;
        MutableIntSet tiles = new IntHashSet();
        for(SRelation rel: relations)
        {
            int exportQuad = rel.tileQuad();
            if(exportQuad == tileQuad) continue;
            if(TileQuad.zoom(exportQuad) == TileQuad.zoom(tileQuad))
            {
                exportQuad = TileQuad.subtractQuad(exportQuad, tileQuad);
            }
            TileQuad.forEach(exportQuad, tile -> tiles.add(tile));
        }
        tiles.forEach(tile -> ft.exportFeature(tile, this));
    }

    public long areaCovered()
    {
        Bounds b = bounds();
        if(b == null) return 0;
        return b.width() * b.height();
    }

    protected static final Bounds MISSING_BOUNDS = new Box();

    public void buildInvalid(FeatureTile ft)
    {
        if(isLocal())
        {
            setTileQuad(TileCatalog.PURGATORY_TILE);  // TODO: has to be a "quad"?
            normalize(ft);
            setFlag(BUILT_FLAG);
        }
    }
}