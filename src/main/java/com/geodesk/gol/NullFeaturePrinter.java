package com.geodesk.gol;

import com.geodesk.feature.Feature;

import java.io.PrintWriter;

public class NullFeaturePrinter implements FeaturePrinter
{
    @Override public void print(PrintWriter out, Feature feature) {};
}
