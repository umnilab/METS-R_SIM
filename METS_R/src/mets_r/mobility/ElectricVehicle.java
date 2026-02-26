package mets_r.mobility;

import java.io.IOException;
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;

/**
 * Electric vehicles
 * 
 * @author Zengxiang Lei
 *
 */

public class ElectricVehicle extends Vehicle {
	/* Constant */
	public static double gravity = 9.8; // the gravity is 9.80N/kg for NYC
	
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
	protected double batteryCapacity; // the battery capacity
	protected double batteryLevel; // current battery level, unit KWh
	protected double mass; // mass of the vehicle in kg
	protected boolean onChargingRoute_ = false;
	protected double tickConsume; // Energy consumption per tick, for verifying the energy model
	protected double totalConsume; // Total energy consumption by this vehicle
	protected double tripConsume; // Energy consumption for the latest accomplished trip
	protected double linkConsume; // Parameters for storing energy consumptions
	protected double lowerBatteryRechargeLevel_; // unit: kwh
	protected double higherBatteryRechargeLevel_; // unit: kwh
	protected double metersPerKwh; // For charging planning, unit: m/kwh
	
	// For recording charging behaviors
	public int chargingTime = 0;
	public int chargingWaitingTime = 0;
	public double initialChargingState = 0;
	
	public ElectricVehicle(int vType, int vSensor) {
		super(vType, vSensor);
		initializeEVFields(GlobalVariables.EV_BATTERY, 1521, 5000, GlobalVariables.RECHARGE_LEVEL_LOW, GlobalVariables.RECHARGE_LEVEL_HIGH);
	}
	
	public ElectricVehicle(double maximumAcceleration, double maximumDeceleration, int vClass, int vSensor) {
		super(maximumAcceleration, maximumDeceleration, vClass, vSensor);
		initializeEVFields(GlobalVariables.EV_BATTERY, 1521, 5000, GlobalVariables.RECHARGE_LEVEL_LOW, GlobalVariables.RECHARGE_LEVEL_HIGH);
	}
	
	protected void initializeEVFields(double battery_capacity, double mass, double meters_per_kwh, double recharge_low, double reacharge_high) {
		this.batteryCapacity = battery_capacity;
		this.batteryLevel = reacharge_high * this.batteryCapacity
				+ this.rand.nextDouble() * (1 - reacharge_high) * this.batteryCapacity; 
		this.mass = mass; // vehicle's mass
		this.lowerBatteryRechargeLevel_ = recharge_low * this.batteryCapacity;
		this.higherBatteryRechargeLevel_ = reacharge_high * this.batteryCapacity;
		this.metersPerKwh = meters_per_kwh;
	}
	
	@Override
	public void updateBatteryLevel() {
		double tickEnergy = calculateEnergy(); // The energy consumption(kWh) for this tick
		tickConsume = tickEnergy;
		totalConsume += tickEnergy;
		linkConsume += tickEnergy;
		tripConsume += tickEnergy;
		batteryLevel -= tickEnergy;
	}
	
