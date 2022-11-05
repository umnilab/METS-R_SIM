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

import galois.objects.GObject;
import galois.objects.Lockable;
import galois.objects.Mappable;
import galois.runtime.Replayable;

/**
 * A node in a graph.
 * 
 * @param <N> the type of the data stored in each node
 */
public interface GNode<N> extends Replayable, Lockable, Mappable<GNode<N>>, GObject {
  /**
   * Retrieves the node data associated with the vertex
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @return the data contained in the node
   */
  public N getData();

  /**
   * Retrieves the node data associated with the vertex. Equivalent to {@link #getData(byte, byte)}
   * passing <code>flags</code> to both parameters.
   *
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the data contained in the node
   */
  public N getData(byte flags);

  /**
   * Retrieves the node data associated with the vertex. For convenience, this method
   * also calls {@link GObject#access(byte)} on the returned data.
   * 
   * <p>Recall that the
   * {@link GNode} object maintains information about the vertex and its connectivity
   * in the graph. This is separate from the data itself. For example,
   * <code>getData(MethodFlag.NONE, MethodFlag.SAVE_UNDO)</code>
   * does not acquire an abstract lock on the {@link GNode} (perhaps because
   * it was returned by a call to {@link GNode#map(util.fn.LambdaVoid)}), but it
   * saves undo information on the returned data in case the iteration needs to
   * be rolled back.
   * </p>
   * 
   * @param nodeFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                  upon invocation of this method on the <i>node itself</i>.
   *                  See {@link galois.objects.MethodFlag}
   * @param dataFlags Galois runtime actions (e.g., conflict detection) that need to be executed
   *                  upon invocation of this method on the <i>data</i> contained in the node.
   *                  See {@link galois.objects.MethodFlag}
   * @return the data contained in the node
   */
  public N getData(byte nodeFlags, byte dataFlags);

  /**
   * Sets the node data.
   *
   * All the Galois runtime actions (e.g., conflict detection) will be performed when
   * the method is executed.
   *
   * @param d the data to be stored
   * @return the old data associated with the node
   */
  public N setData(N d);

  /**
   * Sets the node data.
   *
   * @param d the data to be stored
   * @param flags Galois runtime actions (e.g., conflict detection) that need to be executed
   *              upon invocation of this method. See {@link galois.objects.MethodFlag}
   * @return the old data associated with the node
   */
  public N setData(N d, byte flags);
}
