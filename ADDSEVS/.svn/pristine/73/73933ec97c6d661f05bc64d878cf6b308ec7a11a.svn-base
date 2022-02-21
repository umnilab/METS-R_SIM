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

File: Bag.java 

 */

package galois.objects;

import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds {@link Bag}s.
 *
 * @param <T>  type of elements contained in bag
 */
public class BagBuilder<T> {

  /**
   * Creates a new bag of elements.
   *
   * @param <T> type of elements contained in bag
   * @return a new bag
   */
  public Bag<T> create() {
    if (GaloisRuntime.getRuntime().useSerial()) {
      return new SerialBag<T>();
    } else {
      return new ConcurrentBag<T>();
    }
  }

  private static class SerialBag<T> implements Bag<T> {
    private Node<T> head;
    int size;

    public SerialBag() {
      head = null;
      size = 0;
    }

    @Override
    public boolean add(T o, byte flags) {
      Node<T> cur = head;
      head = new Node<T>(o, cur);
      size++;
      return true;
    }

    @Override
    public boolean addAll(Collection<? extends T> c, byte flags) {
      for (T o : c) {
        add(o, flags);
      }
      return true;
    }

    @Override
    public void clear(byte flags) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(Object o, byte flags) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> c, byte flags) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty(byte flags) {
      return size == 0;
    }

    @Override
    public Iterator<T> iterator(byte flags) {
      return new Iterator<T>() {
        Node<T> cur = head;

        @Override
        public boolean hasNext() {
          for (; cur != null; cur = cur.next) {
            if (cur.absent) {
              continue;
            }
            return true;
          }
          return false;
        }

        @Override
        public T next() {
          T retval = cur.obj;
          cur = cur.next;
          return retval;
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override
    public boolean remove(Object o, byte flags) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c, byte flags) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> c, byte flags) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int size(byte flags) {
      return size;
    }

    @Override
    public Object[] toArray(byte flags) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <U> U[] toArray(U[] a, byte flags) {
      throw new UnsupportedOperationException();
    }

    private static class Node<T> {
      private boolean absent;
      private final Node<T> next;
      private final T obj;

      public Node(T obj, Node<T> next) {
        this.obj = obj;
        this.next = next;
      }
    }

    @Override
    public final boolean add(T o) {
      return add(o, MethodFlag.ALL);
    }

    @Override
    public final boolean addAll(Collection<? extends T> c) {
      return addAll(c, MethodFlag.ALL);
    }

    @Override
    public final void clear() {
      clear(MethodFlag.ALL);
    }

    @Override
    public final boolean contains(Object o) {
      return contains(o, MethodFlag.ALL);
    }

    @Override
    public final boolean containsAll(Collection<?> c) {
      return containsAll(c, MethodFlag.ALL);
    }

    @Override
    public final boolean isEmpty() {
      return isEmpty(MethodFlag.ALL);
    }

    @Override
    public final Iterator<T> iterator() {
      return iterator(MethodFlag.ALL);
    }

    @Override
    public final boolean remove(Object o) {
      return remove(o, MethodFlag.ALL);
    }

    @Override
    public final boolean removeAll(Collection<?> c) {
      return removeAll(c, MethodFlag.ALL);
    }

    @Override
    public final boolean retainAll(Collection<?> c) {
      return retainAll(c, MethodFlag.ALL);
    }

    @Override
    public final int size() {
      return size(MethodFlag.ALL);
    }

    @Override
    public final Object[] toArray() {
      return toArray(MethodFlag.ALL);
    }

    @Override
    public final <U> U[] toArray(U[] a) {
      return toArray(a, MethodFlag.ALL);
    }

    @Override
    public void access(byte flags) {
    }
  }

  private static class ConcurrentBag<T> implements Bag<T> {
    private final AtomicReference<Node<T>> head;
    private final GaloisRuntime runtime;

    private ConcurrentBag() {
      head = new AtomicReference<Node<T>>();
      runtime = GaloisRuntime.getRuntime();
    }

    @Override
    public final boolean add(T o) {
      return add(o, MethodFlag.ALL);
    }

