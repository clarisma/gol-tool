package com.geodesk.gol.compiler;

import com.clarisma.common.soar.Archive;
import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.SharedStruct;
import com.clarisma.common.soar.Struct;

import java.util.*;

public abstract class FeatureLayout
{
    protected Archive archive;
    protected SIndexTree[] indexes;
    protected List<STagTable> sharedTags;
    protected List<SString> sharedStrings;
    protected List<SRelationTable> sharedRelTables;
    private int maxDrift;
    private Queue<Struct> deferred = new ArrayDeque<>();

    public FeatureLayout(Archive archive)
    {
        this.archive = archive;
    }

    private static <T extends SharedStruct> List<T> gatherShared(Collection<T> structs)
    {
        List<T> shared = new ArrayList<>();
        for(T item: structs)
        {
            if(item.userCount() > 2) shared.add(item);
        }
        return shared;
    }

    public FeatureLayout indexes(SIndexTree[] indexes)
    {
        this.indexes = indexes;
        return this;
    }

    public FeatureLayout tags(Collection<STagTable> tags)
    {
        sharedTags = gatherShared(tags);
        return this;
    }

    public FeatureLayout strings(Collection<SString> strings)
    {
        sharedStrings = gatherShared(strings);
        return this;
    }

    public FeatureLayout relationTables(Collection<SRelationTable> relTables)
    {
        sharedRelTables = gatherShared(relTables);
        return this;
    }

    protected void maxDrift(int max)
    {
        maxDrift = max;
    }

    private void tryPlaceDeferred()
    {
        while(!deferred.isEmpty())
        {
            int pos = archive.size();
            if(deferred.peek().alignedLocation(pos) != pos) break;
            archive.place(deferred.remove());
            // log.debug("{} structs in queue.", deferred.size());
        }
    }

    protected void place(Struct s)
    {
        if(maxDrift == 0)
        {
            archive.place(s);
            return;
        }
        int pos = archive.size();
        for(;;)
        {
            if (s.alignedLocation(pos) != pos)
            {
                if(s instanceof SString)
                {
                    // log.debug("debug");
                }
                // log.debug("Added {} to queue.", s);
                // log.debug("{} structs in queue.", deferred.size());
                s.setLocation(~pos);
                deferred.add(s);
                return;
            }
            if (deferred.isEmpty() || pos + s.size() + deferred.peek().location() < maxDrift)
            {
                archive.place(s);
                tryPlaceDeferred();
                return;
            }
            archive.place(deferred.remove());
            tryPlaceDeferred();
            pos = archive.size();
        }
    }

    protected void flush()
    {
        while(!deferred.isEmpty()) archive.place(deferred.remove());
    }

    // TODO: place local roles or relations
    protected void placeFeatureBody(SFeature f)
    {
        STagTable tags = f.tags();
        if(tags.location() == 0) place(tags);
        for(Map.Entry<String,String> e: tags)
        {
            STagTable.Entry tag = (STagTable.Entry)e;	// TODO
            SString s = tag.keyString();
            if(s != null && s.location() == 0) place(s);
            s = tag.valueString();
            if(s != null && s.location() == 0) place(s);
        }

        SFeature.SFeatureBody body = f.body();
        if(body != null)
        {
            place(body);
            for (Struct bodyPart : body)
            {
                if (bodyPart.location() == 0) place(bodyPart);
            }
        }

        SRelationTable relTable = f.relations();
        if(relTable != null && relTable.location() == 0)
        {
            place(relTable);
        }
    }

    protected void placeFeatureBodies(SIndexTree branch)
    {
        if(branch == null) return;
        SIndexTree[] children = branch.childBranches();
        if(children != null)
        {
            for (SIndexTree child : children) placeFeatureBodies(child);
            return;
        }
        for(SFeature f: branch) placeFeatureBody(f);
    }

    protected void placeFeatureBodies()
    {
        for(SIndexTree root: indexes) placeFeatureBodies(root);
    }

    public abstract void layout();
}
