package mets_r.facility;

import java.io.IOException;

/**
 * Taxi/Bus service Zone
 * Each zone has two queues of passengers, one for buses and one for taxis
 * Any problem please contact lei67@purdue.edu
 * 
 * @author: Zengixang Lei
 **/

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.ElectricVehicleWithAbandon;
import mets_r.mobility.Plan;
import mets_r.mobility.Request;
import mets_r.mobility.RequestWithValuation;
import mets_r.mobility.Vehicle;
import repast.simphony.essentials.RepastEssentials;

public class ZoneWithAbandon extends Zone{
	private ArrayList<ElectricVehicle> evAbandonList;// added by xiaowei on 09/29
	public ArrayList<Request> passAbandonList;// added by xiaowei on 09/29
	private String formated_msg;
	
	// to test the relocation policy added by xiaowei on 10/06
	public int want_relocate_times;
	public int want_relocate_num;
	public int fail_times;
	public int succ_times;

	public ZoneWithAbandon(int integerID, int capacity) {
		super(integerID, capacity);
		//Initialize metrices
		this.evAbandonList = new ArrayList<ElectricVehicle>();// added by xiaowei on 09/29
		this.passAbandonList = new ArrayList<Request>();// added by xiaowei on 09/29
		this.want_relocate_times = 0;// added by xiaowei on 10/06
		this.want_relocate_num = 0;// added by xiaowei on 10/06
		this.fail_times = 0;// added by xiaowei on 10/06
		this.succ_times = 0;// added by xiaowei on 10/06
	}
	
