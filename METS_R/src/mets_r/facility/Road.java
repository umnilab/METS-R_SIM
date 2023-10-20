package mets_r.facility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import mets_r.*;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.Vehicle;

/**
 * Inherit from A-RESCUE
 * Modified by: Zengxiang Lei
 */

public class Road {
	/* Constants */
	public final static int Street = 1;
	public final static int Highway = 2;
	public final static int Bridge = 3;
	public final static int Tunnel = 4;
	public final static int Driveway = 8;
	public final static int Ramp = 9;
	public final static int U_Turn = 13;
	
	/* Private variables */
	private int ID;
	private int roadType;
	private double length;
	private ArrayList<Coordinate> coords;
	
	// Connection with other facilities
	private ArrayList<Lane> lanes; // Lanes inside the road
	private Node upStreamNode;
	private Node downStreamNode;
	private ArrayList<Integer> downStreamRoads; // Includes the opposite link in U-turn
	private int upStreamJunction;
	private int downStreamJunction;
	private int neighboringZone;
	private double distToZone;
	
	// For vehicle movement
	private int lastUpdateHour; // To find the current hour of the simulation
	private AtomicInteger nVehicles_; // Number of vehicles currently in the road
	private Vehicle lastVehicle_; // Vehicle stored as a linked list
	private Vehicle firstVehicle_;
	private double travelTime;
	private double freeSpeed_;
	private TreeMap<Integer, ArrayList<Vehicle>> departureVehMap; // Use this class to control the vehicle that entering
	private ConcurrentLinkedQueue<Vehicle> toAddDepartureVeh; // Tree map is not thread-safe, so use this 
	private boolean eventFlag; // Indicator whether there is an event happening on the road
	private double cachedFreeSpeed_; // For caching the speed before certain regulation events
	private ConcurrentLinkedQueue<Double> travelTimeHistory_; 
	
	// For parallel computing
	private int nShadowVehicles; // Potential vehicles might be loaded on the road
	private int nFutureRoutingVehicles; // Potential vehicles might performing routing on the road
	
	/* Public variables */
    public double totalEnergy;
    public int totalFlow;

	// Road constructor
	public Road(int id) {
		this.ID = id;
		this.lanes = new ArrayList<Lane>();
		this.nVehicles_ = new AtomicInteger(0);

		this.freeSpeed_ = GlobalVariables.FREE_SPEED; // m/s
		this.downStreamRoads = new ArrayList<Integer>();
		this.departureVehMap = new TreeMap<Integer, ArrayList<Vehicle>>();
		this.toAddDepartureVeh = new ConcurrentLinkedQueue<Vehicle>();
		this.lastUpdateHour = -1;
		this.travelTime =  this.length / this.freeSpeed_;
		this.travelTimeHistory_ = new ConcurrentLinkedQueue<Double>();
		this.neighboringZone = -1;
		this.setDistToZone(Double.MAX_VALUE);

		// For adaptive network partitioning
		this.nShadowVehicles = 0;
		this.nFutureRoutingVehicles = 0;
		this.eventFlag = false;

		// Set default value
		this.cachedFreeSpeed_ = this.freeSpeed_; 
		this.totalEnergy = 0;
		this.totalFlow = 0;
	}
	
	public Road(int id, double length) {
		this(id);
		this.length = length;
	}

	// Set the defaultFreeSpeed_
	public void setDefaultFreeSpeed() {
		this.cachedFreeSpeed_ = this.freeSpeed_;
	}

	// Get the defaultFreeSpeed_
	public double getDefaultFreeSpeed() {
		return this.cachedFreeSpeed_;
	}

	// Check the eventFlag
	public boolean checkEventFlag() {
		return this.eventFlag;
	}

	// Set the eventFlag
	public void setEventFlag() {
		this.eventFlag = true;
	}

	// Restore the eventFlag after the event
	public void restoreEventFlag() {
		this.eventFlag = false;
	}

