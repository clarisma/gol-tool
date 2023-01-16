/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.fab.FabException;
import com.clarisma.common.fab.FabReader;
import com.clarisma.common.util.Log;

import java.io.*;
import java.nio.file.Path;

public class ProjectReader extends FabReader
{
    private Project project;
    private String section;

    public ProjectReader()
    {
        project = new Project();
    }

    public Project project()
    {
        return project;
    }

    @Override protected void beginKey(String key)
    {
        section = key;
    }

    @Override protected void endKey()
    {
        section = null;
    }

    @Override protected void keyValue(String key, String value)
    {
        if(section == null)
        {
            Log.debug("%s=%s", key, value);
            project.set(key, value);
        }
        else
        {
            switch(section)
            {
            case "properties":
                Log.debug("PROPERTY %s=%s", key, value);
                project.setProperty(key, value);
                break;
            }
        }
        /*
        if(!project.set(key, value))
        {
            error("Unknown setting: " + key);
        }
         */
    }
}

