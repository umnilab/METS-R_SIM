package mets_r.mobility;

import com.vividsolutions.jts.geom.Coordinate;

/**
 * Trip plan 
 * 
 * @author: Zengxiang Lei
 **/

public class Plan { 
	private Integer dest_id; // ID of the destination zone
	private Coordinate location; // Exact coordinates of the destination
	private Double departure_time; // Departure time

	public Plan(int dest_id, Coordinate loc, double d) {
		this.dest_id = dest_id;
		this.location = loc;
		this.departure_time = d;
	}

	public Coordinate getLocation() {
		return location;
	}

	public Double getDuration() {
		return departure_time;
	}

	public int getDestID() {
		return dest_id;
	}
}