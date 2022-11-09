/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Stack;

public class XmlWriter extends PrintWriter
{
    private final String indentString = "  ";
    private final Stack<String> elements = new Stack<>();
    private boolean childElements = true;

    public XmlWriter(OutputStream out)
    {
        super(out);
        println("<?xml version='1.0' encoding='UTF-8'?>");
    }

    protected void indent()
    {
        for(int i=0; i<elements.size(); i++) print(indentString);
    }

    public void begin(String tag)
    {
        if(!childElements)
        {
            println(">");
        }
        indent();
        print("<");
        print(tag);
        elements.push(tag);
        childElements = false;
    }

    public void attr(String a, Object v)
    {
        print(' ');
        print(a);
        print("=\"");
        print(EscapeXml.escapeXml(v.toString()));
        print('\"');
    }

    public void attr(String a, long v)
    {
        print(' ');
        print(a);
        print("=\"");
        print(v);
        print('\"');
    }

    public void end()
    {
        String tag = elements.pop();
        if(childElements)
        {
            indent();
            print("</");
            print(tag);
            println(">");
        }
        else
        {
            println("/>");
        }
        childElements = true;
    }
}
