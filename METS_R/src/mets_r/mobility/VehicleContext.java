package mets_r.mobility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.*;
import repast.simphony.context.DefaultContext;
import repast.simphony.space.gis.Geography;

public class VehicleContext extends DefaultContext<Vehicle> {
	// For taxi/ride-hailing operation
	private HashMap<Integer, ConcurrentLinkedQueue<ElectricTaxi>> availableTaxiMap;
	private ConcurrentHashMap<ElectricTaxi, Integer> relocationTaxiMap; 
	
	// For data collection
	private HashMap<Integer, ElectricTaxi> taxiMap; 
	private HashMap<Integer, ElectricBus> busMap;
	
	// For tracking private vehicle trips, note the key is not the agentID but the one used in the TravelDemand JSON files
	private HashMap<Integer, ElectricVehicle> privateEVMap;
	private HashMap<Integer, Vehicle> privateGVMap;
	
	private HashMap<Integer, Integer> privateVIDMap; // key is the agentID, value is the ID in the TravelDemand JSON

	public VehicleContext() {
		super("VehicleContext");
		ContextCreator.logger.info("VehicleContext creation");
		Geography<Zone> zoneGeography;
		zoneGeography = ContextCreator.getZoneGeography();

		this.availableTaxiMap = new HashMap<Integer, ConcurrentLinkedQueue<ElectricTaxi>>();
		this.relocationTaxiMap = new ConcurrentHashMap<ElectricTaxi, Integer>();
		this.taxiMap = new HashMap<Integer, ElectricTaxi>();
		this.busMap = new HashMap<Integer, ElectricBus>();
		
		this.privateEVMap = new HashMap<Integer, ElectricVehicle>();
		this.privateGVMap = new HashMap<Integer, Vehicle>();
		
		this.privateVIDMap = new HashMap<Integer, Integer>();
		
		createTaxiContextFromZone(zoneGeography, GlobalVariables.NUM_OF_EV);
		createBusContextFromZone(zoneGeography, GlobalVariables.NUM_OF_BUS);
	}

	public void createTaxiContextFromZone(Geography<Zone> zoneGeography, int vehicle_num) {
		int total_vehicles = 0;

		// Count the total demand
		double demand_total = 0;
		for (Zone z: zoneGeography.getAllObjects()) {
			demand_total += (ContextCreator.demand_per_zone.get(z.getID()) + 0.001);
		}
		// Generate the vehicles in other zones
		int num_total = vehicle_num;
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			ConcurrentLinkedQueue<ElectricTaxi> tmpQueue = new ConcurrentLinkedQueue<ElectricTaxi>();
			if(z.getCapacity()>0) {
				int vehicle_num_to_generate = (int) Math
						.ceil(vehicle_num * (ContextCreator.demand_per_zone.get(z.getID()) + 0.001) / demand_total);
				vehicle_num_to_generate = vehicle_num_to_generate <= z.getCapacity()? vehicle_num_to_generate: z.getCapacity();
				vehicle_num_to_generate = num_total <= vehicle_num_to_generate ? num_total : vehicle_num_to_generate;
				num_total -= vehicle_num_to_generate;
				for (int i = 0; i < vehicle_num_to_generate; i++) {
					// GeometryFactory fac = new GeometryFactory();
					// Sample 1%% of vehicles for collecting trajectory data\
					ElectricTaxi v = new ElectricTaxi();																	
					this.add(v);
					ContextCreator.logger.debug("Vehicle:" + i + " generated");
					v.initializePlan(z.getID(), z.getClosestRoad(false), (int) ContextCreator.getCurrentTick());
					total_vehicles += 1;
					this.taxiMap.put(v.getID(), v);
					tmpQueue.add(v);
				}
				z.addParkingVehicleStock(vehicle_num_to_generate);
			}
			this.availableTaxiMap.put(z.getID(), tmpQueue);
		}
		
		if(num_total > 0) { //assign the rest vehicle to zones with additional space
			for (Zone z : ContextCreator.getZoneContext().getAll()) {
				if(z.getCapacity()>0) {
					int vehicle_num_to_generate = num_total <= z.getCapacity()? num_total: z.getCapacity();
					num_total -= vehicle_num_to_generate;
					for (int i = 0; i < vehicle_num_to_generate; i++) {
						// GeometryFactory fac = new GeometryFactory();
						ElectricTaxi v = new ElectricTaxi();
						this.add(v);
						v.initializePlan(z.getID(), z.getClosestRoad(false), (int) ContextCreator.getCurrentTick());
						total_vehicles += 1;
						this.taxiMap.put(v.getID(), v);
						this.availableTaxiMap.get(z.getID()).add(v);
					}
					z.addParkingVehicleStock(vehicle_num_to_generate);
				}
			}
		}

	    if(num_total > 0) {
	    	ContextCreator.logger.info("There are still vehicles to generate, but no space for them, to generate number " + num_total);
	    }

