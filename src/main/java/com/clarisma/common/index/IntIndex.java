package com.clarisma.common.index;

import java.io.IOException;

public interface IntIndex 
{
	int get(long key) throws IOException;
	void put(long key, int value) throws IOException;
}
