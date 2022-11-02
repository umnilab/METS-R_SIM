package mets_r.mobility;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.DataCollector;
import mets_r.facility.CityContext;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.routing.RouteV;

import org.opengis.referencing.operation.MathTransformFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.geotools.referencing.GeodeticCalculator;
import org.geotools.referencing.ReferencingFactoryFinder;

import repast.simphony.essentials.RepastEssentials;
import repast.simphony.space.gis.Geography;
import util.Pair;

/**
 * @author Xianyuan Zhan, Xinwu Qian, Hemant Gehlot, Zengxiang Lei
 * 
 **/

public class Vehicle {
	protected int id;
	protected Random rand;
	protected int vehicleID_;
	// Constants
	public final static int GASOLINE = 0;
	public final static int ETAXI = 1;
	public final static int EBUS = 2;
	public final static int PARKING = 0;
	public final static int OCCUPIED_TRIP = 1;
	public final static int RELOCATION_TRIP = 2;
	public final static int CRUISING_TRIP = 5;
	public final static int PICKUP_TRIP = 6;
	public final static int BUS_TRIP = 3;
	public final static int CHARGING_TRIP = 4;
	public final static int NONE_OF_THE_ABOVE = -1;
	// Vehicle status and class
	private int vehicleClass; // 0 for gasoline vehicle, 1 for electric vehicle, 2 for bus.
	private int vehicleState; // 0 for empty, 1 for normal trip, 2 for relocation trip, 3 for bus trip, 4 for
								// charging trip

	/*
	 * Vehicle movement variables that do not need to be visible to descendant
	 * classes
	 */
	private int destRoadID;
	private Coordinate currentCoord_; // this variable is created when the vehicle is initialized
	private float length;
	private float distance_;// distance from downstream junction
	private double nextDistance_; // distance from the start point of next line segment
	private float currentSpeed_;
	private float accRate_;
	private double bearing_;
	private float desiredSpeed_; // in meter/sec
	private int regime_;
	private float maxAcceleration_; // in meter/sec2
	private float normalDeceleration_; // in meter/sec2
	private float maxDeceleration_; // in meter/sec2
	private float distanceToNormalStop_; // assuming normal dec is applied
	private float lastStepMove_;
	private double travelPerTurn;
	private int deptime;
	private int endTime;
	private int originID = -1;
	private int destinationID = -1;
	private Coordinate originCoord;
	private Coordinate destCoord;
	private Coordinate previousEpochCoord;// HGehlot: this variable stores the coordinates of the vehicle when last time
											// vehicle snapshot was recorded for visualization interpolation
	private boolean reachDest;
	private boolean onLane;
	private boolean atOrigin;
	private Road road;
	private Road nextRoad_;
	private Lane lane;
	private Lane nextLane_;
	// For vehicle based routing
	private List<Road> roadPath; // The route is always started with the current road, whenever entering the next
									// road, the current road will be popped out
	private List<Coordinate> coordMap;
	private Geography<Lane> laneGeography;
	private Vehicle leading_; // leading vehicle in the lane
	private Vehicle trailing_; // Trailing vehicle in the lane
	private Vehicle macroLeading_; // Leading vehicle on the road (with all lanes combined)
	private Vehicle macroTrailing_; // Trailing vehicle on the road (with all lanes combined)
	// Variables for lane changing model
	private Lane targetLane_; // This is the correct lane that vehicle should change to.
	private boolean correctLane; // To check if the vehicle is in the correct lane
	private boolean nosingFlag;// If a vehicle in MLC and it can't find gap acceptance then nosing is true.
	private boolean yieldingFlag; // The vehicle need to yield if true
	// For adaptive network partitioning
	private int Nshadow; // Number of current shadow roads in the path
	private ArrayList<Road> futureRoutingRoad;
	// For solving the grid-lock issue
	private int lastMoveTick = -1;
	private int stuckTime = 0;
	private ArrayList<Plan> activityplan; // A set of zone for the vehicle to visit

	/* Vehicle movement that can be accessed through descendant classes */
	private float accummulatedDistance_; // Accumulated travel distance in the current trip
	protected boolean movingFlag = false; // Whether this vehicle is moving

	GeodeticCalculator calculator = new GeodeticCalculator(ContextCreator.getLaneGeography().getCRS());
	MathTransformFactory mtFactory = ReferencingFactoryFinder.getMathTransformFactory(null);

	public Vehicle(int vClass) {
		this.id = ContextCreator.generateAgentID();
		this.rand = new Random(GlobalVariables.RandomGenerator.nextInt());
		this.currentCoord_ = new Coordinate();
		this.activityplan = new ArrayList<Plan>(); // Empty plan

		this.length = GlobalVariables.DEFAULT_VEHICLE_LENGTH;
		this.travelPerTurn = GlobalVariables.TRAVEL_PER_TURN;
		this.maxAcceleration_ = GlobalVariables.MAX_ACCELERATION;
		this.maxDeceleration_ = GlobalVariables.MAX_DECELERATION;
		this.normalDeceleration_ = -0.5f;

		this.previousEpochCoord = new Coordinate();
		this.endTime = 0;
		this.atOrigin = true;
		this.reachDest = false;
		this.onLane = false;
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
		this.setDestRoadID(0);
		// Upload the vehicle into the queue of the corresponding link
		this.lastStepMove_ = 0;
		this.vehicleID_ = this.id;
		this.accummulatedDistance_ = 0;
		this.roadPath = null;

		// For adaptive network partitioning
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
		this.setVehicleClass(vClass);
		this.setState(NONE_OF_THE_ABOVE);
	}

	// This is a new subclass of Vehicle class that has some different parameters
	// like max acceleration and max deceleration
	public Vehicle(float maximumAcceleration, float maximumDeceleration, int vClass) {
		this.id = ContextCreator.generateAgentID();
		this.currentCoord_ = new Coordinate();
		this.activityplan = new ArrayList<Plan>(); // empty plan
		this.length = GlobalVariables.DEFAULT_VEHICLE_LENGTH;
		this.travelPerTurn = GlobalVariables.TRAVEL_PER_TURN;
		this.maxAcceleration_ = maximumAcceleration;
		this.maxDeceleration_ = maximumDeceleration;
		this.normalDeceleration_ = -0.5f;
		this.previousEpochCoord = new Coordinate();
		this.endTime = 0;
		this.atOrigin = true;
		this.reachDest = false;
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
		this.setDestRoadID(0);
		// Upload the vehicle into the queue of the corresponding link
		this.lastStepMove_ = 0;
		this.vehicleID_ = this.id;
		this.accummulatedDistance_ = 0;
		this.roadPath = null;

		// For adaptive network partitioning
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
		this.setVehicleClass(vClass);
	}

