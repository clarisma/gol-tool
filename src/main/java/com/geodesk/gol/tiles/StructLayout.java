/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.Struct;

import java.util.ArrayDeque;
import java.util.Queue;

public class StructLayout
{
    private Struct header;
	private Struct last;
	private int pos;
    private int maxDrift;
    private Queue<Struct> deferred = new ArrayDeque<>();

    public StructLayout(Struct header)
    {
        this.header = header;
        last = header;
        header.setLocation(0);
        pos = header.size();
    }

    public int size()
    {
        return pos;
    }

    public void put(Struct s)
    {
        pos = s.alignedLocation(pos);
        s.setLocation(pos);
        last.setNext(s);
        pos += s.size();
        last = s;
    }

    protected void maxDrift(int max)
    {
        maxDrift = max;
    }

    private void tryPlaceDeferred()
    {
        while(!deferred.isEmpty())
        {
            if(deferred.peek().alignedLocation(pos) != pos) break;
            place(deferred.remove());
        }
    }

    protected void place(Struct s)
    {
        if(maxDrift == 0)
        {
            put(s);
            return;
        }
        for(;;)
        {
            if (s.alignedLocation(pos) != pos)
            {
                s.setLocation(~pos);
                deferred.add(s);
                return;
            }
            if (deferred.isEmpty() || pos + s.size() + deferred.peek().location() < maxDrift)
            {
                put(s);
                tryPlaceDeferred();
                return;
            }
            put(deferred.remove());
            tryPlaceDeferred();
        }
    }

    protected void flush()
    {
        while(!deferred.isEmpty()) put(deferred.remove());
    }

}
