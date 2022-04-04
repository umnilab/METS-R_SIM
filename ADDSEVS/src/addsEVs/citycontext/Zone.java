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

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.routing.RouteV;
import addsEVs.vehiclecontext.ElectricVehicle;
import repast.simphony.essentials.RepastEssentials;

public class Zone {
	// Basic attributes
	protected int id;
//	protected Coordinate coord; //unused variable

	protected int integerID;
	protected int zoneClass; // 0 for normal zone, 1 for airport
	protected String charID;
	
	protected Passenger firstPassInQueueForTaxi; //nonsharable passenger queue for taxis
	protected Map<Integer, Queue<Passenger>> sharablePassForTaxi; //shareable passenger for taxis
	protected Passenger firstPassInQueueForBus; //passenger queue for bus
	
	protected int nPassForTaxi; //number of passenger for Taxi
	protected int nPassForBus; //number of passenger for Bus
	// protected LinkedBlockingQueue<Passenger> passQueueForTaxi; //passenger queue for taxis
	// protected ArrayList<Passenger> passQueueForBus; //passenger queue for bus
	
	// demand generation model start
	protected ArrayList<Float> destDistribution; //Destination distribution of generated passengers
	// demand generation model end
	
	// mode choice model start
	private static float busTicketPrice = 15; //Placeholder for bus ticket prices to every other zones
	private static float alpha = -0.078725f; //$
	private static float beta = -0.020532f; //minutes
	private static float basePriceTaxi  = 2.0f; //Placeholder, taxi fees per miles
	private static float initialPriceTaxi = 2.5f;
	private static float taxiBase = -0.672839f;
	private static float busBase = -1.479586f;
	private boolean hasBus;
	public int busGap;
	// change into public int due to the change in Busschedule.java 03/15/2022 zhengyu
	// utility function
	//	float taxiUtil = alpha*(initialPriceTaxi+basePriceTaxi*taxiTravelDistance.get(destID))+
	//			beta*(taxiTravelTime.get(destID)/60+5)+taxiBase;
	//	float busUtil = (float) (alpha*busTicketPrice+
	//			beta*(busTravelTime.get(destID)/60+this.busGap/2)+busBase);
	
	public Map<Integer, Float> taxiTravelTime;
	public Map<Integer, Float> taxiTravelDistance;
	public Map<Integer, Float> busTravelTime;
	public Map<Integer, Float> busTravelDistance;
	// mode choice model end
	
	// metrics start, pass for passengers
	public int numberOfGeneratedTaxiPass;
	public int numberOfGeneratedBusPass;
	public int taxiServedPass;
	public int busServedPass;
	public int numberOfLeavedTaxiPass;
	public int numberOfLeavedBusPass;
	public int numberOfRelocatedVehicles;
	public int taxiPassWaitingTime; //Waiting time of served Passengers
	public int busPassWaitingTime;
	public int taxiWaitingTime;
	// metrics end
	protected int vehicleStock=0; // Number of available vehicles at this zone
	
	// for repositioning
	protected int lastUpdateHour = -1;
	protected double futureDemand;
	protected double futureSupply;
	

	public Zone(int integerID) {
		this.id = ContextCreator.generateAgentID();
		this.integerID = integerID;
		this.sharablePassForTaxi = new HashMap<Integer, Queue<Passenger>>();
		this.charID = Integer.toString(integerID);
		this.firstPassInQueueForBus = null;
		this.firstPassInQueueForTaxi = null;
		this.nPassForBus = 0;
		this.nPassForTaxi = 0;
		this.hasBus = false;
		this.busGap = -1;
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
	}
	
	public void step(){
		// Zone serve passenger, passenger wait
		this.servePassengerByTaxi();
		this.passengerWaitTaxi();
		this.passengerWaitBus(); // Passenger wait
		this.relocateTaxi();
		this.taxiWaitPassenger();
		// if(GlobalVariables.HUB_INDEXES.contains(this.integerID)){
			// ContextCreator.logger.debug(this.integerID + "Vehicle: "+ this.vehicleStock + "Pass: "+ this.nPassForTaxi);
		// }
	}
	
