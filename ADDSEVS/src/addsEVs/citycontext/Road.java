package addsEVs.citycontext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import addsEVs.*;
import addsEVs.data.DataCollector;
import addsEVs.vehiclecontext.ElectricVehicle;
import addsEVs.vehiclecontext.Bus;
import addsEVs.vehiclecontext.Vehicle;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.essentials.RepastEssentials;
import repast.simphony.space.gis.Geography;

/* 
 * Inherit from ARESCUE simulation
 */

public class Road {
	private int id;
	private int linkid;
	private int left;
	private int right;
	private int through;
	private int tlinkid;
	private int nLanes;
	private int fromNode;
	private int toNode;
	private int curhour; // To find the current hour of the simulation
	private String identifier; // Can be used to match with shape file roads
	private String description = "";
	
	public int nVehicles_; // Number of vehicles currently in the road
	
	private int nShadowVehicles; // Potential vehicles might be loaded on the road
	private int nFutureRoutingVehicles; // Potential vehicles might performing routing on the road
	
	private double length;
	private double travelTime;
	private double freeSpeed_;
	private double freeSpeedStd_;
	
	private Road oppositeRoad;
	private ArrayList<Road> downStreamMovements;
	
	private ArrayList<Lane> lanes; // Use lanes as objects inside the road
	private ArrayList<Junction> junctions;
	private ArrayList<Double> dynamicTravelTime; // Dynamic travel time of
	
	private TreeMap<Double, ArrayList<Vehicle>> newqueue; // Use this class to control the vehicle that entering the road
	
	private Vehicle lastVehicle_;
	private Vehicle firstVehicle_;
	
	private boolean eventFlag; // Indicator whether there is an event happening on the road
	
	private double defaultFreeSpeed_; // Store default speed limit value in case of events
	
	private double totalEnergy;
	private int totalFlow;

	// Road constructor
	public Road() {
		this.id = ContextCreator.generateAgentID();
		this.description = "road " + id;
		this.junctions = new ArrayList<Junction>();
		this.lanes = new ArrayList<Lane>();
		this.nVehicles_ = 0;
		
		this.freeSpeed_ = GlobalVariables.FREE_SPEED;
		this.freeSpeedStd_ = 0;
		this.downStreamMovements = new ArrayList<Road>();
		this.oppositeRoad = null;
		this.newqueue = new TreeMap<Double, ArrayList<Vehicle>>(); 
		this.identifier = " ";
		this.curhour = -1;
		this.travelTime = (float) this.length / this.freeSpeed_;
		
		// For adaptive network partitioning
		this.nShadowVehicles = 0;
		this.nFutureRoutingVehicles = 0;
		this.eventFlag = false;
		
		// Set default value
		this.defaultFreeSpeed_ = this.freeSpeed_;	
		this.totalEnergy = 0;
		this.totalFlow = 0;
	}
	
	// Set the defaultFreeSpeed_
	public void setDefaultFreeSpeed() {
		this.defaultFreeSpeed_ = this.freeSpeed_;
	}
	
