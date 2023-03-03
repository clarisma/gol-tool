package com.geodesk.gol.tiles;

import com.geodesk.gol.update_old.StringManager;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static com.geodesk.gol.tiles.TagTableBuilder.*;

public class TagTableBuilderTest
{
    StringSource strings;

    @Before public void setUp() throws IOException
    {
        try(InputStream in = getClass().getClassLoader().getResourceAsStream("tags/strings.txt"))
        {
            strings = new StringManager(in);
        }
    }

    @Test public void test()
    {
        String[] kv = new String[] {
            "maxweight", "7.31",
            "highway", "primary",
            "name", "Shattuck Avenue",
            "maxspeed", "30",
            "banana", "apple",
            "lit", "yes"
        };

        String[] kv2 = new String[] {
            "maxweight", "12",
            "surface", "asphalt",
            "highway", "primary",
            "name", "Bancroft Way",
            "maxspeed", "30",
            "lit", "no"
        };


        long[] tags = fromStrings(kv, strings);
        long[] tags2 = fromStrings(kv2, strings);

        Assert.assertEquals(6, tags.length);
        Assert.assertEquals(6, tags2.length);
        testDiff(tags, tags2);
    }

    private void testDiff(long[] a, long[] b)
    {
        MutableLongList list = new LongArrayList();
        diffTags(a, b, strings, list);
        long[] diff = list.toArray();
        list.clear();
        mergeTags(a, diff, strings, list);
        long[] merged = list.toArray();
        list.clear();
        Assert.assertTrue(Arrays.equals(b, merged));

        diffTags(b, a, strings, list);
        diff = list.toArray();
        list.clear();
        mergeTags(b, diff, strings, list);
        merged = list.toArray();
        list.clear();
        Assert.assertTrue(Arrays.equals(a, merged));

        diffTags(a, a, strings, list);
        Assert.assertTrue(list.isEmpty());
        diffTags(b, b, strings, list);
        Assert.assertTrue(list.isEmpty());
    }

    @Test public void testLocalKeys()
    {
        String[] kv = new String[]
        {
            "banana2912", "monkey",
            "apple7391", "cherry"
        };

        String[] kv2 = new String[]
        {
            "banana2912", "monkey",
            "apple7391", "cherry",
            "aaa_grape4444", "yes"
        };


        long[] tags = fromStrings(kv, strings);
        long[] tags2 = fromStrings(kv2, strings);
        Assert.assertEquals(2, tags.length);
        Assert.assertEquals(3, tags2.length);

        testDiff(tags, tags2);
    }

}