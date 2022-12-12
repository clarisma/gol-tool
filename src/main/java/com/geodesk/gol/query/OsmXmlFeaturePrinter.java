/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.clarisma.common.text.Strings;
import com.geodesk.core.XY;
import com.geodesk.feature.*;
import com.geodesk.feature.store.AnonymousWayNode;
import com.geodesk.gol.GolTool;
import org.eclipse.collections.api.map.primitive.MutableLongLongMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

// TODO: Add comment about file not suitable for editing
// TODO: add query string to comments

public class OsmXmlFeaturePrinter extends AbstractFeaturePrinter
{
    final MutableLongObjectMap<Node> syntheticNodes = new LongObjectHashMap();
    final MutableLongObjectMap<Node> nodes = new LongObjectHashMap<>();
    final Set<Way> ways = new HashSet<>();
    final Set<Relation> relations = new HashSet<>();
    final XmlWriter xml;

    private class SyntheticWayNode extends AnonymousWayNode
    {
        final long id;

        SyntheticWayNode(long id, int x, int y)
        {
            super(null, x, y);
            this.id = id;
        }

        @Override public long id()
        {
            return id;
        }
    }

    public OsmXmlFeaturePrinter(PrintStream out)
    {
        super(out);
        xml = new XmlWriter(out);
    }

    private Node addNode(Node node)
    {
        long id = node.id();
        return nodes.getIfAbsentPut(id, node);
    }

    @Override public void print(Feature feature)
    {
        if(feature instanceof Node node)
        {
            addNode(node);
        }
        else if(feature instanceof Way way)
        {
            ways.add(way);
        }
        else
        {
            relations.add((Relation)feature);
        }
    }

    private void loadMembers(Relation rel)
    {
        for (Feature member : rel.members())
        {
            if (member instanceof Way memberWay)
            {
                ways.add(memberWay);
            }
            else if(member instanceof Node memberNode)
            {
                addNode(memberNode);
            }
            else
            {
                Relation memberRel = (Relation)member;
                if(!relations.contains(memberRel))
                {
                    relations.add(memberRel);
                    loadMembers(memberRel);
                }
            }
        }
    }

    private void loadAll()
    {
        // Recursively retrieve all relation members
        // (We make a copy of relation set, because loadMembers will modify
        // the underlying set)
        for(Relation rel: new ArrayList<>(relations)) loadMembers(rel);

        // Retrieve all feature nodes referenced by the ways

        for(Way way: ways)
        {
            for(Node node: way) addNode(node);
        }

        // Now, get all way nodes; create IDs for anonymous nodes

        long nextNodeId = 0;
        for(Way way: ways)
        {
            Features<Node> wayNodes = way.nodes();
            int i=0;
            for(Node node: way.nodes())
            {
                long nodeId = node.id();
                if(nodeId == 0)
                {
                    int x = node.x();
                    int y = node.y();
                    long xy = XY.of(x,y);
                    if(!syntheticNodes.containsKey(xy))
                    {
                        for (;;)
                        {
                            nextNodeId++;
                            if (!nodes.containsKey(nextNodeId)) break;
                        }
                        nodeId = nextNodeId;
                        syntheticNodes.put(xy, new SyntheticWayNode(nodeId, x, y));
                    }
                }
            }
        }
    }

    private void printNode(Node node)
    {
        xml.begin("node");
        xml.attr("id", node.id());
        xml.attr("lat", transformer.toString(transformer.transformY(node.y())));
        xml.attr("lon", transformer.toString(transformer.transformX(node.x())));
        xml.attr("version", "1");
        xml.attr("visible", "true");
        printTags(node);
        xml.end();
    }

    private void printWay(Way way)
    {
        xml.begin("way");
        xml.attr("id", way.id());
        xml.attr("version", "1");
        xml.attr("visible", "true");
        printTags(way);
        printWayNodes(way);
        xml.end();
    }

    private void printRelation(Relation rel)
    {
        xml.begin("relation");
        xml.attr("id", rel.id());
        xml.attr("version", "1");
        xml.attr("visible", "true");
        printTags(rel);
        printMembers(rel);
        xml.end();
    }

    private void printTags(Feature f)
    {
        extractProperties(f.tags());
        printProperties();
    }

    private void printWayNodes(Way way)
    {
        for(Node node: way.nodes())
        {
            long nodeId = node.id();
            if(nodeId == 0)
            {
                long xy = XY.of(node.x(), node.y());
                node = syntheticNodes.get(xy);
                assert node != null;
                nodeId = node.id();
            }
            xml.begin("nd");
            xml.attr("ref", nodeId);
            xml.end();
        }
    }

    private void printMembers(Relation rel)
    {
        for(Feature member: rel.members())
        {
            xml.begin("member");
            xml.attr("type", FeatureType.toString(member.type()));  // lowercase version
            xml.attr("ref", member.id());
            xml.attr("role", member.role());
            xml.end();
        }
    }

    @Override protected void printProperty(String key, String value)
    {
        xml.begin("tag");
        xml.attr("k", key);
        xml.attr("v", value);
        xml.end();
    }


    @Override public void printHeader()
    {
        xml.begin("osm");
        xml.attr("version", "0.6");
        xml.attr("generator", "geodesk gol/" + GolTool.VERSION);
        xml.attr("upload", "never");
    }

    @Override public void printFooter()
    {
        loadAll();

        List<Node> nodeList = new ArrayList<>(nodes.size() + syntheticNodes.size());
        nodeList.addAll(nodes.values());
        nodeList.addAll(syntheticNodes.values());
        // Collections.sort(nodeList);  // wait for geodesk#45 to allow sorting

        for(Node node: nodeList) printNode(node);
        nodeList = null;
        List<Way> wayList = new ArrayList<>(ways);
        for(Way way: wayList) printWay(way);
        wayList = null;
        List<Relation> relList = new ArrayList<>(relations);
        for(Relation rel: relList) printRelation(rel);
        relList = null;

        xml.end();
        xml.flush();
    }
}

