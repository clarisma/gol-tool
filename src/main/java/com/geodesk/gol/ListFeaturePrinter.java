package com.geodesk.gol;

import com.geodesk.feature.Feature;

import java.io.PrintWriter;

public class ListFeaturePrinter implements FeaturePrinter
{
    @Override public void print(PrintWriter out, Feature feature)
    {
        char letter = switch(feature.type())
        {
        case NODE -> 'N';
        case WAY -> 'W';
        case RELATION -> 'R';
        };
        out.format("%c%d\n", letter, feature.id());
    }
}
