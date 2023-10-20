package galois.partition;

import java.util.ArrayList;
import java.util.HashMap;

import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import galois.objects.graph.MorphGraph;
import galois.partition.GrowBisection.SaveNodesToArray;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.*;
import edu.uci.ics.jung.graph.Graph;
import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.space.projection.ProjectionEvent;
import repast.simphony.space.projection.ProjectionListener;
import repast.simphony.space.graph.JungNetwork;
import repast.simphony.space.graph.RepastEdge;

public class GaliosGraphConverter<T> implements ProjectionListener<T> {
	public Graph<T, RepastEdge<T>> RepastGraph; // Repast graph
	public IntGraph<MetisNode> GaliosGraph;
	public MetisGraph metisGraph;
	private HashMap<T, Integer> Node2GaliosID;
	private HashMap<Integer, T> GaliosID2Node;
	public ArrayList<ArrayList<Road>> PartitionedInRoads; // 2D array list for roads that lie entirely in each partition
	public ArrayList<Road> PartitionedBwRoads; // Array list for roads that lie in the boundary of two partitions
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

	public GaliosGraphConverter() {
		RepastGraph = null;
		GaliosGraph = new MorphGraph.IntGraphBuilder().backedByVector(true).directed(true).create();
		metisGraph = new MetisGraph();
		assert GaliosGraph != null;
		this.Node2GaliosID = new HashMap<T, Integer>();
		this.GaliosID2Node = new HashMap<Integer, T>();
		nodeNum = 0;
		edgeNum = 0;
		ResolvedRoads = new ArrayList<Road>();
		LeftOverRoads = new ArrayList<Road>();

		// Setting the alpha, beta, gamma
		alpha = GlobalVariables.PART_ALPHA;
		beta = GlobalVariables.PART_BETA;
		gamma = GlobalVariables.PART_GAMMA;
	}

