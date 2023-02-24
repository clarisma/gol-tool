/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

public class ChangedRelation extends ChangedFeature
{
    long[] memberIds;
    String[] roles;
    long[] membersAdded;
    long[] membersDropped;

    public ChangedRelation(long id, int flags, int version, String[] tags,
        long[] memberIds, String[] roles)
    {
        super(id, flags, version, tags);
        this.memberIds = memberIds;
        this.roles = roles;
    }
}

