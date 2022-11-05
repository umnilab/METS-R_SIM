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

File: SerialLocalComputationObjectGraph.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.objects.MethodFlag;
import galois.runtime.Callback;
import galois.runtime.MapInternalContext;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TObjectIntHashMap;

import java.util.ArrayList;
import java.util.List;

import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

final class SerialLocalComputationObjectGraph<N extends GObject, E> implements ObjectGraph<N, E> {
  private Node[] nodes;
  private E[] edgeData;
  private int[] inIdx;
  private int[] ins;
  private int[] outIdx;
  private int[] outs;

  public SerialLocalComputationObjectGraph(ObjectGraph<N, E> in) {
    createGraph(in);
  }

  @SuppressWarnings("unchecked")
  private void createGraph(final ObjectGraph<N, E> g) {
    int numNodes = g.size();
    this.nodes = (Node[]) new SerialLocalComputationObjectGraph.Node[numNodes];
    final GNode[] rnodes = new GNode[numNodes];
    final TObjectIntHashMap<GNode<N>> nodeMap = new TObjectIntHashMap<GNode<N>>();

    g.map(new LambdaVoid<GNode<N>>() {
      @Override
      public void call(GNode<N> node) {
        int idx = nodeMap.size();
        nodeMap.put(node, idx);
        nodes[idx] = new Node(idx, node.getData());
        rnodes[idx] = node;
      }
    });

    TIntArrayList inIdx = new TIntArrayList();
    final TIntArrayList ins = new TIntArrayList();
    TIntArrayList outIdx = new TIntArrayList();
    final TIntArrayList outs = new TIntArrayList();
    final List<E> edgeData = new ArrayList<E>();

    inIdx.add(0);
    outIdx.add(0);
    for (int i = 0; i < numNodes; i++) {
      final GNode<N> src = rnodes[i];
      g.mapInNeighbors(src, new LambdaVoid<GNode<N>>() {
        @Override
        public void call(GNode<N> dst) {
          ins.add(nodeMap.get(dst));
        }
      });
      inIdx.add(ins.size());

      src.map(new LambdaVoid<GNode<N>>() {
        @Override
        public void call(GNode<N> dst) {
          outs.add(nodeMap.get(dst));
          edgeData.add(g.getEdgeData(src, dst));
        }
      });
      outIdx.add(outs.size());
    }

    this.inIdx = inIdx.toArray();
    this.ins = ins.toArray();
    this.outIdx = outIdx.toArray();
    this.outs = outs.toArray();
    this.edgeData = (E[]) edgeData.toArray();
  }

  @SuppressWarnings("unchecked")
  private int getId(GNode n) {
    return ((Node) n).id;
  }

  @Override
  public GNode<N> createNode(N n) {
    throw new UnsupportedOperationException();
  }

  @Override
  public GNode<N> createNode(N n, byte flags) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean add(GNode<N> src) {
    return add(src, MethodFlag.ALL);
  }

  @Override
  public boolean add(GNode<N> src, byte flags) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean remove(GNode<N> src) {
    return remove(src, MethodFlag.ALL);
  }

  @Override
  public boolean remove(GNode<N> src, byte flags) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean contains(GNode<N> src) {
    return contains(src, MethodFlag.ALL);
  }

  @Override
  public boolean contains(GNode<N> src, byte flags) {
    int idx = getId(src);
    return 0 <= idx && idx < nodes.length;
  }

  private void acquireAll(byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public int size() {
    return size(MethodFlag.ALL);
  }

  @Override
  public int size(byte flags) {
    acquireAll(flags);
    return nodes.length;
  }

  @Override
  public boolean addNeighbor(GNode<N> src, GNode<N> dst) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean addNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst) {
    return removeNeighbor(src, dst, MethodFlag.ALL);
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst) {
    return hasNeighbor(src, dst, MethodFlag.ALL);
  }

  private boolean hasNeighbor(GNode<N> src, GNode<N> dst, int[] adjIdx, int[] adj) {
    int idx = getId(src);
    int target = getId(dst);
    int start = adjIdx[idx];
    int end = adjIdx[idx + 1];

    for (int i = start; i < end; i++) {
      if (adj[i] == target)
        return true;
    }
    return false;
  }

