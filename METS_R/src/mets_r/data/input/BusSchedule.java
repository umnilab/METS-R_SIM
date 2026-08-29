package mets_r.data.input;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.Road;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;

class The_Comparator implements Comparator<OneBusSchedule> {
	public int compare(OneBusSchedule s1, OneBusSchedule s2) {
		Integer d1 = s1.departureTime.get(0);
		Integer d2 = s2.departureTime.get(0);
		return d1.compareTo(d2);
	}
}

public class BusSchedule {
	// Required
	private ArrayList<Integer> routeIDs;
	private HashMap<Integer, String> routeID2RouteName;
	private HashMap<String, Integer> routeName2RouteID;
	private HashMap<Integer, ArrayList<Integer>> toVisitZones; 
	
	// Optional
	private HashMap<Integer, ArrayList<Road>> toVisitRoads; 
	private HashMap<Integer, ArrayList<List<Road>>> scheduledPaths;
	
	private ArrayList<Double> busGap; // Time gap between two-consecutive runs
	private ArrayList<Double> busNum; // Number of runs in total
	
	// For mapping between zone ID in json files to simulation zone ID
	private Map<Long, Integer> locationIDMap; 
	
	// For assign schedule to vehicles, key: depart zone
	private ConcurrentHashMap<Integer, PriorityBlockingQueue<OneBusSchedule>> pendingSchedules;
	private ConcurrentHashMap<Integer, Integer> ongoingSchedules;
	
	// For generating bus routes
	public static Random rand_route_only = new Random(GlobalVariables.RandomGenerator.nextInt());
	public static int route_num = 0;

	public BusSchedule() {
		routeIDs = new ArrayList<Integer>();
		routeID2RouteName = new HashMap<Integer, String>();
		routeName2RouteID = new HashMap<String, Integer>();
		toVisitZones = new HashMap<Integer, ArrayList<Integer>>();
		toVisitRoads = new HashMap<Integer, ArrayList<Road>>();
		scheduledPaths = new HashMap<Integer, ArrayList<List<Road>>> ();
		pendingSchedules = new ConcurrentHashMap<Integer, PriorityBlockingQueue<OneBusSchedule>>();
		ongoingSchedules = new  ConcurrentHashMap<Integer, Integer>();
		
		readEventFile();
	}
 
	// Read and parse the JSON files
	@SuppressWarnings("unchecked")
	public void readEventFile() {
		JSONParser parser = new JSONParser();
		try {
			locationIDMap = new HashMap<Long, Integer>();
			BufferedReader br = new BufferedReader(new FileReader(GlobalVariables.ZONES_CSV));
			br.readLine(); // Skip the first row
			int integerID = 1;
			String line;
			while ((line = br.readLine()) != null) {
				String[] result = line.split(",");
				locationIDMap.put(Long.parseLong(result[1]), integerID);
				integerID += 1;
			}
			br.close();
			
			Object obj;
			try (FileReader scheduleReader =
					new FileReader(GlobalVariables.BUS_SCHEDULE)) {
				obj = parser.parse(scheduleReader);
			}
			JSONObject jsonObject = (JSONObject) obj;
			ArrayList<Long> routeNames = (ArrayList<Long>) jsonObject.get("names");
			ArrayList<ArrayList<Long>> routes =
					(ArrayList<ArrayList<Long>>) jsonObject.get("routes");
			busNum = (ArrayList<Double>) jsonObject.get("nums");
			busGap = (ArrayList<Double>) jsonObject.get("gaps");
			if (routeNames == null || routes == null || busNum == null || busGap == null
					|| routeNames.size() != routes.size()
					|| routeNames.size() != busNum.size()
					|| routeNames.size() != busGap.size()) {
				throw new IllegalArgumentException(
						"Bus schedule names, routes, nums, and gaps must have equal lengths");
			}
			for (Long name : routeNames) {
				int routeID = BusSchedule.genRouteID();
				this.routeIDs.add(routeID);
				routeID2RouteName.put(routeID, name.toString());
				routeName2RouteID.put(name.toString(), routeID);
			}
			
			int k = 0;
			for (ArrayList<Long> route : routes) {
				int rID = this.routeIDs.get(k);
				ArrayList<Integer> oneRoute = new ArrayList<Integer>();
				for (Long station : route) {
					if(locationIDMap.containsKey(station.longValue())){
						oneRoute.add(locationIDMap.get(station.longValue()));
					}
				}
				if (oneRoute.size() < 2) {
					throw new IllegalArgumentException(
							"Bus route " + routeID2RouteName.get(rID)
									+ " must contain at least two recognized stops");
				}
				toVisitZones.put(rID, oneRoute);
				
				k++;
			}
			
			ContextCreator.logger.info("Loaded bus schedule from offline files.");
		} catch (FileNotFoundException e) {
			throw new IllegalStateException(
					"Bus schedule input was not found: " + GlobalVariables.BUS_SCHEDULE, e);
		} catch (Exception e) {
			throw new IllegalStateException(
					"Failed to load bus schedule from " + GlobalVariables.BUS_SCHEDULE, e);
		}	
	}
	
