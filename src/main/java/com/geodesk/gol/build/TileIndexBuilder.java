/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.soar.Archive;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.geom.Tile;
import com.geodesk.geom.TileQuad;
import com.geodesk.feature.store.ZoomLevels;
import com.geodesk.util.MapMaker;
import com.geodesk.util.Marker;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;


// TODO: if max-tiles is power of 2, reduce by 1, this way indexing is more
//  efficient since the addressable range includes 0 (= no tile)
//  e.g. 8 bits can store 256 values: 255 tiles + 0-entry

// TODO: What guarantees can we make about the order of tiles in the Tile Catalog?
//  Are TIPs always in ascending order?
//  --> No guarantees about layout
//  --> Pile numbers follow index traversal order (depth-first, west, south)

public class TileIndexBuilder 
{
	private int zoomLevels;
	private int minZoom;
	private STile root;

	public TileIndexBuilder()
	{
	}

	public STile root()
	{
		return root;
	}

	private static class STile extends Struct implements Comparable<STile>
	{
		int tile;
		long totalCount;
		long childCount;
		STile parent;
		STile[] children;
		
		STile(int tile, long count)
		{
			this.tile = tile;
			this.totalCount = count;
		}
		
		void addChild(STile child)
		{
			int childZoom = Tile.zoom(child.tile);
			int step = childZoom - Tile.zoom(tile);
			assert step > 0 || childZoom==0;  // TODO: double-check when we add 0/0/0
			if(children==null) 
			{
				children = new STile[(1 << step) << step];
			}
			int left = Tile.column(tile) << step;
			int top  = Tile.row(tile) << step;
			int width = 1 << step;
			int pos = (Tile.row(child.tile)-top) * width + (Tile.column(child.tile)-left);
			assert children[pos] == null;
			children[pos] = child;
			childCount += child.totalCount;
		}
		
		public void build()
		{
			if(children == null) return;
			int childCount = 0;
			setAlignment(2);
			if(parent == null)
			{
				// Root
				setSize(children.length * 4 + 4);	// include TIP for Purgatory
				return;
			}
			for(STile child: children) if(child != null) childCount++;
			assert childCount > 0;
			setSize((children.length > 32 ? 12 : 8) + childCount * 4);
		}

		private void writeTile(StructOutputStream out, STile tile) throws IOException
		{
			if(tile.children == null)
			{
				out.writeInt(0);
				return;
			}
			out.writePointer(tile, 1);
			// TODO: sparse vs. dense tiles?
		}
		
		@Override
		public void writeTo(StructOutputStream out) throws IOException 
		{
			if(parent == null)
			{
				// Root
				out.writeInt(0);		// TIP for Purgatory
				for(STile child: children) writeTile(out, child);
				return;
			}
			out.writeInt(0);					// the page of the tile (initially 0)
			long tilesUsed = 0;
			for(int i=0; i<children.length; i++)
			{
				if(children[i] != null) 
				{
					tilesUsed |= 1L << i;
				}
			}
			if(children.length > 32)
			{
				out.writeLong(tilesUsed);
			}
			else
			{
				out.writeInt((int)tilesUsed);
			}
			for(STile child: children)
			{
				if(child != null) writeTile(out, child);
			}
		}

		@Override
		public int compareTo(STile o) 
		{
			return Integer.compare(tile, o.tile);
		}
		
		private void writeCatalogEntry(PrintWriter out, int tip, int tile)
		{
			out.format("%06X\t%s\n", tip, Tile.toString(tile));
		}

		// TODO: should we produce a TileCatalog instead, and let the TC
		//  write to a file?
		public void writeToCatalog(PrintWriter out, int indexStart)
		{
			int tip = (location() - indexStart) / 4; // TODO: was + 1;
			if(parent != null)
			{
				writeCatalogEntry(out, tip, tile);
				tip += children.length > 32 ? 3 : 2;
			}
			for(STile child: children)
			{
				if(child != null)
				{
					if(child.children == null)
					{
						writeCatalogEntry(out, tip, child.tile);
					}
					tip++;
				}
			}
		}

	}
	
	private int compareTilesByDensity(STile a, STile b)
	{
		int zoomA = Tile.zoom(a.tile);
		int zoomB = Tile.zoom(b.tile);
		if (zoomA != zoomB)
		{
			if(zoomA == minZoom) return -1; 
			if(zoomB == minZoom) return 1;
		}
		return Long.compare(b.totalCount, a.totalCount);
	}

	private STile addParentTiles(List<STile> tiles)
	{
		STile root = new STile(0,0);
		MutableIntObjectMap<STile> parentTiles;
		Collection<STile> childTiles = tiles;
		int minZoom = ZoomLevels.minZoom(zoomLevels);
		int parentZoom = 11;	// TODO: make more flexible?
		for(;;)
		{
			parentTiles = new IntObjectHashMap<>();
			while(!ZoomLevels.isValidZoomLevel(zoomLevels, parentZoom)) parentZoom--;
			for(STile ct: childTiles)
			{
				int parentTile = Tile.zoomedOut(ct.tile, parentZoom);
				STile pt = parentTiles.get(parentTile);
				if(pt == null)
				{
					pt = new STile(parentTile, 0);
					parentTiles.put(parentTile, pt);
				}
				// We don't actually add the child to the parent yet,
				// we just mark the parent
				ct.parent = pt;
				pt.totalCount += ct.totalCount;
			}
			childTiles = parentTiles.values();
			tiles.addAll(childTiles);
			if(parentZoom == minZoom)
			{
				int extent = 1 << minZoom;
				for(int col=0; col<extent; col++)
				{
					for(int row=0; row<extent; row++)
					{
						int parentTile = Tile.fromColumnRowZoom(col, row, parentZoom);
						STile pt = parentTiles.get(parentTile);
						if(pt == null)
						{
							pt = new STile(parentTile, 0);
							tiles.add(pt);
						}
						pt.parent = root;
					}
				}
				return root;
			}
			parentZoom--;
		}
	}

