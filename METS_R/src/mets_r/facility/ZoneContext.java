package mets_r.facility;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import repast.simphony.context.DefaultContext;
import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.ShapefileLoader;

public class ZoneContext extends DefaultContext<Zone> {

	private HashMap<Integer, Zone> zoneDictionary;

	public ZoneContext() {
		super("ZoneContext");
		ContextCreator.logger.info("ZoneContext creation");
		this.zoneDictionary = new HashMap<Integer, Zone>();
		GeographyParameters<Zone> geoParams = new GeographyParameters<Zone>();
		Geography<Zone> zoneGeography = GeographyFactoryFinder.createGeographyFactory(null)
				.createGeography("ZoneGeography", this, geoParams);
		/* Read in the data and add to the context and geography */
		File zoneFile = null;
		ShapefileLoader<Zone> zoneLoader = null;
		try {
			zoneFile = new File(GlobalVariables.ZONES_SHAPEFILE);
			URI uri = zoneFile.toURI();
			zoneLoader = new ShapefileLoader<Zone>(Zone.class, uri.toURL(), zoneGeography, this);
			BufferedReader br = new BufferedReader(new FileReader(GlobalVariables.ZONE_CSV));
			int int_id = 0;
			while (zoneLoader.hasNext()) {
				String line = br.readLine();
				String[] result = line.split(",");
				Zone zone = zoneLoader.nextWithArgs(int_id, (int) Math.round(Double.parseDouble(result[2]))); // Using customize parameters
				this.zoneDictionary.put(int_id, zone);
				int_id += 1;
				ContextCreator.logger.debug("int_ID" + zone.getIntegerID() + ","
						+ zoneGeography.getGeometry(zone).getCentroid().getCoordinate());
			}
			br.close();
			ContextCreator.logger.info("Zone generated, total number: " + int_id);
		} catch (Exception e) {
			ContextCreator.logger.error("Malformed URL exception or file not exists when reading housesshapefile.");
			e.printStackTrace();
		}

	}

	public Zone findZoneWithIntegerID(int integerID) {
		if (this.zoneDictionary.containsKey(integerID)) {
			return this.zoneDictionary.get(integerID);
		} else {
			return null;
		}
	}
	
	public Collection<Zone> getAllObjects() {
		return zoneDictionary.values();
	}
}
