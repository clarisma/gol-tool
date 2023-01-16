/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.geodesk.core.Box;

public class CWay extends CFeature<CWay.Change>
{
    // TODO: need current nodeIDs?

    public CWay(long id)
    {
        super(id);
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

        public Change(ChangeType changeType, int version, String[] tags, long[] nodeIds)
        {
            super(changeType, version, tags);
            this.nodeIds = nodeIds;
        }
    }
}
