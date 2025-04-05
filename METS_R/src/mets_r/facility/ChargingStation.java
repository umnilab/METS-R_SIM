package mets_r.facility;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Vehicle;

/**
 * This class defines charging facilities for EV cars and buses
 * @author: Jiawei Xue, Zengxiang Lei 
 */
public class ChargingStation {
	/* Constants */
	public final static double ChargingThres = 0.9; // Charging is considered as finished when reaching 90% SoC 
	
	/* Private variables */
	private int ID;
	private Random rand;
	// We assume the battery capacity for the bus is 300.0 kWh, and the battery
	// capacity for the taxi is 50.0 kWh.
	private LinkedList<ElectricVehicle> queueChargingL2; // Car queue waiting for L2 charging
	private LinkedList<ElectricVehicle> queueChargingL3; // Car queue waiting for L3 charging
	private LinkedList<ElectricBus> queueChargingBus; // Bus queue waiting for bus charging
	private int numL2; // Number of L2 chargers
	private int numL3; // Number of L3 chargers
	private int numBusCharger; //Number of Bus chargers
	private ArrayList<ElectricVehicle> chargingVehicleL2; // Cars that are charging themselves under the L2 chargers
	private ArrayList<ElectricVehicle> chargingVehicleL3; // Cars that are charging themselves under the L3 chargers
	private ArrayList<ElectricBus> chargingBus; // Buses that are charging themselves under the bus chargers

	private static double chargingRateL2 = 10.0; // Charging rate for L2: 10.0kWh/hour
	private static double chargingRateL3 = 100.0; // Charging rate for L3: 100.0kWh/hour
	private static double chargingRateBus = 100.0; // Charging rate for bus: 100.0kWh/hour

	// The average electricity price in the USA is 0.10-0.12 dollar per kWh.
	// We assume the electricity price in L2 charging station is 0.20 dollar per kWh, in L3 0.30 dollar per kWh
	private static double chargingFeeL2 = 2.0; // Charging price: 2.0 dollars/hour
	private static double chargingFeeL3 = 30.0; // Charging price: 30.0 dollars/hour
	
	// Weights in the utility functions
	private static float C0 = -1.265f;//
	private static float alpha = -0.96f; // Value of waiting time: dollars/hour
	private static float beta = -0.324f;
	private static float gamma = -2.16f; // Value of L3 charging time: dollars/hour
	private static float C1 = 0.776f;

	// For thread-safe operation
	private ConcurrentLinkedQueue<ElectricVehicle> toAddChargingL2; // Pending Car queue waiting for L2 charging
	private ConcurrentLinkedQueue<ElectricVehicle> toAddChargingL3; // Pending Car queue waiting for L3 charging
	private ConcurrentLinkedQueue<ElectricBus> toAddChargingBus; // Pending Bus queue waiting for bus charging
	
	/* Public Variables */
	// Statistics
	public int numChargedCar;
	public int numChargedBus;
	
	
	/**
	 * This function construct a charging station
	 * @param integerID charging station ID
	 * @param numL2 number of L2 chargers
	 * @param numL3 number of L3 chargers
	 * @param numBus number of bus chargers
	 */
	public ChargingStation(int integerID, int numL2, int numL3, int numBus) {
		this.ID = integerID;
		this.rand = new Random(GlobalVariables.RandomGenerator.nextInt());
		this.numL2 = numL2; // Number of level 2 chargers
		this.numL3 = numL3; // Number of level 3 chargers
		this.numBusCharger = numBus; // Number of bus chargers
		this.queueChargingL2 = new LinkedList<ElectricVehicle>();
		this.queueChargingL3 = new LinkedList<ElectricVehicle>();
		this.queueChargingBus = new LinkedList<ElectricBus>();
		this.chargingVehicleL2 = new ArrayList<ElectricVehicle>();
		this.chargingVehicleL3 = new ArrayList<ElectricVehicle>();
		this.chargingBus = new ArrayList<ElectricBus>();
		this.toAddChargingL2 = new ConcurrentLinkedQueue<ElectricVehicle>();
		this.toAddChargingL3 = new ConcurrentLinkedQueue<ElectricVehicle>();
		this.toAddChargingBus = new ConcurrentLinkedQueue<ElectricBus>();
		this.numChargedCar = 0;
	}

