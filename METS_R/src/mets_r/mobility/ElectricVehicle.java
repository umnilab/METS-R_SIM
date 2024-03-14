package mets_r.mobility;

import java.io.IOException;
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.output.DataCollector;

/**
 * Electric vehicles
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
	protected double batteryLevel_; // current battery level
	protected double mass; // mass of the vehicle in kg
	protected boolean onChargingRoute_ = false;
	protected double tickConsume; // Energy consumption per tick, for verifying the energy model
	protected double totalConsume; // Total energy consumption by this vehicle
	protected double tripConsume; // Energy consumption for the latest accomplished trip
	
	// For recording charging behaviors
	public int chargingTime = 0;
	public int chargingWaitingTime = 0;
	public double initialChargingState = 0;
	
	public ElectricVehicle(int vType, int vSensor) {
		super(vType, vSensor);
		this.batteryLevel_ = GlobalVariables.RECHARGE_LEVEL_LOW * GlobalVariables.EV_BATTERY
				+ this.rand.nextDouble() * (1 - GlobalVariables.RECHARGE_LEVEL_LOW) * GlobalVariables.EV_BATTERY; 
		this.mass = 1521; // vehicle's mass
	}
	
	public ElectricVehicle(double maximumAcceleration, double maximumDeceleration, int vClass, int vSensor) {
		super(maximumAcceleration, maximumDeceleration, vClass, vSensor);
	}

	public void updateBatteryLevel() {
		double tickEnergy = calculateEnergy(); // The energy consumption(kWh) for this tick
		tickConsume = tickEnergy;
		totalConsume += tickEnergy;
		tripConsume += tickEnergy;
		batteryLevel_ -= tickEnergy;
	}
	
	@Override
	public void reachDest() {
		// Log the trip consume here
		String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
				+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
				+ this.getDepTime() + "," + this.getTripConsume() + ",-1,"
				+ "0\r\n";
		try {
			ContextCreator.agg_logger.ev_logger.write(formated_msg);
		} catch (IOException e) {
			e.printStackTrace();
		}		
		super.reachDest(); // Update the vehicle status
	}
	
	@Override
	public void reportStatus() {
		if(this.getVehicleSensorType() == Vehicle.CONNECTED_VEHICLE) {
			String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
					+ "," + this.getRoad().getID() + "," + this.getDistance() + "," + this.currentSpeed() 
					+ "," + this.currentAcc() + "," + this.batteryLevel_  + "," + this.getTickConsume() 
					+ "," + this.getMass() + "\r\n";
			try {
				ContextCreator.agg_logger.traj_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		super.reportStatus();
	}

	public double getBatteryLevel() {
		return batteryLevel_;
	}
	
	public double getMass() {
		return 1.05 * mass;
	}

	public boolean onChargingRoute() {
		return this.onChargingRoute_;
	}

	// Charge the battery.
	public void chargeItself(double batteryValue) {
		chargingTime += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
		batteryLevel_ += batteryValue;
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
		if (!this.movingFlag) { // static, no movement energy consumed
			velocity = 0;
			acceleration = 0;
		}
		double slope = 0.0f; // positive: uphill; negative: downhill, this is always 0, change this if the
								// slope data is available
		double dt = GlobalVariables.SIMULATION_STEP_SIZE; // the length of one tick
		double f1 = getMass() * acceleration;
		double f2 = getMass() * gravity * Math.cos(slope) * cr / 1000 * (c1 * velocity + c2);
		double f3 = 1 / 2 * p0 * A * cd * velocity * velocity;
		double f4 = getMass() * gravity * Math.sin(slope);
		double F = f1 + f2 + f3 + f4;
		double Pte = F * velocity;
		double Pbat;
		if (acceleration >= 0) {
			Pbat = (Pte/etaM + Pconst) / etaG;
		} else {
			double nrb = 1 / Math.exp(0.0411 / Math.abs(acceleration)); // 0.0411 from the equation (10)
			Pbat = Pte * nrb + Pconst / etaG;
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

	// Reset link consume once a ev has passed a link
	public void recSpeedVehicle() {
		DataCollector.getInstance().recordSpeedVehilce(this.getRoad().getID(), this.currentSpeed());
	}
	
	public double getTickConsume() {
		return tickConsume;
	}
	
	public double getTripConsume() {
		return tripConsume;
	}

	public double getTotalConsume() {
		return totalConsume;
	}
	
	public void finishCharging(Integer chargerID, String chargerType) {
		String formated_msg = ContextCreator.getCurrentTick() + "," + chargerID + "," + this.getID() + ","
				+ this.getVehicleClass() + "," + chargerType + "," + this.chargingWaitingTime + ","
				+ this.chargingTime + "," + this.initialChargingState + "\r\n";
		try {
			ContextCreator.agg_logger.charger_logger.write(formated_msg);
			this.chargingWaitingTime = 0;
			this.chargingTime = 0;
		} catch (IOException e) {
			e.printStackTrace();
		}

		this.onChargingRoute_ = false;
		this.setNextPlan(); // Return to where it was before goCharging
		this.setState(Vehicle.PRIVATE_TRIP);
		this.departure(); 
	}
}
