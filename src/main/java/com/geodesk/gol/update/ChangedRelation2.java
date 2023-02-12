/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

public class ChangedRelation2 extends ChangedFeature2
{
    long[] memberIds;
    String[] roles;
    long[] membersAdded;
    long[] membersDropped;

    public ChangedRelation2(long id, int version, int flags, String[] tags,
        long[] memberIds, String[] roles)
    {
        super(id, version, flags, tags);
        this.memberIds = memberIds;
        this.roles = roles;
    }
}