	// Step function
	public void step() {
		processToAddEV();
		processToAddBus();
		chargeL2(); // Function 3.
		chargeL3(); // Function 4.
		chargeBus(); // Function 5.
	}
	
	/**
	 * Calculate the current capacity of the charging station for electric taxis 
	 * @return Number of electric taxis
	 */
	public int capacity() {
		return this.numL2 + this.numL3 - this.chargingVehicleL2.size() - this.chargingVehicleL3.size();
	}
	
	public int capacityBus() {
		return this.numBusCharger - this.chargingBus.size();
	}

	public int numL2() {
		return this.numL2;
	}

	public int numL3() {
		return this.numL3;
	}
	
	public int numBusCharger() {
		return this.numBusCharger;
	}

	// Function1: busArrive()
	public void receiveBus(ElectricBus bus) {
		bus.initialChargingState = bus.getBatteryLevel();
		this.numChargedBus += 1;
		if (numBusCharger >0) this.toAddChargingBus.add(bus);
		else {
			this.numChargedBus -= 1;
			ContextCreator.logger.error("Something went wrong, no bus charger at this station.");
		}
	}

	public void processToAddBus() {
		for (ElectricBus bus = this.toAddChargingBus.poll(); bus != null; bus = this.toAddChargingBus.poll()) {
			queueChargingBus.add(bus);
		}
	}

	// Function2: vehicleArrive()
	public void receiveEV(ElectricVehicle ev) {
		ev.initialChargingState = ev.getBatteryLevel();
		this.numChargedCar += 1;
		if ((numL2 > 0) && (numL3 > 0)) {
			double utilityL2 = C0 + alpha * waitingTimeL2() + beta * chargingTimeL2(ev) * chargingFeeL2 + C1 * (ev.getSoC() > 0.8 ? 1 : 0);
			double utilityL3 = alpha* waitingTimeL3() + beta * chargingTimeL3(ev) * chargingFeeL3 + gamma * chargingTimeL3(ev);
			double shareOfL2 = Math.exp(utilityL2) / (Math.exp(utilityL2) + Math.exp(utilityL3));
			double random = rand.nextDouble();
			if (random < shareOfL2) {
				toAddChargingL2.add(ev);
			} else {
				toAddChargingL3.add(ev);
			}
		} else if (numL2 > 0) {
			toAddChargingL2.add(ev);
		} else if (numL3 > 0) {
			toAddChargingL3.add(ev);
		} else {
			this.numChargedCar -= 1;
			ContextCreator.logger.error("Something went wrong, no car charger at this station.");
		}
	}

	public void processToAddEV() {
		for (ElectricVehicle ev = this.toAddChargingL2.poll(); ev != null; ev = this.toAddChargingL2.poll()) {
			queueChargingL2.add(ev);
		}
		for (ElectricVehicle ev = this.toAddChargingL3.poll(); ev != null; ev = this.toAddChargingL3.poll()) {
			queueChargingL3.add(ev);
		}
	}

	// Function3: charge the vehicle by the L2 chargers.
	public void chargeL2() {
		if (chargingVehicleL2.size() > 0) { // the number of vehicles that are at chargers.
			ArrayList<ElectricVehicle> toRemoveVeh = new ArrayList<ElectricVehicle>();
			for (ElectricVehicle ev : chargingVehicleL2) {
				double maxChargingDemand = ev.getBatteryCapacity() * ChargingThres - ev.getBatteryLevel();

				// double maxChargingSupply = chargingRateL2/3600.0 *
				// GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL
				// *GlobalVariables.SIMULATION_STEP_SIZE; // the maximal charging supply(kWh)
				// within every 100 ticks.
				double C_car = ev.getBatteryCapacity();
				double SOC_i = ev.getBatteryLevel() / C_car;
				double P = chargingRateL2;
				double t = GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL
						* GlobalVariables.SIMULATION_STEP_SIZE / 3600.0;
				double maxChargingSupply = (nonlinearCharging(SOC_i, C_car, P, t) - SOC_i) * C_car;
				
				if (maxChargingDemand > maxChargingSupply) { // the vehicle completes charging
					ev.chargeItself(maxChargingSupply); // battery increases by maxSupply
				} else {
					ev.chargeItself(maxChargingDemand); // battery increases by maxChargingDemand
					toRemoveVeh.add(ev); // the vehicle leaves the charger
					// add vehicle to departureQueue of corresponding road
					ev.finishCharging(this.getID(), "L2");
				}
			}
			this.chargingVehicleL2.removeAll(toRemoveVeh);
		}

		// The vehicles in the queue enter the charging areas.
		if (chargingVehicleL2.size() < numL2) { // num2: number of L2 charger.
			int addNumber = Math.min(numL2 - chargingVehicleL2.size(), queueChargingL2.size());
			for (int i = 0; i < addNumber; i++) {
				ElectricVehicle vehicleEnter = queueChargingL2.poll();
				chargingVehicleL2.add(vehicleEnter); // the vehicle enters the charging areas.
			}
			for (ElectricVehicle v : queueChargingL2) {
				v.chargingWaitingTime += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
			}
		}
	}