	@Override
	public void reachDest() {
		if (this.onChargingRoute_) {
			String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + ",4,"
					+ this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
					+ this.getDepTime() + "," + this.getTripConsume() + ",-1" + ",0\r\n";
			try {
				ContextCreator.agg_logger.ev_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
			super.reachDestButNotLeave(); 
			super.leaveNetwork(); // remove from the network
			// Add to the charging station
			ContextCreator.logger.debug("Vehicle arriving at charging station:" + this.getID());
			ChargingStation cs = ContextCreator.getChargingStationContext().get(this.getDestID());
			cs.receiveEV(this);
			this.tripConsume = 0;
		}
		else {
			// Log the trip consume here
			String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
			        + "," + this.getOriginID() + "," + this.getDestID() + "," + this.getOriginRoad() + "," + this.getDestRoad() + "," + this.getAccummulatedDistance() + ","
					+ this.getDepTime() + "," + this.getTripConsume() + ",-1,"
					+ "0\r\n";
			try {
				ContextCreator.agg_logger.ev_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
			this.tripConsume = 0;
			this.reachDestButNotLeave();
			
			if(this.getState() == Vehicle.PRIVATE_TRIP) {
				ContextCreator.getZoneContext().get(this.getDestID()).arrivedPrivateEVTrip += 1;
			}
			if(this.activityPlan.size() >= 2) { 
		    	this.vehicleState = Vehicle.PRIVATE_TRIP;
		    	this.setNextPlan();
		    	this.departure();
		    }
			else if(batteryLevel <= lowerBatteryRechargeLevel_) {
				super.reachDestButNotLeave(); // Go charging
				this.goCharging();
			}
			else {
				this.vehicleState = Vehicle.NONE_OF_THE_ABOVE;
				this.leaveNetwork();
			}
		}
	}
	
	// Decide which charger to select
	public int decideChargerType() {
		double utilityL2 = GlobalVariables.CHARGING_UTILITY_C0 + 
				GlobalVariables.CHARGING_UTILITY_BETA * ChargingStation.chargingTimeL2(this) * GlobalVariables.CHARGING_FEE_L2 + 
				GlobalVariables.CHARGING_UTILITY_C1 * (this.getSoC() > 0.8 ? 1 : 0);
		double utilityL3 =  GlobalVariables.CHARGING_UTILITY_BETA * ChargingStation.chargingTimeL3(this) * GlobalVariables.CHARGING_FEE_DCFC + 
				GlobalVariables.CHARGING_UTILITY_GAMMA * ChargingStation.chargingTimeL3(this);
		
		double shareOfL2 = Math.exp(utilityL2) / (Math.exp(utilityL2) + Math.exp(utilityL3));
		double random = rand.nextDouble();
		if (random < shareOfL2) {
			return ChargingStation.L2;
		} else {
			return ChargingStation.L3;
		}
	}
	
	@Override
	public void appendToRoad(Road road) {
		super.appendToRoad(road);
		
		// Track the remaining mileage and the estimated remaining distance
		if(this.getState() == Vehicle.PRIVATE_TRIP) {
			// Check if vehicle has enough battery
			if((!this.hasEnoughBattery(this.getDistToTravel())) && (this.batteryLevel < this.higherBatteryRechargeLevel_)) {
				ContextCreator.logger.warn("Not enough battery for vehicle:" + this.getID() +" with battery:" + this.batteryLevel + "kWh but has distance:"+ this.getDistToTravel() + "m to go!");
				this.goCharging();
			}
		}
		
		// Update the zone info of relocating taxis
		if(this.getState() == Vehicle.ACCESSIBLE_RELOCATION_TRIP && 
				road.getNeighboringZone(false) > 0) {
			int newZone = road.getNeighboringZone(false);
			ContextCreator.getVehicleContext().updateRelocationTaxi((ElectricTaxi) this, newZone);
		}
	}
	
	// Find the closest charging station and insert a plan to go to charging station into the current activity plan
	public void goCharging() {
		// Add a charging activity
		int chargerType = this.decideChargerType();
		goCharging(chargerType);
	}
	
	// Find the cheapest charging station with specific charger type and update the activity plan
	public void goCharging(int chargerType) {
		// Sanity check
		int current_dest_zone = this.getDestID();
		int current_dest_road = this.getDestRoad();
		if(current_dest_zone < 0) { // vehicle is heading to the charging station already
			ContextCreator.logger.warn("Vehicle " + this.getID() + " is already on route to charging.");
			return;
		}
		
		// Add a charging activity
		ChargingStation cs = ContextCreator.getCityContext().findCheapestChargingStation(this.getCurrentCoord(),
				chargerType);
		if(cs == null) { // Fallback: no L2/DCFC charger within a reasonable buffer
			cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord(),
					chargerType);
		}
		if(cs == null && chargerType == ChargingStation.L3) {
			cs = ContextCreator.getCityContext().findCheapestChargingStation(this.getCurrentCoord(),
					ChargingStation.L2);
			if(cs == null) { // Fallback: no L2 charger within a reasonable buffer
				cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord(),
						ChargingStation.L2);
			}
		}
		if(cs != null) {
			this.onChargingRoute_ = true;
			this.setState(Vehicle.CHARGING_TRIP);
			this.addPlan(cs.getID(), cs.getClosestRoad(true), ContextCreator.getNextTick());
			this.setNextPlan();
			this.addPlan(current_dest_zone, current_dest_road, ContextCreator.getNextTick());
			this.departure();
			ContextCreator.logger.debug("Vehicle " + this.getID() + " is on route to charging.");
		}
		else {
			ContextCreator.logger.warn("Vehicle " + this.getID() + " cannot find charging station at coordinate: " + this.getCurrentCoord());
		}
	}
	
