package mets_r.data;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.mobility.ElectricTaxi;

/**
 * This class is the simple data object for capturing the state of an
 * electric vehicle at a particular point in time.
 * 
 * This object is immutable and composed of simple data variables. It should be
 * trivial to serialize and reconstruct this object. All values are available
 * directly as public members and through "get" methods.
 * 
 * @author Zengxiang Lei
 **/

public class EVSnapshot {
	final public int id;
	final public double prev_x; // The X-axis (longitude) position of the vehicle in the previous epoch 
	final public double prev_y; // The Y-axis (latitude) position of the vehicle in the previous epoch 
	final public double x;
	final public double y;
	final public double speed;
	final public int origin_id;
	final public int dest_id;
	final public double batteryLevel;
	final public double totalConsumption;
	final public int roadID;
	final public int served_pass;

	public EVSnapshot(ElectricTaxi vehicle, Coordinate coordinate) throws Throwable {
		this(vehicle.getVehicleID(), vehicle.getpreviousEpochCoord().x, vehicle.getpreviousEpochCoord().y, coordinate.x,
				coordinate.y, vehicle.currentSpeed(), vehicle.getOriginID(), vehicle.getDestID(),
				vehicle.nearlyArrived(), vehicle.getVehicleClass(), vehicle.getBatteryLevel(),
				vehicle.getTotalConsume(), vehicle.getRoad().getID(), vehicle.served_pass);
	}

	public EVSnapshot(int id, double prev_x, double prev_y, double x, double y, double speed, int origin_id, int dest_id,
			int nearlyArrived, int vehicleClass, double batteryLevel, double energyConsumption, int roadID,
			int served_pass) throws Throwable {
		if (id < 0) {
			throw new Exception("Vehicle ID cannot be negative.");
		}
		if (Double.isNaN(x) || Double.isInfinite(x)) {
			throw new NumberFormatException("X-axis value is invalid.");
		}
		if (Double.isNaN(y) || Double.isInfinite(y)) {
			throw new NumberFormatException("Y-axis value is invalid.");
		}
		if (Double.isNaN(speed) || Double.isInfinite(speed)) {
			throw new NumberFormatException("Speed value is invalid.");
		}

		// Store the values in the object
		this.id = id;
		this.prev_x =  prev_x;
		this.prev_y =  prev_y;
		this.x =  x;
		this.y =  y;
		this.speed = speed;
		this.origin_id = origin_id;
		this.dest_id = dest_id;
		this.batteryLevel =  batteryLevel;
		this.totalConsumption =  energyConsumption;
		this.roadID = roadID;
		this.served_pass = served_pass;
	}

	public int getId() {
		return this.id;
	}

	public double getPrevX() {
		return this.prev_x;
	}

	public double getPrevY() {
		return this.prev_y;
	}

	public double getX() {
		return this.x;
	}

	public double getY() {
		return this.y;
	}

	public double getSpeed() {
		return this.speed;
	}

	public int getOriginID() {
		return this.origin_id;
	}

	public int getDestID() {
		return this.dest_id;
	}

	public double getTotalEnergyConsumption() {
		return this.totalConsumption;
	}

	public double getBatteryLevel() {
		return this.batteryLevel;
	}

	public int getRoadID() {
		return this.roadID;
	}

	public int getServedPass() {
		return this.served_pass;
	}
}