	/*
	 * Convert from Repast graph to Galios graph: Contains two modes: mode : true,
	 * used for initial stage, load vertex weight as the number of vehicles on each
	 * zone + 1 false, used for later stage in simulation, use vertex weight as 1
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public MetisGraph RepastToGaliosGraph(boolean mode) {
		int i = 0;

		try {
			// Load Repast network
			RepastGraph = null;

			if (ContextCreator.getRoadNetwork() instanceof JungNetwork)
				RepastGraph = ((JungNetwork) ContextCreator.getRoadNetwork()).getGraph();
			else if (ContextCreator.getRoadNetwork() instanceof ContextJungNetwork)
				RepastGraph = ((ContextJungNetwork) ContextCreator.getRoadNetwork()).getGraph();

			// Create the MetisNode list
			nodes = new ArrayList<GNode<MetisNode>>();
			nodeNum = RepastGraph.getVertexCount();

			int nodeWeight = 1;

			// Add vertices to IntGraph
			for (T vertex : RepastGraph.getVertices()) {
				Node2GaliosID.put(vertex, i);
				GaliosID2Node.put(i, vertex);
				GNode<MetisNode> n = GaliosGraph.createNode(new MetisNode(i, nodeWeight));
				nodes.add(n);
				GaliosGraph.add(n);
				i++;
			}
			// Add Edges to IntGraph
			for (RepastEdge<T> edge : RepastGraph.getEdges()) {
				T source = RepastGraph.getSource(edge);
				T dest = RepastGraph.getDest(edge);

				Node node1 = (Node) source;
				Node node2 = (Node) dest;

				Road road = ContextCreator.getCityContext().findRoadBetweenNodeIDs(node1.getID(), node2.getID());
				
				int edgeWeight = 1;
				if(road != null) { // edge for road
					this.ResolvedRoads.add(road);
					// For adaptive network partitioning. 
					edgeWeight = 1 + road.getVehicleNum() * this.alpha + road.getShadowVehicleNum() * this.beta
							+ road.getFutureRoutingVehNum() * this.gamma;
				}

				GNode<MetisNode> n1 = nodes.get(Node2GaliosID.get(source));
				GNode<MetisNode> n2 = nodes.get(Node2GaliosID.get(dest));

				GaliosGraph.addEdge(n1, n2, edgeWeight);
				GaliosGraph.addEdge(n2, n1, edgeWeight); // We input an undirected graph
				n1.getData().addEdgeWeight(edgeWeight); // This update the sum of weights for edges adjacent this node
				n1.getData().incNumEdges();
				n2.getData().addEdgeWeight(edgeWeight);
				n2.getData().incNumEdges();

				/* Weighting scheme by only pushing weights on one end */
				// Push weights only to downstream nodes
				n2.getData().setWeight(n2.getData().getWeight() - 1 + edgeWeight);
				edgeNum++;
			}
			metisGraph.setNumEdges(edgeNum);
			metisGraph.setGraph(GaliosGraph);
			computeLeftOverRoads(); // Get the leftover roads
			ContextCreator.logger.info("finshied reading Repast graph " + GaliosGraph.size() + " "
					+ metisGraph.getNumEdges() + " # Leftover roads: " + this.LeftOverRoads.size());
			return metisGraph;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/*
	 * Convert from Galois graph to Repast graph: We only need the partitioned road
	 * sets and the roads between partitions The RepastToGaliosGraph() method must
	 * be called before this method to load the Repast Graph
	 */
	public void GaliosToRepastGraph(IntGraph<MetisNode> resultGraph, int nparts) {
		int i;
		try {
			// Create the MetisNode list to save the nodes from resultgraph
			@SuppressWarnings("unchecked")
			GNode<MetisNode>[] nodes = new GNode[resultGraph.size()];
			resultGraph.map(new SaveNodesToArray(nodes));

			// Total weights of each partition
			PartitionWeights = new ArrayList<Integer>();

			// Initialize the 2d arraylist to store the edges that completely lie in a partition
			PartitionedInRoads = new ArrayList<ArrayList<Road>>(nparts);
			for (i = 0; i < nparts; i++) {
				PartitionedInRoads.add(new ArrayList<Road>());
				// Initialize the total partition weights
				PartitionWeights.add(0);
			}

			// Initialize the arraylist to store the edges that lie in between partitions
			PartitionedBwRoads = new ArrayList<Road>();

			i = 0;
			for (RepastEdge<T> edge : RepastGraph.getEdges()) { // loop over all the edges to categorize them into the
																// two categories and store them.
				T source = RepastGraph.getSource(edge);
				T dest = RepastGraph.getDest(edge);

				Node node1 = (Node) source;
				Node node2 = (Node) dest;

				Road road = ContextCreator.getCityContext().findRoadBetweenNodeIDs(node1.getID(), node2.getID());
				
				if(road !=null) {
					// Get the two nodes of an edge in galois form
					GNode<MetisNode> n1 = nodes[Node2GaliosID.get(source)];
					GNode<MetisNode> n2 = nodes[Node2GaliosID.get(dest)];

					// Compute the edge weight of the link
					int edgeWeight = 1 + road.getVehicleNum() * this.alpha + road.getShadowVehicleNum() * this.beta
							+ road.getFutureRoutingVehNum() * this.gamma;

					if (n1.getData().getPartition() == n2.getData().getPartition()) { // if road lie within a partition then
																						// add the road corresponding to
																						// that partition
						PartitionedInRoads.get(n1.getData().getPartition()).add(road);
						PartitionWeights.set(n1.getData().getPartition(),
								PartitionWeights.get(n1.getData().getPartition()) + edgeWeight);

					} else { // If road lies between partitions then store the road along with its partition IDs
						PartitionedBwRoads.add(road);
					}
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// Get the list of roads that are left over from the Repast graph
	// Must be called after a graph transformation is done
	public void computeLeftOverRoads() {
		for (Road r : ContextCreator.getRoadContext().getObjects(Road.class)) {
			this.LeftOverRoads.add(r);
		}
		this.LeftOverRoads.removeAll(this.ResolvedRoads);
	}

	/*
	 * ZH: Get the 2D array list that contains all roads that fall entirely in each
	 * partition
	 */
	public ArrayList<ArrayList<Road>> getPartitionedInRoads() {
		// Value and index for the partition with minimum total edge weight
		return this.PartitionedInRoads;
	}

	/* Get the array list that contains the boundary roads */
	public ArrayList<Road> getPartitionedBwRoads() {
		// Add the leftover roads into the boundary roads
		return this.PartitionedBwRoads;
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
