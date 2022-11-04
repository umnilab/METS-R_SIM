package mets_r.mobility;

import java.io.IOException;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ChargingStation;
import mets_r.facility.ChargingStationWithAbandon;
import mets_r.mobility.Plan;
import mets_r.mobility.Request;
import mets_r.facility.Zone;
import repast.simphony.essentials.RepastEssentials;

/**
 * 
 * @author Xiaowei Chen
 *
 */

public class ElectricVehicleWithAbandon extends ElectricVehicle{
	// Local variables
	public int EV_ID; // the id of generated ev as order
	private boolean Initialshow = true;
	public double batteryRechargeprob = 0.2; // if the random variable is smaller than 0.3, then ev has the prob to recharge
	public int start_zone_id = 0;
	public int sortid_charging_station; // this indicates to find the order of distance sorted charging station near this ev
	public double utility_for_service = 0;
	private double valuation_driver;//ev's valuation of the trip
	public double chargingIntention = 0;
	
	@Override
	public void setInitialParams() {
		// added by xiaowei on 09/29
		super.setInitialParams();
		this.EV_ID = GlobalVariables.Random_ID_EV;
		GlobalVariables.Random_ID_EV +=1;
		System.out.println("EV_ID" + this.EV_ID + ", Initial_battery:" + this.batteryLevel_);
		//unit:kWh, full capacity times a random variable to give taxi SoC 
		this.valuation_driver =  GlobalVariables.VALUATION_EV_MEAN+
				GlobalVariables.VALUATION_EV_STD*GlobalVariables.RandomGenerator.nextGaussian();
		this.sortid_charging_station = 0; // should start from 0
		this.chargingIntention = GlobalVariables.RandomGenerator.nextDouble();
	}
	
	public ElectricVehicleWithAbandon(){
		super();
	}
	public ElectricVehicleWithAbandon(float maximumAcceleration, float maximumDeceleration) {
		super( maximumAcceleration, maximumDeceleration);
	}
	
	// Find the closest charging station and update the activity plan
	@Override
	public void goCharging(){
		super.setVehicleReachDest();
		int current_dest_zone = this.getDestID();
		Coordinate current_dest_coord = this.getDestCoord();
		// Add a charging activity
		ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord());
		
