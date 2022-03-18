package addsEVs.vehiclecontext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.*;
import addsEVs.vehiclecontext.Vehicle;
import repast.simphony.context.DefaultContext;
import repast.simphony.space.gis.Geography;

public class VehicleContext extends DefaultContext<Vehicle> {
	private HashMap<Integer, LinkedBlockingQueue<ElectricVehicle>> vehicleList;
	
	public VehicleContext() {
		super("VehicleContext");
		ContextCreator.logger.info("VehicleContext creation");
		Geography<Zone> zoneGeography;
		zoneGeography = ContextCreator.getZoneGeography();
		
		this.vehicleList = new HashMap<Integer, LinkedBlockingQueue<ElectricVehicle>>();
		
        // Geography<Airport> airportGeography;
        // airportGeography = ContextCreator.getAirportGeography();

		//createVehicleContextFromManualDemand(zoneGeography,vehicleGeography);
		//createVehicleContextFromActivityModels(zoneGeography,vehicleGeography);
		//Generate 1000 vehicles
		createVehicleContextFromZone(zoneGeography, GlobalVariables.NUM_OF_EV);
		System.out.println("EV generated!");
//		busRoute.add(GlobalVariables.BUS_ROUTES);
		createBusContextFromZone(zoneGeography, 
//				ContextCreator.getBusSchedule(), 
//				ContextCreator.getBusNum(), 
//				ContextCreator.getBusGap(), 
				GlobalVariables.NUM_OF_BUS);
		System.out.println("BUS generated!");
	}
	
	public void createVehicleContextFromZone(Geography<Zone> zoneGeography, int vehicle_num) {
		int total_vehicles = 0;
		int num_total = (vehicle_num + 1) * 500;
		int num_hub = num_total / 2 / GlobalVariables.HUB_INDEXES.size();
		int num_per_zone = (int) Math.ceil(vehicle_num * 500 / 2.0 / zoneGeography.size());
		for (Zone z : zoneGeography.getAllObjects()) {
			Geometry hgeom = zoneGeography.getGeometry(z);
			Coordinate coord = hgeom.getCoordinate();
			LinkedBlockingQueue<ElectricVehicle> tmpQueue = new LinkedBlockingQueue<ElectricVehicle>();
			int vehicle_num_to_generate = 0;
			if (num_total < num_per_zone) {
				vehicle_num_to_generate = num_total;
			} else {
				vehicle_num_to_generate = num_per_zone;
			}
			num_total -= num_per_zone;
			if (GlobalVariables.HUB_INDEXES.contains(z.getIntegerID())) {
				vehicle_num_to_generate = num_hub;
			}
//				else if(GlobalVariables.HUB_INDEXES.contains(z.getIntegerID())){
//					vehicle_num_to_generate = vehicle_num*200;
//				}
			for (int i = 0; i < vehicle_num_to_generate; i++) {
				// GeometryFactory fac = new GeometryFactory();
				ElectricVehicle v;
				v = new ElectricVehicle();
				v.addPlan(z.getIntegerID(), z.getCoord(), 0.0); // Initialize the first plan
				this.add(v);
				tmpQueue.add(v);
				// System.out.println("Vehicle:" + i+ " generated");
				v.setOriginalCoord(coord);
				v.setCurrentCoord(coord);
//					Point geom = fac.createPoint(coord);
//					vehicleGeography.move(v, geom);
				total_vehicles += 1;
			}
			this.vehicleList.put(z.getIntegerID(), tmpQueue);
			z.addVehicleStock(vehicle_num_to_generate);
		}
		System.out.println("Total EV vehicles generated " + total_vehicles);
	}
	
