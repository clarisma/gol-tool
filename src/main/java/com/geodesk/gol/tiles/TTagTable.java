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
import com.geodesk.feature.store.TagValues;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TTagTable extends SharedStruct
{
    private final TTile tile;
    private final int hashCode;
    private final long[] tags;

    private final static int LOCAL_KEY = 4;

    private static long[] EMPTY = new long[] { 0xffff_ffff_ffff_ffffL };
        // TODO: change this to 0x8000;

    public TTagTable(TileReader reader, int pTable, int uncommonTagsFlag)
    {
        this.tile = reader.tile();
        ByteBuffer buf = reader.buf();
        setAlignment(1);    // 2-byte aligned (1 << 1)
        int uncommonTagCount = 0;
        int size;
        int p = pTable;
        if(uncommonTagsFlag != 0)
        {
            for (;;)
            {
                p -= 4;
                int k = buf.getInt(p);
                p -= (k & 2) + 2;
                uncommonTagCount++;
                if((k & 4) != 0) break;
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

        tags = new long[tagCount];
        p = pTable;
        int origin = pTable & 0xffff_fffc;
        int n = uncommonTagCount;
        while(n > 0)
        {
            n--;
            p -= 6;
            long tag = buf.getLong(p);
			int rawPointer = (int) (tag >> 16);
			int flags = rawPointer & 7;
			int pKey = ((rawPointer ^ flags) >> 1) + origin;    // preserve sign
			int keyCode = reader.readString(pKey);
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
            tag |= (keyCode << 3) | (flags & 3) | LOCAL_KEY;
            tags[n] = tag;
        }

        p = pTable;
        n = uncommonTagCount;
        for(;;)
        {
            int k = buf.getInt(p);
            if(k == TagValues.EMPTY_TABLE_MARKER)
            {
                // TODO: workaround for current empty table marker
                k = 0x8000;
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
            long tag = ((k & 0x7ffc) << 1) | (k & 3);
            k |= (long)val << 32;
            tags[n++] = tag;
            p += 4;
            if((k & 0x8000) != 0) break;
        }

        hashCode = Arrays.hashCode(tags);
        setSize(size);
    }

    // TODO: is tagStrings allowed to be null (currently not; can be null in STagTable)
    public TTagTable(TTile tile, String[] tagStrings)
    {
        this.tile = tile;
        setAlignment(1);    // 2-byte aligned (1 << 1)
        if(tagStrings.length == 0)
        {
            tags = EMPTY;
            hashCode = 0;
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
                tag = k << 3;
                size += 2;
            }
            else
            {
                // local key
                int keyCode = tile.localStringCode(key);
                tile.useLocalStringAsKey(keyCode);
                    // ensure that string will be 4-byte aligned
                tag = (keyCode << 3) | LOCAL_KEY;
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
     * Sorts tags in representation order:
     * - local keys first, descending key string
     * - then global keys, ascending key code
     *
     * @param tile
     * @param tags
     */
    private static void sortTags(TTile tile, long[] tags)
    {
        for(int i=1; i<tags.length; i++)
        {
            long tag = tags[i];
            int j = i - 1;
            while (j >= 0)
            {
                long other = tags[j];
                int localKeyFlag = (int)tag & LOCAL_KEY;
                int otherLocalKeyFlag = (int)other & LOCAL_KEY;
                int compare = otherLocalKeyFlag - localKeyFlag;
                if(compare > 0) break;
                if(compare == 0)
                {
                    int key = (int)tag >>> 3;
                    int otherKey = (int)other >>> 3;
                    if(localKeyFlag == 0)
                    {
                        compare = key - otherKey;
                        if(compare >= 0) break;     // TODO: duplicate keys?
                    }
                    else
                    {
                        compare = tile.localString(otherKey).compareTo(
                            tile.localString(key));
                        if(compare >= 0) break;     // TODO: duplicate keys?
                    }
                }
                tags[j + 1] = tags[j];
                j = j - 1;
            }
            tags[j + 1] = tag;
        }
    }


    @Override public void writeTo(StructOutputStream out) throws IOException
    {
        int origin = anchorLocation() & 0xffff_fffc;
        int lastTag = tags.length-1;
        for(int i=0; i<tags.length; i++)
        {
            long tag = tags[i];
            int keyCode = (int)tag >>> 3;
            int flags = (int)tag & 3;
            if((tag & LOCAL_KEY) == 0)
            {
                int k = (keyCode << 2) | flags;
                out.writeShort(i != lastTag ? k : (k | 0x8000));
            }
            int val = (int)(tag >>> 32);
            if((tag & 2) == 0)
            {
                // narrow value
                out.writeShort(val);
            }
            else
            {
                // wide value
                if((tag & 1) != 0)
                {
                    // wide string
                    out.writePointer(tile.localStringStruct(val));
                }
                else
                {
                    // wide number
                    out.writeInt(val);
                }
            }
            if((tag & LOCAL_KEY) != 0)
            {
                SString keyString = tile.localStringStruct(keyCode);
                int ptr = keyString.location() - origin;
				assert (ptr & 3) == 0;
				ptr <<= 1;
                ptr |= flags;
				out.writeInt(i != 0 ? ptr : (ptr | 4));
					// don't use writePointer, pointers to uncommon
					// keys require special handling
            }
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

    public boolean hasUncommonKeys()
    {
        return (tags[0] & LOCAL_KEY) != 0;
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
        int i= 0;
        for(; out.position() < anchorLocation; i++)
        {
            long tag = tags[i];
            assert (tag & LOCAL_KEY) != 0;
            writeValue(out, tag);
            SString keyString = tile.localStringStruct((int)tag >>> 3);
            int ptr = keyString.location() - origin;
			assert (ptr & 3) == 0;
			ptr <<= 1;
            ptr |= (int)tag & 3;
            if(i == 0) ptr |= 4;
			out.writeInt(ptr);
                // don't use writePointer, pointers to uncommon
                // keys require special handling
        }
        int lastTagIndex = tags.length-1;
        for(; i <= lastTagIndex; i++)
        {
            long tag = tags[i];
            assert (tag & LOCAL_KEY) == 0;
            int k = ((int)tag >>> 1) & 0x7ffc;
            k |= (int)tag & 3;
            if(i == lastTagIndex) tag |= 0x8000;
            out.writeShort((short)k);
            writeValue(out, tag);
        }
    }
}
