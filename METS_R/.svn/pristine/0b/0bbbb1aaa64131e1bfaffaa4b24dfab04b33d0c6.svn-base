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

import galois.runtime.OrderedIteration.Status;
import galois.runtime.wl.OrderableWorklist;

import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

class OrderedExecutor<T> extends AbstractGaloisExecutor<T> {
  private final static boolean fineLogLevel = logger.isLoggable(Level.FINE);

  private static final int GET_FREE_ITER_ATTEMPTS = 10;

  /**
   * comparator used to order worklist
   */
  private final Comparator<T> comp;

  private final OrderableWorklist<T> worklist;
  /**
   * cannot use an ordered set like java.util.TreeSet, because it does not allow adding different 
   * items of same priority. Although ROBComparator breaks ties based on hashCode, two different
   * iterations can have same hashCode and the same priority, because the hashCode is not guaranteed
   * to be unique
   */
  private final PriorityQueue<OrderedIteration<T>> rob;
  private final ROBComparator<T> robComp;
  private final ReentrantLock robLock;

  private ArrayBlockingQueue<OrderedIteration<T>> freeList;

  public OrderedExecutor(OrderableWorklist<T> worklist) {
    this.worklist = worklist;

    comp = worklist.getComparator();
    robComp = new ROBComparator<T>(comp);
    rob = new PriorityQueue<OrderedIteration<T>>(64, robComp);
    robLock = new ReentrantLock();

    freeList = new ArrayBlockingQueue<OrderedIteration<T>>(getMaxIterations());
    for (int i = 0; i < getMaxIterations(); ++i) {
      freeList.add(new OrderedIteration<T>(i));
    }
  }

  private static void log(String msg, Object... args) {
    if (fineLogLevel) {
      logger.fine(String.format("Thread %d: %s", Thread.currentThread().getId(), String.format(msg, args)));
    }
  }

  static void dbout(String msg, Object... args) {
    System.err.printf("[Thread %2d] %s\n", Thread.currentThread().getId(), String.format(msg, args));
  }

