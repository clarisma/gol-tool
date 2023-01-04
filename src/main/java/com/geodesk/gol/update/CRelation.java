/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

public class CRelation extends CFeature<CRelation.Change>
{
    public CRelation(long id)
    {
        super(id);
    }

    public static class Change extends CFeature.Change
    {
        CFeature<?>[] members;
        String[] roles;

        public Change(ChangeType changeType, int version, String[] tags,
            CFeature<?>[] members, String[] roles)
        {
            super(changeType, version, tags);
            this.members = members;
            this.roles = roles;
        }

    }
}