	public void postProcessing() {
		for(int rID: this.routeIDs) {
			ArrayList<Integer> oneRoute = toVisitZones.get(rID);
			// For each route loaded from the offline file
			for(int i = 0; i < oneRoute.size() - 1; i++) {
				for(int j = i + 1; j < oneRoute.size() ; j++) {
					if(!ContextCreator.getZoneContext().get(oneRoute.get(i)).traversingBusRoutes.containsKey(oneRoute.get(j))) {
						ContextCreator.getZoneContext().get(oneRoute.get(i)).traversingBusRoutes.put(oneRoute.get(j), new ArrayList<Integer>());
					}
					ContextCreator.getZoneContext().get(oneRoute.get(i)).traversingBusRoutes.get(oneRoute.get(j)).add(rID);
				}
			}
			
			// Generate list of road based on the zone
			ArrayList<Road> stopRoads = new ArrayList<Road>();
			for(int zoneID: oneRoute) {
				Zone z = ContextCreator.getZoneContext().get(zoneID);
				Integer closestRoadID = z.getClosestRoad(false);
				if (closestRoadID == null) {
					throw new IllegalStateException("Route " + rID + ": zone " + zoneID
							+ " has no reachable destination road");
				}
				Road r = ContextCreator.getRoadContext().get(closestRoadID);
				if (r == null) {
					throw new IllegalStateException("Route " + rID + ": zone " + zoneID
							+ " closest road ID " + closestRoadID
							+ " not found in road context");
				}
				stopRoads.add(r);
			}
			toVisitRoads.put(rID, stopRoads);
		}
		
		// Translate bus route into bus schedules
		int n = this.routeIDs.size();
		for (int i = 0; i < n; i++) {
			int startZone = this.toVisitZones.get(routeIDs.get(i)).get(0);
			if (!pendingSchedules.containsKey(startZone)) {
				pendingSchedules.putIfAbsent(startZone,
						new PriorityBlockingQueue<OneBusSchedule>(11, new The_Comparator()));
			}
			for (int j = 0; j < busNum.get(i); j++) { 
				int depTime = (int) (j * busGap.get(i) * 60 / GlobalVariables.SIMULATION_STEP_SIZE);
				ArrayList<Integer> departureTime = new ArrayList<Integer>();
				for (int k=0; k< this.toVisitZones.get(routeIDs.get(i)).size(); k++) {
					departureTime.add(depTime);
				}
				OneBusSchedule obs = new OneBusSchedule(this.routeIDs.get(i), this.toVisitZones.get(routeIDs.get(i)), departureTime, this.toVisitRoads.get(routeIDs.get(i)));
				pendingSchedules.get(startZone).add(obs);
			}
		}
	}

