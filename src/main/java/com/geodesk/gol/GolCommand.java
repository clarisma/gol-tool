package com.geodesk.gol;

import com.clarisma.common.cli.BasicCommand;
import com.clarisma.common.cli.Option;
import com.clarisma.common.cli.Parameter;
import com.geodesk.core.Box;
import com.geodesk.feature.FeatureLibrary;
import com.geodesk.gol.build.Utils;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public abstract class GolCommand extends BasicCommand
{
    private Path golPath;
    protected FeatureLibrary features;
    protected Box bbox;
    protected Path polygonFilePath;

    @Option("new,n: create GOL if it does not exist")
    protected boolean createIfMissing;

    @Option("url,u: URL of tile repository")
    private String url;

    @Parameter("0=gol")
    public void library(String filename)
    {
        golPath = Utils.golPath(filename);
    }

    @Option("bbox,b=W,S,E,N: bounding box")
    public void bounds(String bounds)
    {
        bbox = Box.fromWSEN(bounds);
    }

    @Option("polygon,p=file: polygon file")
    public void polygonFile(String file)
    {
        polygonFilePath = Paths.get(file);
    }

    protected abstract void performWithLibrary() throws Exception;

    @Override public int perform() throws Exception
    {
        int result = 0;
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
            performWithLibrary();
        }
        finally
        {
            if(features != null) features.close();
        }
        return result;
    }

    @Override public int error(Throwable ex)
    {
        return ErrorReporter.report(ex, verbosity);
    }
}
