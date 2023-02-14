/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

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
     * The TIP of one of the tiles where the past version of the feature lives;
     * or TIP_NOT_FOUND if the feature does not currently exist and has not
     * been previously referenced; or TIP_ANONYMOUS_NODE if a node exists
     * purely as an anonymous node (as a location on a way).
     * The above are different from 0, which is the TIP of the Purgatory,
     * which contains "missing" features (features that don't currently exist,
     * but are being referenced by relations).
     */
    private int tip = TIP_NOT_FOUND;

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

    // TODO: store tile number as well, makes it easier to calc quad of features


    /**
     * A feature's tags have changed. For a node, this may cause its feature
     * status to change (which in turn could cause CHANGED_MEMBERS for its
     * parent ways).
     * For explicitly changed features, this flag is always set until analysis
     * determines that the tags remain unchanged.
     */
    public static final int CHANGED_TAGS = 1 << 0;

    /**
     * For a node, indicates that its x/y has changed (which always results in
     * CHANGED_BBOX, and may result in CHANGED_TILES).
     * For a way, indicates that the x/y of one or more of its nodes changed,
     * which may result in CHANGED_BBOX and/or CHANGED_TILES).
     * For relations, this does not apply. // TODO: check
     */
    public static final int CHANGED_GEOMETRY = 1 << 1;

    /**
     * A way's node IDs have changed. This always results in CHANGED_GEOMETRY
     * and CHANGED_MEMBERS.
     * Does not apply to nodes and relations.
     * For explicitly changed ways, this flag is always set until analysis
     * determines that the way's node IDs remain unchanged.
     */
    public static final int CHANGED_NODE_IDS = 1 << 2;

    /**
     * - A way's feature-node table needs to be updated (because the way's
     *   node IDs changed, or the feature-status of one or more way-nodes
     *   has changed (even if the node IDs remain the same)
     * - The members (or their roles) of a relation have changed.
     * - Does not apply to nodes.
     * For explicitly changed ways/relations, this flag is always set until
     * analysis determines that the feature will continue to have the same
     * members.
     */
    public static final int CHANGED_MEMBERS = 1 << 3;

    /**
     * The bounding box of a feature has changed.
     * For a node, always caused by CHANGED_GEOMETRY.
     * For a way, may be caused by CHANGED_GEOMETRY of one or more of its nodes.
     * For a relation, may be caused by CHANGED_BBOX of one or more of its
     * members.
     */
    public static final int CHANGED_BBOX = 1 << 4;

    /**
     * A feature has moved into (or out of) one or more tiles.
     * For a node, may be caused by CHANGED_GEOMETRY.
     * For a way, may be caused by CHANGED_GEOMETRY of one or more of its nodes.
     * For a relation, may be caused by CHANGED_TILES of one or more of its
     * members.
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
