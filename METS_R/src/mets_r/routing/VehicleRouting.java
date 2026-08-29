package mets_r.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import org.jgrapht.alg.shortestpath.BidirectionalDijkstraShortestPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.alg.shortestpath.YenKShortestPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.GraphPath;

import repast.simphony.context.space.graph.ContextJungNetwork;
import repast.simphony.space.graph.JungNetwork;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import edu.uci.ics.jung.graph.Graph;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ConnectorRoad;
import mets_r.facility.Node;
import mets_r.facility.Road;
import mets_r.facility.RoadContext;

public class VehicleRouting {
	public DefaultDirectedWeightedGraph<Node, RepastEdge<Node>> transformedNetwork = null;
	// NodeToJgraph intentionally reuses the RepastEdge instances from the source
	// network. Keep one lock across every VehicleRouting generation so a route on
	// an old or newly rebuilt graph cannot overlap a shared edge-weight update.
	private static final ReentrantReadWriteLock SHARED_GRAPH_LOCK = new ReentrantReadWriteLock();
	private static final AtomicLong SHARED_GRAPH_VERSION = new AtomicLong();
	private static final int MAX_DETERMINISTIC_ROUTE_CACHE_ENTRIES = 65536;
	private final Object deterministicRouteCacheLock = new Object();
	private volatile DeterministicRouteCache deterministicRouteCache =
			new DeterministicRouteCache(Long.MIN_VALUE);

	@SuppressWarnings({"unchecked", "rawtypes"})
	public VehicleRouting(Network<Node> roadNetwork) {
		SHARED_GRAPH_LOCK.writeLock().lock();
		try {
			Graph<Node, RepastEdge<Node>> graphA = null;
			if (roadNetwork instanceof JungNetwork)
				graphA = ((JungNetwork) roadNetwork).getGraph();
			else if (roadNetwork instanceof ContextJungNetwork)
				graphA = ((ContextJungNetwork) roadNetwork).getGraph();
			NodeToJgraph<Node> converter = new NodeToJgraph<Node>();
			transformedNetwork = converter.convertToJgraph(graphA);
		} finally {
			SHARED_GRAPH_LOCK.writeLock().unlock();
		}
	}

	public void setEdgeWeight(Node node1, Node node2, double weight) {
//		ContextCreator.logger.info("Node 1" + node1.getID() + " Node 2" + node2.getID() + " Weight " + weight); 
		if (node1 == null || node2 == null || !Double.isFinite(weight)) return;
		SHARED_GRAPH_LOCK.writeLock().lock();
		try {
			RepastEdge<Node> edge = transformedNetwork.getEdge(node1, node2);
			if (edge != null) setSharedEdgeWeightLocked(edge, weight);
		} finally {
			SHARED_GRAPH_LOCK.writeLock().unlock();
		}
	}

	static boolean setSharedEdgeWeight(RepastEdge<Node> edge, double weight) {
		if (edge == null || !Double.isFinite(weight)) return false;
		SHARED_GRAPH_LOCK.writeLock().lock();
		try {
			return setSharedEdgeWeightLocked(edge, weight);
		} finally {
			SHARED_GRAPH_LOCK.writeLock().unlock();
		}
	}

	static int setSharedEdgeWeights(Map<RepastEdge<Node>, Double> updates) {
		if (updates == null || updates.isEmpty()) return 0;
		SHARED_GRAPH_LOCK.writeLock().lock();
		try {
			int applied = 0;
			for (Map.Entry<RepastEdge<Node>, Double> update : updates.entrySet()) {
				RepastEdge<Node> edge = update.getKey();
				Double weight = update.getValue();
				if (edge == null || weight == null || !Double.isFinite(weight.doubleValue())) {
					continue;
				}
				double normalizedWeight = Math.max(1.0e-3, weight.doubleValue());
				if (sameWeight(edge.getWeight(), normalizedWeight)) continue;
				edge.setWeight(normalizedWeight);
				applied++;
			}
			if (applied > 0) SHARED_GRAPH_VERSION.incrementAndGet();
			return applied;
		} finally {
			SHARED_GRAPH_LOCK.writeLock().unlock();
		}
	}

