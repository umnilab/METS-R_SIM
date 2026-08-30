package mets_r.mobility;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.facility.ConnectorRoad;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.facility.RoadContext;
import mets_r.facility.Signal;
import mets_r.routing.RouteContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.GeodeticCalculator;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

/**
 * Inherit from A-RESCUE
 * 
 * General vehicle
 *
 * Driver behavior models are selected with {@code CAR_FOLLOWING_MODEL} and
 * {@code LANE_CHANGING_MODEL} in Data.properties.
 *
 * Car-following models:
 * - {@code HERMAN}: the original METS-R car-following formulation. It uses
 *   power-law acceleration/deceleration terms based on speed difference,
 *   spacing, and the locally calibrated alpha/beta/gamma parameters. This is
 *   the most compact model and preserves historical METS-R behavior.
 * - {@code KRAUSS}: a SUMO-style collision-avoidance model. It selects a
 *   next-step speed from free-flow desire, acceleration limits, safe velocity,
 *   tau, minGap, driver imperfection sigma, and normal/apparent/emergency
 *   braking constraints. Compared with HERMAN, it is explicitly safety-speed
 *   driven and more directly tied to leader braking assumptions.
 * - {@code IDM}: SUMO's Intelligent Driver Model. It integrates a next-step
 *   speed from desired speed, tau, minGap, delta, acceleration, comfortable
 *   deceleration, and leader closing speed, then converts that speed back to a
 *   METS-R acceleration command.
 * - {@code WIEDEMANN74}: a VISSIM-style psycho-physical model for urban
 *   following. It uses driver-specific standstill distance, oscillation, and
 *   threshold regimes such as free, approaching, following, braking, and
 *   collision response. Compared with Krauss, it is regime/threshold based
 *   rather than safe-speed-only.
 * - {@code WIEDEMANN99}: a VISSIM-style psycho-physical model intended for
 *   freeway and higher-speed operation. It extends Wiedemann74 with the CC0-CC9
 *   parameter family, explicit speed-difference thresholds, oscillation inside
 *   the following regime, and separate acceleration behavior above/below high
 *   speed. It is more parameter-rich than Wiedemann74.
 *
 * Lane-changing models:
 * - {@code AHMED}: the original METS-R lane-changing model, named after Ahmed
 *   (1999). It separates mandatory lane changes for route/connectivity needs
 *   from discretionary changes for speed advantage, then applies critical lead
 *   and lag gap acceptance with nosing/yielding behavior.
 * - {@code LC2013}: a SUMO-style lane-changing model. It combines strategic,
 *   cooperative, speed-gain, keep-right, and regulatory motivations with
 *   secure-gap checks and acceleration advice for the ego vehicle and blocking
 *   followers. Compared with AHMED, it has richer motivation scoring and
 *   explicit cooperative speed adjustment.
 * 
 * @author Xianyuan Zhan, Xinwu Qian, Hemant Gehlot, Zengxiang Lei
 **/

public class Vehicle {
	/**
	 * Result of processing a vehicle that has left its current lane geometry.
	 * A completed source-road traversal is deliberately distinct from an already
	 * handled arrival so callers cannot count the same arrival once per tick.
	 */
	public enum RoadTransitionOutcome {
		BLOCKED(false),
		ROAD_CHANGED(true),
		ARRIVED(true),
		ALREADY_ARRIVED(false);

		private final boolean completesSourceTraversal;

		RoadTransitionOutcome(boolean completesSourceTraversal) {
			this.completesSourceTraversal = completesSourceTraversal;
		}

		public boolean completesSourceTraversal() {
			return this.completesSourceTraversal;
		}

		public boolean transitioned() {
			return this == ROAD_CHANGED;
		}
	}

	/* Constants */
	// VehicleType
	public final static int GV = 0; // Private gasoline vehicle
	public final static int ETAXI = 1;
	public final static int EBUS = 2;
	public final static int EV = 3; // Private electric vehicle
	
	//SensorType
	public final static int DSRC = 0;
	public final static int CV2X = 1;
	public final static int MOBILEDEVICE = 2;
	
	//TripType
	public final static int PARKING = 0;
	public final static int OCCUPIED_TRIP = 1;
	public final static int INACCESSIBLE_RELOCATION_TRIP = 2; // For designated relocation tasks, vehicles will not be															
	                                                          // available until it reaches the relocation destination
	public final static int BUS_TRIP = 3;
	public final static int CHARGING_TRIP = 4;
	public final static int CRUISING_TRIP = 5;
	public final static int PICKUP_TRIP = 6;
	public final static int ACCESSIBLE_RELOCATION_TRIP = 7; // Vehicles are available to the zone that they travel through
	public final static int PRIVATE_TRIP = 8;
	
	public final static int NONE_OF_THE_ABOVE = -1;
	private static final int LC_REASON_STRATEGIC = 1;
	private static final int LC_REASON_COOPERATIVE = 2;
	private static final int LC_REASON_SPEED_GAIN = 3;
	private static final int LC_REASON_KEEP_RIGHT = 4;
	private static final int LC_REASON_REGULATORY = 5;
	private static final double COINCIDENT_WAYPOINT_TOLERANCE_METERS = 0.001;
	private static final double EXTERNAL_LANE_ENTRY_LONGITUDINAL_TOLERANCE_METERS = 0.05;
	private static final double COSIM_LANE_OBSERVATION_DOWNSTREAM_TOLERANCE_METERS = 5.0;
	// OpenDRIVE and SUMO lane centerlines are generated independently and may
	// differ slightly even when their lane mapping is topologically exact.
	private static final double COSIM_LANE_OBSERVATION_MAP_SLACK_METERS = 0.75;
	private static final double MANDATORY_LANE_CHANGE_THRESHOLD_BUFFER_METERS = 0.1;
	private static final int RECOVERY_LOOP_LOOKAHEAD_ROADS = 4;
	private static final double STOP_LINE_WAIT_DISTANCE_METERS = 1.0;
	/* Constants */
	private static final double GRAVITY = 9.81; // m/sÂ², used for grade resistance
	
	/* Private variables that are not visible to descendant classes */
	private Road destRoad_;
	private Road originRoad_;
	private Road lastDeparturableRoad_;
	private Coordinate currentCoord_; // this variable is created when the vehicle is initialized
	private Coordinate originCoord_;
	private final double length; // vehicle length, fixed at construction
	private double distance_; // distance to downstream junction
	private double nextDistance_; // distance to the next control point in the current lane's line segments
	private double distToTravel_; // route-distance estimate captured at the current within-road reference point
	private double distToTravelReferenceDistance_; // distance_ when distToTravel_ was last rebased
	
	private double currentSpeed_;
	private double accRate_;
	private double plannedAcceleration_;
	private boolean hasAccelerationPlan_;
	private boolean accDecided_;
	private double bearing_;
	private double desiredSpeed_; // in meter/sec
	private int regime_;
	private double maxAcceleration_; // in meter/sec2
	private double normalDeceleration_; // in meter/sec2
	private double maxDeceleration_; // in meter/sec2
	private double currentLaneSlope_; // grade (rise/run) of the current segment, positive = uphill
	private int currentSegmentIdx_;   // index into lane.segmentSlopes[] for the segment being travelled
	private int deptime;
	private int endTime;
	private int originID = -1;
	private int destinationID = -1;
	private Coordinate previousEpochCoord;// This variable stores the coordinates of the vehicle when last time
										  // vehicle snapshot was recorded for visualization interpolation
	private boolean isReachDest;
	private boolean onLane; // On a lane, false when the vehicle is in an intersection or not on road
	private boolean onRoad; // On a road, false when the vehicle is parking/charging
	private int currentParkingRoad; // Road-backed parking spot, or -1 when not parked on a road
	private Road road;
	private Lane lane;
	/*
	 * Explicit native connector identity. Physics still uses the target lane's
	 * existing linked lists, but queries and intersection collision ownership use
	 * this field until the vehicle's rear clears the junction.
	 */
	private volatile ConnectorRoad currentConnector;
	private volatile ConnectorRoad.ConnectorPath currentConnectorPath;
	private volatile boolean connectorFrontCleared;
	private boolean preserveConnectorReservationOnLaneDetach;

	/*
	 * A regular-road -> CoSim-road handoff has two distinct boundaries. The
	 * external simulator owns the vehicle as soon as it leaves the source lane,
	 * but the vehicle must not occupy a lane on the CoSim road until the external
	 * pose actually reaches that lane. While this flag is set, road points at the
	 * target CoSim road (so the bridge can discover the vehicle), lane is null,
	 * and native movement must not step the vehicle.
	 */
	private volatile boolean externalRoadTransition;
	private Road externalTransitionSourceRoad;
	private Road externalTransitionTargetRoad;
	private Lane externalTransitionTargetLane;
	
	private double prevDistance;
	private double prevSpeed;
	private Road prevRoad_;
	private Road prevNextRoad_;
	
	// Speed snapshot taken at the start of each move() tick, used to keep
	// the speed unchanged within the tick a vehicle transitions onto a
	// CoSim road (so CARLA reads the entering speed rather than an
	// in-tick clamped value).
	private double tickStartSpeed_;
	
	// Vehicle class, status, and sensorType
	protected int vehicleState; 
	protected int vehicleClass; 
	protected int vehicleSensorType;
	private boolean attackVehicle = false;
	
	// For vehicle based routing
	private List<Coordinate> coordMap;
	private Vehicle leading_; // leading vehicle in the lane
	private Vehicle trailing_; // Trailing vehicle in the lane
	private Vehicle macroLeading_; // Leading vehicle on the road (with all lanes combined)
	private Vehicle macroTrailing_; // Trailing vehicle on the road (with all lanes combined)
	
	// Variables for lane changing model
	private boolean nosingFlag;// If a vehicle in MLC and it can't find gap acceptance then nosing is true.
	private boolean yieldingFlag; // The vehicle need to yield if true
	private double lcAccelerationAdvice_;
	private double lcSpeedGainProbabilityLeft_;
	private double lcSpeedGainProbabilityRight_;
	private double lcKeepRightProbability_;
	private int lcBlockedTicks_;
	private double wiedemann74Ax_;
	private double wiedemann74Z_;
	private double wiedemann99Z_;
	private double wiedemannOscillationSign_;
	// Cache for lane projection (valid within a single calcState call)
	private Lane cachedProjectionLane_ = null;
	private double cachedProjectionDistance_ = 0;
	private Lane laneChangeSourceLane_;
	private Lane laneChangeTargetLane_;
	private double laneChangeElapsedSeconds_;
	private double laneChangeDurationSeconds_;
	private double laneChangeLateralDistanceMeters_;
	private Lane.ArcGeometry laneChangeSourceGeometry_;
	private Lane.ArcGeometry laneChangeTargetGeometry_;
	private final LanePosition lanePositionScratchA_ = new LanePosition();
	private final LanePosition lanePositionScratchB_ = new LanePosition();
	private final double[] laneChangeSeparationScratch_ = new double[2];
	private final double[] laneChangeTangentScratch_ = new double[2];
	private final double[] laneChangeDistanceAngleScratch_ = new double[2];
	private final Coordinate laneChangePreviousCoordScratch_ = new Coordinate();
	private Lane mandatoryPreparationSourceLane_;
	private Lane mandatoryPreparationTargetLane_;
	private double mandatoryPreparationDistance_ = Double.NaN;
	private double mandatoryRemainingManeuverDurationSeconds_ = Double.NaN;
	private long mandatoryPreparationGeometryEpoch_ = Long.MIN_VALUE;
	
	// For adaptive network partitioning
	private int Nshadow; // Number of current shadow roads in the path
	private ArrayList<Road> futureRoutingRoad;
	protected ArrayList<Plan> activityPlan; // A set of zone for the vehicle to visit
	
	// For calculating vehicle coordinates
	GeodeticCalculator calculator = new GeodeticCalculator(ContextCreator.getLaneGeography().getCRS());
	
	// Cumulative stopped time for the current road traversal. This is not cleared
	// by stop-and-go creeping.
	private int roadTraversalStoppedTicks;
	// Stop-control dwell is deliberately separate: upstream congestion must not
	// satisfy a stop sign before the vehicle reaches the stop line.
	private int stopLineWaitTicks;
	private int roadPatienceLastRecoveryTick = -1;
	private boolean roadPatienceRecoveryResolved;
	private boolean deferredRoadRecoveryQueued;
	private final AtomicInteger transferringQueueTick_ = new AtomicInteger(Integer.MIN_VALUE);
	private final AtomicInteger arrivalQueueTick_ = new AtomicInteger(Integer.MIN_VALUE);
	private boolean deferredMissedLaneRecoveryRequested;
	private boolean deferredRoadPatienceRecoveryRequested;
	private int missedLaneRecoveryRoadID = -1;
	private Lane missedLaneRecoverySelectedLane;
	private List<Road> missedLaneRecoverySelectedPath;
	private boolean missedLaneRecoveryInitialAttempted;
	private boolean missedLaneRecoveryFinalAttempted;
	private boolean missedLaneRecoveryFallbackHandled;
	private boolean missedLaneRecoveryQuarantined;
	
	/* Protected variables that can be accessed through descendant classes */
	protected int ID;
	protected Random rand; // Random seeds for making lane changing, cruising decisions
	protected Random rand_route_only; // Random seeds for making lane changing, cruising decisions
	protected Random rand_relocate_only; // Random seeds for making lane changing, cruising decisions
	protected Random rand_car_follow_only; // Random seeds for making lane changing, cruising decisions
	protected double accummulatedDistance_; // Accumulated travel distance in the current trip
	protected boolean movingFlag = false; // Whether this vehicle is moving
	protected boolean atOrigin;
	protected List<Road> roadPath; // The route is always started with the current road, whenever entering the next
	                               // road, the current road will be popped out
	protected Road nextRoad_;
	protected Lane nextLane_;
	protected double linkTravelTime;
	// Monotonic road-entry identity used to make traversal completion one-shot.
	private long roadTraversalEpoch;
	private long lastCompletedRoadTraversalEpoch;
	
	protected int numTrips; // Number of trips initialized
	
	/**
	 * Constructor of Vehicle Class
	 * @param vClass Vehicle type, 0 for gasoline (private vehicle), 1 for EV taxi, 2 for EV bus, 3 for EV (private vehicle) 
	 * @param sType Vehicle sensor type, 0 for no sensor, 1 for connected vehicle sensor
	 */
	public Vehicle(int vClass, int vSensor) {
		this(vClass, vSensor, GlobalVariables.DEFAULT_VEHICLE_LENGTH);
	}

	public Vehicle(int vClass, int vSensor, double length) {
		if (!Double.isFinite(length) || length <= 0.0) {
			throw new IllegalArgumentException(
					"Vehicle length must be a finite positive value in meters");
		}
		this.ID = ContextCreator.generateAgentID();
		this.rand = new Random(GlobalVariables.RandomGenerator.nextInt());
		this.rand_route_only = new Random(rand.nextInt());
		this.rand_relocate_only = new Random(rand.nextInt());
		this.rand_car_follow_only = new Random(rand.nextInt());
		this.currentCoord_ = new Coordinate(0, 0, 0.0);
		this.activityPlan = new ArrayList<Plan>(); // Empty plan

		this.length = length;
		this.maxAcceleration_ = 3.0;
		this.maxDeceleration_ = -4.0;
		this.normalDeceleration_ = -0.5;
		this.currentLaneSlope_ = 0;
		this.currentSegmentIdx_ = 0;
		this.plannedAcceleration_ = 0.0;
		this.hasAccelerationPlan_ = false;
		this.accDecided_ = false;

		this.previousEpochCoord = new Coordinate(0, 0, 0.0);
		this.endTime = 0;
		this.atOrigin = true;
		this.isReachDest = false;
		this.onLane = false;
		this.onRoad = false;
		this.currentParkingRoad = -1;
		this.accRate_ = 0;
		this.lane = null;
		this.nextLane_ = null;
		this.nosingFlag = false;
		this.yieldingFlag = false;
		this.clearLaneChangeManeuverFields();
		this.resetLaneChangeRuntimeState();
		this.lcSpeedGainProbabilityLeft_ = 0;
		this.lcSpeedGainProbabilityRight_ = 0;
		this.lcKeepRightProbability_ = 0;
		this.lcBlockedTicks_ = 0;
		this.initializeWiedemannDriverState();
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		this.road = null;
		this.nextRoad_ = null;
		this.externalRoadTransition = false;
		this.currentConnector = null;
		this.currentConnectorPath = null;
		this.externalTransitionSourceRoad = null;
		this.externalTransitionTargetRoad = null;
		this.externalTransitionTargetLane = null;
		this.coordMap = new LinkedList<Coordinate>();
		this.originCoord_ = null;
		this.originRoad_ = null;
		this.destRoad_ = null;
		this.lastDeparturableRoad_ = null;
		this.accummulatedDistance_ = 0;
		this.roadPath = null;
		this.linkTravelTime = 0;
		this.roadTraversalEpoch = 0L;
		this.lastCompletedRoadTraversalEpoch = -1L;

		// For adaptive network partitioning
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
		this.vehicleClass = vClass;
		this.vehicleSensorType = vSensor;
		
		// Start with parking
		this.setState(Vehicle.PARKING);
	}

	/**
	 * This is a constructor of the Vehicle class with customized parameters, used for define special vehicles like bus
	 * @param maximumAcceleration maximum acceleration of this vehicle
	 * @param maximumDeceleration minimum deceleration of this vehicle
	 * @param vClass Vehicle type
	 */
	public Vehicle(double maximumAcceleration, double maximumDeceleration, int vClass, int vSensor) {
		this(maximumAcceleration, maximumDeceleration, vClass, vSensor,
				GlobalVariables.DEFAULT_VEHICLE_LENGTH);
	}

	public Vehicle(double maximumAcceleration, double maximumDeceleration,
			int vClass, int vSensor, double length) {
		this(vClass, vSensor, length);
		this.maxAcceleration_ = maximumAcceleration;
		this.maxDeceleration_ = maximumDeceleration;
	}

	/**
	 * Initialize a new one-shot trip directly at an externally authoritative
	 * position. This bypasses departure queues and never constructs a temporary
	 * zone-origin trip.
	 */
	public synchronized void initializeCoSimTripAt(Road startRoad, Lane startLane,
			double downstreamDistance, Coordinate authoritativePose,
			double authoritativeBearing, double authoritativeSpeed,
			Road destinationRoad, Road requiredNextRoad, Lane requiredNextLane) {
		if (this.onRoad || this.onLane || this.road != null || this.lane != null
				|| this.externalRoadTransition) {
			throw new IllegalStateException("Direct COSIM initialization requires a new, detached vehicle");
		}
		if (startRoad == null || startLane == null || startLane.getRoad() != startRoad
				|| destinationRoad == null || authoritativePose == null
				|| !Double.isFinite(authoritativePose.x)
				|| !Double.isFinite(authoritativePose.y)
				|| !Double.isFinite(authoritativePose.z)
				|| !Double.isFinite(authoritativeBearing)
				|| !Double.isFinite(authoritativeSpeed) || authoritativeSpeed < 0.0
				|| !Double.isFinite(downstreamDistance)
				|| downstreamDistance < 0.0
				|| downstreamDistance > startLane.getLength()) {
			throw new IllegalArgumentException("Invalid direct COSIM initialization state");
		}

		ArrayList<Road> initialPath;
		if (requiredNextRoad == null) {
			List<Road> route = RouteContext.shortestPathRoute(
					startRoad, destinationRoad, this.rand_route_only);
			if (route == null || route.isEmpty() || route.get(0) != startRoad) {
				throw new IllegalArgumentException("No route from the matched road to the destination");
			}
			initialPath = new ArrayList<Road>(route);
		} else {
			if (!startRoad.getDownStreamRoads().contains(requiredNextRoad.getID())
					|| requiredNextLane == null
					|| requiredNextLane.getRoad() != requiredNextRoad
					|| !startLane.getDownStreamLanes().contains(requiredNextLane.getID())) {
				throw new IllegalArgumentException("Matched connector is not a legal lane transition");
			}
			List<Road> suffix = RouteContext.shortestPathRoute(
					requiredNextRoad, destinationRoad, this.rand_route_only);
			if (suffix == null || suffix.isEmpty() || suffix.get(0) != requiredNextRoad) {
				throw new IllegalArgumentException("No route from the matched connector to the destination");
			}
			initialPath = new ArrayList<Road>(suffix.size() + 1);
			initialPath.add(startRoad);
			initialPath.addAll(suffix);
		}

		this.originRoad_ = startRoad;
		this.destRoad_ = destinationRoad;
		this.originID = startRoad.getNeighboringZone(false);
		this.destinationID = destinationRoad.getNeighboringZone(true);
		this.deptime = ContextCreator.getCurrentTick();
		this.originCoord_ = new Coordinate(authoritativePose);
		this.activityPlan.clear();
		this.activityPlan.add(new Plan(this.destinationID,
				destinationRoad.getID(), ContextCreator.getNextTick()));
		this.roadPath = initialPath;
		this.nextRoad_ = initialPath.size() > 1 ? initialPath.get(1) : null;
		this.nextLane_ = null;
		this.atOrigin = false;
		this.isReachDest = false;
		this.numTrips++;
		this.setState(Vehicle.PRIVATE_TRIP);

		try {
			startRoad.teleportVehicle(this, startLane, downstreamDistance);
			this.setCurrentCoord(authoritativePose);
			this.setPreviousEpochCoord(authoritativePose);
			this.setBearing(authoritativeBearing);
			this.setSpeed(authoritativeSpeed);
			if (this.nextRoad_ != null) {
				if (requiredNextRoad != null) {
					if (!this.assertNextLaneForExternalTransition(
							requiredNextRoad, requiredNextLane)) {
						throw new IllegalArgumentException(
								"Matched connector lane is inconsistent with the initialized route");
					}
				} else {
					this.assignNextLane();
					if (this.nextLane_ == null) {
						throw new IllegalArgumentException(
								"Initialized route has no reachable next lane");
					}
				}
			}
			this.setDistToTravelEstimate(this.routeDistanceFromCurrentPosition(this.roadPath));
			this.setShadowImpact();
		} catch (RuntimeException ex) {
			if (this.onRoad || this.onLane || this.externalRoadTransition) this.leaveNetwork();
			throw ex;
		}
	}

	/**
	 * Update the destination of the vehicle according to its plan, a plan is a triplet as (target zone, target location, departure time)
	 */
	public void setNextPlan() {
		Plan next = this.activityPlan.get(1);
		this.originID = this.destinationID;
		this.destinationID = next.getDestZoneID();
		this.deptime = (int) next.getDepartureTime();
		this.destRoad_ = ContextCreator.getRoadContext().get(next.getDestRoadID());
		this.atOrigin = true; // The vehicle will be rerouted to the new target when enters a new link.
		this.activityPlan.remove(0); // Remove current schedule
	}
	
	public void setNextPlan(int delay) { // departure time is right away after a specific delay 
		Plan next = this.activityPlan.get(1);
		this.originID = this.destinationID;
		this.destinationID = next.getDestZoneID();
		this.deptime = Math.max((int) next.getDepartureTime(), ContextCreator.getCurrentTick() + delay);
		this.destRoad_ = ContextCreator.getRoadContext().get(next.getDestRoadID());
		this.atOrigin = true; // The vehicle will be rerouted to the new target when enters a new link.
		this.activityPlan.remove(0); // Remove current schedule
	}
	
	/**
	 * Initialize the vehicle state 
	 */
	public void initializePlan(int loc_id, int road_id, double d) {
		Road road = ContextCreator.getRoadContext().get(road_id);
		// Clear the old plans
		this.activityPlan.clear();
		this.setCurrentCoord(road.getStartCoord());
		this.addPlan(loc_id, road_id, d); 
		this.addPlan(loc_id, road_id, d); 
		this.setNextPlan(); // This will set the origin to 0 and dest to loc_id
		this.addPlan(loc_id, road_id, d);
		this.setNextPlan(); // This will set the origin to the loc_id
	}
	
	/**
	 * Modify the current destination zone and location of the vehicle
	 * @param dest_id Target zone ID
	 * @param dest_road Target road
	 * @return
	 */
	public boolean modifyPlan(int dest_id, Road road) {
		if(this.isOnRoad()) {
			if(this.activityPlan.size() > 1) {
				ContextCreator.logger.error("Something went wrong, cannot modify the vehicle with multiple plans");
			}
			this.activityPlan.clear();
			this.addPlan(dest_id, road.getID(), ContextCreator.getNextTick());
			this.destinationID = dest_id;
			this.destRoad_ = road;
			// Reroute it
			this.rerouteAndSetNextRoad();
			return true;
		}
		else {
			return false;
		}
	}

	/** 
	 * Vehicle enters the road, success when any lane on the road has enough space
	 * @param The road that the vehicle enter
	 * @return Whether the road successfully enter the road 
	 */
	public boolean enterNetwork(Road road) {
		return enterNetwork(road, selectDepartureLane(road), false);
	}
	
	/**
	 * Vehicle enters the road, success when the road has enough space in the specified lane
	 * @param The road and the lane that the vehicle enter
	 * @return Whether the road successfully enter the road 
	 */
	public boolean enterNetwork(Road road, Lane lane) {
		return enterNetwork(road, lane, false);
	}

	/**
	 * Vehicle enters a co-simulation road after the external simulator has
	 * explicitly released it from the road's entering queue.
	 */
	public boolean enterNetworkByControl(Road road) {
		return enterNetwork(road, selectDepartureLane(road), true);
	}

	public boolean enterNetworkByControl(Road road, Lane lane) {
		return enterNetwork(road, lane, true);
	}

	private synchronized boolean enterNetwork(Road road, Lane lane, boolean allowCoSimEntry) {
		// Sanity check
		if (road == null || lane == null) return false;
		if(lane.getRoad() != road) return false;
		if (!isViableDepartureLane(road, lane)) return false;
		if (road.getExternalLaneReservationBlocker(lane, this) != null) return false;
		if (road.getControlType() == Road.COSIM && !allowCoSimEntry) return false;
		if (!allowCoSimEntry
				&& ContextCreator.getRoadContext().hasEnteringVehicleRegistration(this)
				&& !ContextCreator.getRoadContext().isEnteringVehicleRegistered(road, this)) {
			// Another road owns this vehicle's pending admission. The caller will
			// discard its stale local queue entry.
			return false;
		}
		
		// A stale queue entry must never increment road membership twice.
		if (this.isOnRoad()) {
			ContextCreator.getRoadContext().removeVehicleFromEnteringQueues(this);
			return false;
		}
		
		boolean canEnter = false;

	    if (road instanceof ConnectorRoad) {
	        canEnter = true; // Connector admission is validated atomically below.
	    } else if (road.getControlType() != Road.COSIM) {
	        double gap = entranceGap(lane);

	        if (gap >= 1.2 * this.length()) {
	            canEnter = true;
	        }
	    } else {
	        canEnter = true;

	        Vehicle lastVeh1 = lane.lastVehicle();
	        Vehicle lastVeh2 = road.lastVehicle();

	        if (lastVeh1 != null) {
	            Coordinate c1 = lastVeh1.getCurrentCoord();
	            Coordinate c2 = lane.getStartCoord();

	            if (ContextCreator.getCityContext().getDistance(c1, c2) < 1.2 * this.length()) {
	                canEnter = false;
	            }
	        }

	        if ((lastVeh2 != null) && (lastVeh2 != lastVeh1)) {
	            Coordinate c1 = lastVeh2.getCurrentCoord();
	            Coordinate c2 = lane.getStartCoord();

	            if (ContextCreator.getCityContext().getDistance(c1, c2) < 1.2 * this.length()) {
	                canEnter = false;
	            }
	        }
	    }
	    
	    if (canEnter) {
	    	// Check the upStream road
	    	for (int lid : lane.getUpStreamLanes()) {
	            Lane l = ContextCreator.getLaneContext().get(lid);
	            if (l == null) continue;
	            
	            if(l.getRoad().getControlType() != Road.COSIM) {
	            	Vehicle v = l.firstVehicle();
	                if (v != null) {
	                    if (v.getDistanceToNextJunction()
								< stoppingDistanceMeters(v.currentSpeed(), v.maxDeceleration_)) {
	                        canEnter = false;
	                        break;
	                    }
	                }
	            }
	            else {
	            	Vehicle v = l.firstVehicle();
		            if (v != null) {
		                Coordinate c1 = v.getCurrentCoord();
		                Coordinate c2 = lane.getStartCoord();

		                if (ContextCreator.getCityContext().getDistance(c1, c2)
								< stoppingDistanceMeters(v.currentSpeed(), v.maxDeceleration_)) {
		                    canEnter = false;
		                    break;
		                }
		            }
	            }
	            
	        }
	    }

	    if (!canEnter) return false;

		ConnectorRoad enteringConnector = road instanceof ConnectorRoad
				? (ConnectorRoad) road : null;
		ConnectorRoad.ConnectorPath enteringConnectorPath = enteringConnector == null
				? null : enteringConnector.getPath(lane);
		double connectorRequiredGap = 1.2 * this.length();
		boolean requireClearConnectorPath = enteringConnector != null
				&& enteringConnector.requiresClearPathAdmission(
						enteringConnectorPath, connectorRequiredGap);
		if (enteringConnector != null && (enteringConnectorPath == null
				|| !ContextCreator.getRoadContext().tryEnterConnector(enteringConnector,
						enteringConnectorPath, this, requireClearConnectorPath))) {
			return false;
		}

	    // Admission is committed while holding this vehicle's monitor. Remove the
	    // winning entry and any stale duplicates before publishing road membership.
	    ContextCreator.getRoadContext().removeVehicleFromEnteringQueues(this);
	    this.currentSpeed_ = 0.0;
	    this.distance_ = 0;
	    this.setPreviousEpochCoord(lane.getStartCoord());
	    this.setCurrentCoord(lane.getStartCoord());
		if (enteringConnector != null) {
			this.currentConnector = enteringConnector;
			this.currentConnectorPath = enteringConnectorPath;
			this.connectorFrontCleared = false;
		}
	    this.appendToLane(lane);
	    this.appendToRoad(road);
		if (enteringConnector != null) {
			ContextCreator.getRoadContext().activateConnectorVehicle(enteringConnector, this);
			ContextCreator.getRoadContext().updateConnectorVehicleState(enteringConnector, this);
		}

	    return true;
	}
	
	
	/**
	 * Append vehicle to the pending list to a specific road
	 * If this vehicle is on road, ignore the road variable and reroute it
	 */
	public synchronized void departure(Road road) {
		Road departureRoad = this.isOnRoad()
				? resolveOnRoadDepartureRoad(road)
				: resolveQueuedDepartureRoad(road);
		if (departureRoad == null) {
			ContextCreator.logger.warn("departure skipped for vehicle " + this.getID() + " because departure road is null.");
			return;
		}
		if (!this.isOnRoad() && ContextCreator.getRoadContext()
				.isOnlyEnteringVehicleRegistered(departureRoad, this)) {
			// A repeated request for the same pending departure is an idempotent
			// refresh. Road will retain its queue position, or re-key it if the
			// departure time changed, without counting another trip.
			departureRoad.addVehicleToPendingQueue(this);
			return;
		}
		this.resetMissedLaneRecoveryState();
		this.numTrips ++;
		this.originRoad_ = departureRoad;
		updateLastDeparturableRoad(departureRoad);
		this.isReachDest = false;
		if(!this.isOnRoad()) { // If the vehicle not in the network, we add it to a pending list to the closest link
			this.originCoord_ = departureRoad.getStartCoord();
			departureRoad.addVehicleToPendingQueue(this);
		}
		else if (!sameRoad(this.road, departureRoad)) {
			queueDepartureFromRoad(departureRoad);
		}
		else { // The vehicle is on the requested road.
			if (!this.onLane || this.lane == null) {
				// Arrival leaves the vehicle at the downstream boundary. A new trip on
				// the same road must start a fresh road-entry episode instead of leaving
				// the off-lane vehicle in the transfer queue indefinitely.
				queueDepartureFromRoad(departureRoad);
			}
			else {
				ContextCreator.getRoadContext().markRoadActive(this.road);
				this.rerouteAndSetNextRoad(); // refresh the CoordMap
			}
		}
	}

	private Road resolveOnRoadDepartureRoad(Road requestedRoad) {
		if (isDeparturableRoad(this.road)) {
			return this.road;
		}
		if (isDeparturableRoad(this.lastDeparturableRoad_)) {
//			ContextCreator.logger.warn("Vehicle " + this.getID() + " is on non-departurable road "
//					+ roadLabel(this.road) + "; departing from last departurable road "
//					+ roadLabel(this.lastDeparturableRoad_) + ".");
			return this.lastDeparturableRoad_;
		}
		if (isDeparturableRoad(requestedRoad)) {
//			ContextCreator.logger.warn("Vehicle " + this.getID() + " is on non-departurable road "
//					+ roadLabel(this.road) + " and has no cached departurable road; using requested road "
//					+ roadLabel(requestedRoad) + ".");
			return requestedRoad;
		}
		Road fallbackRoad = this.departurableFallbackRoad(this.road);
		if (fallbackRoad != null) {
//			ContextCreator.logger.warn("Vehicle " + this.getID() + " is on non-departurable road "
//					+ roadLabel(this.road) + "; using nearby departurable road "
//					+ roadLabel(fallbackRoad) + ".");
			return fallbackRoad;
		}
		ContextCreator.logger.warn("Vehicle " + this.getID() + " is departing from non-departurable road "
				+ roadLabel(this.road) + " and has no viable fallback road.");
		return null;
	}

	private Road resolveQueuedDepartureRoad(Road requestedRoad) {
		if (isDeparturableRoad(requestedRoad)) {
			return requestedRoad;
		}
		if (isDeparturableRoad(this.lastDeparturableRoad_)) {
			ContextCreator.logger.warn("Vehicle " + this.getID() + " requested departure road "
					+ roadLabel(requestedRoad) + " is not departurable; using last departurable road "
					+ roadLabel(this.lastDeparturableRoad_) + ".");
			return this.lastDeparturableRoad_;
		}
		Road fallbackRoad = this.departurableFallbackRoad(requestedRoad);
		if (fallbackRoad != null) {
			ContextCreator.logger.warn("Vehicle " + this.getID() + " requested departure road "
					+ roadLabel(requestedRoad) + " is not departurable; using nearby departurable road "
					+ roadLabel(fallbackRoad) + ".");
			return fallbackRoad;
		}
		if (requestedRoad != null) {
			ContextCreator.logger.warn("Vehicle " + this.getID() + " requested departure road "
					+ roadLabel(requestedRoad) + " is not departurable and no viable fallback road is available.");
		}
		return null;
	}

	private boolean isDeparturableRoad(Road road) {
		// Connector roads are traversal-only. Treating one as a trip origin lets it
		// overwrite lastDeparturableRoad_, but connectors are not ordinary routing-
		// graph origins and therefore cannot safely host a queued departure.
		return road != null && road.canBeTripOrigin() && selectDepartureLane(road) != null;
	}

	private Lane selectDepartureLane(Road road) {
		if (road == null) return null;
		Lane firstViable = null;
		for (Lane candidate : road.getLanes()) {
			if (!isViableDepartureLane(road, candidate)) continue;
			if (firstViable == null) firstViable = candidate;
			if (this.nextRoad_ != null && laneConnectsToRoad(candidate, this.nextRoad_)) {
				return candidate;
			}
		}
		return firstViable;
	}

	private boolean isViableDepartureLane(Road road, Lane lane) {
		if (road == null || lane == null || lane.getRoad() != road
				|| !lane.isDepartureGeometryUsable()) {
			return false;
		}
		if (!(road instanceof ConnectorRoad) && road.getControlType() != Road.COSIM) {
			double requiredLength = 1.2 * this.length();
			if (!Double.isFinite(lane.getLength()) || lane.getLength() < requiredLength) {
				return false;
			}
		}
		return road.getControlType() == Road.COSIM || !lane.getDownStreamLanes().isEmpty();
	}


	private void updateLastDeparturableRoad(Road road) {
		if (isDeparturableRoad(road)) {
			this.lastDeparturableRoad_ = road;
		}
	}

	private boolean sameRoad(Road first, Road second) {
		return first == second || (first != null && second != null && first.getID() == second.getID());
	}

	private String roadLabel(Road road) {
		if (road == null) return "null";
		return road.getOrigID() + "(" + road.getID() + ")";
	}

	private String laneLabel(Lane lane) {
		if (lane == null) return "null";
		return lane.getOrigID() + "(" + lane.getID() + ")";
	}

	private String formatDebugDouble(double value) {
		if (Double.isNaN(value)) return "NaN";
		if (Double.isInfinite(value)) return value > 0 ? "Inf" : "-Inf";
		return String.format(java.util.Locale.US, "%.3f", value);
	}

	private boolean shouldLogStuckTransferFailure() {
		if (!GlobalVariables.DEBUG_STUCK_VEHICLE) return false;
		if (this.roadTraversalStoppedTicks < GlobalVariables.DEBUG_STUCK_VEHICLE_MIN_TIME) return false;
		int interval = Math.max(1, GlobalVariables.DEBUG_STUCK_VEHICLE_LOG_INTERVAL);
		int firstLogTick = Math.max(0, GlobalVariables.DEBUG_STUCK_VEHICLE_MIN_TIME);
		return this.roadTraversalStoppedTicks == firstLogTick
				|| (this.roadTraversalStoppedTicks > firstLogTick
						&& (this.roadTraversalStoppedTicks - firstLogTick) % interval == 0);
	}


	private String signalDebugLabel(Signal signal) {
		if (signal == null) return "null";
		return signal.getID() + ":state=" + signal.getState()
				+ ":nextState=" + signal.getNextState()
				+ ":nextUpdateTick=" + signal.getNextUpdateTick()
				+ ":phaseTicks=" + signal.getPhaseTick();
	}

	private String vehicleDebugLabel(Vehicle vehicle) {
		if (vehicle == null) return "null";
		return vehicle.getID()
				+ ":class=" + vehicle.getVehicleClass()
				+ ":state=" + vehicle.getState()
				+ ":road=" + roadLabel(vehicle.getRoad())
				+ ":lane=" + laneLabel(vehicle.getLane())
				+ ":dist=" + formatDebugDouble(vehicle.getDistanceToNextJunction())
				+ ":speed=" + formatDebugDouble(vehicle.currentSpeed_)
				+ ":roadStopped=" + vehicle.getRoadTraversalStoppedTicks();
	}

	private void logStuckTransferFailure(String reason, Junction junction, Signal signal,
			boolean movable, boolean conflictFree, Lane targetLane, double entranceGap,
			double requiredGap, Vehicle conflictBlocker) {
		if (!shouldLogStuckTransferFailure()) return;
		Vehicle targetLeader = targetLane == null ? null : targetLane.lastVehicle();
		StringBuilder msg = new StringBuilder();
		msg.append("STUCK_TRANSFER_FAIL")
				.append(" tick=").append(ContextCreator.getCurrentTick())
				.append(" veh=").append(this.getID())
				.append(" class=").append(this.vehicleClass)
				.append(" state=").append(this.vehicleState)
				.append(" roadStoppedTicks=").append(this.roadTraversalStoppedTicks)
				.append(" roadStoppedSeconds=").append(formatDebugDouble(
						this.roadTraversalStoppedTicks * GlobalVariables.SIMULATION_STEP_SIZE))
				.append(" reason=").append(reason)
				.append(" originZone=").append(this.originID)
				.append(" destZone=").append(this.destinationID)
				.append(" currentRoad=").append(roadLabel(this.road))
				.append(" currentLane=").append(laneLabel(this.lane))
				.append(" distToJunction=").append(formatDebugDouble(this.distance_))
				.append(" speed=").append(formatDebugDouble(this.currentSpeed_))
				.append(" nextRoad=").append(roadLabel(this.nextRoad_))
				.append(" nextLane=").append(laneLabel(targetLane))
				.append(" destRoad=").append(roadLabel(this.destRoad_))
				.append(" pathSize=").append(this.roadPath == null ? -1 : this.roadPath.size())
				.append(" junction=").append(junction == null ? "null" : junction.getID())
				.append(" controlType=").append(junction == null ? "null" : junction.getControlType())
				.append(" signal=").append(signalDebugLabel(signal))
				.append(" ticksToSignalUpdate=").append(signal == null
						? "null" : signal.getNextUpdateTick() - ContextCreator.getCurrentTick())
				.append(" delay=").append(junction == null || this.road == null || this.nextRoad_ == null
						? "null" : junction.getDelay(this.road.getID(), this.nextRoad_.getID()))
				.append(" movable=").append(movable)
				.append(" conflictFree=").append(conflictFree)
				.append(" conflictBlocker=").append(vehicleDebugLabel(conflictBlocker))
				.append(" entranceGap=").append(formatDebugDouble(entranceGap))
				.append(" requiredGap=").append(formatDebugDouble(requiredGap))
				.append(" targetLeader=").append(vehicleDebugLabel(targetLeader))
				.append(" targetRoadVeh=").append(this.nextRoad_ == null ? "null" : this.nextRoad_.getVehicleNum())
				.append(" targetLaneVeh=").append(targetLane == null ? "null" : targetLane.nVehicles())
				.append(" onLane=").append(this.onLane)
				.append(" onRoad=").append(this.onRoad);
		ContextCreator.logger.info(msg.toString());
	}

	private synchronized void queueDepartureFromRoad(Road departureRoad) {
		this.clearShadowImpact();
		this.removeFromCurrentLane();
		this.removeFromCurrentRoad();
		this.resetRoadTraversalPatience();
		this.onLane = false;
		this.onRoad = false;
		this.accRate_ = 0;
		this.accDecided_ = false;
		this.hasAccelerationPlan_ = false;
		this.nextLane_ = null;
		this.nextRoad_ = null;
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		// A queued departure starts a new lane-geometry episode. appendToLane()
		// appends its waypoints, so retaining the old coordMap would make the
		// re-entered vehicle follow stale waypoints from the road it left.
		if (this.coordMap == null) {
			this.coordMap = new LinkedList<Coordinate>();
		} else {
			this.coordMap.clear();
		}
		this.distance_ = 0.0;
		this.nextDistance_ = 0.0;
		this.currentSegmentIdx_ = 0;
		this.currentLaneSlope_ = 0.0;
		this.cachedProjectionLane_ = null;
		this.cachedProjectionDistance_ = 0.0;
		this.currentSpeed_ = 0.0;
		this.roadPath = null;
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
		this.originCoord_ = departureRoad.getStartCoord();
		this.setCurrentCoord(this.originCoord_);
		this.setPreviousEpochCoord(this.originCoord_);
		departureRoad.addVehicleToPendingQueue(this);
	}

	private Road departurableFallbackRoad() {
		return departurableFallbackRoad(this.road);
	}

	private Road departurableFallbackRoad(Road excludedRoad) {
		if (!sameRoad(this.lastDeparturableRoad_, excludedRoad)
				&& isDeparturableRoad(this.lastDeparturableRoad_)) {
			return this.lastDeparturableRoad_;
		}
		// A restored or externally positioned connector vehicle may not yet have a
		// checkpoint. Its source is the only topologically valid physical road in
		// the vehicle's past; never choose a merely nearby road from connector space.
		if (excludedRoad instanceof ConnectorRoad) {
			Road connectorSource = ((ConnectorRoad) excludedRoad).getSourceRoad();
			return isDeparturableRoad(connectorSource) ? connectorSource : null;
		}
		Coordinate fallbackCoord = this.getCurrentCoord();
		if (fallbackCoord == null && excludedRoad != null) {
			fallbackCoord = excludedRoad.getStartCoord();
		}
		if (fallbackCoord == null) return null;
		Road nearbyRoad = ContextCreator.getCityContext().findRoadAtCoordinates(
				fallbackCoord, false, excludedRoad);
		return isDeparturableRoad(nearbyRoad) ? nearbyRoad : null;
	}

	private boolean requeueFromDeparturableRoadForReroute(String source) {
		if (isDeparturableRoad(this.road)) {
			return false;
		}
		Road fallbackRoad = departurableFallbackRoad();
		if (fallbackRoad == null) {
			ContextCreator.logger.warn(source + ": vehicle " + this.getID()
					+ " is on non-departurable road " + roadLabel(this.road)
					+ " and has no departurable fallback road; skipping reroute.");
			this.roadPath = null;
			this.nextRoad_ = null;
			return true;
		}
		ContextCreator.logger.warn(source + ": vehicle " + this.getID()
				+ " is on non-departurable road " + roadLabel(this.road)
				+ "; requeueing departure from " + roadLabel(fallbackRoad) + ".");
		this.originRoad_ = fallbackRoad;
		updateLastDeparturableRoad(fallbackRoad);
		queueDepartureFromRoad(fallbackRoad);
		return true;
	}
	
	public void departure(int roadID) {
		Road road = ContextCreator.getRoadContext().get(roadID);
		departure(road);
	}
	
	/**
	 * Append vehicle to the pending list to the closest road
	 */
	public void departure() {
		if(this.road!=null) {
			departure(this.road);
		}
		else{
			Road road = ContextCreator.getCityContext().findRoadAtCoordinates(this.getCurrentCoord(), false);
			departure(road);
		}
	}


	/**
	 *  A place holder for updating battery status for EVs
	 */
	public void updateBatteryLevel() {
		// Do nothing
	}
	
	/**
	 * A place holder for reporting vehicle status
	 */
	public void reportStatus() {
		// Do nothing
	}

	/**
	 *  Clear the legacy impact from the shadow vehicles and future routing vehicles. Performed before next routing computation.
	 */
	public void clearShadowImpact() {
		if (this.roadPath != null) {
			if (this.Nshadow > this.roadPath.size())
				this.Nshadow = this.roadPath.size();
			if (this.Nshadow > 0) {
				for (int i = 0; i < this.Nshadow; i++) {
					Road r = this.roadPath.get(i);
					r.decreaseShadowVehicleNum();
				}
			}
			this.Nshadow = 0;
			// Clear future routing road impact
			for (Road r : this.futureRoutingRoad) {
				r.decreaseFutureRoutingVehNum();
			}
			this.futureRoutingRoad.clear();
		}
	}

	/**
	 * Remove shadow vehicle count after the vehicle leaves the road
	 * @param r Road that this vehicle left
	 */
	public void removeShadowCount(Road r) {
		if (this.Nshadow > 0) {
			r.decreaseShadowVehicleNum();
			this.Nshadow--;
		}

		// Remove the future routing road impact
		if (this.futureRoutingRoad.contains(r)) {
			r.decreaseFutureRoutingVehNum();
			this.futureRoutingRoad.remove(r);
		}
	}

	/**
	 *  Set shadow vehicles and future routing road
	 */
	public void setShadowImpact() {
		this.Nshadow = GlobalVariables.N_SHADOW;
		if (this.roadPath == null) {
			this.Nshadow = 0;
			return;
		}
		if (this.roadPath.size() < this.Nshadow)
			this.Nshadow = this.roadPath.size();
		if (this.Nshadow > 0) {

			int shadowCount = 1; // Count actual number of Nshadow vehicles added
			double cumlativeTT_Nshadow = 0.0; // Cumulative TT for Nshadow allocation
			double cumulativeTT = 0.0;
			int foundFutureRoutingRoad = 0; // Future routing road count: number of road found in shadow roads
			for (int i = 0; i < this.Nshadow; i++) {
				Road r = this.roadPath.get(i);
				// Increase the shadow vehicle count: include current road
				if (i < 1) {
					// Current vehicle will always be added by default
					// Set the shadow vehicle count
					r.incrementShadowVehicleNum();
				} else {
					if (cumlativeTT_Nshadow <= GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL
							* GlobalVariables.SIMULATION_STEP_SIZE) {
						// Set the shadow vehicle count
						r.incrementShadowVehicleNum();
						cumlativeTT_Nshadow += r.getTravelTime();
						shadowCount += 1;
					}
				}

				cumulativeTT += r.getTravelTime();
				// Found the road with cumulative TT greater than than network refresh interval,
				// use it as the future routing road
				if (foundFutureRoutingRoad < GlobalVariables.PART_REFRESH_MULTIPLIER) {
					if (cumulativeTT >= GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL
							* GlobalVariables.SIMULATION_STEP_SIZE) {
						this.futureRoutingRoad.add(r);
						r.incrementFutureRoutingVehNum();
						// Update the future routing road count
						foundFutureRoutingRoad += 1;
						// Reset the cumulative TT
						cumulativeTT = 0.0;
					}
				}
			}

			// Reset the Nshadow count
			this.Nshadow = shadowCount;

		} else {
			this.Nshadow = 0;
		}
	}
	
	/**
	 * Set the next to-visit road of this vehicle
	 */
	public void setNextRoad() {
		// Special case, the roadPath is null which means the origin
		// and destination are at the same link
		if (this.roadPath == null) {
			this.nextRoad_ = null;
			return;
		}
		this.removeShadowCount(this.roadPath.get(0));
		double nextRouteDistance = this.distToTravel_ - this.distToTravelReferenceDistance_;
		this.roadPath.remove(0);
		double currentRoadDistance = this.currentRoadDistanceToTravel();
		if (!this.roadPath.isEmpty()) {
			// The route estimate used the road-level length for this newly entered
			// road. Replace it with the vehicle's actual within-road distance.
			nextRouteDistance += currentRoadDistance - this.roadPath.get(0).getLength();
		}
		this.distToTravel_ = Math.max(0.0, nextRouteDistance);
		this.distToTravelReferenceDistance_ = currentRoadDistance;
		if (this.road.getID() == this.getDestRoad() || this.roadPath.size() <= 1) {
			this.nextRoad_ = null;
		} else {
			this.nextRoad_ = this.roadPath.get(1);
			this.assignNextLane();
		}
	}
	
	/**
	 * Get the next to-visit road of this vehicle
	 */
	public Road getNextRoad() {
		return this.nextRoad_;
	}

	private double currentRoadDistanceToTravel() {
		return Math.max(0.0, this.distance_);
	}

	private double routeDistanceFromCurrentPosition(List<Road> path) {
		double remainingDistance = this.currentRoadDistanceToTravel();
		if (path == null) return remainingDistance;
		for (int i = 1; i < path.size(); i++) {
			remainingDistance += path.get(i).getLength();
		}
		return remainingDistance;
	}

	private void setDistToTravelEstimate(double remainingDistance) {
		this.distToTravel_ = Math.max(0.0, remainingDistance);
		this.distToTravelReferenceDistance_ = this.currentRoadDistanceToTravel();
	}

	/**
	 * Get the next target lane of this vehicle
	 */
	public Lane getNextLane() {
		return this.nextLane_;
	}

	/**
	 * Replace an unreachable route-prepared next lane with a lane on the same
	 * planned road which is directly reachable from the vehicle's actual lane.
	 * This method never changes {@code nextRoad_}, {@code roadPath}, or the
	 * destination.
	 *
	 * @return the retained or adjusted next lane, or {@code null} when no direct
	 *         successor exists on the planned target road
	 */
	public synchronized Lane adjustNextLaneToDirectSuccessor(Road plannedTargetRoad) {
		if (this.externalRoadTransition || this.road == null || this.lane == null
				|| plannedTargetRoad == null || this.nextRoad_ == null
				|| this.nextRoad_.getID() != plannedTargetRoad.getID()) {
			return null;
		}
		if (this.nextLane_ != null && this.nextLane_.getRoad() == plannedTargetRoad
				&& this.lane.getDownStreamLanes().contains(this.nextLane_.getID())) {
			return this.nextLane_;
		}

		ArrayList<Lane> directSuccessors = new ArrayList<Lane>();
		for (Integer laneID : this.lane.getDownStreamLanes()) {
			if (laneID == null) continue;
			Lane candidate = ContextCreator.getLaneContext().get(laneID);
			if (candidate != null && candidate.getRoad() == plannedTargetRoad
					&& !directSuccessors.contains(candidate)) {
				directSuccessors.add(candidate);
			}
		}

		// Reuse the normal route-aware tie breakers, but only across lanes which
		// are legal from the actual source lane. This preserves the planned road
		// and makes selection independent of loader insertion order.
		Lane adjustedLane = this.selectRoutePreparedNextLane(directSuccessors);
		if (adjustedLane == null) {
			return null;
		}
		this.nextLane_ = adjustedLane;
		return adjustedLane;
	}

	/**
	 * Select an externally asserted lane on the already-planned next road.
	 * Validation is completed before {@code nextLane_} is changed. This method
	 * never changes {@code nextRoad_}, {@code roadPath}, the destination, or lane
	 * membership.
	 *
	 * @return {@code true} only when the exact lane is a direct successor of the
	 *         vehicle's actual current lane on the planned target road
	 */
	public synchronized boolean assertNextLaneForExternalTransition(
			Road plannedTargetRoad, Lane assertedLane) {
		if (this.externalRoadTransition || this.road == null || this.lane == null
				|| plannedTargetRoad == null || assertedLane == null
				|| this.nextRoad_ == null
				|| this.nextRoad_.getID() != plannedTargetRoad.getID()
				|| assertedLane.getRoad() != plannedTargetRoad
				|| plannedTargetRoad.getLaneIndex(assertedLane) < 0
				|| !this.isDirectLaneTransition(this.lane, assertedLane)) {
			return false;
		}
		this.nextLane_ = assertedLane;
		return true;
	}

	/**
	 * Reroute the vehicle in the middle of the road
	 */
	public void rerouteAndSetNextRoad() {
		// Vehicle departed
		this.atOrigin = false;
		// Clear legacy impact
		this.clearShadowImpact();
		this.setDistToTravelEstimate(0.0);
		if (this.road == null || this.destRoad_ == null) {
			ContextCreator.logger.warn("Cannot reroute vehicle " + this.getID()
					+ " because current road or destination road is null.");
			this.roadPath = null;
			this.nextRoad_ = null;
			return;
		}
		if (requeueFromDeparturableRoadForReroute("rerouteAndSetNextRoad")) {
			return;
		}
		this.roadPath = RouteContext.shortestPathRoute(this.road, this.destRoad_, this.rand_route_only); // K-shortest path or shortest path
		if (this.roadPath == null) {
			// Cannot find route between this.road and this.destRoad_, meaning this.road or this.destRoad_ is at a deadend
			// Fallback to use valid roads,  this fallback would fail when the r2 or the new departure road are not properly connnected. How to fix this?
			Road r2 = ContextCreator.getCityContext().findRoadAtCoordinates(this.destRoad_.getEndCoord(), true, this.destRoad_);
			
			if (r2 != null) {
				this.roadPath = RouteContext.shortestPathRoute(this.road, r2, this.rand_route_only); // K-shortest path or shortest path
			}
			
			if(this.roadPath == null) {
				Road r1 = ContextCreator.getCityContext().findRoadAtCoordinates(this.getCurrentCoord(), false, this.road);
				
				if (isDeparturableRoad(r1) && r2 != null) {
					this.roadPath = RouteContext.shortestPathRoute(r1, r2, this.rand_route_only);
				}
				
				if(this.roadPath == null) {
					ContextCreator.logger.warn("Cannot find path from road " + this.road.getOrigID()
							+ " to destination road " + this.destRoad_.getOrigID()
							+ " for vehicle " + this.getID()
							+ "; holding at the road boundary for traversal recovery.");
					this.nextRoad_ = null;
					this.nextLane_ = null;
					return;
				}
				else {
					if (this.road.getDownStreamRoads().contains(r1.getID())) {
						this.roadPath.add(0, this.road);
					} else {
						ContextCreator.logger.warn("rerouteAndSetNextRoad: fallback origin " + roadLabel(r1)
								+ " is not connected from current road " + roadLabel(this.road)
								+ "; requeueing departure from fallback road.");
						this.originRoad_ = r1;
						updateLastDeparturableRoad(r1);
						this.isReachDest = false;
						queueDepartureFromRoad(r1);
						return;
					}
				}
				
			}
			else{
				this.destRoad_ = r2;
			}
		}

		if (this.roadPath.isEmpty()) {
			ContextCreator.logger.warn("Routing returned an empty path for vehicle " + this.getID()
					+ "; holding at the road boundary for traversal recovery.");
			this.roadPath = null;
			this.nextRoad_ = null;
			this.nextLane_ = null;
			return;
		}
		
		this.setShadowImpact();
		this.setDistToTravelEstimate(this.routeDistanceFromCurrentPosition(this.roadPath));
		
	    if (this.roadPath.size() < 2) { // The origin and destination road is the same so this vehicle has arrived
			this.nextRoad_ = null;
		} else {
			this.nextRoad_ = roadPath.get(1);
			this.assignNextLane();
		}
	}
	
	/**
	 * Reroute the vehicle in the middle of the road with a specified next road.
	 * In co-sim scenarios the external simulator is authoritative about which road
	 * the vehicle crosses into, so the path is updated even when nextRoad is not
	 * a direct downstream neighbor of the vehicle's currently stored road.
	 */
	public void rerouteWithSpecifiedNextRoad(Road nextRoad) {
		if (nextRoad == null) {
			ContextCreator.logger.warn("rerouteWithSpecifiedNextRoad: vehicle " + this.getID()
					+ " has null requested next road.");
			this.nextRoad_ = null;
			return;
		}
		if (this.road == null) {
			ContextCreator.logger.warn("rerouteWithSpecifiedNextRoad: vehicle " + this.getID()
					+ " has null current road, cannot reroute to " + nextRoad.getOrigID());
			return;
		}
		if (this.destRoad_ == null) {
			ContextCreator.logger.warn("rerouteWithSpecifiedNextRoad: vehicle " + this.getID()
					+ " has null destination road, cannot reroute to " + nextRoad.getOrigID());
			this.nextRoad_ = null;
			return;
		}
		if (this.nextRoad_ == nextRoad) {
			return; // Already heading to the specified road, nothing to do
		}
		if (!this.road.getDownStreamRoads().contains(nextRoad.getID())) {
			ContextCreator.logger.warn("rerouteWithSpecifiedNextRoad: vehicle " + this.getID()
					+ " current road " + this.road.getOrigID() + " is not directly connected to next road "
					+ nextRoad.getOrigID() + " â€” updating path anyway for co-sim tracking.");
		}
		if (!isDeparturableRoad(nextRoad)) {
			Road fallbackRoad = departurableFallbackRoad();
			if (fallbackRoad == null) {
				ContextCreator.logger.warn("rerouteWithSpecifiedNextRoad: requested next road " + roadLabel(nextRoad)
						+ " is not departurable and vehicle " + this.getID()
						+ " has no departurable fallback road; skipping reroute.");
				this.nextRoad_ = null;
				return;
			}
			ContextCreator.logger.warn("rerouteWithSpecifiedNextRoad: requested next road " + roadLabel(nextRoad)
					+ " is not departurable; requeueing vehicle " + this.getID()
					+ " from " + roadLabel(fallbackRoad) + ".");
			this.originRoad_ = fallbackRoad;
			updateLastDeparturableRoad(fallbackRoad);
			this.isReachDest = false;
			queueDepartureFromRoad(fallbackRoad);
			return;
		}

		// Vehicle departed
		this.atOrigin = false;
		// Clear legacy impact
		this.clearShadowImpact();
		this.roadPath = RouteContext.shortestPathRoute(nextRoad, this.destRoad_, this.rand_route_only);

		if (this.roadPath == null) {
			// Fallback: destination road may be a dead-end; try nearest valid road
			Road r2 = ContextCreator.getCityContext().findRoadAtCoordinates(this.destRoad_.getEndCoord(), true, this.destRoad_);
			if (r2 != null) {
				this.roadPath = RouteContext.shortestPathRoute(nextRoad, r2, this.rand_route_only);
			}
			if (this.roadPath == null) {
				ContextCreator.logger.warn("Cannot find path from " + nextRoad.getOrigID() + " to the vehicle destination, gracefully removing this trip.");
				this.nextRoad_ = null;
				return;
			} else {
				this.destRoad_ = r2;
			}
		}
		if (this.roadPath.isEmpty()) {
			ContextCreator.logger.warn("Routing returned an empty path from " + nextRoad.getOrigID()
					+ " to the vehicle destination, gracefully removing this trip.");
			this.nextRoad_ = null;
			return;
		}

		this.roadPath.add(0, this.road); // Prepend the current road
		this.setShadowImpact();
		this.setDistToTravelEstimate(this.routeDistanceFromCurrentPosition(this.roadPath));
		if (this.roadPath.size() < 2) {
			this.nextRoad_ = null;
		} else {
			this.nextRoad_ = roadPath.get(1);
			this.assignNextLane();
		}
	}
	
	
	/**
	 * Update route based on list of roadIDs, return false if the route start and end links are inconsistent 
	 */
	public boolean updateRouteByRoadName(List<String> route) {
		List<Road> newPath = new ArrayList<Road>();
		for(String rid: route) {
			Road r = ContextCreator.getCityContext().findRoadWithOrigID(rid);
			if(r != null) {
				newPath.add(r);
			}
			else {
				return false;
			}
		}
	    return updateRoute(newPath);
	}
	
	/**
	 * Update route based on list of roads. The route must start from the
	 * vehicle's current road; its terminal road becomes the current trip
	 * destination so trace-replay callers can replace the remaining route.
	 */
	public boolean updateRoute(List<Road> newPath) {
		if (newPath == null || newPath.isEmpty()) {
			ContextCreator.logger.warn("updateRoute skipped for vehicle " + this.getID() + " because the route is empty.");
			return false;
		}
		if (this.road == null) {
			ContextCreator.logger.warn("updateRoute skipped for vehicle " + this.getID()
					+ " because current road is null.");
			return false;
		}
		for(Road r: newPath) {
			if (r == null) {
				ContextCreator.logger.warn("updateRoute skipped for vehicle " + this.getID()
						+ " because the route contains a null road.");
				return false;
			}
		}
		if(this.road != newPath.get(0)) {
			ContextCreator.logger.warn("updateRoute skipped for vehicle " + this.getID()
					+ " because route starts from " + roadLabel(newPath.get(0))
					+ " but current road is " + roadLabel(this.road) + ".");
			return false;
		}
		Road terminalRoad = newPath.get(newPath.size() - 1);
		if (this.destRoad_ != terminalRoad) {
			ContextCreator.logger.info("updateRoute: vehicle " + this.getID()
					+ " destination road changed from " + roadLabel(this.destRoad_)
					+ " to " + roadLabel(terminalRoad) + ".");
			this.destRoad_ = terminalRoad;
			int terminalZone = terminalRoad.getNeighboringZone(true);
			if (terminalZone >= 0 && ContextCreator.getZoneContext().get(terminalZone) != null) {
				this.destinationID = terminalZone;
				if (!this.activityPlan.isEmpty()) {
					double departureTime = this.activityPlan.get(0).getDepartureTime();
					this.activityPlan.set(0, new Plan(terminalZone, terminalRoad.getID(), departureTime));
				}
			} else {
				ContextCreator.logger.warn("updateRoute: terminal road " + roadLabel(terminalRoad)
						+ " has no valid arrival zone; destination zone remains " + this.destinationID + ".");
			}
		}
		// Vehicle departured
		this.atOrigin = false;
		this.clearShadowImpact();
		this.roadPath = newPath;
		this.setDistToTravelEstimate(this.routeDistanceFromCurrentPosition(this.roadPath));
		if (newPath.size() < 2) {
			// Intentional same-road / one-road route: arrive immediately instead of rerouting forever.
			this.nextRoad_ = null;
			this.setShadowImpact();
			return true;
		}
		this.nextRoad_ = newPath.get(1);
		this.setShadowImpact();
		this.assignNextLane();
		return true;
	}
	
	/**
	 * Map the current logical downstream station to another lane on the same road.
	 * Lane control points are used only to interpolate geometry; their spacing does
	 * not determine longitudinal progress.
	 */
	public double distanceInNewLane(Lane plane) {
		if (plane == null || this.lane == null) return Double.NaN;
		if (this.lane == plane) return this.distance_;
		if (plane == this.cachedProjectionLane_) return this.cachedProjectionDistance_;

		Lane.ArcGeometry geometry = this.buildLaneGeometry(plane);
		double fraction = this.logicalRoadFraction(this.lane, this.distance_);
		LanePosition position = this.lanePositionAtRoadFraction(
				geometry, fraction, this.lanePositionScratchA_);
		this.cachedProjectionLane_ = plane;
		this.cachedProjectionDistance_ = position == null ? Double.NaN : position.logicalDistance;
		return this.cachedProjectionDistance_;
	}

	private double logicalRoadFraction(Lane referenceLane, double distanceToJunction) {
		if (referenceLane == null || !Double.isFinite(distanceToJunction)) return Double.NaN;
		double logicalLength = referenceLane.getLength();
		if (!Double.isFinite(logicalLength) || logicalLength <= 0.0) return Double.NaN;
		return Math.max(0.0, Math.min(1.0, distanceToJunction / logicalLength));
	}

	private Lane.ArcGeometry buildLaneGeometry(Lane geometryLane) {
		return geometryLane == null ? null : geometryLane.getArcGeometry();
	}

	private LanePosition lanePositionAtRoadFraction(Lane.ArcGeometry geometry,
			double downstreamFraction, LanePosition result) {
		if (geometry == null || !Double.isFinite(downstreamFraction)) return null;
		double logicalLength = geometry.getLane().getLength();
		if (!Double.isFinite(logicalLength) || logicalLength <= 0.0) return null;
		double fraction = Math.max(0.0, Math.min(1.0, downstreamFraction));
		double fromUpstream = (1.0 - fraction) * geometry.getGeometricLength();
		int segmentIndex = -1;
		for (int i = 0; i < geometry.size() - 1; i++) {
			double segmentLength = geometry.cumulativeAt(i + 1) - geometry.cumulativeAt(i);
			if (segmentLength <= COINCIDENT_WAYPOINT_TOLERANCE_METERS) continue;
			if (fromUpstream <= geometry.cumulativeAt(i + 1) + 1e-9) {
				segmentIndex = i;
				break;
			}
		}
		if (segmentIndex < 0) {
			for (int i = geometry.size() - 2; i >= 0; i--) {
				if (geometry.cumulativeAt(i + 1) - geometry.cumulativeAt(i)
						> COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
					segmentIndex = i;
					break;
				}
			}
		}
		if (segmentIndex < 0) return null;
		double segmentLength = geometry.cumulativeAt(segmentIndex + 1)
				- geometry.cumulativeAt(segmentIndex);
		double param = Math.max(0.0, Math.min(1.0,
				(fromUpstream - geometry.cumulativeAt(segmentIndex)) / segmentLength));
		Coordinate upstream = geometry.coordinateAt(segmentIndex);
		Coordinate downstream = geometry.coordinateAt(segmentIndex + 1);
		double upstreamZ = Double.isFinite(upstream.z) ? upstream.z : 0.0;
		double downstreamZ = Double.isFinite(downstream.z) ? downstream.z : upstreamZ;
		result.logicalDistance = fraction * logicalLength;
		result.coordinate.x = upstream.x + param * (downstream.x - upstream.x);
		result.coordinate.y = upstream.y + param * (downstream.y - upstream.y);
		result.coordinate.z = upstreamZ + param * (downstreamZ - upstreamZ);
		result.segmentIndex = segmentIndex;
		return result;
	}

	static NormalizedArcPosition interpolateNormalizedArc(ArrayList<Coordinate> coordinates,
			double[] cumulative, double geometricLength, double downstreamFraction) {
		if (coordinates == null || cumulative == null || coordinates.size() < 2
				|| cumulative.length != coordinates.size() || !Double.isFinite(geometricLength)
				|| geometricLength <= COINCIDENT_WAYPOINT_TOLERANCE_METERS
				|| !Double.isFinite(downstreamFraction)) return null;
		double fraction = Math.max(0.0, Math.min(1.0, downstreamFraction));
		double fromUpstream = (1.0 - fraction) * geometricLength;
		int segmentIndex = -1;
		for (int i = 0; i < coordinates.size() - 1; i++) {
			double segmentLength = cumulative[i + 1] - cumulative[i];
			if (segmentLength <= COINCIDENT_WAYPOINT_TOLERANCE_METERS) continue;
			if (fromUpstream <= cumulative[i + 1] + 1e-9) {
				segmentIndex = i;
				break;
			}
		}
		if (segmentIndex < 0) {
			for (int i = coordinates.size() - 2; i >= 0; i--) {
				if (cumulative[i + 1] - cumulative[i]
						> COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
					segmentIndex = i;
					break;
				}
			}
		}
		if (segmentIndex < 0) return null;
		double segmentLength = cumulative[segmentIndex + 1] - cumulative[segmentIndex];
		double param = Math.max(0.0, Math.min(1.0,
				(fromUpstream - cumulative[segmentIndex]) / segmentLength));
		Coordinate upstream = coordinates.get(segmentIndex);
		Coordinate downstream = coordinates.get(segmentIndex + 1);
		double upstreamZ = Double.isFinite(upstream.z) ? upstream.z : 0.0;
		double downstreamZ = Double.isFinite(downstream.z) ? downstream.z : upstreamZ;
		return new NormalizedArcPosition(new Coordinate(
				upstream.x + param * (downstream.x - upstream.x),
				upstream.y + param * (downstream.y - upstream.y),
				upstreamZ + param * (downstreamZ - upstreamZ)), segmentIndex);
	}

	private double laneChangeDurationSeconds(double lateralDistanceMeters) {
		double lateralSpeed = GlobalVariables.LANE_CHANGE_LATERAL_SPEED;
		if (!Double.isFinite(lateralSpeed) || lateralSpeed <= 0.0) lateralSpeed = 1.0;
		double minDuration = GlobalVariables.LANE_CHANGE_MIN_DURATION;
		if (!Double.isFinite(minDuration) || minDuration <= 0.0) {
			minDuration = Math.max(0.001, GlobalVariables.SIMULATION_STEP_SIZE);
		}
		return Math.max(minDuration, Math.max(0.0, lateralDistanceMeters) / lateralSpeed);
	}

	private double lateralDistanceAtFraction(Lane first, Lane second, double fraction) {
		Lane.ArcGeometry firstGeometry = this.buildLaneGeometry(first);
		Lane.ArcGeometry secondGeometry = this.buildLaneGeometry(second);
		LanePosition firstPosition = this.lanePositionAtRoadFraction(
				firstGeometry, fraction, this.lanePositionScratchA_);
		LanePosition secondPosition = this.lanePositionAtRoadFraction(
				secondGeometry, fraction, this.lanePositionScratchB_);
		return this.lateralSeparationMeters(firstGeometry, firstPosition, secondPosition);
	}

	private double lateralSeparationMeters(Lane.ArcGeometry sourceGeometry,
			LanePosition sourcePosition, LanePosition targetPosition) {
		if (sourceGeometry == null || sourcePosition == null || targetPosition == null) {
			return Double.NaN;
		}
		double totalSeparation = this.distance2(
				sourcePosition.coordinate, targetPosition.coordinate,
				this.laneChangeSeparationScratch_);
		if (!Double.isFinite(totalSeparation) || totalSeparation <= 0.0) return Double.NaN;
		Coordinate segmentStart = sourceGeometry.coordinateAt(sourcePosition.segmentIndex);
		Coordinate segmentEnd = sourceGeometry.coordinateAt(sourcePosition.segmentIndex + 1);
		double tangentLength = this.distance2(
				segmentStart, segmentEnd, this.laneChangeTangentScratch_);
		if (!Double.isFinite(tangentLength) || tangentLength <= 0.0
				|| !Double.isFinite(this.laneChangeTangentScratch_[1])
				|| !Double.isFinite(this.laneChangeSeparationScratch_[1])) {
			return totalSeparation;
		}
		double bearingDifference = Math.toRadians(
				this.laneChangeSeparationScratch_[1] - this.laneChangeTangentScratch_[1]);
		double lateralDistance = Math.abs(totalSeparation * Math.sin(bearingDifference));
		return Double.isFinite(lateralDistance) && lateralDistance > 0.05
				? lateralDistance : Double.NaN;
	}

	/**
	 * Exact preparation boundary requested for mandatory multi-lane maneuvers:
	 * exclusion threshold + 0.1 m + speed times every remaining maneuver duration.
	 */
	private double mandatoryLaneChangePreparationDistance() {
		double preparation = GlobalVariables.NO_LANECHANGING_LENGTH
				+ MANDATORY_LANE_CHANGE_THRESHOLD_BUFFER_METERS;
		if (this.road == null || this.lane == null) return preparation;
		Lane routeLane = this.targetLane();
		if (routeLane == null || routeLane == this.lane) return preparation;
		int currentIndex = this.road.getLaneIndex(this.lane);
		int targetIndex = this.road.getLaneIndex(routeLane);
		if (currentIndex < 0 || targetIndex < 0) return preparation;
		double fraction = this.logicalRoadFraction(this.lane, this.distance_);
		if (!Double.isFinite(fraction)) return preparation;
		long geometryEpoch = Lane.getArcGeometryEpoch();
		double remainingDuration = this.mandatoryRemainingManeuverDurationSeconds_;
		boolean cached = this.mandatoryPreparationSourceLane_ == this.lane
				&& this.mandatoryPreparationTargetLane_ == routeLane
				&& this.mandatoryPreparationGeometryEpoch_ == geometryEpoch
				&& Double.doubleToLongBits(this.mandatoryPreparationDistance_)
						== Double.doubleToLongBits(this.distance_)
				&& Double.isFinite(remainingDuration);
		if (!cached) {
			remainingDuration = 0.0;
			int direction = targetIndex > currentIndex ? 1 : -1;
			for (int index = currentIndex; index != targetIndex; index += direction) {
				Lane from = this.road.getLane(index);
				Lane to = this.road.getLane(index + direction);
				double lateralDistance = this.lateralDistanceAtFraction(from, to, fraction);
				if (!Double.isFinite(lateralDistance) || lateralDistance <= 0.0) {
					lateralDistance = Math.max(0.1, GlobalVariables.LANE_WIDTH);
				}
				remainingDuration += this.laneChangeDurationSeconds(lateralDistance);
			}
			this.mandatoryPreparationSourceLane_ = this.lane;
			this.mandatoryPreparationTargetLane_ = routeLane;
			this.mandatoryPreparationDistance_ = this.distance_;
			this.mandatoryRemainingManeuverDurationSeconds_ = remainingDuration;
			this.mandatoryPreparationGeometryEpoch_ = geometryEpoch;
		}
		double longitudinalSpeed = Math.max(0.0, this.currentSpeed_);
		return preparation + longitudinalSpeed * remainingDuration;
	}

	/**
	 * The normal route-required lane-changing boundary already includes the
	 * configured endpoint buffer and the distance needed for the remaining
	 * physical lateral maneuvers.
	 */
	private boolean isWithinNoLaneChangingArea() {
		return this.lane != null && Double.isFinite(this.distance_)
				&& this.distance_ <= this.mandatoryLaneChangePreparationDistance();
	}

	/**
	 * A lane is clear only when it has no physical occupant or pending admission.
	 * Active lane changes count as occupants even though their vehicles remain in
	 * their source-lane lists until the physical maneuver completes.
	 */
	private boolean isLaneClear(Lane targetLane) {
		Road targetRoad = targetLane == null ? null : targetLane.getRoad();
		return targetRoad != null
				&& targetLane.nVehicles() == 0
				&& targetLane.firstVehicle() == null
				&& targetLane.lastVehicle() == null
				&& targetRoad.getExternalLaneReservationBlocker(targetLane, this) == null
				&& targetRoad.getLaneChangeReservationBlocker(targetLane, this) == null;
	}

	/** Begin a physical lane-change maneuver; lane-list membership swaps at completion. */
	public boolean changeLane(Lane plane) {
		if (plane == null || this.lane == null || this.road == null || plane == this.lane
				|| this.isLaneChanging() || !this.onLane || !this.onRoad
				|| this.hasActiveConnectorReservation() || this.externalRoadTransition
				|| this.road instanceof ConnectorRoad || this.road.getControlType() == Road.COSIM
				|| plane.getRoad() != this.road
				|| Math.abs(this.road.getLaneIndex(plane) - this.road.getLaneIndex(this.lane)) != 1) {
			return false;
		}
		double fraction = this.logicalRoadFraction(this.lane, this.distance_);
		Lane.ArcGeometry sourceGeometry = this.buildLaneGeometry(this.lane);
		Lane.ArcGeometry targetGeometry = this.buildLaneGeometry(plane);
		LanePosition sourcePosition = this.lanePositionAtRoadFraction(
				sourceGeometry, fraction, this.lanePositionScratchA_);
		LanePosition targetPosition = this.lanePositionAtRoadFraction(
				targetGeometry, fraction, this.lanePositionScratchB_);
		if (sourcePosition == null || targetPosition == null) return false;
		double lateralDistance = this.lateralSeparationMeters(
				sourceGeometry, sourcePosition, targetPosition);
		if (!Double.isFinite(lateralDistance) || lateralDistance <= 0.0) {
			lateralDistance = Math.max(0.1, GlobalVariables.LANE_WIDTH);
		}
		this.laneChangeSourceLane_ = this.lane;
		this.laneChangeTargetLane_ = plane;
		this.laneChangeElapsedSeconds_ = 0.0;
		this.laneChangeLateralDistanceMeters_ = lateralDistance;
		this.laneChangeDurationSeconds_ = this.laneChangeDurationSeconds(lateralDistance);
		this.laneChangeSourceGeometry_ = sourceGeometry;
		this.laneChangeTargetGeometry_ = targetGeometry;
		if (!this.road.registerLaneChangeReservation(plane, this)) {
			this.clearLaneChangeManeuverFields();
			return false;
		}
		return true;
	}

	public boolean isLaneChanging() {
		return this.laneChangeSourceLane_ != null && this.laneChangeTargetLane_ != null;
	}

	public Lane getLaneChangeTargetLane() {
		return this.laneChangeTargetLane_;
	}

	public double getLaneChangeElapsedSeconds() {
		return this.laneChangeElapsedSeconds_;
	}

	public double getLaneChangeDurationSeconds() {
		return this.laneChangeDurationSeconds_;
	}

	public double getLaneChangeLateralDistanceMeters() {
		return this.laneChangeLateralDistanceMeters_;
	}

	/** Logical target-lane station used by deterministic Road reservation queries. */
	public double getLaneChangeReservedDistance(Lane requestedLane) {
		if (!this.isLaneChanging() || requestedLane != this.laneChangeTargetLane_) return Double.NaN;
		double fraction = this.logicalRoadFraction(this.laneChangeSourceLane_, this.distance_);
		return Double.isFinite(fraction) ? fraction * requestedLane.getLength() : Double.NaN;
	}

	/** Distance occupied by this vehicle on a physical or reserved lane. */
	public double getDistanceOnLaneForSafety(Lane requestedLane) {
		if (requestedLane == null) return Double.NaN;
		if (requestedLane == this.lane) return this.distance_;
		return this.getLaneChangeReservedDistance(requestedLane);
	}

	public boolean restoreLaneChangeManeuver(Lane targetLane, double elapsedSeconds,
			double durationSeconds, double lateralDistanceMeters) {
		if (this.isLaneChanging() || targetLane == null || this.lane == null || this.road == null
				|| targetLane.getRoad() != this.road
				|| Math.abs(this.road.getLaneIndex(targetLane) - this.road.getLaneIndex(this.lane)) != 1
				|| this.road instanceof ConnectorRoad || this.road.getControlType() == Road.COSIM) {
			return false;
		}
		double fraction = this.logicalRoadFraction(this.lane, this.distance_);
		Lane.ArcGeometry sourceGeometry = this.buildLaneGeometry(this.lane);
		Lane.ArcGeometry targetGeometry = this.buildLaneGeometry(targetLane);
		LanePosition sourcePosition = this.lanePositionAtRoadFraction(
				sourceGeometry, fraction, this.lanePositionScratchA_);
		LanePosition targetPosition = this.lanePositionAtRoadFraction(
				targetGeometry, fraction, this.lanePositionScratchB_);
		if (sourcePosition == null || targetPosition == null) return false;
		double actualLateralDistance = this.lateralSeparationMeters(
				sourceGeometry, sourcePosition, targetPosition);
		if (!Double.isFinite(lateralDistanceMeters) || lateralDistanceMeters <= 0.0) {
			lateralDistanceMeters = Double.isFinite(actualLateralDistance) && actualLateralDistance > 0.0
					? actualLateralDistance : Math.max(0.1, GlobalVariables.LANE_WIDTH);
		}
		if (!Double.isFinite(durationSeconds) || durationSeconds <= 0.0) {
			durationSeconds = this.laneChangeDurationSeconds(lateralDistanceMeters);
		}
		this.laneChangeSourceLane_ = this.lane;
		this.laneChangeTargetLane_ = targetLane;
		this.laneChangeElapsedSeconds_ = Math.max(0.0, Math.min(durationSeconds, elapsedSeconds));
		this.laneChangeDurationSeconds_ = durationSeconds;
		this.laneChangeLateralDistanceMeters_ = lateralDistanceMeters;
		this.laneChangeSourceGeometry_ = sourceGeometry;
		this.laneChangeTargetGeometry_ = targetGeometry;
		if (!this.road.registerLaneChangeReservation(targetLane, this)) {
			this.clearLaneChangeManeuverFields();
			return false;
		}
		this.refreshActiveLaneChangePose();
		return true;
	}

	private void refreshActiveLaneChangePose() {
		if (!this.isLaneChanging()) return;
		double fraction = this.logicalRoadFraction(this.laneChangeSourceLane_, this.distance_);
		LanePosition sourcePosition = this.lanePositionAtRoadFraction(
				this.laneChangeSourceGeometry_, fraction, this.lanePositionScratchA_);
		LanePosition targetPosition = this.lanePositionAtRoadFraction(
				this.laneChangeTargetGeometry_, fraction, this.lanePositionScratchB_);
		if (sourcePosition == null || targetPosition == null) {
			this.cancelLaneChangeManeuver();
			return;
		}
		double linearProgress = this.laneChangeDurationSeconds_ <= 0.0 ? 1.0
				: Math.max(0.0, Math.min(1.0,
						this.laneChangeElapsedSeconds_ / this.laneChangeDurationSeconds_));
		double progress = linearProgress * linearProgress * (3.0 - 2.0 * linearProgress);
		this.laneChangePreviousCoordScratch_.x = this.currentCoord_.x;
		this.laneChangePreviousCoordScratch_.y = this.currentCoord_.y;
		this.laneChangePreviousCoordScratch_.z = this.currentCoord_.z;
		this.setCurrentCoordInternal(
				sourcePosition.coordinate.x + progress
						* (targetPosition.coordinate.x - sourcePosition.coordinate.x),
				sourcePosition.coordinate.y + progress
						* (targetPosition.coordinate.y - sourcePosition.coordinate.y),
				sourcePosition.coordinate.z + progress
						* (targetPosition.coordinate.z - sourcePosition.coordinate.z));
		double moved = this.distance2(this.laneChangePreviousCoordScratch_,
				this.currentCoord_, this.laneChangeDistanceAngleScratch_);
		if (moved > COINCIDENT_WAYPOINT_TOLERANCE_METERS
				&& Double.isFinite(this.laneChangeDistanceAngleScratch_[1])) {
			this.bearing_ = this.laneChangeDistanceAngleScratch_[1];
		}
		this.currentSegmentIdx_ = sourcePosition.segmentIndex;
		this.currentLaneSlope_ = this.laneChangeSourceLane_.getSegmentSlope(sourcePosition.segmentIndex);
	}

	private double updateActiveLaneChangeByDx(double requestedDx) {
		if (!this.isLaneChanging()) return this.updateCoordByDx(requestedDx);
		double oldSpeed = this.currentSpeed_;
		double step = Math.max(0.001, GlobalVariables.SIMULATION_STEP_SIZE);
		double actualDx = Math.max(0.0, Math.min(Math.max(0.0, requestedDx), this.distance_));
		this.accRate_ = Math.max(this.maxDeceleration_,
				2.0 * (actualDx - oldSpeed * step) / (step * step));
		this.currentSpeed_ = Math.max(0.0, oldSpeed + this.accRate_ * step);
		this.distance_ = Math.max(0.0, this.distance_ - actualDx);
		this.laneChangeElapsedSeconds_ = Math.min(this.laneChangeDurationSeconds_,
				this.laneChangeElapsedSeconds_ + step);
		this.refreshActiveLaneChangePose();
		if (this.isLaneChanging()
				&& this.laneChangeElapsedSeconds_ + 1e-9 >= this.laneChangeDurationSeconds_) {
			this.completeLaneChangeManeuver();
		}
		return actualDx;
	}

	private void completeLaneChangeManeuver() {
		if (!this.isLaneChanging() || this.road == null) return;
		Lane targetLane = this.laneChangeTargetLane_;
		Lane.ArcGeometry targetGeometry = this.laneChangeTargetGeometry_;
		double fraction = this.logicalRoadFraction(this.laneChangeSourceLane_, this.distance_);
		LanePosition targetPosition = this.lanePositionAtRoadFraction(
				targetGeometry, fraction, this.lanePositionScratchA_);
		if (targetPosition == null) {
			this.cancelLaneChangeManeuver();
			return;
		}
		Road currentRoad = this.road;
		currentRoad.unregisterLaneChangeReservation(this);
		this.clearLaneChangeManeuverFields();
		this.removeFromCurrentLane();
		this.installLanePosition(targetLane, targetGeometry, targetPosition);
		this.insertToLane(targetLane);
		this.nosingFlag = false;
		this.lcBlockedTicks_ = 0;
	}

	private void installLanePosition(Lane targetLane, Lane.ArcGeometry geometry,
			LanePosition position) {
		this.distance_ = position.logicalDistance;
		this.setCurrentCoordInternal(position.coordinate);
		this.coordMap.clear();
		for (int i = position.segmentIndex + 1; i < geometry.size(); i++) {
			this.coordMap.add(new Coordinate(geometry.coordinateAt(i)));
		}
		if (this.coordMap.isEmpty()) this.coordMap.add(targetLane.getEndCoord());
		this.currentSegmentIdx_ = position.segmentIndex;
		this.currentLaneSlope_ = targetLane.getSegmentSlope(position.segmentIndex);
		this.updateBearingAndNextDistanceToCoordMap(targetLane);
	}

	/** Cancel native lateral state before an external-control or road lifecycle change. */
	public void cancelLaneChangeForRoadLifecycle() {
		this.cancelLaneChangeManeuver();
	}
	private void cancelLaneChangeManeuver() {
		Road currentRoad = this.road;
		if (currentRoad != null) currentRoad.unregisterLaneChangeReservation(this);
		this.clearLaneChangeManeuverFields();
	}

	private void clearLaneChangeManeuverFields() {
		this.laneChangeSourceLane_ = null;
		this.laneChangeTargetLane_ = null;
		this.laneChangeElapsedSeconds_ = 0.0;
		this.laneChangeDurationSeconds_ = 0.0;
		this.laneChangeLateralDistanceMeters_ = 0.0;
		this.laneChangeSourceGeometry_ = null;
		this.laneChangeTargetGeometry_ = null;
	}

	static final class NormalizedArcPosition {
		final Coordinate coordinate;
		final int segmentIndex;

		NormalizedArcPosition(Coordinate coordinate, int segmentIndex) {
			this.coordinate = coordinate;
			this.segmentIndex = segmentIndex;
		}
	}
	private static final class LanePosition {
		double logicalDistance;
		final Coordinate coordinate = new Coordinate();
		int segmentIndex;
	}
	private void updateBearingAndNextDistanceToCoordMap() {
		this.updateBearingAndNextDistanceToCoordMap(this.onLane ? this.lane : null);
	}

	private void updateBearingAndNextDistanceToCoordMap(Lane distanceLane) {
		double previousBearing = Double.isFinite(this.bearing_) ? this.bearing_ : 0.0;
		this.bearing_ = previousBearing;
		this.nextDistance_ = 0.0;
		Coordinate current = this.currentCoord_;
		if (current == null || this.coordMap.isEmpty()) {
			return;
		}

		double[] distanceAndAngle = new double[2];
		double distanceToFirst =
				ContextCreator.getCityContext().getDistance(current, this.coordMap.get(0));
		if (Double.isFinite(distanceToFirst) && distanceToFirst >= 0.0) {
			this.nextDistance_ = distanceLane == null
					? distanceToFirst : distanceLane.toLogicalDistance(distanceToFirst);
		}

		// Keep nextDistance_ tied to the first waypoint, but look past every
		// coincident leading point when choosing a heading.
		for (Coordinate target : this.coordMap) {
			double distance = this.distance2(current, target, distanceAndAngle);
			if (!Double.isFinite(distance)
					|| distance <= COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
				continue;
			}
			if (Double.isFinite(distanceAndAngle[1])) {
				this.bearing_ = distanceAndAngle[1];
			}
			return;
		}
		this.bearing_ = previousBearing;
	}

	/**
	 * Append a vehicle to vehicle list in a specific lane
	 * @param plane Target lane
	 */
	public void appendToLane(Lane plane) {
		if (plane != null) {
			this.distance_ = this.distance_ + plane.getLength();
			
			ArrayList<Coordinate> coords = plane.getCoords();
			double accDist = plane.getLength();
			for (int i = 0; i < coords.size() - 1; i++) {
				accDist -= plane.toLogicalDistance(distance(coords.get(i), coords.get(i+1)));
				if (this.distance_ + 1e-4 >= accDist) { // Find the first pt in CoordMap that has smaller distance_
					for (int j = i + 1; j < coords.size(); j++) { // Add the rest coords into the CoordMap
						coordMap.add(coords.get(j));
					}
					currentSegmentIdx_ = i;
					currentLaneSlope_ = plane.getSegmentSlope(i);
					break;
				}
			}
			if (coordMap.size() == 0) {
				ContextCreator.logger.error("Lane changing error, could not find coordMap for the target lane:" + lane.getID() + ", accDist: " + accDist+ ", distance: "+ this.distance_);
			}
			this.updateBearingAndNextDistanceToCoordMap(plane);
			this.insertToLane(plane);
			this.nextLane_ = null;
		} else {
			ContextCreator.logger.error("There is no target lane to set!");
		}
		if (Double.isNaN(distance_)) {
			ContextCreator.logger.error("distance_ is NaN in append for " + this);
		}
	}

	/**
	 * Insert vehicle into plane at the location between the leadVehicle and
	 * @param plane Target lane
	 * @param leadVehicle The in front vehicle
	 * @param lagVehicle The behind vehicle
	 */
	public void insertToLane(Lane plane) {
		Vehicle leadVehicle = null;
		Vehicle lagVehicle = null;
		
		Vehicle toCheckVeh = plane.firstVehicle();
		while (toCheckVeh != null) { // find where to insert the veh
			 if(toCheckVeh.getDistanceToNextJunction() < this.distance_) {
				 leadVehicle = toCheckVeh;
				 toCheckVeh = toCheckVeh.trailing();
			 }
			 else {
				 lagVehicle = toCheckVeh;
				 break;
			 }
		}
		if (leadVehicle != null) {
			this.leading(leadVehicle);
			if(this.leading_!=null) this.leading_.trailing(this);
			else plane.firstVehicle(this);
			if (lagVehicle != null) {
				this.trailing(lagVehicle);
				if(this.trailing_!=null) this.trailing_.leading(this);
				else plane.lastVehicle(this);
			} else {
				plane.lastVehicle(this);
			}
		} else if (lagVehicle != null) {
			plane.firstVehicle(this);
			this.trailing(lagVehicle);
			if(this.trailing_!=null) this.trailing_.leading(this);
			else {
				plane.lastVehicle(this);
			}
		} else {
			plane.firstVehicle(this);
			plane.lastVehicle(this);
		}

		this.lane = plane;
		this.lane.addOneVehicle();
		this.onLane = true;
	}
	
	/**
	 * Update coordinates of vehicles given the lane and distance
	 */
	public void teleportToLane(Lane lane, double distance) {
		if(distance <= lane.getLength()) {
			this.distance_ = distance;
			Vehicle leadVehicle = null;
			Vehicle lagVehicle = null;
			
			Vehicle toCheckVeh = lane.firstVehicle();
			while (toCheckVeh != null) { // find where to insert the veh
				 if(toCheckVeh.getDistanceToNextJunction() < distance) {
					 leadVehicle = toCheckVeh;
					 toCheckVeh = toCheckVeh.trailing();
				 }
				 else {
					 lagVehicle = toCheckVeh;
					 break;
				 }
			}
			if (leadVehicle != null) {
				this.leading(leadVehicle);
				if(this.leading_!=null) this.leading_.trailing(this);
				else lane.firstVehicle(this);
				if (lagVehicle != null) {
					this.trailing(lagVehicle);
					if(this.trailing_!=null) this.trailing_.leading(this);
					else lane.lastVehicle(this);
				} else {
					lane.lastVehicle(this);
				}
			} else if (lagVehicle != null) {
				lane.firstVehicle(this);
				this.trailing(lagVehicle);
				if(this.trailing_!=null) this.trailing_.leading(this);
				else {
					lane.lastVehicle(this);
				}
			} else {
				lane.firstVehicle(this);
				lane.lastVehicle(this);
			}
			
			ArrayList<Coordinate> coords = lane.getCoords();
			coordMap.clear();
			double accDist = lane.getLength();
			boolean positioned = false;
			for (int i = 0; i < coords.size() - 1; i++) {
				Coordinate upstream = coords.get(i);
				Coordinate downstream = coords.get(i + 1);
				double geometricSegmentDistance =
						ContextCreator.getCityContext().getDistance(upstream, downstream);
				double segmentDistance = lane.toLogicalDistance(geometricSegmentDistance);
				if (!Double.isFinite(segmentDistance) || segmentDistance <= 1e-9) {
					continue;
				}
				accDist -= segmentDistance;
				if (this.distance_ + 1e-4 >= accDist) { // Find the first pt in CoordMap that has smaller distance_, add noise to avoid numerical issue
					double distanceToDownstream = Math.max(0.0,
							Math.min(segmentDistance, this.distance_ - accDist));
					double distanceFromUpstream = segmentDistance - distanceToDownstream;
					double fraction = Math.max(0.0, Math.min(1.0,
							distanceFromUpstream / segmentDistance));
					this.setCurrentCoord(new Coordinate(
							upstream.x + fraction * (downstream.x - upstream.x),
							upstream.y + fraction * (downstream.y - upstream.y),
							upstream.z + fraction * (downstream.z - upstream.z)));
					double[] distAndAngle = new double[2];
					double horizontalDistance = this.distance2(upstream, downstream, distAndAngle);
					if (horizontalDistance > COINCIDENT_WAYPOINT_TOLERANCE_METERS
							&& Double.isFinite(distAndAngle[1])) {
						this.bearing_ = distAndAngle[1];
					}
					
					for (int j = i + 1; j < coords.size(); j++) { // Add the rest coords into the CoordMap
						coordMap.add(coords.get(j));
					}
					currentSegmentIdx_ = i;
					currentLaneSlope_ = lane.getSegmentSlope(i);
					this.updateBearingAndNextDistanceToCoordMap(lane);
					positioned = true;
					break;
				}
			}
			if (!positioned) {
				// A zero-length connector has no heading of its own. Keep the
				// incoming bearing and install the normal end-of-lane sentinel.
				Coordinate endpoint = lane.getEndCoord();
				this.setCurrentCoord(endpoint);
				this.coordMap.add(endpoint);
				this.updateBearingAndNextDistanceToCoordMap(lane);
				this.currentSegmentIdx_ = 0;
				this.currentLaneSlope_ = 0.0;
			}
			this.lane = lane;
			this.lane.addOneVehicle();
			this.onLane = true;
		} else {
			ContextCreator.logger.error("Teleport to lane error, the specified distance" + distance + "is greater than the length of lane " + lane.getID());
		}
	}
	
	public void resetLaneChangeRuntimeState() {
		this.lcAccelerationAdvice_ = Double.POSITIVE_INFINITY;
	}

	private void addLaneChangeAccelerationAdvice(double acc, int reason, boolean ownAdvice) {
		if (Double.isNaN(acc)) {
			return;
		}
		if (acc < this.lcAccelerationAdvice_) {
			this.lcAccelerationAdvice_ = acc;
		}
	}

	/**
	 * Phase 1: evaluate and execute lane-changing decisions.
	 * Must run for ALL vehicles on the road before acceleration decisions,
	 * so that the acceleration is computed against the correct (post-lane-change) leading vehicle.
	 */
	public void calcLaneChangingState(int tickcount) {
		if (this.lane == null) return;
		boolean invalidDesiredSpeed = !Double.isFinite(this.desiredSpeed_)
				|| this.desiredSpeed_ <= 0.0;
		if (this.road instanceof ConnectorRoad
				|| this.hasActiveConnectorReservation() || this.isLaneChanging()) {
			// Connector occupancy and an already active physical maneuver suppress new
			// lane-changing decisions, but every vehicle still needs a valid target speed.
			if (invalidDesiredSpeed) {
				this.desiredSpeed_ = this.lane.getRandomFreeSpeed(
						rand_car_follow_only.nextGaussian());
			}
			return;
		}
		if (!this.prepareLaneChangeTarget()) return;
		if (this.lane == null || this.road == null || !this.isOnLane()) return;
		this.cachedProjectionLane_ = null;
		if (tickcount % 10 == 0 || invalidDesiredSpeed) {
			this.desiredSpeed_ = this.lane.getRandomFreeSpeed(rand_car_follow_only.nextGaussian());
		}
		if (this.road.getNumberOfLanes() > 1 && this.isOnLane()) {
			this.makeLaneChangingDecision();
		}
	}

	/**
	 * Phase 2: compute acceleration based on the current (possibly new) leading vehicle.
	 * Called after all lane changes on the road are finalized and the macro list is repaired.
	 */
	public void calcAccState() {
		if (!this.accDecided_) {
			this.makeAcceleratingDecision();
		} else {
			this.accDecided_ = false;
			this.ensureAccelerationPlan(this.accRate_);
		}
	}

	/**
	 * The Car-Following model calculates the acceleration rate based on interaction
	 * with other vehicles (car following or lane changing). 
	 * 
	 * This function updates accRate_ at the end.
	 */
	public void makeAcceleratingDecision() {
		double aZ = this.accRate_; /* car-following */
		double acc = this.maxAcceleration_; /* returned rate */
		if (this.isOnLane()) {
			/*
			 * Vehicle will have acceleration rate based on car following if it is not
			 * in yielding or nosing state
			 */
			if (!this.nosingFlag && !this.yieldingFlag) {
				aZ = this.calcCarFollowingRate(this.vehicleAhead());
			} else if (this.nosingFlag) {
				aZ = this.nosing();
			} else if (this.yieldingFlag) {
				aZ = this.yielding();
			}

		if ("LC2013".equals(GlobalVariables.LANE_CHANGING_MODEL) && this.lcAccelerationAdvice_ < aZ) {
			aZ = this.lcAccelerationAdvice_;
		}

		if (aZ < acc)
			acc = aZ; // car-following rate

		double effMaxDec = effectiveModelMaxDeceleration();
		if (acc < effMaxDec) {
			acc = effMaxDec;
		}
	} else {
			// Vehicle is at an intersection, handle it with a different (here simplified)
			// process
			acc = 0;
		}
		
		if (!Double.isFinite(acc)) {
			ContextCreator.logger.error("Invalid planned acceleration " + acc + " for " + this);
			acc = 0.0;
		}
		this.plannedAcceleration_ = acc;
		this.hasAccelerationPlan_ = true;
	}
	
	/**
	 * Calculate the vehicle acceleration when it is free flow (not doing car following)
	 * 
	 * @return acc Vehicle acceleration
	 */
	public double calcFreeFlowRate() {
		double effNormalDec = effectiveNormalDeceleration();
		double effMaxDec    = effectiveMaxDeceleration();
		double comfortableDec = Math.max(0.1, -effNormalDec);
		
		// road ends
		if (this.nextRoad_ != null && this.road != null && this.road.getID() != this.nextRoad_.getID()) {
			Junction nextJunction = this.nextJunction();
			
			if (!(this.road instanceof ConnectorRoad)
					&& requiresPhysicalStop(nextJunction)) { // brake before entering a connector
				double decTime = this.currentSpeed_ / comfortableDec;
				if (this.distance_ <= 0.5 * this.currentSpeed_ * decTime) {
					return  (Math.max(effMaxDec, - 0.5 * (this.currentSpeed_ * this.currentSpeed_ / this.distance_)));
				}
			}
			
			if (this.nextRoad_.getSpeedLimit() < this.currentSpeed_) { // edge case 2: brake to prepare for entering the next road
				double decTime = (this.currentSpeed_ - this.nextRoad_.getSpeedLimit()) / comfortableDec;
				if (this.distance_ <= 0.5 * (this.currentSpeed_ + this.nextRoad_.getSpeedLimit()) * decTime) {
					return  (Math.max(effMaxDec, - 0.5 * (this.currentSpeed_ * this.currentSpeed_
							- this.nextRoad_.getSpeedLimit() * this.nextRoad_.getSpeedLimit()) / this.distance_));
				}
			}
		}
		
		// free flow
		if (this.currentSpeed_ < this.desiredSpeed_) { // accelerate to reach the desired speed
			return Math.min(this.maxAcceleration(), (this.desiredSpeed_ - this.currentSpeed_) / GlobalVariables.SIMULATION_STEP_SIZE);
		} else { // decelerate if it exceeds the desired speed
			return Math.max(effNormalDec, (this.desiredSpeed_ - this.currentSpeed_) / GlobalVariables.SIMULATION_STEP_SIZE);
		}
	}

	private boolean requiresPhysicalStop(Junction junction) {
		if (junction == null || this.road == null || this.nextRoad_ == null) return false;
		int fromRoadID = this.road.getID();
		int toRoadID = this.nextRoad_.getID();
		if (junction.getMandatoryStopDelay(fromRoadID, toRoadID) > this.stopLineWaitTicks) {
			return true;
		}
		if (junction.getControlType() == Junction.StaticSignal
				|| junction.getControlType() == Junction.DynamicSignal) {
			return junction.getSignalState(fromRoadID, toRoadID) > Signal.Yellow;
		}
		return false;
	}
	
	/**
	 * Calculate the vehicle acceleration based on its distance to front vehicle
	 * 
	 * @param front Front vehicle, can be null which means there is no front vehicle
	 * @return acc Vehicle acceleration
	 */
	public double calcCarFollowingRate(Vehicle front) {
		String model = GlobalVariables.CAR_FOLLOWING_MODEL;
		if ("KRAUSS".equals(model)) {
			return calcKraussCarFollowingRate(front);
		}
		if ("IDM".equals(model)) {
			return calcIdmCarFollowingRate(front);
		}
		if ("WIEDEMANN74".equals(model) || "WIEDEMANN_74".equals(model) || "W74".equals(model)) {
			return calcWiedemann74CarFollowingRate(front);
		}
		if ("WIEDEMANN99".equals(model) || "WIEDEMANN_99".equals(model) || "W99".equals(model)) {
			return calcWiedemann99CarFollowingRate(front);
		}
		return calcHermanCarFollowingRate(front);
	}

	private double calcHermanCarFollowingRate(Vehicle front) {
		// If there is no front vehicle the car will be in free flow regime and have max
		// acceleration if not reaching the
		// desired speed
		double acc;
		double space = gapDistance(front);
		double speed = currentSpeed_ == 0f ? 0.00001f : currentSpeed_;
		double headway = 2.0f * space / (speed + currentSpeed_); // time headway
		double hupper, hlower;

		double AlphaDec = GlobalVariables.ALPHA_DEC;
		double BetaDec = GlobalVariables.BETA_DEC;
		double GammaDec = GlobalVariables.GAMMA_DEC;

		double AlphaAcc = GlobalVariables.ALPHA_ACC;
		double BetaAcc = GlobalVariables.BETA_ACC;
		double GammaAcc = GlobalVariables.GAMMA_ACC;

		hupper = GlobalVariables.H_UPPER;
		hlower = GlobalVariables.H_LOWER;

		double effNormalDec = effectiveNormalDeceleration();
		
		// There will be three regimes emergency/free-flow/car-following regime
		// depending on headway
		// Emergency regime
		if (headway < hlower) {
			double dv = currentSpeed_ - front.currentSpeed_;
			if (dv < 0.0f) { // the leader is decelerating
				acc = front.accRate_ + 0.25f * effNormalDec;
			} else {
				if(space <= 0) {
					space = 0.01f;
				}
				acc = front.accRate_ - 0.5f * dv * dv / space;
			}
			acc = Math.min(effNormalDec, acc);
			regime_ = GlobalVariables.STATUS_REGIME_EMERGENCY;
		}
		// Free-flow regime
		else if (headway > hupper) { // desired speed model will do
			acc = calcFreeFlowRate();
			regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
		}
		// We are using Herman model
		else {
			double dv = front.currentSpeed_ - currentSpeed_;
			if (dv < 0) {
				acc = dv * AlphaDec *  Math.pow(currentSpeed_, BetaDec) /  (Math.pow(space, GammaDec));
			} else if (dv > 0) {
				acc = dv * AlphaAcc *  Math.pow(currentSpeed_, BetaAcc) /  (Math.pow(space, GammaAcc));
			} else { // uniform speed
				acc = 0.0f;
			}
			regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
		}
		return acc;
	}

	private double calcKraussCarFollowingRate(Vehicle front) {
		if (front == null) {
			regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			return calcFreeFlowRate();
		}

		double step = GlobalVariables.SIMULATION_STEP_SIZE;
		double tau = kraussHeadwayTime();
		double decel = kraussDecelMagnitude();
		double emergencyDecel = kraussEmergencyDecelMagnitude();
		double rawGap = gapDistance(front);
		double leaderSpeed = Math.max(0.0, front.currentSpeed_);
		double leaderApparentDecel = apparentDecelMagnitude(front);
		double safeSpeed = safeFollowSpeed(rawGap, leaderSpeed, leaderApparentDecel,
				decel, tau, GlobalVariables.KRAUSS_MIN_GAP);

		double freeFlowSpeed = Math.max(0.0, currentSpeed_ + calcFreeFlowRate() * step);
		double maxNextSpeed = Math.max(0.0, currentSpeed_ + this.maxAcceleration() * step);
		double minNextSpeed = Math.max(0.0, currentSpeed_ - emergencyDecel * step);
		double targetSpeed = Math.min(Math.min(Math.min(freeFlowSpeed, maxNextSpeed), safeSpeed),
				Math.max(0.0, desiredSpeed_));
		if (GlobalVariables.KRAUSS_SIGMA > 0) {
			double imperfection = GlobalVariables.KRAUSS_SIGMA * maxAcceleration_ * rand_car_follow_only.nextDouble() * step;
			targetSpeed = Math.max(0.0, targetSpeed - imperfection);
		}
		targetSpeed = Math.max(minNextSpeed, targetSpeed);

		double acc = (targetSpeed - currentSpeed_) / step;
		regime_ = (rawGap <= secureGap(currentSpeed_, leaderSpeed, decel, leaderApparentDecel,
				tau, GlobalVariables.KRAUSS_MIN_GAP))
				? GlobalVariables.STATUS_REGIME_EMERGENCY
				: GlobalVariables.STATUS_REGIME_CARFOLLOWING;
		if (acc < -decel) {
			regime_ = GlobalVariables.STATUS_REGIME_EMERGENCY;
		}
		return clampAcceleration(acc);
	}

	private double calcIdmCarFollowingRate(Vehicle front) {
		double step = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 1e-6);
		double desiredSpeed = Math.max(0.0, desiredSpeed_);
		if (desiredSpeed <= 1e-6) {
			regime_ = front == null ? GlobalVariables.STATUS_REGIME_FREEFLOWING
					: GlobalVariables.STATUS_REGIME_CARFOLLOWING;
			return clampAcceleration(-idmDecelMagnitude());
		}

		double leaderSpeed = front == null ? desiredSpeed : Math.max(0.0, front.currentSpeed_);
		double gap = front == null ? 1.0e6 : gapDistance(front);
		double idmSpeed = idmNextSpeed(gap, currentSpeed_, leaderSpeed, desiredSpeed, front != null);
		double freeFlowSpeed = Math.max(0.0, currentSpeed_ + calcFreeFlowRate() * step);
		double targetSpeed = Math.min(Math.min(idmSpeed, freeFlowSpeed), desiredSpeed);
		double acc = (Math.max(0.0, targetSpeed) - currentSpeed_) / step;

		if (front == null) {
			regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
		} else if (gap <= Math.max(0.1, GlobalVariables.IDM_MIN_GAP) || acc < -idmDecelMagnitude()) {
			regime_ = GlobalVariables.STATUS_REGIME_EMERGENCY;
		} else {
			regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
		}
		return clampAcceleration(acc);
	}

	private double idmNextSpeed(double gap, double egoSpeed, double leaderSpeed,
			double desiredSpeed, boolean hasLeader) {
		double step = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 1e-6);
		int iterations = Math.max(1, (int) (step / positiveOr(GlobalVariables.IDM_STEPPING, 0.25) + 0.5));
		double dt = step / iterations;
		double newSpeed = Math.max(0.0, egoSpeed);
		double remainingGap = hasLeader ? Math.max(0.0, gap) : 1.0e6;

		for (int i = 0; i < iterations; i++) {
			double acc = idmAcceleration(newSpeed, leaderSpeed, remainingGap, desiredSpeed, hasLeader);
			newSpeed = Math.max(0.0, newSpeed + acc * dt);
			if (hasLeader) {
				remainingGap -= Math.max(0.0, (newSpeed - leaderSpeed) * dt);
			}
		}
		return newSpeed;
	}

	private double idmAcceleration(double speed, double leaderSpeed, double gap,
			double desiredSpeed, boolean hasLeader) {
		double speedRatio = Math.max(0.0, speed) / Math.max(1e-6, desiredSpeed);
		double delta = Math.max(0.0, GlobalVariables.IDM_DELTA);
		double interaction = 0.0;
		if (hasLeader) {
			double desiredGap = idmDesiredGap(speed, leaderSpeed);
			double effectiveGap = Math.max(1e-6, gap);
			interaction = desiredGap * desiredGap / (effectiveGap * effectiveGap);
		}
		return maxAcceleration_ * (1.0 - Math.pow(speedRatio, delta) - interaction)
				- GRAVITY * currentLaneSlope_;
	}

	private double idmDesiredGap(double speed, double leaderSpeed) {
		double twoSqrtAccelDecel = 2.0 * Math.sqrt(Math.max(0.1, maxAcceleration_)
				* idmDecelMagnitude());
		double closingSpeed = Math.max(0.0, speed) - Math.max(0.0, leaderSpeed);
		double dynamicGap = speed * Math.max(0.0, GlobalVariables.IDM_TAU)
				+ speed * closingSpeed / twoSqrtAccelDecel;
		return Math.max(0.0, GlobalVariables.IDM_MIN_GAP) + Math.max(0.0, dynamicGap);
	}

	private double idmDecelMagnitude() {
		return positiveOr(GlobalVariables.IDM_DECEL, Math.max(0.1, -effectiveNormalDeceleration()));
	}

	private double calcWiedemann74CarFollowingRate(Vehicle front) {
		if (front == null) {
			regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			return applyWiedemannJerkLimit(calcFreeFlowRate());
		}

		double gap = gapDistance(front);
		double closingSpeed = currentSpeed_ - front.currentSpeed_;
		double tau = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, GlobalVariables.WIEDEMANN74_TAU);
		double desiredGap = wiedemann74DesiredDistance();
		double upperFollowingGap = desiredGap * Math.max(1.0,
				GlobalVariables.WIEDEMANN74_FOLLOWING_DISTANCE_FACTOR);
		double leaderSpeed = Math.max(0.0, front.currentSpeed_);
		double leaderApparentDecel = wiedemannApparentDecelMagnitude(front);
		double normalDecel = Math.max(0.1, -effectiveNormalDeceleration());
		double emergencyDecel = wiedemannEmergencyDecelMagnitude();
		double safeSpeed = safeFollowSpeed(gap, leaderSpeed, leaderApparentDecel,
				normalDecel, tau, wiedemann74Ax_);
		double requiredDecel = requiredFollowerDecel(gap, currentSpeed_, leaderSpeed,
				leaderApparentDecel, tau, wiedemann74Ax_);

		if (gap <= Math.max(0.1, wiedemann74Ax_) || requiredDecel > emergencyDecel) {
			regime_ = GlobalVariables.STATUS_REGIME_EMERGENCY;
			return clampAcceleration(-Math.min(requiredDecel, emergencyDecel));
		}

		if (closingSpeed > 0.0) {
			double timeToDesiredGap = (gap - desiredGap) / Math.max(0.1, closingSpeed);
			if (gap <= desiredGap || timeToDesiredGap <= tau) {
				regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
				double targetSpeed = Math.min(leaderSpeed, safeSpeed);
				return applyWiedemannJerkLimit(accelerationToSpeed(targetSpeed, tau));
			}
		}

		if (gap <= upperFollowingGap) {
			regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
			double ratio = (gap - desiredGap) / Math.max(0.1, upperFollowingGap - desiredGap);
			ratio = Math.max(0.0, Math.min(1.0, ratio));
			double targetSpeed = leaderSpeed + ratio * (Math.min(desiredSpeed_, this.road.getSpeedLimit()) - leaderSpeed);
			targetSpeed = Math.min(targetSpeed, safeSpeed);
			return applyWiedemannJerkLimit(accelerationToSpeed(targetSpeed, tau));
		}

		if (closingSpeed > 0.0 && gap <= upperFollowingGap + closingSpeed * tau) {
			regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
			double targetSpeed = Math.min(safeSpeed, leaderSpeed + (gap - upperFollowingGap) / tau);
			return applyWiedemannJerkLimit(accelerationToSpeed(targetSpeed, tau));
		}

		if (currentSpeed_ < desiredSpeed_) {
			regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			return applyWiedemannJerkLimit(calcFreeFlowRate());
		}
		regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
		return applyWiedemannJerkLimit(accelerationToSpeed(Math.min(desiredSpeed_, safeSpeed), tau));
	}

	private double calcWiedemann99CarFollowingRate(Vehicle front) {
		if (front == null) {
			regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			return applyWiedemannJerkLimit(wiedemann99FreeAcceleration());
		}

		double gap = gapDistance(front);
		double leaderSpeed = Math.max(0.0, front.currentSpeed_);
		double relativeSpeed = leaderSpeed - currentSpeed_;
		double closingSpeed = Math.max(0.0, -relativeSpeed);
		double tau = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, GlobalVariables.WIEDEMANN99_CC1);
		double desiredGap = wiedemann99DesiredSafetyDistance();
		double upperFollowingGap = desiredGap + GlobalVariables.WIEDEMANN99_CC2;
		double leaderApparentDecel = wiedemannApparentDecelMagnitude(front);
		double normalDecel = Math.max(0.1, -effectiveNormalDeceleration());
		double emergencyDecel = wiedemannEmergencyDecelMagnitude();
		double safeSpeed = safeFollowSpeed(gap, leaderSpeed, leaderApparentDecel,
				normalDecel, tau, GlobalVariables.WIEDEMANN99_CC0);
		double requiredDecel = requiredFollowerDecel(gap, currentSpeed_, leaderSpeed,
				leaderApparentDecel, tau, GlobalVariables.WIEDEMANN99_CC0);

		if (gap <= Math.max(0.1, GlobalVariables.WIEDEMANN99_CC0) || requiredDecel > emergencyDecel) {
			regime_ = GlobalVariables.STATUS_REGIME_EMERGENCY;
			return clampAcceleration(-Math.min(requiredDecel, emergencyDecel));
		}

		double brakeEntryTime = Math.max(0.0, -GlobalVariables.WIEDEMANN99_CC3);
		if (closingSpeed > 0.0 && gap > upperFollowingGap
				&& (gap - upperFollowingGap) / Math.max(0.1, closingSpeed) <= brakeEntryTime) {
			regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
			return applyWiedemannJerkLimit(accelerationToSpeed(
					Math.min(safeSpeed, leaderSpeed + Math.max(0.0, gap - upperFollowingGap) / Math.max(tau, 0.1)),
					tau));
		}

		if (gap <= desiredGap || requiredDecel > normalDecel) {
			regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
			return applyWiedemannJerkLimit(accelerationToSpeed(Math.min(leaderSpeed, safeSpeed), tau));
		}

		if (gap <= upperFollowingGap) {
			regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
			double gapOffset = Math.max(0.0, gap - desiredGap);
			double distanceImpact = GlobalVariables.WIEDEMANN99_CC6 * gapOffset * gapOffset;
			double lowerSpeedThreshold = GlobalVariables.WIEDEMANN99_CC4 - distanceImpact;
			double upperSpeedThreshold = GlobalVariables.WIEDEMANN99_CC5 + distanceImpact;
			double acc;
			if (relativeSpeed < lowerSpeedThreshold) {
				acc = accelerationToSpeed(Math.min(leaderSpeed, safeSpeed), tau);
				wiedemannOscillationSign_ = 1.0;
			} else if (relativeSpeed > upperSpeedThreshold) {
				acc = Math.min(wiedemann99FreeAcceleration(), Math.max(0.0, GlobalVariables.WIEDEMANN99_CC7));
				wiedemannOscillationSign_ = -1.0;
			} else {
				if (gap <= desiredGap + 0.1 * Math.max(0.1, GlobalVariables.WIEDEMANN99_CC2)) {
					wiedemannOscillationSign_ = 1.0;
				} else if (gap >= desiredGap + 0.9 * Math.max(0.1, GlobalVariables.WIEDEMANN99_CC2)) {
					wiedemannOscillationSign_ = -1.0;
				}
				acc = wiedemannOscillationSign_ * Math.max(0.0, GlobalVariables.WIEDEMANN99_CC7);
				acc -= (currentSpeed_ - leaderSpeed) / Math.max(1.0, 1.0 / Math.max(0.01, GlobalVariables.WIEDEMANN99_CC6));
			}
			return applyWiedemannJerkLimit(Math.min(acc, accelerationToSpeed(safeSpeed, tau)));
		}

		regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
		return applyWiedemannJerkLimit(Math.min(wiedemann99FreeAcceleration(),
				accelerationToSpeed(safeSpeed, tau)));
	}

	private void initializeWiedemannDriverState() {
		double axOffset = clippedGaussian(0.0, GlobalVariables.WIEDEMANN74_AX_STD,
				-GlobalVariables.WIEDEMANN74_AX_RANGE, GlobalVariables.WIEDEMANN74_AX_RANGE);
		this.wiedemann74Ax_ = Math.max(0.1, GlobalVariables.WIEDEMANN74_AX + axOffset);
		this.wiedemann74Z_ = clippedGaussian(GlobalVariables.WIEDEMANN74_Z_MEAN,
				GlobalVariables.WIEDEMANN74_Z_STD, 0.0, 1.0);
		this.wiedemann99Z_ = clippedGaussian(GlobalVariables.WIEDEMANN99_Z_MEAN,
				GlobalVariables.WIEDEMANN99_Z_STD, 0.0, 1.0);
		this.wiedemannOscillationSign_ = rand_car_follow_only.nextBoolean() ? 1.0 : -1.0;
	}

	private double wiedemann74DesiredDistance() {
		double speed = Math.sqrt(Math.max(0.1, currentSpeed_));
		double bx = GlobalVariables.WIEDEMANN74_BX_ADD
				+ GlobalVariables.WIEDEMANN74_BX_MULT * this.wiedemann74Z_;
		return Math.max(0.1, this.wiedemann74Ax_ + bx * speed);
	}

	private double wiedemann99DesiredSafetyDistance() {
		return Math.max(0.1, GlobalVariables.WIEDEMANN99_CC0
				+ GlobalVariables.WIEDEMANN99_CC1 * Math.max(0.0, currentSpeed_));
	}

	private double wiedemann99FreeAcceleration() {
		double step = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 0.1);
		if (currentSpeed_ >= desiredSpeed_) {
			return calcFreeFlowRate();
		}
		double v80 = 80.0 / 3.6;
		double ratio = Math.min(1.0, Math.max(0.0, currentSpeed_) / v80);
		double acc = GlobalVariables.WIEDEMANN99_CC8
				+ (GlobalVariables.WIEDEMANN99_CC9 - GlobalVariables.WIEDEMANN99_CC8) * ratio
				+ this.wiedemann99Z_;
		double desiredBound = (desiredSpeed_ - currentSpeed_) / step;
		return clampAcceleration(Math.min(Math.min(acc, maxAcceleration()), desiredBound));
	}

	private double wiedemannApparentDecelMagnitude(Vehicle veh) {
		if (veh == null) {
			return Math.max(0.1, -effectiveNormalDeceleration());
		}
		return Math.max(0.1, -veh.effectiveNormalDeceleration());
	}

	private double wiedemannEmergencyDecelMagnitude() {
		return Math.max(0.1, -effectiveMaxDeceleration());
	}

	private double accelerationToSpeed(double targetSpeed, double horizon) {
		double dt = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, horizon);
		return clampAcceleration((Math.max(0.0, targetSpeed) - currentSpeed_) / dt);
	}

	private double applyWiedemannJerkLimit(double acc) {
		if (GlobalVariables.WIEDEMANN_JERK_LIMIT <= 0.0 || Double.isNaN(acc)) {
			return clampAcceleration(acc);
		}
		double maxDelta = GlobalVariables.WIEDEMANN_JERK_LIMIT
				* Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 0.1);
		double limited = Math.max(accRate_ - maxDelta, Math.min(accRate_ + maxDelta, acc));
		return clampAcceleration(limited);
	}

	private double clippedGaussian(double mean, double std, double min, double max) {
		if (std <= 0.0) {
			return Math.max(min, Math.min(max, mean));
		}
		double value = mean + std * rand_car_follow_only.nextGaussian();
		return Math.max(min, Math.min(max, value));
	}

	private double kraussHeadwayTime() {
		double actionStep = Math.max(GlobalVariables.SIMULATION_STEP_SIZE,
				positiveOr(GlobalVariables.KRAUSS_ACTION_STEP_LENGTH, GlobalVariables.SIMULATION_STEP_SIZE));
		return Math.max(actionStep, positiveOr(GlobalVariables.KRAUSS_TAU, actionStep));
	}

	private double kraussDecelMagnitude() {
		return positiveOr(GlobalVariables.KRAUSS_DECEL, Math.max(0.1, -effectiveNormalDeceleration()));
	}

	private double kraussEmergencyDecelMagnitude() {
		return positiveOr(GlobalVariables.KRAUSS_EMERGENCY_DECEL,
				Math.max(kraussDecelMagnitude(), Math.max(0.1, -effectiveMaxDeceleration())));
	}

	private double apparentDecelMagnitude(Vehicle veh) {
		if (veh == null) {
			return positiveOr(GlobalVariables.KRAUSS_APPARENT_DECEL, kraussDecelMagnitude());
		}
		return positiveOr(GlobalVariables.KRAUSS_APPARENT_DECEL,
				Math.max(0.1, -veh.effectiveNormalDeceleration()));
	}

	private double safeFollowSpeed(double gap, double leaderSpeed, double leaderApparentDecel,
			double followerDecel, double tau, double minGap) {
		if (Double.isInfinite(gap) || gap == Double.MAX_VALUE) {
			return Math.max(0.0, desiredSpeed_);
		}
		double availableGap = Math.max(0.0, gap - Math.max(0.0, minGap));
		double leaderStoppingDistance = leaderSpeed * leaderSpeed
				/ (2.0 * Math.max(0.1, leaderApparentDecel));
		double brakeTerm = Math.max(0.1, followerDecel) * tau;
		double radicand = brakeTerm * brakeTerm
				+ 2.0 * Math.max(0.1, followerDecel) * (availableGap + leaderStoppingDistance);
		return Math.max(0.0, -brakeTerm + Math.sqrt(Math.max(0.0, radicand)));
	}

	private double secureGap(double followerSpeed, double leaderSpeed, double followerDecel,
			double leaderApparentDecel, double tau, double minGap) {
		double followerStoppingDistance = followerSpeed * tau
				+ followerSpeed * followerSpeed / (2.0 * Math.max(0.1, followerDecel));
		double leaderStoppingDistance = leaderSpeed * leaderSpeed
				/ (2.0 * Math.max(0.1, leaderApparentDecel));
		return Math.max(minGap, minGap + followerStoppingDistance - leaderStoppingDistance);
	}

	private double requiredFollowerDecel(double gap, double followerSpeed, double leaderSpeed,
			double leaderApparentDecel, double tau, double minGap) {
		double usable = gap - Math.max(0.0, minGap) - followerSpeed * tau
				+ leaderSpeed * leaderSpeed / (2.0 * Math.max(0.1, leaderApparentDecel));
		if (usable <= 0.0) {
			return Double.POSITIVE_INFINITY;
		}
		return followerSpeed * followerSpeed / (2.0 * usable);
	}

	private double clampAcceleration(double acc) {
		if (Double.isNaN(acc)) return 0.0;
		return Math.max(effectiveModelMaxDeceleration(), Math.min(maxAcceleration_, acc));
	}

	private double effectiveModelMaxDeceleration() {
		if ("KRAUSS".equals(GlobalVariables.CAR_FOLLOWING_MODEL)) {
			return -kraussEmergencyDecelMagnitude();
		}
		return effectiveMaxDeceleration();
	}

	private double positiveOr(double value, double fallback) {
		return value > 0.0 ? value : fallback;
	}
	
	/**
	 * Get the closest longitudinal constraint across every lane currently occupied.
	 */
	public Vehicle vehicleAhead() {
		Vehicle best = this.leading_;
		double bestGap = this.laneOccupancyGap(best, this.lane);
		if (this.road != null && this.lane != null) {
			Vehicle reservedSourceLeader = this.road.findLaneChangeReservedLeader(
					this.lane, this, this.distance_);
			double reservedGap = this.laneOccupancyGap(reservedSourceLeader, this.lane);
			if (reservedGap < bestGap) {
				best = reservedSourceLeader;
				bestGap = reservedGap;
			}
		}
		if (this.isLaneChanging()) {
			double targetDistance = this.getLaneChangeReservedDistance(this.laneChangeTargetLane_);
			Vehicle targetLeader = this.leadVehicle(this.laneChangeTargetLane_, targetDistance);
			double targetGap = this.laneOccupancyGap(targetLeader, this.laneChangeTargetLane_);
			if (targetGap < bestGap) best = targetLeader;
		}
		return best;
	}

	private double laneOccupancyGap(Vehicle front, Lane occupiedLane) {
		if (front == null || occupiedLane == null) return Double.POSITIVE_INFINITY;
		double ownDistance = this.getDistanceOnLaneForSafety(occupiedLane);
		double frontDistance = front.getDistanceOnLaneForSafety(occupiedLane);
		if (!Double.isFinite(ownDistance) || !Double.isFinite(frontDistance)) {
			return Double.POSITIVE_INFINITY;
		}
		return ownDistance - frontDistance - front.length();
	}

	/**
	 * Get the upcoming intersection
	 */
	public Junction nextJunction() {
		if (this.road == null) {
			return null;
		}
		return ContextCreator.getJunctionContext().get(this.road.getDownStreamJunction());
	}

	/**
	 * Get the headway distance
	 * @param front Front vehicle
	 * @return headwayDistance
	 */
	public double gapDistance(Vehicle front) {
		if (front == null) return Double.MAX_VALUE;
		double headwayDistance = this.laneOccupancyGap(front, this.lane);
		if (this.isLaneChanging()) {
			headwayDistance = Math.min(headwayDistance,
					this.laneOccupancyGap(front, this.laneChangeTargetLane_));
		}
		if (Double.isFinite(headwayDistance)) return Math.max(0.0, headwayDistance);

		if (front.getLane() != null && this.lane != null) {
			if (this.lane.getRoad() == front.getLane().getRoad()) {
				headwayDistance = this.distance_ - front.getDistanceToNextJunction() - front.length();
			} else {
				headwayDistance = this.distance_ + front.getLane().getLength()
						- front.getDistanceToNextJunction() - front.length();
			}
			return Math.max(0.0, headwayDistance);
		}
		return Double.MAX_VALUE;
	}
	/**
	 * The Lane-Changing model for calculating the lane changing decisions
	 */
	public boolean makeLaneChangingDecision() {
		if ("LC2013".equals(GlobalVariables.LANE_CHANGING_MODEL)) {
			return makeLC2013LaneChangingDecision();
		}
		return makeAhmedLaneChangingDecision();
	}

	// Ahmed (1999) lane changing model.
	private boolean makeAhmedLaneChangingDecision() {
		double distanceFraction = this.distFraction();
		if (distanceFraction < 0.5
				|| this.distance_ <= this.mandatoryLaneChangePreparationDistance()) {
			// Halfway to the downstream intersection, only mantatory LC allowed, check the
			// correct lane.
			if (!this.isInCorrectLane()) { // change lane if not in correct
				// lane
				Lane tarLane = this.tempLane();
				if (tarLane != null) {
					return this.mandatoryLC(tarLane);
				}
			}
			// Do not make a discretionary change away from a route-correct lane in the
			// mandatory lane-changing portion of the approach.
			return false;
		} else if(distanceFraction < 1.0){
			if (distanceFraction > 0.75) {
				// First 25% in the road, do discretionary LC
				double laneChangeProb1 = rand_car_follow_only.nextDouble();
				// The vehicle is at beginning of the lane, it is free to change lane
				Lane tarLane = this.findBetterLane();
				if (tarLane != null) {
					if (laneChangeProb1 < GlobalVariables.LANE_CHANGING_PROB_PART1) {
						return this.discretionaryLC(tarLane);
					}
				}
			} else {
				// First 25%-50% in the road, we do discretionary LC but only to correct lanes
				double laneChangeProb2 = rand_car_follow_only.nextDouble();
				// The vehicle is at beginning of the lane, it is free to change lane
				Lane tarLane = this.findBetterCorrectLane();
				if (tarLane != null) {
					if (laneChangeProb2 < GlobalVariables.LANE_CHANGING_PROB_PART2) {
						return this.discretionaryLC(tarLane);
					}
				}

			}
		}
		return false;
	}

	private boolean makeLC2013LaneChangingDecision() {
		LaneChangeIntent intent = lc2013ChooseIntent();
		if (intent == null || intent.targetLane == null) {
			lc2013DecayPersistentMotivation();
			return false;
		}

		LaneChangeSafety safety = lc2013EvaluateSafety(intent);
		if (safety.accepted) {
			lc2013ApplySafetyAdvice(safety, intent.reason);
			boolean changedLane = this.changeLane(intent.targetLane);
			if (changedLane) {
				this.nosingFlag = false;
				this.lcBlockedTicks_ = 0;
				return true;
			}
		}

		this.lcBlockedTicks_++;
		if (safety.egoAdvice < Double.POSITIVE_INFINITY) {
			addLaneChangeAccelerationAdvice(safety.egoAdvice, intent.reason, true);
		}
		return false;
	}

	private LaneChangeIntent lc2013ChooseIntent() {
		LaneChangeIntent best = null;

		LaneChangeIntent regulatory = lc2013RegulatoryIntent();
		best = lc2013BetterIntent(best, regulatory);

		LaneChangeIntent strategic = lc2013StrategicIntent();
		best = lc2013BetterIntent(best, strategic);

		Lane leftLane = this.leftLane();
		Lane rightLane = this.rightLane();
		best = lc2013BetterIntent(best, lc2013SpeedGainIntent(leftLane, false));
		best = lc2013BetterIntent(best, lc2013SpeedGainIntent(rightLane, true));
		best = lc2013BetterIntent(best, lc2013KeepRightIntent(rightLane));

		return best;
	}

	private LaneChangeIntent lc2013BetterIntent(LaneChangeIntent current, LaneChangeIntent candidate) {
		if (candidate == null || candidate.targetLane == null) {
			return current;
		}
		if (current == null || candidate.urgent && !current.urgent || candidate.score > current.score) {
			return candidate;
		}
		return current;
	}

	private LaneChangeIntent lc2013RegulatoryIntent() {
		if (GlobalVariables.LC2013_REGULATORY_PARAM <= 0 || this.nextLane_ == null
				|| this.lane == null || this.lane.isConnectToLane(this.nextLane_)) {
			return null;
		}
		Lane targetLane = lc2013RouteLaneTowardTarget();
		if (targetLane == null) {
			return null;
		}
		double lookahead = lc2013StrategicLookaheadDistance(targetLane);
		if (this.distance_ > lookahead) {
			return null;
		}
		double urgency = lc2013Urgency(lookahead);
		return new LaneChangeIntent(targetLane, LC_REASON_REGULATORY,
				GlobalVariables.LC2013_REGULATORY_PARAM * (1.0 + urgency), urgency >= 1.0);
	}

	private LaneChangeIntent lc2013StrategicIntent() {
		if (GlobalVariables.LC2013_STRATEGIC_PARAM < 0 || this.nextLane_ == null || this.isInCorrectLane()) {
			return null;
		}
		Lane targetLane = lc2013RouteLaneTowardTarget();
		if (targetLane == null) {
			return null;
		}
		double lookahead = lc2013StrategicLookaheadDistance(targetLane);
		if (this.distance_ > lookahead) {
			return null;
		}
		double urgency = lc2013Urgency(lookahead);
		return new LaneChangeIntent(targetLane, LC_REASON_STRATEGIC,
				GlobalVariables.LC2013_STRATEGIC_PARAM * (1.0 + urgency), urgency >= 1.0);
	}

	private Lane lc2013RouteLaneTowardTarget() {
		Lane targetLane = this.targetLane();
		if (targetLane == null || targetLane == this.lane) {
			return null;
		}
		int targetIndex = this.road.getLaneIndex(targetLane);
		int currentIndex = this.road.getLaneIndex(this.lane);
		if (targetIndex < 0 || currentIndex < 0) {
			return null;
		}
		if (targetIndex < currentIndex) {
			return this.leftLane();
		}
		if (targetIndex > currentIndex) {
			return this.rightLane();
		}
		return null;
	}

	private LaneChangeIntent lc2013SpeedGainIntent(Lane candidateLane, boolean right) {
		if (candidateLane == null || GlobalVariables.LC2013_SPEED_GAIN_PARAM <= 0) {
			return null;
		}
		double newDistance = this.distanceInNewLane(candidateLane);
		Vehicle leadVehicle = this.leadVehicle(candidateLane, newDistance);
		double candidateLaneSpeed = lc2013ExpectedLaneSpeed(candidateLane, newDistance, leadVehicle);
		if (lc2013RemainingTime(newDistance, candidateLaneSpeed) < GlobalVariables.LC2013_SPEED_GAIN_REMAIN_TIME) {
			return null;
		}
		double currentLaneSpeed = lc2013ExpectedLaneSpeed(this.lane, this.distance_, this.leading_);
		double relativeGain = (candidateLaneSpeed - currentLaneSpeed) / Math.max(1.0, desiredSpeed_);
		double asymmetry = right ? Math.max(0.01, GlobalVariables.LC2013_SPEED_GAIN_RIGHT) : 1.0;
		double score = GlobalVariables.LC2013_SPEED_GAIN_PARAM * relativeGain * asymmetry;
		double threshold = GlobalVariables.LC2013_SPEED_GAIN_THRESHOLD;

		if (right) {
			this.lcSpeedGainProbabilityRight_ = lc2013UpdatedProbability(this.lcSpeedGainProbabilityRight_, score);
			score += this.lcSpeedGainProbabilityRight_;
		} else {
			this.lcSpeedGainProbabilityLeft_ = lc2013UpdatedProbability(this.lcSpeedGainProbabilityLeft_, score);
			score += this.lcSpeedGainProbabilityLeft_;
		}

		if (score <= threshold) {
			return null;
		}
		boolean urgent = score >= GlobalVariables.LC2013_SPEED_GAIN_URGENCY;
		return new LaneChangeIntent(candidateLane, LC_REASON_SPEED_GAIN, score, urgent);
	}

	private LaneChangeIntent lc2013KeepRightIntent(Lane rightLane) {
		if (rightLane == null || GlobalVariables.LC2013_KEEP_RIGHT_PARAM <= 0) {
			return null;
		}
		double newDistance = this.distanceInNewLane(rightLane);
		double currentLaneSpeed = lc2013ExpectedLaneSpeed(this.lane, this.distance_, this.leading_);
		Vehicle leadVehicle = this.leadVehicle(rightLane, newDistance);
		double rightLaneSpeed = lc2013ExpectedLaneSpeed(rightLane, newDistance, leadVehicle);
		double unobstructedTime = rightLaneSpeed <= 0.1 ? 0.0
				: Math.max(0.0, this.distance_) / rightLaneSpeed;
		double acceptanceTime = Math.max(0.0, GlobalVariables.LC2013_KEEP_RIGHT_ACCEPTANCE_TIME);
		if (rightLaneSpeed + 0.1 < currentLaneSpeed || unobstructedTime < acceptanceTime) {
			this.lcKeepRightProbability_ = lc2013UpdatedProbability(this.lcKeepRightProbability_, -1.0);
			return null;
		}
		double score = GlobalVariables.LC2013_KEEP_RIGHT_PARAM
				* (1.0 + Math.min(1.0, unobstructedTime / Math.max(1.0, acceptanceTime + 1.0)));
		this.lcKeepRightProbability_ = lc2013UpdatedProbability(this.lcKeepRightProbability_, score);
		return new LaneChangeIntent(rightLane, LC_REASON_KEEP_RIGHT, score + this.lcKeepRightProbability_, false);
	}

	private double lc2013UpdatedProbability(double previous, double score) {
		double step = Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 0.1);
		if (score > 0.0) {
			return Math.min(1.0, previous + score * step);
		}
		return Math.max(0.0, previous - 0.5 * step);
	}

	private void lc2013DecayPersistentMotivation() {
		this.lcSpeedGainProbabilityLeft_ = lc2013UpdatedProbability(this.lcSpeedGainProbabilityLeft_, -1.0);
		this.lcSpeedGainProbabilityRight_ = lc2013UpdatedProbability(this.lcSpeedGainProbabilityRight_, -1.0);
		this.lcKeepRightProbability_ = lc2013UpdatedProbability(this.lcKeepRightProbability_, -1.0);
	}

	private LaneChangeSafety lc2013EvaluateSafety(LaneChangeIntent intent) {
		LaneChangeSafety safety = new LaneChangeSafety();
		boolean routeRequired = intent.reason == LC_REASON_STRATEGIC
				|| intent.reason == LC_REASON_REGULATORY;
		if (routeRequired && this.isWithinNoLaneChangingArea()) {
			safety.accepted = this.isLaneClear(intent.targetLane);
			return safety;
		}
		double newDistance = this.distanceInNewLane(intent.targetLane);
		Vehicle leadVehicle = this.leadVehicle(intent.targetLane, newDistance);
		Vehicle lagVehicle = this.lagVehicle(intent.targetLane, newDistance);
		double leadGap = this.leadGap(leadVehicle, newDistance);
		double lagGap = this.lagGap(lagVehicle, newDistance);
		double assertive = Math.max(0.1, GlobalVariables.LC2013_ASSERTIVE
				* (1.0 + Math.max(-0.5, Math.min(0.5, GlobalVariables.LC2013_IMPATIENCE))));
		if (this.lcBlockedTicks_ > 0) {
			assertive *= 1.0 + Math.min(0.5, 0.05 * this.lcBlockedTicks_);
		}
		double tau = Math.max(kraussHeadwayTime(), GlobalVariables.LC2013_HEADWAY_TIME);
		double minGap = Math.max(GlobalVariables.KRAUSS_MIN_GAP, GlobalVariables.LC2013_MIN_GAP_LAT);
		double egoDecel = kraussDecelMagnitude();

		if (leadVehicle != null) {
			double leaderApparentDecel = apparentDecelMagnitude(leadVehicle);
			double requiredLeadGap = secureGap(currentSpeed_, leadVehicle.currentSpeed_, egoDecel,
					leaderApparentDecel, tau, minGap) / assertive;
			if (leadGap < requiredLeadGap) {
				double safeSpeed = safeFollowSpeed(leadGap, leadVehicle.currentSpeed_,
						leaderApparentDecel, egoDecel, tau, minGap);
				safety.egoAdvice = (safeSpeed - currentSpeed_) / Math.max(GlobalVariables.SIMULATION_STEP_SIZE, 0.1);
				safety.accepted = false;
				return safety;
			}
		}

		if (lagVehicle != null) {
			double lagDecel = lagVehicle.kraussDecelMagnitude();
			double egoApparentDecel = apparentDecelMagnitude(this);
			double requiredLagGap = secureGap(lagVehicle.currentSpeed_, currentSpeed_, lagDecel,
					egoApparentDecel, tau, minGap) / assertive;
			if (lagGap < requiredLagGap) {
				double requiredLagDecel = requiredFollowerDecel(lagGap, lagVehicle.currentSpeed_,
						currentSpeed_, egoApparentDecel, tau, minGap);
				double cooperativeDecel = Math.max(0.0, GlobalVariables.LC2013_COOPERATIVE_PARAM)
						* Math.max(GlobalVariables.LC2013_SAFE_DECEL, lagDecel);
				boolean cooperative = requiredLagDecel <= cooperativeDecel
						&& (intent.urgent || intent.reason == LC_REASON_STRATEGIC
								|| intent.reason == LC_REASON_REGULATORY
								|| rand_car_follow_only.nextDouble() < Math.min(1.0,
										GlobalVariables.LC2013_COOPERATIVE_PARAM));
				if (!cooperative) {
					safety.accepted = false;
					return safety;
				}
				safety.blockingFollower = lagVehicle;
				safety.followerAdvice = -requiredLagDecel * Math.max(0.0,
						Math.min(1.0, GlobalVariables.LC2013_COOPERATIVE_SPEED));
			}
		}

		safety.accepted = true;
		return safety;
	}

	private void lc2013ApplySafetyAdvice(LaneChangeSafety safety, int reason) {
		if (safety.egoAdvice < Double.POSITIVE_INFINITY) {
			addLaneChangeAccelerationAdvice(safety.egoAdvice, reason, true);
		}
		if (safety.blockingFollower != null && safety.followerAdvice < Double.POSITIVE_INFINITY) {
			safety.blockingFollower.addLaneChangeAccelerationAdvice(safety.followerAdvice,
					LC_REASON_COOPERATIVE, false);
		}
	}

	private double lc2013ExpectedLaneSpeed(Lane laneToCheck, double projectedDistance, Vehicle leadVehicle) {
		if (laneToCheck == null) return 0.0;
		double laneDesiredSpeed = Math.min(desiredSpeed_, laneToCheck.getRoad().getSpeedLimit());
		if (leadVehicle == null) {
			return laneDesiredSpeed;
		}
		double leaderDistance = leadVehicle.getDistanceOnLaneForSafety(laneToCheck);
		if (!Double.isFinite(leaderDistance)) leaderDistance = leadVehicle.distance_;
		double gap = Math.max(0.0, projectedDistance - leaderDistance - leadVehicle.length());
		double lookaheadTime = Math.max(GlobalVariables.SIMULATION_STEP_SIZE,
				GlobalVariables.LC2013_SPEED_GAIN_LOOKAHEAD);
		double safeSpeed = safeFollowSpeed(gap, leadVehicle.currentSpeed_, apparentDecelMagnitude(leadVehicle),
				kraussDecelMagnitude(), Math.max(kraussHeadwayTime(), lookaheadTime),
				GlobalVariables.KRAUSS_MIN_GAP);
		return Math.min(laneDesiredSpeed, safeSpeed);
	}

	private double lc2013RemainingTime(double projectedDistance, double expectedSpeed) {
		double speed = Math.max(0.1, expectedSpeed);
		return Math.max(0.0, projectedDistance) / speed;
	}

	private double lc2013LookaheadDistance() {
		return Math.max(0.0, currentSpeed_)
				* Math.max(GlobalVariables.SIMULATION_STEP_SIZE,
						GlobalVariables.LC2013_LOOKAHEAD_TIME);
	}

	private double lc2013StrategicLookaheadDistance(Lane targetLane) {
		double configured = Math.max(0.0, GlobalVariables.LC2013_STRATEGIC_LOOKAHEAD);
		double dynamic = lc2013LookaheadDistance() * Math.max(0.0, GlobalVariables.LC2013_STRATEGIC_PARAM);
		double leftFactor = targetLane == this.leftLane() ? Math.max(0.0, GlobalVariables.LC2013_LOOKAHEAD_LEFT) : 1.0;
		return Math.max(this.mandatoryLaneChangePreparationDistance(),
				Math.max(dynamic, configured) * leftFactor);
	}

	private double lc2013Urgency(double lookaheadDistance) {
		if (lookaheadDistance <= 0.0) {
			return 1.0;
		}
		return Math.max(0.0, Math.min(1.0, 1.0 - this.distance_ / lookaheadDistance));
	}

	private static class LaneChangeIntent {
		final Lane targetLane;
		final int reason;
		final double score;
		final boolean urgent;

		LaneChangeIntent(Lane targetLane, int reason, double score, boolean urgent) {
			this.targetLane = targetLane;
			this.reason = reason;
			this.score = score;
			this.urgent = urgent;
		}
	}

	private static class LaneChangeSafety {
		boolean accepted = false;
		double egoAdvice = Double.POSITIVE_INFINITY;
		Vehicle blockingFollower = null;
		double followerAdvice = Double.POSITIVE_INFINITY;
	}

	/**
	 * Record the vehicle snapshot for visualization. 
	 * 
	 * Note that this is recording
	 * is independent of snapshots of vehicles whether they move or not in the
	 * current tick. (So when vehicles do not move in a tick but we need to record
	 * positions for viz interpolation then recVehSnaphotForVisInterp is useful).
	 * 
	 * Also, we update the coordinates of the previous epoch in the end of the
	 * function.
	 */
	public void recVehSnaphotForVisInterp() {
		Coordinate currentCoord = this.getCurrentCoord();
		if (ContextCreator.dataCollector == null) {
			setPreviousEpochCoord(currentCoord);
			return;
		}
		try {
			ContextCreator.dataCollector.recordSnapshot(this, currentCoord);
		} catch (Throwable t) {
			// Could not log the vehicle's new position in data buffer!
			ContextCreator.logger.debug("ERR" + t.getMessage());
		}
		finally {
			setPreviousEpochCoord(currentCoord);// update the previous coordinate as the current coordinate
		}
	}
	
	public Coordinate getpreviousEpochCoord() {
		return this.previousEpochCoord;
	}

	/**
	 * Prevent visualization interpolation across a discontinuous position change by
	 * resetting the previous snapshot coordinate to the vehicle's current location.
	 */
	public void syncPreviousEpochCoord() {
		setPreviousEpochCoord(this.currentCoord_);
	}
	
	/**
	 * For visualization purpose, record the previous coordinates of the vehicle
	 * @param newCoord New cooridnates of the vehicle
	 */
	protected void setPreviousEpochCoord(Coordinate newCoord) {
		if (newCoord == null) {
			return;
		}
		this.previousEpochCoord.x = newCoord.x;
		this.previousEpochCoord.y = newCoord.y;
	}

	/**
	 * Calculate new location and speed after an iteration based on its current
	 * location, speed and acceleration. 
	 * 
	 * Also, the vehicle will be removed from the network if it arrives its destination.
	 */
	public void move() {
		if (this.externalRoadTransition) {
			return;
		}
		if (this.isDormantOnRoad()) {
			this.currentSpeed_ = 0.0;
			this.accRate_ = 0.0;
			this.accDecided_ = false;
			this.hasAccelerationPlan_ = false;
			this.movingFlag = false;
			return;
		}

		/* Load the acc decision */
		if (!this.hasAccelerationPlan_) {
			ContextCreator.logger.debug("Vehicle.move missing acceleration plan; using zero acceleration. tick="
					+ ContextCreator.getCurrentTick() + " vehicle=" + this.getID()
					+ " road=" + (this.road == null ? -1 : this.road.getID())
					+ " lane=" + (this.lane == null ? -1 : this.lane.getID())
					+ " onLane=" + this.onLane + " state=" + this.vehicleState);
			accRate_ = 0.0;
			this.accDecided_ = false;
		} else {
			accRate_ = this.plannedAcceleration_;
			this.hasAccelerationPlan_ = false;
		}
		
		// Snapshot the speed at the start of this tick. If this tick ends
		// with the vehicle transitioning onto a CoSim road,
		// executeRoadTransition() will restore this value so the speed
		// reported to CARLA matches the speed the vehicle had when it
		// entered the tick (in-tick braking / collision clamping is not
		// applied to the entering speed seen by CARLA).
		this.tickStartSpeed_ = this.currentSpeed_;
		
		/* Sanity check */
		if (distance_ < -0.001 || Double.isNaN(distance_))
			ContextCreator.logger.error("Vehicle.move(): distance_=" + distance_ + " " + this);
		if (currentSpeed_ < 0 || Double.isNaN(currentSpeed_))
			ContextCreator.logger.error("Vehicle.move(): currentSpeed_=" + currentSpeed_ + " " + this);
		if (Double.isNaN(accRate_))
			ContextCreator.logger.error("Vehicle.move(): accRate_=" + accRate_ + " " + this);
		
		this.endTime++;
		this.linkTravelTime++;
		
		double lastStepMove_ = 0;
		
		if (this.isOnLane()) { 
			double dx = 0; // Travel distance calculated by physics
			// Calculate the actual speed and acceleration
			double step = GlobalVariables.SIMULATION_STEP_SIZE;
			
			double dv = Math.max(accRate_ * step, -currentSpeed_); // Change of speed, no back up allowed
			if (dv + currentSpeed_ > 0) { // Still moving at the end of the cycle
				dx = currentSpeed_ * step + 0.5f * dv * step;
			} else { // Stops before the cycle end
				dx = 0.5f * currentSpeed_ * step;
			}
			if (Double.isNaN(dx)) {
				ContextCreator.logger.error("dx is NaN in move() for " + this);
			}

			// Collision clamp: prevent overtaking the lane-level leader in a single step
			if (leading_ != null && leading_.lane == this.lane) {
				double maxDx = this.distance_ - leading_.distance_ - leading_.length();
				if (maxDx < 0) maxDx = 0;
				if (dx > maxDx) {
					dx = maxDx;
				}
			}

			dx = this.applyActiveLaneChangeCollisionClamp(dx);

			// A planned downstream lane is a lane-changing objective, not permission
			// to leave the actual source lane diagonally. Preserve a usable mandatory
			// lane-changing zone and let the normal lane-changing model keep trying.
			dx = this.applyMandatoryLaneChangeJunctionHold(dx);
			if (!this.isOnLane() || this.lane == null || this.road == null) {
				// Bounded missed-turn recovery may have safely detached and requeued
				// the vehicle. Do not continue this already-started move against the
				// old lane geometry.
				this.movingFlag = false;
				return;
			}

			// Update longitudinal and lateral position. Active maneuvers progress even
			// when congestion reduces longitudinal movement to zero.
			lastStepMove_ = this.isLaneChanging()
					? this.updateActiveLaneChangeByDx(dx) : this.updateCoordByDx(dx);
		}
		if (!this.isOnLane()) {
			ContextCreator.getVehicleContext().addTransferringVehicles(this);
		}
		
		// Update the position of vehicles, 0<=distance_<=lane.length()
		if (this.distance_ < 0) {
			this.distance_ = 0;
		}
		if (lastStepMove_ > 0.001) {
			this.accummulatedDistance_ += lastStepMove_; // Record the moved distance
			this.movingFlag = true;
			this.stopLineWaitTicks = 0;
		} else {
			this.movingFlag = false;
			this.roadTraversalStoppedTicks += 1;
			this.updateStopLineWaitTicks();
			this.requestRoadPatienceRecoveryIfNeeded();
		}
		
		if (this.isOnLane()) { 
			this.advanceInMacroList();
			this.advanceInLaneList();
		}
		this.updateNativeConnectorMembership();
	}

	private boolean prepareLaneChangeTarget() {
		if (this.nextRoad_ == null) {
			return true;
		}
		if (this.nextLane_ != null && this.nextLane_.getRoad() == this.nextRoad_) {
			return true;
		}

		// Route changes and external observations can leave nextLane_ unset or
		// attached to an older next road. Refresh it before any lane-changing model
		// is allowed to use that target.
		this.assignNextLane();
		if (this.nextRoad_ == null) {
			return true;
		}
		if (this.nextLane_ != null && this.nextLane_.getRoad() == this.nextRoad_) {
			return true;
		}

		this.requestMissedLaneTransitionRecovery();
		return this.nextRoad_ == null || (this.nextLane_ != null
				&& this.nextLane_.getRoad() == this.nextRoad_);
	}

	private void updateStopLineWaitTicks() {
		if (this.road == null || this.nextRoad_ == null
				|| this.road instanceof ConnectorRoad
				|| this.distance_ > STOP_LINE_WAIT_DISTANCE_METERS) {
			this.stopLineWaitTicks = 0;
			return;
		}
		Junction junction = this.nextJunction();
		if (junction == null || junction.getMandatoryStopDelay(
				this.road.getID(), this.nextRoad_.getID()) <= 0) {
			this.stopLineWaitTicks = 0;
			return;
		}
		this.stopLineWaitTicks += 1;
	}

	/**
	 * Road-traversal patience may change the route only after patience is depleted
	 * and the vehicle is frontmost on its lane. Rechecking this predicate when deferred recovery
	 * executes prevents a queued request from acting after the vehicle moves away
	 * or changes control state.
	 */
	private boolean isRoadTraversalPatienceDepletedAtLaneFront() {
		return this.roadTraversalStoppedTicks
				>= GlobalVariables.MAX_ROAD_TRAVERSAL_PATIENCE
				&& this.road != null
				&& (!(this.road instanceof ConnectorRoad)
						|| (this.currentConnector == this.road
								&& this.currentConnectorPath != null))
				&& this.lane != null && this.lane.getRoad() == this.road
				&& this.lane.firstVehicle() == this
				&& !this.externalRoadTransition
				&& this.road.getControlType() != Road.COSIM;
	}

	private void requestRoadPatienceRecoveryIfNeeded() {
		if (!this.isRoadTraversalPatienceDepletedAtLaneFront()
				|| this.roadPatienceRecoveryResolved || this.destRoad_ == null) {
			return;
		}
		int currentTick = ContextCreator.getCurrentTick();
		int retryInterval = Math.max(1, GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL);
		if (this.roadPatienceLastRecoveryTick >= 0
				&& currentTick - this.roadPatienceLastRecoveryTick < retryInterval) {
			return;
		}
		this.deferredRoadPatienceRecoveryRequested = true;
		this.enqueueDeferredRoadRecovery();
	}

	private void requestMissedLaneTransitionRecovery() {
		if (this.restoreLatchedRoadTraversalRecovery()) {
			return;
		}
		boolean finalAttemptEligible =
				this.isRoadTraversalPatienceDepletedAtLaneFront();
		if (this.missedLaneRecoveryInitialAttempted
				&& (!finalAttemptEligible || this.missedLaneRecoveryFinalAttempted)) {
			return;
		}
		this.deferredMissedLaneRecoveryRequested = true;
		this.enqueueDeferredRoadRecovery();
	}

	private void enqueueDeferredRoadRecovery() {
		if (this.deferredRoadRecoveryQueued) return;
		VehicleContext vehicleContext = ContextCreator.getVehicleContext();
		if (vehicleContext == null) return;
		this.deferredRoadRecoveryQueued = true;
		vehicleContext.addDeferredRoadRecovery(this);
	}

	boolean claimTransferringQueueForTick(int tick) {
		return claimQueueForTick(this.transferringQueueTick_, tick);
	}

	boolean claimArrivalQueueForTick(int tick) {
		return claimQueueForTick(this.arrivalQueueTick_, tick);
	}

	private static boolean claimQueueForTick(AtomicInteger marker, int tick) {
		int observed = marker.get();
		while (observed != tick) {
			if (marker.compareAndSet(observed, tick)) return true;
			observed = marker.get();
		}
		return false;
	}

	private double applyActiveLaneChangeCollisionClamp(double requestedDx) {
		double clampedDx = Math.max(0.0, requestedDx);
		if (this.road == null || this.lane == null) return clampedDx;

		// A normal lane occupant must also respect a vehicle partially entering its
		// lane, even though that vehicle remains linked to its own source lane.
		Vehicle reservedSourceLeader = this.road.findLaneChangeReservedLeader(
				this.lane, this, this.distance_);
		clampedDx = this.clampDxAgainstLaneLeader(
				clampedDx, this.lane, this.distance_, reservedSourceLeader);
		if (!this.isLaneChanging()) return clampedDx;

		double targetDistance = this.getLaneChangeReservedDistance(this.laneChangeTargetLane_);
		if (!Double.isFinite(targetDistance)) return 0.0;
		Vehicle targetLeader = this.leadVehicle(this.laneChangeTargetLane_, targetDistance);
		return this.clampDxAgainstLaneLeader(
				clampedDx, this.laneChangeTargetLane_, targetDistance, targetLeader);
	}

	private double clampDxAgainstLaneLeader(double requestedDx, Lane occupiedLane,
			double ownDistance, Vehicle leader) {
		if (leader == null || occupiedLane == null || !Double.isFinite(ownDistance)) {
			return requestedDx;
		}
		double leaderDistance = leader.getDistanceOnLaneForSafety(occupiedLane);
		if (!Double.isFinite(leaderDistance)) return requestedDx;
		double maximumDx = Math.max(0.0,
				ownDistance - leaderDistance - leader.length());
		return Math.min(requestedDx, maximumDx);
	}
	private double applyMandatoryLaneChangeJunctionHold(double requestedDx) {
		if (this.isLaneChanging() || !this.hasIncompleteMandatoryLaneChange()) {
			return requestedDx;
		}

		double laneLength = this.lane.getLength();
		double holdDistance = this.mandatoryLaneChangePreparationDistance();
		boolean noUsableLaneChangingZone = !Double.isFinite(laneLength)
				|| !Double.isFinite(holdDistance)
				|| this.road == null || this.road.getNumberOfLanes() <= 1
				|| !this.currentRoadHasLaneConnectingTo(this.nextLane_);
		if (noUsableLaneChangingZone
				|| this.isRoadTraversalPatienceDepletedAtLaneFront()) {
			this.requestMissedLaneTransitionRecovery();
			if (!this.hasIncompleteMandatoryLaneChange()) {
				return requestedDx;
			}
		}

		// The preparation boundary is also the only hold point. It includes the
		// configured endpoint buffer plus the longitudinal distance needed to
		// finish every remaining lateral maneuver at the current speed.
		if (noUsableLaneChangingZone) {
			return 0.0;
		}
		double distanceUntilHold = Math.max(0.0, this.distance_ - holdDistance);
		return Math.min(Math.max(0.0, requestedDx), distanceUntilHold);
	}

	private boolean hasIncompleteMandatoryLaneChange() {
		return this.onLane && this.road != null && this.lane != null
				&& this.nextRoad_ != null
				&& (this.nextLane_ == null || this.nextLane_.getRoad() != this.nextRoad_
						|| !this.isDirectLaneTransition(this.lane, this.nextLane_));
	}

	private boolean currentRoadHasLaneConnectingTo(Lane targetLane) {
		if (this.road == null || targetLane == null) {
			return false;
		}
		for (Lane sourceLane : this.road.getLanes()) {
			if (this.isDirectLaneTransition(sourceLane, targetLane)) {
				return true;
			}
		}
		return false;
	}
	
	public double updateCoordByDx(double dx) {
		double lastStepMove = 0;
		boolean travelledMaxDist = false; // True when traveled with maximum distance (=dx).
		double distTravelled = 0; // The distance traveled so far.
		double oldv = currentSpeed_; // Speed at the beginning
		double step = GlobalVariables.SIMULATION_STEP_SIZE;
		accRate_ =  Math.max(this.maxDeceleration_, 2.0f * (dx - oldv * step) / (step * step));
		currentSpeed_ =  Math.max(currentSpeed_ + accRate_ * step, 0);
		while (!travelledMaxDist) {
			// If we can get all the way to the next coords on the route then, just go there
			if (distTravelled + nextDistance_ <= dx + 1e-3) { // Add a small value since the nextDistance_ might be a tiny but non-zero value
				distTravelled += nextDistance_;
				this.setCurrentCoordInternal(this.coordMap.get(0));
				this.coordMap.remove(0);
				if (this.coordMap.isEmpty()) {
					this.distance_ = 0;
					this.setCurrentCoordInternal(this.getLane().getEndCoord());
					this.nextDistance_ = 0;
					lastStepMove = distTravelled;
					this.coordMap.add(this.currentCoord_);
					this.onLane = false; // add to junction
					break;
			} else {
				this.distance_ -= this.nextDistance_;
				this.updateBearingAndNextDistanceToCoordMap();
				if (this.onLane && this.lane != null) {
					currentSegmentIdx_++;
					currentLaneSlope_ = this.lane.getSegmentSlope(currentSegmentIdx_);
				}
			}
			}
			// Otherwise move as far as we can 
			else {
				double distToMove = dx - distTravelled;
				if(distToMove > 0) {
					this.distance_ -=  distToMove;
					this.move2(this.coordMap.get(0), nextDistance_, distToMove);
					this.nextDistance_ -= distToMove;
				}
				lastStepMove =  dx;
				travelledMaxDist = true;
			}
		}
		return lastStepMove;
	}
	
	
	/**
	 * This function makes the vehicle follow the turning curve to get to the next lane.
	 */
	public void enterNextLane(Lane plane) {
		this.coordMap.clear();
		Lane currlane = this.getLane();
		double turningDistance = currlane == null ? 0.0 : currlane.getTurningDist(plane.getID());
		ArrayList<Coordinate> turningCoordinates = currlane == null
				? new ArrayList<Coordinate>() : currlane.getTurningCoords(plane.getID());
		if (turningDistance > 0.0 && !turningCoordinates.isEmpty()) {
			this.distance_ = turningDistance;
			this.coordMap.addAll(turningCoordinates);
			this.updateBearingAndNextDistanceToCoordMap(null);
		}
		else {
			Coordinate targetCoord = plane.getStartCoord();
			this.nextDistance_ = ContextCreator.getCityContext().getDistance(this.getCurrentCoord(), targetCoord);
			this.distance_ = this.nextDistance_;
			this.coordMap.add(targetCoord);
		}
	}

	/**
	 * This function changes the vehicle from its current road to the next road.
	 * 
	 * @return 0-fail , 1-success to change the road
	 */
	public synchronized boolean changeRoad() {
		return this.changeRoadWithOutcome(false).completesSourceTraversal();
	}

	/**
	 * Change road while optionally preserving an externally asserted exact lane.
	 * Exact mode keeps all normal gates but disables reroute and alternate-lane
	 * gridlock recovery.
	 */
	public synchronized boolean changeRoad(boolean exactTargetLane) {
		return this.changeRoadWithOutcome(exactTargetLane).completesSourceTraversal();
	}

	public synchronized RoadTransitionOutcome changeRoadWithOutcome() {
		return this.changeRoadWithOutcome(false);
	}

	private RoadTransitionOutcome changeRoadWithOutcome(boolean exactTargetLane) {
		if (this.isReachDest) {
			this.onLane = false;
			return RoadTransitionOutcome.ALREADY_ARRIVED;
		}
		boolean hadNextRoad = this.nextRoad_ != null;
		if (!this.changeRoadInternal(exactTargetLane)) {
			return RoadTransitionOutcome.BLOCKED;
		}
		return hadNextRoad ? RoadTransitionOutcome.ROAD_CHANGED
				: RoadTransitionOutcome.ARRIVED;
	}

	private boolean changeRoadInternal(boolean exactTargetLane) {
		if (this.isLaneChanging()) return false;
		// This road change was already handed to the external simulator. A second
		// transfer must not consume the following route edge while the pose is still
		// on the connector.
		if (this.externalRoadTransition) {
			return false;
		}
		// Check if the vehicle has reached the destination or not
		if (this.isReachDest) {
			this.clearShadowImpact(); // Clear shadow impact if already reaches destination
			this.onLane = false;
			return true; // Only reach destination once
		} 
		else if (this.nextRoad_ != null) {
			if (this.road == null) {
				logStuckTransferFailure("NO_CURRENT_ROAD", null, null, false, true,
						this.nextLane_, Double.NaN, 1.2 * this.length(), null);
				return false;
			}
			if (this.road instanceof ConnectorRoad) {
				ConnectorRoad connector = (ConnectorRoad) this.road;
				Lane targetLane = this.currentConnectorPath == null ? null
						: this.currentConnectorPath.getTargetLane();
				if (this.currentConnector != connector || targetLane == null
						|| connector.getTargetRoad() != this.nextRoad_
						|| this.lane != connector.getLane(this.currentConnectorPath)
						|| !this.isDirectLaneTransition(this.lane, targetLane)) {
					return false;
				}
				double requiredGap = 1.2 * this.length();
				double targetGap = Double.NaN;
				if (!this.isLaneClear(targetLane)
						&& (targetGap = this.receivingPathClearance(targetLane, requiredGap)) < requiredGap) {
					logStuckTransferFailure("CONNECTOR_EXIT_HEADWAY", null, null,
							true, true, targetLane, targetGap, requiredGap, null);
					return false;
				}
				return this.executeRoadTransitionInternal(targetLane, this.nextRoad_, null, null);
			}
			if (!this.nextRoadMatchesPath()) {
				logStuckTransferFailure("ROUTE_MISMATCH", null, null, false, true,
						this.nextLane_, Double.NaN, 1.2 * this.length(), null);
				if (exactTargetLane) return false;
				ContextCreator.logger.warn("changeRoad: route mismatch for vehicle " + this.getID()
						+ " current road " + this.road.getOrigID()
						+ " next road " + this.nextRoad_.getOrigID() + "; rerouting.");
				this.rerouteAndSetNextRoad();
				return false;
			}
			ConnectorRoad plannedConnector = ContextCreator.getRoadContext()
					.getConnector(this.road, this.nextRoad_);
			if (this.nextLane_ == null && (plannedConnector == null || exactTargetLane)) {
				this.assignNextLane();
			}
			ConnectorRoad.ConnectorPath plannedConnectorPath = plannedConnector == null
					? null : exactTargetLane
							? plannedConnector.getPath(this.lane, this.nextLane_)
							: plannedConnector.selectPath(this.lane, this.nextLane_);
			if (plannedConnectorPath == null) {
				if (exactTargetLane) return false;
				Lane missedTargetLane = this.nextLane_;
				this.requestMissedLaneTransitionRecovery();
				logStuckTransferFailure("MISSED_LANE_RECOVERY_QUEUED",
						this.nextJunction(), null, false, true, missedTargetLane, Double.NaN,
						1.2 * this.length(), null);
				// Route recovery only chooses the new legal successor. Retrying on the
				// next tick makes the repaired movement pass through the junction-control
				// and selected ConnectorPath headway gates below.
				return false;
			}
			this.nextLane_ = plannedConnectorPath.getTargetLane();
			ConnectorRoad.MovementPriority movementPriority =
					ConnectorRoad.movementPriorityForState(plannedConnectorPath.getState());
			Junction nextJunction = this.nextJunction();
			Signal signal = null;
			boolean movable = false;
			if (nextJunction == null) {
				movable = true;
			} else { // nextRoad data is consistent
				switch(nextJunction.getControlType()) {
					case Junction.NoControl:
					case Junction.Yield:
						movable = true;
						break;
					case Junction.Priority:
						movable = movementPriority
								!= ConnectorRoad.MovementPriority.BLOCKED;
						break;
					case Junction.DynamicSignal:
						signal = nextJunction.getSignal(this.road.getID(), this.nextRoad_.getID());
						if(nextJunction.getSignalState(this.road.getID(), this.nextRoad_.getID())<= Signal.Yellow)
							movable = true;
						break;
					case Junction.StaticSignal:
						signal = nextJunction.getSignal(this.road.getID(), this.nextRoad_.getID());
						if(nextJunction.getSignalState(this.road.getID(), this.nextRoad_.getID())<= Signal.Yellow)
							movable = true;
						break;
					case Junction.StopSign:
						if(nextJunction.getMandatoryStopDelay(this.road.getID(),
								this.nextRoad_.getID()) <= this.stopLineWaitTicks)
							movable = true;
						break;
					default:
						movable = true;
						break;
				}
			}

			double requiredGap = 1.2 * this.length();
			double targetGapForDebug = Double.NaN;
			boolean sourceRoadControlledByCosim = this.road.getControlType() == Road.COSIM;
			if(!sourceRoadControlledByCosim && !movable) {
				logStuckTransferFailure("CONTROL_GATE", nextJunction, signal, movable, true,
						this.nextLane_, targetGapForDebug, requiredGap, null);
				return false;
			}

			return this.executeRoadTransitionInternal(this.nextLane_, this.nextRoad_,
					plannedConnector, plannedConnectorPath);
		}
		else{
			// A missing route is not an arrival. Keep the vehicle at the boundary so
			// road-traversal patience can select a feasible successor if routing cannot
			// be restored. Same-road trips still arrive normally.
			if (this.destRoad_ != null && (this.road == null
					|| this.road.getID() != this.destRoad_.getID())) {
				return false;
			}
			this.reachDest();
			return true;
		}
	}
	
	/**
	 * Move across exactly one segment boundary. Physical-road entry selects and
	 * reserves the connector; connector exit enters the already-selected target
	 * lane after the caller's headway check.
	 *
	 * @return true when the transition was accepted; false when its target-lane
	 *         reservation could not be acquired or the request is inconsistent
	 */
	public synchronized boolean executeRoadTransition(Lane targetLane, Road targetRoad) {
		return this.executeRoadTransitionInternal(targetLane, targetRoad, null, null);
	}

	private boolean executeRoadTransitionInternal(Lane targetLane, Road targetRoad,
			ConnectorRoad selectedConnector, ConnectorRoad.ConnectorPath selectedConnectorPath) {
		if (targetLane == null || targetRoad == null || targetLane.getRoad() != targetRoad) {
			return false;
		}
		if (this.externalRoadTransition) {
			return this.externalTransitionTargetRoad == targetRoad
					&& this.externalTransitionTargetLane == targetLane;
		}
		Road sourceRoad = this.road;
		Lane sourceLane = this.lane;
		if (sourceRoad == null || sourceLane == null || sourceLane.getRoad() != sourceRoad
				|| this.nextRoad_ == null || this.nextRoad_.getID() != targetRoad.getID()) {
			return false;
		}
		if (sourceRoad instanceof ConnectorRoad) {
			ConnectorRoad connector = (ConnectorRoad) sourceRoad;
			ConnectorRoad.ConnectorPath connectorPath = connector.getPath(sourceLane);
			if (connector != this.currentConnector || connectorPath == null
					|| connectorPath != this.currentConnectorPath
					|| connector.getTargetRoad() != targetRoad
					|| connectorPath.getTargetLane() != targetLane
					|| !this.isDirectLaneTransition(sourceLane, targetLane)) {
				return false;
			}
			this.coordMap.clear();
			this.removeFromCurrentLane();
			this.removeFromCurrentRoad();
			this.distance_ = 0.0;
			this.nextDistance_ = 0.0;
			this.appendToLane(targetLane);
			this.appendToRoad(targetRoad);
			return true;
		}

		ConnectorRoad connector = selectedConnector;
		ConnectorRoad.ConnectorPath connectorPath = selectedConnectorPath;
		if (connector == null || connectorPath == null) {
			connector = ContextCreator.getRoadContext().getConnector(sourceRoad, targetRoad);
			connectorPath = connector == null ? null : connector.getPath(sourceLane, targetLane);
		}
		if (connector == null || connector.getSourceRoad() != sourceRoad
				|| connector.getTargetRoad() != targetRoad || connectorPath == null
				|| connectorPath.getSourceLane() != sourceLane
				|| connectorPath.getTargetLane() != targetLane) {
			return false;
		}
		Lane connectorLane = connector == null || connectorPath == null ? null
				: connector.getLane(connectorPath);
		if (connector == null || connectorPath == null || connectorLane == null) {
			return false;
		}
		double requiredGap = 1.2 * this.length();
		boolean requireClearConnectorPath = connector.requiresClearPathAdmission(
				connectorPath, requiredGap);
		if (!requireClearConnectorPath) {
			double connectorPathGap = this.entranceGap(connectorLane);
			if (connectorPathGap < requiredGap) {
				logStuckTransferFailure("CONNECTOR_PATH_HEADWAY", this.nextJunction(), null,
						true, true, connectorLane, connectorPathGap, requiredGap, null);
				return false;
			}
		}
		if (!ContextCreator.getRoadContext().tryEnterConnector(connector,
				connectorPath, this, requireClearConnectorPath)) {
			return false;
		}
		boolean transitioned = false;
		try {
			if (connector.getControlType() == Road.COSIM) {
				transitioned = this.beginExternalRoadTransition(
						sourceRoad, targetLane, targetRoad);
				if (transitioned) {
					this.currentConnector = connector;
					this.currentConnectorPath = connectorPath;
					this.connectorFrontCleared = false;
					ContextCreator.getRoadContext()
							.activateConnectorVehicle(connector, this);
					ContextCreator.getRoadContext()
							.updateConnectorVehicleState(connector, this);
				}
				return transitioned;
			}
			this.coordMap.clear();
			this.removeFromCurrentLane();
			this.removeFromCurrentRoad();
			this.distance_ = 0.0;
			this.nextDistance_ = 0.0;
			this.appendToLane(connectorLane);
			this.appendToRoad(connector);
			this.nextLane_ = targetLane;
			this.currentConnector = connector;
			this.currentConnectorPath = connectorPath;
			this.connectorFrontCleared = false;
			ContextCreator.getRoadContext().activateConnectorVehicle(connector, this);
			ContextCreator.getRoadContext().updateConnectorVehicleState(connector, this);
			transitioned = true;
			return true;
		} finally {
			if (!transitioned) releaseUnusedConnectorAdmission(connector);
		}
	}

	private void releaseUnusedConnectorAdmission(ConnectorRoad connector) {
		if (connector == null) return;
		if (this.currentConnector == connector) {
			if (this.externalRoadTransition) {
				this.clearExternalRoadTransitionState();
			} else {
				this.clearNativeConnectorMembership();
			}
			return;
		}
		ContextCreator.getRoadContext().leaveConnector(connector, this);
	}

	private void updateNativeConnectorMembership() {
		ConnectorRoad connector = this.currentConnector;
		if (connector == null) return;
		if (this.onRoad && this.road == connector) {
			if (this.currentConnectorPath == null
					|| this.lane != connector.getLane(this.currentConnectorPath)
					|| !Double.isFinite(this.distance_)) {
				this.clearNativeConnectorMembership();
				return;
			}
			this.connectorFrontCleared = false;
			ContextCreator.getRoadContext().updateConnectorVehicleState(connector, this);
			return;
		}
		if (!this.onRoad || this.road != connector.getTargetRoad()
				|| this.lane == null || this.lane.getRoad() != this.road
				|| !Double.isFinite(this.distance_)) {
			this.clearNativeConnectorMembership();
			return;
		}

		ContextCreator.getRoadContext().updateConnectorVehicleState(connector, this);
		double laneLength = this.lane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0) {
			this.clearNativeConnectorMembership();
			return;
		}
		if (!this.connectorFrontCleared
				&& this.distance_ <= laneLength + COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			this.connectorFrontCleared = true;
			ContextCreator.getRoadContext().connectorFrontCleared(connector, this);
		}
		double rearClearDistance = Math.max(0.0,
				laneLength - Math.max(0.0, this.length()) - 0.25);
		if (this.connectorFrontCleared
				&& this.distance_ <= rearClearDistance
						+ COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			this.clearNativeConnectorMembership();
		}
	}

	private void clearNativeConnectorMembership() {
		ConnectorRoad connector = this.currentConnector;
		if (connector == null) {
			this.currentConnectorPath = null;
			return;
		}
		ContextCreator.getRoadContext().leaveConnector(connector, this);
		this.currentConnector = null;
		this.currentConnectorPath = null;
		this.connectorFrontCleared = false;
	}

	public ConnectorRoad getCurrentConnector() {
		return this.currentConnector;
	}

	public ConnectorRoad.ConnectorPath getCurrentConnectorPath() {
		return this.currentConnectorPath;
	}

	/**
	 * True while the vehicle's front reference point is physically inside the
	 * connector. Intersection occupancy may remain reserved briefly afterward
	 * until the rear of the vehicle clears the junction.
	 */
	public boolean isOnConnector() {
		return this.currentConnector != null
				&& (this.road == this.currentConnector || !this.connectorFrontCleared);
	}

	public boolean hasActiveConnectorReservation() {
		return this.currentConnector != null;
	}

	public double getConnectorDistanceRemaining() {
		if (!this.isOnConnector() || this.lane == null) return Double.NaN;
		if (this.road == this.currentConnector) {
			return Double.isFinite(this.distance_) ? Math.max(0.0, this.distance_) : Double.NaN;
		}
		double remaining = this.distance_ - this.lane.getLength();
		return Double.isFinite(remaining) ? Math.max(0.0, remaining) : Double.NaN;
	}

	/** Exact for native turns and centerline-projected for external turns. */
	public synchronized double getEstimatedConnectorDistanceRemaining() {
		if (!this.isOnConnector() || this.currentConnector == null) return 0.0;
		double nativeRemaining = this.getConnectorDistanceRemaining();
		if (Double.isFinite(nativeRemaining)) return nativeRemaining;
		double estimated = this.currentConnector.estimateRemainingDistance(this.currentCoord_);
		return Double.isFinite(estimated) ? Math.max(0.0, estimated) : Double.NaN;
	}

	public synchronized double getEstimatedConnectorTravelTimeRemaining() {
		if (this.currentConnector == null || !this.isOnConnector()) return 0.0;
		double remainingDistance = this.getEstimatedConnectorDistanceRemaining();
		double selectedPathLength = this.currentConnector
				.getPathLength(this.currentConnectorPath);
		double connectorLength = Double.isFinite(selectedPathLength)
				? Math.max(0.0, selectedPathLength)
				: Math.max(0.0, this.currentConnector.getLength());
		double connectorTravelTime = Math.max(0.0, this.currentConnector.getTravelTime());
		if (!Double.isFinite(remainingDistance)) return Double.NaN;
		if (connectorLength <= 0.0) return 0.0;
		return connectorTravelTime * Math.min(1.0, remainingDistance / connectorLength);
	}

	/** Remaining trip distance including current and future connector movements. */
	public synchronized double getDistToTravelIncludingConnectors() {
		double remaining = Math.max(0.0, this.getDistToTravel());
		if (this.externalRoadTransition && this.currentConnector != null) {
			double connectorRemaining = this.getEstimatedConnectorDistanceRemaining();
			if (Double.isFinite(connectorRemaining)) remaining += connectorRemaining;
			if (this.externalTransitionTargetLane != null
					&& Double.isFinite(this.externalTransitionTargetLane.getLength())) {
				remaining += Math.max(0.0, this.externalTransitionTargetLane.getLength());
			}
		}
		return remaining + this.futureConnectorMetric(false);
	}

	/** Connector-only component of the remaining trip distance. */
	public synchronized double getRemainingConnectorDistance() {
		double remaining = this.isOnConnector()
				? this.getEstimatedConnectorDistanceRemaining() : 0.0;
		if (!Double.isFinite(remaining)) remaining = 0.0;
		return remaining + this.futureConnectorMetric(false);
	}

	/** Connector-only component of the remaining trip travel-time estimate. */
	public synchronized double getRemainingConnectorTravelTime() {
		double remaining = this.isOnConnector()
				? this.getEstimatedConnectorTravelTimeRemaining() : 0.0;
		if (!Double.isFinite(remaining)) remaining = 0.0;
		return remaining + this.futureConnectorMetric(true);
	}

	private double futureConnectorMetric(boolean travelTime) {
		if (this.roadPath == null || this.roadPath.size() < 2
				|| ContextCreator.getRoadContext() == null) return 0.0;
		double total = 0.0;
		boolean skippedOccupiedConnector = false;
		for (int i = 0; i < this.roadPath.size() - 1; i++) {
			Road source = this.roadPath.get(i);
			Road target = this.roadPath.get(i + 1);
			ConnectorRoad connector = ContextCreator.getRoadContext()
					.getConnector(source, target);
			if (connector == null) continue;
			// A native connector entry deliberately keeps roadPath at [source, target, ...].
			// Its remaining geometry was already included above, so do not add the full
			// source-to-target connector a second time.
			if (!skippedOccupiedConnector && this.road == this.currentConnector
					&& connector == this.currentConnector
					&& source == connector.getSourceRoad()
					&& target == connector.getTargetRoad()) {
				skippedOccupiedConnector = true;
				continue;
			}
			double value;
			if (travelTime) {
				value = connector.getTravelTime();
			}
			else {
				ConnectorRoad.ConnectorPath selectedPath = null;
				if (source == this.road && target == this.nextRoad_
						&& this.lane != null) {
					selectedPath = connector.selectPath(this.lane, this.nextLane_);
				}
				double selectedLength = connector.getPathLength(selectedPath);
				value = Double.isFinite(selectedLength)
						? selectedLength : connector.getLength();
			}
			if (Double.isFinite(value) && value > 0.0) total += value;
		}
		return total;
	}

	/** Immutable vehicle-local state required to restore an active connector. */
	public static final class ConnectorPersistenceSnapshot {
		private final ConnectorRoad connector;
		private final ConnectorRoad.ConnectorPath connectorPath;
		private final boolean frontCleared;
		private final boolean externalTransition;
		private final Road externalSourceRoad;
		private final Road externalTargetRoad;
		private final Lane targetLane;
		private final ArrayList<Coordinate> remainingCoordinates;
		private final double distance;
		private final double nextDistance;
		private final int segmentIndex;
		private final double laneSlope;

		private ConnectorPersistenceSnapshot(ConnectorRoad connector,
				ConnectorRoad.ConnectorPath connectorPath,
				boolean frontCleared, boolean externalTransition,
				Road externalSourceRoad, Road externalTargetRoad, Lane targetLane,
				ArrayList<Coordinate> remainingCoordinates, double distance,
				double nextDistance, int segmentIndex, double laneSlope) {
			this.connector = connector;
			this.connectorPath = connectorPath;
			this.frontCleared = frontCleared;
			this.externalTransition = externalTransition;
			this.externalSourceRoad = externalSourceRoad;
			this.externalTargetRoad = externalTargetRoad;
			this.targetLane = targetLane;
			this.remainingCoordinates = remainingCoordinates;
			this.distance = distance;
			this.nextDistance = nextDistance;
			this.segmentIndex = segmentIndex;
			this.laneSlope = laneSlope;
		}

		public ConnectorRoad getConnector() { return this.connector; }
		public ConnectorRoad.ConnectorPath getConnectorPath() { return this.connectorPath; }
		public boolean isFrontCleared() { return this.frontCleared; }
		public boolean isExternalTransition() { return this.externalTransition; }
		public Road getExternalSourceRoad() { return this.externalSourceRoad; }
		public Road getExternalTargetRoad() { return this.externalTargetRoad; }
		public Lane getTargetLane() { return this.targetLane; }
		public List<Coordinate> getRemainingCoordinates() {
			return Collections.unmodifiableList(this.remainingCoordinates);
		}
		public double getDistance() { return this.distance; }
		public double getNextDistance() { return this.nextDistance; }
		public int getSegmentIndex() { return this.segmentIndex; }
		public double getLaneSlope() { return this.laneSlope; }
	}

	/** Capture connector fields atomically with respect to road transitions. */
	public synchronized ConnectorPersistenceSnapshot getConnectorPersistenceSnapshot() {
		ConnectorRoad connector = this.currentConnector;
		if (connector == null) {
			if (this.externalRoadTransition) {
				throw new IllegalStateException("Vehicle " + this.getID()
						+ " has an external transition without a connector");
			}
			return null;
		}
		ConnectorRoad.ConnectorPath connectorPath = this.currentConnectorPath;
		if (connectorPath == null || !connector.getPaths().contains(connectorPath)) {
			throw new IllegalStateException("Vehicle " + this.getID()
					+ " has incomplete connector-path state");
		}
		Lane targetLane = this.externalRoadTransition
				? this.externalTransitionTargetLane : connectorPath.getTargetLane();
		if (targetLane == null || targetLane.getRoad() != connector.getTargetRoad()
				|| connectorPath.getTargetLane() != targetLane) {
			throw new IllegalStateException("Vehicle " + this.getID()
					+ " has incomplete connector target-lane state");
		}
		if (this.externalRoadTransition
				&& (this.externalTransitionSourceRoad != connector.getSourceRoad()
						|| this.externalTransitionTargetRoad != connector.getTargetRoad())) {
			throw new IllegalStateException("Vehicle " + this.getID()
					+ " has inconsistent external connector roads");
		}
		ArrayList<Coordinate> remaining = new ArrayList<Coordinate>();
		for (Coordinate coordinate : this.coordMap) {
			if (coordinate != null) remaining.add(new Coordinate(coordinate));
		}
		return new ConnectorPersistenceSnapshot(connector, connectorPath,
				this.connectorFrontCleared, this.externalRoadTransition,
				this.externalTransitionSourceRoad, this.externalTransitionTargetRoad,
				targetLane, remaining, this.distance_, this.nextDistance_,
				this.currentSegmentIdx_, this.currentLaneSlope_);
	}

	/**
	 * Restore the physical path and reservation for a vehicle already attached to
	 * its saved connector segment (or to the external target-road handoff). Normal
	 * admission checks are bypassed because all occupants share one snapshot.
	 */
	public synchronized void restoreConnectorPersistenceState(
			ConnectorRoad connector, ConnectorRoad.ConnectorPath connectorPath,
			boolean frontCleared, boolean externalTransition,
			Road externalSourceRoad, Road externalTargetRoad, Lane targetLane,
			List<Coordinate> remainingCoordinates, double restoredDistance,
			double restoredNextDistance, int restoredSegmentIndex,
			double restoredLaneSlope, Coordinate restoredPose,
			double restoredBearing) {
		boolean nativeConnector = connector != null && connectorPath != null
				&& !externalTransition && this.road == connector
				&& this.lane == connector.getLane(connectorPath);
		boolean externalConnector = connector != null && externalTransition
				&& this.road == connector.getTargetRoad() && this.lane == null;
		if (connector == null || connectorPath == null || targetLane == null || restoredPose == null
				|| (!nativeConnector && !externalConnector)
				|| !connector.getPaths().contains(connectorPath)
				|| connectorPath.getTargetLane() != targetLane
				|| targetLane.getRoad() != connector.getTargetRoad()
				|| this.currentConnector != null || this.externalRoadTransition
				|| !Double.isFinite(restoredDistance) || restoredDistance < 0.0
				|| !Double.isFinite(restoredNextDistance) || restoredNextDistance < 0.0) {
			throw new IllegalArgumentException("Invalid saved connector state for vehicle "
					+ this.getID());
		}
		if (externalTransition) {
			if (this.lane != null || externalSourceRoad != connector.getSourceRoad()
					|| externalTargetRoad != connector.getTargetRoad()) {
				throw new IllegalStateException("Cannot restore external connector state for vehicle "
						+ this.getID());
			}
			if (!externalTargetRoad.tryReserveExternalLane(targetLane, this)) {
				ContextCreator.logger.warn("Restored authoritative connector state for vehicle "
						+ this.getID() + " without a target-lane reservation; native hand-back "
						+ "will retry the reservation.");
			}
		}

		this.currentCoord_ = new Coordinate(restoredPose);
		this.bearing_ = restoredBearing;
		this.distance_ = restoredDistance;
		this.nextDistance_ = restoredNextDistance;
		this.currentSegmentIdx_ = Math.max(0, restoredSegmentIndex);
		this.currentLaneSlope_ = restoredLaneSlope;
		this.coordMap.clear();
		if (remainingCoordinates != null) {
			for (Coordinate coordinate : remainingCoordinates) {
				if (coordinate != null) this.coordMap.add(new Coordinate(coordinate));
			}
		}
		if (this.coordMap.isEmpty()) this.coordMap.add(new Coordinate(restoredPose));
		this.currentConnector = connector;
		this.currentConnectorPath = connectorPath;
		this.connectorFrontCleared = frontCleared;
		this.externalRoadTransition = externalTransition;
		this.externalTransitionSourceRoad = externalTransition ? externalSourceRoad : null;
		this.externalTransitionTargetRoad = externalTransition ? externalTargetRoad : null;
		this.externalTransitionTargetLane = externalTransition ? targetLane : null;
		this.onRoad = true;
		this.onLane = !externalTransition;

		try {
			ContextCreator.getRoadContext().restoreConnectorVehicle(
					connector, this, externalTransition && !frontCleared);
			if (externalTransition) {
				ContextCreator.getVehicleContext().registerExternalRoadTransition(this);
			}
		} catch (RuntimeException ex) {
			if (externalTransition) {
				externalTargetRoad.releaseExternalLaneReservation(targetLane, this);
			}
			this.currentConnector = null;
			this.currentConnectorPath = null;
			this.connectorFrontCleared = false;
			this.externalRoadTransition = false;
			this.externalTransitionSourceRoad = null;
			this.externalTransitionTargetRoad = null;
			this.externalTransitionTargetLane = null;
			throw ex;
		}
		this.syncPreviousEpochCoord();
		if (this.lane != null) {
			this.advanceInLaneList();
			this.retreatInLaneList();
		}
		this.advanceInMacroList();
		this.retreatInMacroList();
	}

	private boolean beginExternalRoadTransition(Road sourceRoad, Lane targetLane, Road targetRoad) {
		// Connector handoff advances exactly the already-selected route edge. This
		// keeps the post-attach path update deterministic and prevents reroute code
		// from queueing a departure while the vehicle is externally owned.
		if (this.nextRoad_ == null || this.nextRoad_.getID() != targetRoad.getID()) {
			return false;
		}
		if (!targetRoad.beginExternalLaneAdmission(targetLane, this)) {
			return false;
		}
		boolean retainAdmissionReservation = false;
		try {

		// Build every potentially fallible geometry component before detaching the
		// source-road membership. A malformed target must leave the vehicle waiting
		// safely at the source lane end.
		ArrayList<Coordinate> priorCoordMap = new ArrayList<Coordinate>(this.coordMap);
		double priorDistance = this.distance_;
		double priorNextDistance = this.nextDistance_;
		double priorBearing = this.bearing_;
		int priorSegmentIndex = this.currentSegmentIdx_;
		double priorLaneSlope = this.currentLaneSlope_;
		try {
			this.enterNextLane(targetLane);
			this.appendExternalTargetLaneGeometry(targetLane);
		} catch (RuntimeException ex) {
			this.coordMap.clear();
			this.coordMap.addAll(priorCoordMap);
			this.distance_ = priorDistance;
			this.nextDistance_ = priorNextDistance;
			this.bearing_ = priorBearing;
			this.currentSegmentIdx_ = priorSegmentIndex;
			this.currentLaneSlope_ = priorLaneSlope;
			ContextCreator.logger.error("Invalid external road transition geometry for vehicle "
					+ this.getID(), ex);
			return false;
		}

		this.externalTransitionSourceRoad = sourceRoad;
		this.externalTransitionTargetRoad = targetRoad;
		this.externalTransitionTargetLane = targetLane;
		this.externalRoadTransition = true;

		// Publish transition discoverability before removing source-road
		// membership. QUERY_coSimVehicle samples the registry on both sides of its
		// road scan, so the vehicle is always visible during the handoff.
		VehicleContext vehicleContext = ContextCreator.getVehicleContext();
		if (vehicleContext != null) {
			vehicleContext.registerExternalRoadTransition(this);
		}
		retainAdmissionReservation = true;

		double handoffSpeed = this.currentSpeed_;
		if (sourceRoad.getControlType() != Road.COSIM && Double.isFinite(this.tickStartSpeed_)) {
			handoffSpeed = Math.max(handoffSpeed, this.tickStartSpeed_);
		}

		this.removeFromCurrentLane();
		this.removeFromCurrentRoad();
		this.attachToRoad(targetRoad);
		try {
			this.setNextRoad();
		} catch (RuntimeException ex) {
			// Road ownership is already valid and must remain visible to the bridge.
			// A later external route command can repair routing independently.
			ContextCreator.logger.error("External handoff route update failed for vehicle "
					+ this.getID() + " on road " + targetRoad.getID(), ex);
		}

		// distance_ has no lane meaning while on the connector. Keeping it at zero
		// prevents the off-lane macro member from reserving physical lane space.
		this.distance_ = 0.0;
		this.currentSegmentIdx_ = 0;
		this.currentLaneSlope_ = 0.0;
		this.onLane = false;
		this.currentSpeed_ = Math.max(0.0, handoffSpeed);
		this.accRate_ = 0.0;
		this.accDecided_ = false;
		this.hasAccelerationPlan_ = false;
		this.advanceInMacroList();

		return true;
		} finally {
			targetRoad.endExternalLaneAdmission(targetLane, this, retainAdmissionReservation);
		}
	}

	private void appendExternalTargetLaneGeometry(Lane targetLane) {
		double laneLength = targetLane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0) {
			throw new IllegalArgumentException("External transition target lane has invalid length");
		}
		ArrayList<Coordinate> targetCoordinates = targetLane.getCoords();
		if (targetCoordinates == null || targetCoordinates.isEmpty()) {
			throw new IllegalArgumentException("External transition target lane has no geometry");
		}
		this.distance_ += laneLength;
		for (int i = 1; i < targetCoordinates.size(); i++) {
			this.coordMap.add(targetCoordinates.get(i));
		}
		this.currentSegmentIdx_ = 0;
		this.currentLaneSlope_ = targetLane.getSegmentSlope(0);
		this.updateBearingAndNextDistanceToCoordMap(null);
	}

	public boolean isExternalRoadTransition() {
		return this.externalRoadTransition;
	}

	/**
	 * Immutable, point-in-time view of external connector state. Query handlers
	 * must use one instance for an entire response record instead of combining
	 * separately timed flag and target getters.
	 */
	public static final class ExternalRoadTransitionSnapshot {
		private final Vehicle vehicle;
		private final boolean pending;
		private final Road sourceRoad;
		private final Road targetRoad;
		private final Lane targetLane;
		private final ConnectorRoad.ConnectorPath connectorPath;

		private ExternalRoadTransitionSnapshot(Vehicle vehicle, boolean pending,
				Road sourceRoad, Road targetRoad, Lane targetLane,
				ConnectorRoad.ConnectorPath connectorPath) {
			this.vehicle = vehicle;
			this.pending = pending;
			this.sourceRoad = sourceRoad;
			this.targetRoad = targetRoad;
			this.targetLane = targetLane;
			this.connectorPath = connectorPath;
		}

		public Vehicle getVehicle() {
			return this.vehicle;
		}

		public int getVehicleID() {
			return this.vehicle == null ? -1 : this.vehicle.getID();
		}

		public boolean isPending() {
			return this.pending;
		}

		public Road getSourceRoad() {
			return this.sourceRoad;
		}

		public Road getTargetRoad() {
			return this.targetRoad;
		}

		public Lane getTargetLane() {
			return this.targetLane;
		}

		public ConnectorRoad.ConnectorPath getConnectorPath() {
			return this.connectorPath;
		}
	}

	/** Capture all pending-transition fields while holding only this vehicle. */
	public synchronized ExternalRoadTransitionSnapshot getExternalRoadTransitionSnapshot() {
		boolean pending = this.externalRoadTransition;
		return new ExternalRoadTransitionSnapshot(this, pending,
				pending ? this.externalTransitionSourceRoad : null,
				pending ? this.externalTransitionTargetRoad : null,
				pending ? this.externalTransitionTargetLane : null,
				pending ? this.currentConnectorPath : null);
	}

	public String getRoadTransitionState() {
		return this.externalRoadTransition ? "EXTERNAL_CONNECTOR" : "NONE";
	}

	public Road getExternalTransitionSourceRoad() {
		return this.externalRoadTransition ? this.externalTransitionSourceRoad : null;
	}

	public Road getExternalTransitionTargetRoad() {
		return this.externalRoadTransition ? this.externalTransitionTargetRoad : null;
	}

	public Lane getExternalTransitionTargetLane() {
		return this.externalRoadTransition ? this.externalTransitionTargetLane : null;
	}

	public boolean isExternalRoadTransitionTo(Road targetRoad, Lane targetLane) {
		if (!this.externalRoadTransition || targetRoad == null || targetLane == null
				|| this.externalTransitionTargetRoad == null || this.externalTransitionTargetLane == null) {
			return false;
		}
		return this.externalTransitionTargetRoad.getID() == targetRoad.getID()
				&& this.externalTransitionTargetLane.getID() == targetLane.getID();
	}

	/**
	 * Commit a pending external connector when the authoritative pose is inside
	 * the reserved target lane footprint.
	 */
	public synchronized boolean tryCommitExternalRoadTransition() {
		double tolerance = Math.max(COINCIDENT_WAYPOINT_TOLERANCE_METERS,
				Math.max(0.0, GlobalVariables.LANE_WIDTH) * 0.5);
		return this.tryCommitExternalRoadTransition(tolerance);
	}

	public synchronized boolean tryCommitExternalRoadTransition(double maxLateralDistanceMeters) {
		if (!this.externalRoadTransition || !Double.isFinite(maxLateralDistanceMeters)
				|| maxLateralDistanceMeters < 0.0) {
			return false;
		}
		ExternalLaneProjection projection = this.projectExternalPoseToTargetLane();
		if (projection == null || !Double.isFinite(projection.lateralDistanceMeters)
				|| projection.lateralDistanceMeters > maxLateralDistanceMeters
				|| !Double.isFinite(projection.upstreamOvershootMeters)
				|| projection.upstreamOvershootMeters
						> EXTERNAL_LANE_ENTRY_LONGITUDINAL_TOLERANCE_METERS) {
			return false;
		}
		return this.commitExternalRoadTransition(projection.downstreamDistance);
	}

	/** Pure geometry gate used before an all-or-nothing native-control release. */
	public synchronized boolean isExternalRoadTransitionPoseReadyForLaneEntry() {
		return this.isExternalRoadTransitionPoseReadyForLaneEntry(
				this.externalTransitionTargetLane);
	}

	/** Evaluate release readiness against one specific lane on the target road. */
	public synchronized boolean isExternalRoadTransitionPoseReadyForLaneEntry(Lane candidateLane) {
		if (!this.externalRoadTransition || this.externalTransitionTargetRoad == null
				|| candidateLane == null || candidateLane.getRoad() != this.externalTransitionTargetRoad) {
			return false;
		}
		return this.isAuthoritativeExternalPoseReadyForLane(candidateLane);
	}

	/**
	 * Collapse a sparsely observed, already-traversed target road to its endpoint.
	 * This is intentionally narrower than a normal commit: the observed road must
	 * be the directly planned successor, its next lane must be directly connected
	 * from the pending target lane, and the external pose must already be on that
	 * successor lane. Collision and reservation checks remain those of the normal
	 * target-lane endpoint commit.
	 */
	public synchronized boolean tryCommitExternalRoadTransitionAfterSkippedTarget(
			Road observedRoad) {
		if (!this.externalRoadTransition || observedRoad == null
				|| this.externalTransitionTargetRoad == null
				|| this.externalTransitionTargetLane == null || this.nextRoad_ == null
				|| this.nextLane_ == null) {
			return false;
		}
		Road targetRoad = this.externalTransitionTargetRoad;
		Lane targetLane = this.externalTransitionTargetLane;
		Lane observedLane = this.nextLane_;
		if (this.nextRoad_.getID() != observedRoad.getID()
				|| !targetRoad.getDownStreamRoads().contains(observedRoad.getID())
				|| observedLane.getRoad() != observedRoad
				|| !targetLane.getDownStreamLanes().contains(observedLane.getID())
				|| !this.isAuthoritativeExternalPoseReadyForLane(observedLane)) {
			return false;
		}
		return this.commitExternalRoadTransition(0.0);
	}

	private boolean isAuthoritativeExternalPoseReadyForLane(Lane candidateLane) {
		ExternalLaneProjection projection = this.projectExternalPoseToLane(candidateLane);
		double lateralTolerance = Math.max(COINCIDENT_WAYPOINT_TOLERANCE_METERS,
				Math.max(0.0, GlobalVariables.LANE_WIDTH) * 0.5);
		return projection != null && Double.isFinite(projection.lateralDistanceMeters)
				&& projection.lateralDistanceMeters <= lateralTolerance
				&& Double.isFinite(projection.upstreamOvershootMeters)
				&& projection.upstreamOvershootMeters
						<= EXTERNAL_LANE_ENTRY_LONGITUDINAL_TOLERANCE_METERS;
	}

	/**
	 * Attach a pending external connector vehicle at an already validated target
	 * lane distance. The authoritative external pose and bearing are retained;
	 * lane geometry is used only to rebuild linked-list and route state.
	 */
	public synchronized boolean commitExternalRoadTransition(double downstreamDistance) {
		Road targetRoad = this.externalTransitionTargetRoad;
		Lane targetLane = this.externalTransitionTargetLane;
		if (targetRoad == null || targetLane == null
				|| !targetRoad.beginExternalLaneCommit(targetLane, this)) return false;
		try {
			return this.commitExternalRoadTransitionAtDistance(downstreamDistance, true);
		} finally {
			targetRoad.endExternalLaneCommit();
		}
	}

	private boolean commitExternalRoadTransitionAtDistance(double downstreamDistance,
			boolean preserveExternalPose) {
		if (!this.externalRoadTransition || this.externalTransitionTargetRoad == null
				|| this.externalTransitionTargetLane == null || this.road != this.externalTransitionTargetRoad
				|| this.lane != null) {
			return false;
		}
		Road targetRoad = this.externalTransitionTargetRoad;
		Lane targetLane = this.externalTransitionTargetLane;
		if (!targetRoad.hasExternalLaneReservation(targetLane, this)) {
			return false;
		}
		double laneLength = targetLane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0 || !Double.isFinite(downstreamDistance)
				|| downstreamDistance < -COINCIDENT_WAYPOINT_TOLERANCE_METERS
				|| downstreamDistance > laneLength + COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			return false;
		}

		double clampedDistance = Math.max(0.0, Math.min(laneLength, downstreamDistance));
		if (!this.externalTargetLaneIntervalAvailable(targetLane, clampedDistance)) {
			return false;
		}
		Coordinate authoritativePose = preserveExternalPose ? this.getCurrentCoord() : null;
		double authoritativeBearing = this.bearing_;
		this.preserveConnectorReservationOnLaneDetach = true;
		try {
			this.teleportToLane(targetLane, clampedDistance);
		} finally {
			this.preserveConnectorReservationOnLaneDetach = false;
		}
		if (this.lane != targetLane || !this.onLane) {
			return false;
		}

		// CoSim coordinates may include a small lateral displacement from the
		// centerline. Preserve that authoritative pose while retaining the projected
		// lane distance for linked-list ordering and eventual control release.
		if (preserveExternalPose) {
			this.setCurrentCoord(authoritativePose);
			if (Double.isFinite(authoritativeBearing)) {
				this.bearing_ = authoritativeBearing;
			}
		}
		this.advanceInMacroList();
		this.retreatInMacroList();
		this.updateNativeConnectorMembership();
		this.clearExternalRoadTransitionState(true);
		return true;
	}

	/**
	 * Native-control release places the vehicle at the nearest free position no
	 * farther downstream than the projection. Increasing distance moves upstream.
	 * If the lane is full, the pending state and reservation remain intact.
	 */
	public synchronized boolean commitExternalRoadTransitionAtClosestAvailableDistance(
			double preferredDownstreamDistance) {
		return this.commitExternalRoadTransitionAtClosestAvailableDistance(
				this.externalTransitionTargetLane, preferredDownstreamDistance);
	}

	public synchronized boolean commitExternalRoadTransitionAtClosestAvailableDistance(
			Lane releaseLane, double preferredDownstreamDistance) {
		if (!this.externalRoadTransition || this.externalTransitionTargetRoad == null
				|| this.externalTransitionTargetLane == null || releaseLane == null
				|| releaseLane.getRoad() != this.externalTransitionTargetRoad
				|| !this.externalTransitionTargetRoad.isNativeReleaseInProgress()
				|| !Double.isFinite(preferredDownstreamDistance)) {
			return false;
		}
		Road targetRoad = this.externalTransitionTargetRoad;
		Lane originalTargetLane = this.externalTransitionTargetLane;
		boolean alternateLane = releaseLane != originalTargetLane;
		boolean releaseLaneAlreadyReserved =
				targetRoad.hasExternalLaneReservation(releaseLane, this);
		boolean acquiredReleaseReservation = !releaseLaneAlreadyReserved;
		if (acquiredReleaseReservation
				&& !targetRoad.tryReserveExternalLaneForNativeRelease(releaseLane, this)) {
			return false;
		}
		this.externalTransitionTargetLane = releaseLane;

		Lane targetLane = releaseLane;
		double laneLength = targetLane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0) {
			this.externalTransitionTargetLane = originalTargetLane;
			if (acquiredReleaseReservation) {
				targetRoad.releaseExternalLaneReservation(releaseLane, this);
			}
			return false;
		}
		double candidate = Math.max(0.0, Math.min(laneLength, preferredDownstreamDistance));

		while (candidate <= laneLength + COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			double shiftedCandidate = candidate;
			double vehicleEnd = candidate + Math.max(0.0, this.length());
			Vehicle other = targetLane.firstVehicle();
			while (other != null) {
				Vehicle next = other.trailing();
				if (other != this) {
					double otherStart = other.getDistanceToNextJunction();
					double otherEnd = otherStart + Math.max(0.0, other.length());
					boolean overlaps = candidate < otherEnd - COINCIDENT_WAYPOINT_TOLERANCE_METERS
							&& otherStart < vehicleEnd - COINCIDENT_WAYPOINT_TOLERANCE_METERS;
					if (overlaps) {
						shiftedCandidate = Math.max(shiftedCandidate,
								otherEnd + COINCIDENT_WAYPOINT_TOLERANCE_METERS);
					}
				}
				other = next;
			}
			if (shiftedCandidate <= candidate + 1e-9) {
				boolean committed = this.commitExternalRoadTransitionAtDistance(candidate, false);
				if (committed) {
					if (alternateLane) {
						targetRoad.releaseExternalLaneReservation(originalTargetLane, this);
						this.nextLane_ = null;
					}
					return true;
				}
				break;
			}
			candidate = shiftedCandidate;
		}
		this.externalTransitionTargetLane = originalTargetLane;
		if (acquiredReleaseReservation) {
			targetRoad.releaseExternalLaneReservation(releaseLane, this);
		}
		return false;
	}

	private boolean externalTargetLaneIntervalAvailable(Lane targetLane, double downstreamDistance) {
		double vehicleStart = downstreamDistance;
		double vehicleEnd = downstreamDistance + Math.max(0.0, this.length());
		Vehicle other = targetLane.firstVehicle();
		while (other != null) {
			Vehicle next = other.trailing();
			if (other != this) {
				double otherStart = other.getDistanceToNextJunction();
				double otherEnd = otherStart + Math.max(0.0, other.length());
				boolean overlaps = vehicleStart < otherEnd - COINCIDENT_WAYPOINT_TOLERANCE_METERS
						&& otherStart < vehicleEnd - COINCIDENT_WAYPOINT_TOLERANCE_METERS;
				if (overlaps) return false;
			}
			other = next;
		}
		return true;
	}

	/** Called by Road when a COSIM road is projected back to native control. */
	public synchronized boolean completeExternalRoadTransitionAfterProjection() {
		if (!this.externalRoadTransition) return false;
		if (this.road != this.externalTransitionTargetRoad || this.lane == null) return false;
		this.updateNativeConnectorMembership();
		this.clearExternalRoadTransitionState(true);
		return true;
	}

	public synchronized boolean resumeNativeConnectorTraversal() {
		ConnectorRoad connector = this.currentConnector;
		ConnectorRoad.ConnectorPath connectorPath = this.currentConnectorPath;
		Lane targetLane = this.externalTransitionTargetLane;
		if (!this.externalRoadTransition || connector == null || connectorPath == null
				|| this.externalTransitionSourceRoad != connector.getSourceRoad()
				|| this.externalTransitionTargetRoad != connector.getTargetRoad()
				|| this.road != connector.getTargetRoad() || this.lane != null
				|| !this.onRoad || this.onLane || targetLane == null
				|| connectorPath.getTargetLane() != targetLane
				|| !connector.getPaths().contains(connectorPath)
				|| this.currentCoord_ == null) {
			return false;
		}
		ArrayList<Coordinate> connectorSuffix =
				this.nativeConnectorSuffix(connectorPath, targetLane);
		return connectorSuffix != null
				&& this.installNativeConnectorSuffix(targetLane, connectorSuffix);
	}

	private ArrayList<Coordinate> nativeConnectorSuffix(
			ConnectorRoad.ConnectorPath connectorPath, Lane targetLane) {
		List<Coordinate> centerLine = connectorPath.getCenterLine();
		int segment = this.closestConnectorSegment(centerLine, this.currentCoord_);
		ArrayList<Coordinate> result = new ArrayList<Coordinate>();
		if (segment < 0) {
			ContextCreator.logger.warn("PATH_FALLBACK:" + this.ID);
		}
		if (segment >= 0) {
			for (int i = segment + 1; i < centerLine.size(); i++) {
				Coordinate coordinate = centerLine.get(i);
				if (coordinate != null && Double.isFinite(coordinate.x)
						&& Double.isFinite(coordinate.y)) {
					result.add(new Coordinate(coordinate));
				}
			}
		}
		Coordinate laneStart = targetLane.getStartCoord();
		if (laneStart == null || !Double.isFinite(laneStart.x)
				|| !Double.isFinite(laneStart.y)) return null;
		this.finishConnectorSuffixAtLaneStart(result, laneStart);
		return result;
	}

	private int closestConnectorSegment(
			List<Coordinate> centerLine, Coordinate pose) {
		if (centerLine == null || centerLine.size() < 2 || pose == null) return -1;
		int bestSegment = -1;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int i = 0; i < centerLine.size() - 1; i++) {
			Coordinate upstream = centerLine.get(i);
			Coordinate downstream = centerLine.get(i + 1);
			double candidateDistance =
					this.distanceToConnectorSegment(pose, upstream, downstream);
			if (Double.isFinite(candidateDistance)
					&& candidateDistance < bestDistance) {
				bestDistance = candidateDistance;
				bestSegment = i;
			}
		}
		return bestSegment;
	}

	private double distanceToConnectorSegment(
			Coordinate pose, Coordinate upstream, Coordinate downstream) {
		if (upstream == null || downstream == null) return Double.NaN;
		double dx = downstream.x - upstream.x;
		double dy = downstream.y - upstream.y;
		double squaredLength = dx * dx + dy * dy;
		if (!Double.isFinite(squaredLength) || squaredLength <= 0.0) {
			return Double.NaN;
		}
		double fraction = ((pose.x - upstream.x) * dx
				+ (pose.y - upstream.y) * dy) / squaredLength;
		fraction = Math.max(0.0, Math.min(1.0, fraction));
		Coordinate projection = new Coordinate(
				upstream.x + fraction * dx, upstream.y + fraction * dy, pose.z);
		return this.distance(pose, projection);
	}

	private void finishConnectorSuffixAtLaneStart(
			ArrayList<Coordinate> suffix, Coordinate laneStart) {
		if (suffix.isEmpty()) {
			suffix.add(new Coordinate(laneStart));
			return;
		}
		int lastIndex = suffix.size() - 1;
		Coordinate last = suffix.get(lastIndex);
		double endpointError = this.distance(last, laneStart);
		if (Double.isFinite(endpointError)
				&& endpointError <= COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			suffix.set(lastIndex, new Coordinate(laneStart));
		} else {
			suffix.add(new Coordinate(laneStart));
		}
	}

	private boolean installNativeConnectorSuffix(
			Lane targetLane, List<Coordinate> connectorSuffix) {
		double connectorDistance =
				this.remainingDistance(this.currentCoord_, connectorSuffix);
		ConnectorRoad connector = this.currentConnector;
		Lane connectorLane = connector == null || this.currentConnectorPath == null
				? null : connector.getLane(this.currentConnectorPath);
		double laneLength = connectorLane == null ? Double.NaN : connectorLane.getLength();
		if (!Double.isFinite(connectorDistance) || connectorDistance < 0.0
				|| !Double.isFinite(laneLength) || laneLength < 0.0
				|| connectorLane.getCoords() == null
				|| connectorLane.getCoords().size() < 2) {
			return false;
		}
		Coordinate authoritativePose = new Coordinate(this.currentCoord_);
		double authoritativeBearing = this.bearing_;
		this.clearExternalRoadTransitionState(true);
		this.removeFromCurrentRoad();
		this.appendToRoad(connector);
		connector.teleportVehicle(this, connectorLane,
				Math.min(connectorDistance, laneLength));
		this.nextLane_ = targetLane;
		this.setCurrentCoord(authoritativePose);
		this.bearing_ = authoritativeBearing;
		this.syncPreviousEpochCoord();
		this.advanceInMacroList();
		this.retreatInMacroList();
		this.updateNativeConnectorMembership();
		return true;
	}

	private double remainingDistance(
			Coordinate start, List<Coordinate> waypoints) {
		if (start == null || waypoints == null || waypoints.isEmpty()) {
			return Double.NaN;
		}
		double result = 0.0;
		Coordinate previous = start;
		for (Coordinate waypoint : waypoints) {
			if (waypoint == null) return Double.NaN;
			double segmentDistance = this.distance(previous, waypoint);
			if (!Double.isFinite(segmentDistance) || segmentDistance < 0.0) {
				return Double.NaN;
			}
			result += segmentDistance;
			previous = waypoint;
		}
		return result;
	}

	public synchronized void cancelExternalRoadTransition() {
		this.clearExternalRoadTransitionState();
	}

	public synchronized double getExternalTransitionProjectedDistance() {
		if (!this.externalRoadTransition || this.externalTransitionTargetLane == null) {
			return Double.NaN;
		}
		ExternalLaneProjection projection = this.projectExternalPoseToTargetLane();
		if (projection != null && Double.isFinite(projection.downstreamDistance)) {
			return projection.downstreamDistance;
		}
		double laneLength = this.externalTransitionTargetLane.getLength();
		return Double.isFinite(laneLength) ? Math.max(0.0, laneLength) : Double.NaN;
	}

	private synchronized void clearExternalRoadTransitionState() {
		this.clearExternalRoadTransitionState(false);
	}

	private synchronized void clearExternalRoadTransitionState(boolean retainConnectorReservation) {
		if (!this.externalRoadTransition && this.externalTransitionTargetRoad == null
				&& this.externalTransitionTargetLane == null
				&& (retainConnectorReservation || this.currentConnector == null)) {
			return;
		}
		if (!retainConnectorReservation && this.currentConnector != null) {
			this.clearNativeConnectorMembership();
		}
		Road targetRoad = this.externalTransitionTargetRoad;
		Lane targetLane = this.externalTransitionTargetLane;
		if (targetRoad != null && targetLane != null) {
			targetRoad.releaseExternalLaneReservation(targetLane, this);
		}
		VehicleContext vehicleContext = ContextCreator.getVehicleContext();
		if (vehicleContext != null) {
			vehicleContext.unregisterExternalRoadTransition(this);
		}
		this.externalRoadTransition = false;
		this.externalTransitionSourceRoad = null;
		this.externalTransitionTargetRoad = null;
		this.externalTransitionTargetLane = null;
	}

	private ExternalLaneProjection projectExternalPoseToTargetLane() {
		return this.projectExternalPoseToLane(this.externalTransitionTargetLane);
	}

	private ExternalLaneProjection projectExternalPoseToLane(Lane targetLane) {
		return this.projectPoseToLane(this.currentCoord_, targetLane);
	}

	/**
	 * Return why this macro-road member cannot safely be handed from native
	 * stepping to external control, or {@code null} when its representation is
	 * safe. Pending external connectors are already externally owned and are
	 * intentionally accepted without a physical target-lane attachment. Native
	 * connector occupants and vehicles with unfinished route preparation are also
	 * accepted: connector motion and any required lane changing transfer to the
	 * external simulator with the controlled segment.
	 */
	public synchronized String coSimTakeoverBlockReason(Road takeoverRoad) {
		if (takeoverRoad == null) return "Requested takeover road is null";
		if (!this.onRoad) return "Vehicle " + this.ID + " is not marked on-road";
		if (this.road != takeoverRoad) {
			return "Vehicle " + this.ID + " macro membership disagrees with its current road";
		}
		Coordinate pose = this.currentCoord_;
		if (pose == null || !Double.isFinite(pose.x) || !Double.isFinite(pose.y)
				|| !Double.isFinite(pose.z)) {
			return "Vehicle " + this.ID + " has no finite current pose";
		}
		if (this.externalRoadTransition) {
			if (this.externalTransitionSourceRoad == null
					|| this.externalTransitionTargetRoad != takeoverRoad
					|| this.externalTransitionTargetLane == null
					|| this.externalTransitionTargetLane.getRoad() != takeoverRoad
					|| this.lane != null || this.onLane) {
				return "Vehicle " + this.ID + " has malformed pending external-transition state";
			}
			return null;
		}
		if (this.lane == null) {
			return "Vehicle " + this.ID + " has no current lane";
		}
		if (this.lane.getRoad() != takeoverRoad) {
			return "Vehicle " + this.ID + " current lane belongs to a different road";
		}
		if (this.currentConnector == null && this.currentConnectorPath != null) {
			return "Vehicle " + this.ID + " has a connector path without connector membership";
		}
		if (this.currentConnector != null
				&& (this.currentConnector.getTargetRoad() != takeoverRoad
						|| this.currentConnectorPath == null
						|| !this.currentConnector.getPaths().contains(this.currentConnectorPath)
						|| this.currentConnectorPath.getTargetLane() != this.lane)) {
			return "Vehicle " + this.ID + " has malformed native connector membership";
		}
		if (!this.onLane) {
			double endpointError = ContextCreator.getCityContext().getDistance(
					pose, this.lane.getEndCoord());
			boolean recoverableLaneEnd = !this.isDormantOnRoad() && this.nextRoad_ != null
					&& Double.isFinite(this.distance_)
					&& Math.abs(this.distance_) <= COINCIDENT_WAYPOINT_TOLERANCE_METERS
					&& Double.isFinite(endpointError)
					&& endpointError <= EXTERNAL_LANE_ENTRY_LONGITUDINAL_TOLERANCE_METERS;
			if (recoverableLaneEnd) return null;
			return "Vehicle " + this.ID + " has inconsistent off-lane state: distance="
					+ this.distance_ + ", endpointErrorMeters=" + endpointError;
		}
		double laneLength = this.lane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0
				|| !Double.isFinite(this.distance_)
				|| this.distance_ < -COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			return "Vehicle " + this.ID + " has distance " + this.distance_
					+ " outside current lane length " + laneLength
					+ " (unfinished native connector or corrupt lane state)";
		}
		if (this.distance_ > laneLength + COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			return "Vehicle " + this.ID + " has distance " + this.distance_
					+ " outside current lane length " + laneLength
					+ " (corrupt lane state)";
		}
		return null;
	}

	/**
	 * Rebind METS-R's mirrored membership to an externally authoritative COSIM
	 * observation. Previous route and connector state are deliberately not used
	 * as admission criteria. Geometry supplies only the lane/path and linked-list
	 * distance needed by METS-R bookkeeping; the supplied pose, bearing, and speed
	 * are retained exactly.
	 *
	 * @return true when a connector observation also acquired its target-lane
	 *         reservation, or for every physical-road observation
	 */
	public synchronized boolean synchronizeAuthoritativeCoSimObservation(
			Road observedSegment, Lane observedLane,
			ConnectorRoad.ConnectorPath observedConnectorPath,
			double projectedDownstreamDistance, Coordinate authoritativePose,
			double authoritativeBearing, double authoritativeSpeed) {
		if (observedSegment == null || observedSegment.getControlType() != Road.COSIM) {
			throw new IllegalArgumentException(
					"Authoritative observation requires a controlled COSIM segment");
		}
		if (authoritativePose == null || !Double.isFinite(authoritativePose.x)
				|| !Double.isFinite(authoritativePose.y)
				|| !Double.isFinite(authoritativePose.z)
				|| !Double.isFinite(authoritativeBearing)
				|| !Double.isFinite(authoritativeSpeed)
				|| authoritativeSpeed < 0.0) {
			throw new IllegalArgumentException(
					"Authoritative observation requires a finite pose, bearing, and non-negative speed");
		}
		if (!Double.isFinite(projectedDownstreamDistance)) {
			throw new IllegalArgumentException(
					"Authoritative observation has no finite segment projection");
		}

		ConnectorRoad connector = observedSegment instanceof ConnectorRoad
				? (ConnectorRoad) observedSegment : null;
		Lane targetLane;
		Road mirroredRoad;
		if (connector == null) {
			if (observedLane == null || observedLane.getRoad() != observedSegment) {
				throw new IllegalArgumentException(
						"Authoritative physical-road observation requires a lane on that road");
			}
			targetLane = observedLane;
			mirroredRoad = observedSegment;
		} else {
			if (observedConnectorPath == null
					|| !connector.getPaths().contains(observedConnectorPath)
					|| observedConnectorPath.getSourceLane() == null
					|| observedConnectorPath.getTargetLane() == null
					|| observedConnectorPath.getSourceLane().getRoad()
							!= connector.getSourceRoad()
					|| observedConnectorPath.getTargetLane().getRoad()
							!= connector.getTargetRoad()) {
				throw new IllegalArgumentException(
						"Authoritative connector observation requires a valid connector path");
			}
			targetLane = observedConnectorPath.getTargetLane();
			mirroredRoad = connector.getTargetRoad();
		}

		double laneLength = targetLane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0
				|| targetLane.getCoords() == null || targetLane.getCoords().size() < 2) {
			throw new IllegalArgumentException(
					"Authoritative segment has no usable target-lane geometry");
		}
		double mirroredDistance = Math.max(0.0,
				Math.min(laneLength, projectedDownstreamDistance));

		// CARLA owns the route while this API is active. Remove every retained
		// route/connector assertion before rebuilding current membership.
		this.clearShadowImpact();
		if (this.externalRoadTransition) {
			this.clearExternalRoadTransitionState();
		} else if (this.currentConnector != null) {
			this.clearNativeConnectorMembership();
		}
		this.removeFromCurrentLane();
		this.removeFromCurrentRoad();
		ContextCreator.getRoadContext().removeVehicleFromEnteringQueues(this);
		this.roadPath = null;
		this.nextRoad_ = null;
		this.nextLane_ = null;
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
		this.distToTravel_ = 0.0;
		this.distToTravelReferenceDistance_ = 0.0;
		this.atOrigin = false;
		this.isReachDest = false;
		this.resetRoadTraversalPatience();
		this.accRate_ = 0.0;
		this.accDecided_ = false;
		this.hasAccelerationPlan_ = false;
		this.resetLaneChangeRuntimeState();

		if (connector == null) {
			mirroredRoad.teleportVehicle(this, targetLane, mirroredDistance);
			this.setCurrentCoord(new Coordinate(authoritativePose));
			this.setPreviousEpochCoord(authoritativePose);
			this.bearing_ = authoritativeBearing;
			this.currentSpeed_ = authoritativeSpeed;
			return true;
		}

		this.appendToRoadForTeleport(mirroredRoad);
		this.currentConnector = connector;
		this.currentConnectorPath = observedConnectorPath;
		this.connectorFrontCleared = false;
		this.externalRoadTransition = true;
		this.externalTransitionSourceRoad = connector.getSourceRoad();
		this.externalTransitionTargetRoad = connector.getTargetRoad();
		this.externalTransitionTargetLane = targetLane;
		this.onLane = false;
		this.distance_ = 0.0;
		this.nextDistance_ = 0.0;
		this.currentSegmentIdx_ = 0;
		this.currentLaneSlope_ = 0.0;
		this.coordMap.clear();
		this.coordMap.add(new Coordinate(authoritativePose));
		this.setCurrentCoord(new Coordinate(authoritativePose));
		this.setPreviousEpochCoord(authoritativePose);
		this.bearing_ = authoritativeBearing;
		this.currentSpeed_ = authoritativeSpeed;

		boolean targetLaneReserved =
				mirroredRoad.tryReserveExternalLane(targetLane, this);
		ContextCreator.getRoadContext()
				.mirrorAuthoritativeConnectorVehicle(connector, this);
		VehicleContext vehicleContext = ContextCreator.getVehicleContext();
		if (vehicleContext != null) {
			vehicleContext.registerExternalRoadTransition(this);
		}
		this.advanceInMacroList();
		this.retreatInMacroList();
		ContextCreator.getRoadContext()
				.updateConnectorVehicleState(connector, this);
		return targetLaneReserved;
	}

	/**
	 * Synchronize the lane-list representation of a non-pending vehicle on a
	 * COSIM road with an authoritative external pose. Validation is completed
	 * before the vehicle is detached, so a rejected observation leaves position,
	 * lane membership, distance, speed, bearing, and route state unchanged.
	 *
	 * @return projected distance from the observed lane's downstream endpoint
	 * @throws IllegalArgumentException when the observation is inconsistent with
	 *         the current co-simulation state or is too far from the asserted lane
	 */
	public synchronized double synchronizeCoSimLaneObservation(Road observedRoad,
			Lane observedLane, Coordinate authoritativePose, double authoritativeBearing,
			double authoritativeSpeed) {
		if (this.externalRoadTransition) {
			throw new IllegalArgumentException(
					"Cannot synchronize a lane while an external road transition is pending");
		}
		if (this.road == null) {
			throw new IllegalArgumentException("Vehicle has no current road");
		}
		if (this.lane == null) {
			throw new IllegalArgumentException("Vehicle has no retained current lane");
		}
		boolean reattachingFromJunction = !this.onLane;
		if (reattachingFromJunction) {
			if (!this.onRoad || this.isDormantOnRoad() || this.nextRoad_ == null
					|| this.lane.getRoad() != this.road) {
				throw new IllegalArgumentException(
						"Vehicle is not in a recoverable active lane-end handoff state");
			}
			if (this.currentCoord_ == null) {
				throw new IllegalArgumentException(
						"Off-lane vehicle has no retained lane-end pose");
			}
			double laneEndDistance = ContextCreator.getCityContext().getDistance(
					this.currentCoord_, this.lane.getEndCoord());
			if (!Double.isFinite(this.distance_)
					|| Math.abs(this.distance_) > COINCIDENT_WAYPOINT_TOLERANCE_METERS
					|| !Double.isFinite(laneEndDistance)
					|| laneEndDistance > EXTERNAL_LANE_ENTRY_LONGITUDINAL_TOLERANCE_METERS) {
				throw new IllegalArgumentException(
						"Off-lane vehicle is not at its retained lane endpoint: distance="
								+ this.distance_ + ", endpointErrorMeters=" + laneEndDistance);
			}
		}
		if (observedRoad == null || observedRoad.getID() != this.road.getID()) {
			throw new IllegalArgumentException("Observed road does not match the vehicle's current road");
		}
		if (this.road.getControlType() != Road.COSIM) {
			throw new IllegalArgumentException("Observed lane synchronization requires a COSIM road");
		}
		if (observedLane == null || observedLane.getRoad() != observedRoad) {
			throw new IllegalArgumentException("Observed lane does not belong to the current road");
		}
		if (authoritativePose == null || !Double.isFinite(authoritativePose.x)
				|| !Double.isFinite(authoritativePose.y)
				|| !Double.isFinite(authoritativePose.z)) {
			throw new IllegalArgumentException("Observed lane synchronization requires a finite pose");
		}
		if (!Double.isFinite(authoritativeBearing) || !Double.isFinite(authoritativeSpeed)) {
			throw new IllegalArgumentException(
					"Observed lane synchronization requires finite bearing and speed");
		}

		Lane previousLane = this.lane;
		boolean laneChanged = observedLane != previousLane;
		if (reattachingFromJunction && laneChanged) {
			throw new IllegalArgumentException(
					"A lane-end handoff may only reattach to its retained source lane");
		}
		int previousLaneIndex = observedRoad.getLaneIndex(previousLane);
		int observedLaneIndex = observedRoad.getLaneIndex(observedLane);
		if (previousLaneIndex < 0 || observedLaneIndex < 0) {
			throw new IllegalArgumentException(
					"Current or observed lane is missing from the asserted road");
		}
		if (laneChanged && Math.abs(observedLaneIndex - previousLaneIndex) != 1) {
			throw new IllegalArgumentException(
					"Observed lane rebind must be adjacent on the same road: currentLaneIndex="
							+ previousLaneIndex + ", observedLaneIndex=" + observedLaneIndex);
		}

		ExternalLaneProjection projection = this.projectPoseToLane(authoritativePose, observedLane);
		double configuredLaneWidth = Math.max(0.0, GlobalVariables.LANE_WIDTH);
		// Near trimmed road ends Town06's topologically matching OpenDRIVE and
		// SUMO centerlines diverge by nearly one lane width. The external adapter
		// has already asserted the exact current mapped lane; retain the strict
		// same-road/adjacent-lane topology checks above and bound only geometry here.
		double lateralTolerance = Math.max(COINCIDENT_WAYPOINT_TOLERANCE_METERS,
				configuredLaneWidth + COSIM_LANE_OBSERVATION_MAP_SLACK_METERS);
		double lateralDistance = projection == null
				? Double.NaN : projection.lateralDistanceMeters;
		double upstreamOvershoot = projection == null
				? Double.NaN : projection.upstreamOvershootMeters;
		double downstreamOvershoot = projection == null
				? Double.NaN : projection.downstreamOvershootMeters;
		double downstreamDistance = projection == null
				? Double.NaN : projection.downstreamDistance;
		if (projection == null || !Double.isFinite(projection.lateralDistanceMeters)
				|| projection.lateralDistanceMeters > lateralTolerance
				|| !Double.isFinite(projection.upstreamOvershootMeters)
				|| projection.upstreamOvershootMeters
						> EXTERNAL_LANE_ENTRY_LONGITUDINAL_TOLERANCE_METERS
				|| !Double.isFinite(projection.downstreamOvershootMeters)
				|| projection.downstreamOvershootMeters
						> COSIM_LANE_OBSERVATION_DOWNSTREAM_TOLERANCE_METERS
				|| !Double.isFinite(projection.downstreamDistance)) {
			throw new IllegalArgumentException(
					"Authoritative pose is not within the asserted current lane: road="
							+ observedRoad.getOrigID() + ", currentLaneIndex=" + previousLaneIndex
							+ ", observedLaneIndex=" + observedLaneIndex + ", laneChanged="
							+ laneChanged + ", pose=(" + authoritativePose.x + ","
							+ authoritativePose.y + "," + authoritativePose.z + ")"
							+ ", lateralDistanceMeters=" + lateralDistance
							+ ", lateralToleranceMeters=" + lateralTolerance
							+ ", configuredLaneWidthMeters=" + configuredLaneWidth
							+ ", mapSlackMeters=" + COSIM_LANE_OBSERVATION_MAP_SLACK_METERS
							+ ", upstreamOvershootMeters=" + upstreamOvershoot
							+ ", upstreamToleranceMeters="
							+ EXTERNAL_LANE_ENTRY_LONGITUDINAL_TOLERANCE_METERS
							+ ", downstreamOvershootMeters=" + downstreamOvershoot
							+ ", downstreamToleranceMeters="
							+ COSIM_LANE_OBSERVATION_DOWNSTREAM_TOLERANCE_METERS
							+ ", downstreamDistanceMeters=" + downstreamDistance);
		}

		// Road.teleportVehicle rebuilds both lane-level and road-level ordering.
		// The road never changes here, so road counts and the route are preserved.
		double previousDistance = this.distance_;
		boolean previousOnLane = this.onLane;
		Coordinate previousPose = new Coordinate(this.currentCoord_);
		Coordinate previousEpochPose = new Coordinate(this.previousEpochCoord);
		double previousBearing = this.bearing_;
		double previousSpeed = this.currentSpeed_;
		Lane previousNextLane = this.nextLane_;
		double previousNextDistance = this.nextDistance_;
		int previousSegmentIndex = this.currentSegmentIdx_;
		double previousLaneSlope = this.currentLaneSlope_;
		ArrayList<Coordinate> previousCoordMap = new ArrayList<Coordinate>();
		for (Coordinate coordinate : this.coordMap) {
			previousCoordMap.add(new Coordinate(coordinate));
		}
		this.preserveConnectorReservationOnLaneDetach = true;
		try {
			this.removeFromCurrentLane();
			observedRoad.teleportVehicle(this, observedLane, projection.downstreamDistance);
			this.setCurrentCoord(new Coordinate(authoritativePose));
			this.bearing_ = authoritativeBearing;
			this.currentSpeed_ = authoritativeSpeed;
			// Road.teleportVehicle snapshots the projected centerline pose. Replace
			// that snapshot only after restoring the externally authoritative pose.
			this.syncPreviousEpochCoord();
			// Only a real lane rebind can change the legal route-prepared target.
			// Same-lane pose updates must not mutate route state.
			if (laneChanged) this.assignNextLane();
			this.updateNativeConnectorMembership();
			return projection.downstreamDistance;
		} catch (RuntimeException ex) {
			// Every expected rejection occurs above. Still make an unexpected
			// insertion failure best-effort atomic before propagating it.
			try {
				if (this.lane != null) this.removeFromCurrentLane();
				double rollbackDistance = Math.max(0.0,
						Math.min(previousLane.getLength(), previousDistance));
				observedRoad.teleportVehicle(this, previousLane, rollbackDistance);
				this.setCurrentCoord(previousPose);
				this.previousEpochCoord = previousEpochPose;
				this.bearing_ = previousBearing;
				this.currentSpeed_ = previousSpeed;
				this.nextLane_ = previousNextLane;
				this.nextDistance_ = previousNextDistance;
				this.currentSegmentIdx_ = previousSegmentIndex;
				this.currentLaneSlope_ = previousLaneSlope;
				this.coordMap.clear();
				for (Coordinate coordinate : previousCoordMap) {
					this.coordMap.add(new Coordinate(coordinate));
				}
				this.onLane = previousOnLane;
			} catch (RuntimeException rollbackFailure) {
				ex.addSuppressed(rollbackFailure);
			}
			throw ex;
		} finally {
			this.preserveConnectorReservationOnLaneDetach = false;
		}
	}

	private ExternalLaneProjection projectPoseToLane(Coordinate pose, Lane targetLane) {
		if (targetLane == null || pose == null) return null;
		ArrayList<Coordinate> coordinates = targetLane.getCoords();
		if (coordinates == null || coordinates.size() < 2) return null;

		// Select against the finite polyline first. Testing only perpendicular
		// projections leaves gaps around interior vertices: the nearest point can
		// be a shared endpoint while both adjacent raw parameters are out of range.
		double bestClampedDistanceMeters = Double.POSITIVE_INFINITY;
		double bestRawParam = Double.NaN;
		double bestClampedParam = Double.NaN;
		double bestSegmentDistance = Double.NaN;
		double bestAccumulatedDownstreamDistance = Double.NaN;
		double bestDx = Double.NaN;
		double bestDy = Double.NaN;
		Coordinate bestSegmentUpstream = null;
		int bestCoordinateIndex = -1;
		int firstUsableCoordinateIndex = -1;
		int lastUsableCoordinateIndex = -1;
		double accumulatedDownstreamDistance = 0.0;
		for (int i = coordinates.size() - 1; i > 0; i--) {
			Coordinate upstream = coordinates.get(i - 1);
			Coordinate downstream = coordinates.get(i);
			double dx = downstream.x - upstream.x;
			double dy = downstream.y - upstream.y;
			double squaredLength = dx * dx + dy * dy;
			double segmentDistance = ContextCreator.getCityContext().getDistance(upstream, downstream);
			if (!Double.isFinite(squaredLength) || squaredLength <= 0.0
					|| !Double.isFinite(segmentDistance) || segmentDistance <= 0.0) {
				continue;
			}

			if (lastUsableCoordinateIndex < 0) lastUsableCoordinateIndex = i;
			firstUsableCoordinateIndex = i;
			double rawParam = ((pose.x - upstream.x) * dx
					+ (pose.y - upstream.y) * dy) / squaredLength;
			if (!Double.isFinite(rawParam)) {
				accumulatedDownstreamDistance += segmentDistance;
				continue;
			}
			double clampedParam = Math.max(0.0, Math.min(1.0, rawParam));
			double nearestX = upstream.x + clampedParam * dx;
			double nearestY = upstream.y + clampedParam * dy;
			// Vehicle coordinates are stored in the lane geography CRS, which can
			// be geographic (degrees). Convert every candidate projection to meters
			// before ranking it or comparing it with meter-valued lane tolerances.
			// Use the authoritative pose elevation so this remains a horizontal
			// lateral distance, matching the terminal-extension calculation below.
			Coordinate clampedProjection = new Coordinate(nearestX, nearestY, pose.z);
			double clampedDistanceMeters = this.distance(pose, clampedProjection);
			if (Double.isFinite(clampedDistanceMeters)
					&& clampedDistanceMeters < bestClampedDistanceMeters) {
				bestClampedDistanceMeters = clampedDistanceMeters;
				bestRawParam = rawParam;
				bestClampedParam = clampedParam;
				bestSegmentDistance = segmentDistance;
				bestAccumulatedDownstreamDistance = accumulatedDownstreamDistance;
				bestDx = dx;
				bestDy = dy;
				bestSegmentUpstream = upstream;
				bestCoordinateIndex = i;
			}
			accumulatedDownstreamDistance += segmentDistance;
		}

		if (bestCoordinateIndex < 0 || bestSegmentUpstream == null
				|| firstUsableCoordinateIndex < 0 || lastUsableCoordinateIndex < 0) return null;

		double bestLateralDistance = bestClampedDistanceMeters;
		double bestUpstreamOvershoot = 0.0;
		double bestDownstreamOvershoot = 0.0;
		boolean beyondUpstream = bestCoordinateIndex == firstUsableCoordinateIndex
				&& bestRawParam < 0.0;
		boolean beyondDownstream = bestCoordinateIndex == lastUsableCoordinateIndex
				&& bestRawParam > 1.0;
		if (beyondUpstream || beyondDownstream) {
			// For true terminal extensions, keep lateral and longitudinal error
			// independent. Pending connector callers rely on this decomposition.
			Coordinate lineProjection = new Coordinate(
					bestSegmentUpstream.x + bestRawParam * bestDx,
					bestSegmentUpstream.y + bestRawParam * bestDy, pose.z);
			bestLateralDistance = this.distance(pose, lineProjection);
			if (beyondUpstream) {
				bestUpstreamOvershoot = -bestRawParam * bestSegmentDistance;
			} else {
				bestDownstreamOvershoot = (bestRawParam - 1.0) * bestSegmentDistance;
			}
		}
		double bestDownstreamDistance = bestAccumulatedDownstreamDistance
				+ (1.0 - bestClampedParam) * bestSegmentDistance;
		if (!Double.isFinite(bestLateralDistance)
				|| !Double.isFinite(bestDownstreamDistance)) return null;

		double laneLength = Math.max(0.0, targetLane.getLength());
		bestDownstreamDistance = targetLane.toLogicalDistance(bestDownstreamDistance);
		bestDownstreamDistance = Math.max(0.0, Math.min(laneLength, bestDownstreamDistance));
		return new ExternalLaneProjection(bestDownstreamDistance, bestLateralDistance,
				bestUpstreamOvershoot, bestDownstreamOvershoot);
	}

	private static final class ExternalLaneProjection {
		final double downstreamDistance;
		final double lateralDistanceMeters;
		final double upstreamOvershootMeters;
		final double downstreamOvershootMeters;

		ExternalLaneProjection(double downstreamDistance, double lateralDistanceMeters,
				double upstreamOvershootMeters, double downstreamOvershootMeters) {
			this.downstreamDistance = downstreamDistance;
			this.lateralDistanceMeters = lateralDistanceMeters;
			this.upstreamOvershootMeters = upstreamOvershootMeters;
			this.downstreamOvershootMeters = downstreamOvershootMeters;
		}
	}
	/**
	 * Check if the vehicle is close to a road, used when the vehicle attempts to depart from its closest road
	 * 
	 * @param road The closest road to the vehicle
	 * @return 0-not close enough to the road , 1-close enough to the road
	 */
	public boolean closeToRoad(Road road) {
		Coordinate currentCoord = this.getCurrentCoord();
		Coordinate nextCoord = road.getStartCoord();
		if (distance(currentCoord, nextCoord) < GlobalVariables.TRAVEL_PER_TURN) {
			return true;
		} else
			return false;
	}
	
	/**
	 * Max acceleration based on IDM model, adjusted for road grade.
	 * Uphill (positive slope) reduces available acceleration; downhill increases it.
	 * @return effective maximum acceleration in m/sÂ²
	 */
	public double maxAcceleration() {
		double gradeComponent = GRAVITY * currentLaneSlope_;
		double speedRatio = this.currentSpeed_ / this.desiredSpeed_;
		double speedRatioSquared = speedRatio * speedRatio;
		return maxAcceleration_ * (1 - speedRatioSquared * speedRatioSquared) - gradeComponent;
	}
	
	/**
	 * Effective normal (comfortable) deceleration adjusted for road grade.
	 * More negative uphill (gravity assists braking); less negative downhill (gravity opposes braking).
	 * @return effective normal deceleration in m/sÂ² (negative value)
	 */
	private double effectiveNormalDeceleration() {
		return normalDeceleration_ - GRAVITY * currentLaneSlope_;
	}
	
	/**
	 * Effective maximum (emergency) deceleration adjusted for road grade.
	 * More negative uphill; less negative downhill.
	 * @return effective maximum deceleration in m/sÂ² (negative value)
	 */
	private double effectiveMaxDeceleration() {
		return maxDeceleration_ - GRAVITY * currentLaneSlope_;
	}
	
	/**
	 * Register the vehicle to the target road
	 * @param road Target road
	 */
	public void appendToRoad(Road road) {
		attachToRoad(road);
		if (road instanceof ConnectorRoad) {
			// A normal physical-road crossing already carries nextRoad_ into the
			// connector. An explicitly queued connector departure has no route yet,
			// so initialize its connector-prefixed route after admission.
			if (this.nextRoad_ == null && this.destRoad_ != null) {
				this.rerouteAndSetNextRoad();
			}
			return;
		}
		
		// Set next road
		if ((this.nextRoad_!=null) && (this.nextRoad_.getID() == road.getID())) // Veh enter the next road in its planned route
		{
			this.setNextRoad();
		}
		else { // Veh enter the road not in its planned route
			if(this.destRoad_ != null)
				this.rerouteAndSetNextRoad();
		}
	}

	/**
	 * Register this vehicle on a road for trace replay or snapshot restoration
	 * without changing its route. A normal {@link #appendToRoad(Road)} may reroute
	 * or queue the vehicle before the replay position has been applied.
	 */
	public void appendToRoadForTeleport(Road road) {
		if (this.road != null) {
			throw new IllegalStateException("Vehicle must be detached before teleport road attachment");
		}
		attachToRoad(road);
	}

	private void attachToRoad(Road road) {
		if (road == null) {
			throw new IllegalArgumentException("Target road must not be null");
		}
		this.resetMissedLaneRecoveryEpisode();
		this.missedLaneRecoveryQuarantined = false;
		this.resetRoadTraversalPatience();
		this.road = road;
		this.roadTraversalEpoch++;
		this.linkTravelTime = 0.0;
		updateLastDeparturableRoad(road);

		// Append first, then let advance/retreat place the vehicle by distance.
		Vehicle oldLast = road.lastVehicle();
		this.macroLeading_ = oldLast;
		this.macroTrailing_ = null;
		if (oldLast != null) {
			oldLast.macroTrailing_ = this;
		} else {
			road.firstVehicle(this);
		}
		road.lastVehicle(this);

		road.changeNumberOfVehicles(1);
		ContextCreator.getRoadContext().markRoadActive(road);
		this.onRoad = true;
	}
	
	/**
	 * Get front vehicle in the same road
	 */
	public Vehicle macroLeading() {
		return macroLeading_;
	}
	
	/**
	 * Set front vehicle in the same road
	 * @param v New front vehicle
	 */
	public void macroLeading(Vehicle v) {
		if(v == null) this.macroLeading_ = null;
		else this.macroLeading_ = v;
	}
	
	/**
	 * Get behind vehicle in the same road
	 */
	public Vehicle macroTrailing() {
		return macroTrailing_;
	}
	
	/**
	 * Set behind vehicle in the same road
	 * @param v New behind vehicle
	 */
	public void macroTrailing(Vehicle v) {
		if(v == null) this.macroTrailing_ = null;
		else this.macroTrailing_ = v;
	}
	
	/**
	 * Get front vehicle in the same lane
	 */
	public Vehicle leading() {
		return leading_;
	}
	
	/**
	 * Set front vehicle in the same lane
	 * @param v New front vehicle
	 */
	public void leading(Vehicle v) {
		if(v == null) this.leading_ = null;
		else if(v == this) {
			ContextCreator.logger.warn("Attempt to insert a vehicle itself as the leading with distance " + this.distance_);
			this.leading_ = null;
		}
		else if(v.getDistanceToNextJunction() > this.distance_) {
			ContextCreator.logger.warn("Attempt to insert a behind vehicle (id=" + v.getID() + " dist=" + v.getDistanceToNextJunction()
				+ " lane=" + (v.getLane() != null ? v.getLane().getID() : "null") + ") to the leading of vehicle (id=" + this.getID()
				+ " dist=" + this.distance_ + " lane=" + (this.lane != null ? this.lane.getID() : "null") + ")",
				new Throwable("stack trace"));
			this.leading_ = v;
		}
		else this.leading_ = v;
	}
	
	/**
	 * Get behind vehicle in the same lane
	 */
	public Vehicle trailing() {
		return trailing_;
	}
	
	/**
	 * Set behind vehicle in the same lane
	 * @param v New behind vehicle
	 */
	public void trailing(Vehicle v) {
		if(v == null) this.trailing_ = null;
		else if(v == this) {
			ContextCreator.logger.warn("Attempt to insert a vehicle itself as the trailing with distance " + this.distance_);
			this.trailing_ = null;
		}
		else if(v.getDistanceToNextJunction() < this.distance_) {
			ContextCreator.logger.warn("Attempt to insert a front vehicle (id=" + v.getID() + " dist=" + v.getDistanceToNextJunction()
				+ " lane=" + (v.getLane() != null ? v.getLane().getID() : "null") + ") to the trailing of vehicle (id=" + this.getID()
				+ " dist=" + this.distance_ + " lane=" + (this.lane != null ? this.lane.getID() : "null") + ")",
				new Throwable("stack trace"));
			this.trailing_ = v;
		}
		else this.trailing_ = v;
	}
	
	/**
	 * Get the departure time of the current trip (or the last trip when parking)
	 */
	public int getDepTime() {
		return this.deptime;
	}
	
	/**
	 * Get the finished time of the last trip
	 */
	public int getEndTime() {
		return this.endTime;
	}
	
	public Road getRoad() {
		return road;
	}
	
	/**
	 * Get distance to the next intersection
	 */
	public double getDistanceToNextJunction() {
		return distance_;
	}
	
	/**
	 * Get distance fraction to go in the current link
	 */
	public double distFraction() {
		if (distance_ > 0 && this.lane != null)
			return distance_ /  this.lane.getLength();
		else
			return 0;
	}
	
	/**
	 * Get the length of the vehicle
	 */
	public double length() {
		return length;
	}

	public Lane getLane() {
		return lane;
	}

	public int getID() {
		return this.ID;
	}
	
	/**
	 * Set vehicle ID
	 * @param id
	 */
	public void setID(int id) {
		this.ID = id;
	}
	
	/**
	 * Get origin zone ID
	 */
	public int getOriginID() {
		return this.originID;
	}
	
	/**
	 * Get destination zone ID
	 */
	public int getDestID() {
		return this.destinationID;
	}
	
	/**
	 * Get the current trip plans of the vehicle
	 */
	public ArrayList<Plan> getPlan() {
		return this.activityPlan;
	}
	
	/**
	 * Remove a specific plan in the current trip plans
	 */
	public void removePlan(Plan p) {
		this.activityPlan.remove(p);
	}
	
	/**
	 * Add a new plan to the end of the plan list
	 * @param dest_id Destination zone ID
	 * @param  road_id Destination road ID
	 * @param d Departure time
	 */
	public void addPlan(int dest_id, int road_id, double d) {
		Plan p = new Plan(dest_id, road_id, d);
		this.activityPlan.add(p);
	}
	
	
	/**
	 * Add multiple plans to the end of the plan list
	 * @param activityPlan to-add plans
	 */
	public void addPlan(List<Plan> activityPlan) {
		this.activityPlan.addAll(activityPlan);
	}
	
	/**
	 * Get origin location
	 */
	public Coordinate getOriginCoord() {
		Coordinate coord = new Coordinate();
		coord.x = this.originCoord_.x;
		coord.y = this.originCoord_.y;
		coord.z = this.originCoord_.z;
		return coord;
	}
	
	/**
	 * Get destination location
	 */
	public Coordinate getDestCoord() {
		if(destRoad_ != null)
			return this.destRoad_.getEndCoord();
		return this.getCurrentCoord();
	}
	
	/**
	 * Get origin road
	 */
	public int getOriginRoad() {
		if (this.originRoad_ == null) return -1;
		return this.originRoad_.getID();
	}

	/**
	 * Get the last road visited by this vehicle that can be used as a route origin.
	 */
	public int getLastDeparturableRoad() {
		if (this.lastDeparturableRoad_ == null) return -1;
		return this.lastDeparturableRoad_.getID();
	}
	
	/**
	 * Get destination road
	 */
	public int getDestRoad() {
		if (this.destRoad_ == null) return -1;
		return this.destRoad_.getID();
	}
	
	/**
	 * Get (a copy of) of the vehicle location
	 */
	public synchronized Coordinate getCurrentCoord() {
		Coordinate coord = new Coordinate();
		coord.x = this.currentCoord_.x;
		coord.y = this.currentCoord_.y;
		coord.z = this.currentCoord_.z;
		return coord;
	}
	
	/**
	 * Get (a copy of) of the vehicle location in the original coordinate system
	 */
	public synchronized Coordinate getCurrentCoord(MathTransform transform) {
		Coordinate coord = new Coordinate();
		coord.x = this.currentCoord_.x;
		coord.y = this.currentCoord_.y;
		coord.z = this.currentCoord_.z;
		try {
			JTS.transform(coord, coord, transform.inverse());
		} catch (TransformException e) {
			e.printStackTrace();
		}
		return coord;
	}
	
	/**
	 * Set the vehicle location
	 * @param coord New location
	 */
	public synchronized void setCurrentCoord(Coordinate coord) {
		if (coord == null) {
			ContextCreator.logger.error("New coord is null!");
		} else {
			this.setCurrentCoordInternal(coord);
		}
	}
	
	private void setCurrentCoordInternal(Coordinate coord) {
		this.setCurrentCoordInternal(coord.x, coord.y, coord.z);
	}

	private void setCurrentCoordInternal(double x, double y, double z) {
		this.currentCoord_.x = x;
		this.currentCoord_.y = y;
		this.currentCoord_.z = z;
		this.refreshConnectorPoseState();
	}

	/**
	 * Set the vehicle location using coordinates from the original coordinate system
	 * @param coord New location
	 */
	public synchronized void setCurrentCoord(Coordinate coord, MathTransform transform) {
		try {
			JTS.transform(coord, coord, transform);
		} catch (TransformException e) {
			e.printStackTrace();
		}
		if (coord == null) {
			ContextCreator.logger.error("New coord is null!");
		} else {
			this.setCurrentCoordInternal(coord);
		}
	}

	private void refreshConnectorPoseState() {
		ConnectorRoad connector = this.currentConnector;
		if (connector != null && ContextCreator.getRoadContext() != null) {
			ContextCreator.getRoadContext().updateConnectorVehicleState(connector, this);
		}
	}
	
	/**
	 * Check whether the vehicle is almost arrived 
	 */
	public int nearlyArrived() { // If nearly arrived then return 1 else 0
		if (this.nextRoad_ == null && (this.destRoad_ == null || this.road == null
				|| this.road.getID() == this.destRoad_.getID())) {
			return 1;
		} else {
			return 0;
		}
	}

	/**
	 * Vehicles controlled by external APIs may intentionally stay visible on the
	 * last road after completing a dispatch or repositioning leg. They should not
	 * keep cycling through the intersection transfer queue or consuming traction
	 * energy until a new command assigns an active trip.
	 */
	public boolean isDormantOnRoad() {
		if (!this.onRoad) return false;
		if (this.missedLaneRecoveryQuarantined) return true;
		if (this.vehicleState != Vehicle.NONE_OF_THE_ABOVE && this.vehicleState != Vehicle.PARKING) return false;
		if (this.nextRoad_ != null) return false;
		return true;
	}

	protected void pauseOnRoadWithoutMovement() {
		this.cancelExternalRoadTransition();
		this.clearShadowImpact();
		this.currentSpeed_ = 0.0;
		this.accRate_ = 0.0;
		this.accDecided_ = false;
		this.hasAccelerationPlan_ = false;
		this.nextRoad_ = null;
		this.nextLane_ = null;
		this.roadPath = null;
		this.movingFlag = false;
		this.resetRoadTraversalPatience();
		this.resetLaneChangeRuntimeState();
	}
	
	/**
	 *  Call when arriving the destination
	 */
	public void reachDest() {
		this.reachDestButNotLeave();
		if(this.getState() == Vehicle.PRIVATE_TRIP) {
			ContextCreator.getZoneContext().get(this.getDestID()).arrivedPrivateGVTrip += 1;
		}
		if(this.activityPlan.size() >= 2) {
	    	this.vehicleState = Vehicle.PRIVATE_TRIP;
	    	this.setNextPlan();
	    	this.departure();
	    }
		else {
			this.vehicleState = Vehicle.NONE_OF_THE_ABOVE;
			this.leaveNetwork();
		}
	}
	
	/**
	 *  Call when arriving the destination but not leave the network
	 */
	public void reachDestButNotLeave() {
		this.cancelExternalRoadTransition();
		this.attackVehicle = false;
		this.onLane = false; // Trigger change road if next trip is scheduled
		// Reach destination
		this.isReachDest = true;
		this.accummulatedDistance_ = 0;
		// Vehicle arrive
		this.endTime = ContextCreator.getCurrentTick();
		this.originCoord_ = this.getCurrentCoord();
	}
	
	/**
	 *  Call when leave the network (entered to parking space)
	 */
	public void leaveNetwork() {
		this.clearShadowImpact();
		this.removeFromCurrentLane();
		this.removeFromCurrentRoad();
		this.resetRoadTraversalPatience();
		this.onLane = false;
		this.onRoad = false;
		this.isReachDest = false; // Reset so a recycled vehicle enters roads normally
		this.endTime = 0;
		this.atOrigin = true;
		this.accRate_ = 0;
		this.nextLane_ = null;
		this.nosingFlag = false;
		this.yieldingFlag = false;
		this.clearLaneChangeManeuverFields();
		this.resetLaneChangeRuntimeState();
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		this.nextRoad_ = null;
		this.originCoord_ = null;
		this.originRoad_ = null;
		this.destRoad_ = null;
		// Update the vehicle into the queue of the corresponding link
		this.accummulatedDistance_ = 0;
		this.roadPath = null;
		// For adaptive network partitioning
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
	}

	public boolean getParked(Road road) {
		if (road == null || !road.tryAddParkedVehicle()) {
			return false;
		}
		int zoneID = road.getNeighboringZone(true);
		if (ContextCreator.getZoneContext().get(zoneID) == null) {
			zoneID = road.getNeighboringZone(false);
		}
		updateLastDeparturableRoad(road);
		this.onParkedOnRoad(road, zoneID);
		this.currentParkingRoad = road.getID();
		this.leaveNetwork();
		this.setState(Vehicle.PARKING);
		return true;
	}

	protected void onParkedOnRoad(Road road, int zoneID) {
	}

	public boolean releaseRoadParkingSpot() {
		if (this.currentParkingRoad < 0) {
			return false;
		}
		Road parkedRoad = ContextCreator.getRoadContext().get(this.currentParkingRoad);
		if (parkedRoad != null) {
			parkedRoad.removeOneParkedVehicle();
		}
		this.currentParkingRoad = -1;
		return true;
	}

	public int getCurrentParkingRoad() {
		return this.currentParkingRoad;
	}

	public void setCurrentParkingRoad(int currentParkingRoad) {
		this.currentParkingRoad = currentParkingRoad;
	}

	public double currentSpeed() {
		return currentSpeed_;
	}

	public double getDesiredSpeed() {
		return this.desiredSpeed_;
	}

	public void setDesiredSpeed(double desiredSpeed) {
		if (!Double.isFinite(desiredSpeed) || desiredSpeed < 0.0) {
			throw new IllegalArgumentException("Desired speed must be finite and non-negative");
		}
		this.desiredSpeed_ = desiredSpeed;
	}

	/**
	 * Initialize desired speed when loading a legacy snapshot that predates the
	 * persisted field. This does not consume the car-following random stream.
	 */
	public void initializeDesiredSpeedAfterLegacyRestore() {
		double fallback = Math.max(0.0, this.currentSpeed_);
		Road speedRoad = this.lane != null ? this.lane.getRoad() : this.road;
		if (speedRoad != null && Double.isFinite(speedRoad.getSpeedLimit())
				&& speedRoad.getSpeedLimit() > 0.0) {
			fallback = Math.max(fallback, speedRoad.getSpeedLimit());
		}
		this.desiredSpeed_ = fallback;
	}
	
	/**
	 * Remove vehicle from a lane
	 */
	public void removeFromCurrentLane() {
		if (this.isLaneChanging()) this.cancelLaneChangeManeuver();
		if (this.currentConnector != null
				&& !this.preserveConnectorReservationOnLaneDetach) {
			this.clearNativeConnectorMembership();
		}
		if (this.lane != null) {
			Vehicle curLeading = this.leading();
			Vehicle curTrailing = this.trailing();
			if (curTrailing != null) {
				if (curLeading != null) {
					curLeading.trailing(curTrailing);
					curTrailing.leading(curLeading);
				} else {
					curTrailing.leading(null);
					this.lane.firstVehicle(curTrailing);
				}
			} else if (curLeading != null) {
				curLeading.trailing(null);
				this.lane.lastVehicle(curLeading);
			} else {
				this.lane.firstVehicle(null);
				this.lane.lastVehicle(null);
			}
			this.leading(null);
			this.trailing(null);
			this.lane.removeOneVehicle();
			this.lane = null;
		}
	}
	
	/**
	 *  Remove a vehicle from the macro vehicle list in the current road segment.
	 */
	public void removeFromCurrentRoad() {
		if (this.road != null) {
			// Current road of this vehicle
			Road pr = this.getRoad();
			boolean removePendingExternalTransition = this.externalRoadTransition
					&& pr == this.externalTransitionTargetRoad;
			pr.changeNumberOfVehicles(-1);
			// If this is not the first vehicle on the road
			if (this.macroLeading_ != null) {
				this.macroLeading_.macroTrailing_ = this.macroTrailing_;
			} else { // this is the first vehicle on the road
				pr.firstVehicle(this.macroTrailing_);
			}
			if (macroTrailing_ != null) {
				macroTrailing_.macroLeading_ = macroLeading_;
			} else {
				pr.lastVehicle(macroLeading_);
			}
			this.road = null;
			ContextCreator.getRoadContext().markRoadActive(pr);
			if (removePendingExternalTransition) {
				this.clearExternalRoadTransitionState();
			}
		}
	}

	/**
	 * Reorder this vehicle forward in the lane-level linked list when its
	 * distance has become smaller than its leading vehicle's distance.
	 * Mirrors advanceInMacroList but operates on leading_/trailing_ pointers.
	 */
	public void advanceInLaneList() {
		if (leading_ == null || this.distance_ >= leading_.distance_) {
			return;
		}
		Vehicle front = leading_;
		while (front != null && this.distance_ < front.distance_) {
			front = front.leading_;
		}
		Lane pl = this.lane;
		this.leading_.trailing_ = this.trailing_;
		if (this.trailing_ != null) {
			this.trailing_.leading_ = this.leading_;
		} else {
			pl.lastVehicle(this.leading_);
		}
		this.leading_ = front;
		if (this.leading_ != null) {
			this.trailing_ = this.leading_.trailing_;
			this.leading_.trailing_ = this;
		} else {
			this.trailing_ = pl.firstVehicle();
			pl.firstVehicle(this);
		}
		if (this.trailing_ != null) {
			this.trailing_.leading_ = this;
		} else {
			pl.lastVehicle(this);
		}
	}

	/** Move a vehicle backward after its distance increases during restoration. */
	public void retreatInLaneList() {
		if (trailing_ == null || this.distance_ <= trailing_.distance_) return;
		Vehicle behind = trailing_;
		while (behind != null && this.distance_ > behind.distance_) {
			behind = behind.trailing_;
		}
		Lane pl = this.lane;
		this.trailing_.leading_ = this.leading_;
		if (this.leading_ != null) {
			this.leading_.trailing_ = this.trailing_;
		} else {
			pl.firstVehicle(this.trailing_);
		}
		this.trailing_ = behind;
		if (this.trailing_ != null) {
			this.leading_ = this.trailing_.leading_;
			this.trailing_.leading_ = this;
		} else {
			this.leading_ = pl.lastVehicle();
			pl.lastVehicle(this);
		}
		if (this.leading_ != null) {
			this.leading_.trailing_ = this;
		} else {
			pl.firstVehicle(this);
		}
	}

	/**
	 * Advance a vehicle to the position in macro vehicle list that corresponding to
	 * its current distance. This function is invoked whenever a vehicle is moved
	 * (including moved into a downstream segment), so that the vehicles in macro
	 * vehicle list is always sorted by their position. 
	 */
	public void advanceInMacroList() {
		// (0) Check if vehicle should be advanced in the list
		if (macroLeading_ == null || this.distance_ >= macroLeading_.distance_) {
			// No macroLeading or the distance to downstream node is greater
			// than marcroLeading. No need to advance this vehicle in list
			return;
		}
		// (1) Find vehicle's position in the list
		// Now this vehicle has a macroLeading that has the higher distance to
		// downstream node which should not be the vehicle marcroLeading anymore.
		// Need to find new marcroLeading.
		Vehicle front = macroLeading_;
		while (front != null && this.distance_ < front.distance_) {
			front = front.macroLeading_;
		}
		// (2) Take this vehicle out from the list
		// this macroLeading now will be assigned to be macroLeading of this
		// vehicle marcroTrailing
		Road pr = this.road;
		this.macroLeading_.macroTrailing_ = this.macroTrailing_;
		if (this.macroTrailing_ != null) {
			macroTrailing_.macroLeading_ = this.macroLeading_;
		} else {
			pr.lastVehicle(this.macroLeading_);
		}
		// (3) Insert this vehicle after the front
		// (3.1) Point to the front
		this.macroLeading_ = front;
		if (this.macroLeading_ != null) {
			this.macroTrailing_ = this.macroLeading_.macroTrailing_;
			this.macroLeading_.macroTrailing_ = this;
		} else {
			this.macroTrailing_ = pr.firstVehicle();
			pr.firstVehicle(this);
		}
		// (3.2) Point to the trailing vehicle
		if (this.macroTrailing_ != null) {
			this.macroTrailing_.macroLeading_ = this;
		} else {
			pr.lastVehicle(this);
		}
	}

	/**
	 * Mirror of advanceInMacroList: handles the case where this vehicle's distance
	 * increased (e.g., after a lane change to a longer lane), so it needs to move
	 * backward (toward lastVehicle) in the macro list.
	 */
	public void retreatInMacroList() {
		if (macroTrailing_ == null || this.distance_ <= macroTrailing_.distance_) {
			return;
		}
		Vehicle behind = macroTrailing_;
		while (behind != null && this.distance_ > behind.distance_) {
			behind = behind.macroTrailing_;
		}
		Road pr = this.road;
		this.macroTrailing_.macroLeading_ = this.macroLeading_;
		if (this.macroLeading_ != null) {
			macroLeading_.macroTrailing_ = this.macroTrailing_;
		} else {
			pr.firstVehicle(this.macroTrailing_);
		}
		this.macroTrailing_ = behind;
		if (this.macroTrailing_ != null) {
			this.macroLeading_ = this.macroTrailing_.macroLeading_;
			this.macroTrailing_.macroLeading_ = this;
		} else {
			this.macroLeading_ = pr.lastVehicle();
			pr.lastVehicle(this);
		}
		if (this.macroLeading_ != null) {
			this.macroLeading_.macroTrailing_ = this;
		} else {
			pr.firstVehicle(this);
		}
	}

	/**
	 * This function will check if the current lane
	 * connect to a lane in the next road if yes then it gives the checkLaneFlag
	 * true value if not then the checkLaneFlag has false value the function will be
	 * called after the vehicle updates its route i.e. the routeUpdateFlag has true
	 * value
	 */
	public boolean isInCorrectLane() {
		if (nextRoad_ == null || nextLane_ == null || lane == null) {
			return true;
		}
		return this.nextLane_.getRoad() == this.nextRoad_
				&& this.isDirectLaneTransition(this.lane, this.nextLane_);
	}

	/**
	 * Find if the potential next road and current lane are connected
	 * @param nextRoad
	 * @return Boolean connected
	 */
	public boolean checkNextLaneConnected(Road nextRoad) {
		boolean connected = false;
		Lane curLane = this.lane;

		if (nextRoad != null && curLane != null) {
			for (int dl : curLane.getDownStreamLanes()) {
				Lane downStreamLane = ContextCreator.getLaneContext().get(dl);
				if (downStreamLane != null && downStreamLane.getRoad() == nextRoad) {
					// if this lane already connects to downstream road then
					// assign to the connected lane
					connected = true;
					break;
				}
			}
		}

		return connected;
	}

	/**
	 * Prefer an entry lane which is already aligned with the road after
	 * {@code nextRoad_}. This is only a preference among topologically legal
	 * candidates; it never creates a lane connection or bypasses lane changing.
	 */
	private Lane selectRoutePreparedNextLane(List<Lane> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		Lane bestCandidate = null;
		boolean bestCanReachFollowingRoad = false;
		long bestTotalLaneChanges = Long.MAX_VALUE;
		int bestFollowingRoadLaneChanges = Integer.MAX_VALUE;
		int bestCurrentRoadLaneChanges = Integer.MAX_VALUE;
		int bestLaneIndex = Integer.MAX_VALUE;
		Road followingRoad = this.plannedRoadAfterNextRoad();
		for (Lane candidate : candidates) {
			if (candidate == null || candidate.getRoad() != this.nextRoad_) {
				continue;
			}
			int currentRoadLaneChanges = this.laneChangesNeededOnCurrentRoad(candidate);
			if (currentRoadLaneChanges == Integer.MAX_VALUE) {
				continue;
			}
			int followingRoadLaneChanges = this.laneChangesNeededForFollowingRoad(candidate, followingRoad);
			boolean canReachFollowingRoad = followingRoad == null
					|| followingRoadLaneChanges != Integer.MAX_VALUE;
			int effectiveFollowingChanges = followingRoad == null ? 0 : followingRoadLaneChanges;
			long totalLaneChanges = canReachFollowingRoad
					? (long) currentRoadLaneChanges + effectiveFollowingChanges
					: Long.MAX_VALUE;
			int laneIndex = this.nextRoad_.getLaneIndex(candidate);
			if (laneIndex < 0) {
				laneIndex = Integer.MAX_VALUE;
			}

			boolean better;
			if (bestCandidate == null) {
				better = true;
			} else if (canReachFollowingRoad != bestCanReachFollowingRoad) {
				better = canReachFollowingRoad;
			} else if (totalLaneChanges != bestTotalLaneChanges) {
				better = totalLaneChanges < bestTotalLaneChanges;
			} else if (effectiveFollowingChanges != bestFollowingRoadLaneChanges) {
				// On equal total cost, make the lane change earlier on the current road.
				better = effectiveFollowingChanges < bestFollowingRoadLaneChanges;
			} else if (currentRoadLaneChanges != bestCurrentRoadLaneChanges) {
				better = currentRoadLaneChanges < bestCurrentRoadLaneChanges;
			} else if (laneIndex != bestLaneIndex) {
				better = laneIndex < bestLaneIndex;
			} else {
				better = candidate.getID() < bestCandidate.getID();
			}
			if (better) {
				bestCandidate = candidate;
				bestCanReachFollowingRoad = canReachFollowingRoad;
				bestTotalLaneChanges = totalLaneChanges;
				bestFollowingRoadLaneChanges = effectiveFollowingChanges;
				bestCurrentRoadLaneChanges = currentRoadLaneChanges;
				bestLaneIndex = laneIndex;
			}
		}
		return bestCandidate;
	}

	private int laneChangesNeededOnCurrentRoad(Lane nextRoadLane) {
		Lane sourceLane = this.externalRoadTransition && this.externalTransitionTargetLane != null
				? this.externalTransitionTargetLane : this.lane;
		if (sourceLane == null || this.road == null || nextRoadLane == null) {
			return Integer.MAX_VALUE;
		}
		int sourceIndex = this.road.getLaneIndex(sourceLane);
		if (sourceIndex < 0) {
			return Integer.MAX_VALUE;
		}
		int bestLaneChanges = Integer.MAX_VALUE;
		for (Lane feederLane : this.road.getLanes()) {
			if (!this.isDirectLaneTransition(feederLane, nextRoadLane)) {
				continue;
			}
			int feederIndex = this.road.getLaneIndex(feederLane);
			if (feederIndex >= 0) {
				bestLaneChanges = Math.min(bestLaneChanges, Math.abs(sourceIndex - feederIndex));
			}
		}
		return bestLaneChanges;
	}

	private Road plannedRoadAfterNextRoad() {
		if (this.roadPath == null || this.roadPath.size() < 3 || this.nextRoad_ == null
				|| this.roadPath.get(1) == null
				|| this.roadPath.get(1).getID() != this.nextRoad_.getID()) {
			return null;
		}
		return this.roadPath.get(2);
	}

	private int laneChangesNeededForFollowingRoad(Lane entryLane, Road followingRoad) {
		if (entryLane == null || followingRoad == null || this.nextRoad_ == null) {
			return Integer.MAX_VALUE;
		}
		int entryIndex = this.nextRoad_.getLaneIndex(entryLane);
		if (entryIndex < 0) {
			return Integer.MAX_VALUE;
		}
		int bestLaneChanges = Integer.MAX_VALUE;
		for (Lane exitLane : this.nextRoad_.getLanes()) {
			if (!this.laneConnectsToRoad(exitLane, followingRoad)) {
				continue;
			}
			int exitIndex = this.nextRoad_.getLaneIndex(exitLane);
			if (exitIndex >= 0) {
				bestLaneChanges = Math.min(bestLaneChanges, Math.abs(entryIndex - exitIndex));
			}
		}
		return bestLaneChanges;
	}

	private boolean laneConnectsToRoad(Lane sourceLane, Road targetRoad) {
		if (sourceLane == null || targetRoad == null) {
			return false;
		}
		for (Lane targetLane : targetRoad.getLanes()) {
			if (sourceLane.getDownStreamLanes().contains(targetLane.getID())) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Assign the next lane to the vehicle
	 */
	public void assignNextLane() {
		// During an external connector handoff lane intentionally remains null, but
		// route advancement must choose the following lane from the reserved target
		// lane rather than falling back to lane 0.
		Lane curLane = this.externalRoadTransition && this.externalTransitionTargetLane != null
				? this.externalTransitionTargetLane : this.lane;
		if (this.nextRoad_ == null) {
			this.nextLane_ = null;
			return;
		} else {
			if (curLane == null) {
				// A downstream lane cannot be selected safely until the vehicle's
				// actual source lane is known. Normal admission and trace teleport
				// both install that lane before preparing the route.
				this.nextLane_ = null;
				return;
			}
			else {
				// Also consider targets reachable from another lane on this road. If one
				// is better aligned with the road after nextRoad_, choose it as the
				// mandatory-LC objective now instead of waiting for the shorter road.
				ArrayList<Lane> mandatoryLaneChangeTargets = new ArrayList<Lane>();
				for (Lane candidate : this.nextRoad_.getLanes()) {
					if (this.currentRoadHasLaneConnectingTo(candidate)) {
						mandatoryLaneChangeTargets.add(candidate);
					}
				}
				Lane routePreparedLane = this.selectRoutePreparedNextLane(mandatoryLaneChangeTargets);
				if (routePreparedLane != null) {
					this.nextLane_ = routePreparedLane;
					return;
				}
				
				if (this.patchDisconnectedNextRoad()) {
					return;
				}
				if (this.nextRoad_ == null) {
					return;
				}
				if (!isDeparturableRoad(this.road)) {
					if (requeueFromDeparturableRoadForReroute("assignNextLane")) {
						return;
					}
				}
				this.nextLane_ = null;
				return;
			}
		}
	}

	private boolean isDirectLaneTransition(Lane sourceLane, Lane targetLane) {
		return sourceLane != null && targetLane != null && targetLane.getRoad() != null
				&& sourceLane.getDownStreamLanes().contains(targetLane.getID());
	}

	/**
	 * Repair a missed mandatory lane change without moving the vehicle. The actual
	 * lane's declared successors are the only admissible targets. When possible a
	 * stale target-lane choice is corrected on the existing route immediately;
	 * otherwise a legal missed turn is installed only after lane changing has no
	 * remaining useful opportunity.
	 */
	public synchronized boolean performDeferredRoadRecovery(
			Map<Long, List<Road>> recoveryPathCache) {
		this.deferredRoadRecoveryQueued = false;
		boolean roadPatienceRequested = this.deferredRoadPatienceRecoveryRequested;
		boolean missedLaneRequested = this.deferredMissedLaneRecoveryRequested;
		this.deferredRoadPatienceRecoveryRequested = false;
		this.deferredMissedLaneRecoveryRequested = false;

		boolean recovered = false;
		boolean roadPatienceAttempted = false;
		if (roadPatienceRequested
				&& this.isRoadTraversalPatienceDepletedAtLaneFront()
				&& this.destRoad_ != null
				&& !this.roadPatienceRecoveryResolved) {
			roadPatienceAttempted = true;
			this.roadPatienceLastRecoveryTick = ContextCreator.getCurrentTick();
			recovered = this.installBestReroutedSuccessor(this.legalSuccessorLanes(),
					this.nextRoad_, true, 1.2 * this.length(), recoveryPathCache);
			if (recovered) {
				this.roadPatienceRecoveryResolved = true;
				this.latchRoadTraversalRecovery();
				this.logStuckRecovery("ROAD_PATIENCE_REROUTED", "REROUTED", null);
			}
		}
		if (missedLaneRequested && !recovered) {
			recovered = this.performMissedLaneTransitionRecovery(recoveryPathCache);
		}
		if (roadPatienceAttempted && !recovered
				&& this.isRoadTraversalPatienceDepletedAtLaneFront()
				&& this.isRoadPatienceLivenessFallbackEligible()) {
			// The ordinary patience reroute can fail even on a normal departurable
			// road. Reuse the bounded fallback so such a vehicle cannot remain the
			// permanent downstream blocker for otherwise healthy upstream lanes.
			this.handleMissedLaneRecoveryLivenessFallback(
					"ROAD_PATIENCE_REROUTE_FAILED");
		}
		return recovered;
	}

	private boolean performMissedLaneTransitionRecovery(
			Map<Long, List<Road>> recoveryPathCache) {
		if (this.externalRoadTransition || this.road == null || this.lane == null
				|| this.lane.getRoad() != this.road
				|| this.road.getControlType() == Road.COSIM) {
			return false;
		}
		if (this.nextLane_ != null && this.nextLane_.getRoad() == this.nextRoad_
				&& this.isDirectLaneTransition(this.lane, this.nextLane_)) {
			return false;
		}

		Road plannedRoad = this.plannedNextRoadForRecovery();
		this.refreshMissedLaneRecoveryEpisode();
		ArrayList<Lane> successors = this.legalSuccessorLanes();
		Lane plannedSuccessor = null;
		for (Lane successor : successors) {
			if (successor.getRoad() != plannedRoad) {
				continue;
			}
			if (this.pathStartsWithCurrentAnd(plannedRoad)) {
				this.nextRoad_ = plannedRoad;
				this.nextLane_ = successor;
				this.latchRoadTraversalRecovery();
				return true;
			}
			if (plannedSuccessor == null) plannedSuccessor = successor;
		}

		double laneLength = this.lane.getLength();
		double laneChangeThreshold = this.mandatoryLaneChangePreparationDistance();
		boolean noRemainingLaneChangeOpportunity = !Double.isFinite(laneLength)
				|| !Double.isFinite(laneChangeThreshold)
				|| laneLength <= laneChangeThreshold
				|| this.distance_ <= laneChangeThreshold
				|| this.road.getNumberOfLanes() <= 1
				|| this.nextLane_ == null
				|| this.nextLane_.getRoad() != this.nextRoad_
				|| !this.currentRoadHasLaneConnectingTo(this.nextLane_);
		boolean finalAttempt = this.isRoadTraversalPatienceDepletedAtLaneFront();
		if (!noRemainingLaneChangeOpportunity && !finalAttempt) {
			return false;
		}

		if (finalAttempt) {
			if (this.missedLaneRecoveryFinalAttempted) {
				return false;
			}
			this.missedLaneRecoveryFinalAttempted = true;
			this.missedLaneRecoveryInitialAttempted = true;
		} else {
			if (this.missedLaneRecoveryInitialAttempted) {
				return false;
			}
			this.missedLaneRecoveryInitialAttempted = true;
		}
		
		if (plannedSuccessor != null
				&& this.installLegalSuccessorRoute(plannedSuccessor, recoveryPathCache)) {
			this.latchRoadTraversalRecovery();
			return true;
		}
		if (this.installBestReroutedSuccessor(successors, plannedRoad, false, 0.0,
				recoveryPathCache)) {
			this.latchRoadTraversalRecovery();
			return true;
		}
		if (finalAttempt && this.isRoadPatienceLivenessFallbackEligible()) {
			this.handleMissedLaneRecoveryLivenessFallback(
					"MISSED_LANE_RECOVERY_FAILED");
		}
		return false;
	}

	private void refreshMissedLaneRecoveryEpisode() {
		int sourceRoadID = this.road == null ? -1 : this.road.getID();
		if (this.missedLaneRecoveryRoadID != sourceRoadID) {
			this.resetMissedLaneRecoveryEpisode();
			this.missedLaneRecoveryRoadID = sourceRoadID;
		}
	}

	private void resetMissedLaneRecoveryEpisode() {
		this.missedLaneRecoveryRoadID = -1;
		this.missedLaneRecoverySelectedLane = null;
		this.missedLaneRecoverySelectedPath = null;
		this.missedLaneRecoveryInitialAttempted = false;
		this.missedLaneRecoveryFinalAttempted = false;
		this.missedLaneRecoveryFallbackHandled = false;
	}

	private void resetMissedLaneRecoveryState() {
		this.resetMissedLaneRecoveryEpisode();
		this.missedLaneRecoveryQuarantined = false;
	}

	private void latchRoadTraversalRecovery() {
		if (this.road == null || this.nextLane_ == null || this.nextRoad_ == null
				|| this.nextLane_.getRoad() != this.nextRoad_
				|| !this.isFeasibleRecoveryTransition(this.nextLane_)
				|| !this.pathStartsWithCurrentAnd(this.nextRoad_)) {
			return;
		}
		this.missedLaneRecoveryRoadID = this.road.getID();
		this.missedLaneRecoverySelectedLane = this.nextLane_;
		this.missedLaneRecoverySelectedPath = Collections.unmodifiableList(
				new ArrayList<Road>(this.roadPath));
	}

	private boolean restoreLatchedRoadTraversalRecovery() {
		if (this.road == null || this.missedLaneRecoveryRoadID != this.road.getID()
				|| this.missedLaneRecoverySelectedLane == null
				|| this.missedLaneRecoverySelectedPath == null) {
			return false;
		}
		Road selectedRoad = this.missedLaneRecoverySelectedLane.getRoad();
		if (selectedRoad == null
				|| !this.isFeasibleRecoveryTransition(this.missedLaneRecoverySelectedLane)) {
			return false;
		}
		if (this.nextRoad_ == selectedRoad
				&& this.nextLane_ == this.missedLaneRecoverySelectedLane
				&& this.pathStartsWithCurrentAnd(selectedRoad)) {
			return true;
		}
		return this.installLegalSuccessorRoute(this.missedLaneRecoverySelectedLane,
				this.missedLaneRecoverySelectedPath);
	}

	private boolean isRoadPatienceLivenessFallbackEligible() {
		if (this.road instanceof ConnectorRoad) {
			// A validated native connector occupant has no safe place to wait: it
			// physically blocks the intersection until it moves or is recovered.
			return true;
		}
		// On normal roads, limit detach/requeue to the downstream transfer zone.
		// This avoids treating an isolated vehicle stopped far upstream as a
		// junction liveness failure merely because its patience timer elapsed.
		double recoveryDistance = Math.max(STOP_LINE_WAIT_DISTANCE_METERS,
				1.2 * this.length());
		return Double.isFinite(this.distance_) && this.distance_ >= 0.0
				&& this.distance_ <= recoveryDistance;
	}

	private void logStuckRecovery(String reason, String action, Road fallbackRoad) {
		ContextCreator.logger.warn("STUCK_RECOVERED"
				+ " tick=" + ContextCreator.getCurrentTick()
				+ " veh=" + this.getID()
				+ " reason=" + reason
				+ " action=" + action
				+ " roadStoppedTicks=" + this.roadTraversalStoppedTicks
				+ " currentRoad=" + roadLabel(this.road)
				+ " currentLane=" + laneLabel(this.lane)
				+ " distToJunction=" + formatDebugDouble(this.distance_)
				+ " fallbackRoad=" + roadLabel(fallbackRoad));
	}

	private void handleMissedLaneRecoveryLivenessFallback(String reason) {
		if (this.missedLaneRecoveryFallbackHandled || this.externalRoadTransition) {
			return;
		}
		this.missedLaneRecoveryFallbackHandled = true;
		// Never restart from the same road/lane that just exhausted its patience;
		// doing so resets the traversal timer and recreates the identical failure.
		Road fallbackRoad = this.departurableFallbackRoad(this.road);
		if (fallbackRoad != null) {
			this.logStuckRecovery(reason, "REQUEUED", fallbackRoad);
			this.originRoad_ = fallbackRoad;
			this.updateLastDeparturableRoad(fallbackRoad);
			this.isReachDest = false;
			this.queueDepartureFromRoad(fallbackRoad);
			return;
		}

		// No safe road exists from which to retry. Preserve the trip and last pose,
		// but detach the quarantined vehicle so it cannot remain a permanent
		// physical blocker. A later explicit departure can safely requeue it.
		this.logStuckRecovery(reason, "QUARANTINED", null);
		this.missedLaneRecoveryQuarantined = true;
		this.clearShadowImpact();
		this.removeFromCurrentLane();
		this.removeFromCurrentRoad();
		this.onLane = false;
		this.onRoad = false;
		this.currentSpeed_ = 0.0;
		this.accRate_ = 0.0;
		this.accDecided_ = false;
		this.hasAccelerationPlan_ = false;
		this.movingFlag = false;
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		this.nosingFlag = false;
		this.yieldingFlag = false;
		this.clearLaneChangeManeuverFields();
		this.resetLaneChangeRuntimeState();
	}

	/**
	 * Return the actual lane's valid successors in a stable order. The planned
	 * road is considered first, followed by road ID and lane ID, so recovery is
	 * reproducible even when loader insertion order varies.
	 */
	private ArrayList<Lane> legalSuccessorLanes() {
		ArrayList<Lane> successors = new ArrayList<Lane>();
		if (this.lane == null || ContextCreator.getLaneContext() == null) {
			return successors;
		}
		for (Integer laneID : this.lane.getDownStreamLanes()) {
			if (laneID == null) continue;
			Lane successor = ContextCreator.getLaneContext().get(laneID);
			if (!this.isFeasibleRecoveryTransition(successor)) continue;
			boolean duplicate = false;
			for (Lane existing : successors) {
				if (existing.getID() == successor.getID()) {
					duplicate = true;
					break;
				}
			}
			if (!duplicate) successors.add(successor);
		}
		final Road plannedRoad = this.plannedNextRoadForRecovery();
		successors.sort((first, second) -> {
			boolean firstPlanned = first.getRoad() == plannedRoad;
			boolean secondPlanned = second.getRoad() == plannedRoad;
			if (firstPlanned != secondPlanned) return firstPlanned ? -1 : 1;
			int roadOrder = Integer.compare(first.getRoad().getID(), second.getRoad().getID());
			return roadOrder != 0 ? roadOrder : Integer.compare(first.getID(), second.getID());
		});
		return successors;
	}

	private boolean isFeasibleRecoveryTransition(Lane successorLane) {
		if (!this.isDirectLaneTransition(this.lane, successorLane)
				|| this.road == null || successorLane.getRoad() == null) {
			return false;
		}
		if (this.road instanceof ConnectorRoad) {
			ConnectorRoad connector = (ConnectorRoad) this.road;
			ConnectorRoad.ConnectorPath connectorPath = connector.getPath(this.lane);
			return connectorPath != null
					&& connectorPath.getTargetLane() == successorLane;
		}
		RoadContext roadContext = ContextCreator.getRoadContext();
		if (roadContext == null) return false;
		ConnectorRoad connector = roadContext.getConnector(
				this.road, successorLane.getRoad());
		return connector != null
				&& connector.getPath(this.lane, successorLane) != null;
	}

	private Road plannedNextRoadForRecovery() {
		if (this.nextRoad_ != null) {
			return this.nextRoad_;
		}
		if (this.roadPath != null && this.roadPath.size() > 1) {
			return this.roadPath.get(1);
		}
		return null;
	}

	private boolean installLegalSuccessorRoute(Lane successorLane,
			Map<Long, List<Road>> recoveryPathCache) {
		if (!this.isFeasibleRecoveryTransition(successorLane)) {
			return false;
		}
		Road successorRoad = successorLane.getRoad();
		if (successorRoad == null || successorRoad == this.road) {
			return false;
		}

		if (this.pathStartsWithCurrentAnd(successorRoad)) {
			this.nextRoad_ = successorRoad;
			this.nextLane_ = successorLane;
			return true;
		}

		List<Road> successorPath = this.buildRecoveryPath(successorRoad,
				recoveryPathCache);
		if (successorPath == null) {
			return false;
		}
		return this.installLegalSuccessorRoute(successorLane, successorPath);
	}

	private boolean installLegalSuccessorRoute(Lane successorLane, List<Road> successorPath) {
		if (!this.isFeasibleRecoveryTransition(successorLane)
				|| successorLane.getRoad() == null
				|| !this.isConnectedRecoveryPath(successorPath, successorLane.getRoad())
				|| this.recoveryPathRevisitsCurrentRoad(successorPath)) {
			return false;
		}
		this.clearShadowImpact();
		this.roadPath = new ArrayList<Road>(successorPath);
		this.nextRoad_ = successorLane.getRoad();
		this.nextLane_ = successorLane;
		this.atOrigin = false;
		this.setDistToTravelEstimate(this.routeDistanceFromCurrentPosition(this.roadPath));
		this.setShadowImpact();
		return true;
	}

	private boolean installBestReroutedSuccessor(List<Lane> successors, Road excludedRoad,
			boolean requireEntranceGap, double requiredGap,
			Map<Long, List<Road>> recoveryPathCache) {
		Lane bestLane = null;
		List<Road> bestPath = null;
		double bestTravelTime = Double.POSITIVE_INFINITY;
		LinkedHashMap<Integer, Lane> bestLaneBySuccessorRoad = new LinkedHashMap<Integer, Lane>();
		for (Lane successor : successors) {
			Road successorRoad = successor == null ? null : successor.getRoad();
			if (successorRoad == null || successorRoad == excludedRoad) {
				continue;
			}
			if (requireEntranceGap && (this.entranceGap(successor) < requiredGap
					|| successorRoad.getExternalLaneReservationBlocker(successor, this) != null)) {
				continue;
			}
			Lane current = bestLaneBySuccessorRoad.get(successorRoad.getID());
			if (current == null || this.compareRecoveryTargets(successor, current) < 0) {
				bestLaneBySuccessorRoad.put(successorRoad.getID(), successor);
			}
		}
		boolean hasNonUTurnCandidate = false;
		for (Lane successor : bestLaneBySuccessorRoad.values()) {
			if (!this.isUTurnSuccessor(successor.getRoad())) {
				hasNonUTurnCandidate = true;
				break;
			}
		}
		int candidatePasses = hasNonUTurnCandidate ? 2 : 1;
		for (int candidatePass = 0; candidatePass < candidatePasses; candidatePass++) {
			boolean evaluateUTurns = !hasNonUTurnCandidate || candidatePass == 1;
			if (evaluateUTurns && bestLane != null) {
				break;
			}
			for (Lane successor : bestLaneBySuccessorRoad.values()) {
				Road successorRoad = successor.getRoad();
				if (this.isUTurnSuccessor(successorRoad) != evaluateUTurns) {
					continue;
				}
				List<Road> candidatePath = this.buildRecoveryPath(successorRoad,
						recoveryPathCache);
				if (candidatePath == null) {
					continue;
				}
				double candidateTravelTime = this.recoveryPathTravelTime(candidatePath);
				if (bestLane == null
						|| Double.compare(candidateTravelTime, bestTravelTime) < 0
						|| (Double.compare(candidateTravelTime, bestTravelTime) == 0
								&& this.compareRecoveryTargets(successor, bestLane) < 0)) {
					bestLane = successor;
					bestPath = candidatePath;
					bestTravelTime = candidateTravelTime;
				}
			}
		}
		return bestLane != null && this.installLegalSuccessorRoute(bestLane, bestPath);
	}

	private double recoveryPathTravelTime(List<Road> candidatePath) {
		double totalTravelTime = 0.0;
		for (Road pathRoad : candidatePath) {
			if (pathRoad == null) return Double.POSITIVE_INFINITY;
			double roadTravelTime = pathRoad.getTravelTime();
			if (!Double.isFinite(roadTravelTime) || roadTravelTime < 0.0) {
				return Double.POSITIVE_INFINITY;
			}
			totalTravelTime += roadTravelTime;
		}
		return Double.isFinite(totalTravelTime)
				? totalTravelTime : Double.POSITIVE_INFINITY;
	}

	private int compareRecoveryTargets(Lane first, Lane second) {
		int roadOrder = Integer.compare(first.getRoad().getID(), second.getRoad().getID());
		return roadOrder != 0 ? roadOrder : Integer.compare(first.getID(), second.getID());
	}

	private List<Road> buildRecoveryPath(Road successorRoad,
			Map<Long, List<Road>> recoveryPathCache) {
		if (successorRoad == null || this.road == null || this.destRoad_ == null) {
			return null;
		}
		long cacheKey = ((long) successorRoad.getID() << 32)
				^ (this.destRoad_.getID() & 0xffffffffL);
		List<Road> suffix = null;
		if (recoveryPathCache != null && recoveryPathCache.containsKey(cacheKey)) {
			List<Road> cached = recoveryPathCache.get(cacheKey);
			if (cached == null || cached.isEmpty()) return null;
			suffix = cached;
		} else {
			suffix = RouteContext.shortestPathRoute(
					successorRoad, this.destRoad_, this.rand_route_only);
			if (recoveryPathCache != null) {
				recoveryPathCache.put(cacheKey, suffix == null || suffix.isEmpty()
						? Collections.<Road>emptyList()
						: Collections.unmodifiableList(new ArrayList<Road>(suffix)));
			}
		}
		if (suffix == null || suffix.isEmpty() || suffix.get(0) == null
				|| suffix.get(0).getID() != successorRoad.getID()) {
			return null;
		}
		ArrayList<Road> recoveredPath = new ArrayList<Road>(suffix.size() + 1);
		recoveredPath.add(this.road);
		recoveredPath.addAll(suffix);
		if (this.recoveryPathRevisitsCurrentRoad(recoveredPath)) {
			return null;
		}
		return this.isConnectedRecoveryPath(recoveredPath, successorRoad)
				? recoveredPath : null;
	}

	private boolean pathStartsWithCurrentAnd(Road successorRoad) {
		return this.roadPath != null && this.roadPath.size() > 1
				&& this.roadPath.get(0) != null && this.roadPath.get(1) != null
				&& this.roadPath.get(0).getID() == this.road.getID()
				&& this.roadPath.get(1).getID() == successorRoad.getID()
				&& !this.recoveryPathRevisitsCurrentRoad(this.roadPath)
				&& this.isConnectedRecoveryPath(this.roadPath, successorRoad);
	}

	private boolean recoveryPathRevisitsCurrentRoad(List<Road> candidatePath) {
		if (candidatePath == null || this.road == null) return false;
		int lastIndex = Math.min(candidatePath.size() - 1, RECOVERY_LOOP_LOOKAHEAD_ROADS);
		for (int i = 2; i <= lastIndex; i++) {
			Road pathRoad = candidatePath.get(i);
			if (pathRoad != null && pathRoad.getID() == this.road.getID()) {
				return true;
			}
		}
		return false;
	}

	private boolean isUTurnSuccessor(Road successorRoad) {
		if (this.road == null || successorRoad == null) return false;
		int currentUpstream = this.road.getUpStreamJunction();
		int currentDownstream = this.road.getDownStreamJunction();
		return currentUpstream >= 0 && currentDownstream >= 0
				&& successorRoad.getUpStreamJunction() == currentDownstream
				&& successorRoad.getDownStreamJunction() == currentUpstream;
	}

	private boolean isConnectedRecoveryPath(List<Road> candidatePath, Road successorRoad) {
		if (candidatePath == null || candidatePath.size() < 2
				|| candidatePath.get(0) == null || candidatePath.get(1) == null
				|| candidatePath.get(0).getID() != this.road.getID()
				|| candidatePath.get(1).getID() != successorRoad.getID()) {
			return false;
		}
		// The exact source-lane -> successor-lane connection is authoritative for
		// the first edge and is checked by installLegalSuccessorRoute(). Validate
		// every later road edge in the newly computed suffix here.
		for (int i = 2; i < candidatePath.size(); i++) {
			Road upstreamRoad = candidatePath.get(i - 1);
			Road downstreamRoad = candidatePath.get(i);
			if (upstreamRoad == null || downstreamRoad == null
					|| !upstreamRoad.getDownStreamRoads().contains(downstreamRoad.getID())) {
				return false;
			}
		}
		return true;
	}

	private boolean nextRoadMatchesPath() {
		return this.roadPath != null && this.roadPath.size() > 1 && this.roadPath.get(1) != null
				&& this.nextRoad_ != null && this.nextRoad_.getID() == this.roadPath.get(1).getID();
	}

	private boolean patchDisconnectedNextRoad() {
		if (this.road == null || this.nextRoad_ == null || this.roadPath == null) {
			return false;
		}
		List<Road> patchPath = RouteContext.shortestPathRoute(this.road, this.nextRoad_, this.rand_route_only);
		if (patchPath == null || patchPath.size() <= 2) {
			return false;
		}
		this.clearShadowImpact();
		this.roadPath.addAll(1, new ArrayList<Road>(patchPath.subList(1, patchPath.size() - 1)));
		this.nextRoad_ = this.roadPath.get(1);
		this.setShadowImpact();
		this.assignNextLane();
		return this.nextLane_ != null;
	}

	/**
	 * Return the target lane (the lane that connect to the downstream Road)
	 */
	public Lane targetLane() {
		if (this.nextRoad_ == null || this.nextLane_ == null
				|| this.nextLane_.getRoad() != this.nextRoad_
				|| this.road == null || this.lane == null) {
			return null;
		}
		Lane closestConnectedLane = null;
		int currentLaneIndex = this.road.getLaneIndex(this.lane);
		int closestLaneChanges = Integer.MAX_VALUE;
		for (Lane candidate : this.road.getLanes()) {
			if (!this.isDirectLaneTransition(candidate, this.nextLane_)) {
				continue;
			}
			int candidateIndex = this.road.getLaneIndex(candidate);
			int laneChanges = currentLaneIndex < 0 || candidateIndex < 0
					? Integer.MAX_VALUE : Math.abs(candidateIndex - currentLaneIndex);
			if (closestConnectedLane == null || laneChanges < closestLaneChanges) {
				closestConnectedLane = candidate;
				closestLaneChanges = laneChanges;
			}
		}
		return closestConnectedLane;
	}

	/**
	 * Return the next lane that the vehicle need to change to in order to reach the
	 * target lane
	 */
	public Lane tempLane() {
		Lane plane = this.targetLane();
		if (plane == null || this.road == null || this.lane == null) {
			return null;
		}
		Lane tempLane_ = null;
		if (this.road.getLaneIndex(plane) > this.road.getLaneIndex(this.lane)) {
			tempLane_ = this.rightLane();
		}
		if (this.road.getLaneIndex(plane) < this.road.getLaneIndex(this.lane)) {
			tempLane_ = this.leftLane();
		}
		return tempLane_;
	}

	/**
	 * Get left lane
	 * @return Lane leftLane
	 */
	public Lane leftLane() {
		Lane leftLane = null;
		if (this.road.getLaneIndex(this.lane) > 0) {
			leftLane = this.road.getLane(this.road.getLaneIndex(this.lane) - 1);
		}
		return leftLane;
	}

	/**
	 * Get right lane
	 * @return Lane rightLane
	 */
	public Lane rightLane() {
		Lane rightLane = null;
		if (this.road.getLaneIndex(this.lane) < this.road.getNumberOfLanes() - 1) {
			rightLane = this.road.getLane(this.road.getLaneIndex(this.lane) + 1);
		}
		return rightLane;
	}

	/**
	 * Mandatory lane changing. The input parameter is the
	 * temporary lane.
	 */
	public boolean mandatoryLC(Lane plane) {
		if (plane == null) {
			return false;
		}
		if (this.isWithinNoLaneChangingArea()) {
			this.nosingFlag = false;
			return this.isLaneClear(plane) && this.changeLane(plane);
		}
		double newDistance = this.distanceInNewLane(plane);
		Vehicle leadVehicle = this.leadVehicle(plane, newDistance);
		Vehicle lagVehicle = this.lagVehicle(plane, newDistance);
		/*
		 * Consider the condition to change the lane as follow: If there are leading and
		 * trailing vehicle then the vehicle will check for gap acceptance as usual.
		 * However, if there is no leading or no trailing, the leadGap or the lagGap
		 * should be neglected. In the case the vehicle cannot change the lane and the
		 * distance to downstream is less than some threshold then the vehicle starts
		 * nosing.
		 */
		if (leadVehicle != null) {
			if (lagVehicle != null) {
				if (this.leadGap(leadVehicle, newDistance) >= this.critLeadGapMLC(leadVehicle, plane)
						&& this.lagGap(lagVehicle, newDistance) >= this.critLagGapMLC(lagVehicle, plane)) {
					boolean changedLane = this.changeLane(plane);
					if (changedLane) {
						this.nosingFlag = false;
					}
					return changedLane;
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}
			} else {
				if (this.leadGap(leadVehicle, newDistance) >= this.critLeadGapMLC(leadVehicle, plane)) {
					boolean changedLane = this.changeLane(plane);
					if (changedLane) {
						this.nosingFlag = false;
					}
					return changedLane;
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}

			}
		} else {
			if (lagVehicle != null) {
				if (this.lagGap(lagVehicle, newDistance) >= this.critLagGapMLC(lagVehicle, plane)) {
					boolean changedLane = this.changeLane(plane);
					if (changedLane) {
						this.nosingFlag = false;
					}
					return changedLane;
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}
			} else {
				boolean changedLane = this.changeLane(plane);
				if (changedLane) {
					this.nosingFlag = false;
				}
				return changedLane;
			}
		}
		return false;
	}

	/**
	 * If the vehicle with MLC state can't change the lane after some distance. The
	 * vehicle need to nose and yield the lag Vehicle of the target lane in order to
	 * have enough gap to change the lane This function is called only when
	 * nosingFlag is true and must be recalled until nosingFlag receive false value
	 * after the vehicle nosed, tag the lag vehicle in target lane to yielding
	 * status. This function will be called in makeAccelerationDecision
	 */
	public double nosing() {
		double acc = 0;
		double lagGap;
		Lane tarLane = this.tempLane();
		if(tarLane != null) {
			double newDistance = this.distanceInNewLane(tarLane);
			Vehicle leadVehicle = this.leadVehicle(tarLane, newDistance);
			Vehicle lagVehicle = this.lagVehicle(tarLane, newDistance);
			/*
			 * 0. If there is a lag vehicle in the target lane, the vehicle will yield that
			 * lag vehicle however, the yielding is only true if the distance is less than
			 * some threshold
			 */
			lagGap = this.lagGap(lagVehicle, newDistance);
			if (lagVehicle != null  && lagGap < GlobalVariables.MIN_LAG) {
				lagVehicle.yieldingFlag = true;
			}
			Vehicle front = this.leading();
			/*
			 * 1. If there is a lead and a lag vehicle in the target lane. the vehicle will
			 * check the lead gap before decide to decelerate. if the lead gap is large,
			 * then the subject vehicle will be assigned with the accelerate rate as in car
			 * following. 2. if there is no lead vehicle in the target lane. the subject
			 * vehicle will max accelerate.
			 */
			if (leadVehicle != null) {
				if (this.leadGap(leadVehicle, newDistance) < this.critLeadGapMLC(leadVehicle, tarLane)) {
					if (this.currentSpeed_ > 12.2f) {
						acc = -1.47f;// meters/sec^2
					} else if (this.currentSpeed_ > 6.1f)
						acc = -2.04f;
					else
						acc = -2.4f;
				} else {
					if (front != null)
						acc = this.calcCarFollowingRate(front);
					else
						acc = this.calcFreeFlowRate();
				}
			} else {
				if (front != null)
					acc = this.calcCarFollowingRate(front);
				else
					acc = this.calcFreeFlowRate();
			}
		}
		this.nosingFlag = false;

		return acc;
	}

	/**
	 * While moving, the vehicle will checks if the vehicles in adjection lanes are
	 * nosing to its lane or not after some distance to the downstream node If the
	 * nosing is true then it will be tagged in yielding state to slow down.
	 */
	public double yielding() {
		double acc = 0;
		if (this.currentSpeed_ > 24.3f)
			acc = -2.44f;
		else if (this.currentSpeed_ > 18.3f)
			acc = -2.6f;
		else if (this.currentSpeed_ > 12.2f)
			acc = -2.74f;
		else if (this.currentSpeed_ > 6.1f)
			acc = -2.9f;
		else
			acc = -3.05f;
		this.yieldingFlag = false;
		return acc;
	}
	
	/**
	 * Calculate critical lead gap of the vehicle with the lead vehicle in the target lane.
	 * @param leadVehicle Lead vehicle
	 * @param plane Target lane
	 * @return double critLead
	 */
	public double critLeadGapMLC(Vehicle leadVehicle, Lane plane) {
		double critLead = 0;
		double minLead_ = GlobalVariables.MIN_LEAD;
		double betaLead01 = GlobalVariables.betaLeadMLC01;
		double betaLead02 = GlobalVariables.betaLeadMLC02;
		double gama = GlobalVariables.MLCgamma;
		if (leadVehicle != null)
			critLead = minLead_ + (betaLead01 * this.currentSpeed()
					+ betaLead02 * (this.currentSpeed() - leadVehicle.currentSpeed()))
					* (1 - Math.exp(-gama * this.distFraction() * plane.getLength()));
		if (critLead < minLead_)
			critLead = minLead_;
		return critLead;
	}

	/**
	 * Calculate lead gap of the vehicle with the lead vehicle in the target lane
	 * @param leadVehicle Lead vehicle
	 * @param plane Target lane
	 * @return double leadGap
	 */
	public double leadGap(Vehicle leadVehicle, double newDistance) {
		if (leadVehicle == null) return newDistance;
		Lane projectedLane = this.cachedProjectionLane_;
		double leadDistance = projectedLane == null ? Double.NaN
				: leadVehicle.getDistanceOnLaneForSafety(projectedLane);
		if (!Double.isFinite(leadDistance)) leadDistance = leadVehicle.distance_;
		return newDistance - leadDistance - leadVehicle.length();
	}

	/** 
	 * Calculate critical lag gap of the vehicle with the lag vehicle in the target lane.
	 * @param lagVehicle
	 * @param plane
	 * @return double critLag
	 */
	public double critLagGapMLC(Vehicle lagVehicle, Lane plane) {
		double critLag = 0;
		double betaLag01 = GlobalVariables.betaLagMLC01;
		double betaLag02 = GlobalVariables.betaLagMLC02;
		double gama = GlobalVariables.MLCgamma;
		double minLag_ = GlobalVariables.MIN_LAG;
		if (lagVehicle != null) {
			critLag = minLag_
					+ (betaLag01 * this.currentSpeed() + betaLag02 * (this.currentSpeed() - lagVehicle.currentSpeed()))
							* (1 - Math.exp(-gama * this.distFraction() * plane.getLength()));
		}
		if (critLag < minLag_)
			critLag = minLag_;
		return critLag;
	}

	/** 
	 * Calculate lag gap of the vehicle with the lag vehicle in the target lane.
	 * @param lagVehicle
	 * @param plane
	 * @return double lagGap
	 */
	public double lagGap(Vehicle lagVehicle, double newDistance) {
		Lane projectedLane = this.cachedProjectionLane_;
		if (lagVehicle != null) {
			double lagDistance = projectedLane == null ? Double.NaN
					: lagVehicle.getDistanceOnLaneForSafety(projectedLane);
			if (!Double.isFinite(lagDistance)) lagDistance = lagVehicle.distance_;
			return lagDistance - newDistance - this.length();
		}
		double laneLength = projectedLane == null || !Double.isFinite(projectedLane.getLength())
				? this.lane.getLength() : projectedLane.getLength();
		return laneLength - newDistance;
	}
	
	/** Find the nearest physical or reserved vehicle ahead in a target lane. */
	public Vehicle leadVehicle(Lane plane, double dist) {
		if (plane == null || !Double.isFinite(dist)) return null;
		Vehicle physical = null;
		Vehicle candidate = this.macroTrailing_;
		while (candidate != null) {
			if (candidate.lane == plane) {
				if (candidate.getDistanceToNextJunction() <= dist) physical = candidate;
				break;
			}
			if (candidate.getDistanceToNextJunction() > dist + 31.459) break;
			candidate = candidate.macroTrailing_;
		}
		if (physical == null) {
			candidate = this.macroLeading_;
			while (candidate != null) {
				if (candidate.lane == plane
						&& candidate.getDistanceToNextJunction() <= dist) {
					physical = candidate;
					break;
				}
				candidate = candidate.macroLeading_;
			}
		}
		Vehicle reserved = this.road == null ? null
				: this.road.findLaneChangeReservedLeader(plane, this, dist);
		return this.closerLaneLeader(physical, reserved, plane, dist);
	}

	private Vehicle closerLaneLeader(Vehicle first, Vehicle second, Lane plane, double egoDistance) {
		if (first == null) return second;
		if (second == null) return first;
		double firstDistance = first.getDistanceOnLaneForSafety(plane);
		double secondDistance = second.getDistanceOnLaneForSafety(plane);
		if (!Double.isFinite(firstDistance)) return second;
		if (!Double.isFinite(secondDistance)) return first;
		boolean firstAhead = firstDistance <= egoDistance;
		boolean secondAhead = secondDistance <= egoDistance;
		if (!firstAhead) return secondAhead ? second : null;
		if (!secondAhead) return first;
		return secondDistance > firstDistance ? second : first;
	}

	/** Find the nearest physical or reserved vehicle behind in a target lane. */
	public Vehicle lagVehicle(Lane plane, double dist) {
		if (plane == null || !Double.isFinite(dist)) return null;
		Vehicle physical = null;
		Vehicle candidate = this.macroLeading_;
		while (candidate != null) {
			if (candidate.lane == plane) {
				if (candidate.getDistanceToNextJunction() > dist) physical = candidate;
				break;
			}
			if (candidate.getDistanceToNextJunction() <= dist - 31.459) break;
			candidate = candidate.macroLeading_;
		}
		if (physical == null) {
			candidate = this.macroTrailing_;
			while (candidate != null) {
				if (candidate.lane == plane
						&& candidate.getDistanceToNextJunction() > dist) {
					physical = candidate;
					break;
				}
				candidate = candidate.macroTrailing_;
			}
		}
		Vehicle reserved = this.road == null ? null
				: this.road.findLaneChangeReservedLag(plane, this, dist);
		return this.closerLaneLag(physical, reserved, plane, dist);
	}

	private Vehicle closerLaneLag(Vehicle first, Vehicle second, Lane plane, double egoDistance) {
		if (first == null) return second;
		if (second == null) return first;
		double firstDistance = first.getDistanceOnLaneForSafety(plane);
		double secondDistance = second.getDistanceOnLaneForSafety(plane);
		if (!Double.isFinite(firstDistance)) return second;
		if (!Double.isFinite(secondDistance)) return first;
		boolean firstBehind = firstDistance > egoDistance;
		boolean secondBehind = secondDistance > egoDistance;
		if (!firstBehind) return secondBehind ? second : null;
		if (!secondBehind) return first;
		return secondDistance < firstDistance ? second : first;
	}
	/**
	 * Discretionary LC model at current stage.
	 * The DLC is implementing as follow: 1. If the vehicle is not close to downstream node
	 * 2. and it finds a correct lane with better traffic condition -> then it will
	 * change lane. If the vehicle is in correct lane then we find a better lane that is also
	 * connected to downstream line this function is called at the
	 * makeLaneChangingDecision
	 */
	public Lane findBetterLane() {
		Lane curLane = this.lane;
		Lane targetLane = null;
		Lane rightLane = this.rightLane();
		Lane leftLane = this.leftLane();
		// If left and right lane exist then check if they are both connect to
		// next lane or not
		if (this.equals(curLane.firstVehicle())) {
			return null;
		} else {
			if (leftLane != null && rightLane != null) {
				Lane tempLane = leftLane.betterLane(rightLane);
				targetLane = curLane.betterLane(tempLane); // Get the lane that
				// has best traffic condition
			} else if (leftLane != null)
				targetLane = curLane.betterLane(leftLane);
			else if (rightLane != null) {
				targetLane = curLane.betterLane(rightLane);
			}
			return targetLane;
		}
	}
	
	/**
	 * Find either the correct lane for connecting to the downstream road or a faster lane based on vehicles' current loc
	 * @return Lane targetLane
	 */
	public Lane findBetterCorrectLane() {
		Lane curLane = this.lane;
		Lane targetLane = null;
		Lane rightLane = this.rightLane();
		Lane leftLane = this.leftLane();
		// If left and right lane exist then check if they are both connect to
		// next lane or not
		if (this.equals(curLane.firstVehicle())) { // This is the first veh, no need to change lane
			return null;
		} else {
			if (leftLane != null && rightLane != null) {
				// if both left and right lanes are connected to downstream lane
				if (leftLane.isConnectToLane(this.nextLane_) && rightLane.isConnectToLane(this.nextLane_)) {
					Lane tempLane = leftLane.betterLane(rightLane);
					targetLane = curLane.betterLane(tempLane); // get the lane that
					// has best traffic condition
				}
				// if only left lane connects to downstream lane
				else if (leftLane.isConnectToLane(this.nextLane_)) {
					targetLane = curLane.betterLane(leftLane);
				}
				// if only right lane connects to downstream lane
				else if (rightLane.isConnectToLane(this.nextLane_)) {
					targetLane = curLane.betterLane(rightLane);
				}
			} else if (leftLane != null && leftLane.isConnectToLane(this.nextLane_))
				targetLane = curLane.betterLane(leftLane);
			else if (rightLane != null && rightLane.isConnectToLane(this.nextLane_)) {
				targetLane = curLane.betterLane(rightLane);
			}
			return targetLane;
		}

	}

	/**
	 * Given a target lane, ask vehicle to change to that lane discretionarily.
	 * @param plane Target lane
	 */
	public boolean discretionaryLC(Lane plane) {
		if (plane == null) {
			return false;
		}
		double newDistance = this.distanceInNewLane(plane);
		Vehicle leadVehicle = this.leadVehicle(plane, newDistance);
		Vehicle lagVehicle = this.lagVehicle(plane, newDistance);
		double leadGap = this.leadGap(leadVehicle, newDistance);
		double lagGap = this.lagGap(lagVehicle, newDistance);
		double critLead = this.criticalLeadDLC(leadVehicle);
		double critLag = this.criticalLagDLC(lagVehicle);
		if (leadGap > critLead && lagGap > critLag) { // there exists enough space for lane changing
			return this.changeLane(plane);
		}
		return false;
	}
	
	/**
	 * Get critical lead gap for DLC (discretional lane changing)
	 * @param pv
	 * @return double critLead
	 */
	public double criticalLeadDLC(Vehicle pv) {
		double critLead = 0;
		double minLead = GlobalVariables.minLeadDLC;
		if (pv != null) {
			critLead = minLead + GlobalVariables.betaLeadDLC01 * this.currentSpeed_
					+ GlobalVariables.betaLeadDLC02 * (this.currentSpeed_ - pv.currentSpeed_);
		}
		critLead = Math.max(minLead, critLead);
		return critLead;
	}
	
	/**
	 * Get critical lag gap for DLC (discretional lane changing)
	 * @param pv
	 * @return double critLag
	 */
	public double criticalLagDLC(Vehicle pv) {
		double critLag = 0;
		double minLag = GlobalVariables.minLagDLC;
		if (pv != null) {
			critLag = minLag + GlobalVariables.betaLagDLC01 * this.currentSpeed_
					+ GlobalVariables.betaLagDLC02 * (this.currentSpeed_ - pv.currentSpeed_);
		}
		critLag = Math.max(minLag, critLag);
		return critLag;
	}
	
	/**
	 * Whether the vehicle is on a lane
	 * @return boolean onLane
	 */
	public boolean isOnLane() {
		return onLane;
	}
	
	/**
	 * Whether the vehicle is in a link (include the intersection)
	 * @return boolean onRoad
	 */
	public boolean isOnRoad() {
		return onRoad;
	}
	
	/**
	 * Distance of the target lane for accepting a newly entered vehicle
	 * @param nextlane target Lane
	 * @return double gap
	 */
	public double entranceGap(Lane nextlane) {
		double gap = 0;
		if (nextlane != null) {
			Vehicle newleader = nextlane.lastVehicle();
			if (newleader != null) {
				gap = nextlane.getLength() - newleader.getDistanceToNextJunction()
						- newleader.length();
			} else
				gap = nextlane.getLength();
		}
		return gap;
	}

	/**
	 * Return the usable clearance from a connector exit along the vehicle's
	 * assigned physical-road route. An empty receiving lane contributes its full
	 * logical length. If that is shorter than the normal admission gap, continue
	 * through the exact connector path reachable from that lane and then through
	 * its target lane. The scan stops at the first vehicle, external reservation,
	 * occupied exclusive connector path, or topology mismatch, and stops
	 * immediately once {@code requiredClearance} is available.
	 *
	 * <p>Reaching the end of the assigned route without an obstacle is sufficient:
	 * a destination vehicle can finish its trip without requiring the final lane
	 * alone to contain its full entrance headway.</p>
	 */
	private double receivingPathClearance(Lane receivingLane, double requiredClearance) {
		if (receivingLane == null) return 0.0;
		double required = Double.isFinite(requiredClearance)
				? Math.max(0.0, requiredClearance) : 0.0;
		if (required <= 0.0) return 0.0;

		Road receivingRoad = receivingLane.getRoad();
		if (receivingRoad == null || this.nextRoad_ == null
				|| receivingRoad.getID() != this.nextRoad_.getID()
				|| this.roadPath == null || this.roadPath.size() < 2
				|| this.roadPath.get(1) == null
				|| this.roadPath.get(1).getID() != receivingRoad.getID()) {
			return Math.max(0.0, this.entranceGap(receivingLane));
		}

		double clearance = 0.0;
		Lane laneToCheck = receivingLane;
		Road roadToCheck = receivingRoad;
		int routeIndex = 1;
		while (laneToCheck != null && roadToCheck != null) {
			if (roadToCheck.getExternalLaneReservationBlocker(laneToCheck, this) != null) {
				return clearance;
			}

			Vehicle leader = laneToCheck.lastVehicle();
			if (leader != null) {
				double laneEntranceGap = laneToCheck.getLength()
						- leader.getDistanceToNextJunction() - leader.length();
				return clearance + Math.max(0.0, laneEntranceGap);
			}

			double laneLength = laneToCheck.getLength();
			if (!Double.isFinite(laneLength) || laneLength < 0.0) return clearance;
			clearance += laneLength;
			if (clearance >= required) return clearance;

			if (routeIndex + 1 >= this.roadPath.size()) {
				return required;
			}
			Road downstreamRoad = this.roadPath.get(routeIndex + 1);
			ConnectorRoad downstreamConnector = downstreamRoad == null
					|| ContextCreator.getRoadContext() == null ? null
							: ContextCreator.getRoadContext().getConnector(
									roadToCheck, downstreamRoad);
			ConnectorRoad.ConnectorPath downstreamPath = downstreamConnector == null
					? null : downstreamConnector.selectPath(laneToCheck, null);
			Lane connectorLane = downstreamConnector == null || downstreamPath == null
					? null : downstreamConnector.getLane(downstreamPath);
			if (connectorLane == null || downstreamPath.getTargetLane() == null
					|| downstreamPath.getTargetLane().getRoad() != downstreamRoad) {
				return clearance;
			}

			Vehicle connectorLeader = connectorLane.lastVehicle();
			boolean exclusiveConnectorPath = downstreamConnector
					.requiresClearPathAdmission(downstreamPath, required);
			if (exclusiveConnectorPath && (connectorLeader != null
					|| downstreamConnector.hasActiveVehicleOnPath(downstreamPath, this))) {
				return clearance;
			}
			if (connectorLeader != null) {
				double connectorEntranceGap = connectorLane.getLength()
						- connectorLeader.getDistanceToNextJunction()
						- connectorLeader.length();
				return clearance + Math.max(0.0, connectorEntranceGap);
			}

			double connectorLength = connectorLane.getLength();
			if (!Double.isFinite(connectorLength) || connectorLength < 0.0) {
				return clearance;
			}
			clearance += connectorLength;
			if (clearance >= required) return clearance;

			laneToCheck = downstreamPath.getTargetLane();
			roadToCheck = downstreamRoad;
			routeIndex++;
		}
		return clearance;
	}
	
	/**
	 * Distance between two locations
	 * @param c1
	 * @param c2
	 * @return double distance
	 */
	private double distance(Coordinate c1, Coordinate c2) {
		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		double distance = calculator.getOrthodromicDistance();
		return distance;
	}
	
	
	/**
	 * Distance between two locations
	 * @param c1
	 * @param c2
	 * @param returnVals data structure for saving the distance and angle
	 * @return double distance
	 */
	private double distance2(Coordinate c1, Coordinate c2, double[] returnVals) {
		double distance;
		double radius;
		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		distance = calculator.getOrthodromicDistance();
		radius = calculator.getAzimuth(); // the azimuth in degree, value from -180-180
		if (returnVals != null && returnVals.length == 2) {
			returnVals[0] = distance;
			returnVals[1] = radius;
		}
		if (Double.isNaN(distance)) {
			ContextCreator.logger.error("Geodetic distance is NaN for " + this);
			distance = 0.0;
			radius = 0.0;
		}
		return distance;
	}	
	
	/**
	 * Move vehicle toward a target location for certain amount of distance
	 * @param target
	 * @param distanceToTarget
	 * @param distanceTravelled
	 */
	private void move2(Coordinate target, double distanceToTarget, double distanceTravelled) {
		double p = distanceTravelled / distanceToTarget;
		if (p < 0) p = 0;
		if (p > 1) p = 1;
		double originX = this.currentCoord_.x;
		double originY = this.currentCoord_.y;
		double originZ = this.currentCoord_.z;
		this.setCurrentCoordInternal(
			(1 - p) * originX + p * target.x,
			(1 - p) * originY + p * target.y,
			(1 - p) * originZ + p * target.z);
	}
	
	/**
	 * Manually specify the acceleration
	 * @param acc
	 */
	public boolean controlVehicleAcc(double acc) {
		if(!accDecided_) {
			this.plannedAcceleration_ = acc;
			this.hasAccelerationPlan_ = true;
			this.accDecided_ = true;
			return true;
		}
		return false;
	}

	public void ensureAccelerationPlan(double fallbackAcc) {
		if (!this.hasAccelerationPlan_) {
			this.plannedAcceleration_ = Double.isNaN(fallbackAcc) ? 0.0 : fallbackAcc;
			this.hasAccelerationPlan_ = true;
		}
		this.accDecided_ = false;
	}
	
	public int getVehicleClass() {
		return this.vehicleClass;
	}
	
	public int getVehicleSensorType() {
		return this.vehicleSensorType;
	}
	
	public void setVehicleSensorType(int sensorType) {
		this.vehicleSensorType = sensorType;
	}

	public boolean isAttackVehicle() {
		return this.attackVehicle;
	}

	public void setAttackVehicle(boolean attackVehicle) {
		this.attackVehicle = attackVehicle;
	}
	
	/**
	 * Get vehicle acceleration
	 */
	public double currentAcc() {
		return this.accRate_;
	}
	
	/**
	 * Get vehicle bearing
	 */
	public double getBearing() {
		return this.bearing_;
	}

	public double getSnapshotBearing(double prevX, double prevY, Coordinate currentCoord) {
		if (currentCoord == null || Double.isNaN(prevX) || Double.isNaN(prevY)
				|| Double.isInfinite(prevX) || Double.isInfinite(prevY)
				|| Double.isNaN(currentCoord.x) || Double.isNaN(currentCoord.y)
				|| Double.isInfinite(currentCoord.x) || Double.isInfinite(currentCoord.y)) {
			return this.bearing_;
		}
		this.calculator.setStartingGeographicPoint(prevX, prevY);
		this.calculator.setDestinationGeographicPoint(currentCoord.x, currentCoord.y);
		double distance = this.calculator.getOrthodromicDistance();
		double azimuth = this.calculator.getAzimuth();
		if (distance > 0.1 && Double.isFinite(azimuth)) {
			return azimuth;
		}
		return this.bearing_;
	}

	static double stoppingDistanceMeters(double speed, double maxDeceleration) {
		if (!Double.isFinite(speed) || speed <= 0.0) return 0.0;
		double deceleration = Math.abs(maxDeceleration);
		if (!Double.isFinite(deceleration) || deceleration <= 1.0e-9) {
			return Double.POSITIVE_INFINITY;
		}
		return speed * speed / (2.0 * deceleration);
	}
	
	public void setBearing(double bearing) {
		this.bearing_ = bearing;
		this.refreshConnectorPoseState();
	}

	/**
	 * Get vehicle state (parking, doing certain type of trip, charging, etc.)
	 */
	public int getState() {
		return this.vehicleState;
	}

	public void setState(int newState) {
		this.vehicleState = newState;
	}
	
	/**
	 * Cumulative travel distance for the current trip
	 */
	public double getAccummulatedDistance() {
		return this.accummulatedDistance_;
	}
	
	/**
	 * Travel time on the current link
	 */
	public double getLinkTravelTime() {
		return linkTravelTime;
	}

	/** Identity of the current road-entry episode. */
	public synchronized long getRoadTraversalEpoch() {
		return this.roadTraversalEpoch;
	}

	/**
	 * Mark a captured road-entry episode complete exactly once. If the vehicle has
	 * already entered its next road, its new timer is left untouched.
	 */
	public synchronized boolean completeRoadTraversal(long completedEpoch) {
		if (completedEpoch <= 0L || completedEpoch > this.roadTraversalEpoch
				|| completedEpoch <= this.lastCompletedRoadTraversalEpoch) {
			return false;
		}
		this.lastCompletedRoadTraversalEpoch = completedEpoch;
		if (completedEpoch == this.roadTraversalEpoch) {
			this.linkTravelTime = 0.0;
		}
		return true;
	}

	/**
	 * Whether this vehicle is a genuine active observation for the supplied road.
	 * Off-lane arrivals, queued departures, external handoffs, and dormant vehicles
	 * remain in some linked lists temporarily but are not road traffic.
	 */
	public boolean isTravelTimeObservationEligible(Road observedRoad) {
		return observedRoad != null && this.road == observedRoad
				&& this.onRoad && this.onLane && !this.isReachDest
				&& !this.externalRoadTransition && this.lane != null
				&& this.lane.getRoad() == observedRoad
				&& !this.isDormantOnRoad();
	}

	/**
	 *  Reset link travel time once a vehicle has passed a link
	 */
	public void resetLinkTravelTime() {
		this.linkTravelTime = 0;
	}
	
	/**
	 * Get car following regime of the vehicle
	 */
	public int getRegime() {
		return regime_;
	}
	
	/**
	 * Get current route
	 */
	public List<String> getRoute(){
		List<String> res = new ArrayList<String>();
		for(Road r: this.getRoadPathSnapshot()) {
			res.add(r.getOrigID());
		}
		return res;
	}

	/** Return a detached snapshot of the currently assigned remaining route. */
	public List<Road> getRoadPathSnapshot() {
		List<Road> path = this.roadPath;
		return path == null ? new ArrayList<Road>() : new ArrayList<Road>(path);
	}
	
	/**
	 * Get number of started trips.
	 */
	public int getNumTrips() {
		return this.numTrips;
	}
	
	/**
	 * Get the vehicle's estimated remaining route distance. The route-level
	 * estimate is adjusted by the already-maintained within-road distance so the
	 * result changes with vehicle movement without recomputing the route.
	 */
	public double getDistToTravel() {
		if (this.roadPath == null || this.roadPath.isEmpty()) {
			return Math.max(0.0, this.distToTravel_);
		}
		return Math.max(0.0, this.distToTravel_ - this.distToTravelReferenceDistance_
				+ this.currentRoadDistanceToTravel());
	}
	
	/**
	 * Extend the coordMap by attaching a coordinate to its starting place
	 */
	public void extendCoordMap(Coordinate newCoord) {
		double newdist =  this.distance(newCoord, this.currentCoord_); 
		if(newdist > 0.0) {
			this.coordMap.add(0, this.currentCoord_);
			this.currentCoord_ = newCoord;
			this.distance_ += newdist;
			this.nextDistance_ = newdist;
		}
	}
	
	
	/**
	 * Print the coordMap (subroute within a road) of the vehicle
	 */
	public void printCoordMap() {
		for(Coordinate coord: this.coordMap) {
			ContextCreator.logger.info(coord);
		}
	}
	
	/**
	 * Return the list of the most recent (up to numPt) to be visited coordinates
	 */
	public ArrayList<ArrayList<Double>> getRecentCoordMap(int numPt, boolean transformCoord) {
		ArrayList<ArrayList<Double>> res = new ArrayList<ArrayList<Double>>();
		if(transformCoord) {
			for(Coordinate coord: this.coordMap) {
				if(coord != null) {
					Coordinate coord2 = new Coordinate();
					coord2.x = coord.x;
					coord2.y = coord.y;
					coord2.z = coord.z;
					ArrayList<Double> xy = new ArrayList<Double>();
					try {
						JTS.transform(coord2, coord2,
								SumoXML.getData(GlobalVariables.NETWORK_FILE).transform.inverse());
						xy.add(coord2.x);
						xy.add(coord2.y);
					} catch (TransformException e) {
						ContextCreator.logger
								.error("Coordinates transformation failed, input x: " + coord.x + " y:" + coord.y);
						e.printStackTrace();
					}
					res.add(xy);
				}
				if(res.size() >= numPt) break;
			}
		}
		else {
			for(Coordinate coord: this.coordMap) {
				ArrayList<Double> xy = new ArrayList<Double>();
				xy.add(coord.x);
				xy.add(coord.y);
				res.add(xy);
				if(res.size() >= numPt) break;
			}
		}
		return res;
	}
	
	/**
	 * Check if the current vehicle is about to enter a next Road
	 */
	public boolean aboutToEnterRoad(Road dsRoad) {
		if(isSameRoad(this.prevNextRoad_, dsRoad)) {
			if(this.prevDistance < (this.prevSpeed + 0.5 * this.maxAcceleration_) * GlobalVariables.SIMULATION_STEP_SIZE) return true;
		}
		return false;
	}

	public boolean wasPreviouslyOnRoad(Road road) {
		return isSameRoad(this.prevRoad_, road);
	}

	private boolean isSameRoad(Road r1, Road r2) {
		if(r1 == r2) return true;
		if(r1 == null || r2 == null) return false;
		return r1.getID() == r2.getID();
	}
	
	/**
	 * Record the prevState for thread safe check of aboutToEnterRoad
	 */
	public void recordPrevState() {
		this.prevDistance = this.distance_;
		this.prevSpeed = this.currentSpeed_;
		this.prevRoad_ = this.road;
		this.prevNextRoad_ = this.nextRoad_;
	}
	
	public int getRoadTraversalStoppedTicks() {
		return this.roadTraversalStoppedTicks;
	}

	public int getStopLineWaitTicks() {
		return this.stopLineWaitTicks;
	}

	public void restoreRoadTraversalPatience(int stoppedTicks, int restoredStopLineWaitTicks) {
		this.roadTraversalStoppedTicks = Math.max(0, stoppedTicks);
		this.stopLineWaitTicks = Math.max(0, restoredStopLineWaitTicks);
		this.roadPatienceLastRecoveryTick = -1;
		this.roadPatienceRecoveryResolved = false;
		this.deferredRoadRecoveryQueued = false;
		this.deferredMissedLaneRecoveryRequested = false;
		this.deferredRoadPatienceRecoveryRequested = false;
	}

	private void resetRoadTraversalPatience() {
		this.restoreRoadTraversalPatience(0, 0);
	}
	
	/* Getters and setters for save/load support */
	public Random getRandom() { return this.rand; }
	public Random getRandomRoute() { return this.rand_route_only; }
	public Random getRandomRelocate() { return this.rand_relocate_only; }
	public Random getRandomCarFollow() { return this.rand_car_follow_only; }
	
	public void setRandom(Random r) { this.rand = r; }
	public void setRandomRoute(Random r) { this.rand_route_only = r; }
	public void setRandomRelocate(Random r) { this.rand_relocate_only = r; }
	public void setRandomCarFollow(Random r) { this.rand_car_follow_only = r; }
	
	public void setSpeed(double speed) {
		this.currentSpeed_ = speed;
		this.refreshConnectorPoseState();
	}
	public void setAccRate(double acc) { this.accRate_ = acc; }
	public void setDistance(double dist) { this.distance_ = dist; }
	public void setMovingFlag(boolean flag) { this.movingFlag = flag; }
	public void setOnRoad(boolean flag) { this.onRoad = flag; }
	public void setOnLane(boolean flag) { this.onLane = flag; }
	public void setOriginID(int id) { this.originID = id; }
	public void setDestID(int id) { this.destinationID = id; }
	public void setDepTime(int t) { this.deptime = t; }
	public void setEndTime(int t) { this.endTime = t; }
	public void setAccumulatedDistance(double d) { this.accummulatedDistance_ = d; }
	public void setNumTrips(int n) { this.numTrips = n; }
	public void setLinkTravelTime(double t) { this.linkTravelTime = t; }
	public void setDestRoad(Road r) { this.destRoad_ = r; }
	public void setOriginRoad(Road r) { this.originRoad_ = r; if (r != null && this.originCoord_ == null) this.originCoord_ = r.getStartCoord(); updateLastDeparturableRoad(r); }
	public void setLastDeparturableRoad(Road r) {
		// Migrate snapshots written while connectors were incorrectly cached by
		// restoring their physical source checkpoint.
		Road checkpoint = r instanceof ConnectorRoad
				? ((ConnectorRoad) r).getSourceRoad() : r;
		this.lastDeparturableRoad_ = isDeparturableRoad(checkpoint) ? checkpoint : null;
	}
	public void setActivityPlan(ArrayList<Plan> plan) { this.activityPlan = plan; }
	public boolean getMovingFlag() { return this.movingFlag; }

	public List<Road> getRoadPath() { return this.roadPath; }
	public void setRoadPath(List<Road> path) { this.roadPath = path; }
	public void setDistToTravel(double d) {
		this.distToTravel_ = d;
		this.distToTravelReferenceDistance_ = this.currentRoadDistanceToTravel();
	}
	public boolean isAtOrigin() { return this.atOrigin; }
	public void setAtOrigin(boolean v) { this.atOrigin = v; }
	public boolean isReachDest() { return this.isReachDest; }
	public void setReachDest(boolean v) { this.isReachDest = v; }
	public void setNextRoadDirectly(Road r) { this.nextRoad_ = r; }
}
