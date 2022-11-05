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

File: Graph.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.objects.Mappable;
import util.fn.LambdaVoid;

/**
 * Root interface in the graph hierarchy. A graph is a set of nodes connected by edges. Two nodes can be connected by at
 * most one edge (i.e., this graph is <b>not</b> a multigraph), but self edges are allowed. <br/>
 * This interface supports the storage of data in the nodes of the graph, but not in the edges. Use
 * {@link galois.objects.graph.ObjectGraph} if you need to store values on the edges too.
 *
 * @param <N> the type of the data contained in a node
 */
public interface Graph<N extends GObject> extends Mappable<GNode<N>>, GObject {
  /**
   * Creates a new node holding the indicated data.
   * The new node is <b>not</b> automatically added to the graph (use {@link #add(GNode, byte)}),
   *
   * @param n data contained in the new node.
   * @return the newly created node.
   */
  public GNode<N> createNode(N n);

  /**
   * Creates a new node holding the indicated data.
   * The new node is <b>not</b> automatically added to the graph (use {@link #add(GNode, byte)}),
   *
   * @param n data contained in the new node.
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the newly created node.
   */
  public GNode<N> createNode(N n, byte flags);

  /**
   * Adds a node to the graph.
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param n node created by the current graph
   * @return true if the node was not already in the graph
   * @throws IllegalArgumentException if the node has been created by another graph
   */
  public boolean add(GNode<N> n);

  /**
   * Adds a node to the graph.
   *
   * @param n     node created by the current graph
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return true if the node was not already in the graph
   * @throws IllegalArgumentException if the node has been created by another graph
   */
  public boolean add(GNode<N> n, byte flags);

  /**
   * Removes a node from the graph along with all its outgoing/incoming edges.
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param n node to be removed
   * @return true if the node was removed from the graph
   */
  public boolean remove(GNode<N> n);

  /**
   * Removes a node from the graph along with all its outgoing/incoming edges.
   *
   * @param n node to be removed
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return true if the node was removed from the graph
   */
  public boolean remove(GNode<N> n, byte flags);

  /**
   * Checks if a node is in the graph
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param n the node to check for
   * @return true if the node is part of this graph
   */
  public boolean contains(GNode<N> n);

  /**
   * Checks if a node is in the graph
   *
   * @param n the node to check for
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return true if the node is part of this graph
   */
  public boolean contains(GNode<N> n, byte flags);

  /**
   * Returns the number of nodes in this graph.
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @return the number of nodes (vertices) in this graph
   */
  public int size();

  /**
   * Returns the number of nodes in this graph.
   *
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the number of nodes (vertices) in this graph
   */
  public int size(byte flags);

  /**
   * Adds an edge between the two nodes. If the graph is directed, the first node is interpreted
   * to be the source of the edge.
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src the source of the edge
   * @param dst the destination of the edge
   * @return true if the edge was not already in the graph
   */
  public boolean addNeighbor(GNode<N> src, GNode<N> dst);

  /**
   * Adds an edge between the two nodes. If the graph is directed, the first node is interpreted
   * to be the source of the edge.
   *
   * @param src the source of the edge
   * @param dst the destination of the edge
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return true if the edge was not already in the graph
   */
  public boolean addNeighbor(GNode<N> src, GNode<N> dst, byte flags);

  /**
   * Removes the edge between the two nodes from this graph. If the graph is directed, the first node is interpreted
   * to be the source of the edge.
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src the source of the edge
   * @param dst the target of the edge
   * @return true if the edge was removed from this graph
   */
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst);

  /**
   * Removes the edge between the two nodes from this graph. If the graph is directed, the first node is interpreted
   * to be the source of the edge.
   *
   * @param src the source of the edge
   * @param dst the target of the edge
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return true if the edge was removed from this graph
   */
  public boolean removeNeighbor(GNode<N> src, GNode<N> dst, byte flags);

  /**
   * Checks if there is an edge between the two nodes in this graph. If the graph is directed, the first node is interpreted
   * to be the source of the edge.
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src the source of the edge
   * @param dst the target of the edge
   * @return true if the two nodes are neighbors in this graph
   */
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst);

  /**
   * Checks if there is an edge between the two nodes in this graph. If the graph is directed, the first node is interpreted
   * to be the source of the edge.
   *
   * @param src the source of the edge
   * @param dst the target of the edge
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return true if the two nodes are neighbors in this graph
   */
  public boolean hasNeighbor(GNode<N> src, GNode<N> dst, byte flags);

  /**
   * Applies the function passed as parameter to every <i>in neighbor</i> of the given node exactly once.
   * For example, if we assume that each node contains an integer and we want to print the integer contained in each incoming
   * neighbor of <code>node</code> in <code>graph</code>
   * <pre>
   *   graph.mapInNeighbors(node, new LambdaVoid&lt;GNode&lt;Integer&gt;&gt;() {
   *      public void call(GNode&lt;Integer&gt; inNeighbor) {
   *        System.out.println(inNeighbor.getData());
   *     }
   *   }
   * </pre>
   * If you wish to apply a function to the <i>out neighbors</i>, please use GNode#map
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.

   * @param src  a node in the graph
   * @param body the function to be applied once to each incoming neighbor
   * @throws java.util.ConcurrentModificationException
   *          if the function modifies the set of in neighbors
   *          of the node
   * @see GNode#map
   */
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> body);

  /**
   * Applies the function passed as parameter to every <i>in neighbor</i> of the given node exactly once.
   * For example, if we assume that each node contains an integer and we want to print the integer contained in each incoming
   * neighbor of <code>node</code> in <code>graph</code>
   * <pre>
   *   graph.mapInNeighbors(node, new LambdaVoid&lt;GNode&lt;Integer&gt;&gt;() {
   *      public void call(GNode&lt;Integer&gt; inNeighbor) {
   *        System.out.println(inNeighbor.getData());
   *     }
   *   }
   * </pre>
   * If you wish to apply a function to the <i>out neighbors</i>, please use GNode#map
   *
   * @param src  a node in the graph
   * @param body the function to be applied once to each incoming neighbor
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @throws java.util.ConcurrentModificationException
   *          if the function modifies the set of in neighbors
   *          of the node
   * @see GNode#map
   */
  public void mapInNeighbors(GNode<N> src, LambdaVoid<GNode<N>> body, byte flags);

  /**
   * Computes the number of <i>in neighbors</i> of the node, i.e., the number of different vertices that have an outgoing edge
   * that ends at the indicated node.
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src a node in the graph
   * @return the number of incoming neighbors
   */
  public int inNeighborsSize(GNode<N> src);

  /**
   * Computes the number of <i>in neighbors</i> of the node, i.e., the number of different vertices that have an outgoing edge
   * that ends at the indicated node.
   *
   * @param src a node in the graph
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the number of incoming neighbors
   */
  public int inNeighborsSize(GNode<N> src, byte flags);

  /**
   * Computes the number of <i>out neighbors</i> of the node, i.e., the number of different vertices that have an incoming edge
   * that starts at the indicated node.
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param src a node in the graph
   * @return the number of outgoing neighbors
   */
  public int outNeighborsSize(GNode<N> src);

  /**
   * Computes the number of <i>out neighbors</i> of the node, i.e., the number of different vertices that have an incoming edge
   * that starts at the indicated node.
   *
   * @param src a node in the graph
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the number of outgoing neighbors
   */
  public int outNeighborsSize(GNode<N> src, byte flags);

  /**
   * Tests if the current graph is directed.
   *
   * @return true if the graph uses directed edges
   */
  public boolean isDirected();
}
