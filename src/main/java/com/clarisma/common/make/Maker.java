package com.clarisma.common.make;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class Maker
{
    public void make(Target target)
    {
        List<Target> targets = new ArrayList<>();
        List<Target> ordered = new ArrayList<>();
        targets.add(target);
        int n = 0;
        while(n < targets.size())
        {
            Target t = targets.get(n);
            t.unprocessedDependencies = t.sources.size();
            if(t.unprocessedDependencies == 0) ordered.add(t);
            targets.addAll(t.sources);
            n++;
        }
        n = 0;
        while(n < ordered.size())
        {
            Target t = ordered.get(n);
            for(Target s: t.sources)
            {

            }
            if(t.unprocessedDependencies == 0) ordered.add(t);
            targets.addAll(t.sources);
            n++;
        }
    }
}
