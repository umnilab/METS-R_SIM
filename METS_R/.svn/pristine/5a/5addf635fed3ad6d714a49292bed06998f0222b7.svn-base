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

import galois.objects.Lockable;
import galois.objects.MethodFlag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents data accessed during an iteration. 
 */
public class Iteration {
  private static ThreadLocal<Iteration> iteration = new ThreadLocal<Iteration>();
  /**
   * A list of any commit actions
   */
  private final Deque<Callback> commitActions;
  private final List<ReleaseCallback> releaseActions;

  /**
   * A stack of callbacks to undo any actions the iteration has done
   */
  private final Deque<Callback> undoActions;

  /**
   * locked objects
   */
  private final List<Lockable> locked;

  /**
   * id of the iteration assigned on creation
   */
  private final int id;

  public Iteration(int id) {
    this.id = id;
    this.undoActions = new ArrayDeque<Callback>();
    this.releaseActions = new ArrayList<ReleaseCallback>();
    this.commitActions = new ArrayDeque<Callback>();
    this.locked = new ArrayList<Lockable>();
  }

  protected void reset() {
    // Clear logs should have taken care of everything
  }

  /**
   * Returns the currently executing iteration or null if no iteration is currently being
   * executed.
   *
   * @return  the current iteration
   */
  public static Iteration getCurrentIteration() {
    return iteration.get();
  }

  /**
   * Hack to set the current iteration being executed by a thread.
   *
   * @param it  the current iteration
   */
  static void setCurrentIteration(Iteration it) {
    iteration.set(it);
  }

  /**
   * Acquires an <i> abstract lock</i> on the given object if <code>flags</code>
   * contains {@link MethodFlag#CHECK_CONFLICT}. Returns the current iteration if
   * <code>flags</code> contains {@link MethodFlag#CHECK_CONFLICT}.
   * 
   * @param lockable  the object to lock
   * @param flags     method flags
   * @return          the current iteration if <code>flags</code> contains {@link MethodFlag#CHECK_CONFLICT}
   * @see #acquire(Lockable)
   */
  public static Iteration acquire(Lockable lockable, byte flags) {
    Iteration it = null;
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
      it = Iteration.getCurrentIteration();
      it.acquire(lockable);
    }
    return it;
  }

  /**
   * Acquires an <i>abstract lock</i> on the given object.
   * 
   * @param lockable  object to acquire an abstract lock on
   */
  public void acquire(Lockable lockable) {
    AtomicReference<Iteration> owner = lockable.getOwner();

    if (owner.get() == this) {
      return;
    }

    while (!owner.compareAndSet(null, this)) {
      GaloisRuntime.getRuntime().raiseConflict(this, owner.get());
    }

    locked.add(lockable);
  }

  void addCommitAction(Callback c) {
    commitActions.addLast(c);
  }

  void addUndoAction(Callback c) {
    undoActions.addFirst(c);
  }

  /**
   * Add a new conflict log to the iteration (i.e. the iteration has made
   * calls listed in this CL)
   *
   * @param cm
   */
  void addReleaseAction(ReleaseCallback callback) {
    releaseActions.add(callback);
  }

  /**
   * Clears undo logs, commit logs, conflict logs
   */
  protected int clearLogs(boolean releaseLocks) {
    undoActions.clear();
    commitActions.clear();

    if (releaseLocks)
      return releaseLocks();
    else
      return 0;
  }

  private int releaseLocks() {
    int total = 0;
    for (int i = 0; i < releaseActions.size(); i++) {
      total += releaseActions.get(i).release(this);
    }
    releaseActions.clear();

    for (int i = 0; i < locked.size(); i++) {
      locked.get(i).getOwner().set(null);
      total++;
    }
    locked.clear();
    return total;
  }

  /**
   * Called to abort an iteration. This unwinds the undo log, clears conflict
   * logs and releases all held partitions
   */
  int performAbort() {
    Callback c;
    while ((c = undoActions.poll()) != null) {
      c.call();
    }

    return clearLogs(true);
  }

  /**
   * Commit iteration. This clears the conflict logs and releases any held
   * partitions, and performs any commit actions
   */
  int performCommit(boolean releaseLocks) {
    Callback c;
    while ((c = commitActions.poll()) != null) {
      c.call();
    }

    return clearLogs(releaseLocks);
  }

  /**
   * Create a new iteration that reuses as much storage from this finished
   * iteration as it can to reduce the need for garbage collection. Returns
   * the new Iteration
   */
  Iteration recycle() {
    reset();
    return this;
  }

  public int getId() {
    return id;
  }
}