	@Override
	public void reportStatus() {
		if(this.getVehicleSensorType() == Vehicle.CV2X || this.getVehicleSensorType() == Vehicle.DSRC ) { 			
			// Send V2X data for CAV applications
			if(GlobalVariables.V2X) {
				ContextCreator.kafkaManager.produceBSM(this, this.getCurrentCoord(), this.getVehicleSensorType());
			}
		}
		super.reportStatus();
	}

	public double getMass() {
		return 1.05 * mass; // 1.05 for compensating potential passengers and supplies
	}
	
	public double getLinkConsume() {
		return linkConsume;
	}

	// Reset link consume once a EV has passed a link
	public void resetLinkConsume() {
		this.linkConsume = 0;
	}

	// Charge the battery.
	public void chargeItself(double batteryValue) {
		chargingTime += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
		batteryLevel += batteryValue;
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
		
		//Put vehicle back to the departure link of the charging station
		this.onChargingRoute_ = false;
		this.setNextPlan(); // Return to where it was before goCharging
		this.setState(Vehicle.CHARGING_RETURN_TRIP);
		this.departure(ContextCreator.getChargingStationContext().get(chargerID).getClosestRoad(false)); 
	}
	
	public double getBatteryLevel() {
		return this.batteryLevel;
	}
	
	public double getSoC() {
		return this.batteryLevel/this.batteryCapacity;
	}
	
	// Set the battery level, unit: % of the total capacity
	public void setBatteryLevel(double bt) {
		this.batteryLevel = Math.max(0.0, Math.min(1.0, bt / 100.0)) * this.batteryCapacity;
	}
	
	
	public void setLowerBatteryRechargeLevel(double bt) {
		this.lowerBatteryRechargeLevel_ = bt / 100.0 * this.batteryCapacity;
	}
	
	public void setHigherBatteryRechargeLevel(double bt) {
		this.higherBatteryRechargeLevel_ = bt / 100.0 * this.batteryCapacity;
	}
	
	public void setBatteryCapacity(double bc) {
		this.batteryCapacity = bc;
	}
	
	public double getBatteryCapacity() {
		return this.batteryCapacity;
	}
	
	public boolean hasEnoughBattery() {
		return this.batteryLevel > this.lowerBatteryRechargeLevel_;
	}
	
	// Unit: meters
	public boolean hasEnoughBattery(double distance) {
		return this.batteryLevel > distance/this.metersPerKwh;
	}
	
	/* Getters and setters for save/load support */
	public boolean isOnChargingRoute() { return this.onChargingRoute_; }
	public void setOnChargingRoute(boolean v) { this.onChargingRoute_ = v; }
	public void setTotalConsume(double v) { this.totalConsume = v; }
	public void setTripConsume(double v) { this.tripConsume = v; }
	public void setLinkConsume(double v) { this.linkConsume = v; }
	public double getMass_() { return this.mass; }
	public void setMass_(double v) { this.mass = v; }
	public double getLowerRechargeLevel() { return this.lowerBatteryRechargeLevel_; }
	public double getHigherRechargeLevel() { return this.higherBatteryRechargeLevel_; }
	public void setLowerRechargeLevel(double v) { this.lowerBatteryRechargeLevel_ = v; }
	public void setHigherRechargeLevel(double v) { this.higherBatteryRechargeLevel_ = v; }
	public double getMetersPerKwh() { return this.metersPerKwh; }
	public void setMetersPerKwh(double v) { this.metersPerKwh = v; }
	public void setBatteryLevelDirectly(double v) { this.batteryLevel = v; }
	public int getChargingTime() { return this.chargingTime; }
	public int getChargingWaitingTime() { return this.chargingWaitingTime; }
	public void setChargingTime(int v) { this.chargingTime = v; }
	public void setChargingWaitingTime(int v) { this.chargingWaitingTime = v; }
	public double getInitialChargingState() { return this.initialChargingState; }
	public void setInitialChargingState(double v) { this.initialChargingState = v; }
}
