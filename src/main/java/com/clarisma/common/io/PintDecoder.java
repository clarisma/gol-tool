/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.io;

import java.nio.ByteBuffer;

public class PintDecoder
{
    protected ByteBuffer buf;
    protected int pos;

    public PintDecoder(ByteBuffer buf, int pos)
    {
        this.buf = buf;
        this.pos = pos;
    }

    public int pos()
    {
        return pos;
    }

    public static long readPacked56(ByteBuffer buf)
    {
        int pos = buf.position();
        int prefix = buf.get(pos);
        int len = Integer.numberOfTrailingZeros(prefix)+1;
        long v = buf.getLong(pos-8+len);
        buf.position(pos+len);
        return v >>> ((8-len) * 8 + len);
    }

    public static int readPacked28(ByteBuffer buf)
    {
        int pos = buf.position();
        int prefix = buf.get(pos);
        int len = Integer.numberOfTrailingZeros(prefix)+1;
        int v = buf.getInt(pos-4+len);
        buf.position(pos+len);
        return v >>> ((4-len) * 8 + len);
    }
}
