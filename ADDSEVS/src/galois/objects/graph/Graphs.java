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

File: Graphs.java 

 */

package galois.objects.graph;

import galois.objects.GObject;
import util.Launcher;
import util.MutableInteger;
import util.MutableReference;
import util.fn.LambdaVoid;

/**
 * This class contains static utility methods that operate on or return objects of type {@link Graph}
 */
public class Graphs {

  /**
   * Retrieves a random node from the graph.
   * @param graph the graph to choose the node from
   * @param <N> the type of the data contained in a node
   * @return a random vertex contained in the indicated graph
   */
  public static <N extends GObject> GNode<N> getRandom(Graph<N> graph) {
    return getRandom(graph, graph.size());
  }

  /**
   * Retrieves a random node from the graph.
   * @param graph the graph to choose the node from
   * @param seed a seed used to initialize the random generator
   * @param <N> the type of the data contained in a node
   * @return a random vertex contained in the indicated graph
   */
  public static <N extends GObject> GNode<N> getRandom(Graph<N> graph, int seed) {
    final int size = graph.size();
    final int random = Launcher.getLauncher().getRandom(seed).nextInt(size);
    final MutableInteger counter = new MutableInteger(0);
    final MutableReference<GNode<N>> ret = new MutableReference<GNode<N>>();
    graph.map(new LambdaVoid<GNode<N>>() {
      @Override
      public void call(GNode<N> node) {
        if (counter.getAndIncrement() == random) {
          ret.set(node);
        }
      }
    });
    return ret.get();
  }
}
