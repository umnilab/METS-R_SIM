package evacSim.routing;

import java.util.*;

//import org.jgrapht.EdgeFactory;
import org.jgrapht.*;
import org.jgrapht.graph.*;

import edu.uci.ics.jung.graph.Graph;
import repast.simphony.space.graph.RepastEdge;
import evacSim.ContextCreator;

/**
 * A Converter class that converts a Jung graph to that used in the Jgraph library.
 * 
 * @author Samiul Hasan
 */
@SuppressWarnings("serial")
public class JungToJgraph<T>
{

   @SuppressWarnings("unchecked")
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
         // if (ContextCreator.debugSH) System.out.println("Weight: "+ weight);

         T source = jungGraph.getSource(edge);
         T target = jungGraph.getDest(edge);
         jGraph.addEdge(source, target, edge);
         jGraph.setEdgeWeight(edge, weight);
      }
      return jGraph;
   }
}
