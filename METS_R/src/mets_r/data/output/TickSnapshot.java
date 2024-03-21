package mets_r.data.output;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.data.input.NetworkEventObject;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.Vehicle;

/**
 * Inherent from A-RESCUE
 * 
 * The tick "snapshot" is the basic bundle of data for the METS_R output data
 * buffer. All pieces of data collected about the simulation during one model
 * tick will be packaged into one of these objects and placed into the
 * simulation data buffer as one single item.
 * 
 * Each piece of code within the program which wishes to act upon the simulation
 * output data will read one tick snapshot from the buffer, process it, and then
 * read the next tick snapshot from the buffer.
 * 
 * 
 * @author Christopher Thompson
 **/

public class TickSnapshot {

	/** The number of the time step of this snapshot of the simulation. */
	private final int tickNumber;

	/** The collection of vehicle data gathered during this time tick. */
	/** Consider two classes of vehicles: EV and Bus */
	private HashMap<Integer, VehicleSnapshot> vehicles;
	private HashMap<Integer, EVSnapshot> evs_occupied;
	private HashMap<Integer, EVSnapshot> evs_relocation;
	private HashMap<Integer, EVSnapshot> evs_charging;
	private HashMap<Integer, BusSnapshot> buses;

	// Link energy consumptions for UCB
//	private Map<Integer, ArrayList<Double>> link_UCB; // the link energy consumption for taxis
//	private Map<Integer, ArrayList<Double>> link_UCB_BUS; // the link energy consumption for bus.
//	private Map<Integer, ArrayList<Double>> speed_vehicle; // used for shadow bus construction.

	// Placeholders for future operation algorithms that may use data
	// from zones and charging stations
	// private HashMap<Integer, ZoneSnapshot> zones;
	// private HashMap<Integer, ChargingStationSnapshot> chargers;

	/** The collection of event data gathered during this time tick. */
	private ArrayList<ArrayList<NetworkEventObject>> events;

	/**
	 * Creates the tick snapshot with the given simulation tick number and
	 * initializes all the data structures for holding the data gathered during the
	 * tick.
	 * 
	 * @param tickNumber the tick number within the simulation.
	 * @throws IllegalArgumentException if the tick number given is invalid.
	 */
	public TickSnapshot(int tickNumber) {
		// Verify the given tick number is valid and set it
		if (tickNumber < 0) {
			throw new IllegalArgumentException("Invalid tick number");
		}
		this.tickNumber = tickNumber;

		// Setup the map for holding the vehicle data
		this.vehicles = new HashMap<Integer, VehicleSnapshot>();
		this.buses = new HashMap<Integer, BusSnapshot>();
		this.evs_occupied = new HashMap<Integer, EVSnapshot>();
		this.evs_relocation = new HashMap<Integer, EVSnapshot>();
		this.evs_charging = new HashMap<Integer, EVSnapshot>();

		// Setup the map for holding the event data. Two subarraylists (for starting
		// events and ending events) is created in a large arraylist
		this.events = new ArrayList<ArrayList<NetworkEventObject>>(3);
		for (int i = 0; i < 3; i++) {
			this.events.add(new ArrayList<NetworkEventObject>());
		}

		// Setup the map for holding the link energy consumption, which is a map of
		// linkid: link of passed vehicles, we store this for each tick
	}

