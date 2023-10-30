package mets_r.mobility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.vividsolutions.jts.geom.Coordinate;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.*;
import repast.simphony.context.DefaultContext;
import repast.simphony.space.gis.Geography;

public class VehicleContext extends DefaultContext<Vehicle> {
	// For operation
	private HashMap<Integer, ConcurrentLinkedQueue<ElectricTaxi>> availableTaxiMap;
	private ConcurrentHashMap<ElectricTaxi, Integer> relocationTaxiMap; 
	
	// For data collection
	private ArrayList<ElectricTaxi> taxiList; 
	private ArrayList<ElectricBus> busList;

	public VehicleContext() {
		super("VehicleContext");
		ContextCreator.logger.info("VehicleContext creation");
		Geography<Zone> zoneGeography;
		zoneGeography = ContextCreator.getZoneGeography();

		this.availableTaxiMap = new HashMap<Integer, ConcurrentLinkedQueue<ElectricTaxi>>();
		this.relocationTaxiMap = new ConcurrentHashMap<ElectricTaxi, Integer>();
		this.taxiList = new ArrayList<ElectricTaxi>();
		this.busList = new ArrayList<ElectricBus>();
		createVehicleContextFromZone(zoneGeography, GlobalVariables.NUM_OF_EV);
		ContextCreator.logger.info("EV generated!");
		createBusContextFromZone(zoneGeography, GlobalVariables.NUM_OF_BUS);
		ContextCreator.logger.info("BUS generated!");
	}

	public void createVehicleContextFromZone(Geography<Zone> zoneGeography, int vehicle_num) {
		int total_vehicles = 0;

		// Generating vehicle according to demand distribution
		double demand_total = 0;
		for (Zone z: zoneGeography.getAllObjects()) {
			if(z.getCapacity()>0) {
				demand_total += ContextCreator.demand_per_zone.get(z.getIntegerID());
			}
		}
		// Generate the vehicles in other zones
		int num_total = vehicle_num;
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			ConcurrentLinkedQueue<ElectricTaxi> tmpQueue = new ConcurrentLinkedQueue<ElectricTaxi>();
			if(z.getCapacity()>0) {
				Coordinate coord = zoneGeography.getGeometry(z).getCoordinate();
				
				int vehicle_num_to_generate = (int) Math
						.ceil(vehicle_num * ContextCreator.demand_per_zone.get(z.getIntegerID()) / demand_total);
				vehicle_num_to_generate = vehicle_num_to_generate <= z.getCapacity()? vehicle_num_to_generate: z.getCapacity();
				vehicle_num_to_generate = num_total <= vehicle_num_to_generate ? num_total : vehicle_num_to_generate;
				num_total -= vehicle_num_to_generate;
				for (int i = 0; i < vehicle_num_to_generate; i++) {
					// GeometryFactory fac = new GeometryFactory();
					// Sample 1%% of vehicles for collecting trajectory data\
					ElectricTaxi v;
					if(vehicle_num>10000) v = new ElectricTaxi(GlobalVariables.RandomGenerator.nextDouble()<0.001);
					else v = new ElectricTaxi(GlobalVariables.RandomGenerator.nextDouble()<0.01);
					v.addPlan(z.getIntegerID(), z.getCoord(), (int) ContextCreator.getCurrentTick()); // Initialize the
																										// first plan
					this.add(v);
					ContextCreator.logger.debug("Vehicle:" + i + " generated");
					v.setCurrentCoord(coord);
					v.addPlan(z.getIntegerID(), z.getCoord(), (int) ContextCreator.getCurrentTick());
					v.setNextPlan();
					v.addPlan(z.getIntegerID(), z.getCoord(), (int) ContextCreator.getCurrentTick());
					v.setNextPlan();
					total_vehicles += 1;
					this.taxiList.add(v);
					tmpQueue.add(v);
				}
				z.addParkingVehicleStock(vehicle_num_to_generate);
			}
			this.availableTaxiMap.put(z.getIntegerID(), tmpQueue);
		}
		
