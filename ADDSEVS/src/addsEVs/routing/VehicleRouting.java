package addsEVs.routing;


import java.util.ArrayList;
import java.util.List;

import org.jgrapht.*;
import org.jgrapht.graph.*;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.alg.KShortestPaths;
import org.jgrapht.GraphPath;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.CityContext;
import addsEVs.citycontext.Junction;
import addsEVs.citycontext.Road;
import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.essentials.RepastEssentials;
import repast.simphony.space.projection.ProjectionEvent;
import repast.simphony.space.projection.ProjectionListener;
import repast.simphony.space.graph.EdgeCreator;
import repast.simphony.space.graph.JungNetwork;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import repast.simphony.space.graph.ShortestPath;
import edu.uci.ics.jung.graph.Graph;

public class VehicleRouting {
	private Network<Junction> network;
	public WeightedGraph<Junction, RepastEdge<Junction>> transformedNetwork = null;
	public CityContext cityContext;
	
	public VehicleRouting(Network<Junction> network){
		this.cityContext = ContextCreator.getCityContext();
		this.network = network;
		
		Graph<Junction, RepastEdge<Junction>> graphA = null;

		if (network instanceof JungNetwork)
			graphA = ((JungNetwork) network).getGraph();
		else if (network instanceof ContextJungNetwork)
			graphA = ((ContextJungNetwork) network).getGraph();

		JungToJgraph<Junction> converter = new JungToJgraph<Junction>();
		this.transformedNetwork = converter.convertToJgraph(graphA);
	}
	
	/**
	 * Creates routing info using the Node Based routing algorithm
	 */
	public void calcRoute() {
		Graph<Junction, RepastEdge<Junction>> graphA = null;

		if (network instanceof JungNetwork)
			graphA = ((JungNetwork) network).getGraph();
		else if (network instanceof ContextJungNetwork)
			graphA = ((ContextJungNetwork) network).getGraph();

		JungToJgraph<Junction> converter = new JungToJgraph<Junction>();
		this.transformedNetwork = converter.convertToJgraph(graphA);
//		for (Junction node: this.transformedNetwork.vertexSet()){
//			int tempdegree=0;
//			if(node.getRoads().size()>=5)
//				for (Road road: node.getRoads() )
//					if(road.getFn() == node.getJunctionID())
//						tempdegree+=1;
//				if(tempdegree>=4)	
//					System.out.println("junctionid"+node.getJunctionID()+"sizeofjunction"+node.getRoads().size()+"rode_list"+node.getRoads());
//		}
	}
	
	public List<List<Road>> computeKRoute(int K, Road currentRoad, Road destRoad,
			Junction currJunc, Junction destJunc){
		List<List<Road>> roadPath_= new ArrayList<List<Road>>();
		
		KShortestPaths<Junction, RepastEdge<Junction>> ksp = new KShortestPaths<Junction, RepastEdge<Junction>>(transformedNetwork, currJunc, K);
		List<GraphPath<Junction, RepastEdge<Junction>>> kshortestPath = ksp.getPaths(destJunc);
		System.out.println(kshortestPath.size());
		for(int k=0;k<kshortestPath.size();k++){
			List<RepastEdge<Junction>> shortestPath = kshortestPath.get(k).getEdgeList();
			// Find the roads which are associated with these edges
			
			if(shortestPath != null){ //LZ: found the shortest path
				List<Road> oneRoadPath_ = new ArrayList<Road>();  //LZ: save this path as a list of road and store it in oneRoadPath_
				oneRoadPath_.add(currentRoad);
				for (RepastEdge<Junction> edge : shortestPath) {
					int linkID = cityContext.getLinkIDFromEdge(edge);
					Road road = cityContext.findRoadWithLinkID(linkID);
					oneRoadPath_.add(road);
				}
				// Add the whole path into roadPaths
				roadPath_.add(oneRoadPath_);
			}
		}
		
		return roadPath_;
	}
	/* Perform the routing computation */
	public List<Road> computeRoute(Road currentRoad, Road destRoad,
			Junction currJunc, Junction destJunc) {
		List<Road> roadPath_;
		List<RepastEdge<Junction>> shortestPath;
		shortestPath = null;
		
		// Get the edges that make up the shortest path
		int K = GlobalVariables.K_VALUE;
		double theta = GlobalVariables.THETA_LOGIT;
		
		if (GlobalVariables.K_SHORTEST_PATH) {
			// Find the k-shortest path
			KShortestPaths<Junction, RepastEdge<Junction>> ksp = new KShortestPaths<Junction, RepastEdge<Junction>>(transformedNetwork, currJunc, K);
			List<GraphPath<Junction, RepastEdge<Junction>>> kshortestPath = ksp.getPaths(destJunc);
			
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

			// calculate the probability
			for (int i = 0; i < kshortestPath.size(); i++) {
				double prob = Math.exp(-theta * pathLength.get(i)) / total;
				pathProb.add(prob);
				if (i == 0)
					cumProb.add(i, prob);
				else
					cumProb.add(i, cumProb.get(i - 1) + prob);
			}

			// find the path to go
			int k = 0;
			double random = Math.random();
			for (int i = 0; i < kshortestPath.size(); i++) {
				if (random < cumProb.get(i)) {
					k = i;
					break;
				}
			}
			shortestPath = kshortestPath.get(k).getEdgeList();
			
		} else if (GlobalVariables.SINGLE_SHORTEST_PATH) {
			/* Old thread unsafe implementation using shortest path algorithm from Repast library
			ShortestPath<Junction> p = new ShortestPath<Junction>(network);
			shortestPath = p.getPath(currJunc, destJunc);
			p.finalize();*/
			
			/* New implementation using JGraphT */
			DijkstraShortestPath<Junction, RepastEdge<Junction>> sp = new DijkstraShortestPath<Junction, RepastEdge<Junction>>(transformedNetwork, currJunc, destJunc);
			shortestPath = sp.getPathEdgeList();
			
//			/*debugging*/
//			Junction currJunc_db = null;
//			Junction destJunc_db = null;
//			for (Junction node: this.transformedNetwork.vertexSet())
//			{
//				if(node.getJunctionID()==11010)
//					currJunc_db = node;
//				if(node.getJunctionID()==11073)
//					destJunc_db = node;
//			}
//			DijkstraShortestPath<Junction, RepastEdge<Junction>> sp_debug = new DijkstraShortestPath<Junction, RepastEdge<Junction>>(transformedNetwork, currJunc_db, destJunc_db);
//			for (RepastEdge<Junction> edge : sp_debug.getPathEdgeList())
//			{
//				System.out.println("id="+cityContext.getLinkIDFromEdge(edge));
//			}
		}

		
		// Find the roads which are associated with these edges
		double shortestPathLength = 0.0f;
		roadPath_ = new ArrayList<Road>();
		if(shortestPath != null){ //LZ: found the shortest path
			roadPath_.add(currentRoad);
			for (RepastEdge<Junction> edge : shortestPath) {
				int linkID = cityContext.getLinkIDFromEdge(edge);
				Road road = cityContext.findRoadWithLinkID(linkID);
	//			System.out.println("linkID: " + linkID + " Path-" + road.getID());
				roadPath_.add(road);
				shortestPathLength = shortestPathLength + edge.getWeight();
			}
		}
        //shortestPath = null;
		
		return roadPath_;
	}
	

}
