package com.geodesk.gol.build;

import com.clarisma.common.soar.SBytes;
import com.clarisma.common.soar.StructOutputStream;
import com.clarisma.common.util.Bytes;
import com.geodesk.gol.compiler.SIndexSchema;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * A class that describes the key indexing used in a GOL.
 *
 * - One or more keys are mapped to a *category* (with a number from 1 to 30)
 *
 * - These categories are configured using the `indexed-keys` property
 *
 * - A Feature can belong to more than one category based on its keys
 *   (e.g. a hotel that is also a restaurant could belong to `tourism` as
 *   well as `amenity`. A street with tram tracks could belong to both
 *   `highway` and `railway`)
 *
 * - Each feature type (node, way, area, relation) within a tile can have
 *   zero or more key index buckets (the maximum is specified by the
 *   `max-key-indexes` setting)
 *
 * - Each key index bucket contains one or more categories. The Compiler
 *   determines the ideal distribution of categories to indexed buckets
 *   for each individual feature type in each tile, based on the features
 *   present.
 *
 * - The categories to which a Feature belongs, as well as the categories
 *   present in an index bucket, are described using *key bits* (a 32-bit
 *   integer, where bit 0 corresponds to Category 1, etc.; the two topmost
 *   bits are reserved).
 *
 * - Only global keys can be indexed (see https://github.com/clarisma/gol-tool/issues/9)
 *
 */
public class KeyIndexSchema implements Serializable
{
    private final Map<String,Integer> keysToCategories;
    private final String[] categories;

    private static final int MAX_CATEGORIES = 30;

    /**
     * Defines a KeyIndexSchema based on the given String.
     *
     * @param s     a String listing the index categories
     *
     * @throws IllegalArgumentException if a key has been assigned to more
     *   than one category, or the number of categories is exceeded
     */
    public KeyIndexSchema(String s)
    {
        Map<String,Integer> map = new HashMap<>();
        categories = s.split("\\s");
        if(categories.length > MAX_CATEGORIES)
        {
            throw new IllegalArgumentException(String.format(
                "A maximum of %d index categories may be defined (%d listed)",
                    MAX_CATEGORIES, categories.length));
        }
        for (int category = 0; category < categories.length; category++)
        {
            String[] keys = categories[category].split("/");
            for (String key : keys)
            {
                if(map.containsKey(key))
                {
                    throw new IllegalArgumentException(String.format(
                        "Key `%s` assigned to more than one category", key));
                }
                map.put(key, category + 1);
            }
        }
        keysToCategories = map;
    }

    /**
     * Returns the category to which a key belongs.
     *
     * @param key   the key
     * @return  the category number (1 to 30), or 0 if the given key
     *          is not indexed
     */
    public int getCategory(String key)
    {
        Integer category = keysToCategories.get(key);
        return category == null ? 0 : category;
    }

    /**
     * Returns the name of a given category. The category name is made of
     * one or more keys that make up the category, separated by forward slashes.
     *
     * @param category  the category (1 to 30)
     * @return  the category name (e.g. "highway", "amenity/shop/craft")
     */
    public String getCategoryName(int category)
    {
        return categories[category-1];
    }

    /**
     * Based on the key bits, obtains a String representing the categories.
     *
     * @param keyBits       a bitfield indicating the categories of a feature
     *                      or index bucket
     * @param separator     a String to separate the categories (e.g. "+")
     * @param uncategorized the String to return if `keyBits` is `0`
     * @return  a String that describes the categories represented by `keyBits`
     */
    public String getBucketName(int keyBits, String separator, String uncategorized)
    {
        if(keyBits == 0) return uncategorized;
        StringBuilder buf = new StringBuilder();
        int cat = -1;
        while(keyBits != 0)
        {
            int zeroes = Integer.numberOfTrailingZeros(keyBits);
            cat += zeroes+1;
            if(buf.length() > 0) buf.append(separator);
            buf.append(categories[cat]);
            keyBits >>>= zeroes+1;
        }
        return buf.toString();
    }

    private static class IndexSchemaEntry implements Comparable<IndexSchemaEntry>
    {
        int keyCode;
        int category;

        @Override public int compareTo(IndexSchemaEntry o)
        {
            return Integer.compare(keyCode, o.keyCode);
        }
    }

    public SBytes encode(ObjectIntMap<String> strings)
    {
        int count = keysToCategories.size();
        IndexSchemaEntry[] entries = new IndexSchemaEntry[count];
        int i=0;
        for(Map.Entry<String,Integer> kv : keysToCategories.entrySet())
        {
            IndexSchemaEntry e = new IndexSchemaEntry();
            e.keyCode = strings.get(kv.getKey());
            e.category = kv.getValue();
            entries[i++] = e;
        }
        Arrays.sort(entries);
        byte[] b = new byte[count * 4 + 4];
        Bytes.putInt(b, 0, count);
        int pos=4;
        for(i=0; i<count; i++)
        {
            IndexSchemaEntry e = entries[i];
            Bytes.putInt(b, pos, e.keyCode | (e.category << 16));
            pos += 4;
        }
        return new SBytes(b, 2);
    }
}
