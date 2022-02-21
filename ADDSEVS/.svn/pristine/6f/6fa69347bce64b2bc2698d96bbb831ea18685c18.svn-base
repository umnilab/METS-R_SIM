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

File: ObjectGraph.java 

 */

package galois.objects.graph;

import galois.objects.GObject;

/**
 * Interface used to represent graphs whose edges contain data of a non-primitive type. Nulls are allowed as
 * edge data. Edge data must implement {@link GObject} just like node data.
 * 
 * <p>
 * Since we have
 * value edge graphs which we also want to implement this interface, the restriction on
 * edge data is maintained via runtime checks rather than via generic types.
 * </p>
 * 
 * <p>
 * Edges can be created <b>only</b> by invoking {@link ObjectGraph#addEdge}: invocations of
 * {@link Graph#addNeighbor(GNode, GNode)} will result in a exception, because no edge data has been specified.
 * </p>
 * 
 * @param <N> the type of the object stored in each node
 * @param <E> the type of the object stored in each edge, must implement {@link GObject}
 */
public interface ObjectGraph<N extends GObject, E> extends Graph<N> {
  /**
   * Adds an edge to the graph containing the specified data.
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src  the source node of the edge.
   * @param dst  the destination node of the edge.
   * @param data information to be stored in the new edge.
   * @return true if there was no previous edge between the two nodes
   */
  public boolean addEdge(GNode<N> src, GNode<N> dst, E data);

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
  public boolean addEdge(GNode<N> src, GNode<N> dst, E data, byte flags);

  /**
   * Retrieves the data associated with an edge.
   * 
   * <p>
   * Be aware that this method will return null in two cases:
   * </p>
   * 
   * <ul>
   *   <li> there is no edge between the two nodes
   *   <li> there is an edge between the two nodes, and its data is null
   * </ul>
   * 
   * <p>
   * In order to distinguish between the two cases, use {@link Graph#hasNeighbor(GNode, GNode)}
   * </p>
   * 
   * <p>
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   * </p>
   * 
   * @param src  the source node of the edge
   * @param dst  the destination node of the edge
   * @return the data associated with the edge, or null if the edge does not exist
   */
  public E getEdgeData(GNode<N> src, GNode<N> dst);

  /**
   * Retrieves the data associated with an edge. Equivalent to {@link #getEdgeData(GNode, GNode, byte, byte)}
   * passing <code>flags</code> to both flag parameters.
   * 
   * <p>
   * Be aware that this method will return null in two cases:
   * </p>
   * 
   * <ul>
   *   <li> there is no edge between the two nodes
   *   <li> there is an edge between the two nodes, and its data is null
   * </ul>
   * 
   * <p>
   * In order to distinguish between the two cases, use {@link Graph#hasNeighbor(GNode, GNode)}
   * </p>
   * 
   * @param src  the source node of the edge
   * @param dst  the destination node of the edge
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the data associated with the edge, or null if the edge does not exist
   */
  public E getEdgeData(GNode<N> src, GNode<N> dst, byte flags);

  /**
   * Retrieves the data associated with an edge.  For convenience, this method
   * also calls {@link GObject#access(byte)} on the returned data.
   * 
   * <p>
   * Be aware that this method will return null in two cases:
   * </p>
   * 
   * <ul>
   *   <li> there is no edge between the two nodes
   *   <li> there is an edge between the two nodes, and its data is null
   * </ul>
   * 
   * <p>
   * In order to distinguish between the two cases, use {@link Graph#hasNeighbor(GNode, GNode)}
   * </p>
   * 
   * <p>Recall that an edge maintains information about two vertices.
   * This is separate from the data itself. For example,
   * <code>getEdgeData(src, dst, MethodFlag.NONE, MethodFlag.SAVE_UNDO)</code>
   * does not acquire an abstract lock on the vertices (perhaps because
   * they were returned by a call to {@link GNode#map(util.fn.LambdaVoid)}), but it
   * saves undo information on the returned data in case the iteration needs to
   * be rolled back.
   * </p>
   * 
   * @param src  the source node of the edge
   * @param dst  the destination node of the edge
   * @param edgeFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                  upon invocation of this method on the <i>edge itself<i>.
   *                  See {@link galois.objects.MethodFlag}
   * @param dataFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                  upon invocation of this method on the <i>data</i> contained in this edge.
   *                  See {@link galois.objects.MethodFlag}
   * @return the data associated with the edge, or null if the edge does not exist
   */
  public E getEdgeData(GNode<N> src, GNode<N> dst, byte edgeFlags, byte dataFlags);

  /**
   * Sets the data associated with an edge.
   *
   * <p>
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   * </p>
   * 
   * @param src the source node of the edge
   * @param dst the destination node of the edge
   * @param d the data to associate with the edge
   * @return the data previously associated with the edge, or null if the edge does not exist
   */
  public E setEdgeData(GNode<N> src, GNode<N> dst, E d);

  /**
   * Sets the data associated with an edge.
   *
   * @param src the source node of the edge.
   * @param dst the destination node of the edge.
   * @param d the data to associate with the edge
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the data previously associated with the edge, or null if the edge does not exist
   */
  public E setEdgeData(GNode<N> src, GNode<N> dst, E d, byte flags);
}
