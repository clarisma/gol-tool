/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.fab.FabException;
import com.clarisma.common.fab.FabReader;

import java.io.*;
import java.nio.file.Path;

public class ProjectReader extends FabReader
{
    private Project project;

    public ProjectReader()
    {
        project = new Project();
    }

    public Project project()
    {
        return project;
    }

    @Override protected void keyValue(String key, String value)
    {
        project.set(key, value);
    }
}

