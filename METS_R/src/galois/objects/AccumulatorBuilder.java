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

File: AccumulatorBuilder.java 

 */

package galois.objects;

import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * An accumulator which allows concurrent updates but not concurrent reads.
 * 
 * @see GMutableInteger
 * @see util.MutableInteger
 */
public class AccumulatorBuilder {

  public Accumulator create() {
    return create(0);
  }

  public Accumulator create(int value) {
    if (GaloisRuntime.getRuntime().useSerial())
      return new SerialAccumulator(value);
    else
      return new ConcurrentAccumulator(value);
  }

  private static class SerialAccumulator implements Accumulator {
    private int value;

    public SerialAccumulator(int v) {

    }

    @Override
    public final void add(int delta) {
      add(delta, MethodFlag.ALL);
    }

    @Override
    public void add(final int delta, byte flags) {
      value += delta;
    }

    @Override
    public int get() {
      return value;
    }

    @Override
    public void set(int v) {
      value = v;
    }

    @Override
    public void access(byte flags) {
    }
  }

  private static class ConcurrentAccumulator implements Accumulator {
    private AtomicInteger value;

    public ConcurrentAccumulator(int v) {
      value = new AtomicInteger(v);
    }

    @Override
    public final void add(int delta) {
      add(delta, MethodFlag.ALL);
    }

    @Override
    public void add(final int delta, byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
        Iteration it = Iteration.getCurrentIteration();
        if (it != null)
          GaloisRuntime.getRuntime().onUndo(it, new Callback() {
            public void call() {
              value.addAndGet(-delta);
            }
          });
      }
      value.addAndGet(delta);
    }

    @Override
    public int get() {
      assert GaloisRuntime.getRuntime().inRoot();
      return value.get();
    }

    @Override
    public void set(int v) {
      assert GaloisRuntime.getRuntime().inRoot();
      value.set(v);
    }

    @Override
    public void access(byte flags) {
    }
  }
}
