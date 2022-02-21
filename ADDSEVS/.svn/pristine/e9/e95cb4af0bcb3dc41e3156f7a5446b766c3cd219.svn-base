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

File: SerialVectorMorphObjectGraph.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.objects.MethodFlag;
import galois.runtime.MapInternalContext;
import galois.runtime.IterationAbortException;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;

import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

final class SerialVectorMorphObjectGraph<N extends GObject, E> implements ObjectGraph<N, E> {
  private LinkedNode head;
  private int size;
  private final boolean isDirected;
  boolean modNodes;

  private static final int chunkSize = 64;

  SerialVectorMorphObjectGraph() {
    this(true);
  }

  SerialVectorMorphObjectGraph(boolean isDirected) {
    this.isDirected = isDirected;
    head = null;
    size = 0;
  }

  @SuppressWarnings("unchecked")
  private EdgeGraphNode downcast(GNode n) {
    return (EdgeGraphNode) n;
  }

  @Override
  public GNode<N> createNode(final N n) {
    return createNode(n, MethodFlag.ALL);
  }

  @Override
  public GNode<N> createNode(N n, byte flags) {
    GNode<N> ret = new EdgeGraphNode(n, isDirected);
    return ret;
  }

  @Override
  public boolean add(GNode<N> src) {
    return add(src, MethodFlag.ALL);
  }

  @Override
  public boolean add(GNode<N> src, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    if (gsrc.add(this)) {
      size++;
      modNodes = true;
      return true;
    }
    return false;
  }

  @Override
  public boolean remove(GNode<N> src) {
    return remove(src, MethodFlag.ALL);
  }

  @Override
  public boolean remove(GNode<N> src, byte flags) {
    if (!contains(src, flags)) {
      return false;
    }
    modNodes = true;
    size--;
    EdgeGraphNode gsrc = downcast(src);
    boolean ret = gsrc.remove(this);
    // has to be there, because containsNode returned true and we have the lock on the node
    assert ret;
    return true;
  }

  @Override
  public boolean contains(GNode<N> src) {
    return contains(src, MethodFlag.ALL);
  }

  @Override
  public boolean contains(GNode<N> src, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    return gsrc.inGraph(this);
  }

  @Override
  public int size() {
    return size(MethodFlag.ALL);
  }

  @Override
  public int size(byte flags) {
    int ret = size;
    assert ret >= 0;
    return ret;
  }

  @Override
  public boolean addNeighbor(GNode<N> src, GNode<N> dst) {
    throw new UnsupportedOperationException("addNeighbor not supported in EdgeGraphs. Use createEdge/addEdge instead");
  }

