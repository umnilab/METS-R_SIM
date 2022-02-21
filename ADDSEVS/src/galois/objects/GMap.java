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

File: GMap.java 

 */

package galois.objects;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * A map from keys to values. Methods that operate over the entire
 * map are not supported in parallel. See individual methods for
 * details.
 *
 * @param <K>  type of keys
 * @param <V>  type of values
 * @see Map
 */
public interface GMap<K extends Lockable, V extends GObject> extends Map<K, V> {

  /**
   * Not supported in parallel.
   */
  @Override
  public int size();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #size()
   */
  public int size(byte flags);

  /**
   * Not supported in parallel.
   */
  @Override
  public boolean isEmpty();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #size()
   */
  public boolean isEmpty(byte flags);

  @Override
  public boolean containsKey(Object arg0);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #containsKey(Object)
   */
  public boolean containsKey(Object arg0, byte flags);

  /**
   * Not supported in parallel.
   */
  @Override
  public boolean containsValue(Object arg0);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #containsValue(Object)
   */
  public boolean containsValue(Object arg0, byte flags);

  @Override
  public V get(Object arg0);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #get(Object)
   */
  public V get(Object arg0, byte flags);

  /**
   * @param keyFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                 upon invocation of this method on the key. See {@link galois.objects.MethodFlag}
   * @param valueFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                   upon invocation of this method on the value. See {@link galois.objects.MethodFlag}
   * @see #get(Object)
   */
  public V get(Object arg0, byte keyFlags, byte valueFlags);

  @Override
  public V put(K arg0, V arg1);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #put(Lockable, Object)
   */
  public V put(final K arg0, V arg1, byte flags);

  /**
   * @param keyFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                 upon invocation of this method on the key. See {@link galois.objects.MethodFlag}
   * @param valueFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                   upon invocation of this method on the return value. See {@link galois.objects.MethodFlag}
   * @see #put(Lockable, Object)
   */
  public V put(final K arg0, V arg1, byte keyFlags, byte valueFlags);

  @Override
  public V remove(Object arg0);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #remove(Object)
   */
  public V remove(Object arg0, byte flags);

  /**
   * @param keyFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                 upon invocation of this method on the key. See {@link galois.objects.MethodFlag}
   * @param valueFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                   upon invocation of this method on the return value. See {@link galois.objects.MethodFlag}
   * @see #remove(Object)
   */
  public V remove(Object arg0, byte keyFlags, byte valueFlags);

  @Override
  public void putAll(Map<? extends K, ? extends V> arg0);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #putAll(Map)
   */
  public void putAll(Map<? extends K, ? extends V> arg0, byte flags);

  /**
   * Not supported in parallel.
   */
  @Override
  public void clear();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #clear()
   */
  public void clear(byte flags);

  /**
   * Not supported in parallel.
   */
  @Override
  public Set<K> keySet();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #keySet()
   */
  public Set<K> keySet(byte flags);

  /**
   * Not supported in parallel.
   */
  @Override
  public Collection<V> values();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #values()
   */
  public Collection<V> values(byte flags);

  /**
   * Not supported in parallel.
   */
  @Override
  public Set<Map.Entry<K, V>> entrySet();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #entrySet()
   */
  public Set<Map.Entry<K, V>> entrySet(byte flags);

  /**
   * Not supported in parallel.
   */
  @Override
  public boolean equals(Object arg0);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #equals(Object)
   */
  public boolean equals(Object arg0, byte flags);

  /**
   * Not supported in parallel.
   */
  @Override
  public int hashCode();

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see #hashCode()
   */
  public int hashCode(byte flags);
}
