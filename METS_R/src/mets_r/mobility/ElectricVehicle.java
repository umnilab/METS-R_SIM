package mets_r.mobility;

import java.io.IOException;
import java.util.List;

import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;


import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.DataCollector;
import mets_r.facility.Zone;

/**
 * Private electric vehicles
 * 
 * @author Zengxiang Lei
 *
 */

public class ElectricVehicle extends Vehicle {
	/* Constant */
	public static double gravity = 9.8; // the gravity is 9.80N/kg for NYC
	public static double batteryCapacity = GlobalVariables.EV_BATTERY; // the storedEnergy is 50 kWh.
	
	// Parameters for Fiori (2016) model
	public static double p0 = 1.2256;
	public static double A = 2.3316;
	public static double cd = 0.28;
	public static double cr = 1.75;
	public static double c1 = 0.0328;
	public static double c2 = 4.575;
	public static double etaM = 0.92;
	public static double etaG = 0.91;
	public static double Pconst = 1500; // energy consumption by auxiliary accessories

	// Local variables
	private int numPeople_; // no of people inside the vehicle
	private double avgPersonMass_; // average mass of a person in lbs
	private double batteryLevel_; // current battery level
	private double mass; // mass of the vehicle in kg
	private boolean onChargingRoute_ = false;

	// Parameters for storing energy consumptions
	private double tickConsume;
	private double totalConsume;
	private double linkConsume; // For UCB eco-routing, energy spent for passing current link, will be reset to
								// zero once this ev entering a new road.
	private double tripConsume; // For UCB testing
	
	// Parameter to show which route has been chosen in eco-routing.
	private int routeChoice = -1;
	
	public ElectricVehicle() {
		super(Vehicle.PRIVATE_EV);
		this.setInitialParams();
	}
	
	// Parking at Zone z
	public void getParked(Zone z) {
		super.leaveNetwork();
		this.setState(Vehicle.PARKING);
	}

	@Override
	public void updateBatteryLevel() {
		double tickEnergy = calculateEnergy(); // The energy consumption(kWh) for this tick
		tickConsume = tickEnergy;
		linkConsume += tickEnergy; // Update energy consumption on current link
		totalConsume += tickEnergy;
		tripConsume += tickEnergy;
		batteryLevel_ -= tickEnergy;
	}

	/**
	 * @param list of passengers with their visiting plans
	 */
	public void servePassenger(List<Request> planList) {
		if (!planList.isEmpty()) {
			this.setState(Vehicle.OCCUPIED_TRIP);
			
			for (Request p : planList) {
				this.addPlan(p.getDestination(),
						ContextCreator.getZoneContext().get(p.getDestination()).getCoord(),
						ContextCreator.getNextTick());
				this.setNumPeople(this.getNumPeople() + 1);
			}
			this.setNextPlan();
			// Add vehicle to new queue of corresponding road
			this.departure();
			
		}
	}

