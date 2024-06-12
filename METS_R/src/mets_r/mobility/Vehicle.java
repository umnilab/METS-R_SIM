package mets_r.mobility;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.output.DataCollector;
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

import repast.simphony.space.gis.Geography;

/**
 * Inherit from A-RESCUE
 * 
 * General vehicle
 * 
 * @author Xianyuan Zhan, Xinwu Qian, Hemant Gehlot, Zengxiang Lei
 **/

public class Vehicle {
	/* Constants */
	public final static int GV = 0; // Private gasoline vehicle
	public final static int ETAXI = 1;
	public final static int EBUS = 2;
	public final static int EV = 3; // Private electric vehicle
	
	public final static int DSRC = 0;
	public final static int CV2X = 1;
	
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
	
	/* Private variables that are not visible to descendant classes */
	private int destRoadID;
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
	private Coordinate originCoord;
	private Coordinate destCoord;
	private Coordinate previousEpochCoord;// This variable stores the coordinates of the vehicle when last time
										  // vehicle snapshot was recorded for visualization interpolation
	private boolean isReachDest;
	private boolean onLane; // On a lane, false when the vehicle is in an intersection or not on road
	private boolean onRoad; // On a road, false when the vehicle is parking/charging
	private Road road;
	private Lane lane;
	
	// Vehicle class, status, and sensorType
	private int vehicleClass; 
	private int vehicleSensorType;
	private int vehicleState; 
	
