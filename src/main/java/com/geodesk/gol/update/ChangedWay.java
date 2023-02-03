/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

public class ChangedWay extends ChangedFeature
{
    long[] nodeIds;

    public ChangedWay(long id, int version, int flags, long[] tags, long[] nodeIds)
    {
        super(id, version, flags, tags);
        this.nodeIds = nodeIds;
    }
}
