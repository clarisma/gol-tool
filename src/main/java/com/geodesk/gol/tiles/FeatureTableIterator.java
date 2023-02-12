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
    private int shift;
    private int stepAfter;
    private int stepAfterTileChange;
    private int stepAfterTip;
    private ByteBuffer buf;
    private int pos;
    private int entry;
    private int tip = FeatureConstants.START_TIP;
    private int role;
    private ByteBuffer foreignBuf;
    private int pForeignTile;
}
