/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.geodesk.core.XY;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.FeatureStore;

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
        final int x;
        final int y;

        /**
         * The number of ways from which this node has been removed.
         * This helps determine whether a feature node will lose its
         * way-node flag, or whether an anonymous node becomes an orphan.
         * If this node belongs to any ways in the future, this count
         * is not used, as the node is not at risk of losing its way-node
         * status or becoming an orphan.
         */
        private int wayDropCount;

        /**
         * The number of relations from which this node has been removed.
         * This helps determine whether a feature node without tags may
         * be demoted to anonymous node because it is no longer part of
         * any relations. If this node has tags in the future or belongs
         * to any relations in the future, this count is not used, as the
         * node is not at risk of being demoted.
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