	// Get the defaultFreeSpeed_
	public double getDefaultFreeSpeed() {
		return this.defaultFreeSpeed_;
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
	public void step() {		
		Vehicle v;
		int tickcount = (int) RepastEssentials.GetTickCount();
		if(tickcount % GlobalVariables.FREQ_RECORD_LINK_SNAPSHOT_FORVIZ == 0){
			this.recRoadSnaphot(); // Record vehicle location here!
		}
		try {
			/* Vehicle departure */
			while (this.newqueue.size() > 0) {
				v = this.newqueueHead(); // Change to use the TreeMap
				if (v.closeToRoad(this) == 1 && tickcount >= v.getDepTime()) {
					// check whether the origin is the destination
					if (v.getOriginID() == v.getDestID()) {
						this.removeVehicleFromNewQueue(v); //Remove vehicle from the waiting vehicle queue
						v.setReachDest();
					}
					else {
						if (v.enterNetwork(this)) {
							v.advanceInMacroList(); //Vehicle entering the network
						}
						else{
							break; // Road is full, jump out the loop
						}
					}
					
				} 
				else {
					// Iterate all element in the TreeMap
					@SuppressWarnings("rawtypes")
					Set keys = (Set) this.newqueue.keySet();
					for (@SuppressWarnings("rawtypes") 
					Iterator i = (Iterator) keys.iterator(); i.hasNext();) {
						Double key = (Double) i.next();
						ArrayList<Vehicle> temList = this.newqueue.get(key);
						for (Vehicle pv : temList) {
							if (tickcount >= pv.getDepTime()) {
								pv.primitiveMove();
							}
							else{
								break;
							}
						}
					}
					break;
				}
			}
            
			
			/* Vehicle movement */
			Vehicle pv = this.firstVehicle();
			if (pv != null && pv.leading() != null) { 
				pv.leading(null);
			}

			while (pv != null) {
				if (tickcount <= pv.getLastMoveTick()) {
					break; // Reached the last vehicle
				}
				pv.updateLastMoveTick(tickcount);
				if (!pv.calcState()) {
					ContextCreator.logger.error("Link "+this.linkid+" vehicle list is corrupted");
					break;
				}
				if(tickcount % GlobalVariables.FREQ_RECORD_VEH_SNAPSHOT_FORVIZ == 0){
					pv.recVehSnaphotForVisInterp(); // Note vehicle can be killed after calling pv.travel, so we record vehicle location here!
				}
				pv.travel();
				pv.updateBatteryLevel(); // Update the energy for each move
				pv = pv.macroTrailing();
			}
		} catch (Exception e) {
			ContextCreator.logger.error("Road " + this.linkid
					+ " had an error while moving vehicles");
			e.printStackTrace();
			RunEnvironment.getInstance().pauseRun();
		}

	}
	
	@Override
	public String toString() {
		return "Agent id: " + id + " description: " + description;
	}

	public void setLinkid(int linkid) {
		this.linkid = linkid;
	}

	public int getLinkid() {
		return linkid;
	}

	public void setTlinkid(int tlinkid) {
		this.tlinkid = tlinkid;

	}

	public int getTlinkid() {
		return tlinkid;
	}

	public int getID() {
		return id;
	}

	public void setOppositeRoad(Road r) {
		this.oppositeRoad = r;
	}

	public Road getOppositeRoad() {
		return this.oppositeRoad;
	}
	
	public void setFreeflowsp(double freeflowsp) {
		this.freeSpeed_ = freeflowsp * 0.44704;
	}	


	public void sortLanes() {
		Collections.sort(this.lanes, new LaneComparator());
	}

	/**
	 * Get a unique identifier for this Road. Not the same as ID (which is an
	 * auto-generated field common to every agent used in the model), this
	 * identifier is used to link road features in a GIS with Edges added to the
	 * RoadNetwork (a repast Network Projection).
	 * 
	 * @return the identifier for this Road.
	 * @throws NoIdentifierException
	 *             if the identifier has not been set correctly. This might
	 *             occur if the roads are not initialized correctly (e.g. there
	 *             is no attribute called 'identifier' present in the shape-file
	 *             used to create this Road).
	 */
	public String getIdentifier() {
		if (identifier == "" || identifier == null) {
			ContextCreator.logger.error("Road: error, the identifier field for this road has not been initialised."
							+ "\n\tIf reading in a shapefile please make sure there is a string column called 'identifier' which is"
							+ " unique to each feature");
		}
		return identifier;
	}

	// Set left
	public void setLeft(int left) {
		this.left = left;
	}

	public int getLeft() {
		return left;
	}

	public void setThrough(int through) {
		this.through = through;
	}

	public int getThrough() {
		return through;
	}

	public void setRight(int right) {
		this.right = right;
	}

	public int getRight() {
		return right;
	}

	public void setFn(int node) {
		this.fromNode = node;
	}

	public int getFn() {
		return fromNode;
	}

	public void setTn(int node) {
		this.toNode = node;
	}

	public int getTn() {
		return toNode;
	}

	public void setLength(double length) {
		this.length = length;
	}

	public double getLength() {
		return length;
	}

	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}

