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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;

class SerialMappableExecutor<T> implements Executor, MapInternalContext {
  private final Mappable<T> mappable;
  private final Deque<Callback> suspendThunks;
  private int numCommitted;

  public SerialMappableExecutor(Mappable<T> mappable) {
    this.mappable = mappable;
    suspendThunks = new ArrayDeque<Callback>();
  }

  public IterationStatistics call(Object body, MappableType type, Object... args) throws ExecutionException {
    numCommitted = 0;
    type.call(mappable, body, this, args);
    mappable.mapInternalDone();
    IterationStatistics stats = new IterationStatistics();
    stats.putStats(Thread.currentThread(), numCommitted, 0);
    return stats;
  }

  @Override
  public void abort() {
  }

  @Override
  public void begin() {
  }

  @Override
  public void commit(Object obj) {
    Features.getReplayFeature().onCommit(null, getIterationId(), obj);
    numCommitted++;
    if (!suspendThunks.isEmpty()) {
      for (Callback c : suspendThunks)
        c.call();
    }
  }

  public int getIterationId() {
    return 0;
  }

  @Override
  public int getThreadId() {
    return 0;
  }

  @Override
  public void arbitrate(Iteration current, Iteration conflicter) throws IterationAbortException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onCommit(Iteration it, Callback action) {
    suspendThunks.addFirst(action);
  }

  @Override
  public void onRelease(Iteration it, ReleaseCallback action) {
  }

  @Override
  public void onUndo(Iteration it, Callback action) {
  }

  @Override
  public boolean isSerial() {
    return true;
  }

  @Override
  public void suspend(Callback listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void suspendDone() {
    throw new UnsupportedOperationException();
  }
}
