package mets_r.mobility;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.facility.Road;

/**
 * Trip plan 
 * 
 * @author: Zengxiang Lei
 **/

public class Plan { 
	private Integer destZoneID; // ID of the destination zone
	private int destRoadID; // ID of the destination road
	private Coordinate location; // Exact coordinates of the destination
	private Double departure_time; // Departure time

	public Plan(int dest_id, int road_id, Coordinate loc, double d) {
		this.destZoneID = dest_id;
		this.destRoadID = road_id;
		this.location = loc;
		this.departure_time = d;
	}
	
	public Plan(int dest_id, int road_id, double d) {
		this.destZoneID = dest_id;
		this.destRoadID = road_id;
		this.location = ContextCreator.getRoadContext().get(road_id).getEndCoord();
		this.departure_time = d;
	}
	
	public Plan(int road_id, double d) {
		Road r = ContextCreator.getRoadContext().get(road_id);
		this.destZoneID = r.getNeighboringZone(true);
		this.destRoadID = road_id;
		this.location = ContextCreator.getRoadContext().get(road_id).getEndCoord();
		this.departure_time = d;
	}

	public Coordinate getLocation() {
		return location;
	}

	public Double getDuration() {
		return departure_time;
	}

	public int getDestZoneID() {
		return destZoneID;
	}
	
	public int getDestRoadID() {
		return destRoadID;
	}
}