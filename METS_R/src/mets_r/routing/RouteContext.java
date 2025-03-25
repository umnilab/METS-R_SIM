package mets_r.routing;

import java.util.List;
import java.util.Random;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;

import mets_r.ContextCreator;
import mets_r.facility.*;

public class RouteContext {
	public static GeometryFactory geomFac; // Used for creating Geometries
	public static VehicleRouting vbr;

	// Buffers used for efficiency (so don't have to search for objects in
	// entire space), not sure if these values are any good
	public static double little_buffer_distance; 
	public static double big_buffer_distance;

	/* Initialize route object */
	public static void createRoute(){
		geomFac = new GeometryFactory();
		vbr = new VehicleRouting(ContextCreator.getRoadNetwork());
		little_buffer_distance = 0.0001;
		big_buffer_distance = 100;
	}

	/* Update the node based routing object, update the next nearest node matrix */
	public static void setEdgeWeight(Node node1, Node node2, double weight) {
		vbr.setEdgeWeight(node1, node2, weight);
	}
	
	public static List<Road> shortestPathRoute(Road originRoad, Road destRoad, Random rand){
		Node originDownstreamNode = originRoad.getDownStreamNode();
		Node destUpstreamNode = destRoad.getUpStreamNode();
		List<Road> path = vbr.computeRoute(originRoad, destRoad, originDownstreamNode, destUpstreamNode, rand);
		return path;
	}

	public static List<Road> shortestPathRoute(Coordinate origin, Coordinate destination, Random rand) {
		Road originRoad = ContextCreator.getCityContext().findRoadAtCoordinates(origin, false);
		Road destRoad = ContextCreator.getCityContext().findRoadAtCoordinates(destination, true);
		return shortestPathRoute(originRoad, destRoad, rand);
	}

	public static List<Road> shortestPathRoute(Road originRoad, Coordinate destination, Random rand){
		Road destRoad = ContextCreator.getCityContext().findRoadAtCoordinates(destination, true);
		return shortestPathRoute(originRoad, destRoad, rand);
	}

	public static void printRoute(List<Road> path) {
		ContextCreator.logger.info("Route:");
		for (Road r : path) {
			ContextCreator.logger.info(" " + r.getOrigID());
		}
	}
}
