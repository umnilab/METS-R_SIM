package mets_r.routing;

import org.jgrapht.*;
import org.jgrapht.graph.*;

import repast.simphony.space.graph.RepastEdge;

@SuppressWarnings("serial")
public class evacSimWeightedGraph<V, E> extends SimpleDirectedWeightedGraph<V, E> {

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public evacSimWeightedGraph(EdgeFactory ef) {
		super(ef);
	}

	public evacSimWeightedGraph(Class<? extends E> edgeClass) {
		this(new ClassBasedEdgeFactory<V, E>(edgeClass));
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void setEdgeWeight(E e, double weight) {
		// super.setEdgeWeight(e, weight);
		((RepastEdge) e).setWeight(weight);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public double getEdgeWeight(E e) {
		// super.setEdgeWeight(e, weight);
		return ((RepastEdge) e).getWeight();
	}
}
