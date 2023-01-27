/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.geodesk.feature.FeatureType;

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
public abstract class CFeature<T extends CFeature.Change>
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
    public static final int CHANGED_TAGS = 1 << 0;
    /**
     * Indicates that a node's x/y or a way's geometry has changed (does not
     * apply to relations).
     */
    public static final int CHANGED_GEOMETRY = 1 << 1;
    /**
     * A way's node IDs have changed. This *always* results in CHANGED_GEOMETRY.
     */
    public static final int CHANGED_NODE_IDS = 1 << 2;
    /**
     * The members (or their roles) of a relation have changed.
     */
    public static final int CHANGED_MEMBERS = 1 << 3;
    /**
     * The bounding box of a way or relation has changed.
     */
    public static final int CHANGED_BBOX = 1 << 4;
    /**
     * A feature has moved into (or out of) one or more tiles.
     */
    public static final int CHANGED_TILES = 1 << 5;


    public static final int CREATE = 1 << 8;

    /**
     * The feature will be deleted. Ways and relations are only deleted
     * explicitly. Nodes can be deleted explicitly or implicitly (loss of
     * feature status because a node will no longer have tags AND will
     * not be part of relations AND will not be a duplicate AND will not
     * be an orphan).
     */
    public static final int DELETE = 1 << 9;

    /**
     * A node's future x/y is the same as one or more other nodes.
     * This does not mean that the node will be tagged geodesk:duplicate
     * (even if it does not have tags), because it may be included in a
     * relation.
     */
    public static final int SHARED_FUTURE_LOCATION = 1 << 10;
    /**
     * A feature will be member of at least one relation in the future.
     */
    // public static final int FUTURE_RELATION_MEMBER = 1 << 9;


    public CFeature(long id)
    {
        this.id = id;
    }

    public abstract FeatureType type();

    public long id()
    {
        return id;
    }

    public int tip()
    {
        return tip;
    }

    public int pointer()
    {
        return ptr;
    }

    public void found(int tip, int ptr)
    {
        this.tip = tip;
        this.ptr = ptr;
    }

    public void change(T newChange)
    {
        if(newChange.version == 1) newChange.flags |= CREATE;
        if(change != null)
        {
            if (newChange.version >= change.version)
            {
                int createFlag = change.flags & CREATE;
                if (createFlag != 0 && (newChange.flags & DELETE) != 0)
                {
                    change = null;
                    return;
                }
                // continue...
            }
            else
            {
                change.flags |= newChange.flags & CREATE;
                return;
            }
        }
        change = newChange;
    }

    public static class Change
    {
        int flags;
        /**
         * The OSM version number of the feature if it changed explicitly,
         * otherwise 0.
         */
        final int version;
        /**
         * The future tags of the feature.
         */
        String[] tags;

        protected Change(int version, int flags, String[] tags)
        {
            this.version = version;
            assert flags == (flags & (CREATE | DELETE));
            this.flags = flags;
            this.tags = tags;
        }

        @Override public String toString()
        {
            String action = "Modified #";
            if ((flags & CREATE) != 0) action = "Created #";
            if ((flags & DELETE) != 0) action = "Deleted #";
            return action + version;
        }
    }

    @Override public String toString()
    {
        return "%s/%d (%s)".formatted(FeatureType.toString(type()), id,
            change != null ? change : "referenced");
    }
}
