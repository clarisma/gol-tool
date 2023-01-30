/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.util.Bytes;
import com.clarisma.common.util.Log;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.*;
import org.eclipse.collections.api.list.primitive.LongList;
import org.eclipse.collections.api.map.primitive.*;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ChangeModel
{
    /**
     * The FeatureStore to which these changes apply.
     */
    private final FeatureStore store;

    /**
     * - All explicitly changed nodes (tags, x/y)
     * - All implicitly changed or created nodes (promoted to feature,
     *   demoted to anonymous, purgatory node)
     *
     * An anonymous node is promoted to feature if in the future:
     *   - it has tags OR
     *   - it is a relation member OR
     *   - it is a "duplicate" OR
     *   - it is an "orphan" -
     *
     * A feature node is demoted to anonymous if in the future:
     *   - it has no tags AND
     *   - it isn't referenced by any relations AND
     *   - it is not a "duplicate" AND
     *   - it is not an "orphan"
     *   - All nodes (potentially) referenced by relations
     */
    private final MutableLongObjectMap<CNode> nodes = new LongObjectHashMap<>();

    /**
     * - All explicitly changed ways (tags, node IDs)
     * - All implicitly changed ways (a way-node moved, was promoted to
     *   feature or demoted to anonymous)
     * - All ways (potentially) referenced by relations
     */
    private final MutableLongObjectMap<CWay> ways = new LongObjectHashMap<>();

    /**
     * - All explicitly changed relations (tags, member IDs, roles)
     * - All implicitly changed relations (member feature's
     *   geometry change caused the relation's bbox or quad to change)
     * - All relations (potentially) referenced by other relations
     */
    private final MutableLongObjectMap<CRelation> relations = new LongObjectHashMap<>();

    /**
     * A mapping of node IDs to past locations. Contains the locations of all
     * modified or deleted nodes, as well as all future nodes referenced
     * by new/modified ways.
     */
    private final MutableLongLongMap locations = new LongLongHashMap();

    /**
     * A mapping of x/y to one of the nodes (non-deterministic) located there
     * in the future. This helps us discover potential duplicate nodes.
     * (If more than one node lives at a location, we promote those that
     * are anonymous nodes to feature status, tagged with geodesk:duplicate)
     * // TODO: also orphans can be duplicate
     */
    private final MutableLongObjectMap<CNode> futureLocationToNode = new LongObjectHashMap<>();
    private final ObjectIntMap<String> globalStringsToCodes;
    private final String[] globalStrings;
    private final Map<String, String> localStrings = new HashMap<>();

    /**
     * A map of feature nodes found during the tile scans that will belong to a
     * way in the future, but are not yet in `nodes` (because they have not been
     * explicitly changed and are not referenced by any explicitly changed
     * relations).
     * We cannot store these nodes in `nodes` because `nodes` must not be
     * mutated concurrently while the tile-scanning is ongoing.
     * Once tile scan is completed, we transfer these additional nodes to
     * `nodes`.
     */
    private final MutableLongObjectMap<CNode> moreNodes = new LongObjectHashMap<>();

    /**
     * A map of ways discovered during the tile scan that have been implicitly
     * changed. Like `moreNodes`, we cannot store these ways in `ways` during
     * the tile scan because we cannot write to `ways` concurrently. Once tile
     * scanning is completed, we transfer these ways to `ways`
     */
    private final MutableLongObjectMap<CWay> implicitlyChangedWays = new LongObjectHashMap<>();

    /**
     * A map of nodes that have been dropped from one or more ways or relations,
     * and which potentially may become orphans, lose their way-node status, or
     * may be demoted to anonymous node.
     */
    private MutableLongObjectMap<CNode> droppedNodes = new LongObjectHashMap<>();

    public ChangeModel(FeatureStore store) throws IOException
    {
        this.store = store;
        globalStringsToCodes = store.stringsToCodes();
        globalStrings = store.codesToStrings();
    }

    public LongObjectMap<CNode> nodes()
    {
        return nodes;
    }

    public LongObjectMap<CWay> ways()
    {
        return ways;
    }

    public LongObjectMap<CRelation> relations()
    {
        return relations;
    }

    public LongLongMap locations()
    {
        return locations;
    }

    private CNode getNode(long id)
    {
        CNode node = nodes.get(id);
        if (node == null)
        {
            node = new CNode(id);
            nodes.put(id, node);
        }
        return node;
    }

    private CWay getWay(long id)
    {
        CWay way = ways.get(id);
        if (way == null)
        {
            way = new CWay(id);
            ways.put(id, way);
        }
        return way;
    }

    private CRelation getRelation(long id)
    {
        CRelation rel = relations.get(id);
        if (rel == null)
        {
            rel = new CRelation(id);
            relations.put(id, rel);
        }
        return rel;
    }

    public CFeature<?> getFeature(long typedId)
    {
        FeatureType type = FeatureId.type(typedId);
        long id = FeatureId.id(typedId);
        if (type == FeatureType.WAY)
        {
            return getWay(id);
        }
        if (type == FeatureType.NODE)
        {
            return getNode(id);
        }
        assert (type == FeatureType.RELATION);
        return getRelation(id);
    }

    /**
     * If the feature with the specified ID already exists in the mappings,
     * returns it. Unlike `getFeature`, no `CFeature` is created if it does
     * not exist already.
     *
     * @param typedId
     * @return
     */
    public CFeature<?> peekFeature(long typedId)
    {
        FeatureType type = FeatureId.type(typedId);
        long id = FeatureId.id(typedId);
        if (type == FeatureType.WAY) return ways.get(id);
        if (type == FeatureType.NODE) return nodes.get(id);
        assert (type == FeatureType.RELATION);
        return relations.get(id);
    }

    // The strings delivered by the XML parser are all unique; looking them
    // up in the global string table and creating unique local strings
    // reduces required memory for strings by 95%
    private String getString(String s)
    {
        int code = globalStringsToCodes.getIfAbsent(s, -1);
        if (code >= 0)
        {
            s = globalStrings[code];
        }
        else
        {
            String exisiting = localStrings.putIfAbsent(s, s);
            if (exisiting != null) s = exisiting;
        }
        return s;
    }

    private String[] getTags(List<String> kv)
    {
        String[] tags = new String[kv.size()];
        for (int i = 0; i < tags.length; i++)
        {
            tags[i] = getString(kv.get(i));
        }
        return tags;
    }

    public void changeNode(int version, long id, List<String> tags, int x, int y)
    {
        getNode(id).change(new CNode.Change(version, 0, getTags(tags), x, y));
    }

    public void deleteNode(int version, long id)
    {
        getNode(id).change(new CNode.Change(version, CFeature.DELETE, null, 0, 0));
    }

    public void changeWay(int version, long id, List<String> tags, LongList nodes)
    {
        getWay(id).change(new CWay.Change(version, 0, getTags(tags), nodes.toArray()));
    }

    public void deleteWay(int version, long id)
    {
        getWay(id).change(new CWay.Change(version, CFeature.DELETE, null, null));
    }

    public void changeRelation(int version, long id, List<String> tags,
        LongList memberList, List<String> roleList)
    {
        CFeature<?>[] members = new CFeature[memberList.size()];
        for (int i = 0; i < members.length; i++)
        {
            members[i] = getFeature(memberList.get(i));
        }
        getRelation(id).change(new CRelation.Change(version, 0, getTags(tags),
            members, getTags(roleList)));
        // TODO: We can use same method for tags and roles for now;
        //  either change approach or use more appropriate method name
    }

    public void deleteRelation(int version, long id)
    {
        getRelation(id).change(new CRelation.Change(version, CFeature.DELETE,
            null, null, null));
    }

    public void setLocation(long nodeId, long xy)
    {
        // TODO: need to re-design, may not be threadsafe
        //  Setting a value for a key which is already contained in the map
        //  should be threadsafe, but we have no control over the implementation
        //  Even if it is safe, it causes "false sharing" (output thread potentially
        //  writes to the same cache line from which the worker threads reads,
        //  which will negatively impact performance). Possible solution:
        //  Use indirection (store values in a separate array; store an index
        //  into this array as the map's value)
        if(!locations.containsKey(nodeId)) return; // TODO
        locations.put(nodeId, xy);
    }

    public void dump()
    {
        /*
        Log.debug("Total strings used: %d", globalStringUseCount + localStringUseCount);
        Log.debug("  %d global", globalStringUseCount);
        Log.debug("  %d local", localStringUseCount);
        Log.debug("    %d unique", localStrings.size());
         */
    }

    // TODO
    private void gatherLocations()
    {
        nodes.forEach(node -> locations.put(node.id(), 0));
        ways.forEach(way ->
        {
            CWay.Change change = way.change;
            if (change != null && change.nodeIds != null)
            {
                for (long nodeId : change.nodeIds) locations.put(nodeId, 0);
            }
        });
    }

    public void prepare()
    {
        gatherLocations();
    }

    private long countMissing(LongObjectMap<? extends CFeature> features)
    {
        long count = 0;
        for (CFeature<?> f : features)
        {
            if (f.tip() < 0 && (f.change==null || (f.change.flags & CFeature.CREATE) == 0))
            {
                // Log.debug("Missing %s", f);
                count++;
            }
        }
        return count;
    }

    private long countMissingLocations()
    {
        return locations.count(xy -> xy == 0);
    }

    public void reportMissing()
    {
        Log.debug("Not found: %,d of %,d nodes", countMissing(nodes), nodes.size());
        Log.debug("Not found: %,d of %,d ways", countMissing(ways), ways.size());
        Log.debug("Not found: %,d of %,d relations", countMissing(relations),relations.size());
        Log.debug("Not found: %,d of %,d locations", countMissingLocations(), locations.size());
    }

    // TODO: consolidate these flags
    protected static final int LAST_FLAG = 1;
    protected static final int FOREIGN_FLAG = 2;
    protected static final int DIFFERENT_ROLE_FLAG = 4;
    protected static final int DIFFERENT_TILE_FLAG = 8;

    // TODO: generalize
    protected void readRelation(CRelation rel)
    {
        int localTip = rel.tip();
        if(localTip <= 0) return;
        int tilePage = store.fetchTile(rel.tip());
        ByteBuffer localBuf = store.bufferOfPage(tilePage);
        int pLocalTile = store.offsetOfPage(tilePage);
        int pRel = pLocalTile + rel.pointer();
        // Log.debug("Reading relation/%d", StoredFeature.id(localBuf, pRel));
        int ppBody = pRel + 12;
        int p = localBuf.getInt(ppBody) + ppBody;
        if(localBuf.getInt(p) != 0)
        {
            // not an empty relation

            int pForeignTile = 0;
            int foreignTip = FeatureConstants.START_TIP;
            ByteBuffer foreignBuf = null;
            int role = 0;
            String roleString = null;

            for (;;)
            {
                int entry = localBuf.getInt(p);
                ByteBuffer featureBuf;
                int memberTip;
                int memberPtr;
                int pMember;
                if ((entry & FOREIGN_FLAG) == 0)
                {
                    // local member
                    featureBuf = localBuf;
                    pMember = (p & 0xffff_fffc) + ((entry >> 3) << 2);
                    memberTip = localTip;
                    memberPtr = pMember - pLocalTile;
                    p += 4;
                }
                else
                {
                    p += 4;
                    if ((entry & DIFFERENT_TILE_FLAG) != 0)
                    {
                        int tipDelta = localBuf.getShort(p);
                        if ((tipDelta & 1) != 0)
                        {
                            // wide TIP delta
                            tipDelta = localBuf.getInt(p);
                            p += 2;
                        }
                        tipDelta >>= 1;     // signed
                        p += 2;
                        foreignTip += tipDelta;
                        int foreignTilePage = store.fetchTile(foreignTip);
                        foreignBuf = store.bufferOfPage(foreignTilePage);
                        pForeignTile = store.offsetOfPage(foreignTilePage);
                    }
                    featureBuf = foreignBuf;
                    memberPtr = (entry >>> 4) << 2;
                    pMember = pForeignTile + memberPtr;
                    memberTip = foreignTip;
                }
                if ((entry & DIFFERENT_ROLE_FLAG) != 0)
                {
                    int rawRole = localBuf.getChar(p);
                    if ((rawRole & 1) != 0)
                    {
                        // common role
                        role = rawRole >>> 1;   // unsigned
                        roleString = null;
                        p += 2;
                    }
                    else
                    {
                        rawRole = localBuf.getInt(p);
                        role = -1;
                        roleString = Bytes.readString(localBuf, p + (rawRole >> 1)); // signed
                        p += 4;
                    }
                }
                long id = StoredFeature.id(featureBuf, pMember);
                // Log.debug("Found member with ID %d", id);
                // TODO: generalize this as StoredFeature.typedId(buf,p)
                long typedId = (id << 2) | ((featureBuf.getInt(pMember) >> 3) & 3);
                CFeature<?> member = peekFeature(typedId);
                if(member != null)
                {
                    member.found(memberTip, memberPtr);
                }
                if((entry & LAST_FLAG) != 0) break;
            }
        }
    }

    public void readRelations()
    {
        for(CRelation rel: relations) readRelation(rel);
    }

    /**
     * Updates the model with the results from a tile scan.
     * Careful: As the tile scan may still be in progress, we cannot safely
     * manipulate any of the mappings.
     *
     * @param featuresFound   an array of typed feature IDs (even positions)
     *                        and their corresponding tips/ptr (odd positions)
     * @param wayNodesFound   an array of node ID arrays that correspond to
     *                        ways in `featuresFound`, or null if the found
     *                        feature is a node or relation
     */
    public void processScanResults(long[] featuresFound, long[][] wayNodesFound)
    {
        for(int i=0; i<featuresFound.length; i += 2)
        {
            long typedId = featuresFound[i];
            if (typedId == 0) break;
            long id = FeatureId.id(typedId);
            long tipAndOffset = featuresFound[i + 1];
            int tip = (int)(tipAndOffset >>> 32);
            int ptr = (int)tipAndOffset;

            switch(FeatureId.type(typedId))
            {
            case NODE:
                processNode(id, tip, ptr);
                break;
            case WAY:
                long[] nodeIds = wayNodesFound[i >>> 1];
                processWay(id, tip, ptr, nodeIds);
                break;
            case RELATION:
                processRelation(id, tip, ptr);
                break;
            }
        }
    }

    private void processNode(long nodeId, int tip, int ptr)
    {
        // TODO
    }

    /**
     * - If one or more of the way's anonymous nodes coincide with a future
     *   location (resulting in the node being promoted to a feature node
     *   tagged as geodesk:duplicate), the way is considered "dirty" since
     *   its waynode table has to be rewritten (even though the way itself
     *   may not have changed).
     *
     * - Likewise, if one or more of the way's anonymous nodes have been
     *   promoted to feature nodes due to being included in a relation
     *   for the first time, the way is considered "dirty"
     *
     * - The contrary case (a feature node being demoted to anonymous node
     *   due to being dropped from its last relation, or because it is no
     *   longer duplicate in the future) *also* results in the way becoming
     *   "dirty" -- however, we cannot handle this situation here // TODO
     *
     * @param wayId
     * @param tip
     * @param ptr
     * @param nodeIds
     */
    private void processWay(long wayId, int tip, int ptr, long[] nodeIds)
    {
        CWay way = ways.get(wayId);
            // don't use getWay() here, because we are not allowed to create
            // insert new CWay objects into `ways` due to concurrent access
            // by the ChangeAnalyzer's worker threads
            // If `way` is null, that means the way is not explicitly changed
            // We then have to determine if the way is implicitly changed
            // (one of its nodes moved, in which case we add it to
            // `implicitlyChangedWays`) or if it is merely a "donor" of
            // anonymous node locations referenced by other (explicitly changed)
            // ways (in this case, we don't need to do anything else)

        boolean geometryChanged = false;
        if(nodeIds != null)
        {
            StoredWay storedWay = store.getWay(tip, ptr);
            StoredWay.XYIterator iter = storedWay.iterXY(0);
            int n = 0;
            while (iter.hasNext())
            {
                long xy = iter.nextXY();
                long nodeId = nodeIds[n++];
                CNode node = nodes.get(nodeId);
                if (node != null && node.change != null)
                {
                    if (!node.change.xyEquals(xy)) geometryChanged = true;
                }
                setLocation(nodeId, xy);
                // TODO: verify that this is threadsafe
                //  We are never adding a key/value entry; only setting the
                //  velue of an existing entry
            }

            // TODO: also get way's feature nodes; if no CNode exists for a node
            //  whose ID is listed in `locations`, we need to create a CNode
            //  (but we cannot add it to `nodes` yet due to concurrency)


            assert n == nodeIds.length ||
                (n == nodeIds.length - 1 && nodeIds[n] == nodeIds[0]) :
                "way/%d has %d coordinate pairs, but nodeIds has %d entries"
                    .formatted(wayId, n, nodeIds.length);
        }

        if(way != null)
        {
            way.found(tip, ptr);
        }
        else
        {
            // way is not explicitly changed
            if(geometryChanged)
            {
                way = new CWay(wayId, new CWay.Change(0,
                    CFeature.CHANGED_GEOMETRY, null, nodeIds));
                implicitlyChangedWays.put(wayId, way);
                    // We are potentially replacing an existing record
                    // if the way is multi-tile and more than one of its
                    // tiles are being scanned, but that's ok
            }
        }
    }

    private void processRelation(long relId, int tip, int ptr)
    {
        // TODO
    }
}
