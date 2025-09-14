package mets_r.facility;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.ShapefileLoader;

/**
 * Initializing charging facilities 
 * @author: Zengxiang Lei 
 **/

public class ChargingStationContext extends FacilityContext<ChargingStation> {
	
	private int numL2 = 0;
	private int numL3 = 0;
	private int numBusCharger = 0;
	
	public ChargingStationContext() {

		super("ChargingStationContext");

		ContextCreator.logger.info("ChargingStationContext creation");
		GeographyParameters<ChargingStation> geoParams = new GeographyParameters<ChargingStation>();
		Geography<ChargingStation> chargingStationGeography = GeographyFactoryFinder.createGeographyFactory(null)
				.createGeography("ChargingStationGeography", this, geoParams);

		File chargingStationFile = null;
		ShapefileLoader<ChargingStation> chargingStationLoader = null;
		try {
			chargingStationFile = new File(GlobalVariables.CHARGER_SHAPEFILE);
			URI uri = chargingStationFile.toURI();
			chargingStationLoader = new ShapefileLoader<ChargingStation>(ChargingStation.class, uri.toURL(),
					chargingStationGeography, this);
			// Read the charging station's attributes CSV file
			BufferedReader br = new BufferedReader(new FileReader(GlobalVariables.CHARGER_CSV));
			br.readLine(); // Skip the head line
			int int_id = -1; // Use negative integers as charging station IDs, start with ID = -1
			while (chargingStationLoader.hasNext()) {
				String line = br.readLine();
				String[] result = line.split(",");
				// To support two formats, one with detailed charging station specifications,
				// one just has ID and num of chargers
				ChargingStation cs = null;
				if (result.length >= 6) {
					if (result.length >= 8) {
						cs = chargingStationLoader.nextWithArgs(int_id, (int) Math.round(Double.parseDouble(result[3])),
								(int) Math.round(Double.parseDouble(result[4])), (int) Math.round(Double.parseDouble(result[5])),
								Double.parseDouble(result[6]), Double.parseDouble(result[7])); // Using customize parameters
					}
					else {
						cs = chargingStationLoader.nextWithArgs(int_id, (int) Math.round(Double.parseDouble(result[3])),
								(int) Math.round(Double.parseDouble(result[4])), (int) Math.round(Double.parseDouble(result[5])),
								GlobalVariables.CHARGING_FEE_L2, GlobalVariables.CHARGING_FEE_DCFC); // Using customize parameters
					}
					numL2 += cs.numCharger(ChargingStation.L2);
					numL3 += cs.numCharger(ChargingStation.L3);
					numBusCharger += cs.numCharger(ChargingStation.BUS);
				} 
				else {
					ContextCreator.logger.error(
							"Incorrect format for charging station. The incorrect element is"+ result[0]+","+ result[1]+","+ result[2]);
				}
				this.put(int_id, cs);
				int_id = int_id - 1;
			}
			br.close();
			ContextCreator.logger.info("Charging Station generated, total number: " + (-int_id));
		} catch (Exception e) {
			ContextCreator.logger.error("Exception when reading charging sation shape file/csv.");
			e.printStackTrace();
		}
	}
	
	public int numCharger(int chargerType) {
		switch(chargerType) {
		  case ChargingStation.L2:
			  return numL2;
		  case ChargingStation.L3:
			  return numL3;
		  case ChargingStation.BUS:
			  return numBusCharger;
		  default:
			  return 0;
		}
	}
}
