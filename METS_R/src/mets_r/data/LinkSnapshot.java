package mets_r.data;

class LinkSnapshot {
	final int id; // Unique ID of the road
	final float speed; //Current time mean speed (unit meters/second)
	final int nVehicles; //  number of vehicles currently on the road, including the downstream junction
	final float energy;
	final int flow;

	LinkSnapshot(int id, double speed, int nVehicles, double energy, int flow) {
		this.id = id;
		this.speed = (float) speed;
		this.nVehicles = nVehicles;
		this.energy = (float) energy;
		this.flow = flow;
		if (Double.isNaN(speed) || Double.isInfinite(speed)) {
			throw new NumberFormatException("Speed is NaN or Inifinite for " + id);
		}
		if (Double.isNaN(energy) || Double.isInfinite(energy)) throw new NumberFormatException("Energy is NaN or Inifinite for " + id);
	}

	public int getId() {
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

	String getJSONLine() {
		return String.format("%d,%d,%.3f, %d, $.3f", id, nVehicles, speed, flow, energy);
	}

}
