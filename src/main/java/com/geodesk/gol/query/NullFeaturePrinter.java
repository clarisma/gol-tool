/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.geodesk.feature.Feature;

public class NullFeaturePrinter extends AbstractFeaturePrinter
{
    public NullFeaturePrinter()
    {
        super(null);
    }
    @Override public void print(Feature feature) {};
}
