/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.info;

import com.clarisma.common.store.BlobStoreConstants;
import com.clarisma.common.text.Table;
import com.geodesk.core.Tile;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.TileIndexWalker;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

import java.nio.ByteBuffer;

public class TileReport extends Table
{
    private final FeatureStore store;
    private final int pageSize;
    final long totalPages;
    private int smallestSizeInPages = Integer.MAX_VALUE;
    private int largestSizeInPages = Integer.MIN_VALUE;

    public TileReport(FeatureStore store, TileIndexWalker walker)
    {
        this.store = store;
        pageSize = store.pageSize();
        totalPages = store.baseMapping().getInt(BlobStoreConstants.TOTAL_PAGES_OFS);

        column();
        column().format("###,###,###,###");
        column().format("###,###,###,###");
        column().format("###,###,###,### KB");
        column().format("###,###,###,### KB");
        column().format("###,###,###,### KB");
        column().format("###,###,###,### KB");
        column().format("##0.00%");

        add("");
        add("Loaded");
        add("Count");
        add("Min KB");
        add("Max KB");
        add("Avg KB");
        add("Total KB");
        divider("=");

        createReport(walker);
    }

    private int fetchSizeInPages(int page)
    {
        ByteBuffer buf = store.bufferOfPage(page);
        int ofs = store.offsetOfPage(page);
        int sizeInPages = ((buf.getInt(ofs) & BlobStoreConstants.PAYLOAD_SIZE_MASK) +
            pageSize + 3) / pageSize;
        if(sizeInPages < smallestSizeInPages) smallestSizeInPages = sizeInPages;
        if(sizeInPages > largestSizeInPages) largestSizeInPages = sizeInPages;
        return sizeInPages;
    }

    private long sizeInKB(long pages)
    {
        return pages * pageSize / 1024;
    }

    private void addSize(long pages)
    {
        add(sizeInKB(pages));
        add((double)pages / totalPages);
    }

    private void createReport(TileIndexWalker walker)
    {
        MutableLongList tiles = new LongArrayList();
        long totalTilePages = 0;

        int loadedTileCount = 0;
        while(walker.next())
        {
            int tile = walker.tile();
            int page = walker.tilePage();
            int sizeInPages;
            if(page == 0)
            {
                sizeInPages = 0;
            }
            else
            {
                sizeInPages = fetchSizeInPages(page);
                loadedTileCount++;
            }
            tiles.add(((long)Tile.zoom(tile) << 32) | sizeInPages);
            totalTilePages += sizeInPages;
        }
        tiles.sortThis();

        int purgatoryPage = store.baseMapping().getInt(store.tileIndexPointer()) >>> 1;
        int purgatorySizeInPages = 0;
        if(purgatoryPage != 0)
        {
            purgatorySizeInPages = fetchSizeInPages(purgatoryPage);
            totalTilePages += purgatorySizeInPages;
            loadedTileCount++;
        }

        add("Tiles");
        add(loadedTileCount);
        add(tiles.size() + 1);
        add(sizeInKB(smallestSizeInPages));
        add(sizeInKB(largestSizeInPages));
        add(loadedTileCount == 0 ? 0 : sizeInKB(totalTilePages / loadedTileCount));
        addSize(totalTilePages);

        int currentZoom = 0;
        int start = 0;
        int end = 0;
        while(start < tiles.size())
        {
            int nextZoom = -1;
            for(;;)
            {
                end++;
                if(end >= tiles.size()) break;
                nextZoom = (int)(tiles.get(end) >>> 32);
                if(nextZoom != currentZoom) break;
            }
            add("  Zoom " + currentZoom);
            int n = start;
            while(n < end)
            {
                if((int)tiles.get(n) > 0) break;
                n++;
            }
            long totalZoomPages = 0;
            for(int i=n; i<end; i++)
            {
                totalZoomPages += (int)tiles.get(i);
            }
            int loadedTilesAtZoom = end - n;

            add(loadedTilesAtZoom);
            add(end - start);
            add(sizeInKB((int)tiles.get(n)));
            add(sizeInKB((int)tiles.get(end-1)));
            add(sizeInKB(totalZoomPages / loadedTilesAtZoom));
            addSize(totalZoomPages);

            currentZoom = nextZoom;
            start = end;
        }
        add("  Purgatory");
        add(purgatorySizeInPages > 0 ? 1 : 0);
        add(1);
        long purgatorySizeInKB = sizeInKB(purgatorySizeInPages);
        add(purgatorySizeInKB);
        add(purgatorySizeInKB);
        add(purgatorySizeInKB);
        addSize(purgatorySizeInPages);

        int metaDataSizeInBytes = store.baseMapping().getInt(BlobStoreConstants.METADATA_SIZE_OFS);
        int tileIndexSizeInPages = (metaDataSizeInBytes + pageSize - 1) / pageSize;
        add("GOL Metadata");
        add("");
        add("");
        add("");
        add("");
        add("");
        addSize(tileIndexSizeInPages);

        long emptyPages = totalPages - totalTilePages - tileIndexSizeInPages;
        add("Free pages");
        add("");
        add("");
        add("");
        add("");
        add("");
        addSize(emptyPages);

        divider("-");
        add("Total");
        add("");
        add("");
        add("");
        add("");
        add("");
        addSize(totalPages);
    }
}
