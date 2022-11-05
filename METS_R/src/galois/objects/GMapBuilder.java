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

import galois.runtime.Callback;
import galois.runtime.GaloisRuntime;
import galois.runtime.Iteration;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds {@link GMap}s.

 * @see GMap
 */
public class GMapBuilder<K extends Lockable, V extends GObject> {
  /**
   * Creates a new map.
   * 
   * @param <K>
   *          type of keys
   * @param <V>
   *          type of values
   * @return a new map
   */
  public GMap<K, V> create() {
    if (GaloisRuntime.getRuntime().useSerial()) {
      return new SerialGMap<K, V>();
    } else {
      return new ConcurrentGMap<K, V>();
    }
  }

  private static class SerialGMap<K extends Lockable, V extends GObject> implements GMap<K, V> {
    private Map<K, V> map = new HashMap<K, V>();

    public SerialGMap() {
    }

    @Override
    public int size(byte flags) {
      return map.size();
    }

    @Override
    public boolean isEmpty(byte flags) {
      return map.isEmpty();
    }

    @Override
    public boolean containsKey(Object arg0, byte flags) {
      return map.containsKey(arg0);
    }

    @Override
    public boolean containsValue(Object arg0, byte flags) {
      return map.containsValue(arg0);
    }

    @Override
    public V get(Object arg0, byte flags) {
      return get(arg0, flags, flags);
    }

    @Override
    public V get(Object arg0, byte keyFlags, byte valueFlags) {
      return map.get(arg0);
    }

    @Override
    public V put(final K arg0, V arg1, byte flags) {
      return put(arg0, arg1, flags, flags);
    }

    @Override
    public V put(K arg0, V arg1, byte keyFlags, byte valueFlags) {
      final V retval = map.put(arg0, arg1);
      return retval;
    }

    @Override
    public V remove(final Object arg0, byte flags) {
      return remove(arg0, flags, flags);
    }

    @Override
    public V remove(Object arg0, byte keyFlags, byte valueFlags) {
      final V retval = map.remove(arg0);
      return retval;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> arg0, byte flags) {
      map.putAll(arg0);
    }

    @Override
    public void clear(byte flags) {
      map.clear();
    }

    @Override
    public Set<K> keySet(byte flags) {
      return Collections.unmodifiableSet(map.keySet());
    }

    @Override
    public Collection<V> values(byte flags) {
      return Collections.unmodifiableCollection(map.values());
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet(byte flags) {
      return Collections.unmodifiableSet(map.entrySet());
    }

    @Override
    public boolean equals(Object arg0, byte flags) {
      return map.equals(arg0);
    }

    @Override
    public int hashCode(byte flags) {
      return map.hashCode();
    }

    @Override
    public final boolean isEmpty() {
      return isEmpty(MethodFlag.ALL);
    }

    @Override
    public final boolean containsKey(Object arg0) {
      return containsKey(arg0, MethodFlag.ALL);
    }

    @Override
    public final boolean containsValue(Object arg0) {
      return containsValue(arg0, MethodFlag.ALL);
    }

    @Override
    public final V get(Object arg0) {
      return get(arg0, MethodFlag.ALL);
    }

    @Override
    public final V put(K arg0, V arg1) {
      return put(arg0, arg1, MethodFlag.ALL);
    }

    @Override
    public final V remove(Object arg0) {
      return remove(arg0, MethodFlag.ALL);
    }

    @Override
    public final void putAll(Map<? extends K, ? extends V> arg0) {
      putAll(arg0, MethodFlag.ALL);
    }

    @Override
    public final void clear() {
      clear(MethodFlag.ALL);
    }

    @Override
    public final Set<K> keySet() {
      return keySet(MethodFlag.ALL);
    }

    @Override
    public final Collection<V> values() {
      return values(MethodFlag.ALL);
    }

    @Override
    public final Set<Map.Entry<K, V>> entrySet() {
      return entrySet(MethodFlag.ALL);
    }

    @Override
    public final boolean equals(Object arg0) {
      return equals(MethodFlag.ALL);
    }

    @Override
    public final int hashCode() {
      return hashCode(MethodFlag.ALL);
    }

    @Override
    public final int size() {
      return size(MethodFlag.ALL);
    }
  }

  private static class ConcurrentGMap<K extends Lockable, V extends GObject> implements GMap<K, V> {
    private Map<K, V> map;

    public ConcurrentGMap() {
      map = new ConcurrentHashMap<K, V>();
    }

    @Override
    public final int size() {
      return size(MethodFlag.ALL);
    }

    @Override
    public int size(byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("no globals");
      }
      return map.size();
    }

    @Override
    public final boolean isEmpty() {
      return isEmpty(MethodFlag.ALL);
    }

