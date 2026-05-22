package mets_r.mobility;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Zone;

/**
 * Electric taxis
 * 
 * @author Zengxiang Lei, Jiawei Xue, Juan Suarez
 *
 */

public class ElectricTaxi extends ElectricVehicle {
	private static final int MAX_PASSENGERS = 4;

	/* Local variables */
	private int passNum; // no of people inside the vehicle
	private double avgPersonMass_; // average mass of a person in lbs

	private int cruisingTime_;
	
	private Queue<Request> toBoardRequests;
	private Queue<Request> onBoardRequests;
	
	// For operational features
	private int currentZone;
	
	/* Public variables */
	// Service metrics are cumulative for the vehicle.
	private int matchedRequests = 0;
	private int matchedPassengers = 0;
	private int pickupRequests = 0;
	private int pickupPassengers = 0;
	private int dropoffRequests = 0;
	private int dropoffPassengers = 0;
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
		+ this.getPassNum() + "," + this.getMatchedRequests() + "," + this.getMatchedPassengers() + ","
		+ this.getPickupRequests() + "," + this.getPickupPassengers() + ","
		+ this.getDropoffRequests() + "," + this.getDropoffPassengers() + "\r\n";
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

	public boolean goParking(int roadID) {
		Road road = ContextCreator.getRoadContext().get(roadID);
		return this.goParking(this.resolveParkingZoneForRoad(road), road);
	}

	public boolean goParking(Road road) {
		return this.goParking(this.resolveParkingZoneForRoad(road), road);
	}

	public boolean goParking(Zone targetZone, Road targetRoad) {
		if (targetZone == null || targetRoad == null || !targetRoad.canBeDest()) {
			return false;
		}
		if (this.getState() == Vehicle.PARKING && this.getCurrentParkingRoad() == targetRoad.getID()) {
			this.setCurrentZone(targetZone.getID());
			return true;
		}
		if (!targetRoad.tryAddParkedVehicle()) {
			return false;
		}

		int previousState = this.getState();
		Zone currentZone = ContextCreator.getZoneContext().get(this.getCurrentZone());
		int anchorZoneID = currentZone == null ? this.getCurrentZone() : currentZone.getID();
		int anchorRoadID = this.currentRoadAnchorID(currentZone, targetRoad);
		if (previousState == Vehicle.PARKING) {
			this.releaseParkingSpot(currentZone);
		} else {
			this.releaseRoadParkingSpot();
		}
		if (previousState == Vehicle.CRUISING_TRIP) {
			this.stopCruising();
		}

		ContextCreator.getVehicleContext().removeAvailableTaxiFromAllZones(this);
		ContextCreator.getVehicleContext().removeRelocationTaxiFromAllZones(this);
		this.ensureCurrentPlanAnchor(anchorZoneID, anchorRoadID);
		this.setCurrentZone(targetZone.getID());
		this.setCurrentParkingRoad(targetRoad.getID());
		this.addPlan(targetZone.getID(), targetRoad.getID(), ContextCreator.getNextTick());
		this.setNextPlan();
		this.setState(Vehicle.INACCESSIBLE_RELOCATION_TRIP);
		targetZone.addFutureSupply();
		this.departure();
		return true;
	}

	private int currentRoadAnchorID(Zone currentZone, Road fallbackRoad) {
		if (this.getDestRoad() >= 0) {
			return this.getDestRoad();
		}
		if (this.getRoad() != null) {
			return this.getRoad().getID();
		}
		if (this.getCurrentParkingRoad() >= 0) {
			return this.getCurrentParkingRoad();
		}
		if (currentZone != null && currentZone.getClosestRoad(false) != null) {
			return currentZone.getClosestRoad(false);
		}
		return fallbackRoad == null ? -1 : fallbackRoad.getID();
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
		if (z != null) {
			this.setCurrentZone(z.getID());
		}
		this.setCurrentParkingRoad(-1);
		super.leaveNetwork();
		this.setState(Vehicle.PARKING);
	}

	@Override
	protected void onParkedOnRoad(Road road, int zoneID) {
		if (ContextCreator.getZoneContext().get(zoneID) != null) {
			this.setCurrentZone(zoneID);
		}
	}