	// Change the destination of the vehicle
	public void setNextPlan() {
		Plan next = this.activityplan.get(1);
		this.originID = this.destinationID;
		this.destinationID = next.getDestID();
		double duration = next.getDuration();
		this.deptime = (int) duration;
		CityContext cityContext = (CityContext) ContextCreator.getCityContext();
		this.destCoord = next.getLocation();
		this.setDestRoadID(cityContext.findRoadAtCoordinates(this.destCoord).getLinkid());
		this.atOrigin = true; // The vehicle will be rerouted to the new target when enters a new link.
		this.activityplan.remove(0); // Remove current schedule
	}

	// Vehicle enters the road
	public boolean enterNetwork(Road road) {
		Lane firstlane = road.firstLane(); // First lane is the right lane, which is closest to the outside street
		double gap = entranceGap(firstlane);
		int tickcount = (int) RepastEssentials.GetTickCount();
		if (gap >= 1.2 * this.length() && tickcount > firstlane.getLastEnterTick()) {
			road.removeVehicleFromNewQueue(this);
			firstlane.updateLastEnterTick(tickcount);
			this.updateLastMoveTick(tickcount);
			currentSpeed_ = 0f; // The initial speed
			desiredSpeed_ = road.getRandomFreeSpeed(rand.nextDouble());
			this.setRoad(road);
			this.appendToLane(firstlane);
			this.appendToRoad(this.road);
			this.setNextRoad();
			this.assignNextLane();
			return true;
		}
		return false;
	}

	// Add vehicle to the closest road
	public void departure() {
		this.reachDest = false;
		if(!this.onLane) {
			Road road = ContextCreator.getCityContext().findRoadAtCoordinates(this.getCurrentCoord());
			// The first list of coordinates for the vehicle to follow
			Coordinate[] coords = laneGeography.getGeometry(road.firstLane()).getCoordinates();
			for (Coordinate coord : coords) {
				this.coordMap.add(coord);    
			}
			road.addVehicleToPendingQueue(this);
		}
		else {
			this.setNextRoad(); // refresh the CoordMap
			this.assignNextLane();
		}
	}

	// A place holder for updating battery status for EVs
	public void updateBatteryLevel() {
		// Do nothing
	}

	// Clear the legacy impact from the shadow vehicles and future routing vehicles.
	// Performed before next routing computation.
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

	// Remove shadow vehicle count after the vehicle leaves the road
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

	// Set shadow vehicles and future routing road
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

