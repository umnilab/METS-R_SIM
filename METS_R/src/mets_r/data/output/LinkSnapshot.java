package mets_r.data.output;

class LinkSnapshot {
	final String id; // Unique ID of the road
	final float speed; //Current time mean speed (unit meters/second)
	final int nVehicles; //  number of vehicles currently on the road, including the downstream junction
	final float energy;
	final int flow;
	final int parkingCapacity;
	final int parkedNum;

	LinkSnapshot(String id, double speed, int nVehicles, double energy, int flow,
			int parkingCapacity, int parkedNum) {
		this.id = id;
		this.speed = (float) speed;
		this.nVehicles = nVehicles;
		this.energy = (float) energy;
		this.flow = flow;
		this.parkingCapacity = parkingCapacity;
		this.parkedNum = parkedNum;
		if (Double.isNaN(speed) || Double.isInfinite(speed)) {
			throw new NumberFormatException("Speed is NaN or Inifinite for " + id);
		}
		if (Double.isNaN(energy) || Double.isInfinite(energy)) throw new NumberFormatException("Energy is NaN or Inifinite for " + id);
	}

	public String getId() {
		return this.id;
	}

	public double getSpeed() {
		return this.speed;
	}

	public int nVehicles() {
		return this.nVehicles;
	}

	public double getEnergy() {
		return this.energy;
	}

	public int getFlow() {
		return this.flow;
	}

	public int getParkingCapacity() {
		return this.parkingCapacity;
	}

	public int getParkedNum() {
		return this.parkedNum;
	}

	String getJSONLine() {
		return String.format("%s,%d,%.3f,%d,%.3f,%d,%d", id, nVehicles, speed, flow,
				energy, parkingCapacity, parkedNum);
	}

}
