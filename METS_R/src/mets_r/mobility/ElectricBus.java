package mets_r.mobility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.OneBusSchedule;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Zone;

/**
 * Electric buses
 * 
 * @author Zengxiang Lei, Jiawei Xue
 */

public class ElectricBus extends ElectricVehicle {
	/* Constants */
	// Parameters for Fiori (2016) model,
	// Modified frontal area and draft coefficient according to Bus 2 in the paper
	// "Aerodynamic Exterior Body Design of Bus"
	public static double A = 6.93; // frontal area of the vehicle
	public static double cd = 0.68; // draft coefficient
	public static double Pconst = 5500; // energy consumption by auxiliary accessories
	
	/* Local variables */
	private int routeID;
	private int passNum;
	private int numSeat; // Capacity of the bus.
	private int nextStop; // nextStop =i means the i-th entry of ArrayList<Integer> toVisitZone.
	
	private ArrayList<Integer> stopZones;
	private ArrayList<Road> stopRoads;
	private ArrayList<List<Road>> roadsBwStops;
	// Each entry represents a bus stop (zone) along the bus route.
	// For instance, it is [0,1,2,3,4,5,6,7,8,9,0];
	private HashMap<Integer, Integer> zoneStops; // Reverse table for track the zone in the busStop;
	
	// Timetable variable here, and the next departure time
	private ArrayList<Integer> departureTime;
	
	
	private ArrayList<Queue<Request>> toBoardRequests; // The pickup stop array
	private ArrayList<Queue<Request>> onBoardRequests; // The drop-off stop array
	
	// [x0,x1,x2,x3,x4,x5,x6,x7,x8,x9]. xi means that there are xi passengers having
	// the destination of zone i.
	private double avgPersonMass_; // average mass of a person in lbs
	private double mass; // mass of the vehicle in kg
	
	/* Public variables */
	// Service metrics
	public int served_pass = 0;
	
	// Parameter to show which route has been chosen in eco-routing.
	private int routeChoice = -1;
	

	// Constructor
	public ElectricBus(int routeID, ArrayList<Integer> route, ArrayList<Integer> departureTime) {
		super(1.2, -2.0, Vehicle.EBUS, Vehicle.NONE_OF_THE_ABOVE); // max acc, min dc, and vehicle class
		initializeEVFields(GlobalVariables.BUS_BATTERY, 18000, 666, GlobalVariables.BUS_RECHARGE_LEVEL_LOW, GlobalVariables.BUS_RECHARGE_LEVEL_HIGH);
		this.routeID = routeID;
		this.stopZones = route;
		this.zoneStops = new HashMap<Integer, Integer>();
		for (int i = 0; i < route.size(); i++) {
			zoneStops.put(route.get(i), i);
		}
		this.departureTime = new ArrayList<Integer>();
		this.toBoardRequests = new ArrayList<Queue<Request>>();
		for (int i = 0; i < route.size(); i++) {
			this.toBoardRequests.add(new LinkedList<Request>());
		}
		this.onBoardRequests = new ArrayList<Queue<Request>>();
		for (int i = 0; i < route.size(); i++) {
			this.onBoardRequests.add(new LinkedList<Request>());
		}
		this.passNum = 0;
		this.nextStop = Math.min(1, this.stopZones.size() - 1);
		this.numSeat = 40;
		this.avgPersonMass_ = 60.0;
	}

	// UpdateBatteryLevel
	@Override
	public void updateBatteryLevel() {
		if (this.routeID >= 0) {
			double tickEnergy = calculateEnergy(); // the energy consumption(kWh) for this tick
			tickConsume = tickEnergy;
			totalConsume += tickEnergy;
			linkConsume += tickEnergy;
			tripConsume += tickEnergy;
			batteryLevel -= tickEnergy;
		}
	}
	
	@Override
	public int decideChargerType() {
		return ChargingStation.BUS;
	}
	
