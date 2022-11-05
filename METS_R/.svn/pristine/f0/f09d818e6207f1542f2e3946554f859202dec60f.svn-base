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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import util.fn.Lambda;

@MatchingConcurrentVersion(ConcurrentBucketed.class)
@MatchingLeafVersion(ConcurrentBucketedLeaf.class)
class ConcurrentBucketed<T> implements Worklist<T> {
  private static final int CACHE_MULTIPLE = 16;

  private final Lambda<T, Integer> indexer;
  private Worklist<T>[] bucket;
  private final boolean ascending;
  private final int[] cursor;
  private AtomicInteger size;

  public ConcurrentBucketed(int numBuckets, Lambda<T, Integer> indexer, Maker<T> maker, boolean needSize) {
    this(numBuckets, true, indexer, maker, needSize);
  }

  @SuppressWarnings("unchecked")
  public ConcurrentBucketed(int numBuckets, boolean ascending, Lambda<T, Integer> indexer, Maker<T> maker,
      boolean needSize) {
    this(numBuckets, ascending, indexer, (Worklist<T>[]) null, needSize);

    bucket = new Worklist[numBuckets];
    for (int i = 0; i < numBuckets; i++) {
      bucket[i] = maker.make();
    }
  }

  private ConcurrentBucketed(int numBuckets, boolean ascending, Lambda<T, Integer> indexer, Worklist<T>[] bucket,
      boolean needSize) {
    this.indexer = indexer;
    this.ascending = ascending;
    this.bucket = bucket;

    int numThreads = GaloisRuntime.getRuntime().getMaxThreads();
    cursor = new int[numThreads * CACHE_MULTIPLE]; // Make cache-friendly

    if (!ascending)
      Arrays.fill(cursor, numBuckets - 1);

    if (needSize)
      size = new AtomicInteger();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Worklist<T> newInstance() {
    int numBuckets = bucket.length;
    Worklist<T>[] b = new Worklist[numBuckets];
    for (int i = 0; i < numBuckets; i++) {
      b[i] = bucket[i].newInstance();
    }
    return new ConcurrentBucketed<T>(numBuckets, ascending, indexer, b, size != null);
  }

  private int getIndex(int tid) {
    return tid * CACHE_MULTIPLE;
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();
    int index = indexer.call(item);

    if (size != null)
      size.incrementAndGet();

    bucket[index].add(item, ctx);

    if (ascending) {
      if (index < cursor[getIndex(tid)])
        cursor[getIndex(tid)] = index;
    } else {
      if (index > cursor[getIndex(tid)])
        cursor[getIndex(tid)] = index;
    }
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    int tid = ctx.getThreadId();
    int cur = cursor[getIndex(tid)];
    T retval = null;

    while (cur < bucket.length && cur >= 0) {
      retval = bucket[cur].poll(ctx);
      if (retval == null) {
        if (ascending) {
          cur++;
        } else {
          cur--;
        }
      } else {
        break;
      }
    }

    if (retval != null) {
      if (size != null)
        size.decrementAndGet();

      if (cursor[getIndex(tid)] != cur) {
        cursor[getIndex(tid)] = cur;
      }
    } else {
      if (ascending)
        cursor[getIndex(tid)] = 0;
      else
        cursor[getIndex(tid)] = bucket.length - 1;
    }

    return retval;
  }

  @Override
  public int size() {
    if (size != null)
      return size.get();
    else
      throw new UnsupportedOperationException();
  }

  @Override
  public boolean isEmpty() {
    if (size != null) {
      return size.get() == 0;
    } else {
      for (int i = 0; i < bucket.length; i++) {
        if (!bucket[i].isEmpty())
          return false;
      }
      return true;
    }
  }

  @Override
  public void finishAddInitial() {

  }
}
