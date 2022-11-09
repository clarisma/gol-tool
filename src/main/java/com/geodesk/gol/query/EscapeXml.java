/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.clarisma.common.text.Strings;

public class EscapeXml
{
    public static String escapeXml(String s)
    {
        if(Strings.indexOfAny(s, "&<>\"'") < 0) return s;
        StringBuffer buf = new StringBuffer();
        for(int i=0; i<s.length(); i++)
        {
            char ch = s.charAt(i);
            switch(ch)
            {
            case '&':
                buf.append("&amp;");
                break;
            case '>':
                buf.append("&gt;");
                break;
            case '<':
                buf.append("&lt;");
                break;
            case '\"':
                buf.append("&quot;");
                break;
            case '\'':
                buf.append("&apos;");
                break;
            default:
                buf.append(ch);
                break;
            }
        }
        return buf.toString();
    }
}
