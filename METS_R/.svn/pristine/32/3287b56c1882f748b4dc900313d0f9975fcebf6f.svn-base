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

File: MorphGraph.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import galois.runtime.GaloisRuntime;

/**
 * Most general implementation of a {@link ObjectGraph}, allowing modifications of the structure of the graph
 * (unlike {@link LocalComputationGraph}), as well as modifications of the data in the edges
 * and nodes.
 *
 * @see galois.objects.graph.LocalComputationGraph
 */
public final class MorphGraph {

  private MorphGraph() {
  }

  /**
   * A {@link galois.objects.graph.MorphGraph} builder, providing combinations of several features.
   */
  public static class ObjectGraphBuilder {

    boolean serial = false;
    boolean directed = false;
    boolean backedByVector = false;

    /**
     * Constructs a new builder instance with the following default settings: the graph will be undirected, parallel,
     * and backed by a hash map.
     */
    public ObjectGraphBuilder() {
    }

    /**
     * Indicates whether the implementation of the graph about to be created is serial (there is no concurrency or
     * transactional support) or parallel (can be safely used within Galois iterators). For example, a graph that is
     * purely thread local can benefit from using a serial implementation, which is expected to add no overheads
     * due to concurrency or the runtime system.
     *
     * @param serial boolean value that indicates whether the graph is serial or not.
     */
    public ObjectGraphBuilder serial(boolean serial) {
      this.serial = serial;
      return this;
    }

    /**
     * Indicates whether edges in the graph are directed or not.
     *
     * @param directed boolean flag that indicates whether the graph is directed.
     */
    public ObjectGraphBuilder directed(boolean directed) {
      this.directed = directed;
      return this;
    }

    /**
     * Indicates whether the underlying implementation of the graph should use a {@link java.util.ArrayList}
     * or a {@link java.util.HashMap} for representing the set of neighbors of a node. The chosen representation
     * has an impact on performance. As a rule of thumb, use vector-based representations if and only if the set of
     * neighbors of every node in the graph remains sparse throughout the computation.
     *
     * @param backedByVector flag that indicates whether to use the vector-based representation.
     */
    public ObjectGraphBuilder backedByVector(boolean backedByVector) {
      this.backedByVector = backedByVector;
      return this;
    }

    /**
     * Builds the final graph. This method does not alter the state of this
     * builder instance, so it can be invoked again to create
     * multiple independent graphs.
     *
     * @param <N> the type of the object stored in each node
     * @param <E> the type of the object stored in each edge
     * @return an object graph with the requested features
     */
    public <N extends GObject, E> ObjectGraph<N, E> create() {
      ObjectGraph<N, E> retval;
      if (serial || GaloisRuntime.getRuntime().useSerial()) {
        if (backedByVector)
          retval = new SerialVectorMorphObjectGraph<N, E>(directed);
        else
          retval = new SerialHashMorphObjectGraph<N, E>(directed);
      } else {
        if (backedByVector)
          retval = new VectorMorphObjectGraph<N, E>(directed);
        else
          retval = new HashMorphObjectGraph<N, E>(directed);
      }

      if (GaloisRuntime.getRuntime().ignoreUserFlags())
        retval = new ObjectGraphToAllObjectGraphAdapter<N, E>(retval);

      return retval;
    }
  }

  /**
   * A {@link galois.objects.graph.MorphGraph} builder, providing combinations of several features.
   */
  public static class IntGraphBuilder {

    boolean serial = false;
    boolean directed = false;
    boolean backedByVector = false;

    /**
     * Constructs a new builder instance with the following default settings: the graph will be undirected, parallel,
     * and backed by a hash map.
     */
    public IntGraphBuilder() {
    }

    /**
     * Indicates whether the implementation of the graph about to be created is serial (there is no concurrency or
     * transactional support) or parallel (can be safely used within Galois iterators). For example, a graph that is
     * purely thread local can benefit from using a serial implementation, which is expected to add no overheads
     * due to concurrency or the runtime system.
     *
     * @param serial boolean value that indicates whether the graph is serial or not.
     */
    public IntGraphBuilder serial(boolean serial) {
      this.serial = serial;
      return this;
    }

    /**
     * Indicates whether edges in the graph are directed or not.
     *
     * @param directed boolean flag that indicates whether the graph is directed.
     */
    public IntGraphBuilder directed(boolean directed) {
      this.directed = directed;
      return this;
    }

    /**
     * Indicates whether the underlying implementation of the graph should use a {@link java.util.ArrayList}
     * or a {@link java.util.HashMap} for representing the set of neighbors of a node. The chosen representation
     * has an impact on performance. As a rule of thumb, use vector-based representations if and only if the set of
     * neighbors of every node in the graph remains sparse throughout the computation.
     *
     * @param backedByVector flag that indicates whether to use the vector-based representation.
     */
    public IntGraphBuilder backedByVector(boolean backedByVector) {
      this.backedByVector = backedByVector;
      return this;
    }

