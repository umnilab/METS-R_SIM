package addsEVs.citycontext;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.vehiclecontext.Bus;
import addsEVs.vehiclecontext.ElectricVehicle;
import addsEVs.vehiclecontext.Vehicle;

/**
* @author: Jiawei Xue
* Charging facilities for EV cars and buses
**/

public class ChargingStation{
	private int integerID;
	// We assume the battery capacity for the bus is 300.0 kWh, and the battery capacity for the taxi is 50.0 kWh.
	private LinkedBlockingQueue<ElectricVehicle> queueChargingL2;  //car queue waiting for L2 charging
	private LinkedBlockingQueue<ElectricVehicle> queueChargingL3;  //car queue waiting for L3 charging
	private LinkedBlockingQueue<Bus> queueChargingBus;             //bus queue waiting for bus charging
	private int num2;       // number of L2 chargers
	private int num3;       // number of L3 chargers
	private ArrayList<ElectricVehicle> chargingVehicleL2;    // cars that are charging themselves under the L2 chargers
	private ArrayList<ElectricVehicle> chargingVehicleL3;    // cars that are charging themselves under the L3 chargers
	private ArrayList<Bus> chargingBus;                      // buses that are charging themselves under the bus chargers
	
	private static double chargingRateL2 = 10.0;  // charging rate for L2: 10.0kWh/hour
	private static double chargingRateL3 = 50.0;  // charging rate for L3: 50.0kWh/hour
	private static double chargingRateBus = 100.0; // charging rate for bus: 100.0kWh/hour
	
	// The average electricity price in the USA is 0.10-0.12 dollar per kWh.
	// Here, we assume the electricity price in charging station is 0.20 dollar per kWh.
	private static double chargingFeeL2 =  2.0;   // charging price: 2.0 dollars/hour
	private static double chargingFeeL3 =  10.0;   // charging price: 10.0 dollars/hour 
	private static float alpha = 10.0f;  //dollars/hour
	
	public int numChargedVehicle;

	public ChargingStation(int integerID, int numL2, int numL3){
		ContextCreator.generateAgentID();
		this.integerID = integerID;
		Integer.toString(integerID);
		this.num2 = numL2;               // number of level 2 charger 
		this.num3 = numL3;               // number of level 3 charger
		this.queueChargingL2 = new LinkedBlockingQueue<ElectricVehicle>();
		this.queueChargingL3 = new LinkedBlockingQueue<ElectricVehicle>();
		this.queueChargingBus = new LinkedBlockingQueue<Bus>();
		this.chargingVehicleL2 = new ArrayList<ElectricVehicle>();
		this.chargingVehicleL3 = new ArrayList<ElectricVehicle>();
		this.chargingBus = new ArrayList<Bus>();
		this.numChargedVehicle = 0;
	}
	
	/**
	* every fresh time, run the step() function
	*/
	// step function
	public void step() {
		chargeL2();  //function 3.
		chargeL3();  //function 4.
		chargeBus(); //function 5.
	}
	
	// function1: busArrive()
	public void receiveBus(Bus bus){
//		System.out.print("Receiving bus!");
		bus.initial_charging_state = bus.getBatteryLevel();
		queueChargingBus.add(bus);
	}
	
	public int capacity(){
		return this.num2 + this.num3 - this.chargingVehicleL2.size() - this.chargingVehicleL3.size();
	}
	
	// function2: vehicleArrive()
	public void receiveVehicle(ElectricVehicle ev){
		ev.initial_charging_state = ev.getBatteryLevel();
		this.numChargedVehicle += 1;
		if ((num2>0)&&(num3>0)){
			double utilityL2 = -alpha * totalTimeL2(ev) - chargingCostL2(ev);
			double utilityL3 = -alpha * totalTimeL3(ev) - chargingCostL3(ev);
			double shareOfL2 = Math.exp(utilityL2)/(Math.exp(utilityL2) + Math.exp(utilityL3));
			double random = Math.random();	
			if (random < shareOfL2){  
				queueChargingL2.add(ev);
			}else{
				queueChargingL3.add(ev); 
			}			
		}else if(num2>0){
			queueChargingL2.add(ev);
		}else if(num3>0){
			queueChargingL3.add(ev);
		}else{
			this.numChargedVehicle -= 1;
			ContextCreator.logger.info("No chargers at this station");
		}
	}
	