    /**
     * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
     *              upon invocation of this method. See {@link galois.objects.MethodFlag}
     * @see #add(Object)
     */
    public boolean add(T o, byte flags) {
      Node<T> cur;
      Node<T> node;
      do {
        cur = head.get();
        node = new Node<T>(o, cur);
      } while (!head.compareAndSet(cur, node));

      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.SAVE_UNDO)) {
        final Node<T> n = node;
        runtime.onUndo(Iteration.getCurrentIteration(), new Callback() {
          @Override
          public void call() {
            n.absent = true;
          }
        });
      }
      return true;
    }

    @Override
    public final boolean addAll(Collection<? extends T> c) {
      return addAll(c, MethodFlag.ALL);
    }

    /**
     * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
     *              upon invocation of this method. See {@link galois.objects.MethodFlag}
     * @see #addAll(Collection)
     */
    public boolean addAll(Collection<? extends T> c, byte flags) {
      for (T o : c) {
        add(o, flags);
      }
      return true;
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final void clear() {
      clear(MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public void clear(byte flags) {
      throw new UnsupportedOperationException();
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final boolean contains(Object o) {
      return contains(o, MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public boolean contains(Object o, byte flags) {
      throw new UnsupportedOperationException();
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final boolean containsAll(Collection<?> c) {
      return containsAll(c, MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public boolean containsAll(Collection<?> c, byte flags) {
      throw new UnsupportedOperationException();
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final boolean isEmpty() {
      return isEmpty(MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public boolean isEmpty(byte flags) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}. Only valid outside parallel execution.
     */
    @Override
    public final Iterator<T> iterator() {
      return iterator(MethodFlag.ALL);
    }

    /**
     * Iterates over all the elements in the bag. Only valid outside
     * parallel execution.
     *
     * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
     *              upon invocation of this method. See {@link galois.objects.MethodFlag}
     * @see #iterator()
     */
    public Iterator<T> iterator(byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("No global actions during parallel execution");
      }

      return new Iterator<T>() {
        Node<T> cur = head.get();

        @Override
        public boolean hasNext() {
          for (; cur != null; cur = cur.next) {
            if (cur.absent) {
              continue;
            }
            return true;
          }
          return false;
        }

        @Override
        public T next() {
          T retval = cur.obj;
          cur = cur.next;
          return retval;
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final boolean remove(Object o) {
      return remove(o, MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public boolean remove(Object o, byte flags) {
      throw new UnsupportedOperationException();
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final boolean removeAll(Collection<?> c) {
      return removeAll(c, MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public boolean removeAll(Collection<?> c, byte flags) {
      throw new UnsupportedOperationException();
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final boolean retainAll(Collection<?> c) {
      return retainAll(c, MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public boolean retainAll(Collection<?> c, byte flags) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}. Only valid outside parallel execution.
     */
    @Override
    public final int size() {
      return size(MethodFlag.ALL);
    }

    /**
     * Number of elements in the bag. Only valid outside parallel execution.
     *
     * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
     *              upon invocation of this method. See {@link galois.objects.MethodFlag}
     * @return size of the bag
     */
    public int size(byte flags) {
      Iterator<T> iterator = iterator(flags);
      int ret = 0;
      while (iterator.hasNext()) {
        ret++;
        iterator.next();
      }
      return ret;
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final Object[] toArray() {
      return toArray(MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public Object[] toArray(byte flags) {
      throw new UnsupportedOperationException();
    }

    /**
     * Not supported by this class.
     */
    @Override
    public final <U> U[] toArray(U[] a) {
      return toArray(a, MethodFlag.ALL);
    }

    /**
     * Not supported by this class.
     */
    public <U> U[] toArray(U[] a, byte flags) {
      throw new UnsupportedOperationException();
    }

    private static class Node<T> {
      private boolean absent;
      private final Node<T> next;
      private final T obj;

      public Node(T obj, Node<T> next) {
        this.obj = obj;
        this.next = next;
      }
    }

    @Override
    public void access(byte flags) {
    }
  }
}
