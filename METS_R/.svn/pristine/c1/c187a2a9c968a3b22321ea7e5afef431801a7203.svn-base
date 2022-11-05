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

File: GrowBisection.java 

*/



package evacSim.partition;

import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;

import java.util.Arrays;
import java.util.Random;

import util.Launcher;
import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

/**
 * The implementation of Graph Growing Partitioning algorithm (GGP)
 * Start from a vertex and grow a region around it in a breath-first fashion,
 * until half of the vertices have been included
 */
public class GrowBisection {

  public static final int SMALL_NUM_ITER_PARTITION = 3;
  public static final int LARGE_NUM_ITER_PARTITION = 8;
  private static Random random = Launcher.getLauncher().getRandom(0);

  public static void bisection(MetisGraph metisGraph, int[] tpwgts, int coarsenTo) {

    IntGraph<MetisNode> graph = metisGraph.getGraph();
    int numNodes = graph.size();
    @SuppressWarnings("unchecked")
    GNode<MetisNode>[] nodes = new GNode[numNodes];
    graph.map(new SaveNodesToArray(nodes));

    int nbfs = (numNodes <= coarsenTo ? SMALL_NUM_ITER_PARTITION : LARGE_NUM_ITER_PARTITION);

    int maxWgtPart1 = (int) PMetis.UB_FACTOR * tpwgts[1];
    int minWgtPart1 = (int) (1.0 / PMetis.UB_FACTOR) * tpwgts[1];

    int bestMinCut = Integer.MAX_VALUE;
    int[] bestWhere = new int[numNodes];

    for (; nbfs > 0; nbfs--) {

      int[] pwgts = new int[2];
      pwgts[1] = tpwgts[0] + tpwgts[1];
      pwgts[0] = 0;

      for (int i = 0; i < numNodes; i++) {
        nodes[i].getData().setPartition(1);
      }

      bisection(graph, nodes, minWgtPart1, maxWgtPart1, pwgts);
      /* Check to see if we hit any bad limiting cases */
      if (pwgts[1] == 0) {
        int i = random.nextInt(numNodes);
        MetisNode nodeData = nodes[i].getData();
        nodeData.setPartition(1);
        pwgts[0] += nodeData.getWeight();
        pwgts[1] -= nodeData.getWeight();
      }

      metisGraph.computeTwoWayPartitionParams();
      Balancer.balanceTwoWay(metisGraph, tpwgts);
      FMTwoWayRefiner.fmTwoWayEdgeRefine(metisGraph, tpwgts, 4);

      if (bestMinCut > metisGraph.getMinCut()) {
        bestMinCut = metisGraph.getMinCut();
        for (int i = 0; i < bestWhere.length; i++) {
          bestWhere[i] = nodes[i].getData().getPartition();
        }
      }
    }
    for (int i = 0; i < numNodes; i++) {
      nodes[i].getData().setPartition(bestWhere[i]);
    }
    metisGraph.setMinCut(bestMinCut);
  }

  private static void bisection(IntGraph<MetisNode> graph, GNode<MetisNode>[] nodes, int minWgtPart1, int maxWgtPart1,
      int[] pwgts) {
    int numNodes = nodes.length;
    int[] visited = new int[numNodes];
    int[] queue = new int[numNodes];
    Arrays.fill(visited, 0);
    queue[0] = random.nextInt(numNodes);
    visited[queue[0]] = 1;
    int first = 0;
    int last = 1;
    int nleft = numNodes - 1;
    boolean drain = false;
    for (;;) {
      if (first == last) { 
        if (nleft == 0 || drain) {
          break;
        }

        int k = random.nextInt(nleft);
        int i = 0;
        for (; i < numNodes; i++) {
          if (visited[i] == 0) {
            if (k == 0) {
              break;
            } else {
              k--;
            }
          }
        }
        queue[0] = i;
        visited[i] = 1;
        first = 0;
        last = 1;
        nleft--;
      }

      int i = queue[first++];
      int nodeWeight = nodes[i].getData().getWeight();

      if (pwgts[0] > 0 && (pwgts[1] - nodeWeight) < minWgtPart1) {
        drain = true;
        continue;
      }

      nodes[i].getData().setPartition(0);
      pwgts[0] += nodeWeight;
      pwgts[1] -= nodeWeight;
      if (pwgts[1] <= maxWgtPart1) {
        break;
      }

      drain = false;

      AccessNeighborClosure closure = new AccessNeighborClosure(last, nleft, visited, queue);
      nodes[i].map(closure, nodes[i]);
      last = closure.last;
      nleft = closure.nleft;
    }
  }

  static class AccessNeighborClosure implements Lambda2Void<GNode<MetisNode>, GNode<MetisNode>> {
    int last;
    int nleft;
    int[] visited;
    int[] queue;

    public AccessNeighborClosure(int last, int nleft, int[] visited, int[] queue) {
      this.last = last;
      this.nleft = nleft;
      this.visited = visited;
      this.queue = queue;
    }

    @Override
    public void call(GNode<MetisNode> neighbor, GNode<MetisNode> src) {
      int k = neighbor.getData().getNodeId();//id is same as the position in nodes array
      if (visited[k] == 0) {
        queue[last++] = k;
        visited[k] = 1;
        nleft--;
      }
    }
  }

  static class SaveNodesToArray implements LambdaVoid<GNode<MetisNode>> {
    GNode<MetisNode> nodes[];

    public SaveNodesToArray(GNode<MetisNode>[] nodes) {
      this.nodes = nodes;
    }

    @Override
    public void call(GNode<MetisNode> node) {
      nodes[node.getData().getNodeId()] = node;
    }

  }

}
