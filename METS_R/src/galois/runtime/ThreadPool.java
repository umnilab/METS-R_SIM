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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

/**
 * Simple thread pool.
 * 
 *
 */
class ThreadPool {
  private final List<Worker> workers;
  private final List<Thread> threads;
  private List<? extends Callable<?>> callables;
  private final Semaphore start;
  private final Semaphore finish;
  private final int numThreads;
  private volatile boolean shutdown;

  /**
   * Create a thread pool with the given number of threads.
   * 
   * @param numThreads  the number of threads in the thread pool
   */
  public ThreadPool(int numThreads) {
    this.numThreads = numThreads;

    workers = new ArrayList<Worker>();
    threads = new ArrayList<Thread>();
    start = new Semaphore(0);
    finish = new Semaphore(0);

    for (int i = 0; i < numThreads; i++) {
      Worker w = new Worker(i);
      Thread t = new Thread(w);
      t.setDaemon(true);
      threads.add(t);
      workers.add(w);

      t.start();
    }
  }

  /**
   * Shutdown and release the threads in this thread pool.
   */
  public void shutdown() {
    shutdown = true;
    start.release(numThreads);
  }

  /**
   * Calls the given function with all the threads in the thread pool.
   * 
   * @param callables  function to call
   * @throws InterruptedException  if a thread was interrupted waiting for shutdown
   * @throws ExecutionException    if an error was encountered while execution the function
   */
  public void callAll(List<? extends Callable<?>> callables) throws InterruptedException, ExecutionException {
    this.callables = callables;
    start.release(numThreads);
    finish.acquire(numThreads);
    for (int i = 0; i < numThreads; i++) {
      if (workers.get(i).error != null) {
        throw new ExecutionException(workers.get(i).error);
      }
    }
  }

  private class Worker implements Runnable {
    private final int id;
    private Throwable error;

    public Worker(int id) {
      this.id = id;
    }

    private void __stackSamplerRecordMe() throws Exception {
      callables.get(id).call();
    }

    @Override
    public void run() {
      while (!shutdown) {
        try {
          start.acquire();
          if (!shutdown) {
            __stackSamplerRecordMe();
          }
        } catch (InterruptedException e) {
          error = e;
        } catch (Throwable e) {
          error = e;
        } finally {
          finish.release();
        }
      }
    }
  }
}