		if(cs == null) {
			if (this.batteryLevel_ > lowerBatteryRechargeLevel_) { // go back and provide services
				System.out.println("No available CS nearby");
				// set 12 = Vehicle cannot find charging station && battery is enough
				String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
						+ this.start_zone_id + "," + this.getVehicleID() + ",12," + this.getOriginID() + ","
						+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
						+ this.getTripConsume() + ",-1" + "," + this.getNumPeople()+"\r\n";
				try {
					ContextCreator.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
				this.onChargingRoute_ = false;
				this.sortid_charging_station = 0;
				
				ContextCreator.getVehicleContext().getVehiclesByZone(current_dest_zone).add(this);
				Zone z = ContextCreator.getCityContext().findZoneWithIntegerID(current_dest_zone);
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
			else { // leave the system
				System.out.println("Vehicle leaves the system since cannot find CSs.");
				// set 13 = Vehicle cannot find charging station && battery is low
				String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
						+ this.start_zone_id + "," + this.getVehicleID() + ",13," + this.getOriginID() + ","
						+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
						+ this.getTripConsume() + ",-1" + "," + this.getNumPeople()+"\r\n";
				try {
					ContextCreator.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
				
				super.leaveNetwork();
			}
		}
		else {
			this.onChargingRoute_ = true;
			this.setState(Vehicle.CHARGING_TRIP);
			this.addPlan(cs.getIntegerID(), cs.getCoord(), (int) RepastEssentials.GetTickCount());
			this.setNextPlan();
			this.addPlan(current_dest_zone, current_dest_coord, (int) RepastEssentials.GetTickCount());
			this.departure();
			//ContextCreator.logger.info("Vehicle "+ this.EV_ID+" is on route to charging" + ", and its SOC is  "+ this.batteryLevel_);
		}
	}
	
	// re-find the closest charging station and update the activity plan, due to meeting a full charging station
	public void refindCharging(int originID, int sortid_charging_station){ // added by xiaowei on 09/29
		Plan finalone = this.activityplan.get(1);
		int finalone_dest_zone = finalone.getDestID();
		Coordinate finalone_dest_coord = finalone.getLocation();
		this.activityplan.remove(0); //  remove current go charging schedule, now, the current destination schedule is at position 0
		this.onChargingRoute_ = true;
		this.sortid_charging_station +=1;
		// Find a list of sorted charging station based on distance, and find the id = sortid_charging_station one
		// since the ev is in the nearest charging station (sorted id =0), find the next nearest one, so the sortid should be 0.		
		ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord()); 
		if(cs == null) {
			if (this.batteryLevel_ > lowerBatteryRechargeLevel_) { // go back and provide services
				System.out.println("No available CS nearby");
				// set 12 = Vehicle cannot find charging station && battery is enough
				String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
						+ this.start_zone_id + "," + this.getVehicleID() + ",12," + this.getOriginID() + ","
						+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
						+ this.getTripConsume() + ",-1" + "," + this.getNumPeople()+"\r\n";
				try {
					ContextCreator.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
				this.onChargingRoute_ = false;
				this.sortid_charging_station = 0;
				this.setNextPlan();
				this.setState(Vehicle.RELOCATION_TRIP);
				ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addFutureSupply();
				this.departure();
			} 
			else { // leave the system
				System.out.println("Vehicle leaves the system due to three full CSs.");
				// set 13 = Vehicle cannot find charging station && battery is low
				String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
						+ this.start_zone_id + "," + this.getVehicleID() + ",13," + this.getOriginID() + ","
						+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
						+ this.getTripConsume() + ",-1" + "," + this.getNumPeople()+"\r\n";
				try {
					ContextCreator.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
				
				super.leaveNetwork();
			}
		}
		else {
			this.addPlan(cs.getIntegerID(), cs.getCoord(), (int) RepastEssentials.GetTickCount());
			this.setNextPlan();
			this.addPlan(finalone_dest_zone, finalone_dest_coord, (int) RepastEssentials.GetTickCount());
			this.departure();
			System.out.println("vid:"+this.getVehicleFakeID()+" activityplan size: " + this.activityplan.size());
			//ContextCreator.logger.info("Vehicle "+ this.getId()+" is on route to charging ( road : "+this.road.getLinkid()+")");
		}
	}	

	@Override
	public void setReachDest() {
		// Check if the vehicle was on a charging route
		if (this.onChargingRoute_) {
			super.setVehicleReachDest(); // remove from the network
			super.leaveNetwork();
			// Add to the charging station
			// if return_value ==0, the current charging station is full,
			// then if the batteryLevel_ > batteryRechargeLevel, ev stops searching for cs
			// and continue to provide service (which similar to the condition of finish
			// charging)
			// otherwise, the ev quits the entire system
			ChargingStationWithAbandon cs = (ChargingStationWithAbandon) ContextCreator.getCityContext().findChargingStationWithID(this.getDestID());
			boolean return_value = cs.tryToReceiveEV(this);
			if (this.sortid_charging_station < 3 && !return_value) { // if ev face with full cs less than 3 times, and
																		// the return_value==0, Do refindCharging
				String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
						+ this.start_zone_id + "," + this.getVehicleID() + ",10," + this.getOriginID() + ","
						+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
						+ this.getTripConsume() + ", charging station id" + cs.getIntegerID() + ","
						+ this.getNumPeople()+"\r\n";
				try {
					ContextCreator.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
				ContextCreator.logger.info("Vehicle" + this.getVehicleFakeID()
						+ " arrives at occupied charging station:" + cs.getIntegerID() + ", we assign next nearby cs");
				this.refindCharging(this.getDestID(), this.sortid_charging_station);
//				cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord(), 1); 
//				return_value = cs.receiveVehicle(this);
				System.out.println("Reassignment CS " + cs.getIntegerID() + " for EV " + this.getVehicleFakeID()
						+ ", and the return_value is " + return_value);
			} else if (this.sortid_charging_station >= 3 && !return_value) {
				if (this.batteryLevel_ > lowerBatteryRechargeLevel_) { // go back and provide services
					System.out.println("Three times for CSs & 0; ID: " + cs.getIntegerID());
					// set 8 = Vehicle wants to recharge, but meet full CSs for three times.
					String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
							+ this.start_zone_id + "," + this.getVehicleID() + ",8," + this.getOriginID() + ","
							+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
							+ this.getTripConsume() + ",-1" + "," + this.getNumPeople()+"\r\n";
					try {
						ContextCreator.ev_logger.write(formated_msg);
					} catch (IOException e) {
						e.printStackTrace();
					}
					this.onChargingRoute_ = false;
					this.sortid_charging_station = 0;
					this.setState(Vehicle.RELOCATION_TRIP);
					this.setNextPlan();
					ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addFutureSupply();
					this.departure();
				} 
				else { // leave the system
					System.out.println("Vehicle leaves the system due to three full CSs.");
					// set 6= Vehicle leaves system due to three full CSs
					String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
							+ this.start_zone_id + "," + this.getVehicleID() + ",11," + this.getOriginID() + ","
							+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
							+ this.getTripConsume() + ",-1" + "," + this.getNumPeople()+"\r\n";
					try {
						ContextCreator.ev_logger.write(formated_msg);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			} else if (return_value) {
				String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
						+ this.start_zone_id + "," + this.getVehicleID() + ",4," + this.getOriginID() + ","
						+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
						+ this.getTripConsume() + ",-1" + "," + this.getNumPeople()+"\r\n";
				try {
					ContextCreator.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}

				// this.onRoad = true;
				this.tripConsume = 0;

				if (this.sortid_charging_station > 0) {
					System.out.println("vid:" + this.getVehicleFakeID() + "; activityplan size: "
							+ this.activityplan.size() + "; sortid_charging_station:" + this.sortid_charging_station
							+ "; batteryLevel_:" + this.batteryLevel_);
				}
			}
		} else {
			// if ev is not on the charging route, then setNextPlan for ev
			String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
					+ this.start_zone_id + "," + this.getVehicleID() + "," + this.getState() + "," + this.getOriginID()
					+ "," + this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
					+ this.getTripConsume() + "," + this.getRouteChoice() + "," + this.getNumPeople()+"\r\n";
			try {
				ContextCreator.ev_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
			this.tripConsume = 0;
			
            Zone z = ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()); // get destination zone info
			
			super.setVehicleReachDest(); // Update the vehicle status
			// Decide the next step
			if (this.Initialshow && this.getNumPeople()==0) { // if ev first show with prob want to charge, then gocharging
				this.Initialshow = false;
				if (chargingIntention <= batteryRechargeprob) {
					this.goCharging();
				} else {
					ContextCreator.getVehicleContext().getVehiclesByZone(this.getDestID()).add(this);// append this
																										// vehicle
																										// to the
																										// available
																										// vehicle
																										// of given
																										// zones
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
			else if (this.getState() == Vehicle.OCCUPIED_TRIP) {
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
				System.out.println(this.getState());
				ContextCreator.logger.error("Vehicle does not belong to any of given states!");
			}
		}
	}
	
	@Override
	public void finishCharging(Integer chargerID, String chargerType){
		String formated_msg = RepastEssentials.GetTickCount()+ "," + chargerID + "," + this.getVehicleID() + "," + this.getVehicleClass()
		+ "," + chargerType + "," + this.charging_waiting_time + "," + this.charging_time + ","
		+ this.initial_charging_state + "," + this.sortid_charging_station;
		try {
			ContextCreator.charger_logger.write(formated_msg);
			ContextCreator.charger_logger.newLine();
			this.charging_waiting_time = 0;
			this.charging_time = 0;
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		double C_car = GlobalVariables.EV_BATTERY;
		this.utility_for_service = this.utility_for_service/(C_car - this.initial_charging_state);
		this.onChargingRoute_ = false;
		this.setState(Vehicle.RELOCATION_TRIP);
		this.sortid_charging_station =0;
		this.setNextPlan();
		ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addFutureSupply();
		this.departure();
	}
	
	public int getVehicleFakeID() {
		return this.EV_ID;
	}
	
	public double getValuationDriver(){
		return this.valuation_driver;
	}
	
	public int setStartZoneID(int start_zone_id){
		return this.start_zone_id = start_zone_id;
	}	
}

