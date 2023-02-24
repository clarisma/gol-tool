/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.soar;

import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.util.Log;

import java.nio.ByteBuffer;

public class StructWriter
{
    private ByteBuffer buf;
	private int start;
	private int pos;
	private int end;
	private PbfOutputStream links;

	public StructWriter(ByteBuffer buf, int start, int maxLen)
	{
		this.buf = buf;
		this.start = start;
		this.end = start + maxLen;
		this.pos = start;
	}

	public void setLinks(PbfOutputStream links)
	{
		this.links = links;
	}

	public void writeByte(byte b)
	{
		buf.put(pos, b);
		pos++;
	}

    public void writeBytes(byte b[])
    {
        buf.put(pos, b);
        pos += b.length;
    }

    public void write(byte b[], int off, int len)
    {
        buf.put(pos, b, off, len);
        pos += len;
    }

    public void writeInt(int value)
    {
        buf.putInt(pos, value);
		pos += 4;
    }

    public void writeShort(short value)
    {
    	buf.putShort(pos, value);
		pos += 2;
    }

    public void writeLong(long value)
    {
		buf.putLong(pos, value);
		pos += 8;
    }

	/**
	 * Returns position (relative to tile start, NOT buffer start)
	 *
	 * @return
	 */
	public int position()
	{
		return pos-start;
	}

	public void writePointer(Struct target)
	{
		if(target == null)
		{
			// TODO: warn
			writeInt(0);
			return;
		}
		writeInt(target.anchorLocation() - (pos - start));
	}

	public void writePointer(Struct target, int flags)
	{
		int p;
		if(target == null)
		{
			// TODO: assert
			Log.debug("Writing null pointer at %08X", pos);
			p = 0;
		}
		else
		{
			if(target.location() == 0)
			{
				Log.debug("Writing null pointer at %08X", pos);
			}
			p = target.anchorLocation() - (pos - start);
		}
		// TODO: check if flags clash with pointer?
		writeInt(p | flags);
	}

	// TODO: not all tagged pointers are shifted; if 2-byte aligned and we only need one
	// flag, no need to shift; if 4-byte aligned and we only need two flags, no need for
	// shift
	public void writeTaggedPointer(Struct target, int flagCount, int flags)
	{
		assert flagCount > 0: "No need for tagged pointer if flagCount = 0";
		int p;
		if(target == null)
		{
			// TODO: warn
			p = 0;
		}
		else
		{
			// TODO: This is wrong -- make caller request specific treatment
			//  of pointer, don't arbitrarily rebase it
			//  This messes up pointers to strings, which are normally
			//  1-byte aligned, but change to 4-byte alignment if they are
			//  used as keys due to the constraints of tag tables

			// TODO: current code may be ok, but better to make explicit

			if(target.location() == 0)
			{
				Log.error("Target %s has not been placed", target);
			}

			int alignment = target.alignment();
			assert (flagCount-1) <= alignment:
				String.format("Cannot have %d flag bits for a pointer to a 2^%d aligned struct",
					flagCount, alignment);
			int from = (pos - start) & (0xffff_ffff << (flagCount-1));
			p = target.anchorLocation() - from;

			/*
			assert p == (p & (0xffff_ffff << alignment)):
				String.format("Pointer %X is not aligned to 2^%d", p, alignment);
			 */
		}

		assert flags == (flags & (0xffff_ffff >> (32-flagCount))):
			String.format("Flags must only use the lowest %d bits", flagCount);

		writeInt((p << 1) | flags);
	}

	public void write(Struct s)
	{
		pos = start + s.location();
		int oldPos = pos;
		s.write(this);
		if(pos != oldPos + s.size())
		{
			Log.debug("!!!");
		}
		assert pos == oldPos + s.size():
			String.format("Size of %s is %d, but %d bytes were written",
				s, s.size(), pos-oldPos);
		assert pos <= end: "Wrote beyond end of buffer";
	}

    public void writeForeignPointer(int tile, long id, int shift, int flags)
    {
		assert links != null;
		assert shift >= 0 && shift <= 4;
		links.writeFixed32(pos);
		links.writeFixed32((tile << 4) | shift);
		links.writeFixed64(id);
		writeInt(flags);
    }

	public void writeChain(Struct s)
	{
		do
		{
			assert start+s.location() >= pos: "Chained structs are out of order";
			write(s);
			s = s.next();
		}
		while(s != null);
	}
}

