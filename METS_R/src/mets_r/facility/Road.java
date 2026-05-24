package mets_r.facility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.vividsolutions.jts.geom.Coordinate;
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
	
	public final static int COSIM = 1;
	
	public final static int NONE_OF_THE_ABOVE = -1;

	private static final double DEFAULT_STREET_PARKING_CAPACITY_PER_METER = 0.115;
	private static final double DEFAULT_STREET_PARKING_MAX_SPEED_MPS = 30.0 * 0.44694;
	
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
	private ArrayList<Road> upStreamRoads;
	private ArrayList<Integer> downStreamRoads; // Includes the opposite link in U-turn
	private int upStreamJunction;
	private int downStreamJunction;
	private int neighboringDepartureZone;
	private int neighboringArrivalZone;
	private double distToDepartureZone;
	private double distToArrivalZone;
	
	private boolean _canBeOrigin;
	private boolean _canBeDest;
	
	// For vehicle movement
	private int lastUpdateHour; // To find the current hour of the simulation
	private AtomicInteger nVehicles_; // Number of vehicles currently in the road
	private volatile int parking_capacity; // Maximum number of parked vehicles this road provides
	private boolean parkingCapacityExplicitlySet;
	private AtomicInteger parked_num; // Number of vehicles currently parked on this road
	private volatile boolean parkingStateDirty;
	private int prevParkingCapacity;
	private int prevParkedNum;
	private Vehicle lastVehicle_; // Vehicle stored as a linked list
	private Vehicle firstVehicle_;
	private Vehicle prevFirstVehicle; // For parallel computing
	private double travelTime;
	private TreeMap<Integer, ArrayList<Vehicle>> departureVehMap; // Use this class to control the vehicle that entering
	private ConcurrentLinkedQueue<Vehicle> toAddDepartureVeh; // Tree map is not thread-safe, so use this 
	private boolean eventFlag; // Indicator whether there is an event happening on the road
	private double speedLimit_; // Speed for travel time estimation
	private double cachedSpeedLimit_; // For caching the speed before certain regulation events
	private double travelTimeSum;
	private int travelTimeCount;
	
	// For parallel computing - AtomicInteger for thread safety since these are
	// modified by setShadowImpact/clearShadowImpact from parallel road step threads
	// and read by the METIS partitioner for edge weight computation.
	private AtomicInteger nShadowVehicles;
	private AtomicInteger nFutureRoutingVehicles;
	
	/* Public variables */
	public double currentEnergy;
    public double totalEnergy;
    public int currentFlow;
    public int totalFlow;
    public int prevFlow;

	// Road constructor
	public Road(int id) {
		this.ID = id;
		this.origID = Integer.toString(id);
		this.lanes = new ArrayList<Lane>();
		this.nVehicles_ = new AtomicInteger(0);
		this.parking_capacity = 0;
		this.parkingCapacityExplicitlySet = false;
		this.parked_num = new AtomicInteger(0);
		this.parkingStateDirty = false;
		this.prevParkingCapacity = 0;
		this.prevParkedNum = 0;
		this.speedLimit_ = 31.2928; // m/s, 70 mph
		this.downStreamRoads = new ArrayList<Integer>();
		this.departureVehMap = new TreeMap<Integer, ArrayList<Vehicle>>();
		this.toAddDepartureVeh = new ConcurrentLinkedQueue<Vehicle>();
		this.lastUpdateHour = -1;
		this.travelTime =  this.length / this.speedLimit_;
		this.travelTimeSum = 0.0;
		this.travelTimeCount = 0;
		this.neighboringDepartureZone = 0;
		this.neighboringArrivalZone = 0;
		this.distToArrivalZone = Double.MAX_VALUE;
		this.distToDepartureZone = Double.MAX_VALUE;
		
		this._canBeDest = true;
		this._canBeOrigin = true;
		
		this.upStreamRoads = new ArrayList<Road>(); // Sort by priority

		// For adaptive network partitioning
		this.nShadowVehicles = new AtomicInteger(0);
		this.nFutureRoutingVehicles = new AtomicInteger(0);
		this.eventFlag = false;

		// Set default value
		this.cachedSpeedLimit_ = this.speedLimit_; 
		this.totalEnergy = 0;
		this.totalFlow = 0;
		this.prevFlow = 0;
		this.currentEnergy = 0;
		this.currentFlow = 0;
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
	// Scheduling step
	public void stepPart1() {
		if (ContextCreator.getRoadContext().get(this.getID()) != this) return;

		int tickcount = ContextCreator.getCurrentTick();
		addVehicleToDepartureMap();
		
		/* Log all vehicle states */
		Vehicle currentVehicle = this.firstVehicle();
		this.prevFirstVehicle = currentVehicle;
		if (this.prevFirstVehicle != null) this.prevFirstVehicle.recordPrevState();
		boolean shouldReportStatus = GlobalVariables.V2X;
		boolean shouldRecordSnapshot = GlobalVariables.ENABLE_DATA_COLLECTION
				&& tickcount % GlobalVariables.JSON_TICKS_BETWEEN_TWO_RECORDS == 0;
		while (currentVehicle != null) {
			Vehicle nextVehicle = currentVehicle.macroTrailing();
			try {
				if (shouldReportStatus) {
					currentVehicle.reportStatus();
				}
				if (shouldRecordSnapshot) {
					currentVehicle.recVehSnaphotForVisInterp(); // Note vehicle can be killed after calling pv.travel,
																// so we record vehicle location here!
				}
			} catch (Throwable ex) {
				ContextCreator.logger.error("Road.stepPart1 status/snapshot failed road=" + this.ID
						+ " vehicle=" + currentVehicle.getID(), ex);
			}
			currentVehicle = nextVehicle;
		}
		if (shouldRecordSnapshot) {
			try {
				ContextCreator.dataCollector.recordLinkSnapshot(this);
			} catch (Throwable ex) {
				ContextCreator.logger.error("Road.stepPart1 link snapshot failed road=" + this.ID, ex);
			}
		}

		/* Vehicle departure */
		if (this.getControlType() != Road.COSIM) {
			while (!this.departureVehMap.isEmpty()) {
			    Vehicle v = this.departureVehicleQueueHead();
			    if (v == null) break;
			    try {
			    	int departTime = v.getDepTime();
				    if (tickcount >= departTime) {
				        boolean busTrip = (v.getVehicleClass() == Vehicle.EBUS);
				        if ((busTrip && (v.getOriginID() == v.getDestID()))) {
				            this.removeVehicleFromNewQueue(departTime, v);
				            v.reachDest();
				        } else if(v.enterNetwork(this)) {
				        	this.removeVehicleFromNewQueue(departTime, v);
				        }
				        else {
				            break; // Network is full, stop processing departures
				        }
				    } else {
				        break; // Reached vehicles scheduled for future ticks
				    }
			    } catch (Throwable ex) {
				    ContextCreator.logger.error("Road.stepPart1 departure failed road=" + this.ID
						    + " vehicle=" + v.getID(), ex);
				    break;
			    }
			}
		}

		/* Vehicle decision uses three-phase approach to avoid stale acceleration after lane changes */
		if(!(this.getControlType() == Road.COSIM)) {
			if (this.firstVehicle_ == null) return;

			boolean usesLaneChangeAdvice = "LC2013".equals(GlobalVariables.LANE_CHANGING_MODEL);
			if (usesLaneChangeAdvice) {
				currentVehicle = this.firstVehicle();
				while (currentVehicle != null) {
					Vehicle nextVehicle = currentVehicle.macroTrailing();
					currentVehicle.resetLaneChangeRuntimeState();
					currentVehicle = nextVehicle;
				}
			}

			// Phase 1: lane-changing decisions for all vehicles
			currentVehicle = this.firstVehicle();
			while (currentVehicle != null) {
				Vehicle nextVehicle = currentVehicle.macroTrailing();
				if (currentVehicle.isDormantOnRoad()) {
					currentVehicle = nextVehicle;
					continue;
				}
				try {
					currentVehicle.calcLaneChangingState(tickcount);
				} catch (Throwable ex) {
					ContextCreator.logger.error("Road.stepPart1 lane-change failed road=" + this.ID
							+ " vehicle=" + currentVehicle.getID(), ex);
				}
				currentVehicle = nextVehicle;
			}

			// Phase 2: repair macro list ordering after all lane changes
			List<Vehicle> vehicleBuffer = new ArrayList<>(Math.max(0, this.nVehicles_.get()));
			currentVehicle = this.firstVehicle();

			// 1. Create a static snapshot of the vehicles currently on the road
			while (currentVehicle != null) {
			    vehicleBuffer.add(currentVehicle);
			    currentVehicle = currentVehicle.macroTrailing();
			}

			// 2. Iterate through the buffered list to safely apply macro list repairs
			for (Vehicle v : vehicleBuffer) {
				if (v.isDormantOnRoad()) {
					continue;
				}
				try {
				    v.advanceInMacroList();
				    v.retreatInMacroList();
				    v.advanceInLaneList();
				} catch (Throwable ex) {
					ContextCreator.logger.error("Road.stepPart1 list repair failed road=" + this.ID
							+ " vehicle=" + v.getID(), ex);
				}
			}

			// Phase 3: acceleration decisions (now with correct leading vehicles)
			currentVehicle = this.firstVehicle();
			while (currentVehicle != null) {
				Vehicle nextVehicle = currentVehicle.macroTrailing();
				if (currentVehicle.isDormantOnRoad()) {
					currentVehicle = nextVehicle;
					continue;
				}
				try {
					currentVehicle.calcAccState();
				} catch (Throwable ex) {
					ContextCreator.logger.error("Road.stepPart1 acceleration failed road=" + this.ID
							+ " vehicle=" + currentVehicle.getID(), ex);
					currentVehicle.ensureAccelerationPlan(0.0);
				}
				currentVehicle = nextVehicle;
			}
		}
	}

	// Realization step
	public void stepPart2() {
		if (ContextCreator.getRoadContext().get(this.getID()) != this) return;

		/* Vehicle movement */
		if(!(this.getControlType() == Road.COSIM)) {
			Vehicle currentVehicle = this.firstVehicle();
			
			// happened during time t to t + 1, conducting vehicle movements
			while (currentVehicle != null) {
				Vehicle nextVehicle = currentVehicle.macroTrailing();
				if (currentVehicle.isDormantOnRoad()) {
					currentVehicle = nextVehicle;
					continue;
				}
				try {
					currentVehicle.move();
					currentVehicle.updateBatteryLevel(); // Update the energy for each move
				} catch (Throwable ex) {
					ContextCreator.logger.error("Road.stepPart2 movement failed road=" + this.ID
							+ " vehicle=" + currentVehicle.getID(), ex);
					currentVehicle.ensureAccelerationPlan(0.0);
				}
				currentVehicle = nextVehicle;
			}
		}
	}
	
	/**
	 * Teleport vehicle for trace-based replay
	 * 
	 * This function would not check the collision issue 
	 * since it is used for synchronize the vehicle information
	 * from other sources and the "collision" could just be
	 * caused by the order of vehicle updates.
	 */
	public void teleportVehicle(Vehicle veh, Lane lane, double dist) { 
		if (veh.getRoad() != this) {
			veh.appendToRoad(this);
		}
		
		// Move veh to the x and y location
		veh.teleportToLane(lane, dist);
		
		// Insert the veh to the proper macroList loc, find the macroleading and trailing veh
		veh.advanceInMacroList();
	}

	@Override
	public String toString() {
		return "Agent id: " + this.ID + " type: " + this.roadType;
	}
	
	public int getID() {
		return ID;
	}
	
	public Coordinate getStartCoord() {
		Coordinate first_coord = this.coords.get(0);
		return new Coordinate(first_coord.x, first_coord.y,
				Double.isNaN(first_coord.z) ? 0.0 : first_coord.z);
	}
	
	public Coordinate getEndCoord() {
		Coordinate last_coord = this.coords.get(this.coords.size()-1);
		return new Coordinate(last_coord.x, last_coord.y,
				Double.isNaN(last_coord.z) ? 0.0 : last_coord.z);
	}
	
	public void setCoords(Coordinate[] coordinates) {
		this.coords = new ArrayList<Coordinate>(Arrays.asList(coordinates));
	}
	
	public void setCoords(ArrayList<Coordinate> coordinates) {
		this.coords = coordinates;
	}

	
	public ArrayList<Coordinate> getCoords() {
		// Deep copy to avoid being modified somewhere
		ArrayList<Coordinate> res = new ArrayList<Coordinate>();
		for(Coordinate coord: this.coords) {
			Coordinate coord2 = new Coordinate();
			coord2.x = coord.x;
			coord2.y = coord.y;
			coord2.z = coord.z;
			res.add(coord2);
		}
		return res;
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
		this.refreshDefaultParkingCapacity();
	}

	public double getLength() {
		if (this.length <= 0 && this.lanes.size() > 0){ // no length is provided
		    for(Lane lane: this.lanes) {
		    	this.length += lane.getLength();
		    }
		    this.length /= this.lanes.size();
		}
		this.refreshDefaultParkingCapacity();
		return this.length;
	}

	public void addDownStreamRoad(int dsRoad) {
		if (!this.downStreamRoads.contains(dsRoad))
			this.downStreamRoads.add(dsRoad);
	}

	public void removeDownStreamRoad(int dsRoad) {
		this.downStreamRoads.remove(Integer.valueOf(dsRoad));
	}
	
	public void addUpStreamRoad(Road usRoad, int priority) { // priority: 0 - straight, 1 - right turn, 2 - left turn
		if (!this.upStreamRoads.contains(usRoad)) {
			this.upStreamRoads.add(Math.min(this.upStreamRoads.size(), priority), usRoad);
		}
	}

	public void removeUpStreamRoad(Road usRoad) {
		this.upStreamRoads.remove(usRoad);
	}

	public ArrayList<Integer> getDownStreamRoads() {
		return this.downStreamRoads;
	}
	
	public ArrayList<String> getDownStreamRoadOrigIDs() {
		ArrayList<String> res = new ArrayList<String>();
		for(int rid: this.downStreamRoads) {
			res.add(ContextCreator.getRoadContext().get(rid).getOrigID());
		}
		return res;
	}
	
	public boolean canBeOrigin() {
		return this._canBeOrigin;
	}
	
	public boolean canBeDest(){
		return this._canBeDest;
	}
	
	public void setCanBeOrigin(Boolean b) {
		this._canBeOrigin = b;
	}
	
	public void setCanBeDest(Boolean b) {
		this._canBeDest = b;
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
	
	public Vehicle prevFirstVehicle() {
		return this.prevFirstVehicle;
	}

	public Vehicle lastVehicle() {
		return lastVehicle_;
	}

	/* Number of vehicles on the road */
	public int getVehicleNum() {
		return this.nVehicles_.get();
	}

	public synchronized int getPendingDepartureVehicleNum() {
		int count = this.toAddDepartureVeh.size();
		for (ArrayList<Vehicle> queue : this.departureVehMap.values()) {
			if (queue != null) {
				count += queue.size();
			}
		}
		return count;
	}

	public int getStepLoadWeight() {
		long weight = 1L
				+ (long) this.getVehicleNum() * Math.max(1, GlobalVariables.PART_ALPHA)
				+ (long) this.getPendingDepartureVehicleNum() * Math.max(1, GlobalVariables.PART_ALPHA)
				+ (long) this.getShadowVehicleNum() * Math.max(1, GlobalVariables.PART_BETA)
				+ (long) this.getFutureRoutingVehNum() * Math.max(1, GlobalVariables.PART_GAMMA);
		return weight >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) weight;
	}

	public int getParkingCapacity() {
		return this.parking_capacity;
	}

	public synchronized void setParkingCapacity(int parkingCapacity) {
		this.parkingCapacityExplicitlySet = true;
		int newCapacity = Math.max(0, parkingCapacity);
		if (this.parking_capacity != newCapacity) {
			this.parking_capacity = newCapacity;
			this.markParkingStateChanged();
		}
	}

	private void refreshDefaultParkingCapacity() {
		if (this.parkingCapacityExplicitlySet) {
			return;
		}
		int newCapacity;
		if (this.roadType != Road.Street || this.speedLimit_ >= DEFAULT_STREET_PARKING_MAX_SPEED_MPS
				|| Double.isNaN(this.speedLimit_) || Double.isInfinite(this.speedLimit_)
				|| this.length <= 0 || Double.isNaN(this.length) || Double.isInfinite(this.length)) {
			newCapacity = 0;
		} else {
			newCapacity = Math.max(0,
					(int) Math.floor(this.length * DEFAULT_STREET_PARKING_CAPACITY_PER_METER));
		}
		if (this.parking_capacity != newCapacity) {
			this.parking_capacity = newCapacity;
			this.markParkingStateChanged();
		}
	}

	public int getParkedNum() {
		return this.parked_num.get();
	}

	public void setParkedNum(int parkedNum) {
		int newParkedNum = Math.max(0, parkedNum);
		if (this.parked_num.getAndSet(newParkedNum) != newParkedNum) {
			this.markParkingStateChanged();
		}
	}

	public boolean hasParkingSpace() {
		return this.parked_num.get() < this.parking_capacity;
	}

	public boolean tryAddParkedVehicle() {
		while (true) {
			int currentParked = this.parked_num.get();
			if (currentParked >= this.parking_capacity) return false;
			if (this.parked_num.compareAndSet(currentParked, currentParked + 1)) {
				this.markParkingStateChanged();
				return true;
			}
		}
	}

	public boolean addOneParkedVehicle() {
		return this.tryAddParkedVehicle();
	}

	public void removeOneParkedVehicle() {
		int val = this.parked_num.decrementAndGet();
		if (val < 0) {
			ContextCreator.logger.error(this.ID + " road parking out of stock, parked_num: " + val);
			this.parked_num.compareAndSet(val, 0);
		}
		this.markParkingStateChanged();
	}

	private void markParkingStateChanged() {
		this.parkingStateDirty = true;
		ContextCreator.dataCollector.recordRoadParkingStateChange(this);
	}

	public boolean hasActiveVehicles() {
		if (this.nVehicles_.get() > 0 || this.firstVehicle_ != null || this.lastVehicle_ != null
				|| !this.departureVehMap.isEmpty() || !this.toAddDepartureVeh.isEmpty()) {
			return true;
		}
		for (Lane lane : this.lanes) {
			if (lane.nVehicles() > 0) return true;
		}
		return false;
	}

	public void restoreRuntimeState(double restoredTravelTime, double restoredSpeedLimit,
			double restoredCurrentEnergy, double restoredTotalEnergy, int restoredCurrentFlow,
			int restoredTotalFlow, int restoredPrevFlow, int restoredControlType,
			int restoredParkingCapacity, int restoredParkedNum) {
		this.lastUpdateHour = -1;
		this.nVehicles_.set(0);
		this.firstVehicle_ = null;
		this.lastVehicle_ = null;
		this.prevFirstVehicle = null;
		this.departureVehMap.clear();
		this.toAddDepartureVeh.clear();
		this.eventFlag = false;
		this.speedLimit_ = restoredSpeedLimit;
		this.cachedSpeedLimit_ = restoredSpeedLimit;
		this.travelTime = restoredTravelTime;
		this.travelTimeSum = 0.0;
		this.travelTimeCount = 0;
		this.nShadowVehicles.set(0);
		this.nFutureRoutingVehicles.set(0);
		this.currentEnergy = restoredCurrentEnergy;
		this.totalEnergy = restoredTotalEnergy;
		this.currentFlow = restoredCurrentFlow;
		this.totalFlow = restoredTotalFlow;
		this.prevFlow = restoredPrevFlow;
		this.controlType = restoredControlType;
		this.parking_capacity = Math.max(0, restoredParkingCapacity);
		this.parkingCapacityExplicitlySet = true;
		this.parked_num.set(Math.max(0, restoredParkedNum));
		this.prevParkingCapacity = this.parking_capacity;
		this.prevParkedNum = this.parked_num.get();
		this.parkingStateDirty = true;
	}

	/* For adaptive network partitioning */
	public int getShadowVehicleNum() {
		return this.nShadowVehicles.get();
	}

	public void incrementShadowVehicleNum() {
		this.nShadowVehicles.incrementAndGet();
	}

	public void resetShadowVehicleNum() {
		this.nShadowVehicles.set(0);
	}

	public void decreaseShadowVehicleNum() {
		int val = this.nShadowVehicles.decrementAndGet();
		if (val < 0)
			this.nShadowVehicles.compareAndSet(val, 0);
	}

	public int getFutureRoutingVehNum() {
		return this.nFutureRoutingVehicles.get();
	}

	public void incrementFutureRoutingVehNum() {
		this.nFutureRoutingVehicles.incrementAndGet();
	}

	public void resetFutureRountingVehNum() {
		this.nFutureRoutingVehicles.set(0);
	}

	public void decreaseFutureRoutingVehNum() {
		int val = this.nFutureRoutingVehicles.decrementAndGet();
		if (val < 0)
			this.nFutureRoutingVehicles.compareAndSet(val, 0);
	}

	// This add queue using TreeMap structure
	public synchronized void addVehicleToDepartureMap() {
		ArrayList<Vehicle> pending = new ArrayList<Vehicle>();
		for (Vehicle v = this.toAddDepartureVeh.poll(); v != null; v = this.toAddDepartureVeh.poll()) {
			pending.add(v);
		}
		pending.sort((a, b) -> Integer.compare(a.getID(), b.getID()));
		for (Vehicle v : pending) {
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
		if (v != null) {
			this.toAddDepartureVeh.add(v);
			ContextCreator.getRoadContext().markRoadActive(this);
		}
	}

	/*
	 * RemoveVehicleFromNewQueue, will remove vehicle v from the TreeMap by looking
	 * at the departuretime_ of the vehicle if there are more than one vehicle with
	 * the same departuretime_, it will remove the vehicle match with id of v.
	 */
	public synchronized void removeVehicleFromNewQueue(int departureTime, Vehicle v) {
		ArrayList<Vehicle> temporalList = this.departureVehMap.get(departureTime);
		if (temporalList == null) return;
		if (temporalList.size() > 1) {
			this.departureVehMap.get(departureTime).remove(v);
		} else {
			this.departureVehMap.remove(departureTime);
		}
	}

	public synchronized Vehicle departureVehicleQueueHead() {
		while (!this.departureVehMap.isEmpty()) {
			int firstDeparture_ = this.departureVehMap.firstKey();
			ArrayList<Vehicle> queue = this.departureVehMap.get(firstDeparture_);
			if (queue != null && !queue.isEmpty()) {
				return queue.get(0);
			}
			this.departureVehMap.remove(firstDeparture_);
		}
		return null;
	}

	public synchronized List<Vehicle> getEnteringVehicleQueueSnapshot() {
		ArrayList<Vehicle> vehicles = new ArrayList<Vehicle>();
		for (ArrayList<Vehicle> queue : this.departureVehMap.values()) {
			vehicles.addAll(queue);
		}
		ArrayList<Vehicle> pending = new ArrayList<Vehicle>(this.toAddDepartureVeh);
		pending.sort((a, b) -> {
			int departCompare = Integer.compare(a.getDepTime(), b.getDepTime());
			return departCompare != 0 ? departCompare : Integer.compare(a.getID(), b.getID());
		});
		vehicles.addAll(pending);
		return vehicles;
	}

	public synchronized void restoreEnteringVehicleQueue(List<Vehicle> vehicles) {
		this.departureVehMap.clear();
		this.toAddDepartureVeh.clear();
		if (vehicles == null) return;
		for (Vehicle v : vehicles) {
			if (v == null || v.isOnRoad()) continue;
			int departureTime = v.getDepTime();
			ArrayList<Vehicle> queue = this.departureVehMap.get(departureTime);
			if (queue == null) {
				queue = new ArrayList<Vehicle>();
				this.departureVehMap.put(departureTime, queue);
			}
			queue.add(v);
		}
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
		if(travelTimeCount > 0) {
			newTravelTime = GlobalVariables.SIMULATION_STEP_SIZE * travelTimeSum / travelTimeCount;
			travelTimeSum = 0.0;
			travelTimeCount = 0;
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
	
	public synchronized boolean stateHasChanged() {
		int currentParkingCapacity = this.parking_capacity;
		int currentParkedNum = this.parked_num.get();
		if(this.prevFlow == this.totalFlow && this.prevParkingCapacity == currentParkingCapacity
				&& this.prevParkedNum == currentParkedNum && !this.parkingStateDirty) {
			return false;
		}
		else {
			this.prevFlow = this.totalFlow;
			this.prevParkingCapacity = currentParkingCapacity;
			this.prevParkedNum = currentParkedNum;
			this.parkingStateDirty = false;
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
		this.refreshDefaultParkingCapacity();
	}
	
	public void addLane(Lane l, int index) {
		this.lanes.add(index, l);
		this.refreshDefaultParkingCapacity();
	}

	public ArrayList<Lane> getLanes() {
		return this.lanes;
	}

	public int getNumberOfLanes() {
		return this.lanes.size();
	}

	/**
	 * Rightmost (SUMO lane index 0) lane under {@link #sortLanes()}: smallest lane
	 * integer id sorts to index 0.
	 */
	public Lane firstLane() {
		Lane firstLane = null;
		if (!this.getLanes().isEmpty()) {
			firstLane = this.getLane(0);
		}
		return firstLane;
	}

	public void printRoadInfo() {
		ContextCreator.logger.info("Road: " + this.getID() + " has lanes from right (SUMO 0) to left as follow: ");
		for (int i = 0; i < this.lanes.size(); i++) {
			ContextCreator.logger.info(this.lanes.get(i).getID() + " with Repast ID: " + this.lanes.get(i).getID());
		}
	}

	public void printRoadCoordinate() {
		ContextCreator.logger.info("Coordinate of road: " + this.getID());
		ContextCreator.logger.info("Starting point: " + this.getStartCoord());
		ContextCreator.logger.info("Ending point: " + this.getEndCoord());
	}

	/*
	 * Update background traffic through a speed file. if road event flag is
	 * true, just pass to cached speed limit, otherwise, update link free flow speed
	 */
	public void updateFreeFlowSpeed() {
		// Get current tick
		int hour = ContextCreator.getCurrentTick() / GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL;
		// each hour set events
		if (this.lastUpdateHour < hour) {
			double new_speed = ContextCreator.background_traffic.getBackgroundTraffic(this.origID, hour);
			for(Lane lane: this.getLanes()) {
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
		this.currentFlow += 1;
		if (v.getVehicleClass() == Vehicle.EV) { // Private
			ElectricVehicle ev = (ElectricVehicle) v;
			this.totalEnergy += ev.getLinkConsume();
			this.currentEnergy += ev.getLinkConsume();
			ev.resetLinkConsume();
		}
		else if(v.getVehicleClass() == Vehicle.ETAXI) { // EV Taxi
			ElectricTaxi ev = (ElectricTaxi) v;
			this.totalEnergy += ev.getLinkConsume();
			this.currentEnergy += ev.getLinkConsume();
			if(ev.getVehicleSensorType() == Vehicle.MOBILEDEVICE) {
				ContextCreator.kafkaManager.produceLinkEnergy(ev.getID(), ev.getVehicleClass(), this.getID(),
						ev.getLinkConsume());
			}
			ev.resetLinkConsume();
		} else if (v.getVehicleClass() == Vehicle.EBUS) {
			ElectricBus bv = (ElectricBus) v;
			this.totalEnergy += bv.getLinkConsume();
			this.currentEnergy += bv.getLinkConsume();
			if(bv.getVehicleSensorType() == Vehicle.MOBILEDEVICE) {
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
	
	public double getAndResetCurrentEnergy() {
		double res = this.currentEnergy;
		this.currentEnergy = 0;
		return res;
	}

	public int getAndResetCurrentFlow() {
		int res = this.currentFlow;
		this.currentFlow = 0;
		return res;
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
		this.cachedSpeedLimit_ = this.speedLimit_;
		this.refreshDefaultParkingCapacity();
	}
	
	public int getRoadType() {
		return roadType;
	}
	
	public void setControlType(int controlType) {
		// If from cosim to others and there is vehicles on road, recompute the distance and recreated the vehicle linkedlist
		if (this.controlType == Road.COSIM && this.getVehicleNum() > 0) {
			// Collect all vehicles on the road by traversing the macro linked list
			ArrayList<Vehicle> vehicles = new ArrayList<>();
			Vehicle curVeh = this.firstVehicle();
			while (curVeh != null) {
				vehicles.add(curVeh);
				curVeh = curVeh.macroTrailing();
			}

			int n = vehicles.size();
			if (n == 0) return;

			// Remove all vehicles from their current lanes
			for (Vehicle veh : vehicles) {
				veh.removeFromCurrentLane();
			}

			// For each vehicle on the road, compute the closest distance to each lane
			// Assign the vehicle to the lane with the closest distance
			// Compute the distance variable (distance to the road end point) for each vehicle
			Lane[] assignedLanes = new Lane[n];
			double[] assignedDistances = new double[n];

			for (int v = 0; v < n; v++) {
				Vehicle veh = vehicles.get(v);
				Coordinate currCoord = veh.getCurrentCoord();

				Lane closestLane = null;
				double minPerpDist = Double.MAX_VALUE;
				double bestDistance = 0;

				for (Lane lane : this.lanes) {
					ArrayList<Coordinate> coords = lane.getCoords();
					double distFromEnd = 0;  
					boolean found = false;

					// Iterate from downstream end to upstream, same as changeLane
					for (int i = coords.size() - 1; i > 0; i--) {
						Coordinate a = coords.get(i);     // more downstream
						Coordinate b = coords.get(i - 1); // more upstream

						double dx = b.x - a.x;
						double dy = b.y - a.y;
						double lenSq = dx * dx + dy * dy;

						if (lenSq > 0) {
							double apx = currCoord.x - a.x;
							double apy = currCoord.y - a.y;
							double param = (apx * dx + apy * dy) / lenSq;

							if (param >= 0.0 && param <= 1.0) {
								// Projection falls on this segment, compute perpendicular distance
								double projX = a.x + param * dx;
								double projY = a.y + param * dy;
								Coordinate projCoord = new Coordinate(projX, projY);
								double perpDist = ContextCreator.getCityContext().getDistance(currCoord, projCoord);

								if (perpDist < minPerpDist) {
									minPerpDist = perpDist;
									closestLane = lane;
									// Distance to road end = accumulated distance from downstream + partial segment
									double segLen = ContextCreator.getCityContext().getDistance(a, b);
									bestDistance = distFromEnd + segLen * param;
								}
								found = true;
								break;
							}
						}

						double segLen = ContextCreator.getCityContext().getDistance(a, b);
						distFromEnd += segLen;
					}

					// If no projection found on this lane, use nearest endpoint
					if (!found) {
						double distToStart = ContextCreator.getCityContext().getDistance(currCoord, coords.get(0));
						double distToEnd = ContextCreator.getCityContext().getDistance(currCoord, coords.get(coords.size() - 1));
						if (distToEnd < minPerpDist) {
							minPerpDist = distToEnd;
							closestLane = lane;
							bestDistance = 0; // Near downstream end
						}
						if (distToStart < minPerpDist) {
							minPerpDist = distToStart;
							closestLane = lane;
							bestDistance = lane.getLength(); // Near upstream end
						}
					}
				}

				assignedLanes[v] = closestLane;
				assignedDistances[v] = Math.max(0, Math.min(bestDistance, closestLane.getLength()));
			}

			// Update the CoordMap of each vehicle by teleporting to the assigned lane
			// (this also rebuilds lane-level linked lists: leading/trailing, firstVehicle/lastVehicle)
			for (int v = 0; v < n; v++) {
				Vehicle veh = vehicles.get(v);
				Coordinate currCoord = veh.getCurrentCoord();
				veh.teleportToLane(assignedLanes[v], assignedDistances[v]);
				if(veh.getDistanceToNextJunction() > 0) {
					// concatenate the currCoord to the first point in the CoordMap, then update the distance
					veh.extendCoordMap(currCoord);
				}
				assignedDistances[v] = veh.getDistanceToNextJunction();
			}

			// Sort the vehicles by the distance variable (distFraction, descending)
			// firstVehicle has the highest distFraction (closest to upstream end)
			Integer[] sortedIndices = new Integer[n];
			for (int i = 0; i < n; i++) sortedIndices[i] = i;
			Arrays.sort(sortedIndices, (idx1, idx2) -> {
				double fracA = assignedDistances[idx1] / assignedLanes[idx1].getLength();
				double fracB = assignedDistances[idx2] / assignedLanes[idx2].getLength();
				return Double.compare(fracB, fracA); // Descending order
			});

			// Update the first vehicle and last vehicle of the road
			// Recreate the vehicle macro linked list, starting from the first vehicle
			Vehicle first = vehicles.get(sortedIndices[0]);
			this.firstVehicle(first);
			Vehicle prev = first;
			for (int i = 1; i < n; i++) {
				Vehicle curr = vehicles.get(sortedIndices[i]);
				prev.macroTrailing(curr);
				curr.macroLeading(prev);
				prev = curr;
			}
			this.lastVehicle(prev);
		}
		this.controlType = controlType;
	}
	
	public int getControlType() {
		return controlType;
	}

	public void recordTravelTime(Vehicle v) {
		this.travelTimeSum += v.getLinkTravelTime();
		this.travelTimeCount += 1;
		if (v.getVehicleSensorType() == Vehicle.MOBILEDEVICE) {
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
		if (goDest) this.neighboringArrivalZone = neighboringZone;
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
		this.cachedSpeedLimit_ = speedLimit;
		this.refreshDefaultParkingCapacity();
	}
	
	public String getOrigID() {
		return this.origID;
	}
	
	public void setOrigID(String newID) {
		this.origID = newID;
	}
	
	public boolean noEnterRoadConflict(Road usroad) {
		 return this.enterRoadConflictBlocker(usroad) == null;
	}

	public Vehicle enterRoadConflictBlocker(Road usroad) {
		 return this.enterRoadConflictBlocker(usroad, null);
	}

	public Vehicle enterRoadConflictBlocker(Road usroad, Vehicle enteringVehicle) {
		 Junction prevJunction = ContextCreator.getJunctionContext().get(this.getUpStreamJunction());
		 for(Road r: this.upStreamRoads) {
			 if(this.isSameRoad(r, usroad)) break;
			 if(r.prevFirstVehicle()!= null) {
				Vehicle v = r.prevFirstVehicle();
				if(this.isSameVehicle(v, enteringVehicle)) continue;
				if(!v.wasPreviouslyOnRoad(r)) continue;
				if(prevJunction != null && v.aboutToEnterRoad(this)
						&& this.isConflictVehicleMovable(prevJunction, r, v)) {
					return v;
				}
			 }
		 }
		 return null;
	}

	private boolean isSameRoad(Road r1, Road r2) {
		if(r1 == r2) return true;
		if(r1 == null || r2 == null) return false;
		return r1.getID() == r2.getID();
	}

	private boolean isSameVehicle(Vehicle v1, Vehicle v2) {
		if(v1 == v2) return true;
		if(v1 == null || v2 == null) return false;
		return v1.getID() == v2.getID();
	}

	private boolean isConflictVehicleMovable(Junction prevJunction, Road upstreamRoad, Vehicle v) {
		switch(prevJunction.getControlType()) {
			case Junction.NoControl:
				return true;
			case Junction.DynamicSignal:
				return prevJunction.getSignalState(upstreamRoad.getID(), this.getID()) <= Signal.Yellow;
			case Junction.StaticSignal:
				return prevJunction.getSignalState(upstreamRoad.getID(), this.getID()) <= Signal.Yellow;
			case Junction.StopSign:
				return prevJunction.getDelay(upstreamRoad.getID(), this.getID()) <= v.getStuckTime();
			case Junction.Yield:
				return prevJunction.getDelay(upstreamRoad.getID(), this.getID()) <= v.getStuckTime();
			default:
				return true;
		}
	}
}
