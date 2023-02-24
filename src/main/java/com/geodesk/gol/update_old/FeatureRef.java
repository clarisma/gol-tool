/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

public class FeatureRef
{
    protected int tip;
    protected int ptr;
    protected int quad;

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
}