		if(num_total > 0) { //assign the rest vehicle to zones with additional space
			for (Zone z : ContextCreator.getZoneContext().getAll()) {
				if(z.getCapacity()>0) {
					Coordinate coord = zoneGeography.getGeometry(z).getCoordinate();
					int vehicle_num_to_generate = num_total <= z.getCapacity()? num_total: z.getCapacity();
					num_total -= vehicle_num_to_generate;
					for (int i = 0; i < vehicle_num_to_generate; i++) {
						// GeometryFactory fac = new GeometryFactory();
						ElectricTaxi v;
						if(vehicle_num>10000) v = new ElectricTaxi(GlobalVariables.RandomGenerator.nextDouble()<0.001);
						else v = new ElectricTaxi(GlobalVariables.RandomGenerator.nextDouble()<0.01);
						v.addPlan(z.getIntegerID(), z.getCoord(), (int) ContextCreator.getCurrentTick()); // Initialize the
																											// first plan
						this.add(v);
						ContextCreator.logger.debug("Vehicle:" + i + " generated");
						v.setCurrentCoord(coord);
						v.addPlan(z.getIntegerID(), z.getCoord(), (int) ContextCreator.getCurrentTick());
						v.setNextPlan();
						v.addPlan(z.getIntegerID(), z.getCoord(), (int) ContextCreator.getCurrentTick());
						v.setNextPlan();
						total_vehicles += 1;
						this.taxiList.add(v);
						this.availableTaxiMap.get(z.getIntegerID()).add(v);
					}
					z.addParkingVehicleStock(vehicle_num_to_generate);
				}
			}
		}

	    if(num_total > 0) {
	    	ContextCreator.logger.info("There are still vehicles to generate, but no space for them, to generate number " + num_total);
	    }

		ContextCreator.logger.info("Total EV vehicles generated " + total_vehicles);
	}

	// Initialize buses for each route, if station is assigned to specified
	// location, replace zoneGeography to stationGeography
	public void createBusContextFromZone(Geography<Zone> zoneGeography, int bus_num) {
		// Go through all routes, generate vehicle_num[i] buses in the beginning of the
		// routes
		int num_per_hub = (int) Math.ceil(bus_num / (GlobalVariables.HUB_INDEXES.size()>0?GlobalVariables.HUB_INDEXES.size():1));
		try {
			for (int startZone : GlobalVariables.HUB_INDEXES) {
				ArrayList<Integer> route = new ArrayList<Integer>(Arrays.asList(startZone));
				int vehicle_gap = Math.round(60 / GlobalVariables.SIMULATION_STEP_SIZE); // Ticks between two
																							// consecutive bus
				// GeometryFactory fac = new GeometryFactory();
				// Decide the next departure time
				int next_departure_time = 0;
				// Generate vehicle_num buses for the corresponding route
				Zone z = ContextCreator.getZoneContext().get(route.get(0));
				for (int j = 0; j < num_per_hub; j++) {
					ElectricBus b;
					b = new ElectricBus(-1, route, next_departure_time);
					b.addPlan(z.getIntegerID(), z.getCoord(), ContextCreator.getCurrentTick());
					this.add(b);
					b.setCurrentCoord(z.getCoord());
					b.addPlan(z.getIntegerID(), z.getCoord(), ContextCreator.getCurrentTick());
					b.setNextPlan();
					b.addPlan(z.getIntegerID(), z.getCoord(), next_departure_time); // Initialize the first plan
					b.setNextPlan();
					b.departure();
					next_departure_time += vehicle_gap;
					if (next_departure_time > 3600 / GlobalVariables.SIMULATION_STEP_SIZE) {
						next_departure_time = 0;
					}
					this.busList.add(b);
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

	public List<ElectricTaxi> getTaxis() {
		return this.taxiList;
	}

	public List<ElectricBus> getBuses() {
		return this.busList;
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

}