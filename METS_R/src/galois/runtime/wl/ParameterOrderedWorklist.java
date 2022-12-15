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

import java.util.Comparator;
import java.util.PriorityQueue;

import util.MutableReference;

/**
 * Worklist for use with ordered iterators in ParaMeter. 
 */
public class ParameterOrderedWorklist<T> extends AbstractParameterWorklist<T, MutableReference<T>> {
  private Comparator<T> comparator; // the comparator used to order the worklist

  public ParameterOrderedWorklist(Comparator<T> comparator) {
    this.comparator = comparator;

    /*
     * We need to reinitialize the two internal queues now that the comparator
     * is set.
     */
    current = makeInternalQueue();
    next = makeInternalQueue();
  }

  public Comparator<T> comparator() {
    return comparator;
  }

  public boolean canCommit(T x) {
    if (next.isEmpty()) {
      return true;
    }

    T top = ((OrderedList<T>) next).peek().get();

    return comparator.compare(x, top) <= 0;
  }

  @Override
  protected MutableReference<T> wrap(T x) {
    return new Box<T>(x);
  }

  @Override
  protected OrderedList<T> makeInternalQueue() {
    if (comparator == null) {
      /*
       * NB: The constructor for AbstractParameterWorklist calls this method
       * before comparator is initialized. We cannot simply throw an error in
       * this case (as this is expected behavior which is accounted for in the
       * constructor). However, if we simply initialize the OrderedList with a
       * null comparator, this produces a list which uses the natural ordering
       * of its elements to sort, which, if the constructor is ever changed, may
       * lead to unexpected behavior. We return null instead to make sure that
       * some sort of error gets thrown in this case.
       */
      return null;
    }
    return new OrderedList<T>(comparator);
  }

  private static class OrderedList<U> implements InternalParameterQueue<MutableReference<U>> {
    private PriorityQueue<MutableReference<U>> pq;
    private Comparator<U> comparator;

    private OrderedList(final Comparator<U> comparator) {
      this.comparator = comparator;

      pq = new PriorityQueue<MutableReference<U>>(20, new Comparator<MutableReference<U>>() {
        @Override
        public int compare(MutableReference<U> o1, MutableReference<U> o2) {
          return comparator.compare(o1.get(), o2.get());
        }
      });
    }

    @Override
    public void add(MutableReference<U> o) {
      pq.add(o);
    }

    @Override
    public boolean isEmpty() {
      return pq.isEmpty();
    }

    @Override
    public MutableReference<U> poll() {
      return pq.poll();
    }

    @Override
    public int size() {
      return pq.size();
    }

    public MutableReference<U> peek() {
      return pq.peek();
    }

    @Override
    public OrderedList<U> copy() {
      OrderedList<U> retval = new OrderedList<U>(comparator);
      retval.pq = new PriorityQueue<MutableReference<U>>(pq);
      return retval;
    }

    @Override
    public void clear() {
      pq.clear();
    }
  }

  private static class Box<S> extends MutableReference<S> {
    public Box(S obj) {
      super(obj);
    }

    @Override
    public boolean equals(Object o) {
      return this == o;
    }
  }

}
