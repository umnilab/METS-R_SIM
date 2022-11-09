package mets_r.data;

import java.io.IOException;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricTaxi;
import repast.simphony.context.DefaultContext;
import repast.simphony.engine.environment.RunEnvironment;

/**
 * DataCollectionContext
 * 
 * This functions as the home for the core of the data collection system within
 * EvacSim and the object through which the Repast framework will ensure it is
 * scheduled to receive the signals it needs to operate at key points in the
 * execution of the simulation.
 * 
 * @author Christopher Thompson (thompscs@purdue.edu)
 * @date 20 sept 2017
 */
public class DataCollectionContext extends DefaultContext<Object> {

	/** A convenience reference to to the system-wide data collector. */
	private DataCollector collector;

	/** A consumer of output data from the buffer which saves it to disk. */
	private CsvOutputWriter outputWriter;

	/** A consumer of output data from the buffer which saves it to disk. */
	private JsonOutputWriter jsonOutputWriter;

	/**
	 * Creates the data collection framework for the program and ensures it is ready
	 * to start receiving data when the simulation starts.
	 */
	public DataCollectionContext() {
		// Needed for the repast contexts framework to give it a name
		super("DataCollectionContext");

		// There is no real need to do this, but this gives us a location
		// where we know during startup this was guaranteed to be called
		// at least once to ensure that it created an instance of itself
		this.collector = DataCollector.getInstance();

		// Create the output file writer. without specifying a filename,
		// this will generate a unique value including a current timestamp
		// and placing it in the current jre working directory.
		if (GlobalVariables.ENABLE_CSV_WRITE) {
			this.outputWriter = new CsvOutputWriter();
			this.collector.registerDataConsumer(this.outputWriter);
		}

		// Create the JSON output file writer. without specifying a filename,
		// this will generate a unique value including a current timestamp
		// and placing it in the current jre working directory.
		if (GlobalVariables.ENABLE_JSON_WRITE) {
			this.jsonOutputWriter = new JsonOutputWriter();
			this.collector.registerDataConsumer(this.jsonOutputWriter);
		}
	}

	public void startCollecting() {
		this.collector.startDataCollection();
	}

	public void stopCollecting() {
		this.collector.stopDataCollection();
	}

	public void startTick() {
		// Get the current tick number from the system
		double tickNumber = RunEnvironment.getInstance().getCurrentSchedule().getTickCount();
		// Tell the data framework what tick is starting
		this.collector.startTickCollection(tickNumber);
	}

	public void stopTick() {
		this.collector.stopTickCollection();
	}

