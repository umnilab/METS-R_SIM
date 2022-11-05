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

File: Coarsener.java 

*/



package evacSim.partition;

import galois.objects.MethodFlag;
import galois.objects.graph.GNode;
import galois.objects.graph.IntGraph;
import galois.objects.graph.MorphGraph;
import galois.runtime.ForeachContext;
import galois.runtime.GaloisRuntime;
import galois.runtime.wl.Priority;
import galois.runtime.wl.RandomPermutation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

public class Coarsener {
  final private int coarsenTo;
  final public static double COARSEN_FRACTION = 0.9;
  final private int maxVertexWeight;
  final private boolean useSerial;
  private IntGraph<MetisNode> graph;

  public Coarsener(boolean userSerial, int coarsenTo, int maxVertexWeight) {
    this.useSerial = userSerial;
    this.coarsenTo = coarsenTo;
    this.maxVertexWeight = maxVertexWeight;
  }

  public int getCoarsenTo() {
    return coarsenTo;
  }

  public MetisGraph coarsen(MetisGraph metisGraph) throws ExecutionException {
    boolean notFirstTime = false; // use when the graph have all weights equal
    do {
      MetisGraph coarseMetisGraph = new MetisGraph();
      IntGraph<MetisNode> coarser = null;
      if (useSerial) {
        coarser = new MorphGraph.IntGraphBuilder().backedByVector(true).directed(true).serial(true).create();
      } else {
        coarser = new MorphGraph.IntGraphBuilder().backedByVector(true).directed(true).create();
      }
      coarseMetisGraph.setGraph(coarser);
      this.graph = metisGraph.getGraph();
      if (useSerial) {
        notFirstTime = serialMatch(notFirstTime, coarser);
        serialCreateCoarserGraph(coarseMetisGraph);
      } else {
        notFirstTime = parallelMatch(notFirstTime, coarser);
        parallelCreateCoarserGraph(coarseMetisGraph);
      }
      // assigning id to coarseGraph
      coarser.map(new LambdaVoid<GNode<MetisNode>>() {
        int id = 0;

        @Override
        public void call(GNode<MetisNode> node) {
          node.getData().setNodeId(id++);
        }
      });
      coarseMetisGraph.setNumEdges(coarseMetisGraph.getNumEdges() / 2);

      coarseMetisGraph.setFinerGraph(metisGraph);
      metisGraph = coarseMetisGraph;
    } while (isDone(metisGraph));
    return metisGraph;
  }

  final private boolean serialMatch(boolean notFirstTime, IntGraph<MetisNode> coarser) {
    if (notFirstTime) {
    	graph.map(new HEMatchingStrategy(graph, coarser, maxVertexWeight));
    } else {
      graph.map(new RMMatchingStrategy(graph, coarser, maxVertexWeight));
      notFirstTime = true;
    }
    return notFirstTime;
  }

  final private boolean parallelMatch(boolean notFirstTime, final IntGraph<MetisNode> coarser)
      throws ExecutionException {

    if (notFirstTime) {
      GaloisRuntime.foreach(Utility.getAllNodes(graph),
          new Lambda2Void<GNode<MetisNode>, ForeachContext<GNode<MetisNode>>>() {
            HEMatchingStrategy match = new HEMatchingStrategy(graph, coarser, maxVertexWeight);

            @Override
            public void call(GNode<MetisNode> item, ForeachContext<GNode<MetisNode>> ctx) {              
              match.call(item);              
            }
          }, Priority.first(RandomPermutation.class));
    } else {
      GaloisRuntime.foreach(Utility.getAllNodes(graph),
          new Lambda2Void<GNode<MetisNode>, ForeachContext<GNode<MetisNode>>>() {
            RMMatchingStrategy match = new RMMatchingStrategy(graph, coarser, maxVertexWeight);

            @Override
            public void call(GNode<MetisNode> item, ForeachContext<GNode<MetisNode>> ctx) {
              match.call(item);
            }
          }, Priority.first(RandomPermutation.class));
      notFirstTime = true;
    }
    return notFirstTime;
  }

  /**
   * determine if the graph is coarse enough
   */
  final private boolean isDone(MetisGraph metisGraph) {
    IntGraph<MetisNode> graph = metisGraph.getGraph();
    int size = graph.size();
    return size > coarsenTo && size < COARSEN_FRACTION * metisGraph.getFinerGraph().getGraph().size()
        && metisGraph.getNumEdges() > size / 2;
  }

  final private MetisGraph serialCreateCoarserGraph(final MetisGraph coarseMetisGraph) {
    final boolean[] visited = new boolean[graph.size()];
    graph.map(new AddEdgesClosure(visited, coarseMetisGraph));
    return coarseMetisGraph;
  }

