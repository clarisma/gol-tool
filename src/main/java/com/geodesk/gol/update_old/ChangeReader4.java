/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import com.clarisma.common.util.Log;
import com.geodesk.core.Mercator;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.TagValues;
import com.geodesk.gol.build.BuildContext;
import com.geodesk.gol.tiles.TagTableBuilder;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
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

public class ChangeReader4 extends DefaultHandler
{
    private final TileFinder tileFinder;
    private final StringManager strings;
    private boolean reportProgress = true;  // TODO

    private int currentChangeType;
    private long currentId;
    private int currentVersion;
    private int currentX, currentY;
    private final List<String> tagList = new ArrayList<>();
    private final MutableLongList memberList = new LongArrayList();
    private final List<String> roleList = new ArrayList<>();
    private final List<ChangedNode> nodes = new ArrayList<>();
    private final List<ChangedWay> ways = new ArrayList<>();
    private final List<ChangedRelation> relations = new ArrayList<>();
    private long changeCount;

    public ChangeReader4(BuildContext ctx, TileFinder tileFinder) throws IOException
    {
        this.tileFinder = tileFinder;
        FeatureStore store = ctx.getFeatureStore();
        strings = new StringManager(store.stringsToCodes(), store.codesToStrings());
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
            parser.parse(in, this);
            if(reportProgress) reportProgress();
            strings.dump();
            reportFreeMemory();
            reportStats();
            in.close();
        }
        catch(SAXException | ParserConfigurationException ex)
        {
            throw new IOException("%s: Invalid file (%s)".formatted(file, ex.getMessage()));
        }

        Log.debug("CR4: Read %s in %,d ms", file, System.currentTimeMillis() - start);

    }

    private void reportFreeMemory()
    {
        for(int i=0; i<10; i++) Runtime.getRuntime().gc();
        Log.debug("Memory used: %d MB",
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            / (1024 * 1024));
        /*
        Log.debug("%,d bytes free", Runtime.getRuntime().freeMemory());
        Log.debug("%,d bytes total", Runtime.getRuntime().totalMemory());
        Log.debug("%,d bytes max", Runtime.getRuntime().maxMemory());
         */
    }

    private void reportStats()
    {
        Log.debug("Building model...");
        MutableLongObjectMap<ChangedNode> nodes = getFeatures(this.nodes);
        MutableLongObjectMap<ChangedWay> ways = getFeatures(this.ways);
        MutableLongObjectMap<ChangedRelation> relations = getFeatures(this.relations);

        int deletedWayCount = 0;
        long wayNodeCount = 0;
        int completeWayCount = 0;
        for(ChangedWay w: ways)
        {
            if((w.flags & ChangedFeature.DELETE) != 0)
            {
                deletedWayCount++;
            }
            else
            {
                boolean complete = true;
                for (long nodeId : w.nodeIds)
                {
                    if (!nodes.containsKey(nodeId))
                    {
                        complete = false;
                        break;
                    }
                }
                if (complete) completeWayCount++;
                wayNodeCount += w.nodeIds.length;
            }
        }

        MutableLongSet nodeIds = new LongHashSet(wayNodeCount);
        for(ChangedWay w: ways)
        {
            if((w.flags & ChangedFeature.DELETE) == 0)
            {
                nodeIds.addAll(w.nodeIds);
            }
        }

        int deletedRelCount = 0;
        long memberCount = 0;
        for(ChangedRelation r: relations)
        {
            if((r.flags & ChangedFeature.DELETE) != 0)
            {
                deletedRelCount++;
            }
            else
            {
                memberCount += r.memberIds.length;
            }
        }

        MutableLongSet memberIds = new LongHashSet(memberCount);
        for(ChangedRelation r: relations)
        {
            if((r.flags & ChangedFeature.DELETE) != 0)
            {
                deletedRelCount++;
            }
            else
            {
                memberIds.addAll(r.memberIds);
            }
        }

        reportFreeMemory();

        Log.debug("%,d way-nodes   (%,d unique)", wayNodeCount, nodeIds.size());
        Log.debug("%,d rel-members (%,d unique)", memberCount, memberIds.size());
        Log.debug("%,d of %,d modified ways have complete coordinates",
            completeWayCount, ways.size() - deletedWayCount);
        Log.debug("%,d of %,d ways deleted", deletedWayCount, ways.size());
        Log.debug("%,d of %,d relations deleted", deletedRelCount, relations.size());
    }

    private <T extends ChangedFeature> MutableLongObjectMap<T> getFeatures(List<T> list)
    {
        int dupeCount = 0;
        int count = list.size();
        MutableLongObjectMap<T> map =new LongObjectHashMap<>(count);
        for(T f: list)
        {
            if (map.containsKey(f.id))
            {
                // Log.debug("Dupe: %s %d", f.getClass().getSimpleName(), f.id);
                dupeCount++;
            }
            else
            {
                map.put(f.id, f);
            }
        }
        Log.debug("%,d duplicate changes", dupeCount);
        return map;
    }

    private void startFeature(FeatureType type, Attributes attr)
    {
        currentVersion = Integer.parseInt(attr.getValue("version"));
        currentId = Long.parseLong(attr.getValue("id"));
        if(tileFinder != null && currentVersion != 1)
        {
            tileFinder.addFeature(FeatureId.of(type, currentId));
        }
        changeCount++;
        if (reportProgress)
        {
            if((changeCount % 8192) == 0) reportProgress();
        }
    }

    private void reportProgress()
    {
        System.err.format("Reading... %,d nodes / %,d ways / %,d relations\r",
            nodes.size(), ways.size(), relations.size());
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
            nodes.add(new ChangedNode(currentId, currentVersion, currentChangeType,
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
            ways.add(new ChangedWay(currentId, currentVersion, currentChangeType,
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
            relations.add(new ChangedRelation(currentId, currentVersion, currentChangeType,
                tags, memberIds, roles));
            tagList.clear();
            memberList.clear();
            roleList.clear();
            break;
        }
    }
}
