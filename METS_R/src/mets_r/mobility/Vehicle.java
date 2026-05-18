package mets_r.mobility;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.routing.RouteContext;
import java.util.ArrayList;
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
	public final static int CHARGING_RETURN_TRIP = 9;
	
	public final static int NONE_OF_THE_ABOVE = -1;
	private static final int LC_REASON_STRATEGIC = 1;
	private static final int LC_REASON_COOPERATIVE = 2;
	private static final int LC_REASON_SPEED_GAIN = 3;
	private static final int LC_REASON_KEEP_RIGHT = 4;
	private static final int LC_REASON_REGULATORY = 5;
	/* Constants */
	private static final double GRAVITY = 9.81; // m/s², used for grade resistance
	
	/* Private variables that are not visible to descendant classes */
	private Road destRoad_;
	private Road originRoad_;
	private Coordinate currentCoord_; // this variable is created when the vehicle is initialized
	private Coordinate originCoord_;
	private double length; // vehicle length
	private double distance_; // distance to downstream junction
	private double nextDistance_; // distance to the next control point in the current lane's line segments
	private double distToTravel_; // remaining distance to be traversed along the road, excluding the junction area, used as a rough estimation of travel distance
	
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
	private Road road;
	private Lane lane;
	
	private double prevDistance;
	private double prevSpeed;
	
	// Speed snapshot taken at the start of each move() tick, used to keep
	// the speed unchanged within the tick a vehicle transitions onto a
	// CoSim road (so CARLA reads the entering speed rather than an
	// in-tick clamped value).
	private double tickStartSpeed_;
	
	// Vehicle class, status, and sensorType
	protected int vehicleState; 
	protected int vehicleClass; 
	protected int vehicleSensorType;
	
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
		this.ID = ContextCreator.generateAgentID();
		this.rand = new Random(GlobalVariables.RandomGenerator.nextInt());
		this.rand_route_only = new Random(rand.nextInt());
		this.rand_relocate_only = new Random(rand.nextInt());
		this.rand_car_follow_only = new Random(rand.nextInt());
		this.currentCoord_ = new Coordinate(0, 0, 0.0);
		this.activityPlan = new ArrayList<Plan>(); // Empty plan

		this.length = GlobalVariables.DEFAULT_VEHICLE_LENGTH;
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
		this.coordMap = new ArrayList<Coordinate>();
		this.originCoord_ = null;
		this.destRoad_ = null;
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
		this(vClass, vSensor);
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
	 * Vehicle enters the road, success when the road has enough space in its rightmost lane
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

	private boolean enterNetwork(Road road, Lane lane, boolean allowCoSimEntry) {
		// Sanity check
		if (road == null || lane == null) return false;
		if(lane.getRoad() != road) return false;
		if (road.getControlType() == Road.COSIM && !allowCoSimEntry) return false;
		
		// Guard: if vehicle is already counted on a road, remove it first to
		// prevent double-incrementing nVehicles_ (e.g. when a vehicle ended up
		// in two departure queues due to a stale pending-queue entry).
		if (this.isOnRoad()) {
			ContextCreator.logger.warn("enterNetwork called on vehicle " + this.getID()
					+ " that is already on road " + this.road.getID() + " – rejecting enterNetwork call to avoid corrupting state");
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
	public void departure(Road road) {
		this.numTrips ++;
		this.originRoad_ = road;
		this.isReachDest = false;
		if(!this.isOnRoad()) { // If the vehicle not in the network, we add it to a pending list to the closest link
			this.originCoord_ = road.getStartCoord();
			road.addVehicleToPendingQueue(this);
		}
		else { // The vehicle is on road, we just need to reroute it
			this.rerouteAndSetNextRoad(); // refresh the CoordMap
		}
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
		this.distToTravel_ -= this.roadPath.get(0).getLength();
		this.roadPath.remove(0);
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

	/**
	 * Get the next target lane of this vehicle
	 */
	public Lane getNextLane() {
		return this.nextLane_;
	}

	/**
	 * Reroute the vehicle in the middle of the road
	 */
	public void rerouteAndSetNextRoad() {
		// Vehicle departed
		this.atOrigin = false;
		// Clear legacy impact
		this.clearShadowImpact();
		this.roadPath = RouteContext.shortestPathRoute(this.road, this.destRoad_, this.rand_route_only); // K-shortest path or shortest path
		this.setShadowImpact();
		this.distToTravel_ = 0;
		if (this.roadPath == null) {
			// Cannot find route between this.road and this.destRoad_, meaning this.road or this.destRoad_ is at a deadend
			// Fallback to use valid roads,  this fallback would fail when the r2 or the new departure road are not properly connnected. How to fix this?
			Road r2 = ContextCreator.getCityContext().findRoadAtCoordinates(this.destRoad_.getEndCoord(), true, this.destRoad_);
			
			this.roadPath = RouteContext.shortestPathRoute(this.road, r2, this.rand_route_only); // K-shortest path or shortest path
			
			if(this.roadPath == null) {
				Road r1 = ContextCreator.getCityContext().findRoadAtCoordinates(this.getCurrentCoord(), false, this.road);
				
				this.roadPath = RouteContext.shortestPathRoute(r1, r2, this.rand_route_only); 
				
				if(this.roadPath == null) {
					this.nextRoad_ = null;
					return;
				}
				else {
					this.roadPath.add(0, this.road);
				}
				
			}
			else{
				this.destRoad_ = r2;
			}
		}
		
		this.setShadowImpact();
		this.distToTravel_ = this.distance_;
		
	    if (this.roadPath.size() < 2) { // The origin and destination road is the same so this vehicle has arrived
			this.nextRoad_ = null;
		} else {
			this.nextRoad_ = roadPath.get(1);
			this.assignNextLane();
			this.distToTravel_ = this.distance_ - this.road.getLength();
			for(Road r: roadPath) {
				this.distToTravel_ += r.getLength();
			}
		}
	}
	
	/**
	 * Reroute the vehicle in the middle of the road with a specified next road.
	 * In co-sim scenarios the external simulator is authoritative about which road
	 * the vehicle crosses into, so the path is updated even when nextRoad is not
	 * a direct downstream neighbor of the vehicle's currently stored road.
	 */
	public void rerouteWithSpecifiedNextRoad(Road nextRoad) {
		if (this.road == null) {
			ContextCreator.logger.warn("rerouteWithSpecifiedNextRoad: vehicle " + this.getID()
					+ " has null current road, cannot reroute to " + nextRoad.getOrigID());
			return;
		}
		if (this.nextRoad_ == nextRoad) {
			return; // Already heading to the specified road, nothing to do
		}
		if (!this.road.getDownStreamRoads().contains(nextRoad.getID())) {
			ContextCreator.logger.warn("rerouteWithSpecifiedNextRoad: vehicle " + this.getID()
					+ " current road " + this.road.getOrigID() + " is not directly connected to next road "
					+ nextRoad.getOrigID() + " — updating path anyway for co-sim tracking.");
		}

		// Vehicle departed
		this.atOrigin = false;
		// Clear legacy impact
		this.clearShadowImpact();
		this.roadPath = RouteContext.shortestPathRoute(nextRoad, this.destRoad_, this.rand_route_only);

		if (this.roadPath == null) {
			// Fallback: destination road may be a dead-end; try nearest valid road
			Road r2 = ContextCreator.getCityContext().findRoadAtCoordinates(this.destRoad_.getEndCoord(), true, this.destRoad_);
			this.roadPath = RouteContext.shortestPathRoute(nextRoad, r2, this.rand_route_only);
			if (this.roadPath == null) {
				ContextCreator.logger.warn("Cannot find path from " + nextRoad.getOrigID() + " to the vehicle destination, gracefully removing this trip.");
				this.nextRoad_ = null;
				return;
			} else {
				this.destRoad_ = r2;
			}
		}

		this.roadPath.add(0, this.road); // Prepend the current road
		this.setShadowImpact();
		this.distToTravel_ = this.distance_;
		if (this.roadPath.size() < 2) {
			this.nextRoad_ = null;
		} else {
			this.nextRoad_ = roadPath.get(1);
			this.assignNextLane();
			this.distToTravel_ = this.distance_ - this.road.getLength();
			for (Road r : roadPath) {
				this.distToTravel_ += r.getLength();
			}
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
	 * Update route based on list of road, return false if the route start and end links are inconsistent 
	 */
	public boolean updateRoute(List<Road> newPath) {
		if(this.road == newPath.get(0) && this.destRoad_ == newPath.get(newPath.size() - 1)){
			double dtt = 0;
			for(Road r: newPath) {
				dtt += r.getLength();
			}
			this.distToTravel_ = dtt;
			// Vehicle departured
			this.atOrigin = false;
			this.roadPath = newPath;
			this.nextRoad_ = newPath.get(1);
			this.setShadowImpact();
			this.assignNextLane();
			return true;
		}
		else {
			return false;
		}
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
		if (this.coordMap.isEmpty()) {
			this.nextDistance_ = 0;
			return;
		}
		double[] distAndAngle = new double[2];
		this.distance2(this.getCurrentCoord(), this.coordMap.get(0), distAndAngle);
		this.nextDistance_ = distAndAngle[0];
		if (this.nextDistance_ > 1e-4) {
			this.bearing_ = distAndAngle[1];
			return;
		}
		if (this.coordMap.size() > 1) {
			this.distance2(this.coordMap.get(0), this.coordMap.get(1), distAndAngle);
			if (distAndAngle[0] > 1e-4) {
				this.bearing_ = distAndAngle[1];
			}
		}
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
		else {
			// Update bearing to be the directions of the first two consecutive coord in coordMap
			if(this.coordMap.size() >= 1) {
				Coordinate c1 = this.getCurrentCoord();
			    Coordinate c2 = this.coordMap.get(0);
			    // returnVals[0] → distance, returnVals[1] → azimuth in [-180,180]
			    double[] returnVals = new double[2];
			    this.distance2(c1, c2, returnVals);
			    
			    this.bearing_ = returnVals[1];
			}
		}
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
				 // edge case, two vehicle share the same distance, this can happen due to the accuracy loss in the co-sim map
				 if(toCheckVeh.getDistanceToNextJunction() == distance) {
					 distance = distance + 0.01; // edge case add a tiny value to the distance of the to-insert vehicle
				 }
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
			for (int i = 0; i < coords.size() - 1; i++) {
				accDist -= distance(coords.get(i), coords.get(i+1));
				if (this.distance_ + 1e-4 >= accDist) { // Find the first pt in CoordMap that has smaller distance_, add noise to avoid numerical issue
					
					this.setCurrentCoord(coords.get(i)); // Set current coord
					double[] distAndAngle = new double[2];
					this.distance2(coords.get(i), coords.get(i+1), distAndAngle);
					double distToMove = distAndAngle[0] - (this.distance_ - accDist);
					if (distToMove > 0) {
						move2(coords.get(i), coords.get(i+1), distAndAngle[0], distToMove); // Update vehicle location
					}
					this.nextDistance_ = (this.distance_ - accDist);
					this.bearing_ = distAndAngle[1];
					
				for (int j = i + 1; j < coords.size(); j++) { // Add the rest coords into the CoordMap
					coordMap.add(coords.get(j));
				}
				currentSegmentIdx_ = i;
				currentLaneSlope_ = lane.getSegmentSlope(i);
				break;
			}
		}
	this.lane = lane;
	this.lane.addOneVehicle();
	this.onLane = true;
		if (coordMap.size() == 0) {
			ContextCreator.logger.error("Teleport to lane error, could not find coordMap for the target lane:" + lane.getID() + ", accDist: " + accDist+ ", distance: "+ this.distance_);
		}
	}
	else {
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
		if (this.nextRoad_ != null && this.road.getID() != this.nextRoad_.getID()) {
			Junction nextJunction = ContextCreator.getJunctionContext().get(this.road.getDownStreamJunction());
			
			if (nextJunction.getDelay(this.road.getID(), this.nextRoad_.getID())>0) { // edge case 1: brake for the red light
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
	 * For visualization purpose, record the previous coordinates of the vehicle
	 * @param newCoord New cooridnates of the vehicle
	 */
	protected void setPreviousEpochCoord(Coordinate newCoord) {
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
		/* Load the acc decision */
		accRate_ = accPlan_.pop();
		
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
	}
	
	public double updateCoordByDx(double dx) {
		double lastStepMove = 0;
		boolean travelledMaxDist = false; // True when traveled with maximum distance (=dx).
		double distTravelled = 0; // The distance traveled so far.
		double oldv = currentSpeed_; // Speed at the beginning
		double step = GlobalVariables.SIMULATION_STEP_SIZE;
		accRate_ =  Math.max(this.maxDeceleration_, 2.0f * (dx - oldv * step) / (step * step));
		currentSpeed_ =  Math.max(currentSpeed_ + accRate_ * step, 0);
		// Update vehicle coords
		double[] distAndAngle = new double[2];
		
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
				this.distance2(this.getCurrentCoord(), this.coordMap.get(0), distAndAngle);
				this.distance_ -= this.nextDistance_;
				this.nextDistance_ = distAndAngle[0];
				this.bearing_ = distAndAngle[1];
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
		if(currlane.getTurningDist(plane.getID())>0) {
			this.distance_ = currlane.getTurningDist(plane.getID());
			this.coordMap.addAll(currlane.getTurningCoords(plane.getID()));
			this.nextDistance_ = ContextCreator.getCityContext().getDistance(this.getCurrentCoord(), this.coordMap.get(0));
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
	public boolean changeRoad() {
		// Check if the vehicle has reached the destination or not
		if (this.isReachDest) {
			this.clearShadowImpact(); // Clear shadow impact if already reaches destination
			this.onLane = false;
			return true; // Only reach destination once
		} 
		else if (this.nextRoad_ != null) {
			coordMap.clear();
			Junction nextJunction = ContextCreator.getJunctionContext().get(this.road.getDownStreamJunction());
			boolean movable = false;
			if(this.nextRoad_.getID() == this.roadPath.get(1).getID()) { // nextRoad data is consistent
				switch(nextJunction.getControlType()) {
					case Junction.NoControl:
						movable = true;
						break;
					case Junction.DynamicSignal:
						if(nextJunction.getSignalState(this.road.getID(), this.nextRoad_.getID())<= Signal.Yellow)
							movable = true;
						break;
					case Junction.StaticSignal:
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
			

			if((movable && this.nextRoad_.noEnterRoadConflict(this.road)) || this.road.getControlType() == Road.COSIM) { // Cosim road would rely the external simulator to decide movable or not
				// Check if the target road has space
				if(this.nextRoad_.getControlType() == Road.COSIM) {
						// For cosim road, get the last vehicle, check whether the distance is greater than 1.2 * this.length
						Vehicle lastVeh = nextLane_.lastVehicle();
						if((lastVeh == null)) {
							this.executeRoadTransition(nextLane_, nextRoad_);
							
							return true;
						}
						else {
							Coordinate c1 = lastVeh.getCurrentCoord();
							// Get dist between the coord and the begining coord of the lane
							Coordinate c2 = nextLane_.getStartCoord();
							if((ContextCreator.getCityContext().getDistance(c1, c2) >= 1.2 * this.length())){
								this.executeRoadTransition(nextLane_, nextRoad_);
								
								return true;
							}
						}
					}
					else {
						if ((this.entranceGap(nextLane_) >= 1.2 * this.length())) {
							this.executeRoadTransition(nextLane_, nextRoad_);
							return true;
						}
						else if (this.stuckTime >= GlobalVariables.MAX_STUCK_TIME) { // addressing gridlock 
							for(Integer dnlaneID: this.lane.getDownStreamLanes()) {
								Lane dnlane = ContextCreator.getLaneContext().get(dnlaneID);
								if (this.entranceGap(dnlane) >= 1.2*this.length()) {
									this.executeRoadTransition(dnlane, dnlane.getRoad());
									return true;
								}
							}
						}
					}
			}
			
			this.onLane = false;
			return false;
		}
		else{
			this.reachDest();
			return true;
		}
	}
	
	/**
	 * Transitions the vehicle to the next lane and road
	 */
	public void executeRoadTransition(Lane targetLane, Road targetRoad) {
        // Capture the current (source) road BEFORE we detach from it so we
        // can detect a regular-road -> CoSim-road crossing in this tick.
        Road sourceRoad = this.road;
        
        this.enterNextLane(targetLane);
        this.removeFromCurrentLane();
        this.removeFromCurrentRoad();
        this.appendToLane(targetLane);
        this.appendToRoad(targetRoad);
        
        // When the vehicle enters a CoSim road this tick, freeze its speed
        // at the value it had at the start of the tick. METS-R does not
        // step dynamics on CoSim roads, so currentSpeed_ on the CoSim side
        // is what CARLA reads via veh_inform['speed'] when (re)creating
        // the vehicle. In-tick deceleration / collision clamping in move()
        // can drive currentSpeed_ to ~0, which would make the CARLA-side
        // vehicle stop abruptly at the boundary and risk rear-end collisions.
        if (targetRoad.getControlType() == Road.COSIM
                && (sourceRoad == null || sourceRoad.getControlType() != Road.COSIM)) {
            this.currentSpeed_ = this.tickStartSpeed_;
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
	 * @return effective maximum acceleration in m/s²
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
	 * @return effective normal deceleration in m/s² (negative value)
	 */
	private double effectiveNormalDeceleration() {
		return normalDeceleration_ - GRAVITY * currentLaneSlope_;
	}
	
	/**
	 * Effective maximum (emergency) deceleration adjusted for road grade.
	 * More negative uphill; less negative downhill.
	 * @return effective maximum deceleration in m/s² (negative value)
	 */
	private double effectiveMaxDeceleration() {
		return maxDeceleration_ - GRAVITY * currentLaneSlope_;
	}
	
	/**
	 * Register the vehicle to the target road
	 * @param road Target road
	 */
	public void appendToRoad(Road road) {
		this.road = road;
		
		// If the macroLeading is modified in advanceInMacroList by other thread
		// then this vehicle will be misplaced in the Linked List
		if (road.lastVehicle() != null) {
			road.lastVehicle().macroTrailing_ = this;
			macroLeading_ = road.lastVehicle();
		} else {
			macroLeading_ = null;
			road.firstVehicle(this);
		}
		road.lastVehicle(this);
		
		// After this appending, update the number of vehicles
		road.changeNumberOfVehicles(1);
		this.onRoad = true;
		
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
		return this.originRoad_.getID();
	}
	
	/**
	 * Get destination road
	 */
	public int getDestRoad() {
		return this.destRoad_.getID();
	}
	
	/**
	 * Get (a copy of) of the vehicle location
	 */
	public Coordinate getCurrentCoord() {
		Coordinate coord = new Coordinate();
		coord.x = this.currentCoord_.x;
		coord.y = this.currentCoord_.y;
		coord.z = this.currentCoord_.z;
		return coord;
	}
	
	/**
	 * Get (a copy of) of the vehicle location in the original coordinate system
	 */
	public Coordinate getCurrentCoord(MathTransform transform) {
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
	public void setCurrentCoord(Coordinate coord) {
		if (coord == null) {
			ContextCreator.logger.error("New coord is null!");
		} else {
			this.currentCoord_.x = coord.x;
			this.currentCoord_.y = coord.y;
			this.currentCoord_.z = coord.z;
		}
	}
	
	/**
	 * Set the vehicle location using coordinates from the original coordinate system
	 * @param coord New location
	 */
	public void setCurrentCoord(Coordinate coord, MathTransform transform) {
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

	public double currentSpeed() {
		return currentSpeed_;
	}
	
	/**
	 * Remove vehicle from a lane
	 */
	public void removeFromCurrentLane() {
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
		if (nextRoad_ == null) {
			return true;
		}
		// If using dynamic shortest path then we need to check lane only after
		// the route is updated
		for (int pl : this.nextLane_.getUpStreamLanes()) {
			if (pl == this.lane.getID()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Find if the potential next road and current lane are connected
	 * @param nextRoad
	 * @return Boolean connected
	 */
	public boolean checkNextLaneConnected(Road nextRoad) {
		boolean connected = false;
		Lane curLane = this.lane;

		if (nextRoad != null) {
			for (int dl : curLane.getDownStreamLanes()) {
				if (ContextCreator.getLaneContext().get(dl).getRoad() == nextRoad) {
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
	 * Assign the next lane to the vehicle
	 */
	public void assignNextLane() {
		Lane curLane = this.lane;
		if (this.nextRoad_ == null) {
			this.nextLane_ = null;
			return;
		} else {
			if(curLane == null) { // edge case, vehicle has not entered the network yet, this may occur when someone calls teleportVeh in the control APIs
				this.nextLane_ = this.nextRoad_.getLane(0);
				return;
			}
			else {
				for (int dl : curLane.getDownStreamLanes()) {
					if (ContextCreator.getLaneContext().get(dl).getRoad() == this.nextRoad_) {
						this.nextLane_ = ContextCreator.getLaneContext().get(dl);
						// If this lane already connects to downstream road then assign to the connected lane
						return;
					}
				}
				
				// Vehicle is currently on an incorrect lane that does not connect to the next road
				for (Lane dl: this.nextRoad_.getLanes()) {
					if(dl.getUpStreamLaneInRoad(this.road)!=null) {
						this.nextLane_ = dl;
						return;
					}
				}
				
				// Fallback, use the first lane
				this.nextLane_ = this.nextRoad_.getLane(0);
				
				// Vehicle's route data is broken and there is not connection between this.road and this.nextRoad_
				// Raise a warning and call the routing function to complete the missing route
//				ContextCreator.logger.warn("No connection between curRoad " + this.road + " to nextRoad" + this.nextRoad_ + " for vehicle " + this.getID() + " fixing by reroute it.");
//				List<Road> patchPath = RouteContext.shortestPathRoute(this.road, this.nextRoad_, this.rand_route_only); // K-shortest path or shortest path
//				if (patchPath != null && patchPath.size() > 2) {
//					List<Road> subPatch = patchPath.subList(1, patchPath.size() - 1);
//					this.roadPath.addAll(1, subPatch); // insert pathPath between roadPath
//					this.nextRoad_ = this.roadPath.get(1); // update the nextRoad
//					this.setShadowImpact();
//					this.assignNextLane(); // try to get next lane again 
//					return;
//				}
			}
		}
		// nextRoad is not connected to the current lane — acceptable in co-sim where the
		// external simulator may route the vehicle across non-adjacent roads. Lane 0 of
		// nextRoad is used as the fallback entry point.
		ContextCreator.logger.warn("assignNextLane: no lane connection from curLane " + curLane + " curRoad " + this.getRoad() + " to nextRoad " + this.nextRoad_ + " — using lane 0 as fallback");
	}

	/**
	 * Return the target lane (the lane that connect to the downstream Road)
	 */
	public Lane targetLane() {
		if (this.nextLane_ != null) 
			return nextLane_.getUpStreamLaneInRoad(this.road);
		else
			return null;
	}

	/**
	 * Return the next lane that the vehicle need to change to in order to reach the
	 * target lane
	 */
	public Lane tempLane() {
		Lane plane = this.targetLane();
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
				gap =  nextlane.getLength() - newleader.getDistanceToNextJunction() - newleader.length();
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
	
	public int getVehicleClass() {
		return this.vehicleClass;
	}
	
	public int getVehicleSensorType() {
		return this.vehicleSensorType;
	}
	
	public void setVehicleSensorType(int sensorType) {
		this.vehicleSensorType = sensorType;
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
	 * Get vehicle's roungh distance to travel
	 */
	public double getDistToTravel() {
		return this.distToTravel_;
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
		if(this.nextRoad_ == dsRoad) {
			if(this.prevDistance < (this.prevSpeed + 0.5 * this.maxAcceleration_) * GlobalVariables.SIMULATION_STEP_SIZE) return true;
		}
		return false;
	}
	
	/**
	 * Record the prevState for thread safe check of aboutToEnterRoad
	 */
	public void recordPrevState() {
		this.prevDistance = this.distance_;
		this.prevSpeed = this.currentSpeed_;
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
	
	public void setSpeed(double speed) { this.currentSpeed_ = speed; }
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
	public void setOriginRoad(Road r) { this.originRoad_ = r; if (r != null && this.originCoord_ == null) this.originCoord_ = r.getStartCoord(); }
	public void setActivityPlan(ArrayList<Plan> plan) { this.activityPlan = plan; }
	public boolean getMovingFlag() { return this.movingFlag; }

	public List<Road> getRoadPath() { return this.roadPath; }
	public void setRoadPath(List<Road> path) { this.roadPath = path; }
	public void setDistToTravel(double d) { this.distToTravel_ = d; }
	public boolean isAtOrigin() { return this.atOrigin; }
	public void setAtOrigin(boolean v) { this.atOrigin = v; }
	public boolean isReachDest() { return this.isReachDest; }
	public void setReachDest(boolean v) { this.isReachDest = v; }
	public void setNextRoadDirectly(Road r) { this.nextRoad_ = r; }
}