	public void generatePassenger(){
		int tickcount = (int) RepastEssentials.GetTickCount();
		//double sim_time = tickcount*GlobalVariables.SIMULATION_STEP_SIZE;
		int hour = (int) Math.floor(tickcount
				* GlobalVariables.SIMULATION_STEP_SIZE / 3600);
//		System.out.println("Zone " + this.integerID + "Hour " + hour);
		if(this.lastUpdateHour!=hour){
			this.futureDemand = 0;
			this.futureSupply = 0;
		}
		Passenger current_taxi_pass = this.firstPassInQueueForTaxi;
		Passenger current_bus_pass = this.firstPassInQueueForBus;
		// Be prepared to add new passengers
		if(current_taxi_pass!=null){
			while(current_taxi_pass.nextPassengerInQueue()!=null){
				current_taxi_pass = current_taxi_pass.nextPassengerInQueue();
			}
		}
		if(current_bus_pass!=null){
			while(current_bus_pass.nextPassengerInQueue()!=null){
				current_bus_pass = current_bus_pass.nextPassengerInQueue();
			}
		}
		
		if(this.zoneClass == 0){ //From other place to hub
            int j = 0;
            for(int destination : GlobalVariables.HUB_INDEXES){
            	double numToGenerate = ContextCreator.getTravelDemand().get(this.getIntegerID()+j*GlobalVariables.NUM_OF_ZONE*2+
        				GlobalVariables.NUM_OF_ZONE).get(hour) / 12.0;
            	if(this.lastUpdateHour!=hour){
            		this.futureDemand+=numToGenerate * GlobalVariables.PASSENGER_DEMAND_FACTOR;
            		this.futureSupply+=ContextCreator.getTravelDemand().get(this.getIntegerID()+j*GlobalVariables.NUM_OF_ZONE*2).get(hour) * GlobalVariables.PASSENGER_DEMAND_FACTOR / 12.0;
            	}
            	numToGenerate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
            	numToGenerate = Math.floor(numToGenerate) + (Math.random()<(numToGenerate-Math.floor(numToGenerate))?1:0);
				for (int i = 0; i < numToGenerate; i++) {
					Passenger new_pass = new Passenger(this.integerID, destination, 24000); // Wait for at most 2 hours
					float threshold = getSplitRatio(destination);
					if ((Math.random() > threshold && hasBus) || !hasBus) {
						nPassForTaxi += 1;
						if(new_pass.isShareable()) {
							if(!this.sharablePassForTaxi.keySet().contains(destination)) {
								this.sharablePassForTaxi.put(destination, new LinkedList<Passenger>());
							}
							this.sharablePassForTaxi.get(destination).add(new_pass);
						}
						else {
							if (this.firstPassInQueueForTaxi == null) {
								this.firstPassInQueueForTaxi = new_pass;
							} else {
								current_taxi_pass.setNextPassengerInQueue(new_pass);
							}
							current_taxi_pass = new_pass;
						}
						this.numberOfGeneratedTaxiPass += 1;
					} else {
						nPassForBus += 1;
						if (this.firstPassInQueueForBus == null) {
							this.firstPassInQueueForBus = new_pass;
						} else {
							current_bus_pass.setNextPassengerInQueue(new_pass);
						}
						current_bus_pass = new_pass;
						this.numberOfGeneratedBusPass += 1;
					}
				}
				j+=1;
			}
			//ContextCreator.logger.debug("current buss pass is"+this.numberOfGeneratedBusPass); 
			//ContextCreator.logger.debug("current taxi pass is"+this.numberOfGeneratedTaxiPass);
		}
		else if (this.zoneClass == 1) { //From hub to other place
			int j = GlobalVariables.HUB_INDEXES.indexOf(this.integerID);
			for (int i = 0; i < GlobalVariables.NUM_OF_ZONE; i++) {
				int destination = i;
				double numToGenerate =ContextCreator.getTravelDemand().get(i+j*GlobalVariables.NUM_OF_ZONE*2).get(hour) / 12.0;
				
				if(this.lastUpdateHour!=hour){
            		this.futureDemand+=numToGenerate*GlobalVariables.PASSENGER_DEMAND_FACTOR;
            		this.futureSupply+=ContextCreator.getTravelDemand().get(i+j*GlobalVariables.NUM_OF_ZONE*2+GlobalVariables.NUM_OF_ZONE).get(hour)*GlobalVariables.PASSENGER_DEMAND_FACTOR / 12.0;
            	}
				numToGenerate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
				numToGenerate = Math.floor(numToGenerate) + (Math.random()<(numToGenerate-Math.floor(numToGenerate))?1:0);
	            if (destination != this.integerID) {
					for (int k = 0; k < numToGenerate; k++) {
						Passenger new_pass = new Passenger(this.integerID, destination, 24000); // Pass wait for two hours
						float threshold = getSplitRatio(destination);
						if ((Math.random() > threshold && hasBus) || !hasBus) {
							nPassForTaxi += 1;
							if(new_pass.isShareable()) {
								if(!this.sharablePassForTaxi.keySet().contains(destination)) {
									this.sharablePassForTaxi.put(destination, new LinkedList<Passenger>());
								}
								this.sharablePassForTaxi.get(destination).add(new_pass);
							}
							else {
								if (this.firstPassInQueueForTaxi == null) {
									this.firstPassInQueueForTaxi = new_pass;
								} else {
									current_taxi_pass.setNextPassengerInQueue(new_pass);
								}
								current_taxi_pass = new_pass;
							}
							
							this.numberOfGeneratedTaxiPass += 1;
						} else {
							nPassForBus += 1;
							if (this.firstPassInQueueForBus == null) {
								this.firstPassInQueueForBus = new_pass;
							} else {
								current_bus_pass.setNextPassengerInQueue(new_pass);
							}
							current_bus_pass = new_pass;
							this.numberOfGeneratedBusPass += 1;
						}
					}
				}
			}
		}
		//ContextCreator.logger.debug("current buss pass is"+this.numberOfGeneratedBusPass); 
		//ContextCreator.logger.debug("current taxi pass is"+this.numberOfGeneratedTaxiPass);
		if(this.lastUpdateHour!=hour){
			this.lastUpdateHour = hour;
		}
	    //ContextCreator.logger.info("Passenger generated");
	}
	