    /**
     * Reads a text file that contains the feature density (approximated by
	 * the number of nodes) for each tile at zoom level 12. The file contains
	 * one row per tile:
	 *
	 * <pre>
	 *   column, row, node_count
	 * </pre>
	 *
	 * <code>column</code> and <code>row</code> are numbers between 0 and 4095
	 * (inclusive). <code>node_count</code> is a positive long integer.
	 * Empty tiles can be omitted. Tiles do not have to be in any order.
	 * There is no header row.
	 *
	 * @param densityFile	path of the text file
	 * @return a list of {@link STile} objects.
	 *
	 * @throws IOException if the file cannot be found or read
	 */
	private static List<STile> readTileDensities(Path densityFile) throws IOException
	{
		List<STile> tiles = new ArrayList<>();
		try(BufferedReader in = new BufferedReader(new FileReader(densityFile.toFile())))
		{
			for(;;)
			{
				String line = in.readLine();
				if(line == null) break;
				int n = line.indexOf(',');
				if(n < 0) continue;
				int n2 = line.indexOf(',', n+1);
				if(n2 < 0) continue;
				int col = Integer.parseInt(line.substring(0, n));
				int row = Integer.parseInt(line.substring(n+1, n2));
				int count = Integer.parseInt(line.substring(n2+1));
				STile tile = new STile(Tile.fromColumnRowZoom(col, row, 12), count);
				tiles.add(tile); 
			}
		}
		return tiles;
	}

	
	public STile buildTileTree(Path densityFile,
		int zoomLevels, int maxTiles, int minDensity) throws IOException
	{
		this.zoomLevels = zoomLevels;
		minZoom = ZoomLevels.minZoom(zoomLevels);
		assert minZoom == 0;
			// Root grid support has been disabled; root must be zoom 0

		List<STile> tiles = readTileDensities(densityFile);
		root = addParentTiles(tiles);
		tiles.sort(this::compareTilesByDensity);

		int tileCount = Math.min(tiles.size(), maxTiles);
		while(tileCount > 1)
		{
			if(tiles.get(tileCount-1).totalCount >= minDensity) break;
			tileCount--;
		}
		tiles.subList(tileCount, tiles.size()).clear();

		for(STile t: tiles) t.parent.addChild(t);
		for(STile t: tiles) t.build();
		root.build();
		return root;
	}

	// TODO: remove
	/*
	public void writeTileIndex(Path indexPath) throws IOException
	{
		Archive archive = new Archive();
		archive.setHeader(new SBytes(new byte[0], 0));
		addToArchive(archive);
		archive.writeFile(indexPath);
	}
	 */

	public Struct addToArchive(Archive archive)
	{
		addToArchive(archive, root);
		return root;
	}

	private void addToArchive(Archive archive, STile root)
	{
		// TODO: more efficient layout?
		//  But if we use other techniques (page-aware, hole-filling, etc.),
		//  be sure to only place entries after the root, else we end up with
		//  negative TIPs (which are not allowed)
		
		archive.place(root);
		for(STile child: root.children)
		{
			if(child != null)
			{
				if(child.children != null)
				{
					addToArchive(archive, child);
				}
			}
		}
	}
	
	public void writeTileCatalog(Path catalogFile) throws IOException
	{
		PrintWriter out = new PrintWriter(catalogFile.toFile());
		STile tile = root;
		if(tile.children.length == 1 && tile.children[0].children == null)
		{
			// Exception for single-tile
			out.println("000001\t0/0/0");
				// TODO: This is ugly, double-check what the TIP should be
				//  for single-tile GOL
		}
		else
		{
			int indexStart = root.location();
			while (tile != null)
			{
				tile.writeToCatalog(out, indexStart);
				tile = (STile) tile.next();
			}
		}
		out.close();
	}
	
	private static void addToMap(MapMaker map, STile tile, int viewQuad)
	{
		if(tile.parent != null)
		{
			if(!TileQuad.coversTile(viewQuad, tile.tile)) return;
			Marker m = map.add(Tile.bounds(tile.tile)).tooltip(
				String.format("<b>%s</b><br>%,d nodes<br>(%,d total)", 
					Tile.toString(tile.tile), 
					tile.totalCount - tile.childCount,
					tile.totalCount));
			if(Tile.zoom(tile.tile)== 12) m.option("color", "red");
		}
		if(tile.children != null)
		{
			for(STile child: tile.children)
			{
				if(child != null) addToMap(map, child, viewQuad);
			}
		}
	}
	
	public void createTileMap(Path tileMapFile, int viewQuad) throws IOException
	{
		MapMaker map = new MapMaker();
		addToMap(map, root, viewQuad);
		map.save(tileMapFile.toString());
	}
}
