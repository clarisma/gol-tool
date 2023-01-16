/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

/**
 * A class that describes a feature within a ChangeGraph. Used for both features
 * that have been changed and nodes/members of changed ways/relations.
 *
 * For the flags, "past" means "before applying changes", "future" means
 * "after applying changes"
 *
 * @param <T> a `Change` object specific to node, way or relation that
 *            describes what (if anything) about this feature has changed
 */
public class CFeature<T extends CFeature.Change>
{
    private final long id;
    /**
     * The TIP of one of the tiles where the past version of the feature lives,
     * or -1 if the feature does not currently exist (0 = purgatory)
     */
    private int tip = -1;
    /**
     * If the feature exists, this is the offset (anchor) of its past version
     * in the tile referenced by the TIP.
     */
    private int ptr;
    /**
     * Explicit or implicit changes to the feature, or `null` if the feature is
     * unchanged.
     */
    T change;

    /**
     * A feature's tags have changed.
     */
    public static final int CHANGED_TAGS = 1;
    /**
     * Indicates that a node's x/y or a way's geometry has changed (does not
     * apply to relations).
     */
    public static final int CHANGED_GEOMETRY = 2;
    /**
     * A way's node IDs have changed. This *always* results in CHANGED_GEOMETRY.
     */
    public static final int CHANGED_NODE_IDS = 4;
    /**
     * The members (or their roles) of a relation have changed.
     */
    public static final int CHANGED_MEMBERS = 4;
    /**
     * The bounding box of a way or relation has changed.
     */
    public static final int CHANGED_BBOX = 16;
    /**
     * A feature has moved into (or out of) one or more tiles.
     */
    public static final int CHANGED_TILES = 32;

    /**
     * A node's future x/y is the same as one or more other nodes.
     * This does not mean
     */
    public static final int SHARED_FUTURE_LOCATION = 128;
    /**
     * A feature will be member of at least one relation in the future.
     */
    public static final int FUTURE_RELATION_MEMBER = 256;


    public CFeature(long id)
    {
        this.id = id;
    }

    public long id()
    {
        return id;
    }

    public static class Change
    {
        // just use flag bits? We need flags anyway
        ChangeType changeType;
        /**
         * The OSM version number of the feature if it changed explicitly,
         * otherwise 0.
         */
        final int version;
        /**
         * The future tags of the feature.
         */
        String[] tags;

        protected Change(ChangeType changeType, int version, String[] tags)
        {
            this.changeType = changeType;
            this.version = version;
            this.tags = tags;
        }
    }
}
