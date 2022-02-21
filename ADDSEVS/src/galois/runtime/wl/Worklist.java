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
 * Worklist used by Galois iterators. Worklists are not intended to be
 * instantiated directly but rather by passing an ordering rule to
 * the iterator.
 * 
 *
 * @param <T>  type of elements contained in the worklist
 * @see galois.runtime.wl.Priority.Rule
 * @see galois.runtime.GaloisRuntime#foreach(Iterable, util.fn.Lambda2Void, galois.runtime.wl.Priority.Rule)
 */
public interface Worklist<T> {
  /**
   * Adds an element to this worklist. This method is used when adding elements
   * from the initial elements passed to an executor. Thread-safe.
   * 
   * @param item  the item to add
   * @param ctx   an executor context
   */
  public void addInitial(T item, ForeachContext<T> ctx);

  /**
   * Marks when no more elements will be added from the initial elements
   * passed to an executor.
   */
  public void finishAddInitial();

  /**
   * Adds an element to this worklist. This method is used for newly generated
   * elements or elements added during Galois execution. Thread-safe.
   * 
   * @param item  the item to add
   * @param ctx   an executor context
   */
  public void add(T item, ForeachContext<T> ctx);

  /**
   * Removes an element from this worklist. Thread-safe.
   * 
   * @param ctx   an executor context
   * @return      an element or <code>null</code> if there are no more elements in this
   *              worklist
   */
  public T poll(ForeachContext<T> ctx);

  /**
   * Checks for emptiness. Only called by one thread at
   * a time so does not have to be thread-safe. Also,
   * can assume that all threads have had {@link #poll(ForeachContext)} return
   * <code>null</code>.
   * @return  true if there are no more elements in this worklist
   */
  public boolean isEmpty();

  /**
   * @return  the number of elements in this worklist, during concurrent execution
   *          this may be a lower bound on the number of elements
   */
  public int size();

  /**
   * @return  a new, empty instance of this worklist
   */
  public Worklist<T> newInstance();
}