	/**
	 * Used to tell this Road who it's Junctions (end-points) are.
	 * 
	 * @param j the Junction at either end of this Road.
	 */
	public void addJunction(Junction j) {
		if (this.junctions.size() == 2) {
			ContextCreator.logger.error("Road: Error: this Road object already has two Junctions.");
		}
		this.junctions.add(j);
	}

	public ArrayList<Junction> getJunctions() {
		if (this.junctions.size() != 2) {
			ContextCreator.logger.error("Road: Error: This Road does not have two Junctions");
		}
		return this.junctions;
	}

	public void addDownStreamMovement(Road dsRoad) {
		this.downStreamMovements.add(dsRoad);
	}

	public ArrayList<Road> getConnectedRoads() {
		return this.downStreamMovements;
	}

	public void setNumberOfVehicles(int nVeh){
		this.nVehicles_ = nVeh;
		if(this.nVehicles_<0) {
			this.nVehicles_=0;
		}
	}
	
	public void changeNumberOfVehicles(int nVeh){
		this.nVehicles_ += nVeh;
		if(this.nVehicles_<0) {
			this.nVehicles_=0;
		}
	}
	
	public void firstVehicle(Vehicle v) {
		if (v != null)
			this.firstVehicle_ = v;
		else
			this.firstVehicle_ = null;
	}

	public void lastVehicle(Vehicle v) {
		if (v != null)
			this.lastVehicle_ = v;
		else
			this.lastVehicle_ = null;
	}

	public Vehicle firstVehicle() {
		return firstVehicle_;
	}

	public Vehicle lastVehicle() {
		return lastVehicle_;
	}
	
	/* Number of vehicles on the road */
	public int getVehicleNum() {
		return this.nVehicles_;
	}
	
	/* For adaptive network partitioning */
	public int getShadowVehicleNum() {
		return this.nShadowVehicles;
	}
	
	public void incrementShadowVehicleNum() {
		this.nShadowVehicles++;
	}
	
	public void resetShadowVehicleNum() {
		this.nShadowVehicles = 0;
	}
	
	public void decreaseShadowVehicleNum() {
		this.nShadowVehicles--;
		if (this.nShadowVehicles < 0)
			this.nShadowVehicles = 0;
	}
	
	public int getFutureRoutingVehNum() {
		return this.nFutureRoutingVehicles;
	}
	
	public void incrementFutureRoutingVehNum() {
		this.nFutureRoutingVehicles++;
	}
	
	public void resetFutureRountingVehNum() {
		this.nFutureRoutingVehicles = 0;
	}
	
	public void decreaseFutureRoutingVehNum() {
		this.nFutureRoutingVehicles--;
		if (this.nFutureRoutingVehicles < 0)
			this.nFutureRoutingVehicles = 0;
	}
	

	//This add queue using TreeMap structure
	public void addVehicleToNewQueue(Vehicle v) {
		double departuretime_ = v.getDepTime();
		if (!this.newqueue.containsKey(departuretime_)) {
			ArrayList<Vehicle> temporalList = new ArrayList<Vehicle>();
			temporalList.add(v);
			this.newqueue.put(departuretime_, temporalList);
		} else {
			ArrayList<Vehicle> temporalList = new ArrayList<Vehicle>();
			temporalList = this.newqueue.get(departuretime_);
			temporalList.add(v);
			this.newqueue.put(departuretime_, temporalList);
		}
	}


