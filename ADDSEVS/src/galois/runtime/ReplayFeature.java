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

import java.io.Serializable;

import util.Launcher;
import util.fn.LambdaVoid;

/**
 * Encapsulates functionality needed to support deterministic replay of
 * Galois iterators.
 * 
 *
 */
public abstract class ReplayFeature {
  private static final int CACHE_MULTIPLE = 16;

  private final int[] first;
  private final int[] counters;

  private final int maxtIterations;
  private final Callback[] undoActions;
  private final Callback[] commitActions;
  private boolean invalid;

  public void invalidate() {
    invalid = true;
  }

  protected final void checkValidity() {
    assert !invalid;
  }

  protected ReplayFeature(int maxtIterations) {
    this.maxtIterations = maxtIterations;
    counters = new int[maxtIterations * CACHE_MULTIPLE];
    first = new int[maxtIterations * CACHE_MULTIPLE];

    undoActions = new Callback[maxtIterations];
    commitActions = new Callback[maxtIterations];
    for (int iterationId = 0; iterationId < maxtIterations; iterationId++) {
      final int index = getCounterIndex(iterationId);

      undoActions[iterationId] = new Callback() {
        @Override
        public void call() {
          counters[index] = first[index];
          first[index] = 0;
        }
      };
      commitActions[iterationId] = new Callback() {
        @Override
        public void call() {
          first[index] = 0;
        }
      };
    }
  }

  protected final String getLogName(int curLog) {
    return String.format("replaylog%d", curLog);
  }

  protected final long getRid(Object obj) {
    Replayable r = (Replayable) obj;
    long rid = r.getRid();
    assert rid != 0;
    return rid;
  }

  private int getCounterIndex(int iterationId) {
    return iterationId * CACHE_MULTIPLE;
  }

  protected final long makeRid(int iterationId) {
    int index = getCounterIndex(iterationId);
    int counter = counters[index]++;
    return Rids.makeRid(maxtIterations, iterationId, counter);
  }

  protected final void setNextRid(Iteration it, int iterationId, Replayable obj) {
    if (it != null) {
      int index = getCounterIndex(iterationId);
      if (first[index] == 0) {
        first[index] = counters[index];
        it.addUndoAction(undoActions[iterationId]);
        it.addCommitAction(commitActions[iterationId]);
      }
    }

    long rid = makeRid(iterationId);
    obj.setRid(rid);
  }

  protected final void setNextRid(Replayable obj) {
    // Depend on ctx.createReplayable() to set rid during parallel execution,
    // which in turn should call setNextRid(int,Object) or setNextRid(Iteration,int,Object)
    long rid = Rids.makeSerialRid();
    obj.setRid(rid);
  }

  public abstract void onCommit(Iteration it, int iterationId, Object obj);

  public abstract void onCallbackExecute(long rid);

  public abstract <T> void registerCallback(long rid, LambdaVoid<ForeachContext<T>> callback);

  public abstract void registerCallback(long rid, Callback callback);

  public abstract boolean isCallbackControlled();

  public void onCreateReplayable(Replayable obj) {
    // checkValidity();
    if (GaloisRuntime.getRuntime().inRoot()) {
      setNextRid(obj);
    } else {
      Iteration it = Iteration.getCurrentIteration();
      if (it != null)
        setNextRid(it, it.getId(), obj);
      else
        setNextRid(null, 0, obj);
    }
  }

  public abstract void onFinish();

  /**
   * Replay ids. Packed long representing id of object.
   * 
   * Format:
   * <pre>
   *          bN ... b2 b1 b0
   *  serial  .... id .... 0
   *  thread  counter .. iterationId 1
   *  error   0  0  0  ... 0
   * </pre>
   * 
   * On overflow, there may be id collisions with existing objects! 
   */
  private static final class Rids {
    private static boolean initialized;
    private static long counter = 1;

    private static long makeRid(int maxtIterations, int iterationId, int counter) {
      int logNT = 32 - Integer.numberOfLeadingZeros(maxtIterations - 1);
      return (counter << (logNT + 1)) | (iterationId << 1) | 1;
    }

    private static long makeSerialRid() {
      if (!initialized) {
        Launcher.getLauncher().onRestart(new Runnable() {
          @Override
          public void run() {
            counter = 1;
            initialized = false;
          }
        });
        initialized = true;
      }
      return counter++ << 1;
    }
  }

  /**
   * The three different types of replay features.
   * 
   *
   */
  static enum Type {
    RECORD, PLAYBACK, NO;
    @SuppressWarnings("unchecked")
    public ReplayFeature create(int numThreads) {
      switch (this) {
      case RECORD:
        return new RecordReplayFeature(numThreads);
      case PLAYBACK:
        return new PlaybackReplayFeature(numThreads);
      case NO:
        return new NoReplayFeature(numThreads);
      default:
        throw new Error("Unknown option: " + this);
      }
    }
  }

  /**
   * The two different types of callbacks.
   * 
   *
   */
  static enum CallbackType {
    CALLBACK, LAMBDA_CONTEXT;
  }

  /**
   * Log that stores active elements and callbacks for deterministic
   * replay.
   * 
   *
   */
  static final class Log implements Serializable {
    private static final long serialVersionUID = 1L;
    private Action[] actions;
    private long[] rids;
    private int[] iterationIds;
    private int size;
    private final int capacity;

    /**
     * Entries are either active elements (POLL) or callbacks (CALLBACK).
     *
     */
    protected static enum Action {
      POLL, CALLBACK
    }

    /**
     * Creates a new log file capable of storing at most the given
     * capacity of entries.
     * 
     * @param capacity  maximum capacity of log file
     */
    public Log(int capacity) {
      this.capacity = capacity;
      actions = new Action[capacity];
      rids = new long[capacity];
      iterationIds = new int[capacity];
      size = capacity;
    }

    public void putEntry(int index, Action action, long rid, int iterationId) {
      actions[index] = action;
      rids[index] = rid;
      iterationIds[index] = iterationId;
    }

    public Action getAction(int index) {
      return actions[index];
    }

    public long getRid(int index) {
      return rids[index];
    }

    public int getIterationId(int index) {
      return iterationIds[index];
    }

    public void setSize(int size) {
      this.size = size;
    }

    public int size() {
      return size;
    }

    public int getCapacity() {
      return capacity;
    }
  }
}
