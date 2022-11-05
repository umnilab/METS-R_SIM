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
import galois.runtime.wl.OrderableWorklist;
import galois.runtime.wl.ParameterOrderedWorklist;
import galois.runtime.wl.ParameterUnorderedWorklist;
import galois.runtime.wl.ParameterWorklist;
import galois.runtime.wl.Priority;
import galois.runtime.wl.Worklist;
import galois.runtime.wl.Priority.Rule;

import java.io.FileInputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import util.Launcher;
import util.Reflection;
import util.RuntimeStatistics;
import util.Sampler;
import util.StackSampler;
import util.Statistics;
import util.SystemProperties;
import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

/**
 * Provides methods to access Galois runtime from application code.
 *
 */
public final class GaloisRuntime {
  private static Logger logger = Logger.getLogger("galois.runtime.GaloisRuntime");

  private static final int ITERATION_MULTIPLIER = SystemProperties.getIntProperty("iterationMultiplier", 1);
  private static GaloisRuntime instance = null;

  private boolean invalid;
  private final boolean useParameter;
  private final boolean useSerial;
  private final ReplayFeature.Type replayType;
  private final int maxThreads;

  private final int maxIterations;
  private final boolean moreStats;
  private final boolean ignoreUserFlags;

  private final ThreadSuspender threadSuspender;
  private final ArrayDeque<ExecutorFrame> stack;
  private final Executor root;
  private ExecutorFrame current;
  private static byte currentMask;

  private GaloisRuntime(int numThreads, boolean useParameter, boolean useSerial, ReplayFeature.Type replayType,
      boolean moreStats, boolean ignoreUserFlags) {
    this.maxIterations = ITERATION_MULTIPLIER * numThreads;
    this.maxThreads = useParameter ? 1 : numThreads;
    this.useParameter = useParameter;
    this.useSerial = useSerial;
    this.replayType = replayType;
    this.moreStats = moreStats;
    this.ignoreUserFlags = ignoreUserFlags;

    threadSuspender = new ThreadSuspender(maxThreads);
    stack = new ArrayDeque<ExecutorFrame>();
    root = new DummyExecutor();
    current = new ExecutorFrame(new ThreadPool(numThreads), root, MethodFlag.NONE);
    currentMask = current.mask;
  }

  /**
   * Called by the testing framework to reset the runtime.
   */
  private static void initialize(int numThreads, boolean useParameter, boolean useSerial,
      ReplayFeature.Type replayType, boolean moreStats, boolean ignoreUserFlags) {
    if (instance != null) {
      instance.invalidate();
    }

    instance = new GaloisRuntime(numThreads, useParameter, useSerial, replayType, moreStats, ignoreUserFlags);
  }

  /**
   * Returns the current instance of the runtime.
   * 
   * @return  a reference to the current runtime
   */
  public static GaloisRuntime getRuntime() {
    if (instance == null) {
      // Use default serial Runtime
      initialize(1, false, true, ReplayFeature.Type.NO, false, false);
    }
    return instance;
  }

  /**
   * Creates an unordered Galois iterator that concurrently applies a function over all elements
   * in some initial collection. Additional elements may be added during iteration.
   * 
   * @param <T>       type of elements to iterate over
   * @param initial   initial elements to iterate over
   * @param body      function to apply
   * @param priority  specification of the order elements are processed
   * @throws ExecutionException  if there is an uncaught exception during execution
   * @see #foreach(Mappable, LambdaVoid)
   * @see #foreach(Mappable, Lambda2Void, galois.runtime.wl.Priority.Rule)
   */
  public static <T> void foreach(Iterable<T> initial, Lambda2Void<T, ForeachContext<T>> body, Rule priority)
      throws ExecutionException {
    getRuntime().runBody(initial, body, priority);
  }

