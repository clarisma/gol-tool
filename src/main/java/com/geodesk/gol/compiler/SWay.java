/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

// TODO: could get rid of coords, cut memory use
//  could null out nodeIds once SWay is built

import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.soar.StructOutputStream;
import com.clarisma.common.util.Log;
import com.geodesk.feature.FeatureId;
import com.geodesk.core.Tile;
import com.geodesk.core.TileQuad;
import com.geodesk.core.XY;
import com.geodesk.feature.store.FeatureConstants;
import com.geodesk.core.Box;
import com.geodesk.geom.Bounds;
import com.geodesk.geom.Coordinates;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SWay extends SFeature
{
    private Bounds bounds;
    private SWayBody body;
    private long[] nodeIds;
    private SNode[] featureNodes;


    public SWay(long id)
    {
        super(id);
        flags |= 1 << FEATURE_TYPE_BITS;
        setSize(32);
        setAnchor(16);
    }

    public Bounds bounds()
    {
        return bounds;
    }

    public void setBounds(Bounds bounds)
    {
        this.bounds = bounds;
    }

    @Override public SFeatureBody body()
    {
        return body;
    }

    public void setNodeIds(long[] nodeIds)
    {
        this.nodeIds = nodeIds;
    }

    public long[] nodeIds()
    {
        return nodeIds;
    }

    private void calculateBounds(int[] coords, int zoom)
    {
        Box bbox = new Box();
        int quad = 0;

        int prevX = 0;
        int prevY = 0;

        for(int i=0; i<coords.length; i+=2)
        {
            int x = coords[i];
            int y = coords[i+1];
            assert !(x==0 && y==0);
            bbox.expandToInclude(x,y);
            if(i > 0)
            {
                quad = TileQuad.addLineSegment(quad, prevX, prevY, x, y, zoom);
            }
            prevX = x;
            prevY = y;
        }
        // TODO: oversize
        bounds = bbox;
        // zoom level cannot be higher than the indexed level, zoom out if necessary
        assert quad != -1;
        tileQuad = TileQuad.zoomedOut(quad, zoom);
    }


    public class SWayBody extends SFeatureBody
    {
        private final byte[] encodedCoords;
        private int[] tipDeltas;

        private static final int LOCAL_NODE = Integer.MAX_VALUE;
        private static final int SAME_TIP = Integer.MIN_VALUE;

        // TODO: decide whether to de-duplicate feature nodes
        //  Most common case: closed ring starts and ends with feature node

        public SWayBody(int[] coords, FeatureTile ft)
        {

            /*
            if(featureNodes != null)
            {
                featureNodeCount = featureNodes.length;
                if(featureNodeCount > 0 && isArea())
                {
                    SNode firstNode = featureNodes[0];
                    if(firstNode.x() == coords[0] &&
                        firstNode.y() == coords[1])
                    {
                        assert(firstNode == featureNodes[featureNodes.length-1]);
                        featureNodeCount--;
                    }
                }
            }
             */

            int featureNodeSize = featureNodes.length * 4;
            int prevTip = -1;
            for(int i=0; i<featureNodes.length; i++)
            {
                SNode node = featureNodes[i];
                if(node.isForeign())
                {
                    if(tipDeltas == null)
                    {
                        tipDeltas = new int[featureNodes.length];
                        for(int i2=0; i2<featureNodes.length; i2++) tipDeltas[i2] = LOCAL_NODE;
                    }
                    int tip = node.getTip(ft);
                    if(tip == prevTip)
                    {
                        tipDeltas[i] = SAME_TIP;
                    }
                    else
                    {
                        // The initial TIP delta must be encoded, even if it is 0;
                        // that's why we don't set the starting prevTip to START_TIP

                        if(prevTip < 0) prevTip = FeatureConstants.START_TIP;
                        int tipDelta = tip - prevTip;
                        featureNodeSize += FeatureTile.isWideTipDelta(tipDelta) ? 4 : 2;
                        tipDeltas[i] = tipDelta;
                        prevTip = tip;
                    }
                }
            }

            // We base the origin of the delta-encoded coordinates off the lower left-hand
            // corner of the Way's bounding box. In many cases, this allows us to encode
            // at least one coordinate using a single byte, since most ways start at a
            // bounding box edge
            int prevX = bounds.minX();
            int prevY = bounds.minY();
            int end = coords.length - (isArea() ? 2 : 0);
            // We skip the final coordinate if Way is an Area, since the
            // coordinate is the same as the first
            PbfOutputStream out = new PbfOutputStream();
            out.writeVarint(end >> 1);  // number of coordinate pairs
            for(int i=0; i<end; i+=2)
            {
                // Delta-encoded X/Y pairs
                int x = coords[i];
                int y = coords[i+1];
                out.writeSignedVarint(x - prevX);
                out.writeSignedVarint(y - prevY);
                prevX = x;
                prevY = y;
            }

            encodedCoords = out.toByteArray();

            int preArea = featureNodeSize + (isRelationMember() ? 4 : 0);
            setSize(encodedCoords.length + preArea);
            setAnchor(preArea);
            setAlignment(isRelationMember() || preArea > 0 ? 1 : 0);
        }


        public void writeTo(StructOutputStream out) throws IOException
        {
            if(tipDeltas == null)
            {
                int lastFlag = 1;
                for (int i = featureNodes.length - 1; i >= 0; i--)
                {
                    out.writeTaggedPointer(featureNodes[i], 2, lastFlag);
                    lastFlag = 0;
                }
            }
            else
            {
                int[] tips = new int[tipDeltas.length];
                int prevTip = FeatureConstants.START_TIP;
                for (int i = 0; i < featureNodes.length; i++)
                {
                    int tipDelta = tipDeltas[i];
                    if (tipDelta == LOCAL_NODE) continue;
                    if (tipDelta == SAME_TIP) tipDelta = 0;
                    prevTip += tipDelta;
                    tips[i] = prevTip;
                }

                int lastFlag = 1;
                for (int i = featureNodes.length - 1; i >= 0; i--)
                {
                    int flags = lastFlag;
                    lastFlag = 0;
                    SNode node = featureNodes[i];
                    int tipDelta = tipDeltas[i];
                    if(tipDelta == LOCAL_NODE)
                    {
                        out.writeTaggedPointer(node, 2, flags);
                    }
                    else
                    {
                        flags |= 2; // foreign_node flag
                        if(tipDelta != SAME_TIP)
                        {
                            flags |= 8; // different_tile flag

                            if (FeatureTile.isWideTipDelta(tipDelta))
                            {
                                out.writeShort(tipDelta >> 15);
                                out.writeShort((tipDelta >> 1) | 1);
                            }
                            else
                            {
                                out.writeShort(tipDelta << 1);
                            }
                        }
                        out.writeForeignPointer(tips[i], FeatureId.ofNode(node.id()), 2, flags);
                            // TODO: pointer occupies top 28 bits, but we only
                            //  shift by 2 because it is 4-byte aligned
                            // TODO: unify handling of pointers, this is too confusing
                    }
                }
            }
            if(isRelationMember()) out.writePointer(relations());
            out.write(encodedCoords);
        }

        public String toString()
        {
            return String.format("BODY of way/%d with %d nodes%s", id,
                nodeIds.length, featureNodes.length == 0 ? "" :
                    String.format(" (%d feature nodes)", featureNodes.length));
        }
    }

    private static final SNode[] EMPTY_WAY_NODES = new SNode[0];

    @Override public void build(FeatureTile ft)
    {
        if(nodeIds != null)
        {
            // We do this for local ways as well as ghost ways

            int missingCoords = 0;
            int[] coords = new int[nodeIds.length * 2];
            List<SNode> wayNodes = null;
            for (int i = 0; i < nodeIds.length; i++)
            {
                int x, y;
                long nodeId = nodeIds[i];
                SNode node = ft.peekNode(nodeId);
                if (node != null)
                {
                    assert node.isBuilt();
                    if (wayNodes == null) wayNodes = new ArrayList<>();
                    wayNodes.add(node);
                    x = node.x();
                    y = node.y();
                    // TODO: check these coordinates, they will be 0/0
                    //  if the node is missing (a blank SNode is created
                    //  if the node is part of a relation)
                    node.setFlag(WAYNODE_FLAG);
                }
                else
                {
                    long xy = ft.getCoordinates(nodeId);
                    // assert xy != 0: String.format("node/%d has no coordinates", nodeId);

                    if (xy == 0)
                    {
                        missingCoords++;
                    }
                    x = XY.x(xy);
                    y = XY.y(xy);
                }
                coords[i * 2] = x;
                coords[i * 2 + 1] = y;

                // TODO: check: deal with missing coordinates
            }
            if (missingCoords > 0)
            {
                if (Coordinates.fixMissing(coords, 0, 0))
                {
                    Log.debug("Patched %d missing coordinates in way/%d", missingCoords, id);
                }
                else
                {
                    Log.error("Unable to fix %d missing coordinates for way/%d", missingCoords, id);
                }
            }
            setWayNodes(wayNodes);

            if (isLocal())
            {
                calculateBounds(coords, Tile.zoom(ft.tile()));
                // TODO: move following into calculateBounds to mirror approach in SRelation?
                if (!TileQuad.containsTile(tileQuad, ft.tile()))
                {
                    markAsForeign();
                }
                else
                {
                    normalize(ft);
                    if (Coordinates.isClosedRing(coords))
                    {
                        if (AreaClassifier.isArea(tags)) setFlag(AREA_FLAG);
                    }
                    calculateMultitileFlags(ft);
                }
                if (!isForeign()) body = new SWayBody(coords, ft);
            }
        }
        setFlag(BUILT_FLAG);
    }

    private void setWayNodes(List<SNode> wayNodes)
    {
        if (wayNodes != null)
        {
            featureNodes = wayNodes.toArray(EMPTY_WAY_NODES);
            setFlag(WAYNODE_FLAG);
        }
        else
        {
            featureNodes = EMPTY_WAY_NODES;
        }
    }

    private static final int[] MISSING_COORDS = new int[4]; // all zero

    @Override public void buildInvalid(FeatureTile ft)
    {
        super.buildInvalid(ft);
        int[] coords;
        List<SNode> wayNodes = null;
        if(nodeIds != null)
        {
            // If the Way has node ids, this means it is in the Purgatory
            // because all of its nodes are missing
            // The only case in which it will have feature nodes are if
            // these missing nodes are referenced by any relations
            // But there is no real reason to resolve them, we could skip
            // this part altogether

            for (int i = 0; i < nodeIds.length; i++)
            {
                SNode node = ft.peekNode(nodeIds[i]);
                if (node != null)
                {
                    assert node.isBuilt();
                    if (wayNodes == null) wayNodes = new ArrayList<>();
                    wayNodes.add(node);
                }
            }
            coords = new int[nodeIds.length * 2];
        }
        else
        {
            coords = MISSING_COORDS;
        }
        setWayNodes(wayNodes);
        bounds = MISSING_BOUNDS;
        body = new SWayBody(coords, ft);
        // setFlag(BUILT_FLAG);   // already done by SFeature.buildInvalid()
    }

    public void calculateUsage()
    {
        if(isForeign()) return;
        super.calculateUsage();
        for(SNode node: featureNodes)
        {
            node.addUsage(1, usage() * UsageScores.WAYNODE_RATIO);
        }
    }

    @Override public void export(FeatureTile ft)
    {
        /*
        if(id == 234844556)
        {
            Compiler.log.debug("Exporting {} way/{} in {}",
                isLocal() ? "local" : "foreign",
                id, Tile.toString(ft.tile()));
        }
         */

        if(isForeign())
        {
            // For a ghost way, we export all local nodes to the way's tiles

            if(featureNodes != null)
            {
                TileQuad.forEach(tileQuad, tile -> ft.exportNodes(tile, featureNodes));
            }
        }
        else
        {
            // For a local multi-tile way, we need to export all local nodes from this
            // tile to the way's other tiles

            if(TileQuad.tileCount(tileQuad) > 1)
            {
                int exportQuad = TileQuad.subtractTile(tileQuad, ft.tile());
                TileQuad.forEach(exportQuad, tile -> ft.exportNodes(tile, featureNodes));
            }
            super.export(ft);
        }
    }
}
