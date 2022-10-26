package addsEVs.citycontext;

import java.io.File;
import java.net.URI;
import java.util.HashMap;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
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
		Geography<Zone> zoneGeography = GeographyFactoryFinder
				.createGeographyFactory(null).createGeography("ZoneGeography",
						this, geoParams);

		/* Read in the data and add to the context and geography */
		File zoneFile = null;
		ShapefileLoader<ZoneWithAbandon> zoneLoader = null;
		try {
			zoneFile = new File(GlobalVariables.ZONES_SHAPEFILE);
			URI uri=zoneFile.toURI();
			zoneLoader = new ShapefileLoader<ZoneWithAbandon>(ZoneWithAbandon.class,
					uri.toURL(), zoneGeography, this);
			int int_id =  0; 
			while (zoneLoader.hasNext()) {
				ZoneWithAbandon zone = zoneLoader.nextWithArgs(int_id); //Using customize parameters
				this.zoneDictionary.put(int_id, zone);
				int_id +=1;
				ContextCreator.logger.debug("int_ID" + zone.getIntegerID()+","+zoneGeography.getGeometry(zone).getCentroid().getCoordinate());
			}
			ContextCreator.logger.info("Zone generated, total number: " + int_id);

		} catch (java.net.MalformedURLException e) {
			ContextCreator.logger.error("Malformed URL exception when reading housesshapefile.");
			e.printStackTrace();
		}

	}
	
	public Zone findZoneWithIntegerID(int integerID) {
		if(this.zoneDictionary.containsKey(integerID)) {
			return this.zoneDictionary.get(integerID);
		}
		else {
			return null;
		}
	}
}
