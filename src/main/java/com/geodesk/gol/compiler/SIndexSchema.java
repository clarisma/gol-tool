/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Struct that encodes the mappings between key codes and category codes,
 * which are used for key-based indexing.
 */
public class SIndexSchema extends Struct
{
    private List<Entry> entries;

    private static class Entry implements Comparable<Entry>
    {
        int keyCode;
        int category;

        @Override public int compareTo(Entry o)
        {
            return Integer.compare(keyCode, o.keyCode);
        }
    }

    public SIndexSchema(ObjectIntMap<String> keyToCategory, ObjectIntMap<String> strings)
    {
        entries = new ArrayList<>(keyToCategory.size());
        keyToCategory.forEachKeyValue((key,category) ->
        {
            Entry e = new Entry();
            e.keyCode = strings.get(key);
            e.category = category;
            entries.add(e);
        });
        Collections.sort(entries);
        setAlignment(2);
        setSize(entries.size() * 4 + 4);
    }

    @Override public void writeTo(StructOutputStream out) throws IOException
    {
        out.writeInt(entries.size());
        for(Entry e: entries)
        {
            out.writeInt(e.keyCode | (e.category << 16));
        }
    }
}
