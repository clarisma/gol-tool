/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.geodesk.core.Tile;
import com.geodesk.core.TileQuad;
import com.geodesk.feature.store.ZoomLevels;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// TODO: The root tile (0/0/0) is always pile 2, TIP 1; if the minimum zoom level is
//  greater than 0, we need an implicit root tile, which is stored as pile 2,
//  but has no corresponding TIP (we use this to store oversized features)
// TODO: We need a purgatory pile (always pile 1), which does not correspond
//  to a tile, but has a TIP (always TIP 0)
//  (root tile is actually TIP 2)

public class TileCatalog 
{
	/**
	 * A bit field representing the zoom levels that are in use.
	 */
	private final int zoomLevels;
	/**
	 * The minimum zoom level in use.
	 */
	private final int minZoom;
	/**
	 * The maximum zoom level in use.
	 */
	private final int maxZoom;
	/**
	 * Lookup table from tile number to pile.
	 */
	private final MutableIntIntMap tileToPile;
	/**
	 * Lookup table from tile number to TIP.
	 */
	private final MutableIntIntMap tileToTip;
	/**
     * A mapping of pile numbers to tile numbers.
	 * We use a simple array here, since pile numbers are dense and contiguous.
	 * Remember that index entry 0 is not used, as valid pile numbers start at 1.
	 */
	private final int[] pileToTile;
	
	// public static final int OVERSIZED_PILES = 3;

	// TODO: consolidate with TileArchive
	public static final int PURGATORY_TILE = 0x0f00_0000;
	public static final int PURGATORY_PILE = 1;
	public static final int PURGATORY_TIP = 0;

	private void tileCatalogError(Path tileCatalogFile, int line) throws IOException
	{
		throw new IOException(
			String.format("Tile Catalog %s invalid at line %d", tileCatalogFile, line));
	}
	
	public TileCatalog(Path tileCatalogFile) throws IOException
	{
		List<String> lines = Files.readAllLines(tileCatalogFile);
		int tileCount = lines.size() + 1;	// + Purgatory tile
		int zoomLevelsInUse = 0;
		// TODO: take load factor into consideration, or else
		//  the hashmaps will resize
		// TODO: looks like eclipse maps automatically make extra room
		tileToPile = new IntIntHashMap(tileCount);
		tileToTip = new IntIntHashMap(tileCount);
		pileToTile = new int[tileCount+1];		// entry 0 not used
		for(int i=0; i<tileCount-1; i++)
		{
			String line = lines.get(i);
			int n = line.indexOf('\t');
			if (n < 1) tileCatalogError(tileCatalogFile, i);
			String strTip = line.substring(0, n);
			String strTile = line.substring(n+1).strip();
			try
			{
				int tip = Integer.parseInt(strTip, 16);
				int tile = Tile.fromString(strTile);
				if(tip < 1 || tile < 0) tileCatalogError(tileCatalogFile, i);
				int pile = i + 2;	// account for empty 0-index and purgatory
				tileToPile.put(tile, pile);
				tileToTip.put(tile, tip);
				pileToTile[pile] = tile;
				zoomLevelsInUse |= 1 << Tile.zoom(tile);
			}
			catch(NumberFormatException ex)
			{
				tileCatalogError(tileCatalogFile, i);
			}
		}

		tileToPile.put(PURGATORY_TILE, PURGATORY_PILE);
		tileToTip.put(PURGATORY_TILE, PURGATORY_TIP);
		pileToTile[PURGATORY_PILE] = PURGATORY_TILE;

		zoomLevels = zoomLevelsInUse;
		minZoom = ZoomLevels.minZoom(zoomLevelsInUse);
		maxZoom = ZoomLevels.maxZoom(zoomLevelsInUse);
	}
	
	public int tileCount()
	{
		return pileToTile.length-1;
	}
	
	public int zoomLevels()
	{
		return zoomLevels;
	}
	
	public int minZoom()
	{
		return minZoom;
	}
	
	public int maxZoom()
	{
		return maxZoom;
	}
	
	public boolean containsTile(int tile)
	{
		return tileToPile.containsKey(tile);
	}

