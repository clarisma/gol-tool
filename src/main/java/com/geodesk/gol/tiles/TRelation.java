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

import static com.geodesk.feature.store.FeatureFlags.FEATURE_TYPE_BITS;

public class TRelation extends TFeature2D<TRelation.Body>
{
    private TFeature[] members;
    private int[] roles;
    private int[] tipDeltas;

    public TRelation(long id)
    {
        super(id);
        flags |= 2 << FEATURE_TYPE_BITS;
    }

    class Body extends Struct
    {
        @Override public void writeTo(StructOutputStream out) throws IOException
        {
            // TODO
        }
    }
}

