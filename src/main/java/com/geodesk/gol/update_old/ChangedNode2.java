/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

public class ChangedNode2 extends ChangedFeature2
{
    int x;
    int y;

    public ChangedNode2(long id, int version, int flags, String[] tags, int x, int y)
    {
        super(id, version, flags, tags);
        this.x = x;
        this.y = y;
    }
}