    @Override
    public boolean isEmpty(byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("no globals");
      }
      return map.isEmpty();
    }

    @Override
    public final boolean containsKey(Object arg0) {
      return containsKey(arg0, MethodFlag.ALL);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean containsKey(Object arg0, byte flags) {
      Iteration.acquire((K) arg0, flags);
      return map.containsKey(arg0);
    }

    @Override
    public final boolean containsValue(Object arg0) {
      return containsValue(arg0, MethodFlag.ALL);
    }

    @Override
    public boolean containsValue(Object arg0, byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("no globals");
      }
      return map.containsValue(arg0);
    }

    @Override
    public final V get(Object arg0) {
      return get(arg0, MethodFlag.ALL);
    }

    @Override
    public V get(Object arg0, byte flags) {
      return get(arg0, flags, flags);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(Object arg0, byte keyFlags, byte valueFlags) {
      Iteration.acquire((K) arg0, keyFlags);
      V retval = map.get(arg0);
      if (retval != null) {
        retval.access(valueFlags);
      }
      return retval;
    }

    @Override
    public final V put(K arg0, V arg1) {
      return put(arg0, arg1, MethodFlag.ALL);
    }

    @Override
    public V put(K arg0, V arg1, byte flags) {
      return put(arg0, arg1, flags, flags);
    }

    @Override
    public V put(final K arg0, V arg1, byte keyFlags, byte valueFlags) {
      Iteration it = Iteration.acquire(arg0, keyFlags);
      final V retval = map.put(arg0, arg1);
      final boolean hadEntry = retval != null;
      if (GaloisRuntime.needMethodFlag(keyFlags, MethodFlag.SAVE_UNDO)) {
        if (it == null) {
          it = Iteration.getCurrentIteration();
        }
        GaloisRuntime.getRuntime().onUndo(it, new Callback() {
          @Override
          public void call() {
            if (hadEntry)
              map.put(arg0, retval);
            else
              map.remove(arg0);
          }
        });
      }

      if (retval != null) {
        retval.access(valueFlags);
      }

      return retval;
    }

    @Override
    public final V remove(Object arg0) {
      return remove(arg0, MethodFlag.ALL);
    }

    @Override
    public V remove(final Object arg0, byte flags) {
      return remove(arg0, flags, flags);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(final Object arg0, byte keyFlags, byte valueFlags) {
      Iteration it = Iteration.acquire((K) arg0, keyFlags);
      final V retval = map.remove(arg0);
      final boolean hadEntry = retval != null;
      if (hadEntry && GaloisRuntime.needMethodFlag(keyFlags, MethodFlag.SAVE_UNDO)) {
        if (it == null) {
          it = Iteration.getCurrentIteration();
        }
        GaloisRuntime.getRuntime().onUndo(it, new Callback() {
          @Override
          public void call() {
            map.put((K) arg0, retval);
          }
        });
      }

      if (retval != null)
        retval.access(valueFlags);

      return retval;
    }

    @Override
    public final void putAll(Map<? extends K, ? extends V> arg0) {
      putAll(arg0, MethodFlag.ALL);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> arg0, byte flags) {
      for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
        put(entry.getKey(), entry.getValue());
      }
    }

    @Override
    public final void clear() {
      clear(MethodFlag.ALL);
    }

    @Override
    public void clear(byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, (byte) (MethodFlag.CHECK_CONFLICT | MethodFlag.SAVE_UNDO))) {
        throw new Error("no globals");
      }
      map.clear();
    }

    @Override
    public final Set<K> keySet() {
      return keySet(MethodFlag.ALL);
    }

    @Override
    public Set<K> keySet(byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("no globals");
      }
      return Collections.unmodifiableSet(map.keySet());
    }

    @Override
    public final Collection<V> values() {
      return values(MethodFlag.ALL);
    }

    @Override
    public Collection<V> values(byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("no globals");
      }
      return Collections.unmodifiableCollection(map.values());
    }

    @Override
    public final Set<Map.Entry<K, V>> entrySet() {
      return entrySet(MethodFlag.ALL);
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet(byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("no globals");
      }
      return Collections.unmodifiableSet(map.entrySet());
    }

    @Override
    public final boolean equals(Object arg0) {
      return equals(MethodFlag.ALL);
    }

    @Override
    public boolean equals(Object arg0, byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("no globals");
      }
      return map.equals(arg0);
    }

    @Override
    public final int hashCode() {
      return hashCode(MethodFlag.ALL);
    }

    @Override
    public int hashCode(byte flags) {
      if (GaloisRuntime.needMethodFlag(flags, MethodFlag.CHECK_CONFLICT)) {
        throw new Error("no globals");
      }
      return map.hashCode();
    }
  }
}
