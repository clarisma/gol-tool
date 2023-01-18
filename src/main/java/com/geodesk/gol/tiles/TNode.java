/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.compiler.SNode;

import java.io.IOException;

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

    @Override public void writeTo(StructOutputStream out) throws IOException
    {
        // assert !isForeign();
        out.writeInt(minX);
        out.writeInt(minY);
        writeId(out);
        out.writePointer(tags, tags.hasUncommonKeys() ? 1 : 0);
        if (relations != null) out.writePointer(relations);
    }
}
