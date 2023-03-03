package com.geodesk.gol.update_old;

import org.junit.Test;

public class ChangeSetAnalyzerTest
{
    /* @Test */ public void testAnalyze() throws Exception
    {
        ChangeSetAnalyzer reader = new ChangeSetAnalyzer();
        reader.read("c:\\geodesk\\research\\de-3531.osc");
        reader.report();
    }

}