/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.clarisma.common.soar.StructWriter;
import com.geodesk.feature.store.FeatureFlags;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.compiler.SNode;

import java.io.IOException;
import java.nio.ByteBuffer;

public class TNode extends TFeature
{
    public TNode(long id)
    {
        super(id);
        setSize(20);
        setAnchor(8);
    }

    @Override public int maxX()
    {
        return minX;
    }

    @Override public int maxY()
    {
        return minY;
    }

    @Override public Struct body()
    {
        return null;
    }

    @Override public void write(StructWriter out)
    {
        assert !isForeign();
        out.writeInt(minX);
        out.writeInt(minY);
        writeId(out);
        out.writePointer(tags, tags.hasUncommonKeys() ? 1 : 0);
        if (relations != null) out.writePointer(relations);
    }

    @Override public void readStub(TileReader reader, int p)
    {
        ByteBuffer buf = reader.buf();
        minX = buf.getInt(p - 8);
        minY = buf.getInt(p - 4);
        flags |= (buf.getInt(p) & 0xFE) | LOCAL_FLAG;
        setLocation(p - 8);
        setSize(isRelationMember() ? 24 : 20);
        tags = reader.readTagsIndirect(p + 8);
    }

    @Override public void readBody(TileReader reader)
    {
        if(!isRelationMember()) return;
        relations = reader.readRelationTableIndirect(location() + 20);
    }
}
