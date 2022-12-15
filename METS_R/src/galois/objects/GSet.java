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

File: GSet.java 

 */

package galois.objects;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

/**
 * A set of elements.
 * 
 *
 * @param <E>  type of elements in set
 * @see Set
 */
public interface GSet<E extends Lockable> extends Set<E> {
  @Override
  public boolean add(E e);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #add(Lockable)
   */
  public boolean add(E e, byte flags);

  @Override
  public boolean addAll(Collection<? extends E> c);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #addAll(Collection)
   */
  public boolean addAll(Collection<? extends E> c, byte flags);

  @Override
  public void clear();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #clear()
   */
  public void clear(byte flags);

  @Override
  public boolean contains(Object o);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #contains(Object)
   */
  public boolean contains(Object o, byte flags);

  @Override
  public boolean containsAll(Collection<?> c);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #containsAll(Collection)
   */
  public boolean containsAll(Collection<?> c, byte flags);

  @Override
  public boolean equals(Object o);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #equals(Object)
   */
  public boolean equals(Object o, byte flags);

  @Override
  public int hashCode();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #hashCode()
   */
  public int hashCode(byte flags);

  @Override
  public boolean isEmpty();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #isEmpty()
   */
  public boolean isEmpty(byte flags);

  @Override
  public Iterator<E> iterator();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #iterator()
   */
  public Iterator<E> iterator(byte flags);

  @Override
  public boolean remove(Object o);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #remove(Object)
   */
  public boolean remove(Object o, byte flags);

  @Override
  public boolean removeAll(Collection<?> c);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #removeAll(Collection)
   */
  public boolean removeAll(Collection<?> c, byte flags);

  @Override
  public boolean retainAll(Collection<?> c);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #retainAll(Collection)
   */
  public boolean retainAll(Collection<?> c, byte flags);

  @Override
  public int size();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #size()
   */
  public int size(byte flags);

  @Override
  public Object[] toArray();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #toArray()
   */
  public Object[] toArray(byte flags);

  @Override
  public <T> T[] toArray(T[] a);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #toArray(Object[])
   */
  public <T> T[] toArray(T[] a, byte flags);
}
