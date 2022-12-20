/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.parser.Parser;

import java.util.regex.Pattern;

public class TagFilterParser extends Parser
{
    private static final String COMMA = ",";
    private static final String EXCLAMATION_MARK = "!";
    private static final String LPAREN = "(";
    private static final String RPAREN = ")";
    private static final String EXCEPT = "except";
    private static final String ONLY = "only";

    private final static Pattern KEY_IDENTIFIER_PATTERN =
        Pattern.compile("\\p{L}[[\\p{L}\\p{N}]:_]*");

    private void clause()
    {
        expect(IDENTIFIER);
        String key = stringValue();
        nextToken();
        if(acceptAndConsume(LPAREN))
        {
            boolean exclude;
            if(accept(EXCEPT))
            {
                exclude = true;
            }
            else
            {
                expect(ONLY);
                exclude = false;
            }
            nextToken();
            for(;;)
            {
                expect(IDENTIFIER);
                String value = stringValue();
                nextToken();
                if(acceptAndConsume(RPAREN)) break;
                acceptAndConsume(COMMA);        // comma is optional
            }
            acceptAndConsume(COMMA);        // comma is optional
        }
    }
}
