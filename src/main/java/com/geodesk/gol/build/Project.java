/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.geodesk.feature.store.ZoomLevels;

import java.io.*;
import java.nio.file.Path;
import java.util.Map;

public class Project implements Serializable
{
	private Path workPath;
	private String name;		// TODO: needed?
	private int verbosity;
	private boolean keepWork;
	private Path sourcePath;
	private int maxTiles = 16 * 1024;
	private int minTileDensity = 10_000;
	private int zoomLevels = ZoomLevels.fromString(ZoomLevels.DEFAULT); // TODO
	private int minStringUsage = 1000;
	private int maxStringCount = 1 << 14;
	private int rtreeBucketSize = 16;
	private KeyIndexSchema keyIndexSchema;
	private int maxKeyIndexes = 8;
	private int keyIndexMinFeatures = 300;
	private Map<String,String> properties;
	private boolean idIndexing;
	private boolean tagDuplicateNodes;
	private boolean tagOrphanNodes;
	private boolean updatable;


	private static void error(String msg, Object... args)
	{
		throw new IllegalArgumentException(String.format(msg, args));
	}

	public static void checkRange(double val, double min, double max)
	{
		if (val < min)
		{
			error("Must not be less than " + min);
		}
		if (val > max)
		{
			error("Must not be greater than " + max);
		}
	}

	public boolean idIndexing()
	{
		return idIndexing;
	}

	public Path workPath()
	{
		return workPath;
	}

	public void workPath(Path path)
	{
		workPath = path;
	}

    public String name()
    {
        return name;
    }

	public int verbosity()
	{
		return verbosity;
	}

	public void verbosity(int level)
	{
		verbosity = level;
	}

	public boolean keepWork()
	{
		return keepWork;
	}

	public void keepWork(boolean keepWork)
	{
		this.keepWork = keepWork;
	}

	public Path sourcePath() { return sourcePath; };

	public void sourcePath(Path path)
	{
		sourcePath = path;
	}
	public void sourcePath(String s)
	{
		sourcePath = Path.of(s);
	}

	public int maxTiles()
	{
		return maxTiles;
	}

	public void maxTiles(int maxTiles)
	{
		checkRange(maxTiles, 1, 8 * 1024 * 1024);
		this.maxTiles = maxTiles;
	}

    public int minTileDensity()
    {
        return minTileDensity;
    }

	public void minTileDensity(int minTileDensity)
	{
		checkRange(minTileDensity, 1, 10_000_000);
		this.minTileDensity = minTileDensity;
	}

    public int minStringUsage()
    {
        return minStringUsage;
    }

	public void minStringUsage(int count)
	{
		checkRange(count, 1, 100_000_000);
		this.minStringUsage = count;
	}

	public boolean tagDuplicateNodes()
	{
		return tagDuplicateNodes;
	}

	public boolean tagOrphanNodes()
	{
		return tagOrphanNodes;
	}

	public int zoomLevels()
	{
		return zoomLevels;
	}

	public void zoomLevels(String s)
	{
		zoomLevels = ZoomLevels.fromString(s);
		zoomLevels |= 1;
			// TODO: Zoom level 0 must always be included;
			//  disable this if we're going to support root grids
		if(ZoomLevels.zoomSteps(zoomLevels) < 0)
		{
			error("Invalid arrangement of zoom levels: %s", s);
		}
	}

	public int maxStringCount()
	{
		return maxStringCount;
	}

	public void maxStringCount(int maxStringCount)
	{
		checkRange(maxStringCount, 256, 64 * 1024);
		this.maxStringCount = maxStringCount;
	}

	public KeyIndexSchema keyIndexSchema()
	{
		return keyIndexSchema;
	}

	public void keyIndexSchema(String s)
	{
		keyIndexSchema = new KeyIndexSchema(s);
	}

	public int rtreeBucketSize()
	{
		return rtreeBucketSize;
	}

	public void rtreeBucketSize(int count)
	{
		checkRange(count, 4, 256);
		rtreeBucketSize = count;
	}
	
	public int maxKeyIndexes()
	{
		return maxKeyIndexes;
	}

	public void maxKeyIndexes(int count)
	{
		checkRange(count, 0, 32);
		maxKeyIndexes = count;
	}

	public int keyIndexMinFeatures()
	{
		return keyIndexMinFeatures;
	}

	public void keyIndexMinFeatures(int count)
	{
		checkRange(count, 1, 1_000_000);
		keyIndexMinFeatures = count;
	}

	public static boolean booleanValue(String value)
	{
		// Allow setting on command line to be used as a flag
		if(value == null) value = "yes";
		switch (value)
		{
		case "yes", "true", "on": return true;
		case "no", "false", "off": return false;
		default:
			throw new IllegalArgumentException("Must be yes/no");	// TODO
		}
	}

	public boolean set(String name, String value)
	{
		switch(name)
		{
		case "source":
			sourcePath(Path.of(value));
			return true;
		case "indexed-keys":
			keyIndexSchema(value);
			return true;
		case "key-index-min-features":
			keyIndexMinFeatures(Integer.parseInt(value));
			return true;
		case "max-key-indexes":
			maxKeyIndexes(Integer.parseInt(value));
			return true;
		case "max-strings":
			maxStringCount(Integer.parseInt(value));
			return true;
		case "max-tiles":
			maxTiles(Integer.parseInt(value));
			return true;
		case "min-string-usage":
			minStringUsage(Integer.parseInt(value));
			return true;
		case "min-tile-density":
			minTileDensity(Integer.parseInt(value));
			return true;
		case "rtree-bucket-size":
			rtreeBucketSize(Integer.parseInt(value));
			return true;
		case "tag-duplicate-nodes":
			tagDuplicateNodes = booleanValue(value);
			return true;
		case "tag-orphan-nodes":
			tagOrphanNodes = booleanValue(value);
			return true;
		case "tile-zoom-levels":
			zoomLevels(value);
			return true;
		case "updatable":
			updatable = booleanValue(value);
			return true;
		}
		return false;
	}

	public Map<String,String> properties()
	{
		return properties;
	}

	public static Project read(Path path) throws Exception
	{
		FileInputStream fin = new FileInputStream(path.toFile());
		ObjectInputStream in = new ObjectInputStream(fin);
		Project project  = (Project)in.readObject();
		in.close();
		return project;
	}

	public void write(Path path) throws IOException
	{
		FileOutputStream fout = new FileOutputStream(path.toFile());
		ObjectOutputStream out = new ObjectOutputStream(fout);
		out.writeObject(this);
		out.close();
	}
}
