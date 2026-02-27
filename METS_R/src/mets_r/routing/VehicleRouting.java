package mets_r.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jgrapht.alg.shortestpath.BidirectionalDijkstraShortestPath;
import org.jgrapht.alg.shortestpath.YenKShortestPath;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.GraphPath;

import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.space.graph.JungNetwork;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import edu.uci.ics.jung.graph.Graph;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.Node;
import mets_r.facility.Road;

public class VehicleRouting {
	public DefaultDirectedWeightedGraph<Node, RepastEdge<Node>> transformedNetwork = null;

	@SuppressWarnings({"unchecked", "rawtypes"})
	public VehicleRouting(Network<Node> roadNetwork) {
		Graph<Node, RepastEdge<Node>> graphA = null;
		if (roadNetwork instanceof JungNetwork)
			graphA = ((JungNetwork) roadNetwork).getGraph();
		else if (roadNetwork instanceof ContextJungNetwork)
			graphA = ((ContextJungNetwork) roadNetwork).getGraph();
		NodeToJgraph<Node> converter = new NodeToJgraph<Node>();
		transformedNetwork = converter.convertToJgraph(graphA);
	}

	public void setEdgeWeight(Node node1, Node node2, double weight) {
//		ContextCreator.logger.info("Node 1" + node1.getID() + " Node 2" + node2.getID() + " Weight " + weight); 
		transformedNetwork.setEdgeWeight(transformedNetwork.getEdge(node1, node2), weight);
	}

	public List<List<Road>> computeKRoute(int K, Road currentRoad, Road destRoad, Node currNode,
			Node destNode) {
		List<List<Road>> roadPath_ = new ArrayList<List<Road>>();
		YenKShortestPath<Node, RepastEdge<Node>> ksp = new YenKShortestPath<Node, RepastEdge<Node>>(
				transformedNetwork);
		List<GraphPath<Node, RepastEdge<Node>>> kshortestPath = ksp.getPaths(currNode, destNode, K);
		for (int k = 0; k < kshortestPath.size(); k++) {
			List<RepastEdge<Node>> shortestPath = kshortestPath.get(k).getEdgeList();
			// Find the roads which are associated with these edges
			if (shortestPath != null) { // Found the shortest path
				List<Road> oneRoadPath_ = new ArrayList<Road>(); // Save this path as a list of road and store it in
																	// oneRoadPath_
				oneRoadPath_.add(currentRoad);
				for (RepastEdge<Node> edge : shortestPath) {
					int roadID = ContextCreator.getCityContext().getRoadIDFromEdge(edge);
					if(roadID > 0) {
						oneRoadPath_.add(ContextCreator.getRoadContext().get(roadID));
					}
				}
				// Add the whole path into roadPaths
				roadPath_.add(oneRoadPath_);
			}
		}
		
		return roadPath_;
	}
 
	/* Perform the routing computation */
	public List<Road> computeRoute(Road currentRoad, Road destRoad, Node currNode, Node destNode, Random rand) {
		List<Road> roadPath_ = null;
		List<RepastEdge<Node>> shortestPath = null;

		// Get the edges that make up the shortest path
		int K = GlobalVariables.K_VALUE;
		double theta = GlobalVariables.THETA_LOGIT;
		
		if (currentRoad.getID() == destRoad.getID()) { // Origin and destination road is the same
			roadPath_ = new ArrayList<Road>();
			roadPath_.add(currentRoad);
		}
		else {
			if (GlobalVariables.K_SHORTEST_PATH && rand != null) { // rand is null when this is used merely for travel time estimation
				// Find the k-shortest path
				YenKShortestPath<Node, RepastEdge<Node>> ksp = new YenKShortestPath<Node, RepastEdge<Node>>(
						transformedNetwork);
				List<GraphPath<Node, RepastEdge<Node>>> kshortestPath = ksp.getPaths(currNode, destNode, K);
	
				List<Double> pathLength = new ArrayList<Double>();
				List<Double> pathProb = new ArrayList<Double>();
				List<Double> cumProb = new ArrayList<Double>();
				double total = 0.0;
	
				for (GraphPath<Node, RepastEdge<Node>> kpath : kshortestPath) {
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
				double random = rand.nextDouble();
				for (int i = 0; i < kshortestPath.size(); i++) {
					if (random < cumProb.get(i)) {
						k = i;
						break;
					}
				}
				shortestPath = kshortestPath.get(k).getEdgeList();
	
			} else { // Single shortest path
				try {
					BidirectionalDijkstraShortestPath<Node, RepastEdge<Node>> sp = new BidirectionalDijkstraShortestPath<Node, RepastEdge<Node>>(
							transformedNetwork);
					shortestPath = sp.getPath(currNode, destNode).getEdgeList();
				}
				catch(Exception e) {
					ContextCreator.logger.error("Routing engine error between " + currentRoad.getOrigID() + ", " + destRoad.getOrigID() + ": " + e.getMessage());
				}
			}
	
			// Find the roads which are associated with these edges
			if (shortestPath != null) { // Found the shortest path
				roadPath_ = new ArrayList<Road>();
				roadPath_.add(currentRoad);
				for (RepastEdge<Node> edge : shortestPath) {
					int roadID = ContextCreator.getCityContext().getRoadIDFromEdge(edge);
					if(roadID >= 0) {
						roadPath_.add(ContextCreator.getRoadContext().get(roadID));
					}
				}
				roadPath_.add(destRoad);
			}
		}
		return roadPath_;
	}
}
