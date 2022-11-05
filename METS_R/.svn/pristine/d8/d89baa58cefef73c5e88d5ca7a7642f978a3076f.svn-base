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
 * Reference to the context calling a <code>mapInternal</code> method. Implementations
 * of <code>mapInternal</code> must use the context to implement the following
 * code pattern (or similar), which allows iteration to be injected into the
 * <code>mapInternal</code> method.
 * 
 * <pre>
 *   public void mapInternal(LambdaVoid&lt;T&gt; body, MapInternalContext ctx) {
 *     ...
 *     for (T item : internal) {
 *       while (true) {
 *         try {
 *           ctx.begin();
 *           body(item);
 *           ctx.commit(item);
 *           break;
 *         } catch (IterationAbortException _) {
 *           ctx.abort();
 *         }
 *       }
 *     }
 *     ...
 *   }
 * </pre>
 * 
 * @see galois.objects.Mappable#mapInternal(util.fn.LambdaVoid, MapInternalContext)
 * @see galois.objects.Mappable#mapInternal(util.fn.Lambda2Void, MapInternalContext, Object)
 * @see galois.objects.Mappable#mapInternal(util.fn.Lambda3Void, MapInternalContext, Object, Object)
 *
 */
public interface MapInternalContext {
  /**
   * Signals the beginning of processing a new element.
   */
  public void begin();

  /**
   * Signals the completion of processing a new element.
   * @param obj  element whose processing is complete
   */
  public void commit(Object obj);

  /**
   * Signals the abort of processing an element.
   */
  public void abort();

  /**
   * Returns the thread id of the currently executing thread; useful for
   * accessing thread or iteration local variables.
   * 
   * @return  the thread id of the current executing thread
   */
  public int getThreadId();
}
