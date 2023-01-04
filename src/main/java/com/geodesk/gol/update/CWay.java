/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

public class CWay extends CFeature<CWay.Change>
{
    // TODO: need current nodeIDs

    public CWay(long id)
    {
        super(id);
    }

    public static class Change extends CFeature.Change
    {
        long[] nodeIds;

        public Change(ChangeType changeType, int version, String[] tags, long[] nodeIds)
        {
            super(changeType, version, tags);
            this.nodeIds = nodeIds;
        }
    }
}
