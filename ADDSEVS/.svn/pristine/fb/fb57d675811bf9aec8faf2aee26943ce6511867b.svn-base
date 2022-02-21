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

File: HEMatchingStrategy.java 

*/



package evacSim.partition;

import galois.objects.MethodFlag;
import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

/**
 * Finding a maximal matching that contains edges with large weight,
 * so in the partition phase, the bisection will be performed on small weight edge.
 * The vertices are visited in random order and match a vertex u with the neighbor vertex v
 * such that the weight of the edge (u, v) is maximum over all valid incident edges.
 */

public class HEMatchingStrategy implements LambdaVoid<GNode<MetisNode>> {
	private int maxVertexWeight;
	IntGraph<MetisNode> graph; 
	IntGraph<MetisNode> coarseGraph;
	public HEMatchingStrategy(IntGraph<MetisNode> graph, IntGraph<MetisNode> coarseGraph, int maxVertexWeight) {
		this.graph=graph;
		this.coarseGraph=coarseGraph;
		this.maxVertexWeight=maxVertexWeight;
	}

	public void call(GNode<MetisNode> node) {
		MetisNode nodeData = node.getData(MethodFlag.CHECK_CONFLICT,MethodFlag.NONE);
		if (nodeData.isMatched()) {
			return;
		}
		FindMaxUnmatchedNeighborClosure closure=new FindMaxUnmatchedNeighborClosure(node);
		node.map(closure, node, MethodFlag.CHECK_CONFLICT);
		GNode<MetisNode> match=closure.maxNode;
		MetisNode matchNodeData = match.getData(MethodFlag.NONE,MethodFlag.NONE);
		nodeData.setMatch(match);
		matchNodeData.setMatch(node);
		int weight = nodeData.getWeight();
		if (node != match) {
			weight += matchNodeData.getWeight();
		}

		MetisNode newNodeData = new MetisNode(weight);
		GNode<MetisNode> newNode = coarseGraph.createNode(newNodeData);
		coarseGraph.add(newNode);
		nodeData.setMapTo(newNode);
		matchNodeData.setMapTo(newNode);
	}

	class FindMaxUnmatchedNeighborClosure implements Lambda2Void<GNode<MetisNode>,GNode<MetisNode>>{
		int maxwgt;
		GNode<MetisNode> maxNode;
		GNode<MetisNode> temp;
		public FindMaxUnmatchedNeighborClosure(GNode<MetisNode> node){
			maxwgt=Integer.MIN_VALUE;
			maxNode=node;
			temp=node;
		}
		@Override
		public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
			assert node==temp;
			assert neighbor!=temp;
			MetisNode nodeData = node.getData(MethodFlag.NONE,MethodFlag.NONE);
			long edgeData = graph.getEdgeData(node, neighbor, MethodFlag.NONE);
			MetisNode neighborData = neighbor.getData(MethodFlag.NONE,MethodFlag.NONE);
			if (!neighborData.isMatched() && maxwgt < edgeData
					&& nodeData.getWeight() + neighborData.getWeight() <= maxVertexWeight) {
				maxwgt = neighborData.getWeight();
				maxNode = neighbor;
			}
		}
	}
}
