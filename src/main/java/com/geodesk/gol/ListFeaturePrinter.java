package com.geodesk.gol;

import com.geodesk.feature.Feature;

import java.io.PrintStream;
import java.io.PrintWriter;

public class ListFeaturePrinter extends AbstractFeaturePrinter
{
    public ListFeaturePrinter(PrintStream out)
    {
        super(out);
    }
    @Override public void print(Feature feature)
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
