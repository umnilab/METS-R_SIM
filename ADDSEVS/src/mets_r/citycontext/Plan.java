package mets_r.citycontext;

import com.vividsolutions.jts.geom.Coordinate;

/**
 * Vechicle plan location: destination zone id duration: waiting time (hour) of
 * starting this plan
 * 
 * @author: Zengxiang Lei
 **/

public class Plan {
	private Integer dest_id;
	private Coordinate location; // Add coordination to allow relocating to any place
	private Double duration;

	public Plan(int dest_id, Coordinate loc, double d) {
		this.dest_id = dest_id;
		this.location = loc;
		this.duration = d;
	}

	public Coordinate getLocation() {
		return location;
	}

	public Double getDuration() {
		return duration;
	}

	public int getDestID() {
		return dest_id;
	}
}