	/* New step function using node based routing */
	// @ScheduledMethod(start=1, priority=1, duration=1)
	public void step() {
		int tickcount = ContextCreator.getCurrentTick();
		
		/* Vehicle loading */
		this.addVehicleToDepartureMap();

		/* Vehicle departure */
		int curr_size = this.departureVehMap.size();
		for (int i = 0; i < curr_size; i++) {
			Vehicle v = this.departureVehicleQueueHead();
			int departTime = v.getDepTime();
			if (v.closeToRoad(this) == 1 && tickcount >= departTime) {
				// check whether the origin is the destination
				if (v.getOriginCoord() == v.getDestCoord()) {
					this.removeVehicleFromNewQueue(departTime, v); // Remove vehicle from the waiting vehicle queue
					v.reachDest();
				} else if (v.enterNetwork(this)) {
					this.removeVehicleFromNewQueue(departTime, v);
				} else {
					break; // Vehicle cannot enter the network
				}
			} else {
				// Iterate all element in the TreeMap
				Set<Integer> keys = (Set<Integer>) this.departureVehMap.keySet();
				for (Iterator<Integer> it = (Iterator<Integer>) keys.iterator(); it.hasNext();) {
					int key =  it.next();
					ArrayList<Vehicle> temList = this.departureVehMap.get(key);
					for (Vehicle pv : temList) {
						if (tickcount >= pv.getDepTime()) {
							pv.primitiveMove();
						} else {
							break;
						}
					}
				}
				break;
			}
		}

		/* Vehicle movement */
		Vehicle currentVehicle = this.firstVehicle();
		// happened at time t, deciding acceleration and lane changing
		while (currentVehicle != null) {
			Vehicle nextVehicle = currentVehicle.macroTrailing();
			currentVehicle.calcState();
			if (tickcount % GlobalVariables.JSON_TICKS_BETWEEN_TWO_RECORDS == 0) {
				currentVehicle.recVehSnaphotForVisInterp(); // Note vehicle can be killed after calling pv.travel,
															// so we record vehicle location here!
			}
			currentVehicle = nextVehicle;
		}

		// happened during time t to t + 1, conducting vehicle movements
		currentVehicle = this.firstVehicle();
		while (currentVehicle != null) {
			Vehicle nextVehicle = currentVehicle.macroTrailing();
			if (tickcount <= currentVehicle.getAndSetLastMoveTick(tickcount)) {
				break; // Reached the end of linked list
			}
			currentVehicle.move();
			currentVehicle.updateBatteryLevel(); // Update the energy for each move
			currentVehicle.checkAtDestination();
			currentVehicle = nextVehicle;
		}
	}

	@Override
	public String toString() {
		return "Agent id: " + this.ID + " type: " + this.roadType;
	}
	
	public int getID() {
		return ID;
	}
	
	public Coordinate getStartCoord() {
		return this.coords.get(0);
	}
	
	public Coordinate getEndCoord() {
		return this.coords.get(this.coords.size()-1);
	}
	
	public void setCoords(Coordinate[] coordinates) {
		this.coords = new ArrayList<Coordinate>(Arrays.asList(coordinates));;
	}
	
	public void setCoords(ArrayList<Coordinate> coordinates) {
		this.coords = coordinates;
	}
	
	public ArrayList<Coordinate> getCoords() {
		return this.coords;
	}


	public void sortLanes() {
		Collections.sort(this.lanes, new LaneComparator());
	}

	public Node getUpStreamNode() {
		return upStreamNode;
	}
	
	public void setUpStreamNode(Node node) {
		this.upStreamNode = node;
	}
	
	public Node getDownStreamNode() {
		return downStreamNode;
	}
	
	public void setDownStreamNode(Node node) {
		this.downStreamNode = node;
	}
	
	public void setLength(double length) {
		this.length = length;
	}

	public double getLength() {
		return length;
	}

	public void addDownStreamRoad(int dsRoad) {
		if (dsRoad > 0) {
			if (!this.downStreamRoads.contains(dsRoad))
				this.downStreamRoads.add(dsRoad);
			else
				ContextCreator.logger.error("Cannot register the down stream road since it is already added");
		}
	}

	public ArrayList<Integer> getDownStreamRoads() {
		return this.downStreamRoads;
	}

	public void changeNumberOfVehicles(int nVeh) {
		if (this.nVehicles_.addAndGet(nVeh) < 0) {
			ContextCreator.logger.error("Something went wrong, the vehicle number becomes negative!");
		}
	}

	public void firstVehicle(Vehicle v) {
		if (v != null) {
			this.firstVehicle_ = v;
			v.macroLeading(null);
		} else
			this.firstVehicle_ = null;
	}

