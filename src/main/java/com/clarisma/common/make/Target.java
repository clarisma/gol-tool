/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.make;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Target
{
    private final String name;
    final List<Target> sources = new ArrayList<>();
    private final List<String> sourceProperties = new ArrayList<>();
    protected Path workPath;
    int unprocessedDependencies;

    public Target(String name)
    {
        this.name = name;
    }

    public void dependsOn(String property)
    {
        sourceProperties.add(property);
    }

    public void dependsOn(String... properties)
    {
        for(String property: properties) sourceProperties.add(property);
    }

    public void dependsOn(Target source)
    {
        sources.add(source);
    }

    public boolean make(Map<String,Object> properties)
    {
        return false;   // TODO
    }

    public Path workPath()
    {
        return workPath;
    }

    protected void deleteWorkFile(String name)
    {
        try
        {
            Files.deleteIfExists(workPath.resolve(name));
            // TODO: output action if verbose
        }
        catch(IOException ex)
        {
            throw new RuntimeException(
                String.format("Could not delete work file %s: %s",
                    name, ex.getMessage(), ex));
        }
    }


    public void clean()
    {

    }
}
