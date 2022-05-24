package com.geodesk.gol;

import com.geodesk.feature.Feature;

import java.io.PrintStream;
import java.io.PrintWriter;

public interface FeaturePrinter
{
    default void printHeader() {};
    void print(Feature feature);
    default void printFooter() {};
}
