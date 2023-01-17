/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

public class CNode extends CFeature<CNode.Change>
{
    public CNode(long id)
    {
        super(id);
    }

    public static class Change extends CFeature.Change
    {
        final int x;
        final int y;

        public Change(ChangeType changeType, int version, String[] tags, int x, int y)
        {
            super(changeType, version, tags);
            this.x = x;
            this.y = y;
        }
    }
}
