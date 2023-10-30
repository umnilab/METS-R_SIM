package mets_r.mobility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.DataCollector;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Zone;
import mets_r.routing.RouteContext;
import util.Pair;

/**
 * Electric taxis
 * 
 * @author Zengxiang Lei, Jiawei Xue, Juan Suarez
 *
 */

public class ElectricTaxi extends Vehicle {
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
	public static double etaM = 0.92; // efficiency of the driver line
	public static double etaG = 0.91; // efficiency of the electric motor
	public static double Pconst = 1500; // energy consumption by auxiliary accessories

	/* Local variables */
	private int numPeople_; // no of people inside the vehicle
	private double avgPersonMass_; // average mass of a person in lbs
	private double batteryLevel_; // current battery level
	private double lowerBatteryRechargeLevel_;
	private double higherBatteryRechargeLevel_;
	private double mass; // mass of the vehicle in kg
	private boolean onChargingRoute_ = false;
	private int cruisingTime_;

	// Parameters for storing energy consumptions
	private double tickConsume;
	private double totalConsume;
	private double linkConsume; // For UCB eco-routing, energy spent for passing current link, will be reset to
								// zero once this ev entering a new road.
	private double tripConsume; // For UCB testing
	
	private boolean recordTrajectory;
	
	/* Public variables */
	// For operational features
	public Queue<Request> passengerWithAdditionalActivityOnTaxi;
	public int currentZone;
	
	// Service metrics
	public int served_pass = 0;
	public int charging_time = 0;
	public int charging_waiting_time = 0;
	public double initial_charging_state = 0;
	
	// Parameter to show which route has been chosen in eco-routing.
	public int routeChoice = -1;

	public ElectricTaxi(boolean recordTrajectory) {
		super(Vehicle.ETAXI);
		this.recordTrajectory = recordTrajectory;
		this.setInitialParams();
	}

