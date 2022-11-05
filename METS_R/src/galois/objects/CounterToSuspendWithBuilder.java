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

File: CounterToSuspendWith.java 

 */

package galois.objects;

import galois.objects.AbstractCounter.AbstractConcurrentCounter;
import galois.objects.AbstractCounter.AbstractSerialCounter;
import galois.runtime.ForeachContext;
import galois.runtime.Features;
import galois.runtime.GaloisRuntime;
import util.fn.LambdaVoid;

/**
 * Builds counters that trigger a Galois iterator to suspend with a serial
 * action when a given value is reached. After the serial action finishes,
 * execution returns to the iterator and the counter is reset. 
 *
 *
 * @param <T>  type of elements being iterated over in Galois iterator
 * @see ForeachContext#suspendWith(galois.runtime.Callback)
 */
public class CounterToSuspendWithBuilder {

  /**
   * Creates a new counter. When this counter reaches the given value or
   * more, suspend the current Galois iterator and execute the given
   * action.
   * 
   * @param countTo   value to count to
   * @param exact     trigger action when value is exactly <code>countTo</code>;
   *                  otherwise, trigger action when <code>value &gt;= countTo</code>
   * @param callback  action to execute when counter is triggered
   */
  public <T> Counter<T> create(int countTo, boolean exact, LambdaVoid<ForeachContext<T>> callback) {
    if (GaloisRuntime.getRuntime().useSerial())
      return new SerialCounterToSuspendWith<T>(countTo, exact, callback);
    else
      return new ConcurrentCounterToSuspendWith<T>(countTo, exact, callback);
  }

  private static class SerialCounterToSuspendWith<T> extends AbstractSerialCounter<T> {
    private final LambdaVoid<ForeachContext<T>> callback;
    private final boolean exact;

    protected SerialCounterToSuspendWith(int countTo, boolean exact, LambdaVoid<ForeachContext<T>> callback) {
      super(countTo);
      this.callback = callback;
      this.exact = exact;

      Features.getReplayFeature().registerCallback(getRid(), callback);
    }

    private boolean compare(int a, int b) {
      return exact ? a == b : a >= b;
    }

    @Override
    protected void body(final ForeachContext<T> ctx, int delta) {
      if (!Features.getReplayFeature().isCallbackControlled()) {
        if (compare(counterAddAndGet(delta), countTo)) {
          ctx.suspendWith(new MyCallback(ctx));
        }
      }
    }

    private class MyCallback extends OnlyAddCallback {
      private MyCallback(ForeachContext<T> ctx) {
        super(ctx);
      }

      @Override
      public void call() {
        if (!compare(counterGet(), countTo)) {
          return;
        }
        reset();

        Features.getReplayFeature().onCallbackExecute(getRid());
        callback.call(this);
      }
    }
  }

  private static class ConcurrentCounterToSuspendWith<T> extends AbstractConcurrentCounter<T> {
    private final LambdaVoid<ForeachContext<T>> callback;
    private final boolean exact;

    protected ConcurrentCounterToSuspendWith(int countTo, boolean exact, LambdaVoid<ForeachContext<T>> callback) {
      super(countTo);
      this.callback = callback;
      this.exact = exact;

      Features.getReplayFeature().registerCallback(getRid(), callback);
    }

    private boolean compare(int a, int b) {
      return exact ? a == b : a >= b;
    }

    @Override
    protected void body(final ForeachContext<T> ctx, int delta) {
      if (!Features.getReplayFeature().isCallbackControlled()) {
        if (compare(counterAddAndGet(delta), countTo)) {
          ctx.suspendWith(new MyCallback(ctx));
        }
      }
    }

    private class MyCallback extends OnlyAddCallback {
      private MyCallback(ForeachContext<T> ctx) {
        super(ctx);
      }

      @Override
      public void call() {
        if (!compare(counterGet(), countTo)) {
          return;
        }
        reset();

        Features.getReplayFeature().onCallbackExecute(getRid());
        callback.call(this);
      }
    }
  }
}
