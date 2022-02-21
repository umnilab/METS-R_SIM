package addsEVs.vehiclecontext;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.CityContext;
import addsEVs.citycontext.Junction;
import addsEVs.citycontext.Lane;
import addsEVs.citycontext.Plan;
import addsEVs.citycontext.Road;
import addsEVs.data.DataCollector;
import addsEVs.routing.RouteV;

import java.util.ArrayList;
import java.util.List;

import org.opengis.referencing.operation.MathTransformFactory;
import org.geotools.referencing.GeodeticCalculator;
import org.geotools.referencing.ReferencingFactoryFinder;
import java.lang.Math;

import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.essentials.RepastEssentials;
import repast.simphony.space.gis.Geography;
import util.Pair;

public class Vehicle {
	protected int id;
	protected int vehicleID_;
	// constants
	public final static int GASOLINE = 0;
	public final static int ETAXI = 1;
	public final static int EBUS = 2;
	public final static int EMPTY = 0;
	public final static int OCCUPIED_TRIP = 1;
	public final static int RELOCATION_TRIP = 2;
	public final static int BUS_TRIP = 3;
	public final static int CHARGING_TRIP = 4;
	public final static int NONE_OF_THE_ABOVE = -1;
	// Vehicle status and class
	private int vehicleClass; //LZ: 0 for gasoline vehicle, 1 for electric vehicle, 2 for bus.
	private int vehicleState; //LZ: 0 for empty, 1 for normal trip, 2 for relocation trip, 3 for bus trip, 4 for charging trip
		
	//Vehicle movement variables that do not need to be visible to descendant classes 
	private int destRoadID;
	private Coordinate currentCoord_; //LZ: this variable is created when the vehicle is initialized 
	private float length;
	private float distance_;// distance from downstream junction
	private double nextDistance_; // distance from the start point of next line segment
	private float currentSpeed_;
	private float accRate_;
	private double bearing_;
	private double desiredSpeed_; // in meter/sec
	private  int regime_;
	private float maxAcceleration_; // in meter/sec2
	private float normalDeceleration_; // in meter/sec2
	private float maxDeceleration_; // in meter/sec2
	private float distanceToNormalStop_; // assuming normal dec is applied
	private float lastStepMove_;
	private double travelPerTurn;
	
	// Vehicle movement that can be accessed through descendant classes
	protected int deptime; 
	protected int endTime;
	protected int lastRouteTime; // The time of getting the last routing information
	protected int destinationZone;
	protected Coordinate originalCoord;
	protected Coordinate destCoord;
	protected Coordinate previousEpochCoord;//HGehlot: this variable stores the coordinates of the vehicle when last time vehicle snapshot was recorded for visualization interpolation 
	protected float accummulatedDistance_;
	protected boolean reachDest;
	protected boolean reachActLocation; // LZ: whether vehicle is on road.
	protected boolean onlane = false;
	protected boolean atOrigin = true;
	
	// a set of zone for the vehicle to visit
	protected ArrayList<Plan> activityplan;
	protected Road road; 
	private Road nextRoad_; 
	protected Lane lane;
	private Lane nextLane_;
	
	// Zhan: For vehicle based routing 
	protected List<Road> roadPath; // The route is always started with the current road, whenever entering the next road, the current road will be popped out
	private List<Coordinate> coordMap;
	private Geography<Lane> laneGeography;
	protected Vehicle leading_; // leading vehicle in the lane
	private Vehicle trailing_; // trailing vehicle in the lane
	protected Vehicle macroLeading_; // BL: leading vehicle on the road (with all lanes combined)
	protected Vehicle macroTrailing_; // BL: trailing vehicle on the road (with all lanes combined)

	// BL: Variables for lane changing model
	private Lane targetLane_; // BL: this is the correct lane that vehicle should change to.
    //	private Lane tempLane_;// BL: this is the adjacent lane toward the target lane direction
	private boolean correctLane; // BL: to check if the vehicle is in the correct lane
	protected boolean nosingFlag;// BL: if a vehicle in MLC and it can't find gap acceptance then nosing is true.
	protected boolean yieldingFlag; // BL: the vehicle need to yield if true
	GeodeticCalculator calculator = new GeodeticCalculator(ContextCreator.getLaneGeography().getCRS());
	MathTransformFactory mtFactory = ReferencingFactoryFinder.getMathTransformFactory(null);
	
	// For adaptive network partitioning
	private int Nshadow; // Number of current shadow roads in the path
	private ArrayList<Road> futureRoutingRoad;
	
	// LZ: For solving the grid-lock issue
	private int lastMoveTick = -1;
	private int stuckTime = 0; // number of times the vehicle get stuck
	protected boolean movingFlag = false; // whether this vehicle is moving

	public Vehicle(int vClass) {
		this.id = ContextCreator.generateAgentID();
		this.currentCoord_ = new Coordinate();
		this.activityplan =  new ArrayList<Plan>(); //empty plan

		this.length = GlobalVariables.DEFAULT_VEHICLE_LENGTH;
		this.travelPerTurn = GlobalVariables.TRAVEL_PER_TURN;
		this.maxAcceleration_ = GlobalVariables.MAX_ACCELERATION;
		this.maxDeceleration_ = GlobalVariables.MAX_DECELERATION;
		this.normalDeceleration_ = -0.5f;

		this.previousEpochCoord = new Coordinate();
		this.endTime = 0;
		this.atOrigin = true;
		this.reachDest = false;
		this.reachActLocation = true; 
		this.accRate_ = 0;
		this.nextLane_ = null;
		this.nosingFlag = false;
		this.yieldingFlag = false;
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		this.nextRoad_ = null;
		this.laneGeography = ContextCreator.getLaneGeography();
		this.coordMap = new ArrayList<Coordinate>();
		this.setDestRoadID(0);
		// upload the vehicle into the queue of the corresponding link
		this.lastStepMove_ = 0;
		this.vehicleID_ = this.id;
		this.accummulatedDistance_ = 0;
		this.roadPath = null;
		this.lastRouteTime = -1;
		//this.setNextPlan();
		
		// For adaptive network partitioning
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
		this.setVehicleClass(vClass);
		this.setState(NONE_OF_THE_ABOVE);
	}

	//Gehlot: This is a new subclass of Vehicle class that has some different parameters like max acceleration and max deceleration 
	public Vehicle(float maximumAcceleration, float maximumDeceleration, int vClass) {
		this.id = ContextCreator.generateAgentID();
		this.currentCoord_ = new Coordinate();
		this.activityplan =  new ArrayList<Plan>(); //empty plan
		
		this.length = GlobalVariables.DEFAULT_VEHICLE_LENGTH;
		this.travelPerTurn = GlobalVariables.TRAVEL_PER_TURN;
		this.maxAcceleration_ = maximumAcceleration;
		this.maxDeceleration_ = maximumDeceleration;
		this.normalDeceleration_ = -0.5f;

		this.previousEpochCoord = new Coordinate();
		this.endTime = 0;
		this.atOrigin = true;
		this.reachDest = false;
		this.reachActLocation = true;
		this.accRate_ = 0;
		this.nextLane_ = null;
		this.nosingFlag = false;
		this.yieldingFlag = false;
		this.macroLeading_ = null;
		this.macroTrailing_ = null;
		this.leading_ = null;
		this.trailing_ = null;
		this.nextRoad_ = null;
		this.laneGeography = ContextCreator.getLaneGeography();
		this.coordMap = new ArrayList<Coordinate>();
		this.setDestRoadID(0);
		// upload the vehicle into the queue of the corresponding link
		this.lastStepMove_ = 0;
		// this.vehicleID_ = h.getId();
		this.vehicleID_ = this.id;
		this.accummulatedDistance_ = 0;
		this.roadPath = null;
		this.lastRouteTime = -1;
		//this.setNextPlan();
		
		// For adaptive network partitioning
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
		this.setVehicleClass(vClass);
	}
	
	// Change the destination of the vehicle
	public void setNextPlan() {
//		Plan current = this.activityplan.get(0);
		Plan next = this.activityplan.get(1);
		this.destinationZone = next.getDestID();
		double duration = next.getDuration();
		int deptime = (int) duration;
		this.setDepTime(deptime);
		CityContext cityContext = (CityContext) ContextCreator.getCityContext();
		this.destCoord = next.getLocation(); //this.destZone.getCoord();
		this.originalCoord = this.getCurrentCoord(); //cityContext.findHouseWithDestID(current.getLocation()).getCoord();
		this.setDestRoadID(cityContext.findRoadAtCoordinates(this.destCoord, true).getLinkid());
		this.atOrigin = true; // LZ: the vehicle will be rerouted to the new target when enters a new link.
		this.activityplan.remove(0); // remove current schedule
	}
	