  @Override
  public boolean addNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    throw new UnsupportedOperationException("addNeighbor not supported in EdgeGraphs. Use createEdge/addEdge instead");
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst) {
    return removeNeighbor(src, dst, MethodFlag.ALL);
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    EdgeGraphNode gdst = downcast(dst);
    int index = gsrc.outNeighbors.indexOf(gdst);
    if (index < 0) {
      return false;
    }
    E data = gsrc.outData.get(index);
    // src has to be connected to dst
    gsrc.removeNeighborRetEdgeData(gdst, true);
    // dst might no be connected to src if src==dst && the graph is undirected
    gdst.removeNeighbor(gsrc, false);
    return true;
  }

  @Override
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst) {
    return hasNeighbor(src, dst, MethodFlag.ALL);
  }

  @Override
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    EdgeGraphNode gdst = downcast(dst);
    boolean ret = gsrc.outNeighbors.contains(gdst);
    assert ret == gdst.inNeighbors.contains(gsrc);
    return ret;
  }

  @Override
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> lambda) {
    mapInNeighbors(src, lambda, MethodFlag.ALL);
  }

  @Override
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> lambda, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    boolean prevModInNeighbors = gsrc.isModInNeighbors();
    gsrc.setModInNeighors(false);
    ArrayList<EdgeGraphNode> neighbors = gsrc.inNeighbors;
    final int size = neighbors.size();
    for (int i = 0; i < size; i++) {
      checkForConcurrentModifications(gsrc.isModInNeighbors());
      EdgeGraphNode neighbor = neighbors.get(i);
      lambda.call(neighbor);
    }
    checkForConcurrentModifications(gsrc.isModInNeighbors());
    gsrc.setModInNeighors(prevModInNeighbors);
  }

  @Override
  public int inNeighborsSize(GNode<N> src) {
    return inNeighborsSize(src, MethodFlag.ALL);
  }

  @Override
  public int inNeighborsSize(GNode<N> src, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    return gsrc.inNeighbors.size();
  }

  @Override
  public int outNeighborsSize(GNode<N> src) {
    return outNeighborsSize(src, MethodFlag.ALL);
  }

  @Override
  public int outNeighborsSize(GNode<N> src, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    return gsrc.outNeighbors.size();
  }

  @Override
  public boolean addEdge(GNode<N> src, GNode<N> dst, E data) {
    return addEdge(src, dst, data, MethodFlag.ALL);
  }

  @Override
  public boolean addEdge(GNode<N> src, GNode<N> dst, final E data, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    EdgeGraphNode gdst = downcast(dst);
    // if the edge is already there, do not allow overwriting of data (use setEdgeData instead)
    if (gsrc.addNeighbor(gdst, data, true)) {
      gdst.addNeighbor(gsrc, data, false);
      return true;
    }
    return false;
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
    EdgeGraphNode gsrc = downcast(src);
    EdgeGraphNode gdst = downcast(dst);
    int index = gsrc.outNeighbors.indexOf(gdst);
    if (index >= 0) {
      E ret = gsrc.outData.get(index);
      return ret;
    }
    return null;
  }

  @Override
  public E setEdgeData(GNode<N> src, GNode<N> dst, E d) {
    return setEdgeData(src, dst, d, MethodFlag.ALL);
  }

  @Override
  public E setEdgeData(GNode<N> src, GNode<N> dst, final E data, byte flags) {
    EdgeGraphNode gsrc = downcast(src);
    EdgeGraphNode gdst = downcast(dst);
    int index = gsrc.outNeighbors.indexOf(gdst);
    if (index < 0) {
      return null;
    }
    E oldData = gsrc.outData.get(index);
    // fast check to avoid redundant work
    if (oldData != data) {
      gsrc.outData.set(index, data);
      if (gsrc != gdst || isDirected) {
        index = gdst.inNeighbors.indexOf(gsrc);
        assert oldData == gdst.inData.get(index);
        gdst.inData.set(index, data);
      }
    }

    return oldData;
  }

  @Override
  public boolean isDirected() {
    return isDirected;
  }

  private boolean tryMark(EdgeGraphNode curr) {
    return true;
  }

  private EdgeGraphNode scanForNode(LinkedNode start) {
    while (start != null) {
      if (start.isDummy()) {
        start = start.getNext();
      } else {
        return (EdgeGraphNode) start;
      }
    }
    return null;
  }

  @Override
  public void mapInternal(LambdaVoid<GNode<N>> body, MapInternalContext ctx) {
    EdgeGraphNode begin = scanForNode(head);

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
      }

      EdgeGraphNode cur = begin;

      for (int i = 0; i < chunkSize; i++) {
        if (owned) {
          while (true) {
            try {
              // Help out GC
              ctx.begin();
              body.call(cur);
              ctx.commit(cur);
              break;
            } catch (IterationAbortException e) {
              ctx.abort();
            }
          }
        }

        cur = scanForNode(cur.next);
        if (cur == null) {
          break;
        }
      }

      begin = cur;
    }
  }

  @Override
  public <A1> void mapInternal(Lambda2Void<GNode<N>, A1> body, MapInternalContext ctx, A1 arg1) {
    EdgeGraphNode begin = scanForNode(head);

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
      }

      EdgeGraphNode cur = begin;

      for (int i = 0; i < chunkSize; i++) {
        if (owned) {
          while (true) {
            try {
              // Help out GC
              ctx.begin();
              body.call(cur, arg1);
              ctx.commit(cur);
              break;
            } catch (IterationAbortException e) {
              ctx.abort();
            }
          }
        }

        cur = scanForNode(cur.next);
        if (cur == null) {
          break;
        }
      }

      begin = cur;
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public <A1, A2> void mapInternal(Lambda3Void<GNode<N>, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2) {
    EdgeGraphNode begin = scanForNode(head);

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
      }

      EdgeGraphNode cur = begin;

      for (int i = 0; i < chunkSize; i++) {
        if (owned) {
          while (true) {
            try {
              // Help out GC
              ctx.begin();
              body.call(cur, arg1, arg2);
              ctx.commit(cur);
              break;
            } catch (IterationAbortException e) {
              ctx.abort();
            }
          }
        }

        cur = scanForNode(cur.next);
        if (cur == null) {
          break;
        }
      }

      begin = cur;
    }
  }

  @Override
  public void map(LambdaVoid<GNode<N>> body) {
    map(body, MethodFlag.ALL);
  }

  @Override
  public void map(LambdaVoid<GNode<N>> body, byte flags) {
    boolean prevModNodes = modNodes;
    modNodes = false;
    LinkedNode curr = head;
    while (curr != null) {
      if (!curr.isDummy()) {
        checkForConcurrentModifications(modNodes);
        EdgeGraphNode gsrc = (EdgeGraphNode) curr;
        assert gsrc.isIn();
        body.call(gsrc);
      }
      curr = curr.getNext();
    }
    checkForConcurrentModifications(modNodes);
    modNodes = prevModNodes;
  }

  @Override
  public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1) {
    map(body, arg1, MethodFlag.ALL);
  }

  @Override
  public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1, byte flags) {
    boolean prevModNodes = modNodes;
    modNodes = false;
    LinkedNode curr = head;
    while (curr != null) {
      if (!curr.isDummy()) {
        checkForConcurrentModifications(modNodes);
        EdgeGraphNode gsrc = (EdgeGraphNode) curr;
        assert gsrc.isIn();
        body.call(gsrc, arg1);
      }
      curr = curr.getNext();
    }
    checkForConcurrentModifications(modNodes);
    modNodes = prevModNodes;
  }

  @Override
  public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2) {
    map(body, arg1, arg2, MethodFlag.ALL);
  }

  @Override
  public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2, byte flags) {
    boolean prevModNodes = modNodes;
    modNodes = false;
    LinkedNode curr = head;
    while (curr != null) {
      if (!curr.isDummy()) {
        checkForConcurrentModifications(modNodes);
        EdgeGraphNode gsrc = (EdgeGraphNode) curr;
        assert gsrc.isIn();
        body.call(gsrc, arg1, arg2);
      }
      curr = curr.getNext();
    }
    checkForConcurrentModifications(modNodes);
    modNodes = prevModNodes;
  }

  @Override
  public void mapInternalDone() {
  }

  private void checkForConcurrentModifications(boolean modified) {
    if (modified) {
      throw new ConcurrentModificationException();
    }
  }

  private static interface LinkedNode {
    public void setNext(LinkedNode next);

    public LinkedNode getNext();

    public boolean isDummy();
  }

  private static class DummyLinkedNode implements LinkedNode {
    private LinkedNode next;

    @Override
    public void setNext(LinkedNode next) {
      this.next = next;
    }

    @Override
    public LinkedNode getNext() {
      return next;
    }

    @Override
    public boolean isDummy() {
      return true;
    }
  }

  private final class EdgeGraphNode extends SerialGNode<N> implements LinkedNode {
    private final ArrayList<EdgeGraphNode> inNeighbors;
    private final ArrayList<E> inData;
    private final ArrayList<EdgeGraphNode> outNeighbors;
    private final ArrayList<E> outData;
    protected N data;
    private LinkedNode dummy;
    private LinkedNode next;
    private static final int NUM_NEIGHBORS = 8;
    // flags contains all the predicate information (=boolean) about a node
    private byte flags;
    private static final byte IN_MASK = 1;
    private static final byte MOD_IN = 1 << 1;
    private static final byte MOD_OUT = 1 << 2;

    private EdgeGraphNode(N d, boolean isDirected) {
      outNeighbors = new ArrayList<EdgeGraphNode>(NUM_NEIGHBORS);
      outData = new ArrayList<E>(NUM_NEIGHBORS);
      if (isDirected) {
        inNeighbors = new ArrayList<EdgeGraphNode>(NUM_NEIGHBORS);
        inData = new ArrayList<E>(NUM_NEIGHBORS);
      } else {
        inNeighbors = outNeighbors;
        inData = outData;
      }
      data = d;
    }

    private boolean inGraph(SerialVectorMorphObjectGraph<N, E> g) {
      return SerialVectorMorphObjectGraph.this == g && isIn();
    }

    private boolean add(SerialVectorMorphObjectGraph<N, E> g) {
      if (SerialVectorMorphObjectGraph.this != g) {
        // XXX(ddn): Nodes could belong to more than 1 graph, but since
        // this rarely happens in practice, simplify implementation
        // assuming that this doesn't occur
        throw new UnsupportedOperationException("cannot add nodes created by a different graph");
      }
      if (!isIn()) {
        setIn(true);
        dummy = new DummyLinkedNode();
        dummy.setNext(this);
        setNext(head);
        head = dummy;
        return true;
      }
      return false;
    }

    private boolean addNeighbor(EdgeGraphNode node, E data, boolean fromOutNeighbors) {
      ArrayList<EdgeGraphNode> neighbors = fromOutNeighbors ? outNeighbors : inNeighbors;
      if (!neighbors.contains(node)) {
        neighbors.add(node);
        ArrayList<E> neighborsData = fromOutNeighbors ? outData : inData;
        neighborsData.add(data);
        if (fromOutNeighbors) {
          setModOutNeighors(true);
        } else {
          setModInNeighors(true);
        }
        return true;
      }
      return false;
    }

    private boolean remove(SerialVectorMorphObjectGraph<N, E> g) {
      if (!inGraph(g)) {
        return false;
      }
      setIn(false);
      dummy.setNext(next);
      final int outNeighborsSize = outNeighbors.size();
      for (int i = 0; i < outNeighborsSize; i++) {
        EdgeGraphNode gdst = outNeighbors.get(i);
        if (this != gdst || isDirected) {
          gdst.removeNeighbor(this, false);
        }
      }
      if (isDirected) {
        int inNeighborsSize = inNeighbors.size();
        for (int i = 0; i < inNeighborsSize; i++) {
          EdgeGraphNode gsrc = inNeighbors.get(i);
          gsrc.removeNeighbor(this, true);
        }
        if (inNeighborsSize > 0) {
          setModInNeighors(true);
        }
        inNeighbors.clear();
        inData.clear();
      }
      if (outNeighborsSize > 0) {
        setModOutNeighors(true);
      }
      outNeighbors.clear();
      outData.clear();
      return true;
    }

    private boolean removeNeighbor(EdgeGraphNode node, boolean fromOutNeighbors) {
      ArrayList<EdgeGraphNode> neighbors = fromOutNeighbors ? outNeighbors : inNeighbors;
      int index = neighbors.indexOf(node);
      if (index < 0) {
        return false;
      }
      removeAndGetEdgeData(neighbors, index, fromOutNeighbors);
      return true;
    }

    // same as before BUT the neighbor has to be there, so we return the edge data instead
    private E removeNeighborRetEdgeData(EdgeGraphNode node, boolean fromOutNeighbors) {
      ArrayList<EdgeGraphNode> neighbors = fromOutNeighbors ? outNeighbors : inNeighbors;
      assert neighbors.contains(node);
      int index = neighbors.indexOf(node);
      return removeAndGetEdgeData(neighbors, index, fromOutNeighbors);
    }

    private E removeAndGetEdgeData(ArrayList<EdgeGraphNode> neighbors, int index, boolean fromOutNeighbors) {
      if (fromOutNeighbors) {
        setModOutNeighors(true);
      } else {
        setModInNeighors(true);
      }
      ArrayList<E> data = fromOutNeighbors ? outData : inData;
      int indexLast = neighbors.size() - 1;
      if (index < indexLast) {
        // swap the element + data at this index with the one in the last position
        // the swap avoids shifting the contents of the arraylist
        neighbors.set(index, neighbors.remove(indexLast));
        final E ret = data.remove(indexLast);
        data.set(index, ret);
        return ret;
      }
      assert index == indexLast;
      neighbors.remove(indexLast);
      return data.remove(indexLast);
    }

    @Override
    public LinkedNode getNext() {
      return next;
    }

    @Override
    public void setNext(LinkedNode next) {
      this.next = next;
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
    public N getData(byte nodeflags, byte dataFlags) {
      N ret = this.data;
      return ret;
    }

    @Override
    public boolean isDummy() {
      return false;
    }

    @Override
    public N setData(N data) {
      return setData(data, MethodFlag.ALL);
    }

    @Override
    public N setData(N data, byte flags) {
      N oldData = this.data;
      // fast check to avoid redundant calls to the CM
      if (oldData != data) {
        this.data = data;
      }
      return oldData;
    }

    @Override
    public void map(LambdaVoid<GNode<N>> body) {
      map(body, MethodFlag.ALL);
    }

    @Override
    public void mapInternal(LambdaVoid<GNode<N>> body, MapInternalContext ctx) {
      boolean prevModOutNeighbors = isModOutNeighbors();
      setModOutNeighors(false);
      int size = outNeighbors.size();
      for (int i = 0; i < size; i++) {
        checkForConcurrentModifications(isModOutNeighbors());
        EdgeGraphNode node = outNeighbors.get(i);
        if (tryMark(node)) {
          while (true) {
            try {
              ctx.begin();
              body.call(node);
              ctx.commit(node);
              break;
            } catch (IterationAbortException _) {
              ctx.abort();
            }
          }
        }
      }
      checkForConcurrentModifications(isModOutNeighbors());
      setModOutNeighors(prevModOutNeighbors);
    }

    @Override
    public <A1> void mapInternal(Lambda2Void<GNode<N>, A1> body, MapInternalContext ctx, A1 arg1) {
      boolean prevModOutNeighbors = isModOutNeighbors();
      setModOutNeighors(false);
      int size = outNeighbors.size();
      for (int i = 0; i < size; i++) {
        checkForConcurrentModifications(isModOutNeighbors());
        EdgeGraphNode node = outNeighbors.get(i);
        if (tryMark(node)) {
          while (true) {
            try {
              ctx.begin();
              body.call(node, arg1);
              ctx.commit(node);
              break;
            } catch (IterationAbortException _) {
              ctx.abort();
            }
          }
        }
      }
      checkForConcurrentModifications(isModOutNeighbors());
      setModOutNeighors(prevModOutNeighbors);
    }

    @Override
    public <A1, A2> void mapInternal(Lambda3Void<GNode<N>, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2) {
      boolean prevModOutNeighbors = isModOutNeighbors();
      setModOutNeighors(false);
      int size = outNeighbors.size();
      for (int i = 0; i < size; i++) {
        checkForConcurrentModifications(isModOutNeighbors());
        EdgeGraphNode node = outNeighbors.get(i);
        if (tryMark(node)) {
          while (true) {
            try {
              ctx.begin();
              body.call(node, arg1, arg2);
              ctx.commit(node);
              break;
            } catch (IterationAbortException _) {
              ctx.abort();
            }
          }
        }
      }
      checkForConcurrentModifications(isModOutNeighbors());
      setModOutNeighors(prevModOutNeighbors);
    }

    @Override
    public void mapInternalDone() {
    }

    @Override
    public void map(LambdaVoid<GNode<N>> body, byte flags) {
      boolean prevModOutNeighbors = isModOutNeighbors();
      setModOutNeighors(false);
      int size = outNeighbors.size();
      for (int i = 0; i < size; i++) {
        checkForConcurrentModifications(isModOutNeighbors());
        EdgeGraphNode node = outNeighbors.get(i);
        body.call(node);
      }
      checkForConcurrentModifications(isModOutNeighbors());
      setModOutNeighors(prevModOutNeighbors);
    }

    @Override
    public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1) {
      map(body, arg1, MethodFlag.ALL);
    }

    @Override
    public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1, byte flags) {
      boolean prevModOutNeighbors = isModOutNeighbors();
      setModOutNeighors(false);
      int size = outNeighbors.size();
      for (int i = 0; i < size; i++) {
        checkForConcurrentModifications(isModOutNeighbors());
        EdgeGraphNode node = outNeighbors.get(i);
        body.call(node, arg1);
      }
      checkForConcurrentModifications(isModOutNeighbors());
      setModOutNeighors(prevModOutNeighbors);
    }

    @Override
    public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2) {
      map(body, arg1, arg2, MethodFlag.ALL);
    }

    @Override
    public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2, byte flags) {
      boolean prevModOutNeighbors = isModOutNeighbors();
      setModOutNeighors(false);
      int size = outNeighbors.size();
      for (int i = 0; i < size; i++) {
        checkForConcurrentModifications(isModOutNeighbors());
        EdgeGraphNode node = outNeighbors.get(i);
        body.call(node, arg1, arg2);
      }
      checkForConcurrentModifications(isModOutNeighbors());
      setModOutNeighors(prevModOutNeighbors);
    }

    private boolean isIn() {
      return (flags & IN_MASK) != 0;
    }

    private void setIn(boolean value) {
      flags = value ? (byte) (flags | IN_MASK) : (byte) (flags & ~IN_MASK);
    }

    private boolean isModInNeighbors() {
      return (flags & MOD_IN) != 0;
    }

    private void setModInNeighors(boolean value) {
      flags = value ? (byte) (flags | MOD_IN) : (byte) (flags & ~MOD_IN);
    }

    private boolean isModOutNeighbors() {
      return (flags & MOD_OUT) != 0;
    }

    private void setModOutNeighors(boolean value) {
      flags = value ? (byte) (flags | MOD_OUT) : (byte) (flags & ~MOD_OUT);
    }
  }

  @Override
  public void access(byte flags) {
  }
}
