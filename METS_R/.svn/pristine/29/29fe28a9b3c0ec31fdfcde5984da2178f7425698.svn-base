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

File: RandomKwayEdgeRefiner.java 

*/



package evacSim.partition;

import galois.objects.MethodFlag;
import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import galois.runtime.ForeachContext;
import galois.runtime.GaloisRuntime;
import galois.runtime.wl.Priority;
import galois.runtime.wl.RandomPermutation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

/**
 * an random refine algortihm for k-way partitions
 */
public class RandomKwayEdgeRefiner {

  private float[] tpwgts;
  private float ubfactor;
  private float npasses;
  private int ffactor;
  private int nparts;
  private int[] minwgts;
  private int[] maxwgts;
  private int[] itpwgts;

  public RandomKwayEdgeRefiner(float[] tpwgts, int nparts, float ubfactor, int npasses, int ffactor) {    
    this.tpwgts = tpwgts;
    this.nparts = nparts;
    this.ubfactor = ubfactor;
    this.npasses = npasses;
    this.ffactor = ffactor;
    minwgts = new int[nparts];
    maxwgts = new int[nparts];
    itpwgts = new int[nparts];
  }

  public void refine(final MetisGraph metisGraph) throws ExecutionException {

    int tvwgt = 0;
    for (int i = 0; i < nparts; i++) {
      tvwgt += metisGraph.getPartWeight(i);
    }
    for (int i = 0; i < nparts; i++) {
      itpwgts[i] = (int) (tpwgts[i] * tvwgt);
      maxwgts[i] = (int) (tpwgts[i] * tvwgt * ubfactor);
      minwgts[i] = (int) (tpwgts[i] * tvwgt * (1.0 / ubfactor));
    }

    for (int pass = 0; pass < npasses; pass++) {
      int oldcut = metisGraph.getMinCut();
      Set<GNode<MetisNode>> boundary = new LinkedHashSet<GNode<MetisNode>>();
      boundary.addAll(metisGraph.getBoundaryNodes());
      
      GaloisRuntime.foreach(boundary, new Lambda2Void<GNode<MetisNode>, ForeachContext<GNode<MetisNode>>>() {          
      	@Override
      	public void call(GNode<MetisNode> node, ForeachContext<GNode<MetisNode>> ctx) {
      		refineOneNode(metisGraph, node);
      	}
      }, Priority.first(RandomPermutation.class));

      if (metisGraph.getMinCut() == oldcut) {
        break;
      }
    }
  }

