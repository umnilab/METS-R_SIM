package mets_r.facility;
import java.util.ArrayList;
import java.util.LinkedList;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.ElectricVehicleWithAbandon;
import repast.simphony.essentials.RepastEssentials;

/**
* @author: Jiawei Xue, Zengxiang Lei, Xiaowei Chen
* Charging facilities for EV cars and buses
**/

public class ChargingStationWithAbandon extends ChargingStation{
	public int lambda0;
	private LinkedList<ElectricVehicle> leaveChargingL2;  // Car leave L2 charging due to charging station capacity
	private LinkedList<ElectricVehicle> leaveChargingL3;  // Car leave L3 charging due to charging station capacity
	private int waitnum2;       // Number of waiting space in L2 charging station
	private int waitnum3;       // Number of waiting space in L3 charging station
	private static double chargingRateL2 = 200.0;  // Charging rate for L2: 200.0kWh/hour // modified by xiaowei on 09/29
	private static double chargingRateL3 = 200.0;  // Charging rate for L3: 200.0kWh/hour // modified by xiaowei on 09/29
	private static double chargingRateBus = 100.0; // Charging rate for bus: 100.0kWh/hour
	public int numChargedVehicleL2;
	public int numChargedVehicleL3;
	public int numLeaveChargingL2;
	public int numLeaveChargingL3;
	
	public ChargingStationWithAbandon(int integerID, int numL2, int numL3){
		super(integerID, numL2, numL3);	
		this.lambda0 = 0; // the arrival rate of EV, equals the number of arrival EV per hour
		this.waitnum2 = 4; // number of waiting space in L2 charging station
		this.waitnum3 = 4; // number of waiting space in L3 charging station
		this.leaveChargingL2 = new LinkedList<ElectricVehicle>();
		this.leaveChargingL3 = new LinkedList<ElectricVehicle>();
		this.numChargedVehicleL2 = 0; this.numChargedVehicleL3 = 0; 
		this.numLeaveChargingL2 = 0;
		this.numLeaveChargingL3 = 0;
	}
	
	@Override
	public int capacity(){
		return super.capacity() + this.waitnum2 + this.waitnum3 - queueChargingL2.size() - queueChargingL3.size();
	}
	
