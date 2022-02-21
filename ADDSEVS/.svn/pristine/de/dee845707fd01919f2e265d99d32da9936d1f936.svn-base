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

File: Refiner.java 

*/



package evacSim.partition;

import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

/**
 * Refiner for PMetis
 */
public class Refiner {

	public static void refineTwoWay(MetisGraph metisGraph, MetisGraph orgGraph, int[] tpwgts) {

		metisGraph.computeTwoWayPartitionParams();
		while (!metisGraph.equals(orgGraph)) {
			Balancer.balanceTwoWay(metisGraph, tpwgts);
			FMTwoWayRefiner.fmTwoWayEdgeRefine(metisGraph, tpwgts, 8);
			projectTwoWayPartition(metisGraph);
			metisGraph = metisGraph.getFinerGraph();   
		}
		Balancer.balanceTwoWay(metisGraph, tpwgts);
		FMTwoWayRefiner.fmTwoWayEdgeRefine(metisGraph, tpwgts, 8);
	}

	/**
	 * project the partitioning information back the finer graph
	 */
	private static void projectTwoWayPartition(final MetisGraph metisGraph) {

		final MetisGraph finer = metisGraph.getFinerGraph();
		finer.setMinCut(metisGraph.getMinCut());
		finer.initPartWeight();
		finer.setPartWeight(0, metisGraph.getPartWeight(0));
		finer.setPartWeight(1, metisGraph.getPartWeight(1));
		final IntGraph<MetisNode> finerGraph = finer.getGraph();

		finerGraph.map(new LambdaVoid<GNode<MetisNode>>(){
			public void call(GNode<MetisNode> node) {
				MetisNode nodeData = node.getData();
				nodeData.setPartition(nodeData.getMapTo().getData().getPartition());
				nodeData.setEdegree(0);
				nodeData.setIdegree(0);
				finer.unsetBoundaryNode(node);
			}
		});

		finerGraph.map(new LambdaVoid<GNode<MetisNode>>(){
			public void call(GNode<MetisNode> node) {
				final MetisNode nodeData = node.getData();
				nodeData.setIdegree(nodeData.getAdjWgtSum());
				if (finerGraph.outNeighborsSize(node)!=0 && nodeData.getMapTo().getData().isBoundary()) {
					node.map(new Lambda2Void<GNode<MetisNode>,GNode<MetisNode>> (){
						public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
							MetisNode neighborData = neighbor.getData();
							if (nodeData.getPartition() != neighborData.getPartition()) {
								nodeData.setEdegree(nodeData.getEdegree() + (int)finerGraph.getEdgeData(node, neighbor));
							}							
						}	        	
					}, node);
				}
				if (finerGraph.outNeighborsSize(node)!=0) {
					nodeData.setIdegree(nodeData.getIdegree() - nodeData.getEdegree());
				}
				if (finerGraph.outNeighborsSize(node)==0 || nodeData.getEdegree() > 0) {
					finer.setBoundaryNode(node);
				}
			}
		});

	}

}
