/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.text.Format;

public class ProgressReporter
{
    private final String progressVerb;
    private final String resultVerb;
    private final String unitsNoun;
    private final int totalUnits;
    private final long startTime;
    private int unitsProcessed;
    private int percentageReported;

    public ProgressReporter(int totalUnits, String unitsNoun, String progressVerb, String resultVerb)
    {
        this.totalUnits = totalUnits;
        this.progressVerb = progressVerb;
        this.unitsNoun = unitsNoun;
        this.resultVerb = resultVerb;
        startTime = System.currentTimeMillis();
    }

    public void progress(int units)
    {
        if(progressVerb != null)
        {
            synchronized (this)
            {
                unitsProcessed += units;
                int percentageCompleted = (int)(unitsProcessed * 100 / totalUnits);
                if (percentageCompleted != percentageReported)
                {
                    System.err.format("%s... %d%%\r", progressVerb, percentageCompleted);
                    percentageReported = percentageCompleted;
                }
            }
        }
    }

    public void finished()
    {
        if(resultVerb != null)
        {
            long endTime = System.currentTimeMillis();
            System.err.format("%s %d %s in %s\n", resultVerb, totalUnits,
                unitsNoun, Format.formatTimespan(endTime - startTime));
        }
    }
}