	// function3: charge the vehicle by the L2 chargers.
	public void chargeL2(){
		if (chargingVehicleL2.size() > 0){   // the number of vehicles that are at chargers.
			for (int i=0; i < chargingVehicleL2.size(); i++) {
				ElectricVehicle ev = chargingVehicleL2.get(i);   
				double maxChargingDemand = 50.0 - ev.getBatteryLevel();  // the maximal battery level is 50.0kWh
				
				//double maxChargingSupply = chargingRateL2/3600.0 * GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL
				//		*GlobalVariables.SIMULATION_STEP_SIZE;   // the maximal charging supply(kWh) within every 100 ticks. 
				double C_car = 50.0;
				double SOC_i = ev.getBatteryLevel()/C_car;
				double P = chargingRateL2;
				double t = GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL * GlobalVariables.SIMULATION_STEP_SIZE/3600.0;
				double maxChargingSupply = (nonlinearCharging(SOC_i, C_car, P, t)-SOC_i)*C_car;
				
				if (maxChargingDemand > maxChargingSupply){      // the vehicle completes charging 
					ev.chargeItself(maxChargingSupply);          // battery increases by maxSupply
				}else{
					ev.chargeItself(maxChargingDemand);  // battery increases by maxChargingDemand
					chargingVehicleL2.remove(i);	     // the vehicle leaves the charger
					// add vehicle to newqueue of corresponding road
					ev.finishCharging(this.getIntegerID(), "L2");
				}
			}
		}
		// the vehicles in the queue enter the charging areas.
		if (chargingVehicleL2.size() < num2){           //num2: number of L2 charger.       
			int addNumber = Math.min(num2-chargingVehicleL2.size(), queueChargingL2.size());
			for (int i=0; i<addNumber; i++){
				ElectricVehicle vehicleEnter = queueChargingL2.poll();
				chargingVehicleL2.add(vehicleEnter);     //the vehicle enters the charging areas.
			}
			for(ElectricVehicle v: queueChargingL2) {
				v.charging_waiting_time += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
			}
		}
	}
	
	// function4: charge the vehicle by the L3 chargers.
	public void chargeL3(){
		if (chargingVehicleL3.size() > 0){   // the number of vehicles that are at chargers.
			for (int i=0; i < chargingVehicleL3.size(); i++) {
				ElectricVehicle ev = chargingVehicleL3.get(i);   
				double maxChargingDemand = GlobalVariables.EV_BATTERY - ev.getBatteryLevel();  //the maximal battery level is 50.0kWh
				
				//double maxChargingSupply = chargingRateL3/3600.0 * GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL
				//		*GlobalVariables.SIMULATION_STEP_SIZE;   //the maximal charging supply(kWh) within every 100 ticks. 
				double C_car = GlobalVariables.EV_BATTERY;
				double SOC_i = ev.getBatteryLevel()/C_car;
				double P = chargingRateL3;
				double t = GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL * GlobalVariables.SIMULATION_STEP_SIZE/3600.0;
				double maxChargingSupply = (nonlinearCharging(SOC_i, C_car, P, t)-SOC_i)*C_car;
				
				if (maxChargingDemand > maxChargingSupply) {     // the vehicle completes charging 
					ev.chargeItself(maxChargingSupply);          // battery increases by maxSupply
				}else{
					ev.chargeItself(maxChargingDemand);  // battery increases by maxChargingDemand
					chargingVehicleL3.remove(i);	     // the vehicle leaves the charger
					ev.finishCharging(this.getIntegerID(), "L3");
				}
			}
		}
		// the vehicles in the queue enter the charging areas.
		if (chargingVehicleL3.size() < num3) {           // num3: number of L3 charger.       
			int addNumber = Math.min(num3-chargingVehicleL3.size(), queueChargingL3.size());
			for (int i=0; i<addNumber; i++) {
				ElectricVehicle vehicleEnter = queueChargingL3.poll();
				chargingVehicleL3.add(vehicleEnter);     // the vehicle enters the charging areas.
			}
			for(ElectricVehicle v: queueChargingL3) {
				v.charging_waiting_time += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
			}
		}
	}
	
	// function5: charge the bus
	public void chargeBus() {
		if (chargingBus.size() > 0){   // the number of vehicles that are at chargers.
			for (int i=0; i < chargingBus.size(); i++) {
				Bus evBus = chargingBus.get(i);   
				double maxChargingDemand = GlobalVariables.BUS_BATTERY - evBus.getBatteryLevel();  //the maximal battery level is 250kWh
				
				//double maxChargingSupply = chargingRateBus/3600.0 * GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL
				//		*GlobalVariables.SIMULATION_STEP_SIZE;   // the maximal charging supply(kWh) within every 100 ticks. 
				double C_bus = GlobalVariables.BUS_BATTERY;
				double SOC_i = evBus.getBatteryLevel()/C_bus;
				double P = chargingRateBus;
				double t = GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL * GlobalVariables.SIMULATION_STEP_SIZE/3600.0;
				double maxChargingSupply = (nonlinearCharging(SOC_i, C_bus, P, t)-SOC_i)*C_bus;
				
				if (maxChargingDemand > maxChargingSupply) {     // the vehicle completes charging 
					evBus.chargeItself(maxChargingSupply);          // battery increases by maxSupply
				}else{
					evBus.chargeItself(maxChargingDemand);  // battery increases by maxChargingDemand
					chargingBus.remove(i);	     // the vehicle leaves the charger
					evBus.setState(Vehicle.BUS_TRIP);
					evBus.setNextPlan();
					// add vehicle to newqueue of corresponding road
					Coordinate currentCoord = evBus.getOriginalCoord();
					Road road = ContextCreator.getCityContext().findRoadAtCoordinates(currentCoord, false);
					road.addVehicleToNewQueue(evBus);
					evBus.finishCharging(this.getIntegerID(), "Bus");
				}
			}
		}
		// the buses in the queue enter the charging areas.
		int addNumber = queueChargingBus.size(); //Math.min(busCharger-chargingBus.size(), queueChargingBus.size());
		for (int i=0; i<addNumber; i++) {
			Bus evBus = queueChargingBus.poll();
			chargingBus.add(evBus);     // the vehicle enters the charging areas.
			for(Bus b: queueChargingBus) {
				b.charging_waiting_time += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
			}
		}
	}
	