	// For adding bus routes
	public boolean insertNewRoute(String routeName, ArrayList<Integer> stopZones) {
		// sanity check, whether the routeName has been taken
		if(routeName2RouteID.containsKey(routeName)){
			ContextCreator.logger.warn("Fail to insert bus route: " + routeName + " since a route with the same name exists.");
			return false;
		}
		ArrayList<Road> stopRoads = new ArrayList<Road>();
		for(int zoneID: stopZones) {
			Zone z = ContextCreator.getZoneContext().get(zoneID);
			Integer closestRoadID = z.getClosestRoad(false);
			if (closestRoadID == null) {
				ContextCreator.logger.error("insertNewRoute " + routeName + ": zone " + zoneID + " has no reachable destination road.");
				return false;
			}
			Road r = ContextCreator.getRoadContext().get(closestRoadID);
			if (r == null) {
				ContextCreator.logger.error("insertNewRoute " + routeName + ": zone " + zoneID + " closest road ID " + closestRoadID + " not found.");
				return false;
			}
			stopRoads.add(r);
		}
		return insertNewRoute(routeName, stopZones, stopRoads, null);
	}
	
	public boolean insertNewRoute(String routeName, ArrayList<Integer> stopZones, ArrayList<Road> stopRoads) {
		return insertNewRoute(routeName, stopZones, stopRoads, null);
	}
	
	public boolean insertNewRoute(String routeName, ArrayList<Integer> stopZones, ArrayList<Road> stopRoads, ArrayList<List<Road>> roadsBetweenStops) {
		// sanity check, whether the routeName has been taken
		if(routeName2RouteID.containsKey(routeName)){
			ContextCreator.logger.error("Fail to insert bus route: " + routeName + " since a route with the same name exists.");
			return false;
		}
		int rID = BusSchedule.genRouteID();
		this.routeIDs.add(rID);
		this.routeID2RouteName.put(rID, routeName);
		this.routeName2RouteID.put(routeName, rID);
		this.toVisitZones.put(rID, stopZones);
		
		for(int i = 0; i < stopZones.size() - 1; i++) {
			for(int j = i + 1; j < stopZones.size() ; j++) {
				if(!ContextCreator.getZoneContext().get(stopZones.get(i)).traversingBusRoutes.containsKey(stopZones.get(j))) {
					ContextCreator.getZoneContext().get(stopZones.get(i)).traversingBusRoutes.put(stopZones.get(j), new ArrayList<Integer>());
				}
				ContextCreator.getZoneContext().get(stopZones.get(i)).traversingBusRoutes.get(stopZones.get(j)).add(rID);
			}
		}
		
		
		this.toVisitRoads.put(rID, stopRoads);
		this.scheduledPaths.put(rID, roadsBetweenStops);
		return true;
	}
	
	public boolean insertNewRouteByRoadNames(String routeName, ArrayList<Integer> stopZones, ArrayList<String> stopRoadNames) {
		if(routeName2RouteID.containsKey(routeName)){
			ContextCreator.logger.error("Fail to insert bus route: " + routeName + " since a route with the same name exists.");
			return false;
		}
		
		if (stopZones.size() != stopRoadNames.size()) {
			ContextCreator.logger.error("Fail to insert new bus route: " + routeName + ", you have " + stopZones.size() + " zones associated with " + stopRoadNames.size() + " roads");
			return false;
		}
		
		// Translate stopRoads back to list of roads
		ArrayList<Road> stopRoads= new ArrayList<Road>();
		for(String roadName: stopRoadNames) {
			Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadName);
			if(r == null) {
				ContextCreator.logger.error("Fail to insert new bus route: " + routeName + ", cannot find road with roadName: " + roadName);
				return false;
			}
			else {
				stopRoads.add(r);
			}
		}
		