	public void displayMetrics() {
		int vehicleOnRoad = 0;
		int numGeneratedTaxiPass = 0;
		int numGeneratedBusPass = 0;
		int numGeneratedCombinedPass = 0;
		int numWaitingTaxiPass = 0;
		int numWaitingBusPass = 0;
		int taxiPickupPass = 0;
		int busPickupPass = 0;
		int combinePickupPart1 = 0;
		int combinePickupPart2 = 0;
		int taxiServedPass = 0;
		int busServedPass = 0;
		int numLeavedTaxiPass = 0;
		int numLeavedBusPass = 0;
		int numRelocatedTaxi = 0;
		int numChargedVehicle = 0;
		double battery_mean = 0;
		double battery_std = 0;

		int currentTick = (int) RunEnvironment.getInstance().getCurrentSchedule().getTickCount();

		for (Zone z : ContextCreator.getZoneContext().getAllObjects()) {
			numGeneratedTaxiPass += z.numberOfGeneratedTaxiRequest;
			numGeneratedBusPass += z.numberOfGeneratedBusRequest;
			numGeneratedCombinedPass += z.numberOfGeneratedCombinedRequest;
			taxiPickupPass += z.taxiPickupRequest;
			busPickupPass += z.busPickupRequest;
			combinePickupPart1 += z.combinePickupPart1;
			combinePickupPart2 += z.combinePickupPart2;
			taxiServedPass += z.taxiServedRequest;
			busServedPass += z.busServedRequest;
			numLeavedTaxiPass += z.numberOfLeavedTaxiRequest;
			numLeavedBusPass += z.numberOfLeavedBusRequest;
			numRelocatedTaxi += z.numberOfRelocatedVehicles;
			numWaitingTaxiPass += z.getTaxiPassengerNum();
			numWaitingBusPass += z.getBusPassengerNum();

			String formatted_msg2 = currentTick + "," + z.getIntegerID() + "," + z.getTaxiPassengerNum() + ","
					+ z.getBusPassengerNum() + "," + z.getVehicleStock() + "," + z.numberOfGeneratedTaxiRequest + ","
					+ z.numberOfGeneratedBusRequest + "," + z.numberOfGeneratedCombinedRequest + ","
					+ z.taxiPickupRequest + "," + z.busPickupRequest + "," + z.combinePickupPart1 + ","
					+ z.combinePickupPart2 + "," + z.taxiServedRequest + "," + z.busServedRequest + ","
					+ z.taxiPassWaitingTime + "," + z.busPassWaitingTime + "," + z.numberOfLeavedTaxiRequest + ","
					+ z.numberOfLeavedBusRequest + "," + z.taxiParkingTime + "," + z.taxiCruisingTime + "," + z.getFutureDemand() + ","
					+ z.getFutureSupply();
			try {
				ContextCreator.zone_logger.write(formatted_msg2);
				ContextCreator.zone_logger.newLine();
				ContextCreator.zone_logger.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		for (Road r : ContextCreator.getRoadContext().getAllObjects()) {
			vehicleOnRoad += r.getVehicleNum();
			if (r.getTotalEnergy() > 0) {
				String formated_msg = currentTick + "," + r.getLinkid() + "," + r.getTotalFlow() + ","
						+ r.getTotalEnergy();
				try {
					ContextCreator.link_logger.write(formated_msg);
					ContextCreator.link_logger.newLine();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAllObjects()) {
			numChargedVehicle += cs.numChargedVehicle;
		}

		for (ElectricTaxi v : ContextCreator.getVehicleContext().getVehicles()) {
			battery_mean += v.getBatteryLevel();
			battery_std += v.getBatteryLevel() * v.getBatteryLevel();
		}

		battery_mean /= GlobalVariables.NUM_OF_EV;
		battery_std = Math.sqrt(battery_std / GlobalVariables.NUM_OF_EV - battery_mean * battery_mean);

		String formated_msg = currentTick + "," + vehicleOnRoad + "," + numRelocatedTaxi + "," + numChargedVehicle + ","
				+ numGeneratedTaxiPass + "," + numGeneratedBusPass + "," + numGeneratedCombinedPass + ","
				+ taxiPickupPass + "," + busPickupPass + "," + combinePickupPart1 + "," + combinePickupPart2 + ","
				+ taxiServedPass + "," + busServedPass + "," + numLeavedTaxiPass + "," + numLeavedBusPass + ","
				+ numWaitingTaxiPass + "," + numWaitingBusPass + "," + battery_mean + "," + battery_std + ","
				+ System.currentTimeMillis();
		try {
			ContextCreator.network_logger.write(formated_msg);
			ContextCreator.network_logger.newLine();
			ContextCreator.network_logger.flush();
			ContextCreator.link_logger.flush();
			ContextCreator.charger_logger.flush();
			ContextCreator.bus_logger.flush();
			ContextCreator.ev_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (GlobalVariables.ENABLE_METRICS_DISPLAY) {
			System.out.println("tick=" + currentTick + ", nGeneratedPass="
					+ (numGeneratedTaxiPass + numGeneratedBusPass + numGeneratedCombinedPass) + ", taxiPickupPass="
					+ taxiPickupPass + ", busPickupPass=" + busPickupPass + ", combinePickupPass=" + combinePickupPart1
					+ ", nLeavedPass=" + (numLeavedTaxiPass + numLeavedBusPass) + ", nRelocatedVeh=" + numRelocatedTaxi
					+ ", nChargedVeh=" + numChargedVehicle);
		}
	}
}
