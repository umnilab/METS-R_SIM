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

File: KWayRefiner.java 

*/



package evacSim.partition;

import galois.objects.MethodFlag;
import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import galois.runtime.ForeachContext;
import galois.runtime.GaloisRuntime;
import galois.runtime.wl.Priority;
import galois.runtime.wl.RandomPermutation;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

/**
 * Refiner for KMetis
 */
public class KWayRefiner {

  public void refineKWay(MetisGraph metisGraph, MetisGraph orgGraph, float[] tpwgts, float ubfactor, int nparts)
      throws ExecutionException {
    metisGraph.computeKWayPartitionParams(nparts);
    int nlevels = 0;
    MetisGraph metisGraphTemp = metisGraph;
    while (!metisGraphTemp.equals(orgGraph)) {
      metisGraphTemp = metisGraphTemp.getFinerGraph();
      nlevels++;
    }
    int i = 0;
    RandomKwayEdgeRefiner rkRefiner = new RandomKwayEdgeRefiner(tpwgts, nparts, ubfactor, 10, 1);
    while (!metisGraph.equals(orgGraph)) {
      if (2 * i >= nlevels && !metisGraph.isBalanced(tpwgts, (float) 1.04 * ubfactor)) {
        metisGraph.computeKWayBalanceBoundary();
        Balancer.greedyKWayEdgeBalance(metisGraph, nparts, tpwgts, ubfactor, 8);
        metisGraph.computeKWayBoundary();
      }

      rkRefiner.refine(metisGraph);
      projectKWayPartition(metisGraph, nparts);
      metisGraph = metisGraph.getFinerGraph();
      i++;
    }
    if (2 * i >= nlevels && !metisGraph.isBalanced(tpwgts, (float) 1.04 * ubfactor)) {
      metisGraph.computeKWayBalanceBoundary();
      Balancer.greedyKWayEdgeBalance(metisGraph, nparts, tpwgts, ubfactor, 8);
      metisGraph.computeKWayBoundary();
    }
    rkRefiner.refine(metisGraph);

    if (!metisGraph.isBalanced(tpwgts, ubfactor)) {
      metisGraph.computeKWayBalanceBoundary();
      Balancer.greedyKWayEdgeBalance(metisGraph, nparts, tpwgts, ubfactor, 8);
      rkRefiner.refine(metisGraph);
    }
  }

  public void projectKWayPartition(MetisGraph metisGraph, int nparts) throws ExecutionException {
    final MetisGraph finer = metisGraph.getFinerGraph();
    final IntGraph<MetisNode> coarseGraph = metisGraph.getGraph();
    final IntGraph<MetisNode> graph = finer.getGraph();
    graph.map(new LambdaVoid<GNode<MetisNode>>() {
      public void call(GNode<MetisNode> node) {
        MetisNode nodeData = node.getData();
        nodeData.setPartition(nodeData.getMapTo().getData().getPartition());
      }
    });

    computeKWayPartInfo(nparts, finer, coarseGraph, graph);

    finer.initPartWeight();
    for (int i = 0; i < nparts; i++) {
      finer.setPartWeight(i, metisGraph.getPartWeight(i));
    }
    finer.setMinCut(metisGraph.getMinCut());
  }

  private static void computeKWayPartInfo(final int nparts, final MetisGraph finer,
      final IntGraph<MetisNode> coarseGraph, final IntGraph<MetisNode> graph) throws ExecutionException {

    GaloisRuntime.foreach(Utility.getAllNodes(graph),
        new Lambda2Void<GNode<MetisNode>, ForeachContext<GNode<MetisNode>>>() {
          @Override
          public void call(GNode<MetisNode> node, ForeachContext<GNode<MetisNode>> ctx) {
            MetisNode nodeData = node.getData(MethodFlag.NONE, MethodFlag.NONE);
            int numEdges = graph.outNeighborsSize(node, MethodFlag.NONE);
            nodeData.partIndex = new int[numEdges];
            nodeData.partEd = new int[numEdges];
            nodeData.setIdegree(nodeData.getAdjWgtSum());
            if (nodeData.getMapTo().getData(MethodFlag.NONE, MethodFlag.NONE).getEdegree() > 0) {
              int[] map = new int[nparts];
              ProjectNeighborInKWayPartitionClosure closure = new ProjectNeighborInKWayPartitionClosure(graph, map,
                  nodeData);
              node.map(closure, node, MethodFlag.NONE);
              nodeData.setEdegree(closure.ed);
              nodeData.setIdegree(nodeData.getIdegree() - nodeData.getEdegree());
              if (nodeData.getEdegree() - nodeData.getIdegree() >= 0)
                finer.setBoundaryNode(node);
              nodeData.setNDegrees(closure.ndegrees);
            }
          }
        }, Priority.first(RandomPermutation.class));
  }

  static class ProjectNeighborInKWayPartitionClosure implements Lambda2Void<GNode<MetisNode>, GNode<MetisNode>> {
    MetisNode nodeData;
    int ed;
    int[] map;
    int ndegrees;
    IntGraph<MetisNode> graph;

    public ProjectNeighborInKWayPartitionClosure(IntGraph<MetisNode> graph, int[] map, MetisNode nodeData) {
      this.graph = graph;
      this.map = map;
      this.nodeData = nodeData;
      Arrays.fill(map, -1);
    }

    public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
      MetisNode neighborData = neighbor.getData(MethodFlag.NONE);
      if (nodeData.getPartition() != neighborData.getPartition()) {
        int edgeWeight = (int) graph.getEdgeData(node, neighbor, MethodFlag.NONE);
        ed += edgeWeight;
        int index = map[neighborData.getPartition()];
        if (index == -1) {
          map[neighborData.getPartition()] = ndegrees;
          nodeData.partIndex[ndegrees] = neighborData.getPartition();
          nodeData.partEd[ndegrees] += edgeWeight;
          ndegrees++;
        } else {
          nodeData.partEd[index] += edgeWeight;
        }
      }
    }
  }
}
