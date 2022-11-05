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
 *          The type of element held in the internal worklist, and used by
 *          ParaMeter executors
 */
public interface ParameterWorklist<T, S> {
  public void add(T x);

  public T next() throws NoSuchElementException;

  public boolean isEmpty();

  public void clear();

  public S internalNext() throws NoSuchElementException;

  public void defer(S x);

  public void nextStep();

  public int size();

  public void deferRemainingItems();
}
