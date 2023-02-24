/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.geodesk.feature.store.FeatureStore;
import org.eclipse.collections.api.map.primitive.MutableLongIntMap;

import java.util.List;

public class ChangeModel
{
    private final FeatureStore store;
    /**
     * Mapping of node IDs to an index into `changedNodes` or `nodeLocations`.
     * To distinguish, the index into `changedNodes` is XOR'd with -1
     */
    private MutableLongIntMap nodes;
    private MutableLongIntMap ways;
    private MutableLongIntMap relations;

    /**
     * All nodes that have changed, excluding those that were anonymous
     * in the past and will be anonymous in the future.
     */
    private List<ChangedNode> changedNodes;
    private List<ChangedWay> changedWays;
    private List<ChangedRelation> changedRelations;

    /**
     * The X/Y locations of:
     * - changed nodes that were and will be anonymous
     * - referenced anonymous nodes
     * - referenced feature nodes
     * Anonymous nodes come before feature nodes; entry 0 is unused.
     */
    private long[] nodeLocations;
    private int[] wayRefs;
    private int[] relationRefs;

    /**
     * Index of the first node in `nodeLocation` that represents a feature node.
     */
    private int firstFeatureNode;

    public ChangeModel(FeatureStore store)
    {
        this.store = store;
    }
}
