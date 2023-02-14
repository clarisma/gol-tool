/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import com.geodesk.core.XY;
import com.geodesk.feature.FeatureType;

public class CNode extends CFeature<CNode.Change>
{
    public CNode(long id)
    {
        super(id);
    }

    @Override public FeatureType type()
    {
        return FeatureType.NODE;
    }

    public static class Change extends CFeature.Change
    {
        /**
         * The node's future X-coordinate.
         */
        final int x;

        /**
         * The node's future Y-coordinate.
         */
        final int y;

        /**
         * The number of ways from which this node has been dropped.
         * We only track this for nodes that will have no tags in the future.
         * Used to determine whether a node might become an orphan.
         *
         */
        private int wayDropCount;

        /**
         * The number of relations from which this node has been dropped.
         * We only track this for nodes that will have no tags in the future.
         * Used to determine whether a node might lose its feature status.
         */
        private int relationDropCount;


        public Change(int version, int flags, String[] tags, int x, int y)
        {
            super(version, flags, tags);
            this.x = x;
            this.y = y;
        }

        public boolean xyEquals(long xy)
        {
            return x == XY.x(xy) && y == XY.y(xy);
        }
    }
}
