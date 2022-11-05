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

import galois.runtime.wl.Worklist;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import util.CPUFunctions;
import util.CollectionMath;
import util.Launcher;
import util.Statistics;
import util.fn.Lambda2Void;

abstract class AbstractConcurrentExecutor<T> implements Executor {
  private static final boolean cpuFunctionsLoaded = GaloisRuntime.getRuntime().moreStats() && CPUFunctions.isLoaded();

  protected Worklist<T> worklist;
  protected Lambda2Void<T, ForeachContext<T>> body;
  protected final int numThreads;
  private final ReentrantLock lock;
  private final Condition moreWork;
  private final List<Process> processes;
  private final AtomicInteger numDone;
  private final Deque<Callback> suspendThunks;

  protected boolean yield;
  protected boolean finish;
  private Callback suspendListener;

  private IdlenessStatistics idleStats;
  private long systemWaitStart;
  private long systemStartTime;
  private boolean idleCounted;

  protected AbstractConcurrentExecutor() {
    numThreads = GaloisRuntime.getRuntime().getMaxThreads();
    numDone = new AtomicInteger();
    lock = new ReentrantLock();
    moreWork = lock.newCondition();
    processes = new ArrayList<Process>();
    suspendThunks = new ArrayDeque<Callback>();
  }

  protected abstract Process newProcess(int tid);

  @Override
  public boolean isSerial() {
    return false;
  }

  @Override
  public void suspend(Callback listener) {
    systemWaitStart = System.nanoTime();
    suspendListener = listener;
    yield = true;
    makeAllDone();
    wakeupAll();
  }

  public void suspendDone() {
    suspendListener = null;
    if (!idleCounted) {
      idleStats.put(systemStartTime, System.nanoTime(), processes, System.nanoTime() - systemWaitStart);
      idleCounted = true;
    }
  }

  private void startTiming() {
    systemStartTime = System.nanoTime();
  }

  private void stopTiming() {
    if (!idleCounted) {
      idleStats.put(systemStartTime, System.nanoTime(), processes, 0);
      idleCounted = true;
    }
  }

  @Override
  public abstract void arbitrate(Iteration current, Iteration conflicter);

  /**
   * Returns if there really is no more work to be done.
   * 
   * <p>
   * Needed for ordered executors, e.g. in OrderedExecutor while the worklist is empty, rob may
   * not be empty (may have work to be committed, which my result in generation of new work),
   * therefore worklist being empty is not a sufficient condition for termination
   *
   * @return  whether all iterations are complete 
   */
  protected boolean allIterRetired() {
    return true;
  }

  private final void initialize(Lambda2Void<T, ForeachContext<T>> body, Worklist<T> worklist) {
    this.body = body;
    this.worklist = worklist;
    processes.clear();
    for (int tid = 0; tid < numThreads; tid++) {
      processes.add(newProcess(tid));
    }
  }

  private void reset() {
    yield = false;
    finish = false;
    suspendListener = null;
    idleCounted = false;
    suspendThunks.clear();
    numDone.set(0);
  }

  public final IterationStatistics call(Lambda2Void<T, ForeachContext<T>> body, Worklist<T> worklist)
      throws ExecutionException {
    initialize(body, worklist);

    try {
      idleStats = new IdlenessStatistics();

      while (true) {
        reset();

        startTiming();
        GaloisRuntime.getRuntime().callAll(processes);
        stopTiming();

        if (!suspendThunks.isEmpty()) {
          GaloisRuntime.getRuntime().replaceWithRootContextAndCall(new Callback() {
            public void call() {
              for (Callback thunk : suspendThunks) {
                thunk.call();
              }
            }
          });
        }

        if (!allIterRetired()) {
          continue;
        }

        if (finish || !yield) {
          break;
        }
      }

      Launcher.getLauncher().addStats(idleStats);

      if (cpuFunctionsLoaded) {
        CpuStatistics cpuStats = new CpuStatistics(numThreads);
        for (Process p : processes) {
          cpuStats.putStats(p.id, p.cpuIds);
        }
        Launcher.getLauncher().addStats(cpuStats);
      }

      IterationStatistics stats = new IterationStatistics();
      for (Process p : processes) {
        stats.putStats(p.thread, p.numCommitted, p.numAborted);
      }

      return stats;
    } catch (InterruptedException e) {
      throw new ExecutionException(e);
    } catch (Exception e) {
      throw new ExecutionException(e);
    }
  }