	// Function2: vehicleArrive()
	public boolean tryToReceiveEV(ElectricVehicleWithAbandon ev) {
		int tickcount = (int) RepastEssentials.GetTickCount();
		if (tickcount * GlobalVariables.SIMULATION_STEP_SIZE % 3600 == 0) { // Set lamda to 0 if it is divisible by 3600
			this.lambda0 = 0;
		}
		this.lambda0 += 1;
		ev.initial_charging_state = ev.getBatteryLevel();
		this.numChargedVehicle += 1;
		double C_car = GlobalVariables.EV_BATTERY;
		boolean return_value = true;
		double utilityL2 = 0;
		double utilityL3 = 0;
		double cost_M2 = 0;
		double cost_M3 = 0;
		double Ud_c2_temp = 0;
		double Ud_c3_temp = 0;
		if (num2 > 0) {
			double chargingTime2 = (C_car - ev.getBatteryLevel()) / chargingRateL2;// unit: hour
			double p_02 = Q1p0(this.lambda0, num2, waitnum2, chargingRateL2);
			double w_q2 = Q1pn(this.lambda0, num2, waitnum2, chargingRateL2, p_02, queueChargingL2.size());
			double cost_L2 = GlobalVariables.TIMEVALUE_DRIVER * (w_q2 + chargingTime2); // Latency cost
			cost_M2 = GlobalVariables.ONE_TIME_CHARGING_FEE + chargingTime2 * GlobalVariables.ELECTRICITY_FEE_L2; // Monetary
																													// cost,
																													// unit:
																													// dollar
			utilityL2 = cost_L2 + cost_M2;
			// the cost if ev leave CSs
			// Ud_c2_temp = GlobalVariables.valuetime_driver*w_q2 +
			// (GlobalVariables.onetimefee + GlobalVariables.Electric_FEE_L2 *
			// ev.getBatteryLevel()/chargingRateL2);
			Ud_c2_temp = 0;
		}
		if (num3 > 0) {
			double chargingTime3 = (C_car - ev.getBatteryLevel()) / chargingRateL3;// unit: hour
			double p_03 = Q1p0(this.lambda0, num3, waitnum3, chargingRateL3);
			double w_q3 = Q1pn(this.lambda0, num3, waitnum3, chargingRateL3, p_03, queueChargingL3.size());
			double cost_L3 = GlobalVariables.TIMEVALUE_DRIVER * (w_q3 + chargingTime3); // Latency cost
			cost_M3 = GlobalVariables.ONE_TIME_CHARGING_FEE + chargingTime3 * GlobalVariables.ELECTRICITY_FEE_L3; // Monetary
																													// cost,
																													// unit:
																													// dollar
			utilityL3 = cost_L3 + cost_M3;
			// the cost if ev leave CSs
			// Ud_c3_temp = GlobalVariables.valuetime_driver*w_q3 +
			// (GlobalVariables.onetimefee + GlobalVariables.Electric_FEE_L3 *
			// ev.getBatteryLevel()/chargingRateL3);
			Ud_c3_temp = 0;
		}

		if ((num2 > 0) && (num3 > 0)) { // if the system have both L2 and L3, then decide which level to join
			double shareOfL2 = Math.exp(utilityL2) / (Math.exp(utilityL2) + Math.exp(utilityL3));
			double random = Math.random();
			if (random < shareOfL2) { // ev join L2 cs
				// if the current charging + waiting number is smaller than the CS capacity for
				// charging and waiting, add to number to the queue
				if (chargingVehicleL2.size() + queueChargingL2.size() < num2 + waitnum2) {
					this.toAddChargingL2.add(ev);
					this.numChargedVehicleL2 += 1;
					ev.utility_for_service = cost_M2;
				} else { // the utility for service with no recharging soc is the expected waiting cost
							// and the cost of remaining battery
					ev.utility_for_service = Ud_c2_temp;
					leaveChargingL2.add(ev);
					return_value = false;
					this.numChargedVehicle -= 1;
					this.numLeaveChargingL2 += 1;
				}
			} else {// ev join L3 cs
				if (chargingVehicleL3.size() + queueChargingL3.size() < num3 + waitnum3) {
					this.toAddChargingL3.add(ev);
					this.numChargedVehicleL3 += 1;
					ev.utility_for_service = cost_M3;
				} else {
					ev.utility_for_service = Ud_c3_temp;
					leaveChargingL3.add(ev);
					return_value = false;
					this.numChargedVehicle -= 1;
					this.numLeaveChargingL3 += 1;
				}
			}
		} else if ((num2 > 0) && (num3 == 0)) { // cs only have L2 chargers
			if (chargingVehicleL2.size() + queueChargingL2.size() < num2 + waitnum2) {
				this.toAddChargingL2.add(ev);
				this.numChargedVehicleL2 += 1;
				ev.utility_for_service = cost_M2;
			} else {
				ev.utility_for_service = Ud_c2_temp;
				leaveChargingL2.add(ev);
				return_value = false;
				this.numChargedVehicle -= 1;
				this.numLeaveChargingL2 += 1;
			}
		} else if ((num2 == 0) && (num3 > 0)) { // cs only have L3 chargers
			if (chargingVehicleL3.size() + queueChargingL3.size() < num3 + waitnum3) {
				this.toAddChargingL3.add(ev);
				this.numChargedVehicleL3 += 1;
				ev.utility_for_service = cost_M3;
			} else {
				ev.utility_for_service = Ud_c3_temp;
				leaveChargingL3.add(ev);
				return_value = false;
				this.numChargedVehicle -= 1;
				this.numLeaveChargingL3 += 1;
			}
		} else { // if no chargers found in this cs.
			this.numChargedVehicle -= 1;
			ContextCreator.logger.info("No chargers at this station");
		}

		return return_value;
	}
	
