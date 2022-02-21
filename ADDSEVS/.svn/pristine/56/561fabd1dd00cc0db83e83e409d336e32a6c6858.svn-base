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

File: GNode.java 

 */

package galois.objects.graph;

import galois.objects.AbstractReplayable;
import galois.objects.GObject;
import galois.runtime.Iteration;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A node in a graph.
 * 
 * @param <N> the type of the data stored in each node
 */
abstract class ConcurrentGNode<N extends GObject> extends AbstractReplayable implements GNode<N> {
  private final AtomicReference<Iteration> ownerRef = new AtomicReference<Iteration>();

  @Override
  public final AtomicReference<Iteration> getOwner() {
    return ownerRef;
  }

  @Override
  public void access(byte flags) {

  }
}
