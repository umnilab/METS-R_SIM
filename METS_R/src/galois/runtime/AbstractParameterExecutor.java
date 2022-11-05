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

import galois.runtime.wl.ParameterWorklist;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import util.SystemProperties;
import util.fn.Lambda2Void;

abstract class AbstractParameterExecutor<T, S> implements Executor, ForeachContext<T> {
  protected static Logger logger = Logger.getLogger("galois.runtime.ParameterExecutor");

  protected static int parameterCommittedLimit = SystemProperties.getIntProperty(
      "galois.runtime.parameterCommittedLimit", -1);

  protected Lambda2Void<T, ForeachContext<T>> body;

  private int timeStep;

  private final Deque<Callback> suspendThunks;
  protected boolean yield;

  private ParameterStatistics stats;

  protected AbstractParameterExecutor() {
    suspendThunks = new ArrayDeque<Callback>();

    if (parameterCommittedLimit > 0) {
      logger.info("Number of executed iterations per step (i.e. max available parallelism limited by: "
          + parameterCommittedLimit);
    }
  }

  protected void log(String msg, Object... args) {
    //    System.out.println(String.format(msg, args));
  }

  @Override
  public void finish() {
    Iteration.getCurrentIteration().addCommitAction(new Callback() {
      @Override
      public void call() {
        yield = true;
      }
    });
  }

  @Override
  public int getThreadId() {
    return 0;
  }

  @Override
  public int getIterationId() {
    return 0;
  }

  @Override
  public void onRelease(Iteration it, ReleaseCallback action) {
    it.addReleaseAction(action);
  }

  @Override
  public void onCommit(Iteration it, Callback action) {
    it.addCommitAction(action);
  }

  @Override
  public void onUndo(Iteration it, Callback action) {
    it.addUndoAction(action);
  }

  @Override
  public boolean isSerial() {
    return false;
  }

  @Override
  public void suspend(Callback listener) {
    // TODO(ddn);
    throw new UnsupportedOperationException();
  }

  @Override
  public void suspendDone() {
    // TODO(ddn);
    throw new UnsupportedOperationException();
  }

  // the suspend action is added to the deq on commit
  // and is called after the current timestep has finished
  // committing the corresponding iteration
  @Override
  public void suspendWith(final Callback call) {
    Iteration.getCurrentIteration().addCommitAction(new Callback() {
      @Override
      public void call() {
        suspendThunks.addFirst(call);
        yield = true;
      }
    });
  }

  public final ParameterStatistics call(Lambda2Void<T, ForeachContext<T>> body) throws ExecutionException {
    this.body = body;

    stats = new ParameterStatistics();

    runLoop();

    return stats;
  }

  abstract protected int commitAll();

  /**
   * Callback to notify subclasses about end of loop. After this executes, the
   * following must hold true:
   * <ul>
   * <li><code>executionLog</code> contains all the iterations executed in each
   * time step organized by time step
   * <li><code>worklistSizes</code> contains an entry for each time step
   * <li><code>cycles</code> and <code>neighborhoods</code> contains an entry
   * for each iteration in <code>executionLog</code>
   * </ul>
   */
  protected abstract void finishLoop();

  /**
   * Callback to notify subclasses about end of time step.
   */
  protected abstract void finishTimeStep();

  /**
   * Callback to notify subclasses about beginning of time step.
   */
  protected abstract void initTimeStep();

  protected abstract ParameterWorklist<T, S> getWorklist();

  /**
   * called after each step for child classes to log their stats as much as they can
   */
  protected abstract void logStats();

  protected final int getTimeStep() {
    return timeStep;
  }

  protected final <I extends Iteration> void dumpStepStats(StepRecord<I> stepRec) {
    int notUsefulSize = stepRec.getNumNotUsefull();
    int parallelWork = stepRec.executed.size() - notUsefulSize;
    int worklistSize = stepRec.worklistSize - notUsefulSize;
    stats.putStats(Thread.currentThread(), parallelWork, worklistSize, notUsefulSize, stepRec.neighborhoodSizes);
  }

  /**
   * Execute the given item for an iteration. Should add iterations to the
   * commit pool to be committed and also flush the worklist.
   */
  protected abstract void run(S cur, Iteration it) throws ExecutionException;

  protected abstract Iteration newIteration();

  protected final void runBody(T cur, Iteration it) {
    body.call(cur, this);
  }

  private void runLoop() throws ExecutionException {
    timeStep = 0;
    ParameterWorklist<T, S> worklist = getWorklist();

    while (!worklist.isEmpty()) {
      logger.info(String.format("Beginning timestep %d with %d worklist items", timeStep, worklist.size()));

      initTimeStep();

      Iteration currIter = null;
      while (!worklist.isEmpty()) {
        currIter = newIteration();
        Iteration.setCurrentIteration(currIter);
        S cur = worklist.internalNext();

        run(cur, currIter);

      }

      finishTimeStep();

      logger.fine("In root executor, commit outstanding iterations");
      int numCommits = commitAll();
      if (numCommits == 0) {
        throw new IllegalStateException("No commits! must make forward progress");
      }

      logStats();

      // some iteration(s) in the current step added a suspend action
      if (yield) {
        GaloisRuntime.getRuntime().replaceWithRootContextAndCall(new Callback() {

          @Override
          public void call() {
            for (Callback thunk : suspendThunks) {
              thunk.call();
            }
          }
        });

        suspendThunks.clear();
        yield = false;
      }

      // Set up the next round
      worklist.nextStep();
      timeStep++;

    }

    finishLoop();
  }

  /**
   * Not appropriate for ordered parameter because it is difficult to know when
   * we are "done" with a timestep so we have to hold on to the data until the
   * very end.
   */
  protected abstract boolean usingEagerOutput();

  protected static class StepRecord<I extends Iteration> {
    Map<I, Boolean> executed = new HashMap<I, Boolean>();
    int numNotUsefull = 0;
    List<Integer> neighborhoodSizes = new ArrayList<Integer>();
    int worklistSize = 0;

    public void logExecuted(I it, boolean notUseful) {
      executed.put(it, notUseful);
      if (notUseful) {
        ++numNotUsefull;
      }
    }

    public int getNumNotUsefull() {
      return numNotUsefull;
    }

    public void logNeighSize(int neighSize) {
      neighborhoodSizes.add(neighSize);
    }

    public void logWlSize(int wlSize) {
      this.worklistSize = wlSize;
    }

    /**
     * remove records, used by ordered executor when an iteration that
     * had executed in earlier step is aborted by an iteration in current step
     * @param it
     */
    public void unlog(I it) {
      Boolean notUsefull = executed.remove(it);
      if (notUsefull != null && notUsefull) {
        --numNotUsefull;
      }
      // no need to modify neighborhoodSizes since it's manipulated only once iteration commits

    }

    public void clear() {
      executed.clear();
      numNotUsefull = 0;
      neighborhoodSizes.clear();
      worklistSize = 0;
    }
  }
}
