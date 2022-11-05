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

File: ArrayIndexedTree.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.objects.MethodFlag;
import galois.runtime.GaloisRuntime;
import galois.runtime.IterationAbortException;
import galois.runtime.MapInternalContext;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

/**
 * Implementation of the {@link galois.objects.graph.IndexedGraph} interface.
 *
 * @param <N> type of the data contained in a node
 */
public final class ArrayIndexedTree<N extends GObject> implements IndexedGraph<N> {
  private final int maxNeighbors;
  private final AtomicReference<LinkedNode> head;
  private final AtomicInteger size;

  // XXX(ddn): Vulnerable to ABAB problem when we wrap integers
  private int mapVersionNumber = 1;
  private static final int chunkSize = 64;

  private ArrayIndexedTree(int capacity) {
    assert (capacity > 0);
    maxNeighbors = capacity;
    head = new AtomicReference<LinkedNode>();
    size = new AtomicInteger(0);
  }

  /**
   * A {@link ArrayIndexedTree} builder, providing combinations of several features.
   */
  public static class Builder {
    private int branchingFactor = 2;
    private boolean serial = false;

    /**
     * Constructs a new builder instance with the following default settings: the tree will be parallel, and have a
     * branching factor of two
     */
    public Builder() {
    }

    /**
     * Specifies the maximum number of children for a node in the tree.
     *
     * @param branchingFactor Branching factor of the tree
     */
    public Builder branchingFactor(int branchingFactor) {
      this.branchingFactor = branchingFactor;
      return this;
    }

    /**
     * Indicates whether the implementation of the tree about to be created is serial (there is no concurrency or
     * transactional support) or parallel (can be safely used within Galois iterators). For example, a tree that is
     * purely thread local can benefit from using a serial implementation, which is expected to add no overheads
     * due to concurrency or the runtime system.
     *
     * @param serial boolean value that indicates whether the tree is serial or not.
     */
    public Builder serial(boolean serial) {
      this.serial = serial;
      return this;
    }

    /**
     * Builds the final tree. This method does not alter the state of this
     * builder instance, so it can be invoked again to create
     * multiple independent trees.
     *
     * @param <N> the type of the object stored in each node
     * @return a indexed tree with the requested features
     */
    public <N extends GObject> IndexedGraph<N> create() {
      IndexedGraph<N> retval;
      if (serial || GaloisRuntime.getRuntime().useSerial()) {
        retval = new SerialArrayIndexedTree<N>(branchingFactor);
      } else {
        retval = new ArrayIndexedTree<N>(branchingFactor);
      }

      if (GaloisRuntime.getRuntime().ignoreUserFlags())
        retval = new IndexedGraphToAllIndexedGraphAdapter<N>(retval);

      return retval;
    }
  }

  @Override
  public final GNode<N> createNode(final N n) {
    return createNode(n, MethodFlag.ALL);
  }

  @Override
  public GNode<N> createNode(final N n, byte flags) {
    GNode<N> ret = new IndexedTreeNode(n);
    ObjectGraphLocker.createNodeEpilog(ret, flags);
    return ret;
  }

  @Override
  public final boolean add(GNode<N> src) {
    return add(src, MethodFlag.ALL);
  }

  @Override
  public boolean add(GNode<N> src, byte flags) {
    IndexedTreeLocker.addNodeProlog(src, flags);
    if (((IndexedTreeNode) src).add(this)) {
      size.incrementAndGet();
      IndexedTreeLocker.addNodeEpilog(this, src, flags);
      return true;
    }
    return false;
  }

  @Override
  public final boolean remove(GNode<N> src) {
    return remove(src, MethodFlag.ALL);
  }

  @Override
  public boolean remove(GNode<N> src, byte flags) {
    // grab a lock on src if needed
    if (!contains(src, flags)) {
      return false;
    }
    IndexedTreeLocker.removeNodeProlog(src, flags);
    size.decrementAndGet();
    IndexedTreeNode ignsrc = (IndexedTreeNode) src;
    for (int i = 0; i < maxNeighbors; i++) {
      if (ignsrc.child[i] != null) {
        removeNeighbor(ignsrc, i, flags);
      }
    }
    if (ignsrc.parent != null) {
      removeNeighbor(ignsrc.parent, ignsrc, flags);
    }
    boolean ret = ignsrc.remove(this);
    // has to be there, because containsNode returned true and we have the lock
    // on the node
    assert ret;
    return true;
  }

