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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@OnlyLeaf
@MatchingConcurrentVersion(ConcurrentRandomPermutation.class)
@MatchingLeafVersion(ConcurrentRandomPermutation.class)
class ConcurrentRandomPermutation<T> implements Worklist<T> {
  private static final int chunkSize = 2 * GaloisRuntime.getRuntime().getMaxThreads();
  private final List<T> items;
  private final Deque<T>[] inner;
  private final AtomicInteger cur;
  private AtomicInteger size;

  @SuppressWarnings("unchecked")
  public ConcurrentRandomPermutation(Maker<Integer> maker, boolean needSize) {
    items = new ArrayList<T>();
    cur = new AtomicInteger();

    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();
    inner = new Deque[numThreads];
    for (int i = 0; i < numThreads; i++) {
      inner[i] = new ArrayDeque<T>();
    }

    if (needSize)
      size = new AtomicInteger();
  }

  @Override
  public Worklist<T> newInstance() {
    return new ConcurrentRandomPermutation<T>(null, false);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    // Need to support in case of aborts
    if (size != null)
      size.incrementAndGet();

    inner[ctx.getThreadId()].add(item);
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    if (size != null)
      size.incrementAndGet();

    items.add(item);
  }

  @Override
  public void finishAddInitial() {
    // TODO(ddn): Parallelize this?
    Collections.shuffle(items);
    cur.set(items.size());
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();
    T retval = inner[tid].poll();
    if (retval == null) {
      int bottom = cur.addAndGet(-chunkSize);
      int top = bottom + chunkSize - 1;
      if (top >= 0)
        retval = items.get(top);

      for (int cur = Math.max(bottom, 0); cur < top; cur++)
        inner[tid].add(items.get(cur));
    }

    return retval;
  }

  @Override
  public boolean isEmpty() {
    return cur.get() < 0;
  }

  @Override
  public int size() {
    if (size != null) {
      return size.get();
    }
    throw new UnsupportedOperationException();
  }
}
