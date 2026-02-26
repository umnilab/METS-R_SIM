package mets_r.facility;

/**
 * Demand zone/Bus stop
 * Each zone has two queues of passengers, one for buses and one for taxis
 * Any problem please contact lei67@purdue.edu
 * 
 * @author: Zengixang Lei
 **/

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Request;
import mets_r.mobility.Vehicle;

public class Zone {
	/* Constants */
	public final static int ZONE = 0;
	public final static int HUB = 1;
	
	/* Private variables */
	private int zoneType; // 0 for normal zone, 1 for hub
	private int capacity; // parking space in this zone
	private int cachedCapacity; // snapshot for deterministic parallel reads
	private int nRequestForTaxi; // number of requests for Taxi
	private int nRequestForBus; // number of requests for Bus
	private AtomicInteger parkingVehicleStock; // number of parking vehicles at this zone
	private int publicTripTimeIndex = -1;
	private int privateTripTimeIndex = 0;
	
	// For vehicle repositioning
	private int lastDemandUpdateHour = -1; // the last time for updating the demand generation rate
	private double futureDemand; // demand in the near future
	private AtomicInteger futureSupply; // supply in the near future
	private double vehicleSurplus;
	private double vehicleDeficiency;
	private int H = 0; // horizon for considering feature demand
	
	// For multi-thread mode
	private ConcurrentLinkedQueue<Request> toAddRequestForTaxi; // demand from integrated services
	private ConcurrentLinkedQueue<Request> toAddRequestForBus; // demand from integrated services
	private ConcurrentLinkedQueue<ElectricTaxi> parkingRequests; // parking requests from taxis that exceeded max cruising time
	
	/* Protected variables */
	protected int ID;
	protected Coordinate coord;
	protected Random rand; // Random seed for things other than demand generation
	protected Random rand_demand_only; // Random seed only for demand generation
	protected Random rand_diffusion_only; // Random seed only for demand generation
	protected Random rand_mode_only; // Random seed only for demand generation
	protected Random rand_share_only; // Random seed only for demand generation
	protected Random rand_relocate_only; // Random seed only for demand generation
	protected Queue<Request> requestInQueueForTaxi; // Nonsharable passenger queue for taxis
	protected Map<Integer, Queue<Request>> sharableRequestForTaxi; // Shareable passenger for taxis
	protected Queue<Request> requestInQueueForBus; // Passenger queue for bus
	protected List<Integer> neighboringZones; // Sorted neighboring Zone from the closest to the farthest
	protected Integer closestDepartureRoad; // Exit of the facility
	protected Integer closestArrivalRoad; // Entrance of the facility
	protected List<Integer> neighboringDepartureLinks; // Surrounding links for vehicle to choose within the origin zone
	protected List<Integer> neighboringArrivalLinks; // Surrounding links for vehicle to choose within the destination zone
	protected double distToDepartureRoad;
	protected double distToArrivalRoad;
	
	/* Public variables */
	// For mode choice
	public ConcurrentHashMap<Integer, List<Integer>> traversingBusRoutes;
	
	// Service metrics
	public int numberOfGeneratedTaxiRequest;
	public int numberOfGeneratedBusRequest;
	public int numberOfGeneratedPrivateEVTrip;
	public int numberOfGeneratedPrivateGVTrip;
	public int arrivedPrivateEVTrip;
	public int arrivedPrivateGVTrip;
	public int taxiPickupRequest;
	public int busPickupRequest;
	public int taxiServedRequest;
	public int busServedRequest;
	public int numberOfLeavedTaxiRequest;
	public int numberOfLeavedBusRequest;
	public int numberOfRelocatedVehicles;
	public int taxiServedPassWaitingTime; // Waiting time of served Passengers
	public int busServedPassWaitingTime;
	public int taxiLeavedPassWaitingTime; // Waiting time of served Passengers
	public int busLeavedPassWaitingTime;
	public int taxiParkingTime;
	public int taxiCruisingTime;
	
