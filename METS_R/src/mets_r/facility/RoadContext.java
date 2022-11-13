package mets_r.facility;

import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import repast.simphony.context.DefaultContext;
import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.ShapefileLoader;

/*
 * Inherit from ARESCUE simulation
 */

public class RoadContext extends DefaultContext<Road> {
	
	private HashMap<Integer, Road> roadDictionary;
	// Cache every coordinate which forms a road so that Route.onRoad() becomes
	// faster.
	private static Map<Coordinate, ?> coordCache;

	public RoadContext() {
		super("RoadContext");
		ContextCreator.logger.info("RoadContext creation");
		roadDictionary = new HashMap<Integer, Road>();
		// Create the cache:
		coordCache = new HashMap<Coordinate, Object>();

		/*
		 * GIS projection for spatial information about Roads. This is used to then
		 * create junctions and finally the road network.
		 */
		GeographyParameters<Road> geoParams = new GeographyParameters<Road>();
		Geography<Road> roadGeography = GeographyFactoryFinder.createGeographyFactory(null)
				.createGeography("RoadGeography", this, geoParams);

		/* Read in the data and add to the context and geography */
		File roadFile = null;
		ShapefileLoader<Road> roadLoader = null;

		/* CSV file for data attribute */
		String fileName = GlobalVariables.ROADS_CSV;
		// File class needed to turn stringName to actual file
		try {
			roadFile = new File(GlobalVariables.ROADS_SHAPEFILE);
			URI uri = roadFile.toURI();
			roadLoader = new ShapefileLoader<Road>(Road.class, uri.toURL(), roadGeography, this);
			BufferedReader br = new BufferedReader(new FileReader(fileName));
			String line = br.readLine();
			String[] result = line.split(",");
			if(result.length < 18) {
				ContextCreator.logger.error("Missing fields in Road configuration, a proper one should contain (LinkID, LaneNum, TLinkID, FnJunction, TNJunction, SpeedLimit, Left, Throgh, Right, Lane1 - Lane 9)");
			}
			while (roadLoader.hasNext()) {
				Road road = roadLoader.next();
				line = br.readLine();
				result = line.split(",");
				// Update road information
				road = setAttribute(road, result);
				roadDictionary.put(road.getLinkid(), road);

				// Create lanes for this road
				Geometry roadGeom = roadGeography.getGeometry(road);
				for (Coordinate c : roadGeom.getCoordinates()) {
					coordCache.put(c, null);
				}

			}
			br.close();

		} catch (java.net.MalformedURLException e) {
			ContextCreator.logger.info(
					"ContextCreator: malformed URL exception when reading roadshapefile. Check the 'roadLoc' parameter is correct");
			e.printStackTrace();

		} catch (FileNotFoundException e) {
			ContextCreator.logger.info("ContextCreator: No road csv file found");
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static boolean onRoad(Coordinate c) {
		return coordCache.containsKey(c);
	}

	public Road setAttribute(Road r, String[] att) {
		r.setLinkid(Integer.parseInt(att[0]));
		r.setLeft(Integer.parseInt(att[6]));
		r.setThrough(Integer.parseInt(att[7]));
		r.setRight(Integer.parseInt(att[8]));
		r.setTlinkid(Integer.parseInt(att[2]));
		r.setFn(Integer.parseInt(att[3]));
		r.setTn(Integer.parseInt(att[4]));
		return r;
	}
	
	public Road findRoadWithIntegerID(int integerID) {
		if (this.roadDictionary.containsKey(integerID)) {
			return this.roadDictionary.get(integerID);
		} else {
			return null;
		}
	}
	
	public Collection<Road> getAllObjects() {
		return roadDictionary.values();
	}

}
