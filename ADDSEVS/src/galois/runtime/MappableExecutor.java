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





package galois.runtime;

import galois.objects.Mappable;
import galois.runtime.wl.Worklist;
import util.fn.Lambda2Void;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

class MappableExecutor<T> extends UnorderedExecutor<Object> {
  private final Mappable<T> mappable;
  private Object[] args;
  private MappableType type;
  private Object body;
  private Lambda2Void<Object, ForeachContext<Object>> dummyBody;

  public MappableExecutor(Mappable<T> mappable) {
    this.mappable = mappable;
  }

  @Override
  protected Process newProcess(int tid) {
    return new MyProcess(tid);
  }

  private void initialize(Object body, MappableType type, Object[] args) {
    this.body = body;
    this.type = type;
    this.args = args;
  }

  public IterationStatistics call(Object body, MappableType type, Object... args) throws ExecutionException {
    initialize(body, type, args);
    IterationStatistics stats = call(dummyBody, new DummyWorklist(numThreads));
    mappable.mapInternalDone();
    return stats;
  }

  private class MyProcess extends GaloisProcess implements MapInternalContext {
    public MyProcess(int id) {
      super(id);
    }

    @Override
    protected void doCall() throws Exception {
      try {
        type.call(mappable, body, this, args);
      } finally {
        Iteration.setCurrentIteration(null);
      }
    }

    @Override
    public void abort() {
      doAbort();
    }

    @Override
    public void begin() {
      setupCurrentIteration();
    }

    @Override
    public void commit(Object obj) {
      doCommit(obj);
    }
  }

  private class DummyWorklist implements Worklist<Object> {
    private AtomicInteger size;

    private DummyWorklist(int numThreads) {
      size = new AtomicInteger(numThreads);
    }

    @Override
    public void add(Object item, ForeachContext<Object> ctx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void addInitial(Object item, ForeachContext<Object> ctx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void finishAddInitial() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
      return size.get() <= 0;
    }

    @Override
    public Object poll(ForeachContext<Object> ctx) {
      if (size.getAndDecrement() > 0) {
        return this;
      } else {
        return null;
      }
    }

    @Override
    public int size() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Worklist<Object> newInstance() {
      throw new UnsupportedOperationException();
    }
  }
}
