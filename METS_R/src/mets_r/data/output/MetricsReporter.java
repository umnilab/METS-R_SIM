package mets_r.data.output;

import java.io.IOException;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.ThreadedScheduler;
import mets_r.ThreadedScheduler.RoadMetricRecord;
import mets_r.ThreadedScheduler.RoadMetricsSnapshot;
import mets_r.facility.ChargingStation;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Vehicle;

/**
 * Collects the periodic aggregate snapshot used by CSV output and console
 * metrics. It is deliberately independent from trajectory collection so a
 * headless run can omit DataCollector and DataCollectionContext entirely.
 */
public class MetricsReporter {
	public void report() {
		if (!GlobalVariables.ENABLE_AGGREGATE_WRITE && !GlobalVariables.ENABLE_METRICS_DISPLAY) {
			return;
		}

		int vehicleOnRoad = 0;
		int numGeneratedPrivateEVTrip = 0;
		int numArrivedPrivateEVTrip = 0;
		int numGeneratedPrivateGVTrip = 0;
		int numArrivedPrivateGVTrip = 0;
		int numGeneratedTaxiRequests = 0;
		int numGeneratedBusRequests = 0;
		int numWaitingTaxiRequests = 0;
		int numWaitingBusRequests = 0;
		int taxiMatchedRequests = 0;
		int busMatchedRequests = 0;
		int taxiDropoffRequests = 0;
		int busDropoffRequests = 0;
		int numLeftTaxiRequests = 0;
		int numLeftBusRequests = 0;
		int numRelocatedTaxi = 0;
		int numChargedVehicle = 0;
		double privateEVEnergy = 0;
		double eTaxiEnergy = 0;
		double eBusEnergy = 0;
		double batteryMean = 0;
		double batteryStd = 0;

		int currentTick = ContextCreator.getCurrentTick();
		AggregatedLogger aggregate = ContextCreator.agg_logger;

		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			numGeneratedTaxiRequests += z.numberOfGeneratedTaxiRequest;
			numGeneratedBusRequests += z.numberOfGeneratedBusRequest;
			taxiMatchedRequests += z.taxiPickupRequest;
			busMatchedRequests += z.busPickupRequest;
			taxiDropoffRequests += z.taxiServedRequest;
			busDropoffRequests += z.busServedRequest;
			numLeftTaxiRequests += z.numberOfLeavedTaxiRequest;
			numLeftBusRequests += z.numberOfLeavedBusRequest;
			numRelocatedTaxi += z.numberOfRelocatedVehicles;
			numWaitingTaxiRequests += z.getTaxiRequestNum();
			numWaitingBusRequests += z.getBusRequestNum();
			numGeneratedPrivateEVTrip += z.numberOfGeneratedPrivateEVTrip;
			numGeneratedPrivateGVTrip += z.numberOfGeneratedPrivateGVTrip;
			numArrivedPrivateEVTrip += z.arrivedPrivateEVTrip;
			numArrivedPrivateGVTrip += z.arrivedPrivateGVTrip;

			if (aggregate != null) {
				String zoneRecord = currentTick + "," + z.getID() + "," + z.getTaxiRequestNum() + ","
						+ z.getBusRequestNum() + "," + z.getVehicleStock() + "," + z.numberOfGeneratedTaxiRequest + ","
						+ z.numberOfGeneratedBusRequest + "," + z.taxiPickupRequest + "," + z.busPickupRequest + ","
						+ z.taxiServedRequest + "," + z.busServedRequest + "," + z.taxiServedPassWaitingTime + ","
						+ z.busServedPassWaitingTime + "," + z.numberOfLeavedTaxiRequest + ","
						+ z.numberOfLeavedBusRequest + "," + z.taxiLeavedPassWaitingTime + ","
						+ z.busLeavedPassWaitingTime + "," + z.taxiParkingTime + "," + z.taxiCruisingTime + ","
						+ z.getFutureDemand() + "," + z.getFutureSupply() + "," + z.numberOfGeneratedPrivateEVTrip + ","
						+ z.numberOfGeneratedPrivateGVTrip + "," + z.arrivedPrivateEVTrip + "," + z.arrivedPrivateGVTrip;
				try {
					aggregate.zone_logger.write(zoneRecord);
					aggregate.zone_logger.newLine();
				} catch (IOException e) {
					ContextCreator.logger.error("Failed to write aggregate zone record", e);
				}
			}
		}

