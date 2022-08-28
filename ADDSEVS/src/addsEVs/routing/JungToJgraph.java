package addsEVs.routing;

import org.jgrapht.graph.*;
import edu.uci.ics.jung.graph.Graph;
import repast.simphony.space.graph.RepastEdge;

/**
 * A Converter class that converts a Jung graph to that used in the Jgraph library.
 * 
 * @author Samiul Hasan
 */
public class JungToJgraph<T>
{

   @SuppressWarnings({ "unchecked", "rawtypes" })
   public org.jgrapht.WeightedGraph<T, RepastEdge<T>> convertToJgraph(
                                                                      Graph<T, RepastEdge<T>> jungGraph)
   {

      // org.jgrapht.Graphs jGraphs;
      evacSimWeightedGraph<T, RepastEdge<T>> jGraph =
               new evacSimWeightedGraph<T, RepastEdge<T>>(
                        new ClassBasedEdgeFactory<T, RepastEdge<T>>(
                                 (Class<? extends RepastEdge<T>>) RepastEdge.class));

      // Container<T> container=new Container<T> ();

      for (T vertex : jungGraph.getVertices())
      {
         jGraph.addVertex(vertex);
      }

      // jGraphs.addAllVertices(jGraph, jungGraph.getVertices());

      for (RepastEdge<T> edge : jungGraph.getEdges())
      {
         double weight = 1.0;
         if (edge instanceof RepastEdge)
         {
            weight = ((RepastEdge) edge).getWeight();
         }
         T source = jungGraph.getSource(edge);
         T target = jungGraph.getDest(edge);
         jGraph.addEdge(source, target, edge);
         jGraph.setEdgeWeight(edge, weight);
      }
      return jGraph;
   }
}
