package mets_r.data.output;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

import mets_r.GlobalVariables;

public class AggregatedLogger {
	public BufferedWriter ev_logger; // EV Trip logger
	public BufferedWriter bus_logger; // EV Bus Trip logger
	public BufferedWriter link_logger; // Link energy logger
	public BufferedWriter network_logger; // Road network vehicle logger
	public BufferedWriter zone_logger; // Zone logger
	public BufferedWriter charger_logger; // Charger logger
	public BufferedWriter request_logger; // Request logger
	
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
			ev_logger.write("tick,vehicleID,tripType,originID,destID,distance,departureTime,cost,choice,passNum");
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
		}
		catch (IOException e) {
			e.printStackTrace();
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
			ev_logger.close();
			bus_logger.close();
			link_logger.close();
			network_logger.close();
			zone_logger.close();
			charger_logger.close();
			request_logger.close();
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		
	}
}
