package addsEVs.data;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
//import java.util.LinkedList;
import java.util.Map;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.NetworkEventObject;
import addsEVs.citycontext.Road;
import addsEVs.vehiclecontext.Bus;
import addsEVs.vehiclecontext.ElectricVehicle;
import addsEVs.vehiclecontext.Vehicle;


/**
 * 
 * The tick "snapshot" is the basic bundle of data for the EvacSim output
 * data buffer.  All pieces of data collected about the simulation during
 * one model tick will be packaged into one of these objects and placed
 * into the simulation data buffer as one single item.
 * 
 * Each piece of code within the program which wishes to act upon the 
 * simulation output data will read one tick snapshot from the buffer, 
 * process it, and then read the next tick snapshot from the buffer.
 * 
 * 
 * @author Christopher Thompson (thompscs@purdue.edu)
 * @version 1.0
 * @date 28 June 2017
 */
public class TickSnapshot {
    
    /** The number of the time step of this snapshot of the simulation. */
    private final double tickNumber;
    
    /** The collection of vehicle data gathered during this time tick. */
    /** Consider two classes of vehicles: EV and Bus */
    // private HashMap<Integer, VehicleSnapshot> vehicles;
    private HashMap<Integer, VehicleSnapshot> vehicles;
    
    private HashMap<Integer, EVSnapshot> evs;
    
    private HashMap<Integer, BusSnapshot> buses;
    
    private HashMap<Integer, LinkSnapshot> links;
    
    // LZ: link energy consumptions for UCB
    private Map<Integer, ArrayList<Double>> link_UCB; // 
    
    //July,2020,JiaweiXue
    private Map<Integer, ArrayList<Double>> link_UCB_BUS; // the link energy consumption for bus.
    private Map<Integer, ArrayList<Double>> speed_vehicle; // used for shadow bus construction.
    
    
    // LZ TO DO: placeholders
    // private HashMap<Integer, ZoneSnapshot> zones;
    // private HashMap<Integer, ChargingStationSnapshot> chargers;
    
    /** HG: The collection of event data gathered during this time tick. */
    private ArrayList<ArrayList<NetworkEventObject>> events;
    
