package mets_r.data.output;

import java.io.IOException;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Vehicle;
import repast.simphony.context.DefaultContext;

/**
 * Inherent from A-RESCUE
 * 
 * This functions as the home for the core of the data collection system within
 * METS_R and the object through which the Repast framework will ensure it is
 * scheduled to receive the signals it needs to operate at key points in the
 * execution of the simulation.
 * 
 * @author Christopher Thompson
 **/

public class DataCollectionContext extends DefaultContext<Object> {

	/** A consumer of output data from the buffer which saves it to disk. */
	private JsonOutputWriter jsonOutputWriter;
	private BinaryTrajectoryOutputWriter binaryTrajectoryOutputWriter;

	/**
	 * Creates the data collection framework for the program and ensures it is ready
	 * to start receiving data when the simulation starts.
	 */
	public DataCollectionContext() {
		// Needed for the repast contexts framework to give it a name
		super("DataCollectionContext");

		// Create enabled trajectory output writers. Default paths create a unique
		// timestamped directory for each run.
		if (GlobalVariables.ENABLE_JSON_WRITE) {
			this.jsonOutputWriter = new JsonOutputWriter();
			ContextCreator.dataCollector.registerDataConsumer(this.jsonOutputWriter);
		}
		if (GlobalVariables.ENABLE_TRAJECTORY_BINARY_WRITE) {
			this.binaryTrajectoryOutputWriter = new BinaryTrajectoryOutputWriter();
			ContextCreator.dataCollector.registerDataConsumer(this.binaryTrajectoryOutputWriter);
		}
	}

	public void startCollecting() {
		ContextCreator.dataCollector.startDataCollection();
	}

