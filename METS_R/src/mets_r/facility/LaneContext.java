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

public class LaneContext extends FacilityContext<Lane> {
	
	public LaneContext() {
		super("LaneContext");
		// GIS projection for spatial information about Lanes.
		ContextCreator.logger.info("LaneContext: building lane context and projections");
		GeographyParameters<Lane> geoParams = new GeographyParameters<Lane>();
		Geography<Lane> laneGeography = GeographyFactoryFinder.createGeographyFactory(null)
				.createGeography("LaneGeography", this, geoParams);

		// Read in the data and add to the context and geography
		File laneFile = null;
		ShapefileLoader<Lane> laneLoader = null;

		// CSV, SUMO XML or xodr file for data attributes, by default it is the SUMO XML file
		String fileName = GlobalVariables.LANES_CSV;
		if(GlobalVariables.NETWORK_FILE.length() > 0){
			fileName = GlobalVariables.NETWORK_FILE;
		}
		
		if(fileName.endsWith(".csv")) {
			try {
				laneFile = new File(GlobalVariables.LANES_SHAPEFILE);
				URI uri = laneFile.toURI();
				laneLoader = new ShapefileLoader<Lane>(Lane.class, uri.toURL(), laneGeography, this);
				BufferedReader br = new BufferedReader(new FileReader(fileName));
				String line = br.readLine();
				String[] result = line.split(",");
				if(result.length < 5) {
					ContextCreator.logger.error("Missing fields in Lane configuration, a proper one should contain (LaneID, LinkID, Left, Through, Right)");
				}
				while (laneLoader.hasNext()) {
					line = br.readLine();
					result = line.split(",");
					Lane lane = laneLoader.nextWithArgs(Integer.parseInt(result[0]));
					lane = setAttribute(lane, result);
					lane.setCoords(laneGeography.getGeometry(lane).getCoordinates());
					this.put(lane.getID(), lane);
				}
				br.close();
	
			} catch (java.net.MalformedURLException e) {
				ContextCreator.logger.error(
						"ContextCreator: malformed URL exception when reading roadshapefile. Check the 'roadLoc' parameter is correct");
				e.printStackTrace();
			} catch (FileNotFoundException e) {
				ContextCreator.logger.error("ContextCreator: No road csv file found");
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		else {
			SumoXML sxml = SumoXML.getData(fileName);
			GeometryFactory geomFac = new GeometryFactory();
			for (Lane l : sxml.getLane().values()) {
				this.put(l.getID(), l);
				laneGeography.move(l, geomFac.createLineString(l.getCoords().toArray(new Coordinate[l.getCoords().size()])));
			}
		}
	}

	public Lane setAttribute(Lane l, String[] att) {
		l.setRoad(Integer.parseInt(att[1]));
		if(Integer.parseInt(att[2])!=0)
			l.addDownStreamLane(Integer.parseInt(att[2]));
		if(Integer.parseInt(att[3])!=0)
			l.addDownStreamLane(Integer.parseInt(att[3]));
		if(Integer.parseInt(att[4])!=0)
			l.addDownStreamLane(Integer.parseInt(att[4]));
		return l;
	}
}
