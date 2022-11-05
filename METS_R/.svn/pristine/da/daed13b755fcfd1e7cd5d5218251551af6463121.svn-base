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

class BarebonesExecutor<T> extends AbstractConcurrentExecutor<T> {
  BarebonesExecutor() {
  }

  @Override
  public void arbitrate(Iteration current, Iteration conflicter) throws IterationAbortException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onRelease(Iteration it, ReleaseCallback action) {
  }

  @Override
  public void onCommit(Iteration it, Callback action) {
    action.call();
  }

  @Override
  public void onUndo(Iteration it, Callback action) {
  }

  @Override
  protected Process newProcess(int tid) {
    return new MyProcess(tid);
  }

  private class MyProcess extends Process {
    public MyProcess(int id) {
      super(id);
    }

    @Override
    protected void doCall() throws InterruptedException {
      try {
        L1: while (true) {
          T item;

          while ((item = worklist.poll(this)) != null) {
            numCommitted++;
            body.call(item, this);
            Features.getReplayFeature().onCommit(null, getIterationId(), item);
            if (yield) {
              break L1;
            }
          }

          // Slow check
          if (isDone()) {
            break;
          }
        }
      } finally {
        makeAllDone();
        wakeupAll();
      }
    }

    @Override
    public final void add(T t) {
      add(t, MethodFlag.ALL);
    }

    @Override
    public void add(T t, byte flags) {
      worklist.add(t, this);
      if (someDone()) {
        wakeupOne();
      }
    }

    @Override
    public void finish() {
      finish = true;
      yield = true;
    }

    @Override
    public void suspendWith(Callback call) {
      addSuspendThunk(call);
      yield = true;
    }

    @Override
    public int getIterationId() {
      return getThreadId();
    }
  }
}