  final private MetisGraph parallelCreateCoarserGraph(final MetisGraph coarseMetisGraph) throws ExecutionException {
    final boolean[] visited = new boolean[graph.size()];
    final AddEdgesClosure closure = new AddEdgesClosure(visited, coarseMetisGraph);
    GaloisRuntime.foreach(Utility.getAllNodes(graph),
        new Lambda2Void<GNode<MetisNode>, ForeachContext<GNode<MetisNode>>>() {
          @Override
          public void call(GNode<MetisNode> item, ForeachContext<GNode<MetisNode>> ctx) {
            MetisNode nodeData = item.getData(MethodFlag.CHECK_CONFLICT, MethodFlag.NONE);
            GNode<MetisNode> matched = nodeData.getMatch();
            // dummy closures for making cautious
            if(!GaloisRuntime.getRuntime().useSerial()){
            	matched.map(new Lambda2Void<GNode<MetisNode>, GNode<MetisNode>>() {
            		public void call(GNode<MetisNode> dst, GNode<MetisNode> src) {
            			dst.getData(MethodFlag.CHECK_CONFLICT, MethodFlag.NONE).getMapTo().getData(MethodFlag.CHECK_CONFLICT, MethodFlag.NONE);
            		}
            	}, matched, MethodFlag.CHECK_CONFLICT);
            	item.map(new Lambda2Void<GNode<MetisNode>, GNode<MetisNode>>() {
            		public void call(GNode<MetisNode> dst, GNode<MetisNode> src) {
            			dst.getData(MethodFlag.CHECK_CONFLICT, MethodFlag.NONE).getMapTo().getData(MethodFlag.CHECK_CONFLICT, MethodFlag.NONE);
            		}
            	}, item, MethodFlag.CHECK_CONFLICT);
            }
            closure.call(item);
          }
        }, Priority.first(RandomPermutation.class));

    return coarseMetisGraph;
  }

  class AddEdgesClosure implements LambdaVoid<GNode<MetisNode>> {
    private boolean[] visited;
    private MetisGraph coarseMetisGraph;

    public AddEdgesClosure(boolean[] visited, MetisGraph coarseMetisGraph) {
      this.visited = visited;
      this.coarseMetisGraph = coarseMetisGraph;
    }

    public void call(GNode<MetisNode> node) {
      MetisNode nodeData = node.getData(MethodFlag.NONE);
      if (visited[nodeData.getNodeId()])
        return;
      Map<GNode<MetisNode>, Integer> map = new LinkedHashMap<GNode<MetisNode>, Integer>();
      GNode<MetisNode> matched = nodeData.getMatch();
      MetisNode matchedData = matched.getData(MethodFlag.NONE);
      node.map(new buildNeighborClosure(graph, coarseMetisGraph, node, matched, map), node);
      if (matched != node) {
        matched.map(new buildNeighborClosure(graph, coarseMetisGraph, matched, node, map), matched);
      }
      visited[matchedData.getNodeId()] = true;
    }
  }

  class buildNeighborClosure implements Lambda2Void<GNode<MetisNode>, GNode<MetisNode>> {
    IntGraph<MetisNode> graph;
    MetisGraph coarseMetisGraph;
    GNode<MetisNode> n;
    GNode<MetisNode> matched;
    IntGraph<MetisNode> coarseGraph;
    GNode<MetisNode> nodeMapTo;
    Map<GNode<MetisNode>, Integer> map;

    public buildNeighborClosure(IntGraph<MetisNode> graph, MetisGraph coarseMetisGraph, GNode<MetisNode> n,
        GNode<MetisNode> matched, Map<GNode<MetisNode>, Integer> map) {
      this.graph = graph;
      this.coarseMetisGraph = coarseMetisGraph;
      this.n = n;
      this.matched = matched;
      this.map = map;
      coarseGraph = coarseMetisGraph.getGraph();
      nodeMapTo = n.getData(MethodFlag.NONE, MethodFlag.NONE).getMapTo();
    }

    @Override
    public void call(GNode<MetisNode> neighbor, GNode<MetisNode> src) {
      if (neighbor == matched) {
        return;
      }
      int edgeWeight = (int) graph.getEdgeData(n, neighbor, MethodFlag.NONE);
      GNode<MetisNode> neighborMapTo = neighbor.getData(MethodFlag.NONE).getMapTo();
      Integer newEdgeWeight = map.get(neighborMapTo);

      if (newEdgeWeight == null) {
        coarseGraph.addEdge(nodeMapTo, neighborMapTo, edgeWeight, MethodFlag.NONE);
        coarseMetisGraph.incNumEdges();
        MetisNode nodeMapToData = nodeMapTo.getData(MethodFlag.NONE, MethodFlag.NONE);
        nodeMapToData.incNumEdges();
        nodeMapToData.addEdgeWeight(edgeWeight);
        map.put(neighborMapTo, (Integer) edgeWeight);
      } else {
        map.put(neighborMapTo, edgeWeight + newEdgeWeight);
        coarseGraph.setEdgeData(nodeMapTo, neighborMapTo, newEdgeWeight + edgeWeight, MethodFlag.NONE);
        nodeMapTo.getData(MethodFlag.NONE, MethodFlag.NONE).addEdgeWeight(edgeWeight);
      }
    }
  }
}
