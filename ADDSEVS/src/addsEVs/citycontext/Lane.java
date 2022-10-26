package addsEVs.citycontext;

import java.util.ArrayList;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.vehiclecontext.Vehicle;

/**
 * @author Samiul Hasan and Binh Luong 
 * 
 * Inherit from ARESCUE simulation
 **/

@SuppressWarnings("unused")
public class Lane {
	private int id; // An auto-generated id from ContextCreater
	private int laneid; // From shape file
	private int link; // From shape file
	private int left;
	private int through;
	private int right;
	private float length;

	private Road road_; // The Road for which this lane belongs to
	private int nVehicles_; // Number of vehicle in the lane

	/*
	 * To move to the next road of the path, a vehicle may need to make
	 * necessary lane changes. The variable nChanges_ indicates the number of
	 * lane changes required before getting on the downstream link. nChanges_ is
	 * an array, and each element corresponds to one out going arc at the
	 * downstream node. Its value is defined as followings:
	 * 
	 * positive = change to left zero = no need to change negative = change to
	 * right
	 */

	private Vehicle firstVehicle_; // The first vehicle on a lane
	private Vehicle lastVehicle_; // The last vehicle vehicle on a lane
	private double accumulatedDensity_;
	private double accumulatedSpeed_;
	private float speed_;
	private float maxSpeed_;
	private int upConnections_; // Number of upstream connections of lane
	private int downConnections_; // Number of downstream connection of lane
	private ArrayList<Lane> upLanes_;// Upstream lanes that connect to this
	private ArrayList<Lane> dnLanes_;// Down stream lanes that connect to
	private int index;
	private int lastEnterTick = -1; // Store the latest enter time of vehicles

	public Lane() {
		this.id = ContextCreator.generateAgentID();
		this.nVehicles_ = 0;
		this.lastVehicle_ = null;
		this.upLanes_ = new ArrayList<Lane>();
		this.dnLanes_ = new ArrayList<Lane>();
	}

	public void updateLastEnterTick(int current_tick){
		this.lastEnterTick = current_tick;
	}
	
	public int getLastEnterTick(){
		return this.lastEnterTick;
	}
	
	public int getLaneid() {
		return laneid;
	}
	
	public void setLaneid(int laneid) {
		this.laneid = laneid;
	}

	public void setLength(float length) {
		this.length = length;
	}

	public float getLength() {
		return length;
	}
	
	/*
	 * public int getId() { return Id; }
	 * public void setId(long Id) { this.Id = Id; }
	 */
	public int getLink() {
		return link;
	}

	public void setLink(int link) {
		this.link = link;
	}

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

	public void setRoad(Road road) {
		this.road_ = road;
		road.addLane(this);
	}

	public float speed() {
		return this.speed_;
	}

	// Reset the accumulated speed and accumulated density to recalculate in the next Sim step
	public void resetStatistics() {
		accumulatedSpeed_ = 0;
		accumulatedDensity_ = 0;
	}

	public void firstVehicle(Vehicle v) {
		if (v != null) {
			this.firstVehicle_ = v;
			v.leading(null);
		} else
			this.firstVehicle_ = null;
	}

	public void lastVehicle(Vehicle v) {
		if (v != null) {
			this.lastVehicle_ = v;
			v.trailing(null);
		} else
			this.lastVehicle_ = null;

	}

	public float maxSpeed() {
		return maxSpeed_;
	}

	public Vehicle firstVehicle() {
		return firstVehicle_;
	}

	public Vehicle lastVehicle() {
		return lastVehicle_;
	}

	// Calculate max speed of the lane
	public float calcMaxSpeed() {
		return this.maxSpeed_;
	}

	public Road road_() {
		return this.road_;
	}

	public int getID() {
		return this.id;
	}
	public int getIndex() {
		return this.index;
	}
	public void setIndex(){
		this.index = this.road_.getLaneIndex(this);
	}

	/*
	 * -------------------------------------------------------------------- 
	 * Returns the last vehicle in the downstream lanes. The vehicle closest to
	 * the upstream end is returned.
	 * --------------------------------------------------------------------
	 */
	public Vehicle lastInDnLane() {
		Vehicle last = null;
		Vehicle pv;
		double mindis = GlobalVariables.FLT_INF;
		double dis;
		Lane dlane;
		int i;
		for (i = 0; i < dnLanes_.size(); i++) {
			dlane = dnLanes_.get(i);
			pv = dlane.lastVehicle_;
			if (pv != null) {
				dis = dlane.getLength() - (pv.getDistance() + pv.length());
				if (dis < mindis) {
					mindis = dis;
					last = pv;
				}
			}
		}
		return (last);
	}

	public void upConnection(int n) {
		this.upConnections_ = n;
	}

	public void downConnection(int n) {
		this.downConnections_ = n;
	}

	
	// Get all the downstream lanes that connect to this lane.
	public ArrayList<Lane> getDnLanes() {
		return this.dnLanes_;
	}

	public ArrayList<Lane> getUpLanes() {
		return this.upLanes_;
	}

	public void addDnLane(Lane l) {
		this.dnLanes_.add(l);
	}

	public Lane getDnLane(int i) {
		return dnLanes_.get(i);
	}

	public void addUpLane(Lane l) {
		this.upLanes_.add(l);
	}

	public Lane getUpLane(int i) {
		return upLanes_.get(i);
	}

	public Lane getUpStreamConnection(Road pr) {
		Lane connectLane = null;
		for (Lane ul : this.getUpLanes()) {
			if (ul.road_().equals(pr)) {
				connectLane = ul;
				break;
			}
		}
		return connectLane;
	}

	public int index() {
		return this.index;
	}

	// Return number of vehicles
	public int nVehicles() {
		return nVehicles_;
	}
	
	// For diagnosis in the final table
	public int getNumVehicles() {
		return nVehicles_;
	}

	// This add only the number of vehicle to lane, while addVehicle in road and a vehicle to arrayList.
	public void addOneVehicle() {
		nVehicles_++;
	}

	public void removeOneVehicle() {
		this.nVehicles_--;
	}

	public void printLaneConnection(){
		System.out.println("Road: "+ this.road_.getLinkid()+ " lane " +this.laneid +" has downstream connections: ");
		for (int i=0;i<this.dnLanes_.size();i++) {
			System.out.println("To Lane: " +this.dnLanes_.get(i).laneid+" of road: "+this.dnLanes_.get(i).road_.getLinkid());
		}
		System.out.println("and with upstream connection: ");
		for (int i=0;i<this.upLanes_.size();i++) {
			System.out.println("To Lane: " +this.upLanes_.get(i).laneid+" of road: "+this.upLanes_.get(i).road_.getLinkid());
		}
	}
	// Following are functions dedicated for discretionary lane changing
	public boolean isConnectToLane(Lane pl) {
		boolean connectFlag = false;
		if (pl != null) {
			for (Lane ul : pl.getUpLanes()) {
				if (ul.equals(this))
					connectFlag = true;
			}
		}
		return connectFlag;
	}
	
	// Find the lane with less vehicles
	public Lane betterLane(Lane plane) {
		if (this != null && plane != null) {
			if (this.nVehicles_ < plane.nVehicles_) {
				return this;
			} else if (this.nVehicles_ > plane.nVehicles_) {
				return plane;
			} else {
				double randomnumber = GlobalVariables.RandomGenerator
						.nextDouble();
				if (randomnumber > 0.5)
					return this;
				else
					return plane;
			}
		} else if (this != null)
			return this;
		else if (plane != null)
			return plane;
		return null;
	}
	
}