  @Override
  public final boolean contains(GNode<N> src) {
    return contains(src, MethodFlag.ALL);
  }

  @Override
  public boolean contains(GNode<N> src, byte flags) {
    IndexedTreeLocker.containsNodeProlog(src, flags);
    return ((IndexedTreeNode) src).inGraph(this);
  }

  @Override
  public final int size() {
    return size(MethodFlag.ALL);
  }

  @Override
  public int size(byte flags) {
    IndexedTreeLocker.sizeProlog(flags);
    int ret = size.get();
    assert ret >= 0;
    return ret;
  }

  // This method is not supported in an IndexedGraph because it is
  // not clear which neighbor the added neighbor should become.

  @Override
  public final boolean addNeighbor(GNode<N> src, GNode<N> dst) {
    throw new UnsupportedOperationException("addNeighbor not supported in IndexedGraphs");
  }

  @Override
  public boolean addNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    throw new UnsupportedOperationException("addNeighbor not supported in IndexedGraphs");
  }

  @Override
  public final boolean removeNeighbor(GNode<N> src, GNode<N> dst) {
    return removeNeighbor(src, dst, MethodFlag.ALL);
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    IndexedTreeLocker.removeNeighborProlog(src, dst, flags);
    int idx = childIndex(src, dst);
    if (0 <= idx) {
      removeNeighbor(src, idx, flags);
      return true;
    }
    return false;
  }

  @Override
  public final boolean hasNeighbor(GNode<N> src, GNode<N> dst) {
    return hasNeighbor(src, dst, MethodFlag.ALL);
  }

  @Override
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst, byte flags) {
    IndexedTreeLocker.hasNeighborProlog(src, dst, flags);
    return 0 <= childIndex(src, dst);
  }

  @Override
  public final void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> body) {
    mapInNeighbors(src, body, MethodFlag.ALL);
  }

  @Override
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> closure, byte flags) {
    IndexedTreeLocker.mapInNeighborsProlog(this, src, flags);
    IndexedTreeNode n = (IndexedTreeNode) src;
    if (n.parent != null) {
      closure.call(n.parent);
    }
  }

  @Override
  public final int inNeighborsSize(GNode<N> src) {
    return inNeighborsSize(src, MethodFlag.ALL);
  }

  @Override
  public int inNeighborsSize(GNode<N> src, byte flags) {
    IndexedTreeLocker.inNeighborsSizeProlog(this, src, flags);
    IndexedTreeNode ignsrc = (IndexedTreeNode) src;
    if (ignsrc.parent != null) {
      return 1;
    }
    return 0;
  }

  @Override
  public final int outNeighborsSize(GNode<N> src) {
    return outNeighborsSize(src, MethodFlag.ALL);
  }

  @Override
  public int outNeighborsSize(GNode<N> src, byte flags) {
    IndexedTreeLocker.outNeighborsSizeProlog(src, flags);
    IndexedTreeNode ignsrc = (IndexedTreeNode) src;
    int cnt = 0;
    for (int i = 0; i < maxNeighbors; i++) {
      if (ignsrc.child[i] != null) {
        cnt++;
      }
    }
    return cnt;
  }

  @Override
  public boolean isDirected() {
    return true;
  }

  @Override
  public final void setNeighbor(GNode<N> src, GNode<N> dst, int idx) {
    setNeighbor(src, dst, idx, MethodFlag.ALL);
  }

  @Override
  public void setNeighbor(GNode<N> src, GNode<N> dst, int idx, byte flags) {
    IndexedTreeLocker.setNeighborProlog(src, dst, flags);
    IndexedTreeNode ignsrc = (IndexedTreeNode) src;
    IndexedTreeNode old = ignsrc.child[idx];
    if (old != dst) {
      IndexedTreeNode igndst = (IndexedTreeNode) dst;
      if (igndst.parent != null) {
        removeNeighbor(igndst.parent, igndst, flags);
      }
      igndst.parent = ignsrc;
      if (old != null) {
        removeNeighbor(ignsrc, idx, flags);
      }
      ignsrc.child[idx] = igndst;
      IndexedTreeLocker.setNeighborEpilog(old, flags);
    }
  }

  @Override
  public final GNode<N> getNeighbor(GNode<N> node, int idx) {
    return getNeighbor(node, idx, MethodFlag.ALL);
  }

  @Override
  public GNode<N> getNeighbor(GNode<N> node, int idx, byte flags) {
    IndexedTreeLocker.getNeighborProlog(node, flags);
    IndexedTreeNode ignode = (IndexedTreeNode) node;
    GNode<N> ret = ignode.child[idx];
    if (ret != null) {
      IndexedTreeLocker.getNeighborEpilog(ret, flags);
    }
    return ret;
  }

  @Override
  public final boolean removeNeighbor(GNode<N> node, int idx) {
    return removeNeighbor(node, idx, MethodFlag.ALL);
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, int idx, byte flags) {
    IndexedTreeLocker.removeNeighborProlog(src, flags);
    IndexedTreeNode ignsrc = (IndexedTreeNode) src;
    IndexedTreeNode child = ignsrc.child[idx];
    if (child != null) {
      ignsrc.child[idx].parent = null;
      ignsrc.child[idx] = null;
      IndexedTreeLocker.removeNeighborEpilog(this, src, child, idx, flags);
      return true;
    }
    return false;
  }

  private int childIndex(GNode<N> src, GNode<N> dst) {
    IndexedTreeNode ignsrc = (IndexedTreeNode) src;
    List<IndexedTreeNode> neighborList = Arrays.asList(ignsrc.child);
    return neighborList.indexOf(dst);
  }

  private boolean tryMark(IndexedTreeNode curr) {
    int old = curr.iterateVersion.get();
    if (old == mapVersionNumber) {
      return false;
    }

    return curr.iterateVersion.compareAndSet(old, mapVersionNumber);
  }

  private IndexedTreeNode scanForNode(LinkedNode start) {
    while (start != null) {
      if (start.isDummy()) {
        start = start.getNext();
      } else {
        return (IndexedTreeNode) start;
      }
    }
    return null;
  }

  @Override
  public void mapInternal(LambdaVoid<GNode<N>> body, MapInternalContext ctx) {
    IndexedTreeNode lastSuccess = null;
    IndexedTreeNode begin = scanForNode(head.get());
    int[] holder = new int[1];

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
        if (lastSuccess != null) {
          lastSuccess.iterateNext.set(begin, mapVersionNumber);
        }
        lastSuccess = begin;
      } else {
        IndexedTreeNode next = begin.iterateNext.get(holder);
        if (holder[0] == mapVersionNumber) {
          begin = next;
          continue;
        }
      }

      IndexedTreeNode cur = begin;

      for (int i = 0; i < chunkSize; i++) {
        if (owned) {
          while (true) {
            try {
              // Help out GC
              cur.iterateNext.set(null, 0);
              ctx.begin();
              body.call(cur);
              ctx.commit(cur);
              if (i != 0)
                cur.iterateVersion.set(0);
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
    IndexedTreeNode lastSuccess = null;
    IndexedTreeNode begin = scanForNode(head.get());
    int[] holder = new int[1];

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
        if (lastSuccess != null) {
          lastSuccess.iterateNext.set(begin, mapVersionNumber);
        }
        lastSuccess = begin;
      } else {
        IndexedTreeNode next = begin.iterateNext.get(holder);
        if (holder[0] == mapVersionNumber) {
          begin = next;
          continue;
        }
      }

      IndexedTreeNode cur = begin;

      for (int i = 0; i < chunkSize; i++) {
        if (owned) {
          while (true) {
            try {
              // Help out GC
              cur.iterateNext.set(null, 0);
              ctx.begin();
              body.call(cur, arg1);
              ctx.commit(cur);
              if (i != 0)
                cur.iterateVersion.set(0);
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
  public <A1, A2> void mapInternal(Lambda3Void<GNode<N>, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2) {
    IndexedTreeNode lastSuccess = null;
    IndexedTreeNode begin = scanForNode(head.get());
    int[] holder = new int[1];

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
        if (lastSuccess != null) {
          lastSuccess.iterateNext.set(begin, mapVersionNumber);
        }
        lastSuccess = begin;
      } else {
        IndexedTreeNode next = begin.iterateNext.get(holder);
        if (holder[0] == mapVersionNumber) {
          begin = next;
          continue;
        }
      }

      IndexedTreeNode cur = begin;

      for (int i = 0; i < chunkSize; i++) {
        if (owned) {
          while (true) {
            try {
              // Help out GC
              cur.iterateNext.set(null, 0);
              ctx.begin();
              body.call(cur, arg1, arg2);
              ctx.commit(cur);
              if (i != 0)
                cur.iterateVersion.set(0);
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
    IndexedTreeLocker.mapProlog(flags);
    LinkedNode curr = head.get();
    while (curr != null) {
      if (!curr.isDummy()) {
        IndexedTreeNode gsrc = (IndexedTreeNode) curr;
        assert gsrc.in;
        body.call(gsrc);
      }
      curr = curr.getNext();
    }
  }

  @Override
  public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1) {
    map(body, arg1, MethodFlag.ALL);
  }

  @Override
  public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1, byte flags) {
    IndexedTreeLocker.mapProlog(flags);
    LinkedNode curr = head.get();
    while (curr != null) {
      if (!curr.isDummy()) {
        IndexedTreeNode gsrc = (IndexedTreeNode) curr;
        assert gsrc.in;
        body.call(gsrc, arg1);
      }
      curr = curr.getNext();
    }
  }

  @Override
  public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2) {
    map(body, arg1, arg2, MethodFlag.ALL);
  }

  @Override
  public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2, byte flags) {
    IndexedTreeLocker.mapProlog(flags);
    LinkedNode curr = head.get();
    while (curr != null) {
      if (!curr.isDummy()) {
        IndexedTreeNode gsrc = (IndexedTreeNode) curr;
        assert gsrc.in;
        body.call(gsrc, arg1, arg2);
      }
      curr = curr.getNext();
    }
  }

  @Override
  public void mapInternalDone() {
    if (++mapVersionNumber == 0) {
      mapVersionNumber = 1;
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

  protected final class IndexedTreeNode extends ConcurrentGNode<N> implements LinkedNode {
    private final AtomicStampedReference<IndexedTreeNode> iterateNext = new AtomicStampedReference<IndexedTreeNode>(
        null, 0);
    private final AtomicInteger iterateVersion = new AtomicInteger();
    protected N data;
    protected IndexedTreeNode[] child;
    protected IndexedTreeNode parent;
    private boolean in;
    private LinkedNode dummy;
    private LinkedNode next;

    public IndexedTreeNode(N nodedata) {
      data = nodedata;
      child = (IndexedTreeNode[]) Array.newInstance(this.getClass(), maxNeighbors);
      Arrays.fill(child, null);
      parent = null;
    }

    private boolean inGraph(ArrayIndexedTree<N> g) {
      return ArrayIndexedTree.this == g && in;
    }

    private boolean add(ArrayIndexedTree<N> g) {
      if (ArrayIndexedTree.this != g) {
        // XXX(ddn): Nodes could belong to more than 1 graph, but since
        // this rarely happens in practice, simplify implementation
        // assuming that this doesn't occur
        throw new UnsupportedOperationException("cannot add nodes created by a different graph");
      }

      if (!in) {
        in = true;
        dummy = new DummyLinkedNode();
        dummy.setNext(this);

        LinkedNode currHead;
        do {
          currHead = head.get();
          next = currHead;
        } while (!head.compareAndSet(currHead, dummy));
        return true;
      }
      return false;
    }

    private boolean remove(ArrayIndexedTree<N> g) {
      if (inGraph(g)) {
        in = false;
        iterateNext.set(null, 0);
        iterateVersion.set(0);
        dummy.setNext(next);
        return true;
      }
      return false;
    }

    @Override
    public boolean isDummy() {
      return false;
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
    public final N getData() {
      return getData(MethodFlag.ALL);
    }

    @Override
    public final N getData(byte flags) {
      return getData(flags, flags);
    }

    @Override
    public N getData(byte nodeFlags, byte dataFlags) {
      IndexedTreeLocker.getNodeDataProlog(this, nodeFlags);
      N ret = data;
      IndexedTreeLocker.getNodeDataEpilog(ret, dataFlags);
      return ret;
    }

    @Override
    public final N setData(N data) {
      return setData(data, MethodFlag.ALL);
    }

    @Override
    public N setData(N data, byte flags) {
      IndexedTreeLocker.setNodeDataProlog(this, flags);
      N oldData = this.data;
      this.data = data;
      IndexedTreeLocker.setNodeDataEpilog(this, oldData, flags);
      return oldData;
    }

    @Override
    public void mapInternal(LambdaVoid<GNode<N>> body, MapInternalContext ctx) {
      for (int i = 0; i < maxNeighbors; i++) {
        IndexedTreeNode c = child[i];
        if (c != null) {
          if (tryMark(c)) {
            while (true) {
              try {
                ctx.begin();
                body.call(c);
                ctx.commit(c);
                break;
              } catch (IterationAbortException _) {
                ctx.abort();
              }
            }
          }
        }
      }
    }

    @Override
    public <A1> void mapInternal(Lambda2Void<GNode<N>, A1> body, MapInternalContext ctx, A1 arg1) {
      for (int i = 0; i < maxNeighbors; i++) {
        IndexedTreeNode c = child[i];
        if (c != null) {
          if (tryMark(c)) {
            while (true) {
              try {
                ctx.begin();
                body.call(c, arg1);
                ctx.commit(c);
                break;
              } catch (IterationAbortException _) {
                ctx.abort();
              }
            }
          }
        }
      }
    }

    @Override
    public <A1, A2> void mapInternal(Lambda3Void<GNode<N>, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2) {
      for (int i = 0; i < maxNeighbors; i++) {
        IndexedTreeNode c = child[i];
        if (c != null) {
          if (tryMark(c)) {
            while (true) {
              try {
                ctx.begin();
                body.call(c, arg1, arg2);
                ctx.commit(c);
                break;
              } catch (IterationAbortException _) {
                ctx.abort();
              }
            }
          }
        }
      }
    }

    @Override
    public final void map(LambdaVoid<GNode<N>> body) {
      map(body, MethodFlag.ALL);
    }

    @Override
    public void map(LambdaVoid<GNode<N>> body, byte flags) {
      IndexedTreeLocker.mapOutNeighborsProlog(this, flags);
      for (int i = 0; i < maxNeighbors; i++) {
        IndexedTreeNode c = child[i];
        if (c != null) {
          body.call(c);
        }
      }
    }

    @Override
    public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1) {
      map(body, arg1, MethodFlag.ALL);
    }

    @Override
    public <A1> void map(Lambda2Void<GNode<N>, A1> body, A1 arg1, byte flags) {
      IndexedTreeLocker.mapOutNeighborsProlog(this, flags);
      for (int i = 0; i < maxNeighbors; i++) {
        IndexedTreeNode c = child[i];
        if (c != null) {
          body.call(c, arg1);
        }
      }
    }

    @Override
    public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2) {
      map(body, arg1, arg2, MethodFlag.ALL);
    }

    @Override
    public <A1, A2> void map(Lambda3Void<GNode<N>, A1, A2> body, A1 arg1, A2 arg2, byte flags) {
      IndexedTreeLocker.mapOutNeighborsProlog(this, flags);
      for (int i = 0; i < maxNeighbors; i++) {
        IndexedTreeNode c = child[i];
        if (c != null) {
          body.call(c, arg1, arg2);
        }
      }
    }

    @Override
    public void mapInternalDone() {
      if (++mapVersionNumber == 0) {
        mapVersionNumber = 1;
      }
    }
  }

  @Override
  public void access(byte flags) {
  }
}
