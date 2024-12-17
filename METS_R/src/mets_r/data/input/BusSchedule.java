package mets_r.data.input;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
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
	// For storing the schedule
	private ArrayList<Integer> routeName;
	private ArrayList<ArrayList<Integer>> busRoute;
	private ArrayList<Integer> busNum;
	private ArrayList<Integer> busGap; // in minute
	private Map<Long, Integer> locationIDMap;

	// For updating the schedule
	private ConcurrentHashMap<Integer, PriorityQueue<OneBusSchedule>> pendingSchedules;

	private int currentHour = -1;

	public BusSchedule() {
		routeName = new ArrayList<Integer>();
		busRoute = new ArrayList<ArrayList<Integer>>();
		busNum = new ArrayList<Integer>();
		busGap = new ArrayList<Integer>();
		readEventFile();
	}

	// Read and parse the JSON files
	@SuppressWarnings("unchecked")
	public void readEventFile() {
		JSONParser parser = new JSONParser();

		try {
			locationIDMap = new HashMap<Long, Integer>();
			BufferedReader br = new BufferedReader(new FileReader(GlobalVariables.ZONE_CSV));
			br.readLine(); // Skip the first row
			int integerID = 0;
			String line;
			while ((line = br.readLine()) != null) {
				String[] result = line.split(",");
				locationIDMap.put(Long.parseLong(result[1]), integerID);
				integerID += 1;
			}
			br.close();
			
			Object obj = parser.parse(new FileReader(GlobalVariables.BUS_SCHEDULE));
			JSONObject jsonObject = (JSONObject) obj;
			for (Long name : (ArrayList<Long>) jsonObject.get("names")) {
				routeName.add(name.intValue());
			}
			for (ArrayList<Long> route : (ArrayList<ArrayList<Long>>) jsonObject.get("routes")) {
				ArrayList<Integer> oneRoute = new ArrayList<Integer>();
				for (Long station : route) {
					if(locationIDMap.containsKey(station.longValue())){
						oneRoute.add(locationIDMap.get(station.longValue()));
					}
				}
				busRoute.add(oneRoute);
			}
			for (Double num : (ArrayList<Double>) jsonObject.get("nums")) {
				busNum.add(num.intValue());
			}
			for (Double gap : (ArrayList<Double>) jsonObject.get("gaps")) {
				busGap.add(gap.intValue());
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		this.processSchedule();

		ContextCreator.logger.info("Loaded bus schedule from offline files.");
	}

	// For changing bus route schedule
	public void updateEvent(int newHour, ArrayList<Integer> newRouteName, ArrayList<ArrayList<Integer>> newRoutes,
			ArrayList<Integer> newBusNum, ArrayList<Integer> newBusGap) {
		if (currentHour < newHour) {
			currentHour = newHour;
			routeName = newRouteName;
			busNum = newBusNum;
			busGap = newBusGap;

			for (Zone z : ContextCreator.getZoneContext().getAll()) {
				z.busReachableZone.clear(); // clear the bus info
				z.busGap.clear();
			}
			busRoute.clear();
			
			for (ArrayList<Integer> route : newRoutes) {
				int i = 0;
				ArrayList<Integer> oneRoute = new ArrayList<Integer>();
				if (busNum.get(i) > 0) {
					for (long zoneID : route) {
						if(locationIDMap.containsKey(zoneID)){
							oneRoute.add(locationIDMap.get(zoneID));
							Zone zone = ContextCreator.getZoneContext().get(locationIDMap.get(zoneID));
							if (zone.getZoneType() == 0) { // normal zone, the destination should be hub
								for (long destinationID : route) {
									if(locationIDMap.containsKey(destinationID)) {
										if ( ContextCreator.getZoneContext().HUB_INDEXES.contains(locationIDMap.get(destinationID))) {
											zone.setBusInfo(locationIDMap.get(destinationID), this.busGap.get(i));
										}
									}
								}
							} else if (zone.getZoneType() == 1) { // hub, the destination should be other zones (can be
																	// another hub)
								for (long destinationID : route) {
									if(locationIDMap.containsKey(destinationID)) {
										if (zone.getID() != locationIDMap.get(destinationID)) {
											zone.setBusInfo(locationIDMap.get(destinationID), this.busGap.get(i));
										}
									}	
								}
							}
						}
					}
				}
				busRoute.add(oneRoute);
				i += 1;
			}
			ContextCreator.logger.info(busRoute);
			for (Zone z : ContextCreator.getZoneContext().getAll()) {
				// Deal with the remaining passengers for buses in each zone
				z.reSplitPassengerDueToBusRescheduled();
			}

			this.processSchedule();
		}
	}

	// Translate bus route into bus schedules
	public void processSchedule() {
		pendingSchedules = new ConcurrentHashMap<Integer, PriorityQueue<OneBusSchedule>>();
		int n = this.busRoute.size();
		for (int i = 0; i < n; i++) {
			int startZone = this.busRoute.get(i).get(0);
			if (!pendingSchedules.containsKey(startZone)) {
				pendingSchedules.put(startZone, new PriorityQueue<OneBusSchedule>(new The_Comparator()));
			}
			for (int j = 0; j < this.busNum.get(i); j++) {
				Integer depTime = (int) (currentHour * 3600 / GlobalVariables.SIMULATION_STEP_SIZE
						+ j * this.busGap.get(i) * 60 / GlobalVariables.SIMULATION_STEP_SIZE);
				OneBusSchedule obs = new OneBusSchedule(this.routeName.get(i), this.busRoute.get(i), depTime);
				pendingSchedules.get(startZone).add(obs);
			}
		}
	}

	public void popSchedule(int startZone, ElectricBus b) {
		if (this.pendingSchedules != null && this.pendingSchedules.containsKey(startZone)) {
			// Update bus schedule
			if (this.pendingSchedules.get(startZone).size() > 0) {
				if (this.pendingSchedules.get(startZone).peek().departureTime
						.get(0) < (ContextCreator.getCurrentTick() + 3600 / GlobalVariables.SIMULATION_STEP_SIZE)) {
					OneBusSchedule obs = this.pendingSchedules.get(startZone).poll();
					b.updateSchedule(obs.routeID, obs.busRoute, obs.departureTime);
					return;
				}
			}
		}
		// Otherwise set a null schedule
		b.updateSchedule(-1, new ArrayList<Integer>(), new ArrayList<Integer>());
	}

	public boolean hasSchedule(Integer stop) {
		if (ContextCreator.bus_schedule.pendingSchedules.containsKey(stop)) {
			return ContextCreator.bus_schedule.pendingSchedules.get(stop).size() > 0;
		}
		return false;
	}
	

	public ArrayList<ArrayList<Integer>> getBusSchedule() {
		return busRoute;
	}

	public ArrayList<Integer> getBusNum() {
		return busNum;
	}

	public ArrayList<Integer> getBusGap() {
		return busGap;
	}
}