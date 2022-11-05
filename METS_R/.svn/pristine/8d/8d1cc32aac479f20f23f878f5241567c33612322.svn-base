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

File: AbstractBaseObject.java 

 */

package galois.objects;

import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

/**
 * Default implementation of a Galois object suitable for extension
 * by user code. It encodes the following policies:
 *  <ul>
 *   <li><i>exclusive access:</i> only one iteration can access an object
 *       at a time and</li>
 *   <li><i>restore from copy:</i> rollback is implemented by restoring
 *       eager copies of the object</li>
 *  </ul>
 * If an algorithm/application guarantees exclusive access to these
 * objects already (e.g., node data in a graph where there is a
 * one-to-one correspondence between nodes and node data <i>and</i>
 * all accesses go first through the graph), consider using
 * {@link AbstractNoConflictBaseObject} instead, which has a lower
 * memory overhead.
 * 
 * <p>Likewise, if rollback is relatively frequent, consider implementing
 * restore via undo actions rather than restoring from copy.
 */
public abstract class AbstractBaseObject extends AbstractLockable implements GObject {

  @Override
  public void access(byte flags) {
    Iteration it = Iteration.acquire(this, flags);
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      if (it == null) {
        it = Iteration.getCurrentIteration();
      }

      final Object copy = gclone();
      GaloisRuntime.getRuntime().onUndo(it, new Callback() {
        @Override
        public void call() {
          restoreFrom(copy);
        }
      });
    }
  }

  /**
   * Makes a copy of this object. This copy is used as a parameter to {@link restoreFrom(Object)}
   * 
   * @return  A copy of this object
   */
  public abstract Object gclone();

  /**
   * Restores a previous state, saved in the object passed as parameter.
   *
   * @param copy An object that represents a snapshot of some previous state as returned by {@link gclone()}.
   */
  public abstract void restoreFrom(Object copy);
}
