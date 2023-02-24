/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

public class InvalidTileException extends RuntimeException
{
    private final int location;

    public InvalidTileException(String msg)
    {
        super(msg);
        location = -1;
    }

    public InvalidTileException(int location, String msg)
    {
        super("%08X:%s".formatted(location, msg));
        this.location = location;
    }
}
