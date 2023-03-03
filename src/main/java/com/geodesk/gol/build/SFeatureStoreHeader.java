/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.soar.Struct;

import java.nio.ByteBuffer;

import static com.geodesk.feature.store.FeatureStoreConstants.*;

public class SFeatureStoreHeader extends SBlobStoreHeader
{
    private Project project;
    public Struct tileIndex;
    public Struct stringTable;
    public Struct indexSchema;

    public SFeatureStoreHeader(Project project)
    {
        this.project = project;
    }

    @Override protected void writeClientHeader(ByteBuffer buf)
    {
        buf.putInt(MAGIC_CODE_OFS, MAGIC);
        buf.putInt(VERSION_OFS, VERSION);
        buf.putInt(ZOOM_LEVELS_OFS, project.zoomLevels());

        // should pointers be absolute?
        buf.putInt(TILE_INDEX_PTR_OFS, tileIndex.location() - TILE_INDEX_PTR_OFS);
        buf.putInt(STRING_TABLE_PTR_OFS, stringTable.location() - STRING_TABLE_PTR_OFS);
        buf.putInt(INDEX_SCHEMA_PTR_OFS, indexSchema.location() - INDEX_SCHEMA_PTR_OFS);
    }
}
