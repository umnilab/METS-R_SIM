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

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Represents data accessed during an iteration by a thread. Each iteration
 * is accessed by at most one thread so no need to synchronize here either.
 */
class OrderedIteration<T> extends Iteration {

  /**
   * retired means the iteration has either aborted (ABORT_DONE) or committed (COMMIT_DONE)
   */
  private boolean retired;
  private Lock retiredLock;
  private Condition retiredCond;

  private AtomicReference<Status> status;

  /**
   * The object from the collection associated with this iteration
   */
  private T iterationObject;

  public OrderedIteration(int id) {
    super(id);
    status = new AtomicReference<Status>(Status.UNSCHEDULED);
    retired = false;
    // XXX Not recycling lock and cond var so as to avoid potential problems...Might do it in the future...
    retiredLock = new ReentrantLock();
    retiredCond = retiredLock.newCondition();
  }

  public final Condition getRetiredCond() {
    return retiredCond;
  }

  public final Lock getRetiredLock() {
    return retiredLock;
  }

  public final boolean hasRetired() {
    return retired;
  }

  public void setIterationObject(T obj) {
    this.iterationObject = obj;
  }

  public T getIterationObject() {
    return iterationObject;
  }

  /**
   * Called to abort an iteration. This unwinds the undo log, clears conflict
   * logs and releases all held partitions
   */
  @Override
  public int performAbort() {

    if (getStatus() == Status.ABORT_DONE || getStatus() == Status.COMMIT_DONE) {
      return 0;
    }

    if (getStatus() != Status.ABORTING) {
      throw new RuntimeException("wrong status in performAbort " + getStatus());
    }

    int r = super.performAbort();

    setStatus(Status.ABORT_DONE);

    wakeupConflicters();

    return r;

  }

  void wakeupConflicters() {
    retiredLock.lock();
    try {
      retired = true;
      retiredCond.signalAll();
    } finally {
      retiredLock.unlock();
    }
  }

  /**
   * Commit iteration. This clears the conflict logs and releases any held
   * partitions, and performs any commit actions
   */
  @Override
  public int performCommit(boolean releaseLocks) {

    if (getStatus() == Status.ABORT_DONE || getStatus() == Status.COMMIT_DONE) {
      return 0;
    }

    if (getStatus() != Status.COMMITTING) {
      throw new RuntimeException("wrong status in performCommit " + this);
    }

    int r = super.performCommit(releaseLocks);
    setStatus(Status.COMMIT_DONE);

    wakeupConflicters();

    return r;

  }

  @Override
  protected void reset() {
    super.clearLogs(true);
    setStatus(Status.UNSCHEDULED);
    // setIterationObject(null); commenting due to null ptr exception in ROBComparator
  }

  /**
   * Create a new iteration that reuses as much storage from this finished
   * iteration as it can to reduce the need for garbage collection. Returns
   * the new Iteration
   */
  @Override
  Iteration recycle() {
    reset();
    return this;
  }

  /**
   * @return value returned by getStatus() may become stale due to concurrent modifications, therefore should not be cached
   *         in a local variable
   */
  Status getStatus() {
    return status.get();
  }

  public boolean hasStatus(Status expected) {
    return getStatus() == expected;
  }

  public void setStatus(Status status) {
    this.status.set(status);
  }

  public boolean casStatus(Status expect, Status update) {
    return this.status.compareAndSet(expect, update);
  }

  @Override
  public String toString() {
    if (iterationObject != null) {
      return String.format("(it=%d,%s,%s)", this.getId(), status.get(), iterationObject);
    } else {
      return String.format("(it=%d,null,%s)", this.getId(), status.get());
    }
  }

  public enum Status {
    UNSCHEDULED, // this iteration is born new but hasn't been assigned an element
    SCHEDULED, // this iteration is assigned an active element to work on
    READY_TO_COMMIT, // this iteration has completed and is ready to commit given it's highest priority
    COMMITTING, // performing or going to perform commit actions
    COMMIT_DONE, // done performing commit actions
    ABORT_SELF, // this conflicted with some other high priority iteration 'that', that signalled this to abort it self
    // this is still running and perhaps modifying the data structure, therefore, that cannot call abort on this, so that
    // signal this to abort itself, and that sleeps waiting for this to do so

    ABORTING, // performing or going to perform abort/undo actions
    ABORT_DONE
    // done performing undo actions

    // possible transitions are:
    // UNSCHEDULED -> SCHEDULED  // 'this' iteration is assigned a non-null active element and starts running
    // and 'this' is added to the rob
    //
    // SCHEDULED -> READY_TO_COMMIT // this has completed running and can commit as soon as it becomes the highest priority iteration
    //
    // SCHEDULED -> ABORTING // 'this' raises a conflict with 'that' and loses the conflict because 'that' has higher priority
    //
    // SCHEDULED -> ABORT_SELF // some higher priority iteration 'that' raises a conflict with this, and puts 'this' in ABORT_SELF so 
    // that 'this' can abort itself at its own convenience (Note: 'this' could be in the middle of modifying a data structure)
    //
    // READY_TO_COMMIT -> COMMITTING // 'this' is the highest priority iteration in the system (i.e. among all completed and running iterations and 
    // the pending active elements in the worklist) and is at the head of the rob
    //
    // READY_TO_COMMIT -> ABORTING // 'that' raised a conflict with 'this' after this has completed, 'that' calls abort on 'this' 
    //
    // ABORTING -> ABORT_DONE // ABORTING means the 'this' is going to perform its abort actions and release locks; after having performed the abort actions 
    // this goes into ABORT_DONE, but 'this' hasn't been removed from the rob yet
    //
    // COMMITTING -> COMMIT_DONE // 'this' is going to perform its commit actions and release locks. 'this' has been removed from the
    // rob at this point
    //

    // an iteration is running (i.e. has a thread associated with it and could be performing modifications 
    // to the data structures) if it is in following states:
    // SCHEDULED
    // ABORT_SELF
    // Note: UNSCHEDULED doesn't have an active element assigned to it, but can be considered running.
  }

}
