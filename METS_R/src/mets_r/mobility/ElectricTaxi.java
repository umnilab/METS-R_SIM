package mets_r.mobility;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ChargingStation;
import mets_r.facility.Zone;

/**
 * Electric taxis
 * 
 * @author Zengxiang Lei, Jiawei Xue, Juan Suarez
 *
 */

public class ElectricTaxi extends ElectricVehicle {
	/* Local variables */
	private int passNum; // no of people inside the vehicle
	private double avgPersonMass_; // average mass of a person in lbs

	private int cruisingTime_;
	
	private Queue<Request> toBoardRequests;
	private Queue<Request> onBoardRequests;
	
	// For operational features
	private int currentZone;
	
	/* Public variables */
	// Service metrics
	public int servedPass = 0;
	// Parameter to show which route has been chosen in eco-routing.
	public int routeChoice = -1;

	public ElectricTaxi() {
		super(Vehicle.ETAXI, Vehicle.NONE_OF_THE_ABOVE);
		initializeEVFields(GlobalVariables.TAXI_BATTERY, 1521, 5000, GlobalVariables.TAXI_RECHARGE_LEVEL_LOW, GlobalVariables.TAXI_RECHARGE_LEVEL_HIGH);
		this.passNum = 0;
		this.cruisingTime_ = 0;
		this.avgPersonMass_ = 60; // kg
		this.toBoardRequests = new LinkedList<Request>();
		this.onBoardRequests = new LinkedList<Request>();
	}
	
	// Randomly select a neighboring link and update the activity plan
	public void goCruising(Zone z) {
		int dest = z.getNeighboringLink(rand_relocate_only.nextInt(z.getNeighboringLinkSize(true)), true);
		
	    // Add a cruising activity
		this.addPlan(z.getID(), dest, ContextCreator.getNextTick());
		this.setNextPlan();
		this.setState(Vehicle.CRUISING_TRIP);
		this.departure();
	}
	
