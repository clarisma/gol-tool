/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import java.nio.ByteBuffer;

public abstract class ChangedFeature implements Comparable<ChangedFeature>
{
    private final long idAndFlags;
    final int version;
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

    /**
     * The feature will be deleted. Ways and relations are only deleted
     * explicitly. Nodes can be deleted explicitly or implicitly (loss of
     * feature status because a node will no longer have tags AND will
     * not be part of relations AND will not be a duplicate AND will not
     * be an orphan).
     */
    public static final int DELETE = 1;

    protected ChangedFeature(long id, int flags, int version, String[] tags)
    {
        this.idAndFlags = (id << 8) | flags;
        this.version = version;
        this.tags = tags;
    }

    public long id()
    {
        return idAndFlags >>> 8;
    }

    @Override public int compareTo(ChangedFeature other)
    {
        int comp = Long.compare(idAndFlags, other.idAndFlags);
        if(comp != 0) return comp;
        return Integer.compare(version, other.version);
    }
}
