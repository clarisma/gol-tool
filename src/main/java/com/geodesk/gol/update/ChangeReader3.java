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
import com.geodesk.core.Mercator;
import com.geodesk.core.Tile;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.TagValues;
import com.geodesk.gol.TaskEngine;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.build.TileCatalog;
import com.geodesk.gol.tiles.TagTableBuilder;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
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
    private BuildContext context;
    private final StringManager strings;
    private boolean reportProgress = true;  // TODO

    public ChangeReader3(BuildContext ctx) throws IOException
    {
        super(new Task(null), 1, true);
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

        Log.debug("CR3: Read %s in %,d ms", file, System.currentTimeMillis() - start);
    }

    private static int BATCH_SIZE = 8192;

    protected class Handler extends DefaultHandler
    {
        private int currentChangeType;
        private long currentId;
        private int currentVersion;
        private int currentX, currentY;
        private final List<String> tagList = new ArrayList<>();
        private final MutableLongList memberList = new LongArrayList();
        private final List<String> roleList = new ArrayList<>();
        private final List<ChangedFeature> changes = new ArrayList<>();
        private long[] featureIds;
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
            currentVersion = Integer.parseInt(attr.getValue("version"));
            currentId = Long.parseLong(attr.getValue("id"));
            featureIds[featureCount++] = FeatureId.of(type, currentId);
            if(featureCount == BATCH_SIZE)
            {
                flush();
                if (reportProgress)
                {
                    System.err.format("Reading... %,d changes\r", changes.size());
                }
            }
        }

        @Override public void startElement (String uri, String localName,
		    String qName, Attributes attr)
        {
            switch(qName)
            {
            case "node":
                startFeature(FeatureType.NODE, attr);
                double lon = Double.parseDouble(attr.getValue("lon"));
			    double lat = Double.parseDouble(attr.getValue("lat"));
			    currentX = (int)Math.round(Mercator.xFromLon(lon));
			    currentY = (int)Math.round(Mercator.yFromLat(lat));
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
                String type = attr.getValue("type");
                long id = Long.parseLong(attr.getValue("ref"));
			    memberList.add(FeatureId.of(FeatureType.from(type), id));
                roleList.add(attr.getValue("role"));
                break;
            case "tag":
                tagList.add(attr.getValue("k"));
                tagList.add(attr.getValue("v"));
                break;
            case "create":
                currentChangeType = ChangedFeature.CREATE;
                break;
            case "modify":
                currentChangeType = 0;
                break;
            case "delete":
                currentChangeType = ChangedFeature.DELETE;
                break;
            }
        }

        private long[] getTags()
        {
            String[] kv = tagList.toArray(new String[0]);
            return TagTableBuilder.fromStrings(kv, strings);
        }

        private int[] getRoles()
        {
            int[] roles = new int[roleList.size()];
            for(int i=0; i<roles.length; i++)
            {
                String strRole = roleList.get(i);
                int roleCode = strings.globalStringCode(strRole);
                if(roleCode < 0 || roleCode > TagValues.MAX_COMMON_ROLE)
                {
                    roleCode = strings.localStringCode(strRole) | 0x8000_0000;
                }
                roles[i] = roleCode;
            }
            return roles;
        }

        public void endElement (String uri, String localName, String qName)
        {
            long[] tags;
            switch(qName)
            {
            case "node":
                if(currentChangeType == ChangedFeature.DELETE)
                {
                    tags = null;
                }
                else
                {
                    tags = getTags();
                }
                changes.add(new ChangedNode(currentId, currentVersion, currentChangeType,
                    tags, currentX, currentY));
                tagList.clear();
                break;
            case "way":
                long[] nodeIds;
                if(currentChangeType == ChangedFeature.DELETE)
                {
                    tags = null;
                    nodeIds = null;
                }
                else
                {
                    tags = getTags();
                    nodeIds = memberList.toArray();
                }
                changes.add(new ChangedWay(currentId, currentVersion, currentChangeType,
                    tags, nodeIds));
                tagList.clear();
                memberList.clear();
                break;
            case "relation":
                long[] memberIds;
                int[] roles;
                if(currentChangeType == ChangedFeature.DELETE)
                {
                    tags = null;
                    memberIds = null;
                    roles = null;
                }
                else
                {
                    tags = getTags();
                    memberIds = memberList.toArray();
                    roles = getRoles();
                }
                changes.add(new ChangedRelation(currentId, currentVersion, currentChangeType,
                    tags, memberIds, roles));
                tagList.clear();
                memberList.clear();
                roleList.clear();
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
            //Log.debug("%,d way nodes", wayNodeCount);
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
