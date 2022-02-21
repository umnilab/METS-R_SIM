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

/**
 * Builds {@link GSet}s.
 * 
 * @see GSet
 */
public class GSetBuilder<E extends Lockable> {
  private final GMapBuilder<E, GBoolean> mapBuilder = new GMapBuilder<E, GBoolean>();
  private static GBoolean True = new GBoolean();

  /**
   * Creates a new set.
   * 
   * @param <E>
   *          type of elements in set
   * @return a new set
   */
  public GSet<E> create() {
    return new GMapToGSetAdaptor<E>(mapBuilder.create());
  }

  private static class GBoolean implements GObject {
    @Override
    public void access(byte flags) {
    }
  }

  private static class GMapToGSetAdaptor<E extends Lockable> implements GSet<E> {
    private GMap<E, GBoolean> map;

    public GMapToGSetAdaptor(GMap<E, GBoolean> map) {
      this.map = map;
    }

    @Override
    public final boolean add(E e) {
      return add(e, MethodFlag.ALL);
    }

    @Override
    public boolean add(E e, byte flags) {
      GBoolean retval = map.put(e, True, flags);
      if (retval != null && retval == True)
        return false;
      return true;
    }

    @Override
    public final boolean addAll(Collection<? extends E> c) {
      return addAll(c, MethodFlag.ALL);
    }

    @Override
    public boolean addAll(Collection<? extends E> c, byte flags) {
      boolean retval = false;
      for (E e : c) {
        retval |= add(e, flags);
      }
      return retval;
    }

    @Override
    public final void clear() {
      clear(MethodFlag.ALL);
    }

    @Override
    public void clear(byte flags) {
      map.clear(flags);
    }

    @Override
    public final boolean contains(Object o) {
      return contains(o, MethodFlag.ALL);
    }

    @Override
    public boolean contains(Object o, byte flags) {
      return map.containsKey(o, flags);
    }

    @Override
    public final boolean containsAll(Collection<?> c) {
      return containsAll(c, MethodFlag.ALL);
    }

    @Override
    public boolean containsAll(Collection<?> c, byte flags) {
      for (Object o : c) {
        if (!contains(o, flags))
          return false;
      }
      return true;
    }

    @Override
    public final boolean equals(Object o) {
      return equals(o, MethodFlag.ALL);
    }

    @Override
    public boolean equals(Object o, byte flags) {
      return map.equals(o, flags);
    }

    @Override
    public final int hashCode() {
      return hashCode(MethodFlag.ALL);
    }

    @Override
    public int hashCode(byte flags) {
      return map.hashCode(flags);
    }

    @Override
    public final boolean isEmpty() {
      return isEmpty(MethodFlag.ALL);
    }

    @Override
    public boolean isEmpty(byte flags) {
      return map.isEmpty(flags);
    }

    @Override
    public final Iterator<E> iterator() {
      return iterator(MethodFlag.ALL);
    }

    @Override
    public Iterator<E> iterator(byte flags) {
      return map.keySet(flags).iterator();
    }

    @Override
    public final boolean remove(Object o) {
      return remove(o, MethodFlag.ALL);
    }

    @Override
    public boolean remove(Object o, byte flags) {
      return map.remove(o, flags) == True;
    }

    @Override
    public final boolean removeAll(Collection<?> c) {
      return removeAll(c, MethodFlag.ALL);
    }

    @Override
    public boolean removeAll(Collection<?> c, byte flags) {
      boolean retval = false;
      for (Object o : c) {
        retval |= map.remove(o, flags) == True;
      }
      return retval;
    }

    @Override
    public final boolean retainAll(Collection<?> c) {
      return retainAll(c, MethodFlag.ALL);
    }

    @Override
    public boolean retainAll(Collection<?> c, byte flags) {
      return map.keySet(flags).retainAll(c);
    }

    @Override
    public final int size() {
      return size(MethodFlag.ALL);
    }

    @Override
    public int size(byte flags) {
      return map.size(flags);
    }

    @Override
    public final Object[] toArray() {
      return toArray(MethodFlag.ALL);
    }

    @Override
    public Object[] toArray(byte flags) {
      return map.keySet(flags).toArray();
    }

    @Override
    public final <T> T[] toArray(T[] a) {
      return toArray(a, MethodFlag.ALL);
    }

    @Override
    public <T> T[] toArray(T[] a, byte flags) {
      return map.keySet(flags).toArray(a);
    }
  }
}
