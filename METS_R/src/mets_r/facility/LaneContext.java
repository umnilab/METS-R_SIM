package mets_r.facility;

import java.io.File;
import java.net.URI;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;

//import java.util.HashMap;
//import java.util.Map;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

//import com.vividsolutions.jts.geom.Coordinate;

import repast.simphony.context.DefaultContext;
import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.ShapefileLoader;

public class LaneContext extends DefaultContext<Lane> {

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

		// CSV file for data attributes
		String fileName = GlobalVariables.LANES_CSV;

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
				Lane lane = laneLoader.next();
				line = br.readLine();
				result = line.split(",");
				lane = setAttribute(lane, result);
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

	public Lane setAttribute(Lane l, String[] att) {
		l.setLaneid(Integer.parseInt(att[0]));
		l.setLink(Integer.parseInt(att[1]));
		l.setLeft(Integer.parseInt(att[2]));
		l.setThrough(Integer.parseInt(att[3]));
		l.setRight(Integer.parseInt(att[4]));
		return l;
	}
}
