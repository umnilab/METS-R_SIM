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
				if (result.length == 13) {
					cs = chargingStationLoader.nextWithArgs(int_id, (int) Math.round(Double.parseDouble(result[11])),
							(int) Math.round(Double.parseDouble(result[12]))); // Using customize parameters
				} else if (result.length == 2) {
					cs = chargingStationLoader.nextWithArgs(int_id, (int) Math.round(Double.parseDouble(result[1])), 0);
				} else {
					ContextCreator.logger.error(
							"Incorrect format for charging station plan. Is there anything wrong in data/NYC/charging_station?");
				}
				this.put(int_id, cs);
				int_id -= 1;
			}
			br.close();
			ContextCreator.logger.info("Charging Station generated, total number: " + (-int_id));
		} catch (Exception e) {
			ContextCreator.logger.error("Malformed URL exception when reading housesshapefile.");
			e.printStackTrace();
		}
	}
}
