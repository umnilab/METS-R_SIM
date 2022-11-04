package mets_r.mobility;

import java.io.IOException;
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
import repast.simphony.essentials.RepastEssentials;

/**
 * 
 * @author Zengxiang Lei, Jiawei Xue, Juan Suarez
 *
 */

public class ElectricVehicle extends Vehicle {
	// Local variables
	private int numPeople_; // no of people inside the vehicle
	public Queue<Request> passengerWithAdditionalActivityOnTaxi;
	private double avgPersonMass_; // average mass of a person in lbs
	protected double batteryLevel_; // current battery level
	protected double lowerBatteryRechargeLevel_;
	public double higherBatteryRechargeLevel_;
	private double mass; // mass of the vehicle in kg
	protected boolean onChargingRoute_ = false;
	protected int cruisingTime_;

	// Parameters for storing energy consumptions
	private double tickConsume;
	private double totalConsume;
	private double linkConsume; // For UCB eco-routing, energy spent for passing current link, will be reset to
								// zero once this ev entering a new road.
	protected double tripConsume; // For UCB testing

	public int served_pass = 0;
	public int charging_time = 0;
	public int charging_waiting_time = 0;
	public double initial_charging_state = 0;

	public static double gravity = 9.8; // the gravity is 9.80N/kg for NYC
	public static double batteryCapacity = GlobalVariables.EV_BATTERY; // the storedEnergy is 50 kWh.

	// Parameter to show which route has been chosen in eco-routing.
	private int routeChoice = -1;

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

	// Parameters for Maia (2012) model
	// public static double urr = 0.005; // 1996_1998_General Motor Model
	// public static double densityAir = 1.25; // air density = 1.25kg/m3
	// public static double A = 1.89; // A = 1.89m2
	// public static double Cd = 0.19; // Cv
	// Parameter for Fhc, Fla calculation
	// public static double etaM = 0.95; // etaM
	// public static double etaG = 0.95; // etaG
	// public static double Pconst = 1300.0; // pConst
	// public static double Voc = 325.0; // nominal system voltage
	// public static double rIn = 0.0; //
	// public static double T = 20.0; // assume the temperature is 20 Celsius
	// degree;
	// public static double k = 1.03; // k is the Peukert coefficient, k = 1.03;
	// public static double cp = 77.0; // cp = 77.0; ///nominal capacity: 77 AH
	// public static double c = 20.0;

	public ElectricVehicle() {
		super(Vehicle.ETAXI);
		this.setInitialParams();
	}

	public ElectricVehicle(float maximumAcceleration, float maximumDeceleration) {
		super(maximumAcceleration, maximumDeceleration, 1);
		this.setInitialParams();
	}