	// Serve passenger
	public void servePassengerByTaxi() {
		// Ridesharing matching for the sharable passengers. Current solution: If the passenger goes to the same place, pair them together.
		// First serve sharable passengers
		if(this.sharablePassForTaxi.size()>0) {
			for(Queue<Passenger> passQueue: this.sharablePassForTaxi.values()) {
				if(passQueue.size()>0 && ContextCreator.getVehicleContext().getVehicles(this.integerID).size()>0) {
					int pass_num = passQueue.size();
					int v_num = ContextCreator.getVehicleContext().getVehicles(this.integerID).size();
					v_num = (int) Math.min(Math.ceil(pass_num/4.0),v_num);
					for(int i=0; i<v_num; i++) {
						ElectricVehicle v = ContextCreator.getVehicleContext().getVehicles(this.integerID).poll();
						ArrayList<Passenger> tmp_pass = new ArrayList<Passenger>();
						for(int j=0; j< Math.min(4,pass_num); j++) {
							Passenger p = passQueue.poll();
							tmp_pass.add(p);
						    // record served passengers
							this.nPassForTaxi-=1;
							this.taxiServedPass += 1;
							GlobalVariables.SERVE_PASS += 1;
							this.taxiPassWaitingTime += p.getWaitingTime();
						}
						v.servePassenger(tmp_pass);
						this.removeVehicleStock(1);
						pass_num = pass_num - tmp_pass.size();
					}
				}
			}
		}
		
		
		// FCFS service for the rest of passengers
		while (this.firstPassInQueueForTaxi!=null && ContextCreator.getVehicleContext().getVehicles(this.integerID).peek() != null) {
			ElectricVehicle v = ContextCreator.getVehicleContext().getVehicles(this.integerID).poll();
			Passenger p = this.firstPassInQueueForTaxi;
			this.firstPassInQueueForTaxi = p.nextPassengerInQueue();
			//System.out.println("Taxi serving passengers: Origin: " + p.getOrigin() + " Remaining queue" + this.passQueueForTaxi);
			v.servePassenger(Arrays.asList(p));
			this.removeVehicleStock(1);
			// record served passenger
			this.nPassForTaxi-=1;
			this.taxiServedPass += 1;
			GlobalVariables.SERVE_PASS += 1;
			this.taxiPassWaitingTime += p.getWaitingTime();
		}
	}
	
	//Rebalance when the vehicleStock is negative
	public void relocateTaxi() {
		int num_to_relocate = (int) Math
				.round((nPassForTaxi - this.vehicleStock + this.futureDemand  - futureSupply ) / 5);

		double max_stock = 0;
		Zone source = null;
		for (Zone z : ContextCreator.getZoneGeography().getAllObjects()) {
			if (max_stock < (z.getVehicleStock() - z.nPassForTaxi - z.futureDemand  + futureSupply)) {
				max_stock = z.getVehicleStock() - z.nPassForTaxi - z.futureDemand  + futureSupply ;
				source = z;
			}
		}
		while (num_to_relocate > 0) {
			if (source != null) {
				if (ContextCreator.getVehicleContext().getVehicles(source.getIntegerID()).peek() != null) {
					ElectricVehicle v = ContextCreator.getVehicleContext().getVehicles(source.getIntegerID()).poll();
					v.relocation(source.getIntegerID(), this.integerID);
					this.numberOfRelocatedVehicles += 1;
					// GlobalVariables.NUM_RELOCATED_VEHICLES+=1;
					source.removeVehicleStock(1);
				}
			} else {
				break; // The system is short of vehicles!
			}
			num_to_relocate -= 1;
		}
	}