	@Override
	public void generatePassenger(){
		int tickcount = (int) RepastEssentials.GetTickCount();
		int hour = (int) Math.floor(tickcount
				* GlobalVariables.SIMULATION_STEP_SIZE / 3600);
		if(this.lastUpdateHour != hour){ 
			this.futureDemand.set(0);
		}
		
		// change the 'zone to hub' and 'hub to zone' demand to 'zone to zone': modified by xiaowei on 09/29
		for (int i = 0; i < GlobalVariables.NUM_OF_ZONE; i++) {
			int destination = i;
			if(this.getIntegerID() != i) {
				double passRate = ContextCreator.getTravelDemand(this.getIntegerID(), destination, hour)
						* (GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL
								/ (3600 / GlobalVariables.SIMULATION_STEP_SIZE));passRate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
				double numToGenerate = Math.floor(passRate) + (Math.random()<(passRate-Math.floor(passRate))?1:0);
				if(busReachableZone.contains(destination)) {
					float threshold = getSplitRatio(destination, false);
					for (int k = 0; k < numToGenerate; k++) {
						Zone z2 = ContextCreator.getCityContext().findZoneWithIntegerID(destination);
						Request new_pass = new RequestWithValuation(this.integerID, destination,this.getCoord(), z2.getCoord(), this.rand_demand);; // Pass wait for 10 mins
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
						this.futureDemand.addAndGet((int) (passRate * threshold));
					}
				}
				else if(GlobalVariables.COLLABORATIVE_EV && this.nearestZoneWithBus.containsKey(destination)){
					float threshold = getSplitRatio(destination, true);
					for (int k = 0; k < numToGenerate; k++) {
						if (Math.random() > threshold) {
							Zone z2 = ContextCreator.getCityContext().findZoneWithIntegerID(destination);
							Request new_pass = new RequestWithValuation(this.integerID, destination,this.getCoord(), z2.getCoord(), this.rand_demand);; // Pass wait for 10 mins
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
							Request new_pass = new Request(this.integerID, activityPlan); 
							this.addBusPass(new_pass);				
							this.numberOfGeneratedCombinedRequest += 1;
						}
					}
					if(this.lastUpdateHour != hour){ 
						this.nearestZoneWithBus.get(destination).futureDemand.addAndGet((int) (passRate * threshold));
					}
				}
				else {
					for (int k = 0; k < numToGenerate; k++) {
						Zone z2 = ContextCreator.getCityContext().findZoneWithIntegerID(destination);
						if(destination != this.getIntegerID()) {
							Request new_pass = new RequestWithValuation(this.integerID, destination,this.getCoord(), z2.getCoord(), this.rand_demand); // Pass wait for 10 mins
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
					}
					if(this.lastUpdateHour != hour){ 
						this.futureDemand.addAndGet((int) passRate);
					}
				}
			}
		}
			
		if(this.lastUpdateHour!=hour){
			this.lastUpdateHour = hour;
		}
            //System.out.println("number of generated taxi pass is"+this.numberOfGeneratedTaxiPass);
	}
	
	// Serve passenger
	@Override
	public void servePassengerByTaxi() {
		// FCFS service for the rest of passengers
		int curr_size = this.requestInQueueForTaxi.size();
		
		for (int i = 0; i < curr_size; i++) {
			ElectricVehicleWithAbandon v = (ElectricVehicleWithAbandon) ContextCreator.getVehicleContext()
					.getVehiclesByZone(this.integerID).poll();
			RequestWithValuation p = (RequestWithValuation) this.requestInQueueForTaxi.peek();

			double lamda_d_input = this.getVehicleStock();
			double lamda_p1 = this.nRequestForTaxi;
			// System.out.println("lamda_d_input = " + lamda_d_input + "; lamda_p1 " +
			// lamda_p1);
			double w_mq = this.QueueEstimateWaiting(lamda_d_input, lamda_p1);
			double prob_p = p.getValuationRequest()
					- (GlobalVariables.PASSENGER_FEE
							+ GlobalVariables.ONE_TIME_TRIP_FEE / p.getTripDistance() / 1000)
					- w_mq * GlobalVariables.TIMEVALUE_PASS / (p.getTripDistance() / 1000);
			// assume that for ev battery, every kwh can support 3 miles trip
			if (prob_p < 0) {
				p = (RequestWithValuation) this.requestInQueueForTaxi.poll();
				passAbandonList.add(p);
				this.nRequestForTaxi -= 1;
				if(v!=null) {
					ContextCreator.getVehicleContext()
					.getVehiclesByZone(this.integerID).add(v);
				}
				continue;
			}

			if (v != null) {
				double prob_d_q = GlobalVariables.DRIVER_WAGE_FEE - v.getValuationDriver() - v.utility_for_service / 3
						- w_mq * GlobalVariables.TIMEVALUE_DRIVER / (p.getTripDistance() / 1000);

				while (prob_d_q < 0) {
					formated_msg = RepastEssentials.GetTickCount() + "," + v.getVehicleFakeID() + ","
							+ v.start_zone_id + "," + v.getVehicleID() + ",7," + v.getOriginID() + ","
							+ v.getDestID() + "," + v.getAccummulatedDistance() + "," + v.getDepTime() + ","
							+ v.getTripConsume() + ",-1" + "," + v.getNumPeople() + "\r\n";
					try {
						ContextCreator.ev_logger.write(formated_msg);
						// v.accummulatedDistance_=0;
					} catch (IOException e) {
						e.printStackTrace();
					}

					// delete the v with negative prob_d_q
					evAbandonList.add(v);
					// for now, assume vehicle just refuse to serve current trip, vehicle can. if
					// vehicle leaves forever, use the sentences with #1
					if (v.getState() == Vehicle.PARKING) {
						this.removeOneParkingVehicle();
					} else {
						this.removeOneCruisingVehicle();
					} // #1

					if (v.getState() == Vehicle.CRUISING_TRIP) {
						v.leaveNetwork();
					}

					v = (ElectricVehicleWithAbandon) ContextCreator.getVehicleContext()
							.getVehiclesByZone(this.integerID).poll();

					// generate next v from list
					if (v != null) {
						prob_d_q = GlobalVariables.DRIVER_WAGE_FEE - v.getValuationDriver()
								- v.utility_for_service / 3 - w_mq;
					} else {
						break;
					}
				}

				if ((prob_p >= 0) && (prob_d_q >= 0)) {
					// System.out.println("Thanks, matched p and ev");
					p = (RequestWithValuation) this.requestInQueueForTaxi.poll();
					v.servePassenger(Arrays.asList(p));
					ContextCreator.getCityContext().findZoneWithIntegerID(v.getDestID()).futureSupply.addAndGet(1);
					if (v.getState() == Vehicle.PARKING) {
						this.removeOneParkingVehicle();
					} else {
						this.removeOneCruisingVehicle();
					} // #1
					this.nRequestForTaxi -= 1;
					GlobalVariables.SERVE_PASS += 1;
					this.taxiPickupRequest += 1;
					this.taxiPassWaitingTime += p.getCurrentWaitingTime();

					// record the pass-ev trip info
					formated_msg = RepastEssentials.GetTickCount() + "," + v.getVehicleID() + "," + v.getOriginID()
							+ "," + v.getDestID() + "," + v.getBatteryLevel() + "," + v.utility_for_service + ","
							+ v.getValuationDriver() + "," + GlobalVariables.TIMEVALUE_DRIVER + "," + prob_d_q + ","
							+ GlobalVariables.DRIVER_WAGE_FEE + "," + p.getTripDistance() + "," + p.getTripTime()
							+ "," + p.getValuationRequest() + "," + GlobalVariables.TIMEVALUE_PASS + "," + prob_p
							+ "," + GlobalVariables.PASSENGER_FEE + "\r\n";
					try {
						ContextCreator.passenger_logger.write(formated_msg);
						// v.accummulatedDistance_=0;
					} catch (IOException e) {
						e.printStackTrace();
					}

					// set 9 = Vehicle just match with passenger
					formated_msg = RepastEssentials.GetTickCount() + "," + v.getVehicleFakeID() + ","
							+ v.start_zone_id + "," + v.getVehicleID() + ",9," + v.getOriginID() + ","
							+ v.getDestID() + "," + v.getAccummulatedDistance() + "," + v.getDepTime() + ","
							+ v.getTripConsume() + ",-1" + "," + v.getNumPeople()+"\r\n";
					try {
						ContextCreator.ev_logger.write(formated_msg);
						// v.accummulatedDistance_=0;
					} catch (IOException e) {
						e.printStackTrace();
					}
				} else {
					if (v != null) {
						ContextCreator.getVehicleContext().getVehiclesByZone(this.integerID).add(v);
					}
				}
			}
		}
	}
	
    public int getNumAbandonRequest() {
    	return this.passAbandonList.size();
    }
    
    public int getNumAbandonEV() {
    	return this.evAbandonList.size();
    }

	
	public double QueueEstimateWaiting(double lamda_d_input, double lamda_p1){ // added by xiaowei on 09/29
		// this is the estimate waiting time of passenger and driver matching queue, SM/M/1 queue
		// lamda_p1: passenger arrival rate; lamda_d_input: the total ev arrival rate from charging (lamda_d_charging) and arrival (lamda_d_arrival)
		// double lamda_d_input = lamda_d_charging + lamda_d_arrival;
		double lamda = Math.min(lamda_p1,lamda_d_input);
		double mu2 = Math.max(lamda_p1,lamda_d_input);
		double rho = lamda/mu2;
		double w_mq = 0.0;
		//System.out.println("lamda = "+lamda +"; mu2 = " + mu2 + "; rho = " + rho);
//		if(lamda==0) {
//			System.out.println(lamda_p1);
//			System.out.println(lamda_d_input);
//			System.out.println("lamda==0!!!!!!");
//		}
		if(rho != 1) {
			w_mq = rho/((lamda+0.1)*(1-rho)) - 1/mu2;
		}
		return w_mq;
	}	
}


