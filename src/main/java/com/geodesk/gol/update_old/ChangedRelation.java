/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

public class ChangedRelation extends ChangedFeature
{
    long[] memberIds;
    int[] roles;
    long[] membersAdded;
    long[] membersDropped;

    public ChangedRelation(long id, int version, int flags, long[] tags,
        long[] memberIds, int[] roles)
    {
        super(id, version, flags, tags);
        this.memberIds = memberIds;
        this.roles = roles;
    }
}