    /**
     * Creates the tick snapshot with the given simulation tick number and
     * initializes all the data structures for holding the data gathered
     * during the tick.
     * 
     * @param tickNumber the tick number within the simulation.
     * @throws IllegalArgumentException if the tick number given is invalid.
     */
    public TickSnapshot(double tickNumber) {
        // verify the given tick number is valid and set it
        if (tickNumber < 0.0) {
            throw new IllegalArgumentException("Invalid tick number");
        }
        this.tickNumber = tickNumber;
        
        // setup the map for holding the vehicle data
        this.vehicles = new HashMap<Integer, VehicleSnapshot>();
        this.buses = new HashMap<Integer, BusSnapshot>();
        this.evs = new HashMap<Integer, EVSnapshot>();
        this.links = new HashMap<Integer, LinkSnapshot>();
        
        // HG: setup the map for holding the event data. Two subarraylists (for starting events and ending events) is created in a large arraylist
        this.events = new ArrayList<ArrayList<NetworkEventObject>>(3);
        for (int i=0;i<3;i++){
	    	this.events.add(new ArrayList<NetworkEventObject>());
	    }
        
        // LZ: setup the map for holding the link energy consumption, which is a map of linkid: link of passed vehicles, we store this for each tick
        this.link_UCB = Collections.synchronizedMap(new HashMap<Integer, ArrayList<Double>>());
        
        //July,2020,JiaweiXue
        this.link_UCB_BUS = Collections.synchronizedMap (new HashMap<Integer, ArrayList<Double>>());
        this.speed_vehicle = Collections.synchronizedMap(new HashMap<Integer, ArrayList<Double>>());
    }
    
    
    /**
     * Stores the current state of the given vehicle to the tick snapshot.
     * If this vehicle has already been logged in the current snapshot, its
     * values will be overwritten.  Each vehicle can only report one snapshot
     * of current values per simulation tick.
     * 
     * @param vehicle the vehicle from which data values are being recorded.
     * @param coordinate the current vehicle position in the simulation.
     * @throws Throwable if there is an error getting data from the vehicle.
     */
    public void logVehicle(Vehicle vehicle, 
                           Coordinate coordinate) throws Throwable {
        if (vehicle == null) {
            return;
        }
        if (coordinate == null) {
            return;
        }
        
        // pull out values from the vehicle & coord we need to capture
        int id = vehicle.getVehicleID();
        double prev_x = vehicle.getpreviousEpochCoord().x;
        double prev_y = vehicle.getpreviousEpochCoord().y;
        double x = coordinate.x;
        double y = coordinate.y;
        float speed = vehicle.currentSpeed();
        double originalX =  vehicle.getOriginalCoord().x;
        double originalY = vehicle.getOriginalCoord().y;
        double destX = vehicle.getDestCoord().x;
        double destY = vehicle.getDestCoord().y;
        int nearlyArrived = vehicle.nearlyArrived();
        int vehicleClass = vehicle.getVehicleClass();
        int roadID = vehicle.getRoad().getLinkid();
        // double batteryLevel = vehicle.getBatteryLevel();
        //int departure = vehicle.getDepTime();
        //int arrival = vehicle.getEndTime();
        //float distance = vehicle.accummulatedDistance_;
        //double z = coordinate.z;
        
        // TODO: perform any checks on the extracted values
        
        //HGehlot: Check if there is already a vehicleSnapshot in this tick due to visualization interpolation recording.
        //If so, then use the previous coordinates from the recorded snapshot because we had set the previous coordinates to the current coordinates 
        //when we ended the function recVehSnaphotForVisInterp() in vehicle class.
        if(this.getVehicleSnapshot(id)!=null){
        	prev_x = this.getVehicleSnapshot(id).prev_x;
        	prev_y = this.getVehicleSnapshot(id).prev_y;
        }
        
        // create a snapshot for the vehicle and store it in the map
        VehicleSnapshot snapshot = new VehicleSnapshot(id, prev_x, prev_y,
        											   x, y, speed,
        											   originalX, originalY,
                                                       destX, destY, 
                                                       nearlyArrived,
                                                       vehicleClass,
                                                       roadID
                                                       //departure,
                                                       //arrival,
                                                       //distance,    
                                                       );
        this.vehicles.put(id, snapshot);
    }
    
	// LZ: Store the current state of the given EV to the tick snapshot.
	public void logEV(ElectricVehicle vehicle, Coordinate coordinate) throws Throwable {
		if (vehicle == null) {
			return;
		}
		if (coordinate == null) {
			return;
		}

		// pull out values from the vehicle & coord we need to capture
		int id = vehicle.getVehicleID();
		// System.out.println(vehicle.getVehicleClass()+" class: "+ id);
		double prev_x = vehicle.getpreviousEpochCoord().x;
		double prev_y = vehicle.getpreviousEpochCoord().y;
		double x = coordinate.x;
		double y = coordinate.y;
		float speed = vehicle.currentSpeed();
		int originID = vehicle.getOriginID();
		int destID = vehicle.getDestinationID();
//		double originalX = vehicle.getOriginalCoord().x;
//		double originalY = vehicle.getOriginalCoord().y;
//		double destX = vehicle.getDestCoord().x;
//		double destY = vehicle.getDestCoord().y;
		int nearlyArrived = vehicle.nearlyArrived();
		int vehicleClass = vehicle.getVehicleClass();
		int roadID = vehicle.getRoad().getLinkid();
		double batteryLevel = vehicle.getBatteryLevel();
		double energyConsumption = vehicle.getTotalConsume();
		int servedPass= vehicle.served_pass;
		// int departure = vehicle.getDepTime();
		// int arrival = vehicle.getEndTime();
		// float distance = vehicle.accummulatedDistance_;
		// double z = coordinate.z;

		// TODO: perform any checks on the extracted values

		// HGehlot: Check if there is already a vehicleSnapshot in this tick due
		// to visualization interpolation recording.
		// If so, then use the previous coordinates from the recorded snapshot
		// because we had set the previous coordinates to the current
		// coordinates
		// when we ended the function recVehSnaphotForVisInterp() in vehicle
		// class.

		if (this.getEVSnapshot(id) != null) {
			prev_x = this.getEVSnapshot(id).prev_x;
			prev_y = this.getEVSnapshot(id).prev_y;
		}

		// create a snapshot for the vehicle and store it in the map
		EVSnapshot snapshot = new EVSnapshot(id, prev_x, prev_y, x, y, speed, originID, destID, nearlyArrived, vehicleClass, batteryLevel, energyConsumption, roadID, servedPass
		// departure,
		// arrival,
		// distance,
		);
		//System.out.println(vehicle.getVehicleClass()+" class: "+ snapshot.toString());
		this.evs.put(id, snapshot);
		
	}
	
