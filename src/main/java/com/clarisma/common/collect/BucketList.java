package com.clarisma.common.collect;

import java.util.Iterator;

// not used
public class BucketList<E> implements Iterable<E> 
{
	private Object[] first;
	private Object[] last;
	private int bucketSize;
	private int totalSize;
	private int top;
	
	public BucketList(int bucketSize)
	{
		this.bucketSize = bucketSize;
		first = last = new Object[bucketSize];
		top = 1;
	}
	
	public void add(E elem)
	{
		if(top < bucketSize)
		{
			last[top] = elem;
			top++;
		}
		else
		{
			Object[] next = new Object[bucketSize];
			next[1] = elem;
			last[0] = next;
			last = next;
			top = 2;
		}
		totalSize++;
	}
	
	public void addAll(BucketList<E> other)
	{
		if(other.totalSize==0) return;
		if(totalSize==0)
		{
			first = other.first;
		}
		else
		{
			last[0] = other.first;
		}
		last = other.last;
		top = other.top;
		totalSize += other.totalSize;
	}
	
	public int size()
	{
		return totalSize;
	}
	
	private static class Iter<E> implements Iterator<E>
	{
		private E next;
		private Object[] bucket;
		private int index;
		
		Iter(Object[] bucket)
		{
			this.bucket = bucket;
			index = 1;
			fetchNext();
		}
		
		@SuppressWarnings("unchecked")
		private void fetchNext()
		{
			if(index < bucket.length)
			{
				next = (E)bucket[index++];
				if(next != null) return;
			}
			if(bucket[0] != null)
			{
				bucket = (Object[])bucket[0];
				index = 2;
				next = (E)bucket[1];
			}
			else
			{
				next = null;
			}
		}

		public boolean hasNext() 
		{
			return next != null;
		}

		public E next() 
		{
			E n = next;
			fetchNext();
			return n;
		}
		
	}

	public Iterator<E> iterator() 
	{
		return new Iter<E>(first);
	}
}
