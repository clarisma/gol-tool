/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

/*

package com.geodesk.gol.query;



public class Json
{
    public static String escape (String s)
    {
        StringBuilder buf = new StringBuilder ();
        for (int i=0; i<s.length(); i++)
        {
            char ch = s.charAt(i);
            if(ch < 32)
            {
                switch(ch)
                {
                    case '\b':
                        buf.append("\\b");
                        break;
                    case '\f':
                        buf.append("\\f");
                        break;
                    case '\n':
                        buf.append("\\n");
                        break;
                    case '\r':
                        buf.append("\\r");
                        break;
                    case '\t':
                        buf.append("\\t");
                        break;
                    }
                    return Character.MAX_VALUE;	// TODO: check
                }
                switch(ch)
                {
                case '\'':
                case '\"':
                case '\\':
                    return ch;
                }
                return Character.MAX_VALUE;
            }
            switch(ch)
            {
                case ''
            }
            char chEscaped = escape(ch);
            if(chEscaped != Character.MAX_VALUE)
            {
                buf.append('\\');
                buf.append(chEscaped);
            }
            else
            {
                buf.append (ch);
            }
        }
        return buf.toString ();
    }

}

 */