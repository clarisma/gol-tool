/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.soar;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;

public class StructGroup<T extends Struct> extends Struct implements Iterable<T>
{
	protected T firstChild;
	
	public StructGroup(T firstChild)
	{
		this.firstChild = firstChild;
		int pos = 0;
		Struct s = firstChild;
		while(s != null)
		{
			pos = s.alignedLocation(pos);
			pos += s.size();
			s = s.next();
		}
		setSize(pos);
		if(firstChild != null)
		{
			setAlignment(firstChild.alignment());
		}
	}
	
	public void writeTo(StructOutputStream out) throws IOException 
	{
		out.writeChain(firstChild);
	}
	
	public void setLocation(int pos)
	{
		super.setLocation(pos);
		Struct s = firstChild;
		while(s != null)
		{
			pos = s.alignedLocation(pos);
			s.setLocation(pos);
			pos += s.size();
			s = s.next();
		}
	}
	
	public Iterator<T> iterator() 
	{
		return new StructIterator<T>(firstChild);
	}

	public int countChildren()
	{
		int count = 0;
		Struct s = firstChild;
		while(s != null)
		{
			count++;
			s = s.next();
		}
		return count;
	}
	
	public void dump(PrintWriter out)
	{
		super.dump(out);
		Struct s = firstChild;
		while(s != null)
		{
			s.dump(out);
			s = s.next();
		}
	}

}