  /**
   * Creates an unordered Galois iterator that concurrently applies a function over all elements
   * in some initial collection. Additional elements may be added during iteration.
   * 
   * @param <T>       type of elements to iterate over
   * @param initial   initial elements to iterate over
   * @param body      function to apply
   * @param priority  specification of the order elements are processed
   * @throws ExecutionException  if there is an uncaught exception during execution
   * @see #foreach(Mappable, LambdaVoid)
   * @see #foreach(Iterable, Lambda2Void, galois.runtime.wl.Priority.Rule)
   */
  public static <T> void foreach(Mappable<T> initial, Lambda2Void<T, ForeachContext<T>> body, Rule priority)
      throws ExecutionException {
    getRuntime().runBody(initial, body, priority);
  }

  /**
   * Creates an ordered Galois iterator that concurrently applies a function over all elements
   * in some initial collection. Additional elements may be added during iteration. Elements
   * are processed strictly according to some order.
   * 
   * @param <T>       type of elements to iterate over
   * @param initial   initial elements to iterate over
   * @param body      function to apply
   * @param priority  specification of the order elements are processed
   * @throws ExecutionException  if there is an uncaught exception during execution
   * @see #foreachOrdered(Mappable, Lambda2Void, galois.runtime.wl.Priority.Rule)
   */
  public static <T> void foreachOrdered(Iterable<T> initial, Lambda2Void<T, ForeachContext<T>> body, Rule priority)
      throws ExecutionException {
    getRuntime().runOrderedBody(initial, body, priority);
  }

  /**
   * Creates an ordered Galois iterator that concurrently applies a function over all elements
   * in some initial collection. Additional elements may be added during iteration. Elements
   * are processed strictly according to some order.
   * 
   * @param <T>       type of elements to iterate over
   * @param initial   initial elements to iterate over
   * @param body      function to apply
   * @param priority  specification of the order elements are processed
   * @throws ExecutionException  if there is an uncaught exception during execution
   * @see #foreachOrdered(Iterable, Lambda2Void, galois.runtime.wl.Priority.Rule)
   */
  public static <T> void foreachOrdered(Mappable<T> initial, Lambda2Void<T, ForeachContext<T>> body, Rule priority)
      throws ExecutionException {
    getRuntime().runOrderedBody(initial, body, priority);
  }

  /**
   * Creates an unordered Galois iterator that concurrently applies a function over all elements
   * in some initial collection. In contrast to
   * {@link #foreach(Mappable, Lambda2Void, galois.runtime.wl.Priority.Rule)},
   * no additional elements may be added during iteration and the particular order
   * elements are processed in is dictated by the particular {@link Mappable} instance.
   * 
   * @param <T>       type of elements to iterate over
   * @param initial   initial elements to iterate over
   * @param body      function to apply
   * @throws ExecutionException  if there is an uncaught exception during execution
   * @see #foreach(Mappable, Lambda2Void, Object)
   * @see #foreach(Mappable, Lambda3Void, Object, Object)
   */
  public static <T> void foreach(Mappable<T> initial, LambdaVoid<T> body) throws ExecutionException {
    getRuntime().runBody(initial, body);
  }

  /**
   * Creates an unordered Galois iterator that concurrently applies a function over all elements
   * in some initial collection. In contrast to
   * {@link #foreach(Mappable, Lambda2Void,galois.runtime.wl.Priority.Rule)},
   * no additional elements may be added during iteration and the particular order
   * elements are processed in is dictated by the particular {@link Mappable} instance.
   * 
   * @param <T>       type of elements to iterate over
   * @param initial   initial elements to iterate over
   * @param body      function to apply
   * @param arg1      additional argument to function
   * @throws ExecutionException  if there is an uncaught exception during execution
   * @see #foreach(Mappable, LambdaVoid)
   * @see #foreach(Mappable, Lambda3Void, Object, Object)
   */
  public static <T, A1> void foreach(Mappable<T> initial, Lambda2Void<T, A1> body, A1 arg1) throws ExecutionException {
    getRuntime().runBody(initial, body, arg1);
  }