  @Override
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    return hasNeighbor(src, dst, inIdx, ins) || hasNeighbor(src, dst, outIdx, outs);
  }

  void mapNeighbor(int[] adjIdx, int[] adj, GNode<N> src, LambdaVoid<GNode<N>> body) {
    int idx = getId(src);
    int start = adjIdx[idx];
    int end = adjIdx[idx + 1];

    for (int i = start; i < end; i++) {
      GNode<N> other = nodes[adj[i]];
      body.call(other);
    }
  }

  @Override
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> body) {
    mapInNeighbors(src, body, MethodFlag.ALL);
  }

  /**
   * Does not fail if the set of in neighbors of src is modified within the closure.
   */
  @Override
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> body, byte flags) {
    mapNeighbor(inIdx, ins, src, body);
  }

  private int neighborsSize(GNode<N> src, int[] adjIdx, int[] adj) {
    int idx = getId(src);
    int start = adjIdx[idx];
    int end = adjIdx[idx + 1];

    return end - start;
  }

  @Override
  public int inNeighborsSize(GNode<N> src) {
    return inNeighborsSize(src, MethodFlag.ALL);
  }

  @Override
  public int inNeighborsSize(GNode<N> src, byte flags) {
    return neighborsSize(src, inIdx, ins);
  }

  @Override
  public int outNeighborsSize(GNode<N> src) {
    return outNeighborsSize(src, MethodFlag.ALL);
  }

  @Override
  public int outNeighborsSize(GNode<N> src, byte flags) {
    return neighborsSize(src, outIdx, outs);
  }

  @Override
  public boolean addEdge(GNode<N> src, GNode<N> dst, E data) {
    return addEdge(src, dst, data, MethodFlag.ALL);
  }

  @Override
  public boolean addEdge(GNode<N> src, GNode<N> dst, E data, byte flags) {
    throw new UnsupportedOperationException();
  }

  private int getEdgeIdx(GNode<N> src, GNode<N> dst) {
    int idx = getId(src);
    int target = getId(dst);
    int start = outIdx[idx];
    int end = outIdx[idx + 1];

    for (int i = start; i < end; i++) {
      if (outs[i] == target)
        return i;
    }
    throw new Error("No such edge");
  }

  @Override
  public E getEdgeData(GNode<N> src, GNode<N> dst) {
    return getEdgeData(src, dst, MethodFlag.ALL);
  }

  @Override
  public E getEdgeData(GNode<N> src, GNode<N> dst, byte flags) {
    return getEdgeData(src, dst, flags, flags);
  }

  @Override
  public E getEdgeData(GNode<N> src, GNode<N> dst, byte edgeFlags, byte dataFlags) {
    // TODO(ddn): Revive EdgeClosure so that we can implement map with getEdgeData
    // better than this
    return edgeData[getEdgeIdx(src, dst)];
  }

  @Override
  public E setEdgeData(GNode<N> src, GNode<N> dst, E d) {
    return setEdgeData(src, dst, d, MethodFlag.ALL);
  }

  @Override
  public E setEdgeData(final GNode<N> src, final GNode<N> dst, E data, byte flags) {
    int idx = getEdgeIdx(src, dst);
    final E oldData = edgeData[idx];

    if (oldData != data) {
      edgeData[idx] = data;
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
        GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
          @Override
          public void call() {
            setEdgeData(src, dst, oldData, MethodFlag.NONE);
          }
        });
      }
    }

    return oldData;
  }

  @Override
  public boolean isDirected() {
    return true;
  }

  @Override
  public void mapInternal(LambdaVoid<GNode<N>> body, MapInternalContext ctx) {
    int size = nodes.length;

    for (int i = 0; i < size; i++) {
      ctx.begin();
      body.call(nodes[i]);
      ctx.commit(nodes[i]);
    }
  }

  @Override
  public <A1> void mapInternal(Lambda2Void<GNode<N>, A1> body, MapInternalContext ctx, A1 arg1) {
    int size = nodes.length;

    for (int i = 0; i < size; i++) {
      ctx.begin();
      body.call(nodes[i], arg1);
      ctx.commit(nodes[i]);
    }
  }

  @Override
  public <A1, A2> void mapInternal(Lambda3Void<GNode<N>, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2) {
    int size = nodes.length;

    for (int i = 0; i < size; i++) {
      ctx.begin();
      body.call(nodes[i], arg1, arg2);
      ctx.commit(nodes[i]);
    }
  }

  @Override
  public void mapInternalDone() {
  }

  @Override
  public void map(LambdaVoid<GNode<N>> body) {
    map(body, MethodFlag.ALL);
  }

  @Override
  public void map(LambdaVoid<GNode<N>> body, byte flags) {
    acquireAll(flags);
    for (int i = 0; i < nodes.length; i++) {
      body.call(nodes[i]);
    }
  }

  @Override
  public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1) {
    map(body, arg1, MethodFlag.ALL);
  }

  @Override
  public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1, byte flags) {
    acquireAll(flags);
    for (int i = 0; i < nodes.length; i++) {
      body.call(nodes[i], arg1);
    }
  }

  @Override
  public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2) {
    map(body, arg1, arg2, MethodFlag.ALL);
  }

  @Override
  public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2, byte flags) {
    acquireAll(flags);
    for (int i = 0; i < nodes.length; i++) {
      body.call(nodes[i], arg1, arg2);
    }
  }

  private final class Node extends SerialGNode<N> {
    private final int id;
    private N data;

    private Node(int id, N data) {
      this.id = id;
      this.data = data;
    }

    @Override
    public N getData() {
      return getData(MethodFlag.ALL);
    }

    @Override
    public N getData(byte flags) {
      return getData(flags, flags);
    }

    @Override
    public N getData(byte nodeFlags, byte dataFlags) {
      return data;
    }

    @Override
    public N setData(N data) {
      return setData(data, MethodFlag.ALL);
    }

    @Override
    public N setData(N data, byte flags) {
      final N oldData = this.data;

      if (oldData != data) {
        this.data = data;

        if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
          GaloisRuntime.getRuntime().onUndo(Iteration.getCurrentIteration(), new Callback() {
            @Override
            public void call() {
              setData(oldData, MethodFlag.NONE);
            }
          });
        }
      }

      return oldData;
    }

    @Override
    public void mapInternal(LambdaVoid<GNode<N>> body, MapInternalContext ctx) {
      int idx = getId(this);
      int startIdx = outIdx[idx];
      int endIdx = outIdx[idx + 1];

      int size = endIdx - startIdx;
      for (int i = 0; i < size; i++) {
        Node item = nodes[outs[startIdx + i]];
        ctx.begin();
        body.call(item);
        ctx.commit(item);
      }
    }

    @Override
    public <A1> void mapInternal(Lambda2Void<GNode<N>, A1> body, MapInternalContext ctx, A1 arg1) {
      int idx = getId(this);
      int startIdx = outIdx[idx];
      int endIdx = outIdx[idx + 1];

      int size = endIdx - startIdx;
      for (int i = 0; i < size; i++) {
        Node item = nodes[outs[startIdx + i]];
        ctx.begin();
        body.call(item, arg1);
        ctx.commit(item);
      }
    }

    @Override
    public <A1, A2> void mapInternal(Lambda3Void<GNode<N>, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2) {
      int idx = getId(this);
      int startIdx = outIdx[idx];
      int endIdx = outIdx[idx + 1];

      int size = endIdx - startIdx;
      for (int i = 0; i < size; i++) {
        Node item = nodes[outs[startIdx + i]];
        ctx.begin();
        body.call(item, arg1, arg2);
        ctx.commit(item);
      }
    }

    @Override
    public void mapInternalDone() {
    }

    @Override
    public void map(LambdaVoid<GNode<N>> body) {
      map(body, MethodFlag.ALL);
    }

    @Override
    public void map(LambdaVoid<GNode<N>> body, byte flags) {
      SerialLocalComputationObjectGraph.this.mapNeighbor(outIdx, outs, this, body);
    }

    @Override
    public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1) {
      map(body, arg1, MethodFlag.ALL);
    }

    @Override
    public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1, byte flags) {
      int idx = getId(this);
      int start = outIdx[idx];
      int end = outIdx[idx + 1];

      for (int i = start; i < end; i++) {
        GNode<N> other = nodes[outs[i]];
        body.call(other, arg1);
      }
    }

    @Override
    public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2) {
      map(body, arg1, arg2, MethodFlag.ALL);
    }

    @Override
    public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2, byte flags) {
      int idx = getId(this);
      int start = outIdx[idx];
      int end = outIdx[idx + 1];

      for (int i = start; i < end; i++) {
        GNode<N> other = nodes[outs[i]];
        body.call(other, arg1, arg2);
      }
    }
  }

  @Override
  public void access(byte flags) {
  }
}
