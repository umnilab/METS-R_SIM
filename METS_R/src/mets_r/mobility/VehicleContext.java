package mets_r.mobility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.*;
import repast.simphony.context.DefaultContext;
import repast.simphony.space.gis.Geography;

public class VehicleContext extends DefaultContext<Vehicle> {
	private static final Comparator<ElectricTaxi> TAXI_ID_ORDER =
			Comparator.comparingInt(ElectricTaxi::getID);

	// For taxi/ride-hailing operation
	private Map<Integer, TreeSet<ElectricTaxi>> availableTaxiMap;
	private Map<Integer, TreeSet<ElectricTaxi>> relocationTaxiMap;
	
	// For data collection
	private Map<Integer, ElectricTaxi> taxiMap; 
	private Map<Integer, ElectricBus> busMap;
	
	// For tracking private vehicle trips, note the key is not the agentID but the one used in the TravelDemand JSON files
	private Map<Integer, ElectricVehicle> privateEVMap;
	private Map<Integer, Vehicle> privateGVMap;
	private Map<Integer, Integer> privateVIDMap; // key is the agentID, value is the ID in the TravelDemand JSON
	
	ConcurrentLinkedQueue<Vehicle> allTransferringVehicles = new ConcurrentLinkedQueue<Vehicle>();
	ConcurrentLinkedQueue<Vehicle> allArrivingVehicles = new ConcurrentLinkedQueue<Vehicle>();

	public VehicleContext() {
		super("VehicleContext");
		ContextCreator.logger.info("VehicleContext creation");
		Geography<Zone> zoneGeography;
		zoneGeography = ContextCreator.getZoneGeography();

		initMaps();
		
		createTaxiContextFromZone(zoneGeography, GlobalVariables.NUM_OF_EV);
		createBusContextFromZone(zoneGeography, GlobalVariables.NUM_OF_BUS);
	}
	
	/**
	 * Constructor for load mode: creates empty maps without auto-generating vehicles.
	 * @param emptyMode ignored, just used to distinguish from the default constructor
	 */
	public VehicleContext(boolean emptyMode) {
		super("VehicleContext");
		ContextCreator.logger.info("VehicleContext creation (empty for load)");
		initMaps();
	}
	
	private void initMaps() {
		this.availableTaxiMap = new HashMap<Integer, TreeSet<ElectricTaxi>>();
		this.relocationTaxiMap = new HashMap<Integer, TreeSet<ElectricTaxi>>();
		this.taxiMap = new HashMap<Integer, ElectricTaxi>();
		this.busMap = new HashMap<Integer, ElectricBus>();
		this.privateEVMap = new HashMap<Integer, ElectricVehicle>();
		this.privateGVMap = new HashMap<Integer, Vehicle>();
		this.privateVIDMap = new HashMap<Integer, Integer>();
		
		// Ensure zone-based maps are initialized for all zones
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (!this.availableTaxiMap.containsKey(z.getID())) {
				this.availableTaxiMap.put(z.getID(), new TreeSet<ElectricTaxi>(TAXI_ID_ORDER));
			}
			if (!this.relocationTaxiMap.containsKey(z.getID())) {
				this.relocationTaxiMap.put(z.getID(), new TreeSet<ElectricTaxi>(TAXI_ID_ORDER));
			}
		}
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
			TreeSet<ElectricTaxi> tmpQueue = new TreeSet<ElectricTaxi>(TAXI_ID_ORDER);
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
			this.relocationTaxiMap.put(z.getID(), new TreeSet<ElectricTaxi>(TAXI_ID_ORDER));
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

	public synchronized List<ElectricTaxi> getAvailableTaxisSorted(int zoneID) {
		TreeSet<ElectricTaxi> set = this.availableTaxiMap.get(zoneID);
		if (set == null || set.isEmpty()) return new ArrayList<>();
		
		List<ElectricTaxi> sortedList = new ArrayList<>(set);
		// Explicitly sort to guarantee ID order, overriding any TreeSet corruption
		sortedList.sort(Comparator.comparingInt(ElectricTaxi::getID));
		
		return sortedList;
	}
	
	public synchronized void addAvailableTaxi(ElectricTaxi v, int z) {
		this.availableTaxiMap.get(z).add(v);
	}
	
	public synchronized void removeAvailableTaxi(ElectricTaxi v, int z) {
		this.availableTaxiMap.get(z).remove(v);
	}
	
	public synchronized void updateAvailableTaxi(ElectricTaxi v, int oldZone, int newZone) {
		if (this.availableTaxiMap.containsKey(oldZone)) {
			this.availableTaxiMap.get(oldZone).remove(v);
		}
		v.setCurrentZone(newZone);
		this.availableTaxiMap.get(newZone).add(v);
	}
	
