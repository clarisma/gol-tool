package com.clarisma.common.soar;

import java.nio.ByteBuffer;

// methods moved to Bytes

//public class Structs
//{
//	/**
//	 * Reads a string from a buffer. Note that only string
//	 * lengths up to 32K are supported.
//	 *
//	 * @param buf	the buffer
//	 * @param p		the position of the string
//	 * @return
//	 */
//	public static String readString(ByteBuffer buf, int p)
//	{
//		// TODO: This may overrun if string is zero-length
//		int len = buf.getChar(p);
//		if((len & 0x80) != 0)
//		{
//			len = (len & 0x7f) | (len >> 1) & 0xff80;
//			p+=2;
//		}
//		else
//		{
//			len &= 0x7f;
//			p++;
//		}
//
//		byte[] chars = new byte[len];
//		buf.get(p, chars);
//		try
//		{
//			return new String(chars, "UTF-8");
//		}
//		catch (Exception ex)
//		{
//			throw new RuntimeException("Unable to decode string.", ex);
//		}
//	}
//
//	/**
//	 * Compares an ASCII string stored in a buffer to a match string.
//	 *
//	 * @param buf
//	 * @param p
//	 * @param s
//	 * @return
//	 */
//	public static boolean stringEqualsAscii(ByteBuffer buf, int p, String s)
//	{
//		int len = buf.getChar(p);
//		if((len & 0x80) != 0)
//		{
//			len = (len & 0x7f) | (len >> 1) & 0xff00;
//			p+=2;
//		}
//		else
//		{
//			len &= 0x7f;
//			p++;
//		}
//		if(len != s.length()) return false;
//		for(int i=0; i<len; i++)
//		{
//			if(s.charAt(i) != buf.get(p++)) return false;
//		}
//		return true;
//	}
//}
