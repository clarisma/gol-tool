/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

import com.clarisma.common.soar.Archive;
import com.clarisma.common.soar.SString;

import java.util.Collections;

public class DefaultFeatureLayout extends FeatureLayout
{
    public DefaultFeatureLayout(Archive archive)
    {
        super(archive);
    }

    private void placeSpatialIndex(SIndexTree branch)
    {
        if(branch == null) return;
        place(branch);
        SIndexTree[] children = branch.childBranches();
        if(children == null)
        {
            for(SFeature f: branch)
            {
                STagTable tags = f.tags();
                if(tags.userCount() < 3 && tags.location() == 0) place(tags);
            }
        }
        else
        {
            for (SIndexTree child : children) placeSpatialIndex(child);
        }
    }

    public void layout()
    {
        maxDrift(2048);
        for(SIndexTree root: indexes) placeSpatialIndex(root);
        flush();

        Collections.sort(sharedTags);
        for(STagTable tags: sharedTags)
        {
            assert tags.location() >= 0:
                String.format("Marked (%d) but not placed: %s", tags.location(), tags);
            if(tags.location() == 0) place(tags);
        }
        maxDrift(Integer.MAX_VALUE);        // for shared features, drift doesn't matter
        Collections.sort(sharedStrings);

        // place key strings (4-byte aligned) first; better odds of placing without gaps
        //  TODO: does it make a difference?
        for(SString s: sharedStrings)
        {
            if(s.alignment() > 0) place(s);
        }
        for(SString s: sharedStrings)
        {
            if (s.location() == 0) place(s);
        }
        for(SRelationTable rt: sharedRelTables) place(rt);
        flush();

        maxDrift(2048);
        placeFeatureBodies();
        flush();
    }
}
