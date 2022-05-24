package com.geodesk.gol;

import com.geodesk.feature.Feature;

import java.io.PrintStream;
import java.io.PrintWriter;

public class NullFeaturePrinter extends AbstractFeaturePrinter
{
    public NullFeaturePrinter()
    {
        super(null);
    }
    @Override public void print(Feature feature) {};
}
