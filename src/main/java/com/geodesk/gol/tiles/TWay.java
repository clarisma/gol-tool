/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;
import com.clarisma.common.pbf.PbfDecoder;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;

import java.io.IOException;
import java.nio.ByteBuffer;

import static com.geodesk.feature.store.FeatureFlags.*;

public class TWay extends TFeature2D<TWay.Body>
{
    public TWay(long id)
    {
        super(id);
        flags |= 1 << FEATURE_TYPE_BITS;
    }

    public void readBody(TileReader reader)
    {
        ByteBuffer buf = reader.buf();
        int ppBody = location() + 28;
        int pBody = buf.getInt(ppBody) + ppBody;
        reader.checkPointer(pBody);

        PbfDecoder decoder = new PbfDecoder(buf, pBody);
        int nodeCount = (int)decoder.readVarint();
        while(nodeCount > 0)
        {
            decoder.readVarint();
            decoder.readVarint();
            nodeCount--;
        }
        int coordsLen = buf.position() - pBody;
        byte[] coords = new byte[coordsLen];
        buf.get(pBody, coords);
        body = new Body(pBody, coords);
    }

    class Body extends Struct
    {
        private final byte[] encodedCoords;
        private int[] tipDeltas;

        public Body(int p, byte[] encodedCoords)
        {
            setLocation(p);
            setSize(encodedCoords.length);
            // TODO: anchor, size
            this.encodedCoords = encodedCoords;
        }

        @Override public void writeTo(StructOutputStream out) throws IOException
        {
            // TODO
        }
    }
}
