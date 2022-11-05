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

File: LongGraph.java 

 */

package galois.objects.graph;

import galois.objects.GObject;

/**
 * Interface used to represent graphs whose edges contain long data. <br/>
 * Edges can be created <b>only</b> by invoking {@link ObjectGraph#addEdge}: invocations of
 * {@link Graph#addNeighbor(GNode, GNode)} will result in a exception, because no edge data has been specified.
 *
 * @param <N> the type of the object stored in each node
 */
public interface LongGraph<N extends GObject> extends Graph<N> {
  /**
   * Adds an edge to the graph containing the specified data.
   *
   * <p>
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   * </p>
   * 
   * @param src  the source node of the edge
   * @param dst  the destination node of the edge
   * @param data information to be stored in the new edge
   * @return true if there was no previous edge between the two nodes
   */
  public boolean addEdge(GNode<N> src, GNode<N> dst, long data);

  /**
   * Adds an edge to the graph containing the specified data.
   *
   * @param src  the source node of the edge
   * @param dst  the destination node of the edge
   * @param data information to be stored in the new edge
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return true if there was no previous edge between the two nodes
   */
  public boolean addEdge(GNode<N> src, GNode<N> dst, long data, byte flags);

  /**
   * Retrieves the data associated with an edge.
   * <p>
   * Be aware that this method will return -1 in two cases:
   * </p>
   * <ul>
   * <li> there is no edge between the two nodes
   * <li> there is an edge between the two nodes, and its data is -1
   * </ul>
   * <p>
   * In order to distinguish between the two cases, use {@link Graph#hasNeighbor(GNode, GNode)}
   * </p>
   * <p>
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   * </p>
   * 
   * @param src  the source node of the edge
   * @param dst  the destination node of the edge
   * @return the data associated with the edge, or -1 if the edge does not exist
   */
  public long getEdgeData(GNode<N> src, GNode<N> dst);

  /**
   * Retrieves the data associated with an edge.
   * <p>
   * Be aware that this method will return -1 in two cases:
   * </p>
   * <ul>
   * <li> there is no edge between the two nodes
   * <li> there is an edge between the two nodes, and its data is -1
   * </ul>
   * <p>
   * In order to distinguish between the two cases, use {@link Graph#hasNeighbor(GNode, GNode)}
   * </p>
   * @param src  the source node of the edge
   * @param dst  the destination node of the edge
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the data associated with the edge, or -1 if the edge does not exist
   */
  public long getEdgeData(GNode<N> src, GNode<N> dst, byte flags);

  /**
   * Sets the data associated with an edge.
   * <p>
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   * </p>
   * @param src the source node of the edge
   * @param dst the destination node of the edge
   * @param d the data to associate with the edge
   * @return the data previously associated with the edge, or -1 if the edge does not exist
   */
  public long setEdgeData(GNode<N> src, GNode<N> dst, long d);

  /**
   * Sets the data associated with an edge.
   *
   * @param src the source node of the edge
   * @param dst the destination node of the edge
   * @param d the data to associate with the edge
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the data previously associated with the edge, or -1 if the edge does not exist
   */
  public long setEdgeData(GNode<N> src, GNode<N> dst, long d, byte flags);
}
