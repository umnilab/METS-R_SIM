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





package galois.runtime.wl;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

class SpinReadWriteLock implements ReadWriteLock {
  private final int numThreads;
  private final AtomicInteger lock;

  public SpinReadWriteLock(int numThreads) {
    this.numThreads = numThreads;
    this.lock = new AtomicInteger();
  }

  @Override
  public Lock readLock() {
    return new ReadLock();
  }

  @Override
  public Lock writeLock() {
    return new WriteLock();
  }

  private class ReadLock implements Lock {
    @Override
    public void lock() {
      while (lock.incrementAndGet() <= 0) {
        lock.decrementAndGet();
      }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Condition newCondition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock() {
      if (lock.incrementAndGet() > 0) {
        return true;
      } else {
        lock.decrementAndGet();
        return false;
      }
    }

    @Override
    public boolean tryLock(long arg0, TimeUnit arg1) throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void unlock() {
      lock.decrementAndGet();
    }

  }

  private class WriteLock implements Lock {
    @Override
    public void lock() {
      while (!lock.compareAndSet(0, -numThreads)) {
        ;
      }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Condition newCondition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock() {
      return lock.compareAndSet(0, -numThreads);
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void unlock() {
      while (!lock.compareAndSet(-numThreads, 0)) {
        ;
      }
    }
  }
}
