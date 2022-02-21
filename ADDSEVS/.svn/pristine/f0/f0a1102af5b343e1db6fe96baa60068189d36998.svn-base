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

import galois.objects.MethodFlag;
import galois.runtime.OrderedIteration.Status;
import galois.runtime.wl.ParameterOrderedWorklist;
import galois.runtime.wl.ParameterWorklist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;

import util.MutableReference;

class ParameterOrderedExecutor<T> extends AbstractParameterExecutor<T, MutableReference<T>> {

  private static final boolean HELPGC = true;
  private static final int FLUSH_STATS_INTERVAL = 100;

  private final Map<OrderedIteration<MutableReference<T>>, Integer> executionHistory;
  private final List<OrderedStepRecord<T>> allStepRec;
  private OrderedStepRecord<T> currStepRec;

  private int numExecuted;
  private final Comparator<T> itemCmp;
  private final ParameterOrderedWorklist<T> worklist;

  protected final PriorityQueue<OrderedIteration<MutableReference<T>>> rob;

  /**
   * next time step for which stats need to be logged
   */
  private int nextToLog = 0;

  public ParameterOrderedExecutor(ParameterOrderedWorklist<T> worklist) {
    this.worklist = worklist;
    this.itemCmp = worklist.comparator();

    rob = new PriorityQueue<OrderedIteration<MutableReference<T>>>(64, new RobComparator<T>(itemCmp));

    executionHistory = new HashMap<OrderedIteration<MutableReference<T>>, Integer>();
    allStepRec = new ArrayList<OrderedStepRecord<T>>();

    currStepRec = new OrderedStepRecord<T>();
    nextToLog = 0;
  }

  @Override
  protected Iteration newIteration() {
    return new OrderedIteration<MutableReference<T>>(getIterationId());
  }

  @Override
  public final void add(T t) {
    add(t, MethodFlag.ALL);
  }

  @Override
  public void add(T t, byte flags) {
    worklist.add(t);
  }

  private static <U> U getIterationObject(OrderedIteration<MutableReference<U>> it) {
    assert it.getIterationObject() != null;
    return it.getIterationObject().get();
  }

  @Override
  protected int commitAll() {
    int numCommits = 0;
    while (!rob.isEmpty()) {
      OrderedIteration<MutableReference<T>> robHead = rob.peek();

      T robHeadObj = getIterationObject(robHead);
      if (robHead.hasStatus(Status.ABORT_DONE)) {
        rob.poll();
      } else if (worklist.canCommit(robHeadObj)) {
        assert robHead.hasStatus(Status.READY_TO_COMMIT);
        rob.poll();
        Iteration.setCurrentIteration(robHead);
        robHead.setStatus(Status.COMMITTING);

        int ns = robHead.performCommit(true);
        StepRecord<OrderedIteration<MutableReference<T>>> rec = getStepRecord(robHead);
        rec.logNeighSize(ns);

        ++numCommits;
      } else {
        break;
      }

    }

    return numCommits;
  }

  private StepRecord<OrderedIteration<MutableReference<T>>> getStepRecord(OrderedIteration<MutableReference<T>> it) {
    int executedStep = executionHistory.get(it);
    assert allStepRec.get(executedStep) != null;
    return allStepRec.get(executedStep);
  }

  private void abortIteration(MutableReference<T> boxedCur, OrderedIteration<MutableReference<T>> it,
      boolean removeRecords) {
    assert it.getIterationObject() == boxedCur;

    worklist.defer(boxedCur);
    it.setStatus(Status.ABORTING);
    it.performAbort();

    if (removeRecords) {
      StepRecord<OrderedIteration<MutableReference<T>>> rec = getStepRecord(it);
      rec.unlog(it);
      executionHistory.remove(it);
    }
  }

  // NOTE: slightly different from Galois OrderedExecutor
  // Here iteration is added to rob, after it has successfully executed
  // in OrderedExecutor iteration is added to rob when it's scheduled
  // basically we're saving one some useless adds
  private void markExecuted(OrderedIteration<MutableReference<T>> it) {
    assert Iteration.getCurrentIteration() == it;
    it.setStatus(Status.READY_TO_COMMIT);
    rob.add(it);
  }

  @Override
  public void arbitrate(Iteration current, Iteration conflicter) throws IterationAbortException {
    arbitrateInternal((OrderedIteration<MutableReference<T>>) current,
        (OrderedIteration<MutableReference<T>>) conflicter);
  }

