/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.soar;

import java.util.Iterator;

public class StructIterator<T extends Struct> implements Iterator<T> 
{
	T current;
	
	public StructIterator(T first)
	{
		current = first;
	}
	
	public boolean hasNext() 
	{
		return current != null;
	}

	@SuppressWarnings("unchecked")
	public T next() 
	{
		T s = current;
		current = (T)current.next();
		return s;
	}

}
