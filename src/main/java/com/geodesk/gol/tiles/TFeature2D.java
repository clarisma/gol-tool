/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;

import java.io.IOException;

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

    @Override public void writeTo(StructOutputStream out) throws IOException
    {
        // assert !isForeign();
        out.writeInt(minX);
        out.writeInt(minY);
        out.writeInt(maxX);
        out.writeInt(maxY);
        writeId(out);
        out.writePointer(tags, tags.hasUncommonKeys() ? 1 : 0);
        out.writePointer(body);
    }

}
