/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.geodesk.feature.FeatureType;

public class CRelation extends CFeature<CRelation.Change>
{
    // TODO: past quad

    public CRelation(long id)
    {
        super(id);
    }

    @Override public FeatureType type()
    {
        return FeatureType.RELATION;
    }

    public static class Change extends CFeature.Change
    {
        CFeature<?>[] members;
        String[] roles;
        // TODO: bbox, quad

        public Change(int version, int flags, String[] tags,
            CFeature<?>[] members, String[] roles)
        {
            super(version, flags, tags);
            this.members = members;
            this.roles = roles;
        }

    }
}
