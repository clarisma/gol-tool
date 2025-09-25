/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol;

import com.clarisma.common.cli.BasicCommand;
import com.clarisma.common.cli.Option;
import com.clarisma.common.cli.Parameter;
import com.geodesk.feature.filter.IntersectsFilter;
import com.geodesk.geom.Box;
import com.geodesk.geom.Tile;
import com.geodesk.feature.FeatureLibrary;
import com.geodesk.feature.Filter;
import com.geodesk.feature.store.TileIndexWalker;
import com.geodesk.gol.build.Utils;
import com.geodesk.io.PolyReader;
import com.geodesk.util.CoordinateTransformer;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// TODO: option "-o": redirect output to file

public abstract class GolCommand extends BasicCommand
{
    private Path golPath;
    protected FeatureLibrary features;
    protected Box bbox;

    /**
     * The file containing the area to which the command should be applied
     * (user option)
     */
    protected Path areaFilePath;

    /**
     * The area to which the command should be applied, or `null` if `bbox`
     * should be used.
     */
    protected Geometry area;
    private int result;

    @Option("new,n: create GOL if it does not exist")
    protected boolean createIfMissing;

    @Option("url,u: URL of tile repository")
    protected String url;

    @Parameter("0=gol")
    public void library(String filename)
    {
        golPath = Utils.golPath(filename);
    }

    @Option("bbox,b=W,S,E,N: bounding box")
    public void bounds(String bounds)
    {
        if(bounds.indexOf('/') > 0)
        {
            int tile = Tile.fromString(bounds);
            if(tile == -1 || !Tile.isValid(tile))
            {
                throw new IllegalArgumentException("\"" + bounds +
                    "\" is not a valid tile");
            }
            bbox = Tile.bounds(tile);
            return;
        }
        bbox = Box.fromWSEN(bounds);
    }

    @Option("area,a=file: polygon file")
    public void areaFile(String file)
    {
        areaFilePath = Paths.get(file);
    }

    protected abstract void performWithLibrary() throws Exception;

    protected void setResult(int result)
    {
        this.result = result;
    }

    private void readAreaFile() throws IOException
    {
        if(areaFilePath == null) return;
        try(BufferedReader in = new BufferedReader(new FileReader(areaFilePath.toFile())))
        {
            PolyReader reader = new PolyReader(in, new GeometryFactory(),
                new CoordinateTransformer.ToMercator());
            area = reader.read();
        }
    }

    @Override public int perform() throws Exception
    {
        try
        {
            if(!createIfMissing)
            {
                if(Files.notExists(golPath))
                {
                    throw new FileNotFoundException(String.format(
                        "%s does not exist; use option --new (-n) " +
                        "to create an empty library", golPath));
                }
            }
            features = new FeatureLibrary(golPath, url);
            readAreaFile();
            performWithLibrary();
        }
        finally
        {
            if(features != null) features.close();
        }
        return result;
    }

    protected TileIndexWalker getTileIndexWalker()
    {
        TileIndexWalker walker = new TileIndexWalker(features.store());
        if (area != null)
        {
            Filter filter = new IntersectsFilter(area);
            walker.start(filter.bounds(), filter);
        }
        else
        {
            walker.start(bbox != null ? bbox : Box.ofWorld());
        }
        return walker;
    }

    /**
     * Obtains a list of tiles (as TIPs) that wholly or partially lie in
     * the requested bounding box or area.
     *
     * Always includes the Purgatory Tile
     *
     * @return a list of TIPs
     */
    protected IntList getTiles()
    {
        MutableIntList tiles = new IntArrayList();
        TileIndexWalker walker = getTileIndexWalker();
        while(walker.next()) tiles.add(walker.tip());
        tiles.add(0);   // always add purgatory
        return tiles;
    }

    @Override public int error(Throwable ex)
    {
        return ErrorReporter.report(ex, verbosity);
    }
}