	private static boolean setSharedEdgeWeightLocked(RepastEdge<Node> edge, double weight) {
		double normalizedWeight = Math.max(1.0e-3, weight);
		if (sameWeight(edge.getWeight(), normalizedWeight)) return false;
		edge.setWeight(normalizedWeight);
		SHARED_GRAPH_VERSION.incrementAndGet();
		return true;
	}

	private static boolean sameWeight(double currentWeight, double requestedWeight) {
		return Double.doubleToLongBits(currentWeight)
				== Double.doubleToLongBits(requestedWeight);
	}

	static long getSharedGraphVersion() {
		return SHARED_GRAPH_VERSION.get();
	}

	static void markSharedGraphRebuilt() {
		SHARED_GRAPH_VERSION.incrementAndGet();
	}

	static <T> T withSharedGraphReadLock(Supplier<T> action) {
		SHARED_GRAPH_LOCK.readLock().lock();
		try {
			return action.get();
		} finally {
			SHARED_GRAPH_LOCK.readLock().unlock();
		}
	}

	public SingleSourcePathCache buildSingleSourcePathCache(Road originRoad) {
		if (originRoad == null || !originRoad.canBeOrigin()
				|| originRoad instanceof ConnectorRoad
				|| originRoad.getDownStreamNode() == null) {
			return null;
		}
		SHARED_GRAPH_LOCK.readLock().lock();
		try {
			ShortestPathAlgorithm.SingleSourcePaths<Node, RepastEdge<Node>> paths =
					new DijkstraShortestPath<Node, RepastEdge<Node>>(
							this.transformedNetwork)
							.getPaths(originRoad.getDownStreamNode());
			return new SingleSourcePathCache(originRoad, paths,
					SHARED_GRAPH_VERSION.get());
		} finally {
			SHARED_GRAPH_LOCK.readLock().unlock();
		}
	}

	public static final class SingleSourcePathCache {
		private final Road originRoad;
		private final ShortestPathAlgorithm.SingleSourcePaths<Node, RepastEdge<Node>> paths;
		private final long graphVersion;

		SingleSourcePathCache(Road originRoad,
				ShortestPathAlgorithm.SingleSourcePaths<Node, RepastEdge<Node>> paths,
				long graphVersion) {
			this.originRoad = originRoad;
			this.paths = paths;
			this.graphVersion = graphVersion;
		}

		public List<Road> routeTo(Road destinationRoad) {
			if (destinationRoad == null || !destinationRoad.canBeDest()
					|| destinationRoad instanceof ConnectorRoad
					|| destinationRoad.getUpStreamNode() == null) {
				return null;
			}
			SHARED_GRAPH_LOCK.readLock().lock();
			try {
				if (this.graphVersion != SHARED_GRAPH_VERSION.get()) return null;
				if (this.originRoad.getID() == destinationRoad.getID()) {
					ArrayList<Road> sameRoad = new ArrayList<Road>(1);
					sameRoad.add(this.originRoad);
					return sameRoad;
				}
				GraphPath<Node, RepastEdge<Node>> graphPath =
						this.paths.getPath(destinationRoad.getUpStreamNode());
				if (graphPath == null) return null;
				ArrayList<Road> route =
						new ArrayList<Road>(graphPath.getEdgeList().size() + 2);
				route.add(this.originRoad);
				for (RepastEdge<Node> edge : graphPath.getEdgeList()) {
					int roadID = ContextCreator.getCityContext().getRoadIDFromEdge(edge);
					if (roadID >= 0) {
						Road road = ContextCreator.getRoadContext().get(roadID);
						if (road != null) route.add(road);
					}
				}
				route.add(destinationRoad);
				return route;
			} finally {
				SHARED_GRAPH_LOCK.readLock().unlock();
			}
		}
	}

	/** Apply a connector's measured mean to its existing turn edge. */
	public void setConnectorWeight(ConnectorRoad connector) {
		if (connector == null) return;
		Node sourceNode = connector.getSourceRoad().getDownStreamNode();
		Node targetNode = connector.getTargetRoad().getUpStreamNode();
		if (sourceNode == null || targetNode == null) return;
		setEdgeWeight(sourceNode, targetNode,
				Math.max(1.0e-3, connector.getTravelTime()));
	}

