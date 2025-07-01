package mets_r.data.input;

import java.util.ArrayList;
import java.util.List;

import mets_r.facility.Road;

public class OneBusSchedule {
	public Integer routeID;
	public ArrayList<Integer> stopZones; // List of zone ID
	
	// Optional fields
	public ArrayList<Integer> departureTime;
	public ArrayList<Road> stopRoads; // List of road
	public ArrayList<List<Road>> pathBetweenStops; // Paths (list of roads) between stops for bus, if null, uses the contemperaray shortest path.
	
	public OneBusSchedule(int routeID, ArrayList<Integer> stopZones, ArrayList<Integer> departureTime, ArrayList<Road> stopRoads) {
		this.routeID = routeID;
		this.stopZones = stopZones;
		this.departureTime = departureTime;
		this.stopRoads = stopRoads;
		this.pathBetweenStops = null;
	}
	
	public OneBusSchedule(int routeID, ArrayList<Integer> stopZones, ArrayList<Integer> departureTime, ArrayList<Road> stopRoads, ArrayList<List<Road>> routesBetweenStops) {
		this.routeID = routeID;
		this.stopZones = stopZones;
		this.departureTime = departureTime;
		this.stopRoads = stopRoads;
		this.pathBetweenStops = routesBetweenStops;
	}
}
