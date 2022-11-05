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

File: RMMatchingStrategy.java 

*/



package evacSim.partition;

import galois.objects.MethodFlag;
import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

/**
 * Random matching algorithm
 */
public class RMMatchingStrategy implements LambdaVoid<GNode<MetisNode>> {
	private int maxVertexWeight;
	IntGraph<MetisNode> graph;
	IntGraph<MetisNode> coarseGraph;

	public RMMatchingStrategy(IntGraph<MetisNode> graph, IntGraph<MetisNode> coarseGraph, int maxVertexWeight) {
		this.coarseGraph=coarseGraph;
		this.graph=graph;
		this.maxVertexWeight=maxVertexWeight;
	}

	@Override
	public void call(GNode<MetisNode> node) {
		MetisNode nodeData = node.getData(MethodFlag.CHECK_CONFLICT, MethodFlag.NONE);
		if (nodeData.isMatched()) {
			return;
		}
		FindMatchNodeClosure closure=new FindMatchNodeClosure(node);
		node.map(closure, node, MethodFlag.CHECK_CONFLICT);
		GNode<MetisNode> matchNode = closure.match;
		MetisNode maxNodeData = matchNode.getData(MethodFlag.NONE, MethodFlag.NONE);
		nodeData.setMatch(matchNode);
		maxNodeData.setMatch(node);
		int weight = nodeData.getWeight();
		if (node != matchNode) {
			weight += maxNodeData.getWeight();
		}
		MetisNode newNodeData = new MetisNode(weight);
		GNode<MetisNode> newNode = coarseGraph.createNode(newNodeData);
		coarseGraph.add(newNode);
		nodeData.setMapTo(newNode);
		maxNodeData.setMapTo(newNode);		
	}

	private class FindMatchNodeClosure implements Lambda2Void<GNode<MetisNode>,GNode<MetisNode>>{
		GNode<MetisNode> match;
		public FindMatchNodeClosure(GNode<MetisNode> node){
			match = node;
		}
		@Override
		public void call(GNode<MetisNode> neighbor, GNode<MetisNode> src) {
			MetisNode neighMNode = neighbor.getData(MethodFlag.NONE, MethodFlag.NONE);
			if (!neighMNode.isMatched() && src.getData(MethodFlag.NONE, MethodFlag.NONE).getWeight() + neighMNode.getWeight() <= maxVertexWeight) {
				if (match == src)
					match = neighbor;
			}
		}
	}	
}
