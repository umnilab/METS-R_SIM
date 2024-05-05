package mets_r.facility;

import java.io.File;
import java.net.URI;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.ShapefileLoader;

/**
 * Inherit from A-RESCUE
 **/

public class RoadContext extends FacilityContext<Road> {
	
	public RoadContext() {
		super("RoadContext");
		ContextCreator.logger.info("RoadContext creation");
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

		/* CSV or xodr file for data attribute */
		String fileName = GlobalVariables.ROADS_CSV;
		if(GlobalVariables.NETWORK_FILE.length() > 0){
			fileName = GlobalVariables.NETWORK_FILE;
		}
		
		
		if(fileName.endsWith(".csv")) {
			// File class needed to turn stringName to actual file
			try {
				roadFile = new File(GlobalVariables.ROADS_SHAPEFILE);
				URI uri = roadFile.toURI();
				roadLoader = new ShapefileLoader<Road>(Road.class, uri.toURL(), roadGeography, this);
				BufferedReader br = new BufferedReader(new FileReader(fileName));
				String line = br.readLine();
				String[] result = line.split(",");
				if(result.length < 19) {
					ContextCreator.logger.error("Missing fields in Road configuration, a proper one should contain (LinkID, (unused) LaneNum, TLinkID, FnJunction, TNJunction, Left, Through, Right, (optional) Lane1 - Lane 9), length");
				}
				while (roadLoader.hasNext()) {
					line = br.readLine();
					result = line.split(",");
					Road road = roadLoader.nextWithArgs(Integer.parseInt(result[0]));
					road = setAttribute(road, result);
					road.setCoords(roadGeography.getGeometry(road).getCoordinates());
					this.put(road.getID(), road);
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
		else {
			SumoXML sxml = SumoXML.getData(fileName);
			GeometryFactory geomFac = new GeometryFactory();
			for (Road r : sxml.getRoad().values()) {
				this.put(r.getID(), r);
				roadGeography.move(r, geomFac.createLineString(r.getCoords().toArray(new Coordinate[r.getCoords().size()])));
			}
		}
	}

	public Road setAttribute(Road r, String[] att) {
		r.addDownStreamRoad(Integer.parseInt(att[6]));
		r.addDownStreamRoad(Integer.parseInt(att[7]));
		r.addDownStreamRoad(Integer.parseInt(att[8]));
		r.addDownStreamRoad(Integer.parseInt(att[3]));
		r.setRoadType((int)Double.parseDouble(att[2]));
		r.setUpStreamJunction(Integer.parseInt(att[4]));
		r.setDownStreamJunction(Integer.parseInt(att[5]));
		r.setLength(Double.parseDouble(att[18]));
		return r;
	}
}