	// Function3: charge the vehicle by the L2 chargers.
	@Override
	public void chargeL2(){
		if (chargingVehicleL2.size() > 0){   // the number of vehicles that are at chargers.
			ArrayList<ElectricVehicle> toRemoveVeh = new ArrayList<ElectricVehicle>();
			for (ElectricVehicle v: chargingVehicleL2) {  
				ElectricVehicleWithAbandon ev = (ElectricVehicleWithAbandon) v;
				double C_car = GlobalVariables.EV_BATTERY ;
				double maxChargingDemand = C_car - ev.getBatteryLevel();  // the maximal battery level is 50.0kWh
				//double maxChargingSupply = chargingRateL2/3600.0 * GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL
				//		*GlobalVariables.SIMULATION_STEP_SIZE;   // the maximal charging supply(kWh) within every 100 ticks. 
				
				double SOC_i = ev.getBatteryLevel()/C_car;
				double P = chargingRateL2;
				double t = GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL * GlobalVariables.SIMULATION_STEP_SIZE/3600.0;
				double maxChargingSupply = (nonlinearCharging(SOC_i, C_car, P, t)-SOC_i)*C_car;
				
				if (maxChargingDemand > maxChargingSupply){      // the vehicle completes charging 
					ev.chargeItself(maxChargingSupply);          // battery increases by maxSupply
					ev.utility_for_service +=  t * GlobalVariables.TIMEVALUE_DRIVER; // added by xiaowei on 09/29
				}else{
					ev.chargeItself(maxChargingDemand);  // battery increases by maxChargingDemand
					if (maxChargingSupply > 0) {
						ev.utility_for_service +=  t * (maxChargingDemand/maxChargingSupply) * GlobalVariables.TIMEVALUE_DRIVER; // added by xiaowei on 09/29
						
					}
					toRemoveVeh.add(ev);	     // the vehicle leaves the charger
					// add vehicle to newqueue of corresponding road
					ev.finishCharging(this.getIntegerID(), "L2");
				}
			}
			for (ElectricVehicle ev: toRemoveVeh) {
				this.chargingVehicleL2.remove(ev);
			}
		}
		
		// The vehicles in the queue enter the charging areas.
		// The vehicles in the queue enter the charging areas.
		if (chargingVehicleL2.size() < num2){   //num2: number of L2 charger.       
			int addNumber = Math.min(num2-chargingVehicleL2.size(), queueChargingL2.size());
			for (int i=0; i<addNumber; i++){
				ElectricVehicle vehicleEnter = queueChargingL2.poll();
				chargingVehicleL2.add(vehicleEnter);     //the vehicle enters the charging areas.
			}
			for(ElectricVehicle v: queueChargingL2) {
				ElectricVehicleWithAbandon ev = (ElectricVehicleWithAbandon) v;
				ev.charging_waiting_time += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
				ev.utility_for_service +=  GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL * GlobalVariables.TIMEVALUE_DRIVER;// added by xiaowei on 09/29
			}
		}
	}
	
