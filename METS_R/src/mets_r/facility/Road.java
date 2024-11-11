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
import mets_r.mobility.ElectricVehicle;
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
	
	public final static int CoSim = 1;
	
	public final static int NONE_OF_THE_ABOVE = -1;
	
	/* Private variables */
	private int ID;
	private String origID;
	private int roadType = NONE_OF_THE_ABOVE;
	private int controlType = NONE_OF_THE_ABOVE;
	private double length;
	private ArrayList<Coordinate> coords;
	
	// Connection with other facilities
	private ArrayList<Lane> lanes; // Lanes inside the road
	private Node upStreamNode;
	private Node downStreamNode;
	private ArrayList<Integer> downStreamRoads; // Includes the opposite link in U-turn
	private int upStreamJunction;
	private int downStreamJunction;
	private int neighboringDepartureZone;
	private int neighboringArrivalZone;
	private double distToDepartureZone;
	private double distToArrivalZone;
	
	// For vehicle movement
	private int lastUpdateHour; // To find the current hour of the simulation
	private AtomicInteger nVehicles_; // Number of vehicles currently in the road
	private Vehicle lastVehicle_; // Vehicle stored as a linked list
	private Vehicle firstVehicle_;
	private double travelTime;
	private TreeMap<Integer, ArrayList<Vehicle>> departureVehMap; // Use this class to control the vehicle that entering
	private ConcurrentLinkedQueue<Vehicle> toAddDepartureVeh; // Tree map is not thread-safe, so use this 
	private boolean eventFlag; // Indicator whether there is an event happening on the road
	private double speedLimit_; // Speed for travel time estimation
	private double cachedSpeedLimit_; // For caching the speed before certain regulation events
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
		this.origID = Integer.toString(id);
		this.lanes = new ArrayList<Lane>();
		this.nVehicles_ = new AtomicInteger(0);
		this.speedLimit_ = 31.2928; // m/s, 70 mph
		this.downStreamRoads = new ArrayList<Integer>();
		this.departureVehMap = new TreeMap<Integer, ArrayList<Vehicle>>();
		this.toAddDepartureVeh = new ConcurrentLinkedQueue<Vehicle>();
		this.lastUpdateHour = -1;
		this.travelTime =  this.length / this.speedLimit_;
		this.travelTimeHistory_ = new ConcurrentLinkedQueue<Double>();
		this.neighboringDepartureZone = -1;
		this.neighboringArrivalZone = -1;
		this.setDistToZone(Double.MAX_VALUE, false);
		this.setDistToZone(Double.MAX_VALUE, true);

		// For adaptive network partitioning
		this.nShadowVehicles = 0;
		this.nFutureRoutingVehicles = 0;
		this.eventFlag = false;

		// Set default value
		this.cachedSpeedLimit_ = this.speedLimit_; 
		this.totalEnergy = 0;
		this.totalFlow = 0;
	}
	
	public Road(int id, double length) {
		this(id);
		this.length = length;
	}

	// Set the defaultFreeSpeed_
	public void cacheSpeedLimit() {
		this.cachedSpeedLimit_ = this.speedLimit_;
	}

	// Get the speed limit
	public double getSpeedLimit() {
		return this.speedLimit_;
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
		
		/* Log vehicle states */
		Vehicle currentVehicle = this.firstVehicle();
		while (currentVehicle != null) {
			Vehicle nextVehicle = currentVehicle.macroTrailing();
			currentVehicle.reportStatus();
			if (tickcount % GlobalVariables.JSON_TICKS_BETWEEN_TWO_RECORDS == 0) {
				currentVehicle.recVehSnaphotForVisInterp(); // Note vehicle can be killed after calling pv.travel,
															// so we record vehicle location here!
			}
			currentVehicle = nextVehicle;
		}

		/* Vehicle movement */
		if(this.getControlType() == Road.CoSim) {
			ContextCreator.logger.debug("Skipped vehicle movements for the coSim road with origin ID: " + this.getOrigID());
		}
		else {
			currentVehicle = this.firstVehicle();
			// happened at time t, deciding acceleration and lane changing
			while (currentVehicle != null) {
				Vehicle nextVehicle = currentVehicle.macroTrailing();
				if (tickcount> currentVehicle.getAndSetLastVisitTick(tickcount)) {
					currentVehicle.calcState();
				}
				currentVehicle = nextVehicle;
			}
	
			// happened during time t to t + 1, conducting vehicle movements
			currentVehicle = this.firstVehicle();
			while (currentVehicle != null) {
				Vehicle nextVehicle = currentVehicle.macroTrailing();
				if ((tickcount == currentVehicle.getLastVisitTick()) && (tickcount > currentVehicle.getAndSetLastMoveTick(tickcount))) { // vehicle has not been visited yet
					currentVehicle.move();
					currentVehicle.updateBatteryLevel(); // Update the energy for each move
				}
				currentVehicle = nextVehicle;
			}
		}
	}
	
	/**
	 * Insert vehicle to a specific location.
	 * 
	 * This function would not check the collision issue 
	 * since it is used for synchronize the vehicle information
	 * from other sources and the "collision" could just be
	 * caused by the order of vehicle updates.
	 */
	public boolean insertVehicle(Vehicle veh, Lane lane, double dist, double x, double y) {
		if (veh.getRoad() == this) {
			if (veh.getLane() == lane) // Case 1, veh's road is this road and this lane, check overtake issue, change its dist
			{
//				if(veh.leading() != null) {
//					if(veh.leading().getDistance() + veh.leading().length() >= dist) {
//						// Overtaking in the same lane
//						ContextCreator.logger.error("Overtaking " +veh.leading().getID() + " in the same lane, this veh: " + veh.getID() + ", front veh dist: " + veh.leading().getDistance() + ", this veh dist: " + dist);
//						return false;
//					}
//				}
//				if(veh.trailing() != null) {
//					if(veh.trailing().getDistance() <= dist + veh.length()) {
//						// Overtaking in the same lane
//						ContextCreator.logger.error("Overpassing " +veh.trailing().getID() + " in the same lane, this veh: " + veh.getID() + ", behind veh dist: " + veh.trailing().getDistance() + ", this veh dist: " + dist);
//						return false;
//					}
//				}
				veh.setDistance(dist);
				// Move veh to the x and y location
				veh.setCurrentCoord(new Coordinate(x, y));
				// Advance vehicle in the lane
				veh.advanceInMacroList();
			}
			else { // Case 2, veh's road is this road but not the same lane, find the leading and trailing veh
				Vehicle leadVehicle = null;
				Vehicle lagVehicle = null;
				
				Vehicle toCheckVeh = lane.firstVehicle();
				while (toCheckVeh != null) { // find where to insert the veh
					ContextCreator.logger.info("HERE4");
					 if(toCheckVeh.getDistance()<=dist) {
//						 if(toCheckVeh.getDistance() + toCheckVeh.length() > dist) {
//							 ContextCreator.logger.error("Front collision during lane changing between " + toCheckVeh.getID() + " and " + veh.getID() + ", front veh dist: " + toCheckVeh.getDistance() + ", this veh dist: " + dist);
//							 return false;
//						 }
						 leadVehicle = toCheckVeh;
						 toCheckVeh = toCheckVeh.trailing();
					 }
					 else {
//						 if(toCheckVeh.getDistance() < dist + veh.length()) {
//							 ContextCreator.logger.error("Behind collision during lane changing between " + toCheckVeh.getID() + " and " + veh.getID() + ", behind veh dist: " + toCheckVeh.getDistance() + ", this veh dist: " + dist);
//							 return false;
//						 }
						 lagVehicle = toCheckVeh;
						 break;
					 }
				}
				veh.removeFromLane();
				veh.setDistance(dist);
				// Move veh to the x and y location
				veh.setCurrentCoord(new Coordinate(x, y));
				veh.insertToLane(lane, leadVehicle, lagVehicle);
					
				// Insert the veh to the prop macroList loc, find the macroleading and trailing veh
				veh.advanceInMacroList();
			}
			
//			if(dist<=1e-8) { // change road
//				veh.changeRoad();
//			}
		}
		else {
			if ((veh.getNextRoad()!=null) && (veh.getNextRoad().getID() == this.getID())) {
				veh.changeRoad();
			}
			else {
				ContextCreator.logger.error("Veh " + veh.getID() + "is on road " + veh.getRoad().getID() + " is not " + this.getID());
				return false;
			}
		}
		
		veh.getAndSetLastMoveTick(ContextCreator.getCurrentTick());
		return true;
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
		if (this.length <= 0){ // no length is provided
		    for(Lane lane: this.lanes) {
		    	this.length += lane.getLength();
		    }
		    this.length /= this.lanes.size();
		}
		return this.length;
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

	public double calcSpeed() {
		return  Math.max((this.length/(this.travelTime + 1)), 0.0001); // +1s to avoid divide 0
	}

	/**
	 * This function set the current travel time of the road based on historical records
	 * 
	 * @author Zhan & Hemant
	 */
	public boolean updateTravelTimeEstimation() {
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
			newTravelTime =  this.length / this.speedLimit_;
		}
		
		if(this.travelTime == newTravelTime) {
			return false;
		}
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
	
	public void addLane(Lane l, int index) {
		this.lanes.add(index, l);
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
	 * Update background traffic through a speed file. if road event flag is
	 * true, just pass to cached speed limit, otherwise, update link free flow speed
	 */
	public void updateFreeFlowSpeed() {
		// Get current tick
		int hour = (int) Math.floor(ContextCreator.getCurrentTick() / GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL);
		// each hour set events
		if (this.lastUpdateHour < hour) {
			for(Lane lane: this.getLanes()) {
				double new_speed = ContextCreator.background_traffic.getBackgroundTraffic(this.ID, hour);
				if(new_speed > 0) lane.setSpeed(new_speed * 0.44694);
			}
			
			if (this.checkEventFlag()) {
				this.cacheSpeedLimit();
			} else {
				this.speedLimit_ =  this.cachedSpeedLimit_;
			}
		}
		this.lastUpdateHour = hour;
	}

	/* Modify the free flow speed based on the events */
	public void updateFreeFlowSpeed(double newFFSpd) {
		this.speedLimit_ = newFFSpd * 0.44694; // Convert from mph to m/s
	}

	public void recordEnergyConsumption(Vehicle v) {
		this.totalFlow += 1;
		if (v.getVehicleClass() == Vehicle.EV) { // Private
			ElectricVehicle ev = (ElectricVehicle) v;
			this.totalEnergy += ev.getLinkConsume();
			ev.resetLinkConsume();
		}
		else if(v.getVehicleClass() == Vehicle.ETAXI) { // EV Taxi
			ElectricTaxi ev = (ElectricTaxi) v;
			this.totalEnergy += ev.getLinkConsume();
			if(GlobalVariables.ENABLE_ECO_ROUTING_EV) {
				ContextCreator.kafkaManager.produceLinkEnergy(ev.getID(), ev.getVehicleClass(), this.getID(),
						ev.getLinkConsume());
			}
			ev.resetLinkConsume();
		} else if (v.getVehicleClass() == Vehicle.EBUS) {
			ElectricBus bv = (ElectricBus) v;
			this.totalEnergy += bv.getLinkConsume();
			if(GlobalVariables.ENABLE_ECO_ROUTING_BUS) {
				ContextCreator.kafkaManager.produceLinkEnergy(bv.getID(), bv.getVehicleClass(), this.getID(),
						bv.getLinkConsume());
			}
			bv.resetLinkConsume();
		}
	}

	public double getTotalEnergy() {
		return totalEnergy;
	}

	public int getTotalFlow() {
		return totalFlow;
	}

	public void setRoadType(int roadType) {
		// update speed limit
		switch(roadType) {
			case Road.Street:
				this.speedLimit_ = GlobalVariables.STREET_SPEED * 0.44694; 
				break;
			case Road.Highway:
				this.speedLimit_ = GlobalVariables.HIGHWAY_SPEED * 0.44694; 
				break;
			case Road.Bridge:
				this.speedLimit_ = GlobalVariables.BRIDGE_SPEED * 0.44694; 
				break;
			case Road.Tunnel:
				this.speedLimit_ = GlobalVariables.TUNNEL_SPEED * 0.44694; 
				break;
			case Road.Driveway:
				this.speedLimit_ = GlobalVariables.DRIVEWAY_SPEED * 0.44694; 
				break;
			case Road.Ramp:
				this.speedLimit_ = GlobalVariables.RAMP_SPEED * 0.44694; 
				break;
			case Road.U_Turn:
				this.speedLimit_ = GlobalVariables.UTURN_SPEED * 0.44694; 
				break;
			default:
				this.speedLimit_ = GlobalVariables.STREET_SPEED * 0.44694; 
				break;
		}
		this.roadType = roadType;
	}
	
	public int getRoadType() {
		return roadType;
	}
	
	public void setControlType(int controlType) {
		this.controlType = controlType;
	}
	
	public int getControlType() {
		return controlType;
	}

	public void recordTravelTime(Vehicle v) {
		this.travelTimeHistory_.add(v.getLinkTravelTime());
		if (GlobalVariables.ENABLE_ECO_ROUTING_BUS) {
			ContextCreator.kafkaManager.produceLinkTravelTime(v.getID(), v.getVehicleClass(), this.getID(),
					v.getLinkTravelTime(), this.getLength());
		}
		v.resetLinkTravelTime();
	}

	public int getNeighboringZone(boolean goDest) {
		if(goDest) return neighboringArrivalZone;
		else return neighboringDepartureZone;
	}

	public void setNeighboringZone(int neighboringZone, boolean goDest) {
		if (goDest) this.neighboringDepartureZone = neighboringArrivalZone;
		else this.neighboringDepartureZone = neighboringZone;
	}

	public double getDistToZone(boolean goDest) {
		if (goDest) return distToArrivalZone;
		else return distToDepartureZone;
	}

	public void setDistToZone(double distToZone, boolean goDest) {
		if (goDest) this.distToArrivalZone = distToZone; 
		else this.distToDepartureZone = distToZone;
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

	public void setSpeedLimit(double speedLimit) {
		this.speedLimit_ = speedLimit;
	}
	
	public String getOrigID() {
		return this.origID;
	}
	
	public void setOrigID(String newID) {
		this.origID = newID;
	}

}
