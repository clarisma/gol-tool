/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.math.Decimal;
import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.SharedStruct;
import com.clarisma.common.soar.StructOutputStream;
import com.clarisma.common.soar.StructWriter;
import com.clarisma.common.util.Log;
import com.geodesk.feature.store.FeatureConstants;
import com.geodesk.feature.store.TagValues;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Bit 0:       0 = number, 1 = string
 * Bit 1:       0 = narrow, 1 = wide
 * Bits 2-30:   global/local string code
 * Bit 31:      0 = global-string key code, 1 = local-string key code
 * Bits 32-63:  If numeric or global-string value: encoded value
 *              If local-string value: local-string value code
 *
 * TODO: empty table marker should not be stored
 *   empty marker must also be written if table consists solely
 *   of local-key tags
 */
public class TTagTable extends SharedStruct implements Comparable<TTagTable>
{
    private final TTile tile;
    private final int hashCode;
    private final long[] tags;

    private final static long LOCAL_KEY = 0x8000_0000L;
        // has to be long, because int -> long conversion
        // sign-extends, setting the upper 32 bits to 1

    private static long EMPTY_TAG = 0x8000L;
    // TODO: Change TagValues.EMPTY_TABLE_MARKER to match this patter

    // TODO: reading won't work because of broken empty tag code!

    public TTagTable(TileReader reader, int pTable, int uncommonTagsFlag)
    {
        this.tile = reader.tile();
        ByteBuffer buf = reader.buf();
        setAlignment(1);    // 2-byte aligned (1 << 1)
        int uncommonTagCount = 0;
        int size;
        int p = pTable;

        // Do an initial scan of the stored tag table to count the tags
        // and get the size of the structure

        if(uncommonTagsFlag != 0)
        {
            // Scan uncommon (local-key) tags first
            for (;;)
            {
                p -= 4;
                int k = buf.getInt(p);
                p -= (k & 2) + 2;
                uncommonTagCount++;
                if((k & 4) != 0) break;     // bit 2 is last-item flag
            }
            size = pTable - p;
            setAnchor(size);
            setLocation(p);
            p = pTable;
        }
        else
        {
            setLocation(pTable);
            size = 0;
        }
        int tagCount = uncommonTagCount;
        for(;;)
        {
            int k = buf.getInt(p);
            int tagSize = (k & 2) + 4;
            tagCount++;
            size += tagSize;
            p += tagSize;
            if((k & 0x8000) != 0) break;
        }
        int globalKeyTagCount = tagCount - uncommonTagCount;

        // TODO: check here if global-tag count is 1 and
        //  the only global tag is the table-end marker
        //  adjust size if we switch to a "proper" end marker
        //  that respects the wide-value flag

        tags = new long[tagCount];
        p = pTable;
        int origin = pTable & 0xffff_fffc;
        for(int i= globalKeyTagCount; i < tagCount; i++)
        {
            p -= 6;
            long tag = buf.getLong(p);
			int rawPointer = (int) (tag >> 16);
			int flags = rawPointer & 7;
			int pKey = ((rawPointer ^ flags) >> 1) + origin;    // preserve sign
            assert (pKey & 3) == 0; // must be 4-byte aligned
			int keyCode = reader.readString(pKey);
            tile.useLocalStringAsKey(keyCode);
                // ensure that key is 4-byte aligned (when we're reading, this
                // will always be the case, but the alignment information is
                // not preserved, so we need to mark the struct explicitly)
            if((flags & 2) == 0)
            {
                // narrow value
                tag = (tag & 0xffff) << 32;
            }
            else
            {
                // wide value
                p -= 2;
                int val = buf.getInt(p);
                if((flags & 1) != 0)
                {
                    // wide string
                    val = reader.readString(p + val);
                }
                tag = (long)val << 32;
            }
            tag |= (keyCode << 2) | (flags & 3) | LOCAL_KEY;
            tags[i] = tag;

            assert tag !=0; // TODO: check!!!!
        }

        p = pTable;
        for(int i=0; i<globalKeyTagCount; i++)
        {
            int k = buf.getInt(p);
            if(k == TagValues.EMPTY_TABLE_MARKER)
            {
                // TODO: Workaround for broken empty-table marker
                assert i==0;
                tags[0] = EMPTY_TAG;
                size -= 2;
                break;
            }
            int val;
            if((k & 2) == 0)
            {
                // narrow value
                val = k >>> 16;
            }
            else
            {
                // wide value
                p += 2;
                val = buf.getInt(p);
                if((k & 1) != 0)
                {
                    // wide string
                    val = reader.readString(p + val);
                }
            }
            long tag = (k & 0x7fff) | ((long)val << 32);
            tags[i] = tag;

            p += 4;
            if((k & 0x8000) != 0) break;
        }

        hashCode = Arrays.hashCode(tags);
        setSize(size);
    }