	// LZ: Store the link state
	public void logLink(Road road) throws Throwable{
		if(road == null){
			return;
		}
		int id = road.getLinkid();
		double speed = road.calcSpeed();
		int nVehicles = road.getVehicleNum(); //LZ: Oct 19, replaced getNumVehicles with this.
		double energy = road.getTotalEnergy();
		int flow = road.getTotalFlow();
		
		LinkSnapshot snapshot = new LinkSnapshot(id, speed, nVehicles, energy, flow);
		this.links.put(id, snapshot);
	}

	// LZ: Store the current state of the given Bus to the tick snapshot.
	public void logBus(Bus vehicle, Coordinate coordinate) throws Throwable {
		if (vehicle == null) {
			return;
		}
		if (coordinate == null) {
			return;
		}

		// pull out values from the vehicle & coord we need to capture
		int id = vehicle.getVehicleID();
		// System.out.println(vehicle.getVehicleClass()+" class: "+ id);
		int routeID = vehicle.getRouteID();
		double prev_x = vehicle.getpreviousEpochCoord().x;
		double prev_y = vehicle.getpreviousEpochCoord().y;
		double x = coordinate.x;
		double y = coordinate.y;
		float speed = vehicle.currentSpeed();
		float acc = vehicle.currentAcc();
		double batteryLevel = vehicle.getBatteryLevel();
		double energyConsumption = vehicle.getTotalConsume();
		int servedPass = vehicle.served_pass;
//		double originalX = vehicle.getOriginalCoord().x;
//		double originalY = vehicle.getOriginalCoord().y;
//		double destX = vehicle.getDestCoord().x;
//		double destY = vehicle.getDestCoord().y;
//		int nearlyArrived = vehicle.nearlyArrived();
//		int vehicleClass = vehicle.getVehicleClass();
		int roadID = vehicle.getRoad().getLinkid();
		// int departure = vehicle.getDepTime();
		// int arrival = vehicle.getEndTime();
		// float distance = vehicle.accummulatedDistance_;
		// double z = coordinate.z;

		// TODO: perform any checks on the extracted values

		// HGehlot: Check if there is already a vehicleSnapshot in this tick due
		// to visualization interpolation recording.
		// If so, then use the previous coordinates from the recorded snapshot
		// because we had set the previous coordinates to the current
		// coordinates
		// when we ended the function recVehSnaphotForVisInterp() in vehicle
		// class.
		if (this.getBusSnapshot(id) != null) {
			prev_x = this.getBusSnapshot(id).prev_x;
			prev_y = this.getBusSnapshot(id).prev_y;
		}

		// create a snapshot for the vehicle and store it in the map
		BusSnapshot snapshot = new BusSnapshot(id, routeID, prev_x, prev_y, x, y, speed, acc, batteryLevel, energyConsumption, roadID, servedPass);
		//System.out.println(vehicle.getVehicleClass()+" class: "+ snapshot.toString());
		this.buses.put(id, snapshot);
	}
    /**
     * HG: Stores the current state of the given event to the tick snapshot.
     * 
     * @param event the event for which a snapshot is being recorded.
     * @param type whether it is starting or end of the event. 1: starting, 2: ending
     * @throws Throwable if an error occurs trying to record the event.
     */
    public void logEvent(NetworkEventObject event,
            int type) throws Throwable {
    	
    	// make sure the given event object is valid
        if (event == null) {
            throw new IllegalArgumentException("No event given.");
        }
        
        //Add event to the arraylist
        if(type == 1){//if it is event starting
            this.events.get(0).add(event); 
        }else if (type == 2){//if it is event ending
        	this.events.get(1).add(event);
        }else{//if external event has been added to queue
        	this.events.get(2).add(event);
        }
    }
    
      
    /**
     * Returns a list of vehicle IDs stored in the tick snapshot.
     * 
     * @return a list of vehicle IDs stored in the tick snapshot.
     */
    public Collection<Integer> getVehicleList() {
        if (this.vehicles == null || this.vehicles.isEmpty()) {
            return null;
        }
        
        return this.vehicles.keySet();
    }
    
