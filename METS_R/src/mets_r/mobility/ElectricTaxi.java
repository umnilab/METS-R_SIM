package mets_r.mobility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
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

public class ElectricTaxi extends ElectricVehicle {
	/* Local variables */
	private int passNum; // no of people inside the vehicle
	private double avgPersonMass_; // average mass of a person in lbs

	private int cruisingTime_;
	
	private Queue<Request> toBoardRequests;
	private Queue<Request> onBoardRequests;
	
	/* Public variables */
	// For operational features
	public int currentZone;
	// Service metrics
	public int servedPass = 0;
	// Parameter to show which route has been chosen in eco-routing.
	public int routeChoice = -1;

	public ElectricTaxi(boolean vSensor) {
		super(Vehicle.ETAXI, vSensor?Vehicle.CV2X:Vehicle.NONE_OF_THE_ABOVE);
		this.passNum = 0;
		this.cruisingTime_ = 0;
		this.avgPersonMass_ = 60; // kg
		
		this.toBoardRequests = new LinkedList<Request>();
		this.onBoardRequests = new LinkedList<Request>();
	}
	
	// Randomly select a neighboring link and update the activity plan
	public void goCruising(Zone z) {
		int dest = z.getNeighboringLink(rand_relocate_only.nextInt(z.getNeighboringLinkSize(true)), true);
		
		if(z.getNeighboringLinkSize(true) == 1) { // Isolated zone
			// Sample from the neighboring zone
			while(dest == this.getDestRoadID()) {
				int neighboringZoneID = z.getNeighboringZones(rand_relocate_only.nextInt(z.getNeighboringZoneSize()));
				dest = ContextCreator.getZoneContext().get(neighboringZoneID).getNeighboringLink(rand_relocate_only.nextInt(ContextCreator.getZoneContext().get(neighboringZoneID).getNeighboringLinkSize(true)), true);
			}
		}
		else {
			while(dest == this.getDestRoadID()) { // Sample again
				dest = z.getNeighboringLink(rand_relocate_only.nextInt(z.getNeighboringLinkSize(true)), true);
			}
		}
		
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
		+ "," + this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
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

	// Relocate vehicle
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
	
	// Serve passengers in an order specified by a list, assuming all passengers are picked at the same place
	public void servePassenger(List<Request> plist) {
		if (!plist.isEmpty()) {
			if(this.getState() == Vehicle.CRUISING_TRIP) {
				this.stopCruising();
			}
			// Dispatch the vehicle to serve the request
			for (Request p: plist) {
				this.toBoardRequests.add(p);
				this.addPlan(this.getOriginID(),
						p.getOriginCoord(),
						ContextCreator.getNextTick());
				this.servedPass += p.getNumPeople();
				this.passNum = this.getPassNum() + p.getNumPeople();
			}
			
			this.setNextPlan();
			this.setState(Vehicle.PICKUP_TRIP);
			// Add vehicle to new queue of corresponding road
			this.departure();
			
		}
	}
	
	@Override
	public void setNextRoad() {
		this.atOrigin = false;
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
				this.nextRoad_ = null;
			}
			else if (this.roadPath.size() < 2) { // The origin and destination share the same Junction
				this.nextRoad_ = null;
			} else {
				this.nextRoad_ = roadPath.get(1);
				this.assignNextLane();
			}
		}
	}
	
	@Override
	public void appendToRoad(Road road) {
		if(this.getState() == Vehicle.ACCESSIBLE_RELOCATION_TRIP && 
				road.getNeighboringZone(true)!=-1 && 
				this.currentZone != road.getNeighboringZone(true)) {
			this.currentZone = road.getNeighboringZone(true);
			ContextCreator.getVehicleContext().addRelocationTaxi(this, road.getNeighboringZone(true));
		}
		super.appendToRoad(road);
	}

	@Override
	public void reachDest() {
		// Check if the vehicle was on a charging route
		if (this.onChargingRoute_) {
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
				+ this.getPassNum()+ "\r\n";
				try {
					ContextCreator.agg_logger.ev_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
				this.tripConsume = 0;
			}

			Zone z = ContextCreator.getZoneContext().get(this.getDestID()); // get destination zone info
			
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
					if (z.busReachableZone.contains(arrived_request.getDestZone())) {
						z.insertBusPass(arrived_request); // if bus can reach the destination
					} else {
						z.insertTaxiPass(arrived_request); // this is called when we dynamically update bus schedules
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
						ContextCreator.getVehicleContext().getVehiclesByZone(z.getID()).add(this);
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
				// Assume no waiting time for picking up, modify this line if you want to count the waiting time
				Request pickedup_request = this.toBoardRequests.poll();
				pickedup_request.pickupTime = ContextCreator.getCurrentTick();
				this.onBoardRequests.add(pickedup_request);
				this.addPlan(pickedup_request.getDestZone(),
						pickedup_request.getDestCoord(),
							ContextCreator.getNextTick());
				if(this.toBoardRequests.size() == 0) {
					this.setState(Vehicle.OCCUPIED_TRIP);
				}
				this.setNextPlan();
				this.departure();
			}
			else if (this.getState() == Vehicle.CRUISING_TRIP) {
				if(this.cruisingTime_ <= GlobalVariables.SIMULATION_RH_MAX_CRUISING_TIME) {
					if(batteryLevel_ <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
							&& batteryLevel_ <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(5))) {
						ContextCreator.getVehicleContext().getVehiclesByZone(z.getID()).remove(this);
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
					ContextCreator.getVehicleContext().getVehiclesByZone(z.getID()).add(this);
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

	public int getPassNum() {
		return passNum;
	}
	
	@Override
	public double getMass() {
		return 1.05 * mass + passNum * avgPersonMass_;
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
		this.setNextPlan(); // Return to where it was before goCharging
		ContextCreator.getZoneContext().get(this.getDestID()).addFutureSupply();
		this.setState(Vehicle.INACCESSIBLE_RELOCATION_TRIP);
		this.departure(); 
	}
}