	// Function4: charge the vehicle by the L3 chargers.
	public void chargeL3() {
		if (chargingVehicleL3.size() > 0) { // the number of vehicles that are at chargers.
			ArrayList<ElectricVehicle> toRemoveVeh = new ArrayList<ElectricVehicle>();
			for (ElectricVehicle ev : chargingVehicleL3) {
				double maxChargingDemand = ev.getBatteryCapacity() * ChargingThres - ev.getBatteryLevel(); // the maximal battery
																								// level is 50.0kWh
				double C_car = ev.getBatteryCapacity();
				double SOC_i = ev.getBatteryLevel() / C_car;
				double P = chargingRateL3;
				double t = GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL
						* GlobalVariables.SIMULATION_STEP_SIZE / 3600.0;
				double maxChargingSupply = (nonlinearCharging(SOC_i, C_car, P, t) - SOC_i) * C_car;
				
				if (maxChargingDemand > maxChargingSupply) { // the vehicle completes charging
					ev.chargeItself(maxChargingSupply); // battery increases by maxSupply
				} else {
					ev.chargeItself(maxChargingDemand); // battery increases by maxChargingDemand
					toRemoveVeh.add(ev); // the vehicle leaves the charger
					ev.finishCharging(this.getID(), "L3");
				}
			}
			this.chargingVehicleL3.removeAll(toRemoveVeh);
		}
		// The vehicles in the queue enter the charging areas.
		if (chargingVehicleL3.size() < numL3) { // num3: number of L3 charger.
			int addNumber = Math.min(numL3 - chargingVehicleL3.size(), queueChargingL3.size());
			for (int i = 0; i < addNumber; i++) {
				ElectricVehicle vehicleEnter = queueChargingL3.poll();
				chargingVehicleL3.add(vehicleEnter); // the vehicle enters the charging areas.
			}
			for (ElectricVehicle v : queueChargingL3) {
				v.chargingWaitingTime += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
			}
		}
	}

	// Function5: charge the bus
	public void chargeBus() {
		if (chargingBus.size() > 0) {
			ArrayList<ElectricBus> toRemoveBus = new ArrayList<ElectricBus>();
			for (ElectricBus evBus : chargingBus) {
				double maxChargingDemand = evBus.getBatteryCapacity() * ChargingThres - evBus.getBatteryLevel(); // the maximal battery
																									// level is 250kWh
				double C_bus = GlobalVariables.BUS_BATTERY;
				double SOC_i = evBus.getBatteryLevel() / C_bus;
				double P = chargingRateBus;
				double t = GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL
						* GlobalVariables.SIMULATION_STEP_SIZE / 3600.0;
				double maxChargingSupply = (nonlinearCharging(SOC_i, C_bus, P, t) - SOC_i) * C_bus;

				if (maxChargingDemand > maxChargingSupply) { // The vehicle completes charging
					evBus.chargeItself(maxChargingSupply); // Battery increases by maxSupply
				} else {
					evBus.chargeItself(maxChargingDemand); // Battery increases by maxChargingDemand
					toRemoveBus.add(evBus); // The vehicle leaves the charger
					evBus.setState(Vehicle.BUS_TRIP);
					evBus.finishCharging(this.getID(), "Bus");
				}
			}

			this.chargingBus.removeAll(toRemoveBus);
		}
		// The buses in the queue enter the charging areas.
		if (chargingBus.size() < numBusCharger) {
			int addNumber = Math.min(numBusCharger - chargingBus.size(), queueChargingBus.size());
			for (int i = 0; i < addNumber; i++) {
				ElectricBus evBus = queueChargingBus.poll();
				chargingBus.add(evBus); // The vehicle enters the charging areas.
				for (ElectricBus b : queueChargingBus) {
					b.chargingWaitingTime += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
				}
			}
		}
	}

