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
import galois.runtime.GaloisRuntime;

/**
 * A worklist in two parts: a thread-local worklist and a global worklist.
 * The thread-local worklist is polled from and added to first. Only when the local 
 * worklist is empty is the global worklist examined for {@link #poll(ForeachContext)}.
 * {@link #add(Object, ForeachContext)} always goes to the local worklist.
 * 
 *
 * @param <T>  the type of elements of the worklist
 */
class LocalWorklist<T> implements Worklist<T> {
  private Worklist<T> outer;
  private Worklist<T>[] inner;

  /**
   * Creates a worklist from a local and global worklist.
   * 
   * @param outerMaker  maker for global worklist
   * @param innerMaker  maker for local worklist
   */
  @SuppressWarnings("unchecked")
  public LocalWorklist(Maker<T> outerMaker, Maker<T> innerMaker) {
    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();

    inner = new Worklist[numThreads];
    for (int i = 0; i < numThreads; i++) {
      inner[i] = innerMaker.make();
    }
    this.outer = outerMaker.make();
  }

  public Worklist<T> newInstance() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    inner[ctx.getThreadId()].add(item, ctx);
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    outer.add(item, ctx);
  }

  @Override
  public boolean isEmpty() {
    return outer.isEmpty();
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    T retval = inner[ctx.getThreadId()].poll(ctx);

    if (retval == null)
      retval = outer.poll(ctx);

    return retval;
  }

  @Override
  public int size() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void finishAddInitial() {
    outer.finishAddInitial();
  }
}
