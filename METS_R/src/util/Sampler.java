/*
Copyright (c) 2008 Evan Jones, Yang Zhang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */

package util;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Abstract class for simple sampling daemons.
 * 
 *
 */
public abstract class Sampler implements Runnable {
  private final int intervalMillis;
  private final AtomicBoolean doStop;
  private boolean isDone;
  private final ReentrantLock lock;
  private final Condition done;
  private final Random random;

  /**
   * Creates a sampler that executes periodically. The sampler uses randomization
   * so the interval is probabilistic with uniform distribution.
   * 
   * @param intervalMillis  the expected interval period in milliseconds. If
   *                        <code>intervalMillis</code> is zero, the sampler
   *                        will run with an infinite interval (i.e., no-op).
   */
  protected Sampler(int intervalMillis) {
    this.intervalMillis = intervalMillis;

    doStop = new AtomicBoolean(false);
    lock = new ReentrantLock();
    done = lock.newCondition();
    random = new Random();
  }

  /**
   * Takes a sample at the interval.
   */
  protected abstract void sample();

  /**
   * Returns the statistics associated with this sampler.
   * 
   * @return   statistics
   */
  protected abstract Statistics dumpSamples();

  @Override
  public void run() {
    while (!doStop.get()) {
      try {
        int sleepTime = intervalMillis - (intervalMillis / 2) + random.nextInt(intervalMillis);
        Thread.sleep(sleepTime);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      sample();
    }

    lock.lock();
    try {
      isDone = true;
      done.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops the sampler and returns the accumulated statistics.
   * 
   * @return   the accumulated statistics or null if the interval is infinite
   */
  public Statistics stop() {
    if (intervalMillis > 0) {
      doStop.set(true);
      lock.lock();
      try {
        while (!isDone) {
          done.await();
        }
        return dumpSamples();
      } catch (InterruptedException e) {
        throw new Error(e);
      } finally {
        lock.unlock();
      }
    }

    return null;
  }

  /**
   * Starts sampling thread. Subclasses should use this method to start
   * sampling.
   * 
   * @return   a sampler
   */
  protected final static Sampler start(Sampler sampler) {
    if (sampler.intervalMillis > 0) {
      Thread t = new Thread(sampler, "Sampler");
      t.setDaemon(true);
      t.start();
    }
    return sampler;
  }
}
