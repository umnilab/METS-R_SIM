package mets_r.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;

import mets_r.ContextCreator;
import mets_r.facility.*;
import repast.simphony.space.graph.RepastEdge;

public class RouteContext {
	public static GeometryFactory geomFac; // Used for creating Geometries
	public static volatile VehicleRouting vbr;
	private static final Object ROUTING_GRAPH_LOCK = new Object();
	private static final Set<Integer> roadAdjacencyFallbackOrigins =
			Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

	// Buffers used for efficiency (so don't have to search for objects in
	// entire space), not sure if these values are any good
	public static double little_buffer_distance; 
	public static double big_buffer_distance;

	/* Initialize route object */
	public static void createRoute(){
		VehicleRouting rebuilt = new VehicleRouting(ContextCreator.getRoadNetwork());
		for (ConnectorRoad connector : ContextCreator.getRoadContext().getAllConnectors()) {
			rebuilt.setConnectorWeight(connector);
		}
		synchronized (ROUTING_GRAPH_LOCK) {
			geomFac = new GeometryFactory();
			vbr = rebuilt;
			VehicleRouting.markSharedGraphRebuilt();
			roadAdjacencyFallbackOrigins.clear();
			little_buffer_distance = 0.0001;
			big_buffer_distance = 100;
		}
	}

	/* Update the node based routing object, update the next nearest node matrix */
	public static void setEdgeWeight(Node node1, Node node2, double weight) {
		VehicleRouting routing = vbr;
		if (routing != null) routing.setEdgeWeight(node1, node2, weight);
	}

	public static boolean setRoadNetworkEdgeWeight(RepastEdge<Node> edge, double weight) {
		return VehicleRouting.setSharedEdgeWeight(edge, weight);
	}

	public static int setRoadNetworkEdgeWeights(
			Map<RepastEdge<Node>, Double> updates) {
		return VehicleRouting.setSharedEdgeWeights(updates);
	}

	/** Monotonically increasing version of routing topology / edge weights. */
	public static long getRoutingGraphVersion() {
		return VehicleRouting.getSharedGraphVersion();
	}

	public static VehicleRouting.SingleSourcePathCache shortestPathsFrom(
			Road originRoad) {
		VehicleRouting routing = vbr;
		return routing == null ? null
				: routing.buildSingleSourcePathCache(originRoad);
	}

	public static void setConnectorWeight(ConnectorRoad connector) {
		VehicleRouting routing = vbr;
		if (routing != null) routing.setConnectorWeight(connector);
	}
	
