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

    public ChangedWay(long id, int flags, int version, String[] tags, long[] nodeIds)
    {
        super(id, flags, version, tags);
        this.nodeIds = nodeIds;
    }
}
