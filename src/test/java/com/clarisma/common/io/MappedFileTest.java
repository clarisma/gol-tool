package com.clarisma.common.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.clarisma.common.util.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.jts.util.Stopwatch;

public class MappedFileTest 
{
	Path mappedFile;

	// Don't run this test as a unit test, it allocates too much storage

	/*
	@Before public void setUp() throws IOException
	{
		mappedFile = Files.createTempFile("mf-test", ".dat");
		Files.delete(mappedFile);
			// Let MappedFile re-create this file, or else it won't be sparse
	}

	@After public void tearDown() throws IOException
	{
		Files.deleteIfExists(mappedFile);
	}

	private void mapIt(int gigs) throws IOException
	{
		Stopwatch timer = new Stopwatch();
		timer.start();

		Log.debug("Test file: " + mappedFile);
		MappedFile mf = new MappedFile(mappedFile);
		for(int i=0; i<gigs; i++)
		{
			mf.getMapping(i);
		}
		Log.debug("Mapped %d GB in %d ms\n", gigs, timer.stop());
		mf.close();
	}

	@Test public void test() throws IOException
	{
		int gigs = 128; //24;
		Log.debug("New test file: " + mappedFile);
		mapIt(gigs);
		Log.debug("Existing file: " + mappedFile);
		mapIt(gigs);
	}
	 */
}
