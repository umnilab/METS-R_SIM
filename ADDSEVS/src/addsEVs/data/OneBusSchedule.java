package addsEVs.data;

import java.util.ArrayList;

public class OneBusSchedule {
	public Integer routeID;
	public ArrayList<Integer> busRoute;
	public Integer departureTime;
	
	public OneBusSchedule(int routeID, ArrayList<Integer> busRoute, int departureTime) {
		this.routeID = routeID;
		this.busRoute = busRoute;
		this.departureTime = departureTime;
	}
}
