package evacSim.routing;

import java.util.ArrayList;
import java.util.List;

import org.jgrapht.*;
import org.jgrapht.graph.*;
import org.jgrapht.alg.KShortestPaths;
import org.jgrapht.GraphPath;

import com.vividsolutions.jts.geom.Coordinate;

import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.space.projection.ProjectionEvent;
import repast.simphony.space.projection.ProjectionListener;
import repast.simphony.space.graph.EdgeCreator;
import repast.simphony.space.graph.JungNetwork;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import edu.uci.ics.jung.graph.Graph;

/**
 * Calculates the k-shortest path from a specified node to all other nodes Need to convert my
 * network into jgrapht graph then apply k-shortest path
 * 
 * @author Samiul Hasan
 * @version $Revision$ $Date$
 */

public class KShortestPath<T> implements ProjectionListener<T>
{
   private Network<T> network;
   private boolean calc = true;
   private T source;

   // TODO: what is k?
   private int K;

   // TODO: ksp is never initialized
   private KShortestPaths<T, RepastEdge<T>> ksp;

   /**
    * Constructor
    * 
    * @param net
    *           the Network
    */
   public KShortestPath(Network<T> network, T source, int k)
   {
      this.network = network;
      this.source = source;
      this.K = k;
      //network.addProjectionListener(this);
   }

   /**
    * Returns a list of RepastEdges in the shortest path from source to target.
    * 
    * @param source
    * @param target
    * @return
    */
   public List<GraphPath<T, RepastEdge<T>>> getPaths(T target)
   {
      if (calc)
      {
         calcPaths();
         calc = false;
      }
      return ksp.getPaths(target);
   }

   /**
    * Gets the path length kth path.
    * 
    * @param k
    * @return the path length from the source node to the target node.
    */
   public double getPathLength(GraphPath<T, RepastEdge<T>> graphPath)
   {
      if (calc)
      {
         calcPaths();
         calc = false;
      }
      Number n = graphPath.getWeight();

      if (n != null)
         return n.doubleValue();
      else
         return Double.POSITIVE_INFINITY;
   }

   /**
    * Gets the path length from the source node specified in the constructor to the target node.
    * 
    * @deprecated As of release 1.2, replaced by {@link #getPathLength(T source, T target)}
    * @param target
    *           the node we want to get the path length to
    * @return the path length from the source node to the target node.
    */
   /*
    * @Deprecated /public double getPathLength(T target){ return getPathLength(this.source, target);
    * }
    */

   /**
    * Creates shortest path info nodes using the KShortestPath algorithm
    */
   private void calcPaths()
   {
      Graph<T, RepastEdge<T>> graphA = null;
      WeightedGraph<T, RepastEdge<T>> graphB = null;

      if (network instanceof JungNetwork)
         graphA = ((JungNetwork) network).getGraph();
      else if (network instanceof ContextJungNetwork)
         graphA = ((ContextJungNetwork) network).getGraph();

      JungToJgraph<T> converter = new JungToJgraph<T>();
      graphB = converter.convertToJgraph(graphA);

      ksp = new KShortestPaths<T, RepastEdge<T>>(graphB, source, K);
   }

   /**
    * Called when the network is modified so that this will recalculate the shortest path info.
    * 
    * @param evt
    */
   public void projectionEventOccurred(ProjectionEvent<T> evt)
   {
      if (evt.getType() != ProjectionEvent.OBJECT_MOVED)
      {
         calc = true;
      }
   }

   /**
    * Removes this as a projection listener when this ShortestPath is garbage collected.
    */
   @Override
   public void finalize()
   {
      if (network != null) network.removeProjectionListener(this);
   }
}
