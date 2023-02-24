/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;
import com.clarisma.common.pbf.PbfDecoder;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructWriter;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.match.TypeBits;
import com.geodesk.feature.store.FeatureConstants;
import com.geodesk.feature.store.Tip;

import java.nio.ByteBuffer;

import static com.geodesk.feature.store.FeatureFlags.*;

public class TWay extends TFeature2D<TWay.Body>
{
    /**
     * The full list of this way's node IDs, or `null` if the way has not
     * been changed.
     */
    private long[] nodeIds;

    /**
     * This way's feature nodes, in occurrence order.
     */
    private TNode[] featureNodes;

    public TWay(long id)
    {
        super(id);
        flags |= 1 << FEATURE_TYPE_BITS;
    }

    @Override public void readBody(TileReader reader)
    {
        body = new Body(reader, readBodyPointer(reader));
    }

    private static final TNode[] EMPTY_NODES = new TNode[0];

    class Body extends Struct
    {
        private final byte[] encodedCoords;
        private final int[] tips;       // not needed; store in foreign TNode

        public Body(TileReader reader, int pBody)
        {
            ByteBuffer buf = reader.buf();
            PbfDecoder decoder = new PbfDecoder(buf, pBody);
            int nodeCount = (int) decoder.readVarint();
            while (nodeCount > 0)
            {
                decoder.readVarint();
                decoder.readVarint();
                nodeCount--;
            }
            int bodySize = decoder.pos() - pBody;
            encodedCoords = new byte[bodySize];
            buf.get(pBody, encodedCoords);
            int p = pBody - 4;
            if (isRelationMember())
            {
                relations = reader.readRelationTableIndirect(p);
                p -= 4;
                bodySize += 4;
                setAlignment(1);   // 2-byte (1 << 1)
            }
            if ((flags & WAYNODE_FLAG) != 0)
            {
                int pBefore = reader.readFeatureTable(p, 2, -1,
                    TypeBits.NODES & TypeBits.WAYNODE_FLAGGED, false);
                featureNodes = reader.getCurrentNodes();
                tips = reader.getCurrentTips();
                bodySize += p - pBefore;
                reader.resetTables();
                setAlignment(1);   // 2-byte (1 << 1)
            }
            else
            {
                featureNodes = EMPTY_NODES;
                tips = null;
            }
            setSize(bodySize);
            int anchor = bodySize - encodedCoords.length;
            setAnchor(anchor);
            setLocation(pBody - anchor);
        }

        @Override public void write(StructWriter out)
        {
            int prevTip = Integer.MIN_VALUE;
            int truePrevTip = FeatureConstants.START_TIP;

            // Remember: first foreign ref must always indicate tile change even
            // if its tip is START_TIP

            int lastFlag = TileReader.LAST_FLAG;
            for(int i=featureNodes.length-1; i>=0; i--)
            {
                TNode node = featureNodes[i];
                int tip = tips[i];
                int flags = lastFlag;
                if (tip != TileReader.LOCAL_TILE)
                {
                    assert node.isForeign();
                    flags |= TileReader.FOREIGN_FLAG;
                    if (tip != prevTip)
                    {
                        flags |= TileReader.DIFFERENT_TILE_FLAG;
                        int tipDelta = tip - truePrevTip;
                        if (Tip.isWideTipDelta(tipDelta))
                        {
                            out.writeShort((short)(tipDelta >> 15));
                            out.writeShort((short)((tipDelta << 1) | 1));
                        }
                        else
                        {
                            out.writeShort((short) (tipDelta << 1));
                        }
                        prevTip = tip;
                        truePrevTip = tip;
                    }
                    long typedId = FeatureId.ofNode(node.id());
                    out.writeForeignPointer(tip, typedId, 2, flags);
                    // TODO: pointer occupies top 28 bits, but we only
                    //  shift by 2 because it is 4-byte aligned
                    // TODO: unify handling of pointers, this is too confusing
                }
                else
                {
                    assert !node.isForeign();
                    out.writeTaggedPointer(node, 2, flags);
                }
                lastFlag = 0;
            }
            if(isRelationMember()) out.writePointer(relations);
            out.writeBytes(encodedCoords);
        }
    }
}
