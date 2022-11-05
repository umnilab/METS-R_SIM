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

File: Accumulator.java 

 */

package galois.objects;

/**
 * An accumulator which allows concurrent updates but not concurrent reads.
 * 
 * @see GMutableInteger
 * @see util.MutableInteger
 */
public interface Accumulator extends GObject {
  /**
   * Adds the given value to the accumulator.
   * 
   * @param  delta  value to add to accumulator
   */
  public void add(int delta);

  /**
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @see add(int)
   */
  public void add(final int delta, byte flags);

  /**
   * Returns the accumulated value. Only valid non-concurrently.
   * 
   * @return  the accumulated value
   */
  public int get();

  /**
   * Sets the accumulated value. Only valid non-concurrently.
   * 
   * @param  v  the value to set
   */
  public void set(int v);
}