		return insertNewRoute(routeName, stopZones, stopRoads);
	}

	public boolean insertNewRouteByRoadNames(String routeName, ArrayList<Integer> stopZones, ArrayList<String> stopRoadNames, ArrayList<List<String>> roadNamesBetweenStops) {
		if(routeName2RouteID.containsKey(routeName)){
			ContextCreator.logger.error("Fail to insert bus route: " + routeName + " since a route with the same name exists.");
			return false;
		}
		
		if (stopZones.size() != stopRoadNames.size()) {
			ContextCreator.logger.error("Fail to insert new bus routee: " + routeName + ", you have " + stopZones.size() + " zones associated with " + stopRoadNames.size() + " roads");
			return false;
		}
		if (stopZones.size() != roadNamesBetweenStops.size() + 1) {
			ContextCreator.logger.error("Fail to insert new bus routee: " + routeName + ", you have " + stopZones.size() + " zones associated with " + roadNamesBetweenStops.size() + " intermediate paths");
			return false;
		}
		
		
		// Translate stopRoads back to list of roads
		ArrayList<Road> stopRoads= new ArrayList<Road>();
		for(String roadName: stopRoadNames) {
			Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadName);
			if(r == null) {
				ContextCreator.logger.error("Fail to insert new bus route: " + routeName + ", cannot find road with roadName: " + roadName);
				return false;
			}
			else {
				stopRoads.add(r);
			}
		}
		
		ArrayList<List<Road>> roadsBetweenStops= new ArrayList<List<Road>>();
		for(List<String> roadNames: roadNamesBetweenStops) {
			ArrayList<Road> rs = new ArrayList<Road>();
			
			for(String roadName: roadNames) {
				Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadName);
				if(r == null) {
					ContextCreator.logger.error("Fail to insert new bus route: " + routeName + ", cannot find road with roadName: " + roadName);
					return false;
				}
				else {
					rs.add(r);
				}
			}
			
			roadsBetweenStops.add(rs);
		}
		
		return insertNewRoute(routeName, stopZones, stopRoads, roadsBetweenStops);
	}
	
	// For insert a bus run
	public boolean insertBusRun(int rID, ArrayList<Integer> departTime) {
		if(!routeIDs.contains(rID)) {
			return false;
		}
		int startZone = toVisitZones.get(rID).get(0); 
		if (!pendingSchedules.containsKey(startZone)) {
			pendingSchedules.putIfAbsent(startZone,
					new PriorityBlockingQueue<OneBusSchedule>(11, new The_Comparator()));
		}
		
		if(!scheduledPaths.containsKey(rID)) {
			OneBusSchedule obs = new OneBusSchedule(rID, toVisitZones.get(rID), departTime, toVisitRoads.get(rID));
			pendingSchedules.get(startZone).add(obs);
		}
		else {
			OneBusSchedule obs = new OneBusSchedule(rID, toVisitZones.get(rID), departTime, toVisitRoads.get(rID), scheduledPaths.get(rID));
			pendingSchedules.get(startZone).add(obs);
		}
		return true;
	}
	
	public boolean insertBusRun(String routeName, ArrayList<Integer> departTime) {
		if(!routeName2RouteID.containsKey(routeName)) {
			return false;
		}
		return insertBusRun(routeName2RouteID.get(routeName), departTime);
	}
	
	
	public void popSchedule(int startZone, ElectricBus b) {
		if (this.pendingSchedules != null && this.pendingSchedules.containsKey(startZone)) {
			// Update bus schedule
			if (this.pendingSchedules.get(startZone).size() > 0) {
				if (this.pendingSchedules.get(startZone).peek().departureTime
						.get(0) < (ContextCreator.getCurrentTick() + 600 / GlobalVariables.SIMULATION_STEP_SIZE)) {
					OneBusSchedule obs = this.pendingSchedules.get(startZone).poll();
					b.updateSchedule(obs);
					ongoingSchedules.merge(obs.routeID, 1, Integer::sum);
					
					return;
				}
			}
		}
		// Otherwise set a null schedule
		b.updateSchedule(null);
	}

	public boolean hasSchedule(Integer stopZone) {
		PriorityBlockingQueue<OneBusSchedule> schedules =
				this.pendingSchedules.get(stopZone);
		return schedules != null && !schedules.isEmpty();
	}
	
	public int getRouteID(String routeName) {
		if (this.routeName2RouteID.containsKey(routeName)) {
			return this.routeName2RouteID.get(routeName);
		}
		return -1;
	}
	
	public String getRouteName(int rID) {
		if (this.routeID2RouteName.containsKey(rID)) {
			return this.routeID2RouteName.get(rID);
		}
		return null;
	}
	
	public ArrayList<Integer> getRouteIDs() {
		ArrayList<Integer> res = new ArrayList<Integer>();
		for(int r: this.routeIDs) {
			res.add(r);
		}
		return res;
	}
	
	public ArrayList<String> getRouteNames() {
		ArrayList<String> res = new ArrayList<String>();
		for(int r: this.routeIDs) {
			res.add(this.routeID2RouteName.get(r));
		}
		return res;
	}
	
	public int getNextDepartTime(int routeID) {
		ArrayList<Integer> stopZones = this.toVisitZones.get(routeID);
		if (stopZones == null || stopZones.isEmpty()) return -1;
		int startZone = stopZones.get(0);
		PriorityBlockingQueue<OneBusSchedule> schedules =
				this.pendingSchedules.get(startZone);
		if (schedules == null) return -1;
		int nextDeparture = Integer.MAX_VALUE;
		for(OneBusSchedule obs: schedules) {
			if(routeID == obs.routeID) {
				nextDeparture = Math.min(nextDeparture, obs.departureTime.get(0));
			}
		}
		return nextDeparture == Integer.MAX_VALUE ? -1 : nextDeparture;
	}
	
	public boolean isOngoing(int rID) {
		Integer count = ongoingSchedules.get(rID);
		return count != null && count > 0;
	}
	
	public boolean isValid(int rID) {
		if(getNextDepartTime(rID) >= 0 || isOngoing(rID)) return true;
		else return false;
	}

	public boolean referencesZone(int zoneID) {
		for (ArrayList<Integer> stopZones : this.toVisitZones.values()) {
			if (stopZones.contains(zoneID)) return true;
		}
		return false;
	}

	public boolean referencesRoad(Road road) {
		if (road == null) return false;
		int roadID = road.getID();
		for (ArrayList<Road> stopRoads : this.toVisitRoads.values()) {
			if (stopRoads == null) continue;
			for (Road stopRoad : stopRoads) {
				if (stopRoad != null && stopRoad.getID() == roadID) return true;
			}
		}
		for (ArrayList<List<Road>> paths : this.scheduledPaths.values()) {
			if (paths == null) continue;
			for (List<Road> path : paths) {
				if (path == null) continue;
				for (Road pathRoad : path) {
					if (pathRoad != null && pathRoad.getID() == roadID) return true;
				}
			}
		}
		return false;
	}
	
	public void finishSchedule(int rID) {
		ongoingSchedules.computeIfPresent(rID,
				(key, count) -> Math.max(0, count - 1));
	}
	
	public ArrayList<Integer> getStopZones(int rID) {
		if(toVisitZones.containsKey(rID)) {
			ArrayList<Integer> res = new ArrayList<Integer>();
			for(int zoneID: this.toVisitZones.get(rID)) {
				res.add(zoneID);
			}
			return res;
		}
		return null;
	}
	
	public ArrayList<String> getStopRoadNames(int rID){
		if(toVisitRoads.containsKey(rID)) {
			ArrayList<String> res = new ArrayList<String>();
			for(Road r: this.toVisitRoads.get(rID)) {
				res.add(r.getOrigID());
			}
			return res;
		}
		return null;
	}
	
	public Road getStopRoad(int rID, int stopInd){
		if(toVisitRoads.containsKey(rID) && this.toVisitRoads.get(rID).size() > stopInd) {
			return this.toVisitRoads.get(rID).get(stopInd);
		}
		return null;
	}
	
	
	public static int genRouteID() {
		return route_num++;
	}
}
