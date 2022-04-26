package com.clarisma.common.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import com.clarisma.common.io.MappedFile;

public class DensePackedIntIndex extends MappedFile implements IntIndex
{
	private int bits;
	private int slotsPerBlock;
	private int mask;
	
	private static final int BLOCK_SIZE = 4096;
	private static final int BLOCKS_PER_PAGE = MAPPING_SIZE / BLOCK_SIZE;
	
	public DensePackedIntIndex(Path path, int bits) throws IOException 
	{
		super(path);
		this.bits = bits;
		slotsPerBlock = BLOCK_SIZE * 8 / bits;
		mask = 0xffff_ffff >>> (32 - bits);
	}
	
	public int get(long key) throws IOException
	{
		long block = key / slotsPerBlock;
		int page = (int)(block / BLOCKS_PER_PAGE);
		int blockInPage = (int)(block % BLOCKS_PER_PAGE);
		int slot = (int)(key % slotsPerBlock);
		int byteOffset = slot * bits / 8;
		int bitShift = slot * bits - byteOffset * 8;
		int overrun = Math.max(byteOffset - (BLOCK_SIZE-4), 0);
		byteOffset -= overrun;
		bitShift += overrun * 8;
		return (getMapping(page).getInt(blockInPage * BLOCK_SIZE + byteOffset) 
			>>> bitShift) & mask;
	}
	
	public void put(long key, int value) throws IOException
	{
		assert (value & mask) == value; 
		long block = key / slotsPerBlock;
		int page = (int)(block / BLOCKS_PER_PAGE);
		int blockInPage = (int)(block % BLOCKS_PER_PAGE);
		int slot = (int)(key % slotsPerBlock);
		int byteOffset = slot * bits / 8;
		int bitShift = slot * bits - byteOffset * 8;
		int overrun = Math.max(byteOffset - (BLOCK_SIZE-4), 0);
		byteOffset -= overrun;
		bitShift += overrun * 8;
		ByteBuffer mapping = getMapping(page);
		int pos = blockInPage * BLOCK_SIZE + byteOffset;
		mapping.putInt(pos, mapping.getInt(pos) & ~(mask << bitShift) | (value << bitShift));
	}

}
