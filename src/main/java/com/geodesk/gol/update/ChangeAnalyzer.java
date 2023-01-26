/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.geodesk.gol.TaskEngine;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public class ChangeAnalyzer extends TaskEngine<ChangeAnalyzer.CTile>
{
    private final ChangeGraph graph;

    protected ChangeAnalyzer(ChangeGraph graph)
    {
        super(new CTile(), 2, false);
        this.graph = graph;
    }

    @Override protected TaskEngine<CTile>.WorkerThread createWorker()
    {
        return new Worker();
    }

    protected class Worker extends WorkerThread
    {
        /**
         * A list of all the features for which we've found a past version.
         * Even positions contain the typed feature ID, odd positions contain
         * the TIP (upper part) and offset (lower part) of the feature.
         */
        private MutableLongList featuresFound = new LongArrayList();

        @Override protected void process(CTile task) throws Throwable
        {

        }
    }

    protected static class CTile
    {
        static final int FIND_NODES     = 1;
        static final int FIND_WAYS      = 1 << 1;
        static final int FIND_RELATIONS = 1 << 2;
    }


}
