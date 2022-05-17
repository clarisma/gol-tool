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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// TODO: don't include strings in GST if they are only used for keys and have
//  an index number above 8K (or 16K for roles)

/**
 * The StringTableBuilder creates string tables for the Importer as well
 * as the FeatureStore, based on the String Summary file produced by the
 * Analyzer.
 *
 * For the Importer, string tables are separated by keys, values, and roles;
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
 */
public class StringTableBuilder
{
    private List<StringEntry> keys = new ArrayList<>();
    private List<StringEntry> values = new ArrayList<>();
    private List<StringEntry> roles = new ArrayList<>();
    private List<StringEntry> global = new ArrayList<>();

    private static final float KEY_FACTOR = 5;
    private static final float CATEGORY_KEY_FACTOR = 20;
    private static final float ROLE_FACTOR = 2;

    private double minGlobalWeight = 1000;

    private static class StringEntry implements Comparable<StringEntry>
    {
        String string;
        double weight;

        @Override public int compareTo(StringEntry o)
        {
            return Double.compare(o.weight, weight);
        }
    }

    private void addEntry(List<StringEntry> list, String string, double weight)
    {
        StringEntry e = new StringEntry();
        e.string = string;
        e.weight = weight;
        list.add(e);
    }

    public void readStrings(Path file) throws IOException
    {
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
                    // the Importer, unless they have exceptionally high
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

                double weight = keyCount * KEY_FACTOR + roleCount * ROLE_FACTOR + valueCount;
                if(weight > minGlobalWeight)
                {
                    addEntry(global, string, weight);
                }
            }
        }

        Collections.sort(keys);
        Collections.sort(values);
        Collections.sort(roles);
        Collections.sort(global);
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

    public static class SStringTable extends Struct
    {
        private byte[] bytes;

        public SStringTable(List<StringEntry> list)
        {
            PbfOutputStream out = new PbfOutputStream();
            out.writeVarint(list.size());       // todo: fixed uint16?
            for(StringEntry e: list)
            {
                byte[] chars = e.string.getBytes(StandardCharsets.UTF_8);
                out.writeVarint(chars.length);
                out.writeBytes(chars);
            }
            bytes = out.toByteArray();
            setSize(bytes.length);
            setAlignment(0);
        }
        @Override public void writeTo(StructOutputStream out) throws IOException
        {
            out.write(bytes);
        }
    }

    public SStringTable buildStruct()
    {
        return new SStringTable(global);
    }

    public ObjectIntMap<String> createGlobalStringMap()
    {
        MutableObjectIntMap<String> stringMap = new ObjectIntHashMap<>(global.size());
            // TODO : account for load factor?
        for(int i=0; i<global.size(); i++)
        {
            stringMap.put(global.get(i).string, i+1);
        }
        return stringMap;
    }

    public void writeStringTables(Path keysFile, Path valuesFile,
        Path rolesFile, Path globalFile, int maxGlobalStrings) throws FileNotFoundException
    {
        writeStrings(keys, keysFile, Integer.MAX_VALUE);
        writeStrings(values, valuesFile, Integer.MAX_VALUE);
        writeStrings(roles, rolesFile, Integer.MAX_VALUE);
        writeStrings(global, globalFile, maxGlobalStrings);
    }

    public static SBytes encodeStringTable(List<String> strings)
    {
        PbfOutputStream out = new PbfOutputStream();
        out.writeVarint(strings.size());       // todo: fixed uint16?
        for(String s: strings)
        {
            byte[] chars = s.getBytes(StandardCharsets.UTF_8);
            out.writeVarint(chars.length);
            out.writeBytes(chars);
        }
        return new SBytes( out.toByteArray(), 0);
    }

    public static void main(String[] args) throws Exception
    {
        Path root = Paths.get("c:\\geodesk");
        StringTableBuilder stb = new StringTableBuilder();
        stb.readStrings(Paths.get("c:\\geodesk\\string-counts.txt"));
        stb.writeStrings(stb.keys, root.resolve("keys.txt"), Integer.MAX_VALUE);
        stb.writeStrings(stb.values, root.resolve("values.txt"), Integer.MAX_VALUE);
        stb.writeStrings(stb.roles, root.resolve("roles.txt"), Integer.MAX_VALUE);
        stb.writeStrings(stb.global, root.resolve("global.txt"), 1 << 14);
    }
}
