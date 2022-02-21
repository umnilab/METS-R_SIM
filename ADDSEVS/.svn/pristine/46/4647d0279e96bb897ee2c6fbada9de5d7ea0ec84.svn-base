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

File: SerialArrayIndexedTree.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.objects.MethodFlag;
import galois.runtime.MapInternalContext;
import galois.runtime.IterationAbortException;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;

import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

final class SerialArrayIndexedTree<N extends GObject> implements IndexedGraph<N> {
  private final int maxNeighbors;
  private LinkedNode head;
  private int size;

  private static final int chunkSize = 64;

  public SerialArrayIndexedTree(int capacity) {
    assert (capacity > 0);
    maxNeighbors = capacity;
    head = null;
    size = 0;
  }

  @Override
  public GNode<N> createNode(N data) {
    return createNode(data, MethodFlag.ALL);
  }

  @Override
  public GNode<N> createNode(N n, byte flags) {
    return new IndexedTreeNode(n);
  }

  @Override
  public final boolean add(GNode<N> src) {
    return add(src, MethodFlag.ALL);
  }

  @Override
  public boolean add(GNode<N> src, byte flags) {
    if (((IndexedTreeNode) src).add(this)) {
      size++;
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
    size--;
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
    return ((IndexedTreeNode) src).inGraph(this);
  }

  @Override
  public final int size() {
    return size(MethodFlag.ALL);
  }

  @Override
  public int size(byte flags) {
    int ret = size;
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
    return 0 <= childIndex(src, dst);
  }

  @Override
  public final void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> body) {
    mapInNeighbors(src, body, MethodFlag.ALL);
  }

  @Override
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> closure, byte flags) {
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
    }
  }

  @Override
  public final GNode<N> getNeighbor(GNode<N> node, int idx) {
    return getNeighbor(node, idx, MethodFlag.ALL);
  }

  @Override
  public GNode<N> getNeighbor(GNode<N> node, int idx, byte flags) {
    IndexedTreeNode ignode = (IndexedTreeNode) node;
    GNode<N> ret = ignode.child[idx];
    if (ret != null) {
    }
    return ret;
  }

  @Override
  public final boolean removeNeighbor(GNode<N> node, int idx) {
    return removeNeighbor(node, idx, MethodFlag.ALL);
  }

  @Override
  public boolean removeNeighbor(GNode<N> src, int idx, byte flags) {
    IndexedTreeNode ignsrc = (IndexedTreeNode) src;
    IndexedTreeNode child = ignsrc.child[idx];
    if (child != null) {
      ignsrc.child[idx].parent = null;
      ignsrc.child[idx] = null;
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
    return true;
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
    IndexedTreeNode begin = scanForNode(head);

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
      }

      IndexedTreeNode cur = begin;

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
    IndexedTreeNode begin = scanForNode(head);

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
      }

      IndexedTreeNode cur = begin;

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
  }

  @Override
  public <A1, A2> void mapInternal(Lambda3Void<GNode<N>, A1, A2> body, MapInternalContext ctx, A1 arg1, A2 arg2) {
    IndexedTreeNode begin = scanForNode(head);

    while (true) {
      if (begin == null) {
        break;
      }

      boolean owned = false;
      if (tryMark(begin)) {
        owned = true;
      }

      IndexedTreeNode cur = begin;

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
    LinkedNode curr = head;
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
    LinkedNode curr = head;
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
    LinkedNode curr = head;
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

  protected final class IndexedTreeNode extends SerialGNode<N> implements LinkedNode {
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

    private boolean tryMark(IndexedTreeNode curr) {
      return true;
    }

    private boolean inGraph(SerialArrayIndexedTree<N> g) {
      return SerialArrayIndexedTree.this == g && in;
    }

    private boolean add(SerialArrayIndexedTree<N> g) {
      if (SerialArrayIndexedTree.this != g) {
        // XXX(ddn): Nodes could belong to more than 1 graph, but since
        // this rarely happens in practice, simplify implementation
        // assuming that this doesn't occur
        throw new UnsupportedOperationException("cannot add nodes created by a different graph");
      }

      if (!in) {
        in = true;
        dummy = new DummyLinkedNode();
        dummy.setNext(this);

        LinkedNode currHead = head;
        next = currHead;
        head = dummy;
        return true;
      }
      return false;
    }

    private boolean remove(SerialArrayIndexedTree<N> g) {
      if (inGraph(g)) {
        in = false;
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
    public N getData(byte flags) {
      return getData(flags, flags);
    }

    @Override
    public N getData(byte nodeFlags, byte dataFlags) {
      N ret = data;
      return ret;
    }

    @Override
    public final N setData(N data) {
      return setData(data, MethodFlag.ALL);
    }

    @Override
    public N setData(N data, byte flags) {
      N oldData = this.data;
      this.data = data;
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
      for (int i = 0; i < maxNeighbors; i++) {
        IndexedTreeNode c = child[i];
        if (c != null) {
          body.call(c, arg1, arg2);
        }
      }
    }

    @Override
    public void mapInternalDone() {
    }
  }

  @Override
  public void access(byte flags) {
  }
}
