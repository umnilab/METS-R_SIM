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
import galois.runtime.wl.ParameterUnorderedWorklist;
import galois.runtime.wl.ParameterWorklist;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

class ParameterUnorderedExecutor<T> extends AbstractParameterExecutor<T, T> {

  private StepRecord<Iteration> currStepRec = new StepRecord<Iteration>();

  private int numCommitted;
  private final ParameterUnorderedWorklist<T> worklist;

  private Object mappableBody;
  private MappableType type;
  private Object[] args;
  protected final List<Iteration> commitPool;

  public ParameterUnorderedExecutor(ParameterUnorderedWorklist<T> worklist) {
    this.worklist = worklist;
    this.commitPool = new ArrayList<Iteration>();
  }

  public ParameterStatistics call(Object body, MappableType type, Object... args) throws ExecutionException {
    mappableBody = body;
    this.type = type;
    this.args = args;
    ParameterStatistics stats = call(null);

    this.mappableBody = null;
    this.type = null;
    this.args = null;

    return stats;
  }

  @Override
  public final void add(T t) {
    add(t, MethodFlag.ALL);
  }

  @Override
  public void add(T t, byte flags) {
    worklist.add(t);
  }

  @Override
  protected Iteration newIteration() {
    return new Iteration(getIterationId());
  }

  @Override
  protected ParameterWorklist<T, T> getWorklist() {
    return worklist;
  }

  @Override
  public void arbitrate(Iteration current, Iteration conflicter) throws IterationAbortException {
    IterationAbortException.throwException();
  }

  @Override
  protected boolean usingEagerOutput() {
    return true;
  }

  @Override
  protected void initTimeStep() {
    currStepRec.logWlSize(worklist.size());
    numCommitted = 0;
  }

  @Override
  protected void finishTimeStep() {
  }

  @Override
  protected int commitAll() {
    int numCommits = 0;
    for (Iteration it : commitPool) {
      Iteration.setCurrentIteration(it);
      int locksHeld = it.performCommit(true);
      currStepRec.logNeighSize(locksHeld);
      ++numCommits;
    }

    commitPool.clear();

    return numCommits;
  }

  @Override
  protected void run(T cur, Iteration it) {

    try {
      boolean notUsefull = false;
      try {
        if (mappableBody != null) {
          type.call(mappableBody, cur, args);
        } else {
          runBody(cur, it);
        }

      } catch (WorkNotUsefulException e) {
        notUsefull = true;
      }

      numCommitted++;
      if (parameterCommittedLimit > 0) {
        if (numCommitted >= parameterCommittedLimit) {
          worklist.deferRemainingItems();
        }
      }

      commitPool.add(it);
      currStepRec.logExecuted(it, notUsefull);

    } catch (IterationAbortException e) {
      // If the iteration aborts, it cannot be executed in this round. Defer
      // its execution to the next round and undo any work it performed up
      // to this point.
      it.performAbort();
      worklist.defer(cur);
    }

  }

  @Override
  protected void logStats() {
    dumpStepStats(currStepRec);
    currStepRec.clear();
  }

  @Override
  protected void finishLoop() {
    // nothing to do yet
  }

}
