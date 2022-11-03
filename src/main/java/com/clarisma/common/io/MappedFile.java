/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.io;

import com.clarisma.common.store.StoreException;

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
import java.util.Arrays;
import java.util.List;


// TODO: mapped file segments are only released when garbage collected
//  Maybe invoke Cleaner directly when closing file?
//  See https://stackoverflow.com/a/25239834


// TODO: getMapping is not threadsafe, must be wrapped in higher-level code

public class MappedFile 
{
	protected Path path;
	private FileChannel channel;

	// private List<MappedByteBuffer> mappings = new ArrayList<>();
	private volatile MappedByteBuffer[] mappings = new MappedByteBuffer[0];
	private final Object mappingsLock = new Object();


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

	/**
	 * Gets the ByteBuffer for the requested segment. If the segment is not
	 * already mapped, a new mapping is created.
	 *
	 * Note: The old version is NOT THREADSAFE. This is per design, as this
	 * is a low-level component, and synchronization is supposed to be
	 * handled at a higher level. We don't want to synchronize this because
	 * of the higher number of concurrent reads.
	 *
	 * A potential issue arises in Sorter: When writing indexes for
	 * nodes, ways and relations, access is always synchronized -- only one
	 * thread writes to the index. After the index has been created, access
	 * is no longer synchronized, since all further usage is read-only.
	 * But what if the Sorter tries to look up an element that is NOT in the
	 * index? Potentially, this element's index position lies in a segment
	 * that has not yet been mapped; at this point, the list of mapped segments
	 * is mutated and we've got a race condition. We could prevent this by
	 * not allowing access to index positions above a "high-water mark"
	 * established during index creation -- however, the cleaner way is to
	 * adopt the (safe) double-checked locking approach used in `Store`.
	 *
	 * @param n		the segment number
	 * @return		the MappedByteBuffer for the requested segment
	 * @throws IOException
	 */

	// TODO: should make MappedFile a base class of Store, so this code is shared
	// TODO: map segments lazily?
	// TODO: consider option to map in read-only mode
	protected MappedByteBuffer getMapping(int n)
	{
		MappedByteBuffer[] a = mappings;
		MappedByteBuffer buf;
		if (n < a.length && (buf = a[n]) != null) return buf;
		return mapSegment(n);
	}

	// breaking out the mapping method increases the odds that the
	// hot common-case path of getMapping() gets inlined
	// TODO: bring this change to Store

	private MappedByteBuffer mapSegment(int n)
	{
		synchronized (mappingsLock)
		{
			// Read array and perform check again
			MappedByteBuffer[] a = mappings;
			MappedByteBuffer buf;
			int len = a.length;
			if(n >= len)
			{
				a = Arrays.copyOf(a, n + 1);
			}
			else
			{
				buf = a[n];
				if (buf != null) return buf;
				a = Arrays.copyOf(a, a.length);
			}
			try
			{
				// Log.debug("Mapping segment %d...", i);
				buf = channel.map(
					FileChannel.MapMode.READ_WRITE,
					(long) n * MAPPING_SIZE, MAPPING_SIZE);
			}
			catch(IOException ex)
			{
				throw new RuntimeException(
					String.format("%s: Failed to map segment at %X (%s)",
						path, (long)n * MAPPING_SIZE, ex.getMessage()), ex);
					// TODO: note that we throw a different exception in
					//  the code for Store (StoreException)
			}

			buf.order(ByteOrder.LITTLE_ENDIAN);		// TODO: check!
			// TODO: better: make it configurable
			a[n] = buf;
			mappings = a;
			return buf;
		}
	}

	/*	// OLD VERSION -- read note above
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
	 */

	private boolean unmapSegments()
	{
		// Log.debug("unmapping segments");

		synchronized (mappingsLock)
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

				MappedByteBuffer[] a = mappings;
				for (int i = 0; i < a.length; i++)
				{
					MappedByteBuffer buf = a[i];
					if(buf != null) clean.invoke(theUnsafe, buf);
					// TODO: set array entry to null just in case one of the
					//  cleaner invocations fails?
				}
				mappings = new MappedByteBuffer[0];
				return true;
			}
			catch (Exception ex)
			{
				return false;
			}
		}
	}

	/*	// OLD VERSION
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
	 */
	
	public void close() throws IOException
	{
		if(!unmapSegments()) System.err.format("Warning! Failed to unmap %s\n", path);
		channel.close();
	}
}
