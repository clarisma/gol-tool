/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SharedStruct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.feature.FeatureType;
import com.geodesk.geom.Bounds;

import java.io.IOException;

import static com.geodesk.feature.store.FeatureFlags.*;

public abstract class TFeature extends SharedStruct implements Bounds, Comparable<TFeature>
{
    protected final long id;
    protected TTagTable tags;
    protected TRelationTable relations;
    protected int flags;
    protected int minX;
    protected int minY;
    protected int tileQuad = -1;
    protected int group;

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

    @Override public int minX()
    {
        return minX;
    }

    @Override public int minY()
    {
        return minY;
    }

    @Override public int compareTo(TFeature other)
    {
        return Long.compare(id, other.id);
    }

    protected void writeId(StructOutputStream out) throws IOException
    {
        out.writeInt(((int) (id >>> 32) << 8) | (flags & 0xff));
        out.writeInt((int) id);
    }
}
