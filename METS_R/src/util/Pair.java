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

File: Pair.java 

*/



package util;

/**
 * An ordered pair.
 * 
 * @param <A> the type of the first component
 * @param <B> the type of the second component
 */
public class Pair<A, B> {
  protected final A first;
  protected final B second;

  /**
   * Creates an ordered pair with the specified components
   * 
   * @param first  the first component
   * @param second the second component
   */
  public Pair(A first, B second) {
    this.first = first;
    this.second = second;
  }

  /**
   * @return  the first component of the pair
   */
  public A getFirst() {
    return first;
  }

  /**
   * @return  the second component of the pair
   */
  public B getSecond() {
    return second;
  }

  @Override
  public String toString() {
    return "(" + first + ", " + second + ")";
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Pair<?, ?>)) {
      return false;
    }
    Pair<?, ?> otherPair = (Pair<?, ?>) other;
    boolean ret = equals(first, otherPair.getFirst()) && equals(second, otherPair.getSecond());
    return ret;
  }

  /**
   * Compares two objects for equality taking care of null references
   * 
   * @param x  an object
   * @param y  an object
   * @return   returns true when <code>x</code> equals <code>y</code> or if
   *           both are null
   */
  protected final boolean equals(Object x, Object y) {
    return (x == null && y == null) || (x != null && x.equals(y));
  }

  @Override
  public int hashCode() {
    // hashcode (a,b) <> hashcode (b,a)
    if (first == null) {
      return (second == null) ? 0 : second.hashCode() + 1;
    } else if (second == null) {
      return first.hashCode() + 2;
    } else {
      return first.hashCode() * 17 + second.hashCode();
    }
  }
}