  protected synchronized void addSuspendThunk(Callback callback) {
    suspendThunks.add(callback);
  }

  protected void wakeupOne() {
    lock.lock();
    try {
      moreWork.signal();
    } finally {
      lock.unlock();
    }
  }

  protected void wakeupAll() {
    lock.lock();
    try {
      moreWork.signalAll();
    } finally {
      lock.unlock();
    }
  }

  protected void makeAllDone() {
    // Can't use set() because there is a rare possibility
    // that it would happen between an increment decrement
    // pair in isDone and prevent some threads from leaving
    int n;
    do {
      n = numDone.get();
    } while (!numDone.compareAndSet(n, numThreads));
  }

  protected boolean someDone() {
    return numDone.get() > 0;
  }

  protected abstract class Process implements Callable<Object>, ForeachContext<T> {
    private final int id;
    Thread thread;
    protected int numCommitted;
    protected int numAborted;

    private long startTime;
    private long stopTime;
    private long waitStart;
    private long accumWait;

    private int[] cpuIds;

    protected Process(int id) {
      this.id = id;
      if (cpuFunctionsLoaded)
        cpuIds = new int[256];
    }

    protected abstract void doCall() throws Exception;

    protected final void recordCpuId() {
      if (cpuFunctionsLoaded) {
        int cpuid = CPUFunctions.getCpuId();
        cpuIds[cpuid]++;
      }
    }

    @Override
    public final Object call() throws Exception {
      thread = Thread.currentThread();

      accumWait = 0;
      stopTime = 0;
      startTime = System.nanoTime();
      try {
        doCall();
      } finally {
        stopTime = System.nanoTime();
        if (suspendListener != null) {
          suspendListener.call();
        }
      }

      return null;
    }

    protected final void startWaiting() {
      waitStart = System.nanoTime();
    }

    protected final void stopWaiting() {
      accumWait += System.nanoTime() - waitStart;
    }

    protected final boolean isDone() throws InterruptedException {
      lock.lock();
      try {
        // Encountered an error by another thread?
        if (numDone.incrementAndGet() > numThreads) {
          return true;
        }

        // Last man: safe to check global termination property
        if (numDone.get() == numThreads) {
          if (worklist.isEmpty()) {
            wakeupAll();
            return true;
          } else {
            numDone.decrementAndGet();
            return false;
          }
        }

        // Otherwise, wait for some work
        while (numDone.get() < numThreads && worklist.isEmpty()) {
          startWaiting();
          try {
            moreWork.await();
          } finally {
            stopWaiting();
          }
        }

        if (numDone.get() == numThreads) {
          // Done, truly
          wakeupAll();
          return true;
        } else if (numDone.get() > numThreads) {
          // Error by another thread, finish up
          return true;
        } else {
          // More work to do!
          numDone.decrementAndGet();
          return false;
        }
      } finally {
        lock.unlock();
      }
    }

    @Override
    public int getThreadId() {
      return id;
    }
  }

  private static class CpuStatistics extends Statistics {
    private List<int[]> rows;
    private final List<Integer> tids;
    private int maxLength;
    private List<List<Integer>> results;

    public CpuStatistics(int numThreads) {
      rows = new ArrayList<int[]>();
      results = new ArrayList<List<Integer>>();
      tids = new ArrayList<Integer>();
    }

    /**
     * 
     * @param cpuIds  an array of counts indexed by id
     */
    public void putStats(int tid, int[] cpuIds) {
      rows.add(cpuIds);
      tids.add(tid);
      maxLength = Math.max(maxLength, cpuIds.length);
    }

    private void computeResults() {
      if (rows != null) {
        results.add(getResults());
        rows = null;
      }
    }