	/**
	 * Given a tile number, returns the number of its pile.
	 * If the tile has no corresponding pile, the pile of its parent tile
	 * is returned.
	 *
	 * TODO: the returned pile should always be valid, since every tile tree
	 *  has a root tile (either explicit or implicit)
	 *
	 * @param tile		the tile number
	 * @return
	 */
	public int resolvePileOfTile(int tile)
	{
		int pile = tileToPile.get(tile);
		if(pile > 0) return pile;
		for(int zoom=Tile.zoom(tile)-1; zoom >= minZoom; zoom--)
		{
			if((zoomLevels & (1 << zoom)) == 0) continue;
			tile = Tile.zoomedOut(tile, zoom);
			pile = tileToPile.get(tile);
			if(pile > 0) return pile;
		}
		return -1;	// TODO: check
	}
	
	public int tileOfPile(int pile)
	{
		return pileToTile[pile];
	}
	
	public int tipOfTile(int tile)
	{
		return tileToTip.get(tile);
	}
	
	public int resolvePileOfXY(int x, int y)
	{
		return resolvePileOfTile(Tile.fromXYZ(x, y, maxZoom));
	}
	
	public int pileQuadFromTileQuad(int tileQuad)
	{
		// if(tileQuad == TileQuad.OVERSIZED) return OVERSIZED_PILES;
		int pile = resolvePileOfTile(TileQuad.northWestTile(tileQuad));
		return (pile << 2) | ((tileQuad >> 29) & 3);
			// since the tileQuad is always dense, we can use the NE and SW tile bits
			// to stand in as "extends east" and "extends south"
			// TODO: assert denseness
	}
	
	public int tileQuadFromPileQuad(int pileQuad)
	{
		int tile = tileOfPile(pileQuad >>> 2);
		return TileQuad.dense(tile | TileQuad.NW | (((pileQuad) & 3) << 29));
	}

	/**
	 * Checks if all tiles in the given tile quad actually exist in the tile
	 * index; if not, returns a quad at a lower zoom level that fulfills that
	 * requirement. If a 4-tile tile quad can be represented as a single tile
	 * at the next-lower valid zoom level, that single-tile quad is returned
	 * instead (This way, a feature can be placed in a single tile, instead of
	 * having to place up to four copies).
	 *
	 * The given tile quad must itself be valid. If the tile quad is sparse,
	 * it will be treated as if it were dense.
	 *
	 * @param tileQuad	a valid tile quad (sparse or dense)
	 * @return a quad whose tiles are contained in the tile index
	 */
	public int validateTileQuad(int tileQuad)
	{
		tileQuad = TileQuad.dense(tileQuad);
		int zoom = TileQuad.zoom(tileQuad);
		int levels = zoomLevels | 1;  // zoom 0 always valid -- TODO: clean up
		boolean validQuad = false;
		while(zoom >= 0)    // TODO
		{
			if(ZoomLevels.isValidZoomLevel(levels, zoom))
			{
				int lowerTileQuad = TileQuad.zoomedOut(tileQuad, zoom);
				if((lowerTileQuad & TileQuad.SE) == 0)
				{
					// If tile quad consist of a single tile,
					// or a pair, then we're done
					return lowerTileQuad;
				}

				// If we've already found a valid 4-tile quad
				// at a higher zoom level, return that quad
				if(validQuad) return tileQuad;

				// Otherwise, check if all 4 tiles are actually
				// in the tile catalog; if not, we try again
				// at a lower zoom level

				tileQuad = lowerTileQuad;
				int col = Tile.column(tileQuad);
				int row = Tile.row(tileQuad);
				validQuad = true;
				for(int x=0; x<2; x++)
				{
					for(int y=0; y<2; y++)
					{
						if(!containsTile(Tile.fromColumnRowZoom(col+x, row+y, zoom)))
						{
							// TODO: verify, ! was missing before
							validQuad = false;
							break;
						}
					}
				}
			}
			zoom--;
		}
		return 0;   // root quad -- no! TODO: should not get here
	}
}
