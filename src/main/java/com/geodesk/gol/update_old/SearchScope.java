/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import com.clarisma.common.index.IntIndex;
import com.geodesk.geom.Heading;
import com.geodesk.geom.Tile;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.io.IOException;
import java.util.Iterator;

public class SearchScope implements Iterable<SearchTile>
{
    private final IntIndex nodeIndex;
    private final IntIndex wayIndex;
    private final IntIndex relationIndex;
    private final boolean useIdIndexes;
    private final TileCatalog tileCatalog;
    private final MutableIntObjectMap<SearchTile> tiles = new IntObjectHashMap<>();

    public SearchScope(BuildContext ctx) throws IOException
    {
        nodeIndex = ctx.getNodeIndex();
        wayIndex = ctx.getWayIndex();
        relationIndex = ctx.getRelationIndex();
        useIdIndexes = nodeIndex != null & wayIndex != null & relationIndex != null;
        tileCatalog = ctx.getTileCatalog();
    }

    private SearchTile getTile(int tile)
    {
        SearchTile st = tiles.get(tile);
        if (st == null)
        {
            st = new SearchTile(tileCatalog.tipOfTile(tile));
            tiles.put(tile, st);
        }
        return st;
    }

    public void findDuplicates(int futureX, int futureY)
    {
        // TODO: this is inefficnet, could look up tile directly
        //  without mapping pile <--> tile
        int pile = tileCatalog.resolvePileOfXY(futureX, futureY);
        int tile = tileCatalog.tileOfPile(pile);
        if(getTile(tile).addFlags(SearchTile.FIND_DUPLICATE_XY))
        {
            searchParentTiles(tile, SearchTile.FIND_DUPLICATE_XY);
        }
    }

    public void findNode(long id) throws IOException
    {
        int pile = nodeIndex.get(id);
        if(pile != 0)
        {
            int tile = tileCatalog.tileOfPile(pile);
            SearchTile t = getTile(tile);
            t.addFlags(SearchTile.FIND_NODES);
            if(t.addFlags(SearchTile.FIND_WAY_NODES))
            {
                searchParentTiles(tile, SearchTile.FIND_WAY_NODES);
            }
        }
    }

    private void findFeature(IntIndex index, long id, int flags) throws IOException
    {
        int pileQuad = index.get(id);
        if(pileQuad != 0)
        {
            int tile = tileCatalog.tileOfPile(pileQuad >>> 2);
            if ((pileQuad & 3) == 3)
            {
                // For features with > 2 tiles, we need to also scan
                // the adjacent tile (in case `tile` refers to the
                // empty quadrant of a sparse quad)
                SearchTile t = getTile(tile);
                t.addFlags(flags);
                tile = Tile.neighbor(tile, Heading.EAST);
            }
            SearchTile t = getTile(tile);
            t.addFlags(flags);
        }
    }

    public void findWay(long id) throws IOException
    {
        findFeature(wayIndex, id, SearchTile.FIND_WAYS);
    }

    public void findRelation(long id) throws IOException
    {
        findFeature(relationIndex, id, SearchTile.FIND_RELATIONS);
    }

    private void searchParentTiles(int childTile, int flags)
    {
        if (Tile.zoom(childTile) == 0) return;
        int parentTile = tileCatalog.parentTile(childTile);
        SearchTile tile = getTile(parentTile);
        if(!tile.addFlags(flags)) return;
        searchParentTiles(parentTile, flags);
    }

    @Override public Iterator<SearchTile> iterator()
    {
        return tiles.iterator();
    }

    public int size()
    {
        return tiles.size();
    }
}
