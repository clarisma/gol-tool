package com.clarisma.common.index;

import java.io.IOException;
import java.nio.file.Path;

import com.clarisma.common.io.MappedFile;

// TODO: -> com.clarisma.common.storage

public class DenseInt16Index extends MappedFile implements IntIndex
{
	public DenseInt16Index(Path path) throws IOException 
	{
		super(path);
	}
	
	public int get(long key) throws IOException
	{
		long pos = key << 1;
		int page = (int)(pos / MAPPING_SIZE);
		int offset = (int)(pos % MAPPING_SIZE);
		return getMapping(page).getShort(offset) & 0xffff;
	}

	public void put(long key, int value) throws IOException
	{
		long pos = key << 1;
		int page = (int)(pos / MAPPING_SIZE);
		int offset = (int)(pos % MAPPING_SIZE);
		getMapping(page).putShort(offset, (short)value);
	}

}
