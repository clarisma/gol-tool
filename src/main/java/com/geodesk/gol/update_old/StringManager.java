/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import com.clarisma.common.util.Log;
import com.geodesk.gol.tiles.StringSource;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class StringManager implements StringSource
{
    private final ObjectIntMap<String> globalStringsToCodes;
    private final String[] globalStrings;
    private final MutableObjectIntMap<String> localStringsToCodes;
    private final List<String> localStrings;

    public StringManager(ObjectIntMap<String> globalStringsToCodes, String[] globalStrings)
    {
        this.globalStringsToCodes = globalStringsToCodes;
        this.globalStrings = globalStrings;
        localStringsToCodes = new ObjectIntHashMap<>();
        localStrings = new ArrayList<>();
    }

    public StringManager(InputStream in) throws IOException
    {
        List<String> strings = new ArrayList<>();
		strings.add("");
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        for (;;)
        {
            String s = reader.readLine();
            if (s == null) break;
            strings.add(s);
        }
		globalStrings = strings.toArray(new String[0]);
        int stringCount = globalStrings.length;
        MutableObjectIntMap<String> stringMap = new ObjectIntHashMap<>(
            stringCount + stringCount / 2);
		for(int i=0; i<stringCount; i++) stringMap.put(globalStrings[i], i);
		globalStringsToCodes = stringMap;
        localStringsToCodes = new ObjectIntHashMap<>();
        localStrings = new ArrayList<>();
    }


    @Override public String globalString(int code)
    {
        return globalStrings[code];
    }

    @Override public int globalStringCode(String str)
    {
        return globalStringsToCodes.getIfAbsent(str, -1);
    }

    @Override public String localString(int code)
    {
        return localStrings.get(code);
    }

    @Override public int localStringCode(String str)
    {
        int len = localStrings.size();
        int code = localStringsToCodes.getIfAbsentPut(str, len);
        if(code == len) localStrings.add(str);
        return code;
    }

    @Override public int localKeyStringCode(String str)
    {
        return localStringCode(str);
    }

    public void dump()
    {
        Log.debug("%,d local strings", localStrings.size());
    }

    public void add(StringManager other)
    {
        for(String s: other.localStrings) localStringCode(s);
    }
}