	public static List<Road> shortestPathRoute(Road originRoad, Road destRoad, Random rand){
		if (originRoad == null || destRoad == null) {
			ContextCreator.logger.warn("shortestPathRoute skipped because origin or destination road is null: origin="
					+ roadLabel(originRoad) + ", destination=" + roadLabel(destRoad));
			return null;
		}
		if (destRoad instanceof ConnectorRoad) {
			ContextCreator.logger.debug("shortestPathRoute skipped because a connector cannot be a destination: "
					+ roadLabel(destRoad));
			return null;
		}
		if (originRoad instanceof ConnectorRoad) {
			ConnectorRoad connector = (ConnectorRoad) originRoad;
			Road targetRoad = connector.getTargetRoad();
			List<Road> suffix;
			if (targetRoad.getID() == destRoad.getID()) {
				suffix = new ArrayList<Road>();
				suffix.add(targetRoad);
			} else {
				suffix = shortestPathRoute(targetRoad, destRoad, rand);
			}
			if (suffix == null || suffix.isEmpty()) return null;
			ArrayList<Road> connectorPath = new ArrayList<Road>(suffix.size() + 1);
			connectorPath.add(connector);
			connectorPath.addAll(suffix);
			return connectorPath;
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
		if (!originRoad.canBeOrigin() || !destRoad.canBeDest()) {
			ContextCreator.logger.debug("shortestPathRoute skipped for non-routable endpoint: origin="
					+ roadLabel(originRoad) + " originAllowed=" + originRoad.canBeOrigin()
					+ ", destination=" + roadLabel(destRoad) + " destAllowed=" + destRoad.canBeDest());
			return null;
		}
		Node originDownStreamNode = originRoad.getDownStreamNode();
		Node destUpStreamNode = destRoad.getUpStreamNode();
		if (originDownStreamNode == null || destUpStreamNode == null) {
			ContextCreator.logger.warn("shortestPathRoute skipped because origin or destination node is null: origin="
					+ roadLabel(originRoad) + ", destination=" + roadLabel(destRoad));
			return null;
		}
		if (roadAdjacencyFallbackOrigins.contains(originRoad.getID())) {
			List<Road> fallback = roadAdjacencyShortestPath(originRoad, destRoad);
			if (fallback != null) return fallback;
			roadAdjacencyFallbackOrigins.remove(originRoad.getID());
		}

		VehicleRouting routing = vbr;
		List<Road> graphRoute = computeGraphRouteQuietly(routing, originRoad, destRoad,
				originDownStreamNode, destUpStreamNode, rand);
		if (graphRoute != null) return graphRoute;

		// Road.downStreamRoads is the movement authority used by vehicle physics.
		// If it can reach the destination while the node graph cannot, rebuild the
		// derived graph once and retain the authoritative route as a safe fallback.
		List<Road> fallback = roadAdjacencyShortestPath(originRoad, destRoad);
		if (fallback != null) {
			VehicleRouting repaired = rebuildRoutingGraphAfterFailure(routing);
			List<Road> repairedRoute = computeGraphRouteQuietly(repaired, originRoad, destRoad,
					originDownStreamNode, destUpStreamNode, rand);
			if (repairedRoute != null) {
				ContextCreator.logger.warn("Rebuilt an inconsistent routing graph after a missing path from "
						+ roadLabel(originRoad) + " to " + roadLabel(destRoad) + ".");
				return repairedRoute;
			}
			if (roadAdjacencyFallbackOrigins.add(originRoad.getID())) {
				ContextCreator.logger.warn("Routing graph remains inconsistent at origin "
						+ roadLabel(originRoad) + "; using road-adjacency routing for that origin.");
			}
			return fallback;
		}

		ContextCreator.logger.warn("No routing path between " + roadLabel(originRoad) + ", "
				+ roadLabel(destRoad) + " originAllowed=" + originRoad.canBeOrigin()
				+ " destAllowed=" + destRoad.canBeDest());
		return null;
	}

	private static List<Road> computeGraphRouteQuietly(VehicleRouting routing,
			Road originRoad, Road destRoad, Node originNode, Node destNode, Random rand) {
		if (routing == null) return null;
		try {
			return routing.computeRoute(originRoad, destRoad, originNode, destNode, rand, false);
		} catch (Exception ex) {
			return null;
		}
	}

	private static VehicleRouting rebuildRoutingGraphAfterFailure(VehicleRouting failedRouting) {
		synchronized (ROUTING_GRAPH_LOCK) {
			if (vbr != null && vbr != failedRouting) return vbr;
			VehicleRouting rebuilt = new VehicleRouting(ContextCreator.getRoadNetwork());
			vbr = rebuilt;
			VehicleRouting.markSharedGraphRebuilt();
			return rebuilt;
		}
	}

	private static List<Road> roadAdjacencyShortestPath(Road originRoad, Road destRoad) {
		return VehicleRouting.withSharedGraphReadLock(
				() -> roadAdjacencyShortestPathLocked(originRoad, destRoad));
	}

	private static List<Road> roadAdjacencyShortestPathLocked(Road originRoad, Road destRoad) {
		RoadContext roadContext = ContextCreator.getRoadContext();
		if (roadContext == null) return null;
		PriorityQueue<RoadCost> frontier = new PriorityQueue<RoadCost>(
				Comparator.comparingDouble((RoadCost state) -> state.cost)
						.thenComparingInt(state -> state.road.getID()));
		Map<Integer, Double> bestCost = new HashMap<Integer, Double>();
		Map<Integer, Integer> predecessor = new HashMap<Integer, Integer>();
		Set<Integer> settled = new HashSet<Integer>();
		bestCost.put(originRoad.getID(), 0.0);
		frontier.add(new RoadCost(originRoad, 0.0));

		while (!frontier.isEmpty()) {
			RoadCost state = frontier.poll();
			Road current = state.road;
			if (!settled.add(current.getID())) continue;
			if (current.getID() == destRoad.getID()) break;
			for (Integer downstreamID : current.getDownStreamRoads()) {
				if (downstreamID == null) continue;
				Road downstream = roadContext.get(downstreamID);
				if (downstream == null || downstream instanceof ConnectorRoad) continue;
				double candidate = state.cost + transitionCost(current, downstream,
						downstream.getID() == destRoad.getID());
				Double previous = bestCost.get(downstream.getID());
				if (previous == null || candidate < previous) {
					bestCost.put(downstream.getID(), candidate);
					predecessor.put(downstream.getID(), current.getID());
					frontier.add(new RoadCost(downstream, candidate));
				}
			}
		}
		if (!bestCost.containsKey(destRoad.getID())) return null;

		LinkedList<Road> path = new LinkedList<Road>();
		Road cursor = destRoad;
		path.addFirst(cursor);
		while (cursor.getID() != originRoad.getID()) {
			Integer predecessorID = predecessor.get(cursor.getID());
			if (predecessorID == null) return null;
			cursor = roadContext.get(predecessorID);
			if (cursor == null) return null;
			path.addFirst(cursor);
		}
		return path;
	}

	private static double transitionCost(Road source, Road target, boolean targetIsDestination) {
		double movementCost = 1.0e-3;
		Node sourceNode = source.getDownStreamNode();
		Node targetNode = target.getUpStreamNode();
		if (sourceNode != null && targetNode != null && ContextCreator.getRoadNetwork() != null) {
			RepastEdge<Node> movementEdge = ContextCreator.getRoadNetwork().getEdge(sourceNode, targetNode);
			if (movementEdge != null && Double.isFinite(movementEdge.getWeight())) {
				movementCost = Math.max(1.0e-3, movementEdge.getWeight());
			} else {
				ConnectorRoad connector = ContextCreator.getRoadContext().getConnector(source, target);
				if (connector != null && Double.isFinite(connector.getTravelTime())) {
					movementCost = Math.max(1.0e-3, connector.getTravelTime());
				}
			}
		}
		double roadCost = targetIsDestination || !Double.isFinite(target.getTravelTime())
				? 0.0 : Math.max(1.0e-3, target.getTravelTime());
		return movementCost + roadCost;
	}

	private static final class RoadCost {
		final Road road;
		final double cost;

		RoadCost(Road road, double cost) {
			this.road = road;
			this.cost = cost;
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
		if (destRoad instanceof ConnectorRoad) return null;
		if (!originRoad.canBeOrigin() || !destRoad.canBeDest()) return null;
		if (originRoad instanceof ConnectorRoad) {
			ConnectorRoad connector = (ConnectorRoad) originRoad;
			Road targetRoad = connector.getTargetRoad();
			List<List<Road>> suffixes;
			if (targetRoad.getID() == destRoad.getID()) {
				ArrayList<Road> suffix = new ArrayList<Road>();
				suffix.add(targetRoad);
				suffixes = new ArrayList<List<Road>>();
				suffixes.add(suffix);
			} else {
				suffixes = kShortestPathRoute(K, targetRoad, destRoad);
			}
			if (suffixes == null || suffixes.isEmpty()) return suffixes;
			ArrayList<List<Road>> connectorPaths = new ArrayList<List<Road>>(suffixes.size());
			for (List<Road> suffix : suffixes) {
				ArrayList<Road> connectorPath = new ArrayList<Road>(suffix.size() + 1);
				connectorPath.add(connector);
				connectorPath.addAll(suffix);
				connectorPaths.add(connectorPath);
			}
			return connectorPaths;
		}
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
