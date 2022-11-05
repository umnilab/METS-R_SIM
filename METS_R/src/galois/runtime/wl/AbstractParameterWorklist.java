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

import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

import java.util.NoSuchElementException;

/**
 * Abstract class which defines the worklist that ParaMeter operates over.
 * Internally, all ParaMeter worklists contain two queues, one holding elements
 * left to execute in the current round, the other holding elements to execute
 * in the next round.
 * 
 * @author milind
 * 
 * @param <T>
 *          The type of element held in the worklist (visible to the user)
 * @param <S>
 *          The type of element held in the internal worklist and used by
 *          ParaMeter executors
 */
public abstract class AbstractParameterWorklist<T, S> implements ParameterWorklist<T, S> {
  protected InternalParameterQueue<S> next;
  protected InternalParameterQueue<S> current;

  protected AbstractParameterWorklist() {
    next = makeInternalQueue();
    current = makeInternalQueue();
  }

  /**
   * Creates an internal queue for use in {@link AbstractParameterWorklist}. A parameter
   * worklist is implemented with two internal queues: one queue for the current time
   * step and one queue for the next time step.
   * 
   * @return  an internal queue
   */
  protected abstract InternalParameterQueue<S> makeInternalQueue();

  /**
   * Wrap an object of type <code>T</code> in an object of type <code>S</code>. Used
   * because user code
   * adds <code>T</code>s to the worklist, but ParaMeter operates over <code>S</code>s
   * 
   * @param x   the object to wrap
   * @return x  wrapped in an object of type <code>S</code> (or, if <code>S</code>
   *            is the same as <code>T</code>, just <code>x</code>)
   */
  protected abstract S wrap(T x);

  @Override
  public void add(T x) {
    final S wx = wrap(x);
    if (GaloisRuntime.getRuntime().inRoot()) {
      current.add(wx);
    } else {
      GaloisRuntime.getRuntime().onCommit(Iteration.getCurrentIteration(), new Callback() {

        @Override
        public void call() {
          next.add(wx);
        }
      });
    }
  }

  @Override
  public S internalNext() throws NoSuchElementException {
    if (!current.isEmpty()) {
      return current.poll();
    } else {
      throw new NoSuchElementException();
    }
  }

  @Override
  public T next() throws NoSuchElementException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    current.clear();
    next.clear();
  }

  @Override
  public void defer(S x) {
    next.add(x);
  }

  @Override
  public void nextStep() {
    current = next;
    next = makeInternalQueue();
  }

  @Override
  public boolean isEmpty() {
    return current.isEmpty();
  }

  @Override
  public int size() {
    return current.size();
  }

  @Override
  public void deferRemainingItems() {
    while (!isEmpty()) {
      defer(internalNext());
    }
  }

  /**
   * An internal parameter queue
   * 
   * @param <E>  type of elements in queue
   */
  protected interface InternalParameterQueue<E> {
    public void add(E o);

    public E poll();

    public boolean isEmpty();

    public int size();

    public InternalParameterQueue<E> copy();

    public void clear();
  }
}
