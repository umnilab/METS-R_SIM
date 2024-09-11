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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Plan;
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
	private int nRequestForTaxi; // number of requests for Taxi
	private int nRequestForBus; // number of requests for Bus
	private AtomicInteger parkingVehicleStock; // number of parking vehicles at this zone
	private AtomicInteger cruisingVehicleStock;  // number of cruising vehicles at this zone
	private int currentHour = -1;
	private int currentTimeIndex = 0;
	private int lastTravelTimeUpdateHour = -1; // the last time for updating the travel time estimation
	
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
	
	/* Protected variables */
	protected Coordinate coord;
	protected Random rand; // Random seed for things other than demand generation
	protected Random rand_demand_only; // Random seed only for demand generation
	protected Random rand_diffusion_only; // Random seed only for demand generation
	protected Random rand_mode_only; // Random seed only for demand generation
	protected Random rand_share_only; // Random seed only for demand generation
	protected Random rand_relocate_only; // Random seed only for demand generation
	protected int ID;
	protected Queue<Request> requestInQueueForTaxi; // Nonsharable passenger queue for taxis
	protected Map<Integer, Queue<Request>> sharableRequestForTaxi; // Shareable passenger for taxis
	protected Queue<Request> requestInQueueForBus; // Passenger queue for bus
	protected List<Integer> neighboringZones; // Sorted neighboring Zone from the closest to the farthest
	protected List<Integer> neighboringLinks; // Surrounding links for vehicle to cruise if there is no avaiable parking space
	// For multi-thread mode
	protected ConcurrentHashMap<Integer, Integer> nearestZoneWithBus; // For collaborative taxi and transit
	
	/* Public variables */
	// Parameters for mode choice model
	public List<Integer> busReachableZone;
	public ConcurrentHashMap<Integer, Float> busGap;
	public ConcurrentHashMap<Integer, Float> taxiTravelTime;
	public ConcurrentHashMap<Integer, Float> taxiTravelDistance;
	public ConcurrentHashMap<Integer, Float> busTravelTime;
	public ConcurrentHashMap<Integer, Float> busTravelDistance;

	// Service metrics
	public int numberOfGeneratedTaxiRequest;
	public int numberOfGeneratedBusRequest;
	public int numberOfGeneratedCombinedRequest;
	public int taxiPickupRequest;
	public int busPickupRequest;
	public int combinePickupPart1;
	public int combinePickupPart2;
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
		this.sharableRequestForTaxi = new HashMap<Integer, Queue<Request>>();
		this.requestInQueueForBus = new LinkedList<Request>();
		this.requestInQueueForTaxi = new LinkedList<Request>();
		this.nRequestForBus = 0;
		this.nRequestForTaxi = 0;
		this.busReachableZone = new ArrayList<Integer>();
		this.busGap = new ConcurrentHashMap<Integer, Float>();
		this.zoneType = type;
		// Initialize metrices
		this.numberOfGeneratedTaxiRequest = 0;
		this.numberOfGeneratedBusRequest = 0;
		this.numberOfGeneratedCombinedRequest = 0;
		this.taxiPickupRequest = 0;
		this.busPickupRequest = 0;
		this.combinePickupPart1 = 0;
		this.combinePickupPart2 = 0;
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
		this.taxiTravelTime = new ConcurrentHashMap<Integer, Float>();
		this.taxiTravelDistance = new ConcurrentHashMap<Integer, Float>();
		this.busTravelTime = new ConcurrentHashMap<Integer, Float>();
		this.busTravelDistance = new ConcurrentHashMap<Integer, Float>();
		this.nearestZoneWithBus = new ConcurrentHashMap<Integer, Integer>();
		this.neighboringZones = new ArrayList<Integer>();
		this.neighboringLinks = new ArrayList<Integer>();
		this.toAddRequestForTaxi = new ConcurrentLinkedQueue<Request>();
		this.toAddRequestForBus = new ConcurrentLinkedQueue<Request>();
		this.parkingVehicleStock = new AtomicInteger(0); 
		this.cruisingVehicleStock = new AtomicInteger(0); 
		this.futureDemand = 0.0;
		this.futureSupply = new AtomicInteger(0);
		this.vehicleSurplus = 0.0;
		this.vehicleDeficiency = 0.0;
		if (GlobalVariables.PROACTIVE_RELOCATION) {
			H = GlobalVariables.SIMULATION_RH_MAX_CRUISING_TIME / GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL;
		}
	}
    
	public int getID() {
		return ID;
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
	
	// Main logic goes here
	public void ridehailingStep() {
		this.processToAddPassengers();
		// Happens at time step t
		this.servePassengerByTaxi();
		this.relocateTaxi();
	}
	
	public void step() {
		if (ContextCreator.getCurrentTick() == GlobalVariables.SIMULATION_STOP_TIME) {
			// Skip the last update which is outside of the study period
			return;
		}
		this.updateTravelTimeEstimation();

		// Happens between t and t + 1
		this.passengerWaitTaxi();
		this.passengerWaitBus();
		this.taxiWaitPassenger();
		this.generatePassenger();
		this.generatePrivateEVTrip();
		this.generatePrivateGVTrip();
		
		currentTimeIndex += 1;
	}
	
	// Generate EV/GV trips
	protected void generatePrivateEVTrip() {
		// Loop over private trip demand
		for (Entry<Integer, Integer> oneTrip: ContextCreator.travel_demand.getPrivateEVTravelDemand(currentTimeIndex, ID).entrySet()) {
			ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(oneTrip.getKey());
			Zone destZone =  ContextCreator.getZoneContext().get(oneTrip.getValue());
			if(v != null) { // If vehicle exists
				if (v.getState() == Vehicle.NONE_OF_THE_ABOVE) {
					// If vehicle is not on road
					v.initializePlan(this.getIntegerID(), this.sampleCoord(), (int) ContextCreator.getCurrentTick());
					v.addPlan(oneTrip.getValue(), destZone.sampleCoord(), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure();
				}
				else { // If vehicle is on road
					ContextCreator.logger.warn("The private EV: " + oneTrip.getKey() + " is currently on the road at time index"+ currentTimeIndex +" , maybe there are two trips for the same vehicle that are too close?");
				}
			}
			else { // If vehicle does not exists
				// Create vehicle
				v = new ElectricVehicle(Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE);
				// Assign trips
				v.initializePlan(this.getIntegerID(), this.sampleCoord(), (int) ContextCreator.getCurrentTick());
				v.addPlan(oneTrip.getValue(), destZone.sampleCoord(), (int) ContextCreator.getNextTick());
				v.setNextPlan();
				v.setState(Vehicle.PRIVATE_TRIP);
				v.departure();
				ContextCreator.getVehicleContext().registerPrivateEV(oneTrip.getKey(), v);
			}
		}
	}
	
	protected void generatePrivateGVTrip() {
		for (Entry<Integer, Integer> oneTrip: ContextCreator.travel_demand.getPrivateGVTravelDemand(currentTimeIndex, ID).entrySet()) {
			Vehicle v = ContextCreator.getVehicleContext().getPrivateGV(oneTrip.getKey());
			Zone destZone =  ContextCreator.getZoneContext().get(oneTrip.getValue());
			if(v != null) { // If vehicle exists
				if (v.getState() == Vehicle.NONE_OF_THE_ABOVE) {
					// If vehicle is not on road
					v.initializePlan(this.getIntegerID(), this.sampleCoord(), (int) ContextCreator.getCurrentTick());
					v.addPlan(oneTrip.getValue(), destZone.sampleCoord(), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure();
				}
				else { // If vehicle is on road
					ContextCreator.logger.warn("The private GV: " + oneTrip.getKey() + " is currently on the road at time index"+ currentTimeIndex +" , maybe there are two trips for the same vehicle that are too close?");
				}
			}
			else { // If vehicle does not exists
				// Create vehicle
				v = new Vehicle(Vehicle.GV, Vehicle.NONE_OF_THE_ABOVE);
				// Assign trips
				v.initializePlan(this.getIntegerID(), this.sampleCoord(), (int) ContextCreator.getCurrentTick());
				v.addPlan(oneTrip.getValue(), destZone.sampleCoord(), (int) ContextCreator.getNextTick());
				v.setNextPlan();
				v.setState(Vehicle.PRIVATE_TRIP);
				v.departure();
				ContextCreator.getVehicleContext().registerPrivateGV(oneTrip.getKey(), v);
			}
		}
	}
	
	
	// Generate passenger
	protected void generatePassenger() {
		this.currentHour = (int) Math.floor(ContextCreator.getCurrentTick() / GlobalVariables.SIMULATION_DEMAND_REFRESH_INTERVAL) % GlobalVariables.HOUR_OF_DEMAND;
		if (this.lastDemandUpdateHour != this.currentHour) {
			this.futureDemand = 0.0;
		}
		for (Zone destZone : ContextCreator.getZoneContext().getAll()) {
			int destination = destZone.getIntegerID();
			double passRate = ContextCreator.travel_demand.getPublicTravelDemand(this.getIntegerID(), destination, this.currentHour)
					* (GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL
							/ (3600 / GlobalVariables.SIMULATION_STEP_SIZE));
		    
			if (passRate > 0) {
				passRate *= GlobalVariables.RH_DEMAND_FACTOR;
				double numToGenerate = Math.floor(passRate)
						+ (rand_demand_only.nextDouble() < (passRate - Math.floor(passRate)) ? 1 : 0);
				double sharableRate = ContextCreator.travel_demand.getPublicTravelDemand(this.getIntegerID(), destination, this.currentHour);
				if (busReachableZone.contains(destination)) {
					// No combinational mode like taxi-bus or bus-taxi
					float threshold = getSplitRatio(destination, false);
					for (int i = 0; i < numToGenerate; i++) {
						if (rand_mode_only.nextDouble() > threshold) {
							if (rand_share_only.nextDouble()<sharableRate && GlobalVariables.RH_DEMAND_SHARABLE) { // Sharable requests start from the same loc
								Request new_pass = new Request(this.ID, destination, this.getCoord(), 
										destZone.sampleCoord(), true
										); 
								this.addSharableTaxiPass(new_pass, destination);
							} else {
								Request new_pass = new Request(this.ID, destination, this.sampleCoord(), 
										destZone.sampleCoord(), false
										); 
								this.addTaxiPass(new_pass);
							}
							this.numberOfGeneratedTaxiRequest += 1;
						} else { // Bus always start and end at the centroid
							Request new_pass = new Request(this.ID, destination, this.getCoord(), 
									ContextCreator.getZoneContext().get(destination).getCoord(), 
									false); 
							this.addBusPass(new_pass);
							this.numberOfGeneratedBusRequest += 1;
						}
					}
					if (this.lastDemandUpdateHour != this.currentHour) {
						this.futureDemand+= (passRate * threshold);
					}
				} else if (GlobalVariables.COLLABORATIVE_EV) {
					if (this.zoneType == 0 && this.nearestZoneWithBus.containsKey(destination)) { // Normal zone, first take taxi, then bus
						// Split between taxi and taxi-bus combined,
						float threshold = getSplitRatio(destination, true);
						for (int i = 0; i < numToGenerate; i++) {
							if (rand_mode_only.nextDouble() > threshold) {
								if (rand_share_only.nextDouble()<sharableRate && GlobalVariables.RH_DEMAND_SHARABLE) { // Sharable requests start from the same loc
									Request new_pass = new Request(this.ID, destination, this.getCoord(), 
											destZone.sampleCoord(),true
											); 
									this.addSharableTaxiPass(new_pass, destination);
								} else {
									Request new_pass = new Request(this.ID, destination, this.sampleCoord(), 
											destZone.sampleCoord(),false
											); 
									this.addTaxiPass(new_pass);
								}
								this.numberOfGeneratedTaxiRequest += 1;
							} 
							else {
								// First generate its activity plan
								Queue<Plan> activityPlan = new LinkedList<Plan>();
								Plan plan = new Plan(this.nearestZoneWithBus.get(destination),
										ContextCreator.getZoneContext().get(this.nearestZoneWithBus.get(destination)).getCoord(), ContextCreator.getNextTick());
								activityPlan.add(plan);
								Plan plan2 = new Plan(destination,
										ContextCreator.getZoneContext().get(destination).getCoord(),
										ContextCreator.getNextTick());
								activityPlan.add(plan2);
								Request new_pass = new Request(this.ID, this.sampleCoord(), activityPlan); 
								this.addTaxiPass(new_pass);
								this.numberOfGeneratedCombinedRequest += 1;
							}
						}
						if (this.lastDemandUpdateHour != this.currentHour) {
							this.futureDemand+=(passRate * threshold);
						}
					} else if (this.zoneType == 1 && this.nearestZoneWithBus.containsKey(destination)) { // Hub, first bus then taxi
						// Split between taxi and taxi-bus combined,
						float threshold = getSplitRatio(destination, true);
						for (int i = 0; i < numToGenerate; i++) {
							if (rand_mode_only.nextDouble() > threshold) {
								if (rand_share_only.nextDouble()<sharableRate && GlobalVariables.RH_DEMAND_SHARABLE) { // Sharable requests start from the same loc
									Request new_pass = new Request(this.ID, destination, this.getCoord(), 
											destZone.sampleCoord(),true
											); 
									this.addSharableTaxiPass(new_pass, destination);
								} else {
									Request new_pass = new Request(this.ID, destination, this.sampleCoord(), 
											destZone.sampleCoord(),false
											); 
									this.addTaxiPass(new_pass);
								}
								this.numberOfGeneratedTaxiRequest += 1;
							} 
							else {
								// First generate its activity plan
								Queue<Plan> activityPlan = new LinkedList<Plan>();
								Plan plan = new Plan(this.nearestZoneWithBus.get(destination),
										ContextCreator.getZoneContext().get(this.nearestZoneWithBus.get(destination)).getCoord(), ContextCreator.getNextTick());
								activityPlan.add(plan);
								Plan plan2 = new Plan(destination,
										destZone.sampleCoord(),
										ContextCreator.getNextTick());
								activityPlan.add(plan2);
								Request new_pass = new Request(this.ID, this.getCoord(), activityPlan); 
								this.addBusPass(new_pass);
								this.numberOfGeneratedCombinedRequest += 1;
							}
						}
						if (this.lastDemandUpdateHour != this.currentHour) {
							this.futureDemand= (passRate * threshold);
						}
					} 
					else {
						// Taxi only
						for (int i = 0; i < numToGenerate; i++) {
							if (rand_share_only.nextDouble()<sharableRate && GlobalVariables.RH_DEMAND_SHARABLE) { // Sharable requests start from the same loc
								Request new_pass = new Request(this.ID, destination, this.getCoord(), 
										destZone.sampleCoord(),true
										); 
								this.addSharableTaxiPass(new_pass, destination);
							} else {
								Request new_pass = new Request(this.ID, destination, this.sampleCoord(), 
										destZone.sampleCoord(),false
										); 
								this.addTaxiPass(new_pass);
							}
							this.numberOfGeneratedTaxiRequest += 1;
						}
						if (this.lastDemandUpdateHour != this.currentHour) {
							this.futureDemand+=(passRate);
						}
					}
				} else {
					// Taxi only
					for (int i = 0; i < numToGenerate; i++) {
						if (rand_share_only.nextDouble()<sharableRate && GlobalVariables.RH_DEMAND_SHARABLE) { // Sharable requests start from the same loc
							Request new_pass = new Request(this.ID, destination, this.getCoord(), 
									destZone.sampleCoord(),true
									); 
							this.addSharableTaxiPass(new_pass, destination);
						} else {
							Request new_pass = new Request(this.ID, destination, this.sampleCoord(), 
									destZone.sampleCoord(),false
									); 
							this.addTaxiPass(new_pass);
						}
						this.numberOfGeneratedTaxiRequest += 1;
					}
					if (this.lastDemandUpdateHour != this.currentHour) {
						this.futureDemand+=(passRate);
					}
				}
			}
		}
		
		this.vehicleSurplus = this.getVehicleStock() - this.nRequestForTaxi + 0.8 * this.futureSupply.get();
		this.vehicleSurplus = this.vehicleSurplus>0?this.vehicleSurplus:0;
		this.vehicleDeficiency = this.nRequestForTaxi - this.getVehicleStock() - ContextCreator.getVehicleContext().getRelocationTaxi(this.getID()).size();
		this.vehicleDeficiency = this.vehicleDeficiency>0?this.vehicleDeficiency:0;
		
		ContextCreator.logger.debug("current buss pass is" + this.numberOfGeneratedBusRequest);
		ContextCreator.logger.debug("current taxi pass is" + this.numberOfGeneratedTaxiRequest);

		if (this.lastDemandUpdateHour != this.currentHour) {
			this.lastDemandUpdateHour = this.currentHour;
		}
	}

	// Serve passenger
	public void servePassengerByTaxi() {
		// Ridesharing matching for the sharable passengers. Current implementation: If
		// the passenger goes to the same place, pair them together.
		// First serve sharable passengers
		if (this.sharableRequestForTaxi.size() > 0) {
			for (Queue<Request> passQueue : this.sharableRequestForTaxi.values()) {
				if (passQueue.size() > 0) {
					int pass_num = passQueue.size();
					int v_num = this.getVehicleStock();
					v_num = (int) Math.min(Math.ceil(pass_num / 4.0), v_num);
					for (int i = 0; i < v_num; i++) {
						ElectricTaxi v = ContextCreator.getVehicleContext().getVehiclesByZone(this.ID).poll();
						if (v != null) {
							if(v.getState() == Vehicle.PARKING) {
								this.removeOneParkingVehicle();
							}else if(v.getState() == Vehicle.CRUISING_TRIP) {
								this.removeOneCruisingVehicle();
							}
							else {
								ContextCreator.logger.error("Something went wrong, the vehicle is not cruising or parking but still in the zone!");
							}
							ArrayList<Request> tmp_pass = new ArrayList<Request>();
							for (int j = 0; j < Math.min(4 , pass_num); j++) { // Vehicle capacity is 4
								Request p = passQueue.poll();
								tmp_pass.add(p);
								// Record served passengers
								this.nRequestForTaxi -= 1;
								this.taxiPickupRequest += 1;
								this.taxiServedPassWaitingTime += p.getCurrentWaitingTime();
							}
							v.servePassenger(tmp_pass);
							// Update future supply of the target zone
							ContextCreator.getZoneContext().get(tmp_pass.get(0).getDestination()).addFutureSupply();
							pass_num = pass_num - tmp_pass.size();
						}
					}
				}
			}
		}

		int curr_size = this.requestInQueueForTaxi.size();
		for (int i = 0; i < curr_size; i++) {
			ElectricTaxi v = ContextCreator.getVehicleContext().getVehiclesByZone(this.ID).poll();
			if (v != null) {
				Request current_taxi_pass = this.requestInQueueForTaxi.poll();
				if(v.getState() == Vehicle.PARKING) {
					this.removeOneParkingVehicle();
				}else if(v.getState() == Vehicle.CRUISING_TRIP) {
					this.removeOneCruisingVehicle();
				}
				else {
					ContextCreator.logger.error("Something went wrong, the vehicle is not cruising or parking but still in the zone!");
				}
				
				if (current_taxi_pass.lenOfActivity() > 1) {
					v.passengerWithAdditionalActivityOnTaxi.add(current_taxi_pass);
					this.combinePickupPart1 += 1;
				} else if (current_taxi_pass.lenOfActivity() == 0) {
					this.taxiPickupRequest += 1;
				} else {
					// The length of activity is 1 which means this is the second part of a combined
					// trip
					this.combinePickupPart2 += 1;
				}
				v.servePassenger(Arrays.asList(current_taxi_pass));
				// Update future supply of the target zone
				ContextCreator.getZoneContext().get(current_taxi_pass.getDestination()).addFutureSupply();
				// Record served passenger
				this.nRequestForTaxi -= 1;
				this.taxiServedPassWaitingTime += current_taxi_pass.getCurrentWaitingTime();
			} else {
				break; // no vehicle
			}
		}
	}	
	// Serve passenger using Bus
	public ArrayList<Request> servePassengerByBus(int maxPassNum, ArrayList<Integer> busStop) {
		ArrayList<Request> passOnBoard = new ArrayList<Request>();
		for (Request p : this.requestInQueueForBus) { // If there are passengers
			if (busStop.contains(p.getDestination())) {
				if (passOnBoard.size() >= maxPassNum) {
					break;
				} else { // passenger get on board
					passOnBoard.add(p);
					this.busServedPassWaitingTime += p.getCurrentWaitingTime();
					if (p.lenOfActivity() > 1) {
						this.combinePickupPart1 += 1;
					} else if (p.lenOfActivity() == 1) { // count it only when the last trip starts
						this.combinePickupPart2 += 1;
					} else if (p.lenOfActivity() == 0) {
						this.busPickupRequest += 1;
					}
				}
			}
		}
		this.nRequestForBus -= passOnBoard.size();
		this.requestInQueueForBus.removeAll(passOnBoard);
		return passOnBoard;
	}

	// Relocate when the vehicleStock is negative
	// There are two implementations: 1. Using myopic info; 2. Using future estimation (PROACTIVE_RELOCATION).
	protected void relocateTaxi() {
		// Decide the number of relocated vehicle with the precaution on potential
		// future vehicle shortage/overflow
		if (this.getFutureSupply() < 0) {
			ContextCreator.logger.error("Something went wrong, the futureSupply becomes negative!");
		}
		
		if (this.hasEnoughTaxi(H)) {
			boolean systemHasVeh = false;
			
			// Figure out demand in neighboring zones
			double tot_demand = 0;
			for(Integer z: this.neighboringZones) {
				if(!ContextCreator.getZoneContext().get(z).hasEnoughTaxi(H)) {
					tot_demand += ContextCreator.getZoneContext().get(z).getVehicleDeficiency();
				}
			}
			
			for(Integer z: this.neighboringZones) {
				if(!ContextCreator.getZoneContext().get(z).hasEnoughTaxi(H)) {
					double vehicle_deficiency = ContextCreator.getZoneContext().get(z).getVehicleDeficiency();
					double tot_supply = 0;
					for(Integer z2: ContextCreator.getZoneContext().get(z).neighboringZones) {
						if(ContextCreator.getZoneContext().get(z2).hasEnoughTaxi(H)) {
							tot_supply += ContextCreator.getZoneContext().get(z2).getVehicleSurplus() - H * ContextCreator.getZoneContext().get(z2).getFutureDemand(); 
						}
					}
				
					double relocateRate = 1.25 *  Math.min(1, tot_supply/tot_demand) * vehicle_deficiency * (this.vehicleSurplus - H*this.getFutureDemand()) / tot_supply; // relocate more vehicles in case it did not made to the destination.
					int numToRelocate = (int) Math.floor(relocateRate)
							+ (this.rand_relocate_only.nextDouble() < (relocateRate - Math.floor(relocateRate)) ? 1 : 0);
					for (int i = 0; i < numToRelocate; i++) {
						systemHasVeh = false;
						// Relocate from zones with sufficient supply
						ElectricTaxi v = ContextCreator.getVehicleContext().getVehiclesByZone(this.getID())
									.poll();
						if (v != null) {
							ContextCreator.getZoneContext().get(z).numberOfRelocatedVehicles += 1;
							if(v.getState() == Vehicle.PARKING) {
								this.removeOneParkingVehicle();
							}else {
								this.removeOneCruisingVehicle();
							}
							v.relocation(this.ID, z);
							ContextCreator.getZoneContext().get(z).addFutureSupply();
							systemHasVeh = true;
						}
					}
					if (!systemHasVeh) {
						break;
					}
				}
			}
		}
		else {
			double relocateRate = this.vehicleDeficiency; 
			int numToRelocate = (int) Math.floor(relocateRate)
					+ (this.rand_relocate_only.nextDouble() < (relocateRate - Math.floor(relocateRate)) ? 1 : 0);
			List<ElectricTaxi> relocateVeh = ContextCreator.getVehicleContext().getRelocationTaxi(this.getID());
			for (ElectricTaxi v: relocateVeh) {
				int curr_dest = v.getDestID();
				if(numToRelocate > 0 && v.modifyPlan(this.getIntegerID(), v.nextJunction().getCoord())){
					ContextCreator.getZoneContext().get(curr_dest).futureSupply.addAndGet(-1);
					v.setState(Vehicle.INACCESSIBLE_RELOCATION_TRIP);
					ContextCreator.getVehicleContext().removeRelocationTaxi(v);
					this.addFutureSupply();
					numToRelocate -= 1;
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
	    this.taxiCruisingTime += this.cruisingVehicleStock.get() * GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL;
	}

	// Split taxi and bus passengers via a discrete choice model
	// Distance unit in mile
	// Time unit in minute
	private float getSplitRatio(int destID, boolean flag) {
		if (flag) {
			// For collaborative EV services
			if (busTravelTime.containsKey(destID) && taxiTravelTime.containsKey(destID)) {
				double taxiUtil = GlobalVariables.MS_ALPHA
						* (GlobalVariables.INITIAL_PRICE_TAXI
								+ GlobalVariables.BASE_PRICE_TAXI * taxiTravelDistance.get(destID) / 1609)
						+ GlobalVariables.MS_BETA * (taxiTravelTime.get(destID) / 60 + 5) + GlobalVariables.TAXI_BASE;
				// Here the busTravelDistance is actually the taxi travel distance for
				// travelling to the closest zone with bus
				double busUtil = (float) (GlobalVariables.MS_ALPHA
						* (GlobalVariables.BUS_TICKET_PRICE + GlobalVariables.INITIAL_PRICE_TAXI
								+ GlobalVariables.BASE_PRICE_TAXI * busTravelDistance.get(destID) / 1609)
						+ GlobalVariables.MS_BETA * (busTravelTime.get(destID) / 60 + this.busGap.get(destID) / 2 + 5)
						+ GlobalVariables.BUS_BASE);

				return (float) (Math.exp(1) / (Math.exp(taxiUtil - busUtil) + Math.exp(1)));
			} else {
				return 0;
			}
		} else {
			if (busTravelTime.containsKey(destID) && taxiTravelTime.containsKey(destID)) {
				double taxiUtil = GlobalVariables.MS_ALPHA
						* (GlobalVariables.INITIAL_PRICE_TAXI
								+ GlobalVariables.BASE_PRICE_TAXI * taxiTravelDistance.get(destID) / 1609)
						+ GlobalVariables.MS_BETA * (taxiTravelTime.get(destID) / 60 + 5) + GlobalVariables.TAXI_BASE;
				double busUtil = (float) (GlobalVariables.MS_ALPHA * GlobalVariables.BUS_TICKET_PRICE
						+ GlobalVariables.MS_BETA * (busTravelTime.get(destID) / 60 + this.busGap.get(destID) / 2)
						+ GlobalVariables.BUS_BASE);
				
				return (float) (Math.exp(1) / (Math.exp(taxiUtil - busUtil) + Math.exp(1)));
			} else {
				return 0;
			}
		}
	}

	// Update travel time estimation for taxi
	public void updateTravelTimeEstimation() {
		// Get current tick
		int tickcount = ContextCreator.getCurrentTick();
		int hour = (int) Math.floor(tickcount / GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL);
		if (this.lastTravelTimeUpdateHour < hour) {
			if(GlobalVariables.NUM_OF_BUS > 0) {
				this.taxiTravelTime.clear();
				this.taxiTravelDistance.clear();
				// is hub
				if (this.zoneType == 1) {
					// shortest path travel time from hubs to all other zones
					for (Zone z2 : ContextCreator.getZoneContext().getAll()) {
						if (this.getIntegerID() != z2.getIntegerID()) {
							double travel_time = 0;
							double travel_distance = 0;
							List<Road> path = RouteContext.shortestPathRoute(this.getCoord(), z2.getCoord(), null);
							if (path != null) {
								for (Road r : path) {
									travel_distance += r.getLength();
									travel_time += r.getTravelTime();
								}
							}
							this.taxiTravelDistance.put(z2.getIntegerID(), (float) travel_distance);
							this.taxiTravelTime.put(z2.getIntegerID(), (float) travel_time);
						}
					}
				} else {
					// Shortest path to hubs
					for (int z2_id :  ContextCreator.getZoneContext().HUB_INDEXES) {
						Zone z2 = ContextCreator.getZoneContext().get(z2_id);
						if (this.getIntegerID() != z2.getIntegerID()) {
							double travel_time = 0;
							double travel_distance = 0;
							List<Road> path = RouteContext.shortestPathRoute(this.getCoord(), z2.getCoord(), null);
							if (path != null) {
								for (Road r : path) {
									travel_distance += r.getLength();
									travel_time += r.getTravelTime();
								}
							}
							this.taxiTravelDistance.put(z2.getIntegerID(), (float) travel_distance);
							this.taxiTravelTime.put(z2.getIntegerID(), (float) travel_time);
						}
					}
				}
				if (GlobalVariables.COLLABORATIVE_EV) {
					this.updateCombinedTravelEstimation();
				}
			}
			this.lastTravelTimeUpdateHour = hour;
		}
	}

	// Update travel time estimation for modes combining taxi and bus
	public void updateCombinedTravelEstimation() {
		if (this.zoneType == 1) { // hub
			for (Zone z2 : ContextCreator.getZoneContext().getAll()) {
				if (!busReachableZone.contains(z2.getIntegerID())) { // Find the closest zone with bus that can connect
																		// to this hub
					Zone z = ContextCreator.getCityContext().findNearestZoneWithBus(z2.getCoord(), this.getIntegerID());
					if (z != null) {
						this.nearestZoneWithBus.put(z2.getIntegerID(), z.getID());
					}
				}
				if (this.nearestZoneWithBus.containsKey(z2.getIntegerID())) {
					double travel_time2 = 0;
					double travel_distance2 = 0;
					List<Road> path2 = RouteContext.shortestPathRoute(
							ContextCreator.getZoneContext().get(this.nearestZoneWithBus.get(z2.getIntegerID())).getCoord(), z2.getCoord(), null);
					if (path2 != null) {
						for (Road r : path2) {
							travel_distance2 += r.getLength();
							travel_time2 += r.getTravelTime();
						}
						busGap.put(z2.getIntegerID(),
								ContextCreator.getZoneContext().get(this.nearestZoneWithBus.get(z2.getIntegerID())).busGap.get(this.getIntegerID()));
						busTravelDistance.put(z2.getIntegerID(), (float) travel_distance2); // only the distance to the
																							// closest zone with bus
						busTravelTime.put(z2.getIntegerID(), (float) travel_time2 + this.busTravelTime
								.get(this.nearestZoneWithBus.get(z2.getIntegerID()))); // time for bus
																										// and taxi
																										// combined
					}
				}
			}
		} else {
			// Shortest path to hubs
			for (int z2_id :  ContextCreator.getZoneContext().HUB_INDEXES) {
				if (!busReachableZone.contains(z2_id)) { // Find the closest zone with bus that can connect to this hub
					Zone z = ContextCreator.getCityContext().findNearestZoneWithBus(this.getCoord(), z2_id);
					if (z != null) {
						this.nearestZoneWithBus.put(z2_id, z.getID());
					}
				}
				if (this.nearestZoneWithBus.containsKey(z2_id)) {
					double travel_time2 = 0;
					double travel_distance2 = 0;
					List<Road> path2 = RouteContext.shortestPathRoute(this.getCoord(),
							ContextCreator.getZoneContext().get(this.nearestZoneWithBus.get(z2_id)).getCoord(), this.rand);
					if (path2 != null) {
						for (Road r : path2) {
							travel_distance2 += r.getLength();
							travel_time2 += r.getTravelTime();
						}
						busGap.put(z2_id, ContextCreator.getZoneContext().get(this.nearestZoneWithBus.get(z2_id)).busGap.get(z2_id));
						busTravelDistance.put(z2_id, (float) travel_distance2); // only the distance to the closest zone
																				// with bus
						busTravelTime.put(z2_id,
								(float) travel_time2 + ContextCreator.getZoneContext().get(this.nearestZoneWithBus.get(z2_id)).busTravelTime.get(z2_id)); // time
																														// for
																														// bus
																														// and
																														// taxi
																														// combined
					}
				}
			}
		}
	}
	
	// Update bus info and travel time estimation
	public void setBusInfo(int destID, float vehicleGap) {
		if (busGap.containsKey(destID)) {
			this.busGap.put(destID, Math.min(vehicleGap, this.busGap.get(destID)));
		} else {
			this.busGap.put(destID, vehicleGap);
			this.busReachableZone.add(destID);
		}
	}
	
	// Find the passengers whose bus routes no longer exist
	public void reSplitPassengerDueToBusRescheduled() {	
		List<Request> rePass = new ArrayList<Request>();
		for (Request p : this.requestInQueueForBus) {
			if (!this.busReachableZone.contains(p.getDestination())) { // No bus can serve this passenger anymore
				rePass.add(p);
				if (p.lenOfActivity() >= 2) {
					this.numberOfGeneratedCombinedRequest -= 1;
				} else if (p.lenOfActivity() == 1) {
					// do nothing as it will be counted served correctly when it is served
				} else {
					this.numberOfGeneratedBusRequest -= 1;
				}
				nRequestForBus -= 1;
			}
		}
		this.requestInQueueForBus.removeAll(rePass);
		// Assign these passengers to taxis
		// If the passenger planned to used combined trip, skip the first trip
		for (Request p : rePass) {
			if (p.lenOfActivity() >= 2) {
				p.moveToNextActivity();
				p.setOrigin(this.ID);
				p.clearActivityPlan();
			} else if (p.lenOfActivity() == 1) { // Is trying to finish the second trip of its plan
				this.numberOfGeneratedTaxiRequest -= 1; // do nothing as it is still considered as a combined trip
				                                        // here -1 will cancel with the +1 below
			}

			this.toAddRequestForTaxi.add(p);
			this.numberOfGeneratedTaxiRequest += 1;
		}

	}
	
	// Generate passenger waiting time for taxi
	// this is in Zone class since the observations
	// are usually associate with Zones
	// Assume the maximum waiting time for taxi is 15 minutes (we treated as the constraints: service quality demand)
    protected int generateWaitingTimeForTaxi() {
		return (int) (ContextCreator.travel_demand.getWaitingThreshold(this.currentHour) / GlobalVariables.SIMULATION_STEP_SIZE);
	}
    
    // Generate passenger waiting time for bus
 	// Assume bus passenger will keep waiting (we treated this as an objective: evaluated by the simulation output)
    protected int generateWaitingTimeForBus() {
 		return (int) GlobalVariables.SIMULATION_STOP_TIME;
 	}
	
	// Get/Set states
    protected void processToAddPassengers() {
		for (Request q = this.toAddRequestForTaxi.poll(); q != null; q = this.toAddRequestForTaxi.poll()) {
			this.addTaxiPass(q);
		}
		for (Request q = this.toAddRequestForBus.poll(); q != null; q = this.toAddRequestForBus.poll()) {
			this.addBusPass(q);
		}
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
	
	public void addCruisingVehicleStock(int vehicle_num) {
		this.cruisingVehicleStock.addAndGet(vehicle_num);
	}

	public void addOneParkingVehicle() {
		this.parkingVehicleStock.addAndGet(1);
	}
	
	public void addOneCruisingVehicle() {
		this.cruisingVehicleStock.addAndGet(1);
	}
	
	public void removeOneParkingVehicle() {
		if (this.parkingVehicleStock.get() - 1 < 0) {
			ContextCreator.logger.error(this.ID + " out of stock, vehicle_num: " + this.parkingVehicleStock.get());
			return;
		}
		this.addParkingVehicleStock(-1);
	}
	
	public void removeOneCruisingVehicle() {
		if (this.cruisingVehicleStock.get() - 1 < 0) {
			ContextCreator.logger.error(this.ID + " out of stock, vehicle_num: " + this.cruisingVehicleStock.get());
			return;
		}
		this.addCruisingVehicleStock(-1);
	}

	public int getVehicleStock() {
		return this.parkingVehicleStock.get() + this.cruisingVehicleStock.get();
	}

	public int getZoneType() {
		return this.zoneType;
	}
	
	public boolean hasEnoughTaxi(double H) { // H represents future steps (horizon) to consider
		return (this.vehicleSurplus - H * getFutureDemand()) > 0 && this.vehicleDeficiency == 0;
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
	
	public int getTaxiPassengerNum() {
		return nRequestForTaxi;
	}

	public int getBusPassengerNum() {
		return nRequestForBus;
	}
	
	public Coordinate sampleCoord() {
		if(this.zoneType == Zone.HUB || !GlobalVariables.DEMAND_DIFFUSION){
			return this.getCoord();
		}else {
			return this.getNeighboringCoord(rand_diffusion_only.nextInt(this.getNeighboringLinkSize()));
		}
	}
	
	public Coordinate getNeighboringCoord(int index) {
		return ContextCreator.getRoadContext().get(this.getNeighboringLink(index)).getStartCoord();
	}

	public int getIntegerID() {
		return ID;
	}

	public int getCapacity() {
		return capacity - this.parkingVehicleStock.get();
	}
	
	public void addNeighboringZone(int z) {
		if(!this.neighboringZones.contains(z)) {
			this.neighboringZones.add(z);
		}
	}
	
	public void addNeighboringLink(int r) {
		if(!this.neighboringLinks.contains(r)) {
			this.neighboringLinks.add(r);
		}
	}
	
	public int getNeighboringLinkSize() {
		return this.neighboringLinks.size();
	}
	
	public int getNeighboringLink(int index) {
		return this.neighboringLinks.get(index);
	}
	
	public Iterable<Integer> getNeighboringZones(){
		return this.neighboringZones;
	}
	
	public int getNeighboringZoneSize() {
		return this.neighboringZones.size();
	}
}
