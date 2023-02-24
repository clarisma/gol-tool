/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.geodesk.feature.store.FeatureConstants;

import java.nio.ByteBuffer;

public class FeatureTableIterator
{
    private final int shift;
    private final int stepAfter;
    private final int stepAfterTileChange;
    private final boolean roles;
    private ByteBuffer buf;
    private int pos;
    private int entry;
    private int tip = FeatureConstants.START_TIP;
    private int role;
    private ByteBuffer foreignBuf;
    private int pForeignTile;


    public FeatureTableIterator(int shift, int direction, boolean roles)
    {
        this.shift = shift;
        this.roles = roles;
        stepAfter = 4 * direction;

        // Step to take after foreign feature in a different tile:
        // +4 if forward, -2 if backward
        stepAfterTileChange = 3 * direction + 1;

    }

    public boolean next()
    {
        return false; // TODO
    }
}
