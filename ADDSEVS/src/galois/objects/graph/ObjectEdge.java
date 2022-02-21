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

File: ObjectEdge.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import util.Pair;

public class ObjectEdge<N extends GObject, E> extends Pair<GNode<N>, GNode<N>> {
  private final E data;

  public ObjectEdge(GNode<N> src, GNode<N> dst, E data) {
    super(src, dst);
    this.data = data;
  }

  public final GNode<N> getSrc() {
    return first;
  }

  public final GNode<N> getDst() {
    return second;
  }

  public final E getData() {
    return data;
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ObjectEdge)) {
      return false;
    }
    ObjectEdge other = (ObjectEdge) obj;
    return super.equals(other) && data.equals(other.data);
  }

  @Override
  public int hashCode() {
    int ret = super.hashCode();
    return ret * 31 + data.hashCode();
  }
}
