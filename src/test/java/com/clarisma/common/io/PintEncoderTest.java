package com.clarisma.common.io;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

public class PintEncoderTest
{
    private static final long[] VALUES = { 13, 47, 12812, 89474262, 3, 679, 0, 700000, 33392229229L };
    @Test public void test()
    {
        PintEncoder out = new PintEncoder();
        out.writeFixed64(0);
        for(long v: VALUES) out.writePacked56(v);
        ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
        buf.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, buf.getLong());
        for(long v: VALUES)
        {
            assertEquals(v, PintDecoder.readPacked56(buf));
        }
    }
}