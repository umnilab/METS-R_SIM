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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Worklist for use with unordered iterators in ParaMeter. 
 */
public class ParameterUnorderedWorklist<T> extends AbstractParameterWorklist<T, T> {
  public ParameterUnorderedWorklist() {
  }

  @Override
  protected InternalParameterQueue<T> makeInternalQueue() {
    return new RandomList<T>();
  }

  @Override
  protected T wrap(T x) {
    return x;
  }

  private static class RandomList<U> implements InternalParameterQueue<U> {
    private List<U> backingList;
    private Random r;

    private RandomList() {
      backingList = new ArrayList<U>();
      r = new Random();
    }

    @Override
    public void add(U o) {
      backingList.add(o);
    }

    @Override
    public U poll() {
      int size = backingList.size();
      int x = r.nextInt(size);

      U retval = backingList.get(x);
      U lastElement = backingList.get(size - 1);
      backingList.set(x, lastElement);
      backingList.remove(size - 1);

      return retval;
    }

    @Override
    public boolean isEmpty() {
      return backingList.isEmpty();
    }

    @Override
    public int size() {
      return backingList.size();
    }

    @Override
    public RandomList<U> copy() {
      RandomList<U> retval = new RandomList<U>();
      retval.backingList = new ArrayList<U>(backingList);
      return retval;
    }

    @Override
    public void clear() {
      backingList.clear();
    }
  }
}
