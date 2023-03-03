package com.geodesk.gol.update_old;

import com.geodesk.feature.FeatureLibrary;
import com.geodesk.gol.build.TileCatalog;
import org.junit.Test;

public class ChangeGraphTest
{
    /*

    @Test public void testRead() throws Exception
    {
        ChangeGraph graph = new ChangeGraph();
        graph.read("c:\\geodesk\\research\\de-3531.osc");
    }

     */

    /* @Test */ public void testTileCatalog() throws Exception
    {
        FeatureLibrary features = new FeatureLibrary("c:\\geodesk\\tests\\de-update.gol");
        TileCatalog tc = new TileCatalog(features.store());
        tc.write("c:\\geodesk\\tests\\de-update-tile-catalog-from-gol.txt");
    }
}