	// Stop cruising
	public void stopCruising() {
		// Log the cruising trip here
		String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
		+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getOriginRoad() + "," + this.getDestRoad() + "," + this.getAccummulatedDistance() + ","
		+ this.getDepTime() + "," + this.getTripConsume() + "," + this.routeChoice + ","
		+ this.getPassNum()+ "\r\n";
		try {
			ContextCreator.agg_logger.ev_logger.write(formated_msg);
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.setState(Vehicle.NONE_OF_THE_ABOVE);
		this.tripConsume = 0;
		this.cruisingTime_ = 0;
		
	}
	
	// Find the closest Zone with parking space and relocate to there
	public void goParking() {
		ContextCreator.getZoneContext().get(this.getDestID()).submitParkingRequest(this);
	}
	
	// Find the cheapest charging station with specific charger type and update the activity plan
	@Override
	public void goCharging(int chargerType) {
		// Sanity check
		int current_dest_zone = this.getDestID();
		int current_dest_road = this.getDestRoad();
		if(current_dest_zone < 0) { // vehicle is heading to the charging station already
			ContextCreator.logger.warn("Vehicle " + this.getID() + " is already on route to charging.");
			return;
		}
		
		// Add a charging activity
		ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord(),
				chargerType);
		if(cs == null && chargerType == ChargingStation.L3) {
			cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord(),
						ChargingStation.L2);
		}
		if(cs != null) {
			this.onChargingRoute_ = true;
			this.setState(Vehicle.CHARGING_TRIP);
			this.addPlan(cs.getID(), cs.getClosestRoad(true), ContextCreator.getNextTick());
			this.setNextPlan();
			this.addPlan(current_dest_zone, current_dest_road, ContextCreator.getNextTick());
			this.departure();
			ContextCreator.logger.debug("Taxi " + this.getID() + " is on route to charging.");
		}
		else {
			ContextCreator.logger.warn("Taxi " + this.getID() + " cannot find charging station at coordinate: " + this.getCurrentCoord());
		}
	}
	
	// Parking at Zone z
	public void getParked(Zone z) {
		super.leaveNetwork();
		this.setState(Vehicle.PARKING);
	}

	// Relocate vehicle
	public void relocation(int destID, int destRoadID) {
		if(this.getState() == Vehicle.CRUISING_TRIP) {
			this.stopCruising();
		}
		this.addPlan(destID, destRoadID,
				ContextCreator.getNextTick());
		this.setNextPlan();
		this.setState(Vehicle.INACCESSIBLE_RELOCATION_TRIP);
		// Add vehicle to newqueue of corresponding road
		this.departure();
	}
	
	// Serve passengers in an order specified by a list
	public void servePassenger(List<Request> plist) {
		if (!plist.isEmpty()) {
			if(this.getState() == Vehicle.CRUISING_TRIP) {
				this.stopCruising();
			}
			// Dispatch the vehicle to serve the request
			for (Request p: plist) {
				this.toBoardRequests.add(p);
				this.addPlan(this.getOriginID(),
						p.getOriginRoad(),
						ContextCreator.getNextTick());
				this.servedPass += p.getNumPeople();
				this.passNum = this.getPassNum() + p.getNumPeople();
			}
			
			this.setState(Vehicle.PICKUP_TRIP);
			this.setNextPlan();
			// Add vehicle to new queue of corresponding road
			this.departure();
		}
	}

	@Override
	public void reachDest() {
		// Check if the vehicle was on a charging route
		if (this.onChargingRoute_ || this.getDestID() < 0) {
			String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + ",4,"
					+ this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
					+ this.getDepTime() + "," + this.getTripConsume() + ",-1" + "," + this.getPassNum() + "\r\n";
			try {
				ContextCreator.agg_logger.ev_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
			super.reachDestButNotLeave();
			super.leaveNetwork(); // remove from the network
			// Add to the charging station
			ChargingStation cs = ContextCreator.getChargingStationContext().get(this.getDestID());
			cs.receiveEV(this);
			this.tripConsume = 0;
		} else {
			// Log the trip consume here
			if(this.getAccummulatedDistance() > 0) {
				String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getState()
				+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getOriginRoad() + "," + this.getDestRoad() + "," + this.getAccummulatedDistance() + ","
				+ this.getDepTime() + "," + this.getTripConsume() + "," + this.routeChoice + ","
				+ this.getPassNum()+ "\r\n";
				try {
					ContextCreator.agg_logger.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
				this.tripConsume = 0;
			}
			
			Zone z = ContextCreator.getZoneContext().get(this.getDestID()); // get destination zone info
			this.setCurrentZone(z.getID());
			
			super.reachDestButNotLeave(); // Update the vehicle status
			
			// Decide the next step
			if (this.getState() == Vehicle.OCCUPIED_TRIP) {
				Request arrived_request = this.onBoardRequests.poll();
				this.passNum = this.passNum - arrived_request.getNumPeople(); // passenger arrived
				z.taxiServedRequest += 1;
				// if pass need to take the bus to complete his or her trip
				if(arrived_request.lenOfActivity() >= 2){
					// generate a pass and add it to the corresponding zone
					arrived_request.moveToNextActivity();
					if (ContextCreator.bus_schedule.isValid(arrived_request.getBusRoute())) {
						z.insertBusPass(arrived_request); // if bus can reach the destination
					} else {
						z.insertTaxiPass(arrived_request); // this is placed in case one would like to dynamically update bus schedules
					}
				} else {
					arrived_request.arriveTIme = ContextCreator.getCurrentTick();
					// Log the request information here
					String formated_msg = ContextCreator.getCurrentTick() + "," + arrived_request.getID() + ","
							+ arrived_request.getOriginZone() + "," + arrived_request.getDestZone() + ","
							+ arrived_request.getNumPeople() + "," + arrived_request.generationTime + ","
							+ arrived_request.matchedTime + "," + arrived_request.pickupTime + ","
							+ arrived_request.arriveTIme + "," + this.getID() + "," + this.getVehicleClass() + "\r\n";
					try {
						ContextCreator.agg_logger.request_logger.write(formated_msg);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}

				z.removeFutureSupply();
				if (this.onBoardRequests.size() > 0) { // keep serving more passengers
					// super.leaveNetwork();  // Leave network to drop-off passengers
					if(this.getPlan().size()<2) {
						this.addPlan(this.onBoardRequests.peek().getDestZone(), this.onBoardRequests.peek().getDestRoad(), ContextCreator.getNextTick());
					}
					this.setNextPlan();
					ContextCreator.getZoneContext().get(this.getDestID()).addFutureSupply();
					this.departure();
				}
				else { // charging or join the current zone
					if(this.batteryLevel <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
							&& this.batteryLevel <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(1))) {
						this.goCharging(ChargingStation.L3);
					}
					else { 
						// join the current zone
						if(z.getCapacity() > 0) { // Has capacity
							z.addOneParkingVehicle();
		                	this.getParked(z);
					    }
		                else {
		                	// Select a neighboring link and cruise to there
		                	this.goCruising(z);
		                }
						ContextCreator.getVehicleContext().addAvailableTaxi(this, z.getID());
					}
				}
			}
			else if(this.getState() == Vehicle.PICKUP_TRIP){
				// super.leaveNetwork(); // Leave network to pickup passengers
				// Assume no waiting time for picking up, modify this line if you want to count the waiting time
				Request pickedup_request = this.toBoardRequests.poll();
				pickedup_request.pickupTime = ContextCreator.getCurrentTick();
				this.onBoardRequests.add(pickedup_request);

				if(this.toBoardRequests.size() == 0) {
					this.setState(Vehicle.OCCUPIED_TRIP);
					this.addPlan(pickedup_request.getDestZone(),
							pickedup_request.getDestRoad(),
								ContextCreator.getNextTick());
				}
				if(this.getPlan().size()<2) {
					this.addPlan(this.toBoardRequests.peek().getOriginZone(), this.toBoardRequests.peek().getOriginRoad(), ContextCreator.getNextTick());
				}
				
				this.setNextPlan();
				this.departure();
			}
			else if (this.getState() == Vehicle.CRUISING_TRIP) {
				if(this.batteryLevel <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
						&& this.batteryLevel <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(1))) {
					ContextCreator.getVehicleContext().removeAvailableTaxi(this, z.getID());
					this.goCharging(ChargingStation.L3);
				}
				else {
					if(this.cruisingTime_ <= GlobalVariables.SIMULATION_RH_MAX_CRUISING_TIME) {
						if(z.getCapacity() > 0) { // Has capacity
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
					else {
						this.goParking();
					}
				}
				
			}
			else if (this.getState() == Vehicle.INACCESSIBLE_RELOCATION_TRIP || this.getState() == Vehicle.ACCESSIBLE_RELOCATION_TRIP || this.getState() == Vehicle.CHARGING_RETURN_TRIP) {
				if (this.getState() == Vehicle.ACCESSIBLE_RELOCATION_TRIP)
					ContextCreator.getVehicleContext().removeRelocationTaxi(this);
				z.removeFutureSupply();
				if(this.batteryLevel <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
						&& this.batteryLevel <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(5))) {
					this.goCharging(ChargingStation.L3);
				}
				else { // join the current zone
					if(z.getCapacity() > 0) { // Has capacity
	                	z.addOneParkingVehicle();
	    				this.getParked(z);
				    }
	                else {
	                	this.cruisingTime_ = 0;
	                	this.goCruising(z); // Select a neighboring link and cruise to there
	                }
					ContextCreator.getVehicleContext().addAvailableTaxi(this, z.getID());
				}
			}
			else {
				ContextCreator.logger.error("Vehicle does not belong to any of given states!");
			}
		}
	}

	public int getPassNum() {
		return passNum;
	}
	
	@Override
	public double getMass() {
		return mass + passNum * avgPersonMass_;
	}
	
	@Override
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
		if(this.getPlan().size() < 2) { // No where to go, head to the current Zone
			this.addPlan(this.getCurrentZone(), ContextCreator.getZoneContext().get(this.getCurrentZone()).getClosestRoad(true), ContextCreator.getNextTick());
		}
		
		this.setNextPlan(); // Return to where it was before goCharging
		
		this.setState(Vehicle.CHARGING_RETURN_TRIP);
		this.departure(ContextCreator.getChargingStationContext().get(chargerID).getClosestRoad(false)); 
		ContextCreator.getZoneContext().get(this.getDestID()).addFutureSupply();
	}
	
	@Override
	public int decideChargerType() {
		return ChargingStation.L3;
	}
	
	public int getCurrentZone() {
		return this.currentZone;
	}
	
	public void setCurrentZone(int currentZone) {
		this.currentZone = currentZone;
	}
	
	/* Getters and setters for save/load support */
	public int getCruisingTime() { return this.cruisingTime_; }
	public void setCruisingTime(int v) { this.cruisingTime_ = v; }
	public void setPassNum(int v) { this.passNum = v; }
	public Queue<Request> getToBoardRequests() { return this.toBoardRequests; }
	public Queue<Request> getOnBoardRequests() { return this.onBoardRequests; }
	public void setToBoardRequests(Queue<Request> q) { this.toBoardRequests = q; }
	public void setOnBoardRequests(Queue<Request> q) { this.onBoardRequests = q; }
	public int getServedPass() { return this.servedPass; }
	public void setServedPass(int v) { this.servedPass = v; }
}
