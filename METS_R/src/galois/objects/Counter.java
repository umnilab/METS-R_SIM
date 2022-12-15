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

File: Counter.java 

 */

package galois.objects;

import galois.runtime.ForeachContext;

/**
 * Counter objects suitable for use in Galois iterators. When a counter
 * reaches a given value, it will trigger an action like suspending
 * the current Galois iterator ({@link CounterToSuspendWith}) or
 * finishing a Galois iterator early without examining the rest of
 * the elements of its worklist ({@link CounterToFinish}).
 * 
 *
 * @param <T>  type of elements being iterated over in Galois iterator
 */
public interface Counter<T> extends GObject {

  /**
   * Increments counter by one.
   */
  public void increment(ForeachContext<T> ctx);

  /**
   * Increments counter by one.
   * 
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   */
  public void increment(ForeachContext<T> ctx, byte flags);

  /**
   * Increments counter by a delta.
   * 
   * @param delta  value to increment counter by  
   */
  public void increment(ForeachContext<T> ctx, int delta);

  /**
   * Increments counter by a delta.
   * 
   * @param delta  value to increment counter by  
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   */
  public void increment(ForeachContext<T> ctx, int delta, byte flags);

  /**
   * Increments counter by a delta; usable only outside a Galois iterator. It is
   * an error for increments by this method to trigger an action (as there
   * is no current Galois context).
   * 
   * @param delta  value to increment counter by  
   */
  public void increment(int delta);

  /**
   * Resets counter to zero; usuable only outside a Galois iterator. It is
   * an error for increments by this method to trigger an action (as there
   * is no current Galois context).
   */
  public void reset();
}
