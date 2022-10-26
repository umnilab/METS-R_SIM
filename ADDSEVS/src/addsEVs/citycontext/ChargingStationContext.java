package addsEVs.citycontext;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import repast.simphony.context.DefaultContext;
import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.ShapefileLoader;

public class ChargingStationContext extends DefaultContext<ChargingStation>{
	public ChargingStationContext() {
		
		super("ChargingStationContext");
		
		ContextCreator.logger.info("ChargingStationContext creation");
		/*
		 * GIS projection for spatial information about Roads. This is used to
		 * then create junctions and finally the road network.
		 */
		GeographyParameters<ChargingStation> geoParams = new GeographyParameters<ChargingStation>();
		Geography<ChargingStation> chargingStationGeography = GeographyFactoryFinder
				.createGeographyFactory(null).createGeography("ChargingStationGeography",
						this, geoParams);
		
		/* Read in the data and add to the context and geography */
		File chargingStationFile = null;
		String chargerCsvName = null;
		ShapefileLoader<ChargingStationWithAbandon> chargingStationLoader = null;
		try {
			chargingStationFile = new File(GlobalVariables.CHARGER_SHAPEFILE);
			URI uri=chargingStationFile.toURI();
			chargingStationLoader = new ShapefileLoader<ChargingStationWithAbandon>(ChargingStationWithAbandon.class,
					uri.toURL(), chargingStationGeography, this);
			//  Read the charging station's attributes CSV file
			chargerCsvName = GlobalVariables.CHARGER_CSV;
			BufferedReader br = new BufferedReader(new FileReader(chargerCsvName));
			br.readLine(); // Skip the head line
			int int_id =  -1; // Use negative integers as charging station IDs
			while (chargingStationLoader.hasNext()) {
				String line = br.readLine();
				String[] result = line.split(",");
				// To support two formats, one with detailed charging station specifications, one just has ID and num of chargers
				if(result.length == 13){
					chargingStationLoader.nextWithArgs(int_id, (int) Math.round(Double.parseDouble(result[11])), (int) Math.round(Double.parseDouble(result[12]))); //Using customize parameters
				}
				else if(result.length == 2){
					chargingStationLoader.nextWithArgs(int_id, (int) Math.round(Double.parseDouble(result[1])), 0);
				}
				else{
					System.err.println("Incorrect format for charging station plan. Is there anything wrong in data/NYC/charger_jiawei?");
				}
				int_id -=1;
			}
			br.close();
			ContextCreator.logger.info("Charging Station generated, total number: " + (-int_id));
		} catch (Exception e) {
			ContextCreator.logger.error("Malformed URL exception when reading housesshapefile.");
			e.printStackTrace();
		}
	}
}
