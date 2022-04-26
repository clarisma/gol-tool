package com.geodesk.gol.compiler;

import java.util.HashMap;
import java.util.Map;

// This list may be useful: https://github.com/ideditor/id-area-keys/blob/main/areaKeys.json

public class AreaClassifier 
{
	private static final Map<String,String[]> AREA_TAGS = new HashMap<>();
	private static final String[] EMPTY_STRING_ARRAY = new String[0];
	
	private static void key(String key, String... values)
	{
		if(values==null) values=EMPTY_STRING_ARRAY;
		AREA_TAGS.put(key, values);
	}
	
	static
	{
		key("aeroway", "blacklist", "taxiway");
	    key("amenity");
	    key("area");
	    key("area:highway");
	    key("barrier", "whitelist", "city_wall", "ditch", "hedge", 
		    "retaining_wall", "wall", "spikes");
	    key("boundary");
	    key("building");
	    key("building:part");
	    key("craft");
	    key("golf");
	    key("highway", "whitelist", "services", "rest_area", "escape",  "elevator");
		key("historic");
		key("indoor");
	    key("natural", "blacklist", "coastline", "cliff", "ridge", "arete", "tree_row"); // no: "valley"
		key("landuse");
		key("leisure"); // no: slipway, track
		key("man_made", "blacklist", "cutline", "embankment", "pipeline");
		key("military"); // no: "trench"
		key("office");
		key("place");
	    key("power", "whitelist", "plant", "substation", "generator", "transformer");
	    key("public_transport");
	    key("railway", "whitelist", "station", "turntable", "roundhouse", "platform");
	    key("ruins");
	    key("shop");
	    key("tourism");
	    key("waterway", "whitelist", "riverbank", "dock", "boatyard", "dam");
	}
	
	/**
	 * Determines whether a Feature is an Area based on its tags. Follows the rules
	 * as defined in https://wiki.openstreetmap.org/wiki/Overpass_turbo/Polygon_Features
	 * (https://github.com/tyrasd/osm-polygon-features/blob/master/polygon-features.json).
	 * 
	 * If feature has "area" tag, its value is determinative.
	 * If not, check for presence of the tags listed above; optionally, a list of
	 * values determines specifically whether a tag makes a Feature an Area.
	 *  
	 * @param tags key/value pairs
	 * @return
	 */
	// TODO: change to make this take a Tags object
	public static boolean isArea(Iterable<Map.Entry<String,String>> tags)
	{
		boolean potentialArea = false;
		for(Map.Entry<String,String> tag: tags)
		{
			String k = tag.getKey();
			String v = tag.getValue();
			if("area".equals(k)) return !"no".equals(v);
			String[] values = AREA_TAGS.get(k);
			if(values==null) continue;
			if("no".equals(v)) continue;
			if(values.length==0)
			{
				potentialArea = true;
				continue;
			}
			boolean containsValue = false;
			for(int i2=1; i2<values.length; i2++)
			{
				if(values[i2].equals(v))
				{
					containsValue = true;
					break;
				}
			}
			boolean isBlacklist = values[0] == "blacklist";
			assert isBlacklist || values[0] == "whitelist";
			if(containsValue && !isBlacklist || !containsValue && isBlacklist) 
			{
				potentialArea = true;
			}
		}
		return potentialArea;
	}
}