	// Find the closest charging station and update the activity plan
	public void goCharging() {
		int current_dest_zone = this.getDestID();
		Coordinate current_dest_coord = ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).getCoord();
		// Add a charging activity
		ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord());
		this.onChargingRoute_ = true;
		this.setState(Vehicle.CHARGING_TRIP);
		this.addPlan(cs.getIntegerID(), cs.getCoord(), (int) RepastEssentials.GetTickCount());
		this.setNextPlan();
		this.addPlan(current_dest_zone, current_dest_coord, (int) RepastEssentials.GetTickCount());
		this.departure();
		ContextCreator.logger.debug("Vehicle " + this.getId() + " is on route to charging");
	}
	
	// Randomly select a neighboring link and update the activity plan
	public void goCrusing(Zone z) {
		// Add a cruising activity
		Road r = z.neighboringLinks.get(this.rand.nextInt(z.neighboringLinks.size()));
		while(r.getJunctions().get(0) == this.getRoad().getJunctions().get(1)) {
			r = z.neighboringLinks.get(this.rand.nextInt(z.neighboringLinks.size()));
		}
		this.addPlan(z.getIntegerID(), r.getJunctions().get(1).getCoordinate(), (int) RepastEssentials.GetTickCount());
		this.setNextPlan();
		this.departure();
	}
	
	// Stop cruising
	public void stopCruising() {
		if(!this.isOnLane()) { // The vehicle is currently in a junction, so the routing will fail if not leave the network
			this.leaveNetwork();
		}
		// Log the cruising trip here
		String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleID() + "," + this.getState()
		+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
		+ this.getDepTime() + "," + this.getTripConsume() + "," + this.getRouteChoice() + ","
		+ this.getNumPeople()+ "\r\n";
		try {
			ContextCreator.ev_logger.write(formated_msg);
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.tripConsume = 0;
	}
	
	// Find the closest Zone with parking space and relocate to their
	public void goParking() {
		for(Zone z: ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).neighboringZones) {
			if(z.getCapacity()>0) {
				this.stopCruising();
				ContextCreator.getVehicleContext().getVehiclesByZone(this.getDestID()).remove(this);
				z.addFutureSupply();
				this.relocation(this.getDestID(), z.getIntegerID());
				return;
			}
		}
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

	// Relocate vehicle
	/**
	 * @param p
	 */
	public void relocation(int orginID, int destinationID) {
		this.stopCruising();
		this.addPlan(destinationID, ContextCreator.getCityContext().findZoneWithIntegerID(destinationID).getCoord(),
				(int) RepastEssentials.GetTickCount());
		this.setNextPlan();
		// Add vehicle to newqueue of corresponding road
		this.departure();
		this.setState(Vehicle.RELOCATION_TRIP);
	}

	/**
	 * @param list of passengers
	 */
	public void servePassenger(List<Request> plist) {
		if (!plist.isEmpty()) {
			if(this.getState() == Vehicle.CRUISING_TRIP) {
				this.stopCruising();
				// Ask the vehicle to move back to serve the request
				this.addPlan(this.getDestID(),
						ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).getCoord(),
						(int) RepastEssentials.GetTickCount());
				this.setState(Vehicle.PICKUP_TRIP);
			}
			else {
				this.setState(Vehicle.OCCUPIED_TRIP);
			}
			
			for (Request p : plist) {
				this.addPlan(p.getDestination(),
						ContextCreator.getCityContext().findZoneWithIntegerID(p.getDestination()).getCoord(),
						(int) RepastEssentials.GetTickCount());
				this.served_pass += 1;
				this.setNumPeople(this.getNumPeople() + 1);
			}
			this.setNextPlan();
			// Add vehicle to new queue of corresponding road
			this.departure();
			
		}
	}
	
	public void setVehicleReachDest() {
		super.setReachDest();
	}

	@Override
	public void setReachDest() {
		// Check if the vehicle was on a charging route
		if (this.onChargingRoute_) {
			String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleID() + ",4,"
					+ this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
					+ this.getDepTime() + "," + this.getTripConsume() + ",-1" + "," + this.getNumPeople() + "\r\n";
			try {
				ContextCreator.ev_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
			super.setReachDest(); 
			super.leaveNetwork(); // remove from the network
			// Add to the charging station
			ContextCreator.logger.debug("Vehicle arriving at charging station:" + this.getId());
			ChargingStation cs = ContextCreator.getCityContext().findChargingStationWithID(this.getDestID());
			cs.receiveEV(this);
			this.tripConsume = 0;
		} else {
			// Log the trip consume here
			String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleID() + "," + this.getState()
					+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
					+ this.getDepTime() + "," + this.getTripConsume() + "," + this.getRouteChoice() + ","
					+ this.getNumPeople()+ "\r\n";
			try {
				ContextCreator.ev_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
			this.tripConsume = 0;

			Zone z = ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()); // get destination zone info
			
			super.setReachDest(); // Update the vehicle status
			// Decide the next step
			if (this.getState() == Vehicle.OCCUPIED_TRIP) {
				this.setNumPeople(this.getNumPeople() - 1); // passenger arrived
				z.taxiServedRequest += 1;
				// if pass need to take the bus to complete his or her trip
				if (this.passengerWithAdditionalActivityOnTaxi.size() > 0) {
					// generate a pass and add it to the corresponding zone
					Request p = this.passengerWithAdditionalActivityOnTaxi.poll();
					p.moveToNextActivity();
					if (z.busReachableZone.contains(p.getDestination())) {// if bus can reach the destination
						z.toAddRequestForBus.add(p);
					} else {
						// this happens when we dynamically update bus schedules
						z.toAddRequestForTaxi.add(p);
					}
				}
				
				z.removeFutureSupply();
				if (this.getNumPeople() > 0) { // keep serving more passengers
					super.leaveNetwork();
					this.setNextPlan();
					ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addFutureSupply();
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
		    				this.setState(Vehicle.PARKING);
		    				super.leaveNetwork();
					    }
		                else {
		                	z.addOneCrusingVehicle();
		                    this.setState(Vehicle.CRUISING_TRIP);
		                	// Select a neighboring link and cruise to there
		                	this.goCrusing(z);
		                	this.cruisingTime_ = 0;
		                }
					}
				}
			}
			else if(this.getState() == Vehicle.PICKUP_TRIP){
				this.setState(Vehicle.OCCUPIED_TRIP);
				super.leaveNetwork(); // Leave the network to pickup passengers
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
		                	z.addCruisingVehicleStock(-1);
		                	z.addOneParkingVehicle();
		    				this.setState(Vehicle.PARKING);
		    				super.leaveNetwork();
		    				this.cruisingTime_ = 0;
					    }
		                else {
		                	// Keep cruising
		                	this.cruisingTime_ += RepastEssentials.GetTickCount() - this.getDepTime();
		                	this.goCrusing(z);
		                }
					}
				}
				else {
					this.goParking();
					this.cruisingTime_ = 0;
				}
			}
			else if (this.getState() == Vehicle.RELOCATION_TRIP) {
				z.removeFutureSupply();
				if(batteryLevel_ <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
						&& batteryLevel_ <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(5))) {
					this.goCharging();
				}
				else { // join the current zone
					ContextCreator.getVehicleContext().getVehiclesByZone(z.getIntegerID()).add(this);
					if(z.getCapacity() > 0) { // Has capacity
	                	z.addOneParkingVehicle();
	    				this.setState(Vehicle.PARKING);
	    				super.leaveNetwork();
				    }
	                else {
	                	z.addOneCrusingVehicle();
	                    this.setState(Vehicle.CRUISING_TRIP);
	                	// Select a neighboring link and cruise to there
	                	this.goCrusing(z);
	                	this.cruisingTime_ = 0;
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
				+ GlobalVariables.RandomGenerator.nextDouble() * (1 - GlobalVariables.RECHARGE_LEVEL_LOW) * GlobalVariables.EV_BATTERY; // unit:kWh,
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
		// For Maia's model
//		double soc[] = {0.00, 0.10, 0.20, 0.40, 0.60, 0.80, 1.00};             
//		double r[] = {0.0419, 0.0288, 0.0221, 0.014, 0.0145, 0.0145, 0.0162}; 
//		PolynomialSplineFunction f = splineFit(soc,r);                         
//		this.splinef = f; 
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

	// New EV energy consumption model
	// Fiori, C., Ahn, K., & Rakha, H. A. (2016). Power-based electric vehicle
	// energy consumption model: Model development and validation. Applied Energy,
	// 168, 257�268.
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

	// Old EV energy consumption model:
	/*
	 * R. Maia, M. Silva, R. Arajo, and U. Nunes, Electric vehicle simulator for
	 * energy consumption studies in electric mobility systems, in 2011 IEEE Forum
	 * on Integrated and Sustainable Transportation Systems, 2011, pp.232.
	 */
	// Xue, Juan 20191212: calculate the energy for each vehicle per tick. return:
	// kWh.
//	public double calculateEnergy(){		
//		double velocity = currentSpeed();   // obtain the speed
//		double acceleration = currentAcc(); // obtain the acceleration
//		if(!this.movingFlag){
//			return 0;
//		}
//		double slope = 0.0f;          //positive: uphill; negative: downhill
//		double dt = GlobalVariables.SIMULATION_STEP_SIZE;   // this time interval. the length of one tick. 0.3
//		double currentSOC = Math.max(getBatteryLevel()/(this.batteryCapacity + 0.001), 0.001);     // currentSOC
//		// System.out.println("vehicle energy :"+splinef.value(currentSOC)+" "+currentSOC + " " + (getBatteryLevel()));
//		// System.out.println("currentSOC: " + currentSOC + " Battery Level: "+getBatteryLevel());	
//		//step 1: use the model: Fte = Frr + Fad + Fhc + Fla + Fwa. And Fwa = 0.
//		//mass_ = mass_ * 1.05;
//		double Frr = ElectricVehicle.urr * mass_ * ElectricVehicle.gravity;
//		//System.out.println("VID: "+ this.vehicleID_ + " urr: "+ GlobalVariables.urr + "mass_: " + mass_ + " gravity: " + GlobalVariables.gravity);
//		double Fad = 0.5 * ElectricVehicle.densityAir * ElectricVehicle.A * ElectricVehicle.Cd * velocity * velocity;
//		double Fhc = mass_ * ElectricVehicle.gravity * Math.sin(slope); //can be positive, can be negative  // mass loss // m = 1.05
//		double Fla = mass_ * acceleration;       //can be positive, can be negative
//		double Fte = Frr + Fad + Fhc + Fla;     //can be positive, can be negative
//		double Pte = Math.abs(Fte) * velocity;  //positive unit: W
//		
//		//step 2: two cases
//		double Pbat = 0.0f;
//		if (Fte >= 0){      //driven case
//			Pbat = (Pte+ElectricVehicle.Pconst)/ElectricVehicle.etaM/ElectricVehicle.etaG;		   //positive	
//		}else{              //regenerative case
//			Pbat = (Pte+ElectricVehicle.Pconst)*ElectricVehicle.etaM*ElectricVehicle.etaG;         //positive
//		}	
//		double rIn = splinef.value(currentSOC)*ElectricVehicle.c;          //c	
//		double Pmax = ElectricVehicle.Voc*ElectricVehicle.Voc/4.0/rIn;               //rIn
//		
//		//step 3: energy calculation
//		double kt = ElectricVehicle.k/1.03*(1.027 - 0.001122*ElectricVehicle.T + 1.586*Math.pow(10, -5*ElectricVehicle.T*ElectricVehicle.T));  //kt depends on T also.
//		double cpt = ElectricVehicle.cp/2.482*(2.482 + 0.0373*ElectricVehicle.T - 1.65*Math.pow(10, -4*ElectricVehicle.T*ElectricVehicle.T));  //cpt depends on T also. //real capacity: not 77 AH
//		
//		double CR = 0.0f;
//		double I = 0.0f;
//		if (Pbat > Pmax){ 
////			System.out.println("Error process, output error, need to recalculate vi"+Pbat+"," + Pmax+","+Fte+","+velocity+","+acceleration);
//			Pbat = Pmax;
//		}
//		
//        if(Pbat >= 0){  //driven case
//			I = (ElectricVehicle.Voc - Math.sqrt(ElectricVehicle.Voc*ElectricVehicle.Voc -4 * rIn * Pbat + 1e-6))/(2*rIn); // Prevent negative value by adding a tiny error
//			CR = Math.pow(I, kt)*dt;     //unit: AS                                       // I_kt??
//			//System.out.println("VID: "+ this.vehicleID_ + " Pte: "+ Pte + "Frr: " + Frr + " Fad: " + Fad + "Fhc: " + Fhc + "Fla: " + Fla +"V:" + velocity + "a: "+acceleration);
//			//System.out.println("VID: "+ this.vehicleID_ + " Pte: "+ Pte+ " batter level" + this.batteryLevel_ + " within sqrt:"+ Double.toString(GlobalVariables.Voc*GlobalVariables.Voc -4 * rIn * Pbat)); //LZX: Negative power!
//		}else{              //regenerative case
//			I = (0.0f - ElectricVehicle.Voc + Math.sqrt(ElectricVehicle.Voc*ElectricVehicle.Voc + 4 * rIn * Pbat + 1e-6))/(2*rIn);    // Prevent negative value by adding a tiny error
//			CR = -I * dt;    //unit: AS  //?
//		}
//		
//		double capacityConsumption = CR/(cpt*3600);   //unit: AH 
//		double energyConsumption = capacityConsumption * this.batteryCapacity;//ElectricVehicle.storedEnergy;  //unit: kWh
//		//System.out.println("ev energy :"+splinef.value(currentSOC)+" "+currentSOC);
////		if(Double.isNaN(energyConsumption)){
//////			System.out.println("v: "+ velocity + " acc: "+acceleration + " currentSOC: " + currentSOC);
//////			System.out.println("Frr: "+ Frr + " Fad: "+Fad + " Fhc: " + Fhc + " Fla: " + Fla);
//////			System.out.println("Fte: "+ Fte + " Pte: "+Pte + " Pbat: " + Pbat);
//////			System.out.println("rIn:"+ rIn + " Pmax: "+Pmax + " kt: " + kt);
//////			System.out.println("cpt:"+ cpt + " CR: "+CR + " I: " + I);
////			return -0.01;
////		}
//		if (energyConsumption > 0.1) {
//			System.out.println("v: " + velocity + " acc: " + acceleration + " currentSOC: " + currentSOC);
//			System.out.println("Frr: " + Frr + " Fad: " + Fad + " Fhc: " + Fhc + " Fla: " + Fla);
//			System.out.println("Fte: " + Fte + " Pte: " + Pte + " Pbat: " + Pbat);
//			System.out.println("rIn:" + rIn + " Pmax: " + Pmax + " kt: " + kt);
//			System.out.println("cpt:" + cpt + " CR: " + CR + " I: " + I);
//		}
//		return energyConsumption;
//	}

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

	public void finishCharging(Integer chargerID, String chargerType) {
		String formated_msg = RepastEssentials.GetTickCount() + "," + chargerID + "," + this.getVehicleID() + ","
				+ this.getVehicleClass() + "," + chargerType + "," + this.charging_waiting_time + ","
				+ this.charging_time + "," + this.initial_charging_state + "\r\n";
		try {
			ContextCreator.charger_logger.write(formated_msg);
			this.charging_waiting_time = 0;
			this.charging_time = 0;
		} catch (IOException e) {
			e.printStackTrace();
		}

		this.onChargingRoute_ = false;
		this.setState(Vehicle.RELOCATION_TRIP);
		this.setNextPlan();
		ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addFutureSupply();
		this.departure();
	}

	public double getLinkConsume() {
		return linkConsume;
	}

	// Reset link consume once a ev has passed a link
	public void resetLinkConsume() {
		this.linkConsume = 0;
	}

	public void recLinkSnaphotForUCB() {
		DataCollector.getInstance().recordLinkSnapshot(this.getRoad().getLinkid(), this.getLinkConsume());
	}

	public void recSpeedVehicle() {
		DataCollector.getInstance().recordSpeedVehilce(this.getRoad().getLinkid(), this.currentSpeed());
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