	public void stopCollecting() {
		JsonOutputWriter jsonWriter = this.jsonOutputWriter;
		BinaryTrajectoryOutputWriter binaryWriter = this.binaryTrajectoryOutputWriter;
		if (this.jsonOutputWriter != null) {
			ContextCreator.dataCollector.deregisterDataConsumer(this.jsonOutputWriter);
			this.jsonOutputWriter = null;
		}
		if (this.binaryTrajectoryOutputWriter != null) {
			ContextCreator.dataCollector.deregisterDataConsumer(this.binaryTrajectoryOutputWriter);
			this.binaryTrajectoryOutputWriter = null;
		}
		ContextCreator.dataCollector.stopDataCollection();
		try {
			if (jsonWriter != null) {
				jsonWriter.awaitCompletion();
			}
			if (binaryWriter != null) {
				binaryWriter.awaitCompletion();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void startTick() {
		// Get the current tick number from the system
		int tickNumber = (int) ContextCreator.getCurrentTick();
		// Tell the data framework what tick is starting
		ContextCreator.dataCollector.startTickCollection(tickNumber);
	}

	public void stopTick() {
		ContextCreator.dataCollector.stopTickCollection();
	}

	public void displayMetrics() {
		int vehicleOnRoad = 0;
		int numGeneratedPrivateEVTrip = 0;
		int numArrivedPrivateEVTrip = 0;
		int numGeneratedPrivateGVTrip = 0;
		int numArrivedPrivateGVTrip = 0;
		int numGeneratedTaxiPass = 0;
		int numGeneratedBusPass = 0;
		int numWaitingTaxiPass = 0;
		int numWaitingBusPass = 0;
		int taxiPickupPass = 0;
		int busPickupPass = 0;
		int taxiServedPass = 0;
		int busServedPass = 0;
		int numLeavedTaxiPass = 0;
		int numLeavedBusPass = 0;
		int numRelocatedTaxi = 0;
		int numChargedVehicle = 0;
		double privateEVEnergy = 0;
		double eTaxiEnergy = 0;
		double eBusEnergy = 0;
		double battery_mean = 0;
		double battery_std = 0;

		int currentTick = (int) ContextCreator.getCurrentTick();

		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			numGeneratedTaxiPass += z.numberOfGeneratedTaxiRequest;
			numGeneratedBusPass += z.numberOfGeneratedBusRequest;
			taxiPickupPass += z.taxiPickupRequest;
			busPickupPass += z.busPickupRequest;
			taxiServedPass += z.taxiServedRequest;
			busServedPass += z.busServedRequest;
			numLeavedTaxiPass += z.numberOfLeavedTaxiRequest;
			numLeavedBusPass += z.numberOfLeavedBusRequest;
			numRelocatedTaxi += z.numberOfRelocatedVehicles;
			numWaitingTaxiPass += z.getTaxiRequestNum();
			numWaitingBusPass += z.getBusRequestNum();
			numGeneratedPrivateEVTrip += z.numberOfGeneratedPrivateEVTrip;
			numGeneratedPrivateGVTrip += z.numberOfGeneratedPrivateGVTrip;
			numArrivedPrivateEVTrip += z.arrivedPrivateEVTrip;
			numArrivedPrivateGVTrip += z.arrivedPrivateGVTrip;

			String formatted_msg2 = currentTick + "," + z.getID() + "," + z.getTaxiRequestNum() + ","
					+ z.getBusRequestNum() + "," + z.getVehicleStock() + "," + z.numberOfGeneratedTaxiRequest + ","
					+ z.numberOfGeneratedBusRequest + ","
					+ z.taxiPickupRequest + "," + z.busPickupRequest + "," + 
					+ z.taxiServedRequest + "," + z.busServedRequest + ","
					+ z.taxiServedPassWaitingTime + "," + z.busServedPassWaitingTime + "," + z.numberOfLeavedTaxiRequest + ","
					+ z.numberOfLeavedBusRequest + "," + z.taxiLeavedPassWaitingTime + "," + z.busLeavedPassWaitingTime + "," + z.taxiParkingTime + ","
					+ z.taxiCruisingTime + "," + z.getFutureDemand() + ","
					+ z.getFutureSupply() + "," + z.numberOfGeneratedPrivateEVTrip + ","
                    + z.numberOfGeneratedPrivateGVTrip + "," +
                    + z.arrivedPrivateEVTrip + "," +
                    + z.arrivedPrivateGVTrip;
			try {
				ContextCreator.agg_logger.zone_logger.write(formatted_msg2);
				ContextCreator.agg_logger.zone_logger.newLine();
				ContextCreator.agg_logger.zone_logger.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		// Diagnostic: detect a mismatch between the atomic vehicle counter (nVehicles_)
		// and the actual macro-list size on each road. A non-zero discrepancy indicates
		// that vehicles are leaking the counter (incremented without a matching decrement,
		// or vice versa) or that the macro linked list has been corrupted.
		int macroListVehicles = 0;
		int roadsWithMismatch = 0;
		int firstMismatchRoad = -1;
		int firstMismatchCounter = 0;
		int firstMismatchActual = 0;
		for (Road r : ContextCreator.getRoadContext().getAll()) {
			int counter = r.getVehicleNum();
			vehicleOnRoad += counter;
			int currentFlow =  r.getAndResetCurrentFlow();
			if (currentFlow > 0) {
				String formated_msg = currentTick + "," + r.getID() + "," + currentFlow + ","
					   + r.calcSpeed() + "," + r.getAndResetCurrentEnergy();
				try {
					ContextCreator.agg_logger.link_logger.write(formated_msg);
					ContextCreator.agg_logger.link_logger.newLine();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			// Walk the macro linked list and count vehicles.
			int actual = 0;
			Vehicle iv = r.firstVehicle();
			int safety = 0;
			while (iv != null && safety < 100000) {
				actual++;
				safety++;
				Vehicle next = iv.macroTrailing();
				if (next == iv) { break; } // self-loop guard
				iv = next;
			}
			macroListVehicles += actual;
			if (actual != counter) {
				roadsWithMismatch++;
				if (firstMismatchRoad < 0) {
					firstMismatchRoad = r.getID();
					firstMismatchCounter = counter;
					firstMismatchActual = actual;
				}
			}
		}
		if (roadsWithMismatch > 0) {
			ContextCreator.logger.warn("tick=" + currentTick + " nVehicles_/macro-list mismatch on "
					+ roadsWithMismatch + " road(s); first road=" + firstMismatchRoad
					+ " counter=" + firstMismatchCounter + " actual=" + firstMismatchActual
					+ " (totals: counter=" + vehicleOnRoad + " macro=" + macroListVehicles + ")");
		}

		// Breakdown of vehicleOnRoad by vehicle category so the user can see whether
		// the rising count comes from manually generated trips, autoloaded private
		// trips, taxis, or buses. Helps separate "auto-generated traffic" from a leak.
		int taxisOnRoad = 0;
		for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
			if (t.getRoad() != null) taxisOnRoad++;
		}
		int busesOnRoad = 0;
		for (ElectricBus b : ContextCreator.getVehicleContext().getBuses()) {
			if (b.getRoad() != null) busesOnRoad++;
			eBusEnergy += b.getTotalConsume();
		}
		int privateEVOnRoad = 0;
		int privateEVTotal = 0;
		int privateEVStateNone = 0;
		int privateEVStatePrivate = 0;
		int privateEVStateCharging = 0;
		int privateEVStateOther = 0;
		for (ElectricVehicle ev : ContextCreator.getVehicleContext().getPrivateEVs()) {
			privateEVTotal++;
			if (ev.getRoad() != null) privateEVOnRoad++;
			privateEVEnergy += ev.getTotalConsume();
			int st = ev.getState();
			if (st == Vehicle.NONE_OF_THE_ABOVE) privateEVStateNone++;
			else if (st == Vehicle.PRIVATE_TRIP) privateEVStatePrivate++;
			else if (st == Vehicle.CHARGING_TRIP) privateEVStateCharging++;
			else privateEVStateOther++;
		}
		int privateGVOnRoad = 0;
		int privateGVTotal = 0;
		for (Vehicle gv : ContextCreator.getVehicleContext().getPrivateGVs()) {
			privateGVTotal++;
			if (gv.getRoad() != null) privateGVOnRoad++;
		}
		int byCategorySum = taxisOnRoad + busesOnRoad + privateEVOnRoad + privateGVOnRoad;
		if (byCategorySum != vehicleOnRoad) {
			ContextCreator.logger.warn("tick=" + currentTick + " vehicleOnRoad breakdown disagreement: "
					+ "taxis=" + taxisOnRoad + " buses=" + busesOnRoad
					+ " privateEV=" + privateEVOnRoad + "/" + privateEVTotal
					+ " (none=" + privateEVStateNone + " priv=" + privateEVStatePrivate
					+ " chg=" + privateEVStateCharging + " other=" + privateEVStateOther + ")"
					+ " privateGV=" + privateGVOnRoad + "/" + privateGVTotal
					+ " sum=" + byCategorySum + " vs vehicleOnRoad=" + vehicleOnRoad);
		}

		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			numChargedVehicle += cs.numChargedCar.get();
		}

		for (ElectricTaxi v : ContextCreator.getVehicleContext().getTaxis()) {
			battery_mean += v.getBatteryLevel();
			battery_std += v.getBatteryLevel() * v.getBatteryLevel();
			eTaxiEnergy += v.getTotalConsume();
		}

		battery_mean /= GlobalVariables.NUM_OF_EV;
		battery_std = Math.sqrt(battery_std / GlobalVariables.NUM_OF_EV - battery_mean * battery_mean);

		String formated_msg = currentTick + "," + vehicleOnRoad + "," + numRelocatedTaxi + "," + numChargedVehicle + ","
				+ numGeneratedTaxiPass + "," + numGeneratedBusPass + ","
				+ taxiPickupPass + "," + busPickupPass + "," 
				+ taxiServedPass + "," + busServedPass + "," + numLeavedTaxiPass + "," + numLeavedBusPass + ","
				+ numWaitingTaxiPass + "," + numWaitingBusPass + "," + battery_mean + "," + battery_std + ","
				+ numGeneratedPrivateEVTrip + "," + numGeneratedPrivateGVTrip + "," + numArrivedPrivateEVTrip + "," + numArrivedPrivateGVTrip + ","
				+ privateEVEnergy + "," + eTaxiEnergy + "," + eBusEnergy + "," + (privateEVEnergy + eTaxiEnergy + eBusEnergy) + ","
				+ System.currentTimeMillis();
		try {
			ContextCreator.agg_logger.network_logger.write(formated_msg);
			ContextCreator.agg_logger.network_logger.newLine();
			ContextCreator.agg_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (GlobalVariables.ENABLE_METRICS_DISPLAY) {
			ContextCreator.logger.info("tick=" + currentTick 
					+ ", nGeneratedPrivateTrip=" + (numGeneratedPrivateEVTrip + numGeneratedPrivateGVTrip)
					+ ", nArrivedPrivateTrip=" + (numArrivedPrivateEVTrip + numArrivedPrivateGVTrip)
					+ ", nGeneratedPass=" + (numGeneratedTaxiPass + numGeneratedBusPass) 
					+ ", taxiPickupPass=" + taxiPickupPass + ", busPickupPass=" + busPickupPass
					+ ", nLeavedPass=" + (numLeavedTaxiPass + numLeavedBusPass) + ", nRelocatedVeh=" + numRelocatedTaxi
					+ ", nChargedVeh=" + numChargedVehicle);
		}
	}
}
