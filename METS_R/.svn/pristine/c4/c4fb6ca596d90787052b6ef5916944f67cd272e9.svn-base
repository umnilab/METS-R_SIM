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

import galois.runtime.ForeachContext;
import galois.runtime.GaloisRuntime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@NestedAreSerial
@MatchingConcurrentVersion(ConcurrentChunkedLIFO.class)
@MatchingLeafVersion(ConcurrentChunkedLIFOLeaf.class)
class ConcurrentChunkedLIFO<T> implements Worklist<T> {
  private static final int CACHE_MULTIPLE = 16;

  private final int chunkSize;
  private Worklist<T>[] current;
  private final AtomicReference<Node<T>> head;
  private AtomicInteger size;

  public ConcurrentChunkedLIFO(Maker<T> maker, boolean needSize) {
    this(ChunkedLIFO.DEFAULT_CHUNK_SIZE, maker, needSize);
  }

  @SuppressWarnings("unchecked")
  public ConcurrentChunkedLIFO(int chunkSize, Maker<T> maker, boolean needSize) {
    this(chunkSize, (Worklist<T>[]) null, needSize);

    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();
    current = new Worklist[numThreads * CACHE_MULTIPLE];
    for (int i = 0; i < numThreads; i++) {
      current[getIndex(i)] = maker.make();
    }
  }

  private ConcurrentChunkedLIFO(int chunkSize, Worklist<T>[] current, boolean needSize) {
    this.chunkSize = chunkSize;
    this.current = current;
    this.head = new AtomicReference<Node<T>>();

    if (needSize)
      size = new AtomicInteger();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Worklist<T> newInstance() {
    int numThreads = current.length / CACHE_MULTIPLE;
    Worklist<T>[] c = new Worklist[numThreads * CACHE_MULTIPLE];
    for (int i = 0; i < numThreads; i++) {
      c[getIndex(i)] = current[getIndex(i)].newInstance();
    }
    return new ConcurrentChunkedLIFO<T>(chunkSize, c, size != null);
  }

  private int getIndex(int tid) {
    return tid * CACHE_MULTIPLE;
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();

    if (size != null)
      size.incrementAndGet();

    Worklist<T> c = current[getIndex(tid)];

    c.add(item, ctx);

    if (c.size() >= chunkSize) {
      addInternal(c);
      current[getIndex(tid)] = c.newInstance();
    }
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public T poll(final ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();

    if (current[getIndex(tid)] == null)
      current[getIndex(tid)] = pollInternal();

    T retval = null;
    while (current[getIndex(tid)] != null) {
      retval = current[getIndex(tid)].poll(ctx);

      if (retval == null) {
        current[getIndex(tid)] = pollInternal();
      } else {
        break;
      }
    }

    if (size != null && retval != null)
      size.decrementAndGet();

    return retval;
  }

  private void addInternal(Worklist<T> wl) {
    Node<T> next = new Node<T>(wl);
    Node<T> cur;
    do {
      cur = head.get();
      next.next = cur;
    } while (!head.compareAndSet(cur, next));
  }

  private Worklist<T> pollInternal() {
    Node<T> next;
    Node<T> cur;
    do {
      cur = head.get();
      if (cur == null)
        return null;
      next = cur.next;
    } while (!head.compareAndSet(cur, next));

    return cur.wl;
  }

  @Override
  public boolean isEmpty() {
    if (size != null) {
      return size.get() == 0;
    } else {
      return head.get() == null;
    }
  }

  @Override
  public int size() {
    if (size != null)
      return size.get();
    else
      throw new UnsupportedOperationException();
  }

  @Override
  public void finishAddInitial() {

  }

  private static class Node<T> {
    private Node<T> next;
    private Worklist<T> wl;

    public Node(Worklist<T> wl) {
      this.wl = wl;
    }
  }
}