	public void releaseParkingSpot(Zone fallbackZone) {
		if (!this.releaseRoadParkingSpot() && fallbackZone != null) {
			fallbackZone.removeOneParkingVehicle();
		}
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
			this.ensureCurrentPlanAnchor(plist.get(0));
			// Dispatch the vehicle to serve the request
			for (Request p: plist) {
				this.toBoardRequests.add(p);
				this.addPlan(p.getOriginZone(),
						p.getOriginRoad(),
						ContextCreator.getNextTick());
				this.recordPassengerMatch(p);
				this.passNum = this.getPassNum() + p.getNumPeople();
			}
			
			this.setState(Vehicle.PICKUP_TRIP);
			this.setNextPlan();
			// Add vehicle to new queue of corresponding road
			this.departure();
		}
	}

	private void ensureCurrentPlanAnchor(Request request) {
		int fallbackZoneID = request == null ? -1 : request.getOriginZone();
		int fallbackRoadID = request == null ? -1 : request.getOriginRoad();
		this.ensureCurrentPlanAnchor(fallbackZoneID, fallbackRoadID);
	}

	private void ensureCurrentPlanAnchor(int fallbackZoneID, int fallbackRoadID) {
		if (!this.getPlan().isEmpty()) {
			return;
		}
		int zoneID = this.getCurrentZone() >= 0 ? this.getCurrentZone() : this.getDestID();
		if (zoneID < 0) {
			zoneID = fallbackZoneID;
		}
		int roadID = this.getDestRoad();
		if (roadID < 0 && this.getRoad() != null) {
			roadID = this.getRoad().getID();
		}
		if (roadID < 0 && this.getCurrentParkingRoad() >= 0) {
			roadID = this.getCurrentParkingRoad();
		}
		if (roadID < 0) {
			roadID = fallbackRoadID;
		}
		this.setOriginID(zoneID);
		this.setDestID(zoneID);
		if (roadID >= 0) {
			Road road = ContextCreator.getRoadContext().get(roadID);
			this.setOriginRoad(road);
			this.setDestRoad(road);
			this.addPlan(zoneID, roadID, ContextCreator.getNextTick());
		}
	}

	public int remainingCapacity() {
		return Math.max(0, MAX_PASSENGERS - this.passNum);
	}

	public boolean hasPassengerAssignments() {
		return !this.toBoardRequests.isEmpty() || !this.onBoardRequests.isEmpty();
	}

	public void queuePassengerAfterCurrentTrip(Request request) {
		if (request == null) {
			return;
		}
		if (this.getState() == Vehicle.PICKUP_TRIP && !this.toBoardRequests.isEmpty()) {
			insertToBoardRequest(request, 1);
			int insertIndex = Math.min(1, this.getPlan().size());
			this.getPlan().add(insertIndex, new Plan(request.getOriginZone(), request.getOriginRoad(),
					ContextCreator.getNextTick()));
		} else {
			this.toBoardRequests.add(request);
		}
		this.recordPassengerMatch(request);
		this.passNum += request.getNumPeople();
	}

	public boolean startQueuedPickupTrip(boolean addFutureSupply) {
		Request nextPickup = this.toBoardRequests.peek();
		if (nextPickup == null) {
			return false;
		}
		if (addFutureSupply) {
			Zone destZone = ContextCreator.getZoneContext().get(nextPickup.getDestZone());
			if (destZone != null) {
				destZone.addFutureSupply();
			}
		}
		if (this.getPlan().size() < 2) {
			this.addPlan(nextPickup.getOriginZone(), nextPickup.getOriginRoad(),
					ContextCreator.getNextTick());
		}
		this.setState(Vehicle.PICKUP_TRIP);
		this.setNextPlan();
		this.departure();
		return true;
	}

	private boolean shouldWaitForExternalDispatchControl() {
		return GlobalVariables.DISPATCHING_CONTROLLED_BY_CONTROL_APIS
				|| GlobalVariables.REPOSITIONING_CONTROLLED_BY_CONTROL_APIS;
	}

	private boolean shouldWaitForExternalRepositioningControl() {
		return GlobalVariables.REPOSITIONING_CONTROLLED_BY_CONTROL_APIS;
	}

	private void becomeAvailableForExternalControl(Zone z) {
		if (z == null) {
			return;
		}
		int idleRoadID = this.resolveIdleRoadID(z);
		Road idleRoad = idleRoadID >= 0 ? ContextCreator.getRoadContext().get(idleRoadID) : null;

		ContextCreator.getVehicleContext().removeAvailableTaxiFromAllZones(this);
		ContextCreator.getVehicleContext().removeRelocationTaxiFromAllZones(this);
		this.setCurrentZone(z.getID());
		this.setCurrentParkingRoad(-1);
		this.cruisingTime_ = 0;
		this.setState(Vehicle.NONE_OF_THE_ABOVE);
		this.setOriginID(z.getID());
		this.setDestID(z.getID());
		this.getPlan().clear();
		if (idleRoad != null) {
			this.setOriginRoad(idleRoad);
			this.setDestRoad(idleRoad);
			this.addPlan(z.getID(), idleRoad.getID(), ContextCreator.getNextTick());
		}
		ContextCreator.getVehicleContext().addAvailableTaxi(this, z.getID());
	}

	private Zone resolveParkingZoneForRoad(Road road) {
		if (road == null) {
			return null;
		}
		Zone zone = ContextCreator.getZoneContext().get(road.getNeighboringZone(true));
		if (zone != null) {
			return zone;
		}
		return ContextCreator.getZoneContext().get(road.getNeighboringZone(false));
	}

	private boolean hasReservedParkingAtDestination() {
		int parkingRoadID = this.getCurrentParkingRoad();
		return parkingRoadID >= 0 && parkingRoadID == this.getDestRoad();
	}

	public boolean isGoingToReservedParking() {
		return this.getState() == Vehicle.INACCESSIBLE_RELOCATION_TRIP
				&& this.hasReservedParkingAtDestination();
	}

	private boolean completeReservedRoadParking(Zone fallbackZone) {
		Road parkingRoad = ContextCreator.getRoadContext().get(this.getCurrentParkingRoad());
		if (parkingRoad == null) {
			return false;
		}
		this.onParkedOnRoad(parkingRoad, fallbackZone == null ? -1 : fallbackZone.getID());
		this.leaveNetwork();
		this.setState(Vehicle.PARKING);
		Zone parkingZone = ContextCreator.getZoneContext().get(this.getCurrentZone());
		if (parkingZone == null) {
			parkingZone = fallbackZone;
		}
		if (parkingZone != null) {
			ContextCreator.getVehicleContext().addAvailableTaxi(this, parkingZone.getID());
		}
		return true;
	}

	private int resolveIdleRoadID(Zone z) {
		int idleRoadID = this.getDestRoad();
		if (idleRoadID >= 0) {
			return idleRoadID;
		}
		Integer arrivalRoad = z.getClosestRoad(true);
		if (arrivalRoad != null) {
			return arrivalRoad;
		}
		Integer departureRoad = z.getClosestRoad(false);
		return departureRoad == null ? -1 : departureRoad;
	}

	private void insertToBoardRequest(Request request, int index) {
		LinkedList<Request> updatedRequests = new LinkedList<Request>();
		int position = 0;
		boolean inserted = false;
		while (!this.toBoardRequests.isEmpty()) {
			if (!inserted && position == index) {
				updatedRequests.add(request);
				inserted = true;
			}
			updatedRequests.add(this.toBoardRequests.poll());
			position++;
		}
		if (!inserted) {
			updatedRequests.add(request);
		}
		this.toBoardRequests = updatedRequests;
	}

	@Override
	public void reachDest() {
		// Check if the vehicle was on a charging route
		if (this.onChargingRoute_ || this.getDestID() < 0) {
			String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + ",4,"
					+ this.getOriginID() + "," + this.getDestID() + "," + this.getOriginRoad() + ","
					+ this.getDestRoad() + "," + this.getAccummulatedDistance() + ","
					+ this.getDepTime() + "," + this.getTripConsume() + ",-1" + "," + this.getPassNum() + ","
					+ this.getMatchedRequests() + "," + this.getMatchedPassengers() + ","
					+ this.getPickupRequests() + "," + this.getPickupPassengers() + ","
					+ this.getDropoffRequests() + "," + this.getDropoffPassengers() + "\r\n";
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
				+ this.getPassNum() + "," + this.getMatchedRequests() + "," + this.getMatchedPassengers() + ","
				+ this.getPickupRequests() + "," + this.getPickupPassengers() + ","
				+ this.getDropoffRequests() + "," + this.getDropoffPassengers() + "\r\n";
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
				this.recordPassengerDropoff(arrived_request);
				z.taxiServedRequest += 1;
				z.taxiServedPassengers += arrived_request.getNumPeople();
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
				else if (this.startQueuedPickupTrip(true)) {
					// The newly queued request becomes active after the current drop-off.
				}
				else { // charging or join the current zone
					// Built-in charging trigger is bypassed when CHARGING is
					// controlled by an external Control API.
					if((this.batteryLevel <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
							&& this.batteryLevel <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(1)))
							&& !GlobalVariables.CHARGING_CONTROLLED_BY_CONTROL_APIS) {
						this.goCharging(ChargingStation.L3);
					}
					else if (this.shouldWaitForExternalDispatchControl()) {
						this.becomeAvailableForExternalControl(z);
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
				this.recordPassengerPickup(pickedup_request);
				Zone pickupZone = ContextCreator.getZoneContext().get(pickedup_request.getOriginZone());
				if (pickupZone != null) {
					pickupZone.taxiPickedUpRequest += 1;
					pickupZone.taxiPickedUpPassengers += pickedup_request.getNumPeople();
				}
				this.onBoardRequests.add(pickedup_request);

				if(this.toBoardRequests.size() == 0) {
					this.setState(Vehicle.OCCUPIED_TRIP);
					Request nextDropoff = this.onBoardRequests.peek();
					if (nextDropoff != null) {
						this.addPlan(nextDropoff.getDestZone(),
								nextDropoff.getDestRoad(),
									ContextCreator.getNextTick());
					}
				}
				if(this.getPlan().size()<2 && this.toBoardRequests.peek() != null) {
					this.addPlan(this.toBoardRequests.peek().getOriginZone(), this.toBoardRequests.peek().getOriginRoad(), ContextCreator.getNextTick());
				}
				
				this.setNextPlan();
				this.departure();
			}
			else if (this.getState() == Vehicle.CRUISING_TRIP) {
				// Built-in charging trigger is bypassed when CHARGING is
				// controlled by an external Control API. Parking/cruising at
				// the end of a sim-initiated cruise remains sim-controlled.
				if (this.startQueuedPickupTrip(true)) {
					// Continue with the queued assignment after the current cruise leg.
				}
				else if((this.batteryLevel <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
						&& this.batteryLevel <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(1)))
						&& !GlobalVariables.CHARGING_CONTROLLED_BY_CONTROL_APIS) {
					ContextCreator.getVehicleContext().removeAvailableTaxi(this, z.getID());
					this.goCharging(ChargingStation.L3);
				}
				else if (this.shouldWaitForExternalRepositioningControl()) {
					this.becomeAvailableForExternalControl(z);
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
				if (this.hasReservedParkingAtDestination()) {
					if (this.toBoardRequests.peek() != null) {
						this.releaseRoadParkingSpot();
						this.startQueuedPickupTrip(true);
					} else {
						this.completeReservedRoadParking(z);
					}
					return;
				}
				if (this.startQueuedPickupTrip(true)) {
					return;
				}
				// Built-in charging trigger is bypassed when CHARGING is
				// controlled by an external Control API. Parking/cruising at
				// the end of a sim-initiated relocation/charging-return trip
				// remains sim-controlled.
				if((this.batteryLevel <= lowerBatteryRechargeLevel_ || (GlobalVariables.PROACTIVE_CHARGING
						&& this.batteryLevel <= higherBatteryRechargeLevel_ && z.hasEnoughTaxi(5)))
						&& !GlobalVariables.CHARGING_CONTROLLED_BY_CONTROL_APIS) {
					this.goCharging(ChargingStation.L3);
				}
				else if (this.shouldWaitForExternalRepositioningControl()) {
					this.becomeAvailableForExternalControl(z);
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

	private void recordPassengerPickup(Request request) {
		if (request == null) {
			return;
		}
		this.pickupRequests += 1;
		this.pickupPassengers += request.getNumPeople();
	}

	private void recordPassengerMatch(Request request) {
		if (request == null) {
			return;
		}
		this.matchedRequests += 1;
		this.matchedPassengers += request.getNumPeople();
	}

	private void recordPassengerDropoff(Request request) {
		if (request == null) {
			return;
		}
		this.dropoffRequests += 1;
		this.dropoffPassengers += request.getNumPeople();
	}
	
	/* Getters and setters for save/load support */
	public int getCruisingTime() { return this.cruisingTime_; }
	public void setCruisingTime(int v) { this.cruisingTime_ = v; }
	public void setPassNum(int v) { this.passNum = v; }
	public Queue<Request> getToBoardRequests() { return this.toBoardRequests; }
	public Queue<Request> getOnBoardRequests() { return this.onBoardRequests; }
	public void setToBoardRequests(Queue<Request> q) { this.toBoardRequests = q == null ? new LinkedList<Request>() : q; }
	public void setOnBoardRequests(Queue<Request> q) { this.onBoardRequests = q == null ? new LinkedList<Request>() : q; }
	public int getMatchedRequests() { return this.matchedRequests; }
	public void setMatchedRequests(int v) { this.matchedRequests = v; }
	public int getMatchedPassengers() { return this.matchedPassengers; }
	public void setMatchedPassengers(int v) { this.matchedPassengers = v; }
	public int getPickupRequests() { return this.pickupRequests; }
	public void setPickupRequests(int v) { this.pickupRequests = v; }
	public int getPickupPassengers() { return this.pickupPassengers; }
	public void setPickupPassengers(int v) { this.pickupPassengers = v; }
	public int getDropoffRequests() { return this.dropoffRequests; }
	public void setDropoffRequests(int v) { this.dropoffRequests = v; }
	public int getDropoffPassengers() { return this.dropoffPassengers; }
	public void setDropoffPassengers(int v) { this.dropoffPassengers = v; }
}
