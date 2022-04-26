package com.clarisma.common.collect;

// not used
public class IntQueue
{
	private Block firstBlock;
	private Block lastBlock;
	private int blockSize;
	private int readPos;
	private int writePos;
	
	private static class Block
	{
		Block next;
		int[] content;
		
		Block(int blockSize)
		{
			content = new int[blockSize];
		}
	}
	
	public IntQueue(int blockSize)
	{
		this.blockSize = blockSize;
		firstBlock = lastBlock = new Block(blockSize);
	}
	
	public void write(int v)
	{
		if(writePos == blockSize)
		{
			Block b = new Block(blockSize);
			lastBlock.next = b;
			lastBlock = b;
			writePos = 0;
		}
		lastBlock.content[writePos++] = v;
	}
	
	public int read()
	{
		assert !isEmpty();
		if(readPos == blockSize) 
		{
			firstBlock = firstBlock.next;
			readPos = 0;
		}
		return firstBlock.content[readPos++];
	}
	
	public void writeLong(long v)
	{
		write((int)v);
		write((int)(v >>> 32));
	}
	
	public long readLong()
	{
		return (((long)read()) & 0xffff_ffffl)  | (((long)read()) << 32);
	}
	
	
	public boolean isEmpty()
	{
		return firstBlock == lastBlock && readPos == writePos;
	}
	
	public void clear()
	{
		firstBlock = lastBlock;
		firstBlock.next = null;
		readPos = writePos = 0;
	}
}
