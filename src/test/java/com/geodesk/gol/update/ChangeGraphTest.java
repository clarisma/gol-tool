package com.geodesk.gol.update;

import org.junit.Test;

public class ChangeGraphTest
{
    @Test public void testRead() throws Exception
    {
        ChangeGraph graph = new ChangeGraph();
        graph.read("c:\\geodesk\\research\\de-3531.osc");
    }

}