package mets_r.mobility;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.facility.ConnectorRoad;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.routing.RouteContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

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
	private static final double MANDATORY_LANE_CHANGE_HOLD_BUFFER_METERS = 0.1;
	private static final double AHMED_MANDATORY_LC_HOLD_MARGIN_METERS = 0.01;
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
	private LinkedList<Double> accPlan_; 
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
	private int cachedProjectionSegmentIdx_ = -1;
	private Coordinate cachedProjectionCoord_ = null;
	
	// For adaptive network partitioning
	private int Nshadow; // Number of current shadow roads in the path
	private ArrayList<Road> futureRoutingRoad;
	protected ArrayList<Plan> activityPlan; // A set of zone for the vehicle to visit
	
	// For calculating vehicle coordinates
	GeodeticCalculator calculator = new GeodeticCalculator(ContextCreator.getLaneGeography().getCRS());
	
	// For solving the grid-lock issue
	private int stuckTime = 0;
	private int missedLaneRecoveryRoadID = -1;
	private int missedLaneRecoveryLaneID = -1;
	private int missedLaneRecoveryPlannedRoadID = -1;
	private int missedLaneRecoveryLastAttemptTick = -1;
	private boolean missedLaneRecoveryInitialAttempted;
	private boolean missedLaneRecoveryFinalAttempted;
	private boolean missedLaneGridlockRecoveryAttempted;
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
		this.accPlan_ = new LinkedList<Double>();
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
		this.externalTransitionSourceRoad = null;
		this.externalTransitionTargetRoad = null;
		this.externalTransitionTargetLane = null;
		this.coordMap = new ArrayList<Coordinate>();
		this.originCoord_ = null;
		this.originRoad_ = null;
		this.destRoad_ = null;
		this.lastDeparturableRoad_ = null;
		this.accummulatedDistance_ = 0;
		this.roadPath = null;
		this.linkTravelTime = 0;

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
		Lane firstlane = road.firstLane(); // First lane is the right lane, which is closest to the outside street
		return enterNetwork(road, firstlane, false);
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
		Lane firstlane = road.firstLane();
		return enterNetwork(road, firstlane, true);
	}

	public boolean enterNetworkByControl(Road road, Lane lane) {
		return enterNetwork(road, lane, true);
	}

	private synchronized boolean enterNetwork(Road road, Lane lane, boolean allowCoSimEntry) {
		// Sanity check
		if (road == null || lane == null) return false;
		if(lane.getRoad() != road) return false;
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

	    if (road.getControlType() != Road.COSIM) {
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
	                    if (v.getDistanceToNextJunction() < 0.5 * (v.currentSpeed()/ v.maxDeceleration_) * (v.currentSpeed()/ v.maxDeceleration_)) {
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

		                if (ContextCreator.getCityContext().getDistance(c1, c2) < 0.5 * (v.currentSpeed()/ v.maxDeceleration_) * (v.currentSpeed()/ v.maxDeceleration_)) {
		                    canEnter = false;
		                    break;
		                }
		            }
	            }
	            
	        }
	    }

	    if (!canEnter) return false;

	    // Admission is committed while holding this vehicle's monitor. Remove the
	    // winning entry and any stale duplicates before publishing road membership.
	    ContextCreator.getRoadContext().removeVehicleFromEnteringQueues(this);
	    this.currentSpeed_ = 0.0;
	    this.distance_ = 0;
	    this.setPreviousEpochCoord(lane.getStartCoord());
	    this.setCurrentCoord(lane.getStartCoord());
	    this.appendToLane(lane);
	    this.appendToRoad(road);

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
		else { // The vehicle is on road, we just need to reroute it
			ContextCreator.getRoadContext().markRoadActive(this.road);
			this.rerouteAndSetNextRoad(); // refresh the CoordMap
		}
	}

	private Road resolveOnRoadDepartureRoad(Road requestedRoad) {
		if (isDeparturableRoad(this.road)) {
			return this.road;
		}
		if (isDeparturableRoad(this.lastDeparturableRoad_)) {
			ContextCreator.logger.warn("Vehicle " + this.getID() + " is on non-departurable road "
					+ roadLabel(this.road) + "; departing from last departurable road "
					+ roadLabel(this.lastDeparturableRoad_) + ".");
			return this.lastDeparturableRoad_;
		}
		if (isDeparturableRoad(requestedRoad)) {
			ContextCreator.logger.warn("Vehicle " + this.getID() + " is on non-departurable road "
					+ roadLabel(this.road) + " and has no cached departurable road; using requested road "
					+ roadLabel(requestedRoad) + ".");
			return requestedRoad;
		}
		ContextCreator.logger.warn("Vehicle " + this.getID() + " is departing from non-departurable road "
				+ roadLabel(this.road) + " and has no cached departurable road.");
		return this.road != null ? this.road : requestedRoad;
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
		if (requestedRoad != null) {
			ContextCreator.logger.warn("Vehicle " + this.getID() + " requested departure road "
					+ roadLabel(requestedRoad) + " is not departurable and no cached departurable road is available.");
		}
		return requestedRoad;
	}

	private boolean isDeparturableRoad(Road road) {
		return road != null && road.canBeOrigin();
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
		if (this.stuckTime < GlobalVariables.DEBUG_STUCK_VEHICLE_MIN_TIME) return false;
		int interval = Math.max(1, GlobalVariables.DEBUG_STUCK_VEHICLE_LOG_INTERVAL);
		int firstLogTick = Math.max(0, GlobalVariables.DEBUG_STUCK_VEHICLE_MIN_TIME);
		return this.stuckTime == firstLogTick || (this.stuckTime > firstLogTick
				&& (this.stuckTime - firstLogTick) % interval == 0);
	}

	private boolean shouldLogStuckTransferSuccess() {
		return GlobalVariables.DEBUG_STUCK_VEHICLE
				&& this.stuckTime >= GlobalVariables.DEBUG_STUCK_VEHICLE_MIN_TIME;
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
				+ ":stuck=" + vehicle.getStuckTime();
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
				.append(" stuckTicks=").append(this.stuckTime)
				.append(" stuckSeconds=").append(formatDebugDouble(this.stuckTime * GlobalVariables.SIMULATION_STEP_SIZE))
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

	private void logStuckTransferSuccess(String reason, Lane targetLane, Road targetRoad) {
		if (!shouldLogStuckTransferSuccess()) return;
		ContextCreator.logger.info("STUCK_TRANSFER_SUCCESS"
				+ " tick=" + ContextCreator.getCurrentTick()
				+ " veh=" + this.getID()
				+ " class=" + this.vehicleClass
				+ " state=" + this.vehicleState
				+ " stuckTicks=" + this.stuckTime
				+ " stuckSeconds=" + formatDebugDouble(this.stuckTime * GlobalVariables.SIMULATION_STEP_SIZE)
				+ " reason=" + reason
				+ " fromRoad=" + roadLabel(this.road)
				+ " fromLane=" + laneLabel(this.lane)
				+ " targetRoad=" + roadLabel(targetRoad)
				+ " targetLane=" + laneLabel(targetLane)
				+ " originZone=" + this.originID
				+ " destZone=" + this.destinationID);
	}

	private synchronized void queueDepartureFromRoad(Road departureRoad) {
		this.clearShadowImpact();
		this.removeFromCurrentLane();
		this.removeFromCurrentRoad();
		this.onLane = false;
		this.onRoad = false;
		this.accRate_ = 0;
		this.accDecided_ = false;
		this.accPlan_.clear();
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
			this.coordMap = new ArrayList<Coordinate>();
		} else {
			this.coordMap.clear();
		}
		this.distance_ = 0.0;
		this.nextDistance_ = 0.0;
		this.currentSegmentIdx_ = 0;
		this.currentLaneSlope_ = 0.0;
		this.cachedProjectionLane_ = null;
		this.cachedProjectionDistance_ = 0.0;
		this.cachedProjectionSegmentIdx_ = -1;
		this.cachedProjectionCoord_ = null;
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
		if (isDeparturableRoad(this.lastDeparturableRoad_)) {
			return this.lastDeparturableRoad_;
		}
		Road nearbyRoad = ContextCreator.getCityContext().findRoadAtCoordinates(this.getCurrentCoord(), false, this.road);
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
							+ " for vehicle " + this.getID() + "; ending this leg without entering a bad route.");
					this.nextRoad_ = null;
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
					+ "; ending this leg without entering a bad route.");
			this.roadPath = null;
			this.nextRoad_ = null;
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
	 * Compute the new distance for a vehicle to move a new lane.
	 * Results are cached per lane within a single calcState() call.
	 */
	public double distanceInNewLane(Lane plane) {
		if(this.lane == plane) {
			return this.distance_;
		}
		if (plane == cachedProjectionLane_) {
			return cachedProjectionDistance_;
		}
		Coordinate currCoord = this.getCurrentCoord();
	    ArrayList<Coordinate> coords = plane.getCoords();
	    double newDistance = 0;
	    int segIdx = -1;
	    double projectedParam = 0.0;
	    double projectedSegmentLen = 0.0;
	    Coordinate projectedCoord = null;

	    for (int i = coords.size() - 1; i > 0; i--) {
	        Coordinate a = coords.get(i);
	        Coordinate b = coords.get(i - 1);

	        double dx = a.x - b.x;
	        double dy = a.y - b.y;
	        double lenSq = dx * dx + dy * dy;
	        double segmentLen = this.distance(a, b);

	        if (lenSq > 0) {
	            double apx = currCoord.x - b.x;
	            double apy = currCoord.y - b.y;
	            double param = (apx * dx + apy * dy) / lenSq;
	            if (param >= 0.0 && param <= 1.0) {
	                double projectedDistance = newDistance + (1.0 - param) * segmentLen;
	                if (Math.abs(this.distance_ - projectedDistance) < 25.0) {
	                    segIdx = i;
	                    newDistance = projectedDistance;
	                    projectedParam = param;
	                    projectedSegmentLen = segmentLen;
	                    projectedCoord = this.projectCoordinateOnSegment(b, a, param);
	                    break;
	                }
	            }
	        }
	        newDistance += segmentLen;
	    }
	    
	    if(segIdx < 0) { // Fallback: Pick the segment whose clamped nearest point is closest to the vehicle.
	    	double minDist = Double.MAX_VALUE;
	        for (int i = coords.size() - 1; i > 0; i--) {
	            Coordinate a = coords.get(i);
	            Coordinate b = coords.get(i - 1);
	            double dx = a.x - b.x;
	            double dy = a.y - b.y;
	            double lenSq = dx * dx + dy * dy;
	            if (lenSq > 0) {
	                double apx = currCoord.x - b.x;
	                double apy = currCoord.y - b.y;
	                double param = Math.max(0.0, Math.min(1.0, (apx * dx + apy * dy) / lenSq));
	                double closestX = b.x + param * dx;
	                double closestY = b.y + param * dy;
	                double d = Math.hypot(currCoord.x - closestX, currCoord.y - closestY);
	                if (d < minDist) {
	                    minDist = d;
	                    segIdx = i;
	                    projectedParam = param;
	                    projectedSegmentLen = this.distance(a, b);
	                    projectedCoord = this.projectCoordinateOnSegment(b, a, param);
	                }
	            }
	        }
	        if (segIdx >= 0) {
		        newDistance = 0;
		        for (int i = coords.size() - 1; i > segIdx; i--) {
		            newDistance += this.distance(coords.get(i), coords.get(i - 1));
		        }
		        newDistance += (1.0 - projectedParam) * projectedSegmentLen;
	        } else {
	            newDistance = this.distance_;
	        }
	    }
	    
	    
	    cachedProjectionLane_ = plane;
	    cachedProjectionDistance_ = newDistance;
	    cachedProjectionSegmentIdx_ = segIdx;
	    cachedProjectionCoord_ = projectedCoord;
	    
	    return newDistance;
	}

	private Coordinate projectCoordinateOnSegment(Coordinate upstream, Coordinate downstream, double param) {
		double clampedParam = Math.max(0.0, Math.min(1.0, param));
		double upstreamZ = Double.isNaN(upstream.z) ? 0.0 : upstream.z;
		double downstreamZ = Double.isNaN(downstream.z) ? upstreamZ : downstream.z;
		return new Coordinate(
				upstream.x + clampedParam * (downstream.x - upstream.x),
				upstream.y + clampedParam * (downstream.y - upstream.y),
				upstreamZ + clampedParam * (downstreamZ - upstreamZ));
	}

	/**
	 * This function change the lane of a vehicle regardless it is MLC or DLC state.
	 * The vehicle change lane when its lead and lag gaps are acceptable. This will
	 * not change the speed of the vehicle, the information updated in this function
	 * function is as follow: remove the vehicle from old lane and add to new lane,
	 * re-assign the leading and trailing sequence of the vehicle, update the to-visit
	 * coordinate sequences.
	 * @return true if the vehicle was moved to a different lane
	 **/
	public boolean changeLane(Lane plane) {
		if (this.hasActiveConnectorReservation()) return false;
		if (plane == null) {
			return false;
		}
		Lane oldLane = this.lane;
		if (plane == oldLane) {
			return false;
		}
		double newDistance = this.distanceInNewLane(plane);
		int segIdx = (plane == cachedProjectionLane_) ? cachedProjectionSegmentIdx_ : -1;
		Coordinate projectedCoord = (plane == cachedProjectionLane_) ? cachedProjectionCoord_ : null;

		ArrayList<Coordinate> coords = plane.getCoords();
		ArrayList<Coordinate> newCoordMap = new ArrayList<>();
		if (segIdx > 0) {
			for (int j = segIdx; j < coords.size(); j++) {
				newCoordMap.add(coords.get(j));
			}
		}

		if(newCoordMap.size() == 0) {
			return false;
		}
		if (projectedCoord == null) {
			return false;
		}

		if(this.distance_ > GlobalVariables.NO_LANECHANGING_LENGTH) {
			this.distance_ = newDistance;
			this.setCurrentCoord(projectedCoord);
			this.coordMap.clear();
			this.coordMap.addAll(newCoordMap);
			this.updateBearingAndNextDistanceToCoordMap();

			this.removeFromCurrentLane();
			this.insertToLane(plane);
			currentSegmentIdx_ = segIdx - 1;
			currentLaneSlope_ = plane.getSegmentSlope(currentSegmentIdx_);
			return this.lane != oldLane && this.lane == plane;
		}
		return false;
	}

	private void updateBearingAndNextDistanceToCoordMap() {
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
			this.nextDistance_ = distanceToFirst;
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
				accDist -= distance(coords.get(i), coords.get(i+1));
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
			this.updateBearingAndNextDistanceToCoordMap();
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
				double segmentDistance = ContextCreator.getCityContext().getDistance(upstream, downstream);
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
					this.updateBearingAndNextDistanceToCoordMap();
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
				this.updateBearingAndNextDistanceToCoordMap();
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
		if (this.hasActiveConnectorReservation()) return;
		if (this.lane == null) return;
		if (!this.prepareLaneChangeTarget()) return;
		if (this.lane == null || this.road == null || !this.isOnLane()) return;
		this.cachedProjectionLane_ = null;
		if (tickcount % 10 == 0) {
			this.desiredSpeed_ = this.lane.getRandomFreeSpeed(rand_car_follow_only.nextGaussian());
		}
		if (this.road.getNumberOfLanes() > 1 && this.isOnLane() && (this.distance_ >= GlobalVariables.NO_LANECHANGING_LENGTH)) {
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
		
		accPlan_.add(acc);
		
		if (Double.isNaN(accRate_)) {
			ContextCreator.logger.error("NaN acceleration rate for " + this);
		}
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
			
			if (nextJunction != null && nextJunction.getDelay(this.road.getID(), this.nextRoad_.getID()) > 0) { // edge case 1: brake for the red light
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
	 * Get the front vehicle
	 * @return v The front vehicle, null for no vehicle ahead
	 */
	public Vehicle vehicleAhead() {
		if (leading_ != null) {
			return leading_;
		}
		else {
			return null;
		}
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
		double headwayDistance;
		if (front != null && front.getLane() != null && this.lane != null) { /* vehicle ahead */
			if (this.lane.getID() == front.getLane().getID()) { /* same lane */
				headwayDistance = this.distance_ - front.getDistanceToNextJunction() - front.length();
				
			} else if (this.lane.getRoad() == front.getLane().getRoad()) { /* adjacent lane on same road */
				headwayDistance = this.distance_ - front.getDistanceToNextJunction() - front.length();
			} else { /* front vehicle is in a downstream road */
				headwayDistance = this.distance_ + front.getLane().getLength()
						- front.getDistanceToNextJunction() - front.length();
			}
		} else { /* no vehicle ahead. */
			headwayDistance = Double.MAX_VALUE;
		}

		return Math.max(0.0, headwayDistance);
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
		if (this.distFraction() < 0.5) {
			// Halfway to the downstream intersection, only mantatory LC allowed, check the
			// correct lane
			if (!this.isInCorrectLane()) { // change lane if not in correct
				// lane
				Lane tarLane = this.tempLane();
				if (tarLane != null) {
					return this.mandatoryLC(tarLane);
				}
			}
		} else if(this.distFraction() < 1.0){
			if (this.distFraction() > 0.75) {
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
		double gap = Math.max(0.0, projectedDistance - leadVehicle.distance_ - leadVehicle.length());
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
		return Math.max(GlobalVariables.NO_LANECHANGING_LENGTH,
				currentSpeed_ * Math.max(GlobalVariables.SIMULATION_STEP_SIZE, GlobalVariables.LC2013_LOOKAHEAD_TIME));
	}

	private double lc2013StrategicLookaheadDistance(Lane targetLane) {
		double configured = Math.max(GlobalVariables.NO_LANECHANGING_LENGTH,
				GlobalVariables.LC2013_STRATEGIC_LOOKAHEAD);
		double dynamic = lc2013LookaheadDistance() * Math.max(0.0, GlobalVariables.LC2013_STRATEGIC_PARAM);
		double leftFactor = targetLane == this.leftLane() ? Math.max(0.0, GlobalVariables.LC2013_LOOKAHEAD_LEFT) : 1.0;
		return Math.max(dynamic, configured) * leftFactor;
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
			this.accPlan_.clear();
			this.movingFlag = false;
			return;
		}

		/* Load the acc decision */
		if (accPlan_.isEmpty()) {
			ContextCreator.logger.debug("Vehicle.move missing acceleration plan; using zero acceleration. tick="
					+ ContextCreator.getCurrentTick() + " vehicle=" + this.getID()
					+ " road=" + (this.road == null ? -1 : this.road.getID())
					+ " lane=" + (this.lane == null ? -1 : this.lane.getID())
					+ " onLane=" + this.onLane + " state=" + this.vehicleState);
			accRate_ = 0.0;
			this.accDecided_ = false;
		} else {
			accRate_ = accPlan_.pop();
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

			// Update vehicle coords
			lastStepMove_ = updateCoordByDx(dx);
		}
		else {
			ContextCreator.getVehicleContext().addTransferringVehicles(this);
		}
		
		// Update the position of vehicles, 0<=distance_<=lane.length()
		if (this.distance_ < 0) {
			this.distance_ = 0;
		}
		if (lastStepMove_ > 0.001) {
			this.accummulatedDistance_ += lastStepMove_; // Record the moved distance
			this.movingFlag = true;
			this.stuckTime = 0;
		} else {
			this.movingFlag = false;
			this.stuckTime += 1; // time of getting stuck on road
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

		this.recoverMissedLaneTransition();
		return this.nextRoad_ == null || (this.nextLane_ != null
				&& this.nextLane_.getRoad() == this.nextRoad_);
	}

	private double applyMandatoryLaneChangeJunctionHold(double requestedDx) {
		if (!this.hasIncompleteMandatoryLaneChange()) {
			return requestedDx;
		}

		double laneLength = this.lane.getLength();
		double holdDistance = this.mandatoryLaneChangeHoldDistance(laneLength);
		boolean noUsableLaneChangingZone = !Double.isFinite(laneLength)
				|| laneLength <= GlobalVariables.NO_LANECHANGING_LENGTH
				|| this.distance_ <= GlobalVariables.NO_LANECHANGING_LENGTH
				|| this.road.getNumberOfLanes() <= 1
				|| !this.currentRoadHasLaneConnectingTo(this.nextLane_)
				|| !Double.isFinite(holdDistance);
		if (noUsableLaneChangingZone || this.stuckTime >= GlobalVariables.MAX_STUCK_TIME) {
			this.recoverMissedLaneTransition();
			if (!this.hasIncompleteMandatoryLaneChange()) {
				return requestedDx;
			}
		}

		// changeLane() uses a strict '>' test, so stop just upstream of the
		// configured exclusion zone. On a short/impossible road recovery is retried
		// in place instead of allowing an illegal endpoint transition.
		if (noUsableLaneChangingZone) {
			return 0.0;
		}
		double distanceUntilHold = Math.max(0.0, this.distance_ - holdDistance);
		return Math.min(Math.max(0.0, requestedDx), distanceUntilHold);
	}

	private double mandatoryLaneChangeHoldDistance(double laneLength) {
		double preferredHold = GlobalVariables.NO_LANECHANGING_LENGTH
				+ MANDATORY_LANE_CHANGE_HOLD_BUFFER_METERS;
		if ("LC2013".equals(GlobalVariables.LANE_CHANGING_MODEL)) {
			return preferredHold;
		}
		if (!Double.isFinite(laneLength)) {
			return Double.NaN;
		}

		// Ahmed only evaluates mandatory changes in the downstream half of a
		// lane. Keep strict margins on both model boundaries: changeLane() needs
		// distance > NO_LANECHANGING_LENGTH and Ahmed needs distFraction < 0.5.
		double lowestUsableHold = GlobalVariables.NO_LANECHANGING_LENGTH
				+ AHMED_MANDATORY_LC_HOLD_MARGIN_METERS;
		double highestUsableHold = 0.5 * laneLength
				- AHMED_MANDATORY_LC_HOLD_MARGIN_METERS;
		if (highestUsableHold < lowestUsableHold) {
			return Double.NaN;
		}
		return Math.min(preferredHold, highestUsableHold);
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
				this.setCurrentCoord(this.coordMap.get(0));
				this.coordMap.remove(0);
				if (this.coordMap.isEmpty()) {
					this.distance_ = 0;
					this.setCurrentCoord(this.getLane().getEndCoord());
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
					this.move2(this.getCurrentCoord(), this.coordMap.get(0), nextDistance_, distToMove);
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
			this.updateBearingAndNextDistanceToCoordMap();
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
		return this.changeRoad(false);
	}

	/**
	 * Change road while optionally preserving an externally asserted exact lane.
	 * Exact mode keeps all normal gates but disables reroute and alternate-lane
	 * gridlock recovery.
	 */
	public synchronized boolean changeRoad(boolean exactTargetLane) {
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
			if (this.nextLane_ == null) {
				this.assignNextLane();
			}
			if (this.nextLane_ == null || !this.isDirectLaneTransition(this.lane, this.nextLane_)) {
				if (exactTargetLane) return false;
				Lane missedTargetLane = this.nextLane_;
				boolean recovered = this.recoverMissedLaneTransition();
				logStuckTransferFailure(recovered ? "MISSED_LANE_REROUTED" : "NO_DIRECT_TARGET_LANE",
						this.nextJunction(), null, false, true, missedTargetLane, Double.NaN,
						1.2 * this.length(), null);
				// Route recovery only chooses the new legal successor. Retrying on the
				// next tick makes the repaired movement pass through the normal signal,
				// conflict, reservation, and entrance-gap gates below.
				return false;
			}
			Junction nextJunction = this.nextJunction();
			Signal signal = null;
			boolean movable = false;
			if (nextJunction == null) {
				movable = true;
			} else { // nextRoad data is consistent
				switch(nextJunction.getControlType()) {
					case Junction.NoControl:
						movable = true;
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
						if(nextJunction.getDelay(this.road.getID(), this.nextRoad_.getID()) <= this.stuckTime)
							movable = true;
						break;
					case Junction.Yield:
						if(nextJunction.getDelay(this.road.getID(), this.nextRoad_.getID()) <= this.stuckTime)
							movable = true;
						break;
					default:
						movable = true;
						break;
				}
			}

			double requiredGap = 1.2 * this.length();
			double targetGapForDebug = this.entranceGap(nextLane_);
			boolean sourceRoadControlledByCosim = this.road.getControlType() == Road.COSIM;
			if(!sourceRoadControlledByCosim && !movable) {
				logStuckTransferFailure("CONTROL_GATE", nextJunction, signal, movable, true,
						this.nextLane_, targetGapForDebug, requiredGap, null);
				return false;
			}

			Vehicle conflictBlocker = null;
			boolean conflictFree = true;
			if(!sourceRoadControlledByCosim) {
				conflictBlocker = this.nextRoad_.enterRoadConflictBlocker(this.road, this);
				conflictFree = conflictBlocker == null;
			}
			if(!conflictFree) {
				logStuckTransferFailure("CONFLICT_GATE", nextJunction, signal, movable, conflictFree,
						this.nextLane_, Double.NaN, requiredGap, conflictBlocker);
				return false;
			}

			// Check if the target road has space
			if(this.nextRoad_.getControlType() == Road.COSIM) {
				Vehicle reservationBlocker = this.nextRoad_.getExternalLaneReservationBlocker(nextLane_, this);
				if (reservationBlocker != null) {
					logStuckTransferFailure("COSIM_CONNECTOR_RESERVATION", nextJunction, signal, movable,
							conflictFree, this.nextLane_, Double.NaN, requiredGap, reservationBlocker);
					return false;
				}
				// For cosim road, get the last vehicle, check whether the distance is greater than 1.2 * this.length
				Vehicle lastVeh = nextLane_.lastVehicle();
				if((lastVeh == null)) {
					logStuckTransferSuccess("COSIM_EMPTY_TARGET", nextLane_, nextRoad_);
					return this.executeRoadTransition(nextLane_, nextRoad_);
				}
				else {
					Coordinate c1 = lastVeh.getCurrentCoord();
					// Get dist between the coord and the begining coord of the lane
					Coordinate c2 = nextLane_.getStartCoord();
					double cosimEntranceGap = ContextCreator.getCityContext().getDistance(c1, c2);
					if(cosimEntranceGap >= requiredGap){
						logStuckTransferSuccess("COSIM_TARGET_GAP", nextLane_, nextRoad_);
						return this.executeRoadTransition(nextLane_, nextRoad_);
					}
					logStuckTransferFailure("COSIM_ENTRANCE_GAP", nextJunction, signal, movable, conflictFree,
							this.nextLane_, cosimEntranceGap, requiredGap, null);
				}
			}
			else {
				double targetGap = this.entranceGap(nextLane_);
				if (targetGap >= requiredGap) {
					logStuckTransferSuccess("TARGET_LANE_GAP", nextLane_, nextRoad_);
					return this.executeRoadTransition(nextLane_, nextRoad_);
				}
				else if (this.stuckTime >= GlobalVariables.MAX_STUCK_TIME) { // addressing gridlock 
					if (this.lane == null) {
						logStuckTransferFailure("NO_CURRENT_LANE_FOR_GRIDLOCK_ESCAPE", nextJunction, signal,
								movable, conflictFree, this.nextLane_, targetGap, requiredGap, null);
					} else {
						// A different lane on the same planned road uses the signal and
						// conflict decisions already evaluated above. A successor on a
						// different road first repairs the route and waits until the next
						// tick, when that movement gets its own complete set of gates.
						if (exactTargetLane) {
							logStuckTransferFailure("ASSERTED_LANE_ENTRY_BLOCKED", nextJunction, signal,
									movable, conflictFree, this.nextLane_, targetGap, requiredGap, null);
							return false;
						}
						for (Lane dnlane : this.legalSuccessorLanes()) {
							if (dnlane == this.nextLane_ || dnlane.getRoad() != this.nextRoad_) continue;
							double altGap = this.entranceGap(dnlane);
							if (altGap >= requiredGap) {
								boolean transitioned = this.executeRoadTransition(dnlane, this.nextRoad_);
								if (transitioned) {
									logStuckTransferSuccess("GRIDLOCK_ESCAPE_ALTERNATE_LANE", dnlane,
											this.nextRoad_);
								}
								return transitioned;
							}
						}
						if (this.recoverGridlockedTransition(requiredGap)) {
							return false;
						}
						logStuckTransferFailure("ENTRANCE_GAP_AFTER_GRIDLOCK_ESCAPE", nextJunction, signal,
								movable, conflictFree, this.nextLane_, targetGap, requiredGap, null);
					}
				} else {
					logStuckTransferFailure("ENTRANCE_GAP", nextJunction, signal, movable, conflictFree,
							this.nextLane_, targetGap, requiredGap, null);
				}
			}
			return false;
		}
		else{
			this.reachDest();
			return true;
		}
	}
	
	/**
	 * Start or complete a road transition. Any boundary touching a CoSim road
	 * hands connector motion to the external simulator: the vehicle becomes a
	 * target-road macro member immediately, but joins the target lane only after
	 * {@link #tryCommitExternalRoadTransition()} observes an external pose on it.
	 *
	 * @return true when the transition was accepted; false when its target-lane
	 *         reservation could not be acquired or the request is inconsistent
	 */
	public synchronized boolean executeRoadTransition(Lane targetLane, Road targetRoad) {
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
				|| this.nextRoad_ == null || this.nextRoad_.getID() != targetRoad.getID()
				|| !this.isDirectLaneTransition(sourceLane, targetLane)) {
			return false;
		}
		if (targetRoad.getExternalLaneReservationBlocker(targetLane, this) != null) {
			return false;
		}

		ConnectorRoad connector = ContextCreator.getRoadContext()
				.getConnector(sourceRoad, targetRoad);
		if (connector == null
				|| !ContextCreator.getRoadContext().tryEnterConnector(connector, this)) {
			return false;
		}
		boolean transitioned = false;
		try {
			if (sourceRoad.getControlType() == Road.COSIM
					|| targetRoad.getControlType() == Road.COSIM) {
				transitioned = this.beginExternalRoadTransition(
						sourceRoad, targetLane, targetRoad);
				if (transitioned) {
					this.currentConnector = connector;
					this.connectorFrontCleared = false;
					ContextCreator.getRoadContext()
							.activateConnectorVehicle(connector, this);
					ContextCreator.getRoadContext()
							.updateConnectorVehicleState(connector, this);
				}
				return transitioned;
			}
			this.enterNextLane(targetLane);
			this.removeFromCurrentLane();
			this.removeFromCurrentRoad();
			this.appendToLane(targetLane);
			this.appendToRoad(targetRoad);
			this.currentConnector = connector;
			this.connectorFrontCleared = false;
			ContextCreator.getRoadContext().activateConnectorVehicle(connector, this);
			this.updateNativeConnectorMembership();
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
		if (connector == null) return;
		ContextCreator.getRoadContext().leaveConnector(connector, this);
		this.currentConnector = null;
		this.connectorFrontCleared = false;
	}

	public ConnectorRoad getCurrentConnector() {
		return this.currentConnector;
	}

	/**
	 * True while the vehicle's front reference point is physically inside the
	 * connector. Intersection occupancy may remain reserved briefly afterward
	 * until the rear of the vehicle clears the junction.
	 */
	public boolean isOnConnector() {
		return this.currentConnector != null && !this.connectorFrontCleared;
	}

	public boolean hasActiveConnectorReservation() {
		return this.currentConnector != null;
	}

	public double getConnectorDistanceRemaining() {
		if (!this.isOnConnector() || this.lane == null) return Double.NaN;
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
		double connectorLength = Math.max(0.0, this.currentConnector.getLength());
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
		for (int i = 0; i < this.roadPath.size() - 1; i++) {
			Road source = this.roadPath.get(i);
			Road target = this.roadPath.get(i + 1);
			ConnectorRoad connector = ContextCreator.getRoadContext()
					.getConnector(source, target);
			if (connector == null) continue;
			double value = travelTime ? connector.getTravelTime() : connector.getLength();
			if (Double.isFinite(value) && value > 0.0) total += value;
		}
		return total;
	}

	/** Immutable vehicle-local state required to restore an active connector. */
	public static final class ConnectorPersistenceSnapshot {
		private final ConnectorRoad connector;
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
				boolean frontCleared, boolean externalTransition,
				Road externalSourceRoad, Road externalTargetRoad, Lane targetLane,
				ArrayList<Coordinate> remainingCoordinates, double distance,
				double nextDistance, int segmentIndex, double laneSlope) {
			this.connector = connector;
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
		Lane targetLane = this.externalRoadTransition
				? this.externalTransitionTargetLane : this.lane;
		if (targetLane == null || targetLane.getRoad() != connector.getTargetRoad()) {
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
		return new ConnectorPersistenceSnapshot(connector,
				this.connectorFrontCleared, this.externalRoadTransition,
				this.externalTransitionSourceRoad, this.externalTransitionTargetRoad,
				targetLane, remaining, this.distance_, this.nextDistance_,
				this.currentSegmentIdx_, this.currentLaneSlope_);
	}

	/**
	 * Restore the physical path and reservation for a vehicle already attached to
	 * the saved connector's target road. Normal admission checks are deliberately
	 * bypassed because every saved occupant belongs to the same atomic snapshot.
	 */
	public synchronized void restoreConnectorPersistenceState(
			ConnectorRoad connector, boolean frontCleared, boolean externalTransition,
			Road externalSourceRoad, Road externalTargetRoad, Lane targetLane,
			List<Coordinate> remainingCoordinates, double restoredDistance,
			double restoredNextDistance, int restoredSegmentIndex,
			double restoredLaneSlope, Coordinate restoredPose,
			double restoredBearing) {
		if (connector == null || targetLane == null || restoredPose == null
				|| this.road != connector.getTargetRoad()
				|| targetLane.getRoad() != connector.getTargetRoad()
				|| this.currentConnector != null || this.externalRoadTransition
				|| !Double.isFinite(restoredDistance) || restoredDistance < 0.0
				|| !Double.isFinite(restoredNextDistance) || restoredNextDistance < 0.0) {
			throw new IllegalArgumentException("Invalid saved connector state for vehicle "
					+ this.getID());
		}
		if (externalTransition) {
			if (this.lane != null || externalSourceRoad != connector.getSourceRoad()
					|| externalTargetRoad != connector.getTargetRoad()
					|| !externalTargetRoad.tryReserveExternalLane(targetLane, this)) {
				throw new IllegalStateException("Cannot restore external connector state for vehicle "
						+ this.getID());
			}
		} else if (this.lane != targetLane) {
			throw new IllegalStateException("Saved connector lane does not match vehicle "
					+ this.getID());
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
		this.connectorFrontCleared = frontCleared;
		this.externalRoadTransition = externalTransition;
		this.externalTransitionSourceRoad = externalTransition ? externalSourceRoad : null;
		this.externalTransitionTargetRoad = externalTransition ? externalTargetRoad : null;
		this.externalTransitionTargetLane = externalTransition ? targetLane : null;
		this.onRoad = true;
		this.onLane = !externalTransition;

		try {
			ContextCreator.getRoadContext().restoreConnectorVehicle(
					connector, this, !frontCleared);
			if (externalTransition) {
				ContextCreator.getVehicleContext().registerExternalRoadTransition(this);
			}
		} catch (RuntimeException ex) {
			if (externalTransition) {
				externalTargetRoad.releaseExternalLaneReservation(targetLane, this);
			}
			this.currentConnector = null;
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
		this.accPlan_.clear();
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
		this.updateBearingAndNextDistanceToCoordMap();
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

		private ExternalRoadTransitionSnapshot(Vehicle vehicle, boolean pending,
				Road sourceRoad, Road targetRoad, Lane targetLane) {
			this.vehicle = vehicle;
			this.pending = pending;
			this.sourceRoad = sourceRoad;
			this.targetRoad = targetRoad;
			this.targetLane = targetLane;
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
	}

	/** Capture all pending-transition fields while holding only this vehicle. */
	public synchronized ExternalRoadTransitionSnapshot getExternalRoadTransitionSnapshot() {
		boolean pending = this.externalRoadTransition;
		return new ExternalRoadTransitionSnapshot(this, pending,
				pending ? this.externalTransitionSourceRoad : null,
				pending ? this.externalTransitionTargetRoad : null,
				pending ? this.externalTransitionTargetLane : null);
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
		if (alternateLane
				&& !targetRoad.tryReserveExternalLaneForNativeRelease(releaseLane, this)) {
			return false;
		}
		this.externalTransitionTargetLane = releaseLane;

		Lane targetLane = releaseLane;
		double laneLength = targetLane.getLength();
		if (!Double.isFinite(laneLength) || laneLength < 0.0) {
			this.externalTransitionTargetLane = originalTargetLane;
			if (alternateLane) targetRoad.releaseExternalLaneReservation(releaseLane, this);
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
		if (alternateLane) targetRoad.releaseExternalLaneReservation(releaseLane, this);
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

	public synchronized void cancelExternalRoadTransition() {
		this.clearExternalRoadTransitionState();
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
	 * Check the native representation used while a vehicle is still traversing
	 * the incoming connector of the road to which it has already been attached.
	 * In that state distance_ is the remaining connector prefix plus the complete
	 * target-lane length, and coordMap is that prefix followed by the target
	 * lane's coordinates after its start point.
	 */
	private boolean hasValidatedNativeConnectorPrefix(double laneLength) {
		if (!this.onRoad || !this.onLane || this.externalRoadTransition
				|| this.isDormantOnRoad() || this.road == null || this.lane == null
				|| this.lane.getRoad() != this.road || this.currentCoord_ == null
				|| this.coordMap == null) {
			return false;
		}

		double connectorPrefixDistance = this.distance_ - laneLength;
		if (!Double.isFinite(connectorPrefixDistance)
				|| connectorPrefixDistance <= COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			return false;
		}

		ArrayList<Coordinate> laneCoordinates = this.lane.getCoords();
		if (laneCoordinates == null || laneCoordinates.size() < 2) {
			return false;
		}
		int laneSuffixSize = laneCoordinates.size() - 1;
		int connectorPointCount = this.coordMap.size() - laneSuffixSize;
		if (connectorPointCount <= 0) {
			return false;
		}

		// appendToLane appends lane coordinates [1..end] after the connector.
		// Requiring that exact geometric suffix distinguishes this representation
		// from an arbitrary corrupt over-length distance.
		for (int i = 0; i < laneSuffixSize; i++) {
			Coordinate actual = this.coordMap.get(connectorPointCount + i);
			Coordinate expected = laneCoordinates.get(i + 1);
			if (actual == null || expected == null) {
				return false;
			}
			double suffixError = distance(actual, expected);
			if (!Double.isFinite(suffixError)
					|| suffixError > COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
				return false;
			}
		}

		Coordinate connectorEnd = this.coordMap.get(connectorPointCount - 1);
		double connectorEndError = connectorEnd == null ? Double.NaN
				: distance(connectorEnd, laneCoordinates.get(0));
		if (!Double.isFinite(connectorEndError)
				|| connectorEndError > COINCIDENT_WAYPOINT_TOLERANCE_METERS) {
			return false;
		}

		double remainingPathDistance = 0.0;
		double recomputedPrefixDistance = 0.0;
		Coordinate previous = this.currentCoord_;
		for (int i = 0; i < this.coordMap.size(); i++) {
			Coordinate waypoint = this.coordMap.get(i);
			if (waypoint == null) {
				return false;
			}
			double segmentDistance = distance(previous, waypoint);
			if (!Double.isFinite(segmentDistance) || segmentDistance < 0.0) {
				return false;
			}
			remainingPathDistance += segmentDistance;
			if (i < connectorPointCount) {
				recomputedPrefixDistance += segmentDistance;
			}
			previous = waypoint;
		}

		double firstWaypointDistance = distance(this.currentCoord_, this.coordMap.get(0));
		return Double.isFinite(remainingPathDistance)
				&& Double.isFinite(recomputedPrefixDistance)
				&& Double.isFinite(this.nextDistance_)
				&& Double.isFinite(firstWaypointDistance)
				&& Math.abs(this.nextDistance_ - firstWaypointDistance)
						<= COINCIDENT_WAYPOINT_TOLERANCE_METERS
				&& Math.abs(this.distance_ - remainingPathDistance)
						<= COINCIDENT_WAYPOINT_TOLERANCE_METERS
				&& Math.abs(connectorPrefixDistance - recomputedPrefixDistance)
						<= COINCIDENT_WAYPOINT_TOLERANCE_METERS;
	}
	/**
	 * Return why this macro-road member cannot safely be handed from native
	 * stepping to external control, or {@code null} when its representation is
	 * safe. Pending external connectors are already externally owned and are
	 * intentionally accepted without a physical target-lane attachment. A native
	 * vehicle with another planned road must also finish any mandatory lane change:
	 * once this road becomes externally controlled, METS-R no longer runs the lane
	 * changing model which makes its current lane reach the route-prepared next lane.
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
					|| this.lane != null || this.onLane
					|| !takeoverRoad.hasExternalLaneReservation(
							this.externalTransitionTargetLane, this)) {
				return "Vehicle " + this.ID + " has malformed pending external-transition state";
			}
			return null;
		}
		if (this.currentConnector != null) {
			return "TRANSIENT: Vehicle " + this.ID + " still occupies connector "
					+ this.currentConnector.getOrigID() + " in intersection "
					+ this.currentConnector.getIntersectionID();
		}
		if (this.lane == null) {
			return "Vehicle " + this.ID + " has no current lane";
		}
		if (this.lane.getRoad() != takeoverRoad) {
			return "Vehicle " + this.ID + " current lane belongs to a different road";
		}
		if (!this.onLane) {
			double endpointError = ContextCreator.getCityContext().getDistance(
					pose, this.lane.getEndCoord());
			boolean recoverableLaneEnd = !this.isDormantOnRoad() && this.nextRoad_ != null
					&& Double.isFinite(this.distance_)
					&& Math.abs(this.distance_) <= COINCIDENT_WAYPOINT_TOLERANCE_METERS
					&& Double.isFinite(endpointError)
					&& endpointError <= EXTERNAL_LANE_ENTRY_LONGITUDINAL_TOLERANCE_METERS;
			if (recoverableLaneEnd) {
				return "TRANSIENT: Vehicle " + this.ID
						+ " is between its lane endpoint and native road transition";
			}
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
			if (this.hasValidatedNativeConnectorPrefix(laneLength)) {
				return "TRANSIENT: Vehicle " + this.ID
						+ " is traversing a validated incoming native connector prefix; remaining="
						+ (this.distance_ - laneLength) + " m";
			}
			return "Vehicle " + this.ID + " has distance " + this.distance_
					+ " outside current lane length " + laneLength
					+ " (unvalidated native connector or corrupt lane state)";
		}
		if (this.nextRoad_ != null) {
			if (this.nextLane_ == null) {
				return "TRANSIENT: Vehicle " + this.ID
						+ " has planned next road " + roadLabel(this.nextRoad_)
						+ " but no route-prepared next lane; native route preparation must finish";
			}
			if (this.nextLane_.getRoad() != this.nextRoad_) {
				return "TRANSIENT: Vehicle " + this.ID
						+ " has stale route-prepared next lane " + laneLabel(this.nextLane_)
						+ " for planned next road " + roadLabel(this.nextRoad_)
						+ "; native route preparation must finish";
			}
			if (!this.isDirectLaneTransition(this.lane, this.nextLane_)) {
				Lane feederLane = this.targetLane();
				String feederDetail = feederLane == null
						? "no same-road feeder lane is available"
						: "required feeder lane=" + laneLabel(feederLane)
								+ " (index=" + takeoverRoad.getLaneIndex(feederLane) + ")";
				return "TRANSIENT: Vehicle " + this.ID
						+ " current lane " + laneLabel(this.lane)
						+ " (index=" + takeoverRoad.getLaneIndex(this.lane) + ")"
						+ " cannot directly reach route-prepared next lane "
						+ laneLabel(this.nextLane_) + " (index="
						+ this.nextRoad_.getLaneIndex(this.nextLane_) + ") on planned road "
						+ roadLabel(this.nextRoad_) + "; " + feederDetail
						+ "; native mandatory lane changing must finish before COSIM takeover";
			}
		}
		return null;
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
		this.road = road;
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
			this.currentCoord_.x = coord.x;
			this.currentCoord_.y = coord.y;
			this.currentCoord_.z = coord.z;
			this.refreshConnectorPoseState();
		}
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
			this.currentCoord_.x = coord.x;
			this.currentCoord_.y = coord.y;
			this.currentCoord_.z = coord.z;
			this.refreshConnectorPoseState();
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
		if (this.nextRoad_ == null) {
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
		this.accPlan_.clear();
		this.nextRoad_ = null;
		this.nextLane_ = null;
		this.roadPath = null;
		this.movingFlag = false;
		this.stuckTime = 0;
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
		this.onLane = false;
		this.onRoad = false;
		this.isReachDest = false; // Reset so a recycled vehicle enters roads normally
		this.endTime = 0;
		this.atOrigin = true;
		this.accRate_ = 0;
		this.nextLane_ = null;
		this.nosingFlag = false;
		this.yieldingFlag = false;
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
	
	/**
	 * Remove vehicle from a lane
	 */
	public void removeFromCurrentLane() {
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
			if(curLane == null) { // edge case, vehicle has not entered the network yet, this may occur when someone calls teleportVeh in the control APIs
				this.nextLane_ = this.nextRoad_.getLane(0);
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
	private synchronized boolean recoverMissedLaneTransition() {
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
		this.refreshMissedLaneRecoveryEpisode(plannedRoad);
		ArrayList<Lane> successors = this.legalSuccessorLanes();
		Lane plannedSuccessor = null;
		for (Lane successor : successors) {
			if (successor.getRoad() != plannedRoad) {
				continue;
			}
			if (this.pathStartsWithCurrentAnd(plannedRoad)) {
				this.nextRoad_ = plannedRoad;
				this.nextLane_ = successor;
				return true;
			}
			if (plannedSuccessor == null) plannedSuccessor = successor;
		}

		double laneLength = this.lane.getLength();
		boolean noRemainingLaneChangeOpportunity = !Double.isFinite(laneLength)
				|| laneLength <= GlobalVariables.NO_LANECHANGING_LENGTH
				|| this.distance_ <= GlobalVariables.NO_LANECHANGING_LENGTH
				|| this.road.getNumberOfLanes() <= 1
				|| this.nextLane_ == null
				|| this.nextLane_.getRoad() != this.nextRoad_
				|| !this.currentRoadHasLaneConnectingTo(this.nextLane_)
				|| !Double.isFinite(this.mandatoryLaneChangeHoldDistance(laneLength));
		boolean finalAttempt = this.stuckTime >= GlobalVariables.MAX_STUCK_TIME;
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
		this.missedLaneRecoveryLastAttemptTick = ContextCreator.getCurrentTick();

		if (plannedSuccessor != null
				&& this.installLegalSuccessorRoute(plannedSuccessor)) {
			return true;
		}
		if (this.installBestReroutedSuccessor(successors, plannedRoad, false, 0.0)) {
			return true;
		}
		if (finalAttempt) {
			this.handleMissedLaneRecoveryLivenessFallback();
		}
		return false;
	}

	/**
	 * Replace the legacy gridlock shortcut which could enter an alternate road
	 * using control decisions evaluated for a different movement. This method only
	 * updates the route; changeRoad retries all gates on the following tick.
	 */
	private boolean recoverGridlockedTransition(double requiredGap) {
		if (this.externalRoadTransition || this.road == null || this.lane == null
				|| this.road.getControlType() == Road.COSIM) {
			return false;
		}
		this.refreshMissedLaneRecoveryEpisode(this.nextRoad_);
		int currentTick = ContextCreator.getCurrentTick();
		int retryCooldown = Math.max(1, GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL);
		if (this.missedLaneGridlockRecoveryAttempted
				&& currentTick >= this.missedLaneRecoveryLastAttemptTick
				&& currentTick - this.missedLaneRecoveryLastAttemptTick < retryCooldown) {
			return false;
		}
		this.missedLaneGridlockRecoveryAttempted = true;
		this.missedLaneRecoveryLastAttemptTick = currentTick;
		return this.installBestReroutedSuccessor(
				this.legalSuccessorLanes(), this.nextRoad_, true, requiredGap);
	}

	private void refreshMissedLaneRecoveryEpisode(Road plannedRoad) {
		int sourceRoadID = this.road == null ? -1 : this.road.getID();
		int sourceLaneID = this.lane == null ? -1 : this.lane.getID();
		int plannedRoadID = plannedRoad == null ? -1 : plannedRoad.getID();
		if (this.missedLaneRecoveryRoadID != sourceRoadID
				|| this.missedLaneRecoveryLaneID != sourceLaneID
				|| this.missedLaneRecoveryPlannedRoadID != plannedRoadID) {
			this.resetMissedLaneRecoveryEpisode();
			this.missedLaneRecoveryRoadID = sourceRoadID;
			this.missedLaneRecoveryLaneID = sourceLaneID;
			this.missedLaneRecoveryPlannedRoadID = plannedRoadID;
		}
	}

	private void resetMissedLaneRecoveryEpisode() {
		this.missedLaneRecoveryRoadID = -1;
		this.missedLaneRecoveryLaneID = -1;
		this.missedLaneRecoveryPlannedRoadID = -1;
		this.missedLaneRecoveryLastAttemptTick = -1;
		this.missedLaneRecoveryInitialAttempted = false;
		this.missedLaneRecoveryFinalAttempted = false;
		this.missedLaneGridlockRecoveryAttempted = false;
		this.missedLaneRecoveryFallbackHandled = false;
	}

	private void resetMissedLaneRecoveryState() {
		this.resetMissedLaneRecoveryEpisode();
		this.missedLaneRecoveryQuarantined = false;
	}

	private void handleMissedLaneRecoveryLivenessFallback() {
		if (this.missedLaneRecoveryFallbackHandled || this.externalRoadTransition) {
			return;
		}
		this.missedLaneRecoveryFallbackHandled = true;
		this.stuckTime = 0;

		Road fallbackRoad = isDeparturableRoad(this.road)
				? this.road : this.departurableFallbackRoad();
		if (fallbackRoad != null) {
			this.originRoad_ = fallbackRoad;
			this.updateLastDeparturableRoad(fallbackRoad);
			this.isReachDest = false;
			this.queueDepartureFromRoad(fallbackRoad);
			return;
		}

		// No safe road exists from which to retry. Preserve the trip and last pose,
		// but detach the quarantined vehicle so it cannot remain a permanent
		// physical blocker. A later explicit departure can safely requeue it.
		this.missedLaneRecoveryQuarantined = true;
		this.clearShadowImpact();
		this.removeFromCurrentLane();
		this.removeFromCurrentRoad();
		this.onLane = false;
		this.onRoad = false;
		this.currentSpeed_ = 0.0;
		this.accRate_ = 0.0;
		this.accDecided_ = false;
		this.accPlan_.clear();
		this.movingFlag = false;
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		this.nosingFlag = false;
		this.yieldingFlag = false;
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
			if (successor == null || successor.getRoad() == null) continue;
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

	private Road plannedNextRoadForRecovery() {
		if (this.nextRoad_ != null) {
			return this.nextRoad_;
		}
		if (this.roadPath != null && this.roadPath.size() > 1) {
			return this.roadPath.get(1);
		}
		return null;
	}

	private boolean installLegalSuccessorRoute(Lane successorLane) {
		if (!this.isDirectLaneTransition(this.lane, successorLane)) {
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

		List<Road> successorPath = this.buildRecoveryPath(successorRoad);
		if (successorPath == null) {
			return false;
		}
		return this.installLegalSuccessorRoute(successorLane, successorPath);
	}

	private boolean installLegalSuccessorRoute(Lane successorLane, List<Road> successorPath) {
		if (!this.isDirectLaneTransition(this.lane, successorLane)
				|| successorLane.getRoad() == null
				|| !this.isConnectedRecoveryPath(successorPath, successorLane.getRoad())) {
			return false;
		}
		this.clearShadowImpact();
		this.roadPath = successorPath;
		this.nextRoad_ = successorLane.getRoad();
		this.nextLane_ = successorLane;
		this.atOrigin = false;
		this.setDistToTravelEstimate(this.routeDistanceFromCurrentPosition(this.roadPath));
		this.setShadowImpact();
		return true;
	}

	private boolean installBestReroutedSuccessor(List<Lane> successors, Road excludedRoad,
			boolean requireEntranceGap, double requiredGap) {
		Lane bestLane = null;
		List<Road> bestPath = null;
		double bestTravelTime = Double.POSITIVE_INFINITY;
		for (Lane successor : successors) {
			Road successorRoad = successor == null ? null : successor.getRoad();
			if (successorRoad == null || successorRoad == excludedRoad) {
				continue;
			}
			if (requireEntranceGap && (this.entranceGap(successor) < requiredGap
					|| successorRoad.getExternalLaneReservationBlocker(successor, this) != null)) {
				continue;
			}
			List<Road> candidatePath = this.buildRecoveryPath(successorRoad);
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

	private List<Road> buildRecoveryPath(Road successorRoad) {
		if (successorRoad == null || this.road == null || this.destRoad_ == null) {
			return null;
		}
		List<Road> suffix = RouteContext.shortestPathRoute(
				successorRoad, this.destRoad_, this.rand_route_only);
		if (suffix == null || suffix.isEmpty() || suffix.get(0) == null
				|| suffix.get(0).getID() != successorRoad.getID()) {
			return null;
		}
		ArrayList<Road> recoveredPath = new ArrayList<Road>(suffix.size() + 1);
		recoveredPath.add(this.road);
		recoveredPath.addAll(suffix);
		return this.isConnectedRecoveryPath(recoveredPath, successorRoad)
				? recoveredPath : null;
	}

	private boolean pathStartsWithCurrentAnd(Road successorRoad) {
		return this.roadPath != null && this.roadPath.size() > 1
				&& this.roadPath.get(0) != null && this.roadPath.get(1) != null
				&& this.roadPath.get(0).getID() == this.road.getID()
				&& this.roadPath.get(1).getID() == successorRoad.getID()
				&& this.isConnectedRecoveryPath(this.roadPath, successorRoad);
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
		double leadGap = 0;
		if (leadVehicle != null) {
			leadGap = newDistance - leadVehicle.distance_ - leadVehicle.length(); // leadGap>=-leadVehicle.length()
		} else {
			leadGap = newDistance;
		}
		return leadGap;
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
		double lagGap = 0;
		if (lagVehicle != null)
			lagGap = lagVehicle.distance_ - newDistance - this.length();
		else {
			lagGap = this.lane.getLength() - newDistance;
		}
		return lagGap;
	}
	
	/**
	 * Find the lead vehicle in target lane
	 * @param plane Target lane
	 * @param dist The projected distance of THIS vehicle on the target lane
     * @return Vehicle leadVehicle, or null if none exists ahead
	 */
	public Vehicle leadVehicle(Lane plane, double dist) {
	    
	    // 1. First check if a macro-trailing vehicle could actually be the new leader 
	    // due to lane projection/geometry differences.
	    Vehicle candidate = this.macroTrailing_;
	    while (candidate != null) {
	        if (candidate.lane == plane) {
	            // Found a vehicle on the target lane. Is it physically ahead of us?
	            if (candidate.getDistanceToNextJunction() <= dist) {
	                return candidate;
	            } else {
	                // We found a vehicle on the target lane, but it's behind us. 
	                // Stop searching the trailing list.
	                break; 
	            }
	        }
	        
	        // If the distance gets too large, we are too far behind our projected spot, 31.459 = \pi * 10 m diameter difference on a circular road
	        if (candidate.getDistanceToNextJunction() > dist + 31.459) {
	            break;
	        }
	        candidate = candidate.macroTrailing_;
	    }
	    
	    // 2. Standard search: Look through macro-leading vehicles.
	    candidate = this.macroLeading_;
	    while (candidate != null) {
	        if (candidate.lane == plane) {
	            // The first vehicle we hit on the target lane that has a smaller 
	            // (or equal) distance to the junction is our leader.
	            if (candidate.getDistanceToNextJunction() <= dist) {
	                return candidate;
	            }
	        }
	        // Keep moving forward up the chain
	        candidate = candidate.macroLeading_;
	    }
	    
	    // 3. No vehicle found ahead on the target lane
	    return null; 
	}
	
	/**
	 * Find lag vehicle in target lane
	 * @param plane Target lane
	 * @param dist The projected distance of THIS vehicle on the target lane
     * @return Vehicle lagVehicle, or null if none exists behind
    */
	public Vehicle lagVehicle(Lane plane, double dist) {
	    
	    // 1. First check if a macro-leading vehicle could actually be the new lag vehicle 
	    // due to lane projection/geometry differences.
	    Vehicle candidate = this.macroLeading_;
	    while (candidate != null) {
	        if (candidate.lane == plane) {
	            // Found a vehicle on the target lane. Is it physically behind us?
	            if (candidate.getDistanceToNextJunction() > dist) {
	                return candidate;
	            } else {
	                // We found a vehicle on the target lane, but it's ahead of us. 
	                // Stop searching the leading list, as further vehicles will only be further ahead.
	                break; 
	            }
	        }
	        
	        // If the candidate's distance becomes smaller than our projected distance, 
	        // we are looking too far ahead in the queue to find someone behind us.
	        if (candidate.getDistanceToNextJunction() <= dist - 31.459) {
	            break;
	        }
	        candidate = candidate.macroLeading_;
	    }
	    
	    // 2. Standard search: Look through macro-trailing vehicles.
	    candidate = this.macroTrailing_;
	    while (candidate != null) {
	        if (candidate.lane == plane) {
	            // The first vehicle we hit on the target lane that has a larger 
	            // distance to the junction is our lag vehicle.
	            if (candidate.getDistanceToNextJunction() > dist) {
	                return candidate;
	            }
	        }
	        // Keep moving backward down the chain
	        candidate = candidate.macroTrailing_;
	    }
	    
	    // 3. No vehicle found behind on the target lane
	    return null; 
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
				ConnectorRoad leaderConnector = newleader.getCurrentConnector();
				Road targetRoad = nextlane.getRoad();
				boolean samePlannedConnector = leaderConnector != null
						&& this.road != null && targetRoad != null
						&& leaderConnector.getSourceRoad() == this.road
						&& leaderConnector.getTargetRoad() == targetRoad;
				if (samePlannedConnector) {
					// The leader is still in the turning prefix encoded on this
					// target lane. Connector admission performs the applicable
					// headway or footprint check for this exact movement.
					return Double.POSITIVE_INFINITY;
				}
				gap = nextlane.getLength() - newleader.getDistanceToNextJunction()
						- newleader.length();
			} else
				gap = 9999999; // a number large enough
		}
		return gap;
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
	 * @param origin
	 * @param target
	 * @param distanceToTarget
	 * @param distanceTravelled
	 */
	private void move2(Coordinate origin, Coordinate target, double distanceToTarget, double distanceTravelled) {
		double p = distanceTravelled / distanceToTarget;
		if (p < 0) p = 0;
		if (p > 1) p = 1;
		this.setCurrentCoord(new Coordinate(
			(1 - p) * origin.x + p * target.x,
			(1 - p) * origin.y + p * target.y,
			(1 - p) * origin.z + p * target.z));
	}
	
	/**
	 * Manually specify the acceleration
	 * @param acc
	 */
	public boolean controlVehicleAcc(double acc) {
		if(!accDecided_) {
			this.accPlan_.push(acc);
			this.accDecided_ = true;
			return true;
		}
		return false;
	}

	public void ensureAccelerationPlan(double fallbackAcc) {
		if (this.accPlan_.isEmpty()) {
			this.accPlan_.add(Double.isNaN(fallbackAcc) ? 0.0 : fallbackAcc);
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
		double[] returnVals = new double[2];
		this.distance2(new Coordinate(prevX, prevY, currentCoord.z), currentCoord, returnVals);
		if (returnVals[0] > 0.1 && !Double.isNaN(returnVals[1]) && !Double.isInfinite(returnVals[1])) {
			return returnVals[1];
		}
		return this.bearing_;
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
		if(roadPath == null) return res;
		for(Road r: roadPath) {
			res.add(r.getOrigID());
		}
		return res;
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
	
	public int getStuckTime() {
		return this.stuckTime;
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
	public void setLastDeparturableRoad(Road r) { this.lastDeparturableRoad_ = isDeparturableRoad(r) ? r : null; }
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
