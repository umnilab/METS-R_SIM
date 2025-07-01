package mets_r.mobility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.util.ConcurrentHashSet;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.*;
import repast.simphony.context.DefaultContext;
import repast.simphony.space.gis.Geography;

public class VehicleContext extends DefaultContext<Vehicle> {
	// For taxi/ride-hailing operation
	private Map<Integer, ConcurrentLinkedQueue<ElectricTaxi>> availableTaxiMap;
	private Map<Integer, ConcurrentHashSet<ElectricTaxi>> relocationTaxiMap;
	
	// For data collection
	private Map<Integer, ElectricTaxi> taxiMap; 
	private Map<Integer, ElectricBus> busMap;
	
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
		this.relocationTaxiMap = new HashMap<Integer, ConcurrentHashSet<ElectricTaxi>>();
		
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
		// Generate the vehicles in other zones
		int num_total = vehicle_num;
		// Distribute by the capacity of the Zone
		int park_total = 0;
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			park_total += z.getCapacity();
		}
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			ConcurrentLinkedQueue<ElectricTaxi> tmpQueue = new ConcurrentLinkedQueue<ElectricTaxi>();
			if(z.getCapacity()>0) {
				int vehicle_num_to_generate = (int) Math.ceil(num_total * z.getCapacity()/(park_total + 0.001));
				vehicle_num_to_generate = vehicle_num_to_generate <= z.getCapacity()? vehicle_num_to_generate: z.getCapacity();
				num_total -= vehicle_num_to_generate;
				for (int i = 0; i < vehicle_num_to_generate; i++) {
					ElectricTaxi v = new ElectricTaxi();																	
					this.add(v);
					// initialize a plan to get the origin info recorded
					v.initializePlan(z.getID(), z.getClosestRoad(false), (int) ContextCreator.getCurrentTick());
					v.setCurrentZone(z.getID());
					total_vehicles += 1;
					this.taxiMap.put(v.getID(), v);
					tmpQueue.add(v);
				}
				z.addParkingVehicleStock(vehicle_num_to_generate);
			}
			this.availableTaxiMap.put(z.getID(), tmpQueue);
			this.relocationTaxiMap.put(z.getID(), new ConcurrentHashSet<ElectricTaxi>());
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
						v.setCurrentZone(z.getID());
						total_vehicles += 1;
						this.taxiMap.put(v.getID(), v);
						this.availableTaxiMap.get(z.getID()).add(v);
					}
					z.addParkingVehicleStock(vehicle_num_to_generate);
					
					if(num_total <= 0) break;
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
		int total_buses = 0;
		int num_per_hub = (int) (Math.floor(bus_num / (ContextCreator.getZoneContext().HUB_INDEXES.size()>0? ContextCreator.getZoneContext().HUB_INDEXES.size():1)) + 1);
		int to_be_generated = bus_num;
		try {
			for (int startZone :  ContextCreator.getZoneContext().HUB_INDEXES) {
				ArrayList<Integer> route = new ArrayList<Integer>(Arrays.asList(startZone));
				// GeometryFactory fac = new GeometryFactory();
				// Decide the next departure time 
				ArrayList<Integer> departure_time = new ArrayList<Integer>(Arrays.asList((int) (ContextCreator.getCurrentTick() + 60/GlobalVariables.SIMULATION_STEP_SIZE)));
				// Generate vehicle_num buses for the corresponding route
				Zone z = ContextCreator.getZoneContext().get(route.get(0));
				
				for (int j = 0; j < Math.min(num_per_hub, to_be_generated); j++) {
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
					total_buses += 1;
				}
				
				to_be_generated -= Math.min(num_per_hub, to_be_generated);
			}
			
			ContextCreator.logger.info("Total EV buses generated " + total_buses);
		} catch (Exception e) {
			ContextCreator.logger.error(e.toString());
		}
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

	// Return the list of vehicles for certain zone
	public Queue<ElectricTaxi> getAvailableTaxis(int zoneID) {
		return this.availableTaxiMap.get(zoneID);
	}
	
	public void addAvailableTaxi(ElectricTaxi v, int z) {
		this.availableTaxiMap.get(z).add(v);
	}
	
	public void removeAvailableTaxi(ElectricTaxi v, int z) {
		this.availableTaxiMap.get(z).remove(v);
	}
	
	public void updateRelocationTaxi(ElectricTaxi v,  int newZone) {
		int oldZone = v.getCurrentZone();
		
		if (relocationTaxiMap.containsKey(oldZone)) {
	        ConcurrentHashSet<ElectricTaxi> set = relocationTaxiMap.get(oldZone);
	        set.remove(v);
	    }

	    // Update vehicle's current zone
	    v.setCurrentZone(newZone);

	    // Add to new zone
	    relocationTaxiMap.get(newZone).add(v);
	}
	
	public void removeRelocationTaxi(ElectricTaxi v){
		int zone = v.getCurrentZone();
	    ConcurrentHashSet<ElectricTaxi> set = this.relocationTaxiMap.get(zone);
	    if (set != null) {
	        set.remove(v);
	    }
	}
	
//	public List<ElectricTaxi> getRelocationTaxi(int z){
//		List<ElectricTaxi> result = new ArrayList<ElectricTaxi>();
//		for (ElectricTaxi v : this.relocationTaxiList) {
//              if (v.getCurrentZone() == z) {
//                  result.add(v);
//              }
//          }
//		return result;
//	}
	
	public List<ElectricTaxi> getRelocationTaxi(int z) {
	    ConcurrentHashSet<ElectricTaxi> set = this.relocationTaxiMap.get(z);
	    return (set != null) ? new ArrayList<>(set) : new ArrayList<>();
	}
	
	public int getNumOfRelocationTaxi(int z) {
		ConcurrentHashSet<ElectricTaxi> set = this.relocationTaxiMap.get(z);
	    return (set != null) ? set.size() : 0;
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