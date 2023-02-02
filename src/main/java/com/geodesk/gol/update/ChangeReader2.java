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
import com.geodesk.feature.FeatureType;
import com.geodesk.gol.TaskEngine;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
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

public class ChangeReader2 extends TaskEngine<ChangeReader2.Task>
{
    private ChangeModel model;
    private BuildContext context;

    public ChangeReader2(ChangeModel model, BuildContext ctx)
    {
        super(new Task(null,null), 1, true);
        this.model = model;
        this.context = ctx;
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
        protected MutableIntList typeList;
        protected List<String> stringList;

        Handler()
        {
            newBatch();
        }

        private void collect(int type, String s1, String s2)
        {
            typeList.add(type);
            stringList.add(s1);
            stringList.add(s2);
        }

        private void startFeature(int type, Attributes attr)
        {
            collect(FEATURE | type | currentChangeType,
                attr.getValue("id"),
                attr.getValue("version"));
        }


        private void newBatch()
        {
            typeList = new IntArrayList(BATCH_SIZE + EXTRA_SIZE);
            stringList = new ArrayList<>((BATCH_SIZE + EXTRA_SIZE) * 2);
        }

        public void flush()
        {
            submit(new Task(typeList, stringList));
            newBatch();
        }

        @Override public void startElement (String uri, String localName,
		    String qName, Attributes attr)
        {
            switch(qName)
            {
            case "node":
                startFeature(FEATURE_NODE, attr);
                collect(LONLAT,
                    attr.getValue("lon"),
                    attr.getValue("lat"));
                break;
            case "way":
                startFeature(FEATURE_WAY, attr);
                break;
            case "relation":
                startFeature(FEATURE_RELATION, attr);
                break;
            case "nd":
                collect(MEMBER, attr.getValue("ref"), null);
                break;
            case "member":
                String typeString = attr.getValue("type");
                int type = switch (typeString)
                {
                    case "node" -> FEATURE_NODE;
                    case "way" -> FEATURE_WAY;
                    case "relation" -> FEATURE_RELATION;
                    default -> throw new RuntimeException("Illegal member type: " + typeString);
                };
                collect(MEMBER | type, attr.getValue("ref"),
                    attr.getValue("role"));
                break;
            case "tag":
                collect(TAG, attr.getValue("k"), attr.getValue("v"));
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
                if(typeList.size() >= BATCH_SIZE)
                {
                    // Log.debug("Dispatching %,d elements", typeList.size());
                    flush();
                }
                break;
            }
        }
    }

    protected static class Task
    {
        private final IntList typeList;
        private final List<String> stringList;

        public Task(IntList typeList, List<String> stringList)
        {
            this.typeList = typeList;
            this.stringList = stringList;
        }
    }

    protected class Worker extends WorkerThread
    {
        private final IntIndex nodeIndex;
        private final IntIndex wayIndex;
        private final IntIndex relationIndex;
        private final TileCatalog tileCatalog;
        private MutableIntSet nodeTiles = new IntHashSet();
        private MutableIntSet wayTiles = new IntHashSet();
        private MutableIntSet relationTiles = new IntHashSet();

        Worker() throws IOException
        {
            nodeIndex = context.getNodeIndex();
            wayIndex = context.getWayIndex();
            relationIndex = context.getRelationIndex();
            tileCatalog = context.getTileCatalog();
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

        @Override protected void process(Task task) throws Exception
        {
            // Log.debug("Processing %,d attributes", task.typeList.size());
            IntList typeList = task.typeList;
            List<String> stringList = task.stringList;
            for(int i=0; i<typeList.size(); i++)
            {
                int type = typeList.get(i);
                String s1 = stringList.get(i * 2);
                String s2 = stringList.get(i * 2 + 1);

                if((type & FEATURE) != 0)
                {
                    // Log.debug("ID = %s", s1);
                    long id = Long.parseLong(s1);
                    switch(type & FEATURE_TYPE_MASK)
                    {
                    case FEATURE_NODE:
                        addNodeTile(id);
                        break;
                    case FEATURE_WAY:
                        addFeatureTiles(wayTiles, wayIndex, id);
                        break;
                    case FEATURE_RELATION:
                        addFeatureTiles(relationTiles, relationIndex, id);
                        break;
                    }
                }
            }
        }

        @Override protected void postProcess() throws Exception
        {
            Log.debug("%,d node tiles", nodeTiles.size());
            Log.debug("%,d way tiles", wayTiles.size());
            Log.debug("%,d relation tiles", relationTiles.size());
        }
    }

}
