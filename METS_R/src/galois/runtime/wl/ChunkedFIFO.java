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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Order elements in chunks of size <i>N</i>, full chunks are ordered in FIFO order. Partial
 * chunks are unordered with respect to each other. Elements are unordered
 * within a chunk and are eligible to be ordered by subsequent rules.
 * 
 *
 * @param <T>  the type of elements of the worklist
 * @see ChunkedLIFO
 * @see FIFO
 */
@NestedAreSerial
@MatchingConcurrentVersion(ConcurrentChunkedFIFO.class)
@MatchingLeafVersion(ChunkedFIFOLeaf.class)
public class ChunkedFIFO<T> implements Worklist<T> {
  public static final int DEFAULT_CHUNK_SIZE = 32;

  private final int chunkSize;
  private Worklist<T> current;
  private Worklist<T> next;
  private Deque<Worklist<T>> pool;
  private int size;

  /**
   * Creates a chunked FIFO order with the default chunk size ({@value #DEFAULT_CHUNK_SIZE})
   */
  public ChunkedFIFO(Maker<T> maker, boolean needSize) {
    this(DEFAULT_CHUNK_SIZE, maker, needSize);
  }

  /**
   * Creates a chunked FIFO order with the given chunk size
   * 
   * @param chunkSize        chunk size to use
   */
  public ChunkedFIFO(int chunkSize, Maker<T> maker, boolean needSize) {
    this(chunkSize, maker.make(), maker.make(), needSize);
  }

  private ChunkedFIFO(int chunkSize, Worklist<T> current, Worklist<T> next, boolean needSize) {
    this.chunkSize = chunkSize;
    this.current = current;
    this.next = next;

    pool = new ArrayDeque<Worklist<T>>();
    size = 0;
  }

  @Override
  public Worklist<T> newInstance() {
    return new ChunkedFIFO<T>(chunkSize, current.newInstance(), next.newInstance(), false);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    size++;
    next.add(item, ctx);

    if (next.size() >= chunkSize) {
      pool.add(next);
      next = next.newInstance();
    }
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public T poll(ForeachContext<T> ctx) {
    if (current == null)
      current = pool.poll();

    T retval = null;
    while (current != null) {
      retval = current.poll(ctx);

      if (retval == null) {
        current = pool.poll();
      } else {
        break;
      }
    }

    // Current and poll are empty, try our next queue
    if (current == null) {
      retval = next.poll(ctx);
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
