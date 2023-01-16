/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.math.Decimal;
import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.soar.SBytes;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.feature.store.TagValues;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

// TODO: don't include strings in GST if they are only used for keys and have
//  an index number above 8K (or 16K for roles)

/**
 * The StringTableBuilder creates string tables for the Sorter as well
 * as the FeatureStore, based on the String Summary file produced by the
 * Analyzer.
 *
 * For the Sorter, string tables are separated by keys, values, and roles;
 * we put as many strings into them as possible to maximize space savings,
 * and don't distinguish numbers.
 *
 * The FeatureStore uses a single string table with a maximum of 64K entries
 * (of these, only the first 8K can be used for keys; roles must be stored in
 * the first 16K). If a string can be represented as a number, we don't store
 * it in the string table (TODO: This applies only to values; what about roles
 * or keys that are purely numeric? Should not happen for keys, but possible
 * for roles).
 *
 * TODO: Put indexed keys first, so they appear before all others in the tag table
 *  --> quicker to get a hit in queries
 *
 *  TODO: Should indexed keys be promoted to global strings, even if their
 *   occurence count is not high enough?
 *   Useful for updating: Expectation that key use becomes more frequent in the future
 *   If so, need to ensure that max-strings > number of indexed keys)
 *
 *  TODO: put certain keys ("yes", "no", "outer", "inner") at fixed positions in the GST?
 */
public class StringTableBuilder
{
    private final List<StringEntry> keys = new ArrayList<>();
    private final List<StringEntry> values = new ArrayList<>();
    private final List<StringEntry> roles = new ArrayList<>();
    /**
     * Global strings (includes empty string at #0)
     */
    private String[] globalStrings;
    private ObjectIntMap<String> stringsToCodes;

    private static final float KEY_FACTOR = 5;
    private static final float CATEGORY_KEY_FACTOR = 20;
    private static final float ROLE_FACTOR = 2;

    private static final double INDEXED_KEY_BONUS = 10_000_000_000_000d;

    public ObjectIntMap<String> stringsToCodes()
    {
        return stringsToCodes;
    }

    private static class StringEntry implements Comparable<StringEntry>
    {
        String string;
        double weight;

        @Override public int compareTo(StringEntry o)
        {
            return Double.compare(o.weight, weight);
        }
    }

    private static void addEntry(List<StringEntry> list, String string, double weight)
    {
        StringEntry e = new StringEntry();
        e.string = string;
        e.weight = weight;
        list.add(e);
    }

    public void build(Path file, KeyIndexSchema keyIndexSchema,
        int maxGlobalStrings, int minGlobalStringUsage) throws IOException
    {
        Set<String> indexedKeysToInclude = new HashSet<>(keyIndexSchema.indexedKeys());
        List<StringEntry> tentativeGlobal = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            reader.readLine();   // table header; discard
            for (;;)
            {
                String s = reader.readLine();
                if (s == null) break;
                // log.debug(s);
                int n = s.indexOf('\t');
                String string = null;
                try
                {
                    string = s.substring(0, n);
                }
                catch(Exception ex)
                {
                    throw new RuntimeException(
                        String.format("Failed to read string: \"%s\": %s",
                            s, ex.getMessage()), ex);
                }
                int n2 = s.indexOf('\t', n+1);
                long totalCount = Long.parseLong(s.substring(n+1, n2));
                if(totalCount < 2) continue;

                int n3 = s.indexOf('\t', n2+1);
                long keyCount = Long.parseLong(s.substring(n2+1, n3));
                int n4 = s.indexOf('\t', n3+1);
                long valueCount = Long.parseLong(s.substring(n3+1, n4));
                int len = string.length();
                long roleCount = totalCount - keyCount - valueCount;
                if(len > 2 || totalCount > 10_000 ||
                   (len==2 && totalCount > 1_000_000))
                {
                    // TODO:
                    // Don't add very short strings to the string tables for
                    // the Sorter, unless they have exceptionally high
                    // occurrences; otherwise, writing the string-table index
                    // number uses as much space as the string itself. For
                    // the top 63 strings (roughly over one million uses),
                    // the number is only one byte because of varint encoding,
                    // so in that case string-tabling a string with one or two
                    // characters makes sense
                    if (keyCount > 2) addEntry(keys, string, keyCount * len);
                    if (valueCount > 2) addEntry(values, string, valueCount * len);
                    if (roleCount > 2) addEntry(roles, string, valueCount * len);
                }

                long d = Decimal.parse(string, true);
                if(d != Decimal.INVALID)
                {
                    if(TagValues.isNarrowNumber(d))
                    {
                        // If the string represents a narrow number, there is no
                        // point storing it in the global string table, since
                        // storing it as a numeric value takes up the same space
                        // and is more efficient

                        valueCount = 0;
                    }
                }

                // TODO: Strings that are assigned an explicit code ("yes", "no" ,etc.)

                long usageCount = keyCount + roleCount + valueCount;
                if(indexedKeysToInclude.contains(string))
                {
                    // TODO: In theory, indexed keys could be pushed beyond the
                    //  MAX_COMMON_KEY limit if there's a *huge* amount of high-usage
                    //  strings

                    addEntry(tentativeGlobal, string, INDEXED_KEY_BONUS + usageCount);
                    indexedKeysToInclude.remove(string);
                }
                else if(usageCount >= minGlobalStringUsage)
                {
                    double weight = keyCount * KEY_FACTOR + roleCount * ROLE_FACTOR + valueCount;
                    addEntry(tentativeGlobal, string, weight);
                }
            }
        }

        // Add all indexed keys, even those that aren't used at all
        //  (GST must always include indexed keys regardless of usage count)
        for(String string: indexedKeysToInclude)
        {
            addEntry(tentativeGlobal, string, INDEXED_KEY_BONUS);
        }

        Collections.sort(keys);
        Collections.sort(values);
        Collections.sort(roles);
        Collections.sort(tentativeGlobal);

        int globalStringCount = Math.min(tentativeGlobal.size(), maxGlobalStrings);
        MutableObjectIntMap<String> map = new ObjectIntHashMap<>(globalStringCount);
        globalStrings = new String[globalStringCount+1];
        globalStrings[0] = "";
        int i=1;
        for(StringEntry entry : tentativeGlobal)
        {
            String string = entry.string;;
            map.put(string, i);
            globalStrings[i++] = string;
        }
        stringsToCodes = map;
    }

    private void writeStrings(List<StringEntry> list, Path file, int max) throws FileNotFoundException
    {
        PrintWriter out = new PrintWriter(file.toFile());
        max = Math.min(max, list.size());
        for(int i=0; i<max; i++)
        {
            out.println(list.get(i).string);
        }
        out.close();
    }

    public void writeStringTables(Path keysFile, Path valuesFile,
        Path rolesFile) throws FileNotFoundException
    {
        writeStrings(keys, keysFile, Integer.MAX_VALUE);
        writeStrings(values, valuesFile, Integer.MAX_VALUE);
        writeStrings(roles, rolesFile, Integer.MAX_VALUE);
    }

    public SBytes encodeGlobalStrings()
    {
        PbfOutputStream out = new PbfOutputStream();
        int length = globalStrings.length;     // includes "" at #0
        // The encoded string table, however, does not include entry #0
        out.writeVarint(length - 1);       // todo: fixed uint16?
        for(int i=1; i<length; i++)
        {
            out.writeString(globalStrings[i]);
        }
        return new SBytes(out.toByteArray(), 0);
    }
}
