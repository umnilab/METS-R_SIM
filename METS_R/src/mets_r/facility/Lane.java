package mets_r.facility;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.Vehicle;

/**
 * 
 * Inherit from A-RESCUE with modification
 * 
 * @author Samiul Hasan, Binh Luong, and Zengxiang Lei
 * 
 **/

public class Lane {
	/* Private variables */
	private int ID; // From shape file
	private int index;
	private ArrayList<Coordinate> coords;
	private double length;
	
	// Connection with other facilities
	private ArrayList<Integer> upStreamLanes;// Upstream lanes that connect to this
	private ArrayList<Integer> downStreamLanes;// Down stream lanes that connect to
	private int road; // ID of the road who contains this lane
	
	// For vehicle movement
	private AtomicInteger nVehicles_; // Number of vehicle in the lane
	private Vehicle firstVehicle_; // The first vehicle on a lane
	private Vehicle lastVehicle_; // The last vehicle vehicle on a lane
	private float speed_;
	private float maxSpeed_;
	private AtomicInteger lastEnterTick; // Store the latest enter time of vehicles
	private Random rand; // Random seed for lane changing

	public Lane(int id) {
		this.ID = id;
		this.rand = new Random(GlobalVariables.RandomGenerator.nextInt());
		this.nVehicles_ = new AtomicInteger(0);
		this.lastVehicle_ = null;
		this.upStreamLanes = new ArrayList<Integer>();
		this.downStreamLanes = new ArrayList<Integer>();
		this.lastEnterTick = new AtomicInteger(-1);
	}

	public int getAndSetLastEnterTick(int current_tick) {
		return this.lastEnterTick.getAndSet(current_tick);
	}

	public int getID() {
		return ID;
	}
	

	public Coordinate getStartCoord() {
		return this.coords.get(0);
	}
	
	public Coordinate getEndCoord() {
		return this.coords.get(this.coords.size()-1);
	}
	
	public void setCoords(ArrayList<Coordinate> coords) {
		this.coords = coords;
	}

	public void setLength(double length) {
		this.length = length;
	}

	public double getLength() {
		return length;
	}
	
	public int getRoad() {
		return road;
	}

	public void setRoad(int roadID) {
		this.road = roadID;
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

	public int getIndex() {
		return this.index;
	}

	public void setIndex() {
		this.index = ContextCreator.getRoadContext().get(road).getLaneIndex(this);
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
		for (i = 0; i < downStreamLanes.size(); i++) {
			dlane = ContextCreator.getLaneContext().get(downStreamLanes.get(i));
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
	
	public ArrayList<Integer> getDownStreamLanes() {
		return this.downStreamLanes;
	}
	
	public void addDownStreamLane(int l) {
		if (l > 0) {
			if (!this.downStreamLanes.contains(l))
				this.downStreamLanes.add(l);
			else
				ContextCreator.logger.error("Cannot register the down stream lane since it is already added");
		}
	}
	
	public ArrayList<Integer> getUpStreamLanes() {
		return upStreamLanes;
	}
	
	public void addUpStreamLane(int l) {
		if(!this.upStreamLanes.contains(l))
			this.upStreamLanes.add(l);
		else
			ContextCreator.logger.error("Cannot register the up stream lane since it is already added");
	}

	public Lane getUpStreamLaneInRoad(Road pr) {
		Lane connectLane = null;
		for (int lane : this.getUpStreamLanes()) {
			if (ContextCreator.getLaneContext().get(lane).getRoad() == pr.getID()) {
				connectLane = ContextCreator.getLaneContext().get(lane);
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
			for (int ul : pl.getUpStreamLanes()) {
				if (ul == this.getID())
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
