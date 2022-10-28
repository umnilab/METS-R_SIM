package mets_r.routing;

import java.util.ArrayList;
import java.util.HashMap;

import mets_r.ContextCreator;

/**
 * 
 * Route matrix stores the routing information in a map
 * the map is organised as follows
 * src_junc,dest_junc -> array of linkIDs
 * key  	: src_junc_ID,dest_junc_ID (as a string)
 * value 	: array of linkIDs
 * @author Charitha Saumya
 *
 */

public class RouteMap {
	
	private HashMap<String, ArrayList<Integer>> storage = new HashMap<String, ArrayList<Integer>>();
	
	// Line format for route prediction
	// jun_drc,junc_dest:linkid1,linkid2,linkid3.....,linkidn
	public void updateRoute(String line) {
		// Get the src, dest string
		String[] words = line.split(":");
		
		String key = words[0]; // This is the key to route map
		
		// Get the list of roads for this route
		String[] roadLinkWords = words[1].split(",");
		ArrayList<Integer> roadLinks = new ArrayList<Integer>();
		for(String roadLinkWord : roadLinkWords) {
			roadLinks.add(Integer.parseInt(roadLinkWord));
		}
		
		this.storage.put(key, roadLinks);
		
	}
	
	public ArrayList<Integer> getRoute(String key){
		ArrayList<Integer> roadLinks = this.storage.get(key);
		
		if(roadLinks == null) {
			ContextCreator.logger.error("Route not found for src, dest junctions " + key);
		}
		return roadLinks;
		
	}
}