  /**
   * Creates an unordered Galois iterator that concurrently applies a function over all elements
   * in some initial collection. In contrast to 
   * {@link #foreach(Mappable, Lambda2Void, galois.runtime.wl.Priority.Rule)},
   * no additional elements may be added during iteration and the particular order
   * elements are processed in is dictated by the particular {@link Mappable} instance.
   * 
   * @param <T>       type of elements to iterate over
   * @param initial   initial elements to iterate over
   * @param body      function to apply
   * @param arg1      additional argument to function
   * @param arg2      additional argument to function
   * @throws ExecutionException  if there is an uncaught exception during execution
   * @see #foreach(Mappable, LambdaVoid)
   * @see #foreach(Mappable, Lambda2Void, Object)
   */
  public static <T, A1, A2> void foreach(Mappable<T> initial, Lambda3Void<T, A1, A2> body, A1 arg1, A2 arg2)
      throws ExecutionException {
    getRuntime().runBody(initial, body, arg1, arg2);
  }

  private void invalidate() {
    assert stack.isEmpty();
    current.pool.shutdown();
    invalid = true;
  }

  private void checkValidity() {
    assert !invalid;
  }

  public void onCommit(Iteration it, Callback action) {
    checkValidity();
    current.executor.onCommit(it, action);
  }

  public void onUndo(Iteration it, Callback action) {
    checkValidity();
    current.executor.onUndo(it, action);
  }

  public void onRelease(Iteration it, ReleaseCallback action) {
    checkValidity();
    current.executor.onRelease(it, action);
  }

  public static boolean needMethodFlag(byte flags, byte option) {
    // Since this is called very often, skip the validity check
    // checkValidity();

    // Apparently the following check even when converted to using static
    // fields is slow
    // if (useParameter)
    //   flags = MethodFlag.ALL;

    return ((flags & currentMask) & option) != 0;
  }

  <T> void callAll(List<? extends Callable<T>> callables) throws InterruptedException, ExecutionException {
    checkValidity();
    current.pool.callAll(callables);
  }

  /**
   * Gets the maximum number of threads that can be used by the Runtime.
   *
   * @return maximum number of threads available
   */
  public int getMaxThreads() {
    checkValidity();
    return maxThreads;
  }

  public int getMaxIterations() {
    checkValidity();
    return maxIterations;
  }

  /**
   * Signals that conflict has been detected by the user/library code.
   *
   * @param it          the current iteration
   * @param conflicter  the iteration that is in conflict with the current iteration
   */
  public void raiseConflict(Iteration it, Iteration conflicter) {
    checkValidity();
    current.executor.arbitrate(it, conflicter);
  }

  private void push(ExecutorFrame frame) {
    stack.push(current);
    current = frame;
    currentMask = current.mask;
  }

  private void pop() {
    current = stack.pop();
    currentMask = current.mask;
  }

  void replaceWithRootContextAndCall(final Callback callback) throws ExecutionException {
    ExecutorFrame oldFrame = current;

    pop();
    try {
      pushContextAndCall(root, new Callable<IterationStatistics>() {
        @Override
        public IterationStatistics call() throws Exception {
          callback.call();
          return null;
        }
      });
    } finally {
      push(oldFrame);
    }
  }

  private IterationStatistics __stackSamplerRecordMe(Callable<IterationStatistics> callback) throws Exception {
    return callback.call();
  }

  private IterationStatistics pushContextAndCall(Executor executor, Callable<IterationStatistics> callback)
      throws ExecutionException {
    boolean suspended = false;
    if (!current.executor.isSerial()) {
      try {
        // TODO(ddn): Can lead to deadlock (infinite livelock) when using mappable
        // iterators because one thread sleeps holding its locks and the other threads
        // keep executing but they can never suspend their executor
        if (current.executor instanceof MappableExecutor)
          throw new Error("Not yet supported");
        threadSuspender.suspend(current.executor);
        suspended = true;
      } catch (InterruptedException e) {
        throw new ExecutionException(e);
      }
    }

    boolean isSerial = executor.isSerial();
    byte mask = isSerial ? MethodFlag.NONE : MethodFlag.ALL;
    // Try to reuse thread pool if possible
    ThreadPool pool;
    if (suspended) {
      pool = new ThreadPool(maxThreads);
    } else {
      pool = current.pool;
    }

    push(new ExecutorFrame(pool, executor, mask));
    try {
      if (isSerial)
        return __stackSamplerRecordMe(callback);
      else
        return callback.call();
    } catch (Exception e) {
      throw new ExecutionException(e);
    } finally {
      // Shutdown newly created thread pools
      if (pool != null && suspended)
        pool.shutdown();
      pop();
    }
  }

