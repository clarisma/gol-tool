package com.clarisma.common.collect;

// TODO: establish if single or double linked

// only used by gol tool

@SuppressWarnings("rawtypes")
public abstract class Linked<T extends Linked> 
{
	protected T next;
	protected T prev;
	
	@SuppressWarnings("unchecked")
	protected Linked()
	{
		next = (T)this;
		prev = (T)this;
	}
	
	public final T next() 
	{
		return next;
	}

	public final T prev() 
	{
		return prev;
	}

	@SuppressWarnings("unchecked")
	public void remove()
	{
		T oldPrev = prev;
		if (prev != null) 
		{
			prev.next = next;
			prev = null;
		}
		if (next != null) 
		{
			next.prev = oldPrev;
			next = null;
		}
	}
	
	@SuppressWarnings("unchecked")
	public void append(T sibling)
	{
		assert sibling != this;
		// T oldNext = next;
		sibling.remove();
		sibling.next = next; // oldNext;
		sibling.prev = this;
		// if(next != null) next.prev = sibling;
		next.prev = sibling;
		next = sibling;
	}
	
	@SuppressWarnings("unchecked")
	public void prepend (T sibling)
	{
		assert sibling != this;
		// T oldPrev = prev;
		sibling.remove();
		sibling.next = this;
		sibling.prev = prev; // oldPrev;
		// if(prev != null) prev.next = sibling;
		// oldPrev.next = sibling;
		prev.next = sibling;
		prev = sibling;
	}
	
	@SuppressWarnings("unchecked")
	public boolean contains(T item)
	{
		T first = (T)this;
		T current = (T)this;
		for(;;)
		{
			if(current==item) return true;
			current = (T)current.next;
			if(current==first) return false;
		}
	}
	
	public static <T extends Linked<T>> T removeFirst(T first)
	{
		T next = first.next;
		first.remove();
		return (first==next) ? null : next;
	}
}
