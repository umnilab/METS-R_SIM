package addsEVs.data;

import addsEVs.citycontext.Road;
import jdk.nashorn.internal.runtime.regexp.joni.exception.ValueException;

class LinkSnapshot {
	
	/** Unique ID of the road */
	final int id;
	
	/** Current time mean speed (unit most likely meters/second) */
	final float speed;
	
	/** Number of vehicles currently on the road,
	 * including the junction */
	final int nVehicles;
	
	final float energy;
	
	final int flow;
	
	LinkSnapshot(int id, double speed, int nVehicles, double energy, int flow) {
		this.id = id;
		this.speed = (float) speed;
		this.nVehicles = nVehicles;
		this.energy = (float) energy;
		this.flow = flow;
		
		// validity checks
		if (id < 0) {
			throw new ValueException("Road ID negative for " + id);
		}
		if (speed < 0) {
			throw new ValueException("Speed is negative for " + id);
		}
		if (nVehicles < 0) {
			throw new ValueException(
					"No. of vehicles on road is negative for " + id);
		}
		
		if (flow < 0) {
			throw new ValueException(
					"No. of Flows is negative for " + id);
		}
		
		if (Double.isNaN(speed) || Double.isInfinite(speed)) {
			throw new NumberFormatException("Speed is NaN for " + id);
		}
		
		if (Double.isNaN(energy) || Double.isInfinite(energy)) {
			throw new NumberFormatException("Energy is NaN for " + id);
		}
		
	}
	
	public int getId(){
		return this.id;
	}
	
	public double getSpeed(){
		return this.speed;
	}
	
	public int nVehicles(){
		return this.nVehicles;
	}
	
	public double getEnergy(){
		return  this.energy;
	}
	
	public int getFlow(){
		return this.flow;
	}
	
	/** Return the string of this vehicle's attributes to be exported to JSON */
	String getJSONLine() {
		return String.format("%d,%d,%.3f, %d, $.3f", id, nVehicles, speed, flow, energy);
	}

}
