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

File: IndexedGraph.java 

 */

package galois.objects.graph;

import galois.objects.GObject;

/**
 * This interface represents a graph that allows programmers to refer to a
 * node's edges by a particular index. An indexed graph is always directed, and
 * does not contain any information on the edges.
 *
 * @param <N> type of the data contained in a node
 */
public interface IndexedGraph<N extends GObject> extends Graph<N> {

  /**
   * Set a particular neighbor of a given node.
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src   the node whose neighbor to set
   * @param dst  the new neighbor
   * @param index the particular neighbor to set
   */
  public void setNeighbor(GNode<N> src, GNode<N> dst, int index);

  /**
   * Set a particular neighbor of a given node.
   *
   * @param src   the node whose neighbor to set
   * @param dst  the new neighbor
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @param index the particular neighbor to set
   */
  public void setNeighbor(GNode<N> src, GNode<N> dst, int index, byte flags);

  /**
   * Get a particular neighbor of a given node
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src   the node whose neighbor to get
   * @param index the particular neighbor to get
   * @return the neighbor at index
   */
  public GNode<N> getNeighbor(GNode<N> src, int index);

  /**
   * Get a particular neighbor of a given node
   *
   * @param src   the node whose neighbor to get
   * @param index the particular neighbor to get
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the neighbor at index
   */
  public GNode<N> getNeighbor(GNode<N> src, int index, byte flags);

  /**
   * Remove a particular neighbor of a given node. Note that this is equivalent
   * to calling setNeighbor(src, null, index)
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src   the node whose neighbor to remove
   * @param index the neighbor to remove
   * @return true if the neighbor was successfully removed
   */
  public boolean removeNeighbor(GNode<N> src, int index);

  /**
   * Remove a particular neighbor of a given node. Note that this is equivalent
   * to calling setNeighbor(src, null, index)
   *
   * @param src   the node whose neighbor to remove
   * @param index the neighbor to remove
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return true if the neighbor was successfully removed
   */
  public boolean removeNeighbor(GNode<N> src, int index, byte flags);
}
