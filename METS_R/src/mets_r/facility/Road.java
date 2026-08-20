package mets_r.facility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.vividsolutions.jts.geom.Coordinate;
import mets_r.*;
import mets_r.data.input.BackgroundTraffic;
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

	private static final double MPH_TO_METERS_PER_SECOND = 0.44694;
	private static final double DEFAULT_STREET_PARKING_CAPACITY_PER_METER = 0.115;
	private static final double DEFAULT_STREET_PARKING_MAX_SPEED_MPS = 30.0 * MPH_TO_METERS_PER_SECOND;
	
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
	/* Incremented on every macro-list membership change for sparse link output. */
	private AtomicInteger vehicleCountStateVersion;
	private int previousVehicleCountStateVersion;
	private volatile int parking_capacity; // Maximum number of parked vehicles this road provides
	private boolean parkingCapacityExplicitlySet;
	private AtomicInteger parked_num; // Number of vehicles currently parked on this road
	private volatile boolean parkingStateDirty;
	private int prevParkingCapacity;
	private int prevParkedNum;
	private Vehicle lastVehicle_; // Vehicle stored as a linked list
	private Vehicle firstVehicle_;
	private Vehicle prevFirstVehicle; // For parallel computing
	/* One externally controlled connector may reserve each target lane. */
	private ConcurrentHashMap<Integer, Vehicle> externalLaneReservations;
	private volatile boolean nativeReleaseInProgress;
	private int activeExternalLaneAdmissions;
	private int activeExternalLaneCommits;
	private double travelTime;
	private TreeMap<Integer, ArrayList<Vehicle>> departureVehMap; // Use this class to control the vehicle that entering
	private ConcurrentLinkedQueue<Vehicle> toAddDepartureVeh; // Tree map is not thread-safe, so use this 
	private final ArrayList<Vehicle> stepVehicleBuffer = new ArrayList<Vehicle>();
	private final ArrayList<Vehicle> departureBuffer = new ArrayList<Vehicle>();
	private double speedLimit_; // Speed for travel time estimation
	private double travelTimeSum;
	private int travelTimeCount;
	private double avgEnergyConsumption;
	private double energyConsumptionSum;
	private int energyConsumptionCount;
	
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
		this.vehicleCountStateVersion = new AtomicInteger(0);
		this.previousVehicleCountStateVersion = 0;
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
		this.externalLaneReservations = new ConcurrentHashMap<Integer, Vehicle>();
		this.nativeReleaseInProgress = false;
		this.activeExternalLaneAdmissions = 0;
		this.activeExternalLaneCommits = 0;
		this.lastUpdateHour = -1;
		this.travelTime =  this.length / this.speedLimit_;
		this.travelTimeSum = 0.0;
		this.travelTimeCount = 0;
		this.avgEnergyConsumption = 0.0;
		this.energyConsumptionSum = 0.0;
		this.energyConsumptionCount = 0;
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

	// Get the speed limit
	public double getSpeedLimit() {
		return this.speedLimit_;
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
		boolean shouldRecordSnapshot = ContextCreator.dataCollector != null
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
			while (true) {
				Vehicle v = this.departureVehicleQueueHead();
				if (v == null) break;
				try {
					// Queue ownership transfer and admission use the same vehicle
					// monitor, so two road workers cannot admit or requeue it between
					// the ownership check and queue cleanup.
					synchronized (v) {
						int departTime = v.getDepTime();
						RoadContext roadContext = ContextCreator.getRoadContext();
						boolean registeredAnywhere =
								roadContext.hasEnteringVehicleRegistration(v);
						if (v.isOnRoad() || (registeredAnywhere
								&& !roadContext.isEnteringVehicleRegistered(this, v))) {
							// This entry lost queue ownership or survived an earlier
							// successful admission. Purge it without touching road counts.
							this.removeVehicleFromEnteringQueue(v);
							continue;
						}
						if (tickcount >= departTime) {
							boolean busTrip = (v.getVehicleClass() == Vehicle.EBUS);
							if (busTrip && v.getOriginID() == v.getDestID()) {
								roadContext.removeVehicleFromEnteringQueues(v);
								v.reachDest();
							} else if (v.enterNetwork(this)) {
								this.removeVehicleFromNewQueue(departTime, v);
							} else {
								boolean stillRegisteredAnywhere =
										roadContext.hasEnteringVehicleRegistration(v);
								if (v.isOnRoad() || (stillRegisteredAnywhere
										&& !roadContext.isEnteringVehicleRegistered(this, v))) {
									this.removeVehicleFromEnteringQueue(v);
									continue;
								}
								break; // Network is full, stop processing departures
							}
						} else {
							break; // Reached vehicles scheduled for future ticks
						}
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
				if (currentVehicle.isDormantOnRoad() || currentVehicle.isExternalRoadTransition()) {
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
			List<Vehicle> vehicleBuffer = this.stepVehicleBuffer;
			vehicleBuffer.clear();
			currentVehicle = this.firstVehicle();

			// 1. Create a static snapshot of the vehicles currently on the road
			while (currentVehicle != null) {
			    vehicleBuffer.add(currentVehicle);
			    currentVehicle = currentVehicle.macroTrailing();
			}

			// 2. Iterate through the buffered list to safely apply macro list repairs
			for (Vehicle v : vehicleBuffer) {
				if (v.isDormantOnRoad() || v.isExternalRoadTransition()) {
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
			vehicleBuffer.clear();

			// Phase 3: acceleration decisions (now with correct leading vehicles)
			currentVehicle = this.firstVehicle();
			while (currentVehicle != null) {
				Vehicle nextVehicle = currentVehicle.macroTrailing();
				if (currentVehicle.isDormantOnRoad() || currentVehicle.isExternalRoadTransition()) {
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
				if (currentVehicle.isDormantOnRoad() || currentVehicle.isExternalRoadTransition()) {
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
		if (veh == null) {
			throw new IllegalArgumentException("Vehicle must not be null");
		}
		if (lane == null || lane.getRoad() != this) {
			throw new IllegalArgumentException("Teleport lane must belong to the target road");
		}
		double laneLength = lane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0
				|| !Double.isFinite(dist) || dist < 0.0 || dist > laneLength) {
			throw new IllegalArgumentException("Teleport distance must be within the target lane");
		}
		if (lane.getCoords() == null || lane.getCoords().size() < 2) {
			throw new IllegalArgumentException("Teleport lane must have usable geometry");
		}
		if (veh.getLane() != null) {
			throw new IllegalStateException("Vehicle must be detached from its lane before teleporting");
		}
		if (veh.getRoad() != this) {
			veh.appendToRoadForTeleport(this);
		}
		
		// Move veh to the x and y location
		veh.teleportToLane(lane, dist);
		// A trace-replay teleport is discontinuous, so visualization must not
		// interpolate from the vehicle's pre-teleport position.
		veh.syncPreviousEpochCoord();
		
		// Insert the veh to the proper macroList loc, find the macroleading and trailing veh
		veh.advanceInMacroList();
		// Trace replay may also move a vehicle backward (larger distance to the
		// downstream junction). advanceInMacroList only repairs forward moves.
		veh.retreatInMacroList();
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
		int vehicleCount = this.nVehicles_.addAndGet(nVeh);
		if (nVeh != 0) {
			// A version counter cannot lose a membership update if it races with
			// stateHasChanged(), unlike a boolean dirty flag which may be cleared.
			this.vehicleCountStateVersion.incrementAndGet();
		}
		if (vehicleCount < 0) {
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

	/**
	 * Return the vehicle that currently reserves a lane entrance while it is on
	 * an externally controlled connector.
	 */
	public synchronized Vehicle getExternalLaneReservationBlocker(Lane lane, Vehicle requester) {
		if (lane == null || lane.getRoad() != this) return null;
		Vehicle existing = this.externalLaneReservations.get(lane.getID());
		return existing == requester ? null : existing;
	}

	/**
	 * Atomically reserve a target lane for a regular-road -> CoSim connector.
	 * The reservation prevents a second handoff from passing the lane-level gap
	 * check before the first external vehicle has reached and joined the lane.
	 */
	public synchronized boolean tryReserveExternalLane(Lane lane, Vehicle vehicle) {
		if (lane == null || vehicle == null || lane.getRoad() != this) return false;
		Vehicle current = this.externalLaneReservations.get(lane.getID());
		if (current == vehicle) return true;
		if (this.nativeReleaseInProgress) return false;
		Vehicle existing = this.externalLaneReservations.putIfAbsent(lane.getID(), vehicle);
		return existing == null || existing == vehicle;
	}

	/**
	 * Atomically reserve a target lane and hold release control until the pending
	 * vehicle is fully attached and published in the transition registry.
	 */
	public synchronized boolean beginExternalLaneAdmission(Lane lane, Vehicle vehicle) {
		if (this.nativeReleaseInProgress || lane == null || vehicle == null || lane.getRoad() != this) {
			return false;
		}
		Vehicle existing = this.externalLaneReservations.putIfAbsent(lane.getID(), vehicle);
		if (existing != null && existing != vehicle) return false;
		this.activeExternalLaneAdmissions++;
		return true;
	}

	/** Finish an admission lease, optionally retaining its lane reservation. */
	public synchronized void endExternalLaneAdmission(Lane lane, Vehicle vehicle,
			boolean retainReservation) {
		if (this.activeExternalLaneAdmissions > 0) this.activeExternalLaneAdmissions--;
		if (!retainReservation && lane != null && vehicle != null && lane.getRoad() == this) {
			this.externalLaneReservations.remove(lane.getID(), vehicle);
		}
	}

	/** Reserve an alternate only for a vehicle already owned by this release. */
	public synchronized boolean tryReserveExternalLaneForNativeRelease(Lane lane, Vehicle vehicle) {
		if (!this.nativeReleaseInProgress || lane == null || vehicle == null || lane.getRoad() != this
				|| !this.externalLaneReservations.containsValue(vehicle)) return false;
		Vehicle existing = this.externalLaneReservations.putIfAbsent(lane.getID(), vehicle);
		return existing == null || existing == vehicle;
	}

	/** Release a connector reservation only when it is owned by this vehicle. */
	public synchronized void releaseExternalLaneReservation(Lane lane, Vehicle vehicle) {
		if (lane == null || vehicle == null || lane.getRoad() != this) return;
		this.externalLaneReservations.remove(lane.getID(), vehicle);
	}

	public synchronized boolean hasExternalLaneReservation(Lane lane, Vehicle vehicle) {
		return lane != null && vehicle != null && lane.getRoad() == this
				&& this.externalLaneReservations.get(lane.getID()) == vehicle;
	}

	public synchronized int getExternalLaneReservationCount() {
		return this.externalLaneReservations.size();
	}

	public boolean isNativeReleaseInProgress() {
		return this.nativeReleaseInProgress;
	}

	public synchronized boolean beginConnectorNativeRelease() {
		if (this.controlType == Road.COSIM || this.nativeReleaseInProgress
				|| this.activeExternalLaneAdmissions > 0
				|| this.activeExternalLaneCommits > 0) return false;
		this.nativeReleaseInProgress = true;
		return true;
	}

	public synchronized void endConnectorNativeRelease() {
		this.nativeReleaseInProgress = false;
	}

	public synchronized boolean beginExternalLaneCommit(Lane lane, Vehicle vehicle) {
		if (this.nativeReleaseInProgress || lane == null || vehicle == null
				|| this.externalLaneReservations.get(lane.getID()) != vehicle) return false;
		this.activeExternalLaneCommits++;
		return true;
	}

	public synchronized void endExternalLaneCommit() {
		if (this.activeExternalLaneCommits > 0) this.activeExternalLaneCommits--;
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
		if (ContextCreator.dataCollector != null) {
			ContextCreator.dataCollector.recordRoadParkingStateChange(this);
		}
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
		restoreRuntimeState(restoredTravelTime, restoredSpeedLimit, restoredCurrentEnergy,
				restoredTotalEnergy, restoredCurrentFlow, restoredTotalFlow, restoredPrevFlow,
				restoredControlType, restoredParkingCapacity, restoredParkedNum, -1);
	}

	public void restoreRuntimeState(double restoredTravelTime, double restoredSpeedLimit,
			double restoredCurrentEnergy, double restoredTotalEnergy, int restoredCurrentFlow,
			int restoredTotalFlow, int restoredPrevFlow, int restoredControlType,
			int restoredParkingCapacity, int restoredParkedNum,
			int restoredBackgroundSpeedHour) {
		unregisterEnteringQueueMemberships();
		this.lastUpdateHour = restoredBackgroundSpeedHour;
		this.nVehicles_.set(0);
		this.firstVehicle_ = null;
		this.lastVehicle_ = null;
		this.prevFirstVehicle = null;
		this.departureVehMap.clear();
		this.toAddDepartureVeh.clear();
		this.speedLimit_ = restoredSpeedLimit;
		this.travelTime = restoredTravelTime;
		this.travelTimeSum = 0.0;
		this.travelTimeCount = 0;
		this.avgEnergyConsumption = 0.0;
		this.energyConsumptionSum = 0.0;
		this.energyConsumptionCount = 0;
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
		if (this.toAddDepartureVeh.isEmpty()) {
			return;
		}
		ArrayList<Vehicle> pending = this.departureBuffer;
		pending.clear();
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
		pending.clear();
	}

	// This add vehicle to the thread-safe pending list
	public void addVehicleToPendingQueue(Vehicle v) {
		if (v != null) {
			synchronized (v) {
				RoadContext roadContext = ContextCreator.getRoadContext();
				if (v.isOnRoad()) {
					// An active vehicle can never own an entering-queue slot.
					roadContext.removeVehicleFromEnteringQueues(v);
					this.removeVehicleFromEnteringQueue(v);
					return;
				}
				if (this.hasOneCurrentEnteringQueueOccurrence(v)
						&& roadContext.isOnlyEnteringVehicleRegistered(this, v)) {
					// Repeated departure/recovery requests for the same road are
					// idempotent and must not move the vehicle to the back of the queue.
					roadContext.markRoadActive(this);
					return;
				}
				// Queue ownership is unique per vehicle. Remove the prior indexed
				// owner (or a duplicate on this road) before publishing the new one.
				if (roadContext.hasEnteringVehicleRegistration(v)) {
					roadContext.removeVehicleFromEnteringQueues(v);
				} else {
					this.removeVehicleFromEnteringQueue(v);
				}
				// A concurrent admission may have won before this enqueue acquired
				// the vehicle monitor. Do not create a stale entry for an on-road
				// vehicle.
				if (v.isOnRoad()) return;
				this.toAddDepartureVeh.add(v);
				roadContext.registerEnteringVehicle(this, v);
				roadContext.markRoadActive(this);
			}
		}
	}

	/** Remove all local pending entries for this vehicle. */
	public synchronized void removeVehicleFromNewQueue(int departureTime, Vehicle v) {
		// Departure time can change after an entry was queued. Remove every local
		// occurrence instead of trusting a possibly stale TreeMap key.
		this.removeVehicleFromEnteringQueue(v);
	}

	public synchronized boolean removeVehicleFromEnteringQueue(Vehicle v) {
		if (v == null) return false;
		boolean removed = false;
		for (Vehicle queued : new ArrayList<Vehicle>(this.toAddDepartureVeh)) {
			if (!sameEnteringVehicle(queued, v)) continue;
			while (this.toAddDepartureVeh.remove(queued)) {
				removed = true;
			}
		}
		ArrayList<Integer> emptyDepartureTimes = new ArrayList<Integer>();
		for (Map.Entry<Integer, ArrayList<Vehicle>> entry : this.departureVehMap.entrySet()) {
			Iterator<Vehicle> iterator = entry.getValue().iterator();
			while (iterator.hasNext()) {
				if (sameEnteringVehicle(iterator.next(), v)) {
					iterator.remove();
					removed = true;
				}
			}
			if (entry.getValue().isEmpty()) {
				emptyDepartureTimes.add(entry.getKey());
			}
		}
		for (Integer departureTime : emptyDepartureTimes) {
			this.departureVehMap.remove(departureTime);
		}
		if (removed || !containsVehicleInEnteringQueue(v)) {
			ContextCreator.getRoadContext().unregisterEnteringVehicle(this, v);
		}
		return removed;
	}

	private synchronized int enteringQueueOccurrenceCount(Vehicle v) {
		if (v == null) return 0;
		int occurrences = 0;
		for (Vehicle queued : this.toAddDepartureVeh) {
			if (sameEnteringVehicle(queued, v)) occurrences++;
		}
		for (ArrayList<Vehicle> queue : this.departureVehMap.values()) {
			if (queue == null) continue;
			for (Vehicle queued : queue) {
				if (sameEnteringVehicle(queued, v)) occurrences++;
			}
		}
		return occurrences;
	}

	private synchronized boolean hasOneCurrentEnteringQueueOccurrence(Vehicle v) {
		if (v == null) return false;
		int occurrences = 0;
		boolean currentPlacement = false;
		for (Vehicle queued : this.toAddDepartureVeh) {
			if (sameEnteringVehicle(queued, v)) {
				occurrences++;
				// Pending entries are keyed from the latest departure time when
				// addVehicleToDepartureMap() promotes them.
				currentPlacement = true;
			}
		}
		for (Map.Entry<Integer, ArrayList<Vehicle>> entry : this.departureVehMap.entrySet()) {
			for (Vehicle queued : entry.getValue()) {
				if (sameEnteringVehicle(queued, v)) {
					occurrences++;
					currentPlacement = entry.getKey().intValue() == v.getDepTime();
				}
			}
		}
		return occurrences == 1 && currentPlacement;
	}

	private synchronized boolean containsVehicleInEnteringQueue(Vehicle v) {
		return enteringQueueOccurrenceCount(v) > 0;
	}

	private static boolean sameEnteringVehicle(Vehicle first, Vehicle second) {
		return first == second || (first != null && second != null
				&& first.getID() == second.getID());
	}

	private void unregisterEnteringQueueMemberships() {
		for (Vehicle v : getEnteringVehicleQueueSnapshot()) {
			ContextCreator.getRoadContext().unregisterEnteringVehicle(this, v);
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
		LinkedHashMap<Integer, Vehicle> uniqueVehicles = new LinkedHashMap<Integer, Vehicle>();
		for (ArrayList<Vehicle> queue : this.departureVehMap.values()) {
			for (Vehicle vehicle : queue) {
				if (vehicle != null && !vehicle.isOnRoad()
						&& !uniqueVehicles.containsKey(vehicle.getID())) {
					uniqueVehicles.put(vehicle.getID(), vehicle);
				}
			}
		}
		ArrayList<Vehicle> pending = new ArrayList<Vehicle>(this.toAddDepartureVeh);
		pending.sort((a, b) -> {
			int departCompare = Integer.compare(a.getDepTime(), b.getDepTime());
			return departCompare != 0 ? departCompare : Integer.compare(a.getID(), b.getID());
		});
		for (Vehicle vehicle : pending) {
			if (vehicle != null && !vehicle.isOnRoad()
					&& !uniqueVehicles.containsKey(vehicle.getID())) {
				uniqueVehicles.put(vehicle.getID(), vehicle);
			}
		}
		return new ArrayList<Vehicle>(uniqueVehicles.values());
	}

	public synchronized void restoreEnteringVehicleQueue(List<Vehicle> vehicles) {
		unregisterEnteringQueueMemberships();
		this.departureVehMap.clear();
		this.toAddDepartureVeh.clear();
		this.externalLaneReservations.clear();
		if (vehicles == null) return;
		Set<Integer> restoredVehicleIDs = new HashSet<Integer>();
		for (Vehicle v : vehicles) {
			if (v == null || v.isOnRoad() || !restoredVehicleIDs.add(v.getID())) continue;
			int departureTime = v.getDepTime();
			ArrayList<Vehicle> queue = this.departureVehMap.get(departureTime);
			if (queue == null) {
				queue = new ArrayList<Vehicle>();
				this.departureVehMap.put(departureTime, queue);
			}
			queue.add(v);
			ContextCreator.getRoadContext().registerEnteringVehicle(this, v);
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
	public synchronized boolean updateTravelTimeEstimation() {
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

		double newAvgEnergyConsumption;
		if(energyConsumptionCount > 0) {
			newAvgEnergyConsumption = energyConsumptionSum / energyConsumptionCount;
			energyConsumptionSum = 0.0;
			energyConsumptionCount = 0;
		}
		else {
			newAvgEnergyConsumption = 0.0;
		}
		this.avgEnergyConsumption = newAvgEnergyConsumption;
		
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
		int currentVehicleCountStateVersion = this.vehicleCountStateVersion.get();
		if(this.prevFlow == this.totalFlow && this.prevParkingCapacity == currentParkingCapacity
				&& this.prevParkedNum == currentParkedNum && !this.parkingStateDirty
				&& this.previousVehicleCountStateVersion == currentVehicleCountStateVersion) {
			return false;
		}
		else {
			this.prevFlow = this.totalFlow;
			this.prevParkingCapacity = currentParkingCapacity;
			this.prevParkedNum = currentParkedNum;
			this.previousVehicleCountStateVersion = currentVehicleCountStateVersion;
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

	/**
	 * Set the physical target speed and its corresponding free-flow routing cost.
	 *
	 * <p>The lane speeds drive vehicle behavior, while the road speed limit constrains
	 * existing vehicles and supplies the routing fallback travel time. Clearing old
	 * samples prevents observations collected under the previous target from
	 * immediately replacing the new cost.
	 *
	 * @param targetSpeed target speed in meters per second
	 */
	public synchronized void setTargetSpeed(double targetSpeed) {
		if (!Double.isFinite(targetSpeed) || targetSpeed <= 0.0) {
			throw new IllegalArgumentException("Target speed must be a finite positive value: " + targetSpeed);
		}
		for (Lane lane : this.getLanes()) {
			lane.setSpeed(targetSpeed);
		}
		this.speedLimit_ = targetSpeed;
		this.travelTime = Math.max(0.0, this.length) / targetSpeed;
		this.travelTimeSum = 0.0;
		this.travelTimeCount = 0;
	}

	/**
	 * Apply this road's value from the hourly background-speed profile.
	 *
	 * @return true when a valid profile value changed the target speed
	 */
	public synchronized boolean updateBackgroundSpeed() {
		BackgroundTraffic.BackgroundSpeedSample speedSample = ContextCreator.background_traffic
				.getBackgroundTrafficForSimulationTick(this.origID, ContextCreator.getCurrentTick());
		int profileHour = speedSample.getProfileHour();
		if (this.lastUpdateHour == profileHour) {
			return false;
		}
		// Mark missing or invalid entries as handled so they are not retried every tick.
		this.lastUpdateHour = profileHour;
		double newSpeedMph = speedSample.getSpeed();
		if (!Double.isFinite(newSpeedMph) || newSpeedMph <= 0.0) {
			return false;
		}
		setTargetSpeed(newSpeedMph * MPH_TO_METERS_PER_SECOND);
		return true;
	}

	public int getLastBackgroundSpeedHour() {
		return this.lastUpdateHour;
	}
	public synchronized void recordEnergyConsumption(Vehicle v) {
		this.totalFlow += 1;
		this.currentFlow += 1;
		if (v.getVehicleClass() == Vehicle.EV) { // Private
			ElectricVehicle ev = (ElectricVehicle) v;
			double linkConsume = ev.getLinkConsume();
			this.totalEnergy += linkConsume;
			this.currentEnergy += linkConsume;
			recordEnergyConsumptionSample(linkConsume);
			ev.resetLinkConsume();
		}
		else if(v.getVehicleClass() == Vehicle.ETAXI) { // EV Taxi
			ElectricTaxi ev = (ElectricTaxi) v;
			double linkConsume = ev.getLinkConsume();
			this.totalEnergy += linkConsume;
			this.currentEnergy += linkConsume;
			recordEnergyConsumptionSample(linkConsume);
			if(ev.getVehicleSensorType() == Vehicle.MOBILEDEVICE && ContextCreator.kafkaManager != null) {
				ContextCreator.kafkaManager.produceLinkEnergy(ev.getID(), ev.getVehicleClass(), this.getID(),
						linkConsume);
			}
			ev.resetLinkConsume();
		} else if (v.getVehicleClass() == Vehicle.EBUS) {
			ElectricBus bv = (ElectricBus) v;
			double linkConsume = bv.getLinkConsume();
			this.totalEnergy += linkConsume;
			this.currentEnergy += linkConsume;
			recordEnergyConsumptionSample(linkConsume);
			if(bv.getVehicleSensorType() == Vehicle.MOBILEDEVICE && ContextCreator.kafkaManager != null) {
				ContextCreator.kafkaManager.produceLinkEnergy(bv.getID(), bv.getVehicleClass(), this.getID(),
						linkConsume);
			}
			bv.resetLinkConsume();
		}
	}

	private void recordEnergyConsumptionSample(double linkConsume) {
		if(Double.isNaN(linkConsume) || Double.isInfinite(linkConsume)) {
			return;
		}
		this.energyConsumptionSum += linkConsume;
		this.energyConsumptionCount += 1;
	}

	public double getTotalEnergy() {
		return totalEnergy;
	}

	public double getAvgEnergyConsumption() {
		return avgEnergyConsumption;
	}

	public int getTotalFlow() {
		return totalFlow;
	}
	
	public synchronized double getAndResetCurrentEnergy() {
		double res = this.currentEnergy;
		this.currentEnergy = 0;
		return res;
	}

	public synchronized int getAndResetCurrentFlow() {
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
		this.refreshDefaultParkingCapacity();
	}
	
	public int getRoadType() {
		return roadType;
	}
	
	public void setControlType(int controlType) {
		boolean releasingCoSimControl;
		synchronized (this) {
			releasingCoSimControl = this.controlType == Road.COSIM && controlType != Road.COSIM;
			if (!releasingCoSimControl) {
				this.controlType = controlType;
				return;
			}
			if (this.nativeReleaseInProgress || this.activeExternalLaneAdmissions > 0
					|| this.activeExternalLaneCommits > 0) return;
			this.nativeReleaseInProgress = true;
		}
		try {
		if (!releasingCoSimControl) {
			this.controlType = controlType;
			return;
		}
		if (this.getVehicleNum() == 0) {
			this.externalLaneReservations.clear();
			this.controlType = controlType;
			return;
		}

		ArrayList<Vehicle> vehicles = new ArrayList<Vehicle>();
		Vehicle current = this.firstVehicle();
		while (current != null) {
			vehicles.add(current);
			current = current.macroTrailing();
		}
		if (vehicles.isEmpty()) {
			ContextCreator.logger.error("Cannot release COSIM road " + this.ID
					+ ": macro count is nonzero but its vehicle list is empty");
			return;
		}

		HashSet<Vehicle> retainedConnectorVehicles = new HashSet<Vehicle>();
		ArrayList<Vehicle> vehiclesToAdapt = new ArrayList<Vehicle>();
		for (Vehicle vehicle : vehicles) {
			if (this.remainsOnCoSimConnectorAfterRelease(vehicle)) {
				retainedConnectorVehicles.add(vehicle);
			} else {
				vehiclesToAdapt.add(vehicle);
			}
		}

		List<NativeReleasePlacement> placements =
				this.planNativeReleasePlacements(vehiclesToAdapt);
		if (placements == null) {
			ContextCreator.logger.warn("Keeping road " + this.ID
					+ " under COSIM control because no collision-free native placement exists");
			return;
		}

		// Planning is complete before the first mutation, so a blocked release leaves
		// COSIM membership, lane lists, reservations, and poses untouched.
		for (Vehicle vehicle : vehiclesToAdapt) vehicle.removeFromCurrentLane();
		for (NativeReleasePlacement placement : placements) {
			boolean placed;
			if (placement.externalTransition) {
				placed = placement.vehicle.commitExternalRoadTransitionAtClosestAvailableDistance(
						placement.lane, placement.distance);
			} else {
				placement.vehicle.teleportToLane(placement.lane, placement.distance);
				placed = placement.vehicle.getLane() == placement.lane;
			}
			if (!placed) {
				throw new IllegalStateException("Preplanned native placement failed for vehicle "
						+ placement.vehicle.getID() + " on road " + this.ID);
			}
			// Releasing external control may snap a pose backward or laterally. Treat
			// that placement as a discontinuity so the next trajectory snapshot does
			// not interpolate a false high-speed sweep from the last external pose.
			placement.vehicle.syncPreviousEpochCoord();
		}

		Collections.sort(vehicles, (a, b) -> {
			int byDistance = Double.compare(a.getDistanceToNextJunction(), b.getDistanceToNextJunction());
			return byDistance != 0 ? byDistance : Integer.compare(a.getID(), b.getID());
		});
		for (Vehicle vehicle : vehicles) {
			vehicle.macroLeading(null);
			vehicle.macroTrailing(null);
		}
		this.firstVehicle(vehicles.get(0));
		for (int i = 1; i < vehicles.size(); i++) {
			Vehicle leading = vehicles.get(i - 1);
			Vehicle trailing = vehicles.get(i);
			leading.macroTrailing(trailing);
			trailing.macroLeading(leading);
		}
		this.lastVehicle(vehicles.get(vehicles.size() - 1));
		synchronized (this) {
			for (Map.Entry<Integer, Vehicle> reservation
					: this.externalLaneReservations.entrySet()) {
				if (!retainedConnectorVehicles.contains(reservation.getValue())) {
					this.externalLaneReservations.remove(
							reservation.getKey(), reservation.getValue());
				}
			}
			this.controlType = controlType;
		}
		} finally {
			synchronized (this) {
				this.nativeReleaseInProgress = false;
			}
		}
	}

	private boolean remainsOnCoSimConnectorAfterRelease(Vehicle vehicle) {
		if (vehicle == null || !vehicle.isExternalRoadTransition()) return false;
		ConnectorRoad connector = vehicle.getCurrentConnector();
		if (connector == null) return false;
		Road otherRoad;
		if (connector.getTargetRoad() == this) {
			otherRoad = connector.getSourceRoad();
		} else if (connector.getSourceRoad() == this) {
			otherRoad = connector.getTargetRoad();
		} else {
			return false;
		}
		return otherRoad != this && otherRoad.getControlType() == Road.COSIM;
	}

	private List<NativeReleasePlacement> planNativeReleasePlacements(List<Vehicle> vehicles) {
		Map<Integer, ArrayList<double[]>> occupiedIntervals = new TreeMap<Integer, ArrayList<double[]>>();
		ArrayList<NativeReleasePlacement> result = new ArrayList<NativeReleasePlacement>();
		for (int pass = 0; pass < 2; pass++) {
			boolean externalPass = pass == 1;
			for (Vehicle vehicle : vehicles) {
				if (vehicle.isExternalRoadTransition() != externalPass) continue;
				ArrayList<NativeReleaseProjection> candidates = new ArrayList<NativeReleaseProjection>();
				Lane reservedLane = vehicle.getExternalTransitionTargetLane();
				for (Lane lane : this.lanes) {
					if (externalPass && lane != reservedLane
							&& (!this.isNativeReleaseLaneRouteCompatible(vehicle, lane)
									|| this.getExternalLaneReservationBlocker(lane, vehicle) != null)) {
						continue;
					}
					if (!externalPass && lane != vehicle.getLane()
							&& this.getExternalLaneReservationBlocker(lane, vehicle) != null) {
						continue;
					}
					if (externalPass
							&& !vehicle.isExternalRoadTransitionPoseReadyForLaneEntry(lane)) {
						continue;
					}
					NativeReleaseProjection projection = this.projectForNativeRelease(vehicle, lane);
					if (projection != null) candidates.add(projection);
				}
				Collections.sort(candidates, (a, b) -> {
					if (externalPass && (a.lane == reservedLane) != (b.lane == reservedLane)) {
						return a.lane == reservedLane ? -1 : 1;
					}
					int byDistance = Double.compare(a.perpendicularDistance, b.perpendicularDistance);
					return byDistance != 0 ? byDistance : Integer.compare(a.lane.getID(), b.lane.getID());
				});

				NativeReleasePlacement placement = null;
				for (NativeReleaseProjection candidate : candidates) {
					double available = this.findNativeReleaseDistance(candidate.lane, candidate.distance,
							vehicle.length(), occupiedIntervals.get(candidate.lane.getID()));
					if (Double.isFinite(available)) {
						placement = new NativeReleasePlacement(vehicle, candidate.lane, available,
								externalPass);
						occupiedIntervals.computeIfAbsent(candidate.lane.getID(),
								id -> new ArrayList<double[]>()).add(
										new double[] { available, available + Math.max(0.0, vehicle.length()) });
						break;
					}
				}
				if (placement == null) return null;
				result.add(placement);
			}
		}
		return result;
	}

	private NativeReleaseProjection projectForNativeRelease(Vehicle vehicle, Lane lane) {
		if (lane == null || lane.getRoad() != this) return null;
		ArrayList<Coordinate> coordinates = lane.getCoords();
		if (coordinates == null || coordinates.isEmpty()) return null;
		Coordinate pose = vehicle.getCurrentCoord();
		double bestPerpendicular = Double.POSITIVE_INFINITY;
		double bestDistance = Double.NaN;
		double distanceFromEnd = 0.0;
		for (int i = coordinates.size() - 1; i > 0; i--) {
			Coordinate downstream = coordinates.get(i);
			Coordinate upstream = coordinates.get(i - 1);
			double segmentLength = ContextCreator.getCityContext().getDistance(downstream, upstream);
			if (!Double.isFinite(segmentLength) || segmentLength < 0.0) segmentLength = 0.0;
			double dx = upstream.x - downstream.x;
			double dy = upstream.y - downstream.y;
			double lengthSquared = dx * dx + dy * dy;
			if (lengthSquared > 0.0 && segmentLength > 0.0) {
				double parameter = ((pose.x - downstream.x) * dx + (pose.y - downstream.y) * dy)
						/ lengthSquared;
				if (parameter >= 0.0 && parameter <= 1.0) {
					Coordinate projected = new Coordinate(downstream.x + parameter * dx,
							downstream.y + parameter * dy);
					double perpendicular = ContextCreator.getCityContext().getDistance(pose, projected);
					if (Double.isFinite(perpendicular) && perpendicular < bestPerpendicular) {
						bestPerpendicular = perpendicular;
						bestDistance = distanceFromEnd + parameter * segmentLength;
					}
				}
			}
			distanceFromEnd += segmentLength;
		}
		if (!Double.isFinite(bestDistance)) {
			double toEnd = ContextCreator.getCityContext().getDistance(pose,
					coordinates.get(coordinates.size() - 1));
			double toStart = ContextCreator.getCityContext().getDistance(pose, coordinates.get(0));
			if (toEnd <= toStart) {
				bestPerpendicular = toEnd;
				bestDistance = 0.0;
			} else {
				bestPerpendicular = toStart;
				bestDistance = lane.getLength();
			}
		}
		if (!Double.isFinite(bestPerpendicular) || !Double.isFinite(bestDistance)) return null;
		return new NativeReleaseProjection(lane,
				Math.max(0.0, Math.min(lane.getLength(), bestDistance)), bestPerpendicular);
	}

	private double findNativeReleaseDistance(Lane lane, double preferredDistance, double vehicleLength,
			List<double[]> occupiedIntervals) {
		double laneLength = lane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0) return Double.NaN;
		double candidate = Math.max(0.0, Math.min(laneLength, preferredDistance));
		double footprint = Math.max(0.0, vehicleLength);
		while (candidate <= laneLength + 0.001) {
			double shifted = candidate;
			if (occupiedIntervals != null) {
				for (double[] interval : occupiedIntervals) {
					boolean overlaps = candidate < interval[1] - 0.001
							&& interval[0] < candidate + footprint - 0.001;
					if (overlaps) shifted = Math.max(shifted, interval[1] + 0.001);
				}
			}
			if (shifted <= candidate + 1e-9) return Math.min(candidate, laneLength);
			candidate = shifted;
		}
		return Double.NaN;
	}

	private boolean isNativeReleaseLaneRouteCompatible(Vehicle vehicle, Lane lane) {
		Road followingRoad = vehicle.getNextRoad();
		if (followingRoad == null) return true;
		for (Integer downstreamLaneID : lane.getDownStreamLanes()) {
			Lane downstreamLane = ContextCreator.getLaneContext().get(downstreamLaneID);
			if (downstreamLane != null && downstreamLane.getRoad() == followingRoad) return true;
		}
		return false;
	}

	private static final class NativeReleaseProjection {
		final Lane lane;
		final double distance;
		final double perpendicularDistance;

		NativeReleaseProjection(Lane lane, double distance, double perpendicularDistance) {
			this.lane = lane;
			this.distance = distance;
			this.perpendicularDistance = perpendicularDistance;
		}
	}

	private static final class NativeReleasePlacement {
		final Vehicle vehicle;
		final Lane lane;
		final double distance;
		final boolean externalTransition;

		NativeReleasePlacement(Vehicle vehicle, Lane lane, double distance,
				boolean externalTransition) {
			this.vehicle = vehicle;
			this.lane = lane;
			this.distance = distance;
			this.externalTransition = externalTransition;
		}
	}
	
	public int getControlType() {
		return controlType;
	}

	/** Connector ownership changes after their external vehicles are adapted by the API. */
	protected synchronized void setControlTypeDirect(int controlType) {
		this.controlType = controlType;
	}

	public synchronized void recordTravelTime(Vehicle v) {
		this.travelTimeSum += v.getLinkTravelTime();
		this.travelTimeCount += 1;
		if (v.getVehicleSensorType() == Vehicle.MOBILEDEVICE && ContextCreator.kafkaManager != null) {
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
		 return this.enterRoadConflictBlocker(usroad, enteringVehicle,
				 ConnectorRoad.MovementPriority.UNKNOWN);
	}

	public Vehicle enterRoadConflictBlocker(Road usroad, Vehicle enteringVehicle,
			ConnectorRoad.MovementPriority enteringPriority) {
		 Junction prevJunction = ContextCreator.getJunctionContext().get(this.getUpStreamJunction());
		 int enteringIndex = this.upStreamRoadIndex(usroad);
		 for(int roadIndex = 0; roadIndex < this.upStreamRoads.size(); roadIndex++) {
			 Road r = this.upStreamRoads.get(roadIndex);
			 if(this.isSameRoad(r, usroad)) continue;
			 if(r.prevFirstVehicle()!= null) {
				Vehicle v = r.prevFirstVehicle();
				if(this.isSameVehicle(v, enteringVehicle)) continue;
				if(!v.wasPreviouslyOnRoad(r)) continue;
				if(prevJunction != null && v.aboutToEnterRoad(this)
						&& this.isConflictVehicleMovable(prevJunction, r, v)
						&& this.otherMovementOutranks(r, v, enteringPriority,
								roadIndex, enteringIndex)) {
					return v;
				}
			 }
		 }
		 return null;
	}

	private int upStreamRoadIndex(Road road) {
		for (int i = 0; i < this.upStreamRoads.size(); i++) {
			if (this.isSameRoad(this.upStreamRoads.get(i), road)) return i;
		}
		return this.upStreamRoads.size();
	}

	private boolean otherMovementOutranks(Road otherSource, Vehicle otherVehicle,
			ConnectorRoad.MovementPriority enteringPriority, int otherIndex,
			int enteringIndex) {
		ConnectorRoad otherConnector = ContextCreator.getRoadContext()
				.getConnector(otherSource, this);
		ConnectorRoad.MovementPriority otherPriority = otherConnector == null
				? ConnectorRoad.MovementPriority.UNKNOWN
				: otherConnector.getMovementPriority(otherVehicle.getLane(), null);
		if (otherPriority == ConnectorRoad.MovementPriority.BLOCKED) return false;
		if (enteringPriority == ConnectorRoad.MovementPriority.MAJOR
				&& otherPriority == ConnectorRoad.MovementPriority.MINOR) return false;
		if (enteringPriority == ConnectorRoad.MovementPriority.MINOR
				&& otherPriority == ConnectorRoad.MovementPriority.MAJOR) return true;
		return otherIndex < enteringIndex;
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
				return prevJunction.getMandatoryStopDelay(
						upstreamRoad.getID(), this.getID()) <= v.getStuckTime();
			case Junction.Yield:
			case Junction.Priority:
				return true;
			default:
				return true;
		}
	}
}