	@Override
	public void reachDest() {
		// Log the trip consume here
		String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
				+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
				+ this.getDepTime() + "," + this.getTripConsume() + "," + this.getRouteChoice() + ","
				+ this.getNumPeople()+ "\r\n";
		try {
			ContextCreator.agg_logger.ev_logger.write(formated_msg);
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.tripConsume = 0;

		Zone z = ContextCreator.getZoneContext().get(this.getDestID()); // get destination zone info
		
		super.reachDest(); // Update the vehicle status
			
		// Decide the next step
		if (this.getState() == Vehicle.OCCUPIED_TRIP) {
			this.setNumPeople(this.getNumPeople() - 1); // passenger arrived
			// if pass need to take the bus to complete his or her trip
			if (this.getNumPeople() > 0) { // keep serving more passengers
				super.leaveNetwork();
				this.setNextPlan();
				this.departure();
			}
			else { // join the current zone
                this.getParked(z);
			}
		}
		else {
			ContextCreator.logger.error("Vehicle does not belong to any of given states!");
		}
	}

	public void setInitialParams() {
		this.numPeople_ = 0;
		this.batteryLevel_ = GlobalVariables.RECHARGE_LEVEL_LOW * GlobalVariables.EV_BATTERY
				+ this.rand.nextDouble() * (1 - GlobalVariables.RECHARGE_LEVEL_LOW) * GlobalVariables.EV_BATTERY; // unit:kWh,
																											// times a
																											// large
																											// number to
																											// disable
																											// charging
		this.mass = 1521; // kg
		this.avgPersonMass_ = 60; // kg

		// Parameters for energy calculation
		this.tickConsume = 0.0; // kWh
		this.totalConsume = 0.0; // kWh
		// For Maia's model
//		double soc[] = {0.00, 0.10, 0.20, 0.40, 0.60, 0.80, 1.00};             
//		double r[] = {0.0419, 0.0288, 0.0221, 0.014, 0.0145, 0.0145, 0.0162}; 
//		PolynomialSplineFunction f = splineFit(soc,r);                         
//		this.splinef = f; 
		// Parameters for UCB calculation
		this.linkConsume = 0;
	}

	public double getBatteryLevel() {
		return batteryLevel_;
	}

	public int getNumPeople() {
		return numPeople_;
	}

	public void setNumPeople(int numPeople) {
		numPeople_ = numPeople;
	}

	public double getMass() {
		return 1.05 * mass + numPeople_ * avgPersonMass_;
	}

	public boolean onChargingRoute() {
		return this.onChargingRoute_;
	}

	// EV energy consumption model
	// Fiori, C., Ahn, K., & Rakha, H. A. (2016). Power-based electric vehicle
	// energy consumption model: Model development and validation. Applied Energy,
	// 168, 257 268.
	// P = (ma + mgcos(\theta)\frac{C_r}{1000)(c_1v+c_2) + 1/2 \rho_0
	// AC_Dv^2+mgsin(\theta))v
	public double calculateEnergy() {
		double velocity = currentSpeed(); // obtain the speed
		double acceleration = currentAcc(); // obtain the acceleration
		if (!this.movingFlag) {
			velocity = 0;
			acceleration = 0;
		}
		double slope = 0.0f; // positive: uphill; negative: downhill, this is always 0, change this if the
								// slope data is available
		double dt = GlobalVariables.SIMULATION_STEP_SIZE; // this time interval, the length of one tick. 0.3
		double f1 = getMass() * acceleration;
		double f2 = getMass() * gravity * Math.cos(slope) * cr / 1000 * (c1 * velocity + c2);
		double f3 = 1 / 2 * p0 * A * cd * velocity * velocity;
		double f4 = getMass() * gravity * Math.sin(slope);
		double F = f1 + f2 + f3 + f4;
		double Pte = F * velocity;
		double Pbat;
		if (acceleration >= 0) {
			Pbat = (Pte + Pconst) / (etaM * etaG);
		} else {
			double nrb = 1 / Math.exp(0.0411 / Math.abs(acceleration));
			Pbat = (Pte + Pconst) * nrb;
		}
		double energyConsumption = Pbat * dt / (3600 * 1000); // wh to kw
		return energyConsumption;
	}


	// spline interpolation
	public PolynomialSplineFunction splineFit(double[] x, double[] y) {
		SplineInterpolator splineInt = new SplineInterpolator();
		PolynomialSplineFunction polynomialSpl = splineInt.interpolate(x, y);
		return polynomialSpl;
	}

	public double getTickConsume() {
		return tickConsume;
	}

	public double getTotalConsume() {
		return totalConsume;
	}

	public double getLinkConsume() {
		return linkConsume;
	}

	// Reset link consume once a ev has passed a link
	public void resetLinkConsume() {
		this.linkConsume = 0;
	}
	
	public void recLinkSnaphotForUCB() {
		DataCollector.getInstance().recordLinkSnapshot(this.getRoad().getID(), this.getLinkConsume());
	}

	public void recSpeedVehicle() {
		DataCollector.getInstance().recordSpeedVehilce(this.getRoad().getID(), this.currentSpeed());
	}

	public double getTripConsume() {
		return tripConsume;
	}

	public void setRouteChoice(int i) {
		this.routeChoice = i;
	}

	public int getRouteChoice() {
		return this.routeChoice;
	}
}
