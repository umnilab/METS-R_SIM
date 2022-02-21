/*
Galois, a framework to exploit amorphous data-parallelism in irregular
programs.

Copyright (C) 2010, The University of Texas at Austin. All rights reserved.
UNIVERSITY EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES CONCERNING THIS SOFTWARE
AND DOCUMENTATION, INCLUDING ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR ANY
PARTICULAR PURPOSE, NON-INFRINGEMENT AND WARRANTIES OF PERFORMANCE, AND ANY
WARRANTY THAT MIGHT OTHERWISE ARISE FROM COURSE OF DEALING OR USAGE OF TRADE.
NO WARRANTY IS EITHER EXPRESS OR IMPLIED WITH RESPECT TO THE USE OF THE
SOFTWARE OR DOCUMENTATION. Under no circumstances shall University be liable
for incidental, special, indirect, direct or consequential damages or loss of
profits, interruption of business, or related expenses which may arise from use
of Software or Documentation, including but not limited to those resulting from
defects in Software and/or Documentation, or loss or inaccuracy of data of any
kind.

File: Balancer.java 

*/



package evacSim.partition;

import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import java.util.Arrays;
import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

public class Balancer {

	/**
	 * a blancing algorithm for bisection 
	 * @param metisGraph the graph to balance
	 * @param tpwgts the lowerbounds of weights for the two partitions
	 */
	public static void balanceTwoWay(MetisGraph metisGraph, int[] tpwgts) {
		int pwgts0 = metisGraph.getPartWeight(0);
		int pwgts1 = metisGraph.getPartWeight(1);

		int mindiff = Math.abs(tpwgts[0] - pwgts0);
		if (mindiff < 3 * (pwgts0 + pwgts1) / metisGraph.getGraph().size()) {
			return;
		}
		if (pwgts0 > tpwgts[0] && pwgts0 < (int) (PMetis.UB_FACTOR * tpwgts[0])) {
			return;
		}
		if (pwgts1 > tpwgts[1] && pwgts1 < (int) (PMetis.UB_FACTOR * tpwgts[1])) {
			return;
		}

		if (metisGraph.getNumOfBoundaryNodes() > 0) {
			boundaryTwoWayBalance(metisGraph, tpwgts);
		} else {
			generalTwoWayBalance(metisGraph, tpwgts);
		}
	}