	// Function4: charge the vehicle by the L3 chargers.
	@Override
	public void chargeL3(){
		if (chargingVehicleL3.size() > 0){   // the number of vehicles that are at chargers.
			ArrayList<ElectricVehicle> toRemoveVeh = new ArrayList<ElectricVehicle>();
			for (ElectricVehicle v: chargingVehicleL3) { 
				ElectricVehicleWithAbandon ev = (ElectricVehicleWithAbandon) v;
				double C_car = GlobalVariables.EV_BATTERY;
				double maxChargingDemand = C_car - ev.getBatteryLevel();  //the maximal battery level is 50.0kWh
				double SOC_i = ev.getBatteryLevel()/C_car;
				double P = chargingRateL3;
				double t = GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL * GlobalVariables.SIMULATION_STEP_SIZE/3600.0;
				double maxChargingSupply = (nonlinearCharging(SOC_i, C_car, P, t)-SOC_i)*C_car;
				
				if (maxChargingDemand > maxChargingSupply) {     // the vehicle completes charging 
					ev.chargeItself(maxChargingSupply);          // battery increases by maxSupply
					ev.utility_for_service +=  t * GlobalVariables.TIMEVALUE_DRIVER; // added by xiaowei on 09/29
				}else{
					ev.chargeItself(maxChargingDemand);  // battery increases by maxChargingDemand
					if(maxChargingSupply > 0) {
						ev.utility_for_service +=  t * (maxChargingDemand/maxChargingSupply) * GlobalVariables.TIMEVALUE_DRIVER; // added by xiaowei on 09/29
					}
					toRemoveVeh.add(ev);	     // the vehicle leaves the charger
					ev.finishCharging(this.getIntegerID(), "L3");
				}
			}
			for (ElectricVehicle ev: toRemoveVeh) {
				this.chargingVehicleL3.remove(ev);
			}
		}
		// The vehicles in the queue enter the charging areas.
		if (chargingVehicleL3.size() < num3) {           // num3: number of L3 charger.       
			int addNumber = Math.min(num3-chargingVehicleL3.size(), queueChargingL3.size());
			for (int i=0; i<addNumber; i++) {
				ElectricVehicle vehicleEnter = queueChargingL3.poll();
				chargingVehicleL3.add(vehicleEnter);     // the vehicle enters the charging areas.
			}
			for(ElectricVehicle v: queueChargingL3) {
				ElectricVehicleWithAbandon ev = (ElectricVehicleWithAbandon) v;
				ev.utility_for_service +=  GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL * GlobalVariables.TIMEVALUE_DRIVER;// added by xiaowei on 09/29
				ev.charging_waiting_time += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
			}
		}
	}
	
	public double Q1p0(double lamdad0, double num, double waitnum, double chargingRate) {
		double rhoq1 = lamdad0 / (num * chargingRate);
		// System.out.print("lamdad0:"+ lamdad0+ "; num: "+ num + "; chargingRate:" +
		// chargingRate + "; rhoq1:" + rhoq1);
		double p0 = 0;
		if (rhoq1 < 1) {
			double p0_a = 0.0;
			for (int n = 0; n < num; n++) {
				p0_a += Math.pow(rhoq1, n) / factorial(n);
			}
			double p0_b = Math.pow(rhoq1, num) / factorial((int) num) * (1 - Math.pow(rhoq1 / num, waitnum + 1))
					/ (1 - rhoq1 / num);
			p0 = Math.pow(p0_a + p0_b, -1);
		} else if (rhoq1 == 1) {
			double p0_a = 0.0;
			for (int n = 0; n < num; n++) {
				p0_a += Math.pow(rhoq1, n) / factorial(n);
			}
			double p0_b = Math.pow(rhoq1, num) / factorial((int) num) * (waitnum + 1);
			p0 = Math.pow(p0_a + p0_b, -1);
		} else {
			ContextCreator.logger.info("rho is bigger than 1 at charging station");
		}
		return p0;
	}

	// pn_calculation calculation for M/M/C/K charging queue:// added by xiaowei on
	// 09/29
	@SuppressWarnings("unused")
	public double Q1pn(double lamdad0, double num, double waitnum, double chargingRate, double p0, int waitsize) {
		double rhoq1 = lamdad0 / (num * chargingRate);
		double p_n, W, W_q;
		p_n = W = W_q = 0.0;
		if (waitsize <= num) {
			p_n = Math.pow(rhoq1, waitsize) * p0 / factorial(waitsize);
		} else if (waitsize >= num & waitsize <= (num + waitnum)) {
			p_n = Math.pow(rhoq1, waitsize) * p0 / (factorial((int) num) * Math.pow(num, waitsize - num));
			double pC = Math.pow(rhoq1, (num + waitnum)) * p0 / (factorial((int) num) * Math.pow(num, waitnum));
			double L_q = 0.0;
			for (int n = (int) num; n < (num + waitnum); n++) {
				L_q += (n - num) * p_n;
			}
			double L = L_q + rhoq1 * (1 - pC);
			W = L / (lamdad0 * (1 - pC));
			W_q = L_q / (lamdad0 * (1 - pC));
		} else {
			p_n = 0.0;
		}
		return W_q;
	}

	// Method to find factorial of given number // added by xiaowei on 09/29
	static int factorial(int n) {
		int res = 1, i;
		for (i = 2; i <= n; i++)
			res *= i;
		return res;
	}
}
