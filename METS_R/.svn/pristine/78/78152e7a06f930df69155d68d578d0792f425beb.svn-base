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

File: PMetis.java 

*/



package evacSim.partition;

import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import galois.objects.graph.MorphGraph;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

public class PMetis {

  public static double UB_FACTOR = 1;
  public static Coarsener coarsener;

  public PMetis(int coasenTo, int maxVertexWeight) {
    coarsener = new Coarsener(true, coasenTo, maxVertexWeight);
  }

  /**
   * Partition the graph using PMetis
   */
  public static void partition(MetisGraph metisGraph, int nparts) throws ExecutionException {

    int maxVertexWeight = (int) (1.5 * ((metisGraph.getGraph().size()) / Coarsener.COARSEN_FRACTION));
    MetisGraph.nparts = 2;
    float[] totalPartitionWeights = new float[nparts];
    Arrays.fill(totalPartitionWeights, 1 / (float) nparts);
    metisGraph.computeAdjWgtSums();
    PMetis pmetis = new PMetis(maxVertexWeight, 20);
    pmetis.mlevelRecursiveBisection(metisGraph, nparts, totalPartitionWeights, 0, 0);
  }

  public static class TotalWeightClosure implements LambdaVoid<GNode<MetisNode>> {
    int totalVertexWeight = 0;

    @Override
    public void call(GNode<MetisNode> node) {
      totalVertexWeight += node.getData().getWeight();
    }
  }

  /**
   * totalPartWeights: This is an array containing "nparts" floating point numbers. For partition i , totalPartitionWeights[i] stores the fraction
   * of the total weight that should be assigned to it. See tpwgts in manual of metis.
   * @throws ExecutionException 
   */
  protected void mlevelRecursiveBisection(MetisGraph metisGraph, int nparts, float[] totalPartWeights, int tpindex,
      final int partStartIndex) throws ExecutionException {

    IntGraph<MetisNode> graph = metisGraph.getGraph();
    TotalWeightClosure totalWeightClosure = new TotalWeightClosure();
    graph.map(totalWeightClosure);
    int totalVertexWeight = totalWeightClosure.totalVertexWeight;

    float vertexWeightRatio = 0;
    for (int i = 0; i < nparts / 2; i++) {
      vertexWeightRatio += totalPartWeights[tpindex + i];
    }
    int[] bisectionWeights = new int[2];
    bisectionWeights[0] = (int) (totalVertexWeight * vertexWeightRatio);
    bisectionWeights[1] = totalVertexWeight - bisectionWeights[0];
 
    MetisGraph mcg = coarsener.coarsen(metisGraph);
    GrowBisection.bisection(mcg, bisectionWeights, coarsener.getCoarsenTo());
    Refiner.refineTwoWay(mcg, metisGraph, bisectionWeights);

    if (nparts <= 2) {
      graph.map(new LambdaVoid<GNode<MetisNode>>() {
        @Override
        public void call(GNode<MetisNode> node) {
          node.getData().setPartition(node.getData().getPartition() + partStartIndex);
        }
      });
    } else {
      for (int i = 0; i < nparts / 2; i++) {
        totalPartWeights[i + tpindex] *= (1 / vertexWeightRatio);
      }
      //nparts/2 may not be equal to nparts-nparts/2
      for (int i = 0; i < nparts - nparts / 2; i++) {
        totalPartWeights[i + tpindex + nparts / 2] *= (1 / vertexWeightRatio);
      }
      final MetisGraph[] subGraphs = splitGraph(metisGraph);
      if (nparts > 3) {
        mlevelRecursiveBisection(subGraphs[0], nparts / 2, totalPartWeights, tpindex, partStartIndex);
        mlevelRecursiveBisection(subGraphs[1], nparts - nparts / 2, totalPartWeights, tpindex + nparts / 2,
            partStartIndex + nparts / 2);
        metisGraph.setMinCut(metisGraph.getMinCut() + subGraphs[0].getMinCut() + subGraphs[1].getMinCut());
      } else if (nparts == 3) {
        subGraphs[0].getGraph().map(new LambdaVoid<GNode<MetisNode>>() {
          @Override
          public void call(GNode<MetisNode> node) {
            MetisNode nodeData = node.getData();
            nodeData.setPartition(nodeData.getPartition() + partStartIndex);
          }
        });
        mlevelRecursiveBisection(subGraphs[1], nparts - nparts / 2, totalPartWeights, tpindex + nparts / 2,
            partStartIndex + nparts / 2);
        metisGraph.setMinCut(metisGraph.getMinCut() + subGraphs[1].getMinCut());
      }
      graph.map(new LambdaVoid<GNode<MetisNode>>() {
        @Override
        public void call(GNode<MetisNode> node) {
          MetisNode nodeData = node.getData();
          nodeData.setPartition(nodeData.getSubGraphMap().getData().getPartition());
        }
      });
    }
  }

  private static MetisGraph[] splitGraph(MetisGraph metisGraph) {
    final int[] subGraphNodeNum = new int[2];

    final IntGraph<MetisNode> graph = metisGraph.getGraph();

    final MetisGraph[] subGraphs = new MetisGraph[2];
    subGraphs[0] = new MetisGraph();
    subGraphs[1] = new MetisGraph();
    IntGraph<MetisNode> empty = new MorphGraph.IntGraphBuilder().backedByVector(true).directed(true).serial(true).create();
    subGraphs[0].setGraph(empty);
    empty = new MorphGraph.IntGraphBuilder().backedByVector(true).directed(true).serial(true).create();
    subGraphs[1].setGraph(empty);

    graph.map(new LambdaVoid<GNode<MetisNode>>() {
      @Override
      public void call(GNode<MetisNode> node) {
        MetisNode nodeData = node.getData();
        GNode<MetisNode> newNode = subGraphs[nodeData.getPartition()].getGraph().createNode(
            new MetisNode(subGraphNodeNum[nodeData.getPartition()], nodeData.getWeight()));
        nodeData.setSubGraphMap(newNode);
        subGraphNodeNum[nodeData.getPartition()]++;
      }
    });

    graph.map(new LambdaVoid<GNode<MetisNode>>() {
      @Override
      public void call(GNode<MetisNode> node) {
        final MetisNode nodeData = node.getData();
        subGraphs[nodeData.getPartition()].getGraph().add(nodeData.getSubGraphMap());
      }
    });

    graph.map(new LambdaVoid<GNode<MetisNode>>() {
      @Override
      public void call(GNode<MetisNode> node) {
        final MetisNode nodeData = node.getData();
        final int index = nodeData.getPartition();
        final IntGraph<MetisNode> subGraph = subGraphs[index].getGraph();
        nodeData.getSubGraphMap().getData().setAdjWgtSum(nodeData.getAdjWgtSum());
        node.map(new Lambda2Void<GNode<MetisNode>, GNode<MetisNode>>() {
          public void call(GNode<MetisNode> neighbor, GNode<MetisNode> node) {
            MetisNode neighborData = neighbor.getData();
            int edgeWeight = (int) graph.getEdgeData(node, neighbor);
            if (!nodeData.isBoundary() || nodeData.getPartition() == neighborData.getPartition()) {
              subGraph.addEdge(nodeData.getSubGraphMap(), neighborData.getSubGraphMap(), edgeWeight);
            } else {
              nodeData.getSubGraphMap().getData().setAdjWgtSum(
                  nodeData.getSubGraphMap().getData().getAdjWgtSum() - edgeWeight);
            }
          }
        }, node);
      }
    });
    return subGraphs;
  }
}
