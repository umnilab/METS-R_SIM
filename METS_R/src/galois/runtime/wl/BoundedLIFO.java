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

/**
 * Order elements in last-in-first-order order, i.e., stack order. This order may only
 * contain up to <i>N</i> elements.
 * 
 *
 * @param <T>  the type of elements of the worklist
 * @see LIFO
 */
@OnlyLeaf
@MatchingConcurrentVersion(ConcurrentBoundedLIFO.class)
@MatchingLeafVersion(BoundedLIFO.class)
public class BoundedLIFO<T> implements Worklist<T> {
  public static final int DEFAULT_MAX_ELEMENTS = 32;

  private final Object[] buffer;
  private int top;

  /**
   * Creates a bounded LIFO order with the default maximum number of elements ({@value #DEFAULT_MAX_ELEMENTS})
   */
  public BoundedLIFO(Maker<T> maker, boolean needSize) {
    this(DEFAULT_MAX_ELEMENTS, maker, needSize);
  }

  /**
   * Creates a bounded LIFO order with the given maximum number of elements
   * @param maxElements  the maximum number of elements
   */
  public BoundedLIFO(int maxElements, Maker<T> maker, boolean needSize) {
    buffer = new Object[maxElements];
    top = 0;
  }

  @Override
  public Worklist<T> newInstance() {
    return new BoundedLIFO<T>(buffer.length, null, false);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    buffer[top++] = item;
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @SuppressWarnings("unchecked")
  @Override
  public T poll(ForeachContext<T> ctx) {
    if (top > 0)
      return (T) buffer[--top];
    else
      return null;
  }

  @Override
  public int size() {
    return top;
  }

  @Override
  public boolean isEmpty() {
    return top == 0;
  }

  @Override
  public void finishAddInitial() {

  }
}
