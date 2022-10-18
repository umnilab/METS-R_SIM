package addsEVs.partition;

import java.util.ArrayList;
import java.util.HashMap;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.*;
import addsEVs.partition.GrowBisection.SaveNodesToArray;
import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import galois.objects.graph.MorphGraph;
import edu.uci.ics.jung.graph.Graph;
import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.space.projection.ProjectionEvent;
import repast.simphony.space.projection.ProjectionListener;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.graph.JungNetwork;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;


public class GaliosGraphConverter<T> implements ProjectionListener<T> {
	public Graph<T, RepastEdge<T>> RepastGraph; // Repast graph
	public static Network<Junction> roadNetwork; // Repast network projection
	public IntGraph<MetisNode> GaliosGraph;
	private Geography<Zone> zoneGeography;
	public MetisGraph metisGraph;
	public static CityContext cityContext;
	private HashMap<T, Integer> Node2GaliosID;
	private HashMap<Integer, T> GaliosID2Node;
	public ArrayList<ArrayList<Road>> PartitionedInRoads; // 2D array list for roads that lie entirely in each partition
	public ArrayList<Road> PartitionedBwRoads; // Array list for roads that lie in the boundary of two partitions
	public ArrayList<ArrayList<Integer>> BwRoadMembership; // N_bw * 2 dimension array that holds the membership of each boundary road
	public ArrayList<GNode<MetisNode>> nodes;
	public ArrayList<Integer> PartitionWeights;
	public ArrayList<Road> ResolvedRoads; // Roads that can be resolved from the graph transformation
	public ArrayList<Road> LeftOverRoads; // Roads that are not resolvable from graph transformation
	private int nodeNum;
	private int edgeNum;
	
	// For adaptive network partitioning
	private int alpha; // Weight to current vehicle count
	private int beta; // Weight to shadow vehicle count
	private int gamma; // Weight to future routing roads
	
	
	public GaliosGraphConverter(){
		cityContext = ContextCreator.getCityContext();
		RepastGraph = null;
		GaliosGraph = new MorphGraph.IntGraphBuilder().backedByVector(true).directed(true).create();
		metisGraph = new MetisGraph();
	    assert GaliosGraph != null;
	    this.Node2GaliosID = new HashMap<T, Integer>();
	    this.GaliosID2Node = new HashMap<Integer, T>();
	    nodeNum = 0;
	    edgeNum = 0;
	    zoneGeography = ContextCreator.getZoneGeography();
	    ResolvedRoads = new ArrayList<Road>();
	    LeftOverRoads = new ArrayList<Road>();
	    
	    // Setting the alpha, beta, gamma
	    alpha = GlobalVariables.PART_ALPHA;
	    beta = GlobalVariables.PART_BETA;
	    gamma = GlobalVariables.PART_GAMMA;
	}
	