  // current is executing
  // conflicter has already executed
  private void arbitrateInternal(OrderedIteration<MutableReference<T>> current,
      OrderedIteration<MutableReference<T>> conflicter) {
    T currObj = getIterationObject(current);
    T confObj = getIterationObject(conflicter);
    int res = itemCmp.compare(currObj, confObj);
    if (res >= 0) {
      // aborting current on lower (or equal) priority
      IterationAbortException.throwException();
    } else {
      MutableReference<T> boxedConf = conflicter.getIterationObject();
      abortIteration(boxedConf, conflicter, true);
    }
  }

  @Override
  protected void initTimeStep() {
    numExecuted = 0;

    currStepRec.logWlSize(worklist.size());
  }

  @Override
  protected void finishTimeStep() {

    // Ignore timesteps for which we don't increment timeStep

    assert allStepRec.size() == getTimeStep();
    allStepRec.add(currStepRec);
    currStepRec = new OrderedStepRecord<T>();
  }

  @Override
  protected void finishLoop() {
    System.out.println("finishing loop, nextToLog = " + nextToLog);
    for (int i = nextToLog; i < allStepRec.size(); ++i) {
      dumpStep(allStepRec.get(i));
    }
  }

  private void dumpStep(StepRecord<OrderedIteration<MutableReference<T>>> stepRec) {
    dumpStepStats(stepRec);
    // help gc
    if (HELPGC) {
      for (OrderedIteration<MutableReference<T>> it : stepRec.executed.keySet()) {
        executionHistory.remove(it);
      }
      stepRec.clear();
    }
  }

  @Override
  protected void logStats() {
    if ((getTimeStep() % FLUSH_STATS_INTERVAL) == 0) {
      boolean flushStep = true;
      while (flushStep && (nextToLog <= getTimeStep())) {
        //        System.err.println("nextToLog = " + nextToLog + " currStep = " + getTimeStep());
        StepRecord<OrderedIteration<MutableReference<T>>> nextToLogRec = allStepRec.get(nextToLog);

        // checking to see if nextToLog record is all commit done or abort done, in which case it can be flushed out
        for (OrderedIteration<MutableReference<T>> it : nextToLogRec.executed.keySet()) {
          if (!(it.hasStatus(Status.COMMIT_DONE) || it.hasStatus(Status.ABORT_DONE))) {
            flushStep = false;
            break;
          }
        }

        if (flushStep) {
          dumpStep(nextToLogRec);
          ++nextToLog;
        }
      }
    }
  }

  @Override
  protected ParameterWorklist<T, MutableReference<T>> getWorklist() {
    return worklist;
  }

  @Override
  protected void run(MutableReference<T> boxedCur, Iteration iter) throws ExecutionException {

    OrderedIteration<MutableReference<T>> it = (OrderedIteration<MutableReference<T>>) iter;
    assert Iteration.getCurrentIteration() == it;
    it.setIterationObject(boxedCur);
    it.setStatus(Status.SCHEDULED);

    T cur = boxedCur.get();

    boolean notUsefull = false;
    try {
      try {
        runBody(cur, it);
      } catch (WorkNotUsefulException e) {
        notUsefull = true;
      }
      logger.fine(String.format("Successfully executed %s(%s)", it, cur));

      // it successfully executed,
      markExecuted(it);
      ++numExecuted;
      if (parameterCommittedLimit > 0) {
        if (numExecuted >= parameterCommittedLimit) {
          worklist.deferRemainingItems();
        }
      }

      // record execution
      executionHistory.put(it, getTimeStep());
      currStepRec.logExecuted(it, notUsefull);

    } catch (IterationAbortException _) {
      logger.fine(String.format("%s(%s) aborted", it, cur));
      abortIteration(boxedCur, it, false);
    }

  }

  @Override
  protected boolean usingEagerOutput() {
    return false;
  }

  private static class OrderedStepRecord<T> extends StepRecord<OrderedIteration<MutableReference<T>>> {

  }

  private static class RobComparator<U> implements Comparator<OrderedIteration<MutableReference<U>>> {
    private final Comparator<U> objCmp;

    public RobComparator(Comparator<U> objCmp) {
      this.objCmp = objCmp;
    }

    @Override
    public int compare(OrderedIteration<MutableReference<U>> it1, OrderedIteration<MutableReference<U>> it2) {
      U obj1 = getIterationObject(it1);
      U obj2 = getIterationObject(it2);
      int res = objCmp.compare(obj1, obj2);
      return res;
    }

  }

}
