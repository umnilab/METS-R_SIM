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





package galois.runtime;

/**
 * Reference to the context calling {@link GaloisRuntime#foreach(Iterable, util.fn.Lambda2Void, galois.runtime.wl.Priority.Rule)}.
 * 
 * <p>
 * Provides methods to add to
 * the worklist of the Galois iterator ({@link #add(Object)}),
 * suspend the current iterator and execute some code serially
 * ({@link #suspendWith(Callback)}), and finish executing the
 * current iterator without examining all the elements in the
 * worklist ({@link #finish()}). In all cases, these methods do not appear
 * to take effect until the current iteration
 * commits. In the case of {@link #suspendWith(Callback)} and
 * {@link #finish()}, the corresponding effects will eventually
 * happen but not necessarily immediately after the iteration
 * commits. 
 *
 * @param <T>  type of elements of the Galois iterator
 */
public interface ForeachContext<T> {
  /**
   * Suspends the current iterator and calls the given function
   * serially. After the given function completes, the current
   * iterator resumes execution.
   * 
   * @param call  function to suspend with
   */
  public void suspendWith(Callback call);

  /**
   * Finishes executing the current iterator without examining
   * the rest of the elements on its worklist.
   */
  public void finish();

  /**
   * Adds an element to the worklist of the current iterator.
   * 
   * @param t  element to add
   */
  public void add(T t);

  /**
   * Adds an element to the worklist of the current iterator.
   * 
   * @param t  element to add
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   */
  public void add(T t, byte flags);

  public int getThreadId();

  public int getIterationId();
}
