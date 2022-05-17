package com.clarisma.common.io;

import java.io.IOException;
import java.nio.file.Paths;

import com.clarisma.common.util.Log;
import org.junit.Test;
import org.locationtech.jts.util.Stopwatch;

public class MappedFileTest 
{
	@Test
	public void test() throws IOException  
	{
		int gigs = 1024;
		Stopwatch timer = new Stopwatch();
		timer.start();
		MappedFile mf = new MappedFile(Paths.get("c:\\velojoe\\tests\\mf-test.dat"));
		for(int i=0; i<gigs; i++)
		{
			mf.getMapping(i);
		}
		Log.debug("Mapped %d GB in %d ms\n", gigs, timer.stop());
	}

}
