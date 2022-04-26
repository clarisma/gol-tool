package com.clarisma.common.soar;

import java.io.IOException;

public class SBytes extends SharedStruct
{
    private byte[] bytes;

    public SBytes(byte[] b, int alignment)
    {
        bytes = b;
        setSize(b.length);
        setAlignment(alignment);
    }

    @Override public boolean equals(Object other)
    {
        if(!(other instanceof SBytes)) return false;
        return bytes.equals(((SBytes)other).bytes);
    }

    @Override public int hashCode()
    {
        return bytes.hashCode();
    }

    @Override public String dumped()
    {
        return String.format("BYTES (%d bytes)", bytes.length);
    }

    @Override public void writeTo(StructOutputStream out) throws IOException
    {
        out.write(bytes);
    }
}
