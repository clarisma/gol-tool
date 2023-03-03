/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

class SearchTile
{
    static final int FIND_NODES = 1;
    static final int FIND_WAYS = 1 << 1;
    static final int FIND_RELATIONS = 1 << 2;
    static final int FIND_WAY_NODES = 1 << 3;
    static final int FIND_DUPLICATE_XY = 1 << 4;
    /*
    static final int FIND_FEATURES = FIND_NODES | FIND_WAYS | FIND_RELATIONS;
    static final int FIND_EVERYTHING = FIND_FEATURES | FIND_WAY_NODES;
     */
    static final int SUBMITTED = 1 << 8;

    int tip;
    int flags;

    SearchTile(int tip)
    {
        this.tip = tip;
    }

    boolean isSubmitted()
    {
        return (flags & SUBMITTED) != 0;
    }

    // TODO: Can only add one flag at a time, otherwise check becomes
    //  ambiguous
    public boolean addFlags(int flags)
    {
        assert !isSubmitted();
        if((this.flags & (flags | (flags << 16))) == 0)
        {
            this.flags |= flags;
            return true;
        }
        return false;
    }

    public void done()
    {
        flags <<= 16;
    }

    public boolean hasTasks()
    {
        return (flags & 0xffff) != 0;
    }
}
