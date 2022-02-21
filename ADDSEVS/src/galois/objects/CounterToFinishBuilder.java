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

File: CounterToFinish.java 

 */

package galois.objects;

import galois.objects.AbstractCounter.AbstractConcurrentCounter;
import galois.objects.AbstractCounter.AbstractSerialCounter;
import galois.runtime.ForeachContext;
import galois.runtime.GaloisRuntime;

/**
 * Builds counters that trigger a Galois iterator to finish when a given value
 * is reached. 
 *
 *
 * @param <T>  type of elements being iterated over in Galois iterator
 * @see ForeachContext#finish()
 */
public class CounterToFinishBuilder {
  public <T> Counter<T> create(int countTo) {
    if (GaloisRuntime.getRuntime().useSerial()) {
      return new SerialCounterToFinish<T>(countTo);
    } else {
      return new ConcurrentCounterToFinish<T>(countTo);
    }
  }

  private static class SerialCounterToFinish<T> extends AbstractSerialCounter<T> {
    public SerialCounterToFinish(int countTo) {
      super(countTo);
    }

    @Override
    protected void body(ForeachContext<T> ctx, int delta) {
      if (counterAddAndGet(delta) >= countTo) {
        ctx.finish();
      }
    }
  }

  private static class ConcurrentCounterToFinish<T> extends AbstractConcurrentCounter<T> {
    public ConcurrentCounterToFinish(int countTo) {
      super(countTo);
    }

    @Override
    protected void body(ForeachContext<T> ctx, int delta) {
      if (counterAddAndGet(delta) >= countTo) {
        ctx.finish();
      }
    }
  }
}
