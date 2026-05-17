package mets_r.data.output;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Vehicle;

public class AggregatedLogger {
	public BufferedWriter ev_logger; // EV Trip logger
	public BufferedWriter bus_logger; // EV Bus Trip logger
	public BufferedWriter link_logger; // Link energy logger
	public BufferedWriter network_logger; // Road network vehicle logger
	public BufferedWriter zone_logger; // Zone logger
	public BufferedWriter charger_logger; // Charger logger
	public BufferedWriter request_logger; // Request logger
	public BufferedWriter unfinished_trip_logger; // Trips still active at simulation shutdown
	
	public AggregatedLogger() {
		String outDir = GlobalVariables.AGG_DEFAULT_PATH;
		String timestamp = new SimpleDateFormat("YYYY-MM-dd-hh-mm-ss").format(new Date()); 
		String outpath = outDir + File.separatorChar + GlobalVariables.NUM_OF_EV + "_" + GlobalVariables.NUM_OF_BUS; 
		try {
			Files.createDirectories(Paths.get(outpath));
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "EVLog-" + timestamp + ".csv", false);
			ev_logger = new BufferedWriter(fw);
			ev_logger.write("tick,vehicleID,tripType,originID,destID,originRoad,destRoad,distance,departureTime,cost,choice,passNum");
			ev_logger.newLine();
			ev_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "BusLog-" + timestamp + ".csv", false);
			bus_logger = new BufferedWriter(fw);
			bus_logger.write(
					"tick,vehicleID,routeID,tripType,originID,destID,distance,departureTime,cost,choice,passOnBoard");
			bus_logger.newLine();
			bus_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "LinkLog-" + timestamp + ".csv", false);
			link_logger = new BufferedWriter(fw);
			link_logger.write("tick,linkID,flow,speed,consumption");
			link_logger.newLine();
			link_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "NetworkLog-" + timestamp + ".csv", false);
			network_logger = new BufferedWriter(fw);
			network_logger.write(
					"tick,vehOnRoad,emptyTrip,chargingTrip,generatedTaxiPass,generatedBusPass,"
							+ "taxiPickupPass,busPickupPass,"
							+ "taxiServedPass,busServedPass," 
							+ "taxiLeavedPass,busLeavedPass,"
							+ "numWaitingTaxiPass,numWaitingBusPass," 
							+ "batteryMean,batteryStd,"
							+ "generatedPrivateEVTrip, generatedPrivateGVTrip,"
							+ "arrivedPrivateEVTrip, arrivedPrivateGVTrip,"
							+ "timeStamp");
			network_logger.newLine();
			network_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "ZoneLog-" + timestamp + ".csv", false);
			zone_logger = new BufferedWriter(fw);
			zone_logger.write(
					"tick,zoneID,numTaxiPass,numBusPass,vehStock,taxiGeneratedPass,busGeneratedPass,"
							+ "taxiPickupPass,busPickupPass,"
							+ "taxiServedPass,busServedPass,taxiServedPassWaitingTime,busServedPassWaitingTime,"
							+ "taxiLeavedPass,busLeavedPass,taxiLeavedPassWaitingTime,busLeavedPassWaitingTime,"
							+ "taxiParkingTime,taxiCruisingTime,futureDemand,futureSupply,"
							+ "generatedPrivateEVTrip,generatedPrivateGVTrip,arrivedPrivateEVTrip,arrivedPrivateGVTrip");
			zone_logger.newLine();
			zone_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "ChargerLog-" + timestamp + ".csv", false);
			charger_logger = new BufferedWriter(fw);
			charger_logger
					.write("tick,chargerID,vehID,vehType,chargerType,waitingTime,chargingTime,initialBatteryLevel");
			charger_logger.newLine();
			charger_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "RequestLog-" + timestamp + ".csv", false);
			request_logger = new BufferedWriter(fw);
			request_logger
					.write("tick,requestID,originZone,destZone,numPeople,generationTime,matchedTime,pickupTime,arriveTime,vehID,vehType"); 
			request_logger.newLine();
			request_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "UnfinishedTripLog-" + timestamp + ".csv", false);
			unfinished_trip_logger = new BufferedWriter(fw);
			unfinished_trip_logger.write("tick,vehicleID,vehicleClass,privateVID,routeID,tripType,status,"
					+ "originID,destID,originRoad,destRoad,currentRoad,currentLane,distanceToNextJunction,"
					+ "accumulatedDistance,distToTravel,departureTime,elapsedTime,currentSpeed,"
					+ "batteryLevel,tripEnergy,routeChoice,passNum");
			unfinished_trip_logger.newLine();
			unfinished_trip_logger.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void flush() {
		try {
			ev_logger.flush();
			bus_logger.flush();
			link_logger.flush();
			network_logger.flush();
			zone_logger.flush();
			charger_logger.flush();
			request_logger.flush();
			unfinished_trip_logger.flush();
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Record trips that are still in progress, queued, or otherwise active when the
	 * simulation terminates. Completed trip loggers are called from each vehicle's
	 * arrival logic, so shutdown needs this separate census to avoid silently losing
	 * censored trip observations.
	 */
	public void recordUnfinishedTrips() {
		if (unfinished_trip_logger == null || ContextCreator.getVehicleContext() == null) {
			return;
		}

		int tick = ContextCreator.getCurrentTick();
		List<Vehicle> vehicles = new ArrayList<Vehicle>();
		vehicles.addAll(ContextCreator.getVehicleContext().getTaxis());
		vehicles.addAll(ContextCreator.getVehicleContext().getBuses());
		vehicles.addAll(ContextCreator.getVehicleContext().getPrivateEVs());
		vehicles.addAll(ContextCreator.getVehicleContext().getPrivateGVs());
		vehicles.sort(Comparator.comparingInt(Vehicle::getVehicleClass).thenComparingInt(Vehicle::getID));

		int count = 0;
		for (Vehicle v : vehicles) {
			if (!hasUnfinishedTrip(v)) {
				continue;
			}
			try {
				unfinished_trip_logger.write(formatUnfinishedTrip(v, tick));
				unfinished_trip_logger.newLine();
				count++;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			unfinished_trip_logger.flush();
			ContextCreator.logger.info("Recorded " + count + " unfinished trip(s) at shutdown.");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private boolean hasUnfinishedTrip(Vehicle v) {
		if (v == null) {
			return false;
		}
		if (v.isOnRoad()) {
			return true;
		}
		if (v instanceof ElectricBus && ((ElectricBus) v).getRouteID() >= 0) {
			return true;
		}
		int state = v.getState();
		return state != Vehicle.NONE_OF_THE_ABOVE && state != Vehicle.PARKING;
	}

	private String formatUnfinishedTrip(Vehicle v, int tick) {
		int privateVID = ContextCreator.getVehicleContext().getPrivateVID(v.getID());
		int routeID = v instanceof ElectricBus ? ((ElectricBus) v).getRouteID() : -1;
		int routeChoice = v instanceof ElectricTaxi ? ((ElectricTaxi) v).routeChoice : -1;
		int passNum = passengerCount(v);
		double batteryLevel = v instanceof ElectricVehicle ? ((ElectricVehicle) v).getBatteryLevel() : -1.0;
		double tripEnergy = v instanceof ElectricVehicle ? ((ElectricVehicle) v).getTripConsume() : -1.0;
		int depTime = v.getDepTime();
		int elapsedTime = Math.max(0, tick - depTime);
		Road currentRoad = v.getRoad();
		Lane currentLane = v.getLane();

		return tick + "," + v.getID() + "," + v.getVehicleClass() + "," + privateVID + "," + routeID + ","
				+ v.getState() + "," + unfinishedStatus(v, tick) + "," + v.getOriginID() + "," + v.getDestID()
				+ "," + safeOriginRoad(v) + "," + safeDestRoad(v) + ","
				+ (currentRoad == null ? -1 : currentRoad.getID()) + ","
				+ (currentLane == null ? -1 : currentLane.getID()) + ","
				+ v.getDistanceToNextJunction() + "," + v.getAccummulatedDistance() + ","
				+ v.getDistToTravel() + "," + depTime + "," + elapsedTime + "," + v.currentSpeed() + ","
				+ batteryLevel + "," + tripEnergy + "," + routeChoice + "," + passNum;
	}

	private String unfinishedStatus(Vehicle v, int tick) {
		if (v.isOnRoad()) {
			return "ON_ROAD";
		}
		if (v.getState() == Vehicle.CHARGING_TRIP && v instanceof ElectricVehicle) {
			return "CHARGING_OR_WAITING";
		}
		if (v.getDepTime() > tick) {
			return "PENDING_DEPARTURE";
		}
		return "OFF_ROAD_ACTIVE";
	}

	private int passengerCount(Vehicle v) {
		if (v instanceof ElectricTaxi) {
			return ((ElectricTaxi) v).getPassNum();
		}
		if (v instanceof ElectricBus) {
			return ((ElectricBus) v).getPassNum();
		}
		return 0;
	}

	private int safeOriginRoad(Vehicle v) {
		try {
			return v.getOriginRoad();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private int safeDestRoad(Vehicle v) {
		try {
			return v.getDestRoad();
		} catch (RuntimeException e) {
			return -1;
		}
	}
	
	public void close() {
		try {
			ev_logger.flush();
			bus_logger.flush();
			link_logger.flush();
			network_logger.flush();
			zone_logger.flush();
			charger_logger.flush();
			request_logger.flush();
			unfinished_trip_logger.flush();
			ev_logger.close();
			bus_logger.close();
			link_logger.close();
			network_logger.close();
			zone_logger.close();
			charger_logger.close();
			request_logger.close();
			unfinished_trip_logger.close();
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		
	}
}
