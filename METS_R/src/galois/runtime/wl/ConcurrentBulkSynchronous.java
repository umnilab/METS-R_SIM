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

import galois.objects.Counter;
import galois.objects.CounterToSuspendWithBuilder;
import galois.runtime.ForeachContext;

import java.util.concurrent.atomic.AtomicInteger;

import util.fn.LambdaVoid;

@MatchingConcurrentVersion(ConcurrentBulkSynchronous.class)
@MatchingLeafVersion(ConcurrentBulkSynchronousLeaf.class)
class ConcurrentBulkSynchronous<T> implements Worklist<T> {
  private Worklist<T> current;
  private Worklist<T> next;
  private boolean isEmpty;
  private final Counter<T> counter;
  private AtomicInteger size;

  public ConcurrentBulkSynchronous(Maker<T> m, boolean needSize) {
    this(m.make(), m.make(), needSize);
  }

  private ConcurrentBulkSynchronous(Worklist<T> c, Worklist<T> n, boolean needSize) {
    this.current = c;
    this.next = n;
    counter = new CounterToSuspendWithBuilder().create(1, false, new LambdaVoid<ForeachContext<T>>() {
      @Override
      public void call(ForeachContext<T> x) {
        if (next.isEmpty()) {
          isEmpty = true;
        } else {
          Worklist<T> t = current;
          current = next;
          next = t;
        }
      }
    });

    if (needSize)
      size = new AtomicInteger();
  }

  @Override
  public Worklist<T> newInstance() {
    return new ConcurrentBulkSynchronous<T>(current.newInstance(), next.newInstance(), size != null);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    if (size != null)
      size.incrementAndGet();

    isEmpty = false;
    next.add(item, ctx);
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    T retval = current.poll(ctx);
    if (retval == null && !isEmpty) {
      counter.increment(ctx);
    }

    if (size != null && retval != null)
      size.decrementAndGet();

    return retval;
  }

  @Override
  public boolean isEmpty() {
    if (size != null) {
      return size.get() == 0;
    } else {
      return isEmpty;
    }
  }

  @Override
  public int size() {
    if (size != null)
      return size.get();
    else
      throw new UnsupportedOperationException();
  }

  @Override
  public void finishAddInitial() {

  }
}
