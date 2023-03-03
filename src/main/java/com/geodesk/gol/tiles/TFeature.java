/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SharedStruct;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.clarisma.common.soar.StructWriter;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.FeatureFlags;
import com.geodesk.geom.Bounds;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.geodesk.feature.store.FeatureFlags.*;

/**
 * A struct representing a feature stored in the current tile, or a foreign
 * feature referenced by one or more features in the current tile.
 *
 * Foreign Features
 * ================
 *
 * Foreign features have LOCAL_FLAG cleared. The `location` property of the
 * struct contains the location within the foreign tile, based on the foreign
 * feature's TIP.
 *
 * When building a GOL from an .osm.pbf, a local way or relation can become
 * foreign if it is multi-tile and the current tile is not in its tileset.
 * Since the Sorter has no knowledge of a feature's true geometry (only the
 * tiles it occupies), it treats all tile quads as dense. When the Compiler
 * calculates a feature's actual geometry, it may turn out that the feature
 * doesn't actually occupy all four tiles of the quad (i.e. the uad is "sparse").
 * In that case, the feature is treated as foreign within the tiles that it
 * doesn't occupy (Typically, only one quadrant is omitted, but it is also
 * possible that tow diagonal tiles are omitted)
 */
public abstract class TFeature extends SharedStruct implements Bounds, Comparable<TFeature>
{
    protected final long id;
    protected TTagTable tags;
    protected TRelationTable relations;
    protected int flags;
    protected int minX;
    protected int minY;
    protected int tileQuad = -1;        // TODO: quadOrTIp

    protected static final int LOCAL_FLAG = 1 << 15;


    public TFeature(long id)
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

    public boolean isForeign()
    {
        return (flags & LOCAL_FLAG) == 0;
    }

    public boolean isArea()
    {
        return (flags & AREA_FLAG) != 0;
    }

    public boolean isRelationMember()
    {
        return (flags & RELATION_MEMBER_FLAG) != 0;
    }

    @Override public int minX()
    {
        return minX;
    }

    @Override public int minY()
    {
        return minY;
    }

    public abstract Struct body();

    public TTagTable tags()
    {
        return tags;
    }

    public TRelationTable relations()
    {
        return relations;
    }

    @Override public int compareTo(TFeature other)
    {
        return Long.compare(id, other.id);
    }

    // TODO: remove
    protected void writeId(StructOutputStream out) throws IOException
    {
        out.writeInt(((int) (id >>> 32) << 8) | (flags & 0xff));
        out.writeInt((int) id);
    }

    protected void writeId(StructWriter out)
    {
        out.writeInt(((int) (id >>> 32) << 8) | (flags & 0xff));
        out.writeInt((int) id);
    }

    public abstract void readStub(TileReader reader, int p);

    public abstract void readBody(TileReader reader);

    public int typeCode()
    {
        return (flags >> 3) & 3;
    }

    @Override public String toString()
    {
        int type = typeCode();
        String s;
        if(type == 0)
        {
            s = "node/";
        }
        else if(type == 1)
        {
            s = "way/";
        }
        else
        {
            assert type == 2;
            s = "relation/";
        }
        return s + id;
    }

    public void markAsLast()
    {
        flags |= FeatureFlags.LAST_SPATIAL_ITEM_FLAG;
    }

    public void build(TTile tile)
    {
        // TODO
    }
}