	/**
	 * Stores the current state of the given vehicle to the tick snapshot. If this
	 * vehicle has already been logged in the current snapshot, its values will be
	 * overwritten. Each vehicle can only report one snapshot of current values per
	 * simulation tick.
	 * 
	 * @param vehicle    the vehicle from which data values are being recorded.
	 * @param coordinate the current vehicle position in the simulation.
	 * @throws Throwable if there is an error getting data from the vehicle.
	 */
	public void logVehicle(Vehicle vehicle, Coordinate coordinate) throws Throwable {
		if (vehicle == null) {
			return;
		}
		if (coordinate == null) {
			return;
		}

		// Pull out values from the vehicle & coord we need to capture
		int id = vehicle.getID();
		double prev_x = vehicle.getpreviousEpochCoord().x;
		double prev_y = vehicle.getpreviousEpochCoord().y;
		double x = coordinate.x;
		double y = coordinate.y;
		double bearing = vehicle.getBearing();
		double speed = vehicle.currentSpeed();
		double originalX = vehicle.getOriginCoord().x;
		double originalY = vehicle.getOriginCoord().y;
		double destX = vehicle.getDestCoord().x;
		double destY = vehicle.getDestCoord().y;
		int vehicleClass = vehicle.getVehicleClass();
		int roadID = vehicle.getRoad().getID();

		// Check if there is already a vehicleSnapshot in this tick due to visualization
		// If so, then use the previous coordinates from the recorded snapshot 
		if (this.getVehicleSnapshot(id) != null) {
			prev_x = this.getVehicleSnapshot(id).prev_x;
			prev_y = this.getVehicleSnapshot(id).prev_y;
		}

		VehicleSnapshot snapshot = new VehicleSnapshot(id, prev_x, prev_y, x, y, bearing, speed, originalX, originalY, destX,
				destY, vehicleClass, roadID);
		synchronized(this.vehicles) {
			this.vehicles.put(id, snapshot);
		}
	}

	// Store the current state of the given EV to the tick snapshot.
	public void logEV(ElectricTaxi vehicle, Coordinate coordinate, int vehState) throws Throwable {
		if (vehicle == null) {
			return;
		}
		if (coordinate == null) {
			return;
		}
		// Pull out values from the vehicle & coord we need to capture
		int id = vehicle.getID();
		double prev_x = vehicle.getpreviousEpochCoord().x;
		double prev_y = vehicle.getpreviousEpochCoord().y;
		double x = coordinate.x;
		double y = coordinate.y;
		double speed = vehicle.currentSpeed();
		double bearing = vehicle.getBearing();
		int originID = vehicle.getOriginID();
		int destID = vehicle.getDestID();
		int nearlyArrived = vehicle.nearlyArrived();
		int vehicleClass = vehicle.getVehicleClass();
		int roadID = vehicle.getRoad().getID();
		double batteryLevel = vehicle.getBatteryLevel();
		double energyConsumption = vehicle.getTotalConsume();
		int servedPass = vehicle.servedPass;

		if (this.getEVSnapshot(id, vehicle.getState()) != null) {
			prev_x = this.getEVSnapshot(id, vehicle.getState()).prev_x;
			prev_y = this.getEVSnapshot(id, vehicle.getState()).prev_y;
		}

		// Create a snapshot for the vehicle and store it in the map
		EVSnapshot snapshot = new EVSnapshot(id, prev_x, prev_y, x, y, bearing, speed, originID, destID, nearlyArrived,
				vehicleClass, batteryLevel, energyConsumption, roadID, servedPass
		);

		if (vehState == Vehicle.OCCUPIED_TRIP) {
			synchronized(this.evs_occupied) {
				this.evs_occupied.put(id, snapshot);
			}
			
		} else if (vehState == Vehicle.INACCESSIBLE_RELOCATION_TRIP ||
				vehState == Vehicle.CRUISING_TRIP ||
				vehState == Vehicle.PICKUP_TRIP ) {
			synchronized(this.evs_relocation) {
				this.evs_relocation.put(id, snapshot);
			}
			
		} else if (vehState == Vehicle.CHARGING_TRIP) {
			synchronized(this.evs_charging) {
				this.evs_charging.put(id, snapshot);
			}
		}
	}

	// Store the current state of the given Bus to the tick snapshot.
	public void logBus(ElectricBus vehicle, Coordinate coordinate) throws Throwable {
		if (vehicle == null) {
			return;
		}
		if (coordinate == null) {
			return;
		}

		// Pull out values from the vehicle & coord we need to capture
		int id = vehicle.getID();
		int routeID = vehicle.getRouteID();
		double prev_x = vehicle.getpreviousEpochCoord().x;
		double prev_y = vehicle.getpreviousEpochCoord().y;
		double x = coordinate.x;
		double y = coordinate.y;
		double bearing = vehicle.getBearing();
		double speed = vehicle.currentSpeed();
		double acc = vehicle.currentAcc();
		double batteryLevel = vehicle.getBatteryLevel();
		double energyConsumption = vehicle.getTotalConsume();
		int servedPass = vehicle.served_pass;
		int roadID = vehicle.getRoad().getID();

		if (this.getBusSnapshot(id) != null) {
			prev_x = this.getBusSnapshot(id).prev_x;
			prev_y = this.getBusSnapshot(id).prev_y;
		}

		BusSnapshot snapshot = new BusSnapshot(id, routeID, prev_x, prev_y, x, y, bearing, speed, acc, batteryLevel,
				energyConsumption, roadID, servedPass);
		synchronized(this.buses) {
			this.buses.put(id, snapshot);
		}
	}

