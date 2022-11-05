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

File: ObjectGraphLocker.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.objects.MethodFlag;
import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

import java.util.HashMap;
import java.util.Map;

import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

final class ObjectGraphLocker extends GraphLocker {

  static <N extends GObject, E> void removeNeighborEpilog(final ObjectGraph<N, E> graph, final GNode<N> src,
      final GNode<N> dst, final E edgeData, byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
        @Override
        public void call() {
          graph.addEdge(src, dst, edgeData, MethodFlag.NONE);
        }
      });
    }
  }

  //no epilog
  static <N extends GObject, E> void removeNodeProlog(final ObjectGraph<N, E> graph, final GNode<N> src, byte flags) {
    //we already have a lock on src
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
      graph.mapInNeighbors(src, lock, MethodFlag.NONE);
      if (graph.isDirected()) {
        src.map(lock2, Iteration.getCurrentIteration(), MethodFlag.NONE);
      }
    }
    if (!GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      return;
    }
    Lambda3Void<GNode<N>, GNode<N>, Map<GNode<N>, E>> getOutNeighbors = new Lambda3Void<GNode<N>, GNode<N>, Map<GNode<N>, E>>() {
      @Override
      public void call(GNode<N> dst, GNode<N> src, Map<GNode<N>, E> map) {
        map.put(src, graph.getEdgeData(src, dst, MethodFlag.NONE));
      }
    };
    final Map<GNode<N>, E> outMap = new HashMap<GNode<N>, E>();
    src.map(getOutNeighbors, src, outMap, MethodFlag.NONE);

    final Map<GNode<N>, E> inMap = new HashMap<GNode<N>, E>();
    graph.mapInNeighbors(src, new LambdaVoid<GNode<N>>() {
      @Override
      public void call(GNode<N> dst) {
        inMap.put(dst, graph.getEdgeData(dst, src, MethodFlag.NONE));
      }
    }, MethodFlag.NONE);

    GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
      @Override
      public void call() {
        graph.add(src, MethodFlag.NONE);
        for (final Map.Entry<GNode<N>, E> edgeData : outMap.entrySet()) {
          graph.addEdge(src, edgeData.getKey(), edgeData.getValue(), MethodFlag.NONE);
        }
        for (final Map.Entry<GNode<N>, E> edgeData : inMap.entrySet()) {
          graph.addEdge(edgeData.getKey(), src, edgeData.getValue(), MethodFlag.NONE);
        }
      }
    });
  }

  static <N extends GObject> void addEdgeProlog(GNode<N> src, GNode<N> dst, byte flags) {
    acquireLock(src, dst, flags);
  }

  static <N extends GObject> void addEdgeEpilog(final Graph<N> graph, final GNode<N> src, final GNode<N> dst, byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
        @Override
        public void call() {
          graph.removeNeighbor(src, dst, MethodFlag.NONE);
        }
      });
    }
  }

  static <N extends GObject> void getEdgeDataProlog(GNode<N> src, GNode<N> dst, byte flags) {
    acquireLock(src, dst, flags);
  }

  static <E> void getEdgeDataEpilog(final E data, byte flags) {
    // Lift check up to here because it's slightly faster
    if (GaloisRuntime.needMethodFlag(flags, (byte) (MethodFlag.SAVE_UNDO | MethodFlag.CHECK_CONFLICT))) {
      ((GObject) data).access(flags);
    }
  }

  static <N extends GObject> void setEdgeDataProlog(GNode<N> src, GNode<N> dst, byte flags) {
    acquireLock(src, dst, flags);
  }

  static <N extends GObject, E> void setEdgeDataEpilog(final ObjectGraph<N, E> graph, final GNode<N> src,
      final GNode<N> dst, final E data, byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
        @Override
        public void call() {
          graph.setEdgeData(src, dst, data, MethodFlag.NONE);
        }
      });
    }
  }
}
