/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

public class CFeature<T extends CFeature.Change>
{
    private final long id;
    private int tip;
    private int ptr;
    T change;

    public CFeature(long id)
    {
        this.id = id;
    }

    public static class Change
    {
        ChangeType changeType;
        final int version;
        String[] tags;

        protected Change(ChangeType changeType, int version, String[] tags)
        {
            this.changeType = changeType;
            this.version = version;
            this.tags = tags;
        }
    }
}