  private void refineOneNode(final MetisGraph metisGraph, GNode<MetisNode> n) {
  	final IntGraph<MetisNode> graph = metisGraph.getGraph();
    MetisNode nodeData = n.getData(MethodFlag.CHECK_CONFLICT, MethodFlag.NONE);
    if (nodeData.getEdegree() >= nodeData.getIdegree()) {
      final int from = nodeData.getPartition();
      int from_weight=metisGraph.getPartWeight(from, MethodFlag.CHECK_CONFLICT);
      int vwgt = nodeData.getWeight();
      if (nodeData.getIdegree() > 0 && from_weight - vwgt < minwgts[from])
        return;
      int k = 0;
      int to = 0;
      long id = nodeData.getIdegree();
      for (k = 0; k < nodeData.getNDegrees(); k++) {
        long gain = nodeData.partEd[k] - id;
        if (gain < 0)
          continue;
        to = nodeData.partIndex[k];

        if (metisGraph.getPartWeight(to, MethodFlag.CHECK_CONFLICT) + vwgt <= maxwgts[to] + ffactor * gain && gain >= 0)
          break;
      }
      if (k == nodeData.getNDegrees())
        return;
      for (int j = k + 1; j < nodeData.getNDegrees(); j++) {
        to = nodeData.partIndex[j];
        int to_weight=metisGraph.getPartWeight(to, MethodFlag.CHECK_CONFLICT);
        if ((nodeData.partEd[j] > nodeData.partEd[k] && to_weight + vwgt <= maxwgts[to])
            || nodeData.partEd[j] == nodeData.partEd[k]
            && itpwgts[nodeData.partIndex[k]] * to_weight < itpwgts[to]
                * metisGraph.getPartWeight(nodeData.partIndex[k], MethodFlag.CHECK_CONFLICT))
          k = j;
      }

      to = nodeData.partIndex[k];
      int to_weight=metisGraph.getPartWeight(to, MethodFlag.CHECK_CONFLICT);
      int j = 0;
      if (nodeData.partEd[k] - nodeData.getIdegree() > 0)
        j = 1;
      else if (nodeData.partEd[k] - nodeData.getIdegree() == 0) {
        if (from_weight >= maxwgts[from]
            || itpwgts[from] * (to_weight + vwgt) < itpwgts[to] * from_weight)
          j = 1;
      }
      if (j == 0)
        return;

      /*
       * if we got here, we can now move the vertex from 'from' to 'to'
       */
      //dummy for cautious
      if(!GaloisRuntime.getRuntime().useSerial()){
      	n.map(new LambdaVoid<GNode<MetisNode>>() {
      		@Override
      		public void call(GNode<MetisNode> arg0) {
      		}
      	}, MethodFlag.CHECK_CONFLICT);
      }
      metisGraph.incMinCut(-(nodeData.partEd[k] - nodeData.getIdegree()));
      nodeData.setPartition(to);
      metisGraph.incPartWeight(to, vwgt);
      metisGraph.incPartWeight(from, -vwgt);

      nodeData.setEdegree(nodeData.getEdegree() + nodeData.getIdegree() - nodeData.partEd[k]);
      int temp = nodeData.getIdegree();
      nodeData.setIdegree(nodeData.partEd[k]);
      nodeData.partEd[k] = temp;

      if (nodeData.partEd[k] == 0) {
        nodeData.setNDegrees(nodeData.getNDegrees() - 1);
        nodeData.partEd[k] = nodeData.partEd[nodeData.getNDegrees()];
        nodeData.partIndex[k] = nodeData.partIndex[nodeData.getNDegrees()];
      } else {
        nodeData.partIndex[k] = from;
      }

      if (nodeData.getEdegree() - nodeData.getIdegree() < 0)
        metisGraph.unsetBoundaryNode(n);

      /*
       * update the degrees of adjacent vertices
       */
      final int fromConst = from;
      final int toConst = to;
      n.map(new Lambda2Void<GNode<MetisNode>, GNode<MetisNode>>() {
        @Override
        public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
          MetisNode neighborData = neighbor.getData(MethodFlag.NONE, MethodFlag.NONE);
          if (neighborData.partEd == null) {
            int numEdges = neighborData.getNumEdges();
            neighborData.partIndex = new int[numEdges];
            neighborData.partEd = new int[numEdges];
          }
          int edgeWeight = graph.getEdgeData(node, neighbor, MethodFlag.NONE);
          if (neighborData.getPartition() == fromConst) {
            neighborData.setEdegree(neighborData.getEdegree() + edgeWeight);
            neighborData.setIdegree(neighborData.getIdegree() - edgeWeight);
            if (neighborData.getEdegree() - neighborData.getIdegree() >= 0 && !neighborData.isBoundary())
              metisGraph.setBoundaryNode(neighbor);
          } else if (neighborData.getPartition() == toConst) {
            neighborData.setEdegree(neighborData.getEdegree() - edgeWeight);
            neighborData.setIdegree(neighborData.getIdegree() + edgeWeight);
            if (neighborData.getEdegree() - neighborData.getIdegree() < 0 && neighborData.isBoundary())
              metisGraph.unsetBoundaryNode(neighbor);
          }
          /*Remove contribution from the .ed of 'from' */
          if (neighborData.getPartition() != fromConst) {
            for (int i = 0; i < neighborData.getNDegrees(); i++) {
              if (neighborData.partIndex[i] == fromConst) {
                if (neighborData.partEd[i] == edgeWeight) {
                  neighborData.setNDegrees(neighborData.getNDegrees() - 1);
                  neighborData.partEd[i] = neighborData.partEd[neighborData.getNDegrees()];
                  neighborData.partIndex[i] = neighborData.partIndex[neighborData.getNDegrees()];
                } else {
                  neighborData.partEd[i] -= edgeWeight;
                }
                break;
              }
            }
          }
          /*
           * add contribution to the .ed of 'to'
           */
          if (neighborData.getPartition() != toConst) {
            int i;
            for (i = 0; i < neighborData.getNDegrees(); i++) {
              if (neighborData.partIndex[i] == toConst) {
                neighborData.partEd[i] += edgeWeight;
                break;
              }
            }
            if (i == neighborData.getNDegrees()) {
              int nd = neighborData.getNDegrees();
              neighborData.partIndex[nd] = toConst;
              neighborData.partEd[nd++] = edgeWeight;
              neighborData.setNDegrees(nd);
            }
          }
        }
      }, n, MethodFlag.NONE);
    }
  }
}