    /**
     * Returns a list of ev IDs stored in the tick snapshot.
     * 
     * @return a list of ev IDs stored in the tick snapshot.
     */
    public Collection<Integer> getEVList() {
        if (this.evs == null || this.evs.isEmpty()) {
            return null;
        }
        
        return this.evs.keySet();
    }
    
    /**
     * Returns a list of bus IDs stored in the tick snapshot.
     * 
     * @return a list of bus IDs stored in the tick snapshot.
     */
    public Collection<Integer> getBusList() {
        if (this.buses == null || this.buses.isEmpty()) {
            return null;
        }
        
        return this.buses.keySet();
    }
    
    /**
     * Returns a list of link IDs stored in the tick snapshot.
     * 
     * @return a list of link IDs stored in the tick snapshot.
     */
    public Collection<Integer> getLinkList() {
        if (this.links == null || this.links.isEmpty()) {
            return null;
        }
        
        return this.links.keySet();
    }
    
    
    /**
     * HG: Returns a list of events stored in the tick snapshot.
     * 
     * @return a list of events stored in the tick snapshot.
     */
    public ArrayList<ArrayList<NetworkEventObject>> getEventList() {
        if (this.events == null || this.events.isEmpty()) {
            return null;
        }
        
        return this.events;
    }
    
    // function to get the linkIDs for UCB
    // charitha : bug fix, avoid returning null here
    public Collection<Integer> getLinkIDList() {
    	synchronized (link_UCB) {
        	return this.link_UCB.keySet();
		}
    }
    
    public Collection<Integer> getLinkIDListBus(){
    	synchronized (link_UCB_BUS) {
        	return this.link_UCB_BUS.keySet();
		}
    }
    
    /**
     * Retrieves the matching vehicle from the snapshot list or null if this
     * vehicle has not yet been recorded within this tick.
     * 
     * @param id the identity of the vehicle for which a snapshot is requested.
     * @return the vehicle snapshot for the given id or null if not found.
     */
    public VehicleSnapshot getVehicleSnapshot(int id) {
        // check the map exists and is not empty
        if (this.vehicles == null || this.vehicles.isEmpty()) {
            return null;
        }
        
        // attempt to pull out the vehicle from the map with the given id
        VehicleSnapshot snapshot = this.vehicles.get(id);
        
        // return the found vehicle snapshot or null if nothing found
        return snapshot;
    }
    
    public EVSnapshot getEVSnapshot(int id) {
        // check the map exists and is not empty
        if (this.evs == null || this.evs.isEmpty()) {
            return null;
        }
        
        // attempt to pull out the vehicle from the map with the given id
        EVSnapshot snapshot = this.evs.get(id);
        
        // return the found vehicle snapshot or null if nothing found
        return snapshot;
    }
    
