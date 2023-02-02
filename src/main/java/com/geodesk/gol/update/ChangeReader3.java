/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.index.IntIndex;
import com.clarisma.common.util.Log;
import com.geodesk.core.Heading;
import com.geodesk.core.Tile;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.gol.TaskEngine;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.tiles.TagTableBuilder;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class ChangeReader3 extends TaskEngine<ChangeReader3.Task>
{
    private ChangeModel model;
    private BuildContext context;
    private final StringManager strings;

    public ChangeReader3(ChangeModel model, BuildContext ctx) throws IOException
    {
        super(new Task(null), 1, true);
        this.model = model;
        this.context = ctx;
        FeatureStore store = ctx.getFeatureStore();
        strings = new StringManager(store.stringsToCodes(), store.codesToStrings());
        start();
    }

    @Override protected TaskEngine<Task>.WorkerThread createWorker() throws Exception
    {
        return new Worker();
    }

    public void read(String file, boolean zipped) throws IOException
    {
        long start = System.currentTimeMillis();
        try (FileInputStream fin = new FileInputStream(file))
        {
            InputStream in = fin;
            if (zipped) in = new GZIPInputStream(fin);

            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser parser = factory.newSAXParser();
            // reader.read(in);
            Handler handler = new Handler();
            parser.parse(in, handler);
            handler.flush();
            try
            {
                // TODO: allow additional files to be read
                awaitCompletionOfGroup(0);
            }
            catch(InterruptedException ex)
            {
                // do nothing
            }
            Log.debug("Strings merged.");
            strings.dump();
            in.close();
        }
        catch(SAXException | ParserConfigurationException ex)
        {
            throw new IOException("%s: Invalid file (%s)".formatted(file, ex.getMessage()));
        }

        Log.debug("Read %s in %,d ms", file, System.currentTimeMillis() - start);
    }

    private static final int FEATURE  = 16;
    private static final int LONLAT = 32;
    private static final int TAG = 64;
    private static final int MEMBER = 128;

    private static final int FEATURE_TYPE_MASK  = 3;
    private static final int FEATURE_NODE = 0;
    private static final int FEATURE_WAY = 1;
    private static final int FEATURE_RELATION = 2;

    private static final int CHANGE_MODIFY = 0;
    private static final int CHANGE_CREATE = 4;
    private static final int CHANGE_DELETE = 8;


    private static int BATCH_SIZE = 8192;
    private static int EXTRA_SIZE = 512;

    protected class Handler extends DefaultHandler
    {
        protected int currentChangeType;
        protected final List<String> stringList = new ArrayList<>();
        protected final MutableLongList memberList = new LongArrayList();
        protected long[] featureIds;
        private int featureCount;

        Handler()
        {
            newBatch();
        }

        private void newBatch()
        {
            featureIds = new long[BATCH_SIZE];
            featureCount = 0;
        }

        public void flush()
        {
            submit(new Task(featureIds));
            newBatch();
        }

        private void startFeature(FeatureType type, Attributes attr)
        {
            featureIds[featureCount++] =
                FeatureId.of(type, Long.parseLong(attr.getValue("id")));
            if(featureCount == BATCH_SIZE) flush();
        }

        @Override public void startElement (String uri, String localName,
		    String qName, Attributes attr)
        {
            switch(qName)
            {
            case "node":
                startFeature(FeatureType.NODE, attr);
                break;
            case "way":
                startFeature(FeatureType.WAY, attr);
                break;
            case "relation":
                startFeature(FeatureType.RELATION, attr);
                break;
            case "nd":
                memberList.add(Long.parseLong(attr.getValue("ref")));
                break;
            case "member":
                break;
            case "tag":
                stringList.add(attr.getValue("k"));
                stringList.add(attr.getValue("v"));
                break;
            case "create":
                currentChangeType = CHANGE_CREATE;
                break;
            case "modify":
                currentChangeType = CHANGE_MODIFY;
                break;
            case "delete":
                currentChangeType = CHANGE_DELETE;
                break;
            }
        }

        public void endElement (String uri, String localName, String qName)
        {
            switch(qName)
            {
            case "node":
            case "way":
            case "relation":
                TagTableBuilder.fromStrings(stringList.toArray(new String[0]), strings);
                stringList.clear();
                memberList.clear();
                break;
            }
        }
    }

    protected static class Task
    {
        private final long[] featureIds;

        public Task(long[] featureIds)
        {
            this.featureIds = featureIds;
        }
    }

    protected class Worker extends WorkerThread
    {
        private final IntIndex nodeIndex;
        private final IntIndex wayIndex;
        private final IntIndex relationIndex;
        private final TileCatalog tileCatalog;
        private final StringManager strings;
        private MutableIntSet nodeTiles = new IntHashSet();
        private MutableIntSet wayTiles = new IntHashSet();
        private MutableIntSet relationTiles = new IntHashSet();
        private long wayNodeCount;

        Worker() throws IOException
        {
            nodeIndex = context.getNodeIndex();
            wayIndex = context.getWayIndex();
            relationIndex = context.getRelationIndex();
            tileCatalog = context.getTileCatalog();
            FeatureStore store = context.getFeatureStore();
            strings = new StringManager(store.stringsToCodes(), store.codesToStrings());
        }

        private void addNodeTile(long id) throws IOException
        {
            int pile = nodeIndex.get(id);
            if(pile != 0) nodeTiles.add(tileCatalog.tileOfPile(pile));
        }

        private void addFeatureTiles(MutableIntSet tiles, IntIndex index, long id) throws IOException
        {
            int pileQuad = index.get(id);
            if(pileQuad != 0)
            {
                int tile = tileCatalog.tileOfPile(pileQuad >>> 2);
                if ((pileQuad & 3) == 3)
                {
                    // For features with > 2 tiles, we need to also scan
                    // the adjacent tile (in case `tile` refers to the
                    // empty quadrant of a sparse quad)
                    tiles.add(tile);
                    tile = Tile.neighbor(tile, Heading.EAST);
                }
                tiles.add(tile);
            }
        }

        private long[] getTags(IntList typeList, List<String> stringList, int start)
        {
            while(start < typeList.size())
            {
                int type = typeList.get(start);
                if(type == TAG) break;
                if((type & FEATURE) != 0) return TagTableBuilder.EMPTY_TABLE;
                start++;
            }

            int n = start;
            while(n < typeList.size())
            {
                if(typeList.get(n) != TAG) break;
                n++;
            }
            int tagCount = n - start;
            if(tagCount == 0) return TagTableBuilder.EMPTY_TABLE;
            String[] kv = new String[tagCount * 2];
            for(int i=0; i<kv.length; i++)
            {
                kv[i] = stringList.get(start * 2 + i);
            }
            return TagTableBuilder.fromStrings(kv, strings);
        }

        private long[] getNodeIds(IntList typeList, List<String> stringList, int start)
        {
            while(start < typeList.size())
            {
                int type = typeList.get(start);
                if(type == MEMBER) break;
                if((type & FEATURE) != 0) return TagTableBuilder.EMPTY_TABLE;
                start++;
            }

            int n = start;
            while(n < typeList.size())
            {
                if(typeList.get(n) != MEMBER) break;
                n++;
            }
            int count = n - start;
            long[] nodeIds = new long[count];
            for(int i=0; i<nodeIds.length; i++)
            {
                nodeIds[i] = Long.parseLong(stringList.get((start+i) * 2));
            }
            return nodeIds;
        }

        @Override protected void process(Task task) throws Exception
        {
            long[] ids = task.featureIds;
            for(int i=0; i<ids.length; i++)
            {
                long typedId = ids[i];
                int type = FeatureId.typeCode(typedId);
                long id = FeatureId.id(typedId);
                switch(type)
                {
                case 0:
                    addNodeTile(id);
                    break;
                case 1:
                    addFeatureTiles(wayTiles, wayIndex, id);
                    break;
                case 2:
                    addFeatureTiles(relationTiles, relationIndex, id);
                    break;
                }
            }
        }

        @Override protected void postProcess() throws Exception
        {
            Log.debug("%,d node tiles", nodeTiles.size());
            Log.debug("%,d way tiles", wayTiles.size());
            Log.debug("%,d relation tiles", relationTiles.size());
            Log.debug("%,d way nodes", wayNodeCount);
            strings.dump();
            Log.debug("Merging strings...");
            mergeStrings(strings);
        }
    }

    private synchronized void mergeStrings(StringManager other)
    {
        strings.add(other);
    }

}
