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
