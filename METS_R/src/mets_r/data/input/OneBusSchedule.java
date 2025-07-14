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
		this(routeID, stopZones, departureTime, stopRoads, null);
	}
	
	public OneBusSchedule(int routeID, ArrayList<Integer> stopZones, ArrayList<Integer> departureTime, ArrayList<Road> stopRoads, ArrayList<List<Road>> routesBetweenStops) {
		// Replace this with deep copy
		this.routeID = routeID;
		this.stopZones = new ArrayList<>(stopZones);
	    this.departureTime = new ArrayList<>(departureTime);

		// Deep copy of Road objects for stopRoads
        this.stopRoads = new ArrayList<>();
        for (Road road : stopRoads) {
            this.stopRoads.add(road);  // assumes Road has a copy constructor
        }

        // Deep copy of paths between stops if provided
        if (routesBetweenStops != null) {
            this.pathBetweenStops = new ArrayList<>();
            for (List<Road> segment : routesBetweenStops) {
                List<Road> copiedSegment = new ArrayList<>();
                for (Road road : segment) {
                    copiedSegment.add(road);
                }
                this.pathBetweenStops.add(copiedSegment);
            }
        } else {
            this.pathBetweenStops = null;
        }
	}
}
