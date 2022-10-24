package addsEVs.citycontext;

/**
 * Taxi/Bus service Zone
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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.routing.RouteV;
import addsEVs.vehiclecontext.ElectricVehicle;
import repast.simphony.essentials.RepastEssentials;

public class Zone {
	// Basic attributes
	private int id;

	private int integerID;
	private int zoneClass; // 0 for normal zone, 1 for hub
	private String charID;
	
	protected Queue<Request> requestInQueueForTaxi; // Nonsharable passenger queue for taxis
	protected Map<Integer, Queue<Request>> sharableRequestForTaxi; // Shareable passenger for taxis
	protected Queue<Request> requestInQueueForBus; //Passenger queue for bus
	
	private int nRequestForTaxi; // Number of requests for Taxi
	private int nRequestForBus; // Number of requests for Bus
	
	// Parameters for mode choice model starts
	private int curhour = -1; // the last time for updating the travel time estimation
	public List<Integer> busReachableZone;
	public Map<Integer, Float> busGap; 
	public Map<Integer, Float> taxiTravelTime;
	public Map<Integer, Float> taxiTravelDistance;
	public Map<Integer, Float> busTravelTime;
	public Map<Integer, Float> busTravelDistance;
	// Mode choice model end
	
	// Metrics start
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
	public int taxiPassWaitingTime; //Waiting time of served Passengers
	public int busPassWaitingTime;
	public int taxiWaitingTime;
	// Metrics end
	private AtomicInteger vehicleStock = new AtomicInteger(0); // Number of available vehicles at this zone
	
	// For vehicle repositioning
	private int lastUpdateHour = -1; // the last time for updating the demand generation rate
	private AtomicInteger futureDemand = new AtomicInteger(0); // demand in the near future
	private AtomicInteger futureSupply = new AtomicInteger(0); // supply in the near future
	
	// For collaborative taxi and transit
	public Map<Integer, Zone> nearestZoneWithBus;
	public ConcurrentLinkedQueue<Request> toAddRequestForTaxi; // demand from integrated services
	public ConcurrentLinkedQueue<Request> toAddRequestForBus; // demand from integrated services
	
	// Sorted neighboring Zone from the closest to the farthest
	public List<Zone> neighboringZones;

	public Zone(int integerID) {
		this.id = ContextCreator.generateAgentID();
		this.integerID = integerID;
		this.sharableRequestForTaxi = new HashMap<Integer, Queue<Request>>();
		this.charID = Integer.toString(integerID);
		this.requestInQueueForBus = new LinkedList<Request>();
		this.requestInQueueForTaxi = new LinkedList<Request>();
		this.nRequestForBus = 0;
		this.nRequestForTaxi = 0;
		this.busReachableZone = new ArrayList<Integer>();
		this.busGap = new HashMap<Integer, Float>();
		if(GlobalVariables.HUB_INDEXES.contains(this.integerID)) {
			this.zoneClass = 1;
		}
		else {
			this.zoneClass = 0;
		}
		//Initialize metrices
		this.numberOfGeneratedTaxiRequest = 0;
		this.numberOfGeneratedBusRequest = 0;
		this.numberOfGeneratedCombinedRequest = 0;
		this.taxiPickupRequest = 0;
		this.busPickupRequest = 0;
		this.combinePickupPart1 = 0;
		this.combinePickupPart2 = 0;
		this.taxiServedRequest = 0;
		this.busServedRequest = 0;
		this.numberOfLeavedTaxiRequest = 0;
		this.numberOfLeavedBusRequest = 0;
		this.numberOfRelocatedVehicles = 0;
		this.taxiPassWaitingTime = 0;
		this.busPassWaitingTime = 0;
		this.taxiWaitingTime = 0;
		this.taxiTravelTime = new HashMap<Integer, Float>();
		this.taxiTravelDistance = new HashMap<Integer, Float>();
		this.busTravelTime = new HashMap<Integer, Float>();
		this.busTravelDistance = new HashMap<Integer, Float>();
		this.nearestZoneWithBus = new HashMap<Integer, Zone>();
		this.neighboringZones = new ArrayList<Zone>();
		this.toAddRequestForTaxi = new ConcurrentLinkedQueue<Request>();
		this.toAddRequestForBus = new ConcurrentLinkedQueue<Request>();
	}
	
	public void step(){
		// Happens at time step t
		this.servePassengerByTaxi();
		this.relocateTaxi();
		if( (int) RepastEssentials.GetTickCount() == GlobalVariables.SIMULATION_STOP_TIME) {
			// Skip the last update which is outside of the study period  
			return;
		}
		this.updateTravelEstimation();
		
		// Happens between t and t + 1
		this.passengerWaitTaxi();
		this.passengerWaitBus();
		this.taxiWaitPassenger();
		this.generatePassenger();
	}
	
	public void generatePassenger(){
		int tickcount = (int) RepastEssentials.GetTickCount();
		int hour = (int) Math.floor(tickcount / GlobalVariables.SIMULATION_DEMAND_REFRESH_INTERVAL);
		hour = hour % GlobalVariables.HOUR_OF_DEMAND;
		if(this.lastUpdateHour != hour){ 
			this.futureDemand.set(0);
		}
		
		if(this.zoneClass == 0){ // From other place to hub
            int j = 0;
            for(int destination : GlobalVariables.HUB_INDEXES){
            	double passRate = ContextCreator.getTravelDemand().get(this.getIntegerID()+j*GlobalVariables.NUM_OF_ZONE*2+
        				GlobalVariables.NUM_OF_ZONE).get(hour) * (GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL/
        						(3600/GlobalVariables.SIMULATION_STEP_SIZE));
            	passRate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
            	double numToGenerate = Math.floor(passRate) + (Math.random()<(passRate-Math.floor(passRate))?1:0);
            	
				if(busReachableZone.contains(destination)) {
					// No combinational mode like taxi-bus or bus-taxi
					float threshold = getSplitRatio(destination, false);
					for (int i = 0; i < numToGenerate; i++) {
                        Request new_pass = new Request(this.integerID, destination, 24000); // Wait for at most 2 hours
						if (Math.random() > threshold) {
							if(new_pass.isShareable()) {
								this.addSharableTaxiPass(new_pass, destination);
							}
							else {
								this.addTaxiPass(new_pass);
							}
							this.numberOfGeneratedTaxiRequest += 1;
						} else {
							this.addBusPass(new_pass);
							this.numberOfGeneratedBusRequest += 1;
						}
					}
					if(this.lastUpdateHour != hour){ 
	            		this.futureDemand.addAndGet((int) ( passRate * threshold));
	            	}
				}
				else if (GlobalVariables.COLLABORATIVE_EV && this.nearestZoneWithBus.containsKey(destination)){
					// Split between taxi and taxi-bus combined, 
					float threshold = getSplitRatio(destination, true);
					for (int i = 0; i < numToGenerate; i++) {
						if (Math.random() > threshold) {
							Request new_pass = new Request(this.integerID, destination, 24000); // Wait for at most 2 hours
							if(new_pass.isShareable()) {
								nRequestForTaxi += 1;
								if(!this.sharableRequestForTaxi.containsKey(destination)) {
									this.sharableRequestForTaxi.put(destination, new LinkedList<Request>());
								}
								this.sharableRequestForTaxi.get(destination).add(new_pass);
							}
							else {
								this.addTaxiPass(new_pass);
							}
							this.numberOfGeneratedTaxiRequest += 1;
						}
						else {
							// First generate its activity plan
							Queue<Plan> activityPlan = new LinkedList<Plan>();
							Plan plan = new Plan(this.nearestZoneWithBus.get(destination).getIntegerID(), 
									this.nearestZoneWithBus.get(destination).getCoord(), tickcount);
							activityPlan.add(plan);
							Plan plan2 = new Plan(destination, 
									ContextCreator.getCityContext().findZoneWithIntegerID(destination).getCoord(), tickcount);
							activityPlan.add(plan2);
							Request new_pass = new Request(this.integerID, activityPlan, 24000); // Wait for at most 2 hours
							this.addTaxiPass(new_pass);
							this.numberOfGeneratedCombinedRequest += 1;
						}
					}
					if(this.lastUpdateHour != hour){ 
						this.futureDemand.addAndGet((int) ( passRate * threshold));
	            	}
				}
				else {
					// Taxi only
					for (int i = 0; i < numToGenerate; i++) {
						Request new_pass = new Request(this.integerID, destination, 24000); // Wait for at most 2 hours
						if(new_pass.isShareable()) {
							nRequestForTaxi += 1;
							if(!this.sharableRequestForTaxi.containsKey(destination)) {
								this.sharableRequestForTaxi.put(destination, new LinkedList<Request>());
							}
							this.sharableRequestForTaxi.get(destination).add(new_pass);
						}
						else {
							this.addTaxiPass(new_pass);
						}
						this.numberOfGeneratedTaxiRequest += 1;
					}
					if(this.lastUpdateHour != hour){ 
						this.futureDemand.addAndGet((int) ( passRate));
	            	}
				}
				j+=1;
			}
			ContextCreator.logger.debug("current buss pass is"+this.numberOfGeneratedBusRequest); 
			ContextCreator.logger.debug("current taxi pass is"+this.numberOfGeneratedTaxiRequest);
		}
		else if (this.zoneClass == 1) { //From hub to other place
			int j = GlobalVariables.HUB_INDEXES.indexOf(this.integerID);
			for (int i = 0; i < GlobalVariables.NUM_OF_ZONE; i++) {
				int destination = i;
				double passRate = ContextCreator.getTravelDemand().get(i+j*GlobalVariables.NUM_OF_ZONE*2).get(hour) * 
						(GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL/(3600/GlobalVariables.SIMULATION_STEP_SIZE));;
				passRate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
				double numToGenerate = Math.floor(passRate) + (Math.random()<(passRate-Math.floor(passRate))?1:0);
	            if (destination != this.integerID) {
	            	if(busReachableZone.contains(destination)) {
	            		float threshold = getSplitRatio(destination, false);
	            		for (int k = 0; k < numToGenerate; k++) {
	            			Request new_pass = new Request(this.integerID, destination, 24000);
							if (Math.random() > threshold) {
								if(new_pass.isShareable()) {
									nRequestForTaxi += 1;
									if(!this.sharableRequestForTaxi.containsKey(destination)) {
										this.sharableRequestForTaxi.put(destination, new LinkedList<Request>());
									}
									this.sharableRequestForTaxi.get(destination).add(new_pass);
								}
								else {
									this.addTaxiPass(new_pass);
								}
								
								this.numberOfGeneratedTaxiRequest += 1;
							} else {
								this.addBusPass(new_pass);
								this.numberOfGeneratedBusRequest += 1;
							}
	            		}
	            		if(this.lastUpdateHour != hour){ 
	            			this.futureDemand.addAndGet((int) ( passRate * threshold));
		            	}
	            	}
	            	else if(GlobalVariables.COLLABORATIVE_EV && this.nearestZoneWithBus.containsKey(destination)){
	            		float threshold = getSplitRatio(destination, true);
	            		for (int k = 0; k < numToGenerate; k++) {
	            			if (Math.random() > threshold) {
								Request new_pass = new Request(this.integerID, destination, 24000);
								if(new_pass.isShareable()) {
									nRequestForTaxi += 1;
									if(!this.sharableRequestForTaxi.containsKey(destination)) {
										this.sharableRequestForTaxi.put(destination, new LinkedList<Request>());
									}
									this.sharableRequestForTaxi.get(destination).add(new_pass);
								}
								else {
									this.addTaxiPass(new_pass);
								}
								
								this.numberOfGeneratedTaxiRequest += 1;
							}
							else {
								LinkedList<Plan> activityPlan = new LinkedList<Plan>();
								Plan plan = new Plan(this.nearestZoneWithBus.get(destination).getIntegerID(), 
										this.nearestZoneWithBus.get(destination).getCoord(), tickcount);
								activityPlan.add(plan);
								Plan plan2 = new Plan(destination, 
										ContextCreator.getCityContext().findZoneWithIntegerID(destination).getCoord(), tickcount);
								activityPlan.add(plan2);
								Request new_pass = new Request(this.integerID, activityPlan, 24000); // Wait for at most 2 hours
								this.addBusPass(new_pass);				
								this.numberOfGeneratedCombinedRequest += 1;
							}
	            		}
	            		if(this.lastUpdateHour != hour){ 
	            			this.nearestZoneWithBus.get(destination).futureDemand.addAndGet((int) ( passRate * threshold));
		            	}
	            	}
	            	else {
	            		for (int k = 0; k < numToGenerate; k++) {
	            			Request new_pass = new Request(this.integerID, destination, 24000); 
							if(new_pass.isShareable()) {
								nRequestForTaxi += 1;
								if(!this.sharableRequestForTaxi.containsKey(destination)) {
									this.sharableRequestForTaxi.put(destination, new LinkedList<Request>());
								}
								this.sharableRequestForTaxi.get(destination).add(new_pass);
							}
							else {
								this.addTaxiPass(new_pass);
							}
							
							this.numberOfGeneratedTaxiRequest += 1;
	            		}
	            		if(this.lastUpdateHour != hour){ 
	            			this.futureDemand.addAndGet((int) ( passRate));;
		            	}
	            	}
				}
			}
		}
		ContextCreator.logger.debug("current buss pass is"+this.numberOfGeneratedBusRequest); 
		ContextCreator.logger.debug("current taxi pass is"+this.numberOfGeneratedTaxiRequest);
		if(this.lastUpdateHour!=hour){
			this.lastUpdateHour = hour;
		}
	}
	
	// Serve passenger
	public void servePassengerByTaxi() {
		// Ridesharing matching for the sharable passengers. Current implementation: If the passenger goes to the same place, pair them together.
		// First serve sharable passengers
		if(this.sharableRequestForTaxi.size()>0) {
			for(Queue<Request> passQueue: this.sharableRequestForTaxi.values()) {
				if(passQueue.size()>0) {
					int pass_num = passQueue.size();
					int v_num = vehicleStock.get();
					v_num = (int) Math.min(Math.ceil(pass_num/4.0),v_num);
					for(int i=0; i<v_num; i++) {
						ElectricVehicle v = ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).poll();
						if(v!=null) {
							ArrayList<Request> tmp_pass = new ArrayList<Request>();
							for(int j=0; j< Math.min(4,pass_num); j++) {
								Request p = passQueue.poll();
								tmp_pass.add(p);
							    // Record served passengers
								this.nRequestForTaxi -= 1;
								this.taxiPickupRequest += 1;
								this.taxiPassWaitingTime += p.getWaitingTime();
								GlobalVariables.SERVE_PASS += 1; // For Json ouput
							}
							v.servePassenger(tmp_pass);
							// Update future supply of the target zone
							ContextCreator.getCityContext().findZoneWithIntegerID(v.getDestID()).addFutureSupply();
							this.removeVehicleStock(1);
							pass_num = pass_num - tmp_pass.size();
						}
					}
				}
			}
		}
		
		// FCFS service for the rest of passengers
		int curr_size = this.requestInQueueForTaxi.size();
		for(int i = 0; i < curr_size; i++) {
			ElectricVehicle v = ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).poll();
			if(v!=null) {
				Request current_taxi_pass = this.requestInQueueForTaxi.poll();
				this.removeVehicleStock(1);
				if(current_taxi_pass.lenOfActivity()>1) {
					v.passengerWithAdditionalActivityOnTaxi.add(current_taxi_pass);
					this.combinePickupPart1 += 1;
				}
				else if(current_taxi_pass.lenOfActivity() == 0){
					this.taxiPickupRequest += 1;
				}
				else {
					// The length of activity is 1 which means this is the second part of a combined trip
					this.combinePickupPart2 += 1;
				}
				v.servePassenger(Arrays.asList(current_taxi_pass));
				// Update future supply of the target zone
				ContextCreator.getCityContext().findZoneWithIntegerID(v.getDestID()).addFutureSupply();
				// Record served passenger
				this.nRequestForTaxi-=1;
				GlobalVariables.SERVE_PASS += 1;
				this.taxiPassWaitingTime += current_taxi_pass.getWaitingTime();		
			}
			else {
				break; // no vehicle
			}
		}
	}
	
	// Relocate when the vehicleStock is negative
	// There are two implementations: 1. Using myopic info; 2. Using future estimation.
	public void relocateTaxi() {
		if(GlobalVariables.PROACTIVE_RELOCATION) {
			// Decide the number of relocated vehicle with the precaution on potential future vehicle shortage/overflow 
			// Divided by (generated passenger interval/served passenger interval)=5
			// (GlobalVariables.SIMULATION_PASSENGER_ARRIVAL_INTERVAL/GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL);
			if(this.getFutureSupply()<0) {
				ContextCreator.logger.error("Something went wrong, the futureSupply becomes negative!");
			}
			// 5 means the time step of looking ahead
			// 0.8 is the probability of vehicle with low battery level under the charging threshold
			double relocateRate = 0;
			relocateRate = 1.25 * (nRequestForTaxi - this.getVehicleStock() + 5 * this.futureDemand.get() - 0.8 * this.futureSupply.get());
			int numToRelocate = (int) Math.floor(relocateRate) + (Math.random()<(relocateRate-Math.floor(relocateRate))?1:0);
			for (int i = 0; i< numToRelocate ; i++) {
				boolean systemHasVeh = false;
				for (Zone z: this.neighboringZones) {
					// Relocate from zones with sufficient supply
					if (z.hasEnoughTaxi(5)) {
						ElectricVehicle v = ContextCreator.getVehicleContext().getVehiclesByZone(z.getIntegerID()).poll();
						if (v!=null) {
							v.relocation(z.getIntegerID(), this.integerID);
							this.numberOfRelocatedVehicles += 1;
							z.removeVehicleStock(1);
							this.futureSupply.addAndGet(1);
							systemHasVeh = true;
							break;
						}
					}
				}
				if (!systemHasVeh) {
					break;
				}
				
			}
		}
		else {
			// Reactive reposition
			double relocateRate = (nRequestForTaxi - this.vehicleStock.get());
			int numToRelocate = (int) Math.floor(relocateRate) + (Math.random()<(relocateRate-Math.floor(relocateRate))?1:0);
			for (int i = 0; i< numToRelocate ; i++) {
				boolean systemHasVeh = false;
				for (Zone z: this.neighboringZones) {
					// Relocate from zones with sufficient supply
					ElectricVehicle v = ContextCreator.getVehicleContext().getVehiclesByZone(z.getIntegerID()).poll();
					if (v!=null) {
						v.relocation(z.getIntegerID(), this.integerID);
						this.numberOfRelocatedVehicles += 1;
						z.removeVehicleStock(1);
						this.futureSupply.addAndGet(1);
						systemHasVeh = true;
						break;
					}
				}
				if (!systemHasVeh) {
					break;
				}
				
			}
		}
	}
	
	// Serve passenger using Bus
    public ArrayList<Request> servePassengerByBus(int maxPassNum, ArrayList<Integer> busStop){
    	ArrayList<Request> passOnBoard = new ArrayList<Request>();
    	for(Request p: this.requestInQueueForBus){ // If there are passengers
    		if(busStop.contains(p.getDestination())){
    			if(passOnBoard.size()>= maxPassNum){
    				break;
        		}
    			else { // passenger get on board
	    			passOnBoard.add(p);
	    			if(p.lenOfActivity() > 1) {
	    				this.combinePickupPart1 += 1; 
	    			}
	    			else if(p.lenOfActivity() == 1) { //count it only when the last trip starts
	    				this.combinePickupPart2 += 1; 
	    			}
	    			else if (p.lenOfActivity()==0) {
	    				this.busPickupRequest += 1;
	    			}
    			}
    		}
    	}
    	this.nRequestForBus -= passOnBoard.size();
    	for(Request pass: passOnBoard){
    		this.requestInQueueForBus.remove(pass);
    		this.busPassWaitingTime+=pass.getWaitingTime();
    	}
    	return passOnBoard;
	}
    
    
    //Passenger waiting
    public void passengerWaitTaxi(){
    	int curr_size;
    	for(Queue<Request> passQueue: this.sharableRequestForTaxi.values()) {
    		for(Request p: passQueue) {
    			p.waitNextTime(GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL);
    		}
    		curr_size = passQueue.size();
    		for(int i = 0; i < curr_size; i++) {
    			if(passQueue.peek().check()) {
    				passQueue.poll();
    				this.numberOfLeavedTaxiRequest += 1;
    				nRequestForTaxi -= 1;
    			}
    			else {
    				break;
    			}
    		}
    	}
    	
    	for(Request p: requestInQueueForTaxi){
    		p.waitNextTime(GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL);
    	}
    	curr_size = requestInQueueForTaxi.size();
    	for(int i = 0; i < curr_size; i++) {
    		if(requestInQueueForTaxi.peek().check()) {
    			requestInQueueForTaxi.poll();
				this.numberOfLeavedTaxiRequest += 1;
				nRequestForTaxi -= 1;
			}
			else {
				break;
			}
    	}
    }
    
    //Passenger waiting for bus
    public void passengerWaitBus(){
    	for(Request p: this.requestInQueueForBus){
    		p.waitNextTime(GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL);
    	}
    	int curr_size = requestInQueueForBus.size();
    	for(int i = 0; i < curr_size; i++) {
    		if(requestInQueueForBus.peek().check()){
    			requestInQueueForBus.poll();
    			this.numberOfLeavedBusRequest += 1;
    			nRequestForBus -= 1;
    		}
    	}
	}
    
    // Taxi waiting for passenger, update this if the relocation is based on the taxiWaiting time
    public void taxiWaitPassenger(){
    	this.taxiWaitingTime += this.vehicleStock.get() * GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL;
    }
    
    public int getTaxiPassengerNum(){
    	return nRequestForTaxi;
    }
    
    public int getBusPassengerNum(){
    	return nRequestForBus;
    }
    
	public int getId() {
		return id;
	}

	public Coordinate getCoord() {
		return ContextCreator.getZoneGeography().getGeometry(this)
				.getCentroid().getCoordinate();
	}

	public int getIntegerID() {
		return integerID;
	}

	public String getCharID() {
		return charID;
	}
	
	public void addVehicleStock(int vehicle_num){
		this.vehicleStock.addAndGet(vehicle_num);
	}
	
	public void addOneVehicle(){
		this.vehicleStock.addAndGet(1);
		this.removeFutureSupply();
	}
	
	public void removeVehicleStock(int vehicle_num){
		if(this.getVehicleStock()-vehicle_num<0){
			ContextCreator.logger.error(this.integerID + " out of stock, vehicle_num: " + this.vehicleStock);
			return;
		}
		this.addVehicleStock(-vehicle_num);
	}
	
	public int getVehicleStock(){
		return this.vehicleStock.get();
	}
	
	public int getZoneClass(){
		return this.zoneClass;
	}

	public void setBusInfo(int destID, float vehicleGap) {
		if (busGap.containsKey(destID)) {
			this.busGap.put(destID, Math.min(vehicleGap, this.busGap.get(destID)));
		}
		else {
			this.busGap.put(destID, vehicleGap);
			this.busReachableZone.add(destID);
		}
	}
	
	public void setTaxiTravelTimeMap(Map<Integer, Float> travelTime){
		this.taxiTravelTime = travelTime;
	}
	
	public void setTaxiTravelDistanceMap(Map<Integer, Float> travelDistance){
		this.taxiTravelDistance = travelDistance;
	}
	
	public void setBusTravelTimeMap(Map<Integer, Float> travelTime){
		this.busTravelTime = travelTime;
	}
	
	public void setBusTravelDistanceMap(Map<Integer, Float> travelDistance){
		this.busTravelDistance = travelDistance;
	}
	
	// Split taxi and bus passengers via a discrete choice model
	// Distance unit in mile
	// Time unit in minute
	public float getSplitRatio(int destID, boolean flag){
		if(flag) {
			// For collaborative EV services
			if(busTravelTime.containsKey(destID) && taxiTravelTime.containsKey(destID)){
				double taxiUtil = GlobalVariables.MS_ALPHA*(GlobalVariables.INITIAL_PRICE_TAXI+GlobalVariables.BASE_PRICE_TAXI*taxiTravelDistance.get(destID)/1609)+
						GlobalVariables.MS_BETA*(taxiTravelTime.get(destID)/60+5)+GlobalVariables.TAXI_BASE;
				// Here the busTravelDistance is actually the taxi travel distance for travelling to the closest zone with bus
				double busUtil = (float) (GlobalVariables.MS_ALPHA*(GlobalVariables.BUS_TICKET_PRICE + GlobalVariables.INITIAL_PRICE_TAXI+
						GlobalVariables.BASE_PRICE_TAXI*busTravelDistance.get(destID)/1609)+
						GlobalVariables.MS_BETA*(busTravelTime.get(destID)/60+this.busGap.get(destID)/2+5)+GlobalVariables.BUS_BASE);
				
				return (float) (Math.exp(1)/(Math.exp(taxiUtil-busUtil)+Math.exp(1)));
			}
			else{
				return 0;
			}
		}
		else {
			if(busTravelTime.containsKey(destID) && taxiTravelTime.containsKey(destID)){
				double taxiUtil = GlobalVariables.MS_ALPHA*(GlobalVariables.INITIAL_PRICE_TAXI+GlobalVariables.BASE_PRICE_TAXI*taxiTravelDistance.get(destID)/1609)+
						GlobalVariables.MS_BETA*(taxiTravelTime.get(destID)/60+5)+GlobalVariables.TAXI_BASE;
				double busUtil = (float) (GlobalVariables.MS_ALPHA*GlobalVariables.BUS_TICKET_PRICE+
						GlobalVariables.MS_BETA*(busTravelTime.get(destID)/60+this.busGap.get(destID)/2)+GlobalVariables.BUS_BASE);
				return (float) (Math.exp(1)/(Math.exp(taxiUtil-busUtil)+Math.exp(1)));
			}
			else{
				return 0;
			}
		}
	}
	
	// Update travel time estimation for taxi
	public void updateTravelEstimation(){
		// Get current tick
		int tickcount = (int) RepastEssentials.GetTickCount();
		int hour = (int) Math.floor(tickcount / GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL);
		if (this.curhour < hour) {
			Map<Integer, Float> travelDistanceMap = new HashMap<Integer, Float>();
			Map<Integer, Float> travelTimeMap = new HashMap<Integer, Float>();
			// is hub
			if(this.zoneClass==1){
				// shortest path travel time from hubs to all other zones
				for(Zone z2: ContextCreator.getZoneGeography().getAllObjects()){
					if(this.getIntegerID() != z2.getIntegerID()){
						double travel_time = 0;
						double travel_distance = 0;
						List<Road> path = RouteV.shortestPathRoute(this.getCoord(), z2.getCoord());
						if(path!=null){
							for(Road r: path){
								travel_distance += r.getLength();
								travel_time += r.getTravelTime();
							}
						}
						travelDistanceMap.put(z2.getIntegerID(), (float)travel_distance);
						travelTimeMap.put(z2.getIntegerID(), (float)travel_time);
					}
				}
				this.setTaxiTravelDistanceMap(travelDistanceMap);
				this.setTaxiTravelTimeMap(travelTimeMap);
			}
			else{
				// Shortest path to hubs
				for(int z2_id: GlobalVariables.HUB_INDEXES){
					Zone z2 = ContextCreator.getCityContext().findZoneWithIntegerID(z2_id);
					if(this.getIntegerID() != z2.getIntegerID()){
						double travel_time = 0;
						double travel_distance = 0;
						List<Road> path = RouteV.shortestPathRoute(this.getCoord(), z2.getCoord());
						if(path!=null){
							for(Road r: path){
								travel_distance += r.getLength();
								travel_time += r.getTravelTime();
							}
						}
						travelDistanceMap.put(z2.getIntegerID(), (float)travel_distance);
						travelTimeMap.put(z2.getIntegerID(), (float)travel_time);
					}
				}
				this.setTaxiTravelDistanceMap(travelDistanceMap);
				this.setTaxiTravelTimeMap(travelTimeMap);
			}
			
			if(GlobalVariables.COLLABORATIVE_EV) {
				this.updateCombinedTravelEstimation();
			}
			
			this.curhour = hour;
		}
	}
	
	// Update travel time estimation for modes combining taxi and bus
	public void updateCombinedTravelEstimation(){
		if(this.zoneClass==1){
			for(Zone z2: ContextCreator.getZoneGeography().getAllObjects()){
				if(!busReachableZone.contains(z2.getIntegerID())) { // Find the closest zone with bus that can connect to this hub
					Zone z = ContextCreator.getCityContext().findNearestZoneWithBus(z2.getCoord(), this.getIntegerID());
					if(z != null) {
						this.nearestZoneWithBus.put(z2.getIntegerID(), z);
					}
				}
				if(this.nearestZoneWithBus.containsKey(z2.getIntegerID())) {
					double travel_time2 = 0;
					double travel_distance2 = 0;
					List<Road> path2 = RouteV.shortestPathRoute(this.nearestZoneWithBus.get(z2.getIntegerID()).getCoord(), z2.getCoord());
					if(path2!=null){
						for(Road r: path2){
							travel_distance2 += r.getLength();
							travel_time2 += r.getTravelTime();
						}
						busGap.put(z2.getIntegerID(), this.nearestZoneWithBus.get(z2.getIntegerID()).busGap.get(this.getIntegerID()));
						busTravelDistance.put(z2.getIntegerID(), (float) travel_distance2); // only the distance to the closest zone with bus
					    busTravelTime.put(z2.getIntegerID(), (float) travel_time2 + 
					    		this.busTravelTime.get(this.nearestZoneWithBus.get(z2.getIntegerID()).getIntegerID())); // time for bus and taxi combined
					}
				}
			}
		}
		else{
			// Shortest path to hubs
			for(int z2_id: GlobalVariables.HUB_INDEXES){
				if(!busReachableZone.contains(z2_id)) { // Find the closest zone with bus that can connect to this hub
					Zone z = ContextCreator.getCityContext().findNearestZoneWithBus(this.getCoord(), z2_id);
					if(z != null) {
						this.nearestZoneWithBus.put(z2_id, z);
					}
				}
				if(this.nearestZoneWithBus.containsKey(z2_id)) {
					double travel_time2 = 0;
					double travel_distance2 = 0;
					List<Road> path2 = RouteV.shortestPathRoute(this.getCoord(), this.nearestZoneWithBus.get(z2_id).getCoord());
					if(path2!=null){
						for(Road r: path2){
							travel_distance2 += r.getLength();
							travel_time2 += r.getTravelTime();
						}
						busGap.put(z2_id, this.nearestZoneWithBus.get(z2_id).busGap.get(z2_id));
						busTravelDistance.put(z2_id, (float) travel_distance2); // only the distance to the closest zone with bus
					    busTravelTime.put(z2_id, (float) travel_time2 + 
					    		this.nearestZoneWithBus.get(z2_id).busTravelTime.get(z2_id)); // time for bus and taxi combined
					}
				}
			}
		}
	}
	
	public void reSplitPassengerDueToBusRescheduled(){
		// Find the passengers whose bus routes are deprecated
		List<Request> rePass = new ArrayList<Request>();
    	for(Request p: this.requestInQueueForBus){
    		if(!this.busReachableZone.contains(p.getDestination())){ // No bus can serve this passenger anymore
    			rePass.add(p);
    			if(p.lenOfActivity()>=2) {
    				this.numberOfGeneratedCombinedRequest -= 1;
    			}
    			else if(p.lenOfActivity() == 1) {
    				// do nothing as it will be counted served correctly when it is served
    			}
    			else {
    				this.numberOfGeneratedBusRequest -= 1;
    			}
    			nRequestForBus -= 1;
    		}
    	}
    	for(Request p: rePass){
    		this.requestInQueueForBus.remove(p);
    	}
		// Assign these passengers to taxis
		// If the passenger planned to used combined trip, skip the first trip
		for(Request p: rePass) {
			if(p.lenOfActivity()>=2) {
				p.moveToNextActivity();
				p.setOrigin(this.integerID);
				p.clearActivityPlan();
			}
			else if(p.lenOfActivity()==1) { // Is trying to finish the second trip of a combined trips
				this.numberOfGeneratedTaxiRequest -= 1; // do nothing, here -1 cancel the +1 below
			}
			
			this.toAddRequestForTaxi.add(p);
			this.numberOfGeneratedTaxiRequest += 1;
		}
		
	}
	
	public void addTaxiPass(Request new_pass) {
		this.nRequestForTaxi += 1;
		this.requestInQueueForTaxi.add(new_pass);
	}
	
	public void addSharableTaxiPass(Request new_pass, int destination) {
		this.nRequestForTaxi += 1;
		if(!this.sharableRequestForTaxi.containsKey(destination)) {
			this.sharableRequestForTaxi.put(destination, new LinkedList<Request>());
		}
		this.sharableRequestForTaxi.get(destination).add(new_pass);
	}
	
    public void addBusPass(Request new_pass) {
    	this.nRequestForBus += 1;
		this.requestInQueueForBus.add(new_pass);
	}
    
    public void addFutureSupply() {
    	this.futureSupply.addAndGet(1);
    }
    
    public void removeFutureSupply() {
    	this.futureSupply.addAndGet(-1);
    }
    
    public int getFutureDemand() {
    	return this.futureDemand.get();
    }
    
    public int getFutureSupply() {
    	return this.futureSupply.get();
    }
    
    public boolean hasEnoughTaxi(int k){ // k: future steps to consider
    	return this.getVehicleStock() - this.nRequestForTaxi - k * getFutureDemand() > 0;
    }
    
    public void processToAddPassengers() {
    	for(Request q = this.toAddRequestForTaxi.poll(); q!= null; q = this.toAddRequestForTaxi.poll()) {
    		this.addTaxiPass(q);
    	}
    	for(Request q = this.toAddRequestForBus.poll(); q!= null; q = this.toAddRequestForBus.poll()) {
    		this.addBusPass(q);
    	}
    }
}


