package com.geodesk.gol.build;

// TODO: This is in the wrong place; but we should not build on top of Structs

import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.clarisma.common.store.BlobStoreConstants.*;

public abstract class SBlobStoreHeader extends Struct
{
    private long fingerprint;
    private int metadataSize;
    private int pageSizeShift = 12; // 4KB default page

    public SBlobStoreHeader ()
    {
        setSize(4096);
    }

    public void setMetadataSize(int metadataSize)
    {
        this.metadataSize = metadataSize;
    }

    protected abstract void writeClientHeader(ByteBuffer buf);

    @Override public void writeTo(StructOutputStream out) throws IOException
    {
        byte[] bytes = new byte[4096];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(0, MAGIC);
        buf.putInt(VERSION_OFS, VERSION);

        int totalPages = (metadataSize + (1 << pageSizeShift) - 1) >> pageSizeShift;
        buf.putInt(TOTAL_PAGES_OFS, totalPages);
        buf.putInt(PAGE_SIZE_OFS, pageSizeShift);
        buf.putInt(METADATA_SIZE_OFS, metadataSize);
        writeClientHeader(buf);
        out.write(bytes);
    }
}
