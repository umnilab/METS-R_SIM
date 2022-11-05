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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@NestedAreSerial
@MatchingConcurrentVersion(ConcurrentChunkedFIFO.class)
@MatchingLeafVersion(ConcurrentChunkedFIFOLeaf.class)
class ConcurrentChunkedFIFO<T> implements Worklist<T> {
  private static final int CACHE_MULTIPLE = 16;

  private final int chunkSize;
  private Worklist<T>[] current;
  private Worklist<T>[] next;
  private final ConcurrentLinkedQueue<Worklist<T>> pool;
  private AtomicInteger size;

  public ConcurrentChunkedFIFO(Maker<T> maker, boolean needSize) {
    this(ChunkedFIFO.DEFAULT_CHUNK_SIZE, maker, needSize);
  }

  @SuppressWarnings("unchecked")
  public ConcurrentChunkedFIFO(int chunkSize, Maker<T> maker, boolean needSize) {
    this(chunkSize, null, null, needSize);

    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();
    current = new Worklist[numThreads * CACHE_MULTIPLE];
    next = new Worklist[numThreads * CACHE_MULTIPLE];
    for (int i = 0; i < numThreads; i++) {
      current[getIndex(i)] = maker.make();
      next[getIndex(i)] = maker.make();
    }
  }

  private ConcurrentChunkedFIFO(int chunkSize, Worklist<T>[] current, Worklist<T>[] next, boolean needSize) {
    this.chunkSize = chunkSize;
    this.current = current;
    this.next = next;

    pool = new ConcurrentLinkedQueue<Worklist<T>>();

    if (needSize)
      size = new AtomicInteger();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Worklist<T> newInstance() {
    int numThreads = current.length / CACHE_MULTIPLE;
    Worklist<T>[] c = new Worklist[numThreads * CACHE_MULTIPLE];
    Worklist<T>[] n = new Worklist[numThreads * CACHE_MULTIPLE];
    for (int i = 0; i < numThreads; i++) {
      c[getIndex(i)] = current[getIndex(i)].newInstance();
      n[getIndex(i)] = next[getIndex(i)].newInstance();
    }
    return new ConcurrentChunkedFIFO<T>(chunkSize, c, n, size != null);
  }

  private int getIndex(int tid) {
    return tid * CACHE_MULTIPLE;
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();
    int idx = getIndex(tid);

    if (size != null)
      size.incrementAndGet();

    Worklist<T> n = next[idx];

    n.add(item, ctx);

    if (n.size() >= chunkSize) {
      pool.add(n);
      next[idx] = n.newInstance();
    }
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public T poll(final ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();
    int idx = getIndex(tid);

    if (current[idx] == null)
      current[idx] = pool.poll();

    T retval = null;
    while (current[idx] != null) {
      retval = current[idx].poll(ctx);

      if (retval == null) {
        current[idx] = pool.poll();
      } else {
        break;
      }
    }

    // Current and poll are empty, try our next queue
    if (current[idx] == null) {
      retval = next[idx].poll(ctx);
    }

    if (size != null && retval != null)
      size.decrementAndGet();

    return retval;
  }

  @Override
  public boolean isEmpty() {
    if (size != null) {
      return size.get() == 0;
    } else {
      return pool.isEmpty();
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
}
