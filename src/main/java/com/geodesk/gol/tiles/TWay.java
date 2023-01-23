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
import com.geodesk.feature.match.TypeBits;

import java.nio.ByteBuffer;

import static com.geodesk.feature.store.FeatureFlags.*;

public class TWay extends TFeature2D<TWay.Body>
{
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

    class Body extends Struct
    {
        private final byte[] encodedCoords;
        private final int[] tips;

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
                int pBefore = reader.readTable(p, 2, -1,
                    TypeBits.NODES & TypeBits.WAYNODE_FLAGGED, false);
                featureNodes = reader.getCurrentNodes();
                tips = reader.getCurrentTips();
                bodySize += p - pBefore;
                reader.resetTables();
                setAlignment(1);   // 2-byte (1 << 1)
            }
            else
            {
                tips = null;
            }
            setSize(bodySize);
            int anchor = bodySize - encodedCoords.length;
            setAnchor(anchor);
            setLocation(pBody - anchor);
        }

        @Override public void write(StructWriter out)
        {
            // TODO
        }
    }
}