	private static void boundaryTwoWayBalance(final MetisGraph metisGraph, int[] tpwgts) {

		final IntGraph<MetisNode> graph = metisGraph.getGraph();
		int numNodes = graph.size();
		final int[] moved = new int[numNodes];
		Arrays.fill(moved, -1);
		final int mindiff = Math.abs(tpwgts[0] - metisGraph.getPartWeight(0));
		int from = 0;
		int to = 1;
		if (metisGraph.getPartWeight(0) < tpwgts[0]) {
			from = 1;
			to = 0;
		}

		final PQueue queue = new PQueue(numNodes, metisGraph.getMaxAdjSum());

		for (GNode<MetisNode> boundaryNode : metisGraph.getBoundaryNodes()) {
			MetisNode boundaryNodeData = boundaryNode.getData();
			boundaryNodeData.updateGain();
			if (boundaryNodeData.getPartition() == from && boundaryNodeData.getWeight() <= mindiff) {
				queue.insert(boundaryNode, boundaryNodeData.getGain());
			}
		}

		int mincut = metisGraph.getMinCut();
		for (int nswaps = 0; nswaps < numNodes; nswaps++) {
			final GNode<MetisNode> higain = queue.getMax();
			if (higain == null)
				break;
			MetisNode higainData = higain.getData();
			if (metisGraph.getPartWeight(to) + higainData.getWeight() > tpwgts[to]) {
				break;
			}
			mincut -= (higainData.getEdegree() - higainData.getIdegree());
			metisGraph.incPartWeight(from, -higainData.getWeight());
			metisGraph.incPartWeight(to, higainData.getWeight());

			higainData.setPartition(to);
			moved[higainData.getNodeId()] = nswaps;

			/* Update the id[i]/ed[i] values of the affected nodes */
			higainData.swapEDAndID();
			higainData.updateGain();
			if (higainData.getEdegree() == 0 && graph.outNeighborsSize(higain)!=0) {
				metisGraph.unsetBoundaryNode(higain);
			}
			final int toConstant=to;
			final int fromConstant=from;
			higain.map(new Lambda2Void<GNode<MetisNode>,GNode<MetisNode>>(){
				public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
					MetisNode neighborData = neighbor.getData();
					int oldgain = neighborData.getGain();
					int edgeWeight = (int)graph.getEdgeData(higain, neighbor);
					int kwgt = (toConstant == neighborData.getPartition() ? edgeWeight : -edgeWeight);
					neighborData.setEdegree(neighborData.getEdegree() - kwgt);
					neighborData.setIdegree(neighborData.getIdegree() + kwgt);
					neighborData.updateGain();

					/* Update its boundary information and queue position */
					if (neighborData.isBoundary()) { /* If k was a boundary vertex */
						if (neighborData.getEdegree() == 0) { /* Not a boundary vertex any more */
							metisGraph.unsetBoundaryNode(neighbor);
							/* Remove it if in the queues */
							if (moved[neighborData.getNodeId()] == -1 && neighborData.getPartition() == fromConstant
									&& neighborData.getWeight() <= mindiff) {
								queue.delete(neighbor, oldgain);
							}
						} else if (moved[neighborData.getNodeId()] == -1 && neighborData.getPartition() == fromConstant
								&& neighborData.getWeight() <= mindiff) {
							/* If it has not been moved, update its position in the queue */
							queue.update(neighbor, oldgain, neighborData.getGain());
						}
					} else if (neighborData.getEdegree() > 0) { /* It will now become a boundary vertex */
						metisGraph.setBoundaryNode(neighbor);
						if (moved[neighborData.getNodeId()] == -1 && neighborData.getPartition() == fromConstant
								&& neighborData.getWeight() <= mindiff) {
							queue.insert(neighbor, neighborData.getGain());
						}
					}
				}      	
			}, higain);
		}
		metisGraph.setMinCut(mincut);
	}

	private static void generalTwoWayBalance(final MetisGraph metisGraph, int[] tpwgts) {
		final IntGraph<MetisNode> graph = metisGraph.getGraph();
		int numNodes = graph.size();
		final int[] moved = new int[numNodes];

		final int mindiff = Math.abs(tpwgts[0] - metisGraph.getPartWeight(0));
		int from = 0;
		int to = 1;
		if (metisGraph.getPartWeight(0) < tpwgts[0]) {
			from = 1;
			to = 0;
		}

		final PQueue queue = new PQueue(numNodes, metisGraph.getMaxAdjSum());

		Arrays.fill(moved, -1);
		final int fromConstant=from;  
		/* Insert boundary nodes in the priority queues */
		graph.map(new LambdaVoid<GNode<MetisNode>>(){
			@Override
			public void call(GNode<MetisNode> node) {
				MetisNode nodeData = node.getData();
				int part = nodeData.getPartition();
				if (part == fromConstant && nodeData.getWeight() <= mindiff) {
					queue.insert(node, nodeData.getGain());
				}
			}
		});

		int mincut = metisGraph.getMinCut();

		for (int nswaps = 0; nswaps < numNodes; nswaps++) {
			final GNode<MetisNode> higain = queue.getMax();
			if (higain == null)
				break;
			MetisNode higainData = higain.getData();
			if (metisGraph.getPartWeight(to) + higainData.getWeight() > tpwgts[to]) {
				break;
			}
			mincut -= (higainData.getEdegree() - higainData.getIdegree());
			metisGraph.incPartWeight(from, -higainData.getWeight());
			metisGraph.incPartWeight(to, higainData.getWeight());

			higainData.setPartition(to);
			moved[higainData.getNodeId()] = nswaps;

			/* Update the id[i]/ed[i] values of the affected nodes */
			higainData.swapEDAndID();

			if (higainData.getEdegree() == 0 && higainData.isBoundary() && graph.outNeighborsSize(higain)!=0) {
				metisGraph.unsetBoundaryNode(higain);
			}
			if (higainData.getEdegree() > 0 && !higainData.isBoundary()) {
				metisGraph.setBoundaryNode(higain);
			}
			final int toConstant=to;
			higain.map(new Lambda2Void<GNode<MetisNode>,GNode<MetisNode>>(){
				@Override
				public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
					MetisNode neighborData = neighbor.getData();
					int oldgain = neighborData.getGain();
					int edgeWeight = (int)graph.getEdgeData(higain, neighbor);
					int kwgt = (toConstant == neighborData.getPartition() ? edgeWeight : -edgeWeight);
					neighborData.setEdegree(neighborData.getEdegree() - kwgt);
					neighborData.setIdegree(neighborData.getIdegree() + kwgt);
					neighborData.updateGain();
					/* Update the queue position */
					if (moved[neighborData.getNodeId()] == -1 && neighborData.getPartition() == fromConstant
							&& neighborData.getWeight() <= mindiff) {
						queue.update(neighbor, oldgain, neighborData.getGain());
					}
					/* Update its boundary information */
					if (neighborData.getEdegree() == 0 && neighborData.isBoundary()) {
						metisGraph.unsetBoundaryNode(neighbor);
					} else if (neighborData.getEdegree() > 0 && !neighborData.isBoundary()) {
						metisGraph.setBoundaryNode(neighbor);
					}
				}      	
			}, higain);
		}
		metisGraph.setMinCut(mincut);
	}

	public static void greedyKWayEdgeBalance(final MetisGraph metisGraph, int nparts, float[] tpwgts, float ubfactor,
			int npasses) {

		int[] minwgts = new int[nparts];
		int[] maxwgts = new int[nparts];
		int[] itpwgts = new int[nparts];
		int tvwgt = 0;
		for (int i = 0; i < nparts; i++) {
			tvwgt += metisGraph.getPartWeight(i);
		}
		for (int i = 0; i < nparts; i++) {
			itpwgts[i] = (int) (tpwgts[i] * tvwgt);
			maxwgts[i] = (int) (tpwgts[i] * tvwgt * ubfactor);
			minwgts[i] = (int) (tpwgts[i] * tvwgt * (1.0 / ubfactor));
		}
		final IntGraph<MetisNode> graph = metisGraph.getGraph();

		final PQueue queue = new PQueue(graph.size(), metisGraph.getMaxAdjSum());

		for (int pass = 0; pass < npasses; pass++) {
			int i = 0;
			for (; i < nparts; i++) {
				if (metisGraph.getPartWeight(i) > maxwgts[i]) {
					break;
				}
			}
			if (i == nparts)
				break;
			queue.reset();
			final int[] moved = new int[graph.size()];
			Arrays.fill(moved, -1);
			for (GNode<MetisNode> boundaryNode : metisGraph.getBoundaryNodes()) {
				MetisNode boundaryNodeData = boundaryNode.getData();
				boundaryNodeData.updateGain();
				queue.insert(boundaryNode, boundaryNodeData.getGain());
				moved[boundaryNodeData.getNodeId()] = 2;
			}

			while (true) {
				final GNode<MetisNode> higain = queue.getMax();
				if (higain == null)
					break;
				MetisNode higainData = higain.getData();
				moved[higainData.getNodeId()] = 1;
				final int from = higainData.getPartition();
				if (metisGraph.getPartWeight(from) - higainData.getWeight() < minwgts[from])
					continue; /* This cannot be moved! */
				int k = 0;
				for (; k < higainData.getNDegrees(); k++) {
					int to = higainData.partIndex[k];
					if (metisGraph.getPartWeight(to) + higainData.getWeight() <= maxwgts[to]
					                                                                     || itpwgts[from] * (metisGraph.getPartWeight(to) + higainData.getWeight()) <= itpwgts[to]
					                                                                                                                                                           * metisGraph.getPartWeight(from))
						break;
				}
				if (k == higainData.getNDegrees())
					continue; /* break out if you did not find a candidate */

				for (int j = k + 1; j < higainData.getNDegrees(); j++) {
					int to = higainData.partIndex[j];
					if (itpwgts[higainData.partIndex[k]] * metisGraph.getPartWeight(to) < itpwgts[to]
					                                                                              * metisGraph.getPartWeight(higainData.partIndex[k]))
						k = j;
				}

				final int to = higainData.partIndex[k];

				if (metisGraph.getPartWeight(from) < maxwgts[from] && metisGraph.getPartWeight(to) > minwgts[to]
				                                                                                             && higainData.partEd[k] - higainData.getIdegree() < 0)
					continue;

				/*=====================================================================
				 * If we got here, we can now move the vertex from 'from' to 'to' 
				 *======================================================================*/

				metisGraph.setMinCut(metisGraph.getMinCut() - (higainData.partEd[k] - higainData.getIdegree()));

				/* Update where, weight, and ID/ED information of the vertex you moved */
				higainData.setPartition(to);
				metisGraph.incPartWeight(to, higainData.getWeight());
				metisGraph.incPartWeight(from, -higainData.getWeight());
				higainData.setEdegree(higainData.getEdegree() - higainData.partEd[k] + higainData.getIdegree());
				int temp = higainData.partEd[k];
				higainData.partEd[k] = higainData.getIdegree();
				higainData.setIdegree(temp);

				if (higainData.partEd[k] == 0) {
					higainData.setNDegrees(higainData.getNDegrees() - 1);
					higainData.partEd[k] = higainData.partEd[higainData.getNDegrees()];
					higainData.partIndex[k] = higainData.partIndex[higainData.getNDegrees()];
				} else {
					higainData.partIndex[k] = from;
				}

				if (higainData.getEdegree() == 0) {
					metisGraph.unsetBoundaryNode(higain);
				}

				/* Update the degrees of adjacent vertices */
				higain.map(new Lambda2Void<GNode<MetisNode>,GNode<MetisNode>>(){
					@Override
					public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
						MetisNode neighborData = neighbor.getData();
						int oldgain = neighborData.getGain();
						if (neighborData.partEd == null) {
							int numEdges = neighborData.getNumEdges();
							neighborData.partIndex = new int[numEdges];
							neighborData.partEd = new int[numEdges];
						}
						int edgeWeight = (int)graph.getEdgeData(higain, neighbor);
						if (neighborData.getPartition() == from) {
							neighborData.setEdegree(neighborData.getEdegree() + edgeWeight);
							neighborData.setIdegree(neighborData.getIdegree() - edgeWeight);
							if (neighborData.getEdegree() - neighborData.getIdegree() > 0 && !neighborData.isBoundary())
								metisGraph.setBoundaryNode(neighbor);
						} else if (neighborData.getPartition() == to) {
							neighborData.setEdegree(neighborData.getEdegree() - edgeWeight);
							neighborData.setIdegree(neighborData.getIdegree() + edgeWeight);
							if (neighborData.getEdegree() - neighborData.getIdegree() == 0 && neighborData.isBoundary())
								metisGraph.unsetBoundaryNode(neighbor);
						}

						/* Remove contribution from the .ed of 'from' */
						if (neighborData.getPartition() != from) {
							for (int k = 0; k < neighborData.getNDegrees(); k++) {
								if (neighborData.partIndex[k] == from) {
									if (neighborData.partEd[k] == edgeWeight) {
										neighborData.setNDegrees(neighborData.getNDegrees() - 1);
										neighborData.partEd[k] = neighborData.partEd[neighborData.getNDegrees()];
										neighborData.partIndex[k] = neighborData.partIndex[neighborData.getNDegrees()];
									} else {
										neighborData.partEd[k] -= edgeWeight;
									}
									break;
								}
							}
						}

						/*
						 * add contribution to the .ed of 'to'
						 */

						if (neighborData.getPartition() != to) {
							int k;
							for (k = 0; k < neighborData.getNDegrees(); k++) {
								if (neighborData.partIndex[k] == to) {
									neighborData.partEd[k] += edgeWeight;
									break;
								}
							}
							if (k == neighborData.getNDegrees()) {
								int nd = neighborData.getNDegrees();
								neighborData.partIndex[nd] = to;
								neighborData.partEd[nd++] = edgeWeight;
								neighborData.setNDegrees(nd);
							}
						}

						/* Update the queue */
						if (neighborData.getPartition() == from || neighborData.getPartition() == to) {
							neighborData.updateGain();
							if (moved[neighborData.getNodeId()] == 2) {
								if (neighborData.getEdegree() > 0) {
									queue.update(neighbor, oldgain, neighborData.getGain());
								} else {
									queue.delete(neighbor, oldgain);
									moved[neighborData.getNodeId()] = -1;
								}
							} else if (moved[neighborData.getNodeId()] == -1 && neighborData.getEdegree() > 0) {
								queue.insert(neighbor, neighborData.getGain());
								moved[neighborData.getNodeId()] = 2;
							}
						}
					}        	
				}, higain);
			}
		}
	}
}
