package com.clarisma.common.index;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import com.clarisma.common.util.Log;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.factory.primitive.LongSets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DensePackedIntIndexTest 
{
	Path indexFile;

	@Before public void setUp() throws IOException
	{
		indexFile = Files.createTempFile("index-test", ".idx");
		Files.delete(indexFile);
			// Let MappedFile re-create this file, or else it won't be sparse
	}

	@After public void tearDown() throws IOException
	{
		Files.deleteIfExists(indexFile);
	}

	@Test
	public void test() throws IOException 
	{
		int bits = 18;
		// long maxKeyRange = (long)1 << 32;
		int maxValueRange = 1 << (bits-1);
		int count = 1_000_000;
		int range = 1;

		IntIndex index = new DensePackedIntIndex(indexFile, 18);
		Log.debug("Created %s.", indexFile);
		
		/*
		long kx = 2845508447l;
		int vx = 110220;
		index.put(kx, vx);
		assert index.get(kx) == vx;
		*/
		
		MutableLongSet usedKeys = LongSets.mutable.empty();
		
		Random r = new Random();
		long[] keys = new long[count];
		int[] values = new int[count];
		for(int i=0; i<count; i++)
		{
			long k = 0;
			for(;;)
			{
				k = ((long)r.nextInt(8_000_000)) & 0xffff_ffffl;
				if(!usedKeys.contains(k))
				{
					usedKeys.add(k);
					break;
				}
			}
			int v = r.nextInt(maxValueRange);
			keys[i] = k;
			values[i] = v; 
			for(int i2=0; i2<range; i2++)
			{
				index.put(k+i2, v+i2);
			}
		}
		for(int i=0; i<count; i++)
		{
			long k = keys[i];
			int v = values[i];
			for(int i2=0; i2<range; i2++)
			{
				int sv = index.get(k+i2); 
				assert sv == v+i2:
					String.format("Expected %d but got %d at index position %d",
						v+i2, sv, k+i2);
			}
		}
		Log.debug("Tested %d keys.", usedKeys.size());

		((DensePackedIntIndex)index).close();
	}

}
