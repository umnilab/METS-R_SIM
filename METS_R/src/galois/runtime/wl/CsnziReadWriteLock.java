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


*/





package galois.runtime.wl;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Reader-writer locks based on closable scalable nonzero indicators (C-SNZI) from
 * Y. Lev, V. Luchangco, M.Olszewski. "Scalable Reader-Writer Locks". SPAA'09.
 * 
 */
class CsnziReadWriteLock implements ReadWriteLock {
  private final AtomicReference<Node> tail;
  private final AtomicInteger threadId;
  private final ThreadLocal<LocalState> localState;

  public CsnziReadWriteLock(int numThreads) {
    threadId = new AtomicInteger();
    tail = new AtomicReference<Node>();

    // Setup ring of ReadNodes
    final ReadNode[] rNodes = new ReadNode[numThreads];
    for (int i = 0; i < numThreads; i++) {
      rNodes[i] = new ReadNode(numThreads);
    }
    for (int i = 0; i < numThreads; i++) {
      rNodes[i].next = rNodes[(i + 1) % numThreads];
    }

    localState = new ThreadLocal<LocalState>() {
      @Override
      protected LocalState initialValue() {
        int id = threadId.getAndIncrement();
        return new LocalState(id, rNodes[id]);
      }
    };
  }

  private ReadNode allocateReadNode(LocalState local) {
    ReadNode cur = local.rNode;
    while (true) {
      if (!cur.inUse.get()) {
        if (cur.inUse.compareAndSet(false, true)) {
          return cur;
        }
      }
      cur = (ReadNode) cur.next;
    }
  }

  private void freeReadNode(ReadNode rNode) {
    rNode.inUse.set(false);
  }

  @Override
  public Lock readLock() {
    return new ReadLock();
  }

  @Override
  public Lock writeLock() {
    return new WriteLock();
  }

  private class ReadLock implements Lock {
    @Override
    public void lock() {
      ReadNode rNode = null;
      LocalState local = localState.get();

      while (true) {
        Node t = tail.get();
        if (t == null) {
          // No nodes in the queue
          if (rNode == null) {
            rNode = allocateReadNode(local);
          }
          rNode.spin = false;
          if (tail.compareAndSet(null, rNode)) {
            rNode.csnzi.open();
            local.ticket = rNode.csnzi.arrive(local.threadId);
            if (local.ticket != null) {
              local.departFrom = rNode;
              return;
            }
            rNode = null; // Avoid reusing inserted node
          }
        } else {
          // There is a node in the queue
          if (t instanceof WriteNode) {
            if (rNode == null) {
              rNode = allocateReadNode(local);
            }
            rNode.spin = true;
            if (tail.compareAndSet(t, rNode)) {
              t.qNext = rNode;

              local.ticket = rNode.csnzi.arrive(local.threadId);
              if (local.ticket != null) {
                local.departFrom = rNode;
                while (rNode.spin)
                  ;
                return;
              }
              rNode = null; // Avoid reusing inserted node
            }
          } else {
            // Otherwise, last node is a reader
            local.ticket = ((ReadNode) t).csnzi.arrive(local.threadId);
            if (local.ticket != null) {
              if (rNode != null)
                freeReadNode(rNode);
              local.departFrom = t;
              while (t.spin)
                ;
              return;
            }
          }
        }
      }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Condition newCondition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void unlock() {
      LocalState local = localState.get();
      ReadNode rNode = (ReadNode) local.departFrom;

      if (rNode.csnzi.depart(local.ticket)) {
        return;
      }
      rNode.qNext.spin = false;
      rNode.qNext = null;
      freeReadNode(rNode);
    }
  }

  private class WriteLock implements Lock {
    @Override
    public void lock() {
      LocalState local = localState.get();
      Node t = tail.getAndSet(local.wNode);
      if (t != null) {
        local.wNode.spin = true;
        t.qNext = local.wNode;
        if (t instanceof WriteNode) {
          while (local.wNode.spin) {
            ;
          }
        } else {
          ReadNode rNode = (ReadNode) t;
          // Wait until node is recycled
          while (!rNode.csnzi.isOpened())
            ;

          if (rNode.csnzi.close()) {
            while (t.spin)
              ;
            freeReadNode(rNode);
          } else {
            while (local.wNode.spin)
              ;
          }
        }
      }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Condition newCondition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void unlock() {
      LocalState local = localState.get();
      if (local.wNode.qNext == null) {
        if (tail.compareAndSet(local.wNode, null)) {
          return;
        } else {
          while (local.wNode.qNext == null)
            ;
        }
      }
      // Clean up
      local.wNode.qNext.spin = false;
      local.wNode.qNext = null;
    }
  }

  private final static class LocalState {
    private final int threadId;
    final ReadNode rNode;
    final WriteNode wNode;
    Node departFrom;
    CsnziNode ticket;

