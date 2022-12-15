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

import java.util.concurrent.atomic.AtomicInteger;

@OnlyLeaf
@MatchingConcurrentVersion(ConcurrentBoundedFIFO.class)
@MatchingLeafVersion(ConcurrentBoundedFIFO.class)
class ConcurrentBoundedFIFO<T> implements Worklist<T> {
  private final Object[] buffer;
  private final AtomicInteger widx;
  private int ridx;
  private final int mask;
  private AtomicInteger size;

  public ConcurrentBoundedFIFO(Maker<T> maker, boolean needSize) {
    this(BoundedFIFO.DEFAULT_MAX_ELEMENTS, maker, needSize);
  }

  public ConcurrentBoundedFIFO(int maxElements, Maker<T> maker, boolean needSize) {
    int logMax = 32 - Integer.numberOfLeadingZeros(maxElements - 1);
    mask = (1 << logMax) - 1;
    buffer = new Object[1 << logMax];
    widx = new AtomicInteger();
    ridx = 0;

    if (needSize)
      size = new AtomicInteger();
  }

  @Override
  public Worklist<T> newInstance() {
    return new ConcurrentBoundedFIFO<T>(buffer.length, null, size != null);
  }

  @Override
  public boolean isEmpty() {
    return (ridx & mask) == (widx.get() & mask);
  }

  @Override
  public int size() {
    if (size != null) {
      return size.get();
    } else {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    buffer[widx.getAndIncrement() & mask] = item;
    if (size != null)
      size.incrementAndGet();
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @SuppressWarnings("unchecked")
  @Override
  public T poll(ForeachContext<T> ctx) {
    // Spin here to protect against race in add between 
    // incrementing writerIndex and storing value
    int index = ridx & mask;
    Object v = null;
    do {
      // Create happens-before relationship between thread calling addLast and
      // the thread calling pollFirst
      if (isEmpty())
        return null;
    } while ((v = buffer[index]) == null);

    buffer[index] = null;

    ridx++;
    if (size != null)
      size.decrementAndGet();
    return (T) v;
  }

  @Override
  public void finishAddInitial() {

  }
}
