/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.cli;

public class CommandLineParser
{
    private int current;
    private String[] args;
    private String key;
    private String value;

    public void parse(String[] args)
    {
        this.args = args;
        current = -1;
    }

    public boolean next()
    {
        current++;
        if(current == args.length) return false;
        String arg = args[current];
        if (arg.charAt(0) == '-')
        {
            if(arg.length() < 2)
            {
                key = null;
                value = null;
            }
            else
            {
                int keyStart = arg.charAt(1)=='-' ? 2 : 1;
                int n = arg.indexOf('=');
                if(n > 0)
                {
                    key = arg.substring(keyStart,n);
                    value = arg.substring(n+1);
                }
                else
                {
                    key = arg.substring(keyStart);
                    value = null;
                }
            }
        }
        else
        {
            key = null;
            value = arg;
        }
        return true;
    }

    public String key()
    {
        return key;
    }

    public String value()
    {
        return value;
    }
}