	/* Convert from Repast graph to Galios graph:
	 * Contains two modes:
	 * mode : true, used for initial stage, load vertex weight as the number of vehicles on each zone + 1
	 * 		  false, used for later stage in simulation, use vertex weight as 1 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public MetisGraph RepastToGaliosGraph(boolean mode){
		int i = 0;
		
		try{
			roadNetwork = ContextCreator.getRoadNetwork();
		    // Load Repast network
			RepastGraph = null;
			
			if (roadNetwork instanceof JungNetwork)
				RepastGraph = ((JungNetwork) roadNetwork).getGraph();
			else if (roadNetwork instanceof ContextJungNetwork)
				RepastGraph = ((ContextJungNetwork) roadNetwork).getGraph();
			
			// Create the MetisNode list
		    nodes = new ArrayList<GNode<MetisNode>>();
		    nodeNum = RepastGraph.getVertexCount();
		    
		    int nodeWeight = 1;
		    
		    // Add vertices to IntGraph
		    for (T vertex : RepastGraph.getVertices()){
		    	 Node2GaliosID.put(vertex, i);
		    	 GaliosID2Node.put(i, vertex);
		    	 
		    	 // TODO: Setting initial number of vehicles is problematic, check it next time
		    	 if (mode){
		    		 /* Retrieve the vertex weight */
			    	 Junction j = (Junction) vertex;
			    	 for (Zone zone : zoneGeography.getAllObjects()) {
			    		 Coordinate coord = zone.getCoord();
			    		 if (j.getCoordinate().equals(coord))
			    		 	// For adaptive network partitioning
			    		 	nodeWeight = zone.getTaxiPassengerNum() * this.alpha + 1;
			    		 	 
			    	 }
		    	 }
		    	 
		    	 GNode<MetisNode> n = GaliosGraph.createNode(new MetisNode(i, nodeWeight)); 
		         nodes.add(n);
		         GaliosGraph.add(n);
		         i++;
		    }
		    // Add Edges to IntGraph
		    for (RepastEdge<T> edge : RepastGraph.getEdges()){		    	
		    	T source = RepastGraph.getSource(edge);
		        T dest = RepastGraph.getDest(edge);
		        
		        Junction j1 = (Junction) source;
				Junction j2 = (Junction) dest;
				
				Road road = cityContext.findRoadBetweenJunctionIDs(
						j1.getJunctionID(), j2.getJunctionID());
				
				this.ResolvedRoads.add(road);
				
				// Number of vehicles + 1 on the road, used as the edge weight
//				int edgeWeight = road.getVehicleNum() * 10 + 1; // Use plus one to avoid 0
//				int edgeWeight = road.getVehicleNum() + 1; // Use plus one to avoid 0
				// For adaptive network partitioning
				int edgeWeight = 1 + road.getVehicleNum() * this.alpha  + 
						road.getShadowVehicleNum() * this.beta + road.getFutureRoutingVehNum() * this.gamma;
				
		        GNode<MetisNode> n1 = nodes.get(Node2GaliosID.get(source));
		        GNode<MetisNode> n2 = nodes.get(Node2GaliosID.get(dest));
		        
		        GaliosGraph.addEdge(n1, n2, edgeWeight);
		        GaliosGraph.addEdge(n2, n1, edgeWeight); // We input an undirected graph
		        n1.getData().addEdgeWeight(edgeWeight); // This update the sum of weights for edges adjacent this node
		        n1.getData().incNumEdges();
		        n2.getData().addEdgeWeight(edgeWeight);
		        n2.getData().incNumEdges();
		        
//		        // Add edge weight back to both ends
//		        n1.getData().setWeight(n1.getData().getWeight()+(int)((edgeWeight+1)/2)-1);
//		        n2.getData().setWeight(n2.getData().getWeight()+(int)((edgeWeight+1)/2)-1);
		        
		        /* Weighting scheme by only pushing weights on one end */
		        
		        if (edgeWeight > 1) {
		        	// Push weights only to downstream nodes
		        	n2.getData().setWeight(n2.getData().getWeight() - 1 + edgeWeight);
		        	
//		        	// Push weights only to upstream nodes
//		        	n1.getData().setWeight(n1.getData().getWeight() - 1 + edgeWeight);
		        } 
		        
		        
		        edgeNum++;
		    }
		    metisGraph.setNumEdges(edgeNum);
		    metisGraph.setGraph(GaliosGraph);
		    computeLeftOverRoads(); // Get the leftover roads
		    ContextCreator.logger.info("finshied reading Repast graph " + GaliosGraph.size() + " " + metisGraph.getNumEdges() + 
		    		" # Leftover roads: " + this.LeftOverRoads.size());
		    return metisGraph;
		} catch (Exception e){
			e.printStackTrace();
		}
	    return null;
	}
	
	/* Convert from Galois graph to Repast graph: We only need the partitioned road sets and the roads between partitions 
	   The RepastToGaliosGraph() method must be called before this method to load the Repast Graph */
	public void GaliosToRepastGraph(IntGraph<MetisNode> resultGraph, int nparts){	
		int i;
		try{			
			// Create the MetisNode list to save the nodes from resultgraph
			@SuppressWarnings("unchecked")
			GNode<MetisNode>[] nodes = new GNode[resultGraph.size()];
			resultGraph.map(new SaveNodesToArray(nodes));
		    
			// Total weights of each partition
		    PartitionWeights = new ArrayList<Integer>();
			
		    // Initialize the 2d arraylist to store the edges that completely lie in a partition
		    PartitionedInRoads = new ArrayList<ArrayList<Road>>(nparts);
		    for (i=0;i<nparts;i++){
		    	PartitionedInRoads.add(new ArrayList<Road>());
		    	// Initialize the total partition weights
		    	PartitionWeights.add(0);
		    }
		    
		    // Initialize the arraylist to store the edges that lie in between partitions 
		    PartitionedBwRoads = new ArrayList<Road>();
		    BwRoadMembership = new ArrayList<ArrayList<Integer>>(); // Corresponding membership of the boundary roads
		    
		    i=0;
		    for (RepastEdge<T> edge : RepastGraph.getEdges()){ //loop over all the edges to categorize them into the two categories and store them.
		    	T source = RepastGraph.getSource(edge);
		        T dest = RepastGraph.getDest(edge);
		        
		        Junction j1 = (Junction) source;
				Junction j2 = (Junction) dest;
				
				Road road = cityContext.findRoadBetweenJunctionIDs(j1.getJunctionID(), j2.getJunctionID());
				
				// Get the two nodes of an edge in galois form 
		        GNode<MetisNode> n1 = nodes[Node2GaliosID.get(source)];
		        GNode<MetisNode> n2 = nodes[Node2GaliosID.get(dest)];
		        
		        // Compute the edge weight of the link
		        int edgeWeight = 1 + road.getVehicleNum() * this.alpha  + 
						road.getShadowVehicleNum() * this.beta + road.getFutureRoutingVehNum() * this.gamma;
		        
		        if(n1.getData().getPartition() == n2.getData().getPartition()){ //if road lie within a partition then add the road corresponding to that partition
		        	PartitionedInRoads.get(n1.getData().getPartition()).add(road);
		        	PartitionWeights.set(n1.getData().getPartition(), PartitionWeights.get(n1.getData().getPartition()) + edgeWeight);
		        	
		        }else{ //If road lies between partitions then store the road along with its partition IDs
		        	PartitionedBwRoads.add(road);
		        	ArrayList<Integer> tempList = new ArrayList<Integer>();
		        	tempList.add(n1.getData().getPartition());
		        	tempList.add(n2.getData().getPartition());
		        	BwRoadMembership.add(tempList);
		        }
		    }
		    
		    
		    
		}catch (Exception e){
			e.printStackTrace();
		}
	}
	
	// Get the list of roads that are left over from the Repast graph
	// Must be called after a graph transformation is done
	public void computeLeftOverRoads(){
		for (Road r : ContextCreator.getRoadContext().getObjects(Road.class)) {
			this.LeftOverRoads.add(r);
		}
		this.LeftOverRoads.removeAll(this.ResolvedRoads);
	}
	
	/* ZH: Get the 2D array list that contains all roads that fall entirely in each partition */
	public ArrayList<ArrayList<Road>> getPartitionedInRoads(){
		// Value and index for the partition with minimum total edge weight
		int minWeight= Integer.MAX_VALUE;
		int index = 0;
		for (int i = 0; i < this.PartitionWeights.size(); i++ ) {
			if (minWeight > PartitionWeights.get(i)) {
				minWeight = PartitionWeights.get(i);
				index = i;
			}
		}
		// Add leftover roads to the partition with minimum total edge weight
		for (Road r: this.LeftOverRoads)
			this.PartitionedInRoads.get(index).add(r);
		
		return this.PartitionedInRoads;
	}
	
	/* Get the array list that contains the boundary roads */
	public ArrayList<Road> getPartitionedBwRoads(){
		// Add the leftover roads into the boundary roads
		for (Road r : this.LeftOverRoads)
			this.PartitionedBwRoads.add(r);
		return this.PartitionedBwRoads;
	}
	
	/* Get partition membership for the two end of the boundary roads */
	public ArrayList<ArrayList<Integer>> getBwRoadMembership(){
		return this.BwRoadMembership;
	}
	
	/* Get the total edge weight for each partition */
	public ArrayList<Integer> getPartitionWeights() {
		return this.PartitionWeights;
	}

	@Override
	public void projectionEventOccurred(ProjectionEvent<T> evt) {
		// TODO Auto-generated method stub		
	}
	
	public int getNodeNum() {
		return nodeNum;
	}

}
