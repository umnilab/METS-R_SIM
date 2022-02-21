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

File: FnIterable.java 

*/



package util.fn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import util.Pair;

/**
 * Functional programming like sequences. This class provides map-like functionality.
 * 
 *
 * @param <T>  type of elements of the sequence
 */
public class FnIterable<T> implements Iterable<T> {
  private final Iterable<T> it;

  private FnIterable(Iterable<T> it) {
    this.it = it;
  }

  /**
   * Creates a new functional sequence from an {@link java.lang.Iterable} object
   * 
   * @param <T>  type of elements of the sequence
   * @param it   the iterable object
   * @return     a functional sequence view of the iterable object
   */
  public static <T> FnIterable<T> from(Iterable<T> it) {
    return new FnIterable<T>(it);
  }

  /**
   * Produces a sequence of pairs from two sequences.
   * <pre>
   *  {a1, a2, a3, ...}.zip({b1, b2, b3, ...}) ==> {(a1, b1), (a2, b2), (a3, b3), ...}
   * </pre>
   * 
   * @param <U> type of elements of the argument sequence
   * @param o   argument sequence to zip with
   * @return    sequence of pairs combining this and argument sequence
   */
  public final <U> FnIterable<Pair<T, U>> zip(final FnIterable<U> o) {
    return new FnIterable<Pair<T, U>>(new Iterable<Pair<T, U>>() {
      @Override
      public Iterator<Pair<T, U>> iterator() {
        final Iterator<T> inner1 = it.iterator();
        final Iterator<U> inner2 = o.iterator();
        return new Iterator<Pair<T, U>>() {
          @Override
          public boolean hasNext() {
            return inner1.hasNext() || inner2.hasNext();
          }

          @Override
          public Pair<T, U> next() {
            T o1 = inner1.hasNext() ? inner1.next() : null;
            U o2 = inner2.hasNext() ? inner2.next() : null;
            return new Pair<T, U>(o1, o2);
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }
        };
      }
    });
  }

  /**
   * Maps function to sequence
   * <pre>
   *  {a1, a2, a3, ...}.map(fn) ==> {fn(a1), fn(a2), fn(a3), ...}
   * </pre>
   * 
   * @param <U>  type of elements in result sequence
   * @param fn   function from elements in this sequence to elements of result sequence
   * @return     a sequence of the return values of the function
   */
  public final <U> FnIterable<U> map(final Lambda<T, U> fn) {
    return new FnIterable<U>(new Iterable<U>() {
      @Override
      public Iterator<U> iterator() {
        final Iterator<T> inner = it.iterator();
        return new Iterator<U>() {
          @Override
          public boolean hasNext() {
            return inner.hasNext();
          }

          @Override
          public U next() {
            return fn.call(inner.next());
          }

          @Override
          public void remove() {
            throw new UnsupportedOperationException();
          }
        };
      }
    });
  }

  /**
   * Reduces this sequence to a value.
   * <pre>
   *  {}.reduce(fn, initial)  ==> initial
   *  {a1, a2, a3, ...}.reduce(fn, initial) ==> fn(... fn(fn(fn(initial, a1), a2), a3) ...)
   * </pre>
   * 
   * @param <U>      type of the resulting value
   * @param fn       function to reduce sequence
   * @param initial  initial value to pass reducing function
   * @return         resulting value from applying function over sequence
   */
  public final <U> U reduce(Lambda2<U, T, U> fn, U initial) {
    for (T item : this) {
      initial = fn.call(initial, item);
    }
    return initial;
  }

  /**
   * @return  a copy of this sequence as a list
   */
  public final List<T> toList() {
    List<T> retval = new ArrayList<T>();
    for (T item : this) {
      retval.add(item);
    }
    return retval;
  }

  @Override
  public Iterator<T> iterator() {
    return it.iterator();
  }
}
