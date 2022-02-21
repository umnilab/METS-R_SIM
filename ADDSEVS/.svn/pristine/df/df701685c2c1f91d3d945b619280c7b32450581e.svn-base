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

import java.util.Arrays;
import java.util.Random;

@OnlyLeaf
@MatchingConcurrentVersion(ConcurrentRandomOrder.class)
@MatchingLeafVersion(ConcurrentRandomOrder.class)
class ConcurrentRandomOrder<T> implements Worklist<T> {
  private int queueSize;
  private final Random rand;
  private T queue[];

  public ConcurrentRandomOrder(Maker<T> maker, boolean needSize) {
    this(1024, maker, needSize);
  }

  @SuppressWarnings("unchecked")
  public ConcurrentRandomOrder(int startingSize, Maker<T> maker, boolean needSize) {
    rand = new Random();
    queue = (T[]) new Object[startingSize];
    queueSize = 0;
  }

  @Override
  public Worklist<T> newInstance() {
    return new ConcurrentRandomOrder<T>(queue.length, null, false);
  }

  @Override
  public synchronized void add(T item, ForeachContext<T> ctx) {
    if (queueSize + 1 >= queue.length) {
      resize();
    }
    queue[queueSize++] = item;
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public synchronized T poll(ForeachContext<T> ctx) {
    T retval = null;
    if (queueSize != 0) {
      int bucket = rand.nextInt(queueSize);
      retval = queue[bucket];
      queue[bucket] = queue[queueSize - 1];
      queue[queueSize - 1] = null;
      queueSize--;
    }
    return retval;
  }

  @Override
  public synchronized boolean isEmpty() {
    return queueSize == 0;
  }

  @Override
  public synchronized int size() {
    return queueSize;
  }

  @Override
  public void finishAddInitial() {

  }

  private void resize() {
    queue = Arrays.copyOf(queue, queue.length * 2);
  }
}
