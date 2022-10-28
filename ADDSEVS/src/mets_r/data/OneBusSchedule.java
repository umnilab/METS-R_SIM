package mets_r.data;

import java.util.ArrayList;

public class OneBusSchedule {
	public Integer routeID;
	public ArrayList<Integer> busRoute;
	public ArrayList<Integer> departureTime;
	
	public OneBusSchedule(int routeID, ArrayList<Integer> busRoute, int departureTime) {
		this.routeID = routeID;
		this.busRoute = busRoute;
		this.departureTime = new ArrayList<Integer>();
		for (@SuppressWarnings("unused") int stop: busRoute) {
			this.departureTime.add(departureTime);
		}
	}
	
	public OneBusSchedule(int routeID, ArrayList<Integer> busRoute, ArrayList<Integer> departureTime) {
		this.routeID = routeID;
		this.busRoute = busRoute;
		this.departureTime = departureTime;
	}
}
