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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

@OnlyLeaf
@MatchingConcurrentVersion(ConcurrentChunkedRandomOrder.class)
@MatchingLeafVersion(ConcurrentChunkedRandomOrder.class)
class ConcurrentChunkedRandomOrder<T> implements Worklist<T> {
  private final int chunkSize;
  private final Random[] rand;
  private final int initialCapacity;
  private int queueSize;
  private T queue[];
  private Worklist<T>[] current;
  private AtomicInteger size;

  public ConcurrentChunkedRandomOrder(Maker<T> maker, boolean needSize) {
    this(ChunkedRandomOrder.DEFAULT_CHUNK_SIZE, ChunkedRandomOrder.DEFAULT_INITIAL_CAPACITY, maker, needSize);
  }

  public ConcurrentChunkedRandomOrder(int chunkSize, Maker<T> maker, boolean needSize) {
    this(chunkSize, ChunkedRandomOrder.DEFAULT_INITIAL_CAPACITY, maker, needSize);
  }

  @SuppressWarnings("unchecked")
  public ConcurrentChunkedRandomOrder(int chunkSize, int initialCapacity, Maker<T> maker, boolean needSize) {
    this(initialCapacity, initialCapacity, (Worklist<T>[]) null, needSize);
    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();

    current = new Worklist[numThreads];
    for (int i = 0; i < numThreads; i++) {
      current[i] = new BoundedLIFO(chunkSize, null, false);
    }
  }

  @SuppressWarnings("unchecked")
  private ConcurrentChunkedRandomOrder(int chunkSize, int initialCapacity, Worklist<T>[] current, boolean needSize) {
    this.chunkSize = chunkSize;
    this.initialCapacity = initialCapacity;
    this.current = current;

    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();
    rand = new Random[numThreads];
    for (int i = 0; i < numThreads; i++)
      rand[i] = new Random();

    queue = (T[]) new Object[initialCapacity];

    if (needSize)
      size = new AtomicInteger();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Worklist<T> newInstance() {
    int numThreads = current.length;
    Worklist<T>[] c = new Worklist[numThreads];
    for (int i = 0; i < numThreads; i++) {
      c[i] = current[i].newInstance();
    }

    return new ConcurrentChunkedRandomOrder<T>(chunkSize, initialCapacity, c, size != null);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();

    if (size != null)
      size.incrementAndGet();

    Worklist<T> c = current[tid];

    c.add(item, ctx);

    if (c.size() >= chunkSize) {
      synchronized (this) {
        T t;
        while ((t = c.poll(ctx)) != null) {
          addInternal(t);
        }
      }
    }
  }

  private void addInternal(T item) {
    if (queueSize + 1 >= queue.length) {
      resize();
    }
    queue[queueSize++] = item;
  }

  @Override
  public synchronized void addInitial(T item, ForeachContext<T> ctx) {
    if (size != null)
      size.incrementAndGet();

    addInternal(item);
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();
    T retval = current[tid].poll(ctx);

    if (retval == null) {
      synchronized (this) {
        for (int i = 0; i < chunkSize + 1; i++) {
          if (queueSize == 0) {
            break;
          }

          int bucket = rand[tid].nextInt(queueSize);
          T item = queue[bucket];
          if (retval == null)
            retval = item;
          else
            current[tid].add(item, ctx);

          queue[bucket] = queue[queueSize - 1];
          queue[queueSize - 1] = null;
          queueSize--;
        }
      }
    }

    if (size != null && retval != null)
      size.decrementAndGet();

    return retval;
  }

  @Override
  public synchronized boolean isEmpty() {
    return queueSize == 0;
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

  private void resize() {
    queue = Arrays.copyOf(queue, queue.length * 2);
  }
}
