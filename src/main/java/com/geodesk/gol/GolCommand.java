package com.geodesk.gol;

import com.clarisma.common.cli.Command_old;
import com.geodesk.feature.FeatureStore;

import java.util.List;
import java.util.Map;

public abstract class GolCommand extends Command_old
{
    protected FeatureStore features;

    public GolCommand(List<String> arguments, Map<String, Object> options)
    {
        super(arguments, options);
    }

    protected abstract void perform();

    @Override public void run()
    {
        // features = new FeatureStore(Path.of()) // TODO
        perform();
        features.close();
    }
}
