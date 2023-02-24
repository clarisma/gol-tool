/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.cli.Verbosity;
import com.clarisma.common.util.Log;
import com.geodesk.core.Mercator;
import com.geodesk.core.XY;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.gol.build.BuildContext;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
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
import java.util.*;
import java.util.zip.GZIPInputStream;

// TODO: What happens if there are multiple versions of the same node,
//  some with tags, some without?

public class ChangeReader extends DefaultHandler
{
    private final TileFinder tileFinder;
    private final FeatureStore store;
    private final int verbosity = Verbosity.VERBOSE;  // TODO

    private int currentChangeType;
    private long currentId;
    private int currentVersion;
    private int currentX, currentY;
    private final List<String> tagList = new ArrayList<>();
    private final MutableLongList memberList = new LongArrayList();
    private final List<String> roleList = new ArrayList<>();
    private final List<ChangedNode> nodes = new ArrayList<>();
    private final MutableLongList untaggedNodes = new LongArrayList();
    private final MutableIntList untaggedNodeVersions = new IntArrayList();
    private final List<ChangedWay> ways = new ArrayList<>();
    private final List<ChangedRelation> relations = new ArrayList<>();
    private Map<String,String> strings = new HashMap<>();
    private long changeCount;
    private long wayNodeCount;
    private long memberCount;
    private long anonymousNodeCount;

    private final static String[] EMPTY_STRING_ARRAY = new String[0];

    public ChangeReader(BuildContext ctx, TileFinder tileFinder) throws IOException
    {
        this.tileFinder = tileFinder;
        this.store = ctx.getFeatureStore();
    }

    public void read(String file, boolean zipped) throws IOException
    {
        try (FileInputStream fin = new FileInputStream(file))
        {
            InputStream in = fin;
            if (zipped) in = new GZIPInputStream(fin);

            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser parser = factory.newSAXParser();
            parser.parse(in, this);
            in.close();
        }
        catch(SAXException | ParserConfigurationException ex)
        {
            throw new IOException("%s: Invalid file (%s)".formatted(file, ex.getMessage()));
        }
    }

    public List<ChangedNode> nodes()
    {
        return nodes;
    }

    public List<ChangedWay> ways()
    {
        return ways;
    }

    public List<ChangedRelation> relations()
    {
        return relations;
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

    public void dump()
    {
        // strings = null;
        reportFreeMemory();
        // Log.debug("%,d of %,d nodes are untagged", anonymousNodeCount, nodes.size());
        Log.debug("Tagged nodes:   %,d", nodes.size());
        Log.debug("Untagged nodes: %,d", untaggedNodes.size());
        Log.debug("Ways:           %,d", ways.size());
        Log.debug("Relations:      %,d", relations.size());
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
        if (verbosity >= Verbosity.NORMAL)
        {
            if((changeCount % (8 * 8192)) == 0) reportProgress();
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
            roleList.add(getString(attr.getValue("role")));
            break;
        case "tag":
            tagList.add(getString(attr.getValue("k")));
            tagList.add(getString(attr.getValue("v")));
            break;
        case "create":
        case "modify":
            currentChangeType = 0;
            break;
        case "delete":
            currentChangeType = ChangedFeature.DELETE;
            break;
        }
    }

    private String getString(String s)
    {
        int code = store.codeFromString(s);
        if(code >= 0) return store.stringFromCode(code);
        String unique = strings.get(s);
        if(unique == null)
        {
            strings.put(s, s);
            unique = s;
        }
        return unique;
    }

    private String[] getTags()
    {
        return tagList.toArray(EMPTY_STRING_ARRAY);
    }

    private String[] getRoles()
    {
        return roleList.toArray(EMPTY_STRING_ARRAY);
    }

    public void endElement (String uri, String localName, String qName)
    {
        String[] tags;
        switch(qName)
        {
        case "node":
            if(currentChangeType == ChangedFeature.DELETE)
            {
                tags = null;
                currentX = currentY = 0;
            }
            else
            {
                tags = getTags();
                if(tags.length == 0)
                {
                    untaggedNodes.add(currentId);
                    untaggedNodes.add(XY.of(currentX, currentY));
                    // untaggedNodeVersions.add(currentVersion);
                    break;
                }
            }
            nodes.add(new ChangedNode(currentId, currentChangeType, currentVersion, tags, currentX, currentY));
            // Always clear list, since tags may be listed even for deleted nodes
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
                wayNodeCount+=nodeIds.length;
            }
            ways.add(new ChangedWay(currentId, currentChangeType, currentVersion, tags, nodeIds));
            // Always clear lists, since tags/nodes may be listed even for deleted ways
            tagList.clear();
            memberList.clear();
            break;
        case "relation":
            long[] memberIds;
            String[] roles;
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
                memberCount += memberIds.length;
            }
            relations.add(new ChangedRelation(currentId, currentChangeType, currentVersion, tags, memberIds, roles));
            // Always clear lists, since tags/members/roles may be listed even for deleted relations
            tagList.clear();
            memberList.clear();
            roleList.clear();
            break;
        }
    }
}
