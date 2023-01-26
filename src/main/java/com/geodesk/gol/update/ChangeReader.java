/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.geodesk.core.Mercator;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.impl.factory.primitive.LongLists;
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


public class ChangeReader extends DefaultHandler
{
	private final ChangeGraph graph;
	private final SAXParser parser;
	private ChangeType currentChangeType;
	private long currentId;
	private int currentX, currentY;
	private final List<String> currentTags = new ArrayList<>();
	private final MutableLongList currentChildIds = LongLists.mutable.empty();
	private final List<String> currentRoles = new ArrayList<>();
	/*
	protected String timestamp;
	protected String userName;
	protected String userId;
	 */
	protected int version;

	public ChangeReader(ChangeGraph graph)
	{
		this.graph = graph;
		SAXParserFactory factory = SAXParserFactory.newInstance();
		try
		{
			parser = factory.newSAXParser();
		}
		catch(SAXException | ParserConfigurationException ex)
		{
			throw new RuntimeException(
				"Failed to initialize SAXParser: " + ex.getMessage());
		}
	}

	public void read(InputStream in) throws SAXException, IOException
	{
		parser.parse(in, this);
	}

	public void read(String fileName) throws SAXException, IOException
	{
//		try(BufferedInputStream in = new BufferedInputStream(
//			new FileInputStream(fileName), 64 * 1024))
		try(FileInputStream in = new FileInputStream(fileName))
		{
			parser.parse(in, this);
		}
	}


	private void storeId(Attributes attributes)
	{
		currentId = Long.parseLong(attributes.getValue("id"));
		/*
		userId = attributes.getValue("uid");
		userName = attributes.getValue("user");
		timestamp = attributes.getValue("timestamp");
		 */
		version = Integer.parseInt(attributes.getValue("version"));
	}
		
	public void startElement (String uri, String localName,
		String qName, Attributes attributes)
	{
		switch(qName)
		{
		case "node":
			storeId(attributes);
			double lon = Double.parseDouble(attributes.getValue("lon"));
			double lat = Double.parseDouble(attributes.getValue("lat"));
			currentX = (int)Math.round(Mercator.xFromLon(lon));
			currentY = (int)Math.round(Mercator.yFromLat(lat));
			break;
		case "way":
		case "relation":
			storeId(attributes);
			break;
		case "nd":
			currentChildIds.add(Long.parseLong(attributes.getValue("ref")));
			break;
		case "member":
			currentRoles.add(attributes.getValue("role"));
			long memberId = Long.parseLong(attributes.getValue("ref"));
			String type = attributes.getValue("type");
			currentChildIds.add(FeatureId.of(FeatureType.from(type), memberId));
			break;
		case "tag":
			currentTags.add(attributes.getValue("k"));
			currentTags.add(attributes.getValue("v"));
			break;
		case "create":
			currentChangeType = ChangeType.CREATE;
			break;
		case "modify":
			currentChangeType = ChangeType.MODIFY;
			break;
		case "delete":
			currentChangeType = ChangeType.DELETE;
			break;
		}
	}

	public void endElement (String uri, String localName, String qName)
	{
		switch(qName)
		{
		case "node":
			if(currentChangeType == ChangeType.DELETE)
			{
				graph.deleteNode(version, currentId);
			}
			else
			{
				graph.changeNode(version, currentId, currentTags, currentX, currentY);
			}
			currentTags.clear();
			break;
		case "way":
			if(currentChangeType == ChangeType.DELETE)
			{
				graph.deleteWay(version, currentId);
			}
			else
			{
				graph.changeWay(version, currentId, currentTags, currentChildIds);
			}
			currentTags.clear();
			currentChildIds.clear();
			break;
		case "relation":
			if(currentChangeType == ChangeType.DELETE)
			{
				graph.deleteRelation(version, currentId);
			}
			else
			{
				graph.changeRelation(version, currentId, currentTags,
					currentChildIds, currentRoles);
			}
			currentTags.clear();
			currentChildIds.clear();
			currentRoles.clear();
			break;
		}
	}
}
