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

import java.util.Collection;
import java.util.Iterator;

/**
 * A bag of elements.
 *
 * @param <T>  type of elements contained in bag
 */
public interface Bag<T> extends Collection<T>, GObject {
  @Override
  public boolean add(T o);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #add(Object)
   */
  public boolean add(T o, byte flags);

  @Override
  public boolean addAll(Collection<? extends T> c);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #addAll(Collection)
   */
  public boolean addAll(Collection<? extends T> c, byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public void clear();

  /**
   * Not supported by this class.
   */
  public void clear(byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public boolean contains(Object o);

  /**
   * Not supported by this class.
   */
  public boolean contains(Object o, byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public boolean containsAll(Collection<?> c);

  /**
   * Not supported by this class.
   */
  public boolean containsAll(Collection<?> c, byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public boolean isEmpty();

  /**
   * Not supported by this class.
   */
  public boolean isEmpty(byte flags);

  /**
   * {@inheritDoc}. Only valid outside parallel execution.
   */
  @Override
  public Iterator<T> iterator();

  /**
   * Iterates over all the elements in the bag. Only valid outside
   * parallel execution.
   *
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #iterator()
   */
  public Iterator<T> iterator(byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public boolean remove(Object o);

  /**
   * Not supported by this class.
   */
  public boolean remove(Object o, byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public boolean removeAll(Collection<?> c);

  /**
   * Not supported by this class.
   */
  public boolean removeAll(Collection<?> c, byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public boolean retainAll(Collection<?> c);

  /**
   * Not supported by this class.
   */
  public boolean retainAll(Collection<?> c, byte flags);

  /**
   * {@inheritDoc}. Only valid outside parallel execution.
   */
  @Override
  public int size();

  /**
   * Number of elements in the bag. Only valid outside parallel execution.
   *
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return size of the bag
   */
  public int size(byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public Object[] toArray();

  /**
   * Not supported by this class.
   */
  public Object[] toArray(byte flags);

  /**
   * Not supported by this class.
   */
  @Override
  public <U> U[] toArray(U[] a);

  /**
   * Not supported by this class.
   */
  public <U> U[] toArray(U[] a, byte flags);
}