	public List<List<Road>> computeKRoute(int K, Road currentRoad, Road destRoad, Node currNode,
			Node destNode) {
		SHARED_GRAPH_LOCK.readLock().lock();
		try {
			return computeKRouteLocked(K, currentRoad, destRoad, currNode, destNode);
		} finally {
			SHARED_GRAPH_LOCK.readLock().unlock();
		}
	}

	private List<List<Road>> computeKRouteLocked(int K, Road currentRoad, Road destRoad, Node currNode,
			Node destNode) {
		List<List<Road>> roadPath_ = new ArrayList<List<Road>>();
		YenKShortestPath<Node, RepastEdge<Node>> ksp = new YenKShortestPath<Node, RepastEdge<Node>>(
				transformedNetwork);
		List<GraphPath<Node, RepastEdge<Node>>> kshortestPath = ksp.getPaths(currNode, destNode, K);
		for (int k = 0; k < kshortestPath.size(); k++) {
			List<RepastEdge<Node>> shortestPath = kshortestPath.get(k).getEdgeList();
			// Find the roads which are associated with these edges
			if (shortestPath != null) { // Found the shortest path
				List<Road> oneRoadPath_ = new ArrayList<Road>(); // Save this path as a list of road and store it in
																	// oneRoadPath_
				oneRoadPath_.add(currentRoad);
				for (RepastEdge<Node> edge : shortestPath) {
					int roadID = ContextCreator.getCityContext().getRoadIDFromEdge(edge);
					if(roadID > 0) {
						oneRoadPath_.add(ContextCreator.getRoadContext().get(roadID));
					}
				}
				// Add the destination road
				oneRoadPath_.add(destRoad);
				// Add the whole path into roadPaths
				roadPath_.add(oneRoadPath_);
			}
		}
		
		return roadPath_;
	}
 
	/* Perform the routing computation */
	public List<Road> computeRoute(Road currentRoad, Road destRoad, Node currNode, Node destNode, Random rand) {
		return computeRoute(currentRoad, destRoad, currNode, destNode, rand, true);
	}

	List<Road> computeRoute(Road currentRoad, Road destRoad, Node currNode, Node destNode,
			Random rand, boolean logFailure) {
		SHARED_GRAPH_LOCK.readLock().lock();
		try {
			return computeRouteLocked(currentRoad, destRoad, currNode, destNode, rand, logFailure);
		} finally {
			SHARED_GRAPH_LOCK.readLock().unlock();
		}
	}