		ContextCreator.logger.info("Total EV taxis generated " + total_vehicles);
	}

	// Initialize buses for each route, if station is assigned to specified
	// location, replace zoneGeography to stationGeography
	public void createBusContextFromZone(Geography<Zone> zoneGeography, int bus_num) {
		// Go through all routes, generate vehicle_num[i] buses in the beginning of the
		// routes
		int num_per_hub = (int) Math.ceil(bus_num / (ContextCreator.getZoneContext().HUB_INDEXES.size()>0? ContextCreator.getZoneContext().HUB_INDEXES.size():1));
		try {
			for (int startZone :  ContextCreator.getZoneContext().HUB_INDEXES) {
				ArrayList<Integer> route = new ArrayList<Integer>(Arrays.asList(startZone));
				// GeometryFactory fac = new GeometryFactory();
				// Decide the next departure time 
				ArrayList<Integer> departure_time = new ArrayList<Integer>(Arrays.asList((int) (ContextCreator.getCurrentTick() + 60/GlobalVariables.SIMULATION_STEP_SIZE)));
				// Generate vehicle_num buses for the corresponding route
				Zone z = ContextCreator.getZoneContext().get(route.get(0));
				for (int j = 0; j < num_per_hub; j++) {
					ElectricBus b;
					b = new ElectricBus(-1, route,  departure_time);
					b.addPlan(z.getID(), z.getClosestRoad(false), ContextCreator.getCurrentTick());
					this.add(b);
					b.setCurrentCoord(z.getCoord());
					b.addPlan(z.getID(), z.getClosestRoad(false), ContextCreator.getCurrentTick());
					b.setNextPlan();
					b.addPlan(z.getID(), z.getClosestRoad(false), ContextCreator.getCurrentTick()); // Initialize the first plan
					b.setNextPlan();
					b.departure();
					this.busMap.put(b.getID(), b);
				}
			}
		} catch (Exception e) {
			ContextCreator.logger.error(e.toString());
		}
	}

	// Return the list of vehicles for certain zone
	public ConcurrentLinkedQueue<ElectricTaxi> getVehiclesByZone(int integerID) {
		return this.availableTaxiMap.get(integerID);
	}

	public Collection<ElectricTaxi> getTaxis() {
		return this.taxiMap.values();
	}

	public Collection<ElectricBus> getBuses() {
		return this.busMap.values();
	}
	
	public ElectricTaxi getTaxi(int vid) {
		if(this.taxiMap.containsKey(vid)) return this.taxiMap.get(vid);
		return null;
	}
	
	public ElectricBus getBus(int vid) {
		if(this.busMap.containsKey(vid)) return this.busMap.get(vid);
		return null;
	}
	
	public Vehicle getPublicVehicle(int vid) {
		Vehicle vehicle = (Vehicle) this.getTaxi(vid);
		if(vehicle == null) {
			vehicle = (Vehicle) this.getBus(vid);
		}
		return vehicle;
	}
	
	public Vehicle getPrivateVehicle(int vid){
		Vehicle	vehicle = (Vehicle) this.getPrivateEV(vid);
		if(vehicle == null) {
			vehicle = (Vehicle) this.getPrivateGV(vid);
		}
		return vehicle;
	}
	
	public List<Integer> getPrivateVehicleIDList(){
		List<Integer> vehicleIDList = new ArrayList<Integer>(this.privateEVMap.keySet());
	    vehicleIDList.addAll(this.privateGVMap.keySet());
	    return vehicleIDList;
	}
	
	public List<Integer> getTaxiIDList(){
		List<Integer> vehicleIDList = new ArrayList<Integer>(this.taxiMap.keySet());
	    return vehicleIDList;
	}
	
	public List<Integer> getBusIDList(){
		List<Integer> vehicleIDList = new ArrayList<Integer>(this.busMap.keySet());
	    return vehicleIDList;
	}
	
	public List<Integer> getPublicVehicleIDList(){
		List<Integer> vehicleIDList = new ArrayList<Integer>(this.taxiMap.keySet());
	    vehicleIDList.addAll(this.busMap.keySet());
	    return vehicleIDList;
	}

	// Add vehicle to zones
	public void addAvailableTaxi(ElectricTaxi v, int integerID) {
		this.availableTaxiMap.get(integerID).add(v);
	}
	
	public void addRelocationTaxi(ElectricTaxi v, int z) {
		this.relocationTaxiMap.put(v, z);
	}
	
	public void removeRelocationTaxi(ElectricTaxi v){
		this.relocationTaxiMap.remove(v);
	}
	
	public List<ElectricTaxi> getRelocationTaxi(int z){
		List<ElectricTaxi> result = new ArrayList<ElectricTaxi>();
		if(this.relocationTaxiMap.containsValue(z)) {
			for (Entry<ElectricTaxi, Integer> entry : this.relocationTaxiMap.entrySet()) {
	              if (entry.getValue() == z) {
	                  result.add(entry.getKey());
	              }
	          }
		}
		return result;
	}
	
	public ElectricVehicle getPrivateEV(int vid) {
		if(this.privateEVMap.containsKey(vid)) {
			return this.privateEVMap.get(vid);
		}
		else {
			return null;
		}
	}
	
	public Vehicle getPrivateGV(int vid) {
		if(this.privateGVMap.containsKey(vid)) {
			return this.privateGVMap.get(vid);
		}
		else {
			return null;
		}
	}
	
	public void registerPrivateEV(int vid, ElectricVehicle ev) {
		if(!this.privateEVMap.containsKey(vid)) {
			this.privateEVMap.put(vid, ev);
			this.privateVIDMap.put(ev.getID(), vid);
		}
	}
	
	public void registerPrivateGV(int vid, Vehicle gv) {
		if(!this.privateGVMap.containsKey(vid)) {
			this.privateGVMap.put(vid, gv);
			this.privateVIDMap.put(gv.getID(), vid);
		}
	}
	
	public int getPrivateVID(int agentID) {
		if(privateVIDMap.containsKey(agentID)) {
			return privateVIDMap.get(agentID);
		}
		return -1;
	}

}