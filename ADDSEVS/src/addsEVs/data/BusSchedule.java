package addsEVs.data;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.Zone;
import addsEVs.vehiclecontext.Bus;
import repast.simphony.essentials.RepastEssentials;

class The_Comparator implements Comparator<OneBusSchedule> {
    public int compare(OneBusSchedule s1, OneBusSchedule s2)
    {
        Integer d1 = s1.departureTime;
        Integer d2 = s2.departureTime;
        return d1.compareTo(d2);
    }
}

public class BusSchedule{
	// For storing the schedule
	public ArrayList<Integer> routeName;
	public ArrayList<ArrayList<Integer>> busRoute;
	public ArrayList<Integer> busNum;
	public ArrayList<Integer> busGap;
	
	// For updating the schedule
	public HashMap<Integer, PriorityQueue<OneBusSchedule>> pendingSchedules;

	public int currentHour = 0;
	
	public BusSchedule(){
	    routeName = new ArrayList<Integer>();
		busRoute = new ArrayList<ArrayList<Integer>>();
		busNum = new ArrayList<Integer>();
		busGap = new ArrayList<Integer>();
		if(! GlobalVariables.BUS_PLANNING) { // Using offline bus schedules
			readEventFile();
		}
	}
	// Read and parse the JSON files
	@SuppressWarnings("unchecked")
	public void readEventFile(){
		JSONParser parser = new JSONParser();
		
		try{
			Map<Integer, Integer> locationIDMap = new HashMap<Integer, Integer>();
			BufferedReader br = new BufferedReader(new FileReader(GlobalVariables.ZONE_CSV));
			int integerID=0;
			String line;
			while((line=br.readLine()) != null){
				String[] result=line.split(",");
				locationIDMap.put(Integer.parseInt(result[1]), integerID);
				integerID+=1;
			}
			br.close();
			Object obj = parser.parse(new FileReader(GlobalVariables.BUS_SCHEDULE));
			JSONObject jsonObject = (JSONObject) obj;
			for (Long name: (ArrayList<Long>) jsonObject.get("names")){
				routeName.add(name.intValue());
			}
			for(ArrayList<Long> route: (ArrayList<ArrayList<Long>>) jsonObject.get("routes")){
				ArrayList<Integer> oneRoute = new ArrayList<Integer>(); 
				for(Long station: route){
					oneRoute.add(locationIDMap.get(station.intValue()));
				}
				busRoute.add(oneRoute);
			}
			for(Long num: (ArrayList<Long>) jsonObject.get("nums")){
				busNum.add(num.intValue());
			}
			for(Long gap: (ArrayList<Long>) jsonObject.get("gaps")){
				busGap.add(gap.intValue());
			}
		}
		catch(FileNotFoundException e){
			e.printStackTrace();
		}
		catch(Exception e){
			e.printStackTrace();
		}
		this.processSchedule();
		
		ContextCreator.logger.info("Loaded bus schedule from offline files.");
	}
	// For changing bus route schedule
	public void updateEvent(int newhour, ArrayList<Integer> newRouteName, ArrayList<ArrayList<Integer>> newRoutes, ArrayList<Integer> newBusNum, ArrayList<Integer> newBusGap){
		currentHour = newhour;
		routeName = newRouteName;
		busRoute= newRoutes;
		busNum = newBusNum;
		busGap = newBusGap;
		
		for(Zone z: ContextCreator.getZoneGeography().getAllObjects()) {
			z.busReachableZone.clear(); //clear the bus info
		}
		
		for(ArrayList<Integer> route: this.busRoute) {
			int i = 0;
			if(busNum.get(i)>0) {
				for(int zoneID: route){
					Zone zone = ContextCreator.getCityContext().findZoneWithDestID(zoneID);
					if(zone.getZoneClass() == 0) { // normal zone, the destination should be hub
						for (int destinationID: route) {
						    if(GlobalVariables.HUB_INDEXES.contains(destinationID)){
						    	zone.setBusInfo(destinationID, this.busGap.get(i));
						    }
						}
					}
					else if(zone.getZoneClass() == 1) { // hub, the destination should be other zones (can be another hub)
						for (int destinationID: route) {
							if(zone.getIntegerID() != destinationID){
						    	zone.setBusInfo(destinationID, this.busGap.get(i));
						    }
							
						}
					}
				}
			}
			i += 1;
		}
		
		ContextCreator.logger.info("Received bus schedules");
		ContextCreator.getCityContext().refreshTravelTime(); // Update bus travel time
		if (GlobalVariables.COLLABORATIVE_EV) {
			for(Zone z: ContextCreator.getZoneGeography().getAllObjects()) {
				z.updateCombinedTravelEstimation();
			}
		}
		ContextCreator.logger.info("Travel time refreshed");
		this.processSchedule();
	}
	
	public void processSchedule() {
		pendingSchedules = new HashMap<Integer, PriorityQueue<OneBusSchedule>>();
		int n = this.busRoute.size();
		for(int i=0; i<n; i++) {
			int startZone = this.busRoute.get(i).get(0);
			if(!pendingSchedules.containsKey(startZone)) {
				pendingSchedules.put(startZone, new PriorityQueue<OneBusSchedule>(new The_Comparator()));
			}
			for(int j=0; j<this.busNum.get(i); j++) {
				Integer depTime = (int) (currentHour*3600/GlobalVariables.SIMULATION_STEP_SIZE+j*this.busGap.get(i)/GlobalVariables.SIMULATION_STEP_SIZE);
				OneBusSchedule obs = new OneBusSchedule(this.routeName.get(i), this.busRoute.get(i),depTime);
				pendingSchedules.get(startZone).add(obs);
			}
		}
	}
	
	public void popSchedule(int startZone, Bus b) {
		int current_tick = (int) RepastEssentials.GetTickCount();
		if(this.pendingSchedules!=null && this.pendingSchedules.containsKey(startZone)) {
			// Update bus schedule
			if(this.pendingSchedules.get(startZone).size()>0) {
				if(this.pendingSchedules.get(startZone).peek().departureTime<(current_tick+3600/GlobalVariables.SIMULATION_STEP_SIZE)) {
					OneBusSchedule obs = this.pendingSchedules.get(startZone).poll();
					b.updateSchedule(obs.routeID, 
							obs.busRoute,
							obs.departureTime);
					return;
				}
			}
		}
		// Set a null schedule
		b.updateSchedule(-1, 
				new ArrayList<Integer> (),
				0);
	}
}