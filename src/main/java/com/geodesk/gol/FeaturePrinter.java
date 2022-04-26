package com.geodesk.gol;

import com.geodesk.feature.Feature;

import java.io.PrintWriter;

public interface FeaturePrinter
{
    default void printHeader(PrintWriter out) {};
    void print(PrintWriter out, Feature feature);
    default void printFooter(PrintWriter out) {};
    default void useKeys(String[] keys) {};
}
