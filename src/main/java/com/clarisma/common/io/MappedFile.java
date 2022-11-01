/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.io;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.SPARSE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


// TODO: mapped file segments are only released when garbage collected
//  Maybe invoke Cleaner directly when closing file?
//  See https://stackoverflow.com/a/25239834


// TODO: getMapping is not threadsafe, must be wrapped in higher-level code

public class MappedFile 
{
	protected Path path;
	private FileChannel channel;
	private List<MappedByteBuffer> mappings = new ArrayList<>();

	protected static final int MAPPING_SIZE = 1 << 30;
	
	public MappedFile(Path path) throws IOException
	{
		this.path = path;
		if(!Files.exists(path))
		{
			channel = (FileChannel)Files.newByteChannel(path, CREATE_NEW,READ,WRITE,SPARSE);
		}
		else
		{
			channel = FileChannel.open(path, READ, WRITE);
		}
	}
	
	public Path path()
	{
		return path;
	}
	
	protected MappedByteBuffer getMapping(int number) throws IOException
	{
		while(number >= mappings.size())
		{
			// log.debug("Expanding mappings of {} to {}.", path, mappings.size()+1);
			MappedByteBuffer mapping = channel.map(MapMode.READ_WRITE,
				(long)mappings.size() * MAPPING_SIZE, MAPPING_SIZE);
			mapping.order(ByteOrder.LITTLE_ENDIAN);		// TODO: check!
				// TODO: better: make it configurable
			mappings.add(mapping);
		}
		return mappings.get(number);
	}

	private boolean unmapSegments()
	{
		try
		{
			// See https://stackoverflow.com/a/19447758

			Class unsafeClass;
			try
			{
				unsafeClass = Class.forName("sun.misc.Unsafe");
			}
			catch (Exception ex)
			{
				// jdk.internal.misc.Unsafe doesn't yet have an invokeCleaner() method,
				// but that method should be added if sun.misc.Unsafe is removed.
				unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
			}
			Method clean = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
			clean.setAccessible(true);
			Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
			theUnsafeField.setAccessible(true);
			Object theUnsafe = theUnsafeField.get(null);

			for (MappedByteBuffer buf: mappings)
			{
				clean.invoke(theUnsafe, buf);
			}
			mappings.clear();
			return true;
		}
		catch (Exception ex)
		{
			return false;
		}
	}
	
	public void close() throws IOException
	{
		if(!unmapSegments()) System.err.format("Warning! Failed to unmap %s\n", path);
		channel.close();
	}
}
