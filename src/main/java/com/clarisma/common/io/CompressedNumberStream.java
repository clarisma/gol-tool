/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.io;

import java.io.IOException;
import java.io.OutputStream;

public class CompressedNumberStream
{
	private OutputStream out;
	
	public CompressedNumberStream(OutputStream out) 
	{
		this.out = out;
	}
	
	public void writeVarint(long val) throws IOException
	{
		while (val >= 0x80 || val < 0)
		{
			out.write ((int)(val & 0x7f) | 0x80);
			val >>>= 7;
		}
		out.write((int)val);
	}

	public void writeSignedVarint(long val) throws IOException
	{
		writeVarint((val << 1) ^ (val >> 63));
	}
}
