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
import java.util.HashMap;
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
import mets_r.routing.RouteContext;

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
	private int modeSplitCacheHour = -1;
	private Map<Integer, ModeSplitChoice> modeSplitCache;
	
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
	// Legacy "pickup" counters are updated when requests are matched/assigned.
	public int taxiPickupRequest;
	public int busPickupRequest;
	public int taxiPickupPassengers;
	public int busPickupPassengers;
	// Actual pickup counters are updated when passengers board a taxi or bus.
	public int taxiPickedUpRequest;
	public int busPickedUpRequest;
	public int taxiPickedUpPassengers;
	public int busPickedUpPassengers;
	public int taxiServedRequest;
	public int busServedRequest;
	public int taxiServedPassengers;
	public int busServedPassengers;
	public int numberOfLeavedTaxiRequest;
	public int numberOfLeavedBusRequest;
	/** Sum of {@link Request#getNumPeople()} for taxi requests that abandoned queues (shareable + main). */
	public int numberOfLeavedTaxiPassengers;
	/** Sum of {@link Request#getNumPeople()} for bus requests that abandoned queues. */
	public int numberOfLeavedBusPassengers;
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
		this.modeSplitCache = new HashMap<Integer, ModeSplitChoice>();
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
		this.taxiPickupPassengers = 0;
		this.busPickupPassengers = 0;
		this.taxiPickedUpRequest = 0;
		this.busPickedUpRequest = 0;
		this.taxiPickedUpPassengers = 0;
		this.busPickedUpPassengers = 0;
		this.taxiServedRequest = 0; // Drop-off request number
		this.busServedRequest = 0; // Drop-off request number
		this.taxiServedPassengers = 0;
		this.busServedPassengers = 0;
		this.numberOfLeavedTaxiRequest = 0;
		this.numberOfLeavedBusRequest = 0;
		this.numberOfLeavedTaxiPassengers = 0;
		this.numberOfLeavedBusPassengers = 0;
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
		try {
			stepPart1Unsafe();
		} catch (Throwable ex) {
			ContextCreator.logger.error("Zone.stepPart1 failed; zone=" + this.ID
					+ ", tick=" + ContextCreator.getCurrentTick(), ex);
		}
	}

	private void stepPart1Unsafe() {
		// Guard against a stale scheduled action firing after this zone was removed
		// (e.g. after the meta zone is removed when real zones are added at runtime).
		if (ContextCreator.getZoneContext().get(this.getID()) == null) return;
		// Happens at time step t
		this.processToAddPassengers();
		// Built-in dispatching is bypassed when an external Control API owns it.
		if (!GlobalVariables.DISPATCHING_CONTROLLED_BY_CONTROL_APIS) {
			this.servePassengerByTaxi();
		}
		// Built-in repositioning (parking handoff + proactive relocation) is
		// bypassed when an external Control API owns it.
		if (!GlobalVariables.REPOSITIONING_CONTROLLED_BY_CONTROL_APIS) {
			this.handleParkingRequests();
			this.relocateTaxi();
		}
		
		if (ContextCreator.getCurrentTick() == GlobalVariables.SIMULATION_STOP_TIME) return;
		// Handle private vehicle first
		this.generatePrivateEVTrip();
		this.generatePrivateGVTrip();
		privateTripTimeIndex += 1;
		
		// Handle public vehicle requests
		this.generatePassenger();
	}
	
	public void stepPart2() {
		try {
			stepPart2Unsafe();
		} catch (Throwable ex) {
			ContextCreator.logger.error("Zone.stepPart2 failed; zone=" + this.ID
					+ ", tick=" + ContextCreator.getCurrentTick(), ex);
		}
	}

	private void stepPart2Unsafe() {
		if (ContextCreator.getZoneContext().get(this.getID()) == null) return;
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
			if (destZone == null) {
				ContextCreator.logger.warn("generatePrivateEVTrip: destination zone " + oneTrip.getValue() + " not found, skipping trip");
				continue;
			}
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
			if (destZone == null) {
				ContextCreator.logger.warn("generatePrivateGVTrip: destination zone " + oneTrip.getValue() + " not found, skipping trip");
				continue;
			}
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
		this.publicTripTimeIndex = (ContextCreator.getCurrentTick() / GlobalVariables.SIMULATION_DEMAND_REFRESH_INTERVAL) % GlobalVariables.HOUR_OF_DEMAND;
		if (this.lastDemandUpdateHour != this.publicTripTimeIndex) {
			this.futureDemand = 0.0;
		}
		this.refreshModeSplitCacheIfNeeded();
		for (Zone destZone : ContextCreator.getZoneContext().getAll()) {
			int destination = destZone.getID();
			double passRate = ContextCreator.travel_demand.getPublicTravelDemand(this.getID(), destination, this.publicTripTimeIndex)
					* (GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL
							/ (3600 / GlobalVariables.SIMULATION_STEP_SIZE));
		    
			if (passRate > 0) {
				passRate *= GlobalVariables.RH_DEMAND_FACTOR;
				int basePassengers = (int) passRate;
				int numToGenerate = basePassengers
						+ (rand_demand_only.nextDouble() < (passRate - basePassengers) ? 1 : 0);
				double sharableRate = ContextCreator.travel_demand.getPublicTravelDemand(this.getID(), destination, this.publicTripTimeIndex);

				ModeSplitChoice modeSplit = this.getModeSplitChoice(destZone, destination);
				if (modeSplit.hasBusChoice()) {
					for (int i = 0; i < numToGenerate; i++) {
						if (rand_mode_only.nextDouble() >= modeSplit.busShare
								|| !this.generateBusRequest(destZone, destination, modeSplit.busRouteID)) {
							this.generateTaxiRequest(destZone, destination, sharableRate);
						}
					}
					if (this.lastDemandUpdateHour != this.publicTripTimeIndex) {
						this.futureDemand += passRate * modeSplit.taxiShare;
					}
				} else {
					for (int i = 0; i < numToGenerate; i++) {
						this.generateTaxiRequest(destZone, destination, sharableRate);
					}
					if (this.lastDemandUpdateHour != this.publicTripTimeIndex) {
						this.futureDemand += passRate;
					}
				}
			}
		}
	}

	private void refreshModeSplitCacheIfNeeded() {
		if (this.modeSplitCacheHour == this.publicTripTimeIndex) return;
		this.modeSplitCache.clear();
		this.modeSplitCacheHour = this.publicTripTimeIndex;
	}

	private ModeSplitChoice getModeSplitChoice(Zone destZone, int destination) {
		ModeSplitChoice cachedChoice = this.modeSplitCache.get(destination);
		if (cachedChoice != null) {
			return cachedChoice;
		}

		ModeSplitChoice choice = this.computeModeSplitChoice(destZone, destination);
		this.modeSplitCache.put(destination, choice);
		return choice;
	}

	private ModeSplitChoice computeModeSplitChoice(Zone destZone, int destination) {
		BusModeChoice busChoice = this.findBestBusModeChoice(destination);
		if (busChoice == null) {
			return ModeSplitChoice.taxiOnly();
		}

		TravelCost taxiCost = this.getTaxiTravelCost(destZone);
		if (taxiCost == null) {
			return ModeSplitChoice.taxiOnly();
		}

		double busShare = this.getBusSplitRatio(taxiCost.time, taxiCost.distance,
				busChoice.time, busChoice.distance);
		return new ModeSplitChoice(busChoice.routeID, busShare);
	}

	private void generateTaxiRequest(Zone destZone, int destination, double sharableRate) {
		if (rand_share_only.nextDouble() < sharableRate && GlobalVariables.RH_DEMAND_SHARABLE) {
			Request newPass = new Request(this.ID, destination, this.getClosestRoad(false),
					destZone.sampleRoad(true), 1);
			newPass.setWillingToShare(true);
			this.addSharableTaxiPass(newPass, destination);
		} else {
			Request newPass = new Request(this.ID, destination, this.sampleRoad(false),
					destZone.sampleRoad(true), 1);
			this.addTaxiPass(newPass);
		}
		this.numberOfGeneratedTaxiRequest += 1;
	}

	private boolean generateBusRequest(Zone destZone, int destination, int routeID) {
		Integer originRoadID = this.getClosestRoad(false);
		Integer destRoadID = destZone.getClosestRoad(true);
		if (originRoadID == null || destRoadID == null) return false;

		Request newPass = new Request(this.ID, destination, originRoadID, destRoadID, 1);
		newPass.setBusRoute(routeID);
		this.addBusPass(newPass);
		this.numberOfGeneratedBusRequest += 1;
		return true;
	}

	private BusModeChoice findBestBusModeChoice(int destination) {
		if (ContextCreator.bus_schedule == null) return null;
		List<Integer> routeIDs = this.traversingBusRoutes.get(destination);
		if (routeIDs == null || routeIDs.isEmpty()) return null;

		BusModeChoice bestChoice = null;
		for (int routeID : routeIDs) {
			List<Integer> stops = ContextCreator.bus_schedule.getStopZones(routeID);
			if (stops == null || stops.size() < 2) continue;

			for (int originIndex = 0; originIndex < stops.size(); originIndex++) {
				if (!stops.get(originIndex).equals(this.ID)) continue;
				for (int destIndex = originIndex + 1; destIndex < stops.size(); destIndex++) {
					if (!stops.get(destIndex).equals(destination)) continue;

					TravelCost busCost = this.getBusTravelCost(routeID, originIndex, destIndex);
					if (busCost == null) continue;
					if (bestChoice == null || busCost.time < bestChoice.time) {
						bestChoice = new BusModeChoice(routeID, busCost.time, busCost.distance);
					}
				}
			}
		}
		return bestChoice;
	}

	private TravelCost getBusTravelCost(int routeID, int originStopIndex, int destStopIndex) {
		double busTime = 0;
		double busDistance = 0;
		for (int stopIndex = originStopIndex; stopIndex < destStopIndex; stopIndex++) {
			Road fromRoad = ContextCreator.bus_schedule.getStopRoad(routeID, stopIndex);
			Road toRoad = ContextCreator.bus_schedule.getStopRoad(routeID, stopIndex + 1);
			TravelCost segmentCost = this.getRouteTravelCost(fromRoad, toRoad);
			if (segmentCost == null) return null;
			busTime += segmentCost.time;
			busDistance += segmentCost.distance;
		}
		return new TravelCost(busTime, busDistance);
	}

	private TravelCost getTaxiTravelCost(Zone destZone) {
		Integer originRoadID = this.getClosestRoad(false);
		Integer destRoadID = destZone.getClosestRoad(true);
		if (originRoadID == null || destRoadID == null) return null;

		Road originRoad = ContextCreator.getRoadContext().get(originRoadID);
		Road destRoad = ContextCreator.getRoadContext().get(destRoadID);
		return this.getRouteTravelCost(originRoad, destRoad);
	}

	private TravelCost getRouteTravelCost(Road originRoad, Road destRoad) {
		if (originRoad == null || destRoad == null) return null;

		List<Road> path;
		try {
			path = RouteContext.shortestPathRoute(originRoad, destRoad, rand);
		} catch (RuntimeException ex) {
			return null;
		}
		if (path == null) return null;

		double time = 0;
		double distance = 0;
		for (Road road : path) {
			if (road == null) continue;
			time += road.getTravelTime();
			distance += road.getLength();
		}
		if (Double.isNaN(time) || Double.isInfinite(time)
				|| Double.isNaN(distance) || Double.isInfinite(distance)) {
			return null;
		}
		return new TravelCost(time, distance);
	}

	private double getBusSplitRatio(double taxiTime, double taxiDist, double bestBusTime, double bestBusDist) {
		if (!isFinitePositive(taxiTime) || !isFinitePositive(bestBusTime)
				|| !isFinitePositive(taxiDist) || !isFinitePositive(bestBusDist)) {
			return 0.0;
		}

		double taxiUtil = GlobalVariables.MS_ALPHA
				* (GlobalVariables.INITIAL_PRICE_TAXI
						+ GlobalVariables.BASE_PRICE_TAXI * taxiDist / 1609)
				+ GlobalVariables.MS_BETA * (taxiTime / 60) + GlobalVariables.TAXI_BASE;
		double busUtil = GlobalVariables.MS_ALPHA * GlobalVariables.BUS_TICKET_PRICE
				+ GlobalVariables.MS_BETA * (bestBusTime / 60)
				+ GlobalVariables.BUS_BASE;

		return clamp01(logistic((busUtil + 1.0) - taxiUtil));
	}

	private static boolean isFinitePositive(double value) {
		return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0;
	}

	private static double logistic(double value) {
		if (value >= 0) {
			return 1.0 / (1.0 + Math.exp(-value));
		}
		double expValue = Math.exp(value);
		return expValue / (1.0 + expValue);
	}

	private static double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private static class TravelCost {
		final double time;
		final double distance;

		TravelCost(double time, double distance) {
			this.time = time;
			this.distance = distance;
		}
	}

	private static class BusModeChoice extends TravelCost {
		final int routeID;

		BusModeChoice(int routeID, double time, double distance) {
			super(time, distance);
			this.routeID = routeID;
		}
	}

	private static class ModeSplitChoice {
		final int busRouteID;
		final double busShare;
		final double taxiShare;

		ModeSplitChoice(int busRouteID, double busShare) {
			this.busRouteID = busRouteID;
			this.busShare = clamp01(busShare);
			this.taxiShare = 1.0 - this.busShare;
		}

		static ModeSplitChoice taxiOnly() {
			return new ModeSplitChoice(-1, 0.0);
		}

		boolean hasBusChoice() {
			return this.busRouteID >= 0 && this.busShare > 0.0;
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
						Request firstRequest = passQueue.peek();
						if (firstRequest == null || pass_num <= 0) {
							break;
						}
						ElectricTaxi v = eligibleTaxis.poll();
						if (v != null) {
							int assignedPassengers = v.getPassNum();
							if (assignedPassengers >= 4 || assignedPassengers + firstRequest.getNumPeople() > 4) {
								continue;
							}
							ArrayList<Request> tmp_pass = new ArrayList<Request>();
							while (!passQueue.isEmpty()) {
								Request p = passQueue.peek();
								if (p == null || assignedPassengers + p.getNumPeople() > 4) {
									break;
								}
								passQueue.poll();
								tmp_pass.add(p);
								assignedPassengers += p.getNumPeople();
								// Record served passengers
								p.matchedTime = ContextCreator.getCurrentTick();
								this.nRequestForTaxi -= 1;
								this.taxiPickupRequest += 1;
								this.taxiPickupPassengers += p.getNumPeople();
								this.taxiServedPassWaitingTime += p.getCurrentWaitingTime();
								pass_num -= p.getNumPeople();
							}
							if (tmp_pass.isEmpty()) {
								continue;
							}
							ContextCreator.vehicleContext.removeAvailableTaxi(v, this.getID());
							if(v.getState() == Vehicle.PARKING) {
								v.releaseParkingSpot(this);
							}
							else if(v.getState() != Vehicle.CRUISING_TRIP && v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
								ContextCreator.logger.error("Something went wrong, the vehicle is not cruising or parking but still in the zone!");
							}
							v.servePassenger(tmp_pass);
							// Update future supply of the target zone
							Zone destZone = ContextCreator.getZoneContext().get(tmp_pass.get(0).getDestZone());
							if (destZone != null) {
								destZone.addFutureSupply();
							}
							if(pass_num <= 0) break;
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
					v.releaseParkingSpot(this);
				}
				else if(v.getState() != Vehicle.CRUISING_TRIP && v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
					ContextCreator.logger.error("Something went wrong, the vehicle is not cruising or parking but still in the zone!");
				}
				this.taxiPickupRequest += 1;
				this.taxiPickupPassengers += current_taxi_pass.getNumPeople();
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
		ArrayList<Request> passToServe = new ArrayList<Request>();
		int numPeople = 0;
		
		for (Request p : this.requestInQueueForBus) { // If there are passengers
			if (p.getBusRoute() >= 0 && p.getBusRoute() != b.getRouteID()) {
				continue;
			}
			if (!b.servable(p)) {
				continue;
			}
			if (numPeople + p.getNumPeople() > maxPassNum) {
				break;
			}
			passToServe.add(p);
			numPeople = numPeople + p.getNumPeople();
			p.matchedTime = ContextCreator.getCurrentTick();
			b.pickUpPassenger(p);
			this.busServedPassWaitingTime += p.getCurrentWaitingTime();
			this.busPickupRequest += 1;
			this.busPickupPassengers += p.getNumPeople();
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
	            int numToRelocate = (int) relocateRate;
	            if (this.rand_relocate_only.nextDouble() < (relocateRate - numToRelocate)) {
	                numToRelocate++;
	            }

	            // Dispatch vehicles in deterministic (ID-sorted) order
	            for (int i = 0; i < numToRelocate; i++) {
	                if (dispatchIdx >= sortedAvailable.size()) break;
	                ElectricTaxi v = sortedAvailable.get(dispatchIdx++);

	                this.numberOfRelocatedVehicles += 1;
	                if (v.getState() == Vehicle.PARKING) {
	                    v.releaseParkingSpot(this);
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
	        int numToRelocate = (int) relocateRate;
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
					Request gone = passQueue.poll();
					taxiLeavedPassWaitingTime += gone.getCurrentWaitingTime();
					numberOfLeavedTaxiRequest += 1;
					nRequestForTaxi -= 1;
					numberOfLeavedTaxiPassengers += gone.getNumPeople();
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
				Request gone = requestInQueueForTaxi.poll();
				taxiLeavedPassWaitingTime += gone.getCurrentWaitingTime();
				numberOfLeavedTaxiRequest += 1;
				nRequestForTaxi -= 1;
				numberOfLeavedTaxiPassengers += gone.getNumPeople();
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
				Request gone = requestInQueueForBus.poll();
				busLeavedPassWaitingTime += gone.getCurrentWaitingTime();
				this.numberOfLeavedBusRequest += 1;
				nRequestForBus -= 1;
				numberOfLeavedBusPassengers += gone.getNumPeople();
			}
		}
	}

	// Taxi waiting for passenger, extend this if the relocation is based on the taxiWaiting time
	protected void taxiWaitPassenger() {
		this.taxiParkingTime += this.parkingVehicleStock.get() * GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL;
	}

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
		if (!new_pass.hasExplicitMaxWaitingTime()) {
			new_pass.setGeneratedMaxWaitingTime(this.generateWaitingTimeForTaxi());
		}
		this.requestInQueueForTaxi.add(new_pass);
	}

	protected void addSharableTaxiPass(Request new_pass, int destination) {
		this.nRequestForTaxi += 1;
		if (!new_pass.hasExplicitMaxWaitingTime()) {
			new_pass.setGeneratedMaxWaitingTime(this.generateWaitingTimeForTaxi());
		}
		if (!this.sharableRequestForTaxi.containsKey(destination)) {
			this.sharableRequestForTaxi.put(destination, new LinkedList<Request>());
		}
		this.sharableRequestForTaxi.get(destination).add(new_pass);
	}
	
	protected void addBusPass(Request new_pass) {
		this.nRequestForBus += 1;
		if (!new_pass.hasExplicitMaxWaitingTime()) {
			new_pass.setGeneratedMaxWaitingTime(this.generateWaitingTimeForBus());
		}
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

	public void clearRuntimeState() {
		this.nRequestForTaxi = 0;
		this.nRequestForBus = 0;
		this.parkingVehicleStock.set(0);
		this.publicTripTimeIndex = -1;
		this.privateTripTimeIndex = 0;
		this.invalidateModeSplitCache();
		this.lastDemandUpdateHour = -1;
		this.futureDemand = 0.0;
		this.futureSupply.set(0);
		this.vehicleSurplus = 0.0;
		this.vehicleDeficiency = 0.0;
		this.numberOfGeneratedTaxiRequest = 0;
		this.numberOfGeneratedBusRequest = 0;
		this.numberOfGeneratedPrivateEVTrip = 0;
		this.numberOfGeneratedPrivateGVTrip = 0;
		this.arrivedPrivateEVTrip = 0;
		this.arrivedPrivateGVTrip = 0;
		this.taxiPickupRequest = 0;
		this.busPickupRequest = 0;
		this.taxiPickupPassengers = 0;
		this.busPickupPassengers = 0;
		this.taxiPickedUpRequest = 0;
		this.busPickedUpRequest = 0;
		this.taxiPickedUpPassengers = 0;
		this.busPickedUpPassengers = 0;
		this.taxiServedRequest = 0;
		this.busServedRequest = 0;
		this.taxiServedPassengers = 0;
		this.busServedPassengers = 0;
		this.numberOfLeavedTaxiRequest = 0;
		this.numberOfLeavedBusRequest = 0;
		this.numberOfLeavedTaxiPassengers = 0;
		this.numberOfLeavedBusPassengers = 0;
		this.numberOfRelocatedVehicles = 0;
		this.taxiServedPassWaitingTime = 0;
		this.busServedPassWaitingTime = 0;
		this.taxiLeavedPassWaitingTime = 0;
		this.busLeavedPassWaitingTime = 0;
		this.taxiParkingTime = 0;
		this.taxiCruisingTime = 0;
		this.requestInQueueForTaxi.clear();
		this.requestInQueueForBus.clear();
		this.sharableRequestForTaxi.clear();
		this.toAddRequestForTaxi.clear();
		this.toAddRequestForBus.clear();
		this.parkingRequests.clear();
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

	public int getPublicTripTimeIndex() { return this.publicTripTimeIndex; }
	public int getPrivateTripTimeIndex() { return this.privateTripTimeIndex; }
	public int getLastDemandUpdateHour() { return this.lastDemandUpdateHour; }
	public void setPublicTripTimeIndex(int v) {
		this.publicTripTimeIndex = v;
		this.invalidateModeSplitCache();
	}
	public void setPrivateTripTimeIndex(int v) { this.privateTripTimeIndex = v; }
	public void setLastDemandUpdateHour(int v) { this.lastDemandUpdateHour = v; }
	public void invalidateModeSplitCache() {
		this.modeSplitCacheHour = -1;
		if (this.modeSplitCache != null) {
			this.modeSplitCache.clear();
		}
	}

	public int getParkingVehicleStock() { return this.parkingVehicleStock.get(); }
	public void setParkingVehicleStock(int v) { this.parkingVehicleStock.set(v); }
	public Queue<Request> getTaxiRequestQueue() { return this.requestInQueueForTaxi; }
	public Queue<Request> getBusRequestQueue() { return this.requestInQueueForBus; }
	public Map<Integer, Queue<Request>> getSharableRequestForTaxi() { return this.sharableRequestForTaxi; }
	// Requests inserted via insertTaxiPass/insertBusPass that haven't been
	// drained by processToAddPassengers yet. Exposed so the Control API can
	// look up / cancel requests in the same tick they were inserted.
	public Queue<Request> getToAddTaxiRequestQueue() { return this.toAddRequestForTaxi; }
	public Queue<Request> getToAddBusRequestQueue() { return this.toAddRequestForBus; }
	public void setFutureDemand(double v) { this.futureDemand = v; }
	public void setFutureSupply(int v) { this.futureSupply.set(v); }
	public void setVehicleSurplus(double v) { this.vehicleSurplus = v; }
	public void setVehicleDeficiency(double v) { this.vehicleDeficiency = v; }
	public void setNRequestForTaxi(int v) { this.nRequestForTaxi = v; }
	public void setNRequestForBus(int v) { this.nRequestForBus = v; }
}
