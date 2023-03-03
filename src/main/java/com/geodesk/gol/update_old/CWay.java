/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import com.geodesk.core.Box;
import com.geodesk.feature.FeatureType;

public class CWay extends CFeature<CWay.Change>
{
    // TODO: need current nodeIDs? (No)
    // TODO: past quad

    public CWay(long id)
    {
        super(id);
    }

    public CWay(long id, Change change)
    {
        super(id);
        this.change = change;
    }

    @Override public FeatureType type()
    {
        return FeatureType.WAY;
    }

    public void changeImplicitly(int flags, long[] nodeIds)
    {
        assert change == null;
        change = new Change(0, flags, null, nodeIds);
    }

    public static class Change extends CFeature.Change
    {
        /**
         * The way's future node IDs.
         */
        long[] nodeIds;
        long[] nodeIdsAdded;
        long[] nodeIdsRemoved;
        /**
         * The encoded representation of the way's new geometry (delta-encoded
         * X/Y) or null if the way's geometry has not changed (or has not been
         * calculated yet)
         */
        byte[] geometry;
        /**
         * The way's new bounding box, or null if the bbox has not changed
         * (or has not yet been calculated).
         */
        Box bbox;
        // TODO: quad

        public Change(int version, int flags, String[] tags, long[] nodeIds)
        {
            super(version, flags, tags);
            this.nodeIds = nodeIds;
        }
    }
}