		RoadMetricsSnapshot roadMetrics = ContextCreator.tscheduler == null
				? ThreadedScheduler.collectActiveRoadMetricsSequential(currentTick)
				: ContextCreator.tscheduler.getRoadMetricsSnapshot(currentTick);
		vehicleOnRoad = roadMetrics.vehicleOnRoad;
		if (aggregate != null) {
			for (RoadMetricRecord link : roadMetrics.roadRecords) {
				String roadRecord = currentTick + "," + link.roadID + "," + link.currentFlow
						+ "," + link.speed + "," + link.currentEnergy;
				try {
					aggregate.link_logger.write(roadRecord);
					aggregate.link_logger.newLine();
				} catch (IOException e) {
					ContextCreator.logger.error("Failed to write aggregate road record", e);
				}
			}
		}
		int taxisOnRoad = 0;
		for (ElectricTaxi taxi : ContextCreator.getVehicleContext().getTaxis()) {
			if (taxi.getRoad() != null) taxisOnRoad++;
			batteryMean += taxi.getBatteryLevel();
			batteryStd += taxi.getBatteryLevel() * taxi.getBatteryLevel();
			eTaxiEnergy += taxi.getTotalConsume();
		}
		int busesOnRoad = 0;
		for (ElectricBus bus : ContextCreator.getVehicleContext().getBuses()) {
			if (bus.getRoad() != null) busesOnRoad++;
			eBusEnergy += bus.getTotalConsume();
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
			int state = ev.getState();
			if (state == Vehicle.NONE_OF_THE_ABOVE) privateEVStateNone++;
			else if (state == Vehicle.PRIVATE_TRIP) privateEVStatePrivate++;
			else if (state == Vehicle.CHARGING_TRIP) privateEVStateCharging++;
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
			ContextCreator.logger.warn("tick=" + currentTick + " vehicleOnRoad breakdown disagreement: taxis="
					+ taxisOnRoad + " buses=" + busesOnRoad + " privateEV=" + privateEVOnRoad + "/"
					+ privateEVTotal + " (none=" + privateEVStateNone + " priv=" + privateEVStatePrivate
					+ " chg=" + privateEVStateCharging + " other=" + privateEVStateOther + ") privateGV="
					+ privateGVOnRoad + "/" + privateGVTotal + " sum=" + byCategorySum
					+ " vs vehicleOnRoad=" + vehicleOnRoad);
		}

		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			numChargedVehicle += cs.numChargedCar.get();
		}

		batteryMean /= GlobalVariables.NUM_OF_EV;
		batteryStd = Math.sqrt(batteryStd / GlobalVariables.NUM_OF_EV - batteryMean * batteryMean);

		if (aggregate != null) {
			String networkRecord = currentTick + "," + vehicleOnRoad + "," + numRelocatedTaxi + ","
					+ numChargedVehicle + "," + numGeneratedTaxiRequests + "," + numGeneratedBusRequests + ","
					+ taxiMatchedRequests + "," + busMatchedRequests + "," + taxiDropoffRequests + ","
					+ busDropoffRequests + "," + numLeftTaxiRequests + "," + numLeftBusRequests + ","
					+ numWaitingTaxiRequests + "," + numWaitingBusRequests + "," + batteryMean + "," + batteryStd
					+ "," + numGeneratedPrivateEVTrip + "," + numGeneratedPrivateGVTrip + ","
					+ numArrivedPrivateEVTrip + "," + numArrivedPrivateGVTrip + "," + privateEVEnergy + ","
					+ eTaxiEnergy + "," + eBusEnergy + "," + (privateEVEnergy + eTaxiEnergy + eBusEnergy) + ","
					+ System.currentTimeMillis();
			try {
				aggregate.network_logger.write(networkRecord);
				aggregate.network_logger.newLine();
				aggregate.flush();
			} catch (IOException e) {
				ContextCreator.logger.error("Failed to write aggregate network record", e);
			}
		}

		if (GlobalVariables.ENABLE_METRICS_DISPLAY) {
			ContextCreator.logger.info("tick=" + currentTick + ", nGeneratedPrivateTrip="
					+ (numGeneratedPrivateEVTrip + numGeneratedPrivateGVTrip) + ", nArrivedPrivateTrip="
					+ (numArrivedPrivateEVTrip + numArrivedPrivateGVTrip) + ", nGeneratedRequests="
					+ (numGeneratedTaxiRequests + numGeneratedBusRequests) + ", taxiMatchedRequests="
					+ taxiMatchedRequests + ", busMatchedRequests=" + busMatchedRequests + ", nLeftRequests="
					+ (numLeftTaxiRequests + numLeftBusRequests) + ", nRelocatedVeh=" + numRelocatedTaxi
					+ ", nChargedVeh=" + numChargedVehicle);
		}
	}
}