	public void logEvent(NetworkEventObject event, int type) throws Throwable {

		// Make sure the given event object is valid
		if (event == null) {
			throw new IllegalArgumentException("No event given.");
		}

		// Add event to the arraylist
		if (type == 1) { // if it is event starting
			this.events.get(0).add(event);
		} else if (type == 2) {// if it is event ending
			this.events.get(1).add(event);
		} else {// if external event has been added to queue
			this.events.get(2).add(event);
		}
	}

	public Collection<Integer> getVehicleList() {
		if (this.vehicles == null || this.vehicles.isEmpty()) {
			return null;
		}

		return this.vehicles.keySet();
	}

	public Collection<Integer> getEVList(int vehState) {
		if (vehState == Vehicle.OCCUPIED_TRIP) {
			if (this.evs_occupied == null || this.evs_occupied.isEmpty()) {
				return null;
			}

			return this.evs_occupied.keySet();
		}
		if (vehState == Vehicle.INACCESSIBLE_RELOCATION_TRIP) {
			if (this.evs_relocation == null || this.evs_relocation.isEmpty()) {
				return null;
			}

			return this.evs_relocation.keySet();
		}
		if (vehState == Vehicle.CHARGING_TRIP) {
			if (this.evs_charging == null || this.evs_charging.isEmpty()) {
				return null;
			}

			return this.evs_charging.keySet();
		}
		return null;

	}

	public Collection<Integer> getBusList() {
		if (this.buses == null || this.buses.isEmpty()) {
			return null;
		}

		return this.buses.keySet();
	}

	public ArrayList<ArrayList<NetworkEventObject>> getEventList() {
		if (this.events == null || this.events.isEmpty()) {
			return null;
		}

		return this.events;
	}

	public VehicleSnapshot getVehicleSnapshot(int id) {
		// Check the map exists and is not empty
		if (this.vehicles == null || this.vehicles.isEmpty()) {
			return null;
		}

		// Attempt to pull out the vehicle from the map with the given id
		VehicleSnapshot snapshot = this.vehicles.get(id);

		// Return the found vehicle snapshot or null if nothing found
		return snapshot;
	}

	public EVSnapshot getEVSnapshot(int id, int vehState) {
		if (vehState == Vehicle.OCCUPIED_TRIP) {
			// Check the map exists and is not empty
			if (this.evs_occupied == null || this.evs_occupied.isEmpty()) {
				return null;
			}

			// Attempt to pull out the vehicle from the map with the given id
			EVSnapshot snapshot = this.evs_occupied.get(id);

			// Return the found vehicle snapshot or null if nothing found
			return snapshot;
		}
		if (vehState == Vehicle.INACCESSIBLE_RELOCATION_TRIP) {
			// Check the map exists and is not empty
			if (this.evs_relocation == null || this.evs_relocation.isEmpty()) {
				return null;
			}

			// Attempt to pull out the vehicle from the map with the given id
			EVSnapshot snapshot = this.evs_relocation.get(id);

			// Return the found vehicle snapshot or null if nothing found
			return snapshot;
		}
		if (vehState == Vehicle.CHARGING_TRIP) {
			// Check the map exists and is not empty
			if (this.evs_charging == null || this.evs_charging.isEmpty()) {
				return null;
			}

			// Attempt to pull out the vehicle from the map with the given id
			EVSnapshot snapshot = this.evs_charging.get(id);

			// Return the found vehicle snapshot or null if nothing found
			return snapshot;
		}

		return null;
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

	public boolean isEmpty() {
		return (this.vehicles.isEmpty() && this.evs_occupied.isEmpty() && this.evs_relocation.isEmpty()
				&& this.evs_charging.isEmpty() && this.buses.isEmpty());
	}

	public int getTickNumber() {
		return this.tickNumber;
	}
}
