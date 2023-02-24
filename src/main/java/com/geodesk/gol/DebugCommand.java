/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.Parameter;

public class DebugCommand extends GolCommand
{
    @Parameter("1=args")
    protected String[] args;

    @Override protected void performWithLibrary() throws Exception
    {
        if(args==null || args.length==0) return;
        switch(args[0])
        {
        case "strings":
            dumpStrings();
            break;
        }
    }

    private void dumpStrings()
    {
        for(int i=1;;i++)
        {
            try
            {
                System.out.println(features.store().stringFromCode(i));
            }
            catch (Exception ex)
            {
                break;
            }
        }
    }
}