	public void setNextRoad() {
		try {
			if (!this.atOrigin) { // Not at origin
				// Special case, the roadPath is null which means the origin
				// and destination are at the same link
				if (this.roadPath == null) {
					this.nextRoad_ = null;
					return;
				}
				// Try to stick on the routed path, modify this if you want to implement dynamic
				// routing
				if (this.stuckTime > GlobalVariables.MAX_STUCK_TIME) { // Stuck in one place for certain time steps,
																		// potentially there is a grid lock
					this.stuckTime = 0; // Refresh the stuck time to prevent the case that this function is called every
										// tick
					List<Road> tempPath = RouteV.vehicleRoute(this, this.destCoord); // Recalculate the route
					this.clearShadowImpact();
					// Compute new route
					this.roadPath = tempPath;
					this.setShadowImpact();
					if ((tempPath != null) && (tempPath.size() > 1)) {
						this.nextRoad_ = roadPath.get(1);
					} else {
						this.nextRoad_ = null;
					}
				} else {
					this.removeShadowCount(this.roadPath.get(0));
					this.roadPath.remove(0);
					if (this.road.getLinkid() == this.getDestRoadID() || this.roadPath.size() <= 1) {
						this.nextRoad_ = null;
					} else {
						this.nextRoad_ = this.roadPath.get(1);
					}
				}
			} else {
				// Clear legacy impact
				this.clearShadowImpact();
				this.roadPath = new ArrayList<Road>();
				if (this.getVehicleClass() == 1 && this.getState() == Vehicle.OCCUPIED_TRIP) { // EV taxis
					ElectricVehicle ev = (ElectricVehicle) this;
					if (!ContextCreator.routeResult_received.isEmpty() && GlobalVariables.ENABLE_ECO_ROUTING_EV
							&& !ev.onChargingRoute()) {
						Pair<List<Road>, Integer> route_result = RouteV.ecoRoute(ev.getOriginID(), ev.getDestID());
						this.roadPath = route_result.getFirst();
						ev.setRouteChoice(route_result.getSecond());
					}
				} else if (this.getVehicleClass() == 2) { // EV buses
					ElectricBus evBus = (ElectricBus) this;
					if (!ContextCreator.routeResult_received_bus.isEmpty() && GlobalVariables.ENABLE_ECO_ROUTING_BUS
							&& !evBus.onChargingRoute()) {
						Pair<List<Road>, Integer> route_result = RouteV.ecoRouteBus(evBus.getOriginID(),
								evBus.getDestID());
						this.roadPath = route_result.getFirst();
						evBus.setRouteChoice(route_result.getSecond());
					}
				}
				// Compute new route if eco-routing is not used
				if (this.roadPath == null || this.roadPath.isEmpty()) {
					this.roadPath = RouteV.vehicleRoute(this, this.destCoord); // K-shortest path or shortest path
				}
				this.setShadowImpact();
				
				if (this.roadPath == null || this.roadPath.size() < 2) { // The origin and destination share the same Junction
					this.atOrigin = false;
					this.nextRoad_ = null;
				} else {
					this.atOrigin = false;
					this.nextRoad_ = roadPath.get(1);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			ContextCreator.logger.error("No next road found for Vehicle " + this.getId() + "( roadPath : "
					+ this.roadPath + ")" + " on Road " + this.road.getLinkid());
			this.nextRoad_ = null;
		}
	}

	// Append a vehicle to vehicle list in plane
	public void appendToLane(Lane plane) {
		if (plane != null) {
			this.distance_ = (float) plane.getLength();
			this.lane = plane;
			Vehicle v = plane.lastVehicle();
			this.insertToLane(plane, v, null);
		} else {
			ContextCreator.logger.error("There is no target lane to set!");
		}
		if (Double.isNaN(distance_)) {
			ContextCreator.logger.error("distance_ is NaN in append for " + this);
		}
	}

	// Insert vehicle into plane at the location between the leadVehicle and
	// lagVehicle
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

	// For update the coordinates of vehicle to the corresponding lane coordinate
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
				move2(coords[i], coords[i + 1], distAndAngle[0], distAndAngle[0] - (this.distance_ - accDist)); // Update
																												// vehicle
																												// location
				this.nextDistance_ = (this.distance_ - accDist);
				this.setBearing(distAndAngle[1]);
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

	public void calcState() {
		this.makeAcceleratingDecision();

		if (this.road.getnLanes() > 1 && this.isOnLane() && this.distance_ >= GlobalVariables.NO_LANECHANGING_LENGTH) {
			this.makeLaneChangingDecision();
		}
	}

	/*
	 * -------------------------------------------------------------------- The
	 * Car-Following model calculates the acceleration rate based on interaction
	 * with other vehicles. The function returns a the most restrictive acceleration
	 * (deceleration if negative) rate among the rates given by several constraints.
	 * This function updates accRate_ at the end.
	 * --------------------------------------------------------------------
	 */

	public void makeAcceleratingDecision() {
		float aZ = this.accRate_; /* car-following */
		float acc = this.maxAcceleration(); /* returned rate */
		if (this.onLane) {
			/*
			 * BL: vehicle will have acceleration rate based on car following if it is not
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
			// Vehicle is at the stop line, handle it with a different (here simplified)
			// process
			acc = 0;
		}

		accRate_ = acc;
		if (Double.isNaN(accRate_)) {
			ContextCreator.logger.error("NaN acceleration rate for " + this);
		}
	}

	public float calcFreeFlowRate() {
		if (this.nextRoad_ != null) {
			if (this.nextRoad_.getFreeSpeed() < this.currentSpeed_) { // brake to prepare for entering the next road
				double decTime = (this.currentSpeed_ - this.nextRoad_.getFreeSpeed()) / this.normalDeceleration_;
				if (this.distance_ < 0.5 * (this.currentSpeed_ + this.nextRoad_.getFreeSpeed()) * decTime) {
					return (float) (Math.max(this.maxDeceleration_, 0.5 * (this.currentSpeed_ * this.currentSpeed_)
							- this.nextRoad_.getFreeSpeed() * this.nextRoad_.getFreeSpeed() / this.distance_));
				}
			}
		}
		if (this.currentSpeed_ < this.desiredSpeed_) { // accelerate to reach the desired speed
			return this.maxAcceleration_;
		} else if (this.currentSpeed_ == this.desiredSpeed_) {
			return 0f;
		} else { // decelerate if it exceeds the desired speed
			return this.normalDeceleration_;
		}
	}

	public float calcCarFollowingRate(Vehicle front) {
		// If there is no front vehicle the car will be in free flow regime and have max
		// acceleration if not reaching the
		// desired speed
		if (front == null) {
			regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			return calcFreeFlowRate();
		}

		float acc;
		float space = gapDistance(front);
		float speed = currentSpeed_ == 0f ? 0.00001f : currentSpeed_;
		float headway = 2.0f * space / (speed + currentSpeed_);
		float hupper, hlower;

		float AlphaDec = GlobalVariables.ALPHA_DEC;
		float BetaDec = GlobalVariables.BETA_DEC;
		float GammaDec = GlobalVariables.GAMMA_DEC;

		float AlphaAcc = GlobalVariables.ALPHA_ACC;
		float BetaAcc = GlobalVariables.BETA_ACC;
		float GammaAcc = GlobalVariables.GAMMA_ACC;

		hupper = GlobalVariables.H_UPPER;
		hlower = GlobalVariables.H_LOWER;

		// There will be three regimes emergency/free-flow/car-following regime
		// depending on headway
		// Emergency regime
		if (headway < hlower) {
			float dv = currentSpeed_ - front.currentSpeed_;

			if (dv < GlobalVariables.SPEED_EPSILON) { // the leader is
				// decelerating
				acc = front.accRate_ + 0.25f * normalDeceleration_;
			} else {
				if (space > 0.01) {
					acc = front.accRate_ - 0.5f * dv * dv / space;
				} else {
					float dt = GlobalVariables.SIMULATION_STEP_SIZE;
					float v = front.currentSpeed_ + front.accRate_ * dt;
					space += 0.5f * (front.currentSpeed_ + v) * dt;
					acc = brakeToTargetSpeed(space, v);
				}
			}
			acc = Math.max(this.maxDeceleration_, acc);
			regime_ = GlobalVariables.STATUS_REGIME_EMERGENCY;
		}
		// Free-flow regime
		else if (headway > hupper) { // desired speed model will do
			distanceToNormalStop_ = -(float) (0.5 * this.currentSpeed_ * this.currentSpeed_ / this.normalDeceleration_); // 1/2
																															// at^2
			if (space > distanceToNormalStop_) {
				acc = calcFreeFlowRate();
				regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			} else {
				float dt = GlobalVariables.SIMULATION_STEP_SIZE;
				float v = front.currentSpeed_ + front.accRate_ * dt;
				space += 0.5 * (front.currentSpeed_ + v) * dt;
				acc = brakeToTargetSpeed(space, v);
				regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			}
		}
		// We are using Herman model
		else {
			float dv = front.currentSpeed_ - currentSpeed_;
			if (dv < 0) {
				acc = dv * AlphaDec * (float) Math.pow(currentSpeed_, BetaDec) / (float) (Math.pow(space, GammaDec));

			} else if (dv > 0) {
				acc = dv * AlphaAcc * (float) Math.pow(currentSpeed_, BetaAcc) / (float) (Math.pow(space, GammaAcc));
			} else { // uniform speed
				acc = 0.0f;
			}
			regime_ = GlobalVariables.STATUS_REGIME_CARFOLLOWING;
		}
		return acc;
	}

	public float brakeToTargetSpeed(float s, float v) {
		if (s > GlobalVariables.FLT_EPSILON) {
			float v2 = v * v;
			float u2 = currentSpeed_ * currentSpeed_;
			float acc = (v2 - u2) / s * 0.5f;
			return acc;
		} else {
			float dt = GlobalVariables.SIMULATION_STEP_SIZE;
			if (dt <= 0.0)
				return maxAcceleration_;
			return (v - currentSpeed_) / dt;
		}
	}

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

	public Junction nextJuction() {
		Junction nextJunction;
		if (this.nextRoad_ == null)
			return null;
		if (this.nextRoad_.getJunctions().get(0).getID() == this.road.getJunctions().get(1).getID())
			nextJunction = this.road.getJunctions().get(1);
		else
			nextJunction = this.road.getJunctions().get(0);
		return nextJunction;
	}

	public float gapDistance(Vehicle front) {
		float headwayDistance;
		if (front != null) { /* vehicle ahead */
			if (this.lane.getID() == front.getLane().getID()) { /* same lane */
				if (front.isOnLane()) {
					headwayDistance = this.distance_ - front.getDistance() - front.length();
				} else { // Front vehicle is entering the intersection, i.e., at the stop line
					headwayDistance = this.distance_ - front.length();
				}
			} else { /* different lane */
				headwayDistance = this.distance_ + (float) front.getLane().getLength() - front.getDistance(); // LZ:
																												// front
																												// vehicle
																												// is in
																												// the
																												// next
																												// road
			}
		} else { /* no vehicle ahead. */
			headwayDistance = Float.MAX_VALUE;
		}

		return (headwayDistance);
	}

	public void makeLaneChangingDecision() {
		if (this.distFraction() < 0.5) {
			// Halfway to the downstream intersection, only mantatory LC allowed, check the
			// correct lane
			if (this.isCorrectLane() != true) { // change lane if not in correct
				// lane
				Lane tarLane = this.tempLane();
				if (tarLane != null)
					this.mandatoryLC(tarLane);
			}
		} else {
			if (this.distFraction() > 0.75) {
				// First 25% in the road, do discretionary LC with 100% chance
				double laneChangeProb1 = rand.nextDouble();
				// The vehicle is at beginning of the lane, it is free to change lane
				Lane tarLane = this.findBetterLane();
				if (tarLane != null) {
					if (laneChangeProb1 < GlobalVariables.LANE_CHANGING_PROB_PART1)
						this.discretionaryLC(tarLane);
				}
			} else {
				// First 25%-50% in the road, we do discretionary LC but only to correct lanes
				// with 100% chance
				double laneChangeProb2 = rand.nextDouble();
				// The vehicle is at beginning of the lane, it is free to change lane
				Lane tarLane = this.findBetterCorrectLane();
				if (tarLane != null) {
					if (laneChangeProb2 < GlobalVariables.LANE_CHANGING_PROB_PART2)
						this.discretionaryLC(tarLane);
				}

			}
		}
	}

	/*
	 * Record the vehicle snapshot if this tick corresponds to the required epoch
	 * that is needed for visualization interpolation. Note that this is recording
	 * is independent of snapshots of vehicles whether they move or not in the
	 * current tick. (So when vehicles do not move in a tick but we need to record
	 * positions for viz interpolation then recVehSnaphotForVisInterp is useful).
	 * Also, we update the coordinates of the previous epoch in the end of the
	 * function.
	 */
	public void recVehSnaphotForVisInterp() {
		Coordinate currentCoord = this.getCurrentCoord();
		try {
			// The following condition can be put to reduce the data when the output of
			// interest is the final case when vehicles reach close to destination
			DataCollector.getInstance().recordSnapshot(this, currentCoord);// HGehlot: I use currentCoord rather than
																			// the targeted coordinates (as in move()
																			// function) and this is an approximation
																			// but anyway if the vehicle moves then it
																			// will get overriden.
		} catch (Throwable t) {
			// Could not log the vehicle's new position in data buffer!
			DataCollector.printDebug("ERR" + t.getMessage());
		}
		setPreviousEpochCoord(currentCoord);// update the previous coordinate as the current coordinate
	}

	public Coordinate getpreviousEpochCoord() {
		return this.previousEpochCoord;
	}

	protected void setPreviousEpochCoord(Coordinate newCoord) {
		this.previousEpochCoord.x = newCoord.x;
		this.previousEpochCoord.y = newCoord.y;
	}

	/*
	 * Calculate new location and speed after an iteration based on its current
	 * location, speed and acceleration. The vehicle will be removed from the
	 * network if it arrives its destination.
	 */
	public void travel() {
		this.endTime++;
		this.move(); // Move the vehicle towards their destination
		if (this.trailing_ == this) {
			ContextCreator.logger.error("Something went wrong, the trailing vehicle is itslef!!!");
		}
		this.advanceInMacroList(); // If the vehicle travel too fast, it will change the marcroList of the road.
		if (this.nextRoad_ == null) {
			this.checkAtDestination();
		}
	}

	public void move() {
		/* Sanity check */
		if (distance_ < 0 || Double.isNaN(distance_))
			ContextCreator.logger.error("Vehicle.move(): distance_=" + distance_ + " " + this);
		if (currentSpeed_ < 0 || Float.isNaN(currentSpeed_))
			ContextCreator.logger.error("Vehicle.move(): currentSpeed_=" + currentSpeed_ + " " + this);
		if (Double.isNaN(accRate_))
			ContextCreator.logger.error("Vehicle.move(): accRate_=" + accRate_ + " " + this);

		Coordinate currentCoord = null;
		Coordinate target = null;
		double dx = 0; // Travel distance calculated by physics
		boolean travelledMaxDist = false; // True when traveled with maximum distance (=dx).
		float distTravelled = 0; // The distance traveled so far.
		float oldv = currentSpeed_; // Speed at the beginning
		float step = GlobalVariables.SIMULATION_STEP_SIZE; // 0.3 s
		double minSpeed = GlobalVariables.SPEED_EPSILON; // min allowed speed (m/s)
		double minAcc = GlobalVariables.ACC_EPSILON; // min allowed absolute acceleration(m/s2)

		/* Case 1: Within an intersection */
		Road current_road = this.road;
		if (distance_ < GlobalVariables.INTERSECTION_BUFFER_LENGTH) {
			if (this.nextRoad_ != null) { // This has not reached destination
				if (this.isOnLane()) { // On lane
					this.coordMap.add(this.getCurrentCoord()); // Stop and wait
					if (!this.appendToJunction(nextLane_)) { // This will make this.isOnLane becomes false, return false
																// means the vehicle cannot enter the next road
						this.lastStepMove_ = 0;
						this.movingFlag = false;
					} else {
						current_road.recordEnergyConsumption(this);
						this.lastStepMove_ = distance_; // Successfully entered the next road, update the lastStepMove
														// and accumulatedDistance
						this.accummulatedDistance_ += this.lastStepMove_;
						this.movingFlag = true;
					}
					return; // Move finished
				} else { // Not on lane, directly changing road
					if (!this.changeRoad()) { // Zero means the vehicle cannot enter the next road
						stuckTime += 1;
						this.lastStepMove_ = 0;
						this.movingFlag = false;
					} else {
						stuckTime = 0;
						current_road.recordEnergyConsumption(this);
						this.lastStepMove_ = distance_; // Successfully entered the next road, update the lastStepMove
														// and accumulatedDistance
						this.accummulatedDistance_ += this.lastStepMove_;
						this.movingFlag = true;
					}
				}
			}
			return;// Move finished
		}

		/* Case 2.1: On a link */

		// Calculate the speed and acceleration
		float dv = accRate_ * step; // Change of speed
		if (dv + currentSpeed_ > 0) { // Still moving at the end of the cycle
			dx = currentSpeed_ * step + 0.5f * dv * step;

		} else { // Stops before the cycle end
			dx = -0.5f * currentSpeed_ * currentSpeed_ / accRate_;
			if (currentSpeed_ < minSpeed && accRate_ < minAcc) {
				dx = 0.0f;
			}
		}
		if (Double.isNaN(dx)) {
			ContextCreator.logger.error("dx is NaN in move() for " + this);
		}

		// Solve the crash problem
		double gap = gapDistance(this.vehicleAhead());
		dx = Math.min(dx, gap); // no trespass

		// Actual acceleration rate applied in last time interval.
		accRate_ = (float) Math.max(this.maxDeceleration_, 2.0f * (dx - oldv * step) / (step * step));

		// Update speed
		currentSpeed_ += accRate_ * step;
		if (currentSpeed_ < minSpeed) {
			currentSpeed_ = 0.0f;
			accRate_ = 0.0f; // no back up allowed
		} else {
			accRate_ = ((currentSpeed_ - oldv) / step);
		}

		if (dx <= 0.0f) {
			lastStepMove_ = 0;
			this.movingFlag = false;
			return;
		}

		// Update vehicle coords
		double[] distAndAngle = new double[2];
		while (!travelledMaxDist) {
			target = this.coordMap.get(0);
			// If we can get all the way to the next coords on the route then, just go there
			if (distTravelled + nextDistance_ <= dx) {
				distTravelled += nextDistance_;
				this.setCurrentCoord(target);
				this.coordMap.remove(0);
				if (this.coordMap.isEmpty()) {
					this.distance_ -= nextDistance_;
					this.nextDistance_ = 0;
					if (this.nextRoad_ != null) { // has next road
						if (this.isOnLane()) { // is at the end of a segment
							this.coordMap.add(this.getCurrentCoord()); // Stop and wait
							if (this.appendToJunction(nextLane_)) {
								current_road.recordEnergyConsumption(this);
							}
							lastStepMove_ = distTravelled;
							accummulatedDistance_ += distTravelled;
							break;
						} else { // is in the intersection
							if (this.changeRoad()) {
								stuckTime = 0;
								current_road.recordEnergyConsumption(this);
							} else {
								stuckTime += 1;
							}
							lastStepMove_ = distTravelled;
							accummulatedDistance_ += distTravelled;
							break;
						}
					} else { // No next road, the vehicle arrived at the destination
						this.coordMap.clear();
						this.coordMap.add(this.currentCoord_);
						break;
					}
				} else {
					currentCoord = this.getCurrentCoord();
					this.distance2(currentCoord, this.coordMap.get(0), distAndAngle);
					this.distance_ -= this.nextDistance_;
					this.nextDistance_ = distAndAngle[0];
					this.setBearing(distAndAngle[1]);
				}
			}
			// Otherwise move as far as we can towards the target along the road
			// we're on get the angle between the two points
			// (current and target)
			else {
				double distanceToTarget = this.nextDistance_;
				this.distance_ -= dx - distTravelled;
				this.nextDistance_ -= dx - distTravelled;
				currentCoord = this.getCurrentCoord();
				move2(currentCoord, this.coordMap.get(0), distanceToTarget, dx - distTravelled);
				distTravelled = (float) dx;
				this.accummulatedDistance_ += dx;
				lastStepMove_ = (float) dx;
				travelledMaxDist = true;
			}
		}
		// Update the position of vehicles, 0<=distance_<=lane.length()
		if (distance_ < 0) {
			distance_ = 0;
		}
		if (distTravelled > 0) {
			this.movingFlag = true;
		} else {
			this.movingFlag = false;
		}
		return;
	}

	public void primitiveMove() {
		Coordinate currentCoord = this.getCurrentCoord();
		Coordinate target = this.coordMap.get(0);
		if (this.reachDest) {
			return;
		}

		double[] distAndAngle = new double[2];
		double distToTarget;
		distToTarget = this.distance2(currentCoord, target, distAndAngle);

		if (distToTarget <= travelPerTurn) { // Include the equal case, which is important
			this.setCurrentCoord(target);
		} else {
			double distToTravel = travelPerTurn;
			this.accummulatedDistance_ += distToTravel;
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
		if (this.reachDest) {
			this.clearShadowImpact(); // Clear shadow impact if already reaches destination
			return false; // Only one will reach destination once
		} else if (this.nextRoad_ != null) {
			// Check if there is enough space in the next road to change to
			int tickcount = (int) RepastEssentials.GetTickCount();
			// Check if the target long road has space
			if (this.entranceGap(nextLane_) >= 1.2 * this.length() && (tickcount > this.nextLane_.getLastEnterTick())) {
				this.nextLane_.updateLastEnterTick(tickcount); // LZ: update enter tick so other vehicle cannot enter
																// this road in this tick
				this.removeFromLane();
				this.removeFromMacroList();
				this.appendToLane(nextLane_);
				this.appendToRoad(this.nextRoad_);
				this.setNextRoad();
				this.assignNextLane();
				// Reset the desired speed according to the new road
				this.desiredSpeed_ = this.road.getRandomFreeSpeed(rand.nextDouble());
				return true;
			}
		}
		coordMap.clear();
		coordMap.add(this.getCurrentCoord());// LZ: Fail to enter next link, try again in the next tick
		return false;
	}

	public int closeToRoad(Road road) {
		Coordinate currentCoord = this.getCurrentCoord();
//		GeometryFactory geomFac = new GeometryFactory();
		Coordinate nextCoord;
		if (this.coordMap == null)
			return 0;
		else if (this.coordMap.size() == 0)
			return 0;
		else
			nextCoord = this.coordMap.get(0);
//		Geometry geom1 = geomFac.createPoint(currentCoord);
//		Geometry geom2 = geomFac.createPoint(nextCoord);
//		DistanceOp dist1 = new DistanceOp(geom1, geom2);
		if (distance(currentCoord, nextCoord) < GlobalVariables.TRAVEL_PER_TURN) {
			return 1;

		} else
			return 0;
	}

	public void checkAtDestination() { // At the beginning of the final link
		if (!this.atOrigin) {
			this.setReachDest();
		}
	}

	public float maxAcceleration() {
		return maxAcceleration_;
	}

	public void appendToRoad(Road road) {
		this.road = road;
		this.appendToMacroList(road);
	}

	public void appendToMacroList(Road road) {
		macroTrailing_ = null;
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
	}

	public Vehicle macroLeading() {
		return macroLeading_;
	}

	public void macroLeading(Vehicle v) {
		if (v != null)
			this.macroLeading_ = v;
		else
			this.macroLeading_ = null;
	}

	public Vehicle macroTrailing() {
		return macroTrailing_;
	}

	public void macroTrailing(Vehicle v) {
		if (v != null)
			this.macroTrailing_ = v;
		else
			this.macroTrailing_ = null;
	}

	public Vehicle leading() {
		return leading_;
	}

	public void leading(Vehicle v) {
		if (v != null)
			this.leading_ = v;
		else
			this.leading_ = null;
	}

	public Vehicle trailing() {
		return trailing_;
	}

	public void trailing(Vehicle v) {
		if (v != null)
			this.trailing_ = v;
		else
			this.trailing_ = null;
	}

	public int getDepTime() {
		return this.deptime;
	}

	public int getEndTime() {
		return this.endTime;
	}

	public void setRoad(Road road) {
		this.road = road;
	}

	public Road getRoad() {
		return road;
	}

	public float getDistance() {
		return distance_;
	}

	public float distFraction() {
		if (distance_ > 0)
			return distance_ / (float) this.lane.getLength();
		else
			return 0;
	}

	public float length() {
		return length;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public Lane getLane() {
		return lane;
	}

	public int getVehicleID() {
		return this.vehicleID_;
	}

	public int getOriginID() {
		return this.originID;
	}

	public int getDestID() {
		return this.destinationID;
	}

	public ArrayList<Plan> getPlan() {
		return this.activityplan;
	}

	public void removePlan(Plan p) {
		this.activityplan.remove(p);
	}

	public void addPlan(int dest_id, Coordinate location, double d) {
		Plan p = new Plan(dest_id, location, d);
		this.activityplan.add(p);
	}

	public void addPlan(List<Plan> activityPlan) {
		this.activityplan.addAll(activityPlan);
	}

	public Coordinate getOriginCoord() {
		return this.originCoord;
	}

	public Coordinate getDestCoord() {
		return this.destCoord;
	}

	public Coordinate getCurrentCoord() {
		Coordinate coord = new Coordinate();
		coord.x = this.currentCoord_.x;
		coord.y = this.currentCoord_.y;
		coord.z = this.currentCoord_.z;
		return coord;
	}

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

	public int nearlyArrived() { // If nearly arrived then return 1 else 0
		if (this.nextRoad_ == null) {
			return 1;
		} else {
			return 0;
		}
	}

	// When arrive
	public void setReachDest() {
		this.reachDest = true;
		// this.leaveNetwork(); // remove from the network
		// Vehicle arrive
		this.endTime = (int) RepastEssentials.GetTickCount();
	}
	
	// When leave the network (entered to parking space)
	public void leaveNetwork() {
		this.setPreviousEpochCoord(new Coordinate());
		this.setCurrentCoord(this.destCoord);
		this.originCoord = this.destCoord; // Next origin is the current destination
		this.clearShadowImpact();
		this.removeFromLane();
		this.removeFromMacroList();
		this.onLane = false;
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
		this.setDestRoadID(0);
		// Update the vehicle into the queue of the corresponding link
		this.lastStepMove_ = 0;
		this.accummulatedDistance_ = 0;
		this.roadPath = null;
		// For adaptive network partitioning
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
	}

	public float currentSpeed() {
		return currentSpeed_;
	}

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

	public void updateLastMoveTick(int current_tick) {
		this.lastMoveTick = current_tick;
	}

	public int getLastMoveTick() {
		return this.lastMoveTick;
	}

	// Remove a vehicle from the macro vehicle list in the current road
	// segment.
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

	/*
	 * ------------------------------------------------------------------- BL:
	 * Advance a vehicle to the position in macro vehicle list that corresponding to
	 * its current distance. This function is invoked whenever a vehicle is moved
	 * (including moved into a downstream segment), so that the vehicles in macro
	 * vehicle list is always sorted by their position. The function is called in
	 * travel() -------------------------------------------------------------------
	 */
	/**
	 * @param: distance_ is with respect to the end of road
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

	/*
	 * Function: checkCorrectLane() BL: this function will check if the current lane
	 * connect to a lane in the next road if yes then it gives the checkLaneFlag
	 * true value if not then the checkLaneFlag has false value the function will be
	 * called after the vehicle updates its route i.e. the routeUpdateFlag has true
	 * value
	 */

	public boolean isCorrectLane() {
		if (nextRoad_ == null)
			return true;
		Lane nextLane = this.nextLane_;
		// If using dynamic shortest path then we need to check lane only after
		// the route is updated
		this.correctLane = false;
		if (nextLane.getUpLanes().size() > 0)
			for (Lane pl : nextLane.getUpLanes()) {
				if (pl.equals(this.lane)) {
					this.correctLane = true;
					break;
				}
			}
		return this.correctLane;
	}

	// Find if the potential next road and current lane are connected
	public boolean checkNextLaneConnected(Road nextRoad) {
		boolean connected = false;
		Lane curLane = this.lane;

		if (nextRoad != null) {
			for (Lane dl : curLane.getDnLanes()) {
				if (dl.road_().equals(nextRoad)) {
					// if this lane already connects to downstream road then
					// assign to the connected lane
					connected = true;
					break;
				}
			}
		}

		return connected;
	}

	public void assignNextLane() {
		boolean connected = false;
		Lane curLane = this.lane;
		Road curRoad = this.getRoad();

		if (this.nextRoad_ == null) {
			this.nextLane_ = null;
			return;
		} else {
			for (Lane dl : curLane.getDnLanes()) {
				if (dl.road_().equals(this.nextRoad_)) {
					this.nextLane_ = dl;
					// If this lane already connects to downstream road then
					// assign to the connected lane
					connected = true;
					break;
				}
			}
			if (!connected) {
				for (Lane pl : this.nextRoad_.getLanes()) {
					for (Lane ul : pl.getUpLanes()) {
						if (ul.road_().getID() == curRoad.getID()) {
							this.nextLane_ = pl;
							break; // Assign the next lane to the 1st connected lane
						}
					}
				}
				this.nextLane_ = this.nextRoad_.getLane(0);// HG and XQ: force movement at a 5 leg or irregular
															// intersection
			}
			if (this.nextLane_ == null)
				ContextCreator.logger.error("No next lane found for vehicle: " + this.vehicleID_
						+ " moving on the road: " + this.getRoad().getLinkid() + " lane: " + this.getLane().getLaneid()
						+ " heading to location " + this.getDestID() + " while looking for next lane on road: "
						+ this.nextRoad_.getLinkid() + " that has " + this.nextRoad_.getnLanes() + " lanes");
		}
	}

	/*
	 * Return the target lane (the lane that connect to the downstream Road)
	 */
	public Lane targetLane() {
		Road curRoad = this.getRoad();
		Lane nextLane = this.nextLane_;
		if (nextLane != null) {
			for (Lane pl : nextLane.getUpLanes()) {
				if (pl.road_().equals(curRoad)) {
					this.targetLane_ = pl;
					break;
				}
			}
		}
		return this.targetLane_;
	}

	/*
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

	// Get left lane
	public Lane leftLane() {
		Lane leftLane = null;
		if (this.road.getLaneIndex(this.lane) > 0) {
			leftLane = this.road.getLane(this.road.getLaneIndex(this.lane) - 1);
		}
		return leftLane;
	}

	// Get right lane
	public Lane rightLane() {
		Lane rightLane = null;
		if (this.road.getLaneIndex(this.lane) < this.road.getnLanes() - 1) {
			rightLane = this.road.getLane(this.road.getLaneIndex(this.lane) + 1);
		}
		return rightLane;
	}

	/*
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

	/*
	 * Following we implement mandatory lane changing. The input parameter is the
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

	/*
	 * If the vehicle with MLC state can't change the lane after some distance. The
	 * vehicle need to nose and yield the lag Vehicle of the target lane in order to
	 * have enough gap to change the lane This function is called only when
	 * nosingFlag is true and must be recalled until nosingFlag receive false value
	 * after the vehicle nosed, tag the lag vehicle in target lane to yielding
	 * status. This function will be called in makeAccelerationDecision
	 */
	public float nosing() {
		float acc = 0;
		float lagGap;
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
				if (lagGap < GlobalVariables.minLag) {
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

	/*
	 * While moving, the vehicle will checks if the vehicles in adjection lanes are
	 * nosing to its lane or not after some distance to the downstream node If the
	 * nosing is true then it will be tagged in yielding state to slow down.
	 */
	public float yielding() {
		float acc = 0;
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

	/*
	 * When change lane, distance need to be adjusted with the lane width.
	 */

	// Calculate critical lead gap of the vehicle with the lead vehicle in the
	// target lane.
	public double critLeadGapMLC(Vehicle leadVehicle, Lane plane) {
		double critLead = 0;
		double minLead_ = GlobalVariables.minLead;
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

	// Calculate lead gap of the vehicle with the lead vehicle in the target
	// lane.
	public double leadGap(Vehicle leadVehicle, Lane plane) {
		double leadGap = 0;
		if (leadVehicle != null) {
			leadGap = this.distFraction() * plane.getLength() - leadVehicle.getDistance() - leadVehicle.length(); // leadGap>=-leadVehicle.length()
		} else {
			leadGap = this.distFraction() * plane.getLength();
		}
		return leadGap;
	}

	// Calculate critical lag gap of the vehicle with the lag vehicle in the
	// target lane.
	public double critLagGapMLC(Vehicle lagVehicle, Lane plane) {
		double critLag = 0;
		double betaLag01 = GlobalVariables.betaLagMLC01;
		double betaLag02 = GlobalVariables.betaLagMLC02;
		double gama = GlobalVariables.gama;
		double minLag_ = GlobalVariables.minLag;
		if (lagVehicle != null) {
			critLag = minLag_
					+ (betaLag01 * this.currentSpeed() + betaLag02 * (this.currentSpeed() - lagVehicle.currentSpeed()))
							* (1 - Math.exp(-gama * this.distFraction() * plane.getLength()));
		}
		if (critLag < minLag_)
			critLag = minLag_;
		return critLag;
	}

	// Calculate lag gap of the vehicle with the lag vehicle in the target
	// lane.
	public float lagGap(Vehicle lagVehicle, Lane plane) {
		float lagGap = 0;
		if (lagVehicle != null)
			lagGap = lagVehicle.getDistance() - this.distFraction() * plane.getLength() - this.length();
		else {
			lagGap = this.lane.getLength() - this.distFraction() * plane.getLength();
		}
			
		return lagGap;
	}

	// Find the lead vehicle in target lane
	public Vehicle leadVehicle(Lane plane) {
		Vehicle leadVehicle = this.macroLeading_;
		while (leadVehicle != null && leadVehicle.lane != plane) {
			leadVehicle = leadVehicle.macroLeading_;
		}
		return leadVehicle;
	}

	// Find lag vehicle in target lane
	public Vehicle lagVehicle(Lane plane) {
		Vehicle lagVehicle = this.macroTrailing_;
		while (lagVehicle != null && lagVehicle.lane != plane) {
			lagVehicle = lagVehicle.macroTrailing_;
		}
		return lagVehicle;
	}

	/*
	 * Following we will implement discretionary LC model at current stage, the DLC
	 * is implementing as follow: 1. If the vehicle is not close to downstream node
	 * 2. and it finds a correct lane with better traffic condition -> then it will
	 * change lane
	 */
	/*
	 * If the vehicle is in correct lane then we find a better lane that is also
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
				} else if (this.leading_ != null && this.leading_.currentSpeed_ < this.desiredSpeed_
						&& this.currentSpeed_ < this.desiredSpeed_) {
					if (front.currentSpeed_ > this.currentSpeed_ && front.accRate_ > 0)
						return targetLane;
				}
			}
			return null;
		}
	}

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

	// Once the vehicle finds a better lane. It changes to that lane
	// discretionarily.
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

	/*
	 * Vehicle approaches junction
	 */
	public boolean appendToJunction(Lane nextlane) {
		if (this.reachDest) {
			return false;
		} else { // Want to change to next lane
			coordMap.clear();
			coordMap.add(this.getCurrentCoord());
		}

		this.onLane = false;

		// Record energy consumption
		if (this.getVehicleClass() == 1) { // EV
			((ElectricVehicle) this).recLinkSnaphotForUCB();
			((ElectricVehicle) this).recSpeedVehicle();
		} else if (this.getVehicleClass() == 2) { // Bus
			((ElectricBus) this).recLinkSnaphotForUCBBus();
		}

		if (!this.changeRoad()) {
			return false;
		}

		return true;
	}

	public boolean isOnLane() {
		return onLane;
	}

	public float entranceGap(Lane nextlane) {
		float gap = 0;
		if (nextlane != null) {
			Vehicle newleader = nextlane.lastVehicle();
			if (newleader != null) {
				gap = (float) nextlane.getLength() - newleader.distance_ - newleader.length();
			} else
				gap = 9999999; // a number large enough
		}
		return gap;
	}

	/**
	 * @param c
	 * @param c1
	 * @param c2
	 * @return
	 */

	private double distance(Coordinate c1, Coordinate c2) {

		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		double distance;
		try {
			distance = calculator.getOrthodromicDistance();
		} catch (AssertionError ex) {
			ContextCreator.logger.error("Error with finding distance");
			distance = 0.0;
		}
		return distance;
	}

	/**
	 * 
	 * @param c1         current coordinate
	 * @param c2         next coordinate
	 * @param returnVals a mutable
	 * @return
	 */
	private double distance2(Coordinate c1, Coordinate c2, double[] returnVals) {
		double distance;
		double radius;
		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		try {
			distance = calculator.getOrthodromicDistance();
			radius = calculator.getAzimuth(); // the azimuth in degree, value from -180-180
		} catch (AssertionError e) {
			System.err.println("Error with finding distance: " + e);
			distance = 0.0;
			radius = 0.0;
		}
		if (returnVals != null && returnVals.length == 2) {
			returnVals[0] = distance;
			returnVals[1] = radius;
		}
		if (Double.isNaN(distance)) {
			// RV: Check if this condition ever occurs
			System.err.println("Geodetic distance is NaN for " + this);
			distance = 0.0;
			radius = 0.0;
		}
		return distance;
	}

	/**
	 * A light weight move function based on moveVehicleByVector, and is much faster
	 * than the one using geom
	 * 
	 * @param coord
	 * @param distance
	 * @param angleInDegrees
	 */
	private void move2(Coordinate origin, Coordinate target, double distanceToTarget, double distanceTravelled) {
		double p = distanceTravelled / distanceToTarget;
		if (p < 1) {
			this.setCurrentCoord(new Coordinate((1 - p) * origin.x + p * target.x, (1 - p) * origin.y + +p * target.y));
		} else {
			ContextCreator.logger
					.error("Vehicle.move2(): Cannot move " + this + "from " + origin + " by dist=" + distanceTravelled);
		}
	}

//	/* *
//	 * Thread safe version of the moveByVector, replace the one in the DefaultGeography class
//	 * Creating a new Geometry point given the current location of the vehicle as well as the distance and angle.
//	 * In the end, move the vehicle to the new geometry point.
//	 * Commented to gain better efficiency.
//	 * @return the new Geometry point
//	 */
//	
//	public void moveVehicleByVector(double distance, double angleInRadians) {
//		Geometry geom = ContextCreator.getVehicleGeography().getGeometry(this);
//		if (geom == null) {
//			System.err.println("Error moving object by vector");
//		}
//
//		if (angleInRadians > 2 * Math.PI || angleInRadians < 0) {
//			throw new IllegalArgumentException(
//					"Direction cannot be > PI (360) or less than 0");
//		}
//		double angleInDegrees = Math.toDegrees(angleInRadians);
//		angleInDegrees = angleInDegrees % 360;
//		angleInDegrees = 360 - angleInDegrees;
//		angleInDegrees = angleInDegrees + 90;
//		angleInDegrees = angleInDegrees % 360;
//		if (angleInDegrees > 180) {
//			angleInDegrees = angleInDegrees - 360;
//		}
//		Coordinate coord = geom.getCoordinate();
//		AffineTransform transform;
//		
//		try {
//			if (!ContextCreator.getVehicleGeography().getCRS().equals(DefaultGeographicCRS.WGS84)) {
//				MathTransform crsTrans = CRS.findMathTransform(ContextCreator.getVehicleGeography().getCRS(),
//						DefaultGeographicCRS.WGS84);
//				Coordinate tmp = new Coordinate();
//				JTS.transform(coord, tmp, crsTrans);
//				this.calculator.setStartingGeographicPoint(tmp.x, tmp.y);
//			} else {
//				this.calculator.setStartingGeographicPoint(coord.x, coord.y);
//			}
//			this.calculator.setDirection(angleInDegrees, distance);
//			Point2D p = this.calculator.getDestinationGeographicPoint();
//			if (!ContextCreator.getVehicleGeography().getCRS().equals(DefaultGeographicCRS.WGS84)) {
//				MathTransform crsTrans = CRS.findMathTransform(
//						DefaultGeographicCRS.WGS84, ContextCreator.getVehicleGeography().getCRS());
//				JTS.transform(new Coordinate(p.getX(), p.getY()), coord,
//						crsTrans);
//			}
//
//			transform = AffineTransform.getTranslateInstance(
//					p.getX() - coord.x, p.getY() - coord.y);
//
//			MathTransform mt = mtFactory
//					.createAffineTransform(new GeneralMatrix(transform));
//			geom = JTS.transform(geom, mt);
//		} catch (Exception ex) {
//			System.err.println("Error moving object by vector");
//		}
//		
//		ContextCreator.getVehicleGeography().move(this, geom);
//
//		try {
//		    Coordinate geomCoord = geom.getCoordinate();
//		  //HG: the following condition can be put to reduce the data when the output of interest is the final case when vehicles reach close to destination
////		    if(this.nextRoad() == null){
//		    	DataCollector.getInstance().recordSnapshot(this, geomCoord);
////		    }
//		}
//		catch (Throwable t) {
//		    // Could not record this vehicle move in the data buffer!
//		    DataCollector.printDebug("ERR", t.getMessage());
//		}
//	}

	public int getRegime() {
		return regime_;
	}

	public int getVehicleClass() {
		return vehicleClass;
	}

	public float currentAcc() {
		return accRate_;
	}

	public void setVehicleClass(int vehicleClass) {
		this.vehicleClass = vehicleClass;
	}

	public int getDestRoadID() {
		return destRoadID;
	}

	public void setDestRoadID(int destRoadID) {
		this.destRoadID = destRoadID;
	}

	public double getBearing() {
		return bearing_;
	}

	public void setBearing(double bearing_) {
		this.bearing_ = bearing_;
	}

	public int getState() {
		return this.vehicleState;
	}

	public void setState(int newState) {
		this.vehicleState = newState;
	}

	public float getAccummulatedDistance() {
		return accummulatedDistance_;
	}
}
