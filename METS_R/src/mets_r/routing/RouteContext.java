package mets_r.routing;

import java.util.ArrayList;
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
		if (originRoad == null || destRoad == null) {
			ContextCreator.logger.warn("shortestPathRoute skipped because origin or destination road is null: origin="
					+ roadLabel(originRoad) + ", destination=" + roadLabel(destRoad));
			return null;
		}
		if (vbr == null) {
			ContextCreator.logger.warn("shortestPathRoute skipped because the routing engine is not initialized.");
			return null;
		}
		if (originRoad.getID() == destRoad.getID()) {
			List<Road> sameRoadPath = new ArrayList<Road>();
			sameRoadPath.add(originRoad);
			return sameRoadPath;
		}
		Node originDownStreamNode = originRoad.getDownStreamNode();
		Node destUpStreamNode = destRoad.getUpStreamNode();
		if (originDownStreamNode == null || destUpStreamNode == null) {
			ContextCreator.logger.warn("shortestPathRoute skipped because origin or destination node is null: origin="
					+ roadLabel(originRoad) + ", destination=" + roadLabel(destRoad));
			return null;
		}
		try {
			return vbr.computeRoute(originRoad, destRoad, originDownStreamNode, destUpStreamNode, rand);
		} catch (Exception ex) {
			ContextCreator.logger.warn("Routing failed between " + roadLabel(originRoad) + " and "
					+ roadLabel(destRoad) + ": " + ex.getMessage());
			return null;
		}
	}

	public static List<Road> shortestPathRoute(Coordinate origin, Coordinate destination, Random rand) {
		if (origin == null || destination == null) {
			ContextCreator.logger.warn("shortestPathRoute skipped because origin or destination coordinate is null.");
			return null;
		}
		Road originRoad = ContextCreator.getCityContext().findRoadAtCoordinates(origin, false);
		Road destRoad = ContextCreator.getCityContext().findRoadAtCoordinates(destination, true);
		return shortestPathRoute(originRoad, destRoad, rand);
	}

	public static List<List<Road>> kShortestPathRoute(int K, Road originRoad, Road destRoad) {
		if (originRoad == null || destRoad == null || vbr == null) return null;
		Node originDownStreamNode = originRoad.getDownStreamNode();
		Node destUpStreamNode = destRoad.getUpStreamNode();
		if (originDownStreamNode == null || destUpStreamNode == null) return null;
		return vbr.computeKRoute(K, originRoad, destRoad, originDownStreamNode, destUpStreamNode);
	}

	public static List<List<Road>> kShortestPathRoute(int K, Coordinate origin, Coordinate destination) {
		Road originRoad = ContextCreator.getCityContext().findRoadAtCoordinates(origin, false);
		Road destRoad = ContextCreator.getCityContext().findRoadAtCoordinates(destination, true);
		if (originRoad == null || destRoad == null) return null;
		return kShortestPathRoute(K, originRoad, destRoad);
	}

	public static void printRoute(List<Road> path) {
		if (path == null) {
			ContextCreator.logger.info("Route: null");
			return;
		}
		ContextCreator.logger.info("Route:");
		for (Road r : path) {
			ContextCreator.logger.info(" " + r.getOrigID());
		}
	}

	private static String roadLabel(Road road) {
		if (road == null) return "null";
		return road.getOrigID() + "(" + road.getID() + ")";
	}
}
