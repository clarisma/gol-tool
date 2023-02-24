/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import com.geodesk.feature.FeatureId;
import com.geodesk.feature.FeatureType;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.impl.factory.primitive.LongLists;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

// TODO: OscReader
// TODO: could pass LongList, Iterable<String> to avoid needless copying,
//  but risks introducing subtle bugs if consumer does not copy
//  (Current implementation assumes consumer uses arrays; consumer may also
//  iterate and encode as PBF)

public class ChangeSetReader extends DefaultHandler
{
	private final SAXParser parser;
	private ChangeType currentChangeType;
	private long currentId;
	private double currentLon, currentLat;
	private final List<String> currentTags = new ArrayList<>();
	private final MutableLongList currentChildIds = LongLists.mutable.empty();
	private final List<String> currentRoles = new ArrayList<>();
	protected String timestamp;
	protected String userName;
	protected String userId;
	protected int version;

	public ChangeSetReader() 
	{
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

	protected void node(ChangeType change, long id, double lon, double lat, String[] tags)
	{
		// do nothing
	}

	protected void way(ChangeType change, long id, String[] tags, long[] nodeIds)
	{
		// do nothing
	}

	protected void relation(ChangeType change, long id, String[] tags, long[] memberIds, String[] roles)
	{
		// do nothing
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
		userId = attributes.getValue("uid");
		userName = attributes.getValue("user");
		timestamp = attributes.getValue("timestamp");
		version = Integer.parseInt(attributes.getValue("version"));
	}
		
	public void startElement (String uri, String localName,
		String qName, Attributes attributes)
	{
		switch(qName)
		{
		case "node":
			storeId(attributes);
			currentLon = Double.parseDouble(attributes.getValue("lon"));
			currentLat = Double.parseDouble(attributes.getValue("lat"));
			break;
		case "way":
			storeId(attributes);
			break;
		case "nd":
			currentChildIds.add(Long.parseLong(
				attributes.getValue("ref")));
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
		case "relation":
			storeId(attributes);
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

	private String[] getTags()
	{
		String[] tags = currentTags.toArray(new String[0]);
		currentTags.clear();
		return tags;
	}

	public void endElement (String uri, String localName, String qName)
	{
		switch(qName)
		{
		case "node":
			node(currentChangeType, currentId, currentLon, currentLat, getTags());
			break;
		case "way":
			long[] nodeIds = currentChildIds.toArray();
			way(currentChangeType, currentId, getTags(), nodeIds);
			currentChildIds.clear();
			break;
		case "relation":
			long[] memberIds = currentChildIds.toArray();
			String[] roles = currentRoles.toArray(new String[0]);
			relation(currentChangeType, currentId, getTags(), memberIds, roles);
			currentChildIds.clear();
			currentRoles.clear();
			break;
		}
	}
}