    // TODO: is tagStrings allowed to be null (currently not; can be null in STagTable)
    // TODO: need explicit <empty> tag if tagtable consists solely of local-key tags
    public TTagTable(TTile tile, String[] tagStrings)
    {
        this.tile = tile;
        setAlignment(1);    // 2-byte aligned (1 << 1)
        if(tagStrings.length == 0)
        {
            tags = new long[] { EMPTY_TAG };
            hashCode = 1;
            setSize(4);
            return;
        }
        tags = new long[tagStrings.length >> 1];
        int size = 0;
        int anchor = 0;

        for(int i=0, i2=0; i<tags.length; i++, i2+=2)
        {
            String key = tagStrings[i2];
            String value = tagStrings[i2+1];
            long tag;
            int k = tile.globalStringCode(key);
            if(k >= 0 && k <= TagValues.MAX_COMMON_KEY)
            {
                // global key
                tag = k << 2;
                size += 2;
            }
            else
            {
                // local key
                int keyCode = tile.localStringCode(key);
                tile.useLocalStringAsKey(keyCode);
                    // ensure that string will be 4-byte aligned
                tag = (keyCode << 2) | LOCAL_KEY;
                size += 4;
                anchor += 4;
            }

            int valueSize = 2;
            int v = tile.globalStringCode(value);
            if(v >= 0)
            {
                tag |= ((long)v << 32) | 1;  // local string
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
                                valueSize = 4;
                            }
                            numberValue = true;
                        }
                    }
                }
                if(!numberValue)
                {
                    // wide string
                    tag |= ((long) tile.localStringCode(value) << 32) | 3;
                    valueSize = 4;
                }
            }
            tags[i] = tag;
            size += valueSize;
            if((tag & LOCAL_KEY) != 0) anchor += valueSize;
        }
        sortTags(tile, tags);
        hashCode = Arrays.hashCode(tags);
        setAlignment(1);    // 2-byte aligned (1 << 1)
        setSize(size);
		setAnchor(anchor);
    }

    /**
     * Sorts tags:
     * - global keys first, ascending key code
     * - then local keys, ascending key string
     *
     * @param tile
     * @param tags
     */
    // TODO: broken, use TagTableBuilder
    private static void sortTags(TTile tile, long[] tags)
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
                    int key = (int)tag >>> 3;
                    int otherKey = (int)other >>> 3;
                    if(localKeyFlag == 0)
                    {
                        compare = key - otherKey;
                    }
                    else
                    {
                        compare = tile.localString(key).compareTo(
                            tile.localString(otherKey));
                    }
                    if(compare >= 0) break;     // TODO: duplicate keys?
                }
                tags[j + 1] = tags[j];
                j = j - 1;
            }
            tags[j + 1] = tag;
        }
    }


    @Override public int hashCode()
    {
        return hashCode;
    }

    @Override public boolean equals(Object o)
    {
        if(o instanceof TTagTable other) return Arrays.equals(tags, other.tags);
        return false;
    }

    public int tagCount()
    {
        return tags.length;
    }

    public long getTag(int n)
    {
        return tags[n];
    }

    /*
    public String getKey(int n)
    {
        int k = (int)tags[n];
        if((k & LOCAL_KEY) == 0)
        {
            return tile.globalStringCode()
        }
    }
     */

    public boolean hasUncommonKeys()
    {
        return (tags[tags.length-1] & LOCAL_KEY) != 0;
    }

    private void writeValue(StructWriter out, long tag)
    {
        int value = (int)(tag >> 32);
        if((tag & 3) == 3)
        {
            // wide string
            out.writePointer(tile.localStringStruct(value));
            return;
        }
        if((tag & 2) == 0)
        {
            out.writeShort((short)value);
        }
        else
        {
            out.writeInt(value);
        }
    }

    @Override public void write(StructWriter out)
    {
        int anchorLocation = anchorLocation();
        int origin = anchorLocation & 0xffff_fffc;
        int lastFlag = 4;
        int i = tags.length-1;
        while(out.position() < anchorLocation)
        {
            long tag = tags[i];
            assert (tag & LOCAL_KEY) != 0;
            writeValue(out, tag);
            SString keyString = tile.localStringStruct(
                ((int)tag >>> 2) & 0x1fff_ffff);
            int ptr = keyString.location() - origin;
			assert (ptr & 3) == 0;
			ptr <<= 1;
            ptr |= ((int)tag & 3) | lastFlag;
    		out.writeInt(ptr);
                // don't use writePointer, pointers to uncommon
                // keys require special handling
            i--;
            lastFlag = 0;
        }
        int lastGlobalTagIndex = i;

        // TODO: if lastGlobalTagIndex is negative, write
        //  end-table marker

        for(i=0; i <= lastGlobalTagIndex; i++)
        {
            long tag = tags[i];
            assert (tag & LOCAL_KEY) == 0;
            if(tag == EMPTY_TAG)
            {
                // TODO: workaround for broken empty-table marker
                out.writeInt(TagValues.EMPTY_TABLE_MARKER);
            }
            else
            {
                int k = (char)tag;
                k |= (i == lastGlobalTagIndex) ? 0x8000 : 0;
                out.writeShort((short) k);
                writeValue(out, tag);
            }
        }
    }

    public void gatherStrings(List<? super SString> strings)
    {
        for(int i=0; i<tags.length; i++)
        {
            long tag = tags[i];
            if((tag & LOCAL_KEY) != 0)
            {
                int k = (((int)tag) >>> 2) & 0x1fff_ffff;
                strings.add(tile.localStringStruct(k));
            }
            if((tag & 3) == 3)  // local-string value
            {
                int v = (int)(tag >>> 32);
                strings.add(tile.localStringStruct(v));
            }
        }
    }

    @Override public int compareTo(TTagTable other)
    {
        // We just compare the keys of the first tags; we only need
        // a rough order // TODO: or no sort at all?
        // TODO: this will sort local keys first, but it may not matter
        return Integer.compare((int)tags[0], (int)other.tags[0]);
    }

    /*
    public String[] toStringArray()
    {
        int n = 0;
        int tagCount = tags.length;
        if(tags[0] == EMPTY_TAG)
        {
            n = 1;
            tagCount--;
        }
        String[] kv = new String[tagCount * 2];
        for(int i=n*2; n<tags.length; n++, i+=2)
        {
            long tag = tags[n];
            int k = (int)tag;
            if(k >= 0)
            {
                kv[i] = tile.k >>> 2
        }
    }
     */
}
