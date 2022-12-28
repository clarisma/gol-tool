/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.parser.SimpleParser;

public class TagFilterParser extends SimpleParser
{
    public TagFilterParser(String s)
    {
        super(s);
    }

    private static final SimpleParser.Schema KEY_SCHEMA = new SimpleParser.Schema(
        0b11111111111000000000000000000000000000000000000000000000000L,
       0b11111111111111111111111111010000111111111111111111111111110L,
        0b11111111111000000000000000000000000000000000000000000000000L,
        0b11111111111111111111111111010000111111111111111111111111110L);

    private String keyOrValue(String type)
    {
        String kv = identifier(KEY_SCHEMA);
        if(kv == null)
        {
            kv = rawString();
            if (kv == null) error("Expected " + type);
        }
        return kv;
    }

    private void clause()
    {
        String key = keyOrValue("key");
        if(literal('('))
        {
            boolean exclude;
            if(literal("except"))
            {
                exclude = true;
            }
            else
            {
                if(!literal("only")) error ("Expected 'except' or 'only'");
                exclude = false;
            }
            for(;;)
            {
                String value = keyOrValue("value");
                if(literal(')')) break;
                literal(',');          // comma is optional
            }
            literal(',');          // comma is optional
        }
    }
}
