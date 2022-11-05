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

import galois.runtime.ReplayFeature.Log.Action;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPOutputStream;

import util.fn.LambdaVoid;

class RecordReplayFeature extends ReplayFeature {
  private static final int LOG_SIZE = 1024 * 1024;

  private final AtomicInteger cur;
  private final ReentrantLock lock;
  private final Condition cond;
  private Log log;
  private int curLog;

  public RecordReplayFeature(int maxIterations) {
    super(maxIterations);
    lock = new ReentrantLock();
    cond = lock.newCondition();
    cur = new AtomicInteger();
    log = new Log(LOG_SIZE);
  }

  private int getIndex() {
    boolean makeLog;
    int retval;
    do {
      makeLog = false;
      retval = cur.get();
      if (retval == LOG_SIZE)
        makeLog = true;
      else if (retval > LOG_SIZE) {
        waitForRotate();
        continue;
      }
    } while (!cur.compareAndSet(retval, retval + 1));

    if (makeLog) {
      rotate();
      return 0;
    }

    return retval;
  }

  private void rotate() {
    lock.lock();
    try {
      rotateLog();
      cur.set(1);
      cond.signalAll();
    } finally {
      lock.unlock();
    }
  }

  private void waitForRotate() {
    lock.lock();
    try {
      while (cur.get() > LOG_SIZE) {
        cond.await();
      }
    } catch (InterruptedException e) {
      throw new Error(e);
    } finally {
      lock.unlock();
    }
  }

  private void rotateLog() {
    try {
      ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(getLogName(curLog))));
      out.writeObject(log);
      out.close();

      log = new Log(LOG_SIZE);
      curLog++;
      cur.set(0);
    } catch (FileNotFoundException e) {
      throw new Error(e);
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  @Override
  public void onCommit(Iteration it, int iterationId, Object item) {
    checkValidity();
    // Subtle issue: when getIndex calls rotate, it may update
    // the log variable, so we need to call getIndex first before
    // calling any methods on log.
    int index = getIndex();
    log.putEntry(index, Action.POLL, getRid(item), iterationId);
  }

  @Override
  public void onCallbackExecute(long rid) {
    int index = getIndex();
    log.putEntry(index, Action.CALLBACK, rid, -1);
  }

  @Override
  public boolean isCallbackControlled() {
    checkValidity();
    return false;
  }

  @Override
  public <T> void registerCallback(long rid, LambdaVoid<ForeachContext<T>> callback) {
    checkValidity();
  }

  @Override
  public void registerCallback(long rid, Callback callback) {
    checkValidity();
  }

  @Override
  public void onFinish() {
    checkValidity();
    boolean exactlyFull = cur.get() == LOG_SIZE;
    log.setSize(cur.get());
    rotateLog();
    if (exactlyFull) {
      // Exactly full, need to create a new log
      // to signal the end
      log.setSize(0);
      rotateLog();
    }
  }
}