    /**
     * Builds the final graph. This method does not alter the state of this
     * builder instance, so it can be invoked again to create
     * multiple independent graphs.
     *
     * @param <N> the type of the object stored in each node
     * @return an integer graph with the requested features
     */
    public <N extends GObject> IntGraph<N> create() {
      final ObjectGraphBuilder builder = new ObjectGraphBuilder();
      ObjectGraph<N, Integer> objectGraph = builder.directed(directed).serial(serial).backedByVector(backedByVector)
          .create();
      return new ObjectGraphToIntGraphAdapter<N>(objectGraph);
    }
  }

  /**
   * A {@link galois.objects.graph.MorphGraph} builder, providing combinations of several features.
   */
  public static class LongGraphBuilder {

    boolean serial = false;
    boolean directed = false;
    boolean backedByVector = false;

    /**
     * Constructs a new builder instance with the following default settings: the graph will be undirected, parallel,
     * and backed by a hash map.
     */
    public LongGraphBuilder() {
    }

    /**
     * Indicates whether the implementation of the graph about to be created is serial (there is no concurrency or
     * transactional support) or parallel (can be safely used within Galois iterators). For example, a graph that is
     * purely thread local can benefit from using a serial implementation, which is expected to add no overheads
     * due to concurrency or the runtime system.
     *
     * @param serial boolean value that indicates whether the graph is serial or not.
     */
    public LongGraphBuilder serial(boolean serial) {
      this.serial = serial;
      return this;
    }

    /**
     * Indicates whether edges in the graph are directed or not.
     *
     * @param directed boolean flag that indicates whether the graph is directed.
     */
    public LongGraphBuilder directed(boolean directed) {
      this.directed = directed;
      return this;
    }

    /**
     * Indicates whether the underlying implementation of the graph should use a {@link java.util.ArrayList}
     * or a {@link java.util.HashMap} for representing the set of neighbors of a node. The chosen representation
     * has an impact on performance. As a rule of thumb, use vector-based representations if and only if the set of
     * neighbors of every node in the graph remains sparse throughout the computation.
     *
     * @param backedByVector flag that indicates whether to use the vector-based representation.
     */
    public LongGraphBuilder backedByVector(boolean backedByVector) {
      this.backedByVector = backedByVector;
      return this;
    }

    /**
     * Builds the final graph. This method does not alter the state of this
     * builder instance, so it can be invoked again to create
     * multiple independent graphs.
     *
     * @param <N> the type of the object stored in each node
     * @return a long graph with the requested features
     */
    public <N extends GObject> LongGraph<N> create() {
      final ObjectGraphBuilder builder = new ObjectGraphBuilder();
      ObjectGraph<N, Long> objectGraph = builder.directed(directed).serial(serial).backedByVector(backedByVector)
          .create();
      return new ObjectGraphToLongGraphAdapter<N>(objectGraph);
    }
  }

  /**
   * A {@link galois.objects.graph.MorphGraph} builder, providing combinations of several features.
   */
  public static class FloatGraphBuilder {

    boolean serial = false;
    boolean directed = false;
    boolean backedByVector = false;

    /**
     * Constructs a new builder instance with the following default settings: the graph will be undirected, parallel,
     * and backed by a hash map.
     */
    public FloatGraphBuilder() {
    }

    /**
     * Indicates whether the implementation of the graph about to be created is serial (there is no concurrency or
     * transactional support) or parallel (can be safely used within Galois iterators). For example, a graph that is
     * purely thread local can benefit from using a serial implementation, which is expected to add no overheads
     * due to concurrency or the runtime system.
     *
     * @param serial boolean value that indicates whether the graph is serial or not.
     */
    public FloatGraphBuilder serial(boolean serial) {
      this.serial = serial;
      return this;
    }

    /**
     * Indicates whether edges in the graph are directed or not.
     *
     * @param directed boolean flag that indicates whether the graph is directed.
     */
    public FloatGraphBuilder directed(boolean directed) {
      this.directed = directed;
      return this;
    }

    /**
     * Indicates whether the underlying implementation of the graph should use a {@link java.util.ArrayList}
     * or a {@link java.util.HashMap} for representing the set of neighbors of a node. The chosen representation
     * has an impact on performance. As a rule of thumb, use vector-based representations if and only if the set of
     * neighbors of every node in the graph remains sparse throughout the computation.
     *
     * @param backedByVector flag that indicates whether to use the vector-based representation.
     */
    public FloatGraphBuilder backedByVector(boolean backedByVector) {
      this.backedByVector = backedByVector;
      return this;
    }