	public void lastVehicle(Vehicle v) {
		if (v != null) {
			this.lastVehicle_ = v;
			v.macroTrailing(null);
		} else
			this.lastVehicle_ = null;
	}

	public Vehicle firstVehicle() {
		return firstVehicle_;
	}

	public Vehicle lastVehicle() {
		return lastVehicle_;
	}

	/* Number of vehicles on the road */
	public int getVehicleNum() {
		return this.nVehicles_.get();
	}

	/* For adaptive network partitioning */
	public int getShadowVehicleNum() {
		return this.nShadowVehicles;
	}

	public void incrementShadowVehicleNum() {
		this.nShadowVehicles++;
	}

	public void resetShadowVehicleNum() {
		this.nShadowVehicles = 0;
	}

	public void decreaseShadowVehicleNum() {
		this.nShadowVehicles--;
		if (this.nShadowVehicles < 0)
			this.nShadowVehicles = 0;
	}

	public int getFutureRoutingVehNum() {
		return this.nFutureRoutingVehicles;
	}

	public void incrementFutureRoutingVehNum() {
		this.nFutureRoutingVehicles++;
	}

	public void resetFutureRountingVehNum() {
		this.nFutureRoutingVehicles = 0;
	}

	public void decreaseFutureRoutingVehNum() {
		this.nFutureRoutingVehicles--;
		if (this.nFutureRoutingVehicles < 0)
			this.nFutureRoutingVehicles = 0;
	}

	// This add queue using TreeMap structure
	public void addVehicleToDepartureMap() {
		for (Vehicle v = this.toAddDepartureVeh.poll(); v != null; v = this.toAddDepartureVeh.poll()) {
			int departuretime_ = v.getDepTime();
			if (!this.departureVehMap.containsKey(departuretime_)) {
				ArrayList<Vehicle> temporalList = new ArrayList<Vehicle>();
				temporalList.add(v);
				this.departureVehMap.put(departuretime_, temporalList);
			} else {
				this.departureVehMap.get(departuretime_).add(v);
			}
		}
	}

	// This add vehicle to the thread-safe pending list
	public void addVehicleToPendingQueue(Vehicle v) {
		this.toAddDepartureVeh.add(v);
	}

	/*
	 * RemoveVehicleFromNewQueue, will remove vehicle v from the TreeMap by looking
	 * at the departuretime_ of the vehicle if there are more than one vehicle with
	 * the same departuretime_, it will remove the vehicle match with id of v.
	 */
	public void removeVehicleFromNewQueue(int departureTime, Vehicle v) {
		ArrayList<Vehicle> temporalList = this.departureVehMap.get(departureTime);
		if (temporalList.size() > 1) {
			this.departureVehMap.get(departureTime).remove(v);
		} else {
			this.departureVehMap.remove(departureTime);
		}
	}

	public Vehicle departureVehicleQueueHead() {
		int firstDeparture_ = this.departureVehMap.firstKey();
		return this.departureVehMap.get(firstDeparture_).get(0);
	}

	public double getFreeSpeed() {
		return this.freeSpeed_;
	}
	
	// Assume the car-following speed is normally distributed based on
	// Wagner, P. (2012). Analyzing fluctuations in car-following. Transportation research part B: methodological, 46(10), 1384-1392.
	// From Figure 2 and 4, it can be seen that when speed is less than 30 m/s the speed distribution follows a normal pdf with std around 0.5
	public double getRandomFreeSpeed(double coef) {
		return  Math.max(
				this.freeSpeed_ + coef * 0.5, 0);
	}

	public double calcSpeed() {
		return  Math.max((this.length/(this.travelTime + 1)), 0.0001); // +1s to avoid divide 0
	}

	/**
	 * This function set the current travel time of the road based on historical records
	 * 
	 * @author Zhan & Hemant
	 */
	public boolean setTravelTime() {
		// for output travel times
		double newTravelTime;
		if(travelTimeHistory_.size()>0) {
			double sum = 0;
			int num = 0;
			for(double d: travelTimeHistory_) {
				sum += d;
				num++;
			}
			if(num>0) {
				newTravelTime = GlobalVariables.SIMULATION_STEP_SIZE * sum / num;
			}
			else {
				newTravelTime = this.length / this.calcSpeed();
			}
			travelTimeHistory_.clear();
		}
		else {
			newTravelTime =  this.length / this.freeSpeed_;
		}
		
		if(this.travelTime == newTravelTime) return false;
		else {
			this.travelTime = newTravelTime;
			return true;
		}
	}

