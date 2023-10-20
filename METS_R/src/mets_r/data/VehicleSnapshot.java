package mets_r.data;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.mobility.Vehicle;

/**
 * Inherent from A-RESCUE
 * 
 * This class is the simple data object for capturing the state of a
 * vehicle at a particular point in time.
 * 
 * This object is immutable and composed of simple data variables. It should be
 * trivial to serialize and reconstruct this object. All values are available
 * directly as public members and through "get" methods.
 * 
 * @author Christopher Thompson
 */
public class VehicleSnapshot {
	final public int id;
	final public double prev_x; //The X-axis (longitude) position of the vehicle in the previous epoch
	final public double prev_y; //The Y-axis (latitude) position of the vehicle in the previous epoch
	final public double x;
	final public double y;
	final public double speed;
	final public double originX;
	final public double originY;
	final public double destX;
	final public double destY;
	final public int vehicleClass;
	final public int roadID;

	public VehicleSnapshot(Vehicle vehicle, Coordinate coordinate) throws Throwable {
		this(vehicle.getID(), vehicle.getpreviousEpochCoord().x, vehicle.getpreviousEpochCoord().y, coordinate.x,
				coordinate.y, vehicle.currentSpeed(), vehicle.getOriginCoord().x, vehicle.getOriginCoord().y,
				vehicle.getDestCoord().x, vehicle.getDestCoord().y, vehicle.getVehicleClass(),
				vehicle.getRoad().getID());
	}

	public VehicleSnapshot(int id, double prev_x, double prev_y, double x, double y, double speed, double originX,
			double originY, double destX, double destY, int vehicleClass, int roadID)
			throws Throwable {
		if (roadID < 0) throw new Exception("Road ID cannot be negative.");
		if (Double.isNaN(originX) || Double.isInfinite(originX)) throw new NumberFormatException("Original X-axis value is invalid.");
		if (Double.isNaN(originY) || Double.isInfinite(originY)) throw new NumberFormatException("Original Y-axis value is invalid.");
		if (Double.isNaN(destX) || Double.isInfinite(destX)) throw new NumberFormatException("Dest X-axis value is invalid.");
		if (Double.isNaN(destY) || Double.isInfinite(destY)) throw new NumberFormatException("Dest Y-axis value is invalid.");
		if (id < 0) throw new Exception("Vehicle ID cannot be negative.");
		if (Double.isNaN(x) || Double.isInfinite(x)) throw new NumberFormatException("X-axis value is invalid.");
		if (Double.isNaN(y) || Double.isInfinite(y)) throw new NumberFormatException("Y-axis value is invalid.");
		if (Double.isNaN(speed) || Double.isInfinite(speed)) throw new NumberFormatException("Speed value is invalid.");
		// Store the values in the object
		this.id = id;
		this.prev_x = prev_x;
		this.prev_y = prev_y;
		this.x = x;
		this.y = y;
		this.speed = speed;
		this.originX = originX; /** @author Jiawei Xue */
		this.originY = originY;
		this.destX = destX;
		this.destY = destY;
		this.vehicleClass = vehicleClass;
		this.roadID = roadID;
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

	public double getOriginX() {
		return this.originX;
	}

	public double getOriginY() {
		return this.originY;
	}

	public double getDestX() {
		return this.destX;
	}

	public double getDestY() {
		return this.destY;
	}

	public int getvehicleClass() {
		return this.vehicleClass;
	}

	public int getRoadID() {
		return this.roadID;
	}
}