	// Estimating time of using L2, L3 charger. Unite: hour
	public double chargingTimeL2(ElectricVehicle ev) {
		return (ev.getBatteryCapacity() - ev.getBatteryLevel()) / chargingRateL2;
	}
	
	public double waitingTimeL2() {
		int numChargingVehicle = chargingVehicleL2.size(); // Number of vehicles that is being charging.
		int numWaitingVehicle = queueChargingL2.size(); // Number of vehicles that in the queue.
		return (numChargingVehicle * 20.0 + numWaitingVehicle * 40.0) / (chargingRateL2 * numL2);
	}
	
    public double chargingTimeL3(ElectricVehicle ev) {
    	return (ev.getBatteryCapacity() - ev.getBatteryLevel()) / chargingRateL3;
	}
	
	public double waitingTimeL3() {
		int numChargingVehicle = chargingVehicleL3.size(); // number of vehicles that is being charging.
		int numWaitingVehicle = queueChargingL3.size(); // number of vehicles that in the queue.
		// assume each charging vehicle needs 20kWh more, each waiting vehicle needs 40kWh.
		return (numChargingVehicle * 20.0 + numWaitingVehicle * 40.0) / (chargingRateL3 * numL3); 
	}

	public int getID() {
		return this.ID;
	}

	public Coordinate getCoord() {
		return ContextCreator.getChargingStationGeography().getGeometry(this).getCentroid().getCoordinate();
	}
	
	 /**
     * Computes the final state of charge (SoC_f) based on an initial SoC (SOC_i),
     * battery capacity (C), charging power (P), and a charging time (t in hours),
     * using the corrected nonlinear charging model.
     *
     * @param SOC_i initial state of charge [0,1]
     * @param C     total battery capacity (kWh)
     * @param P     charging power (kW)
     * @param t     incremental charging time (hours)
     * @return SOC_f final state of charge [0,1]
     */
    public static double nonlinearCharging(double SOC_i, double C, double P, double t) {
        // Remaining capacity fraction to be charged.
        double y = 1.0 - Math.max(SOC_i, 0);
        double beta = P / C;
        
        // Compute A and B using y.
        double A = (64 * Math.pow(y, 3) / 27 - 5 * y / 2) / Math.pow(beta, 3);
        double B = Math.sqrt((320 * Math.pow(y, 4) / 9 - 1525 * Math.pow(y, 2) / 12 + 125) / Math.pow(beta, 6));
        
        // Compute t_star (baseline offset time based on current remaining capacity y)
        double t_star = Math.cbrt(A + B) + Math.cbrt(A - B) + 4 * y / (3 * beta);
        double t_total = t_star + t; // effective time after adding the incremental period
        
        // Define the cumulative charging function F(t)
        // F(t) = beta*t*(beta^2*t^2+15) / (2*beta^2*t^2+15)
        double F_t_total = beta * t_total * (beta * beta * Math.pow(t_total, 2) + 15)
                           / (2 * beta * beta * Math.pow(t_total, 2) + 15);
        double F_t_star  = beta * t_star * (beta * beta * Math.pow(t_star, 2) + 15)
                           / (2 * beta * beta * Math.pow(t_star, 2) + 15);
        
        // Compute the incremental charged fraction during time t.
        double minDeltaSOC = 0.001 * beta; // Set the min deltaSoC to force the simulation to reach 100% in a finite and realistic time.
        double incremental = F_t_total - F_t_star;
        
        // Update the final SoC ensuring it does not exceed 1.0.
        double SOC_f = Math.min(1.0, SOC_i + y * incremental + minDeltaSOC);
        return SOC_f;
    }
}