	private List<Road> computeRouteLocked(Road currentRoad, Road destRoad, Node currNode,
			Node destNode, Random rand, boolean logFailure) {
		List<Road> roadPath_ = null;
		List<RepastEdge<Node>> shortestPath = null;

		// Get the edges that make up the shortest path
		int K = GlobalVariables.K_VALUE;
		double theta = GlobalVariables.THETA_LOGIT;
		
		if (currentRoad.getID() == destRoad.getID()) { // Origin and destination road is the same
			roadPath_ = new ArrayList<Road>();
			roadPath_.add(currentRoad);
		}
		else {
			if (GlobalVariables.K_SHORTEST_PATH && rand != null) { // rand is null when this is used merely for travel time estimation
				// Find the k-shortest path
				YenKShortestPath<Node, RepastEdge<Node>> ksp = new YenKShortestPath<Node, RepastEdge<Node>>(
						transformedNetwork);
				List<GraphPath<Node, RepastEdge<Node>>> kshortestPath = ksp.getPaths(currNode, destNode, K);
				if (kshortestPath == null || kshortestPath.isEmpty()) {
					if (logFailure) logNoPath(currentRoad, destRoad);
					return null;
				}
	
				List<Double> pathLength = new ArrayList<Double>();
				List<Double> pathProb = new ArrayList<Double>();
				List<Double> cumProb = new ArrayList<Double>();
				double total = 0.0;
	
				for (GraphPath<Node, RepastEdge<Node>> kpath : kshortestPath) {
					pathLength.add(kpath.getWeight());
				}
				for (int i = 0; i < kshortestPath.size(); i++) {
					total = total + Math.exp(-theta * pathLength.get(i));
				}
	
				// Calculate the probability
				for (int i = 0; i < kshortestPath.size(); i++) {
					double prob = Math.exp(-theta * pathLength.get(i)) / total;
					pathProb.add(prob);
					if (i == 0)
						cumProb.add(i, prob);
					else
						cumProb.add(i, cumProb.get(i - 1) + prob);
				}
	
				// Find the path to go
				int k = 0;
				double random = rand.nextDouble();
				for (int i = 0; i < kshortestPath.size(); i++) {
					if (random < cumProb.get(i)) {
						k = i;
						break;
					}
				}
				shortestPath = kshortestPath.get(k).getEdgeList();
	
			} else { // Single shortest path
				CachedShortestPath cachedPath = getOrComputeDeterministicPath(currNode, destNode);
				if (cachedPath != null) {
					if (cachedPath.intermediateRoadIDs == null) {
						if (logFailure) logNoPath(currentRoad, destRoad);
						return null;
					}
					return materializeCachedPath(currentRoad, destRoad,
							cachedPath.intermediateRoadIDs);
				}
				try {
					BidirectionalDijkstraShortestPath<Node, RepastEdge<Node>> sp = new BidirectionalDijkstraShortestPath<Node, RepastEdge<Node>>(
							transformedNetwork);
					GraphPath<Node, RepastEdge<Node>> graphPath = sp.getPath(currNode, destNode);
					if (graphPath == null) {
						if (logFailure) logNoPath(currentRoad, destRoad);
						return null;
					}
					shortestPath = graphPath.getEdgeList();
				}
				catch(Exception e) {
					if (logFailure) {
						ContextCreator.logger.warn("Routing engine error between " + roadLabel(currentRoad) + ", "
								+ roadLabel(destRoad) + ": " + e.getMessage());
					}
				}
			}
	
			// Find the roads which are associated with these edges
			if (shortestPath != null) { // Found the shortest path
				roadPath_ = new ArrayList<Road>();
				roadPath_.add(currentRoad);
				for (RepastEdge<Node> edge : shortestPath) {
					int roadID = ContextCreator.getCityContext().getRoadIDFromEdge(edge);
					if(roadID >= 0) {
						roadPath_.add(ContextCreator.getRoadContext().get(roadID));
					}
				}
				roadPath_.add(destRoad);
			}
		}
		return roadPath_;
	}

	private CachedShortestPath getOrComputeDeterministicPath(Node currNode, Node destNode) {
		long graphVersion = SHARED_GRAPH_VERSION.get();
		DeterministicRouteCache cache = deterministicRouteCacheFor(graphVersion);
		// Dijkstra depends only on the two graph nodes. Current and destination roads
		// are materialized around the cached intermediate road IDs after the lookup.
		RouteCacheKey key = new RouteCacheKey(currNode, destNode);
		CachedShortestPath cached = cache.paths.get(key);
		if (cached != null) return cached;
		if (cache.paths.size() >= MAX_DETERMINISTIC_ROUTE_CACHE_ENTRIES) {
			return computeDeterministicPath(currNode, destNode);
		}

		CompletableFuture<CachedShortestPath> pending = new CompletableFuture<CachedShortestPath>();
		CompletableFuture<CachedShortestPath> existing = cache.inFlight.putIfAbsent(key, pending);
		if (existing != null) return existing.join();
		try {
			// Keep the expensive graph traversal outside a ConcurrentHashMap bin lock.
			CachedShortestPath computed = computeDeterministicPath(currNode, destNode);
			if (computed != null && cache.paths.size() < MAX_DETERMINISTIC_ROUTE_CACHE_ENTRIES) {
				CachedShortestPath raced = cache.paths.putIfAbsent(key, computed);
				if (raced != null) computed = raced;
			}
			pending.complete(computed);
			return computed;
		} catch (RuntimeException | Error failure) {
			pending.completeExceptionally(failure);
			throw failure;
		} finally {
			cache.inFlight.remove(key, pending);
		}
	}