    public BusSnapshot getBusSnapshot(int id) {
        // check the map exists and is not empty
        if (this.buses == null || this.buses.isEmpty()) {
            return null;
        }
        
        // attempt to pull out the vehicle from the map with the given id
        BusSnapshot snapshot = this.buses.get(id);
        
        // return the found vehicle snapshot or null if nothing found
        return snapshot;
    }
    
    public LinkSnapshot getLinkSnapshot(int id) {
        // check the map exists and is not empty
        if (this.links == null || this.links.isEmpty()) {
            return null;
        }
        
        // attempt to pull out the vehicle from the map with the given id
        LinkSnapshot snapshot = this.links.get(id);
        
        // return the found vehicle snapshot or null if nothing found
        return snapshot;
    }
    
    public ArrayList<Double> getLinkEnergyList(int id) {
    	ArrayList<Double> linkEnergy = null;
    	synchronized (link_UCB) {
    		if(this.link_UCB == null || this.link_UCB.isEmpty()) {
        		return null;
        	}
        	linkEnergy = this.link_UCB.get(id);
        		
		}
    	return linkEnergy;
    }
    
    //July,2020,JiaweiXue
    public ArrayList<Double> getLinkEnergyListBus(int id) {
    	ArrayList<Double> linkEnergy = null;
    	synchronized (link_UCB_BUS) {
        	if(this.link_UCB_BUS == null || this.link_UCB_BUS.isEmpty()) {
        		return null;
        	}
        	linkEnergy = this.link_UCB_BUS.get(id);
		}

    
    	return linkEnergy;
    }
    //July,2020,JiaweiXue
    public ArrayList<Double> getSpeedVehicle(int id) {
    	ArrayList<Double> linkSpeed  = null;
    	synchronized (speed_vehicle) {
        	if(this.speed_vehicle == null || this.speed_vehicle.isEmpty()) {
        		return null;
        	}
        	linkSpeed = this.speed_vehicle.get(id);
		}
   	
    	return linkSpeed;
    }
    
    
    /**
     * Returns whether or not anything was recorded in the snapshot.
     * 
     * @return whether or not anything was recorded in the snapshot.
     */
    public boolean isEmpty() {
        return (this.vehicles.isEmpty() && this.evs.isEmpty() && this.buses.isEmpty());
    }
    
    
    /**
     * Returns the model time step for the tick this snapshot represents.
     * 
     * @return the model time step for the tick this snapshot represents.
     */
    public double getTickNumber() {
        return this.tickNumber;
    }


	public void logLinkUCB(int id, double linkConsume) {
		synchronized(link_UCB){
			if (!this.link_UCB.containsKey(id)) {
				this.link_UCB.put(id, new ArrayList<Double>());
			}
			this.link_UCB.get(id).add(linkConsume);
		}
	}
	
	//July,2020,JiaweiXue
	public void logLinkUCBBus(int id, double linkConsume) {
		synchronized(link_UCB_BUS){
			if (!this.link_UCB_BUS.containsKey(id)) {
				this.link_UCB_BUS.put(id, new ArrayList<Double>());
			}
			this.link_UCB_BUS.get(id).add(linkConsume);
		}
	}
	
	//July,2020,JiaweiXue
	public void logSpeedVehicle(int id, double linkSpeed) {
		synchronized(speed_vehicle){
			if (!this.speed_vehicle.containsKey(id)) {
				this.speed_vehicle.put(id, new ArrayList<Double>());
			}
			this.speed_vehicle.get(id).add(linkSpeed);
			//System.out.println(this.link_UCB);
		}
	}
	
	public Map<Integer, ArrayList<Double>> getLinkUCB(){
		return this.link_UCB;
	}
	
	public void printUCBData(){
		this.link_UCB.entrySet().forEach(entry->{
		    System.out.println(entry.getKey() + " " + entry.getValue());  
		 });
	}
}
