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

import galois.objects.Mappable;
import galois.objects.MethodFlag;
import galois.runtime.wl.Worklist;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

import util.fn.Lambda2Void;
import util.fn.LambdaVoid;

class PlaybackReplayFeature<T> extends ReplayFeature implements Executor, ForeachContext<T> {
  private final TLongObjectHashMap<CallbackType> callbackTypes;
  private final TLongObjectHashMap<Object> callbacks;

  private final Deque<Callback> commitActions;
  private final ItemMap<T> map;
  private Log log;
  private int cur;
  private int curLog;
  private int iterationId;

  public PlaybackReplayFeature(int maxIterations) {
    super(maxIterations);
    callbackTypes = new TLongObjectHashMap<CallbackType>();
    callbacks = new TLongObjectHashMap<Object>();
    commitActions = new ArrayDeque<Callback>();
    map = new TroveItemMap<T>();
  }

  @Override
  public void onRelease(Iteration it, ReleaseCallback action) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onCommit(Iteration it, Callback origCall) {
    commitActions.addLast(origCall); // queue behavior
  }

  @Override
  public void onUndo(Iteration it, Callback action) {
    // throw new UnsupportedOperationException();
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

  private void reset() {
    map.clear();
  }

  public IterationStatistics call(Lambda2Void<T, ForeachContext<T>> body, Worklist<T> worklist)
      throws ExecutionException {
    reset();

    ForeachContext<T> ctx = new AbstractExecutorContext<T>() {
      @Override
      public int getThreadId() {
        return 0;
      }
    };
    T item;
    while ((item = worklist.poll(ctx)) != null) {
      map.update(getRid(item), item);
    }

    return run(body, null, null);
  }

  public IterationStatistics call(Mappable<T> mappable, Object body, MappableType type, Object... args)
      throws ExecutionException {
    reset();

    mappable.map(new LambdaVoid<T>() {
      @Override
      public void call(T item) {
        map.update(getRid(item), item);
      }
    });

    return run(null, body, type, args);
  }

  private IterationStatistics run(Lambda2Void<T, ForeachContext<T>> body, Object mappableBody, MappableType type,
      Object... args) throws ExecutionException {
    nextLog();

    int numCommitted = 0;
    T item;
    while ((item = poll()) != null) {
      numCommitted++;
      try {
        if (body != null)
          body.call(item, this);
        else
          type.call(mappableBody, item, args);
      } catch (WorkNotUsefulException e) {
      } catch (WorkNotProgressiveException e) {
      }
      if (!commitActions.isEmpty()) {
        Callback c;
        while ((c = commitActions.poll()) != null)
          c.call();
      }
    }

    reset();

    IterationStatistics stats = new IterationStatistics();
    stats.putStats(Thread.currentThread(), numCommitted, 0);

    return stats;
  }

  @Override
  public void finish() {
  }

  @Override
  public void suspendWith(Callback call) {
  }

  @Override
  public void arbitrate(Iteration current, Iteration conflicter) throws IterationAbortException {
    throw new UnsupportedOperationException();
  }

  @Override
  public final void add(T item) {
    add(item, MethodFlag.ALL);
  }

  @Override
  public void add(final T item, byte flags) {
    map.update(getRid(item), item);
  }

  @Override
  public int getIterationId() {
    checkValidity();
    return iterationId;
  }

  public int getThreadId() {
    return 0;
  }

  @Override
  public void onCommit(Iteration it, int iterationId, Object item) {
    checkValidity();
    throw new UnsupportedOperationException();
  }

  @Override
  public void onCallbackExecute(long rid) {
    checkValidity();
  }

  @Override
  public boolean isCallbackControlled() {
    checkValidity();
    return true;
  }

  private void nextLog() {
    try {
      ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new FileInputStream(getLogName(curLog))));
      log = (Log) in.readObject();
      cur = 0;
      in.close();
      curLog++;
    } catch (FileNotFoundException e) {
      throw new Error(e);
    } catch (IOException e) {
      throw new Error(e);
    } catch (ClassNotFoundException e) {
      throw new Error(e);
    }
  }

  private T poll() throws ExecutionException {
    long rid;
    while (true) {
      for (; cur < log.size(); cur++) {
        switch (log.getAction(cur)) {
        case POLL:
          iterationId = log.getIterationId(cur);
          rid = log.getRid(cur++);
          return map.poll(rid);
        case CALLBACK:
          rid = log.getRid(cur);
          callCallback(rid, this);
          break;
        default:
          throw new Error();
        }
      }

      if (cur == log.getCapacity()) {
        nextLog();
      } else {
        // End of this executor
        break;
      }
    }

    return null;
  }

  @Override
  public void onCreateReplayable(Replayable item) {
    checkValidity();
    if (GaloisRuntime.getRuntime().inRoot()) {
      setNextRid(item);
    } else {
      setNextRid(null, iterationId, item);
    }
  }

  private static interface ItemMap<T> {
    public void update(long rid, T item);

    public T poll(long rid);

    public void clear();
  }

  private static class TroveItemMap<T> implements ItemMap<T> {
    private final TLongObjectHashMap<T> map;
    private final TLongIntHashMap counts;

    public TroveItemMap() {
      map = new TLongObjectHashMap<T>();
      counts = new TLongIntHashMap();
    }

    @Override
    public void update(long rid, T item) {
      if (map.containsKey(rid)) {
        counts.put(rid, counts.get(rid) + 1);
      } else {
        map.put(rid, item);
        counts.put(rid, 1);
      }
    }

    @Override
    public T poll(long rid) {
      int num = counts.get(rid);
      T retval;
      if (num == 1) {
        counts.remove(rid);
        retval = map.remove(rid);
      } else {
        counts.put(rid, num - 1);
        retval = map.get(rid);
      }

      assert retval != null : String.format("retval null for rid=%d, num=%d\n", rid, num);
      return retval;
    }

    @Override
    public void clear() {
      map.clear();
      counts.clear();
    }
  }

  @Override
  public void onFinish() {
    checkValidity();
  }

  @Override
  public <U> void registerCallback(long rid, LambdaVoid<ForeachContext<U>> callback) {
    checkValidity();
    callbackTypes.put(rid, CallbackType.LAMBDA_CONTEXT);
    callbacks.put(rid, callback);
  }

  @Override
  public void registerCallback(long rid, Callback callback) {
    checkValidity();
    callbackTypes.put(rid, CallbackType.CALLBACK);
    callbacks.put(rid, callback);
  }

  @SuppressWarnings("unchecked")
  private void callCallback(long rid, final ForeachContext<?> ctx) throws ExecutionException {
    final Object obj = callbacks.get(rid);
    switch (callbackTypes.get(rid)) {
    case CALLBACK:
      GaloisRuntime.getRuntime().replaceWithRootContextAndCall((Callback) obj);
      break;
    case LAMBDA_CONTEXT:
      GaloisRuntime.getRuntime().replaceWithRootContextAndCall(new Callback() {
        @Override
        public void call() {
          ((LambdaVoid<ForeachContext<?>>) obj).call(ctx);
        }

      });
      break;
    default:
      throw new Error();
    }
  }
}
