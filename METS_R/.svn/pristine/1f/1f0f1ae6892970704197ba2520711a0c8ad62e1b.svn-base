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

File: GMutableIntegerBuilder.java 

 */

package galois.objects;

import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

/**
 * Builds {@link GMutableInteger}s.
 */
public class GMutableIntegerBuilder {

  /**
   * Creates a new mutable integer with value 0
   */
  public GMutableInteger create() {
    return create(0, MethodFlag.ALL);
  }

  /**
   * Creates a new mutable integer with value 0
   */
  public GMutableInteger create(byte flags) {
    return create(0, flags);
  }

  /**
   * Creates a new mutable integer with the given value
   * 
   * @param value
   *          initial value
   */
  public GMutableInteger create(int value, byte flags) {
    if (GaloisRuntime.getRuntime().useSerial())
      return new SerialGMutableInteger(value, flags);
    else
      return new ConcurrentGMutableInteger(value, flags);
  }

  private static class SerialGMutableInteger implements GMutableInteger {
    private int value;

    private SerialGMutableInteger(int value, byte flags) {
      this.value = value;
    }

    @Override
    public final int get() {
      return get(MethodFlag.ALL);
    }

    @Override
    public final int get(byte flags) {
      return value;
    }

    @Override
    public final void set(int value) {
      set(value, MethodFlag.ALL);
    }

    public void set(int value, byte flags) {
      this.value = value;
    }

    @Override
    public final int incrementAndGet() {
      return incrementAndGet(MethodFlag.ALL);
    }

    public int incrementAndGet(byte flags) {
      return ++value;
    }

    @Override
    public final int getAndIncrement() {
      return getAndIncrement(MethodFlag.ALL);
    }

    public int getAndIncrement(byte flags) {
      return value++;
    }

    @Override
    public final void add(int delta) {
      add(delta, MethodFlag.ALL);
    }

    public void add(int delta, byte flags) {
      value += delta;
    }

    @Override
    public final int decrementAndGet() {
      return decrementAndGet(MethodFlag.ALL);
    }

    public int decrementAndGet(byte flags) {
      return --value;
    }

    @Override
    public final int getAndDecrement() {
      return getAndDecrement(MethodFlag.ALL);
    }

    public int getAndDecrement(byte flags) {
      return value--;
    }

    @Override
    public void access(byte flags) {
    }
  }

  private static class ConcurrentGMutableInteger extends AbstractLockable implements GMutableInteger {
    private int value;

    private ConcurrentGMutableInteger(int value, byte flags) {
      this.value = value;
      prolog(flags);
    }

    @Override
    public final int get() {
      return get(MethodFlag.ALL);
    }

    @Override
    public final int get(byte flags) {
      prolog(flags);
      return value;
    }

    @Override
    public final void set(int value) {
      set(value, MethodFlag.ALL);
    }

    public void set(int value, byte flags) {
      prolog(flags);
      this.value = value;
    }

    @Override
    public final int incrementAndGet() {
      return incrementAndGet(MethodFlag.ALL);
    }

    public int incrementAndGet(byte flags) {
      prolog(flags);
      return ++value;
    }

    @Override
    public final int getAndIncrement() {
      return getAndIncrement(MethodFlag.ALL);
    }

    public int getAndIncrement(byte flags) {
      prolog(flags);
      return value++;
    }

    @Override
    public final void add(int delta) {
      add(delta, MethodFlag.ALL);
    }

    public void add(int delta, byte flags) {
      prolog(flags);
      value += delta;
    }

    @Override
    public final int decrementAndGet() {
      return decrementAndGet(MethodFlag.ALL);
    }

    public int decrementAndGet(byte flags) {
      prolog(flags);
      return --value;
    }

    @Override
    public final int getAndDecrement() {
      return getAndDecrement(MethodFlag.ALL);
    }

    public int getAndDecrement(byte flags) {
      prolog(flags);
      return value--;
    }

    private void prolog(byte flags) {
      Iteration it = Iteration.acquire(this, flags);

      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
        if (it == null) {
          it = Iteration.getCurrentIteration();
        }
        if (it != null) {
          final int oldValue = get(MethodFlag.NONE);
          GaloisRuntime.getRuntime().onUndo(it, new Callback() {
            @Override
            public void call() {
              value = oldValue;
            }
          });
        }
      }
    }

    @Override
    public void access(byte flags) {
    }
  }
}