    // Vehicle enters the road
	public boolean enterNetwork(Road road) {
		Lane firstlane = road.firstLane(); // First lane is the right lane, which is closest to the outside street
		double gap = entranceGap(firstlane);
		int tickcount = (int) RepastEssentials.GetTickCount(); 
		if (gap >= 1.2*this.length() && tickcount > firstlane.getLastEnterTick()) {
			firstlane.updateLastEnterTick(tickcount); 
			this.updateLastMoveTick(tickcount);
			float capspd = (float) road.getFreeSpeed();// calculate the initial speed
			currentSpeed_ = capspd;
			desiredSpeed_ = road.getRandomFreeSpeed();
			road.removeVehicleFromNewQueue(this);
			this.setRoad(road);
			this.setCoordMap(firstlane);
			this.append(firstlane);
			this.appendToRoad(this.road);
			this.setNextRoad();
			this.assignNextLane();
			return true;
		}
		return false;
	}
	
	// A place holder for implementing energy calculation
	public void updateBatteryLevel(){
		//Do nothing
	}

	public Road nextRoad() {
		return this.nextRoad_;
	}
	
	// Clear the legacy impact from the shadow vehicles and future routing vehicles. Performed before next routing computation.
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
		if (this.futureRoutingRoad.contains(r)){
			r.decreaseFutureRoutingVehNum();
			this.futureRoutingRoad.remove(r);
		}
	}
	
	// Set shadow vehicles and future routing road
	public void setShadowImpact() {
		this.Nshadow = GlobalVariables.N_SHADOW;
		if (this.roadPath ==null){
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
			for (int i=0; i < this.Nshadow; i++) {
				Road r = this.roadPath.get(i);
				// Increase the shadow vehicle count: include current road
				if (i < 1) {
					// Current vehicle will always be added by default
					// Set the shadow vehicle count
					r.incrementShadowVehicleNum();
				} else {
					if (cumlativeTT_Nshadow <= GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL * GlobalVariables.SIMULATION_STEP_SIZE) {
						// Set the shadow vehicle count
						r.incrementShadowVehicleNum();
						cumlativeTT_Nshadow += r.getTravelTime();
						shadowCount += 1;
					}
				}
				
				cumulativeTT += r.getTravelTime();
				// Found the road with cumulative TT greater than than network refresh interval, use it as the future routing road
				if (foundFutureRoutingRoad < GlobalVariables.PART_REFRESH_MULTIPLIER) {
					if (cumulativeTT >= GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL * GlobalVariables.SIMULATION_STEP_SIZE){
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
				if (this.road.getLinkid() == this.getDestRoadID() || this.roadPath.size() == 2) { // This is the last road in the route
					this.nextRoad_ = null;
					return;
				}
				// If Vehicle is on charging route, then we trigger this function for charging, else we perform the normal routing
				if (!(this.roadPath == null)) {
					// Compute new route
					if (this.onChargingRoute()){ // tempPath.size() > 1 && this.checkNextLaneConnected(tempPath.get(1))) {
						List<Road> tempPath = RouteV.vehicleRoute(this, this.destCoord); // Use K shortest path
						this.clearShadowImpact();
						this.roadPath = tempPath;
						this.setShadowImpact();
						this.lastRouteTime = (int) RepastEssentials.GetTickCount();
						// this.atOrigin = false;
						this.nextRoad_ = roadPath.get(1);
					} else if(this.stuckTime>GlobalVariables.MAX_STUCK_TIME){ // Stuck in one place for 2 minutes, potentially there is a grid lock
						this.stuckTime = 0; // Refresh the stuck time to prevent the case that this function is called every tick
						List<Road> tempPath = RouteV.vehicleRoute(this, this.destCoord); // Use K shortest path
						this.clearShadowImpact();
						// Compute new route
						this.roadPath = tempPath;
						this.setShadowImpact();
						this.lastRouteTime = (int) RepastEssentials.GetTickCount();
						// this.atOrigin = false;
						this.nextRoad_ = roadPath.get(1);
					}
					else {
						// Stick on the previous route
						this.removeShadowCount(this.roadPath.get(0));
						this.roadPath.remove(0);
						this.nextRoad_ = this.roadPath.get(1);
					}
				}
			} else {
				// Clear legacy impact
				this.clearShadowImpact();
				// Origin = Destination, empty bus 
				this.roadPath = new ArrayList<Road>();
				if(this.getVehicleClass() == 1) {
					ElectricVehicle ev = (ElectricVehicle) this;
					if(ev.getOriginID() == ev.getDestinationID()) {
						this.atOrigin = false;
						this.setReachDest();
						return;
					}
					else if(!ContextCreator.routeResult_received.isEmpty() && GlobalVariables.ENABLE_ECO_ROUTING_EV && !this.onChargingRoute()){
						Pair<List<Road>,Integer> route_result = RouteV.ecoRoute(ev.getOriginID(), ev.getDestinationID());
						this.roadPath = route_result.getFirst();
						ev.setRouteChoice(route_result.getSecond());
					}
				}
				else if(this.getVehicleClass() == 2){
					Bus evBus = (Bus) this;
					if(evBus.getOriginID() == evBus.getDestinationID()) {
						this.atOrigin = false;
						this.setReachDest();
						return;
					}
					else if(!ContextCreator.routeResult_received_bus.isEmpty() && GlobalVariables.ENABLE_ECO_ROUTING_BUS && !this.onChargingRoute()){
						Pair<List<Road>,Integer> route_result = RouteV.ecoRouteBus(evBus.getOriginID(), evBus.getDestinationID());
						this.roadPath = route_result.getFirst();
						evBus.setRouteChoice(route_result.getSecond());
					}
				}
					// Compute new route 
				if (this.roadPath.isEmpty()) {
					this.roadPath = RouteV.vehicleRoute(this, this.destCoord); // K-shortest path
				}
				this.setShadowImpact();
				this.lastRouteTime = (int) RepastEssentials.GetTickCount();
				if (this.roadPath == null || this.roadPath.size() == 0) {// Fail to find the route
					this.atOrigin = false;
					this.nextRoad_ = null;
				} else {
					this.atOrigin = false;
					this.nextRoad_ = roadPath.get(1);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("No next road found for Vehicle " + this.getId() + "( roadPath : " + this.roadPath + ")"
					+ " on Road " + this.road.getLinkid());
			this.nextRoad_ = null;
		}
	}

	// BL: Append a vehicle to vehicle list in plane
	public void append(Lane plane) {
		this.lane = plane;
		Vehicle v = plane.lastVehicle();
		plane.addVehicles();
		if (v != null) {
			this.leading(v);
			v.trailing(this);
		} else {
			plane.firstVehicle(this);
			this.leading(null);
		}
		this.trailing(null);
		plane.lastVehicle(this);
		this.onlane = true;
	}
    
	// Using coordinates of plane to update the vehicles' current&to go locations.
	public void setCoordMap(Lane plane) {
		if (plane != null) { 
			coordMap.clear();
			Coordinate[] coords = laneGeography.getGeometry(plane).getCoordinates();
			coordMap.clear();
			for (Coordinate coord : coords) {
				this.coordMap.add(coord);
			}
			this.setCurrentCoord(this.coordMap.get(0));//LZ: update the vehicle location to be the first pt in the coordMap
			this.coordMap.remove(0);
			this.distance_ = (float) plane.getLength();
			
			double[] distAndAngle = new double[2];
			this.distance2(this.getCurrentCoord(), this.coordMap.get(0), distAndAngle);
			this.nextDistance_ = distAndAngle[0];
			this.setBearing(distAndAngle[1]);
		} else {
			System.out.println("There is no target lane to set!");
		}
		if (Double.isNaN(distance_)) {
			System.out.println("distance_ is NaN in setCoordMap for " + this);
		}
	}

	
	// for lane changing, update the coordinates of vehicle to the lane coordinate
	private void updateCoordMap(Lane lane) {
		Coordinate[] coords = laneGeography.getGeometry(lane).getCoordinates();
		coordMap.clear();
		double accDist = lane.getLength();
		for (int i = 0; i < coords.length - 1; i++) {
			accDist-=distance(coords[i], coords[i+1]);
			if (this.distance_ >= accDist) { // Find the first pt in CoordMap that has smaller distance_;
				double[] distAndAngle = new double[2];
				distance2(coords[i], coords[i+1], distAndAngle);
				move2(coords[i], coords[i+1], distAndAngle[0], distAndAngle[0] - (this.distance_- accDist)); // Update vehicle location
				this.nextDistance_ = (this.distance_- accDist);
				this.setBearing(distAndAngle[1]);
				for (int j = i+1; j < coords.length; j++){ // Add the rest coords into the CoordMap
					coordMap.add(coords[j]);
				}
				break;
			}
		}
		if (coordMap.size() == 0) {
			double[] distAndAngle = new double[2];
			distance2(coords[coords.length-2], coords[coords.length-1], distAndAngle);
			move2(coords[coords.length-2], coords[coords.length-1], distAndAngle[0], distAndAngle[0] - this.distance_); // Update vehicle location
			this.nextDistance_ = this.distance_;
			this.setBearing(distAndAngle[1]);
			coordMap.add(coords[coords.length-1]);
		}
	}
	
	public void clearMacroLeading(){
		this.macroLeading_ = null;
	}

	public boolean calcState() {
		this.makeAcceleratingDecision();
		if (this.road == null) {
			return false;
		}
		if (this.road.getnLanes() > 1 && this.onlane && this.distance_ >= GlobalVariables.NO_LANECHANGING_LENGTH) {
			this.makeLaneChangingDecision();
		}
		return true;
	}

	/*
	 * -------------------------------------------------------------------- The
	 * Car-Following model calculates the acceleration rate based on interaction
	 * with other vehicles. The function returns a the most restrictive
	 * acceleration (deceleration if negative) rate among the rates given by
	 * several constraints. This function updates accRate_ at the end.
	 * --------------------------------------------------------------------
	 */

	public void makeAcceleratingDecision() {
		
		Vehicle front = this.vehicleAhead();
		float aZ = this.accRate_; /* car-following */
		float acc = this.maxAcceleration(); /* returned rate */
		/*
		 * BL: vehicle will have acceleration rate based on car following if it
		 * is not in yielding or nosing state
		 */
		if (!this.nosingFlag && !this.yieldingFlag) {
			aZ = this.calcCarFollowingRate(front);
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

		accRate_ = acc;
		if (Double.isNaN(accRate_)) {
			System.err.println("NaN acceleration rate for " + this);
		}
	}

	public float calcCarFollowingRate(Vehicle front) {
		// SH-if there is no front vehicle the car will be in free flow regime and have max acceleration

		if (front == null) {
			regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			return (this.maxAcceleration_);
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

		// There will be three regimes emergency/free-flow/car-following regime depending on headway

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
			acc = Math.min(normalDeceleration_, acc);
			regime_ = GlobalVariables.STATUS_REGIME_EMERGENCY;
		}
		// Free-flow regime
		else if (headway > hupper) { // desired speed model will do
			if (space > distanceToNormalStop_) {
				acc = maxAcceleration_;
				regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			} else {
				float dt = GlobalVariables.SIMULATION_STEP_SIZE;
				float v = front.currentSpeed_ + front.accRate_ * dt;
				space += 0.5 * (front.currentSpeed_ + v) * dt;
				acc = brakeToTargetSpeed(space, v);
				regime_ = GlobalVariables.STATUS_REGIME_FREEFLOWING;
			}
		}
		// SH: We are using Herman model
		else {
			float dv = front.currentSpeed_ - currentSpeed_;
			if (dv < 0) {
				acc = dv * AlphaDec * (float) Math.pow(currentSpeed_, BetaDec)
						/ (float) (Math.pow(space, GammaDec));

			} else if (dv > 0) {
				acc = dv * AlphaAcc * (float) Math.pow(currentSpeed_, BetaAcc)
						/ (float) (Math.pow(space, GammaAcc));
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
			if (nextLane_.lastVehicle() != null)
				return nextLane_.lastVehicle();
			else
				return null;
		} else {
			return null;
		}
	}

	public Junction nextJuction() {
		Junction nextJunction;
		if (this.nextRoad() == null)
			return null;
		if (this.nextRoad().getJunctions().get(0).getID() == this.road
				.getJunctions().get(1).getID())
			nextJunction = this.road.getJunctions().get(1);
		else
			nextJunction = this.road.getJunctions().get(0);
		return nextJunction;
	}

	public float gapDistance(Vehicle front) {
		float headwayDistance;
		if (front != null) { /* vehicle ahead */	
			if (front.isOnLane()){
				if (this.lane.getID() == front.lane.getID()) { /* same lane */
					headwayDistance = this.distance_ - front.distance() - front.length();
				} else { /* different lane */
					headwayDistance = this.distance_
							+ (float) front.lane.getLength() - front.distance(); //LZ: front vehicle is in the next road
				}
			}
			else{ // Front vehicle is in the intersection
				headwayDistance = this.distance_ - front.length;
			}			
		} else { /* no vehicle ahead. */
			headwayDistance = Float.MAX_VALUE;
		}
		if (Double.isNaN(headwayDistance)) {
			System.out.println("headway is NaN");
		}
		return (headwayDistance);
	}

	public void makeLaneChangingDecision() {
		if (this.distFraction() < 0.5) { 
			// Halfway to the downstream intersection, only mantatory LC allowed, check the correct lane
			if (this.isCorrectLane() != true) { // change lane if not in correct
				// lane
				Lane plane = this.tempLane();
				if (plane != null)
					this.mandatoryLC(plane);
				else {
					//HG and XQ: commented out this because now we are allowing forced jump in 5 leg or irregular intersections for which 
					//it is not possible to assign lane information properly (as only left, right and through are allowed.)). Previously 5 leg intersections were
					//converted to 4 leg intersections in the shape file and then this comment was put to check if anything goes wrong. But we leave all intersections as it they are.
					//so, this error will get printed but forced jump will bypass blocking of vehicles.
//					System.out.println("Vehicle " + this.getId()
//							+ "has no lane to change");
//					System.out.println("this vehicle is on road "
//							+ this.road.getID() + " which has "
//							+ this.road.getnLanes()
//							+ " lane(s) and I am on lane "
//							+ this.road.getLaneIndex(this.lane)
//							+"with Lane ID" + this.lane.getID());
				}
			}
		} else {
			if (this.distFraction() > 0.75) {
				// First 25% in the road, do discretionary LC with 100% chance
				double laneChangeProb1 = GlobalVariables.RandomGenerator
						.nextDouble();
				// The vehicle is at beginning of the lane, it is free to change lane
				Lane tarLane = this.findBetterCorrectLane();
				if (tarLane != null) {
					if (laneChangeProb1 < 1.0)
						this.discretionaryLC(tarLane);
				}
			} else {
				// First 25%-50% in the road, we do discretionary LC but only to correct lanes with 100% chance
				double laneChangeProb2 = GlobalVariables.RandomGenerator
						.nextDouble();
				// The vehicle is at beginning of the lane, it is free to change lane
				Lane tarLane = this.findBetterCorrectLane();
				if (tarLane != null) {
					if (laneChangeProb2 < 1.0)
						this.discretionaryLC(tarLane);
				}
				
			}
		}
	}	

	/*
	 * HGehlot: Record the vehicle snapshot if this tick corresponds to the required epoch that is needed for visualization interpolation.
	 * Note that this is recording is independent of snapshots of vehicles whether they move or not in the current tick. 
	 * (So when vehicles do not move in a tick but we need to record positions for viz interpolation then recVehSnaphotForVisInterp is useful). 
	 * Also, we update the coordinates of the previous epoch in the end of the function.
	 */ 
	public void recVehSnaphotForVisInterp(){
		Coordinate currentCoord = this.getCurrentCoord();
		try {
			//HG: the following condition can be put to reduce the data when the output of interest is the final case when vehicles reach close to destination
//			if(this.nextRoad() == null){
				DataCollector.getInstance().recordSnapshot(this, currentCoord);//HGehlot: I use currentCoord rather than the targeted coordinates (as in move() function) and this is an approximation but anyway if the vehicle moves then it will get overriden.
//			}
		}
		catch (Throwable t) {
		    // could not log the vehicle's new position in data buffer!
		    DataCollector.printDebug("ERR" + t.getMessage());
		}
		setPreviousEpochCoord(currentCoord);//update the previous coordinate as the current coordinate
	}
	
	
	
	public Coordinate getpreviousEpochCoord() {
		return this.previousEpochCoord;
	}
	
	protected void setPreviousEpochCoord(Coordinate newCoord){
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
		try {
			if (!this.reachDest && !this.reachActLocation) {
				this.move(); // move the vehicle towards their destination
				this.advanceInMacroList(); // BL: if the vehicle travel too fast, it will change the marcroList of the road.
				
				// up to this point this.reachDest == false;
				if (this.nextRoad() == null) {
					this.checkAtDestination();
				}
			}

		} catch (Exception e) {
			try { // print the error-causing vehicle during move()
				System.err.println("Vehicle " + this.getVehicleID()
						+ " had an error while travelling on road: "
						+ this.road.getLinkid() + "with next road: "
						+ this.nextRoad().getLinkid());
				e.printStackTrace();
				RunEnvironment.getInstance().pauseRun();
			}
			catch (NullPointerException exc) { // LZ,RV: in case next road is null
				System.err.println("Vehicle " + this.getVehicleID()
				+ " had an error while travelling on road: "
				+ this.road.getLinkid() + "with next road: ");
				e.printStackTrace();
				RunEnvironment.getInstance().pauseRun();
			}
		}
	}

	public void move() {
		// validation checks
		if (distance_ < 0 || Double.isNaN(distance_))
			System.out.println("Vehicle.move(): distance_=" + distance_ + " " + this);
		if (currentSpeed_ < 0 || Float.isNaN(currentSpeed_))
			System.out.println("Vehicle.move(): currentSpeed_=" + currentSpeed_ + " " + this);
		if (Double.isNaN(accRate_))
			System.out.println("Vehicle.move(): accRate_="+accRate_+" "+this);
				
		// LZ: The vehicle is close enough to the intersection/destination initialization
		Coordinate currentCoord = null;
		Coordinate target = null;
		double dx = 0; // LZ: travel distance calculated by physics
		boolean travelledMaxDist = false; // true when traveled with maximum distance (=dx).
		float distTravelled = 0; // the distance traveled so far.
		float oldv = currentSpeed_; // speed at the beginning
		float step = GlobalVariables.SIMULATION_STEP_SIZE; // 0.3 s
		double minSpeed = GlobalVariables.SPEED_EPSILON; // min allowed speed (m/s)
		double minAcc = GlobalVariables.ACC_EPSILON; // min allowed acceleration(m/s2)
		double maxSpeed = this.desiredSpeed_;
		
		Road current_road = this.road;
		if (distance_ < GlobalVariables.INTERSECTION_BUFFER_LENGTH) {
			if (this.nextRoad() != null) { // this has not reached destination
				if (this.isOnLane()) { // On lane
					this.coordMap.add(this.getCurrentCoord()); // Stop and wait
					int canEnterNextRoad = this.appendToJunction(nextLane_);
					if (canEnterNextRoad == 0) { // This will make this.isOnLane becomes false, return 0 means the vehicle cannot enter the next road
						this.lastStepMove_ = 0;
						this.movingFlag = false;
					} else {
						current_road.recordEnergyConsumption(this);
						this.lastStepMove_ = distance_; // Successfully entered the next road, update the lastStepMove and accumulatedDistance
						this.accummulatedDistance_ += this.lastStepMove_;
						this.movingFlag = true;
					}
					return; // move finished
				} else { // not on lane, directly changing road
					if (this.changeRoad() == 0) { // 0 means the vehicle cannot enter the next road
						stuckTime+=1;
						this.lastStepMove_ = 0;
						this.movingFlag = false;
					} else {
						stuckTime=0;
						current_road.recordEnergyConsumption(this);
						this.lastStepMove_ = distance_; //Successfully entered the next road, update the lastStepMove and accumulatedDistance
						this.accummulatedDistance_ += this.lastStepMove_;
						this.movingFlag = true;
					}
				}
			}
			return;// move finished
		}

		float dv = accRate_ * step; // Change of speed
		if (dv > -currentSpeed_) { // still moving at the end of the cycle
			dx = currentSpeed_ * step + 0.5f * dv * step;

		} else { // stops before the cycle end
			dx = -0.5f * currentSpeed_ * currentSpeed_ / accRate_;
			if (currentSpeed_ < minSpeed && accRate_ < minAcc) {
				dx = 0.0f;
			}
		}
		if (Double.isNaN(dx)) {
			System.out.println("dx is NaN in move() for " + this);
		}

		// Solve the crash problem 
		double gap = gapDistance(this.vehicleAhead());
		dx = Math.max(0, Math.min(dx, gap));
		
		// actual acceleration rate applied in last time interval.
		accRate_ = (float) (2.0f * (dx - oldv * step) / (step * step));

		// update speed
		currentSpeed_ += accRate_ * step;
		if (currentSpeed_ < minSpeed) {
			currentSpeed_ = 0.0f;
			accRate_ = 0.0f; // no back up allowed
		} else if (currentSpeed_ > maxSpeed && accRate_ > minAcc) {
			currentSpeed_ = (float) maxSpeed;
			accRate_ = ((currentSpeed_ - oldv) / step);
		}

		if (dx < 0.0f) {
			lastStepMove_ = 0;
			this.movingFlag = false;
			return;
		}
		
		double[] distAndAngle = new double[2];
		while (!travelledMaxDist) {
			target = this.coordMap.get(0);

			// If we can get all the way to the next coords on the route then, just go there
			if (distTravelled + nextDistance_ <= dx) {
				distTravelled += nextDistance_;
				this.setCurrentCoord(target);
				// LZ: Oct 31, the distance and calculated value is not consistent (Vehicle reached the end of the link does not mean Vehicle.distance_ <= 0), therefore, use a intersection buffer （see the changeRoad block above)
				this.coordMap.remove(0);
				if (this.coordMap.isEmpty()) {
					this.distance_ -= nextDistance_;
					this.nextDistance_ = 0;
					if (this.nextRoad() != null) { // has next road
						if (this.isOnLane()) {
							this.coordMap.add(this.getCurrentCoord()); // Stop and wait
							if(this.appendToJunction(nextLane_)==0){
								stuckTime+=1;
							}else{
								stuckTime = 0;
								current_road.recordEnergyConsumption(this);
							}
							lastStepMove_ = distTravelled;
							accummulatedDistance_ += distTravelled;
							break; 
						} else {
							if(this.changeRoad()==0){
								stuckTime+=1;
							}else{
								stuckTime=0;
								current_road.recordEnergyConsumption(this);
							}
							lastStepMove_ = distTravelled;
							accummulatedDistance_ += distTravelled;
							break;
						}
					} else { // no next road, the vehicle arrived at the destination
						this.coordMap.clear();
						this.coordMap.add(this.currentCoord_);
						break;
					}
				}
				else{
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
			// (http://forum.java.sun.com/thread.jspa?threadID=438608&messageID=1973655)
			// LZ: replaced the complicated operation with a equivalent but simpler one
			else {
				double distanceToTarget = this.nextDistance_;
				this.distance_ -= dx - distTravelled;
				this.nextDistance_ -= dx - distTravelled;
				currentCoord = this.getCurrentCoord();
				move2(currentCoord, this.coordMap.get(0), distanceToTarget, dx-distTravelled);
				distTravelled = (float) dx;
				this.accummulatedDistance_ += dx;
				lastStepMove_ = (float) dx;
				travelledMaxDist = true;
			}
		}
		// update the position of vehicles, 0<=distance_<=lane.length()
		if (distance_ < 0) {
			distance_=0;
		}
		//LZ: For debugging, here we observed that this.distance_ can be set to NaN, but other values are valid (even in the first time this message occured)
		if(distTravelled>0){
			this.movingFlag = true;
		}
		else{
			this.movingFlag = false;
		}
		return;
	}

	public void printGlobalVehicle(float dx) {
		if (this.vehicleID_ == GlobalVariables.Global_Vehicle_ID) {
			System.out.println("Next Road ID for vhielc: " + this.vehicleID_
					+ " is: " + this.nextRoad().getLinkid() + " step size is: "
					+ dx + " distance to downstream: " + this.distance_ + 
					" next lane: " + this.nextLane_
					+ " current speed: " + this.currentSpeed_);
		}
	}

	public void primitiveMove() {
		Coordinate currentCoord = null;
		Coordinate target = null;
		if (this.atDestination()) {
			return;
		}
		currentCoord = this.getCurrentCoord();
		// The first list of coordinates for the vehicle to follow
		if (this.coordMap.size() > 0) {
			target = this.coordMap.get(0);
		} else {
			lane = this.road.firstLane();
			Coordinate[] coords = laneGeography.getGeometry(lane).getCoordinates();
			for(Coordinate coord: coords){
				this.coordMap.add(coord);
			}
			//this.setCoordMap(lane);
			target = this.coordMap.get(0);
		}
		
		double[] distAndAngle = new double[2];
		double distToTarget;
		distToTarget = this.distance2(currentCoord, target, distAndAngle);
		
		if (distToTarget <= travelPerTurn) { // Include equal, which is important
			// this.lock.lock();
			// vehicleGeography.move(this, targetGeom);
			this.setCurrentCoord(target);
		}
		else {
			// double angle = angle(target, currentCoord) + Math.PI; //
			// angle()
			// returns range from -PI->PI, but moveByVector wants range
			// 0->2PI
			double distToTravel = travelPerTurn;
			// Need to convert distance from long/lat degrees to meters
//			double distToTravelM = ContextCreator.convertToMeters(distToTravel);
//			System.out.println(distToTravel);
			// System.out.println("Angle: "+angle);
			this.accummulatedDistance_ += distToTravel;
			move2(currentCoord, target, distToTarget, distToTravel);
		}
		return;
	}

	/**
	 * SH: This function change the vehicle from its current road to the next
	 * road.
	 * 
	 * @return 0-fail , 1-success to change the road
	 */

	public int changeRoad() {
		// SH- check if the vehicle has reached the destination or not
		if (this.atDestination()) {
			this.clearShadowImpact(); // ZH: Clear shadow impact if already reaches destination
			return 0; // only one will reach destination once
		} else if (this.nextRoad_ != null) {
			// BL: check if there is enough space in the next road to change to
			int tickcount = (int) RepastEssentials.GetTickCount();
			// LZ: Nov 4, short lane and the vehicle can move freely
			// Check if the target long road has space
			if (this.entranceGap(nextLane_) >= 1.2 * this.length() 
					&& (tickcount > this.nextLane_.getLastEnterTick())) { 
				this.nextLane_.updateLastEnterTick(tickcount); //LZ: update enter tick so other vehicle cannot enter this road in this tick
				this.setCoordMap(nextLane_);
				this.removeFromLane();
				this.removeFromMacroList();
				this.appendToRoad(this.nextRoad());
				this.append(nextLane_); // LZ: Two vehicles entered the same lane, then messed up.
				this.setNextRoad();
				this.assignNextLane();
				// Reset the desired speed according to the new road
				this.desiredSpeed_ =  this.road.getRandomFreeSpeed();
				//LZ: Use current speed instead of the free speed, be consistent with the setting in enteringNetwork
				if(this.currentSpeed_ > this.desiredSpeed_)
					this.currentSpeed_ = (float) this.desiredSpeed_;
				return 1;
//				}
			}
			else if (this.stuckTime > GlobalVariables.MAX_STUCK_TIME) {
				this.lastRouteTime = -1; //old route is not valid 
				this.setNextRoad();
				this.assignNextLane();
			}
		}
		coordMap.clear();
		coordMap.add(this.getCurrentCoord());//LZ: Fail to enter next link, try again in the next tick
		return 0;
	}

	public int closeToRoad(Road road) {
		// SH Temp
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
//		System.out.println(distance(currentCoord, nextCoord));
		if (distance(currentCoord, nextCoord) < GlobalVariables.TRAVEL_PER_TURN) {
//			System.out.println("THIS");
			return 1;
			
		} else
			return 0;
	}

	public boolean atDestination() {
		return this.reachDest;
	}

	public boolean atActivityLocation() {
		return this.reachActLocation;
	}

	public boolean checkAtDestination() throws Exception { // Close to the last intersection
		// double maxMove = this.road.getFreeSpeed() * GlobalVariables.SIMULATION_STEP_SIZE;
		if (distance_ < GlobalVariables.INTERSECTION_BUFFER_LENGTH) {
			this.setReachDest();
			return true;
		}
		return false;
	}

	public float maxAcceleration() {
		return maxAcceleration_;
	}

	public void appendToRoad(Road road) {
		this.road = road;
		this.appendToMacroList(road);
		this.reachActLocation = false; // Mark the vehicle condition to be on the road (neither at origin or destination)
	} 

	public void appendToMacroList(Road road) {
		macroTrailing_ = null;
		//LZ: Oct 14, 2020 update
		//This has trouble with the advanceInMacroList 
		//If the macroLeading is modified in advanceInMacroList by other thread
		//Then this vehicle will be misplaced in the Linked List
		if (road.lastVehicle() != null){
			road.lastVehicle().macroTrailing_ = this; 
			macroLeading_ = road.lastVehicle();
		}
		else{
			macroLeading_ = null;
			road.firstVehicle(this);
		}
		road.lastVehicle(this);
		// after this appending, update the number of vehicles
//		Vehicle pv = road.firstVehicle();
//		int nVehicles_ = 0;
//		while (pv != null) {
//			nVehicles_++;
//			pv = pv.macroTrailing_;
//		}
		road.changeNumberOfVehicles(1);
	}

	public void leading(Vehicle v) {
		if (v != null)
			this.leading_ = v;
		else
			this.leading_ = null;
	}

	public Vehicle leading() {
		return leading_;
	}

	public Vehicle macroLeading() {
		return macroLeading_;
	}

	public Vehicle trailing() {
		return trailing_;
	}

	public Vehicle macroTrailing() {
		return macroTrailing_;
	}

	public void trailing(Vehicle v) {
		if (v != null)
			this.trailing_ = v;
		else
			this.trailing_ = null;
	}

	public void setDepTime(int time) {
		this.deptime = time;
	}

	public int getDepTime() {
		return this.deptime;
	}
	
	public int getEndTime() {
	    return this.endTime;
	}
	
	public void setRoad(Road road) {
		this.road = road;
		this.currentSpeed_ = (float) this.road.getFreeSpeed();
	}

	public Road getRoad() {
		return road;
	}

	public float distance() {
		return distance_;
	}

	public float distFraction() {
		if (distance_ > 0)
			return distance_ / (float) this.road.length();
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

	public int getDestinationZoneID() {
		return this.destinationZone;
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
//		if(this.activityplan.size()>2){ //If there are three plans in a vehicle (ridesharing) in the same time
//			System.out.println("Wow: "+this.activityplan+" "+ this.activityplan.get(0).getDestID()+" "+ this.activityplan.get(1).getDestID()+" "+ this.activityplan.get(2).getDestID());
//		}
		//System.out.println("Plan added");
	}

	public Coordinate getOriginalCoord() {
		return this.originalCoord;
	}

	public Coordinate getDestCoord() {
		return this.destCoord;
	}

	public void setOriginalCoord(Coordinate coord) {
		this.originalCoord = coord;
	}
	
	public Coordinate getCurrentCoord() {
		Coordinate coord = new Coordinate();
		coord.x = this.currentCoord_.x;
		coord.y = this.currentCoord_.y;
		coord.z = this.currentCoord_.z;
		return coord;
	}
	
	public void setCurrentCoord(Coordinate coord) {
		this.currentCoord_.x = coord.x;
		this.currentCoord_.y = coord.y;
		this.currentCoord_.z = coord.z;
	}

	public int nearlyArrived(){//HG: If nearly arrived then return 1 else 0
		if(this.nextRoad_ == null){
			return 1;
		}else{
			return 0;
		}
	}
	
	// Modify this, when arrive, then set current activity be the destination and do nothing
	public void setReachDest() {
		this.leaveNetwork(); // remove from the network 
		// Vehicle arrive
		this.endTime = (int) RepastEssentials.GetTickCount();
//		this.reachDest = true;
	}
	
	
	protected void leaveNetwork() {
		Coordinate target = null;
//		GeometryFactory geomFac = new GeometryFactory();
		target = this.destCoord;
		this.setCurrentCoord(target);
//		Geometry targetGeom = geomFac.createPoint(target);
//		ContextCreator.getVehicleGeography().move(this, targetGeom);
		this.clearShadowImpact();
		this.removeFromLane();
		this.removeFromMacroList();
		this.setPreviousEpochCoord(target);
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
		// upload the vehicle into the queue of the corresponding link
		this.lastStepMove_ = 0;
		//this.vehicleID_ = h.getId();
		this.accummulatedDistance_ = 0;
		this.roadPath = null;
		this.lastRouteTime = -1;
		//this.setNextPlan();
		// For adaptive network partitioning
		this.Nshadow = 0;
		this.futureRoutingRoad = new ArrayList<Road>();
	}

	public void setLastRouteTime(int routeTime) {
		this.lastRouteTime= routeTime;
	}
	
	public float currentSpeed() {
		return currentSpeed_;
	}

//	public void resetVehicle() {
////		CityContext cityContext = (CityContext) ContextCreator.getCityContext();
////		Coordinate currentCoord = ContextCreator.getVehicleGeography().getGeometry(this)
////				.getCoordinate();
////		Road road = cityContext.findRoadAtCoordinates(currentCoord, false); // todest=false
//		// TODO: Remove one of the two add queue functions after finish
//		// debugging
//		// road.addVehicleToQueue(this);
//		this.setNextPlan();
////		this.reachActLocation = true;
////		
////		road.addVehicleToNewQueue(this);
//		this.nextRoad_ = null;
//		this.nextLane_ = null;
//		this.nosingFlag = false;
//		this.yieldingFlag = false;
//		this.macroLeading_ = null;
//		this.macroTrailing_ = null;
//		this.leading_ = null;
//		this.trailing_ = null;
//		this.coordMap = new ArrayList<Coordinate>();
//		this.accummulatedDistance_ = 0;
//	}

//	public void killVehicle() {
//		this.road = null;
//		this.lane = null;
//		this.laneGeography = null;
//		this.nextLane_ = null;
//		this.nosingFlag = false;
//		this.yieldingFlag = false;
//		this.macroLeading_ = null;
//		this.macroTrailing_ = null;
//		this.leading_ = null;
//		this.trailing_ = null;
//		this.coordMap = null;
//		this.currentSpeed_ = 0.0f;
//		this.moveVehicle = false;
//		this.destCoord = null;
//		this.destZone = null;
//		this.targetLane_ = null;
//		this.tempLane_ = null;
//		this.clearShadowImpact(); // ZH: clear any remaining shadow impact
//		// TODO: try the remove method in the vehicleContext
//	}

	/*
	 * ----------------------------------------------------------------------
	 * BL: From this we build functions that are used to incorporate lane
	 * object.
	 * ----------------------------------------------------------------------
	 */
	// BL: In one road find the front leader in the same lane of the road, if
	// there is no front, then return null.
	public Vehicle findFrontBumperLeaderInSameRoad(Lane plane) {
		Vehicle front = this.macroLeading_;
		while (front != null && front.lane != plane) {
			front = front.macroLeading_;
		}
		return front;
	}

	public void removeFromLane() {
		if (this.trailing_ != null) {
			this.lane.firstVehicle(this.trailing_);
			this.trailing_.leading(null);
		} else {
			this.lane.firstVehicle(null);
			this.lane.lastVehicle(null);
		}
		this.lane.removeVehicles();
	}
	
	public void updateLastMoveTick(int current_tick){
		this.lastMoveTick = current_tick;
	}
	
	public int getLastMoveTick(){
		return this.lastMoveTick;
	}

	// BL: remove a vehicle from the macro vehicle list in the current road
	// segment.
	public void removeFromMacroList() {
		// Current road of this vehicle
		Road pr = this.getRoad();
		// if this is not the first vehicle on the road
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
		// pr.removeVehicleFromRoad(this);
		// TODO: need to change the above line by following line later
		// pr.removeVehicleFromRoad();
//		Vehicle pv = pr.firstVehicle();
//		int nVehicles_ = 0;
//		while (pv != null) {
//			nVehicles_++;
//			pv = pv.macroTrailing_;
//		}
		pr.changeNumberOfVehicles(-1);
	}

	/*
	 * ------------------------------------------------------------------- BL:
	 * Advance a vehicle to the position in macro vehicle list that
	 * corresponding to its current distance. This function is invoked whenever
	 * a vehicle is moved (including moved into a downstream segment), so that
	 * the vehicles in macro vehicle list is always sorted by their position.
	 * The function is called in travel()
	 * -------------------------------------------------------------------
	 */
	/**
	 * @param: distance_ is with respect to the end of road
	 */
	// BL: changed from distance_ to realDistance_ (Jul 27/2012)
	public void advanceInMacroList() {
		// (0) check if vehicle should be advanced in the list
		if (macroLeading_ == null || this.distance_ >= macroLeading_.distance_) {
			// no macroLeading or the distance to downstream node is greater
			// than marcroLeading
			// no need to advance this vehicle in list
			return;
		}
		// (1) find vehicle's position in the list
		// now this vehicle has a macroLeading that has the higher distance to
		// downstream node.
		// that should not be the vehicle marcroLeading anymore. Need to find
		// new marcroLeading.
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
			this.macroTrailing_ = macroLeading_.macroTrailing_;
			this.macroLeading_.macroTrailing_ = this;
		} else {
			this.macroTrailing_ = pr.firstVehicle();
			pr.firstVehicle(this);
		}
		// (3.2) Point to the trailing vehicle
		if (macroTrailing_ != null) {
			macroTrailing_.macroLeading_ = this;
		} else {
			pr.lastVehicle(this);
		}
	}

	/*
	 * function: checkCorrectLane() BL: this function will check if the current
	 * lane connect to a lane in the next road if yes then it gives the
	 * checkLaneFlag true value if not then the checkLaneFlag has false value
	 * the function will be called after the vehicle updates its route i.e. the
	 * routeUpdateFlag has true value
	 */

	public boolean isCorrectLane() {
		
		if (nextRoad() == null)
			return true;
		Lane nextLane = this.getNextLane();
		// if using dynamic shortest path then we need to check lane only after
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

		if (this.nextRoad() == null) {
			if (this.getVehicleID() == GlobalVariables.Global_Vehicle_ID)
				System.out.println("Assign next lane: current link ID= "
						+ curRoad.getLinkid() + " current lane ID: "
						+ curLane.getLaneid() + " next link ID="
						+ this.nextRoad());
			this.nextLane_=null;
			return;
		} else {
//			Junction curUpJunc, curDownJunc, nextUpJunc, nextDownJunc;
//			curUpJunc = this.road.getJunctions().get(0);
//			curDownJunc = this.road.getJunctions().get(1);
//			nextUpJunc = this.nextRoad_.getJunctions().get(0);
//			nextDownJunc = this.nextRoad_.getJunctions().get(1);
			for (Lane dl : curLane.getDnLanes()) {
				if (dl.road_().equals(this.nextRoad())) {
					this.nextLane_ = dl;
					// if this lane already connects to downstream road then
					// assign to the connected lane
					connected = true;
					break;
				}
			}
			if (!connected) {
				for (Lane pl : this.nextRoad().getLanes()) {
					for (Lane ul : pl.getUpLanes()) {
						if (ul.road_().getID() == curRoad.getID()) {
							this.nextLane_ = pl;
							break; // assign the next lane to the 1st connected
							// lane
						}
					}
				}
				this.nextLane_ = this.nextRoad().getLane(0);//HG and XQ: force movement at a 5 leg or irregular intersection
			}
			if (this.nextLane_ == null)
				System.err.println("No next lane found for vehicle: "
						+ this.vehicleID_ + " moving on the road: "
						+ this.getRoad().getLinkid() + " lane: "
						+ this.getLane().getLaneid() + " heading to location "
						+ this.getDestinationZoneID()
						+ " while looking for next lane on road: "
						+ this.nextRoad().getLinkid() + " that has "
						+ this.nextRoad().getnLanes() + " lanes");
		}
		// this.updateCoorMapWithNewLane();
	}

	/*
	 * BL: nextLane -> get next lane of a vehicle From the next Road of a
	 * vehicle. Check each lane and see which lane connects to vehicle's current
	 * Road
	 */
	public Lane getNextLane() {
		return this.nextLane_;
	}

	/*
	 * BL: return the target lane (the lane that connect to the downstream Road)
	 */
	public Lane targetLane() {
		Road curRoad = this.getRoad();
		Lane nextLane = this.getNextLane();
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
	 * BL: return the next lane that the vehicle need to change to in order to
	 * reach the correct lane
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

	// get left lane
	public Lane leftLane() {
		Lane leftLane = null;
		if (this.road.getLaneIndex(this.lane) > 0) {
			leftLane = this.road.getLane(this.road.getLaneIndex(this.lane) - 1);
		}
		return leftLane;
	}

	// get right lane
	public Lane rightLane() {
		Lane rightLane = null;
		if (this.road.getLaneIndex(this.lane) < this.road.getnLanes() - 1) {
			rightLane = this.road
					.getLane(this.road.getLaneIndex(this.lane) + 1);
		}
		return rightLane;
	}

	/*
	 * BL: This function change the lane of a vehicle regardless it is MLC or
	 * DLC state. The vehicle change lane when its lead and lag gaps are
	 * acceptable. This will not change the speed of the vehicle, the only
	 * information updated in this function is as follow: remove the vehicle
	 * from old lane and add to new lane. Re-assign the leading and trailing
	 * sequence of the vehicle.
	 */
	public void changeLane(Lane plane, Vehicle leadVehicle, Vehicle lagVehicle) {
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
			
			this.lane.lastVehicle(curLeading);
			curLeading.trailing(null);
		} else {
			this.lane.firstVehicle(null);
			this.lane.lastVehicle(null);
		}

		this.lane.removeVehicles();
		/*
		 * BL: after change the lane the vehicle updates its leading and
		 * trailing in the target lanes. and also the lead and lag vehicle have
		 * to update its leading and trailing.
		 */
		this.lane = plane;// vehicle move to the target lane
		if (leadVehicle != null) {
			this.leading_ = leadVehicle;
			this.leading_.trailing(this);
			if (lagVehicle != null) {
				this.trailing_ = lagVehicle;
				this.trailing_.leading(this);
			} else {
				this.trailing(null);
				this.lane.lastVehicle(this);
			}
		} else if (lagVehicle != null) {
			this.lane.firstVehicle(this);
			this.trailing_ = lagVehicle;
			this.trailing_.leading(this);
		} else {
			this.lane.firstVehicle(this);
			this.lane.lastVehicle(this);
			this.leading(null);
			this.trailing(null);
		}
		this.lane.addVehicles();
		
		this.updateCoordMap(this.lane);
	}

	/*
	 * BL: Following we implement mandatory lane changing. The input parameter
	 * is the temporary lane.
	 */
	public void mandatoryLC(Lane plane) {
		Vehicle leadVehicle = this.leadVehicle(plane);
		Vehicle lagVehicle = this.lagVehicle(plane);
		/*
		 * BL: Consider the condition to change the lane as follow: If there are
		 * leading and trailing vehicle then the vehicle will check for gap
		 * acceptance as usual. However, if there is no leading or no trailing,
		 * the leadGap or the lagGap should be neglected. In the case the
		 * vehicle cannot change the lane and the distance to downstream is less
		 * than some threshold then the vehicle starts nosing.
		 */
		if (leadVehicle != null) {
			if (lagVehicle != null) {
				if (this.leadGap(leadVehicle) >= this
						.critLeadGapMLC(leadVehicle)
						&& this.lagGap(lagVehicle) >= this
								.critLagGapMLC(lagVehicle)) {
					// BL: debug the error that vehicle changes the lane
					// internally but not on the GUI
					this.changeLane(this.tempLane(), leadVehicle, lagVehicle);
					this.nosingFlag = false;
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}
			} else {
				if (this.leadGap(leadVehicle) >= this
						.critLeadGapMLC(leadVehicle)) {
					this.changeLane(this.tempLane(), leadVehicle, null);
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}

			}
		} else {
			if (lagVehicle != null) {
				if (this.lagGap(lagVehicle) >= this.critLagGapMLC(lagVehicle)) {
					this.changeLane(this.tempLane(), null, lagVehicle);
				} else if (this.distFraction() < GlobalVariables.critDisFraction) {
					this.nosingFlag = true;
				}
			} else
				this.changeLane(this.tempLane(), null, null);
		}

	}

	/*
	 * BL: if the vehicle with MLC state can't change the lane after some
	 * distance. The vehicle need to nose and yield the lag Vehicle of the
	 * target lane in order to have enough gap to change the lane This function
	 * is called only when nosingFlag is true and must be recalled until
	 * nosingFlag receive false value after the vehicle nosed, tag the lag
	 * vehicle in target lane to yielding status. This function will be called
	 * in makeAccelerationDecision
	 */
	public float nosing() //
	{
		float acc = 0;
		float lagGap;
		Lane tarLane = this.tempLane();
		Vehicle leadVehicle = this.leadVehicle(tarLane);
		Vehicle lagVehicle = this.lagVehicle(tarLane);
		/*
		 * BL: if there is a lag vehicle in the target lane, the vehicle will
		 * yield that lag vehicle however, the yielding is only true if the
		 * distance is less than some threshold
		 */
		if (lagVehicle != null) {
			lagGap = lagVehicle.distance_ - this.distance_
					- GlobalVariables.DEFAULT_VEHICLE_LENGTH;
			if (lagGap < GlobalVariables.minLag) {
				this.yieldingFlag = true;
			}

		}
		Vehicle front = this.leading();
		/*
		 * BL: 1. if there is a lead and a lag vehicle in the target lane. the
		 * vehicle will check the lead gap before decide to decelerate. if the
		 * lead gap is large, then the subject vehicle will be assigned with the
		 * accelerate rate as in car following. 2. if there is no lead vehicle
		 * in the target lane. the subject vehicle will max accelerate.
		 */
		if (leadVehicle != null) {
			if (this.leadGap(leadVehicle) < this.critLeadGapMLC(leadVehicle)) {
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
					acc = this.maxAcceleration_;
			}
		} else {
			if (front != null)
				acc = this.calcCarFollowingRate(front);
			else
				acc = this.maxAcceleration_;
		}
		this.nosingFlag = false;

		return acc;
	}

	/*
	 * BL: While moving, the vehicle will checks if the vehicles in adjection
	 * lanes are nosing to its lane or not after some distance to the downstream
	 * node If the nosing is true then it will be tagged in yielding state to
	 * slow down.
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
	 * BL: when change lane, distance need to be adjusted with the lane width.
	 */

	// Calculate critical lead gap of the vehicle with the lead vehicle in the
	// target lane.
	public double critLeadGapMLC(Vehicle leadVehicle) {
		double critLead = 0;
		double minLead_ = GlobalVariables.minLead;
		double betaLead01 = GlobalVariables.betaLeadMLC01;
		double betaLead02 = GlobalVariables.betaLeadMLC02;
		double gama = GlobalVariables.gama;
		if (leadVehicle != null)
			critLead = minLead_
					+ (betaLead01 * this.currentSpeed() + betaLead02
							* (this.currentSpeed() - leadVehicle.currentSpeed()))
					* (1 - Math.exp(-gama * this.distance()));
		if (critLead < minLead_)
			critLead = minLead_;
		return critLead;
	}

	// BL: Calculate lead gap of the vehicle with the lead vehicle in the target
	// lane.
	public double leadGap(Vehicle leadVehicle) {
		double leadGap = 0;
		if (leadVehicle != null){
			leadGap = this.distance() - leadVehicle.distance() - leadVehicle.length(); // leadGap>=-leadVehicle.length()
		}
		else{
			leadGap = this.distance(); // LZ: Nov 3, change this.distance() to 9999999
		}
		return leadGap;
	}

	// BL: Calculate critical lag gap of the vehicle with the lag vehicle in the
	// target lane.
	public double critLagGapMLC(Vehicle lagVehicle) {
		double critLag = 0;
		double betaLag01 = GlobalVariables.betaLagMLC01;
		double betaLag02 = GlobalVariables.betaLagMLC02;
		double gama = GlobalVariables.gama;
		double minLag_ = GlobalVariables.minLag;
		if (lagVehicle != null) {
			critLag = minLag_
					+ (betaLag01 * this.currentSpeed() + betaLag02
							* (this.currentSpeed() - lagVehicle.currentSpeed()))
					* (1 - Math.exp(-gama * this.distance()));
		}
		if (critLag < minLag_)
			critLag = minLag_;
		return critLag;
	}

	// BL: Calculate lag gap of the vehicle with the lag vehicle in the target
	// lane.
	public double lagGap(Vehicle lagVehicle) {
		double lagGap = 0;
		if (lagVehicle != null)
			lagGap = lagVehicle.distance() - this.distance() - this.length();
		else
			lagGap = this.lane.getLength() - this.distance() - this.length(); // Nov 3, still need to -this.length()
		return lagGap;
	}

	// BL: find the lead vehicle in target lane
	public Vehicle leadVehicle(Lane plane) {
		Vehicle leadVehicle = this.macroLeading_;
		while (leadVehicle != null && leadVehicle.lane != plane) {
			leadVehicle = leadVehicle.macroLeading_;
		}
		return leadVehicle;
	}

	// BL: find lag vehicle in target lane
	public Vehicle lagVehicle(Lane plane) {
		Vehicle lagVehicle = this.macroTrailing_;
		while (lagVehicle != null && lagVehicle.lane != plane) {
			lagVehicle = lagVehicle.macroTrailing_;
		}
		return lagVehicle;
	}

	/*
	 * BL: Following we will implement discretionary LC model At current stage,
	 * the DLC is implementing as follow: 1. If the vehicle is not close to
	 * downstream node 2. and it finds a correct lane with better traffic
	 * condition -> then it will change lane
	 */
	/*
	 * BL: if the vehicle is in correct lane then we find a better lane that is
	 * also connected to downstream line this function is called at the
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
				targetLane = curLane.betterLane(tempLane); // get the lane that
				// has best traffic condition
			} else if (leftLane != null)
				targetLane = curLane.betterLane(leftLane);
			else if (rightLane != null) {
				targetLane = curLane.betterLane(rightLane);
			}
			// if we have a target lane, then compare the speed of
			// front bumper leader in the lane with current leader
			if (targetLane != null && !targetLane.equals(curLane)) {
				Vehicle front = this
						.findFrontBumperLeaderInSameRoad(targetLane);
				if (front == null) {
					return targetLane;
					// TODO: fix the null for leading vehicle
				} else if (this.leading_ != null
						&& this.leading_.currentSpeed_ < this.desiredSpeed_
						&& this.currentSpeed_ < this.desiredSpeed_) {
					if (front.currentSpeed_ > this.currentSpeed_
							&& front.accRate_ > 0)
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
		if (this.equals(curLane.firstVehicle())) {
			return null;
		} else {
			if (leftLane != null && rightLane != null) {
				// if both left and right lanes are connected to downstream lane
				if (leftLane.isConnectToLane(this.nextLane_)
						&& rightLane.isConnectToLane(this.nextLane_)) {
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
			} else if (leftLane != null
					&& leftLane.isConnectToLane(this.nextLane_))
				targetLane = curLane.betterLane(leftLane);
			else if (rightLane != null
					&& rightLane.isConnectToLane(this.nextLane_)) {
				targetLane = curLane.betterLane(rightLane);
			}
			// if we have a target lane, then compare the speed of
			// front bumper leader in the lane with current leader
			if (targetLane != null && !targetLane.equals(curLane)) {
				Vehicle front = this
						.findFrontBumperLeaderInSameRoad(targetLane);
				if (front == null) {
					return targetLane;
				} else if (this.leading_ != null
						&& this.leading_.currentSpeed_ < this.desiredSpeed_
						&& this.currentSpeed_ < this.desiredSpeed_) {
					if (front.currentSpeed_ > this.currentSpeed_
							&& front.accRate_ > 0)
						return targetLane;
					
				}
			}
			return null;
		}

	}

	// once the vehicle finds a better lane. It changes to that lane
	// discretionarily.
	public void discretionaryLC(Lane plane) {
		Vehicle leadVehicle = this.leadVehicle(plane);
		Vehicle lagVehicle = this.lagVehicle(plane);
		double leadGap = this.leadGap(leadVehicle);
		double lagGap = this.lagGap(lagVehicle);
		double critLead = this.criticalLeadDLC(leadVehicle);
		double critLag = this.criticalLagDLC(lagVehicle);
		if (leadGap > critLead && lagGap > critLag) {
			this.changeLane(plane, leadVehicle, lagVehicle);
		}
	}

	public double criticalLeadDLC(Vehicle pv) {
		double critLead = 0;
		double minLead = GlobalVariables.minLeadDLC;
		// TODO: change betaLead01 and 02 to DLC value.
		if (pv != null) {
			critLead = minLead + GlobalVariables.betaLeadDLC01
					* this.currentSpeed_ + GlobalVariables.betaLeadDLC02
					* (this.currentSpeed_ - pv.currentSpeed_);
		}
		critLead = Math.max(minLead, critLead);
		return critLead;
	}

	public double criticalLagDLC(Vehicle pv) {
		double critLag = 0;
		double minLag = GlobalVariables.minLagDLC;
		// TODO: change betaLag01 and 02 to DLC value
		if (pv != null) {
			critLag = minLag + GlobalVariables.betaLagDLC01
					* this.currentSpeed_ + GlobalVariables.betaLagDLC02
					* (this.currentSpeed_ - pv.currentSpeed_);
		}
		critLag = Math.max(minLag, critLag);
		return critLag;
	}

	/*
	 * After appending vehicle to next road, a new nextlane being assigned. The
	 * coordinate map will be updated with the last point is the starting point
	 * of the next lane
	 */
//	public void updateCoorMapWithNewLane() {
//		Coordinate coor1, coor2;
//		if (!this.coordMap.isEmpty()) {
//			int end = this.coordMap.size() - 1;
//			coor1 = this.coordMap.get(end);
//			if (this.vehicleID_ == GlobalVariables.Global_Vehicle_ID
//					&& !GlobalVariables.Debug_On_Road) {
//				System.out
//						.println("=====I already had my coordinate map but need to update for moving through junction ");
//				System.out.println("which connects to the point: " + coor1);
//			}
//		} else {
//			
//			coor1 = ContextCreator.getVehicleGeography().getGeometry(this).getCoordinate();
//		}
//		if (this.nextLane_ != null) {
//			Lane plane = this.nextLane_;
//			Coordinate c1, c2;
//			Coordinate[] coords = laneGeography.getGeometry(plane)
//					.getCoordinates();
//			c1 = coords[0];
//			c2 = coords[coords.length - 1];
//			coor2 = getNearestCoordinate(coor1, c1, c2);
//			if (this.vehicleID_ == GlobalVariables.Global_Vehicle_ID
//					&& !GlobalVariables.Debug_On_Road) {
//				System.out.println("The adding coordinate is from the lane: "
//						+ plane.getLaneid());
//			}
//		} else
//			coor2 = this.destCoord;
//		if (!coor2.equals(coor1)) {
//			this.coordMap.add(coor2);
//			if (this.vehicleID_ == GlobalVariables.Global_Vehicle_ID
//					&& !GlobalVariables.Debug_On_Road) {
//				System.out
//						.println("@@@@@@@@@@@@@@ New coordinate added @@@@@@@@@@@@@@@@@@@");
//			}
//		} else {
//			if (this.vehicleID_ == GlobalVariables.Global_Vehicle_ID
//					&& !GlobalVariables.Debug_On_Road) {
//				System.out.println("+++++++++++No coordinate added+++++++++ ");
//			}
//		}
//		if (this.id == GlobalVariables.Global_Vehicle_ID
//				&& !GlobalVariables.Debug_On_Road) {
//			System.out
//					.println("My coordinate map after update the next lane: ");
//			int end = this.coordMap.size() - 1;
//			System.out.println("Distance to added point: "
//					+ distance(this.coordMap.get(end - 1),
//							this.coordMap.get(end)));
//		}
//	}

	/*
	 * BL: when vehicle approach junction, need new coordinate and distance
	 */
	public int appendToJunction(Lane nextlane) {
//		coordMap.clear();
//		if (this.getVehicleID() == GlobalVariables.Global_Vehicle_ID
//				&& this.nextRoad().getLinkid() == this.destRoadID) {
//			System.out.println("Vehicle " + this.getVehicleID()
//					+ " is appending to a junction of the final Road");
//		}
		if (this.atDestination()) {
//			this.removeFromLane();
//			this.removeFromMacroList();
			return 0;
		} else{ // LZ: want to change to next lane
//			if (this.getVehicleID() == GlobalVariables.Global_Vehicle_ID
//					&& this.nextRoad().getLinkid() == this.destRoadID)
//				System.out.println("Vehicle " + this.getVehicleID()
//						+ " has final next lane " + nextlane.getLaneid());
//			Coordinate[] coords = laneGeography.getGeometry(nextlane)
//					.getCoordinates();
//			Coordinate start = coords[0];
//			Coordinate end = coords[coords.length - 1];
//			Coordinate coor = this.getNearestCoordinate(lastCoordinate, start, end);
			coordMap.clear();
			coordMap.add(this.getCurrentCoord());
		}

		//this.distance_ = 0; // (float) distance(lastCoordinate, coordMap.get(0)); // LZ: End of the link
				//- lastStepMove_ / 2;
		this.onlane = false;
		//record energy consumption
		if(this.getVehicleClass() == 1){ //EV
			((ElectricVehicle) this).recLinkSnaphotForUCB();
			((ElectricVehicle) this).recSpeedVehicle();
		}else if(this.getVehicleClass() == 2){ //Bus
			((Bus) this).recLinkSnaphotForUCBBus();
		}
		
//		if (this.distance_ <= 0) {
		if (this.changeRoad() == 0){
			return 0;
		}
			
//		}
		
		return 1;
	}

	public boolean isOnLane() {
		return onlane;
	}

	public float entranceGap(Lane nextlane) {
		float gap = 0;
		if (nextlane != null) {
			Vehicle newleader = nextlane.lastVehicle();
			if (newleader != null) {
				gap = (float) nextlane.getLength() - newleader.distance_ - newleader.length();
			} else
				gap = 9999999; 
		}
		return gap;
	}

	/**
	 * @param c
	 * @param c1
	 * @param c2
	 * @return
	 */
//	private Coordinate getNearestCoordinate(Coordinate c, Coordinate c1,
//			Coordinate c2) {
//		/*
//		 * GeometryFactory geomFac = new GeometryFactory(); Geometry coordGeom =
//		 * geomFac.createPoint(c); Geometry geom1 = geomFac.createPoint(c1);
//		 * Geometry geom2 = geomFac.createPoint(c2);
//		 */
//		double dist1 = distance(c, c1);
//		double dist2 = distance(c, c2);
//
//		if (dist1 < dist2)
//			return c1;
//		else
//			return c2;
//
//	}

	private double distance(Coordinate c1, Coordinate c2) {

		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		double distance;
		try {
			distance = calculator.getOrthodromicDistance();
		} catch (AssertionError ex) {
			System.err.println("Error with finding distance");
			distance = 0.0;
		}
		return distance;
	}

	/**
	 * 
	 * @param c1 current coordinate
	 * @param c2 next coordinate
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
	 * A light weight move function based on moveVehicleByVector, and is much faster than the one using geom 
	 * @param coord
	 * @param distance
	 * @param angleInDegrees
	 */
	private void move2(Coordinate origin, Coordinate target, double distanceToTarget, double distanceTravelled){
//		this.calculator.setStartingGeographicPoint(coord.x, coord.y);
//		this.calculator.setDirection(angleInDegrees, distance);
//		Point2D p = this.calculator.getDestinationGeographicPoint();
//		if (p != null) {
//			this.setCurrentCoord(new Coordinate(p.getX(), p.getY()));
//		}
//		else{
//			logger.error("Vehicle.move2(): Cannot move " + this + "from "
//					+ coord + " by dist=" + distance + ", angle=" + angleInDegrees);
//		}
		double p = distanceTravelled/distanceToTarget;
		if(p<1){
			this.setCurrentCoord(new Coordinate((1-p)*origin.x + p*target.x , (1-p)*origin.y + + p*target.y));
		}
		else{
			System.out.println("Vehicle.move2(): Cannot move " + this + "from "
			+ origin + " by dist=" +  distanceTravelled);
		}
	}
	
//	/* *
//	 * Thread safe version of the moveByVector, replace the one in the DefaultGeography class
//	 * Creating a new Geometry point given the current location of the vehicle as well as the distance and angle.
//	 * In the end, move the vehicle to the new geometry point.
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
	
	// Xue, Juan 20191212
	public float currentAcc() {
		return accRate_;
	}

	public void setVehicleClass(int vehicleClass) {
		this.vehicleClass = vehicleClass;
	}

	public boolean onChargingRoute() {
		return false;
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
	
	public int getState(){
		return this.vehicleState;
	}
	
	public void setState(int newState){
		this.vehicleState = newState;
	}
}
