/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;

import java.io.IOException;

import static com.geodesk.feature.store.FeatureFlags.*;

public class TWay extends TFeature2D<TWay.Body>
{
    public TWay(long id)
    {
        super(id);
        flags |= 1 << FEATURE_TYPE_BITS;
    }

    class Body extends Struct
    {
        private final byte[] encodedCoords = null; // TODO
        private int[] tipDeltas;

        @Override public void writeTo(StructOutputStream out) throws IOException
        {
            // TODO
        }
    }
}
