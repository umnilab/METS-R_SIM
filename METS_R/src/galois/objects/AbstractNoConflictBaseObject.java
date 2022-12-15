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

File: AbstractNoConflictBaseObject.java 

 */

package galois.objects;

import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

/**
 * Default implementation of a Galois object suitable for extension
 * by user code. It encodes the following policies:
 *  <ul>
 *   <li><i>unmonitored access:</i> object assumes that access is
 *       mediated externally (i.e., it is an error if two iterations
 *       access this object at the same time and at least one access
 *       is a "write")</li>
 *   <li><i>restore from copy:</i> rollback is implemented by restoring
 *       eager copies of the object</li>
 *  </ul>
 * <p>
 * If an algorithm/application guarantees doesn't exclusive access to these
 * objects already, consider using
 * {@link AbstractBaseObject} instead.
 * </p>
 * 
 * <p>Likewise, if rollback is relatively frequent, consider implementing
 * restore via undo actions rather than restoring from copy.
 */
public abstract class AbstractNoConflictBaseObject implements GObject {

  @Override
  public void access(byte flags) {
    Iteration it = null;
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
