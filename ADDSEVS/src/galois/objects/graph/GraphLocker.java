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

File: GraphLocker.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.objects.MethodFlag;
import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

import java.util.ArrayList;
import java.util.Collection;

import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

class GraphLocker {

  // stateless closures
  static final LockLambda2 lock2 = new LockLambda2();
  static final InNeighborsLambda2 inNeighbors2 = new InNeighborsLambda2();
  static final LockLambda lock = new LockLambda();

  // createNode: no prolog
  static <N extends GObject> void createNodeEpilog(GNode<N> src, byte flags) {
    acquireLock(src, flags);
  }

  static <N extends GObject> void addNodeProlog(GNode<N> src, byte flags) {
    acquireLock(src, flags);
  }

  static <N extends GObject> void addNodeEpilog(final Graph<N> graph, final GNode<N> src, byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
        @Override
        public void call() {
          graph.remove(src, MethodFlag.NONE);
        }
      });
    }
  }

  //no epilog
  static <N extends GObject> void containsNodeProlog(GNode<N> src, byte flags) {
    acquireLock(src, flags);
  }

  // no epilog
  static void mapProlog(byte flags) {
    acquireLockOnAll(flags);
  }

  // no epilog
  static void sizeProlog(byte flags) {
    acquireLockOnAll(flags);
  }

  static <N extends GObject> void getNodeDataProlog(GNode<N> src, byte flags) {
    acquireLock(src, flags);
  }

  static <N extends GObject> void getNodeDataEpilog(N data, byte flags) {
    // Lift check up to here because it's slightly faster
    if (GaloisRuntime.needMethodFlag(flags, (byte) (MethodFlag.SAVE_UNDO | MethodFlag.CHECK_CONFLICT))) {
      data.access(flags);
    }
  }

  static <N extends GObject> void setNodeDataProlog(GNode<N> src, byte flags) {
    acquireLock(src, flags);
  }

  static <N extends GObject> void setNodeDataEpilog(final GNode<N> src, final N data, byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
        @Override
        public void call() {
          src.setData(data, MethodFlag.NONE);
        }
      });
    }
  }

  static <N extends GObject> void acquireLock(GNode<N> src, byte flags) {
    Iteration.acquire(src, flags);
  }

  static <N extends GObject> void acquireLock(GNode<N> src, GNode<N> dst, byte flags) {
    Iteration it = Iteration.acquire(src, flags);
    if (it != null) {
      it.acquire(dst);
    }
  }

  static void acquireLockOnAll(byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
      throw new Error("no globals");
    }
  }

  //no epilog
  @SuppressWarnings("unchecked")
  static <N extends GObject> void removeNodeProlog(final Graph<N> graph, final GNode<N> src, byte flags) {
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
    final Collection<GNode<N>> neighbors = new ArrayList<GNode<N>>();
    src.map(inNeighbors2, neighbors, MethodFlag.NONE);
    if (graph.isDirected()) {
      graph.mapInNeighbors(src, new InNeighborsLambda<N>(neighbors), MethodFlag.NONE);
    }
    GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
      @Override
      public void call() {
        graph.add(src, MethodFlag.NONE);
        for (final GNode<N> e : neighbors) {
          graph.addNeighbor(src, e, MethodFlag.NONE);
        }
      }
    });
  }

  //addNeighbor is not supported by edge graphs

  static <N extends GObject> void removeNeighborProlog(GNode<N> src, GNode<N> dst, byte flags) {
    acquireLock(src, dst, flags);
  }

  static <N extends GObject> void removeNeighborEpilog(final Graph<N> graph, final GNode<N> src, final GNode<N> dst,
      byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
        @Override
        public void call() {
          graph.addNeighbor(src, dst, MethodFlag.NONE);
        }
      });
    }
  }

  static <N extends GObject> void addNeighborEpilog(final Graph<N> graph, final GNode<N> src, final GNode<N> dst,
      byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
        @Override
        public void call() {
          graph.removeNeighbor(src, dst, MethodFlag.NONE);
        }
      });
    }
  }

  //no prolog
  static <N extends GObject> void hasNeighborProlog(GNode<N> src, GNode<N> dst, byte flags) {
    acquireLock(src, dst, flags);
  }

  //no epilog
  static <N extends GObject> void mapInNeighborsProlog(final Graph<N> graph, GNode<N> src, byte flags) {
    acquireLockOnNodeAndInNeighbors(graph, src, flags);
  }

  //no epilog
  static <N extends GObject> void inNeighborsSizeProlog(final Graph<N> graph, GNode<N> src, byte flags) {
    acquireLockOnNodeAndInNeighbors(graph, src, flags);
  }

  //no epilog
  static <N extends GObject> void mapOutNeighborsProlog(GNode<N> src, byte flags) {
    acquireLockOnNodeAndOutNeighbors(src, flags);
  }

  //no epilog
  static <N extends GObject> void outNeighborsSizeProlog(GNode<N> src, byte flags) {
    acquireLockOnNodeAndOutNeighbors(src, flags);
  }

  @SuppressWarnings("unchecked")
  static <N extends GObject> void acquireLockOnNodeAndInNeighbors(final Graph<N> graph, GNode<N> n, byte flags) {
    Iteration it = Iteration.acquire(n, flags);
    if (it != null) {
      graph.mapInNeighbors(n, lock, MethodFlag.NONE);
    }
  }

  @SuppressWarnings("unchecked")
  static <N extends GObject> void acquireLockOnNodeAndOutNeighbors(GNode<N> n, byte flags) {
    Iteration it = Iteration.acquire(n, flags);
    if (it != null) {
      n.map(lock2, it, MethodFlag.NONE);
    }
  }

  private static class InNeighborsLambda<N extends GObject> implements LambdaVoid<GNode<N>> {
    private Collection<GNode<N>> bag;

    public InNeighborsLambda(Collection<GNode<N>> bag) {
      this.bag = bag;
    }

    @Override
    public void call(GNode<N> arg0) {
      bag.add(arg0);
    }
  }

  private static class LockLambda<N extends GObject> implements LambdaVoid<GNode<N>> {
    @Override
    public void call(GNode<N> arg0) {
      acquireLock(arg0, MethodFlag.CHECK_CONFLICT);
    }
  }

  private static class InNeighborsLambda2<N extends GObject> implements Lambda2Void<GNode<N>, Collection<GNode<N>>> {
    @Override
    public void call(GNode<N> arg0, Collection<GNode<N>> bag) {
      bag.add(arg0);
    }
  }

  private static class LockLambda2<N extends GObject> implements Lambda2Void<GNode<N>, Iteration> {
    @Override
    public void call(GNode<N> arg0, Iteration it) {
      it.acquire(arg0);
    }
  }
}
