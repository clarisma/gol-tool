package com.geodesk.gol;

import com.clarisma.common.cli.BasicCommand;
import com.clarisma.common.cli.Command_old;
import com.clarisma.common.cli.Option;
import com.clarisma.common.cli.Parameter;
import com.geodesk.core.Box;
import com.geodesk.feature.FeatureLibrary;
import com.geodesk.gol.build.Utils;

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

    @Override public int perform()
    {
        int result = 0;
        // TODO: constructor that takes Path
        features = new FeatureLibrary(golPath.toString());
        try
        {
            performWithLibrary();
        }
        catch(Throwable ex)
        {
            result = ErrorReporter.report(ex, verbosity);
        }
        features.close();
        return result;
    }
}
