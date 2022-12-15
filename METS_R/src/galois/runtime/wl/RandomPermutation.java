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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Order elements according to a random permutation of initial elements. Elements are ordered randomly
 * with respect to each other. After this worklist is initialized, no additional
 * elements may be added to this worklist. If elements need to be added during
 * iteration, consider {@link RandomOrder} or {@link ChunkedRandomOrder}.
 * 
 *
 * @param <T>  the type of elements of the worklist
 * @see RandomOrder
 * @see ChunkedRandomOrder
 */
@OnlyLeaf
@MatchingConcurrentVersion(ConcurrentRandomPermutation.class)
@MatchingLeafVersion(RandomPermutation.class)
public class RandomPermutation<T> implements Worklist<T> {
  private final List<T> items;

  public RandomPermutation(Maker<T> maker, boolean needSize) {
    this(needSize);
  }

  private RandomPermutation(boolean needSize) {
    items = new ArrayList<T>();
  }

  @Override
  public Worklist<T> newInstance() {
    return new RandomPermutation<T>(false);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    items.add(item);
  }

  @Override
  public void finishAddInitial() {
    Collections.shuffle(items);
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    int size = items.size();
    T retval = null;
    if (size != 0) {
      retval = items.remove(size - 1);
    }
    return retval;
  }

  @Override
  public boolean isEmpty() {
    return items.isEmpty();
  }

  @Override
  public int size() {
    return items.size();
  }
}
