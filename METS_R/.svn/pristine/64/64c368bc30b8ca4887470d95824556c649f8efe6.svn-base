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

File: MetisNode.java 

*/



package evacSim.partition;

import galois.objects.AbstractNoConflictBaseObject;
import galois.objects.graph.GNode;

public class MetisNode extends AbstractNoConflictBaseObject{

	// used for matching phase
	private GNode<MetisNode> _match;

	private int _weight;

	private int _numEdges;
	//the sum of weights of its edges 
	private int _adjwgtsum;

	private int _partition;
	private boolean _isBoundary;

	// the node it maps to in the coarser graph  
	private GNode<MetisNode> _mapTo;
	private int _id;

	// the sum of the weights of its edges connecting neighbors in its partition
	private int _idegree;
	// the sum of the weights of its edges connecting neighbors in the other partition
	private int _edegree;
	// if moving this node to the other partition, the reduced cut  
	private int _gain;

	// the node it mapped in the subgraph got by bisecting the current graph 
	private GNode<MetisNode> subGraphMap;

	public MetisNode(int id, int weight) {
		_id = id;
		_weight = weight;
	}

	public MetisNode(int weight) {
		this(-1, weight);
	}

	public int getNodeId() {
		return _id;
	}

	public void setNodeId(int i) {
		_id = i;
	}

	public boolean isMatched() {
		return _match != null;
	}

	public GNode<MetisNode> getMatch() {
		return _match;
	}

	public void setMatch(GNode<MetisNode> node) {
		_match = node; //only set itself
	}

	public int getWeight() {
		return _weight;
	}

	public void setWeight(int weight) {
		_weight = weight;
	}

	public int getAdjWgtSum() {
		return _adjwgtsum;
	}

	public void addEdgeWeight(int weight) {
		_adjwgtsum += weight;
	}

	public void setAdjWgtSum(int sum) {
		_adjwgtsum = sum;
	}

	public int getPartition() {
		return _partition;
	}

	public void setPartition(int part) {
		_partition = part;
	}

	public boolean isBoundary() {
		return _isBoundary;
	}

	public void setBoundary(boolean isBoundary) {
		_isBoundary = isBoundary;
	}

	public void setMapTo(GNode<MetisNode> mapTo) {
		_mapTo = mapTo;
	}

	public GNode<MetisNode> getMapTo() {
		return _mapTo;
	}

	public int getIdegree() {
		return _idegree;
	}

	public void setIdegree(int idegree) {
		_idegree = idegree;
	}

	public int getEdegree() {
		return _edegree;
	}

	public void setEdegree(int edegree) {
		_edegree = edegree;
	}

	public void swapEDAndID() {
		int temp = _idegree;
		_idegree = _edegree;
		_edegree = temp;
	}

	public int getGain() {
		return _gain;
	}

	public void updateGain() {
		_gain = _edegree - _idegree;
	}

	public void setSubGraphMap(GNode<MetisNode> map) {
		subGraphMap = map;
	}

	public GNode<MetisNode> getSubGraphMap() {
		return subGraphMap;
	}

	//for kway partitioning
	int ndgrees = 0;

	public int getNDegrees() {
		return ndgrees;
	}

	public void setNDegrees(int degrees) {
		ndgrees = degrees;
	}

	public int getNumEdges() {
		return _numEdges;
	}

	public void incNumEdges() {
		_numEdges++;
	}

	public int[] partEd;
	public int[] partIndex;
	
  @Override
  public Object gclone() {
	  //cautious guarantee no undo is needed here
  	return null;
  }

  @Override
  public void restoreFrom(Object copy) {
	  //cautious guarantee no undo is needed here
  }

}
