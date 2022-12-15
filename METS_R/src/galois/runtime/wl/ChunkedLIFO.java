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

/**
 * Order elements in chunks of size <i>N</i>, full chunks are ordered in LIFO order. Elements are unordered
 * within a chunk and are eligible to be ordered by subsequent rules.
 * 
 *
 * @param <T>  the type of elements of the worklist
 * @see ChunkedLIFO
 * @see FIFO
 */
@NestedAreSerial
@MatchingConcurrentVersion(ConcurrentChunkedLIFO.class)
@MatchingLeafVersion(ChunkedLIFOLeaf.class)
public class ChunkedLIFO<T> implements Worklist<T> {
  public static final int DEFAULT_CHUNK_SIZE = 32;

  private final int chunkSize;
  private Worklist<T> current;
  private Node<T> head;
  private int size;

  /**
   * Creates a chunked LIFO order with the default chunk size ({@value #DEFAULT_CHUNK_SIZE})
   */
  public ChunkedLIFO(Maker<T> maker, boolean needSize) {
    this(DEFAULT_CHUNK_SIZE, maker, needSize);
  }

  /**
   * Creates a chunked LIFO order with the given chunk size
   * 
   * @param chunkSize        chunk size to use
   */
  public ChunkedLIFO(int chunkSize, Maker<T> maker, boolean needSize) {
    this(chunkSize, maker.make(), needSize);
  }

  private ChunkedLIFO(int chunkSize, Worklist<T> current, boolean needSize) {
    this.chunkSize = chunkSize;
    this.current = current;
    this.head = null;
  }

  @Override
  public Worklist<T> newInstance() {
    return new ChunkedLIFO<T>(chunkSize, current.newInstance(), false);
  }

  @Override
  public void add(T item, ForeachContext<T> ctx) {
    size++;

    current.add(item, ctx);

    if (current.size() >= chunkSize) {
      addInternal(current);
      current = current.newInstance();
    }
  }

  @Override
  public void addInitial(T item, ForeachContext<T> ctx) {
    add(item, ctx);
  }

  @Override
  public T poll(final ForeachContext<T> ctx) {
    if (current == null)
      current = pollInternal();

    T retval = null;
    while (current != null) {
      retval = current.poll(ctx);

      if (retval == null) {
        current = pollInternal();
      } else {
        break;
      }
    }

    if (retval != null)
      size++;

    return retval;
  }

  private void addInternal(Worklist<T> wl) {
    Node<T> next = new Node<T>(wl);
    next.next = head;
    head = next;
  }

  private Worklist<T> pollInternal() {
    Node<T> cur = head;

    if (cur == null)
      return null;
    Node<T> next = cur.next;
    head = next;

    return cur.wl;
  }

  @Override
  public boolean isEmpty() {
    return head == null;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public void finishAddInitial() {

  }

  private static class Node<T> {
    private Node<T> next;
    private Worklist<T> wl;

    public Node(Worklist<T> wl) {
      this.wl = wl;
    }
  }
}