	// Find the closest charging station and update the activity plan
	public void goCharging() {
		int current_dest_zone = this.getDestID();
		Coordinate current_dest_coord = ContextCreator.getZoneContext().get(this.getDestID()).getCoord();
		// Add a charging activity
		ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord());
		this.onChargingRoute_ = true;
		this.addPlan(cs.getIntegerID(), cs.getCoord(), ContextCreator.getNextTick());
		this.setNextPlan();
		this.addPlan(current_dest_zone, current_dest_coord, ContextCreator.getNextTick());
		this.setState(Vehicle.CHARGING_TRIP);
		this.departure();
		ContextCreator.logger.debug("Vehicle " + this.getID() + " is on route to charging");
	}
	
	// Randomly select a neighboring link and update the activity plan
	public void goCruising(Zone z) {
		// Add a cruising activity
		Coordinate dest = z.getNeighboringCoord(rand_relocate_only.nextInt(z.getNeighboringLinkSize()));
		while(dest == this.getDestCoord()) { // Sample again
			dest = z.getNeighboringCoord(rand_relocate_only.nextInt(z.getNeighboringLinkSize()));
		}
		this.addPlan(z.getIntegerID(), dest, ContextCreator.getNextTick());
		this.setNextPlan();
		this.setState(Vehicle.CRUISING_TRIP);
		this.departure();
	}
	
	// Stop cruising
	public void stopCruising() {
		// Log the cruising trip here
		String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
		+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
		+ this.getDepTime() + "," + this.getTripConsume() + "," + this.routeChoice + ","
		+ this.getNumPeople()+ "\r\n";
		try {
			ContextCreator.agg_logger.ev_logger.write(formated_msg);
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.setState(Vehicle.NONE_OF_THE_ABOVE);
		this.tripConsume = 0;
		this.cruisingTime_ = 0;
		
	}
	
	// Find the closest Zone with parking space and relocate to their
	public void goParking() {
		ContextCreator.getZoneContext().get(this.getDestID()).removeOneCruisingVehicle();
		for(int z: ContextCreator.getZoneContext().get(this.getDestID()).getNeighboringZones()) {
			if(ContextCreator.getZoneContext().get(z).getCapacity()>0) {
				ContextCreator.getVehicleContext().getVehiclesByZone(this.getDestID()).remove(this);
				ContextCreator.getZoneContext().get(this.getDestID()).numberOfRelocatedVehicles += 1;
				ContextCreator.getZoneContext().get(z).addFutureSupply();
				this.addPlan(z, ContextCreator.getZoneContext().get(z).getCoord(),
						ContextCreator.getNextTick());
				this.setNextPlan();
				this.departure();
				this.setState(Vehicle.ACCESSIBLE_RELOCATION_TRIP);
				return;
			}
		}
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
		if(this.recordTrajectory) {
			String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
					+ "," + this.getRoad().getID() + "," + this.getDistance() + "," + this.currentSpeed() 
					+ "," + this.currentAcc() + "," + this.batteryLevel_  + "," + this.getTickConsume() 
					+ "," + this.getNumPeople() + "\r\n";
			try {
				ContextCreator.agg_logger.traj_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
	}

	// Relocate vehicle
	/**
	 * @param p
	 */
	public void relocation(int orginID, int destinationID) {
		if(this.getState() == Vehicle.CRUISING_TRIP) {
			this.stopCruising();
		}
		this.addPlan(destinationID, ContextCreator.getZoneContext().get(destinationID).getCoord(),
				ContextCreator.getNextTick());
		this.setNextPlan();
		this.setState(Vehicle.INACCESSIBLE_RELOCATION_TRIP);
		// Add vehicle to newqueue of corresponding road
		this.departure();
	}

	/**
	 * @param list of passengers
	 */
	public void servePassenger(List<Request> plist) {
		if (!plist.isEmpty()) {
			if(this.getState() == Vehicle.CRUISING_TRIP) {
				this.stopCruising();
			}
			// Dispatch the vehicle to serve the request
			this.addPlan(this.getDestID(),
					plist.get(0).getOriginCoord(),
					ContextCreator.getNextTick());
			
			for (Request p : plist) {
				this.addPlan(p.getDestination(),
						p.getDestCoord(),
						ContextCreator.getNextTick());
				this.served_pass += 1;
				this.setNumPeople(this.getNumPeople() + 1);
			}
			this.setNextPlan();
			this.setState(Vehicle.PICKUP_TRIP);
			// Add vehicle to new queue of corresponding road
			this.departure();
			
		}
	}
	
	@Override
	public void setNextRoad() {
		if(!this.atOrigin) {
			super.setNextRoad();
		}
		else {
			// Clear legacy impact
			this.clearShadowImpact();
			this.roadPath = new ArrayList<Road>();
			if (!ContextCreator.routeResult_received.isEmpty() && GlobalVariables.ENABLE_ECO_ROUTING_EV) {
				Pair<List<Road>, Integer> route_result = RouteContext.ecoRoute(this.getRoad(), this.getOriginID(), this.getDestID());
				this.roadPath = route_result.getFirst();
				this.routeChoice = route_result.getSecond();
			}
			
			// Compute new route if eco-routing is not used
			if (this.roadPath == null || this.roadPath.isEmpty()) {
				this.routeChoice = -1;
				this.roadPath = RouteContext.shortestPathRoute(this.getRoad(), this.getDestCoord(), this.rand_route_only); // K-shortest path or shortest path
			}
			
			// Fix the inconsistency of the start link 
			if (this.getRoad()!=this.roadPath.get(0)) {
				this.routeChoice = -1;
				this.roadPath = RouteContext.shortestPathRoute(this.getRoad(), this.getDestCoord(), this.rand_route_only); // K-shortest path or shortest path
			}
			
			this.setShadowImpact();
			if (this.roadPath == null) {
				ContextCreator.logger.error("Routing fails with origin: " + this.getRoad().getID() + ", destination " + this.getDestCoord() + 
						", destination road " + this.getDestRoadID());
				this.atOrigin = false;
				this.nextRoad_ = null;
			}
			else if (this.roadPath.size() < 2) { // The origin and destination share the same Junction
				this.atOrigin = false;
				this.nextRoad_ = null;
			} else {
				this.atOrigin = false;
				this.nextRoad_ = roadPath.get(1);
				this.assignNextLane();
			}
		}
	}
	
	@Override
	public void appendToRoad(Road road) {
		if(this.getState() == Vehicle.ACCESSIBLE_RELOCATION_TRIP && 
				road.getNeighboringZone()!=-1 && 
				this.currentZone != road.getNeighboringZone()) {
			this.currentZone = road.getNeighboringZone();
			ContextCreator.getVehicleContext().addRelocationTaxi(this, road.getNeighboringZone());
		}
		super.appendToRoad(road);
	}

	@Override
	public void reachDest() {
		// Check if the vehicle was on a charging route
		if (this.onChargingRoute_) {
			String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + ",4,"
					+ this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
					+ this.getDepTime() + "," + this.getTripConsume() + ",-1" + "," + this.getNumPeople() + "\r\n";
			try {
				ContextCreator.agg_logger.ev_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
			super.reachDest(); 
			super.leaveNetwork(); // remove from the network
			// Add to the charging station
			ContextCreator.logger.debug("Vehicle arriving at charging station:" + this.getID());
			ChargingStation cs = ContextCreator.getChargingStationContext().get(this.getDestID());
			cs.receiveEV(this);
			this.tripConsume = 0;
		} else {
			// Log the trip consume here
			if(this.getAccummulatedDistance() > 0) {
				String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
				+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
				+ this.getDepTime() + "," + this.getTripConsume() + "," + this.routeChoice + ","
				+ this.getNumPeople()+ "\r\n";
				try {
					ContextCreator.agg_logger.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
				this.tripConsume = 0;
			}

			Zone z = ContextCreator.getZoneContext().get(this.getDestID()); // get destination zone info
			
			super.reachDest(); // Update the vehicle status
			
			// Decide the next step
			if (this.getState() == Vehicle.OCCUPIED_TRIP) {
				this.setNumPeople(this.getNumPeople() - 1); // passenger arrived
				z.taxiServedRequest += 1;
				// if pass need to take the bus to complete his or her trip
				if (this.passengerWithAdditionalActivityOnTaxi.size() > 0) {
					// generate a pass and add it to the corresponding zone
					Request p = this.passengerWithAdditionalActivityOnTaxi.poll();
					p.moveToNextActivity();
					if (z.busReachableZone.contains(p.getDestination())) {
						z.insertBusPass(p); // if bus can reach the destination
					} else {
						z.insertTaxiPass(p); // this is called when we dynamically update bus schedules
					}
				}
				
				z.removeFutureSupply();
				if (this.getNumPeople() > 0) { // keep serving more passengers
					// super.leaveNetwork();  // Leave network to drop-off passengers
					this.setNextPlan();
					ContextCreator.getZoneContext().get(this.getDestID()).addFutureSupply();
					this.departure();
				}
				else { // charging or join the current zone
					if(batteryLevel_ <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
							&& batteryLevel_ <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(5))) {
						this.goCharging();
					}
					else { // join the current zone
						ContextCreator.getVehicleContext().getVehiclesByZone(z.getIntegerID()).add(this);
						if(z.getCapacity() > 0) { // Has capacity
							z.addOneParkingVehicle();
		                	this.getParked(z);
					    }
		                else {
		                	z.addOneCruisingVehicle();
		                	// Select a neighboring link and cruise to there
		                	this.goCruising(z);
		                }
					}
				}
			}
			else if(this.getState() == Vehicle.PICKUP_TRIP){
				// super.leaveNetwork(); // Leave network to pickup passengers
				this.setState(Vehicle.OCCUPIED_TRIP);
				this.setNextPlan();
				this.departure();
			}
			else if (this.getState() == Vehicle.CRUISING_TRIP) {
				if(this.cruisingTime_ <= GlobalVariables.MAX_CRUISING_TIME * 60 / GlobalVariables.SIMULATION_STEP_SIZE) {
					if(batteryLevel_ <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
							&& batteryLevel_ <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(5))) {
						ContextCreator.getVehicleContext().getVehiclesByZone(z.getIntegerID()).remove(this);
						this.goCharging();
					}
					else {
						if(z.getCapacity() > 0) { // Has capacity
		                	z.removeOneCruisingVehicle();
		                	z.addOneParkingVehicle();
		                	this.cruisingTime_ = 0;
		    				this.getParked(z);
					    }
		                else {
		                	this.cruisingTime_ += ContextCreator.getCurrentTick() - this.getDepTime();
		                	// Keep cruising
		                	this.goCruising(z);
		                }
					}
				}
				else {
					this.cruisingTime_ = 0;
					this.goParking();
				}
			}
			else if (this.getState() == Vehicle.INACCESSIBLE_RELOCATION_TRIP || this.getState() == Vehicle.ACCESSIBLE_RELOCATION_TRIP) {
				if (this.getState() == Vehicle.ACCESSIBLE_RELOCATION_TRIP)
					ContextCreator.getVehicleContext().removeRelocationTaxi(this);
				z.removeFutureSupply();
				if(batteryLevel_ <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
						&& batteryLevel_ <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(5))) {
					this.goCharging();
				}
				else { // join the current zone
					ContextCreator.getVehicleContext().getVehiclesByZone(z.getIntegerID()).add(this);
					if(z.getCapacity() > 0) { // Has capacity
	                	z.addOneParkingVehicle();
	    				this.getParked(z);
				    }
	                else {
	                	z.addOneCruisingVehicle();
	                	this.cruisingTime_ = 0;
	                	this.goCruising(z); // Select a neighboring link and cruise to there
	                	
	                }
				}
			}
			else {
				ContextCreator.logger.error("Vehicle does not belong to any of given states!");
			}
		}
	}

	public void setInitialParams() {
		this.numPeople_ = 0;
		this.cruisingTime_ = 0;
		this.batteryLevel_ = GlobalVariables.RECHARGE_LEVEL_LOW * GlobalVariables.EV_BATTERY
				+ this.rand.nextDouble() * (1 - GlobalVariables.RECHARGE_LEVEL_LOW) * GlobalVariables.EV_BATTERY; // unit:kWh,
																											// times a
																											// large
																											// number to
																											// disable
																											// charging
		this.lowerBatteryRechargeLevel_ = GlobalVariables.RECHARGE_LEVEL_LOW * GlobalVariables.EV_BATTERY;
		this.higherBatteryRechargeLevel_ = GlobalVariables.RECHARGE_LEVEL_HIGH * GlobalVariables.EV_BATTERY;
		this.mass = 1521; // kg
		this.avgPersonMass_ = 60; // kg

		// Parameters for energy calculation
		this.tickConsume = 0.0; // kWh
		this.totalConsume = 0.0; // kWh
		
		// Parameters for UCB calculation
		this.linkConsume = 0;
		this.passengerWithAdditionalActivityOnTaxi = new LinkedList<Request>();
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

	// Charge the battery.
	public void chargeItself(double batteryValue) {
		charging_time += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
		batteryLevel_ += batteryValue;
	}

	// EV energy consumption model
	// Fiori, C., Ahn, K., & Rakha, H. A. (2016). Power-based electric vehicle
	// energy consumption model: Model development and validation. Applied Energy,
	// 168, 257-268.
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
		if(Math.abs(energyConsumption)>5) {
			ContextCreator.logger.warn("Abnormal energy " + this.getID() + ", " + this.getState() + ", dist: "+ this.getDistance() + ", v: " + this.currentSpeed() + ", a: " + this.currentAcc());
		}
		
		return energyConsumption;
	}

	// spline interpolation
	public PolynomialSplineFunction splineFit(double[] x, double[] y) {
		SplineInterpolator splineInt = new SplineInterpolator();
		PolynomialSplineFunction polynomialSpl = splineInt.interpolate(x, y);
		return polynomialSpl;
	}
	


	public void finishCharging(Integer chargerID, String chargerType) {
		String formated_msg = ContextCreator.getCurrentTick() + "," + chargerID + "," + this.getID() + ","
				+ this.getVehicleClass() + "," + chargerType + "," + this.charging_waiting_time + ","
				+ this.charging_time + "," + this.initial_charging_state + "\r\n";
		try {
			ContextCreator.agg_logger.charger_logger.write(formated_msg);
			this.charging_waiting_time = 0;
			this.charging_time = 0;
		} catch (IOException e) {
			e.printStackTrace();
		}

		this.onChargingRoute_ = false;
		this.setNextPlan(); // Return to where it was before goCharging
		ContextCreator.getZoneContext().get(this.getDestID()).addFutureSupply();
		this.setState(Vehicle.INACCESSIBLE_RELOCATION_TRIP);
		this.departure(); 
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
}
