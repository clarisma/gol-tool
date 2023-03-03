/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

public abstract class ChangedFeature extends FeatureRef
{
    final long id;
    final int version;
    int flags;
    long[] tags;

    public static final int CREATE = 1 << 8;

    /**
     * The feature will be deleted. Ways and relations are only deleted
     * explicitly. Nodes can be deleted explicitly or implicitly (loss of
     * feature status because a node will no longer have tags AND will
     * not be part of relations AND will not be a duplicate AND will not
     * be an orphan).
     */
    public static final int DELETE = 1 << 9;

    protected ChangedFeature(long id, int version, int flags, long[] tags)
    {
        this.id = id;
        this.version = version;
        this.flags = flags;
        this.tags = tags;
    }
}