	/*
	 * RemoveVehicleFromNewQueue, will remove vehicle v from the TreeMap by
	 * looking at the departuretime_ of the vehicle if there are more than one
	 * vehicle with the same departuretime_, it will remove the vehicle match
	 * with id of v.
	 */
	public void removeVehicleFromNewQueue(Vehicle v) { 
		double departuretime_ = v.getDepTime();
		//System.out.println("Trying to remove vehicle:"+v.getDepTime());
		ArrayList<Vehicle> temporalList = this.newqueue.get(departuretime_);
		if (temporalList.size() > 1) {
			this.newqueue.get(departuretime_).remove(v);
		} else {
			this.newqueue.remove(departuretime_);
		}
	}

	public Vehicle newqueueHead() {
		if (this.newqueue.size() > 0) {
			double firstDeparture_;
			firstDeparture_ = this.newqueue.firstKey();
			return this.newqueue.get(firstDeparture_).get(0);
		}
		return null;
	}

	public double length() {
		return this.length;
	}

	public double getFreeSpeed() {
		return this.freeSpeed_;
	}
	
	public double getRandomFreeSpeed() {
		return Math.max(this.freeSpeed_ + GlobalVariables.RandomGenerator.nextGaussian()*this.freeSpeedStd_, 1*0.44704); // at least 1 mph
	}

	public float calcSpeed() {
		if (nVehicles_ <= 0)
			return (float) freeSpeed_;
		float sum = 0.0f;
		Vehicle pv = this.firstVehicle();
		while (pv != null) {
			sum += pv.currentSpeed();
			pv = pv.macroTrailing();
		}
		return sum / nVehicles_;
	}

	public void calcLength() {
		Geography<Road> roadGeography = ContextCreator.getRoadGeography();
		Geometry roadGeom = roadGeography.getGeometry(this);
		this.length = (float) ContextCreator.convertToMeters(roadGeom
				.getLength());
		this.initDynamicTravelTime();
		System.out
				.println("Road " + this.linkid + " has length " + this.length);
	}
	
	/**
	 * This function set the current travel time of the road based on the
	 * average speed of the road.
	 * 
	 * @author Zhan & Hemant
	 */
	public void setTravelTime() {
		float averageSpeed = 0;
		if (this.nVehicles_ == 0) {
			averageSpeed = (float) this.freeSpeed_;
		} else {
			Vehicle pv = this.firstVehicle();
			while (pv != null) {
				if (pv.currentSpeed() < 0) {
					ContextCreator.logger.error("Vehicle " + pv.getId()
							+ " has error speed of " + pv.currentSpeed());
				} else
					averageSpeed = +pv.currentSpeed();
				pv = pv.macroTrailing();
			}
			if (averageSpeed < 0.001f) {
				averageSpeed = 0.001f;
			} else {
				if (this.nVehicles_ < 0) {
					ContextCreator.logger.error("Road " + this.getLinkid() + " has "
							+ this.nVehicles_ + " vehicles");
					averageSpeed = (float) this.freeSpeed_;
				} else
					averageSpeed = averageSpeed / this.nVehicles_;
			}
		}
		// outAverageSpeed: For output travel times
		this.travelTime = (float) this.length / averageSpeed;
	}
	

	/**
	 * This function will initialize the dynamic travel time vector of the road.
	 * 
	 * @author Samiul Hasan
	 */
	public void initDynamicTravelTime() {
		int i = 1;
		int intervalLength = GlobalVariables.SIMULATION_INTERVAL_SIZE;
		int end = GlobalVariables.SIMULATION_STOP_TIME;
		while (i < end) {
			this.dynamicTravelTime.add(this.length / this.freeSpeed_);
			i = i + intervalLength;
		}
	}

	public void setDynamicTravelTime(double tick, double time) {
		int intervalLength = GlobalVariables.SIMULATION_INTERVAL_SIZE;
		int interval = (int) tick / intervalLength;
		this.dynamicTravelTime.add(interval, time);
	}

	public double getDynamicTravelTime(double tick) {
		int intervalLength = GlobalVariables.SIMULATION_INTERVAL_SIZE;
		int interval = (int) tick / intervalLength;
		return this.dynamicTravelTime.get(interval);
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
		this.nLanes++;
	}