    public LocalState(int threadId, ReadNode rNode) {
      this.threadId = threadId;
      this.rNode = rNode;
      wNode = new WriteNode();
    }
  }

  private static abstract class Node {
    volatile boolean spin;
    volatile Node qNext;
  }

  private final static class ReadNode extends Node {
    Csnzi csnzi;
    Node next;
    AtomicBoolean inUse;

    public ReadNode(int numThreads) {
      csnzi = new Csnzi(numThreads);
      inUse = new AtomicBoolean();
    }
  }

  private final static class WriteNode extends Node {

  }

  private final static class Csnzi {
    private CsnziRootNode root;
    private final CsnziLeafNode[] leaves;

    public Csnzi(int numThreads) {
      root = new CsnziRootNode();
      leaves = new CsnziLeafNode[numThreads];
      for (int i = 0; i < numThreads; i++)
        leaves[i] = new CsnziLeafNode(root);
    }

    public CsnziNode arrive(int id) {
      while (true) {
        if (!root.isOpen())
          return null;
        if (shouldArriveAtRoot()) {
          if (root.tryIncrement())
            return root;
        } else {
          CsnziLeafNode leaf = getLeafNode(id);
          if (leaf.arrive()) {
            return leaf;
          } else {
            return null;
          }
        }
      }
    }

    public boolean depart(CsnziNode node) {
      return node.depart();
    }

    private boolean shouldArriveAtRoot() {
      // TODO(ddn): Could expand lock dynamically by adding leaves based
      // on failure rate
      return false;
    }

    private CsnziLeafNode getLeafNode(int id) {
      return leaves[id];
    }

    public void open() {
      root.open(0, false);
    }

    @SuppressWarnings("unused")
    public void open(int arrivals, boolean closed) {
      root.open(arrivals, closed);
    }

    public boolean isOpened() {
      return root.isOpen();
    }

    public boolean close() {
      return root.close();
    }

    @SuppressWarnings("unused")
    public boolean closeIfEmpty() {
      return root.closeIfEmpty();
    }
  }

  private abstract static class CsnziNode {
    public abstract boolean arrive();

    public abstract boolean depart();
  }

  private final static class CsnziRootNode extends CsnziNode {
    // state is bitstring: {count, opened|closed}
    // bit0: 0 => opened, 1=> closed
    // bit1-31: count
    private static final int CLOSED = 1;
    private static final int OPENED = 0;
    private final AtomicInteger state;

    public CsnziRootNode() {
      this.state = new AtomicInteger();
    }

    public boolean tryIncrement() {
      int value = state.get();
      // Adding by two increments count and keeps status flag the same
      return state.compareAndSet(value, value + 2);
    }

    @Override
    public boolean arrive() {
      int value;
      do {
        value = state.get();
        if (value == CLOSED)
          return false;
        // Adding by two increments count and keeps status flag the same
      } while (!state.compareAndSet(value, value + 2));

      return true;
    }

    @Override
    public boolean depart() {
      int value;
      do {
        value = state.get();
      } while (!state.compareAndSet(value, value - 2));

      return (value - 2) != CLOSED;
    }

    public boolean isOpen() {
      return (state.get() & 0x1) == OPENED;
    }

    public void open(int count, boolean closed) {
      if (closed) {
        state.set((count << 1) | CLOSED);
      } else {
        state.set((count << 1) | OPENED);
      }
    }

    public boolean close() {
      int value;
      int newValue;
      do {
        value = state.get();
        if ((value & 0x1) != OPENED)
          return false;
        newValue = value | CLOSED; // set flag bit
      } while (!state.compareAndSet(value, newValue));
      return newValue == CLOSED;
    }

    public boolean closeIfEmpty() {
      int value;
      do {
        value = state.get();
        if (value != OPENED)
          return false;
      } while (!state.compareAndSet(value, CLOSED));
      return true;
    }
  }

  private final static class CsnziLeafNode extends CsnziNode {
    private final AtomicInteger count;
    private final CsnziNode parent;

    public CsnziLeafNode(CsnziNode parent) {
      this.parent = parent;
      count = new AtomicInteger();
    }

    @Override
    public boolean arrive() {
      boolean arrivedAtParent = false;
      int value;
      do {
        value = count.get();
        if (value == 0 && !arrivedAtParent) {
          if (parent.arrive())
            arrivedAtParent = true;
          else
            return false;
        }
      } while (!count.compareAndSet(value, value + 1));
      if (arrivedAtParent && value != 0) {
        parent.depart();
      }
      return true;
    }

    @Override
    public boolean depart() {
      int value;
      do {
        value = count.get();
      } while (!count.compareAndSet(value, value - 1));
      if (value == 1) {
        return parent.depart();
      } else {
        return true;
      }
    }
  }
}
