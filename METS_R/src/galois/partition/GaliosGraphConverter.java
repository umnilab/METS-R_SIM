package galois.partition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

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
	public ArrayList<ArrayList<Road>> partitionedInRoads; // 2D array list for roads that lie entirely in each partition
	public ArrayList<Road> partitionedBwRoads; // Array list for roads that lie in the boundary of two partitions
	public ArrayList<GNode<MetisNode>> nodes;
	public ArrayList<Integer> partitionWeights;
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
				if(road != null) {
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
				edgeNum ++;
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
			partitionWeights = new ArrayList<Integer>();

			// Initialize the 2d arraylist to store the edges that completely lie in a partition
			partitionedInRoads = new ArrayList<ArrayList<Road>>(nparts);
			for (i = 0; i < nparts; i++) {
				partitionedInRoads.add(new ArrayList<Road>());
				// Initialize the total partition weights
				partitionWeights.add(0);
			}

			// Initialize the arraylist to store the edges that lie in between partitions
			partitionedBwRoads = new ArrayList<Road>();

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
					
					int p1 = n1.getData().getPartition();
			        int p2 = n2.getData().getPartition();
					
					if (p1 == p2) { // if road lie within a partition then
																						// add the road corresponding to
																						// that partition
						partitionedInRoads.get(n1.getData().getPartition()).add(road);
						partitionWeights.set(n1.getData().getPartition(),
								partitionWeights.get(n1.getData().getPartition()) + edgeWeight);

					} else { // If road lies between partitions then store the road along with its partition IDs
						partitionedBwRoads.add(road);
						
						// Smart Assignment: Give it to the partition (p1 or p2) that has the lighter load
			            int assignedPartition = (partitionWeights.get(p1) <= partitionWeights.get(p2)) ? p1 : p2;
			            partitionedInRoads.get(assignedPartition).add(road);
			            partitionWeights.set(assignedPartition, partitionWeights.get(assignedPartition) + edgeWeight);
					}
				}
			}
			
			// Immediately after the loop, distribute the LeftOverRoads so they get executed too
			this.computeLeftOverRoads();
			int leftoverPartitionIndex = 0;
			for (Road r : this.LeftOverRoads) {
			    // Keep partitionedBwRoads updated if you use it for logging leftover roads
			    if (!partitionedBwRoads.contains(r)) { 
			        partitionedBwRoads.add(r);
			    }
			    
			    // Round-robin distribute the leftover roads into the execution partitions
			    partitionedInRoads.get(leftoverPartitionIndex).add(r);
			    partitionWeights.set(leftoverPartitionIndex, partitionWeights.get(leftoverPartitionIndex) + 1); // Nominal weight
			    
			    leftoverPartitionIndex = (leftoverPartitionIndex + 1) % nparts;
			}
			
			int tot_load = 0;
			for(int k = 0; k<partitionedInRoads.size(); k++) {
				tot_load += partitionWeights.get(k) +  ContextCreator.partitioner.getBackgroundLoad(k);
			}
			
			
			// Post-processing, if some partition has extremely high loads, divide the largest partition into two parts.
			int max_iter = 3;
			
			for(int it = 0; it<max_iter; it++) {
				for(int k = 0; k<partitionedInRoads.size(); k++) {
					if(partitionWeights.get(k) +  ContextCreator.partitioner.getBackgroundLoad(k) < tot_load/nparts/2) {
						int j = 0;
						int max_load = 0;
						for(int h=0; h<partitionedInRoads.size();h++) {
							if(partitionedInRoads.get(h).size()>10 && (max_load < partitionWeights.get(h) + ContextCreator.partitioner.getBackgroundLoad(h))) {
								max_load = partitionWeights.get(h) + ContextCreator.partitioner.getBackgroundLoad(h);
								j = h;
							}
						}
						int max_size = partitionedInRoads.get(j).size();
						// case 1: the load is enormous, we fully transfrom the load from j to k
						if(max_size>0) {
							if(ContextCreator.partitioner.getBackgroundLoad(j)> partitionWeights.get(j)/2) {
								for(int h = 0; h<max_size ; h++) {
									partitionedInRoads.get(k).add(partitionedInRoads.get(j).get(h));
									int edgeWeight = 1 + partitionedInRoads.get(j).get(h).getVehicleNum() * this.alpha + partitionedInRoads.get(j).get(h).getShadowVehicleNum() * this.beta
											+ partitionedInRoads.get(j).get(h).getFutureRoutingVehNum() * this.gamma;
									partitionWeights.set(k,
											partitionWeights.get(k) + edgeWeight);
								}
								partitionedInRoads.get(j).clear();
							}
							else {
								for(int h = 0; h<max_size/2 ; h++) {
									Road r = partitionedInRoads.get(j).get(partitionedInRoads.get(j).size()-1);
									partitionedInRoads.get(k).add(r);
									int edgeWeight = 1 + r.getVehicleNum() * this.alpha + r.getShadowVehicleNum() * this.beta
											+ r.getFutureRoutingVehNum() * this.gamma;
									partitionWeights.set(k,
											partitionWeights.get(k) + edgeWeight);
									
									partitionedInRoads.get(j).remove(partitionedInRoads.get(j).size()-1);
									partitionWeights.set(j,
											partitionWeights.get(j) - edgeWeight);
								}
							}
						}
					}
				}
			}
			
//			for(int k = 0; k<partitionedInRoads.size(); k++) {
//				System.out.println("Partition" + k + "," + partitionedInRoads.get(k).size() + "," + (partitionWeights.get(k) +  ContextCreator.partitioner.getBackgroundLoad(k)));
//			}
			

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
		// 2. Use a HashSet to make the removal instant (O(N) instead of O(N^2))
	    HashSet<Road> resolvedSet = new HashSet<>(this.ResolvedRoads);
	    this.LeftOverRoads.removeAll(resolvedSet);
	}

	/*
	 * ZH: Get the 2D array list that contains all roads that fall entirely in each
	 * partition
	 */
	public ArrayList<ArrayList<Road>> getPartitionedInRoads() {
		// Value and index for the partition with minimum total edge weight
		return this.partitionedInRoads;
	}

	/* Get the array list that contains the boundary roads */
	public ArrayList<Road> getPartitionedBwRoads() {
	    return this.partitionedBwRoads;
	}

	/* Get the total edge weight for each partition */
	public ArrayList<Integer> getPartitionWeights() {
		return this.partitionWeights;
	}

	@Override
	public void projectionEventOccurred(ProjectionEvent<T> evt) {
		// TODO Auto-generated method stub
	}

	public int getNodeNum() {
		return nodeNum;
	}

}
