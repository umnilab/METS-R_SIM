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

File: FMTwoWayRefiner.java 

*/



package evacSim.partition;

import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;

import java.util.Arrays;

import util.fn.Lambda2Void;

/**
 * Boundary KL Refinement
 */

public class FMTwoWayRefiner {

	final static UpdateNeighborClosure updateNeighborClosure=new UpdateNeighborClosure();

	private static final class UpdateNeighborClosure implements Lambda2Void<GNode<MetisNode>,GNode<MetisNode>> {
		private MetisGraph metisGraph;
		private GNode<MetisNode> higain;
		private int to;

		private void setParameters(MetisGraph metisGraph, GNode<MetisNode> higain, int to) {
			this.metisGraph = metisGraph;
			this.higain = higain;
			this.to = to;
		}		
		public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
			MetisNode neighborData = neighbor.getData();
			int edgeWeight = (int)metisGraph.getGraph().getEdgeData(higain, neighbor);
			int kwgt = (to == neighborData.getPartition() ? edgeWeight : -edgeWeight);
			neighborData.setEdegree(neighborData.getEdegree() - kwgt);
			neighborData.setIdegree(neighborData.getIdegree() + kwgt);
			neighborData.updateGain();

			if (neighborData.isBoundary() && neighborData.getEdegree() == 0) {
				metisGraph.unsetBoundaryNode(neighbor);
			}
			if (!neighborData.isBoundary() && neighborData.getEdegree() > 0) {
				metisGraph.setBoundaryNode(neighbor);
			}
		}
	}

	private final static PQueue[] parts = new PQueue[2];

	public static void fmTwoWayEdgeRefine(MetisGraph metisGraph, int[] tpwgts, int npasses) {
		IntGraph<MetisNode> graph = metisGraph.getGraph();
		int numNodes = graph.size();
		@SuppressWarnings("unchecked")
		GNode<MetisNode>[] swaps = new GNode[numNodes];

		int[] moved = new int[numNodes];

		parts[0] = new PQueue(numNodes, metisGraph.getMaxAdjSum());
		parts[1] = new PQueue(numNodes, metisGraph.getMaxAdjSum());

		int limit = Math.min(Math.max((int) (0.01 * numNodes), 15), 100);
		int totalWeight = metisGraph.getPartWeight(0) + metisGraph.getPartWeight(1);

		int avgvwgt = Math.min(totalWeight, 2 * totalWeight / numNodes);
		Arrays.fill(moved, -1);

		int origdiff = Math.abs(tpwgts[0] - metisGraph.getPartWeight(0));
		for (int pass = 0; pass < npasses; pass++) {
			parts[0].reset();
			parts[1].reset();
			int newcut = metisGraph.getMinCut();
			int mincut = newcut;
			int initcut = newcut;

			for (GNode<MetisNode> boundaryNode : metisGraph.getBoundaryNodes()) {
				MetisNode boundaryNodeData = boundaryNode.getData();
				boundaryNodeData.updateGain();
				parts[boundaryNodeData.getPartition()].insert(boundaryNode, boundaryNodeData.getGain());
			}

			int mindiff = Math.abs(tpwgts[0] - metisGraph.getPartWeight(0));

			int mincutorder = -1;
			int nswaps = 0;
			for (; nswaps < numNodes; nswaps++) {
				int from = 1;
				int to = 0;
				if (tpwgts[0] - metisGraph.getPartWeight(0) < tpwgts[1] - metisGraph.getPartWeight(1)) {
					from = 0;
					to = 1;
				}

				GNode<MetisNode> higain = parts[from].getMax();

				if (higain == null) {
					break;
				}
				MetisNode higainData = higain.getData();
				newcut -= higainData.getGain();

				metisGraph.incPartWeight(from, -higainData.getWeight());
				metisGraph.incPartWeight(to, higainData.getWeight());

				if ((newcut < mincut && Math.abs(tpwgts[0] - metisGraph.getPartWeight(0)) <= origdiff + avgvwgt)
						|| (newcut == mincut && Math.abs(tpwgts[0] - metisGraph.getPartWeight(0)) < mindiff)) {

					mincut = newcut;
					mindiff = Math.abs(tpwgts[0] - metisGraph.getPartWeight(0));
					mincutorder = nswaps;
				} else if (nswaps - mincutorder > limit) { /* We hit the limit, undo last move */
					newcut += (higainData.getEdegree() - higainData.getIdegree());
					metisGraph.incPartWeight(from, higainData.getWeight());
					metisGraph.incPartWeight(to, -higainData.getWeight());
					break;
				}
				moveNode(metisGraph, higain, to, moved, swaps, nswaps);
			}

			/* roll back computations */
			for (int i = 0; i < nswaps; i++) {
				moved[swaps[i].getData().getNodeId()] = -1; /* reset moved array */
			}
			nswaps--;
			for (; nswaps > mincutorder; nswaps--) {       
				moveBackNode(metisGraph, swaps[nswaps]);
			}
			metisGraph.setMinCut(mincut);
			if (mincutorder == -1 || mincut == initcut) {
				break;
			}
		}
	}

	private static void moveNode(final MetisGraph metisGraph, final GNode<MetisNode> higain, final int to, final int[] moved,
			GNode<MetisNode>[] swaps, int nswaps) {
		final IntGraph<MetisNode> graph = metisGraph.getGraph();
		MetisNode higainData = higain.getData();
		higainData.setPartition(to);
		moved[higainData.getNodeId()] = nswaps;
		swaps[nswaps] = higain;

		/* Update the id[i]/ed[i] values of the affected nodes */
		higainData.swapEDAndID();
		higainData.updateGain();

		if (higainData.getEdegree() == 0 && graph.outNeighborsSize(higain) >0) {
			metisGraph.unsetBoundaryNode(higain);
		}
		higain.map(new Lambda2Void<GNode<MetisNode>, GNode<MetisNode>>() {
			public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
				MetisNode neighborData = neighbor.getData();
				int oldgain = neighborData.getGain();
				int edgeWeight = (int) graph.getEdgeData(higain, neighbor);
				int kwgt = (to == neighborData.getPartition() ? edgeWeight : -edgeWeight);
				neighborData.setEdegree(neighborData.getEdegree() - kwgt);
				neighborData.setIdegree(neighborData.getIdegree() + kwgt);
				neighborData.updateGain();

				/* Update its boundary information and queue position */
				if (neighborData.isBoundary()) { /* If k was a boundary node */
					if (neighborData.getEdegree() == 0) { 
					 /*
					  * Not a boundary node any more
					  */
						metisGraph.unsetBoundaryNode(neighbor);
						if (moved[neighborData.getNodeId()] == -1) {
						/*
						 * Remove it if in the queues
						 */
							parts[neighborData.getPartition()].delete(neighbor, oldgain);
						}
					} else if (moved[neighborData.getNodeId()] == -1) {
						/* If it has not been moved, update its position in the queue */
						parts[neighborData.getPartition()].update(neighbor, oldgain, neighborData.getGain());
					}
				} else if (neighborData.getEdegree() > 0) { /*
				 * It will now become a boundary node
				 */
					metisGraph.setBoundaryNode(neighbor);
					if (moved[neighborData.getNodeId()] == -1) {
						parts[neighborData.getPartition()].insert(neighbor, neighborData.getGain());
					}
				}
			}
		}, higain);
	}

	private static void moveBackNode(final MetisGraph metisGraph, final GNode<MetisNode> higain) {
		IntGraph<MetisNode> graph = metisGraph.getGraph();
		MetisNode higainData = higain.getData();

		final int to = (higainData.getPartition() + 1) % 2;
		higainData.setPartition(to);
		higainData.swapEDAndID();
		higainData.updateGain();

		if (higainData.getEdegree() == 0 && higainData.isBoundary() && graph.outNeighborsSize(higain)>0) {
			metisGraph.unsetBoundaryNode(higain);
		} else if (higainData.getEdegree() > 0 && !higainData.isBoundary()) {
			metisGraph.setBoundaryNode(higain);
		}

		metisGraph.incPartWeight((to + 1) % 2, -higainData.getWeight());
		metisGraph.incPartWeight(to, higainData.getWeight());

		updateNeighborClosure.setParameters(metisGraph, higain, to);
		higain.map(updateNeighborClosure, higain);
	}
}
