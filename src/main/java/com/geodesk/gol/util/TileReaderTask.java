/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.util;

import java.nio.ByteBuffer;

public class TileReaderTask implements Runnable
{
    protected final ByteBuffer buf;
    protected final int pTile;

    public static final int NODES = 0;
    public static final int WAYS = 1;
    public static final int AREAS = 2;
    public static final int RELATIONS = 3;

    public TileReaderTask(ByteBuffer buf, int pTile)
    {
        this.buf = buf;
        this.pTile = pTile;
    }

    @Override public void run()
    {
        scanNodes(pTile + 8);
        scanFeatures(WAYS, pTile+12);
        scanFeatures(AREAS, pTile+16);
        scanFeatures(RELATIONS, pTile+20);
    }

    private void scanNodes(int ppTree)
    {
        int p = buf.getInt(ppTree);
        if(p == 0) return;
        if((p & 1) == 0)
        {
            beginIndex(NODES, -1);
            scanNodeRoot(ppTree);
            endIndex();
            return;
        }
        p = ppTree + (p ^ 1);
        for(;;)
        {
            int last = buf.getInt(p) & 1;
            int indexBits = buf.getInt(p+4);
            beginIndex(NODES, indexBits);
            scanNodeRoot(p);
            endIndex();
            if(last != 0) break;
            p += 8;
        }
    }

    private void scanNodeRoot(int ppTree)
    {
        int ptr = buf.getInt(ppTree);
        if (ptr != 0)
        {
            if ((ptr & 2) != 0)
            {
                scanNodeLeaf(ppTree + (ptr & 0xffff_fffc));
            }
            else
            {
                scanNodeTree(ppTree + (ptr & 0xffff_fffc));
            }
        }
    }

    private void scanRoot(int ppTree)
    {
        int ptr = buf.getInt(ppTree);
        if (ptr != 0)
        {
            if ((ptr & 2) != 0)
            {
                scanLeaf(ppTree + (ptr & 0xffff_fffc));
            }
            else
            {
                scanTree(ppTree + (ptr & 0xffff_fffc));
            }
        }
    }

    private void scanNodeTree(int p)
    {
        for (;;)
        {
            int ptr = buf.getInt(p);
            int last = ptr & 1;
            if ((ptr & 2) != 0)
            {
                scanNodeLeaf(p + (ptr ^ 2 ^ last));
            }
            else
            {
                scanNodeTree(p + (ptr ^ last));
            }
            if (last != 0) break;
            p += 20;
        }
    }

    private void scanNodeLeaf(int p)
    {
        p += 8;
        for(;;)
        {
            int flags = buf.getInt(p);
            node(p);
            if((flags & 1) != 0) break;
            p += 20 + (flags & 4);
            // If Node is member of relation (flag bit 2), add
            // extra 4 bytes for the relation table pointer
        }
    }

    private void scanFeatures(int type, int ppTree)
    {
        int p = buf.getInt(ppTree);
        if(p == 0) return;
        if((p & 1) == 0)
        {
            beginIndex(type, -1);
            scanRoot(ppTree);
            endIndex();
            return;
        }
        p = ppTree + (p ^ 1);
        for(;;)
        {
            int last = buf.getInt(p) & 1;
            int indexBits = buf.getInt(p+4);
            beginIndex(type, indexBits);
            scanRoot(p);
            endIndex();
            if(last != 0) break;
            p += 8;
        }
    }

    private void scanTree(int p)
    {
        for (;;)
        {
            int ptr = buf.getInt(p);
            int last = ptr & 1;
            if ((ptr & 2) != 0)
            {
                scanLeaf(p + (ptr ^ 2 ^ last));
            }
            else
            {
                scanTree(p + (ptr ^ last));
            }
            if (last != 0) break;
            p += 20;
        }
    }

    private void scanLeaf(int p)
    {
        p += 16;
        for(;;)
        {
            int flags = buf.getInt(p);
            if((flags & (3 << 3)) == (1 << 3))
            {
                way(p);
            }
            else
            {
                relation(p);
            }
            if((flags & 1) != 0) break;
            p += 32;
        }
    }

    protected void beginIndex(int type, int indexBits)
    {
        // do nothing
    }

    protected void endIndex()
    {
        // do nothing
    }

    protected void node(int pNode)
    {
        // do nothing
    }

    protected void way(int p)
    {
        // do nothing
    }

    protected void relation(int p)
    {
        // do nothing
    }
}
