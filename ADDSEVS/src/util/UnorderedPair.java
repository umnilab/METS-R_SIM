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

File: UnorderedPair.java 

*/



package util;

/**
 * Unordered pair type. (a, b) == (b, a)
 * 
 * @param <A> Type of both components 
 */
public class UnorderedPair<A> extends Pair<A, A> {
  /**
   * Creates an unordered pair with the specified components.
   * 
   * @param first  the first component
   * @param second the second component
   */
  public UnorderedPair(A first, A second) {
    super(first, second);
  }

  @Override
  public String toString() {
    return "{" + first + ", " + second + "}";
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof UnorderedPair<?>)) {
      return false;
    }
    UnorderedPair<?> otherPair = (UnorderedPair<?>) other;
    boolean ret = equals(first, otherPair.getFirst()) && equals(second, otherPair.getSecond());
    return ret || equals(first, otherPair.getSecond()) && equals(second, otherPair.getFirst());
  }

  @Override
  public int hashCode() {
    // if it is unordered, hashcode {a,b} = hashcode {b,a}
    if (first == null) {
      return (second == null) ? 0 : second.hashCode() + 1;
    } else if (second == null) {
      return first.hashCode() + 1;
    } else {
      return first.hashCode() ^ second.hashCode();
    }
  }
}
