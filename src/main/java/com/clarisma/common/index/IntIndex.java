/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.index;

import java.io.IOException;

public interface IntIndex 
{
	int get(long key) throws IOException;
	void put(long key, int value) throws IOException;
}