    // Constructor
	public Zone(int ID, int capacity, int type) {
		rand = new Random(GlobalVariables.RandomGenerator.nextInt());
		rand_demand_only = new Random(rand.nextInt());
		rand_mode_only = new Random(rand.nextInt());
		rand_share_only = new Random(rand.nextInt());
		rand_diffusion_only = new Random(rand.nextInt());
		rand_relocate_only = new Random(rand.nextInt());
		this.ID = ID;
		if(capacity < 0) { // By default, infinite capacity
			this.capacity = GlobalVariables.NUM_OF_EV;
		}else {
			this.capacity = capacity;
		}
		this.cachedCapacity = this.capacity;
		this.sharableRequestForTaxi = new TreeMap<Integer, Queue<Request>>();
		this.requestInQueueForBus = new LinkedList<Request>();
		this.requestInQueueForTaxi = new LinkedList<Request>();
		this.nRequestForBus = 0;
		this.nRequestForTaxi = 0;
		this.zoneType = type;
		// Initialize metrices
		this.numberOfGeneratedTaxiRequest = 0;
		this.numberOfGeneratedBusRequest = 0;
		this.numberOfGeneratedPrivateEVTrip = 0;
		this.numberOfGeneratedPrivateGVTrip = 0;
		this.arrivedPrivateEVTrip = 0;
		this.arrivedPrivateGVTrip = 0;
		this.taxiPickupRequest = 0;
		this.busPickupRequest = 0;
		this.taxiServedRequest = 0; // Drop-off request number
		this.busServedRequest = 0; // Drop-off request number
		this.numberOfLeavedTaxiRequest = 0;
		this.numberOfLeavedBusRequest = 0;
		this.numberOfRelocatedVehicles = 0;
		this.taxiServedPassWaitingTime = 0;
		this.busServedPassWaitingTime = 0;
		this.taxiLeavedPassWaitingTime = 0;
		this.busLeavedPassWaitingTime = 0;
		this.taxiParkingTime = 0;
		this.neighboringZones = new ArrayList<Integer>();
		this.closestArrivalRoad = null;
		this.closestDepartureRoad = null;
		this.distToArrivalRoad = Double.MAX_VALUE;
		this.distToDepartureRoad = Double.MAX_VALUE;
		this.neighboringDepartureLinks = new ArrayList<Integer>();
		this.neighboringArrivalLinks = new ArrayList<Integer>();
		this.toAddRequestForTaxi = new ConcurrentLinkedQueue<Request>();
		this.toAddRequestForBus = new ConcurrentLinkedQueue<Request>();
		this.parkingRequests = new ConcurrentLinkedQueue<ElectricTaxi>();
		this.parkingVehicleStock = new AtomicInteger(0); 
		this.futureDemand = 0.0;
		this.futureSupply = new AtomicInteger(0);
		this.vehicleSurplus = 0.0;
		this.vehicleDeficiency = 0.0;
		if (GlobalVariables.PROACTIVE_RELOCATION) {
			H = GlobalVariables.SIMULATION_RH_MAX_CRUISING_TIME / GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL;
		}
		this.traversingBusRoutes = new ConcurrentHashMap<Integer, List<Integer>>();
	}
	
	public void setID(int id) {
		this.ID = id;
	}

	public Coordinate getCoord() {
		return this.coord;
	}
	
	public void setCoord(Coordinate coord) {
		this.coord = coord;
	}
	
	public double getVehicleSurplus() {
		return this.vehicleSurplus;
	}
	
	public double getVehicleDeficiency() {
		return this.vehicleDeficiency;
	}
	
	public void stepPart1() {
		// Happens at time step t
		this.processToAddPassengers();
		this.servePassengerByTaxi();
		this.handleParkingRequests();
		this.relocateTaxi();
		
		if (ContextCreator.getCurrentTick() == GlobalVariables.SIMULATION_STOP_TIME) return;
		// Handle private vehicle first
		this.generatePrivateEVTrip();
		this.generatePrivateGVTrip();
		privateTripTimeIndex += 1;
		
		// Handle public vehicle requests
		this.generatePassenger();
	}
	
	public void stepPart2() {
		if (ContextCreator.getCurrentTick() == GlobalVariables.SIMULATION_STOP_TIME) return;
		// Happens between t and t + 1
		this.passengerWaitTaxi();
		this.passengerWaitBus();
		this.taxiWaitPassenger();
		
		// Happens at time step t + 1 when other things are settled
		this.updateSupplyStates();
	}
	
