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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@OnlyLeaf
@MatchingConcurrentVersion(ConcurrentFIFO.class)
@MatchingLeafVersion(ConcurrentFIFO.class)
class ConcurrentFIFO<T> implements Worklist<T> {
  private final ConcurrentLinkedQueue<T> queue;
  private AtomicInteger size;

  public ConcurrentFIFO(Maker<T> maker, boolean needSize) {
    queue = new ConcurrentLinkedQueue<T>();
    if (needSize)
      size = new AtomicInteger();
  }

  @Override
  public Worklist<T> newInstance() {
    return new ConcurrentFIFO<T>(null, size != null);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    if (queue.add(item) && size != null) {
      size.incrementAndGet();
    }
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public boolean isEmpty() {
    return queue.isEmpty();
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    T retval = queue.poll();
    if (size != null && retval != null) {
      size.decrementAndGet();
    }
    return retval;
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
