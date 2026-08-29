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
	// Physical insertion is an endpoint concern, not a routing-topology concern.
	// Keep it separate from _canBeOrigin so an unusable spawn shape cannot remove
	// an otherwise valid road from routes that merely traverse it.
	private boolean hasUsableDepartureGeometry;
	
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
	/* Active native lane changes reserve their target lane in stable vehicle-ID order. */
	private TreeMap<Integer, TreeMap<Integer, Vehicle>> laneChangeReservations;
	private volatile boolean nativeReleaseInProgress;
	private int activeExternalLaneAdmissions;
	private int activeExternalLaneCommits;
	private static final double TRAVEL_TIME_HISTORY_HALF_LIFE_SECONDS = 900.0;
	private static final double TRAVEL_TIME_PRIOR_SAMPLE_STRENGTH = 3.0;
	private static final double TRAVEL_TIME_EFFECTIVE_SAMPLE_CAP = 100.0;
	private static final double TRAVEL_TIME_MIN_LIVE_SPEED_MPS = 0.5;
	private static final double TRAVEL_TIME_STOPPED_SPEED_MPS = 0.1;
	private static final double TRAVEL_TIME_MAX_SECONDS = 6.0 * 60.0 * 60.0;
	private static final double TRAVEL_TIME_PRIOR_CONFIDENCE = 0.15;
	private static final double TRAVEL_TIME_ETA_PERCENTILE = 0.90;
	private static final double TRAVEL_TIME_LIVE_SUPPORT_STRENGTH = 4.0;
	private static final double TRAVEL_TIME_MAX_INCREASE_FACTOR_PER_REFRESH = 3.0;
	private static final double TRAVEL_TIME_MAX_INCREASE_SECONDS_PER_REFRESH = 90.0;
	private static final double TRAVEL_TIME_MAX_DECREASE_FACTOR_PER_REFRESH = 0.50;
	private static final double TRAVEL_TIME_MAX_DECREASE_SECONDS_PER_REFRESH = 300.0;
	private static final int TRAVEL_TIME_HISTOGRAM_BUCKETS = 64;
	private static final double TRAVEL_TIME_HISTOGRAM_MIN_SECONDS = 0.25;
	private static final double TRAVEL_TIME_HISTOGRAM_MAX_SECONDS = TRAVEL_TIME_MAX_SECONDS;
	private static final double TRAVEL_TIME_HISTOGRAM_LOG_RANGE = Math.log(
			TRAVEL_TIME_HISTOGRAM_MAX_SECONDS / TRAVEL_TIME_HISTOGRAM_MIN_SECONDS);

	private volatile double travelTime;
	private volatile double travelTimeP90;
	private TreeMap<Integer, ArrayList<Vehicle>> departureVehMap; // Use this class to control the vehicle that entering
	private ConcurrentLinkedQueue<Vehicle> toAddDepartureVeh; // Tree map is not thread-safe, so use this 
	private final ArrayList<Vehicle> stepVehicleBuffer = new ArrayList<Vehicle>();
	private final ArrayList<Vehicle> departureBuffer = new ArrayList<Vehicle>();
	private double speedLimit_; // Speed for travel time estimation
	private double travelTimeSum;
	private double travelTimeSquareSum;
	private int travelTimeCount;
	private double completedTravelTimeMean;
	private double completedTravelTimeVariance;
	private double effectiveTravelTimeSampleCount;
	private float[] completedTravelTimeHistogram;
	private float[] pendingTravelTimeHistogram;
	private int lastTravelTimeSampleTick;
	private volatile int lastTravelTimeUpdateTick;
	private volatile double travelTimeConfidence;
	private volatile int liveTravelTimeVehicleCount;
	private volatile int liveTravelTimeStoppedCount;
	private volatile double liveTravelTimeLowerBound;
	private volatile double liveTravelTimeMeanSpeed;
	private double[] liveTravelTimeProjectionBuffer;
	private double[] liveTravelTimeBucketBuffer;
	/* Set once when this physical road first enters the estimator refresh set. */
	private volatile boolean travelTimeEstimatorRegistered;
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
		this.laneChangeReservations = new TreeMap<Integer, TreeMap<Integer, Vehicle>>();
		this.nativeReleaseInProgress = false;
		this.activeExternalLaneAdmissions = 0;
		this.activeExternalLaneCommits = 0;
		this.lastUpdateHour = -1;
		this.travelTime =  this.length / this.speedLimit_;
		this.travelTimeP90 = this.travelTime;
		this.travelTimeSum = 0.0;
		this.travelTimeSquareSum = 0.0;
		this.travelTimeCount = 0;
		this.completedTravelTimeMean = this.travelTime;
		this.completedTravelTimeVariance = 0.0;
		this.effectiveTravelTimeSampleCount = 0.0;
		this.completedTravelTimeHistogram = null;
		this.pendingTravelTimeHistogram = null;
		this.lastTravelTimeSampleTick = -1;
		this.lastTravelTimeUpdateTick = -1;
		this.travelTimeConfidence = TRAVEL_TIME_PRIOR_CONFIDENCE;
		this.liveTravelTimeVehicleCount = 0;
		this.liveTravelTimeStoppedCount = 0;
		this.liveTravelTimeLowerBound = 0.0;
		this.liveTravelTimeMeanSpeed = 0.0;
		this.liveTravelTimeProjectionBuffer = null;
		this.liveTravelTimeBucketBuffer = null;
		this.avgEnergyConsumption = 0.0;
		this.energyConsumptionSum = 0.0;
		this.energyConsumptionCount = 0;
		this.neighboringDepartureZone = 0;
		this.neighboringArrivalZone = 0;
		this.distToArrivalZone = Double.MAX_VALUE;
		this.distToDepartureZone = Double.MAX_VALUE;
		
		this._canBeDest = true;
		this._canBeOrigin = true;
		this.hasUsableDepartureGeometry = true;
		
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
		this.setLength(length);
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
				if (this instanceof ConnectorRoad) {
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
		if (this.upStreamNode != node) {
			this.upStreamNode = node;
			markPhysicalTopologyChanged();
		}
	}
	
	public Node getDownStreamNode() {
		return downStreamNode;
	}
	
	public void setDownStreamNode(Node node) {
		if (this.downStreamNode != node) {
			this.downStreamNode = node;
			markPhysicalTopologyChanged();
		}
	}
	
	public synchronized void setLength(double length) {
		// A malformed imported / dynamically assigned length must not poison every
		// downstream speed and routing calculation. Zero remains a valid connector
		// length; only negative and non-finite values are normalized.
		this.length = Double.isFinite(length) && length >= 0.0 ? length : 0.0;
		this.resetTravelTimeEstimator();
		this.refreshDefaultParkingCapacity();
		markPhysicalTopologyChanged();
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
		if (!this.downStreamRoads.contains(dsRoad)) {
			this.downStreamRoads.add(dsRoad);
			markPhysicalTopologyChanged();
		}
	}

	public void removeDownStreamRoad(int dsRoad) {
		if (this.downStreamRoads.remove(Integer.valueOf(dsRoad))) {
			markPhysicalTopologyChanged();
		}
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

	/** A physical road that can host the start of a vehicle trip. */
	public boolean canBeTripOrigin() {
		return !(this instanceof ConnectorRoad) && this._canBeOrigin
				&& this.hasUsableDepartureGeometry;
	}

	/** A physical road that can host the end of a vehicle trip. */
	public boolean canBeTripDestination() {
		return !(this instanceof ConnectorRoad) && this._canBeDest;
	}
	
	public void setCanBeOrigin(Boolean b) {
		this._canBeOrigin = b;
	}
	
	public void setCanBeDest(Boolean b) {
		this._canBeDest = b;
	}

	public boolean hasUsableDepartureGeometry() {
		return this.hasUsableDepartureGeometry;
	}

	public void setHasUsableDepartureGeometry(boolean usable) {
		this.hasUsableDepartureGeometry = usable;
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
	/** Publish an active native lane change without changing lane-list membership. */
	public synchronized boolean registerLaneChangeReservation(Lane targetLane, Vehicle vehicle) {
		if (targetLane == null || vehicle == null || targetLane.getRoad() != this
				|| vehicle.getRoad() != this) return false;
		TreeMap<Integer, Vehicle> laneReservations =
				this.laneChangeReservations.get(targetLane.getID());
		if (laneReservations == null) {
			laneReservations = new TreeMap<Integer, Vehicle>();
			this.laneChangeReservations.put(targetLane.getID(), laneReservations);
		}
		Vehicle existing = laneReservations.get(vehicle.getID());
		if (existing != null && existing != vehicle) return false;
		laneReservations.put(vehicle.getID(), vehicle);
		return true;
	}

	/** Remove a native lane-change reservation only when owned by this vehicle. */
	public synchronized void unregisterLaneChangeReservation(Vehicle vehicle) {
		if (vehicle == null) return;
		Lane targetLane = vehicle.getLaneChangeTargetLane();
		if (targetLane != null) {
			TreeMap<Integer, Vehicle> laneReservations =
					this.laneChangeReservations.get(targetLane.getID());
			if (laneReservations != null && laneReservations.get(vehicle.getID()) == vehicle) {
				laneReservations.remove(vehicle.getID());
				if (laneReservations.isEmpty()) this.laneChangeReservations.remove(targetLane.getID());
				return;
			}
		}
		Iterator<Map.Entry<Integer, TreeMap<Integer, Vehicle>>> iterator =
				this.laneChangeReservations.entrySet().iterator();
		while (iterator.hasNext()) {
			TreeMap<Integer, Vehicle> laneReservations = iterator.next().getValue();
			if (laneReservations.get(vehicle.getID()) == vehicle) laneReservations.remove(vehicle.getID());
			if (laneReservations.isEmpty()) iterator.remove();
		}
	}

	/** Nearest reserved vehicle ahead at the supplied logical target-lane station. */
	public synchronized Vehicle findLaneChangeReservedLeader(
			Lane targetLane, Vehicle requester, double requesterDistance) {
		if (targetLane == null || targetLane.getRoad() != this
				|| !Double.isFinite(requesterDistance)) return null;
		TreeMap<Integer, Vehicle> laneReservations =
				this.laneChangeReservations.get(targetLane.getID());
		if (laneReservations == null) return null;
		Vehicle best = null;
		double bestDistance = Double.NEGATIVE_INFINITY;
		for (Vehicle candidate : laneReservations.values()) {
			if (candidate == null || candidate == requester) continue;
			double candidateDistance = candidate.getLaneChangeReservedDistance(targetLane);
			if (Double.isFinite(candidateDistance) && candidateDistance <= requesterDistance
					&& candidateDistance > bestDistance) {
				best = candidate;
				bestDistance = candidateDistance;
			}
		}
		return best;
	}

	/** Nearest reserved vehicle behind at the supplied logical target-lane station. */
	public synchronized Vehicle findLaneChangeReservedLag(
			Lane targetLane, Vehicle requester, double requesterDistance) {
		if (targetLane == null || targetLane.getRoad() != this
				|| !Double.isFinite(requesterDistance)) return null;
		TreeMap<Integer, Vehicle> laneReservations =
				this.laneChangeReservations.get(targetLane.getID());
		if (laneReservations == null) return null;
		Vehicle best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (Vehicle candidate : laneReservations.values()) {
			if (candidate == null || candidate == requester) continue;
			double candidateDistance = candidate.getLaneChangeReservedDistance(targetLane);
			if (Double.isFinite(candidateDistance) && candidateDistance > requesterDistance
					&& candidateDistance < bestDistance) {
				best = candidate;
				bestDistance = candidateDistance;
			}
		}
		return best;
	}

	/** Return any other vehicle currently reserving the supplied lane. */
	public synchronized Vehicle getLaneChangeReservationBlocker(
			Lane targetLane, Vehicle requester) {
		if (targetLane == null || targetLane.getRoad() != this) return null;
		TreeMap<Integer, Vehicle> laneReservations =
				this.laneChangeReservations.get(targetLane.getID());
		if (laneReservations == null) return null;
		for (Vehicle candidate : laneReservations.values()) {
			if (candidate != null && candidate != requester) return candidate;
		}
		return null;
	}

	private synchronized ArrayList<Vehicle> activeLaneChangeVehicles() {
		TreeMap<Integer, Vehicle> vehiclesByID = new TreeMap<Integer, Vehicle>();
		for (TreeMap<Integer, Vehicle> laneReservations : this.laneChangeReservations.values()) {
			vehiclesByID.putAll(laneReservations);
		}
		return new ArrayList<Vehicle>(vehiclesByID.values());
	}

	public synchronized int getLaneChangeReservationCount() {
		int count = 0;
		for (TreeMap<Integer, Vehicle> laneReservations : this.laneChangeReservations.values()) {
			count += laneReservations.size();
		}
		return count;
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
		double currentSpeedLimit = Double.isFinite(this.speedLimit_)
				&& this.speedLimit_ >= 0.0 ? this.speedLimit_ : 0.0;
		this.speedLimit_ = Double.isFinite(restoredSpeedLimit)
				&& restoredSpeedLimit >= 0.0 ? restoredSpeedLimit : currentSpeedLimit;
		double restoredFreeFlow = freeFlowTravelTime();
		double safeRestoredTravelTime = Double.isFinite(restoredTravelTime)
				&& restoredTravelTime >= 0.0 ? restoredTravelTime : restoredFreeFlow;
		this.travelTime = safeRestoredTravelTime;
		this.travelTimeP90 = safeRestoredTravelTime;
		this.travelTimeSum = 0.0;
		this.travelTimeSquareSum = 0.0;
		this.travelTimeCount = 0;
		this.completedTravelTimeMean = safeRestoredTravelTime;
		this.completedTravelTimeVariance = 0.0;
		this.effectiveTravelTimeSampleCount = 0.0;
		this.completedTravelTimeHistogram = null;
		this.pendingTravelTimeHistogram = null;
		this.lastTravelTimeSampleTick = -1;
		this.lastTravelTimeUpdateTick = ContextCreator.getCurrentTick();
		this.travelTimeConfidence = TRAVEL_TIME_PRIOR_CONFIDENCE;
		this.liveTravelTimeVehicleCount = 0;
		this.liveTravelTimeStoppedCount = 0;
		this.liveTravelTimeLowerBound = 0.0;
		this.liveTravelTimeMeanSpeed = 0.0;
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

	/** Restore the optional travel-time estimator state saved by newer snapshots. */
	public synchronized void restoreTravelTimeEstimatorState(double restoredCompletedMean,
			double restoredCompletedVariance, double restoredEffectiveSampleCount,
			int restoredLastSampleTick, int restoredLastUpdateTick,
			double restoredConfidence, int restoredLiveVehicleCount,
			int restoredLiveStoppedCount, double restoredLiveLowerBound,
			double restoredLiveMeanSpeed, double restoredPendingSum,
			double restoredPendingSquareSum, int restoredPendingCount) {
		restoreTravelTimeEstimatorState(restoredCompletedMean, restoredCompletedVariance,
				restoredEffectiveSampleCount, restoredLastSampleTick, restoredLastUpdateTick,
				restoredConfidence, restoredLiveVehicleCount, restoredLiveStoppedCount,
				restoredLiveLowerBound, restoredLiveMeanSpeed, restoredPendingSum,
				restoredPendingSquareSum, restoredPendingCount, Double.NaN, null, null);
	}

	/** Restore estimator version 2, including the ETA percentile distribution. */
	public synchronized void restoreTravelTimeEstimatorState(double restoredCompletedMean,
			double restoredCompletedVariance, double restoredEffectiveSampleCount,
			int restoredLastSampleTick, int restoredLastUpdateTick,
			double restoredConfidence, int restoredLiveVehicleCount,
			int restoredLiveStoppedCount, double restoredLiveLowerBound,
			double restoredLiveMeanSpeed, double restoredPendingSum,
			double restoredPendingSquareSum, int restoredPendingCount,
			double restoredTravelTimeP90, float[] restoredCompletedHistogram,
			float[] restoredPendingHistogram) {
		double prior = freeFlowTravelTime();
		this.completedTravelTimeMean = finiteNonNegative(restoredCompletedMean,
				Math.max(prior, this.travelTime));
		this.completedTravelTimeVariance = finiteNonNegative(restoredCompletedVariance, 0.0);
		this.effectiveTravelTimeSampleCount = clamp(
				finiteNonNegative(restoredEffectiveSampleCount, 0.0),
				0.0, TRAVEL_TIME_EFFECTIVE_SAMPLE_CAP);
		this.lastTravelTimeSampleTick = restoredLastSampleTick;
		this.lastTravelTimeUpdateTick = restoredLastUpdateTick;
		this.travelTimeConfidence = clamp(finiteNonNegative(restoredConfidence,
				TRAVEL_TIME_PRIOR_CONFIDENCE), TRAVEL_TIME_PRIOR_CONFIDENCE, 0.95);
		this.liveTravelTimeVehicleCount = Math.max(0, restoredLiveVehicleCount);
		this.liveTravelTimeStoppedCount = Math.min(this.liveTravelTimeVehicleCount,
				Math.max(0, restoredLiveStoppedCount));
		this.liveTravelTimeLowerBound = finiteNonNegative(restoredLiveLowerBound, 0.0);
		this.liveTravelTimeMeanSpeed = finiteNonNegative(restoredLiveMeanSpeed, 0.0);
		this.travelTimeSum = finiteNonNegative(restoredPendingSum, 0.0);
		this.travelTimeSquareSum = finiteNonNegative(restoredPendingSquareSum, 0.0);
		this.travelTimeCount = Math.max(0, restoredPendingCount);
		this.pendingTravelTimeHistogram = sanitizeHistogram(restoredPendingHistogram);
		if (this.pendingTravelTimeHistogram == null && this.travelTimeCount > 0) {
			this.pendingTravelTimeHistogram = new float[TRAVEL_TIME_HISTOGRAM_BUCKETS];
			double pendingMeanSeconds = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 0.0001)
					* this.travelTimeSum / this.travelTimeCount;
			this.pendingTravelTimeHistogram[travelTimeHistogramBucket(pendingMeanSeconds)] =
					(float) this.travelTimeCount;
		}
		this.completedTravelTimeHistogram = sanitizeHistogram(restoredCompletedHistogram);
		if (this.completedTravelTimeHistogram == null
				&& this.effectiveTravelTimeSampleCount > 0.0001) {
			this.completedTravelTimeHistogram = new float[TRAVEL_TIME_HISTOGRAM_BUCKETS];
			double legacyP90 = completedP90FromMoments(this.completedTravelTimeMean,
					this.completedTravelTimeVariance);
			this.completedTravelTimeHistogram[travelTimeHistogramBucket(legacyP90)] =
					(float) this.effectiveTravelTimeSampleCount;
		}
		this.travelTimeP90 = Math.max(this.travelTime,
				finiteNonNegative(restoredTravelTimeP90,
						completedP90FromMoments(this.completedTravelTimeMean,
								this.completedTravelTimeVariance)));
		if (this.travelTimeCount > 0) this.onTravelTimeObservationRecorded();
		if (hasTravelTimeEstimatorEvidence()) markTravelTimeEstimatorRelevant();
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
		this.laneChangeReservations.clear();
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
		double currentLength = this.length;
		double currentTravelTime = this.travelTime;
		if (Double.isFinite(currentLength) && currentLength >= 0.0
				&& Double.isFinite(currentTravelTime) && currentTravelTime >= 0.0) {
			double calculatedSpeed = currentLength / Math.max(currentTravelTime, 0.0001);
			if (Double.isFinite(calculatedSpeed)) {
				return Math.max(calculatedSpeed, 0.0001);
			}
		}
		double fallbackSpeed = Double.isFinite(this.speedLimit_)
				&& this.speedLimit_ > 0.0 ? this.speedLimit_ : 0.0001;
		return Math.max(fallbackSpeed, 0.0001);
	}

	/**
	 * Update the routing travel-time estimate from completed traversals and vehicles that
	 * are still on the road. The latter are censored observations: their elapsed time is
	 * already a lower bound on their eventual traversal time. This prevents a blocked road
	 * with no exits from incorrectly reverting to free flow.
	 */
	public synchronized boolean updateTravelTimeEstimation() {
		return updateTravelTimeEstimationAtTick(ContextCreator.getCurrentTick());
	}

	/* Package-private clock injection keeps the estimator math independently testable. */
	synchronized boolean updateTravelTimeEstimationAtTick(int currentTick) {
		final double stepSize = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 0.0001);
		final double refreshSeconds = Math.max(stepSize,
				GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL * stepSize);
		final double elapsedUpdateSeconds = this.lastTravelTimeUpdateTick >= 0
				? Math.max(stepSize, (currentTick - this.lastTravelTimeUpdateTick) * stepSize)
				: refreshSeconds;
		this.lastTravelTimeUpdateTick = currentTick;

		// Old samples lose influence gradually instead of disappearing at a refresh boundary.
		double historyDecay = Math.exp(-Math.log(2.0) * elapsedUpdateSeconds
				/ TRAVEL_TIME_HISTORY_HALF_LIFE_SECONDS);
		this.effectiveTravelTimeSampleCount *= historyDecay;
		scaleHistogram(this.completedTravelTimeHistogram, historyDecay);

		if (this.travelTimeCount > 0) {
			double batchMean = stepSize * this.travelTimeSum / this.travelTimeCount;
			double batchSecondMoment = stepSize * stepSize * this.travelTimeSquareSum
					/ this.travelTimeCount;
			double batchVariance = Math.max(0.0, batchSecondMoment - batchMean * batchMean);
			double incomingWeight = Math.min(25.0, this.travelTimeCount);
			double oldWeight = this.effectiveTravelTimeSampleCount;
			if (oldWeight <= 0.0001 || !Double.isFinite(this.completedTravelTimeMean)) {
				this.completedTravelTimeMean = batchMean;
				this.completedTravelTimeVariance = batchVariance;
				this.effectiveTravelTimeSampleCount = incomingWeight;
			}
			else {
				double oldMean = this.completedTravelTimeMean;
				double totalWeight = oldWeight + incomingWeight;
				double combinedMean = (oldWeight * oldMean + incomingWeight * batchMean)
						/ totalWeight;
				double combinedVariance = (oldWeight * (this.completedTravelTimeVariance
						+ square(oldMean - combinedMean))
						+ incomingWeight * (batchVariance + square(batchMean - combinedMean)))
						/ totalWeight;
				this.completedTravelTimeMean = combinedMean;
				this.completedTravelTimeVariance = Math.max(0.0, combinedVariance);
				this.effectiveTravelTimeSampleCount = Math.min(
						TRAVEL_TIME_EFFECTIVE_SAMPLE_CAP, totalWeight);
			}
			mergePendingTravelTimeHistogram(incomingWeight);
			this.lastTravelTimeSampleTick = currentTick;
		}
		this.travelTimeSum = 0.0;
		this.travelTimeSquareSum = 0.0;
		this.travelTimeCount = 0;
		this.pendingTravelTimeHistogram = null;

		TravelTimeLiveEvidence liveObservations = null;
		Iterable<Vehicle> directObservations = this.travelTimeObservationVehicles();
		if (directObservations != null) {
			for (Vehicle vehicle : directObservations) {
				liveObservations = this.addTravelTimeLiveObservation(
						liveObservations, vehicle, stepSize);
			}
		}
		else {
			Vehicle currentVehicle = this.firstVehicle();
			int scanLimit = Math.max(16, Math.max(0, this.getVehicleNum()) + 16);
			int scanned = 0;
			while (currentVehicle != null && scanned < scanLimit) {
				Vehicle nextVehicle = currentVehicle.macroTrailing();
				liveObservations = this.addTravelTimeLiveObservation(
						liveObservations, currentVehicle, stepSize);
				currentVehicle = nextVehicle;
				scanned++;
			}
		}
		int liveCount = liveObservations == null ? 0 : liveObservations.liveCount;
		int stoppedCount = liveObservations == null ? 0 : liveObservations.stoppedCount;
		double liveSpeedSum = liveObservations == null ? 0.0 : liveObservations.liveSpeedSum;
		double liveProjectionSum = liveObservations == null
				? 0.0 : liveObservations.liveProjectionSum;
		double liveElapsedMaximum = liveObservations == null
				? 0.0 : liveObservations.liveElapsedMaximum;
		double[] liveProjectedTravelTimes = liveObservations == null
				? null : liveObservations.projectedTravelTimes;
		int liveProjectionCount = liveObservations == null
				? 0 : liveObservations.projectedCount;
		this.liveTravelTimeVehicleCount = liveCount;
		this.liveTravelTimeStoppedCount = stoppedCount;
		this.liveTravelTimeLowerBound = liveElapsedMaximum;
		this.liveTravelTimeMeanSpeed = liveCount > 0 ? liveSpeedSum / liveCount : 0.0;

		double prior = freeFlowTravelTime();
		double previousEstimate = Math.max(prior,
				finiteNonNegative(this.travelTime, prior));
		double historyReliability = this.effectiveTravelTimeSampleCount
				/ (this.effectiveTravelTimeSampleCount + TRAVEL_TIME_PRIOR_SAMPLE_STRENGTH);
		double completedMean = Math.max(prior,
				finiteNonNegative(this.completedTravelTimeMean, prior));
		double newTravelTime = prior + historyReliability * (completedMean - prior);
		double liveWeight = 0.0;
		double queueProjection = prior;

		if (liveCount > 0 && liveProjectedTravelTimes != null
				&& liveProjectionCount > 0) {
			double stoppedFraction = (double) stoppedCount / liveCount;
			double liveSupport = (double) liveCount
					/ (liveCount + TRAVEL_TIME_LIVE_SUPPORT_STRENGTH);
			double liveMeanProjection = liveProjectionSum
					/ liveProjectionCount;
			// Queue pressure grows smoothly with both stopped share and sample support;
			// one stopped vehicle can no longer turn free flow into a 20x estimate.
			queueProjection = newTravelTime
					* (1.0 + 2.0 * stoppedFraction * liveSupport);
			double liveEstimate = Math.max(prior,
					Math.max(liveMeanProjection, queueProjection));
			// A well-supported stopped queue must be able to dominate stale completed
			// traversals. Keep freely moving censored observations conservative, but
			// increase their reliability continuously with the stopped share. Because
			// liveSupport is strictly below one, liveWeight remains safe for the
			// percentile odds conversion without an artificial upper cap.
			liveWeight = liveSupport * (0.55 + 0.45 * stoppedFraction);
			newTravelTime = (1.0 - liveWeight) * newTravelTime + liveWeight * liveEstimate;
		}

		double maximumEstimate = Math.max(TRAVEL_TIME_MAX_SECONDS, prior * 120.0);
		newTravelTime = clamp(finiteNonNegative(newTravelTime, prior), prior, maximumEstimate);
		double refreshIntervals = Math.max(1.0, elapsedUpdateSeconds / refreshSeconds);
		double maximumIncrease = Math.min(
				previousEstimate * Math.pow(TRAVEL_TIME_MAX_INCREASE_FACTOR_PER_REFRESH,
						refreshIntervals),
				previousEstimate + TRAVEL_TIME_MAX_INCREASE_SECONDS_PER_REFRESH
						* refreshIntervals);
		double minimumDecrease = Math.max(prior, Math.max(
				previousEstimate * Math.pow(TRAVEL_TIME_MAX_DECREASE_FACTOR_PER_REFRESH,
						refreshIntervals),
				previousEstimate - TRAVEL_TIME_MAX_DECREASE_SECONDS_PER_REFRESH
						* refreshIntervals));
		newTravelTime = clamp(newTravelTime, minimumDecrease,
				Math.min(maximumEstimate, Math.max(prior, maximumIncrease)));

		double previousP90 = Math.max(previousEstimate,
				finiteNonNegative(this.travelTimeP90, previousEstimate));
		double newTravelTimeP90 = weightedTravelTimePercentile(prior, liveWeight,
				queueProjection, liveProjectedTravelTimes, liveProjectionCount,
				TRAVEL_TIME_ETA_PERCENTILE);
		newTravelTimeP90 = clamp(finiteNonNegative(newTravelTimeP90, newTravelTime),
				newTravelTime, maximumEstimate);
		double maximumP90Increase = Math.min(
				previousP90 * Math.pow(TRAVEL_TIME_MAX_INCREASE_FACTOR_PER_REFRESH,
						refreshIntervals),
				previousP90 + TRAVEL_TIME_MAX_INCREASE_SECONDS_PER_REFRESH
						* refreshIntervals);
		double minimumP90Decrease = Math.max(newTravelTime, Math.max(
				previousP90 * Math.pow(TRAVEL_TIME_MAX_DECREASE_FACTOR_PER_REFRESH,
						refreshIntervals),
				previousP90 - TRAVEL_TIME_MAX_DECREASE_SECONDS_PER_REFRESH
						* refreshIntervals));
		newTravelTimeP90 = clamp(newTravelTimeP90, minimumP90Decrease,
				Math.max(minimumP90Decrease, Math.min(maximumEstimate,
						Math.max(newTravelTime, maximumP90Increase))));

		double sampleAgeSeconds = this.lastTravelTimeSampleTick < 0 ? Double.POSITIVE_INFINITY
				: Math.max(0, currentTick - this.lastTravelTimeSampleTick) * stepSize;
		double freshness = Double.isFinite(sampleAgeSeconds)
				? Math.exp(-Math.log(2.0) * sampleAgeSeconds
						/ TRAVEL_TIME_HISTORY_HALF_LIFE_SECONDS) : 0.0;
		double coefficientOfVariation = completedMean > 0.0
				? Math.sqrt(Math.max(0.0, this.completedTravelTimeVariance)) / completedMean : 1.0;
		double stability = 1.0 / (1.0 + coefficientOfVariation);
		double completedEvidence = historyReliability * freshness * stability;
		double liveEvidence = 0.65 * liveCount / (liveCount + 3.0);
		double combinedEvidence = 1.0 - (1.0 - completedEvidence) * (1.0 - liveEvidence);
		this.travelTimeConfidence = clamp(TRAVEL_TIME_PRIOR_CONFIDENCE
				+ 0.80 * combinedEvidence, TRAVEL_TIME_PRIOR_CONFIDENCE, 0.95);

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
		
		boolean changed = Math.abs(this.travelTime - newTravelTime)
				> 0.000001 * Math.max(1.0, Math.abs(this.travelTime));
		this.travelTime = newTravelTime;
		this.travelTimeP90 = newTravelTimeP90;
		return changed;
	}

	/**
	 * Optional direct source for live estimator observations. Physical roads use
	 * their macro linked list; connector roads override this with their actual
	 * active-vehicle collection.
	 */
	protected Iterable<Vehicle> travelTimeObservationVehicles() {
		return null;
	}

	/** Connector hook used by read accessors to age skipped estimator state. */
	protected void prepareTravelTimeEstimateForRead() {
		// Physical roads are refreshed on the fixed network schedule.
	}

	protected boolean hasUpdatedTravelTimeEstimate() {
		return this.lastTravelTimeUpdateTick >= 0;
	}

	/**
	 * Apply all skipped decay in one operation when an inactive segment is next
	 * read. The elapsed-tick calculation in the estimator preserves the same
	 * half-life without requiring an update on every refresh boundary.
	 */
	protected synchronized boolean refreshTravelTimeEstimationIfStale(int currentTick) {
		if (this.lastTravelTimeUpdateTick < 0 || currentTick <= this.lastTravelTimeUpdateTick) {
			return false;
		}
		int refreshInterval = this.travelTimeRefreshIntervalTicks();
		if (currentTick - this.lastTravelTimeUpdateTick < refreshInterval) return false;
		return this.updateTravelTimeEstimationAtTick(currentTick);
	}

	protected int travelTimeRefreshIntervalTicks() {
		return Math.max(1, GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL);
	}

	private TravelTimeLiveEvidence addTravelTimeLiveObservation(
			TravelTimeLiveEvidence evidence, Vehicle vehicle, double stepSize) {
		if (vehicle == null || !vehicle.isTravelTimeObservationEligible(this)) return evidence;
		double speed = vehicle.currentSpeed();
		if (!Double.isFinite(speed) || speed < 0.0) return evidence;
		if (evidence == null) {
			evidence = new TravelTimeLiveEvidence(this.liveTravelTimeProjectionBuffer);
		}
		evidence.liveSpeedSum += speed;
		evidence.liveCount++;
		if (speed <= TRAVEL_TIME_STOPPED_SPEED_MPS) evidence.stoppedCount++;
		double elapsed = vehicle.getLinkTravelTime() * stepSize;
		if (Double.isFinite(elapsed) && elapsed > evidence.liveElapsedMaximum) {
			evidence.liveElapsedMaximum = elapsed;
		}
		double remainingDistance = Math.max(0.0,
				vehicle.getDistanceToNextJunction());
		double projectionSpeedFloor = Math.max(TRAVEL_TIME_MIN_LIVE_SPEED_MPS,
				Math.min(2.0, Math.max(0.0, this.speedLimit_) * 0.20));
		double projectedTravelTime = elapsed + remainingDistance
				/ Math.max(speed, projectionSpeedFloor);
		if (Double.isFinite(projectedTravelTime) && projectedTravelTime >= 0.0) {
			evidence.addProjection(projectedTravelTime);
			this.liveTravelTimeProjectionBuffer = evidence.projectedTravelTimes;
			evidence.liveProjectionSum += projectedTravelTime;
		}
		return evidence;
	}

	private static final class TravelTimeLiveEvidence {
		int liveCount;
		int stoppedCount;
		double liveSpeedSum;
		double liveProjectionSum;
		double liveElapsedMaximum;
		double[] projectedTravelTimes;
		int projectedCount;

		TravelTimeLiveEvidence(double[] reusableProjectionBuffer) {
			this.projectedTravelTimes = reusableProjectionBuffer;
		}

		void addProjection(double projection) {
			if (this.projectedTravelTimes == null) {
				this.projectedTravelTimes = new double[8];
			}
			else if (this.projectedCount >= this.projectedTravelTimes.length) {
				this.projectedTravelTimes = Arrays.copyOf(this.projectedTravelTimes,
						this.projectedTravelTimes.length * 2);
			}
			this.projectedTravelTimes[this.projectedCount++] = projection;
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

	/** P90 traversal time used as an upper ETA estimate, in simulation seconds. */
	public double getTravelTimeP90() {
		return this.travelTimeP90;
	}

	public double getTravelTimeConfidence() {
		return this.travelTimeConfidence;
	}

	public synchronized double getTravelTimeEffectiveSampleCount() {
		return this.effectiveTravelTimeSampleCount;
	}

	public synchronized double getTravelTimeSampleAgeSeconds() {
		if (this.lastTravelTimeSampleTick < 0) return -1.0;
		return Math.max(0, ContextCreator.getCurrentTick() - this.lastTravelTimeSampleTick)
				* Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 0.0001);
	}

	public int getTravelTimeLiveVehicleCount() {
		return this.liveTravelTimeVehicleCount;
	}

	public double getTravelTimeStoppedFraction() {
		return this.liveTravelTimeVehicleCount > 0
				? (double) this.liveTravelTimeStoppedCount / this.liveTravelTimeVehicleCount : 0.0;
	}

	public double getTravelTimeLiveLowerBound() {
		return this.liveTravelTimeLowerBound;
	}

	public double getTravelTimeLiveMeanSpeed() {
		return this.liveTravelTimeMeanSpeed;
	}

	public synchronized String getTravelTimeEstimateSource() {
		boolean hasCompleted = this.effectiveTravelTimeSampleCount > 0.05;
		boolean hasLive = this.liveTravelTimeVehicleCount > 0;
		if (hasCompleted && hasLive) return "completed_and_live";
		if (hasLive) return "live_censored";
		if (hasCompleted) return "completed_history";
		return "free_flow_prior";
	}

	public synchronized double getCompletedTravelTimeMean() {
		return this.completedTravelTimeMean;
	}

	public synchronized double getCompletedTravelTimeVariance() {
		return this.completedTravelTimeVariance;
	}

	public synchronized int getLastTravelTimeSampleTick() {
		return this.lastTravelTimeSampleTick;
	}

	public synchronized int getLastTravelTimeUpdateTick() {
		return this.lastTravelTimeUpdateTick;
	}

	public synchronized int getTravelTimeLiveStoppedCount() {
		return this.liveTravelTimeStoppedCount;
	}

	public synchronized double getPendingTravelTimeSum() {
		return this.travelTimeSum;
	}

	public synchronized double getPendingTravelTimeSquareSum() {
		return this.travelTimeSquareSum;
	}

	public synchronized int getPendingTravelTimeCount() {
		return this.travelTimeCount;
	}

	public synchronized float[] getCompletedTravelTimeHistogramSnapshot() {
		return this.completedTravelTimeHistogram == null ? null
				: this.completedTravelTimeHistogram.clone();
	}

	public synchronized float[] getPendingTravelTimeHistogramSnapshot() {
		return this.pendingTravelTimeHistogram == null ? null
				: this.pendingTravelTimeHistogram.clone();
	}

	private synchronized void resetTravelTimeEstimator() {
		double prior = freeFlowTravelTime();
		this.travelTime = prior;
		this.travelTimeP90 = prior;
		this.travelTimeSum = 0.0;
		this.travelTimeSquareSum = 0.0;
		this.travelTimeCount = 0;
		this.completedTravelTimeMean = prior;
		this.completedTravelTimeVariance = 0.0;
		this.effectiveTravelTimeSampleCount = 0.0;
		this.completedTravelTimeHistogram = null;
		this.pendingTravelTimeHistogram = null;
		this.lastTravelTimeSampleTick = -1;
		this.lastTravelTimeUpdateTick = -1;
		this.travelTimeConfidence = TRAVEL_TIME_PRIOR_CONFIDENCE;
		this.liveTravelTimeVehicleCount = 0;
		this.liveTravelTimeStoppedCount = 0;
		this.liveTravelTimeLowerBound = 0.0;
		this.liveTravelTimeMeanSpeed = 0.0;
	}

	synchronized boolean hasTravelTimeEstimatorEvidence() {
		return this.effectiveTravelTimeSampleCount > 0.0 || this.travelTimeCount > 0
				|| this.lastTravelTimeSampleTick >= 0 || this.liveTravelTimeVehicleCount > 0
				|| this.energyConsumptionCount > 0;
	}

	private void markTravelTimeEstimatorRelevant() {
		RoadContext roadContext = ContextCreator.getRoadContext();
		if (roadContext == null) return;
		roadContext.markTravelTimeEstimatorRelevant(this);
	}

	boolean claimTravelTimeEstimatorRegistration() {
		if (this.travelTimeEstimatorRegistered) return false;
		synchronized (this) {
			if (this.travelTimeEstimatorRegistered) return false;
			this.travelTimeEstimatorRegistered = true;
			return true;
		}
	}

	void clearTravelTimeEstimatorRegistration() {
		this.travelTimeEstimatorRegistered = false;
	}

	private void markPhysicalTopologyChanged() {
		RoadContext roadContext = ContextCreator.getRoadContext();
		if (roadContext != null) roadContext.markPhysicalTopologyChanged(this);
	}

	private double freeFlowTravelTime() {
		if (!Double.isFinite(this.length) || this.length <= 0.0
				|| !Double.isFinite(this.speedLimit_) || this.speedLimit_ <= 0.0) return 0.0;
		return this.length / this.speedLimit_;
	}

	private static double finiteNonNegative(double value, double fallback) {
		return Double.isFinite(value) && value >= 0.0 ? value : fallback;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static double square(double value) {
		return value * value;
	}

	private static int travelTimeHistogramBucket(double seconds) {
		if (!Double.isFinite(seconds) || seconds <= TRAVEL_TIME_HISTOGRAM_MIN_SECONDS) return 0;
		if (seconds >= TRAVEL_TIME_HISTOGRAM_MAX_SECONDS) {
			return TRAVEL_TIME_HISTOGRAM_BUCKETS - 1;
		}
		double fraction = Math.log(seconds / TRAVEL_TIME_HISTOGRAM_MIN_SECONDS)
				/ TRAVEL_TIME_HISTOGRAM_LOG_RANGE;
		return Math.max(0, Math.min(TRAVEL_TIME_HISTOGRAM_BUCKETS - 1,
				(int) Math.floor(fraction * TRAVEL_TIME_HISTOGRAM_BUCKETS)));
	}

	private static double travelTimeHistogramUpperBound(int bucket) {
		if (bucket >= TRAVEL_TIME_HISTOGRAM_BUCKETS - 1) {
			return TRAVEL_TIME_HISTOGRAM_MAX_SECONDS;
		}
		return TRAVEL_TIME_HISTOGRAM_MIN_SECONDS * Math.exp(
				TRAVEL_TIME_HISTOGRAM_LOG_RANGE * (bucket + 1)
						/ TRAVEL_TIME_HISTOGRAM_BUCKETS);
	}

	private static void scaleHistogram(float[] histogram, double scale) {
		if (histogram == null) return;
		float finiteScale = (float) Math.max(0.0, finiteNonNegative(scale, 0.0));
		for (int i = 0; i < histogram.length; i++) histogram[i] *= finiteScale;
	}

	private static double histogramWeight(float[] histogram) {
		if (histogram == null) return 0.0;
		double total = 0.0;
		for (float weight : histogram) {
			if (Float.isFinite(weight) && weight > 0.0f) total += weight;
		}
		return total;
	}

	private void mergePendingTravelTimeHistogram(double incomingWeight) {
		double pendingWeight = histogramWeight(this.pendingTravelTimeHistogram);
		if (pendingWeight <= 0.0 || incomingWeight <= 0.0) return;
		if (this.completedTravelTimeHistogram == null) {
			this.completedTravelTimeHistogram = new float[TRAVEL_TIME_HISTOGRAM_BUCKETS];
		}
		double incomingScale = incomingWeight / pendingWeight;
		for (int i = 0; i < TRAVEL_TIME_HISTOGRAM_BUCKETS; i++) {
			this.completedTravelTimeHistogram[i] +=
					(float) (this.pendingTravelTimeHistogram[i] * incomingScale);
		}
		double combinedWeight = histogramWeight(this.completedTravelTimeHistogram);
		if (combinedWeight > 0.0 && this.effectiveTravelTimeSampleCount >= 0.0) {
			scaleHistogram(this.completedTravelTimeHistogram,
					this.effectiveTravelTimeSampleCount / combinedWeight);
		}
	}

	private double weightedTravelTimePercentile(double prior, double liveWeight,
			double queueProjection, double[] liveProjectedTravelTimes,
			int liveProjectionCount, double fraction) {
		double completedWeight = histogramWeight(this.completedTravelTimeHistogram);
		boolean hasLive = liveWeight > 0.0 && liveProjectedTravelTimes != null
				&& liveProjectionCount > 0;
		if (completedWeight <= 0.0 && !hasLive) return prior;

		double[] liveBucketWeights = null;
		if (hasLive) {
			if (this.liveTravelTimeBucketBuffer == null) {
				this.liveTravelTimeBucketBuffer =
						new double[TRAVEL_TIME_HISTOGRAM_BUCKETS];
			}
			else {
				Arrays.fill(this.liveTravelTimeBucketBuffer, 0.0);
			}
			liveBucketWeights = this.liveTravelTimeBucketBuffer;
		}
		double baseWeight = TRAVEL_TIME_PRIOR_SAMPLE_STRENGTH + completedWeight;
		double totalLiveWeight = hasLive
				? baseWeight * liveWeight / Math.max(0.0001, 1.0 - liveWeight) : 0.0;
		if (hasLive) {
			double perVehicleWeight = totalLiveWeight / liveProjectionCount;
			for (int i = 0; i < liveProjectionCount; i++) {
				double projection = liveProjectedTravelTimes[i];
				double adjustedProjection = Math.max(queueProjection,
						finiteNonNegative(projection, prior));
				liveBucketWeights[travelTimeHistogramBucket(adjustedProjection)]
						+= perVehicleWeight;
			}
		}

		double totalWeight = baseWeight + totalLiveWeight;
		double threshold = clamp(fraction, 0.0, 1.0) * totalWeight;
		double cumulative = 0.0;
		int priorBucket = travelTimeHistogramBucket(prior);
		for (int i = 0; i < TRAVEL_TIME_HISTOGRAM_BUCKETS; i++) {
			if (this.completedTravelTimeHistogram != null) {
				cumulative += Math.max(0.0, this.completedTravelTimeHistogram[i]);
			}
			if (i == priorBucket) cumulative += TRAVEL_TIME_PRIOR_SAMPLE_STRENGTH;
			if (liveBucketWeights != null) cumulative += liveBucketWeights[i];
			if (cumulative + 1.0e-9 >= threshold) {
				return travelTimeHistogramUpperBound(i);
			}
		}
		return TRAVEL_TIME_HISTOGRAM_MAX_SECONDS;
	}

	private static double completedP90FromMoments(double mean, double variance) {
		mean = finiteNonNegative(mean, 0.0);
		variance = finiteNonNegative(variance, 0.0);
		if (mean <= 0.0 || variance <= 0.0) return mean;
		double logVariance = Math.log1p(variance / (mean * mean));
		double logMean = Math.log(mean) - 0.5 * logVariance;
		double p90 = Math.exp(logMean + 1.2815515655446004 * Math.sqrt(logVariance));
		return finiteNonNegative(p90, mean);
	}

	private static float[] sanitizeHistogram(float[] restoredHistogram) {
		if (restoredHistogram == null || restoredHistogram.length == 0) return null;
		float[] result = new float[TRAVEL_TIME_HISTOGRAM_BUCKETS];
		boolean hasWeight = false;
		for (int i = 0; i < Math.min(result.length, restoredHistogram.length); i++) {
			float weight = restoredHistogram[i];
			if (Float.isFinite(weight) && weight > 0.0f) {
				result[i] = weight;
				hasWeight = true;
			}
		}
		return hasWeight ? result : null;
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
		resetTravelTimeEstimator();
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
		markTravelTimeEstimatorRelevant();
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
		this.resetTravelTimeEstimator();
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
				if (this.controlType != Road.COSIM && controlType == Road.COSIM) {
					ArrayList<Vehicle> activeLaneChanges = this.activeLaneChangeVehicles();
					for (Vehicle vehicle : activeLaneChanges) {
						if (vehicle != null) vehicle.cancelLaneChangeForRoadLifecycle();
					}
					this.laneChangeReservations.clear();
				}
				this.controlType = controlType;
				return;
			}
			if (this.nativeReleaseInProgress) return;
			this.nativeReleaseInProgress = true;
		}
		try {
		if (!releasingCoSimControl) {
			this.controlType = controlType;
			return;
		}
		if (this.getVehicleNum() == 0) {
			this.externalLaneReservations.clear();
			this.laneChangeReservations.clear();
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
			if (this.occupiesIncidentConnector(vehicle)) {
				retainedConnectorVehicles.add(vehicle);
			} else {
				vehiclesToAdapt.add(vehicle);
			}
		}

		List<NativeReleasePlacement> placements =
				this.planNativeReleasePlacements(vehiclesToAdapt);
		if (placements == null) {
			ContextCreator.logger.warn("Keeping road " + this.ID
					+ " under COSIM control because no valid native lane projection exists");
			return;
		}

		// Connector occupants retain their connector representation. Physical-road
		// occupants are mapped independently to their direct lane projections; no
		// collision-free interval packing is attempted during hand-back.
		for (NativeReleasePlacement placement : placements) {
			placement.vehicle.removeFromCurrentLane();
		}
		boolean overlapWarningRaised = false;
		for (NativeReleasePlacement placement : placements) {
			placement.vehicle.teleportToLane(placement.lane, placement.distance);
			boolean placed = placement.vehicle.getLane() == placement.lane;
			if (!placed) {
				throw new IllegalStateException("Preplanned native placement failed for vehicle "
						+ placement.vehicle.getID() + " on road " + this.ID);
			}
			// Releasing external control may snap a pose backward or laterally. Treat
			// that placement as a discontinuity so the next trajectory snapshot does
			// not interpolate a false high-speed sweep from the last external pose.
			placement.vehicle.syncPreviousEpochCoord();
			if (!overlapWarningRaised) {
				double end = placement.distance
						+ Math.max(0.0, placement.vehicle.length());
				Vehicle other = placement.vehicle.leading();
				if (other != null) {
					double otherStart = other.getDistanceToNextJunction();
					double otherEnd = otherStart + Math.max(0.0, other.length());
					if (placement.distance >= otherEnd - 0.001
							|| otherStart >= end - 0.001) other = null;
				}
				if (other == null) {
					other = placement.vehicle.trailing();
					if (other != null) {
						double otherStart = other.getDistanceToNextJunction();
						double otherEnd = otherStart + Math.max(0.0, other.length());
						if (placement.distance >= otherEnd - 0.001
								|| otherStart >= end - 0.001) other = null;
					}
				}
				if (other != null) {
					ContextCreator.logger.warn("OVERLAP:" + placement.vehicle.getID()
							+ "," + other.getID() + "@" + placement.lane.getID());
					overlapWarningRaised = true;
				}
			}
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

	private boolean occupiesIncidentConnector(Vehicle vehicle) {
		if (vehicle == null) return false;
		ConnectorRoad connector = vehicle.getCurrentConnector();
		if (connector == null) return false;
		return connector.getSourceRoad() == this || connector.getTargetRoad() == this;
	}

	private List<NativeReleasePlacement> planNativeReleasePlacements(List<Vehicle> vehicles) {
		ArrayList<NativeReleasePlacement> result = new ArrayList<NativeReleasePlacement>();
		for (Vehicle vehicle : vehicles) {
				NativeReleaseProjection best = null;
				for (Lane lane : this.lanes) {
					NativeReleaseProjection candidate =
							this.projectForNativeRelease(vehicle, lane);
					if (candidate != null && (best == null
							|| candidate.perpendicularDistance < best.perpendicularDistance
							|| (candidate.perpendicularDistance == best.perpendicularDistance
									&& candidate.lane.getID() < best.lane.getID()))) {
						best = candidate;
					}
				}
				if (best == null) return null;
				result.add(new NativeReleasePlacement(
						vehicle, best.lane, best.distance));
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
				bestDistance = Double.isFinite(lane.getGeometricLength())
						? lane.getGeometricLength()
						: lane.toGeometricDistance(lane.getLength());
			}
		}
		if (!Double.isFinite(bestPerpendicular) || !Double.isFinite(bestDistance)) return null;
		bestDistance = lane.toLogicalDistance(bestDistance);
		return new NativeReleaseProjection(lane,
				Math.max(0.0, Math.min(lane.getLength(), bestDistance)), bestPerpendicular);
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

		NativeReleasePlacement(Vehicle vehicle, Lane lane, double distance) {
			this.vehicle = vehicle;
			this.lane = lane;
			this.distance = distance;
		}
	}
	
	public int getControlType() {
		return controlType;
	}

	/** Connector ownership changes after their external vehicles are adapted by the API. */
	protected synchronized void setControlTypeDirect(int controlType) {
		this.controlType = controlType;
	}

	public boolean recordTravelTime(Vehicle v, long traversalEpoch,
			double traversalTicks) {
		if (v == null || !v.completeRoadTraversal(traversalEpoch)) return false;
		boolean observationRecorded = false;
		synchronized (this) {
			if (Double.isFinite(traversalTicks) && traversalTicks > 0.0) {
				this.travelTimeSum += traversalTicks;
				this.travelTimeSquareSum += traversalTicks * traversalTicks;
				this.travelTimeCount += 1;
				observationRecorded = true;
				if (this.pendingTravelTimeHistogram == null) {
					this.pendingTravelTimeHistogram =
							new float[TRAVEL_TIME_HISTOGRAM_BUCKETS];
				}
				double traversalSeconds = traversalTicks
						* Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 0.0001);
				this.pendingTravelTimeHistogram[
						travelTimeHistogramBucket(traversalSeconds)] += 1.0f;
			}
		}
		if (observationRecorded) {
			this.onTravelTimeObservationRecorded();
			markTravelTimeEstimatorRelevant();
		}
		if (v.getVehicleSensorType() == Vehicle.MOBILEDEVICE && ContextCreator.kafkaManager != null) {
			ContextCreator.kafkaManager.produceLinkTravelTime(v.getID(), v.getVehicleClass(), this.getID(),
					traversalTicks, this.getLength());
		}
		return true;
	}

	/** Connector override marks completed observations for event-driven refresh. */
	protected void onTravelTimeObservationRecorded() {
		// Physical roads are already refreshed by the fixed network schedule.
	}

	/** Compatibility entry point for callers that record before changing roads. */
	public boolean recordTravelTime(Vehicle v) {
		if (v == null) return false;
		return recordTravelTime(v, v.getRoadTraversalEpoch(), v.getLinkTravelTime());
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

	public synchronized void setSpeedLimit(double speedLimit) {
		this.speedLimit_ = Double.isFinite(speedLimit) && speedLimit >= 0.0
				? speedLimit : 0.0;
		this.resetTravelTimeEstimator();
		this.refreshDefaultParkingCapacity();
		RoadContext roadContext = ContextCreator.getRoadContext();
		if (roadContext != null) roadContext.markRoutingMetricChanged(this);
	}
	
	public String getOrigID() {
		return this.origID;
	}
	
	public void setOrigID(String newID) {
		if (newID == null ? this.origID != null : !newID.equals(this.origID)) {
			this.origID = newID;
			markPhysicalTopologyChanged();
		}
	}
	
}