	// Generate EV/GV trips
	protected void generatePrivateEVTrip() {
		// Loop over private trip demand
		for (Entry<Integer, Integer> oneTrip: ContextCreator.travel_demand.getPrivateEVTravelDemand(privateTripTimeIndex, ID).entrySet()) {
			ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(oneTrip.getKey());
			Zone destZone =  ContextCreator.getZoneContext().get(oneTrip.getValue());
			if(v != null) { // If vehicle exists
				if (v.getState() == Vehicle.NONE_OF_THE_ABOVE) {
					// If vehicle is not on road
					int destRoad =  destZone.sampleRoad(true);
					v.initializePlan(this.getID(), this.sampleRoad(false), (int) ContextCreator.getCurrentTick());
					v.addPlan(oneTrip.getValue(), destRoad, (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					// Check if vehicle has enough battery, 1 kWh = 5000 m
					if(v.getBatteryLevel() * 5000 < ContextCreator.getCityContext().getDistance(v.getCurrentCoord(), ContextCreator.getRoadContext().get(destRoad).getEndCoord())) {
						v.goCharging();
					}
					else {
						v.departure();
					}
					this.numberOfGeneratedPrivateEVTrip += 1;
				}
				else { // If vehicle is on road
					ContextCreator.logger.warn("The private EV: " + oneTrip.getKey() + " is not available at time index "+ privateTripTimeIndex +" , added destination to its to-be-visited queue");
					int destRoad =  destZone.sampleRoad(true);
					v.addPlan(oneTrip.getValue(), destRoad , (int) ContextCreator.getNextTick());
					this.numberOfGeneratedPrivateEVTrip += 1;
				}
			}
			else { // If vehicle does not exists
				// Create vehicle
				v = new ElectricVehicle(Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE);
				// Update its state via configuration
				ArrayList<Double> vehConfig = ContextCreator.travel_demand.getPrivateEVProfile(oneTrip.getKey());
				if(vehConfig!= null) {
					v.setBatteryCapacity(vehConfig.get(0));
					v.setBatteryLevel(vehConfig.get(1));
					v.setLowerBatteryRechargeLevel(vehConfig.get(2));
					v.setHigherBatteryRechargeLevel(vehConfig.get(3));
				}
				
				// Assign trips
				v.initializePlan(this.getID(), this.sampleRoad(false), (int) ContextCreator.getCurrentTick());
				int destRoad =  destZone.sampleRoad(true);
				v.addPlan(oneTrip.getValue(), destRoad, (int) ContextCreator.getNextTick());
				v.setNextPlan();
				v.setState(Vehicle.PRIVATE_TRIP);
				
				v.departure();
				
				this.numberOfGeneratedPrivateEVTrip += 1;
				ContextCreator.getVehicleContext().registerPrivateEV(oneTrip.getKey(), v);
			}
		}
	}
	
	protected void generatePrivateGVTrip() {
		for (Entry<Integer, Integer> oneTrip: ContextCreator.travel_demand.getPrivateGVTravelDemand(privateTripTimeIndex, ID).entrySet()) {
			Vehicle v = ContextCreator.getVehicleContext().getPrivateGV(oneTrip.getKey());
			Zone destZone =  ContextCreator.getZoneContext().get(oneTrip.getValue());
			if(v != null) { // If vehicle exists
				if (v.getState() == Vehicle.NONE_OF_THE_ABOVE) {
					// If vehicle is not on road
					v.initializePlan(this.getID(), this.sampleRoad(false), (int) ContextCreator.getCurrentTick());
					v.addPlan(oneTrip.getValue(), destZone.sampleRoad(true), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure();
					this.numberOfGeneratedPrivateGVTrip += 1;
				}
				else { // If vehicle is on road
					ContextCreator.logger.warn("The private GV: " + oneTrip.getKey() + " is not available at time index "+ privateTripTimeIndex +" , added destination to its to-be-visited queue");
					v.addPlan(oneTrip.getValue(), destZone.sampleRoad(true), (int) ContextCreator.getNextTick());
					this.numberOfGeneratedPrivateGVTrip += 1;
				}
			}
			else { // If vehicle does not exists
				// Create vehicle
				v = new Vehicle(Vehicle.GV, Vehicle.NONE_OF_THE_ABOVE);
				// Assign trips
				v.initializePlan(this.getID(), this.sampleRoad(false), (int) ContextCreator.getCurrentTick());
				v.addPlan(oneTrip.getValue(), destZone.sampleRoad(true), (int) ContextCreator.getNextTick());
				v.setNextPlan();
				v.setState(Vehicle.PRIVATE_TRIP);
				v.departure();
				this.numberOfGeneratedPrivateGVTrip += 1;
				ContextCreator.getVehicleContext().registerPrivateGV(oneTrip.getKey(), v);
			}
		}
	}
	
	
	// Generate passenger
	protected void generatePassenger() {
		this.publicTripTimeIndex = (int) Math.floor(ContextCreator.getCurrentTick() / GlobalVariables.SIMULATION_DEMAND_REFRESH_INTERVAL) % GlobalVariables.HOUR_OF_DEMAND;
		if (this.lastDemandUpdateHour != this.publicTripTimeIndex) {
			this.futureDemand = 0.0;
		}
		for (Zone destZone : ContextCreator.getZoneContext().getAll()) {
			int destination = destZone.getID();
			double passRate = ContextCreator.travel_demand.getPublicTravelDemand(this.getID(), destination, this.publicTripTimeIndex)
					* (GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL
							/ (3600 / GlobalVariables.SIMULATION_STEP_SIZE));
		    
			if (passRate > 0) {
				passRate *= GlobalVariables.RH_DEMAND_FACTOR;
				double numToGenerate = Math.floor(passRate)
						+ (rand_demand_only.nextDouble() < (passRate - Math.floor(passRate)) ? 1 : 0);
				double sharableRate = ContextCreator.travel_demand.getPublicTravelDemand(this.getID(), destination, this.publicTripTimeIndex);
				
				// Commented by default given its high computational costs, uncomment if you would like use the mode choice model
				// For all candidate transit routes, find the best one
//				if (traversingBusRoutes.containsKey(destination)) {
//					// Find the most proper transit line
//					double bestBusTime = Double.MAX_VALUE;
//					double bestBusDist = Double.MAX_VALUE;
//					int bestBusRoute = -1;
//					for(int routeID : traversingBusRoutes.get(destination)) {
//					    List<Integer> stops = ContextCreator.bus_schedule.getStopZones(routeID);
//					    double bestTimeThisRoute = Double.POSITIVE_INFINITY;
//					    double bestDistThisRoute = Double.POSITIVE_INFINITY;
//
//					    // scan for every origin index
//					    for (int i = 0; i < stops.size(); i++) {
//					        if (!stops.get(i).equals(this.ID)) continue;
//					        // for each origin position, scan forward for every matching destination
//					        for (int j = i+1; j < stops.size(); j++) {
//					            if (!stops.get(j).equals(destination)) continue;
//
//					            // compute time+dist between stops[i] and stops[j]
//					            double busTime = 0, busDist = 0;
//					            for (int k = i; k < j; k++) {
//					                List<Road> seg = RouteContext.shortestPathRoute(ContextCreator.bus_schedule.getStopRoad(routeID, k), ContextCreator.bus_schedule.getStopRoad(routeID, k+1), rand);
//					                busTime += seg.stream().mapToDouble(Road::getTravelTime).sum();
//					                busDist += seg.stream().mapToDouble(Road::getLength).sum();
//					            }
//
//					            // keep best for *this* route
//					            if (busTime < bestTimeThisRoute) {
//					                bestTimeThisRoute = busTime;
//					                bestDistThisRoute = busDist;
//					            }
//					        }
//					    }
//
//					    // compare to global best
//					    if (bestTimeThisRoute < bestBusTime) {
//					        bestBusTime = bestTimeThisRoute;
//					        bestBusDist = bestDistThisRoute;
//					        bestBusRoute = routeID;
//					    }
//					}
//					
//					// Calculate the travel time and travel distance for taxis
//					Coordinate oCoord = this.getCoord();
//					Coordinate dCoord = destZone.getCoord();
//					List<Road> taxiPath = RouteContext.shortestPathRoute(oCoord, dCoord, rand);
//					double taxiTime = taxiPath.stream().mapToDouble(Road::getTravelTime).sum();
//					double taxiDist = taxiPath.stream().mapToDouble(Road::getLength).sum();
//					
//					float threshold = getSplitRatio(taxiTime, taxiDist, bestBusTime, bestBusDist);
//					for (int i = 0; i < numToGenerate; i++) {
//						if (rand_mode_only.nextDouble() > threshold) {
//							if (rand_share_only.nextDouble()<sharableRate && GlobalVariables.RH_DEMAND_SHARABLE) { // Sharable requests start from the same loc
//								Request new_pass = new Request(this.ID, destination, this.getClosestRoad(false), 
//										destZone.sampleRoad(true), 1
//										); 
//								new_pass.setWillingToShare(true);
//								this.addSharableTaxiPass(new_pass, destination);
//							} else {
//								Request new_pass = new Request(this.ID, destination, this.sampleRoad(false), 
//										destZone.sampleRoad(true), 1
//										); 
//								this.addTaxiPass(new_pass);
//							}
//							this.numberOfGeneratedTaxiRequest += 1;
//						} else { // Bus always start and end at the centroid
//							Request new_pass = new Request(this.ID, destination, this.getClosestRoad(false), 
//									ContextCreator.getZoneContext().get(destination).getClosestRoad(true), 
//									1);
//							new_pass.setBusRoute(bestBusRoute);
//							this.addBusPass(new_pass);
//							this.numberOfGeneratedBusRequest += 1;
//						}
//					}
//					if (this.lastDemandUpdateHour != this.publicTripTimeIndex) {
//						this.futureDemand += (passRate * threshold);
//					}
//				} 
//				else {
					// Taxi only
					for (int i = 0; i < numToGenerate; i++) {
						if (rand_share_only.nextDouble()<sharableRate && GlobalVariables.RH_DEMAND_SHARABLE) { // Sharable requests start from the same loc
							Request new_pass = new Request(this.ID, destination, this.getClosestRoad(false), destZone.sampleRoad(true), 1); 
							new_pass.setWillingToShare(true);
							this.addSharableTaxiPass(new_pass, destination);
						} else {
							Request new_pass = new Request(this.ID, destination, this.sampleRoad(false), destZone.sampleRoad(true), 1); 
							this.addTaxiPass(new_pass);
						}
						this.numberOfGeneratedTaxiRequest += 1;
					}
					if (this.lastDemandUpdateHour != this.publicTripTimeIndex) {
						this.futureDemand+=(passRate);
					}
//				}
			}
		}
	}
	
	// Precompute vehicle supply status
	protected void updateSupplyStates() {
		this.vehicleSurplus = this.getVehicleStock() - this.nRequestForTaxi + 0.8 * this.futureSupply.get();
		this.vehicleSurplus = this.vehicleSurplus>0?this.vehicleSurplus:0;
		this.vehicleDeficiency = this.nRequestForTaxi - this.getVehicleStock(); //- ContextCreator.getVehicleContext().getNumOfRelocationTaxi(this.getID());
		this.vehicleDeficiency = this.vehicleDeficiency>0?this.vehicleDeficiency:0;
		
		ContextCreator.logger.debug("current buss pass is" + this.numberOfGeneratedBusRequest);
		ContextCreator.logger.debug("current taxi pass is" + this.numberOfGeneratedTaxiRequest);

		if (this.lastDemandUpdateHour != this.publicTripTimeIndex) {
			this.lastDemandUpdateHour = this.publicTripTimeIndex;
		}
		
		this.cachedCapacity = this.capacity - this.parkingVehicleStock.get();
	}

	// Serve passenger
	public void servePassengerByTaxi() {
		// Ridesharing matching for the sharable passengers. Current implementation: If
		// the passenger goes to the same place, pair them together.
		Queue<ElectricTaxi> eligibleTaxis = new LinkedList<ElectricTaxi>();
		for(ElectricTaxi v: ContextCreator.getVehicleContext().getAvailableTaxisSorted(this.getID())) {
			if(v.hasEnoughBattery()) {
				eligibleTaxis.add(v);
			}
		}
		
		// First serve sharable passengers
		if (this.sharableRequestForTaxi.size() > 0) {
			for (Queue<Request> passQueue : this.sharableRequestForTaxi.values()) {
				if (passQueue.size() > 0) {
					int pass_num = 0;
					for(Request p: passQueue) {
						pass_num  = pass_num + p.getNumPeople();
					}
					int v_num = eligibleTaxis.size();
					for (int i = 0; i < v_num; i++) {
						ElectricTaxi v = eligibleTaxis.poll();
						if (v != null) {
							ContextCreator.vehicleContext.removeAvailableTaxi(v, this.getID());
							if(v.getState() == Vehicle.PARKING) {
								this.removeOneParkingVehicle();
							}
							else if(v.getState() != Vehicle.CRUISING_TRIP) {
								ContextCreator.logger.error("Something went wrong, the vehicle is not cruising or parking but still in the zone!");
							}
							ArrayList<Request> tmp_pass = new ArrayList<Request>();
							while(v.getPassNum() + passQueue.peek().getNumPeople() <= 4) {
								Request p = passQueue.poll();
								tmp_pass.add(p);
								// Record served passengers
								p.matchedTime = ContextCreator.getCurrentTick();
								this.nRequestForTaxi -= 1;
								this.taxiPickupRequest += 1;
								this.taxiServedPassWaitingTime += p.getCurrentWaitingTime();
								pass_num = pass_num - tmp_pass.size();
								if(passQueue.peek() == null) break;
							}
							v.servePassenger(tmp_pass);
							// Update future supply of the target zone
							ContextCreator.getZoneContext().get(tmp_pass.get(0).getDestZone()).addFutureSupply();
							if(pass_num == 0) break;
						}
						else {
							break;
						}
					}
				}
			}
		}
		
		// Then serve non-sharable requests
		int curr_size = this.requestInQueueForTaxi.size();
		for (int i = 0; i < curr_size; i++) {
			// Find the first electric taxi with enough battery
			ElectricTaxi v = eligibleTaxis.poll();
			if (v != null) {
				ContextCreator.vehicleContext.removeAvailableTaxi(v, this.getID());
				Request current_taxi_pass = this.requestInQueueForTaxi.poll();
				if(v.getState() == Vehicle.PARKING) {
					this.removeOneParkingVehicle();
				}
				else if(v.getState() != Vehicle.CRUISING_TRIP) {
					ContextCreator.logger.error("Something went wrong, the vehicle is not cruising or parking but still in the zone!");
				}
				this.taxiPickupRequest += 1;
				current_taxi_pass.matchedTime = ContextCreator.getCurrentTick();
				v.servePassenger(Arrays.asList(current_taxi_pass));
				// Update future supply of the target zone
				ContextCreator.getZoneContext().get(current_taxi_pass.getDestZone()).addFutureSupply();
				// Record served passenger
				this.nRequestForTaxi -= 1;
				this.taxiServedPassWaitingTime += current_taxi_pass.getCurrentWaitingTime();
			} else {
				break; // no vehicle
			}
		}
	}	
	
	// Serve passenger using Bus
	public int servePassengerByBus(ElectricBus b) {
		int maxPassNum = b.remainingCapacity();
		List<Integer> busStop = b.getBusStops();
		ArrayList<Request> passToServe = new ArrayList<Request>();
		int numPeople = 0;
		
		for (Request p : this.requestInQueueForBus) { // If there are passengers
			if (busStop.contains(p.getDestZone())) {
				int stopIndex = busStop.indexOf(p.getDestZone());
				if (stopIndex >= b.getNextStopIndex() || stopIndex == 0) {
					if (numPeople + p.getNumPeople() > maxPassNum) {
						break;
					} else { 
						// passenger get on board
						passToServe.add(p);
						numPeople = numPeople + p.getNumPeople();
						p.matchedTime = ContextCreator.getCurrentTick();
						b.pickUpPassenger(p);
						this.busServedPassWaitingTime += p.getCurrentWaitingTime();
						this.busPickupRequest += 1;
					}
				}
			}
		}
		this.nRequestForBus -= passToServe.size();
		this.requestInQueueForBus.removeAll(passToServe);
		return 0;
	}

	// An example of a proactive relocation function
	public void relocateTaxi() {
	    // 1. Sender Logic (Zone has a surplus)
	    if (this.hasEnoughTaxi(H)) {
	    	List<ElectricTaxi> sortedAvailable = ContextCreator.getVehicleContext().getAvailableTaxisSorted(this.getID());
	        int dispatchIdx = 0;

	        // Figure out total demand in neighboring zones
	        double totalDemand = 0;
	        List<Zone> deficientNeighbors = new ArrayList<>();
	        
	        for (Integer z : this.neighboringZones) {
	            Zone neighbor = ContextCreator.getZoneContext().get(z);
	            if (!neighbor.hasEnoughTaxi(H)) {
	                totalDemand += neighbor.getVehicleDeficiency();
	                deficientNeighbors.add(neighbor);
	            }
	        }

	        if (totalDemand <= 0) return; 

	        double localSurplus = this.vehicleSurplus - (H * this.futureDemand);

	        // Process relocation for each deficient neighbor
	        for (Zone neighbor : deficientNeighbors) {
	            if (dispatchIdx >= sortedAvailable.size()) break;

	            double deficiency = neighbor.getVehicleDeficiency();
	            double totalSupplyForNeighbor = 0;

	            for (Integer z2Id : neighbor.neighboringZones) {
	                Zone z2 = ContextCreator.getZoneContext().get(z2Id);
	                if (z2.hasEnoughTaxi(H)) {
	                    totalSupplyForNeighbor += (z2.getVehicleSurplus() - (H * z2.getFutureDemand()));
	                }
	            }

	            // Guard against division-by-zero if supply calculation evaluates to 0
	            double relocateRate = 0;
	            if (totalSupplyForNeighbor > 0) {
	                double supplyDemandRatio = Math.min(1.0, totalSupplyForNeighbor / totalDemand);
	                relocateRate = 1.25 * supplyDemandRatio * deficiency * localSurplus / totalSupplyForNeighbor; 
	            }

	            // Probabilistic rounding
	            int numToRelocate = (int) Math.floor(relocateRate);
	            if (this.rand_relocate_only.nextDouble() < (relocateRate - numToRelocate)) {
	                numToRelocate++;
	            }

	            // Dispatch vehicles in deterministic (ID-sorted) order
	            for (int i = 0; i < numToRelocate; i++) {
	                if (dispatchIdx >= sortedAvailable.size()) break;
	                ElectricTaxi v = sortedAvailable.get(dispatchIdx++);

	                this.numberOfRelocatedVehicles += 1;
	                if (v.getState() == Vehicle.PARKING) {
	                    this.removeOneParkingVehicle();
	                }
	                
	                ContextCreator.getVehicleContext().removeAvailableTaxi(v, this.getID());
	                v.relocation(neighbor.getID(), neighbor.sampleRoad(true));
	                neighbor.addFutureSupply();
	            }
	        }
	    } 
	    // 2. Receiver / Interceptor Logic (Zone has a shortage)
	    else {
	        double relocateRate = this.getVehicleDeficiency(); 
	        
	        // Probabilistic rounding
	        int numToRelocate = (int) Math.floor(relocateRate);
	        if (this.rand_relocate_only.nextDouble() < (relocateRate - numToRelocate)) {
	            numToRelocate++;
	        }

	        if (numToRelocate <= 0) return;

	        List<ElectricTaxi> sortedrelocateVehicles = ContextCreator.getVehicleContext().getRelocationTaxiSorted(this.getID());
	        
	        for (ElectricTaxi v : sortedrelocateVehicles) {
	            if (numToRelocate <= 0) break; // Early exit once needs are met

	            int currentDest = v.getDestID();
	            if (v.modifyPlan(this.getID(), v.getRoad())) {
	            	ContextCreator.getZoneContext().get(currentDest).futureSupply.addAndGet(-1);
	                v.setState(Vehicle.INACCESSIBLE_RELOCATION_TRIP);
	                ContextCreator.getVehicleContext().removeRelocationTaxi(v);
	                this.addFutureSupply();
	                numToRelocate--;
	            }
	        }
	    }
	}

	// Passenger waiting
	protected void passengerWaitTaxi() {
		int curr_size;
		for (Queue<Request> passQueue : this.sharableRequestForTaxi.values()) {
			for (Request p : passQueue) {
				p.waitNextTime(GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL);
			}
			curr_size = passQueue.size();
			for (int i = 0; i < curr_size; i++) {
				if (passQueue.peek().check()) {
					taxiLeavedPassWaitingTime += passQueue.poll().getCurrentWaitingTime();
					numberOfLeavedTaxiRequest += 1;
					nRequestForTaxi -= 1;
				} else {
					break;
				}
			}
		}

		for (Request p : requestInQueueForTaxi) {
			p.waitNextTime(GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL);
		}
		curr_size = requestInQueueForTaxi.size();
		
		for (int i = 0; i < curr_size; i++) {
			if (requestInQueueForTaxi.peek().check()) {
				taxiLeavedPassWaitingTime += requestInQueueForTaxi.poll().getCurrentWaitingTime();
				numberOfLeavedTaxiRequest += 1;
				nRequestForTaxi -= 1;
			} else {
				break;
			}
		}
	}

	// Passenger waiting for bus
	protected void passengerWaitBus() {
		for (Request p : this.requestInQueueForBus) {
			p.waitNextTime(GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL);
		}
		int curr_size = requestInQueueForBus.size();
		for (int i = 0; i < curr_size; i++) {
			if (requestInQueueForBus.peek().check()) {
				busLeavedPassWaitingTime += requestInQueueForBus.poll().getCurrentWaitingTime();
				this.numberOfLeavedBusRequest += 1;
				nRequestForBus -= 1;
			}
		}
	}

	// Taxi waiting for passenger, extend this if the relocation is based on the taxiWaiting time
	protected void taxiWaitPassenger() {
		this.taxiParkingTime += this.parkingVehicleStock.get() * GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL;
	}

	// Split taxi and bus passengers via a discrete choice model
	// Distance unit in mile
	// Time unit in minute
	// Uncomment if you want to use mode choice model between taxis and buses
//	private float getSplitRatio(double taxiTime, double taxiDist, double bestBusTime, double bestBusDist) {
//		double taxiUtil = GlobalVariables.MS_ALPHA
//				* (GlobalVariables.INITIAL_PRICE_TAXI
//						+ GlobalVariables.BASE_PRICE_TAXI * taxiDist / 1609)
//				+ GlobalVariables.MS_BETA * (taxiTime / 60) + GlobalVariables.TAXI_BASE;
//		double busUtil = (float) (GlobalVariables.MS_ALPHA * GlobalVariables.BUS_TICKET_PRICE
//				+ GlobalVariables.MS_BETA * (bestBusTime / 60)
//				+ GlobalVariables.BUS_BASE);
//		
//		return (float) (Math.exp(1) / (Math.exp(taxiUtil - busUtil) + Math.exp(1)));
//	}
	
	// Generate passenger waiting time for taxi
	// this is in Zone class since the observations
	// are usually associate with Zones
	// Assume the maximum waiting time for taxi is 15 minutes (we treated as the constraints: service quality demand)
    protected int generateWaitingTimeForTaxi() {
		return (int) (ContextCreator.travel_demand.getWaitingThreshold(this.publicTripTimeIndex) / GlobalVariables.SIMULATION_STEP_SIZE);
	}
    
    // Generate passenger waiting time for bus
 	// Assume bus passenger will keep waiting (we treated this as an objective: evaluated by the simulation output)
    protected int generateWaitingTimeForBus() {
 		return (int) GlobalVariables.SIMULATION_STOP_TIME;
 	}
	
	// Get/Set states
	protected void processToAddPassengers() {
		ArrayList<Request> pendingTaxi = new ArrayList<Request>();
		for (Request q = this.toAddRequestForTaxi.poll(); q != null; q = this.toAddRequestForTaxi.poll()) {
			pendingTaxi.add(q);
		}
		pendingTaxi.sort((a, b) -> Integer.compare(a.getID(), b.getID()));
		for (Request q : pendingTaxi) {
			this.addTaxiPass(q);
		}
		ArrayList<Request> pendingBus = new ArrayList<Request>();
		for (Request q = this.toAddRequestForBus.poll(); q != null; q = this.toAddRequestForBus.poll()) {
			pendingBus.add(q);
		}
		pendingBus.sort((a, b) -> Integer.compare(a.getID(), b.getID()));
		for (Request q : pendingBus) {
			this.addBusPass(q);
		}
	}

	public void submitParkingRequest(ElectricTaxi v) {
		this.parkingRequests.add(v);
	}
	
	protected void handleParkingRequests() {
		ArrayList<ElectricTaxi> pending = new ArrayList<ElectricTaxi>();
		for (ElectricTaxi v = this.parkingRequests.poll(); v != null; v = this.parkingRequests.poll()) {
			pending.add(v);
		}
		pending.sort((a, b) -> Integer.compare(a.getID(), b.getID()));
		
		for (ElectricTaxi v : pending) {
			if (v.getState() != Vehicle.CRUISING_TRIP) continue;
			
			for (int z : this.neighboringZones) {
				if (ContextCreator.getZoneContext().get(z).getCapacity() > 0) {
					ContextCreator.getVehicleContext().removeAvailableTaxi(v, this.getID());
					ContextCreator.getZoneContext().get(z).addFutureSupply();
					v.addPlan(z, ContextCreator.getZoneContext().get(z).getClosestRoad(true),
							ContextCreator.getNextTick());
					v.setNextPlan();
					v.departure();
					v.setState(Vehicle.ACCESSIBLE_RELOCATION_TRIP);
					break;
				}
			}
		}
		
		this.parkingRequests.clear(); // Clear up the parking requests once handled
	}

	protected void addTaxiPass(Request new_pass) {
		this.nRequestForTaxi += 1;
		new_pass.setMaxWaitingTime(this.generateWaitingTimeForTaxi());
		this.requestInQueueForTaxi.add(new_pass);
	}

	protected void addSharableTaxiPass(Request new_pass, int destination) {
		this.nRequestForTaxi += 1;
		new_pass.setMaxWaitingTime(this.generateWaitingTimeForTaxi());
		if (!this.sharableRequestForTaxi.containsKey(destination)) {
			this.sharableRequestForTaxi.put(destination, new LinkedList<Request>());
		}
		this.sharableRequestForTaxi.get(destination).add(new_pass);
	}
	
	protected void addBusPass(Request new_pass) {
		this.nRequestForBus += 1;
		new_pass.setMaxWaitingTime(this.generateWaitingTimeForBus());
		this.requestInQueueForBus.add(new_pass);
	}
	
	public void addParkingVehicleStock(int vehicle_num) {
		this.parkingVehicleStock.addAndGet(vehicle_num);
	}

	public void addOneParkingVehicle() {
		this.parkingVehicleStock.addAndGet(1);
	}
	
	public void removeOneParkingVehicle() {
		if (this.parkingVehicleStock.get() - 1 < 0) {
			ContextCreator.logger.error(this.ID + " out of stock, vehicle_num: " + this.parkingVehicleStock.get());
			return;
		}
		this.addParkingVehicleStock(-1);
	}

	public int getVehicleStock() {
		return this.parkingVehicleStock.get(); //+ ContextCreator.getVehicleContext().getNumOfRelocationTaxi(this.ID);
	}

	public int getZoneType() {
		return this.zoneType;
	}
	
	public boolean hasEnoughTaxi(double H) { // H represents future steps (horizon) to consider
		return (this.vehicleSurplus - H * this.futureDemand) > 0 && this.vehicleDeficiency < 0.0001;
	}

	public void insertTaxiPass(Request new_pass) {
		this.toAddRequestForTaxi.add(new_pass);
	}
	
	public void insertBusPass(Request new_pass) {
		this.toAddRequestForBus.add(new_pass);
	}
	
	public void addFutureSupply() {
		this.futureSupply.addAndGet(1);
	}

	public void removeFutureSupply() {
		this.futureSupply.addAndGet(-1);
	}

	public double getFutureDemand() {
		return this.futureDemand;
	}

	public int getFutureSupply() {
		return this.futureSupply.get();
	}
	
	public int getTaxiRequestNum() {
		return nRequestForTaxi;
	}

	public int getBusRequestNum() {
		return nRequestForBus;
	}
	
	public int sampleRoad(boolean goDest) {
		if(this.zoneType == Zone.HUB || !GlobalVariables.DEMAND_DIFFUSION){
			return this.getClosestRoad(goDest);
		}else {
			return this.getNeighboringLink(rand_diffusion_only.nextInt(this.getNeighboringLinkSize(goDest)), goDest);
		}
	}

	public int getID() {
		return ID;
	}

	public int getCapacity() {
		return this.cachedCapacity;
	}
	
	public void addNeighboringZone(int z) {
		if(!this.neighboringZones.contains(z)) {
			this.neighboringZones.add(z);
		}
	}
	
	public void setClosestRoad(int r, boolean goDest) {
		if(goDest) this.closestArrivalRoad = r;
		else this.closestDepartureRoad = r;
	}
	
	public Integer getClosestRoad(boolean goDest) {
		if(goDest) return this.closestArrivalRoad;
		return this.closestDepartureRoad;
	}
	
	public double getDistToRoad(boolean goDest) {
		if (goDest) return distToArrivalRoad;
		else return distToDepartureRoad;
	}

	public void setDistToRoad(double distToRoad, boolean goDest) {
		if (goDest) this.distToArrivalRoad = distToRoad; 
		else this.distToDepartureRoad = distToRoad;
	}
	
	public void addNeighboringLink(int r, boolean goDest) {
		if(goDest) {
			if(!this.neighboringArrivalLinks.contains(r)) {
				this.neighboringArrivalLinks.add(r);
			}
		}
		else {
			if(!this.neighboringDepartureLinks.contains(r)) {
				this.neighboringDepartureLinks.add(r);
			}
		}
	}
	
	public int getNeighboringLinkSize(boolean goDest) {
		if(goDest) {
			return this.neighboringArrivalLinks.size();
		}
		else {
			return this.neighboringDepartureLinks.size();
		}
	}
	
	public List<Integer> getNeighboringLinks(boolean goDest){
		if(goDest){
			return this.neighboringArrivalLinks;
		}
		else{
			return this.neighboringDepartureLinks;
		}
	}
	
	public Integer getNeighboringLink(int index, boolean goDest) {
		if(goDest) {
			return this.neighboringArrivalLinks.get(index);
		}
		else {
			return this.neighboringDepartureLinks.get(index);
		}
	}
	
	public List<Integer> getNeighboringZones(){
		return this.neighboringZones;
	}
	
	public int getNeighboringZones(int index){
		return this.neighboringZones.get(index);
	}
	
	public int getNeighboringZoneSize() {
		return this.neighboringZones.size();
	}
	
	/* Getters and setters for save/load support */
	public Random getRandom() { return this.rand; }
	public Random getRandomDemand() { return this.rand_demand_only; }
	public Random getRandomDiffusion() { return this.rand_diffusion_only; }
	public Random getRandomMode() { return this.rand_mode_only; }
	public Random getRandomShare() { return this.rand_share_only; }
	public Random getRandomRelocate() { return this.rand_relocate_only; }
	
	public void setRandom(Random r) { this.rand = r; }
	public void setRandomDemand(Random r) { this.rand_demand_only = r; }
	public void setRandomDiffusion(Random r) { this.rand_diffusion_only = r; }
	public void setRandomMode(Random r) { this.rand_mode_only = r; }
	public void setRandomShare(Random r) { this.rand_share_only = r; }
	public void setRandomRelocate(Random r) { this.rand_relocate_only = r; }
	
	public int getParkingVehicleStock() { return this.parkingVehicleStock.get(); }
	public void setParkingVehicleStock(int v) { this.parkingVehicleStock.set(v); }
	public Queue<Request> getTaxiRequestQueue() { return this.requestInQueueForTaxi; }
	public Queue<Request> getBusRequestQueue() { return this.requestInQueueForBus; }
	public Map<Integer, Queue<Request>> getSharableRequestForTaxi() { return this.sharableRequestForTaxi; }
	public void setFutureDemand(double v) { this.futureDemand = v; }
	public void setFutureSupply(int v) { this.futureSupply.set(v); }
	public void setVehicleSurplus(double v) { this.vehicleSurplus = v; }
	public void setVehicleDeficiency(double v) { this.vehicleDeficiency = v; }
}