	public ArrayList<Lane> getLanes() {
		return this.lanes;
	}

	public Lane leftLane() {
		return this.lanes.get(0);
	}

	public Lane rightLane() {
		int size = this.lanes.size();
		return this.lanes.get(size - 1);
	}

	public double atan3(double x1, double y1, double x0, double y0) {
		double alpha = 0;
		alpha = Math.atan2(y1 - y0, x1 - x0);
		if (alpha < 0) {
			alpha += 2 * Math.PI;
		}
		return alpha;
	}

	public int getnLanes() {
		return this.nLanes;
	}

	public Lane firstLane() {
		Lane firstLane = null;
		int rightmost = this.getLanes().size() - 1;
		firstLane = this.getLane(rightmost);
		return firstLane;
	}

	public void printRoadInfo() {
		ContextCreator.logger.info("Road: " + this.getIdentifier()
				+ " has lanes from left to right as follow: ");
		for (int i = 0; i < this.lanes.size(); i++) {
			ContextCreator.logger.info(this.lanes.get(i).getLaneid()
					+ " with Repast ID: " + this.lanes.get(i).getID());
		}
	}

	public void printRoadCoordinate() {
		Coordinate start, end;
		start = this.getJunctions().get(0).getCoordinate();
		end = this.getJunctions().get(1).getCoordinate();
		ContextCreator.logger.info("Coordinate of road: " + this.getLinkid());
		ContextCreator.logger.info("Starting point: " + start);
		ContextCreator.logger.info("Ending point: " + end);
	}
	
	/* Wenbo: update background traffic through speed file. if road event flag is true, just pass to default free speed, else, update link free flow speed */
	public void updateFreeFlowSpeed() {
		// Get current tick
		int tickcount = (int) RepastEssentials.GetTickCount();
		int hour = (int) Math.floor(tickcount / GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL);
		hour = hour % GlobalVariables.HOUR_OF_SPEED;
		// each hour set events
		if (this.curhour < hour) {
			double value = Math.max(1, ContextCreator.getBackgroundTraffic().get(this.linkid).get(hour) * 0.44704); // convert
																													// from
																													// mile
																													// per
																													// hour
																													// to
																													// meter
																													// per
																													// second
			double value2 = Math.max(1, ContextCreator.getBackgroundTrafficStd().get(this.linkid).get(hour) * 0.44704);
			if (this.checkEventFlag()) {
				this.setDefaultFreeSpeed();
			} else {
				this.freeSpeed_ = value;
				this.freeSpeedStd_ = value2;
			}
		}
		this.curhour = hour;
	}

	
	/* Modify the free flow speed based on the events */
	public void updateFreeFlowSpeed_event(double newFFSpd) {
		this.freeSpeed_ = newFFSpd* 0.44704; //HG: convert from Miles per hour to meter per second
	}
	
	public void printTick(){
		int tickcount = (int) RepastEssentials.GetTickCount();
		ContextCreator.logger.info("Tick: "+tickcount);
	}

	public void recordEnergyConsumption(Vehicle v) {
		this.totalFlow += 1;
		if(v.getVehicleClass() == 1){ //EV
			ElectricVehicle ev = (ElectricVehicle) v;
			this.totalEnergy += ev.getLinkConsume();
			ev.resetLinkConsume();
		}
		else if(v.getVehicleClass() == 2){
			Bus bv = (Bus) v;
			this.totalEnergy += bv.getLinkConsume();
		}
	}
	
	public double getTotalEnergy(){
		return totalEnergy;
	}
	
	public int getTotalFlow(){
		return totalFlow;
	}
	
	public void recRoadSnaphot() {
		try {
			DataCollector.getInstance().recordRoadSnapshot(this);
		} catch (Throwable t) {
			// could not log the vehicle's new position in data buffer!
			DataCollector.printDebug("ERR" + t.getMessage());
		}
	}
	
}
