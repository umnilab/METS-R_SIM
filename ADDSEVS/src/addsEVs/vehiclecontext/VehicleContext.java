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
		createVehicleContextFromZone(zoneGeography, GlobalVariables.NUM_OF_EV);
		ContextCreator.logger.info("EV generated!");
		createBusContextFromZone(zoneGeography, 
				GlobalVariables.NUM_OF_BUS);
		ContextCreator.logger.info("BUS generated!");
	}
	
	public void createVehicleContextFromZone(Geography<Zone> zoneGeography, int vehicle_num) {
		int total_vehicles = 0;
		int num_total = vehicle_num;
		// 1/2 of vehicles initialized in hubs
		int num_hub;
		if (GlobalVariables.HUB_INDEXES.size() > 0) {
			num_hub = (int) Math.ceil((float) num_total / 2.0 / GlobalVariables.HUB_INDEXES.size());
		}
		else{
			num_hub = 0;
		}
		// Generate the rest vehicles in other zones
		int num_per_zone = (int) Math.ceil((float) vehicle_num / 2.0 / (zoneGeography.size() - GlobalVariables.HUB_INDEXES.size()));
		for (Zone z : zoneGeography.getAllObjects()) {
			Geometry hgeom = zoneGeography.getGeometry(z);
			Coordinate coord = hgeom.getCoordinate();
			LinkedBlockingQueue<ElectricVehicle> tmpQueue = new LinkedBlockingQueue<ElectricVehicle>();
			int vehicle_num_to_generate = 0;
			if (GlobalVariables.HUB_INDEXES.contains(z.getIntegerID())) {
				vehicle_num_to_generate = num_hub;
			} else {
				vehicle_num_to_generate = num_per_zone;
			}
			vehicle_num_to_generate = num_total <= vehicle_num_to_generate ? num_total : vehicle_num_to_generate;
			num_total -= vehicle_num_to_generate;
			for (int i = 0; i < vehicle_num_to_generate; i++) {
				// GeometryFactory fac = new GeometryFactory();
				ElectricVehicle v;
				v = new ElectricVehicle();
				v.addPlan(z.getIntegerID(), z.getCoord(), 0.0); // Initialize the first plan
				this.add(v);
				tmpQueue.add(v);
				ContextCreator.logger.debug("Vehicle:" + i+ " generated");
				v.setOriginalCoord(coord);
				v.setCurrentCoord(coord);
//					Point geom = fac.createPoint(coord);
//					vehicleGeography.move(v, geom);
				total_vehicles += 1;
			}
			this.vehicleList.put(z.getIntegerID(), tmpQueue);
			z.addVehicleStock(vehicle_num_to_generate);
		}
		ContextCreator.logger.info("Total EV vehicles generated " + total_vehicles);
	}
	
	// Initialize buses for each route, if station is assigned to specified location, replace zoneGeography to stationGeography
	public void createBusContextFromZone(Geography<Zone> zoneGeography, 
			int bus_num){
		// Go through all routes, generate vehicle_num[i] buses in the beginning of the routes
		int num_per_hub = (int) Math.ceil(bus_num/ GlobalVariables.HUB_INDEXES.size());
		try{
		for(int startZone: GlobalVariables.HUB_INDEXES){
			ArrayList<Integer> route = new ArrayList<Integer>(Arrays.asList(startZone));
			int vehicle_gap = Math.round(60/GlobalVariables.SIMULATION_STEP_SIZE); //Ticks between two consecutive bus
			// GeometryFactory fac = new GeometryFactory();
			// Decide the next departure time
			int next_departure_time = 0;
			// Generate vehicle_num buses for the corresponding route
			Zone z = ContextCreator.getCityContext().findHouseWithDestID(route.get(0));
			for(int j = 0; j< num_per_hub; j++){
				Bus b;
				b = new Bus(-1, route, next_departure_time);
				b.addPlan(z.getIntegerID(), z.getCoord(), 0); //Initialize the first plan
				this.add(b);
				b.setOriginalCoord(z.getCoord());
				b.setCurrentCoord(z.getCoord());
//				Point geom = fac.createPoint(z.getCoord());
//				vehicleGeography.move(b, geom);
				b.addPlan(z.getIntegerID(), z.getCoord(), next_departure_time); //Wait for 6 minutes, gap is 30 minutes
				b.setNextPlan();
				b.departure(z.getIntegerID());
			    next_departure_time += vehicle_gap;
			}
		}}
		catch(Exception e){
			ContextCreator.logger.error(e.toString());
		}
	}
	
	
	// Return the list of vehicles for certain zone
	public LinkedBlockingQueue<ElectricVehicle> getVehicles(int integerID) {
		return this.vehicleList.get(integerID);
	}
	
	
	// Add vehicle to zones 
	public void addVehicle(ElectricVehicle v, int integerID){
		this.vehicleList.get(integerID).add(v);
	}
}