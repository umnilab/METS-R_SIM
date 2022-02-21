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

import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

abstract class AbstractGaloisExecutor<T> extends AbstractConcurrentExecutor<T> {
  protected static Logger logger = Logger.getLogger("galois.runtime.Executor");

  protected int maxIterations;

  protected AbstractGaloisExecutor() {
    this.maxIterations = GaloisRuntime.getRuntime().getMaxIterations();
  }

  protected int getMaxIterations() {
    return maxIterations;
  }

  protected void setMaxIterations(int maxIterations) {
    this.maxIterations = maxIterations;
  }

  @Override
  public abstract void arbitrate(Iteration current, Iteration conflicter);

  protected abstract void commitIteration(Iteration it, int iterationId, T item, boolean releaseLocks);

  protected abstract void abortIteration(Iteration it) throws IterationAbortException;

  protected abstract Iteration newIteration(Iteration prev, int tid);

  protected abstract T poll(ForeachContext<T> ctx);

  @Override
  protected Process newProcess(int tid) {
    return new GaloisProcess(tid);
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

  // private static int lockCoalescing =
  // SystemProperties.getIntProperty("lockCoalescing", 0);
  private static final int lockCoalescing = 0;

  protected class GaloisProcess extends Process {
    private Iteration currentIteration;
    private int iterationId = -1;
    private boolean first;
    private int lastAbort;
    private int consecAborts;

    public GaloisProcess(int id) {
      super(id);
    }

    protected final void setupCurrentIteration() {
      Iteration it = newIteration(currentIteration, getThreadId());
      if (it != currentIteration || first) {
        // Try to reduce the number of Iteration.setCurrentIteration calls if we
        // can
        currentIteration = it;
        Iteration.setCurrentIteration(it);
        iterationId = currentIteration.getId();
        first = false;
      }
    }

    private T nextItem() {
      T item;
      try {
        item = poll(this);
      } catch (IterationAbortException e) {
        throw new Error("Worklist method threw unexpected exception");
      }

      if (item == null) {
        commitIteration(currentIteration, iterationId, item, true);
      }
      return item;
    }

    protected final void doCommit(T item) {
      try {
        commitIteration(currentIteration, iterationId, item, (numCommitted & lockCoalescing) == 0);
        // XXX(ddn): This count will be incorrect for ordered executors because
        // commitIteration only puts an iteration into ready to commit
        numCommitted++;
        recordCpuId();
      } catch (IterationAbortException _) {
        // an iteration has thrown WorkNotUsefulException/WorkNotProgressiveException,
        // and tries to commit before it goes to RTC (i.e. completes), another thread
        // signals it to abort itself
        readd(item);
        doAbort();
      }
    }

    protected final void doAbort() {
      abortIteration(currentIteration);
      numAborted++;
      // TODO(ddn): Implement this better using control algo! Needed something fast
      // to make boruvka work.
      final int logFactor = 4;
      final int mask = (1 << logFactor) - 1;
      if (lastAbort == numCommitted) {
        // Haven't committed anything since last abort
        consecAborts++;
        if (consecAborts > 1 && (consecAborts & mask) == 0) {
          startWaiting();
          try {
            Thread.sleep(consecAborts >> logFactor);
          } catch (InterruptedException e) {
            throw new Error(e);
          } finally {
            stopWaiting();
          }
        }
      } else {
        consecAborts = 0;
      }
      lastAbort = numCommitted;
    }

    /**
     * Re-add item to worklist in case of abort.
     *
     * @param item
     */
    private void readd(T item) {
      while (true) {
        try {
          worklist.add(item, this);
          break;
        } catch (IterationAbortException e) {
          // Commonly this exception is never thrown, but
          // client code may provide comparators/indexers
          // that may abort, in which case spin until we
          // can put the item back
        }
      }
    }

    @Override
    protected void doCall() throws Exception {
      first = true;
      try {
        L1: while (true) {
          T item;

          while (true) {
            setupCurrentIteration();
            item = nextItem();
            if (item == null) {
              if (yield) {
                break L1;
              } else {
                break;
              }
            }

            try {
              body.call(item, this);
              doCommit(item);
            } catch (IterationAbortException _) {
              readd(item);
              doAbort();
            } catch (WorkNotProgressiveException _) {
              doCommit(item);
            } catch (WorkNotUsefulException _) {
              doCommit(item);
            } catch (Throwable e) {
              // Gracefully terminate processes
              if (currentIteration != null) {
                numAborted++;
                abortIteration(currentIteration);
              }
              throw new ExecutionException(e);
            }

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
        currentIteration = null;
        Iteration.setCurrentIteration(null);
      }
    }

    @Override
    public final void add(final T t) {
      add(t, MethodFlag.ALL);
    }

    @Override
    public void add(final T t, byte flags) {
      final ForeachContext<T> ctx = this;
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
        currentIteration.addCommitAction(new Callback() {
          @Override
          public void call() {
            worklist.add(t, ctx);
            if (someDone()) {
              wakeupOne();
            }
          }
        });
      } else {
        worklist.add(t, ctx);
        if (someDone()) {
          wakeupOne();
        }
      }
    }

    @Override
    public void finish() {
      currentIteration.addCommitAction(new Callback() {
        @Override
        public void call() {
          finish = true;
          yield = true;
        }
      });
    }

    @Override
    public void suspendWith(final Callback call) {
      currentIteration.addCommitAction(new Callback() {
        @Override
        public void call() {
          addSuspendThunk(call);
          yield = true;
        }
      });
    }

    @Override
    public int getIterationId() {
      return iterationId;
    }
  }
}
