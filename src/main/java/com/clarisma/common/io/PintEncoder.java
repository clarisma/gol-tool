package com.clarisma.common.io;

import java.io.ByteArrayOutputStream;

public class PintEncoder extends ByteArrayOutputStream
{
    public void writePacked56(long v)
    {
        int size = (64 - Long.numberOfLeadingZeros(v | 1) + 6) / 7;
        v <<= size;
        v |= 1 << (size - 1);
        writeFixed64(v);
        count -= 8-size;
    }

    public void writePacked28(int v)
    {
        int size = (32 - Integer.numberOfLeadingZeros(v | 1) + 6) / 7;
        v <<= size;
        v |= 1 << (size - 1);
        writeFixed32(v);
        count -= 4-size;
    }

    public void writeFixed32(int val)
    {
        write(val & 0xff);
        write((val >>> 8) & 0xff);
        write((val >>> 16) & 0xff);
        write((val >>> 24) & 0xff);
    }

    public void writeFixed64(long val)
    {
        writeFixed32((int)val);
        writeFixed32((int)(val >>> 32));
    }

    public byte[] buffer()
    {
        return buf;
    }
}