  private <T> void initializeWorklist(final Worklist<T> wl, Iterable<T> initial, Mappable<T> mappable)
      throws ExecutionException {
    final ForeachContext<T> ctx = new SimpleContext<T>(maxThreads);

    if (initial != null) {
      for (T item : initial) {
        wl.addInitial(item, ctx);
      }
    } else {
      mappable.map(new LambdaVoid<T>() {
        @Override
        public void call(T item) {
          wl.addInitial(item, ctx);
        }
      });
    }
    wl.finishAddInitial();
  }

  private <T, S> void initializeWorklist(final ParameterWorklist<T, S> wl, Iterable<T> initial, Mappable<T> mappable)
      throws ExecutionException {
    if (initial != null) {
      for (T item : initial) {
        wl.add(item);
      }
    } else {
      mappable.map(new LambdaVoid<T>() {
        @Override
        public void call(T item) {
          wl.add(item);
        }
      });
    }
  }

  private <T> void runBody(Mappable<T> mappable, Lambda2Void<T, ForeachContext<T>> body, Rule priority)
      throws ExecutionException {
    checkValidity();
    runBody(null, mappable, body, ExecutorType.UNORDERED, priority);
  }

  private <T> void runBody(Iterable<T> initial, Lambda2Void<T, ForeachContext<T>> body, Rule priority)
      throws ExecutionException {
    checkValidity();
    runBody(initial, null, body, ExecutorType.UNORDERED, priority);
  }

  private <T> void runOrderedBody(Mappable<T> mappable, Lambda2Void<T, ForeachContext<T>> body, Rule priority)
      throws ExecutionException {
    checkValidity();
    runBody(null, mappable, body, ExecutorType.ORDERED, priority);
  }

  private <T> void runOrderedBody(Iterable<T> initial, Lambda2Void<T, ForeachContext<T>> body, Rule priority)
      throws ExecutionException {
    checkValidity();
    runBody(initial, null, body, ExecutorType.ORDERED, priority);
  }

