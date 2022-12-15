package mets_r.routing;

import org.jgrapht.graph.*;

import repast.simphony.space.graph.RepastEdge;

@SuppressWarnings("serial")
public class SimWeightedGraph<V, E> extends DefaultDirectedWeightedGraph<V, E> {

	public SimWeightedGraph(Class<? extends E> edgeClass) {
		super(edgeClass);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void setEdgeWeight(E e, double weight) {
		((RepastEdge) e).setWeight(weight);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public double getEdgeWeight(E e) {
		// super.setEdgeWeight(e, weight);
		return ((RepastEdge) e).getWeight();
	}
}
