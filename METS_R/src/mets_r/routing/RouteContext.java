package mets_r.routing;

import java.util.ArrayList;
import java.util.List;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.*;
import util.Pair;

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
	
	public static List<Road> shortestPathRoute(Road originRoad, Road destRoad){
		Node originDownstreamNode = originRoad.getDownStreamNode();
		Node destUpstreamNode = destRoad.getUpStreamNode();
		List<Road> path = vbr.computeRoute(originRoad, destRoad, originDownstreamNode, destUpstreamNode);
		return path;
	}

	public static List<Road> shortestPathRoute(Coordinate origin, Coordinate destination) {
		Road originRoad = ContextCreator.getCityContext().findRoadAtCoordinates(origin);
		Road destRoad = ContextCreator.getCityContext().findRoadAtCoordinates(destination);
		return shortestPathRoute(originRoad, destRoad);
	}

	public static List<Road> shortestPathRoute(Road originRoad, Coordinate destination){
		Road destRoad = ContextCreator.getCityContext().findRoadAtCoordinates(destination);
		return shortestPathRoute(originRoad, destRoad);
	}
	
	public static List<List<Integer>> UCBRoute(Coordinate origin, Coordinate destination) throws Exception {
		// Resolve the origin and destination road and junctions
		Coordinate originCoord = origin;
		Coordinate destCoord = destination;

		Road originRoad = ContextCreator.getCityContext().findRoadAtCoordinates(originCoord);
		Road destRoad = ContextCreator.getCityContext().findRoadAtCoordinates(destCoord);

		Node originDownstreamNode = originRoad.getDownStreamNode();
		Node destUpstreamNode = destRoad.getUpStreamNode();
		
		List<List<Road>> paths = vbr.computeKRoute(GlobalVariables.NUM_CANDIDATE_ROUTES, originRoad, destRoad,
				originDownstreamNode, destUpstreamNode);
		// Transform the paths into a list of link_ids
		List<List<Integer>> result = new ArrayList<List<Integer>>();
		for (List<Road> path : paths) {
			result.add(new ArrayList<Integer>());
			for (Road road : path) {	
				result.get(result.size() - 1).add(road.getID());
			}
		}
		return result;
	}

	// Use ecoRoute to decide route
	public static Pair<List<Road>, Integer> ecoRoute(Road originRoad, int origin, int destination) {
		String key = Integer.toString(origin) + ',' + destination;
		if (!ContextCreator.routeResult_received.containsKey(key)) {
			return new Pair<>(new ArrayList<Road>(), -1); // Empty route
		}
		int choice = ContextCreator.routeResult_received.get(key);
		if (choice < 0) {
			return new Pair<>(new ArrayList<Road>(), -1); // Empty route
		}
		List<Integer> path = (ContextCreator.route_UCB.get(key)).get(choice);
		// Return a list of link
		List<Road> result = new ArrayList<Road>();
		for (int link_id : path) {
			result.add(ContextCreator.getRoadContext().get(link_id));
		}
		Pair<List<Road>, Integer> final_result = new Pair<>(result, choice);
		return final_result;
	}

	// Use ecoRoute to decide route, uncommented this if you want to test eco-routing for buses
	public static Pair<List<Road>, Integer> ecoRouteBus(Road originRoad, int origin, int destination) {
		String key = Integer.toString(origin) + ',' + destination;
		if (!ContextCreator.routeResult_received_bus.containsKey(key)) {
			return new Pair<>(new ArrayList<Road>(), -1); // Empty route
		}
		int choice = ContextCreator.routeResult_received_bus.get(key);
		if (choice < 0) {
			return new Pair<>(new ArrayList<Road>(), -1); // Empty route
		}
		List<Integer> path = (ContextCreator.route_UCB_bus.get(key)).get(choice);

		// Return a list of link
		List<Road> result = new ArrayList<Road>();
		for (int link_id : path) {
			result.add(ContextCreator.getRoadContext().get(link_id));
		}
		Pair<List<Road>, Integer> final_result = new Pair<>(result, choice);
		return final_result;
	}

	public static void printRoute(List<Road> path) {
		ContextCreator.logger.info("Route:");
		for (Road r : path) {
			ContextCreator.logger.info(" " + r.getID());
		}
	}
}
