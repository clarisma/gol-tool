/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import java.nio.ByteBuffer;

public abstract class ChangedFeature2 implements Comparable<ChangedFeature2>
{
    final long id;
    final int version;
    int flags;
    protected ByteBuffer buf;
    protected int pFeature;
    protected int quad;
    String[] tags;

    /**
     * Special value for `tip`: The feature has not been looked up in
     * the index;
     */
    public static final int TIP_UNKNOWN = -3;

    /**
     * Special value for `tip`: The feature was not found during scanning.
     * This is different from tip 0 (feature is "missing", i.e. in the
     * Purgatory). This value indicates that the feature isn't in the
     * Purgatory either, which means it is new and not previously referenced.
     */
    public static final int TIP_NOT_FOUND = -2;

    /**
     * Special value for `tip`: A node exists purely as an anonymous node.
     */
    public static final int TIP_ANONYMOUS_NODE = -1;


    public static final int CREATE = 1 << 8;

    /**
     * The feature will be deleted. Ways and relations are only deleted
     * explicitly. Nodes can be deleted explicitly or implicitly (loss of
     * feature status because a node will no longer have tags AND will
     * not be part of relations AND will not be a duplicate AND will not
     * be an orphan).
     */
    public static final int DELETE = 1 << 9;

    protected ChangedFeature2(long id, int version, int flags, String[] tags)
    {
        this.id = id;
        this.version = version;
        this.flags = flags;
        this.tags = tags;
    }

    @Override public int compareTo(ChangedFeature2 other)
    {
        int comp = Long.compare(id, other.id);
        if(comp != 0) return comp;
        return Integer.compare(version, other.version);
    }
}
