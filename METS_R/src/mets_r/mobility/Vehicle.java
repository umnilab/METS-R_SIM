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
	
	/* Private variables that are not visible to descendant classes */
	private Road originRoad_;
	private Road destRoad_;
	private Coordinate currentCoord_; // this variable is created when the vehicle is initialized
	private double length; // vehicle length
	private double distance_; // distance from downstream junction
	private double nextDistance_; // distance from the start point of next line segment
	
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
	private double travelPerTurn;
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
	
	// For adaptive network partitioning
	private int Nshadow; // Number of current shadow roads in the path
	private ArrayList<Road> futureRoutingRoad;
	protected ArrayList<Plan> activityPlan; // A set of zone for the vehicle to visit
	
	// For calculating vehicle coordinates
	GeodeticCalculator calculator = new GeodeticCalculator(ContextCreator.getLaneGeography().getCRS());
	
	// For solving the grid-lock issue in the multi-thread mode
	private AtomicInteger lastMoveTick = new AtomicInteger(-1);
	private AtomicInteger lastVisitTick = new AtomicInteger(-1);
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
		this.currentCoord_ = new Coordinate();
		this.activityPlan = new ArrayList<Plan>(); // Empty plan

		this.length = GlobalVariables.DEFAULT_VEHICLE_LENGTH;
		this.travelPerTurn = GlobalVariables.TRAVEL_PER_TURN;
		this.maxAcceleration_ = 3.0;
		this.maxDeceleration_ = -4.0;
		this.normalDeceleration_ = -0.5;
		this.accPlan_ = new LinkedList<Double>();
		this.accDecided_ = false;

		this.previousEpochCoord = new Coordinate();
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
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		this.road = null;
		this.nextRoad_ = null;
		this.coordMap = new ArrayList<Coordinate>();
		this.originRoad_ = null;
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
		this.originRoad_ = this.destRoad_;
		this.destinationID = next.getDestZoneID();
		double duration = next.getDuration();
		this.deptime = (int) duration;
		this.destRoad_ = ContextCreator.getRoadContext().get(next.getDestRoadID());
		this.atOrigin = true; // The vehicle will be rerouted to the new target when enters a new link.
		this.activityPlan.remove(0); // Remove current schedule
	}
	
	public void setNextPlan(int delay) { // departure time is right away after a specific delay 
		Plan next = this.activityPlan.get(1);
		this.originID = this.destinationID;
		this.originRoad_ = this.destRoad_;
		this.destinationID = next.getDestZoneID();
		double duration = next.getDuration();
		this.deptime = Math.max((int) duration, ContextCreator.getCurrentTick() + delay);
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
		return enterNetwork(road, firstlane);
	}
	
	/**
	 * Vehicle enters the road, success when the road has enough space in the specified lane
	 * @param The road and the lane that the vehicle enter
	 * @return Whether the road successfully enter the road 
	 */
	public boolean enterNetwork(Road road, Lane lane) {
		// Sanity check
		if(lane.getRoad() != road) return false;
		double gap = entranceGap(lane);
		int tickcount = ContextCreator.getCurrentTick();
		if (gap >= 1.2 * this.length() && tickcount > lane.getAndSetLastEnterTick(tickcount)) {
			this.getAndSetLastMoveTick(tickcount);
			currentSpeed_ = 0.0; // The initial speed
			this.distance_ = 0;
			this.setPreviousEpochCoord(lane.getStartCoord());
			this.setCurrentCoord(lane.getStartCoord());
			this.appendToLane(lane);
			this.appendToRoad(road);
			this.setNextRoad();
			return true;
		}
		return false;
	}
	

	/**
	 * Append vehicle to the pending list to the closest road
	 */
	public void departure() {
		this.numTrips ++;
		this.isReachDest = false;
		if(!this.isOnRoad()) { // If the vehicle not in the network, we add it to a pending list to the closest link
			Road road = ContextCreator.getCityContext().findRoadAtCoordinates(this.getCurrentCoord(), false);  
			road.addVehicleToPendingQueue(this);
		}
		else { // The vehicle is on road, we just need to reroute it
			this.rerouteAndSetNextRoad(); // refresh the CoordMap
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
		ContextCreator.logger.info("At origin: " + this.atOrigin);
		if (!this.atOrigin) { // Not at origin
			// Special case, the roadPath is null which means the origin
			// and destination are at the same link
			ContextCreator.logger.info("Road path: " + this.roadPath);
			if (this.roadPath == null) {
				this.nextRoad_ = null;
				return;
			}
			ContextCreator.logger.info("Before remove shadow count");
			this.removeShadowCount(this.roadPath.get(0));
			this.roadPath.remove(0);
			ContextCreator.logger.info("THIS.ROAD " + this.road);
			ContextCreator.logger.info("DEST.ROAD " + this.getDestRoad());
			if (this.road.getID() == this.getDestRoad() || this.roadPath.size() <= 1) {
				this.nextRoad_ = null;
			} else {
				this.nextRoad_ = this.roadPath.get(1);
				this.assignNextLane();
			}
		} else {
			this.rerouteAndSetNextRoad();
		}
	}
	
	/**
	 * Reroute the vehicle in the middle of the road
	 */
	public void rerouteAndSetNextRoad() {
		// Vehicle departured
		this.atOrigin = false;
		// Clear legacy impact
		this.clearShadowImpact();
		this.roadPath = RouteContext.shortestPathRoute(this.getRoad(), this.destRoad_, this.rand_route_only); // K-shortest path or shortest path
		this.setShadowImpact();
		if (this.roadPath == null) {
			ContextCreator.logger.error("No path can be found between road: " + this.getRoad().getID() + " and road " + this.destRoad_.getID());
			this.nextRoad_ = null;
		}
		else if (this.roadPath.size() < 2) { // The origin and destination road is the same so this vehicle has arrived
			this.nextRoad_ = null;
		} else {
			this.nextRoad_ = roadPath.get(1);
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
	 * This function change the lane of a vehicle regardless it is MLC or DLC state.
	 * The vehicle change lane when its lead and lag gaps are acceptable. This will
	 * not change the speed of the vehicle, the information updated in this function
	 * function is as follow: remove the vehicle from old lane and add to new lane,
	 * re-assign the leading and trailing sequence of the vehicle, update the to-visit
	 * coordinate sequences.
	 **/
	public void changeLane(Lane plane) {
		// Calculate distance based on coords
		Coordinate currCoord = this.getCurrentCoord();
	    ArrayList<Coordinate> coords = lane.getCoords();
	    ArrayList<Coordinate> newCoordMap = new ArrayList<>();
	    double newDistance = 0;

	    for (int i = coords.size() - 1; i > 0; i--) {
	        Coordinate a = coords.get(i);
	        Coordinate b = coords.get(i - 1);

	        double dx = b.x - a.x;
	        double dy = b.y - a.y;
	        double lenSq = dx * dx + dy * dy;
	        double segmentLen = this.distance(a, b);

	        if (lenSq > 0) {
	            double apx = currCoord.x - a.x;
	            double apy = currCoord.y - a.y;
	            double param = (apx * dx + apy * dy) / lenSq;
	            if (param >= 0.0) {
		            for (int j = i; j < coords.size(); j++) {
		                newCoordMap.add(coords.get(j));
		            }
		            break;
		        }
	        }
	        newDistance += segmentLen;
	    }
	    
	    if(newCoordMap.size() == 0) { // Did not find where to insert
	    	return;
	    }
	    
	    double transitionDistance= this.distance(this.getCurrentCoord(), newCoordMap.get(0));
	    if(transitionDistance <= GlobalVariables.NO_LANECHANGING_LENGTH) { // If the transition distance is too high, stop the lane changing
	    	this.nextDistance_ = transitionDistance;
		    this.distance_ = newDistance + transitionDistance;
		    this.coordMap.clear();
		    this.coordMap.addAll(newCoordMap);
		    
			this.removeFromCurrentLane();
			this.distance_ += this.distance(this.currentCoord_, this.coordMap.get(0));
			this.insertToLane(plane);
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
				if (this.distance_ + 1e-4 >= accDist) { // Find the first pt in CoordMap that has smaller distance_, add noise to avoid numerical issue
					for (int j = i + 1; j < coords.size(); j++) { // Add the rest coords into the CoordMap
						coordMap.add(coords.get(j));
					}
					break;
				}
			}
			if (coordMap.size() == 0) {
				ContextCreator.logger.error("Lane changing error, could not find coordMap for the target lane:" + lane.getID() + ", accDist: " + accDist+ ", distance: "+ this.distance_);
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
			 // edge case, two vehicle share the same distance, this can happen due to the accuracy loss in the co-sim map
			 if(toCheckVeh.getDistanceToNextJunction() == this.distance_) {
				 this.distance_ = this.distance_ + 0.001; // edge case add a tiny value to the distance of the to-insert vehicle
			 }
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
					distance2(coords.get(i), coords.get(i+1), distAndAngle);
					double distToMove = distAndAngle[0] - (this.distance_ - accDist);
					if (distToMove > 0) {
						move2(coords.get(i), coords.get(i+1), distAndAngle[0], distToMove); // Update vehicle location
					}
					this.nextDistance_ = (this.distance_ - accDist);
					this.bearing_ = distAndAngle[1];
					
					for (int j = i + 1; j < coords.size(); j++) { // Add the rest coords into the CoordMap
						coordMap.add(coords.get(j));
					}
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
	
	/**
	 * At the start of each simulation step, update the vehicles' decisions on acceleration and lane changing
	 */
	public void calcState() {
		if (ContextCreator.getCurrentTick() % 10 == 0) {
			// re-sample the target speed every 3 seconds to mimic the behavior of 
			// following background traffic (i.e., human-drive vehicles)
			// add this randomness will not change the travel time significantly, but
			// will increase the energy consumption, which creates 
			// more conservative metrics for planning/design
			this.desiredSpeed_ = this.lane.getRandomFreeSpeed(rand_car_follow_only.nextGaussian());
		}
		if (!this.accDecided_) {
			this.makeAcceleratingDecision();
		}
		else {
			this.accDecided_ = false;
		}

		if (this.road.getNumberOfLanes() > 1 && this.isOnLane() && (this.distance_ >= GlobalVariables.NO_LANECHANGING_LENGTH)) {
			this.makeLaneChangingDecision();
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

			if (aZ < acc)
				acc = aZ; // car-following rate

			if (acc < maxDeceleration_) {
				acc = maxDeceleration_;
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
		// car following and road ends
		if (this.nextRoad_ != null && this.road.getID() != this.nextRoad_.getID()) {
			Junction nextJunction = ContextCreator.getJunctionContext().get(this.road.getDownStreamJunction());
			
			if (nextJunction.getDelay(this.road.getID(), this.nextRoad_.getID())>0) { // edge case 1: brake for the red light
				double decTime = this.currentSpeed_ / this.normalDeceleration_;
				if (this.distance_ <= 0.5 * this.currentSpeed_ * decTime) {
					return  (Math.max(this.maxDeceleration_, - 0.5 * (this.currentSpeed_ * this.currentSpeed_
							- this.nextRoad_.getSpeedLimit() * this.nextRoad_.getSpeedLimit()) / this.distance_));
				}
			}
			
			if (this.nextRoad_.getSpeedLimit() < this.currentSpeed_) { // edge case 2: brake to prepare for entering the next road
				double decTime = (this.currentSpeed_ - this.nextRoad_.getSpeedLimit()) / this.normalDeceleration_;
				if (this.distance_ <= 0.5 * (this.currentSpeed_ + this.nextRoad_.getSpeedLimit()) * decTime) {
					return  (Math.max(this.maxDeceleration_, - 0.5 * (this.currentSpeed_ * this.currentSpeed_
							- this.nextRoad_.getSpeedLimit() * this.nextRoad_.getSpeedLimit()) / this.distance_));
				}
			}
		}
		
		// free flow
		if (this.currentSpeed_ < this.desiredSpeed_) { // accelerate to reach the desired speed
			return Math.min(this.maxAcceleration(), (this.desiredSpeed_ - this.currentSpeed_) / GlobalVariables.SIMULATION_STEP_SIZE);
		} else { // decelerate if it exceeds the desired speed
			return Math.max(this.normalDeceleration_, (this.desiredSpeed_ - this.currentSpeed_) / GlobalVariables.SIMULATION_STEP_SIZE);
		}
	}
	
	/**
	 * Calculate the vehicle acceleration based on its distance to front vehicle
	 * 
	 * @param front Front vehicle, can be null which means there is no front vehicle
	 * @return acc Vehicle acceleration
	 */
	public double calcCarFollowingRate(Vehicle front) {
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

		// There will be three regimes emergency/free-flow/car-following regime
		// depending on headway
		// Emergency regime
		if (headway < hlower) {
			double dv = currentSpeed_ - front.currentSpeed_;
			if (dv < 0.0f) { // the leader is decelerating
				acc = front.accRate_ + 0.25f * normalDeceleration_;
			} else {
				if(space <= 0) {
					space = 0.01f;
				}
				acc = front.accRate_ - 0.5f * dv * dv / space;
			}
			acc = Math.min(this.normalDeceleration_, acc);
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
	
	/**
	 * Get the front vehicle
	 * @return v The front vehicle, null for no vehicle ahead
	 */
	public Vehicle vehicleAhead() {
		if (leading_ != null) {
			return leading_;
		} else if (nextLane_ != null) {
			Vehicle v = nextLane_.lastVehicle();
			if (v != null)
				return v;
			else
				return null;
		} else {
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
		if (front != null && front.getLane() != null) { /* vehicle ahead */
			if (this.lane.getID() == front.getLane().getID()) { /* same lane */
				headwayDistance = this.distance_ - front.getDistanceToNextJunction() - front.length();
				
			} else { /* different lane */
				headwayDistance = this.distance_ +  front.getLane().getLength() - front.getDistanceToNextJunction(); // front
																												// vehicle
																												// is in
																												// the
																												// next
																												// road
			}
		} else { /* no vehicle ahead. */
			headwayDistance = Double.MAX_VALUE;
		}

		return (headwayDistance);
	}
	
	/**
	 * The Lane-Changing model for calculating the lane changing decisions
	 */
	public boolean makeLaneChangingDecision() {
		if (this.distFraction() < 0.5) {
			// Halfway to the downstream intersection, only mantatory LC allowed, check the
			// correct lane
			if (!this.isInCorrectLane()) { // change lane if not in correct
				// lane
				Lane tarLane = this.tempLane();
				if (tarLane != null)
					this.mandatoryLC(tarLane);
				    return true;
			}
		} else if(this.distFraction() < 1.0){
			if (this.distFraction() > 0.75) {
				// First 25% in the road, do discretionary LC
				double laneChangeProb1 = rand_car_follow_only.nextDouble();
				// The vehicle is at beginning of the lane, it is free to change lane
				Lane tarLane = this.findBetterLane();
				if (tarLane != null) {
					if (laneChangeProb1 < GlobalVariables.LANE_CHANGING_PROB_PART1)
						this.discretionaryLC(tarLane);
					    return true;
				}
			} else {
				// First 25%-50% in the road, we do discretionary LC but only to correct lanes
				double laneChangeProb2 = rand_car_follow_only.nextDouble();
				// The vehicle is at beginning of the lane, it is free to change lane
				Lane tarLane = this.findBetterCorrectLane();
				if (tarLane != null) {
					if (laneChangeProb2 < GlobalVariables.LANE_CHANGING_PROB_PART2)
						this.discretionaryLC(tarLane);
					    return true;
				}

			}
		}
		return false;
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
		
		if (!this.isOnLane()) {   // Case 1: At an intersection
			Road r = this.getRoad(); 
			if (!this.changeRoad()) { // False means the vehicle cannot enter the next road
				lastStepMove_ = 0;
				this.currentSpeed_ = 0.0f;
				this.accRate_ = 0.0f;
				this.movingFlag = false;
			} else { // Successfully entered the next road
				r.recordEnergyConsumption(this); // Log the info of travel time and energy consumption
				r.recordTravelTime(this);
				lastStepMove_ = distance_; // Update the lastStepMove and accumulatedDistance
				this.movingFlag = true;
			}
		}
		else {
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

			// Solve the crash problem
			double gap = gapDistance(this.vehicleAhead());
			dx = Math.min(dx, gap); // no trespass
			
			// Update vehicle coords
			lastStepMove_ = updateCoordByDx(dx);
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
		
		// Update the vehicle position in the LinkedList
		if (this.trailing_ == this) {
			ContextCreator.logger.error("Something went wrong, the trailing vehicle is itslef!");
		}
		this.advanceInMacroList(); // If the vehicle travel too fast, it will change the marcroList of the road.
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
	 * Moving vehicle to its closest road.
	 */
	public void primitiveMove(Road road) {
		Coordinate currentCoord = this.getCurrentCoord();
		Coordinate target = road.getStartCoord();
		if (this.isReachDest) {
			return;
		}

		double[] distAndAngle = new double[2];
		double distToTarget;
		distToTarget = this.distance2(currentCoord, target, distAndAngle);

		if (distToTarget <= travelPerTurn) { // Include the equal case, which is important
			this.setCurrentCoord(target);
		} else {
			double distToTravel = travelPerTurn;
			move2(currentCoord, target, distToTarget, distToTravel);
		}
		return;
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
			// Check if there is enough space in the next road to change to
			int tickcount = ContextCreator.getCurrentTick();
			coordMap.clear();
			coordMap.add(this.getCurrentCoord());
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

			if(movable) {	
				// Check if the target road has space
				if(this.nextRoad_.getControlType() == Road.COSIM) {
					// For cosim road, get the last vehicle, check whether the distance is greater than 1.2 * this.length
					Vehicle lastVeh = nextLane_.lastVehicle();
					if((lastVeh == null) && (tickcount > this.nextLane_.getAndSetLastEnterTick(tickcount))) {
						this.enterNextLane(nextLane_);
						this.removeFromCurrentLane();
						this.removeFromCurrentRoad();
						this.appendToLane(nextLane_);
						this.appendToRoad(nextRoad_);
						this.setNextRoad();
						return true;
					}
					else {
						Coordinate c1 = lastVeh.getCurrentCoord();
						// Get dist between the coord and the begining coord of the lane
						Coordinate c2 = nextLane_.getStartCoord();
						if((ContextCreator.getCityContext().getDistance(c1, c2) >= 1.2 * this.length()) && (tickcount > this.nextLane_.getAndSetLastEnterTick(tickcount))){
							this.enterNextLane(nextLane_);
							this.removeFromCurrentLane();
							this.removeFromCurrentRoad();
							this.appendToLane(nextLane_);
							this.appendToRoad(nextRoad_);
							this.setNextRoad();
							return true;
						}
					}
				}
				else {
					if ((this.entranceGap(nextLane_) >= 1.2 * this.length()) && (tickcount > this.nextLane_.getAndSetLastEnterTick(tickcount))) { //Update enter tick so other vehicle cannot enter
						this.enterNextLane(nextLane_);
						this.removeFromCurrentLane();
						this.removeFromCurrentRoad();
						this.appendToLane(nextLane_);
						this.appendToRoad(nextRoad_);
						this.setNextRoad();
						return true;
					}
					else if (this.stuckTime >= GlobalVariables.MAX_STUCK_TIME) { // addressing gridlock
						for(Integer dnlaneID: this.lane.getDownStreamLanes()) {
							Lane dnlane = ContextCreator.getLaneContext().get(dnlaneID);
							List<Road> tempPath = RouteContext.shortestPathRoute(dnlane.getRoad(), 
									ContextCreator.getRoadContext().get(this.getDestRoad()), this.rand_route_only); // Recalculate the route
							if (tempPath != null && tempPath.size()>=2 && this.entranceGap(dnlane) >= 1.2*this.length() && (tickcount > dnlane.getAndSetLastEnterTick(tickcount))) {
								this.enterNextLane(dnlane);
								this.removeFromCurrentLane();
								this.removeFromCurrentRoad();
								this.appendToLane(dnlane);
								this.appendToRoad(dnlane.getRoad());
								this.rerouteAndSetNextRoad();
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
	 * Max acceleration based on IDM model
	 * @return maximum acceleration
	 */
	public double maxAcceleration() {
		return maxAcceleration_ * (1 - Math.pow(this.currentSpeed_/this.desiredSpeed_, 4));
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
			ContextCreator.logger.warn("Attempt to insert a vehicle itself as the leading with distance" + this.distance_);
			this.leading_ = null;
		}
		else if(v.distance_ > this.distance_) {
			ContextCreator.logger.warn("Attempt to insert a behind vehicle with distance " + v.getDistanceToNextJunction() +" to the leading of the vehicle with distance " + this.distance_);
			this.leading_ = null;
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
			ContextCreator.logger.warn("Attempt to insert a vehicle itself as the trailing with distance" + this.distance_);
			this.trailing_ = null;
		}
		else if(v.getDistanceToNextJunction() < this.distance_) {
			ContextCreator.logger.warn("Attempt to insert a front vehicle with distance " +v.getDistanceToNextJunction() +" to the trailing of the vehicle with distance " + this.distance_);
			this.trailing_ = null;
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
		if(originRoad_ != null)
			return this.originRoad_.getStartCoord();
		return this.getCurrentCoord();
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
		this.isReachDest = true;
		this.accummulatedDistance_ = 0;
		// Vehicle arrive
		this.endTime = ContextCreator.getCurrentTick();
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
		this.endTime = 0;
		this.atOrigin = true;
		this.accRate_ = 0;
		this.nextLane_ = null;
		this.nosingFlag = false;
		this.yieldingFlag = false;
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		this.nextRoad_ = null;
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
					this.lane.firstVehicle(curTrailing);
				}
			} else if (curLeading != null) {
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
	 * Update the tick that we call the vehicle move function, used to maintain the integrity when vehicle moves across multiple links
	 * @param current_tick current tick
	 * @return last move tick
	 */
	public int getAndSetLastMoveTick(int current_tick) {
		return this.lastMoveTick.getAndSet(current_tick);
	}
	
	public int getAndSetLastVisitTick(int current_tick) {
		return this.lastVisitTick.getAndSet(current_tick);
	}
	
	public int getLastVisitTick() {
		return this.lastVisitTick.get();
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
	 * Advance a vehicle to the position in macro vehicle list that corresponding to
	 * its current distance. This function is invoked whenever a vehicle is moved
	 * (including moved into a downstream segment), so that the vehicles in macro
	 * vehicle list is always sorted by their position. 
	 */
	public void advanceInMacroList() {
		// (0) Check if vehicle should be advanced in the list
		if (macroLeading_ == null || this.distFraction() >= macroLeading_.distFraction()) {
			// No macroLeading or the distance to downstream node is greater
			// than marcroLeading. No need to advance this vehicle in list
			return;
		}
		// (1) Find vehicle's position in the list
		// Now this vehicle has a macroLeading that has the higher distance to
		// downstream node which should not be the vehicle marcroLeading anymore.
		// Need to find new marcroLeading.
		Vehicle front = macroLeading_;
		while (front != null && this.distFraction() < front.distFraction()) {
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
		}
		ContextCreator.logger.error("Cannot assign next lane form the curLane " + curLane + " curRoad " + this.getRoad() + " nextRoad" + this.nextRoad_ + " roadPath" + this.roadPath);
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
	public void mandatoryLC(Lane plane) {
		Vehicle leadVehicle = this.leadVehicle(plane);
		Vehicle lagVehicle = this.lagVehicle(plane);
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
				if (this.leadGap(leadVehicle, plane) >= this.critLeadGapMLC(leadVehicle, plane)
						&& this.lagGap(lagVehicle, plane) >= this.critLagGapMLC(lagVehicle, plane)) {
					this.changeLane(plane);
					this.nosingFlag = false;
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}
			} else {
				if (this.leadGap(leadVehicle, plane) >= this.critLeadGapMLC(leadVehicle, plane)) {
					this.changeLane(plane);
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}

			}
		} else {
			if (lagVehicle != null) {
				if (this.lagGap(lagVehicle, plane) >= this.critLagGapMLC(lagVehicle, plane)) {
					this.changeLane(plane);
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}
			} else
				this.changeLane(plane);
		}
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
			Vehicle leadVehicle = this.leadVehicle(tarLane);
			Vehicle lagVehicle = this.lagVehicle(tarLane);
			/*
			 * 0. If there is a lag vehicle in the target lane, the vehicle will yield that
			 * lag vehicle however, the yielding is only true if the distance is less than
			 * some threshold
			 */
			lagGap = this.lagGap(lagVehicle, tarLane);
			if (lagVehicle != null) {
				if (lagGap < GlobalVariables.MIN_LAG) {
					this.yieldingFlag = true;
				}
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
				if (this.leadGap(leadVehicle, tarLane) < this.critLeadGapMLC(leadVehicle, tarLane)) {
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
	public double leadGap(Vehicle leadVehicle, Lane plane) {
		double leadGap = 0;
		if (leadVehicle != null) {
			leadGap = this.distFraction() * plane.getLength() - leadVehicle.getDistanceToNextJunction() - leadVehicle.length(); // leadGap>=-leadVehicle.length()
		} else {
			leadGap = this.distFraction() * plane.getLength();
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
	public double lagGap(Vehicle lagVehicle, Lane plane) {
		double lagGap = 0;
		if (lagVehicle != null)
			lagGap = lagVehicle.getDistanceToNextJunction() - this.distFraction() * plane.getLength() - this.length();
		else {
			lagGap = this.lane.getLength() - this.distFraction() * plane.getLength();
		}
		return lagGap;
	}

	/**
	 * Find the lead vehicle in target lane
	 * @param plane Target lane
	 * @return Vehicle leadVehicle
	 */
	public Vehicle leadVehicle(Lane plane) {
		Vehicle leadVehicle = this.macroLeading_;
		while (leadVehicle != null && leadVehicle.lane != plane) {
			leadVehicle = leadVehicle.macroLeading_;
		}
		return leadVehicle;
	}

	/**
	 * Find lag vehicle in target lane
	 * @param plane Target lane
	 * @return Vehicle lag Vehicle
	 */
	public Vehicle lagVehicle(Lane plane) {
		Vehicle lagVehicle = this.macroTrailing_;
		while (lagVehicle != null && lagVehicle.lane != plane) {
			lagVehicle = lagVehicle.macroTrailing_;
		}
		return lagVehicle;
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
			// If we have a target lane, then compare the speed of
			// front bumper leader in the lane with current leader
			if (targetLane != null && !targetLane.equals(curLane)) {
				Vehicle front = this.leadVehicle(targetLane);
				if (front == null) {
					return targetLane;
				} else if (this.leading_ != null && this.leading_.currentSpeed() < this.desiredSpeed_
						&& this.currentSpeed_ < this.desiredSpeed_) {
					if (front.currentSpeed_ > this.currentSpeed_ && front.accRate_ > 0)
						return targetLane;
				}
			}
			return null;
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
			// If we have a target lane, then compare the speed of
			// front bumper leader in the lane with current leader
			if (targetLane != null && !targetLane.equals(curLane)) {
				Vehicle front = this.leadVehicle(targetLane);
				if (front == null) {
					return targetLane;
				} else if (this.leading_ != null && this.leading_.currentSpeed_ < this.desiredSpeed_
						&& this.currentSpeed_ < this.desiredSpeed_) {
					if (front.currentSpeed_ > this.currentSpeed_ && front.accRate_ > 0)
						return targetLane;

				}
			}
			return null;
		}

	}

	/**
	 * Given a target lane, ask vehicle to change to that lane discretionarily.
	 * @param plane Target lane
	 */
	public void discretionaryLC(Lane plane) {
		Vehicle leadVehicle = this.leadVehicle(plane);
		Vehicle lagVehicle = this.lagVehicle(plane);
		double leadGap = this.leadGap(leadVehicle, plane);
		double lagGap = this.lagGap(lagVehicle, plane);
		double critLead = this.criticalLeadDLC(leadVehicle);
		double critLag = this.criticalLagDLC(lagVehicle);
		if (leadGap > critLead && lagGap > critLag) { // there exists enough space for lane changing
			this.changeLane(plane);
		}
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
		this.setCurrentCoord(new Coordinate((1 - p) * origin.x + p * target.x, (1 - p) * origin.y + +p * target.y));
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
	
	public double getBearing() {
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
				ArrayList<Double> xy = new ArrayList<Double>();
				try {
					JTS.transform(coord, coord,
							SumoXML.getData(GlobalVariables.NETWORK_FILE).transform.inverse());
					xy.add(coord.x);
					xy.add(coord.y);
				} catch (TransformException e) {
					ContextCreator.logger
							.error("Coordinates transformation failed, input x: " + coord.x + " y:" + coord.y);
					e.printStackTrace();
				}
				res.add(xy);
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
}
