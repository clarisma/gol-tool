/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

public class ChangedNode extends ChangedFeature
{
    int x;
    int y;

    public ChangedNode(long id, int version, int flags, long[] tags, int x, int y)
    {
        super(id, version, flags, tags);
        this.x = x;
        this.y = y;
    }
}