	// function: estimate the total time of using L2 charger.
	// It consists of two parts: waiting time and charging time.
	// Waiting time is equal to total charging demand in front of the vehicle divided by the maximal L2 charging supply.//
	public double totalTimeL2(ElectricVehicle ev) {   // hour
		int numChargingVehicle = chargingVehicleL2.size();   // number of vehicles that is being charging.
		int numWaitingVehicle = queueChargingL2.size();      // number of vehicles that in the queue.
		// assume each charging vehicle needs 20kWh more, each waiting vehicle needs 40kWh.
		double waitingTime = (numChargingVehicle * 20.0 + numWaitingVehicle * 40.0)*3600.0/(chargingRateL2*num2); //unit: second
		double chargingTime = (50.0 - ev.getBatteryLevel())*3600/chargingRateL2;
		double totalTime = waitingTime + chargingTime;
		return totalTime;
	}	
			
	// function: the charging price of using L2 charger.
	public double chargingCostL2(ElectricVehicle ev) {
		double chargingTime = (50.0 - ev.getBatteryLevel())/chargingRateL2;  //unit: hour
		double price = chargingTime * chargingFeeL2;    // unit: dollar
		return price;
	}
	
	// function: estimate the total time of using L3 charger.
	// It consists of two parts: waiting time and charging time.
	// Waiting time is equal to total charging demand in front of the vehicle divided by the maximal L2 charging supply.//
	public double totalTimeL3(ElectricVehicle ev) {   // hour
		int numChargingVehicle = chargingVehicleL3.size();   // number of vehicles that is being charging.
		int numWaitingVehicle = queueChargingL3.size();      // number of vehicles that in the queue.
		// assume each charging vehicle needs 20kWh more, each waiting vehicle needs 40kWh.
		double waitingTime = (numChargingVehicle * 20.0 + numWaitingVehicle * 40.0)*3600.0/(chargingRateL3*num3); //unit: second
		double chargingTime = (50.0 - ev.getBatteryLevel())*3600/chargingRateL3;
		double totalTime = waitingTime + chargingTime;
		return totalTime;
	}
			
	// function: the charging price of using L2 chager.
	public double chargingCostL3(ElectricVehicle ev) {
		double chargingTime = (50.0 - ev.getBatteryLevel())/chargingRateL3;  //unit: hour
		double price = chargingTime * chargingFeeL3;    // unit: dollar
		return price;
	}
	
	// getID
	public int getIntegerID(){
		return this.integerID;
	}
	
	// get Coordinate
	public Coordinate getCoord() {
		return ContextCreator.getChargingStationGeography().getGeometry(this)
				.getCentroid().getCoordinate();
	}
	
	// nonlinear charging model: Juan,Xue, Jan 30, 2020
	// SOC_i, SOC_f:State of charge,[0,1]; C: total battery capacity(kWh), P:charging power(kW),t:actual charging time(hour)
	public double nonlinearCharging(double SOC_i, double C, double P,double t) {
		double y = SOC_i;
		double beta = P/C;
		double A = (64*Math.pow(y,3)/27-5*y/2)/Math.pow(beta,3);
		double B = Math.sqrt((320*Math.pow(y,4)/9-1525*y*y/12+125)/Math.pow(beta,6));
		double t_star = Math.cbrt(A+B) + Math.cbrt(A-B) + 4*y/(3*beta);
		double t_2 = t_star + t;
		double SOC_f = Math.min(1.0, beta*t_2*(beta*beta*t_2*t_2+15)/(2*beta*beta*t_2*t_2+15));
		//ContextCreator.logger.debug("Bingo!"+y+ " " +beta + " "+t_star + " "+ t + " "+ SOC_f);
		//ContextCreator.logger.debug("Bingo2!"+ Math.cbrt(A+B)+ " " +Math.cbrt(A-B) + " "+4*y/(3*beta));
		return SOC_f;
	}
}