    /**
     * Builds the final graph. This method does not alter the state of this
     * builder instance, so it can be invoked again to create
     * multiple independent graphs.
     *
     * @param <N> the type of the object stored in each node
     * @return a float graph with the requested features
     */
    public <N extends GObject> FloatGraph<N> create() {
      final ObjectGraphBuilder builder = new ObjectGraphBuilder();
      ObjectGraph<N, Float> objectGraph = builder.directed(directed).serial(serial).backedByVector(backedByVector)
          .create();
      return new ObjectGraphToFloatGraphAdapter<N>(objectGraph);
    }
  }

  /**
   * A {@link galois.objects.graph.MorphGraph} builder, providing combinations of several features.
   */
  public static class DoubleGraphBuilder {

    boolean serial = false;
    boolean directed = false;
    boolean backedByVector = false;

    /**
     * Constructs a new builder instance with the following default settings: the graph will be undirected, parallel,
     * and backed by a hash map.
     */
    public DoubleGraphBuilder() {
    }

    /**
     * Indicates whether the implementation of the graph about to be created is serial (there is no concurrency or
     * transactional support) or parallel (can be safely used within Galois iterators). For example, a graph that is
     * purely thread local can benefit from using a serial implementation, which is expected to add no overheads
     * due to concurrency or the runtime system.
     *
     * @param serial boolean value that indicates whether the graph is serial or not.
     */
    public DoubleGraphBuilder serial(boolean serial) {
      this.serial = serial;
      return this;
    }

    /**
     * Indicates whether edges in the graph are directed or not.
     *
     * @param directed boolean flag that indicates whether the graph is directed.
     */
    public DoubleGraphBuilder directed(boolean directed) {
      this.directed = directed;
      return this;
    }

    /**
     * Indicates whether the underlying implementation of the graph should use a {@link java.util.ArrayList}
     * or a {@link java.util.HashMap} for representing the set of neighbors of a node. The chosen representation
     * has an impact on performance. As a rule of thumb, use vector-based representations if and only if the set of
     * neighbors of every node in the graph remains sparse throughout the computation.
     *
     * @param backedByVector flag that indicates whether to use the vector-based representation.
     */
    public DoubleGraphBuilder backedByVector(boolean backedByVector) {
      this.backedByVector = backedByVector;
      return this;
    }

    /**
     * Builds the final graph. This method does not alter the state of this
     * builder instance, so it can be invoked again to create
     * multiple independent graphs.
     *
     * @param <N> the type of the object stored in each node
     * @return a double graph with the requested features
     */
    public <N extends GObject> DoubleGraph<N> create() {
      final ObjectGraphBuilder builder = new ObjectGraphBuilder();
      ObjectGraph<N, Double> objectGraph = builder.directed(directed).serial(serial).backedByVector(backedByVector)
          .create();
      return new ObjectGraphToDoubleGraphAdapter<N>(objectGraph);
    }
  }

  /**
   * A {@link galois.objects.graph.MorphGraph} builder, providing combinations of several features.
   */
  public static class VoidGraphBuilder {
    boolean serial = false;
    boolean directed = false;
    boolean backedByVector = false;

    /**
     * Constructs a new builder instance with the following default settings: the graph will be undirected, parallel,
     * and backed by a hash map.
     */
    public VoidGraphBuilder() {
    }

    /**
     * Indicates whether the implementation of the graph about to be created is serial (there is no concurrency or
     * transactional support) or parallel (can be safely used within Galois iterators). For example, a graph that is
     * purely thread local can benefit from using a serial implementation, which is expected to add no overheads
     * due to concurrency or the runtime system.
     *
     * @param serial boolean value that indicates whether the graph is serial or not.
     */
    public VoidGraphBuilder serial(boolean serial) {
      this.serial = serial;
      return this;
    }

    /**
     * Indicates whether edges in the graph are directed or not.
     *
     * @param directed boolean flag that indicates whether the graph is directed.
     */
    public VoidGraphBuilder directed(boolean directed) {
      this.directed = directed;
      return this;
    }

    /**
     * Indicates whether the underlying implementation of the graph should use a {@link java.util.ArrayList}
     * or a {@link java.util.HashMap} for representing the set of neighbors of a node. The chosen representation
     * has an impact on performance. As a rule of thumb, use vector-based representations if and only if the set of
     * neighbors of every node in the graph remains sparse throughout the computation.
     *
     * @param backedByVector flag that indicates whether to use the vector-based representation.
     */
    public VoidGraphBuilder backedByVector(boolean backedByVector) {
      this.backedByVector = backedByVector;
      return this;
    }

    /**
     * Builds the final graph. This method does not alter the state of this
     * builder instance, so it can be invoked again to create
     * multiple independent graphs.
     *
     * @param <N> the type of the object stored in each node
     * @return a graph with the requested features
     */
    public <N extends GObject> Graph<N> create() {
      final ObjectGraphBuilder builder = new ObjectGraphBuilder();
      ObjectGraph<N, Object> objectGraph = builder.directed(directed).serial(serial).backedByVector(backedByVector)
          .create();
      return new ObjectGraphToVoidGraphAdapter<N>(objectGraph);
    }
  }
}
