/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.math.Decimal;
import com.geodesk.feature.store.TagValues;

public class TagTableBuilder
{
    private final static long LOCAL_KEY = 0x8000_0000L;
        // has to be long, because int -> long conversion
        // sign-extends, setting the upper 32 bits to 1

    public static final long[] EMPTY_TABLE = new long[0];

    public static long[] fromStrings(String[] kv, StringSource strings)
    {
        long[] tags = new long[kv.length / 2];

        for(int i=0, i2=0; i<tags.length; i++, i2+=2)
        {
            String key = kv[i2];
            String value = kv[i2+1];
            long tag;
            int k = strings.globalStringCode(key);
            if(k >= 0 && k <= TagValues.MAX_COMMON_KEY)
            {
                // global key
                tag = k << 2;
            }
            else
            {
                // local key
                int keyCode = strings.localKeyStringCode(key);
                    // ensure that string will be 4-byte aligned
                tag = (keyCode << 2) | LOCAL_KEY;
            }

            int v = strings.globalStringCode(value);
            if(v >= 0)
            {
                tag |= ((long)v << 32) | 1;  // global string
            }
            else
            {
                boolean numberValue = false;
                long d = Decimal.parse(value, true);
					// strict=true (formatting the decimal value must
					// produce the same string)
                if(d != Decimal.INVALID)
                {
                    int scale = Decimal.scale(d);
			        if(scale <= 3)
                    {
                        long mantissa = Decimal.mantissa(d);
            			if(mantissa >= TagValues.MIN_NUMBER &&
                            mantissa > TagValues.MAX_WIDE_NUMBER)
                        {
                            if(scale == 0 && mantissa <= TagValues.MAX_NARROW_NUMBER)
                            {
                                // narrow number
                                tag |= (mantissa - TagValues.MIN_NUMBER) << 32;
                            }
                            else
                            {
                                // wide number
                                long num = ((mantissa - TagValues.MIN_NUMBER) << 2) | scale;
                                tag |= (num << 32) | 2;
                            }
                            numberValue = true;
                        }
                    }
                }
                if(!numberValue)
                {
                    // wide (local) string
                    tag |= ((long) strings.localStringCode(value) << 32) | 3;
                }
            }
            tags[i] = tag;
        }
        sortTags(tags, strings);
        return tags;
    }

    /**
     * Sorts tags:
     * - global keys first, ascending key code
     * - then local keys, ascending key string
     *
     * @param tags
     * @param strings
     */
    private static void sortTags(long[] tags, StringSource strings)
    {
        for(int i=1; i<tags.length; i++)
        {
            long tag = tags[i];
            int j = i - 1;
            while (j >= 0)
            {
                long other = tags[j];
                int localKeyFlag = (int)tag >>> 31;
                int otherLocalKeyFlag = (int)other >>> 31;
                int compare = localKeyFlag - otherLocalKeyFlag;
                if(compare > 0) break;
                if(compare == 0)
                {
                    int key = (int)tag >>> 2;
                    int otherKey = (int)other >>> 2;
                    if(localKeyFlag == 0)
                    {
                        compare = key - otherKey;
                    }
                    else
                    {
                        compare = strings.localString(key & 0x1fff_ffff).compareTo(
                            strings.localString(otherKey & 0x1fff_ffff));
                    }
                    if(compare >= 0) break;     // TODO: duplicate keys?
                }
                tags[j + 1] = tags[j];
                j = j - 1;
            }
            tags[j + 1] = tag;
        }
    }

}
