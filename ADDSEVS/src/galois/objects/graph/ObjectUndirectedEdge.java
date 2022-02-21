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

File: ObjectUndirectedEdge.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.objects.Lockable;
import galois.runtime.Features;
import galois.runtime.Iteration;
import galois.runtime.Replayable;
import util.UnorderedPair;

import java.util.concurrent.atomic.AtomicReference;

public class ObjectUndirectedEdge<N extends GObject, E> extends UnorderedPair<GNode<N>> implements Lockable, Replayable {
  private final E data;
  protected long rid;
  AtomicReference<Iteration> ownerRef = new AtomicReference<Iteration>();

  public ObjectUndirectedEdge(GNode<N> src, GNode<N> dst, E data) {
    super(src, dst);
    this.data = data;
    Features.getReplayFeature().onCreateReplayable(this);
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

  @Override
  public long getRid() {
    return rid;
  }

  @Override
  public void setRid(long rid) {
    this.rid = rid;
  }

  @Override
  public AtomicReference<Iteration> getOwner() {
    return ownerRef;
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ObjectUndirectedEdge)) {
      return false;
    }
    ObjectUndirectedEdge objectUndirectedEdge = (ObjectUndirectedEdge) obj;
    return super.equals(objectUndirectedEdge) && data.equals(objectUndirectedEdge.data);
  }

  @Override
  public int hashCode() {
    int ret = super.hashCode();
    return ret * 31 + data.hashCode();
  }
}