	// initialize buses for each route, if station is assigned to specified location, replace zoneGeography to stationGeography
	public void createBusContextFromZone(Geography<Zone> zoneGeography, 
//			ArrayList<ArrayList<Integer>>routes, 
//			ArrayList<Integer> vehicle_nums, 
//			ArrayList<Integer> vehicle_gaps, 
			int bus_num){
		// go through all routes, generate vehicle_num[i] buses in the beginning of the routes
		int num_per_hub = (int) Math.ceil(bus_num * 100/ GlobalVariables.HUB_INDEXES.size());
		try{
		for(int startZone: GlobalVariables.HUB_INDEXES){
			ArrayList<Integer> route = new ArrayList<Integer>(Arrays.asList(startZone));
			int vehicle_gap = Math.round(60/GlobalVariables.SIMULATION_STEP_SIZE); //Ticks between two consecutive bus
//			int vehicle_num = (vehicle_nums.get(i)+2) * bus_num; // Add 2 vehicles in case of delay
			// GeometryFactory fac = new GeometryFactory();
			// Decide the next departure time
			int next_departure_time = 0;
			// generate vehicle_num buses for the corresponding route
			Zone z = ContextCreator.getCityContext().findHouseWithDestID(route.get(0));
//			Zone z2 = ContextCreator.getCityContext().findHouseWithDestID(route.get(1));
			for(int j = 0; j< num_per_hub; j++){
				Bus b;
				b = new Bus(-1, route, next_departure_time);
				b.addPlan(z.getIntegerID(), z.getCoord(), 0); //Initialize the first plan
				this.add(b);
				b.setOriginalCoord(z.getCoord());
				b.setCurrentCoord(z.getCoord());
//				Point geom = fac.createPoint(z.getCoord());
//				vehicleGeography.move(b, geom);
//				System.out.println("Next stop is"+b.getNextStop());
				b.addPlan(z.getIntegerID(), z.getCoord(), next_departure_time); //Wait for 6 minutes, gap is 30 minutes
				b.setNextPlan();
				b.departure(z.getIntegerID());
			    next_departure_time += vehicle_gap;
			}
			
//			// update info in corresponding zone
//			for(int zoneID: route){
//				Zone zone = ContextCreator.getCityContext().findHouseWithDestID(zoneID);
//				zone.setBusInfo(vehicle_gap);
//			}
		}}
		catch(Exception e){
			System.out.println(e.toString());
		}
	}
	
	
	// return the list of vehicles for certain zone
	public LinkedBlockingQueue<ElectricVehicle> getVehicles(int integerID) {
		return this.vehicleList.get(integerID);
	}
	
	
	// add vehicle to zones 
	public void addVehicle(ElectricVehicle v, int integerID){
		this.vehicleList.get(integerID).add(v);
	}

	
//	public void createVehicleContextFromActivityModels(
//			Geography<Zone> zoneGeography, Geography<Vehicle> vehicleGeography) {
//		for (Zone z : zoneGeography.getAllObjects()) {
//			Geometry hgeom = zoneGeography.getGeometry(z);
//			Coordinate coord = hgeom.getCoordinate();
//			
//			for (House h : z.getHouses()) {
//				GeometryFactory fac = new GeometryFactory();
//				Vehicle v;
//				
//				//TODO: Code a mechanism to generate vehicles with different parameters (like max acceleration)
////				if (GlobalVariables.ENABLE_MULTICLASS_ROUTING){//Gehlot: Generate multi-class vehicles
////					if((double) Math.random() > GlobalVariables.PROPORTION_OF_PREDEFINED_ROUTING_VEHICLES + GlobalVariables.PROPORTION_OF_LESS_FREQUENT_ROUTING_VEHICLES){
////						v = new Vehicle(h);
////					}else if((double) Math.random() < (GlobalVariables.PROPORTION_OF_PREDEFINED_ROUTING_VEHICLES)/(GlobalVariables.PROPORTION_OF_PREDEFINED_ROUTING_VEHICLES + GlobalVariables.PROPORTION_OF_LESS_FREQUENT_ROUTING_VEHICLES)){
////						v = new Vehicle_predefinedroutes(h);
////					}else{
////						v = new Vehicle_less_frequent_routing(h);
////					}
////				}else{
////					v = new Vehicle(h);
////				}
//				
//				v = new ElectricVehicle(h);
//				
//				this.add(v);
//				v.setOriginalCoord(coord);
//				Point geom = fac.createPoint(coord);
//				vehicleGeography.move(v, geom);
//			}
//		}
//	}
//	
	
//	public void createVehicleContextFromManualDemand(
//			Geography<Zone> zoneGeography, Geography<Vehicle> vehicleGeography) {
//		/***
//		 * Reads manually from CSV file or Database depending on the Global
//		 * Variable VEHICLES_PER_ZONE_DATABASE in config file
//		 */
//		VehicleCreatorFromManualData vehicleCreator;
//
////		vehicleCreator = new VehicleCreatorFromManualData(
////					GlobalVariables.VEHICLES_CSV, GlobalVariables.READING_CSV);
//		vehicleCreator = new VehicleCreatorFromManualData();
//
//		for (Zone z : zoneGeography.getAllObjects()) {
////			System.out
////					.println("VehicleContext: createVehicleContextFromManualDemand: zone: "
////							+ z.getIntegerID());
//
//			Geometry hgeom = zoneGeography.getGeometry(z);
//			Coordinate coord = hgeom.getCoordinate();
//
//			// Creates a temporal array list of vehicles
//			ArrayList<Vehicle> evacuatingVehicles = new ArrayList<Vehicle>();
//			// gets the ID of the current zone
//			int zoneId = z.getIntegerID();
//			// sets the temporal array list of vehicles as the array of vehicles
//			// evacuating from this zone
//			if (vehicleCreator.getVehiclesByZone(zoneId) != null) {
//				evacuatingVehicles = vehicleCreator.getVehiclesByZone(zoneId);
//				/*
//				 * System.out.println(
//				 * "VehicleContext: createVehicleContextFromManualDemand: Evacuating vehicles size: "
//				 * + evacuatingVehicles.size());
//				 */
//				for (int i = 0; i < evacuatingVehicles.size(); i++) {
//					GeometryFactory fac = new GeometryFactory();
//					Vehicle v = evacuatingVehicles.get(i);
//					this.add(v);
//					v.setOriginalCoord(coord);
//					Point geom = fac.createPoint(coord);
//					vehicleGeography.move(v, geom);
//
//				}
//			}
//		}
//	}
}