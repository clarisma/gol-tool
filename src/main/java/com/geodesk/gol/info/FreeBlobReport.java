/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.info;

import com.clarisma.common.store.BlobStore;
import com.clarisma.common.store.BlobStoreInfo;
import com.clarisma.common.text.Table;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.IntIntMap;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public class FreeBlobReport extends Table
{
    public FreeBlobReport(BlobStore store)
    {
        column().format("###,###,###,### KB");
        column().format("###,###,###,###");
        column().format("###,###,###,### KB");
        column().format("##0.0%");

        add("Free block");
        add("Count");
        add("Total");
        divider("=");

        createReport(store);
    }

    private void createReport(BlobStore store)
    {
        final int pageSize = store.pageSize();
        IntIntMap stats = BlobStoreInfo.getFreeBlobStatistics(store);
        MutableLongList sorted = new LongArrayList(stats.size());
        stats.forEachKeyValue((size,count) -> sorted.add((((long)size) << 32) | count));
        sorted.sortThis();
        long totalFreePages = 0;
        for(int i=0; i<sorted.size(); i++)
        {
            long v = sorted.get(i);
            totalFreePages += (v >>> 32) * (int)v;
        }
        long totalFreeKB = totalFreePages * pageSize / 1024;
        int totalCount = 0;
        for(int i=0; i<sorted.size(); i++)
        {
            long v = sorted.get(i);
            int sizeInPages = (int)(v >>> 32);
            int count = (int)v;
            long sizeInKB = (long)sizeInPages * pageSize / 1024;
            add(sizeInKB);
            add(count);
            totalCount += count;
            long subTotalInKB = sizeInKB * count;
            add(subTotalInKB);
            add((double)subTotalInKB / totalFreeKB);
        }
        divider("-");
        add("Total free");
        add(totalCount);
        add(totalFreeKB);
        add(1);
    }
}