	public synchronized void updateRelocationTaxi(ElectricTaxi v, int newZone) {
		int oldZone = v.getCurrentZone();
		
		if (relocationTaxiMap.containsKey(oldZone)) {
	        TreeSet<ElectricTaxi> set = relocationTaxiMap.get(oldZone);
	        set.remove(v);
	    }

	    v.setCurrentZone(newZone);

	    relocationTaxiMap.get(newZone).add(v);
	}
	
	public synchronized void removeRelocationTaxi(ElectricTaxi v){
		int zone = v.getCurrentZone();
	    TreeSet<ElectricTaxi> set = this.relocationTaxiMap.get(zone);
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
	
	public synchronized List<ElectricTaxi> getRelocationTaxiSorted(int z) {
	    TreeSet<ElectricTaxi> set = this.relocationTaxiMap.get(z);
	    if (set == null) return new ArrayList<>();
	    List<ElectricTaxi> sortedList = new ArrayList<>(set);
		// Explicitly sort to guarantee ID order, overriding any TreeSet corruption
		sortedList.sort(Comparator.comparingInt(ElectricTaxi::getID));
	    return sortedList;
	}
	
	public synchronized int getNumOfRelocationTaxi(int z) {
		TreeSet<ElectricTaxi> set = this.relocationTaxiMap.get(z);
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
		if(!this.privateVIDMap.containsValue(vid)) {
			this.privateEVMap.put(vid, ev);
			this.privateVIDMap.put(ev.getID(), vid);
		}
		else {
			ContextCreator.logger.warn("Vehicle with vid " + vid + " already exists");
		}
	}
	
	public void registerPrivateGV(int vid, Vehicle gv) {
		if(!this.privateVIDMap.containsValue(vid)) {
			this.privateGVMap.put(vid, gv);
			this.privateVIDMap.put(gv.getID(), vid);
		}
		else {
			ContextCreator.logger.warn("Vehicle with vid " + vid + " already exists");
		}
	}
	
	public int getPrivateVID(int agentID) {
		if(privateVIDMap.containsKey(agentID)) {
			return privateVIDMap.get(agentID);
		}
		return -1;
	}
	
	/* Methods for save/load support */
	public void clearMaps() {
		this.taxiMap.clear();
		this.busMap.clear();
		this.privateEVMap.clear();
		this.privateGVMap.clear();
		this.privateVIDMap.clear();
		for (TreeSet<ElectricTaxi> q : this.availableTaxiMap.values()) {
			q.clear();
		}
		for (TreeSet<ElectricTaxi> s : this.relocationTaxiMap.values()) {
			s.clear();
		}
	}
	
	public void registerTaxi(ElectricTaxi v) {
		this.taxiMap.put(v.getID(), v);
	}
	
	public void registerBus(ElectricBus v) {
		this.busMap.put(v.getID(), v);
	}
	
	public void executeGlobalTransfers() {
		List<Vehicle> sortedTransfers = new ArrayList<Vehicle>(this.allTransferringVehicles);
	    sortedTransfers.sort(Comparator.comparingInt(Vehicle::getID));
	    
	    for (Vehicle currentVehicle: sortedTransfers) {
	    	Road r = currentVehicle.getRoad();
            if (!currentVehicle.changeRoad()) { 
                currentVehicle.setSpeed(0.0f);
                currentVehicle.setAccRate(0.0f);
                currentVehicle.setMovingFlag(false);
            } else { 
                // Successfully entered the next road
                r.recordEnergyConsumption(currentVehicle); 
                r.recordTravelTime(currentVehicle);
                currentVehicle.setAccumulatedDistance(currentVehicle.getAccummulatedDistance() + currentVehicle.getDistanceToNextJunction());
                currentVehicle.setMovingFlag(true);
            }
	    }
	    
	    List<Vehicle> sortedArrivals = new ArrayList<>(this.allArrivingVehicles);
	    sortedArrivals.sort(Comparator.comparingInt(Vehicle::getID));
	    
	    for (Vehicle currentVehicle: sortedArrivals) {
	    	currentVehicle.reachDest();
	    }
	    
	    this.allTransferringVehicles.clear();
	    this.allTransferringVehicles.clear();
	}
	
	public void addArrivalVehicles(Vehicle v) {
		this.allArrivingVehicles.add(v);
	}
	
	public void addTransferringVehicles(Vehicle v) {
		this.allTransferringVehicles.add(v);
	}
}