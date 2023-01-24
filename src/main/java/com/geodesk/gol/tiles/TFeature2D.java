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

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class TFeature2D<T extends Struct> extends TFeature
{
    protected int maxX;
    protected int maxY;
    protected T body;

    public TFeature2D(long id)
    {
        super(id);
        setSize(32);
        setAnchor(16);
    }

    @Override public int maxX()
    {
        return maxX;
    }

    @Override public int maxY()
    {
        return maxY;
    }

    @Override public Struct body()
    {
        return body;
    }

    // TODO: remove
    @Override public void writeTo(StructOutputStream out) throws IOException
    {
        assert !isForeign();
        out.writeInt(minX);
        out.writeInt(minY);
        out.writeInt(maxX);
        out.writeInt(maxY);
        writeId(out);
        out.writePointer(tags, tags.hasUncommonKeys() ? 1 : 0);
        out.writePointer(body);
    }

    @Override public void write(StructWriter out)
    {
        assert !isForeign();
        out.writeInt(minX);
        out.writeInt(minY);
        out.writeInt(maxX);
        out.writeInt(maxY);
        writeId(out);
        out.writePointer(tags, tags.hasUncommonKeys() ? 1 : 0);
        out.writePointer(body);
    }

    @Override public void readStub(TileReader reader, int p)
    {
        ByteBuffer buf = reader.buf();
        minX = buf.getInt(p - 16);
        minY = buf.getInt(p - 12);
        maxX = buf.getInt(p - 8);
        maxY = buf.getInt(p - 4);
        flags |= (buf.getInt(p) & 0xFE) | LOCAL_FLAG;
        setLocation(p-16);
        tags = reader.readTagsIndirect(p + 8);
    }

    protected int readBodyPointer(TileReader reader)
    {
        ByteBuffer buf = reader.buf();
        int ppBody = location() + 28;
        int pBody = buf.getInt(ppBody) + ppBody;
        reader.checkPointer(pBody);
        return pBody;
    }
}
