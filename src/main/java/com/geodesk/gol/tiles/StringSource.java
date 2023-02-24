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
    /**
     * Returns the string for the given global-string code.
     *
     * TODO: what happens if not found?
     *
     * @param code      must be >= 0 and < 2^16
     * @return          the corresponding string
     */
    String globalString(int code);

    /**
     * Returns the global-string code for a given string.
     *
     * @param str   the string
     * @return      a code >= 0 and < 2^16, or -1 if the string is
     *              not in the global-string table
     */
    int globalStringCode(String str);

    /**
     * Returns the string for the given local-string code.
     *
     * @param code  must be >= 0 and < 2^31
     * @return      the corresponding string
     */
    String localString(int code);

    /**
     * Returns the local-string code for the given string, assigning one if
     * the string is not yet part of the local-string table.
     *
     * @param str   a string
     * @return      its local-string code (>= 0 and < 2^31)
     */
    int localStringCode(String str);

    /**
     * Returns the local-string code for the given string, which is intended
     * to be used as a key in a tag table (If the string is stored in a
     * Structured Object Archive, its alignment may be restricted as a result).
     * A local-string code will be assigned if the string is not yet part of
     * the local-string table.
     *
     * @param str   a string
     * @return      its local-string code (>= 0 and < 2^31)
     */
    int localKeyStringCode(String str);


    /*


    int localStringCode(String str);
    int localStringCodeFromPtr(int p);
    SString localStringStruct(int code);
    void useLocalStringAsKey(int code);
     */
}
