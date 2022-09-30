package addsEVs.citycontext;

/**
 * Changed to Taxi/Bust service Zone
 * Each zone has two queues of passengers, one for buses and one for taxis
 * Each zone can serve passengers
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
import java.util.concurrent.LinkedBlockingDeque;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.routing.RouteV;
import addsEVs.vehiclecontext.ElectricVehicle;
import repast.simphony.essentials.RepastEssentials;

public class Zone {
	// Basic attributes
	protected int id;

	protected int integerID;
	protected int zoneClass; // 0 for normal zone, 1 for airport
	protected String charID;
	
	protected LinkedBlockingDeque<Passenger> passInQueueForTaxi; // Nonsharable passenger queue for taxis
	protected Map<Integer, Queue<Passenger>> sharablePassForTaxi; // Shareable passenger for taxis
	protected LinkedBlockingDeque<Passenger> passInQueueForBus; //Passenger queue for bus
	
	protected int nPassForTaxi; // Number of passenger for Taxi
	protected int nPassForBus; // Number of passenger for Bus

	protected ArrayList<Float> destDistribution; // Destination distribution of generated passengers
	
	// Parameters for mode choice model starts
	public List<Integer> busReachableZone;
	public Map<Integer, Float> busGap; 
	public Map<Integer, Float> taxiTravelTime;
	public Map<Integer, Float> taxiTravelDistance;
	public Map<Integer, Float> busTravelTime;
	public Map<Integer, Float> busTravelDistance;
	// Mode choice model end
	
	// Metrics start, pass for passengers
	public int numberOfGeneratedTaxiPass;
	public int numberOfGeneratedBusPass;
	public int numberOfGeneratedCombinedPass;
	public int taxiPickupPass;
	public int busPickupPass;
	public int combinePickupPart1;
	public int combinePickupPart2;
	public int taxiServedPass;
	public int busServedPass;
	public int numberOfLeavedTaxiPass;
	public int numberOfLeavedBusPass;
	public int numberOfRelocatedVehicles;
	public int taxiPassWaitingTime; //Waiting time of served Passengers
	public int busPassWaitingTime;
	public int taxiWaitingTime;
	// Metrics end
	
	protected int vehicleStock=0; // Number of available vehicles at this zone
	
	// For vehicle repositioning
	protected int lastUpdateHour = -1;
	protected double futureDemand = 0; // demand in the next 5 minutes
	protected double futureSupply = 0; // supply in the next 5 minutes
	
	// For collaborative taxi and transit
	public Map<Integer, Zone> nearestZoneWithBus;
	
	// For a better vehicle reposition algorithm
	public List<Zone> neighboringZones;
	

	public Zone(int integerID) {
		this.id = ContextCreator.generateAgentID();
		this.integerID = integerID;
		this.sharablePassForTaxi = new HashMap<Integer, Queue<Passenger>>();
		this.charID = Integer.toString(integerID);
		this.passInQueueForBus = new LinkedBlockingDeque<Passenger>();
		this.passInQueueForTaxi = new LinkedBlockingDeque<Passenger>();
		this.nPassForBus = 0;
		this.nPassForTaxi = 0;
		this.busReachableZone = new ArrayList<Integer>();
		this.busGap = new HashMap<Integer, Float>();
		this.destDistribution = new ArrayList<Float>();
		if(GlobalVariables.HUB_INDEXES.contains(this.integerID)) {
			this.zoneClass = 1;
		}
		else {
			this.zoneClass = 0;
		}
		//Initialize metrices
		this.numberOfGeneratedTaxiPass = 0;
		this.numberOfGeneratedBusPass = 0;
		this.numberOfGeneratedCombinedPass = 0;
		this.taxiPickupPass = 0;
		this.busPickupPass = 0;
		this.combinePickupPart1 = 0;
		this.combinePickupPart2 = 0;
		this.taxiServedPass = 0;
		this.busServedPass = 0;
		this.numberOfLeavedTaxiPass = 0;
		this.numberOfLeavedBusPass = 0;
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
	}
	
	public void step(){
		// Zone serve passenger, passenger wait
		this.servePassengerByTaxi();
		this.passengerWaitTaxi();
		this.passengerWaitBus(); // Passenger wait
		this.relocateTaxi();
		this.taxiWaitPassenger();
	}
	
	public void generatePassenger(){
		int tickcount = (int) RepastEssentials.GetTickCount();
		int hour = (int) Math.floor(tickcount
				* GlobalVariables.SIMULATION_STEP_SIZE / 3600);
		if(this.lastUpdateHour != hour){ 
			this.futureDemand = 0;
		}
		
		if(this.zoneClass == 0){ // From other place to hub
            int j = 0;
            for(int destination : GlobalVariables.HUB_INDEXES){
            	double passRate = ContextCreator.getTravelDemand().get(this.getIntegerID()+j*GlobalVariables.NUM_OF_ZONE*2+
        				GlobalVariables.NUM_OF_ZONE).get(hour) / 12.0;
            	passRate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
            	double numToGenerate = Math.floor(passRate) + (Math.random()<(passRate-Math.floor(passRate))?1:0);
            	
				if(busReachableZone.contains(destination)) {
					// No combinational mode like taxi-bus or bus-taxi
					float threshold = getSplitRatio(destination, false);
					for (int i = 0; i < numToGenerate; i++) {
                        Passenger new_pass = new Passenger(this.integerID, destination, 24000); // Wait for at most 2 hours
						if (Math.random() > threshold) {
							if(new_pass.isShareable()) {
								nPassForTaxi += 1;
								if(!this.sharablePassForTaxi.containsKey(destination)) {
									this.sharablePassForTaxi.put(destination, new LinkedList<Passenger>());
								}
								this.sharablePassForTaxi.get(destination).add(new_pass);
							}
							else {
								this.addTaxiPass(new_pass);
							}
							this.numberOfGeneratedTaxiPass += 1;
						} else {
							this.addBusPass(new_pass);
							this.numberOfGeneratedBusPass += 1;
						}
					}
					if(this.lastUpdateHour != hour){ 
	            		this.futureDemand += passRate * threshold;
	            	}
				}
				else if (GlobalVariables.COLLABORATIVE_EV && this.nearestZoneWithBus.containsKey(destination)){
					// Split between taxi and taxi-bus combined, 
					float threshold = getSplitRatio(destination, true);
					for (int i = 0; i < numToGenerate; i++) {
						if (Math.random() > threshold) {
							Passenger new_pass = new Passenger(this.integerID, destination, 24000); // Wait for at most 2 hours
							if(new_pass.isShareable()) {
								nPassForTaxi += 1;
								if(!this.sharablePassForTaxi.containsKey(destination)) {
									this.sharablePassForTaxi.put(destination, new LinkedList<Passenger>());
								}
								this.sharablePassForTaxi.get(destination).add(new_pass);
							}
							else {
								this.addTaxiPass(new_pass);
							}
							this.numberOfGeneratedTaxiPass += 1;
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
							Passenger new_pass = new Passenger(this.integerID, activityPlan, 24000); // Wait for at most 2 hours
							this.addTaxiPass(new_pass);
							this.numberOfGeneratedCombinedPass += 1;
						}
					}
					if(this.lastUpdateHour != hour){ 
	            		this.futureDemand += passRate * threshold;
	            	}
				}
				else {
					// Taxi only
					for (int i = 0; i < numToGenerate; i++) {
						Passenger new_pass = new Passenger(this.integerID, destination, 24000); // Wait for at most 2 hours
						if(new_pass.isShareable()) {
							nPassForTaxi += 1;
							if(!this.sharablePassForTaxi.containsKey(destination)) {
								this.sharablePassForTaxi.put(destination, new LinkedList<Passenger>());
							}
							this.sharablePassForTaxi.get(destination).add(new_pass);
						}
						else {
							this.addTaxiPass(new_pass);
						}
						this.numberOfGeneratedTaxiPass += 1;
					}
					if(this.lastUpdateHour != hour){ 
	            		this.futureDemand += passRate;
	            	}
				}
				j+=1;
			}
			ContextCreator.logger.debug("current buss pass is"+this.numberOfGeneratedBusPass); 
			ContextCreator.logger.debug("current taxi pass is"+this.numberOfGeneratedTaxiPass);
		}
		else if (this.zoneClass == 1) { //From hub to other place
			int j = GlobalVariables.HUB_INDEXES.indexOf(this.integerID);
			for (int i = 0; i < GlobalVariables.NUM_OF_ZONE; i++) {
				int destination = i;
				double passRate =ContextCreator.getTravelDemand().get(i+j*GlobalVariables.NUM_OF_ZONE*2).get(hour) / 12.0;
				passRate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
				double numToGenerate = Math.floor(passRate) + (Math.random()<(passRate-Math.floor(passRate))?1:0);
	            if (destination != this.integerID) {
	            	if(busReachableZone.contains(destination)) {
	            		float threshold = getSplitRatio(destination, false);
	            		for (int k = 0; k < numToGenerate; k++) {
	            			Passenger new_pass = new Passenger(this.integerID, destination, 24000);
							if (Math.random() > threshold) {
								if(new_pass.isShareable()) {
									nPassForTaxi += 1;
									if(!this.sharablePassForTaxi.containsKey(destination)) {
										this.sharablePassForTaxi.put(destination, new LinkedList<Passenger>());
									}
									this.sharablePassForTaxi.get(destination).add(new_pass);
								}
								else {
									this.addTaxiPass(new_pass);
								}
								
								this.numberOfGeneratedTaxiPass += 1;
							} else {
								this.addBusPass(new_pass);
								this.numberOfGeneratedBusPass += 1;
							}
	            		}
	            		if(this.lastUpdateHour != hour){ 
		            		this.futureDemand += passRate * threshold;
		            	}
	            	}
	            	else if(GlobalVariables.COLLABORATIVE_EV && this.nearestZoneWithBus.containsKey(destination)){
	            		float threshold = getSplitRatio(destination, true);
	            		for (int k = 0; k < numToGenerate; k++) {
	            			if (Math.random() > threshold) {
								Passenger new_pass = new Passenger(this.integerID, destination, 24000);
								if(new_pass.isShareable()) {
									nPassForTaxi += 1;
									if(!this.sharablePassForTaxi.containsKey(destination)) {
										this.sharablePassForTaxi.put(destination, new LinkedList<Passenger>());
									}
									this.sharablePassForTaxi.get(destination).add(new_pass);
								}
								else {
									this.addTaxiPass(new_pass);
								}
								
								this.numberOfGeneratedTaxiPass += 1;
							}
							else {
								LinkedList<Plan> activityPlan = new LinkedList<Plan>();
								Plan plan = new Plan(this.nearestZoneWithBus.get(destination).getIntegerID(), 
										this.nearestZoneWithBus.get(destination).getCoord(), tickcount);
								activityPlan.add(plan);
								Plan plan2 = new Plan(destination, 
										ContextCreator.getCityContext().findZoneWithIntegerID(destination).getCoord(), tickcount);
								activityPlan.add(plan2);
								Passenger new_pass = new Passenger(this.integerID, activityPlan, 24000); // Wait for at most 2 hours
								this.addBusPass(new_pass);				
								this.numberOfGeneratedCombinedPass += 1;
							}
	            		}
	            		if(this.lastUpdateHour != hour){ 
	            			this.nearestZoneWithBus.get(destination).futureDemand += passRate * threshold;
		            	}
	            	}
	            	else {
	            		for (int k = 0; k < numToGenerate; k++) {
	            			Passenger new_pass = new Passenger(this.integerID, destination, 24000); 
							if(new_pass.isShareable()) {
								nPassForTaxi += 1;
								if(!this.sharablePassForTaxi.containsKey(destination)) {
									this.sharablePassForTaxi.put(destination, new LinkedList<Passenger>());
								}
								this.sharablePassForTaxi.get(destination).add(new_pass);
							}
							else {
								this.addTaxiPass(new_pass);
							}
							
							this.numberOfGeneratedTaxiPass += 1;
	            		}
	            		if(this.lastUpdateHour != hour){ 
		            		this.futureDemand += passRate;
		            	}
	            	}
				}
			}
		}
		ContextCreator.logger.debug("current buss pass is"+this.numberOfGeneratedBusPass); 
		ContextCreator.logger.debug("current taxi pass is"+this.numberOfGeneratedTaxiPass);
		if(this.lastUpdateHour!=hour){
			this.lastUpdateHour = hour;
		}
	}
	
	// Serve passenger
	public void servePassengerByTaxi() {
		// Ridesharing matching for the sharable passengers. Current solution: If the passenger goes to the same place, pair them together.
		// First serve sharable passengers
		if(this.sharablePassForTaxi.size()>0) {
			for(Queue<Passenger> passQueue: this.sharablePassForTaxi.values()) {
				if(passQueue.size()>0 && ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).size()>0) {
					int pass_num = passQueue.size();
					int v_num = ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).size();
					v_num = (int) Math.min(Math.ceil(pass_num/4.0),v_num);
					for(int i=0; i<v_num; i++) {
						ElectricVehicle v = ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).poll();
						ArrayList<Passenger> tmp_pass = new ArrayList<Passenger>();
						for(int j=0; j< Math.min(4,pass_num); j++) {
							Passenger p = passQueue.poll();
							tmp_pass.add(p);
						    // Record served passengers
							this.nPassForTaxi -= 1;
							this.taxiPickupPass += 1;
							this.taxiPassWaitingTime += p.getWaitingTime();
							GlobalVariables.SERVE_PASS += 1; // For Json ouput
						}
						v.servePassenger(tmp_pass);
						// Update future supply of the target zone
						ContextCreator.getCityContext().findZoneWithIntegerID(v.getDestID()).futureSupply += 1;
						this.removeVehicleStock(1);
						pass_num = pass_num - tmp_pass.size();
						
					}
				}
			}
		}
		
		// FCFS service for the rest of passengers
		while (!this.passInQueueForTaxi.isEmpty() && ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).peek() != null) {
			Passenger current_taxi_pass = this.passInQueueForTaxi.poll();
			ElectricVehicle v = ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).poll();
			if(current_taxi_pass.lenOfActivity()>1) {
				v.passengerWithAdditionalActivityOnTaxi.add(current_taxi_pass);
				this.combinePickupPart1 += 1;
			}
			else if(current_taxi_pass.lenOfActivity() == 0){
				this.taxiPickupPass += 1;
			}
			else {
				// The length of activity is 1 which means this is the second part of a combined trip
				this.combinePickupPart2 += 1;
			}
			v.servePassenger(Arrays.asList(current_taxi_pass));
			// Update future supply of the target zone
			ContextCreator.getCityContext().findZoneWithIntegerID(v.getDestID()).futureSupply += 1;
			this.removeVehicleStock(1);
			// Record served passenger
			this.nPassForTaxi-=1;
			GlobalVariables.SERVE_PASS += 1;
			this.taxiPassWaitingTime += current_taxi_pass.getWaitingTime();			
		}
	}
	
	// Relocate when the vehicleStock is negative
	// There are two implementations: 1. Using myopic info; 2. Using future estimation.
	public void relocateTaxi() {
		if(GlobalVariables.PROACTIVE_RELOCATION) {
			// Decide the number of relocated vehicle with the precaution on potential future vehicle shortage/overflow 
			// Divided by (generated passenger interval/served passenger interval)=5
			// (GlobalVariables.SIMULATION_PASSENGER_ARRIVAL_INTERVAL/GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL);
			if(this.futureSupply<0) {
				ContextCreator.logger.error("Something went wrong, the futureSupply becomes negative!");
			}
			// we prefer storing more vehicles at hubs 6 means the time step of looking ahead
			// 0.5 means probability of vehicle needs to go charging
			// 2 for the reposition rate
			double relocateRate = 0;
			relocateRate = nPassForTaxi - this.vehicleStock + this.futureDemand - this.futureSupply;
			int numToRelocate = (int) Math.floor(relocateRate) + (Math.random()<(relocateRate-Math.floor(relocateRate))?1:0);
			while (numToRelocate > 0) {
//				double max_stock = 0;
//				Zone source = null;
//				for (Zone z : this.neighboringZones) {
//					if (max_stock < (z.getVehicleStock() - z.nPassForTaxi - (z.futureDemand  - z.futureSupply))) {
//						max_stock = z.getVehicleStock() - z.nPassForTaxi - (z.futureDemand  - z.futureSupply);
//						source = z;
//					}
//				}
//				if (source != null && ContextCreator.getVehicleContext().getVehicles(source.getIntegerID()).peek() != null) {
//					ElectricVehicle v = ContextCreator.getVehicleContext().getVehicles(source.getIntegerID()).poll();
//					v.relocation(source.getIntegerID(), this.integerID);
//					this.numberOfRelocatedVehicles += 1;
//					source.removeVehicleStock(1);
//					this.futureSupply += 1;
//				} else {
//					break; // The system is short of vehicles!
//				}
//				numToRelocate -= 1;
				Zone source = null;
				for (Zone z: this.neighboringZones) {
					// Relocate from zones with sufficient supply
					if ((z.getVehicleStock() - z.nPassForTaxi - 6 * z.futureDemand > 0) && 
							(ContextCreator.getVehicleContext().getVehiclesByZone(z.getIntegerID()).peek() != null)) {
						source = z;
						break;
					}
				}
				if (source != null) {
					ElectricVehicle v = ContextCreator.getVehicleContext().getVehiclesByZone(source.getIntegerID()).poll();
					v.relocation(source.getIntegerID(), this.integerID);
					this.numberOfRelocatedVehicles += 1;
					source.removeVehicleStock(1);
					this.futureSupply += 1;
				}
				else {
					break; // The system is short of vehicles!
				}
				numToRelocate -= 1;
			}
		}
		else {
			// Reactive reposition
			double relocateRate = (nPassForTaxi - this.vehicleStock);
			int numToRelocate = (int) Math.floor(relocateRate) + (Math.random()<(relocateRate-Math.floor(relocateRate))?1:0);
			while (numToRelocate > 0) {
//				double max_stock = 0;
//				Zone source = null;
//				for (Zone z : ContextCreator.getZoneGeography().getAllObjects()) {
//					if (max_stock < (z.getVehicleStock() - z.nPassForTaxi)) {
//						max_stock = z.getVehicleStock() - z.nPassForTaxi;
//						source = z;
//					}
//				}
//				if (source != null && max_stock > 0) {
//					if (ContextCreator.getVehicleContext().getVehicles(source.getIntegerID()).peek() != null) {
//						ElectricVehicle v = ContextCreator.getVehicleContext().getVehicles(source.getIntegerID()).poll();
//						v.relocation(source.getIntegerID(), this.integerID);
//						this.numberOfRelocatedVehicles += 1;
//						source.removeVehicleStock(1);
//						this.futureSupply += 1;
//					}
//				} else {
//					break; // The system is short of vehicles!
//				}
//				numToRelocate -= 1;
				Zone source = null;
				for (Zone z: this.neighboringZones) {
					if ((z.getVehicleStock() - z.nPassForTaxi > 0) && 
							(ContextCreator.getVehicleContext().getVehiclesByZone(z.getIntegerID()).peek() != null)) {
						source = z;
						break;
					}
				}
				if (source != null) {
					ElectricVehicle v = ContextCreator.getVehicleContext().getVehiclesByZone(source.getIntegerID()).poll();
					v.relocation(source.getIntegerID(), this.integerID);
					this.numberOfRelocatedVehicles += 1;
					source.removeVehicleStock(1);
					this.futureSupply += 1;
				}
				else {
					break; // The system is short of vehicles!
				}
				numToRelocate -= 1;
			}
		}
	}
	
	// Serve passenger using Bus
    public ArrayList<Passenger> servePassengerByBus(int maxPassNum, ArrayList<Integer> busStop){
    	ArrayList<Passenger> passOnBoard = new ArrayList<Passenger>();
    	for(Passenger p: this.passInQueueForBus){ // If there are passengers
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
	    				this.busPickupPass += 1;
	    			}
    			}
    		}
    	}
    	this.nPassForBus -= passOnBoard.size();
    	for(Passenger pass: passOnBoard){
    		this.passInQueueForBus.remove(pass);
    		this.busPassWaitingTime+=pass.getWaitingTime();
    	}
    	return passOnBoard;
	}
    
    
    //Passenger waiting
    public void passengerWaitTaxi(){
    	for(Queue<Passenger> passQueue: this.sharablePassForTaxi.values()) {
    		for(Passenger p: passQueue) {
    			p.waitNextTime(GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL);
    		}
    		while(passQueue.peek() != null) {
    			if(passQueue.peek().check()) {
    				passQueue.poll();
    				this.numberOfLeavedTaxiPass += 1;
    				nPassForTaxi -= 1;
    			}
    			else {
    				break;
    			}
    		}
    	}
    	
    	List<Passenger> leftPass = new ArrayList<Passenger>();
    	for(Passenger p: this.passInQueueForTaxi){
    		p.waitNextTime(GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL);
    		if(p.check()){
    			leftPass.add(p);
    			this.numberOfLeavedTaxiPass += 1;
    			nPassForTaxi -= 1;
    		}
    	}
    	for(Passenger p: leftPass){
    		this.passInQueueForTaxi.remove(p);
    	}
    }
    
    //Passenger waiting for bus
    public void passengerWaitBus(){
    	List<Passenger> leftPass = new ArrayList<Passenger>();
    	for(Passenger p: this.passInQueueForBus){
    		p.waitNextTime(GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL);
    		if(p.check()){
    			leftPass.add(p);
    			this.numberOfLeavedBusPass += 1;
    			nPassForBus -= 1;
    		}
    	}
    	for(Passenger p: leftPass){
    		this.passInQueueForBus.remove(p);
    	}
	}
    
    //Taxi waiting for passenger
    public void taxiWaitPassenger(){
    	this.taxiWaitingTime += ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).size()*GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL;
    }
    
    public int getTaxiPassengerNum(){
    	return nPassForTaxi;
    }
    
    public int getBusPassengerNum(){
    	return nPassForBus;
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
		this.vehicleStock += vehicle_num;
	}
	
	public void addOneVehicle(){
		this.vehicleStock += 1;
		this.futureSupply -= 1;
	}
	
	public void removeVehicleStock(int vehicle_num){
		if(this.vehicleStock-vehicle_num<0){
			ContextCreator.logger.error(this.integerID + " out of stock, vehicle_num: " + this.vehicleStock);
			return;
		}
		this.vehicleStock -= vehicle_num;
	}
	
	public int getVehicleStock(){
		return this.vehicleStock;
	}
	
	public int getZoneClass(){
		return this.zoneClass;
	}

	public void setBusInfo(int destID, float vehicleGap) {
		this.busGap.put(destID, vehicleGap);
		this.busReachableZone.add(destID);
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
		List<Passenger> rePass = new ArrayList<Passenger>();
    	for(Passenger p: this.passInQueueForBus){
    		if(!this.busTravelTime.containsKey(p.getDestination())){ // No bus can serve this passenger anymore
    			rePass.add(p);
    			if(p.lenOfActivity()>=2) {
    				this.numberOfGeneratedCombinedPass -= 1;
    			}
    			else if(p.lenOfActivity() == 1) {
    				// do nothing as it will be counted served corretly when it is served
    			}
    			else {
    				this.numberOfGeneratedBusPass -= 1;
    			}
    			nPassForBus -= 1;
    		}
    	}
    	for(Passenger p: rePass){
    		this.passInQueueForBus.remove(p);
    	}
		// Assign these passengers to taxis
		// If the passenger planned to used combined trip,
		// skip the first trip
		for(Passenger p: rePass) {
			if(p.lenOfActivity()>=2) {
				p.moveToNextActivity();
				p.setOrigin(this.integerID);
				p.clearActivityPlan();
			}
			else if(p.lenOfActivity()==1) { // Is trying to finish the second trip of a combined trips
				this.numberOfGeneratedTaxiPass -= 1; // cancel with the add 1 below
			}
			
			this.addTaxiPass(p);
			this.numberOfGeneratedTaxiPass += 1;
		}
		
	}
	
	public void addTaxiPass(Passenger new_pass) {
		this.nPassForTaxi += 1;
		this.passInQueueForTaxi.add(new_pass);
	}
	
    public void addBusPass(Passenger new_pass) {
    	this.nPassForBus += 1;
		this.passInQueueForBus.add(new_pass);
	}
    
    public void addFutureSupply() {
    	this.futureSupply += 1;
    }
    
    public void removeFutureSupply() {
    	this.futureSupply -= 1;
    }
    
    public double getFutureDemand() {
    	return this.futureDemand;
    }
    
    public double getFutureSupply() {
    	return this.futureSupply;
    }
}


