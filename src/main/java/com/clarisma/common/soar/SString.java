/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.soar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.clarisma.common.pbf.PbfDecoder;
import com.clarisma.common.pbf.PbfEncoder;

public class SString extends SharedStruct implements Comparable<SString> 
{
	private final String string;
	private final byte[] bytes;
	
	public SString(String s)
	{
		string = s;
		bytes = s.getBytes(StandardCharsets.UTF_8);
		int lenLength = PbfEncoder.varintLength(bytes.length);
		setSize(bytes.length + lenLength);
		setAlignment(0);
	}

	public SString(int p, byte[] bytes)
	{
		this.bytes = bytes;
		string = new String(bytes, StandardCharsets.UTF_8);
		int lenLength = PbfEncoder.varintLength(bytes.length);
		setSize(bytes.length + lenLength);
		setAlignment(0);
		setLocation(p);
	}

	@Override public boolean equals(Object other)
	{
		if(!(other instanceof SString)) return false;
		return string.equals(((SString)other).string);
	}

	@Override public int hashCode()
	{
		return string.hashCode();
	}

	@Override public String toString()
	{
		return string;
	}

	@Override public String dumped()
	{
		// return String.format("STRING \"%s\"%s", string, sharedSuffix());
		return String.format("STRING \"%s\"", string);
	}

	@Override public int compareTo(SString other)
	{
		return string.compareTo(other.string);
	}

	@Override public void writeTo(StructOutputStream out) throws IOException
	{
		out.writeVarint(bytes.length);
		out.write(bytes);
	}

	public static SString read(ByteBuffer buf, int pString)
	{
		int p = pString;
		int len = buf.get(p++);
		if((len & 0x80) != 0)
		{
			len = (len & 0x7f) | (buf.get(p++) << 7);  // TODO: max 16K
		}
		byte[] bytes = new byte[len];
		buf.get(p, bytes);
		return new SString(pString, bytes);
	}
}