    /**
     * Computes the maximum (by one thread) and total samples per
     * cpu.  
     * 
     * <p>
     * Takes data in the following form:
     * <pre>       ... cpu id ...
     * tid1: 1   2   0 100   1   2   3
     * tid2: 100 0   1   0   0   0   0
     * </pre>
     * and computes the maximums and total samples by column, and
     * returns a list of (cpuid, tid, max, total). 
     * </p>
     * 
     * @return the result as a list of quads (cpuid, tid, max, total)
     */
    private List<Integer> getResults() {
      List<Integer> results = new ArrayList<Integer>();

      int size = rows.size();
      for (int i = 0; i < maxLength; i++) {
        int max = 0;
        int maxTid = -1;
        int sum = 0;

        for (int j = 0; j < size; j++) {
          int value = rows.get(j)[i];
          if (max < value) {
            max = value;
            maxTid = tids.get(j);
          }
          
          sum += value;
        }

        if (sum > 0) {
          results.add(i);
          results.add(maxTid);
          results.add(max);
          results.add(sum);
        }
      }

      return results;
    }

    @Override
    public void dumpFull(PrintStream out) {
      computeResults();
      printFullHeader(out, "Processor Utilization");

      out.print("Max util per logical processor [cpuid, tid, utilization, (max / total samples)] per executor:\n");
      for (List<Integer> r : results) {
        int size = r.size();
        out.print("[");
        for (int i = 0; i < size; i += 4) {
          int cpuid = r.get(i);
          int tid = r.get(i + 1);
          int max = r.get(i + 2);
          int total = r.get(i + 3);
          float average = max / (float) total;
          out.printf("%d %d %.4f (%d / %d)", cpuid, tid, average, max, total);
          if (i != size - 4) {
            out.print(", ");
          }
        }
        out.print("]");
        out.println();
      }
    }

    @Override
    public void dumpSummary(PrintStream out) {
      computeResults();
      printSummaryHeader(out, "Processor Utilization");

      int max = 0;
      int sum = 0;
      int count = 0;
      for (List<Integer> r : results) {
        for (int i = 0; i < r.size(); i += 4) {
          max += r.get(i + 2);
          sum += r.get(i + 3);
        }
        count += r.size() / 4;
      }
      float mean = sum == 0 ? 0 : max / (float) sum;
      float meanCounts = results.size() == 0 ? 0 : count / (float) results.size();

      out.printf("mean: %.4f mean total processors: %.2f\n", mean, meanCounts);
    }

    @Override
    public void merge(Object other) {
      CpuStatistics stats = (CpuStatistics) other;
      stats.computeResults();
      results.addAll(stats.results);
    }
  }

  private class IdlenessStatistics extends Statistics {
    private final List<Long> threadTimes;
    private final List<Long> idleTimes;

    private IdlenessStatistics() {
      threadTimes = new ArrayList<Long>();
      idleTimes = new ArrayList<Long>();
    }

    @Override
    public void dumpFull(PrintStream out) {
      printFullHeader(out, "Idleness");

      out.printf("Thread time per measured period (thread*ms): %s\n", threadTimes);
      out.printf("Idle thread time per measured period (thread*ms): %s\n", idleTimes);
    }

    public void put(long systemStartTime, long systemStopTime, List<Process> processes, long systemAccumWait) {
      long idleTime = 0;
      for (Process p : processes) {
        long stopTime = p.stopTime;

        // TODO(ddn): Figure out why this happens
        if (p.startTime == 0) {
          continue;
        }

        // Finished early, use system time as our stop time
        if (p.stopTime == 0) {
          stopTime = systemStopTime;
        }

        idleTime += (p.startTime - systemStartTime) / 1e6;
        idleTime += (systemStopTime - stopTime) / 1e6;
        idleTime += p.accumWait / 1e6;
      }

      idleTime += systemAccumWait / 1e6;
      threadTimes.add((systemStopTime - systemStartTime) / (long) 1e6 * processes.size());
      idleTimes.add(idleTime);
    }

    @Override
    public void dumpSummary(PrintStream out) {
      printSummaryHeader(out, "Idleness (thread*ms)");
      long totalThread = CollectionMath.sumLong(threadTimes);
      long totalIdle = CollectionMath.sumLong(idleTimes);
      out.printf("Thread Time: %d Idle Time: %d Rel. Idleness: %.4f\n", totalThread, totalIdle, totalIdle
          / (double) totalThread);
    }

    @Override
    public void merge(Object obj) {
      IdlenessStatistics other = (IdlenessStatistics) obj;
      threadTimes.addAll(other.threadTimes);
      idleTimes.addAll(other.idleTimes);
    }
  }
}