	// For vehicle based routing
	private List<Coordinate> coordMap;
	private Geography<Lane> laneGeography;
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
	private ArrayList<Plan> activityPlan; // A set of zone for the vehicle to visit
	
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
		this.laneGeography = ContextCreator.getLaneGeography();
		this.coordMap = new ArrayList<Coordinate>();
		this.setDestRoadID(-1);
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
		this.destinationID = next.getDestID();
		double duration = next.getDuration();
		this.deptime = (int) duration;
		this.destCoord = next.getLocation();
		this.setDestRoadID(ContextCreator.getCityContext().findRoadAtCoordinates(this.destCoord).getID());
		this.atOrigin = true; // The vehicle will be rerouted to the new target when enters a new link.
		this.activityPlan.remove(0); // Remove current schedule
	}
	
	/**
	 * Initialize the vehicle state 
	 */
	public void initializePlan(int loc_id, Coordinate location, double d) {
		this.setCurrentCoord(location);
		this.addPlan(loc_id, location, d); 
		this.addPlan(loc_id, location, d); 
		this.setNextPlan(); // This will set the origin to 0 and dest to loc_id
		this.addPlan(loc_id, location, d);
		this.setNextPlan(); // This will set the origin to the loc_id
	}
	
	/**
	 * Modify the current destination zone and location of the vehicle
	 * @param dest_id Target zone ID
	 * @param location Target location
	 * @return
	 */
	public boolean modifyPlan(int dest_id, Coordinate location) {
		if(this.isOnRoad()) {
			if(this.activityPlan.size() > 1) {
				ContextCreator.logger.error("Something went wrong, cannot modify the vehicle with multiple plans");
			}
			this.activityPlan.clear();
			this.addPlan(dest_id, location, ContextCreator.getNextTick());
			this.destinationID = dest_id;
			this.destCoord = location;
			// Reroute it
			List<Road> tempPath = RouteContext.shortestPathRoute(this.getRoad(),location,this.rand_route_only); // Recalculate the route
			this.clearShadowImpact();
			// Set new route
			this.roadPath = tempPath;
			this.setShadowImpact();
			this.setNextRoad();
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
		double gap = entranceGap(firstlane);
		int tickcount = ContextCreator.getCurrentTick();
		if (gap >= 1.2 * this.length() && tickcount > firstlane.getAndSetLastEnterTick(tickcount)) {
			this.getAndSetLastMoveTick(tickcount);
			currentSpeed_ = 0.0; // The initial speed
			this.appendToLane(firstlane);
			this.appendToRoad(road);
			this.setNextRoad();
			return true;
		}
		return false;
	}
	
	/**
	 * Vehicle enters the road, success when the road has enough space in the specified lane
	 * @param The road and the lane that the vehicle enter
	 * @return Whether the road successfully enter the road 
	 */
	public boolean enterNetwork(Road road, Lane lane) {
		// Sanity check
		if(lane.getRoad() != road.getID()) return false;
		double gap = entranceGap(lane);
		int tickcount = ContextCreator.getCurrentTick();
		if (gap >= 1.2 * this.length() && tickcount > lane.getAndSetLastEnterTick(tickcount)) {
			this.getAndSetLastMoveTick(tickcount);
			currentSpeed_ = 0.0; // The initial speed
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
		this.isReachDest = false;
		if(!this.isOnRoad()) { // If the vehicle not in the network, we add it to a pending list to the closest link
			Road road = ContextCreator.getCityContext().findRoadAtCoordinates(this.getCurrentCoord());
			// The first list of coordinates for the vehicle to follow
			Coordinate[] coords = laneGeography.getGeometry(road.firstLane()).getCoordinates();
			for (Coordinate coord : coords) {
				this.coordMap.add(coord);    
			}
			road.addVehicleToPendingQueue(this);
		}
		else { // The vehicle is on road, we just need to reroute it
			this.setNextRoad(); // refresh the CoordMap
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
		if (!this.atOrigin) { // Not at origin
			// Special case, the roadPath is null which means the origin
			// and destination are at the same link
			if (this.roadPath == null) {
				this.nextRoad_ = null;
				return;
			}
			// Stick on the routed path, modify this if you want to implement dynamic routing
			this.removeShadowCount(this.roadPath.get(0));
			this.roadPath.remove(0);
			if (this.road.getID() == this.getDestRoadID() || this.roadPath.size() <= 1) {
				this.nextRoad_ = null;
			} else {
				this.nextRoad_ = this.roadPath.get(1);
				this.assignNextLane();
			}
		} else {
			// Clear legacy impact
			this.clearShadowImpact();
			this.roadPath = new ArrayList<Road>();
			this.roadPath = RouteContext.shortestPathRoute(this.getRoad(), this.destCoord, this.rand_route_only); // K-shortest path or shortest path
			this.setShadowImpact();
			if (this.roadPath == null) {
				ContextCreator.logger.error("Routing fails with origin: " + this.getRoad().getID() + ", destination " + this.getDestCoord() + 
						", destination road " + this.getDestRoadID());
				this.atOrigin = false;
				this.nextRoad_ = null;
			}
			else if (this.roadPath.size() < 2) { // The origin and destination share the same Junction
				this.atOrigin = false;
				this.nextRoad_ = null;
			} else {
				this.atOrigin = false;
				this.nextRoad_ = roadPath.get(1);
				this.assignNextLane();
			}
		}
	}
	
	/**
	 * Get the next to-visit road of this vehicle
	 */
	public Road getNextRoad() {
		return this.nextRoad_;
	}

	/**
	 * Append a vehicle to vehicle list in a specific lane
	 * @param plane Target lane
	 */
	public void appendToLane(Lane plane) {
		if (plane != null) {
			this.distance_ =  plane.getLength();
			this.lane = plane;
			Vehicle v = plane.lastVehicle();
			this.insertToLane(plane, v, null);
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
	public void insertToLane(Lane plane, Vehicle leadVehicle, Vehicle lagVehicle) {
		if (leadVehicle != null) {
			this.leading_ = leadVehicle;
			this.leading_.trailing(this);
			if (lagVehicle != null) {
				this.trailing_ = lagVehicle;
				this.trailing_.leading(this);
			} else {
				plane.lastVehicle(this);
			}
		} else if (lagVehicle != null) {
			plane.firstVehicle(this);
			this.trailing_ = lagVehicle;
			this.trailing_.leading(this);
		} else {
			plane.firstVehicle(this);
			plane.lastVehicle(this);
		}

		this.updateCoordMap(plane);
		this.lane = plane;
		this.lane.addOneVehicle();
		this.onLane = true;
	}

	/**
	 * For update the to-visit coordinates of vehicle to the corresponding lane coordinate
	 * @param lane Target lane of this vehicle
	 */
	private void updateCoordMap(Lane lane) {
		this.distance_ = this.distFraction() * lane.getLength();
		Coordinate[] coords = laneGeography.getGeometry(lane).getCoordinates();
		coordMap.clear();
		double accDist = lane.getLength();
		for (int i = 0; i < coords.length - 1; i++) {
			accDist -= distance(coords[i], coords[i + 1]);
			if (this.distance_ >= accDist) { // Find the first pt in CoordMap that has smaller distance_;
				this.setCurrentCoord(coords[i]); // Set current coord
				double[] distAndAngle = new double[2];
				distance2(coords[i], coords[i + 1], distAndAngle);
				double distToMove = distAndAngle[0] - (this.distance_ - accDist);
				if (distToMove > 0) {
					move2(coords[i], coords[i + 1], distAndAngle[0], distToMove); // Update
					// vehicle
					// location
				}
				this.nextDistance_ = (this.distance_ - accDist);
				this.bearing_ = distAndAngle[1];
				for (int j = i + 1; j < coords.length; j++) { // Add the rest coords into the CoordMap
					coordMap.add(coords[j]);
				}
				break;
			}
		}
		if (coordMap.size() == 0) {
			ContextCreator.logger.error("Lane changing error, could not find coordMap for the target lane!");
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

		if (this.road.getNumberOfLanes() > 1 && this.isOnLane() && this.distance_ >= GlobalVariables.NO_LANECHANGING_LENGTH) {
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
		if (this.onLane) {
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
			if (v != null && v.isOnLane())
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
				if (front.isOnLane()) {
					headwayDistance = this.distance_ - front.getDistance() - front.length();
				} else { // Front vehicle is entering the intersection, i.e., at the stop line
					headwayDistance = this.distance_ - front.length();
				}
			} else { /* different lane */
				headwayDistance = this.distance_ +  front.getLane().getLength() - front.getDistance(); // front
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
			if (!this.isCorrectLane()) { // change lane if not in correct
				// lane
				Lane tarLane = this.tempLane();
				if (tarLane != null)
					this.mandatoryLC(tarLane);
				    return true;
			}
		} else {
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
			DataCollector.printDebug("ERR" + t.getMessage());
		}
		setPreviousEpochCoord(currentCoord);// update the previous coordinate as the current coordinate
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
		if (distance_ < 0 || Double.isNaN(distance_))
			ContextCreator.logger.error("Vehicle.move(): distance_=" + distance_ + " " + this);
		if (currentSpeed_ < 0 || Double.isNaN(currentSpeed_))
			ContextCreator.logger.error("Vehicle.move(): currentSpeed_=" + currentSpeed_ + " " + this);
		if (Double.isNaN(accRate_))
			ContextCreator.logger.error("Vehicle.move(): accRate_=" + accRate_ + " " + this);
		
		Road current_road = this.road;
		this.endTime++;
		this.linkTravelTime++;
		
		double lastStepMove_ = 0;
		
		if (!this.isOnLane()) {   // Case 1: At an intersection
			if (!this.changeRoad()) { // False means the vehicle cannot enter the next road
				lastStepMove_ = 0;
				this.currentSpeed_ = 0.0f;
				this.accRate_ = 0.0f;
				this.movingFlag = false;
			} else { // Successfully entered the next road
				current_road.recordEnergyConsumption(this);
				current_road.recordTravelTime(this);
				lastStepMove_ = distance_; // update the lastStepMove and accumulatedDistance
				this.movingFlag = true;
			}
		}
		else {
			double dx = 0; // Travel distance calculated by physics
			boolean travelledMaxDist = false; // True when traveled with maximum distance (=dx).
			double distTravelled = 0; // The distance traveled so far.
			double oldv = currentSpeed_; // Speed at the beginning
			double step = GlobalVariables.SIMULATION_STEP_SIZE;

			// Calculate the actual speed and acceleration
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

			// Actual acceleration rate applied in last time interval.
			accRate_ =  Math.max(this.maxDeceleration_, 2.0f * (dx - oldv * step) / (step * step));

			// Update speed
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
						this.distance_ -= nextDistance_;
						this.nextDistance_ = 0;
						lastStepMove_ = distTravelled;
						if (this.nextRoad_ != null) { // has next road
							this.coordMap.add(this.getCurrentCoord()); 
							if (this.appendToJunction(nextLane_)) { // Successfully entered the next link
								current_road.recordEnergyConsumption(this);
								current_road.recordTravelTime(this);
							}
							break;
						} else { // No next road, the vehicle arrived at the destination
							current_road.recordEnergyConsumption(this);
							current_road.recordTravelTime(this);
							this.coordMap.clear();
							this.coordMap.add(this.currentCoord_);
							break;
						}
					} else {
						this.distance2(this.getCurrentCoord(), this.coordMap.get(0), distAndAngle);
						this.distance_ -= this.nextDistance_;
						this.nextDistance_ = distAndAngle[0];
						this.bearing_ = distAndAngle[1];
					}
				}
				// Otherwise move as far as we can 
				else {
					double distToMove = dx - distTravelled;;
					if(distToMove > 0) {
						this.distance_ -=  distToMove;
						move2(this.getCurrentCoord(), this.coordMap.get(0), nextDistance_, distToMove);
						this.nextDistance_ -= distToMove;
					}
					lastStepMove_ =  dx;
					travelledMaxDist = true;
				}
			}
		}
		
		// Update the position of vehicles, 0<=distance_<=lane.length()
		if (distance_ < 0) {
			distance_ = 0;
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
	
	/**
	 * Moving vehicle to its closest road.
	 */
	public void primitiveMove() {
		Coordinate currentCoord = this.getCurrentCoord();
		Coordinate target = this.coordMap.get(0);
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
	 * This function change the vehicle from its current road to the next road.
	 * 
	 * @return 0-fail , 1-success to change the road
	 */
	public boolean changeRoad() {
		// Check if the vehicle has reached the destination or not
		if (this.isReachDest || this.roadPath.size()<2) {
			this.clearShadowImpact(); // Clear shadow impact if already reaches destination
			return false; // Only reach destination once
		} else if (this.nextRoad_ != null) {
			// Check if there is enough space in the next road to change to
			int tickcount = ContextCreator.getCurrentTick();
			Junction nextJunction = ContextCreator.getJunctionContext().get(this.road.getDownStreamJunction());
			boolean movable = false;
			if(this.nextRoad_.getID() == this.roadPath.get(1).getID()) { // nextRoad data is consistent
				switch(nextJunction.getControlType()) {
				case Junction.NoControl:
					movable = true;
					break;
				case Junction.DynamicSignal:
					if(nextJunction.getSignal(this.road.getID(), this.nextRoad_.getID())<= Signal.Yellow)
						movable = true;
					break;
				case Junction.StaticSignal:
					if(nextJunction.getSignal(this.road.getID(), this.nextRoad_.getID())<= Signal.Yellow)
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
				if ((this.entranceGap(nextLane_) >= 1.2 * this.length()) && (tickcount > this.nextLane_.getAndSetLastEnterTick(tickcount))) { //Update enter tick so other vehicle cannot enter
					this.removeFromLane();
					this.removeFromMacroList();
					this.appendToLane(nextLane_);
					this.appendToRoad(nextRoad_);
					this.setNextRoad();
					return true;
				}
				else if (this.stuckTime >= GlobalVariables.MAX_STUCK_TIME) { // addressing gridlock
					for(Integer dnlaneID: this.lane.getDownStreamLanes()) {
						Lane dnlane = ContextCreator.getLaneContext().get(dnlaneID);
						List<Road> tempPath = RouteContext.shortestPathRoute(ContextCreator.getRoadContext().get(dnlane.getRoad()), 
								ContextCreator.getRoadContext().get(this.getDestRoadID()), this.rand_route_only); // Recalculate the route
						if (tempPath != null && tempPath.size()>=2 && this.entranceGap(dnlane) >= 1.2*this.length() && (tickcount > dnlane.getAndSetLastEnterTick(tickcount))) {
							this.removeFromLane();
							this.removeFromMacroList();
							this.appendToLane(dnlane);
							this.appendToRoad(ContextCreator.getRoadContext().get(dnlane.getRoad()));
							this.clearShadowImpact();
							// Set new route
							this.roadPath = tempPath;
							this.setShadowImpact();
							this.nextRoad_ = this.roadPath.get(1);
							this.assignNextLane();
							return true;
						}
					}
				}
			}
		}
		coordMap.clear();
		coordMap.add(this.getCurrentCoord());// Fail to enter next link, try again in the next tick
		return false;
	}
	
	/**
	 * Check if the vehicle is close to a road, used when the vehicle attempts to depart from its closest road
	 * 
	 * @param road The closest road to the vehicle
	 * @return 0-not close enough to the road , 1-close enough to the road
	 */
	public int closeToRoad(Road road) {
		Coordinate currentCoord = this.getCurrentCoord();
		Coordinate nextCoord;
		if (this.coordMap == null)
			return 0;
		else if (this.coordMap.size() == 0)
			return 0;
		else
			nextCoord = this.coordMap.get(0);
		if (distance(currentCoord, nextCoord) < GlobalVariables.TRAVEL_PER_TURN) {
			return 1;

		} else
			return 0;
	}
	
	/**
	 * Check whether the vehicle arrived at (the start point of) the final link
	 */
	public void checkAtDestination() {
		if (this.nextRoad_ == null && !this.atOrigin) {
			this.reachDest();
		}
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
		if (v != null)
			this.macroLeading_ = v;
		else
			this.macroLeading_ = null;
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
		if (v != null)
			this.macroTrailing_ = v;
		else
			this.macroTrailing_ = null;
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
		if (v != null)
			this.leading_ = v;
		else
			this.leading_ = null;
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
		if (v != null)
			this.trailing_ = v;
		else
			this.trailing_ = null;
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
	public double getDistance() {
		return distance_;
	}
	
	/**
	 * Set distance to the next intersection
	 */
	public void setDistance(double distance) {
		this.distance_ = distance;
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
	 * @param location Destination location
	 * @param d Departure time
	 */
	public void addPlan(int dest_id, Coordinate location, double d) {
		Plan p = new Plan(dest_id, location, d);
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
		return this.originCoord;
	}
	
	/**
	 * Get destination location
	 */
	public Coordinate getDestCoord() {
		return this.destCoord;
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

		if (this.originCoord == null) {
			this.originCoord = coord;
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

		if (this.originCoord == null) {
			this.originCoord = coord;
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
		if(this.getState() == Vehicle.PRIVATE_TRIP) {
			this.vehicleState = Vehicle.NONE_OF_THE_ABOVE;
		}
		this.isReachDest = true;
		this.accummulatedDistance_ = 0;
		// Vehicle arrive
		this.endTime = ContextCreator.getCurrentTick();
		this.leaveNetwork();
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
		this.setPreviousEpochCoord(new Coordinate());
		this.setCurrentCoord(this.destCoord);
		this.originCoord = this.destCoord; // Next origin is the current destination
		this.clearShadowImpact();
		this.removeFromLane();
		this.removeFromMacroList();
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
		this.setDestRoadID(-1);
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
	public void removeFromLane() {
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
	public void removeFromMacroList() {
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
	public boolean isCorrectLane() {
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
				if (ContextCreator.getLaneContext().get(dl).getRoad() == nextRoad.getID()) {
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
				if (ContextCreator.getLaneContext().get(dl).getRoad() == this.nextRoad_.getID()) {
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
	 * This function change the lane of a vehicle regardless it is MLC or DLC state.
	 * The vehicle change lane when its lead and lag gaps are acceptable. This will
	 * not change the speed of the vehicle, the only information updated in this
	 * function is as follow: remove the vehicle from old lane and add to new lane.
	 * Re-assign the leading and trailing sequence of the vehicle.
	 */
	public void changeLane(Lane plane, Vehicle leadVehicle, Vehicle lagVehicle) {
		Lane prevLane = this.lane;
		this.removeFromLane();
		this.lane = prevLane;
		/*
		 * After change the lane the vehicle updates its leading and trailing in the
		 * target lanes. and also the lead and lag vehicle have to update its leading
		 * and trailing.
		 */
		this.insertToLane(plane, leadVehicle, lagVehicle);
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
					this.changeLane(plane, leadVehicle, lagVehicle);
					this.nosingFlag = false;
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}
			} else {
				if (this.leadGap(leadVehicle, plane) >= this.critLeadGapMLC(leadVehicle, plane)) {
					this.changeLane(plane, leadVehicle, null);
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}

			}
		} else {
			if (lagVehicle != null) {
				if (this.lagGap(lagVehicle, plane) >= this.critLagGapMLC(lagVehicle, plane)) {
					this.changeLane(plane, null, lagVehicle);
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}
			} else
				this.changeLane(plane, null, null);
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
		double gama = GlobalVariables.gama;
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
			leadGap = this.distFraction() * plane.getLength() - leadVehicle.getDistance() - leadVehicle.length(); // leadGap>=-leadVehicle.length()
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
		double gama = GlobalVariables.gama;
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
			lagGap = lagVehicle.getDistance() - this.distFraction() * plane.getLength() - this.length();
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
			this.changeLane(plane, leadVehicle, lagVehicle);
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
	 * Get critical lag gap for DLC (discretional lane changing)
	 * @param pv
	 * @return double critLag
	 */
	public boolean appendToJunction(Lane nextlane) {
		if (this.isReachDest) {
			return false;
		} else { // Want to change to next lane
			coordMap.clear();
			coordMap.add(this.getCurrentCoord());
		}

		this.onLane = false;

		if (!this.changeRoad()) {
			return false;
		}

		return true;
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
				gap =  nextlane.getLength() - newleader.distance_ - newleader.length();
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
		if (p < 0 || p > 1) {
			ContextCreator.logger
			.error("Vehicle.move2(): Cannot move " + this.getID() + " from road " + this.road + " with p=" + p + " by dist=" + distanceTravelled + ", " + distanceToTarget);
		}
		else {
			this.setCurrentCoord(new Coordinate((1 - p) * origin.x + p * target.x, (1 - p) * origin.y + +p * target.y));
		}
	}
	
	/**
	 * Manually specify the acceleration
	 * @param acc
	 */
	public void controlVehicleAcc(double acc) {
		this.accPlan_.push(acc);
		this.accDecided_ = true;
	}
	
	public int getVehicleClass() {
		return this.vehicleClass;
	}
	
	public int getVehicleSensorType() {
		return this.vehicleSensorType;
	}
	
	/**
	 * Get vehicle acceleration
	 */
	public double currentAcc() {
		return this.accRate_;
	}
	
	public int getDestRoadID() {
		return this.destRoadID;
	}
	
	public void setDestRoadID(int destRoadID) {
		this.destRoadID = destRoadID;
	}
	
	public double getBearing() {
		return this.bearing_;
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
	public List<Integer> getRoute(){
		List<Integer> res = new ArrayList<Integer>();
		for(Road r: roadPath) {
			res.add(r.getID());
		}
		return res;
	}
}
