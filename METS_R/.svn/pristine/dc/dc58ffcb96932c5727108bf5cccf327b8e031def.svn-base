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

File: AbstractCounter.java 

 */

package galois.objects;

import galois.runtime.AbstractExecutorContext;
import galois.runtime.Callback;
import galois.runtime.ForeachContext;
import galois.runtime.Features;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;
import galois.runtime.NonDeterministicCallback;
import galois.runtime.Replayable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A class to factor out common behavior of counters.
 * 
 *
 * @param <T>
 */
abstract class AbstractCounter<T> implements Counter<T>, Replayable {
  protected final int countTo;
  private long rid;

  protected AbstractCounter(int countTo) {
    this.countTo = countTo;

    Features.getReplayFeature().onCreateReplayable(this);

    assert getRid() != 0;
  }

  protected abstract int counterAddAndGet(int delta);

  protected abstract void counterSet(int value);

  protected abstract int counterGet();

  @Override
  public final void access(byte flags) {

  }

  @Override
  public final long getRid() {
    return rid;
  }

  @Override
  public final void setRid(long rid) {
    this.rid = rid;
  }

  @Override
  public final void increment(ForeachContext<T> ctx) {
    increment(ctx, MethodFlag.ALL);
  }

  @Override
  public final void increment(ForeachContext<T> ctx, byte flags) {
    increment(ctx, 1, flags);
  }

  @Override
  public final void increment(final ForeachContext<T> ctx, final int delta) {
    increment(ctx, delta, MethodFlag.ALL);
  }

  @Override
  public final void increment(int delta) {
    int value = counterAddAndGet(delta);
    if (value >= countTo) {
      throw new IllegalArgumentException("Serial Increment should not trigger callback");
    }
  }

  @Override
  public void reset() {
    counterSet(0);
  }

  /**
   * Executes when counter reaches it specified value.
   * 
   * @param ctx    a context
   * @param delta  delta to counter value
   */
  protected abstract void body(ForeachContext<T> ctx, int delta);

  @Override
  public final void increment(final ForeachContext<T> ctx, final int delta, byte flags) {
    if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
      GaloisRuntime.getRuntime().onCommit(Iteration.getCurrentIteration(), new Callback() {
        @Override
        public void call() {
          body(ctx, delta);
        }
      });
    } else {
      body(ctx, delta);
    }
  }

  /**
   * Wrapper around contexts to prevent users from calling methods besides
   * {@link ForeachContext#add(Object)} because other methods don't have
   * meaning during serial execution.
   * 
   *
   */
  protected abstract class OnlyAddCallback extends AbstractExecutorContext<T> implements NonDeterministicCallback {
    private ForeachContext<T> ctx;

    public OnlyAddCallback(ForeachContext<T> ctx) {
      this.ctx = ctx;
    }

    @Override
    public final void add(T item) {
      ctx.add(item, MethodFlag.NONE);
    }

    @Override
    public abstract void call();

    @Override
    public final long getRid() {
      return rid;
    }

    @Override
    public final void setRid(long r) {
      rid = r;
    }
  }

  abstract static class AbstractConcurrentCounter<T> extends AbstractCounter<T> {
    private AtomicInteger counter;

    protected AbstractConcurrentCounter(int countTo) {
      super(countTo);
      counter = new AtomicInteger();
      counter.set(0);
    }

    protected final int counterAddAndGet(int delta) {
      return counter.addAndGet(delta);
    }

    protected final void counterSet(int value) {
      counter.set(value);
    }

    protected final int counterGet() {
      return counter.get();
    }
  }

  abstract static class AbstractSerialCounter<T> extends AbstractCounter<T> {
    private int counter;

    protected AbstractSerialCounter(int countTo) {
      super(countTo);
    }

    protected final int counterAddAndGet(int delta) {
      counter += delta;
      return counter;
    }

    protected final void counterSet(int value) {
      counter = value;
    }

    protected final int counterGet() {
      return counter;
    }
  }
}
