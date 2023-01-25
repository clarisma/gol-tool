/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.geodesk.feature.store.FeatureStore;
import com.geodesk.gol.build.KeyIndexSchema;
import com.geodesk.gol.build.Project;
import org.eclipse.collections.api.map.primitive.IntIntMap;

public class IndexSettings
{
    public final int rtreeBucketSize;;
	public final int maxKeyIndexes;
	public final int keyIndexMinFeatures;
    public final int maxIndexedKey;
        // TODO: better names, easy to confuse
    public final IntIntMap keysToCategory;

    public IndexSettings(FeatureStore store, Project settings)
    {
        this.rtreeBucketSize = settings.rtreeBucketSize();
        this.maxKeyIndexes = settings.maxKeyIndexes();
        this.keyIndexMinFeatures = settings.keyIndexMinFeatures();
        keysToCategory = store.keysToCategories();
        maxIndexedKey = keysToCategory.keySet().max();
    }

}
