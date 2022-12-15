package mets_r.routing;

import java.util.ArrayList;
import java.util.List;

import org.jgrapht.alg.shortestpath.BidirectionalDijkstraShortestPath;
import org.jgrapht.alg.shortestpath.KShortestSimplePaths;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.GraphPath;

import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.space.graph.JungNetwork;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import edu.uci.ics.jung.graph.Graph;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.CityContext;
import mets_r.facility.Junction;
import mets_r.facility.Road;

public class VehicleRouting {
	public DefaultDirectedWeightedGraph<Junction, RepastEdge<Junction>> transformedNetwork = null;
	public CityContext cityContext;

	@SuppressWarnings({"unchecked", "rawtypes"})
	public VehicleRouting(Network<Junction> network) {
		this.cityContext = ContextCreator.getCityContext();
		Graph<Junction, RepastEdge<Junction>> graphA = null;
		if (network instanceof JungNetwork)
			graphA = ((JungNetwork) network).getGraph();
		else if (network instanceof ContextJungNetwork)
			graphA = ((ContextJungNetwork) network).getGraph();
		JungToJgraph<Junction> converter = new JungToJgraph<Junction>();
		this.transformedNetwork = converter.convertToJgraph(graphA);
	}

	public void setEdgeWeight(Junction junc1, Junction junc2, double weight) {
		transformedNetwork.setEdgeWeight(transformedNetwork.getEdge(junc1, junc2), weight);
	}

	public List<List<Road>> computeKRoute(int K, Road currentRoad, Road destRoad, Junction currJunc,
			Junction destJunc) {
		List<List<Road>> roadPath_ = new ArrayList<List<Road>>();

		KShortestSimplePaths<Junction, RepastEdge<Junction>> ksp = new KShortestSimplePaths<Junction, RepastEdge<Junction>>(
				transformedNetwork);
		List<GraphPath<Junction, RepastEdge<Junction>>> kshortestPath = ksp.getPaths(currJunc, destJunc, K);

		for (int k = 0; k < kshortestPath.size(); k++) {
			List<RepastEdge<Junction>> shortestPath = kshortestPath.get(k).getEdgeList();
			// Find the roads which are associated with these edges
			if (shortestPath != null) { // Found the shortest path
				List<Road> oneRoadPath_ = new ArrayList<Road>(); // Save this path as a list of road and store it in
																	// oneRoadPath_
				oneRoadPath_.add(currentRoad);
				for (RepastEdge<Junction> edge : shortestPath) {
					Road road = cityContext.getRoadFromEdge(edge);
					oneRoadPath_.add(road);
				}
				// Add the whole path into roadPaths
				roadPath_.add(oneRoadPath_);
			}
		}
		
		return roadPath_;
	}

	/* Perform the routing computation */
	public List<Road> computeRoute(Road currentRoad, Road destRoad, Junction currJunc, Junction destJunc) {
		List<Road> roadPath_ = null;
		List<RepastEdge<Junction>> shortestPath = null;

		// Get the edges that make up the shortest path
		int K = GlobalVariables.K_VALUE;
		double theta = GlobalVariables.THETA_LOGIT;
		
		if (currJunc == destJunc) { // Origin and destination is the same
			roadPath_ = new ArrayList<Road>();
			roadPath_.add(currentRoad);
		}
		else {
			if (GlobalVariables.K_SHORTEST_PATH) {
				// Find the k-shortest path
				KShortestSimplePaths<Junction, RepastEdge<Junction>> ksp = new KShortestSimplePaths<Junction, RepastEdge<Junction>>(
						transformedNetwork);
				List<GraphPath<Junction, RepastEdge<Junction>>> kshortestPath = ksp.getPaths(currJunc, destJunc, K);
	
				List<Double> pathLength = new ArrayList<Double>();
				List<Double> pathProb = new ArrayList<Double>();
				List<Double> cumProb = new ArrayList<Double>();
				double total = 0.0;
	
				for (GraphPath<Junction, RepastEdge<Junction>> kpath : kshortestPath) {
					pathLength.add(kpath.getWeight());
				}
				for (int i = 0; i < kshortestPath.size(); i++) {
					total = total + Math.exp(-theta * pathLength.get(i));
				}
	
				// Calculate the probability
				for (int i = 0; i < kshortestPath.size(); i++) {
					double prob = Math.exp(-theta * pathLength.get(i)) / total;
					pathProb.add(prob);
					if (i == 0)
						cumProb.add(i, prob);
					else
						cumProb.add(i, cumProb.get(i - 1) + prob);
				}
	
				// Find the path to go
				int k = 0;
				double random = GlobalVariables.RandomGenerator.nextDouble();
				for (int i = 0; i < kshortestPath.size(); i++) {
					if (random < cumProb.get(i)) {
						k = i;
						break;
					}
				}
				shortestPath = kshortestPath.get(k).getEdgeList();
	
			} else { // Single shortest path
				try {
					BidirectionalDijkstraShortestPath<Junction, RepastEdge<Junction>> sp = new BidirectionalDijkstraShortestPath<Junction, RepastEdge<Junction>>(
							transformedNetwork);
					shortestPath = sp.getPath(currJunc, destJunc).getEdgeList();
				}
				catch(Exception e) {
					ContextCreator.logger.error("Cannot find path between " + currJunc.getJunctionID() + ", " + destJunc.getJunctionID());
				}
			}
	
			// Find the roads which are associated with these edges
			if (shortestPath != null) { // Found the shortest path
				roadPath_ = new ArrayList<Road>();
				roadPath_.add(currentRoad);
				for (RepastEdge<Junction> edge : shortestPath) {
					Road road = cityContext.getRoadFromEdge(edge);
					roadPath_.add(road);
				}
			}
		}
		return roadPath_;
	}
}