  private static <U> void orderIterationToAbortAndSleepWaiting(OrderedIteration<U> current,
      OrderedIteration<U> conflicter) {
    Lock lock = conflicter.getRetiredLock();
    lock.lock();
    try {
      Condition conflicterRetiredCond = conflicter.getRetiredCond();
      while (!conflicter.hasRetired()) {
        log("%s is going to SLEEP waiting for %s", current, conflicter);

        conflicterRetiredCond.await();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
      throw new RuntimeException("Thread has been interrupted!!!");
    } finally {
      log("%s WOKE UP from sleep waiting for %s", current, conflicter);
      lock.unlock();
    }
  }

  @Override
  protected void abortIteration(Iteration theirIt) throws IterationAbortException {
    OrderedIteration<T> it = (OrderedIteration<T>) theirIt;
    it.setStatus(Status.ABORTING);
    log("aborting %s with object %s", it, it.getIterationObject());

    it.performAbort();
    clearROB(it);

  }

  @Override
  public void arbitrate(Iteration current, Iteration conflicter) {
    log("arbitrate between current %s & conflicter %s", current, conflicter);
    arbitrateInternal((OrderedIteration<T>) current, (OrderedIteration<T>) conflicter);
    log("returning from arbitrate current %s & conflicter %s", current, conflicter);
  }

  // current is the currently running iteration and can be in SCHEDULED or ABORT_SELF
  // conflicter is the iteration current conflicted with, and may or may not be running,
  // conflicter cannot be in UNSCHEDULED state
  // 

  // The following table documents the rules of arbitration
  // The rows are ordered in decreasing order of priority
  //
  // result = compareIterationPriorities(current, conflicter)
  // X means don't care
  //
  // result current    conflicter      | action
  // ==================================|==========
  // X      ABORT_SELF X               | abort  current   
  // X      SCHEDULED  ABORT_DONE      | return
  // X      SCHEDULED  COMMIT_DONE     | return
  // >=0    SCHEDULED  X               | abort  current   
  // <0     SCHEDULED  SCHEDULED       | try to put conflicter in ABORT_SELF and  sleep waiting for it to abort and release locks
  // <0     SCHEDULED  READY_TO_COMMIT | abort  conflicter and add conflicter's object back to the worklist
  // <0     SCHEDULED  ABORT_SELF      | sleep  waiting for conflicter
  // <0     SCHEDULED  ABORT_DONE      | return
  // <0     SCHEDULED  COMMIT_DONE     | return
  // <0     SCHEDULED  ABORTING        | sleep  waiting   
  // <0     SCHEDULED  COMMITTING      | sleep  waiting   
  //

  private void arbitrateInternal(OrderedIteration<T> current, OrderedIteration<T> conflicter)
      throws IterationAbortException {
    assert current.hasStatus(Status.SCHEDULED) || current.hasStatus(Status.ABORT_SELF);

    if (current.hasStatus(Status.ABORT_SELF)) {
      // current is running but lost a conflict to some one and is going to abort eventually any way
      // no point in going through the arbitration
      IterationAbortException.throwException();
    }

    if (conflicter == null) {
      // With new way of locking, conflicter == null means that the corresponding lock
      // has been released. 
      // But this requires that 
      // cm's correctly handle returns from arbitrate rather
      // than expecting that the iteration aborts (which usually happens in unordered case)

      // IterationAbortException.throwException();

      return;
    }

    log("Thread: %d arbitrating between %s (%s) and %s (%s)", Thread.currentThread().getId(), current, current
        .getIterationObject(), conflicter, conflicter.getIterationObject());

    // If the conflicter is done committing or aborting, then the conflict is trivially resolved
    if (conflicter.hasStatus(Status.COMMIT_DONE) || conflicter.hasStatus(Status.ABORT_DONE)
        || conflicter.hasStatus(Status.UNSCHEDULED)) {
      log("%s has a trivially resolved conflict with %s ", current, conflicter);
      return;
    }

    // result > 0 means current has lower priority
    // the comparison is based on the priority of active elements owned 
    // by the two iterations. In cases when priorities of active elements are 
    // equal, a consistent way of breaking ties is needed
    // i.e. if cmp(a,b) < 0 then cmp (b,a) > 0
    int result = compareIterationPriorities(current, conflicter);

    if (result >= 0) {
      // using result >= 0 instead of result > 0 due to the following scenario:
      // Iterations a & b are running in SCHEDULED, a raises a conflict with b and b raises
      // a conflict with 'a' simultaneously. comparison result == 0
      // if we had used the check result > 0, the check would have failed, which 
      // results in a waiting for b to abort itself, and b waiting for a to abort itself i.e. 
      // a deadlock

      log("%s is @@@@@@@aborted@@@@@@ by %s", current, conflicter);
      IterationAbortException.throwException();
    }

    log("%s is going to ######abort##### %s", current, conflicter);

    if (conflicter.casStatus(Status.SCHEDULED, Status.ABORT_SELF)) {
      // Set status of conflicter to A_SELF, wait until notified when it finishes aborting
      log("Telling SCHEDULED iteration %s to abort itself A_SELF", conflicter);
      orderIterationToAbortAndSleepWaiting(current, conflicter);
    } else if (conflicter.casStatus(Status.READY_TO_COMMIT, Status.ABORTING)) {
      // Set status of conflicting iteration to ABORTING, and abort it
      // we do not cas to ABORT_SELF, because of the invariant that iteration with ABORT_SELF is running
      // while iteration with RTC is not running
      log("Telling RTC iteration %s to abort itself ABORTING", conflicter);

      // aborted element is added back to the worklist only if the AbstractGaloisExecutor catches 
      // IterationAbortException thrown by the current Iteration
      // therefore, need to add back the corresponding element

      abortAndAdd(conflicter);

      // TODO: need to figure out how to throw IterationAbortException here, or this
      // abort wouldn't be counted in stats
    } else if (conflicter.hasStatus(Status.ABORT_DONE) || conflicter.hasStatus(Status.COMMIT_DONE)
        || conflicter.hasStatus(Status.UNSCHEDULED)) {
      return;
    } else {
      // conflicter could be COMMITTING, ABORT_SELF, ABORTING, 
      // can't let current fall through arbitrate and go on to modify the 
      // shared data structures. A safe bet is to wait sleeping.
      log("%s trying to abort ABORT_SELF/ABORTING iteration %s", current, conflicter);
      orderIterationToAbortAndSleepWaiting(current, conflicter);
    }
  }

  private void abortAndAdd(OrderedIteration<T> it) {
    worklist.add(it.getIterationObject(), null);
    abortIteration(it);
  }

  private int clearROB(OrderedIteration<T> debugIt) {
    int retval = 0;

    robLock.lock();
    try {
      int niter = 0;
      while (!rob.isEmpty()) {

        // robHead can be in SCHEDULED, READY_TO_COMMIT, ABORTING, ABORT_SELF, ABORT_DONE
        // but it is hard to see a consistent value, since the status may change concurrently
        OrderedIteration<T> robHead = rob.peek();

        log("%d cleaning: looking at: %s ", niter, robHead);

        ++niter;

        if (robHead.hasStatus(Status.COMMIT_DONE) || robHead.hasStatus(Status.UNSCHEDULED)) {
          // an iteration cannot be in the rob in these two states
          throw new RuntimeException("Iteration " + robHead + " has bad status in ROB ");
        }

        if (!robHead.hasStatus(Status.READY_TO_COMMIT) && !robHead.hasStatus(Status.ABORT_DONE)) {
          // robHead not ready to be removed from the rob
          break;
        }

        if (!isOfHighestPriority(robHead)) {
          log("clearRob: iteration %s does not have highest priority", robHead);
          break;
        }

        log("clearRob: iteration %s has highest priority", robHead);

        if (robHead.casStatus(Status.READY_TO_COMMIT, Status.COMMITTING)) {
          // imp to use CAS here, since another thread might be trying 
          // to set status to ABORTING in arbitrate(). Therefore using a CAS to
          // resolve the race

          retval += robHead.performCommit(true);
          // poll() should remove the same object that was returned by peek() i.e. robHead
          rob.poll();

          log("done with committing %s", robHead);
          addToFreeList(robHead);

        } else if (robHead.hasStatus(Status.ABORT_DONE)) {
          rob.poll();
          log("removing A_DONE %s from ROB", robHead);
          addToFreeList(robHead);
        } else {
          break;
          // can't freeze the status at one point, since it could be changing concurrently
        }
      }
      return retval;
    } finally {
      robLock.unlock();
    }
  }

  private void addToFreeList(OrderedIteration<T> it) {
    it.recycle();
    freeList.add(it);
  }

  // iteration entering here should be running & can be in
  // SCHEDULED  
  // ABORT_SELF
  // UNSCHEDULED
  // 
  // we put a SCHEDULED iteration in READY_TO_COMMIT and call clearROB(), which
  // may commit this iteration if it is at the head of the rob
  @Override
  public final void commitIteration(final Iteration theirIt, final int iterationId, final T item, boolean releaseLocks) {
    final OrderedIteration<T> it = (OrderedIteration<T>) theirIt;

    // a safe point to add a record-replay commit action to the iteration
    // the comit action needs to be added at any point before the iteration goest to READY_TO_COMMIT
    // once the iteration goes to READY_TO_COMMIT, any other thread concurrently looking at the rob in clearROB()
    // can commit this iteration

    if (it.hasStatus(Status.SCHEDULED)) {
      // only iteration entering here in SCHEDULED can possibly go to READY_TO_COMMIT and COMMIT_DONE
      // UNSCHEDULED iteration doesn't own an object, and it's object field can be null
      // ABORT_SELF is going to abort any way
      assert Iteration.getCurrentIteration() == it : String.format("Iteration different from currentIteration\n");
      assert item == it.getIterationObject() : String
          .format("iteration object mismatch item = %s, it = %s\n", item, it);

      it.addCommitAction(new Callback() {
        @Override
        public void call() {
          Features.getReplayFeature().onCommit(it, iterationId, item);
        }
      });
    }

    if (!(it.hasStatus(Status.SCHEDULED) || it.hasStatus(Status.ABORT_SELF) || it.hasStatus(Status.UNSCHEDULED))) {
      throw new RuntimeException("Iteration " + it + " with Unexpected Status during commit");
    }

    if (it.casStatus(Status.SCHEDULED, Status.READY_TO_COMMIT)) {
      log("Setting status of %s from SCHEDULED to RTC", it);
      // at this point, there's hope iteration may eventually commit

      clearROB(it);

    } else if (it.hasStatus(Status.ABORT_SELF)) {
      log("Setting status of %s from A_SELF to ABORTING", it);

      IterationAbortException.throwException();
    } else if (it.hasStatus(Status.UNSCHEDULED)) { // 
      // assert it.getIterationObject() == null; goes along with not setting obj to null in OrderedIteration.reset();

      // need to add back the iteration to freeList
      addToFreeList(it);

      clearROB(it);
    } else {
      // can't let an iteration fall through commitIteration
      // either it should clearForCommit
      // or it should abort 
      IterationAbortException.throwException();
    }

  }

  /**
   * 
   * @param current The current iteration
   * @param conflicter The conflicter iteration
   * @return the result of comparing their priorities
   * 
   * If both iterations are running the method returns the result of comparing their priorities. 
   * 
   * Precondition 1: 'current' is the iteration currently ran by the executing thread
   * Precondition 2: Ran with conflict management disabled 
   * NOTE: Currently we do not try to acquire the ROB lock before we compare the priorities of current and
   * conflicter. This relies on the fact that iterationObject is not set to null. If this ever happens, we
   * also need to acquire the lock here too. 
   */
  private int compareIterationPriorities(OrderedIteration<T> current, OrderedIteration<T> conflicter) {
    // if the priority of the current iteration is higher, then abort the other
    // Using robComparator to deal with cases where we compare iteration with objects of same priority
    return robComp.compare(current, conflicter);
  }

  @Override
  protected T poll(ForeachContext<T> ctx) {
    OrderedIteration<T> it = (OrderedIteration<T>) Iteration.getCurrentIteration();
    T obj = null;

    robLock.lock();
    try {
      obj = worklist.poll(ctx);
      if (obj == null) {
        return null;
      }
      assert it.hasStatus(Status.UNSCHEDULED) : String.format("Unwanted status of iter in poll() %s\n", it);
      it.setStatus(Status.SCHEDULED);
      it.setIterationObject(obj);

      rob.add(it);

      log("scheduling %s with object %s", it, obj);
    } finally {
      robLock.unlock();
    }
    return obj;
  }

  private boolean isOfHighestPriority(OrderedIteration<T> it) {
    T currentTopObj = worklist.peek();
    if (currentTopObj == null) {
      return true;
    }
    T itObj = it.getIterationObject();
    // Here we can't use the robComparator because the queue object is not owned yet. Hard-coding
    boolean res = comp.compare(itObj, currentTopObj) <= 0;

    log("Result of comparing %s with %s is %s", itObj, currentTopObj, res);

    return res;
  }

  // we remvove from the freeList
  // if the freeList is empty then we attempt to remove
  // an iteration from ROB,
  // in an extreme case where ROB is full and no iteration can be committed/(or aborted) 
  // because global min lies in the worklist, 
  // In this situation we can try to remove an ABORT_DONE iter from ROB. Not a good idea to have threads block
  // on removing from freeList, because all threads could end up sleeping here.
  @Override
  protected Iteration newIteration(Iteration prev, int tid) {

    OrderedIteration<T> retIt = freeList.poll();

    int attempts = 0;
    while (retIt == null) { // normally should fail

      // try to clear the head of the ROB.
      clearROB(retIt);

      if (attempts >= GET_FREE_ITER_ATTEMPTS) {
        retIt = removeAbortDone();

        if (retIt != null) { // found an iter
          assert retIt.hasStatus(Status.ABORT_DONE);
          retIt.recycle();
          break;
        } else {
          try {
            retIt = freeList.take();
            break; // don't drop down, watch out for the poll()
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }

      ++attempts;
      retIt = freeList.poll();
    }

    assert retIt != null;
    return retIt;

  }

  // used by newIteration when it can't find a free iteration 
  // tries to find one ABORT_DONE iteration in the rob and removes it from
  // the rob. removing an ABORT_DONE iteration is safest, since item it was processing
  // is back in the worklist, all actions have been completed and locks have been released.
  private OrderedIteration<T> removeAbortDone() {
    robLock.lock();
    try {
      for (Iterator<OrderedIteration<T>> i = rob.iterator(); i.hasNext();) {
        OrderedIteration<T> it = i.next();
        if (it.hasStatus(Status.ABORT_DONE)) {
          i.remove();
          return it;
        }
      }
      return null; // couldn't find an ABORT_DONE iteration.
    } finally {
      robLock.unlock();
    }

  }

  @Override
  protected boolean allIterRetired() {
    robLock.lock();
    try {
      // System.err.println(">>>>>> num commits = " + commits + ", rob = " + rob);
      return rob.isEmpty();
    } finally {
      robLock.unlock();
    }
  }

  private static class ROBComparator<U> implements Comparator<OrderedIteration<U>> {
    private Comparator<U> comp;

    public ROBComparator(Comparator<U> iterObjComparator) {
      this.comp = iterObjComparator;
    }

    // The comparison uses the worklist comparator to compare the 
    // priority of active elements owned by the iterations.
    //
    // Ties are broken based on hashCode(), which provides a consistent
    // way to arbitrate between two conflicting iterations.
    // two different Iterations can have the same hashCode and same priority
    // active elements, in which case the comparison may return 0.
    @Override
    public int compare(OrderedIteration<U> it1, OrderedIteration<U> it2) {
      if (it1 == it2) { // It is needed for the set containment
        return 0;
      }

      U it1Obj = it1.getIterationObject();
      U it2Obj = it2.getIterationObject();
      int objCmpRes = comp.compare(it1Obj, it2Obj);

      if (objCmpRes == 0) {
        return it1.hashCode() - it2.hashCode();
      }
      return objCmpRes;
    }
  }
}
