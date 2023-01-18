/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SString;

public interface StringSource
{
    String globalString(int code);
    int globalStringCode(String str);
    String localString(int code);
    int localStringCode(String str);
    int localStringCodeFromPtr(int p);
    SString localStringStruct(int code);
    void useLocalStringAsKey(int code);
}
