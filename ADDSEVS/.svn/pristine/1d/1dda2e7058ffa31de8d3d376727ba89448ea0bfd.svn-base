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

/**
 * Order elements randomly. In contrast with {@link RandomPermutation},
 * elements can be added to this worklist during iteration. Elements are selected with
 * uniform probability. This rule has a relatively high synchronization
 * overhead when used concurrently, consider using {@link ChunkedRandomOrder} if
 * elements do not have to be selected with exact uniform probability.
 * 
 *
 * @param <T>  the type of elements of the worklist
 */
@OnlyLeaf
@MatchingConcurrentVersion(ConcurrentRandomOrder.class)
@MatchingLeafVersion(RandomOrder.class)
public class RandomOrder<T> implements Worklist<T> {
  private T queue[];
  private int size;
  private final Random rand;

  public RandomOrder(Maker<T> maker, boolean needSize) {
    this(32, maker, needSize);
  }

  @SuppressWarnings("unchecked")
  public RandomOrder(int startingSize, Maker<T> maker, boolean needSize) {
    queue = (T[]) new Object[startingSize];
    size = 0;
    rand = new Random();
  }

  @Override
  public Worklist<T> newInstance() {
    return new RandomOrder<T>(queue.length, null, false);
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    T retval = null;
    if (size != 0) {
      int bucket = rand.nextInt(size);
      retval = queue[bucket];
      queue[bucket] = queue[size - 1];
      queue[size - 1] = null;
      size--;
    }
    return retval;
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    if (size + 1 >= queue.length) {
      resize();
    }
    queue[size++] = item;
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public void finishAddInitial() {

  }

  private void resize() {
    queue = Arrays.copyOf(queue, queue.length * 2);
  }
}