	@Override
	// Find the closest charging station with specific charger type and update the activity plan
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
			ContextCreator.logger.debug("Vehicle " + this.getID() + " is on route to charging.");
		}
		
		else {
			ContextCreator.logger.warn("Vehicle " + this.getID() + " cannot find charging station at coordinate: " + this.getCurrentCoord());
		}
	}
	
	// The setReachDest() function applies for three cases:
	// Case 1: arrive at the charging station.
	// Case 2: arrive at the start bus stop, and then go the charging station
	// Case 3: (arrive at the other bus stop), or (arrive at the start bus stop and
	// continue to move)
	@Override
	public void reachDest() {
		if (onChargingRoute_) {
			String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + "," + this.getRouteID()
					+ ",4," + this.getOriginID() + "," + this.getDestID() + "," + this.getAccummulatedDistance() + ","
					+ this.getDepTime() + "," + this.getTripConsume() + ",-1" + "," + this.getPassNum() + "\r\n";
			try {
				ContextCreator.agg_logger.bus_logger.write(formated_msg);
			} catch (IOException e) {
				e.printStackTrace();
			}
			super.reachDestButNotLeave(); 
			super.leaveNetwork(); // remove the bus from the network
			ContextCreator.logger.debug("Bus arriving at charging station:" + this.getID());
			ChargingStation cs = ContextCreator.getChargingStationContext().get(this.getDestID());
			cs.receiveBus(this);
			this.tripConsume = 0;
		} else {
			// drop off passengers at the stop
			if (this.getRouteID() > 0) {
				String formated_msg = ContextCreator.getCurrentTick() + "," + this.getID() + ","
						+ this.getRouteID() + ",3," + this.getOriginID() + "," + this.getDestID() + ","
						+ this.getAccummulatedDistance() + "," + this.getDepTime() + "," + this.getTripConsume() + ","
						+ this.routeChoice + "," + this.getPassNum() + "\r\n";
				try {
					ContextCreator.agg_logger.bus_logger.write(formated_msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			this.tripConsume = 0;
			ContextCreator.logger.debug("Bus arriving at bus stop: " + nextStop);
	
			// Passenger drop off
			int delay = this.dropOffPassenger(nextStop % this.stopZones.size());
			super.reachDestButNotLeave(); // Update the vehicle status
			// Decide the next step
			if (nextStop == stopZones.size() || this.routeID == -1) { // arrive at the last stop
				if(this.routeID != -1) ContextCreator.bus_schedule.finishSchedule(this.routeID);
				this.routeID = -1; // Clear the previous route ID
				for(Queue<Request> ps: this.toBoardRequests) {
					for(Request unserved_pass: ps) {
						String formated_msg = ContextCreator.getCurrentTick() + "," + unserved_pass.getID() + ","
								+ unserved_pass.getOriginZone() + "," + unserved_pass.getDestZone() + ","
								+ unserved_pass.getNumPeople() + "," + unserved_pass.generationTime + ","
								+ unserved_pass.matchedTime + "," + -1 + ","
								+ -1 + "," + this.getID() + "," + this.getVehicleClass() + "\r\n";
						try {
							ContextCreator.agg_logger.request_logger.write(formated_msg);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
				
				if (batteryLevel <= lowerBatteryRechargeLevel_) {
					this.goCharging(ChargingStation.BUS);
				} else if (GlobalVariables.PROACTIVE_CHARGING && batteryLevel <= higherBatteryRechargeLevel_
						&& !ContextCreator.bus_schedule.hasSchedule(this.stopZones.get(0))) {
					this.goCharging(ChargingStation.BUS);
				} else {
					ContextCreator.bus_schedule.popSchedule(this.stopZones.get(0), this);
					super.leaveNetwork();
					this.addPlan(stopZones.get(nextStop),
							stopRoads.get(nextStop).getID(),
							Math.max((int) ContextCreator.getNextTick() + delay, departureTime.get(nextStop)));
					this.setNextPlan();
					this.departure();
				}
			} else {
				// Passengers get on board
				Zone arrivedZone = ContextCreator.getZoneContext().get(this.stopZones.get(this.nextStop));
				delay = Math.max(arrivedZone.servePassengerByBus(this), delay);
				for(Request p: this.toBoardRequests.get(this.nextStop)) {
					this.pickUpPassenger(p);
				}
				
				this.nextStop ++;
				
				// Head to the next Stop
				int destZoneID = stopZones.get(nextStop % stopZones.size());
				Road destRoad = stopRoads.get(nextStop % stopZones.size());
				this.addPlan(destZoneID, destRoad.getID(),
						Math.max((int) ContextCreator.getNextTick() + delay, departureTime.get(nextStop-1)));
				this.setNextPlan();
				this.departure();
				if(roadsBwStops != null) {
					List<Road> path = roadsBwStops.get(nextStop % stopZones.size());
					if(path != null)
						this.updateRoute(path);
				}
				
			}
			ContextCreator.logger.debug("Bus " + this.ID + " has arrive the next station: " + nextStop);
		}
	}
	
	
	// To add delay in boarding or taking off the bus, modify the following functions
	public int pickUpPassenger(Request p) {
		this.onBoardRequests.get(this.zoneStops.get(p.getDestZone())).add(p);
		p.pickupTime = ContextCreator.getCurrentTick();
		this.served_pass += p.getNumPeople();
		this.passNum = this.getPassNum() + p.getNumPeople();
		if(this.passNum > this.numSeat) {
			ContextCreator.logger.error("Bus " + this.getID() + " has " + this.passNum + " passengers which exceed its capacity " + this.numSeat);
		}
		return 0;
	}
	
	public int dropOffPassenger(int stopIndex) {
		for(Request arrived_request: this.onBoardRequests.get(stopIndex)) {
			this.passNum = this.passNum - arrived_request.getNumPeople();
			if(arrived_request.lenOfActivity() >= 2){
				// generate a pass and add it to the corresponding zone
				arrived_request.moveToNextActivity();
				ContextCreator.getZoneContext().get(this.stopZones.get(stopIndex)).insertTaxiPass(arrived_request);
			}
			else {
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
		}
		return 0;
	}
	
	public boolean addToBoardPass(Request p) {
		if(stopZones.contains(p.getDestZone()) &&  stopZones.contains(p.getOriginZone())) {
			int stopIndex = stopZones.indexOf(p.getDestZone());
			int stopIndex2 = stopZones.indexOf(p.getOriginZone());
			if ((stopIndex >= this.getNextStopIndex() && stopIndex2 >= this.getNextStopIndex()-1) || stopIndex == 0) {
				this.toBoardRequests.get(stopIndex2).add(p);
				return true;
			}
		}
		return false;
	}

	public int getPassNum() {
		return this.passNum;
	}

	public int getNextStopIndex() {
		return this.nextStop;
	}
	
	public int getCurrentStop() {
		return this.nextStop - 1;
	}

	public int getRouteID() {
		return this.routeID;
	}

	@Override
	public double getMass() {
		return mass + passNum * avgPersonMass_;
	}
	
	public int remainingCapacity() {
		return this.numSeat - this.passNum;
	}
	
	// return the list of stops (Zones) to be visited by this bus
	public List<Integer> getBusStops(){
		return new ArrayList<Integer>(this.stopZones);
	}
	
	// Change the entire route
	public void updateSchedule(OneBusSchedule obs) {
		if (obs == null) {
			this.routeID = -1;
			this.stopZones = new ArrayList<Integer>(Arrays.asList(this.stopZones.get(this.stopZones.size()-1)));
			this.departureTime = new ArrayList<Integer>(Arrays.asList((int) (ContextCreator.getCurrentTick() + 60/GlobalVariables.SIMULATION_STEP_SIZE)));
			this.stopRoads = null;
			this.roadsBwStops = null;
		} else {
			this.routeID = obs.routeID;
			this.stopZones = obs.stopZones; 
			this.stopRoads = obs.stopRoads;
			this.roadsBwStops = obs.pathBetweenStops;
			this.zoneStops = new HashMap<Integer, Integer>();
			for (int i = 0; i < this.stopZones.size(); i++) {
				this.zoneStops.put(this.stopZones.get(i), i);
			}
			this.departureTime = obs.departureTime;
		}
		this.nextStop = 0;

		this.toBoardRequests = new ArrayList<Queue<Request>>();
		for (int i = 0; i < this.stopZones.size(); i++) {
			this.toBoardRequests.add(new LinkedList<Request>());
		}
		this.onBoardRequests = new ArrayList<Queue<Request>>();
		for (int i = 0; i < this.stopZones.size(); i++) {
			this.onBoardRequests.add(new LinkedList<Request>());
		}
		
		this.passNum = 0;
	}
	
	public boolean insertStop(int zoneID, Road r, int stopInd) {
		if(stopInd == 0 || stopInd + 1 == this.stopRoads.size() || stopInd < this.getCurrentStop()) {
			ContextCreator.logger.warn("Invalid stop index for inserting new stops!");
			return false;
		}
		else {
			this.stopZones.add(stopInd, zoneID);
			this.stopRoads.add(stopInd, r);
			if(this.roadsBwStops != null) {
				this.roadsBwStops.remove(stopInd);
				this.roadsBwStops.add(stopInd, null);
				this.roadsBwStops.add(stopInd, null);
			}
			return true;
		}
	}
	
	public boolean removeStop(int stopInd) {
		if(stopInd == 0 || stopInd + 1 == this.stopRoads.size() || stopInd < this.getCurrentStop()) {
			ContextCreator.logger.warn("Invalid stop index for removing stops!");
			return false;
		}
		else {
			this.stopZones.remove(stopInd);
			this.stopRoads.remove(stopInd);
			if(this.roadsBwStops != null) {
				this.roadsBwStops.remove(stopInd);
				this.roadsBwStops.remove(stopInd);
				this.roadsBwStops.add(stopInd, null);
			}
			return true;
		}
	}
}
