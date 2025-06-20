package mets_r.data.input;

import java.util.ArrayList;

public class OneBusSchedule {
	public Integer routeID;
	public ArrayList<Integer> busRoute; // List of zone ID
	public ArrayList<Integer> departureTime;

	public OneBusSchedule(int routeID, ArrayList<Integer> busRoute, int departureTime) {
		this.routeID = routeID;
		this.busRoute = busRoute;
		this.departureTime = new ArrayList<Integer>();
		for (int i=0; i<busRoute.size(); i++) {
			this.departureTime.add(departureTime);
		}
	}

	public OneBusSchedule(int routeID, ArrayList<Integer> busRoute, ArrayList<Integer> departureTime) {
		this.routeID = routeID;
		this.busRoute = busRoute;
		this.departureTime = departureTime;
	}
}
