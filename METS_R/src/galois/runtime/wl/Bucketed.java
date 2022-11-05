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
import util.fn.Lambda;

/**
 * Order elements according to function mapping elements to integers. Elements are
 * ordered in ascending or descending integer order. Elements with equal integers
 * are unordered with respect to each other.
 * 
 *
 * @param <T>  the type of elements of the worklist
 */
@MatchingConcurrentVersion(ConcurrentBucketed.class)
@MatchingLeafVersion(BucketedLeaf.class)
public class Bucketed<T> implements Worklist<T> {
  private final Lambda<T, Integer> indexer;
  private Worklist<T>[] bucket;
  private int size;
  private int cursor;
  private final boolean ascending;

  /**
   * Creates an ascending order
   * 
   * @param numBuckets  domain of indexing function is [0, numBuckets - 1]
   * @param indexer     function mapping elements to integers [0, numBuckets - 1]
   */
  public Bucketed(int numBuckets, Lambda<T, Integer> indexer, Maker<T> maker, boolean needSize) {
    this(numBuckets, true, indexer, maker, needSize);
  }

  /**
   * Creates an ascending or descending order
   * 
   * @param numBuckets  domain of indexing function is [0, numBuckets - 1]
   * @param ascending   true if ascending order, otherwise descending
   * @param indexer     function mapping elements to integers [0, numBuckets - 1]
   */
  @SuppressWarnings("unchecked")
  public Bucketed(int numBuckets, boolean ascending, Lambda<T, Integer> indexer, Maker<T> maker, boolean needSize) {
    this(numBuckets, ascending, indexer, (Worklist<T>[]) null, needSize);
    bucket = new Worklist[numBuckets];
    for (int i = 0; i < numBuckets; i++) {
      bucket[i] = maker.make();
    }
  }

  private Bucketed(int numBuckets, boolean ascending, Lambda<T, Integer> indexer, Worklist<T>[] bucket, boolean needSize) {
    this.indexer = indexer;
    this.ascending = ascending;
    this.bucket = bucket;

    size = 0;
    if (ascending)
      cursor = 0;
    else
      cursor = numBuckets - 1;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Worklist<T> newInstance() {
    int numBuckets = this.bucket.length;
    Worklist<T>[] b = new Worklist[numBuckets];
    for (int i = 0; i < numBuckets; i++) {
      b[i] = bucket[i].newInstance();
    }
    return new Bucketed<T>(numBuckets, ascending, indexer, b, false);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    int index = indexer.call(item);

    size++;
    bucket[index].add(item, ctx);

    if (ascending) {
      if (cursor >= index)
        cursor = index;
    } else {
      if (cursor <= index)
        cursor = index;
    }
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    int cur;
    T retval = null;

    while ((cur = cursor) < bucket.length && cur >= 0) {
      retval = bucket[cur].poll(ctx);
      if (retval == null) {
        if (ascending)
          cursor++;
        else
          cursor--;
      } else {
        break;
      }
    }

    if (retval != null) {
      size--;
    }

    return retval;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public void finishAddInitial() {

  }
}
