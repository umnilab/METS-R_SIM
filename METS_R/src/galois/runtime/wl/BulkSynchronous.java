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

import galois.runtime.ForeachContext;

@MatchingLeafVersion(BulkSynchronousLeaf.class)
@MatchingConcurrentVersion(ConcurrentBulkSynchronous.class)
public class BulkSynchronous<T> implements Worklist<T> {
  private Worklist<T> current;
  private Worklist<T> next;
  private int size;

  public BulkSynchronous(Maker<T> maker, boolean needSize) {
    this(maker.make(), maker.make(), needSize);
  }

  private BulkSynchronous(Worklist<T> current, Worklist<T> next, boolean needSize) {
    this.current = current;
    this.next = next;
    size = 0;
  }

  @Override
  public Worklist<T> newInstance() {
    return new BulkSynchronous<T>(current.newInstance(), next.newInstance(), false);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    size++;
    next.add(item, ctx);
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    T retval = current.poll(ctx);
    if (retval == null && (retval = next.poll(ctx)) != null) {
      current = next;
      next = next.newInstance();
    }

    if (retval != null)
      size--;

    return retval;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public void finishAddInitial() {

  }
}
