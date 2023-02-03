/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.math.Decimal;
import com.clarisma.common.util.Log;
import com.geodesk.feature.store.TagValues;
import org.eclipse.collections.api.list.primitive.MutableLongList;

public class TagTableBuilder
{
    private final static long LOCAL_KEY = 0x8000_0000L;
        // has to be long, because int -> long conversion
        // sign-extends, setting the upper 32 bits to 1

    public static final long[] EMPTY_TABLE = new long[0];
    private static long EMPTY_TAG = 0x8000L;

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
                            mantissa <= TagValues.MAX_WIDE_NUMBER)
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
     * Compares two key values, ordering them as follows:
     * - global keys before local keys
     * - global keys ordered by key code
     * - local keys ordered alphabetically
     *
     * @param a         the first tagged key code
     * @param b         the second tagged key code
     * @param strings   a StringSource that implements localString() so we
     *                  can obtain the string of a local-key code
     * @return  -1 if `a` comes before `b`; 1 if `b` comes before `a`;
     *          or 0 if they are equal
     */
    private static int compareKeys(int a, int b, StringSource strings)
    {
        if(((a & b) >>> 31) == 0)
        {
            // If one of the keys is a local key, by shifting the marker bit (31)
            // downwards, the local-key code number with flag will always be
            // greater than the highest global-key code, hence global keys
            // will be ordered ahead of locals
            // global keys themselves are sorted by key number
            return (a >>> 2) - (b >>> 2);
        }
        else
        {
            // both are local keys: We need to compare the actual strings

            return strings.localString((a >>> 2) & 0x1fff_ffff).compareTo(
                strings.localString((b >>> 2) & 0x1fff_ffff));
        }
    }

    /**
     * Sorts a tag table in place.
     *
     * - global keys first, ascending key code
     * - then local keys, ascending key string
     *
     * @param tags      the tags to be sorted
     * @param strings   a StringSource that implements localString() so we
     *                  can obtain the string of a local-key code
     */
    public static void sortTags(long[] tags, StringSource strings)
    {
        for(int i=1; i<tags.length; i++)
        {
            long tag = tags[i];
            int j = i - 1;
            while (j >= 0)
            {
                long other = tags[j];
                if(compareKeys((int)tag, (int)other, strings) >= 0) break;
                    // TODO: Do we need to deal with duplicate keys?
                tags[j + 1] = tags[j];
                j = j - 1;
            }
            tags[j + 1] = tag;
        }
    }

    /**
     * Returns a tag table that contains the differences between two sets
     * of tags. New, changed and deleted tags are added to `diff` (which must
     * be empty prior to calling this method).
     * Tags to be deleted have a global string value of 0 (empty string)
     *
     * @param oldTags   the old tags
     * @param newTags   the new tags
     * @param strings   a StringSource that provides localString()
     * @param diff      a list of differences between the old and new tags
     */
    public static void diffTags(long[] oldTags, long[] newTags, StringSource strings, MutableLongList diff)
    {
        int iOld = 0;
        int iNew = 0;

        for(;;)
        {
            if(iOld == oldTags.length)
            {
                while(iNew < newTags.length) diff.add(newTags[iNew++]);
                return;
            }
            if(iNew == newTags.length)
            {
                while(iOld < oldTags.length)
                {
                    diff.add((oldTags[iOld++] & 0xffff_fffcL) | 1);
                }
                return;
            }
            long oldTag = oldTags[iOld];
            long newTag = newTags[iNew];
            if(oldTag == newTag)
            {
                iOld++;
                iNew++;
                continue;
            }
            int compare = compareKeys((int)oldTag, (int)newTag, strings);
            if(compare >= 0)
            {
                // tag is new or different: add new tag
                diff.add(newTag);
                iNew++;
                iOld += compare==0 ? 1 : 0;
            }
            else
            {
                // delete old tag by setting it to global string value 0
                // (empty string)
                diff.add((oldTag & 0xffff_fffcL) | 1);
                    // important: mask must be "L" or we risk sign extension
                    // (int to long), which turns the upper 32 bits to 1s
                iOld++;
            }
        }
    }

    /**
     * Merges old tags and diffs to create new tags, which are added to `merged`
     * (which must be empty prior to calling this method).
     *
     * @param oldTags   the old tags
     * @param diffTags  tags to be added, changed or deleted
     * @param strings   a StringSource that provides localString()
     * @param merged    the resulting tag table
     */
    public static void mergeTags(long[] oldTags, long[] diffTags, StringSource strings, MutableLongList merged)
    {
        int iOld = 0;
        int iDiff = 0;

        for(;;)
        {
            if(iOld == oldTags.length)
            {
                while(iDiff < diffTags.length) merged.add(diffTags[iDiff++]);
                return;
            }
            if(iDiff == diffTags.length)
            {
                while(iOld < oldTags.length) merged.add(oldTags[iOld++]);
                return;
            }

            long oldTag = oldTags[iOld];
            long diffTag = diffTags[iDiff];
            int compare = compareKeys((int)oldTag, (int)diffTag, strings);
            if(compare >= 0)
            {
                if((diffTag & 0xffff_ffff_0000_0003L) != 1)
                {
                    // Add tag unless its value is global-string 0 (empty string),
                    // which indicates that we should delete (omit) the old tag
                    merged.add(diffTag);
                }
                iDiff++;
                iOld += compare==0 ? 1 : 0;
            }
            else
            {
                merged.add(oldTag);
                iOld++;
            }
        }
    }

    public static String keyToString(int k, StringSource strings)
    {
        if(k >= 0) return strings.globalString(k >>> 2);
        return strings.localString((k >>> 2) & 0x1fff_ffff);
    }

    public static String valueToString(long tag, StringSource strings)
    {
        int type = (int)tag & 3;
        int v = (int)(tag >>> 32);
        if(type == 1)
        {
            // global string
            return strings.globalString(v);
        }
        if(type == 3)
        {
            // local string
            return strings.localString(v);
        }
        if(type == 0)
        {
            // narrow number
            return Integer.toString(v + TagValues.MIN_NUMBER);
        }
        // wide number
        int mantissa = (v >>> 2) + TagValues.MIN_NUMBER;
		int scale = v & 3;
		if (scale == 0) return Integer.toString(mantissa);
		return Decimal.toString(Decimal.of(mantissa, scale));
    }


    public static void dump(long[] tags, StringSource strings)
    {
        for(long tag: tags)
        {
            int type = (int)tag & 3;
            Log.debug("  %s = %s [%c%c]",
                keyToString((int)tag, strings),
                valueToString(tag, strings),
                (type & 2) == 0 ? 'N' : 'W',
                (type & 1) == 0 ? 'N' : 'S');
        }
    }

}
