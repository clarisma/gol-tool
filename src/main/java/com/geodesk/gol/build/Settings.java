/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.validation.Validator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Settings
{
    private final Map<String, Property> properties = new HashMap<>();

    private static class Property
    {
        final int group;
        final String name;
        final Validator<?> validator;
        Object value;

        Property(int group, String name, Validator<?> validator, Object defaultValue)
        {
            this.group = group;
            this.name = name;
            this.validator = validator;
            this.value = defaultValue;
        }
    }

    public void property(int group, String name, Object defaultValue)
    {
        property(group, name, null, defaultValue);
    }

    public void property(int group, String name, Validator<?> validator, Object defaultValue)
    {
        properties.put(name, new Property(group, name, validator, defaultValue));
    }

    public void set(String name, Object value)
    {
        properties.get(name).value = value;
    }

    public Object get(String name)
    {
        return properties.get(name).value;
    }

    public Map<String,Object> toMap()
    {
        Map<String,Object> map = new HashMap<>(properties.size());
        for(Property p: properties.values())
        {
            map.put(p.name, p.value);
        }
        return map;
    }

    public int checkChanges(Map<String,Object> previous, List<String> changed)
    {
        int lowestGroup = Integer.MAX_VALUE;
        for(Property p: properties.values())
        {
            Object oldValue = previous.get(p.name);
            if(!Objects.equals(p.value, oldValue))
            {
                changed.add(p.name);
                int group = p.group;
                if(group >= 0 && group < lowestGroup) lowestGroup = group;
            }
        }
        return lowestGroup == Integer.MAX_VALUE ? -1: lowestGroup;
    }
}
