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

import java.util.Comparator;

/**
 * Worklists that can be used for ordered Galois iterators
 * 
 * 
 * @param <T>  the type of elements of the worklist
 */
public interface OrderableWorklist<T> extends Worklist<T> {
  @Override
  public void add(T item, ForeachContext<T> ctx);

  @Override
  public T poll(ForeachContext<T> ctx);

  @Override
  public boolean isEmpty();

  @Override
  public int size();

  public Comparator<T> getComparator();

  public T peek();
}