    public ArrayList<Passenger> servePassengerByBus(int maxPassNum, ArrayList<Integer> busStop){
    	ArrayList<Passenger> passOnBoard = new ArrayList<Passenger>();
    	Passenger p = this.firstPassInQueueForBus;
    	Passenger prevp = null;
    	while(p!=null){ // if there are passengers
    		if(passOnBoard.size()>= maxPassNum){
    			break;
    		}
    		if(busStop.contains(p.getDestination())){
    			passOnBoard.add(p);
    			if(prevp==null){
    				this.firstPassInQueueForBus = p.nextPassengerInQueue();
    			}else{
    				prevp.setNextPassengerInQueue(p.nextPassengerInQueue());
    			}
    		}
    		else{
    		    prevp = p;
    		}
    		p = p.nextPassengerInQueue();
    	}
    	//ContextCreator.logger.debug("Bus serving passengers: Passenger Number: " + passOnBoard + " Remaining queue" + this.passQueueForBus);
    	this.busServedPass+=passOnBoard.size();
    	this.nPassForBus -= passOnBoard.size();
    	for(Passenger pass: passOnBoard){
    		this.busPassWaitingTime+=pass.getWaitingTime();
    	}
    	return passOnBoard;
	}
    
    
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
    	
    	Passenger p = this.firstPassInQueueForTaxi;
    	Passenger prevp = null;
    	while(p!=null){
    		p.waitNextTime(GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL);
    		if(p.check()){
    			if(prevp==null){
    				this.firstPassInQueueForTaxi = p.nextPassengerInQueue();
    			}else{
    				prevp.setNextPassengerInQueue(p.nextPassengerInQueue());
    			}
    			this.numberOfLeavedTaxiPass += 1;
    			nPassForTaxi -= 1;
    		}
    		else{
    			prevp = p;
    		}
    		p = p.nextPassengerInQueue();
    	}
    }
    
    public void passengerWaitBus(){
    	Passenger p = this.firstPassInQueueForBus;
    	Passenger prevp = null;
    	while(p!=null){
    		p.waitNextTime(GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL);
    		if(p.check()){
    			if(prevp==null){
    				this.firstPassInQueueForBus = p.nextPassengerInQueue();
    			}else{
    				prevp.setNextPassengerInQueue(p.nextPassengerInQueue());
    			}
    			this.numberOfLeavedBusPass += 1;
    			nPassForBus -= 1;
    		}
    		else{
    			prevp = p;
    		}
    		p = p.nextPassengerInQueue();
    	}
	}
    
    public void taxiWaitPassenger(){
    	this.taxiWaitingTime += ContextCreator.getVehicleContext().getVehicles(this.integerID).size()*GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL;
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
	
	public void removeVehicleStock(int vehicle_num){
		if(this.vehicleStock-vehicle_num<0){
			System.err.println(this.integerID + " out of stock, vehicle_num: " + this.vehicleStock);
			return;
		}
		this.vehicleStock -= vehicle_num;
	}
	
	public int getVehicleStock(){
		return this.vehicleStock;
	}

	public void setBusInfo(int vehicle_gap) {
		hasBus = true;
		this.busGap = vehicle_gap;
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
	
	public float getSplitRatio(int destID){
		if(busTravelTime.containsKey(destID) && taxiTravelTime.containsKey(destID)){
			// 131 140 180 index
//			ContextCreator.logger.debug("Bus Time for Zone "+this.getIntegerID()+": " + busTravelTime.get(destID));
//			ContextCreator.logger.debug("Bus Gap for Zone "+this.getIntegerID()+": " + this.busGap);
//			ContextCreator.logger.debug("Taxi Time for Zone "+this.getIntegerID()+": " + taxiTravelTime.get(destID));
			float taxiUtil = alpha*(initialPriceTaxi+basePriceTaxi*taxiTravelDistance.get(destID))+
					beta*(taxiTravelTime.get(destID)/60+5)+taxiBase;
			float busUtil = (float) (alpha*busTicketPrice+
					beta*(busTravelTime.get(destID)/60+this.busGap/2)+busBase);
			return (float) (Math.exp(1)/(Math.exp(taxiUtil-busUtil)+Math.exp(1)));
		}
		else{
			return 0;
		}
	}
	
	public void updateTravelEstimation(){
			Map<Integer, Float> travelDistanceMap = new HashMap<Integer, Float>();
			Map<Integer, Float> travelTimeMap = new HashMap<Integer, Float>();
			// is hub
			if(this.zoneClass==1){
				// shortest path travel time to all other zones
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
				// shortest path to hubs
				for(int z2_id: GlobalVariables.HUB_INDEXES){
					Zone z2 = ContextCreator.getCityContext().findHouseWithDestID(z2_id);
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
}


