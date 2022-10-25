package com.geodesk.gol;

import com.clarisma.common.cli.Verbosity;
import com.geodesk.feature.store.FeatureStoreChecker;

public class CheckCommand extends GolCommand
{
    @Override protected void performWithLibrary() throws Exception
    {
        FeatureStoreChecker checker = new FeatureStoreChecker(features.store());
        checker.check();

        // We send errors to stdout instead of stderr
        // (or file specfied by -o) // TODO
        // because error messages are considered result output
        // in this context

        if(checker.hasErrors())
        {
            if(verbosity >= Verbosity.QUIET) checker.reportErrors(System.out);
            setResult(ErrorReporter.INVALID_GOL_FILE);
            return;
        }
        if(verbosity >= Verbosity.QUIET) System.err.println("No errors found.");
    }
}