	private DeterministicRouteCache deterministicRouteCacheFor(long graphVersion) {
		DeterministicRouteCache cache = this.deterministicRouteCache;
		if (cache.graphVersion == graphVersion) return cache;
		synchronized (this.deterministicRouteCacheLock) {
			cache = this.deterministicRouteCache;
			if (cache.graphVersion != graphVersion) {
				cache = new DeterministicRouteCache(graphVersion);
				this.deterministicRouteCache = cache;
			}
			return cache;
		}
	}

	private CachedShortestPath computeDeterministicPath(Node currNode, Node destNode) {
		GraphPath<Node, RepastEdge<Node>> graphPath;
		try {
			BidirectionalDijkstraShortestPath<Node, RepastEdge<Node>> sp =
					new BidirectionalDijkstraShortestPath<Node, RepastEdge<Node>>(
							this.transformedNetwork);
			graphPath = sp.getPath(currNode, destNode);
		} catch (Exception e) {
			// Preserve the existing error path and logging by falling through to the
			// uncached computation in computeRouteLocked.
			return null;
		}
		if (graphPath == null) return CachedShortestPath.noPath();

		List<RepastEdge<Node>> edges = graphPath.getEdgeList();
		int[] roadIDs = new int[edges.size()];
		int roadCount = 0;
		for (RepastEdge<Node> edge : edges) {
			int roadID = ContextCreator.getCityContext().getRoadIDFromEdge(edge);
			if (roadID >= 0) roadIDs[roadCount++] = roadID;
		}
		if (roadCount != roadIDs.length) roadIDs = Arrays.copyOf(roadIDs, roadCount);
		return CachedShortestPath.found(roadIDs);
	}

	private List<Road> materializeCachedPath(Road currentRoad, Road destRoad,
			int[] intermediateRoadIDs) {
		ArrayList<Road> roadPath = new ArrayList<Road>(intermediateRoadIDs.length + 2);
		roadPath.add(currentRoad);
		RoadContext roadContext = ContextCreator.getRoadContext();
		for (int roadID : intermediateRoadIDs) roadPath.add(roadContext.get(roadID));
		roadPath.add(destRoad);
		return roadPath;
	}

	private void logNoPath(Road currentRoad, Road destRoad) {
		ContextCreator.logger.warn("No routing path between " + roadLabel(currentRoad) + ", "
				+ roadLabel(destRoad) + " originAllowed=" + currentRoad.canBeOrigin()
				+ " destAllowed=" + destRoad.canBeDest());
	}

	private static final class DeterministicRouteCache {
		final long graphVersion;
		final ConcurrentHashMap<RouteCacheKey, CachedShortestPath> paths =
				new ConcurrentHashMap<RouteCacheKey, CachedShortestPath>(4096);
		final ConcurrentHashMap<RouteCacheKey, CompletableFuture<CachedShortestPath>> inFlight =
				new ConcurrentHashMap<RouteCacheKey, CompletableFuture<CachedShortestPath>>(64);

		DeterministicRouteCache(long graphVersion) {
			this.graphVersion = graphVersion;
		}
	}

	private static final class RouteCacheKey {
		final Node currNode;
		final Node destNode;
		final int hashCode;

		RouteCacheKey(Node currNode, Node destNode) {
			this.currNode = currNode;
			this.destNode = destNode;
			int hash = System.identityHashCode(currNode);
			hash = 31 * hash + System.identityHashCode(destNode);
			this.hashCode = hash;
		}

		@Override
		public int hashCode() {
			return this.hashCode;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof RouteCacheKey)) return false;
			RouteCacheKey key = (RouteCacheKey) other;
			return this.currNode == key.currNode
					&& this.destNode == key.destNode;
		}
	}

	private static final class CachedShortestPath {
		final int[] intermediateRoadIDs;

		private CachedShortestPath(int[] intermediateRoadIDs) {
			this.intermediateRoadIDs = intermediateRoadIDs;
		}

		static CachedShortestPath found(int[] intermediateRoadIDs) {
			return new CachedShortestPath(intermediateRoadIDs);
		}

		static CachedShortestPath noPath() {
			return new CachedShortestPath(null);
		}
	}

	private String roadLabel(Road road) {
		if (road == null) return "null";
		return road.getOrigID() + "(" + road.getID() + ")";
	}
}