  @SuppressWarnings("unchecked")
  private <T> void runBody(Iterable<T> initial, Mappable<T> mappable, final Lambda2Void<T, ForeachContext<T>> body,
      ExecutorType type, Rule priority) throws ExecutionException {

    IterationStatistics stats = null;
    if (replayType == ReplayFeature.Type.PLAYBACK) {
      final Worklist<T> wl = Priority.makeSerial(priority);
      final PlaybackReplayFeature<T> feature = (PlaybackReplayFeature<T>) Features.getReplayFeature();
      initializeWorklist(wl, initial, mappable);
      stats = pushContextAndCall(feature, new Callable<IterationStatistics>() {
        @Override
        public IterationStatistics call() throws Exception {
          return feature.call(body, wl);
        }
      });
    } else if (useSerial) {
      final Worklist<T> wl = Priority.makeSerial(priority);
      final SerialExecutor<T> ex = new SerialExecutor<T>();
      initializeWorklist(wl, initial, mappable);
      stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
        @Override
        public IterationStatistics call() throws Exception {
          return ex.call(body, wl);
        }
      });
    } else if (useParameter) {
      if (type == ExecutorType.ORDERED) {
        final ParameterOrderedWorklist<T> wl = Priority.makeParameterOrdered(priority);
        final ParameterOrderedExecutor<T> ex = new ParameterOrderedExecutor<T>(wl);
        initializeWorklist(wl, initial, mappable);
        stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
          @Override
          public IterationStatistics call() throws Exception {
            return ex.call(body);
          }
        });
      } else {
        assert type == ExecutorType.BAREBONES || type == ExecutorType.UNORDERED;
        final ParameterUnorderedWorklist<T> wl = Priority.makeParameterUnordered();
        final AbstractParameterExecutor<T, T> ex = new ParameterUnorderedExecutor<T>(wl);
        initializeWorklist(wl, initial, mappable);
        stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
          @Override
          public IterationStatistics call() throws Exception {
            return ex.call(body);
          }
        });
      }
    } else {
      if (type == ExecutorType.ORDERED) {
        final OrderableWorklist<T> wl = Priority.makeOrdered(priority);
        final OrderedExecutor<T> ex = new OrderedExecutor<T>(wl);
        initializeWorklist(wl, initial, mappable);
        stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
          @Override
          public IterationStatistics call() throws Exception {
            return ex.call(body, wl);
          }
        });
      } else if (type == ExecutorType.BAREBONES) {
        final Worklist<T> wl = Priority.makeUnordered(priority);
        final BarebonesExecutor<T> ex = new BarebonesExecutor<T>();
        initializeWorklist(wl, initial, mappable);
        stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
          @Override
          public IterationStatistics call() throws Exception {
            return ex.call(body, wl);
          }
        });
      } else {
        assert type == ExecutorType.UNORDERED;
        final Worklist<T> wl = Priority.makeUnordered(priority);
        final UnorderedExecutor<T> ex = new UnorderedExecutor<T>();
        initializeWorklist(wl, initial, mappable);
        stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
          @Override
          public IterationStatistics call() throws Exception {
            return ex.call(body, wl);
          }
        });
      }
    }
    if (stats == null)
      throw new Error("unknown executor");

    Launcher.getLauncher().addStats(stats);
    Features.getReplayFeature().onFinish();
  }

  @SuppressWarnings("unchecked")
  private <T> void runBody(final Mappable<T> mappable, final Object body, final MappableType type, final Object... args)
      throws ExecutionException {
    IterationStatistics stats = null;

    if (replayType == ReplayFeature.Type.PLAYBACK) {
      final PlaybackReplayFeature<T> feature = (PlaybackReplayFeature<T>) Features.getReplayFeature();
      stats = pushContextAndCall(feature, new Callable<IterationStatistics>() {
        @Override
        public IterationStatistics call() throws Exception {
          return feature.call(mappable, body, type, args);
        }
      });
    } else if (useSerial) {
      final SerialMappableExecutor<T> ex = new SerialMappableExecutor<T>(mappable);
      stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
        @Override
        public IterationStatistics call() throws Exception {
          return ex.call(body, type, args);
        }
      });
    } else if (useParameter) {
      ParameterUnorderedWorklist<T> wl = Priority.makeParameterUnordered();
      final ParameterUnorderedExecutor<T> ex = new ParameterUnorderedExecutor<T>(wl);

      initializeWorklist(wl, null, mappable);
      stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
        @Override
        public IterationStatistics call() throws Exception {
          return ex.call(body, type, args);
        }
      });
    } else {
      final MappableExecutor<T> ex = new MappableExecutor<T>(mappable);
      stats = pushContextAndCall(ex, new Callable<IterationStatistics>() {
        @Override
        public IterationStatistics call() throws Exception {
          return ex.call(body, type, args);
        }
      });
    }
    if (stats == null)
      throw new Error("unknown executor");

    Launcher.getLauncher().addStats(stats);
    Features.getReplayFeature().onFinish();
  }

  private <T> void runBody(Mappable<T> mappable, LambdaVoid<T> body) throws ExecutionException {
    checkValidity();
    runBody(mappable, body, MappableType.TYPE_0);
  }

  private <T, A1> void runBody(Mappable<T> mappable, Lambda2Void<T, A1> body, A1 arg1) throws ExecutionException {
    checkValidity();
    runBody(mappable, body, MappableType.TYPE_1, arg1);
  }

  private <T, A1, A2> void runBody(Mappable<T> mappable, Lambda3Void<T, A1, A2> body, A1 arg1, A2 arg2)
      throws ExecutionException {
    checkValidity();
    runBody(mappable, body, MappableType.TYPE_2, arg1, arg2);
  }

  public boolean useParameter() {
    checkValidity();
    return useParameter;
  }

  public boolean useSerial() {
    checkValidity();
    return useSerial;
  }

  public boolean ignoreUserFlags() {
    checkValidity();
    return ignoreUserFlags;
  }

  public boolean inRoot() {
    checkValidity();
    return current.executor == root;
  }

  public boolean moreStats() {
    checkValidity();
    return moreStats;
  }

  private static enum ExecutorType {
    BAREBONES, UNORDERED, ORDERED;
  }

  private static class ExecutorFrame {
    final ThreadPool pool;
    final Executor executor;
    final byte mask;

    public ExecutorFrame(ThreadPool pool, Executor executor, byte mask) {
      this.pool = pool;
      this.executor = executor;
      this.mask = mask;
    }
  }

  private static class ThreadSuspender implements Callback {
    private final ReentrantLock lock;
    private final Condition cond;
    private final int numThreads;

    private int numSuspended;
    private boolean once;

    public ThreadSuspender(int numThreads) {
      this.numThreads = numThreads;
      lock = new ReentrantLock();
      cond = lock.newCondition();
    }

    private void abort() {
      // TODO(ddn): What to do for barebones executors?
      IterationAbortException.throwException();
    }

    private void commit() {
      Iteration it = Iteration.getCurrentIteration();
      if (it != null)
        it.performCommit(true);
    }

    private void reset() {
      once = false;
      numSuspended = 0;
    }

    public void suspend(Executor executor) throws InterruptedException {
      lock.lock();
      try {
        if (once) {
          abort();
          return;
        } else {
          once = true;
        }

        executor.suspend(this);

        while (numSuspended < numThreads - 1) {
          cond.await();
        }

        executor.suspendDone();
        reset();
        commit();
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void call() {
      lock.lock();
      try {
        numSuspended++;

        if (numSuspended == numThreads - 1) {
          cond.signal();
        }
      } finally {
        lock.unlock();
      }
    }
  }

  private static class DummyExecutor implements Executor {
    @Override
    public void arbitrate(Iteration current, Iteration conflicter) throws IterationAbortException {
    }

    @Override
    public void onCommit(Iteration it, Callback action) {
    }

    @Override
    public void onRelease(Iteration it, ReleaseCallback action) {
    }

    @Override
    public void onUndo(Iteration it, Callback action) {
    }

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
  }

  private static class SimpleContext<T> extends AbstractExecutorContext<T> {
    private final int maxThreads;
    private final AtomicInteger current = new AtomicInteger();

    public SimpleContext(int maxThreads) {
      this.maxThreads = maxThreads;
    }

    @Override
    public int getThreadId() {
      return current.getAndIncrement() % maxThreads;
    }

    @Override
    public int getIterationId() {
      throw new UnsupportedOperationException("Not supported yet.");
    }
  }

  private static void dumpStatistics(List<Statistics> stats, PrintStream summaryOut, PrintStream fullOut) {
    if (stats.isEmpty())
      return;

    fullOut.println("====== Individual Statistics ======");
    for (Statistics stat : stats) {
      stat.dumpFull(fullOut);
    }

    List<Statistics> merged = new ArrayList<Statistics>();
    Map<Class<? extends Statistics>, Statistics> reps = new HashMap<Class<? extends Statistics>, Statistics>();

    for (Statistics stat : stats) {
      Class<? extends Statistics> key = stat.getClass();
      Statistics rep = reps.get(key);

      if (rep == null) {
        reps.put(key, stat);
        merged.add(stat);
      } else {
        rep.merge(stat);
      }
    }

    summaryOut.println("==== Summary Statistics ====");
    fullOut.println("====== Merged Statistics ======");
    for (Statistics stat : merged) {
      stat.dumpSummary(summaryOut);
      stat.dumpFull(fullOut);
    }
  }

  private static void usage() {
    System.err.println("java -cp ... galois.runtime.GaloisRuntime [options] <main class> <args>*");
    System.err.println(" -r <num runs>      : number of runs to use");
    System.err.println(" -t <num threads>   : number of threads to use");
    System.err.println(" -f <property file> : property file to read arguments from");
    System.err.println(" -p                 : use ParaMeter");
    System.err.println(" -s                 : use serial data structures and executor");
    System.err.println(" -dr                : record execution for deterministic replay");
    System.err.println(" -dp                : playback execution from deterministic replay");
    System.err.println(" -g                 : enable additional statistics.");
    System.err.println("                      Currently: stack profiling, processor utilization");
    System.err.println(" --help             : print help");
  }

  /**
   * @param args
   * @throws Exception 
   */
  public static void main(String[] args) throws Exception {
    String main = null;
    String[] mainArgs = new String[0];
    boolean useParameter = false;
    boolean useSerial = false;
    boolean moreStats = false;
    boolean ignoreUserFlags = false;
    ReplayFeature.Type replayType = ReplayFeature.Type.NO;
    int samplerInterval = 0;
    int numThreads = 1;
    int numRuns = 1;

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("-r")) {
        numRuns = Integer.parseInt(args[++i]);
      } else if (arg.equals("-t")) {
        numThreads = Integer.parseInt(args[++i]);
      } else if (arg.equals("-p")) {
        useParameter = true;
      } else if (arg.equals("-f")) {
        Properties p = new Properties(System.getProperties());
        p.load(new FileInputStream(args[++i]));
        System.setProperties(p);
      } else if (arg.equals("--dr")) {
        replayType = ReplayFeature.Type.RECORD;
      } else if (arg.equals("-s")) {
        useSerial = true;
      } else if (arg.equals("--dp")) {
        replayType = ReplayFeature.Type.PLAYBACK;
        useSerial = true;
      } else if (arg.equals("-g")) {
        samplerInterval = 100;
        moreStats = true;
      } else if (arg.equals("-i")) {
        ignoreUserFlags = true;
      } else if (arg.equals("--help")) {
        usage();
        System.exit(1);
      } else {
        main = arg;
        mainArgs = Arrays.asList(args).subList(i + 1, args.length).toArray(new String[args.length - i - 1]);
        break;
      }
    }

    if (main == null) {
      usage();
      System.exit(1);
    }

    if (useParameter) {
      samplerInterval = 0;
      numThreads = 1;
      numRuns = 1;
    }

    String defaultArgs = System.getProperty("args");
    if (defaultArgs != null) {
      if (mainArgs.length != 0) {
        System.err.println("'args' property and commandline args both given");
        System.exit(1);
      }
      mainArgs = defaultArgs.split("\\s+");
    }

    // Run 
    RuntimeStatistics stats = new RuntimeStatistics();
    Launcher launcher = Launcher.getLauncher();

    for (int i = 0; i < numRuns; i++) {
      if (i != 0) {
        launcher.reset();
      }

      initialize(numThreads, useParameter, useSerial, replayType, moreStats, ignoreUserFlags);
      Features.initialize(getRuntime().getMaxIterations(), replayType);

      Sampler sampler = StackSampler.start(samplerInterval);
      launcher.startTiming();
      try {
        Reflection.invokeStaticMethod(main, "main", new Object[] { mainArgs });
        launcher.stopTiming();
        launcher.addStats(sampler.stop());
        long timeWithoutGc = launcher.elapsedTime(true);
        long timeWithGc = launcher.elapsedTime(false);
        if (logger.isLoggable(Level.INFO)) {
          logger.info("Runtime (ms): " + timeWithGc + " (without GC: " + timeWithoutGc + ")");
        }
        stats.putStats(timeWithoutGc, timeWithGc);
      } catch (Exception e) {
        throw e;
      }
    }

    launcher.addStats(stats);
    PrintStream out = new PrintStream("stats.txt");
    dumpStatistics(launcher.getStatistics(), System.out, out);
    out.close();
  }
}