	public double getTravelTime() {
		return this.travelTime;
	}

	public Lane getLane(int i) {
		return this.lanes.get(i);
	}

	public int getLaneIndex(Lane l) {
		return this.lanes.indexOf(l);
	}

	public void addLane(Lane l) {
		this.lanes.add(l);
	}

	public ArrayList<Lane> getLanes() {
		return this.lanes;
	}

	public int getNumberOfLanes() {
		return this.lanes.size();
	}

	public Lane firstLane() {
		Lane firstLane = null;
		int rightmost = this.getLanes().size() - 1;
		firstLane = this.getLane(rightmost);
		return firstLane;
	}

	public void printRoadInfo() {
		ContextCreator.logger.info("Road: " + this.getID() + " has lanes from left to right as follow: ");
		for (int i = 0; i < this.lanes.size(); i++) {
			ContextCreator.logger.info(this.lanes.get(i).getID() + " with Repast ID: " + this.lanes.get(i).getID());
		}
	}

	public void printRoadCoordinate() {
		Geometry roadGeom = ContextCreator.getRoadGeography().getGeometry(this);
		Coordinate start = roadGeom.getCoordinates()[0];
		Coordinate end = roadGeom.getCoordinates()[roadGeom.getNumPoints() - 1];
		ContextCreator.logger.info("Coordinate of road: " + this.getID());
		ContextCreator.logger.info("Starting point: " + start);
		ContextCreator.logger.info("Ending point: " + end);
	}

	/*
	 * Wenbo: update background traffic through speed file. if road event flag is
	 * true, just pass to default free speed, else, update link free flow speed
	 */
	public void updateFreeFlowSpeed() {
		// Get current tick
		int hour = (int) Math.floor(ContextCreator.getCurrentTick() / GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL);
		hour = hour % GlobalVariables.HOUR_OF_SPEED;
		// each hour set events
		if (this.lastUpdateHour < hour) {
			double value = ContextCreator.backgroundTraffic.getBackgroundTraffic(this.ID, hour)* 0.44694; // mph to m/s
			if (this.checkEventFlag()) {
				this.setDefaultFreeSpeed();
			} else {
				this.freeSpeed_ = value;
			}
		}
		this.lastUpdateHour = hour;
	}

	/* Modify the free flow speed based on the events */
	public void updateFreeFlowSpeed(double newFFSpd) {
		this.freeSpeed_ = newFFSpd * 0.44694; // HG: convert from mph to m/s
	}

	public void recordEnergyConsumption(Vehicle v) {
		this.totalFlow += 1;
		if (v.getVehicleClass() == 1) { // EV
			ElectricTaxi ev = (ElectricTaxi) v;
			this.totalEnergy += ev.getLinkConsume();
			ev.resetLinkConsume();
		} else if (v.getVehicleClass() == 2) {
			ElectricBus bv = (ElectricBus) v;
			this.totalEnergy += bv.getLinkConsume();
		}
	}

	public double getTotalEnergy() {
		return totalEnergy;
	}

	public int getTotalFlow() {
		return totalFlow;
	}

	public void setRoadType(int roadType) {
		this.roadType = roadType;
	}
	
	public int getRoadType() {
		return roadType;
	}
	
	public void recordTravelTime(Vehicle v) {
		this.travelTimeHistory_.add(v.getLinkTravelTime());
		v.resetLinkTravelTime();
	}

	public int getNeighboringZone() {
		return neighboringZone;
	}

	public void setNeighboringZone(int neighboringZone) {
		this.neighboringZone = neighboringZone;
	}

	public double getDistToZone() {
		return distToZone;
	}

	public void setDistToZone(double distToZone) {
		this.distToZone = distToZone;
	}

	public int getUpStreamJunction() {
		return upStreamJunction;
	}

	public void setUpStreamJunction(int upStreamJunction) {
		this.upStreamJunction = upStreamJunction;
	}

	public int getDownStreamJunction() {
		return downStreamJunction;
	}

	public void setDownStreamJunction(int downStreamJunction) {
		this.downStreamJunction = downStreamJunction;
	}
}
