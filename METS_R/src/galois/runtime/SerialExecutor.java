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
import galois.runtime.wl.Worklist;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;

import util.fn.Lambda2Void;

class SerialExecutor<T> implements Executor, ForeachContext<T> {
  private final Deque<Callback> suspendThunks;
  private boolean yield;
  private boolean finish;
  private Worklist<T> worklist;

  public SerialExecutor() {
    suspendThunks = new ArrayDeque<Callback>();
  }

  @Override
  public void onRelease(Iteration it, ReleaseCallback action) {
  }

  @Override
  public void onCommit(Iteration it, Callback call) {
    suspendThunks.addFirst(call);
    yield = true;
  }

  @Override
  public void onUndo(Iteration it, Callback action) {
  }

  public IterationStatistics call(Lambda2Void<T, ForeachContext<T>> body, Worklist<T> worklist)
      throws ExecutionException {
    this.worklist = worklist;

    int numCommitted = 0;
    T item;
    while (true) {
      reset();
      while ((item = worklist.poll(this)) != null) {
        numCommitted++;
        try {
          body.call(item, this);
        } catch (WorkNotUsefulException e) {
        } catch (WorkNotProgressiveException e) {
        }
        Features.getReplayFeature().onCommit(null, getIterationId(), item);

        if (yield) {
          break;
        }
      }

      if (!suspendThunks.isEmpty()) {
        try {
          for (Callback thunk : suspendThunks) {
            thunk.call();
          }
        } catch (Exception e) {
          throw new ExecutionException(e);
        }
      }

      if (finish || !yield) {
        break;
      }
    }

    IterationStatistics stats = new IterationStatistics();
    stats.putStats(Thread.currentThread(), numCommitted, 0);
    return stats;
  }

  private void reset() {
    finish = false;
    yield = false;
    suspendThunks.clear();
  }

  @Override
  public final void add(T t) {
    add(t, MethodFlag.ALL);
  }

  @Override
  public void add(T t, byte flags) {
    worklist.add(t, this);
  }

  @Override
  public void finish() {
    finish = true;
    yield = true;
  }

  @Override
  public int getThreadId() {
    return 0;
  }

  @Override
  public void suspendWith(Callback call) {
    suspendThunks.addFirst(call);
    yield = true;
  }

  @Override
  public void arbitrate(Iteration current, Iteration conflicter) throws IterationAbortException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSerial() {
    return true;
  }

  @Override
  public void suspend(Callback listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void suspendDone() {
    throw new UnsupportedOperationException();
  }

  public int getIterationId() {
    return 0;
  }
}
