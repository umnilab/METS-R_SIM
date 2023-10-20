package mets_r.facility;

import java.lang.Iterable;

import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.GeographyParameters;
import com.vividsolutions.jts.geom.Coordinate;
import mets_r.ContextCreator;

/**
 * Inherit from A-RESCUE
 * 
 * Context which holds junction objects
 * 
 * @author Nick Malleson
 */

public class JunctionContext extends FacilityContext<Junction> {

	public JunctionContext() {

		super("JunctionContext");
		ContextCreator.logger.info("JunctionContext creation");

		/* Create a Geography to store junctions in spatially-->Junction Geography */
		GeographyParameters<Junction> geoParams = new GeographyParameters<Junction>();
		// geoParams.setCrs("EPSG:32618");
		GeographyFactoryFinder.createGeographyFactory(null).createGeography("JunctionGeography", this, geoParams);

	}

	public Junction getJunctionWithCoordinates(Coordinate c) {
		Iterable<Junction> it = this.getObjects(Junction.class);
		for (Junction junc : it) {
			if (junc.getCoord().equals(c))
				return junc;
		}
		ContextCreator.logger.error("JunctionContext: getJunctionWithCoordinates: error, junction not found. Coordinates: " + c.toString());
		return null;
	}
}
