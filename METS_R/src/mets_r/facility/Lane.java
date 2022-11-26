package mets_r.facility;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.Vehicle;

/**
 * 
 * Inherit from A-RESCUE
 * 
 * @author Samiul Hasan and Binh Luong
 *         
 **/

public class Lane {
	private int id; // An auto-generated id from ContextCreater
	private Random rand;
	
	private int laneid; // From shape file
	private int link; // From shape file
	private int left;
	private int through;
	private int right;
	private double length;

	private Road road_; // The Road for which this lane belongs to
	private AtomicInteger nVehicles_; // Number of vehicle in the lane

	/*
	 * To move to the next road of the path, a vehicle may need to make necessary
	 * lane changes. The variable nChanges_ indicates the number of lane changes
	 * required before getting on the downstream link. nChanges_ is an array, and
	 * each element corresponds to one out going arc at the downstream node. Its
	 * value is defined as followings:
	 * 
	 * positive = change to left zero = no need to change negative = change to right
	 */

	private Vehicle firstVehicle_; // The first vehicle on a lane
	private Vehicle lastVehicle_; // The last vehicle vehicle on a lane
	private float speed_;
	private float maxSpeed_;
	private ArrayList<Lane> upLanes_;// Upstream lanes that connect to this
	private ArrayList<Lane> dnLanes_;// Down stream lanes that connect to
	private int index;
	private AtomicInteger lastEnterTick = new AtomicInteger(-1); // Store the latest enter time of vehicles

	public Lane() {
		this.id = ContextCreator.generateAgentID();
		this.rand = new Random(GlobalVariables.RandomGenerator.nextInt());
		this.nVehicles_ = new AtomicInteger(0);
		this.lastVehicle_ = null;
		this.upLanes_ = new ArrayList<Lane>();
		this.dnLanes_ = new ArrayList<Lane>();
	}

	public int getAndSetLastEnterTick(int current_tick) {
		return this.lastEnterTick.getAndSet(current_tick);
	}

	public int getLaneid() {
		return laneid;
	}

	public void setLaneid(int laneid) {
		this.laneid = laneid;
	}

	public void setLength(double length) {
		this.length = length;
	}

	public double getLength() {
		return length;
	}

	/*
	 * public int getId() { return Id; } public void setId(long Id) { this.Id = Id;
	 * }
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

	public Road getRoad() {
		return this.road_;
	}

	public int getID() {
		return this.id;
	}

	public int getIndex() {
		return this.index;
	}

	public void setIndex() {
		this.index = this.road_.getLaneIndex(this);
	}

	/*
	 * -------------------------------------------------------------------- Returns
	 * the last vehicle in the downstream lanes. The vehicle closest to the upstream
	 * end is returned.
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
			if (ul.getRoad().equals(pr)) {
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
		return nVehicles_.get();
	}

	// This add only the number of vehicle to lane, while addVehicle in road and a
	// vehicle to arrayList.
	public void addOneVehicle() {
		nVehicles_.addAndGet(1);
	}

	public void removeOneVehicle() {
		this.nVehicles_.addAndGet(-1);
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
			if (this.nVehicles_.get() < plane.nVehicles_.get()) {
				return this;
			} else if (this.nVehicles_.get() > plane.nVehicles_.get()) {
				return plane;
			} else {
				if (rand.nextDouble() > 0.5)
					return this;
				else
					return plane;
			}
		} else {
			return this;
		}
	}

}
