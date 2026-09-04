package mets_r.facility;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.mobility.Vehicle;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.ShapefileLoader;

/**
 * Inherit from A-RESCUE
 **/

public class RoadContext extends FacilityContext<Road> {
	/** Bump when persisted connector identities or topology semantics change. */
	public static final int CONNECTOR_TOPOLOGY_SCHEMA_VERSION = 6;
	private static final double EARTH_RADIUS_METERS = 6371008.8;
	private static final double INTERSECTION_CLEARANCE_MARGIN_METERS = 0.25;
	private static final int FIRST_CONNECTOR_INTERNAL_ID = -2;

	private ConcurrentHashMap<Integer, Boolean> activeRoadIDs;
	private ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>> enteringVehicleRoadIDs;
	private AtomicLong activeRoadMarkVersion;
	private final HashMap<Long, Integer> connectorIDByMovement;
	private int nextConnectorInternalID;
	private volatile ConnectorTopology connectorTopology;
	private final ConcurrentHashMap<Integer, Boolean> activeIntersectionIDs;
	private final ConcurrentHashMap<Integer, Long> dirtyConnectorTravelTimeVersions;
	private final AtomicLong connectorTravelTimeDirtyVersion;
	private final ReentrantReadWriteLock connectorTopologyLock;
	private final java.util.concurrent.ConcurrentSkipListMap<Integer, Road>
			travelTimeEstimatorRoads;
	private final ConcurrentHashMap<Integer, Long> routingMetricRoadVersions;
	private final AtomicLong routingMetricVersion;
	private final AtomicLong physicalTopologyVersion;
	private volatile boolean routingMetricTrackingEnabled;
	
	public RoadContext() {
		super("RoadContext");
		this.activeRoadIDs = new ConcurrentHashMap<Integer, Boolean>();
		this.enteringVehicleRoadIDs = new ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>>();
		this.activeRoadMarkVersion = new AtomicLong(0);
		this.connectorIDByMovement = new HashMap<Long, Integer>();
		this.nextConnectorInternalID = FIRST_CONNECTOR_INTERNAL_ID;
		this.connectorTopology = ConnectorTopology.empty();
		this.activeIntersectionIDs = new ConcurrentHashMap<Integer, Boolean>();
		this.dirtyConnectorTravelTimeVersions = new ConcurrentHashMap<Integer, Long>();
		this.connectorTravelTimeDirtyVersion = new AtomicLong(0L);
		this.connectorTopologyLock = new ReentrantReadWriteLock();
		this.travelTimeEstimatorRoads =
				new java.util.concurrent.ConcurrentSkipListMap<Integer, Road>();
		this.routingMetricRoadVersions = new ConcurrentHashMap<Integer, Long>();
		this.routingMetricVersion = new AtomicLong(0L);
		this.physicalTopologyVersion = new AtomicLong(0L);
		this.routingMetricTrackingEnabled = false;
		ContextCreator.logger.info("RoadContext creation");
		/*
		 * GIS projection for spatial information about Roads. This is used to then
		 * create junctions and finally the road network.
		 */
		GeographyParameters<Road> geoParams = new GeographyParameters<Road>();
		Geography<Road> roadGeography = GeographyFactoryFinder.createGeographyFactory(null)
				.createGeography("RoadGeography", this, geoParams);

		/* Read in the data and add to the context and geography */
		File roadFile = null;
		ShapefileLoader<Road> roadLoader = null;

		/* CSV or xodr file for data attribute */
		String fileName = GlobalVariables.ROADS_CSV;
		if(GlobalVariables.NETWORK_FILE.length() > 0){
			fileName = GlobalVariables.NETWORK_FILE;
		}
		
		
		if(fileName.endsWith(".csv")) {
			// File class needed to turn stringName to actual file
			try {
				roadFile = new File(GlobalVariables.ROADS_SHAPEFILE);
				URI uri = roadFile.toURI();
				roadLoader = new ShapefileLoader<Road>(Road.class, uri.toURL(), roadGeography, this);
				BufferedReader br = new BufferedReader(new FileReader(fileName));
				String line = br.readLine();
				String[] result = line.split(",");
				if(result.length < 19) {
					ContextCreator.logger.error("Missing fields in Road configuration, a proper one should contain (LinkID, (unused) LaneNum, TLinkID, FnJunction, TNJunction, Left, Through, Right, (optional) Lane1 - Lane 9), length");
				}
				while (roadLoader.hasNext()) {
					line = br.readLine();
					result = line.split(",");
					Road road = roadLoader.nextWithArgs(Integer.parseInt(result[0]));
					road = setAttribute(road, result);
					road.setCoords(roadGeography.getGeometry(road).getCoordinates());
					this.put(road.getID(), road);
				}
				br.close();
			} catch (java.net.MalformedURLException e) {
				ContextCreator.logger.info(
						"ContextCreator: malformed URL exception when reading roadshapefile. Check the 'roadLoc' parameter is correct");
				e.printStackTrace();
			} catch (FileNotFoundException e) {
				ContextCreator.logger.info("ContextCreator: No road csv file found");
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} 
		}
		else {
			SumoXML sxml = SumoXML.getData(fileName);
			GeometryFactory geomFac = new GeometryFactory();
			for (Road r : sxml.getRoad().values()) {
				this.put(r.getID(), r);
				roadGeography.move(r, geomFac.createLineString(r.getCoords().toArray(new Coordinate[r.getCoords().size()])));
			}
		}
	}

	@Override
	public void put(int id, Road road) {
		super.put(id, road);
		if (road != null && !(road instanceof ConnectorRoad)) {
			this.physicalTopologyVersion.incrementAndGet();
			this.routingMetricVersion.incrementAndGet();
		}
	}

	public Road setAttribute(Road r, String[] att) {
		if(Integer.parseInt(att[6])!=0)
			r.addDownStreamRoad(Integer.parseInt(att[6]));
		if(Integer.parseInt(att[7])!=0)
			r.addDownStreamRoad(Integer.parseInt(att[7]));
		if(Integer.parseInt(att[8])!=0)
			r.addDownStreamRoad(Integer.parseInt(att[8]));
		if(Integer.parseInt(att[3])!=0)
			r.addDownStreamRoad(Integer.parseInt(att[3]));
		r.setRoadType((int)Double.parseDouble(att[2]));
		r.setUpStreamJunction(Integer.parseInt(att[4]));
		r.setDownStreamJunction(Integer.parseInt(att[5]));
		r.setLength(Double.parseDouble(att[18]));
		return r;
	}
	
	public List<String> getOrigIDList(){
		List<String> facilityIDList = new ArrayList<String>();
		for(Road r: this.getAll()) {
			facilityIDList.add(r.getOrigID());
		}	
		return facilityIDList;
	}

	@Override
	public Road get(int id) {
		Road physicalRoad = super.get(id);
		return physicalRoad != null ? physicalRoad
				: this.connectorTopology.connectorsByInternalID.get(id);
	}

	public List<Road> getAllSteppableRoads() {
		ArrayList<Road> roads = new ArrayList<Road>(super.getAll());
		roads.addAll(this.connectorTopology.connectorsByInternalID.values());
		roads.sort(Comparator.comparingInt(Road::getID));
		return roads;
	}

	/**
	 * Rebuild the queryable connector-road topology after junction movements and
	 * lane turning curves have been initialized. Physical roads remain in the
	 * FacilityContext routing dictionary; connector roads are published atomically
	 * in a separate topology and exposed together with physical roads only to the
	 * segment scheduler.
	 */
	public synchronized void rebuildConnectorTopology() {
		this.connectorTopologyLock.writeLock().lock();
		try {
		ConnectorTopology previous = this.connectorTopology;
		if (ContextCreator.getJunctionContext() == null) {
			for (ConnectorRoad connector : previous.connectorsByMovement.values()) {
				connector.clearRuntimeState();
			}
			for (IntersectionRuntime runtime : previous.intersectionRuntimes.values()) {
				runtime.clearRuntimeState();
			}
			this.connectorTopology = ConnectorTopology.empty();
			this.activeIntersectionIDs.clear();
			this.dirtyConnectorTravelTimeVersions.clear();
			return;
		}

		ArrayList<Junction> junctions =
				new ArrayList<Junction>(ContextCreator.getJunctionContext().getAll());
		junctions.sort(Comparator.comparingInt(Junction::getID));

		HashSet<String> physicalOrigIDs = new HashSet<String>();
		for (Road road : this.getAll()) {
			if (road != null && road.getOrigID() != null) physicalOrigIDs.add(road.getOrigID());
		}

		LinkedHashMap<Long, ConnectorRoad> connectorsByMovement =
				new LinkedHashMap<Long, ConnectorRoad>();
		LinkedHashMap<Integer, ConnectorRoad> connectorsByInternalID =
				new LinkedHashMap<Integer, ConnectorRoad>();
		LinkedHashMap<String, ConnectorRoad> connectorsByOrigID =
				new LinkedHashMap<String, ConnectorRoad>();
		LinkedHashMap<String, ConnectorRoad> connectorsByAlias =
				new LinkedHashMap<String, ConnectorRoad>();
		LinkedHashMap<Integer, LinkedHashSet<ConnectorRoad>> mutableByIntersection =
				new LinkedHashMap<Integer, LinkedHashSet<ConnectorRoad>>();
		LinkedHashMap<Integer, Integer> intersectionByConnectorID =
				new LinkedHashMap<Integer, Integer>();
		int explicitConnectorCount = 0;

		for (Junction junction : junctions) {
			if (junction == null) continue;
			ArrayList<Integer> sourceIDs = new ArrayList<Integer>(junction.getUpStreamRoads());
			Collections.sort(sourceIDs);
			for (Integer sourceID : sourceIDs) {
				Road sourceRoad = sourceID == null ? null : this.get(sourceID.intValue());
				if (sourceRoad == null) continue;
				ArrayList<Integer> targetIDs =
						new ArrayList<Integer>(sourceRoad.getDownStreamRoads());
				Collections.sort(targetIDs);
				for (Integer targetID : targetIDs) {
					Road targetRoad = targetID == null ? null : this.get(targetID.intValue());
					if (targetRoad == null
							|| targetRoad.getUpStreamJunction() != junction.getID()
							|| sourceRoad.getDownStreamJunction() != junction.getID()) {
						continue;
					}
					long movementKey = movementKey(sourceRoad.getID(), targetRoad.getID());
					if (connectorsByMovement.containsKey(movementKey)) continue;

					ConnectorDefinition definition =
							connectorDefinition(sourceRoad, targetRoad);
					String connectorOrigID = definition.origID;
					if (physicalOrigIDs.contains(connectorOrigID)) {
						throw new IllegalStateException("Connector ID " + connectorOrigID
								+ " collides with a physical road original ID");
					}
					LinkedHashSet<String> usableAliases =
							new LinkedHashSet<String>(definition.aliases);
					for (String alias : new ArrayList<String>(usableAliases)) {
						if (physicalOrigIDs.contains(alias) && !connectorOrigID.equals(alias)) {
							ContextCreator.logger.warn("Ignoring connector alias " + alias
									+ " because it collides with a physical road ID");
							usableAliases.remove(alias);
						}
					}
					ConnectorRoad displayCollision = connectorsByAlias.get(connectorOrigID);
					if (displayCollision != null
							&& displayCollision.getMovementKey() != movementKey) {
						throw new IllegalStateException("Ambiguous connector ID "
								+ connectorOrigID + " for movements "
								+ displayCollision.getSourceRoad().getOrigID() + " -> "
								+ displayCollision.getTargetRoad().getOrigID() + " and "
								+ sourceRoad.getOrigID() + " -> " + targetRoad.getOrigID());
					}

					ConnectorRoad connector = previous.connectorsByMovement.get(movementKey);
					if (connector == null
							|| connector.getSourceRoad() != sourceRoad
							|| connector.getTargetRoad() != targetRoad
							|| connector.getIntersectionID() != junction.getID()
							|| !connectorOrigID.equals(connector.getOrigID())
							|| !usableAliases.equals(connector.getAliases())
							|| definition.configuredControlType
									!= connector.getConfiguredControlType()) {
						connector = new ConnectorRoad(connectorInternalID(movementKey),
								movementKey, sourceRoad, targetRoad, junction.getID(), connectorOrigID,
								usableAliases, definition.configuredControlType, definition.paths);
					}
					// Connectors are internal transition state rather than trip endpoints.
					connector.setCanBeOrigin(true);
					connector.setCanBeDest(false);
					if (definition.loadedFromNetXML) explicitConnectorCount++;
					connectorsByMovement.put(movementKey, connector);
					connectorsByInternalID.put(connector.getID(), connector);
					connectorsByOrigID.put(connectorOrigID, connector);
					for (String alias : connector.getAliases()) {
						ConnectorRoad aliasCollision = connectorsByAlias.get(alias);
						if (aliasCollision != null && aliasCollision != connector) {
							throw new IllegalStateException("Ambiguous connector alias " + alias
									+ " for movements "
									+ aliasCollision.getSourceRoad().getOrigID() + " -> "
									+ aliasCollision.getTargetRoad().getOrigID() + " and "
									+ sourceRoad.getOrigID() + " -> " + targetRoad.getOrigID());
						}
						connectorsByAlias.put(alias, connector);
					}
					connectorsByAlias.put(connectorOrigID, connector);
					intersectionByConnectorID.put(connector.getID(), junction.getID());
					mutableByIntersection.computeIfAbsent(junction.getID(),
							id -> new LinkedHashSet<ConnectorRoad>()).add(connector);
				}
			}
		}

		LinkedHashMap<Integer, Set<ConnectorRoad>> connectorsByIntersection =
				new LinkedHashMap<Integer, Set<ConnectorRoad>>();
		LinkedHashMap<Integer, IntersectionRuntime> intersectionRuntimes =
				new LinkedHashMap<Integer, IntersectionRuntime>();
		for (Junction junction : junctions) {
			LinkedHashSet<ConnectorRoad> connectorSet =
					mutableByIntersection.get(junction.getID());
			if (connectorSet == null || connectorSet.isEmpty()) continue;
			ArrayList<ConnectorRoad> connectors = new ArrayList<ConnectorRoad>(connectorSet);
			connectors.sort(Comparator.comparingInt(ConnectorRoad::getID));
			initializeConnectorConflicts(connectors, junction.getCoord());
			LinkedHashSet<ConnectorRoad> ordered =
					new LinkedHashSet<ConnectorRoad>(connectors);
			Set<ConnectorRoad> immutable = Collections.unmodifiableSet(ordered);
			connectorsByIntersection.put(junction.getID(), immutable);
			IntersectionRuntime previousRuntime =
					previous.intersectionRuntimes.get(junction.getID());
			IntersectionRuntime runtime = previousRuntime != null
					&& previousRuntime.matchesTopology(junction.getCoord(), immutable)
							? previousRuntime
							: new IntersectionRuntime(junction.getID(), junction.getCoord(),
									immutable, previousRuntime);
			intersectionRuntimes.put(junction.getID(), runtime);
		}

		this.connectorTopology = new ConnectorTopology(connectorsByMovement,
				connectorsByInternalID, connectorsByOrigID, connectorsByAlias,
				intersectionByConnectorID, connectorsByIntersection, intersectionRuntimes);
		this.dirtyConnectorTravelTimeVersions.keySet().removeIf(
				connectorID -> !connectorsByInternalID.containsKey(connectorID));
		if (ContextCreator.partitioner != null) ContextCreator.partitioner.run();
		rebuildActiveIntersectionIndexes();
		double defaultConnectorGap = 1.2 * GlobalVariables.DEFAULT_VEHICLE_LENGTH;
		int connectorPathCount = 0;
		int clearPathAdmissionCount = 0;
		int zeroLengthPathCount = 0;
		for (ConnectorRoad connector : connectorsByMovement.values()) {
			for (ConnectorRoad.ConnectorPath path : connector.getPaths()) {
				connectorPathCount++;
				Lane connectorLane = connector.getLane(path);
				double pathLength = connectorLane == null
						? Double.NaN : connectorLane.getLength();
				if (connector.requiresClearPathAdmission(path, defaultConnectorGap)) {
					clearPathAdmissionCount++;
				}
				if (!Double.isFinite(pathLength) || pathLength <= 1.0e-9) {
					zeroLengthPathCount++;
				}
			}
		}
		ContextCreator.logger.info("Initialized " + connectorsByMovement.size()
				+ " connector roads across " + connectorsByIntersection.size()
				+ " intersections; " + explicitConnectorCount
				+ " use SUMO net.xml connector definitions; " + clearPathAdmissionCount
				+ " of " + connectorPathCount + " paths are shorter than the default "
				+ defaultConnectorGap + " m entry gap and use clear-path admission; "
				+ zeroLengthPathCount + " are zero-length.");
		} finally {
			this.connectorTopologyLock.writeLock().unlock();
		}
	}

	private int connectorInternalID(long movementKey) {
		Integer existing = this.connectorIDByMovement.get(movementKey);
		if (existing != null) return existing.intValue();
		int connectorID = this.nextConnectorInternalID;
		while (connectorID == -1 || this.connectorTopology.connectorsByInternalID
				.containsKey(connectorID)) {
			connectorID--;
		}
		this.connectorIDByMovement.put(movementKey, connectorID);
		this.nextConnectorInternalID = connectorID == Integer.MIN_VALUE
				? FIRST_CONNECTOR_INTERNAL_ID : connectorID - 1;
		return connectorID;
	}

	public static long movementKey(int sourceRoadID, int targetRoadID) {
		return ((long) sourceRoadID << 32) ^ (targetRoadID & 0xffffffffL);
	}

	private ConnectorDefinition connectorDefinition(Road sourceRoad, Road targetRoad) {
		String inferredID = ConnectorRoad.buildOrigID(
				sourceRoad.getOrigID(), targetRoad.getOrigID());
		ArrayList<SumoXML.ConnectorPathData> explicitData =
				new ArrayList<SumoXML.ConnectorPathData>();
		String networkFile = GlobalVariables.NETWORK_FILE;
		if (networkFile != null && networkFile.toLowerCase().endsWith(".xml")) {
			explicitData.addAll(SumoXML.getData(networkFile).getConnectorPaths(
					sourceRoad.getID(), targetRoad.getID()));
		}
		if (explicitData.isEmpty()) {
			LinkedHashSet<String> aliases = new LinkedHashSet<String>();
			aliases.add(inferredID);
			return new ConnectorDefinition(inferredID, aliases,
					inferredConnectorPaths(sourceRoad, targetRoad), false);
		}

		ArrayList<ConnectorRoad.ConnectorPath> paths =
				new ArrayList<ConnectorRoad.ConnectorPath>();
		LinkedHashSet<String> aliases = new LinkedHashSet<String>();
		int configuredControlType = Road.NONE_OF_THE_ABOVE;
		aliases.add(inferredID);
		for (SumoXML.ConnectorPathData data : explicitData) {
			Lane sourceLane = ContextCreator.getLaneContext().get(data.getSourceLaneID());
			Lane targetLane = ContextCreator.getLaneContext().get(data.getTargetLaneID());
			if (sourceLane == null || targetLane == null
					|| sourceLane.getRoad() != sourceRoad
					|| targetLane.getRoad() != targetRoad) continue;
			ArrayList<Coordinate> line = data.hasExplicitGeometry()
					? new ArrayList<Coordinate>(data.getCenterLine())
					: inferredConnectorLine(sourceLane, targetLane);
			paths.add(new ConnectorRoad.ConnectorPath(sourceLane, targetLane, line,
					data.getViaLaneIDs(), data.getInternalEdgeIDs(),
					data.getParameters(), data.getDirection(),
					data.getState(), data.getTrafficLightID(), data.getLinkIndex(),
					data.getDeclaredLength(), data.getSpeed(), data.hasExplicitGeometry()));
			aliases.addAll(data.getViaLaneIDs());
			aliases.addAll(data.getInternalEdgeIDs());
			String configuredConnectorID = normalizedConnectorMetadata(
					data.getParameter("metsr.connectorId"));
			if (configuredConnectorID != null && !inferredID.equals(configuredConnectorID)) {
				throw new IllegalStateException("SUMO movement " + sourceRoad.getOrigID()
						+ " -> " + targetRoad.getOrigID() + " declares connector ID "
						+ configuredConnectorID + " but the required ID is " + inferredID);
			}
			String carlaSegmentID = normalizedConnectorMetadata(
					data.getParameter("carla.segmentId"));
			if (carlaSegmentID != null) aliases.add(carlaSegmentID);
			String controlType = normalizedConnectorMetadata(
					data.getParameter("metsr.controlType"));
			if (controlType != null && ("COSIM".equalsIgnoreCase(controlType)
					|| Integer.toString(Road.COSIM).equals(controlType))) {
				configuredControlType = Road.COSIM;
			}
		}
		if (paths.isEmpty()) {
			return new ConnectorDefinition(inferredID, aliases,
					inferredConnectorPaths(sourceRoad, targetRoad), false);
		}
		return new ConnectorDefinition(inferredID, aliases, paths,
				configuredControlType, true);
	}

	private String normalizedConnectorMetadata(String value) {
		if (value == null) return null;
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private ArrayList<Coordinate> inferredConnectorLine(Lane sourceLane,
			Lane targetLane) {
		ArrayList<Coordinate> line = new ArrayList<Coordinate>();
		addDistinctCoordinate(line, sourceLane.getEndCoord());
		for (Coordinate coordinate : sourceLane.getTurningCoords(targetLane.getID())) {
			addDistinctCoordinate(line, coordinate);
		}
		addDistinctCoordinate(line, targetLane.getStartCoord());
		ensureConnectorWaypointPair(line);
		return line;
	}

	private List<ConnectorRoad.ConnectorPath> inferredConnectorPaths(
			Road sourceRoad, Road targetRoad) {
		ArrayList<ConnectorRoad.ConnectorPath> paths =
				new ArrayList<ConnectorRoad.ConnectorPath>();
		ArrayList<Lane> sourceLanes = new ArrayList<Lane>(sourceRoad.getLanes());
		ArrayList<Lane> targetLanes = new ArrayList<Lane>(targetRoad.getLanes());
		sourceLanes.sort(Comparator.comparingInt(Lane::getID));
		targetLanes.sort(Comparator.comparingInt(Lane::getID));
		for (Lane sourceLane : sourceLanes) {
			for (Lane targetLane : targetLanes) {
				if (!sourceLane.getDownStreamLanes().contains(targetLane.getID())) continue;
				ArrayList<Coordinate> line = inferredConnectorLine(sourceLane, targetLane);
				paths.add(new ConnectorRoad.ConnectorPath(sourceLane, targetLane, line));
			}
		}
		if (paths.isEmpty()) {
			ArrayList<Coordinate> fallback = new ArrayList<Coordinate>();
			addDistinctCoordinate(fallback, sourceRoad.getEndCoord());
			addDistinctCoordinate(fallback, targetRoad.getStartCoord());
			ensureConnectorWaypointPair(fallback);
			paths.add(new ConnectorRoad.ConnectorPath(sourceRoad.firstLane(),
					targetRoad.firstLane(), fallback));
		}
		return paths;
	}

	private static final class ConnectorDefinition {
		final String origID;
		final Set<String> aliases;
		final List<ConnectorRoad.ConnectorPath> paths;
		final int configuredControlType;
		final boolean loadedFromNetXML;

		ConnectorDefinition(String origID, Set<String> aliases,
				List<ConnectorRoad.ConnectorPath> paths, boolean loadedFromNetXML) {
			this(origID, aliases, paths, Road.NONE_OF_THE_ABOVE, loadedFromNetXML);
		}

		ConnectorDefinition(String origID, Set<String> aliases,
				List<ConnectorRoad.ConnectorPath> paths, int configuredControlType,
				boolean loadedFromNetXML) {
			this.origID = origID;
			this.aliases = Collections.unmodifiableSet(
					new LinkedHashSet<String>(aliases));
			this.paths = Collections.unmodifiableList(
					new ArrayList<ConnectorRoad.ConnectorPath>(paths));
			this.configuredControlType = configuredControlType;
			this.loadedFromNetXML = loadedFromNetXML;
		}
	}

	private void addDistinctCoordinate(ArrayList<Coordinate> line, Coordinate coordinate) {
		if (coordinate == null) return;
		Coordinate copy = new Coordinate(coordinate.x, coordinate.y,
				Double.isNaN(coordinate.z) ? 0.0 : coordinate.z);
		if (!line.isEmpty()) {
			Coordinate previous = line.get(line.size() - 1);
			double dx = previous.x - copy.x;
			double dy = previous.y - copy.y;
			double dz = previous.z - copy.z;
			if (dx * dx + dy * dy + dz * dz <= 1.0e-24) return;
		}
		line.add(copy);
	}

	private void ensureConnectorWaypointPair(ArrayList<Coordinate> line) {
		if (line != null && line.size() == 1) {
			// Vehicle lane traversal needs an endpoint waypoint even when the
			// connector has no spatial extent. A deliberate duplicate preserves
			// the zero length while retaining the source/target lane pairing.
			line.add(new Coordinate(line.get(0)));
		}
	}

	private void initializeConnectorConflicts(List<ConnectorRoad> connectors,
			Coordinate intersectionCoordinate) {
		HashMap<Integer, LinkedHashSet<Integer>> conflictsByConnector =
				new HashMap<Integer, LinkedHashSet<Integer>>();
		for (ConnectorRoad connector : connectors) {
			LinkedHashSet<Integer> conflicts = new LinkedHashSet<Integer>();
			conflictsByConnector.put(connector.getID(), conflicts);
		}
		if (!GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) {
			for (ConnectorRoad connector : connectors) {
				connector.setConflictingConnectorIDs(
						conflictsByConnector.get(connector.getID()));
			}
			return;
		}
		double clearance = Math.max(0.0, GlobalVariables.DEFAULT_VEHICLE_WIDTH)
				+ 2.0 * INTERSECTION_CLEARANCE_MARGIN_METERS;
		for (int i = 0; i < connectors.size(); i++) {
			ConnectorRoad first = connectors.get(i);
			for (int j = i + 1; j < connectors.size(); j++) {
				ConnectorRoad second = connectors.get(j);
				if (!connectorPathsConflict(first, second, intersectionCoordinate, clearance)) {
					continue;
				}
				conflictsByConnector.get(first.getID()).add(second.getID());
				conflictsByConnector.get(second.getID()).add(first.getID());
			}
		}
		for (ConnectorRoad connector : connectors) {
			connector.setConflictingConnectorIDs(
					conflictsByConnector.get(connector.getID()));
		}
	}

	private boolean connectorPathsConflict(ConnectorRoad first, ConnectorRoad second,
			Coordinate intersectionCoordinate, double clearanceMeters) {
		Coordinate anchor = intersectionCoordinate;
		if (anchor == null) {
			ArrayList<Coordinate> representative = first.getRepresentativeCenterLine();
			anchor = representative.isEmpty()
					? new Coordinate(0.0, 0.0, 0.0) : representative.get(0);
		}
		for (List<Coordinate> firstLine : first.getCenterLines()) {
			double[][] firstLocal = ConnectorRoad.toLocalMeters(firstLine, anchor);
			for (List<Coordinate> secondLine : second.getCenterLines()) {
				double[][] secondLocal = ConnectorRoad.toLocalMeters(secondLine, anchor);
				if (ConnectorRoad.polylinesConflict(
						firstLocal, secondLocal, clearanceMeters)) return true;
			}
		}
		return false;
	}

	private void rebuildActiveIntersectionIndexes() {
		this.connectorTopologyLock.writeLock().lock();
		try {
			this.activeIntersectionIDs.clear();
			for (Map.Entry<Integer, IntersectionRuntime> entry
					: this.connectorTopology.intersectionRuntimes.entrySet()) {
				IntersectionRuntime runtime = entry.getValue();
				if (runtime.hasWork()) {
					this.activeIntersectionIDs.put(entry.getKey(), Boolean.TRUE);
				}
			}
		} finally {
			this.connectorTopologyLock.writeLock().unlock();
		}
	}

	/** Clear vehicle-scoped connector state before a fast reset or snapshot load. */
	public synchronized void resetConnectorRuntimeState() {
		this.connectorTopologyLock.writeLock().lock();
		try {
		for (ConnectorRoad connector : this.connectorTopology.connectorsByMovement.values()) {
			connector.clearRuntimeState();
		}
		for (IntersectionRuntime runtime : this.connectorTopology.intersectionRuntimes.values()) {
			runtime.clearRuntimeState();
		}
		this.activeIntersectionIDs.clear();
		this.dirtyConnectorTravelTimeVersions.clear();
		} finally {
			this.connectorTopologyLock.writeLock().unlock();
		}
	}

	public ConnectorRoad getConnector(Road sourceRoad, Road targetRoad) {
		if (sourceRoad == null || targetRoad == null) return null;
		return getConnector(sourceRoad.getID(), targetRoad.getID());
	}

	public ConnectorRoad getConnector(int sourceRoadID, int targetRoadID) {
		return this.connectorTopology.connectorsByMovement.get(
				movementKey(sourceRoadID, targetRoadID));
	}

	public ConnectorRoad getConnector(int connectorInternalID) {
		return this.connectorTopology.connectorsByInternalID.get(connectorInternalID);
	}

	public ConnectorRoad getConnector(String connectorOrigID) {
		if (connectorOrigID == null) return null;
		return this.connectorTopology.connectorsByAlias.get(connectorOrigID);
	}

	public Road getQueryableRoad(String origID) {
		if (origID == null) return null;
		ConnectorRoad connector = getConnector(origID);
		if (connector != null) return connector;
		if (ContextCreator.getCityContext() != null) {
			Road indexedRoad = ContextCreator.getCityContext().findRoadWithOrigID(origID);
			if (indexedRoad != null) return indexedRoad;
		}
		for (Road road : this.getAll()) {
			if (road != null && origID.equals(road.getOrigID())) return road;
		}
		return null;
	}

	public List<String> getQueryableOrigIDList() {
		ArrayList<String> result = new ArrayList<String>(getOrigIDList());
		ArrayList<String> connectorIDs =
				new ArrayList<String>(this.connectorTopology.connectorsByOrigID.keySet());
		Collections.sort(connectorIDs);
		result.addAll(connectorIDs);
		return result;
	}

	public List<Integer> getQueryableIDList() {
		ArrayList<Integer> result = new ArrayList<Integer>(getIDList());
		ArrayList<ConnectorRoad> connectors =
				new ArrayList<ConnectorRoad>(this.connectorTopology.connectorsByMovement.values());
		connectors.sort(Comparator.comparing(ConnectorRoad::getOrigID));
		for (ConnectorRoad connector : connectors) result.add(connector.getID());
		return result;
	}

	public List<ConnectorRoad> getAllConnectors() {
		ArrayList<ConnectorRoad> result =
				new ArrayList<ConnectorRoad>(this.connectorTopology.connectorsByMovement.values());
		result.sort(Comparator.comparing(ConnectorRoad::getOrigID));
		return Collections.unmodifiableList(result);
	}

	public List<Road> getCoSimPhysicalRoadsSnapshot() {
		ArrayList<Road> result = new ArrayList<Road>();
		for (Road road : this.getAll()) {
			if (road != null && road.getControlType() == Road.COSIM) result.add(road);
		}
		result.sort(Comparator.comparing(Road::getOrigID));
		return Collections.unmodifiableList(result);
	}

	public List<ConnectorRoad> getCoSimConnectorsSnapshot() {
		ArrayList<ConnectorRoad> result = new ArrayList<ConnectorRoad>();
		for (ConnectorRoad connector : this.connectorTopology.connectorsByMovement.values()) {
			if (connector != null && connector.getControlType() == Road.COSIM) result.add(connector);
		}
		result.sort(Comparator.comparing(ConnectorRoad::getOrigID));
		return Collections.unmodifiableList(result);
	}

	public List<Road> getCoSimSegmentsSnapshot() {
		ArrayList<Road> result = new ArrayList<Road>();
		result.addAll(getCoSimPhysicalRoadsSnapshot());
		result.addAll(getCoSimConnectorsSnapshot());
		result.sort(Comparator.comparing(Road::getOrigID));
		return Collections.unmodifiableList(result);
	}

	public boolean isConnector(Road road) {
		return road instanceof ConnectorRoad
				&& this.connectorTopology.connectorsByInternalID.get(road.getID()) == road;
	}

	public boolean isConnector(int connectorInternalID) {
		return this.connectorTopology.connectorsByInternalID.containsKey(connectorInternalID);
	}

	public Integer getIntersectionIDForConnector(int connectorInternalID) {
		return this.connectorTopology.intersectionByConnectorID.get(connectorInternalID);
	}

	public Junction getIntersectionForConnector(ConnectorRoad connector) {
		if (connector == null || ContextCreator.getJunctionContext() == null) return null;
		Integer intersectionID = getIntersectionIDForConnector(connector.getID());
		return intersectionID == null ? null
				: ContextCreator.getJunctionContext().get(intersectionID.intValue());
	}

	public Set<ConnectorRoad> getConnectorsForIntersection(int intersectionID) {
		Set<ConnectorRoad> connectors =
				this.connectorTopology.connectorsByIntersectionID.get(intersectionID);
		return connectors == null ? Collections.<ConnectorRoad>emptySet() : connectors;
	}

	/**
	 * Register connector membership after the selected ConnectorPath's ordinary
	 * lane-headway check. Short paths use an exclusive clear-path reservation
	 * instead. Both that reservation and optional cross-movement collision gates
	 * are decided while holding the owning intersection's lock.
	 */
	public boolean tryEnterConnector(ConnectorRoad connector,
			ConnectorRoad.ConnectorPath connectorPath, Vehicle vehicle,
			boolean requireClearPath) {
		this.connectorTopologyLock.readLock().lock();
		try {
		if (connector == null || connectorPath == null || vehicle == null
				|| connector.getLane(connectorPath) == null) return false;
		IntersectionRuntime runtime =
				this.connectorTopology.intersectionRuntimes.get(connector.getIntersectionID());
		if (runtime == null) return false;
		int admission = runtime.tryAdmit(connector, connectorPath, vehicle,
				requireClearPath, ContextCreator.getCurrentTick());
		if (admission == IntersectionRuntime.BLOCKED) return false;
		this.activeIntersectionIDs.put(connector.getIntersectionID(), Boolean.TRUE);
		return true;
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	/** Restore a saved admission without applying new-traffic conflict gates. */
	public void restoreConnectorVehicle(ConnectorRoad connector, Vehicle vehicle) {
		this.connectorTopologyLock.readLock().lock();
		try {
			if (connector == null || vehicle == null) {
				throw new IllegalArgumentException("Connector and vehicle are required");
			}
			IntersectionRuntime runtime = this.connectorTopology.intersectionRuntimes
					.get(connector.getIntersectionID());
			if (runtime == null) {
				throw new IllegalStateException("Missing intersection runtime for connector "
						+ connector.getOrigID());
			}
			runtime.restoreAdmission(connector, vehicle, ContextCreator.getCurrentTick());
			this.activeIntersectionIDs.put(connector.getIntersectionID(), Boolean.TRUE);
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	/**
	 * Mirror an externally authoritative connector observation without applying
	 * native admission or conflict gates. CARLA already owns movement at this
	 * boundary; the runtime state is bookkeeping for queries, visualization, and
	 * collision diagnostics only.
	 */
	public void mirrorAuthoritativeConnectorVehicle(ConnectorRoad connector,
			Vehicle vehicle) {
		this.connectorTopologyLock.readLock().lock();
		try {
			if (connector == null || vehicle == null) {
				throw new IllegalArgumentException("Connector and vehicle are required");
			}
			IntersectionRuntime runtime = this.connectorTopology.intersectionRuntimes
					.get(connector.getIntersectionID());
			if (runtime == null) {
				throw new IllegalStateException("Missing intersection runtime for connector "
						+ connector.getOrigID());
			}
			runtime.restoreAdmission(connector, vehicle, ContextCreator.getCurrentTick());
			this.activeIntersectionIDs.put(connector.getIntersectionID(), Boolean.TRUE);
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	/** Rebuild derived counts and collision state after all saved vehicles return. */
	public void finishConnectorStateRestore() {
		rebuildActiveIntersectionIndexes();
		if (GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) {
			for (Integer intersectionID : getActiveIntersectionIDsSnapshot()) {
				processIntersectionState(intersectionID.intValue());
			}
		}
	}

	/** O(1) event-driven state refresh for a vehicle currently on a connector. */
	public void updateConnectorVehicleState(ConnectorRoad connector, Vehicle vehicle) {
		if (!GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) return;
		this.connectorTopologyLock.readLock().lock();
		try {
		if (connector == null || vehicle == null) return;
		IntersectionRuntime runtime =
				this.connectorTopology.intersectionRuntimes.get(connector.getIntersectionID());
		if (runtime != null) {
			runtime.update(connector, vehicle, ContextCreator.getCurrentTick());
		}
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	/** Release connector occupancy when the vehicle leaves the connector segment. */
	public void leaveConnector(ConnectorRoad connector, Vehicle vehicle) {
		this.connectorTopologyLock.readLock().lock();
		try {
		if (connector == null || vehicle == null) return;
		IntersectionRuntime runtime =
				this.connectorTopology.intersectionRuntimes.get(connector.getIntersectionID());
		if (runtime == null) return;
		boolean released = runtime.release(connector, vehicle,
				vehicle.getCurrentConnector() == connector);
		if (!released) return;
		if (!runtime.hasWork()) {
			this.activeIntersectionIDs.remove(connector.getIntersectionID(), Boolean.TRUE);
			if (runtime.hasWork()) {
				this.activeIntersectionIDs.put(connector.getIntersectionID(), Boolean.TRUE);
			}
		}
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	public int getQueryableVehicleCount(Road road) {
		this.connectorTopologyLock.readLock().lock();
		try {
			if (road == null) return 0;
			if (road instanceof ConnectorRoad) return ((ConnectorRoad) road).getVehicleNum();
			return road.getVehicleNum();
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	public boolean isConnectorActive(ConnectorRoad connector) {
		return connector != null && connector.hasActiveVehicles();
	}

	public List<Integer> getActiveIntersectionIDsSnapshot() {
		ArrayList<Integer> result =
				new ArrayList<Integer>(this.activeIntersectionIDs.keySet());
		Collections.sort(result);
		return result;
	}

	public List<ConnectorRoad> getActiveConnectorsSnapshot() {
		this.connectorTopologyLock.readLock().lock();
		try {
		ArrayList<ConnectorRoad> result = new ArrayList<ConnectorRoad>();
		for (Integer intersectionID : getActiveIntersectionIDsSnapshot()) {
			for (ConnectorRoad connector : getConnectorsForIntersection(intersectionID)) {
				if (connector.getVehicleNum() > 0) result.add(connector);
			}
		}
		result.sort(Comparator.comparing(ConnectorRoad::getOrigID));
		return result;
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	/** Mark one connector for estimator refresh without scanning the full topology. */
	void markConnectorTravelTimeDirty(ConnectorRoad connector) {
		if (connector == null || this.connectorTopology.connectorsByInternalID
				.get(connector.getID()) != connector) return;
		long version = this.connectorTravelTimeDirtyVersion.incrementAndGet();
		this.dirtyConnectorTravelTimeVersions.put(connector.getID(), Long.valueOf(version));
	}

	/**
	 * Capture versioned dirty work. A newer observation replacing a captured version
	 * cannot be removed by acknowledgement of the older refresh.
	 */
	List<ConnectorTravelTimeRefresh> getDirtyConnectorTravelTimeSnapshot() {
		this.connectorTopologyLock.readLock().lock();
		try {
			ArrayList<ConnectorTravelTimeRefresh> result =
					new ArrayList<ConnectorTravelTimeRefresh>();
			for (Map.Entry<Integer, Long> entry
					: this.dirtyConnectorTravelTimeVersions.entrySet()) {
				ConnectorRoad connector = this.connectorTopology.connectorsByInternalID
						.get(entry.getKey());
				if (connector == null) {
					this.dirtyConnectorTravelTimeVersions.remove(
							entry.getKey(), entry.getValue());
					continue;
				}
				result.add(new ConnectorTravelTimeRefresh(
						connector, entry.getValue().longValue()));
			}
			result.sort(Comparator.comparing(
					(ConnectorTravelTimeRefresh refresh) -> refresh.connector.getOrigID())
					.thenComparingInt(refresh -> refresh.connector.getID()));
			return result;
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	void acknowledgeConnectorTravelTimeRefresh(ConnectorTravelTimeRefresh refresh) {
		if (refresh == null) return;
		this.dirtyConnectorTravelTimeVersions.remove(refresh.connector.getID(),
				Long.valueOf(refresh.version));
	}

	static final class ConnectorTravelTimeRefresh {
		final ConnectorRoad connector;
		final long version;

		ConnectorTravelTimeRefresh(ConnectorRoad connector, long version) {
			this.connector = connector;
			this.version = version;
		}
	}

	public ArrayList<ArrayList<Integer>> getActiveIntersectionPartitions(int partitionCount) {
		this.connectorTopologyLock.readLock().lock();
		try {
		int count = Math.max(1, partitionCount);
		ArrayList<ArrayList<Integer>> partitions = new ArrayList<ArrayList<Integer>>(count);
		for (int i = 0; i < count; i++) partitions.add(new ArrayList<Integer>());
		List<Integer> intersections = getActiveIntersectionIDsSnapshot();
		intersections.sort((left, right) -> {
			IntersectionRuntime leftRuntime =
					this.connectorTopology.intersectionRuntimes.get(left);
			IntersectionRuntime rightRuntime =
					this.connectorTopology.intersectionRuntimes.get(right);
			int loadCompare = Integer.compare(
					rightRuntime == null ? 0 : rightRuntime.getVehicleCount(),
					leftRuntime == null ? 0 : leftRuntime.getVehicleCount());
			return loadCompare != 0 ? loadCompare : Integer.compare(left, right);
		});
		int[] loads = new int[count];
		for (Integer intersectionID : intersections) {
			int selected = 0;
			for (int i = 1; i < count; i++) {
				if (loads[i] < loads[selected]) selected = i;
			}
			partitions.get(selected).add(intersectionID);
			IntersectionRuntime runtime =
					this.connectorTopology.intersectionRuntimes.get(intersectionID);
			loads[selected] += Math.max(1, runtime == null ? 0 : runtime.getVehicleCount());
		}
		for (ArrayList<Integer> partition : partitions) Collections.sort(partition);
		return partitions;
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	public void processIntersectionState(int intersectionID) {
		this.connectorTopologyLock.readLock().lock();
		try {
		IntersectionRuntime runtime =
				this.connectorTopology.intersectionRuntimes.get(intersectionID);
		if (runtime != null) {
			runtime.processCollisionCheck(ContextCreator.getCurrentTick());
			if (!runtime.hasWork()) {
				this.activeIntersectionIDs.remove(intersectionID, Boolean.TRUE);
				if (runtime.hasWork()) this.activeIntersectionIDs.put(intersectionID, Boolean.TRUE);
			}
		}
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	public boolean hasActiveConnectorForRoad(Road road) {
		this.connectorTopologyLock.readLock().lock();
		try {
		if (road == null) return false;
		for (ConnectorRoad connector : this.connectorTopology.connectorsByMovement.values()) {
			if ((connector.getSourceRoad() == road || connector.getTargetRoad() == road)
					&& connector.hasActiveVehicles()) return true;
		}
		return false;
		} finally {
			this.connectorTopologyLock.readLock().unlock();
		}
	}

	/** Sequential scheduler hook; the threaded scheduler partitions this same work. */
	public void stepIntersections() {
		if (!GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) return;
		for (Integer intersectionID : getActiveIntersectionIDsSnapshot()) {
			processIntersectionState(intersectionID.intValue());
		}
	}

	public IntersectionSnapshot getIntersectionSnapshot(int intersectionID) {
		IntersectionRuntime runtime =
				this.connectorTopology.intersectionRuntimes.get(intersectionID);
		return runtime == null ? IntersectionSnapshot.empty(intersectionID)
				: runtime.getSnapshot();
	}

	public boolean intersectionHasCollision(int intersectionID) {
		return getIntersectionSnapshot(intersectionID).hasCollision();
	}

	public boolean connectorHasCollision(ConnectorRoad connector) {
		return connector != null && intersectionHasCollision(connector.getIntersectionID());
	}

	public void markRoadActive(Road road) {
		if (road != null) {
			markTravelTimeEstimatorRelevant(road);
			markRoadActive(road.getID());
		}
	}

	public void markRoadActive(int roadID) {
		if (this.activeRoadIDs.putIfAbsent(roadID, Boolean.TRUE) == null) {
			this.activeRoadMarkVersion.incrementAndGet();
		}
	}

	public void markTravelTimeEstimatorRelevant(Road road) {
		if (road != null && !(road instanceof ConnectorRoad)
				&& road.claimTravelTimeEstimatorRegistration()) {
			if (super.get(road.getID()) == road) {
				this.travelTimeEstimatorRoads.put(road.getID(), road);
			} else {
				road.clearTravelTimeEstimatorRegistration();
			}
		}
	}

	/**
	 * Physical roads that have carried traffic or received observations. Roads stay
	 * in this set so their historical estimator decay is applied on the same fixed
	 * refresh schedule; never-used free-flow roads no longer need repeated work.
	 */
	public List<Road> getTravelTimeEstimatorRoadsSnapshot() {
		return new ArrayList<Road>(this.travelTimeEstimatorRoads.values());
	}

	public long markRoutingMetricChanged(Road road) {
		long version = this.routingMetricVersion.incrementAndGet();
		if (!this.routingMetricTrackingEnabled) return version;
		if (road == null || road instanceof ConnectorRoad
				|| super.get(road.getID()) != road) return version;
		this.routingMetricRoadVersions.put(road.getID(), Long.valueOf(version));
		return version;
	}

	public void markRoutingMetricsChanged(Collection<? extends Road> roads) {
		if (roads == null || roads.isEmpty()) return;
		long version = this.routingMetricVersion.incrementAndGet();
		if (!this.routingMetricTrackingEnabled) return;
		for (Road road : roads) {
			if (road != null && !(road instanceof ConnectorRoad)
					&& super.get(road.getID()) == road) {
				this.routingMetricRoadVersions.put(road.getID(), Long.valueOf(version));
			}
		}
	}

	public boolean isRoutingMetricTrackingEnabled() {
		return this.routingMetricTrackingEnabled;
	}

	/** Start retaining per-road versions only when an external delta client exists. */
	public synchronized long enableRoutingMetricTracking() {
		if (!this.routingMetricTrackingEnabled) {
			this.routingMetricRoadVersions.clear();
			this.routingMetricTrackingEnabled = true;
		}
		return this.routingMetricVersion.get();
	}

	public long getRoutingMetricVersion() {
		return this.routingMetricVersion.get();
	}

	public long getPhysicalTopologyVersion() {
		return this.physicalTopologyVersion.get();
	}

	public void markPhysicalTopologyChanged(Road road) {
		if (road == null || road instanceof ConnectorRoad
				|| super.get(road.getID()) != road) return;
		this.physicalTopologyVersion.incrementAndGet();
		this.routingMetricVersion.incrementAndGet();
	}

	/**
	 * Capture a version-consistent set of roads changed after a client's cursor.
	 * Retaining only each road's latest version is sufficient for any cursor age;
	 * a concurrent later update is deliberately left for the next query.
	 */
	public RoutingMetricRefreshSnapshot getRoutingMetricRefreshSnapshot(long afterVersion) {
		enableRoutingMetricTracking();
		long throughVersion = this.routingMetricVersion.get();
		HashSet<Integer> changedRoadIDs = new HashSet<Integer>();
		for (Map.Entry<Integer, Long> entry : this.routingMetricRoadVersions.entrySet()) {
			Long version = entry.getValue();
			if (version != null && version.longValue() > afterVersion
					&& version.longValue() <= throughVersion) {
				changedRoadIDs.add(entry.getKey());
			}
		}
		ArrayList<Road> roads = new ArrayList<Road>(changedRoadIDs.size());
		for (Integer roadID : changedRoadIDs) {
			Road road = super.get(roadID.intValue());
			if (road != null) roads.add(road);
		}
		roads.sort(Comparator.comparingInt(Road::getID));
		return new RoutingMetricRefreshSnapshot(throughVersion, roads, false);
	}

	public static final class RoutingMetricRefreshSnapshot {
		public final long throughVersion;
		public final List<Road> roads;
		public final boolean snapshotRequired;

		RoutingMetricRefreshSnapshot(long throughVersion, List<Road> roads,
				boolean snapshotRequired) {
			this.throughVersion = throughVersion;
			this.roads = Collections.unmodifiableList(roads);
			this.snapshotRequired = snapshotRequired;
		}
	}

	public void registerEnteringVehicle(Road road, Vehicle vehicle) {
		if (road == null || vehicle == null) return;
		ConcurrentHashMap<Integer, Boolean> roadIDs = this.enteringVehicleRoadIDs.get(vehicle.getID());
		if (roadIDs == null) {
			ConcurrentHashMap<Integer, Boolean> newRoadIDs = new ConcurrentHashMap<Integer, Boolean>();
			roadIDs = this.enteringVehicleRoadIDs.putIfAbsent(vehicle.getID(), newRoadIDs);
			if (roadIDs == null) {
				roadIDs = newRoadIDs;
			}
		}
		roadIDs.put(road.getID(), Boolean.TRUE);
	}

	public void unregisterEnteringVehicle(Road road, Vehicle vehicle) {
		if (road == null || vehicle == null) return;
		ConcurrentHashMap<Integer, Boolean> roadIDs = this.enteringVehicleRoadIDs.get(vehicle.getID());
		if (roadIDs == null) return;
		roadIDs.remove(road.getID());
		if (roadIDs.isEmpty()) {
			this.enteringVehicleRoadIDs.remove(vehicle.getID(), roadIDs);
		}
	}

	public boolean hasEnteringVehicleRegistration(Vehicle vehicle) {
		if (vehicle == null) return false;
		ConcurrentHashMap<Integer, Boolean> roadIDs =
				this.enteringVehicleRoadIDs.get(vehicle.getID());
		return roadIDs != null && !roadIDs.isEmpty();
	}

	/**
	 * Return the indexed physical road whose entry queue contains this vehicle.
	 * Physical road IDs are assigned in deterministic construction order, so the
	 * smallest ID preserves the old full-context scan's result if stale state ever
	 * contains more than one registration.
	 */
	public Road getEnteringRoadForVehicle(Vehicle vehicle) {
		if (vehicle == null) return null;
		ConcurrentHashMap<Integer, Boolean> roadIDs =
				this.enteringVehicleRoadIDs.get(vehicle.getID());
		if (roadIDs == null || roadIDs.isEmpty()) return null;
		int selectedRoadID = Integer.MAX_VALUE;
		for (Integer roadID : roadIDs.keySet()) {
			if (roadID != null && roadID.intValue() >= 0
					&& roadID.intValue() < selectedRoadID) {
				selectedRoadID = roadID.intValue();
			}
		}
		return selectedRoadID == Integer.MAX_VALUE ? null : super.get(selectedRoadID);
	}

	public boolean isEnteringVehicleRegistered(Road road, Vehicle vehicle) {
		if (road == null || vehicle == null) return false;
		ConcurrentHashMap<Integer, Boolean> roadIDs =
				this.enteringVehicleRoadIDs.get(vehicle.getID());
		return roadIDs != null && roadIDs.containsKey(road.getID());
	}

	public boolean isOnlyEnteringVehicleRegistered(Road road, Vehicle vehicle) {
		if (road == null || vehicle == null) return false;
		ConcurrentHashMap<Integer, Boolean> roadIDs =
				this.enteringVehicleRoadIDs.get(vehicle.getID());
		return roadIDs != null && roadIDs.size() == 1
				&& roadIDs.containsKey(road.getID());
	}

	public void removeVehicleFromEnteringQueues(Vehicle vehicle) {
		if (vehicle == null) return;
		ConcurrentHashMap<Integer, Boolean> roadIDMap = this.enteringVehicleRoadIDs.get(vehicle.getID());
		if (roadIDMap != null && !roadIDMap.isEmpty()) {
			ArrayList<Integer> roadIDs = new ArrayList<Integer>(roadIDMap.keySet());
			for (Integer roadID : roadIDs) {
				Road road = roadID == null ? null : this.get(roadID);
				if (road != null) {
					road.removeVehicleFromEnteringQueue(vehicle);
				} else {
					roadIDMap.remove(roadID);
				}
			}
			if (roadIDMap.isEmpty()) {
				this.enteringVehicleRoadIDs.remove(vehicle.getID(), roadIDMap);
			}
			return;
		}

		// Fallback for queues restored or created before the membership index existed.
		boolean removed = false;
		ArrayList<Road> fallbackRoads = getLikelyEnteringQueueRoads(vehicle);
		for (Road road : fallbackRoads) {
			if (road != null) {
				removed = road.removeVehicleFromEnteringQueue(vehicle) || removed;
			}
		}
		if (removed) return;
		for (Road road : getActiveRoadsSnapshot()) {
			if (road != null && !fallbackRoads.contains(road)) {
				road.removeVehicleFromEnteringQueue(vehicle);
			}
		}
	}

	private ArrayList<Road> getLikelyEnteringQueueRoads(Vehicle vehicle) {
		ArrayList<Road> roads = new ArrayList<Road>();
		addEnteringQueueCandidate(roads, vehicle.getRoad());
		addEnteringQueueCandidate(roads, vehicle.getOriginRoad());
		addEnteringQueueCandidate(roads, vehicle.getLastDeparturableRoad());
		if (roads.isEmpty()) {
			for (Road road : getActiveRoadsSnapshot()) {
				addEnteringQueueCandidate(roads, road);
			}
		}
		return roads;
	}

	private void addEnteringQueueCandidate(ArrayList<Road> roads, int roadID) {
		if (roadID >= 0) {
			addEnteringQueueCandidate(roads, this.get(roadID));
		}
	}

	private void addEnteringQueueCandidate(ArrayList<Road> roads, Road road) {
		if (road == null || roads.contains(road)) return;
		roads.add(road);
	}

	public List<Road> getActiveRoadsSnapshot() {
		ArrayList<Road> activeRoads = new ArrayList<Road>(this.activeRoadIDs.size());
		for (Integer roadID : this.activeRoadIDs.keySet()) {
			Road road = this.get(roadID);
			if (road != null) {
				activeRoads.add(road);
			} else {
				if (this.activeRoadIDs.remove(roadID) != null) {
					this.activeRoadMarkVersion.incrementAndGet();
				}
			}
		}
		return activeRoads;
	}

	public void refreshActiveRoads(Collection<Road> roads) {
		if (roads == null) {
			return;
		}
		for (Road road : roads) {
			if (road == null) {
				continue;
			}
			if (road.hasActiveVehicles()) {
				markRoadActive(road);
			} else {
				this.activeRoadIDs.computeIfPresent(road.getID(), (id, mark) -> {
					if (road.hasActiveVehicles()) {
						return mark;
					}
					this.activeRoadMarkVersion.incrementAndGet();
					return null;
				});
			}
		}
	}

	public void refreshActiveRoadPartitions(List<? extends Collection<Road>> partitions) {
		if (partitions == null) return;
		for (Collection<Road> partition : partitions) {
			refreshActiveRoads(partition);
		}
	}

	public long getActiveRoadVersion() {
		return this.activeRoadMarkVersion.get();
	}

	public void rebuildActiveRoadsFromState() {
		this.activeRoadIDs.clear();
		this.enteringVehicleRoadIDs.clear();
		this.travelTimeEstimatorRoads.clear();
		this.activeRoadMarkVersion.incrementAndGet();
		for (Road road : this.getAllSteppableRoads()) {
			road.clearTravelTimeEstimatorRegistration();
			if (!(road instanceof ConnectorRoad) && road.hasTravelTimeEstimatorEvidence()) {
				markTravelTimeEstimatorRelevant(road);
			}
			if (road.hasActiveVehicles()) {
				markRoadActive(road);
			}
			for (Vehicle vehicle : road.getEnteringVehicleQueueSnapshot()) {
				registerEnteringVehicle(road, vehicle);
			}
		}
		rebuildActiveIntersectionIndexes();
	}

	public int getActiveRoadCount() {
		return this.activeRoadIDs.size();
	}

	public boolean isRoadActive(int roadID) {
		return this.activeRoadIDs.containsKey(roadID);
	}

	private static final class ConnectorTopology {
		final Map<Long, ConnectorRoad> connectorsByMovement;
		final Map<Integer, ConnectorRoad> connectorsByInternalID;
		final Map<String, ConnectorRoad> connectorsByOrigID;
		final Map<String, ConnectorRoad> connectorsByAlias;
		final Map<Integer, Integer> intersectionByConnectorID;
		final Map<Integer, Set<ConnectorRoad>> connectorsByIntersectionID;
		final Map<Integer, IntersectionRuntime> intersectionRuntimes;

		ConnectorTopology(Map<Long, ConnectorRoad> connectorsByMovement,
				Map<Integer, ConnectorRoad> connectorsByInternalID,
				Map<String, ConnectorRoad> connectorsByOrigID,
				Map<String, ConnectorRoad> connectorsByAlias,
				Map<Integer, Integer> intersectionByConnectorID,
				Map<Integer, Set<ConnectorRoad>> connectorsByIntersectionID,
				Map<Integer, IntersectionRuntime> intersectionRuntimes) {
			this.connectorsByMovement = Collections.unmodifiableMap(
					new LinkedHashMap<Long, ConnectorRoad>(connectorsByMovement));
			this.connectorsByInternalID = Collections.unmodifiableMap(
					new LinkedHashMap<Integer, ConnectorRoad>(connectorsByInternalID));
			this.connectorsByOrigID = Collections.unmodifiableMap(
					new LinkedHashMap<String, ConnectorRoad>(connectorsByOrigID));
			this.connectorsByAlias = Collections.unmodifiableMap(
					new LinkedHashMap<String, ConnectorRoad>(connectorsByAlias));
			this.intersectionByConnectorID = Collections.unmodifiableMap(
					new LinkedHashMap<Integer, Integer>(intersectionByConnectorID));
			this.connectorsByIntersectionID = Collections.unmodifiableMap(
					new LinkedHashMap<Integer, Set<ConnectorRoad>>(
							connectorsByIntersectionID));
			this.intersectionRuntimes = Collections.unmodifiableMap(
					new LinkedHashMap<Integer, IntersectionRuntime>(
							intersectionRuntimes));
		}

		static ConnectorTopology empty() {
			return new ConnectorTopology(
					Collections.<Long, ConnectorRoad>emptyMap(),
					Collections.<Integer, ConnectorRoad>emptyMap(),
					Collections.<String, ConnectorRoad>emptyMap(),
					Collections.<String, ConnectorRoad>emptyMap(),
					Collections.<Integer, Integer>emptyMap(),
					Collections.<Integer, Set<ConnectorRoad>>emptyMap(),
					Collections.<Integer, IntersectionRuntime>emptyMap());
		}
	}

	private static final class IntersectionRuntime {
		static final int BLOCKED = 0;
		static final int ADMITTED = 1;
		static final int ALREADY_ADMITTED = 2;

		private final int intersectionID;
		private final double anchorLongitude;
		private final double anchorLatitude;
		private final Set<ConnectorRoad> connectors;
		private final HashMap<Integer, Admission> activeAdmissionByVehicle =
				new HashMap<Integer, Admission>();
		private final HashMap<Integer, DepartedVehicleState> recentlyDepartedStates =
				new HashMap<Integer, DepartedVehicleState>();
		private final HashSet<Integer> activeCollisionVehicleIDs = new HashSet<Integer>();
		private volatile IntersectionSnapshot snapshot;
		private long snapshotVersion;
		private long eventSequence;

		@SuppressWarnings("unused")
		IntersectionRuntime(int intersectionID, Coordinate anchor,
				Set<ConnectorRoad> connectors) {
			this(intersectionID, anchor, connectors, null);
		}

		IntersectionRuntime(int intersectionID, Coordinate anchor,
				Set<ConnectorRoad> connectors, IntersectionRuntime previous) {
			this.intersectionID = intersectionID;
			this.anchorLongitude = anchor == null ? Double.NaN : anchor.x;
			this.anchorLatitude = anchor == null ? Double.NaN : anchor.y;
			this.connectors = connectors;
			this.snapshot = IntersectionSnapshot.empty(intersectionID);
			this.snapshotVersion = 0L;
			this.eventSequence = 0L;
			recoverActiveAdmissions(previous);
			if (GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK
					&& previous == null && !this.activeAdmissionByVehicle.isEmpty()) {
				processCollisionCheck(ContextCreator.getCurrentTick());
			}
		}

		private void recoverActiveAdmissions(IntersectionRuntime previous) {
			if (previous == null) {
				recoverConnectorVehicles(null);
				return;
			}
			synchronized (previous) {
				for (Admission admission : previous.activeAdmissionByVehicle.values()) {
					if (!this.connectors.contains(admission.connector)) {
						throw new IllegalStateException();
					}
				}
				this.eventSequence = previous.eventSequence;
				this.snapshotVersion = previous.snapshotVersion;
				this.snapshot = previous.snapshot;
				recoverConnectorVehicles(previous);
				this.recentlyDepartedStates.putAll(previous.recentlyDepartedStates);
			}
		}

		private void recoverConnectorVehicles(IntersectionRuntime previous) {
			for (ConnectorRoad connector : this.connectors) {
				for (Vehicle vehicle : connector.getActiveVehiclesSnapshot()) {
					Admission prior = previous == null ? null
							: previous.activeAdmissionByVehicle.get(vehicle.getID());
					boolean sameAdmission = prior != null && prior.vehicle == vehicle
							&& prior.connector == connector;
					long admissionSequence = sameAdmission ? prior.admissionSequence
							: ++this.eventSequence;
					int admissionTick = sameAdmission ? prior.admissionTick
							: Integer.MIN_VALUE;
					ConnectorRoad.ConnectorPath connectorPath = sameAdmission
							? prior.connectorPath : vehicle.getCurrentConnectorPath();
					if (connectorPath == null && vehicle.getRoad() == connector) {
						connectorPath = connector.getPath(vehicle.getLane());
					}
					this.activeAdmissionByVehicle.put(vehicle.getID(),
							new Admission(vehicle, connector, connectorPath,
									admissionSequence, admissionTick));
					if (sameAdmission
							&& previous.activeCollisionVehicleIDs.contains(vehicle.getID())) {
						this.activeCollisionVehicleIDs.add(vehicle.getID());
					}
				}
			}
		}

		synchronized boolean matchesTopology(Coordinate anchor,
				Set<ConnectorRoad> candidateConnectors) {
			double longitude = anchor == null ? Double.NaN : anchor.x;
			double latitude = anchor == null ? Double.NaN : anchor.y;
			return this.connectors.equals(candidateConnectors)
					&& Double.compare(this.anchorLongitude, longitude) == 0
					&& Double.compare(this.anchorLatitude, latitude) == 0;
		}

		synchronized int tryAdmit(ConnectorRoad connector,
				ConnectorRoad.ConnectorPath connectorPath, Vehicle vehicle,
				boolean requireClearPath, int tick) {
			if (connector == null || connectorPath == null || vehicle == null
					|| !this.connectors.contains(connector)
					|| connector.getLane(connectorPath) == null) {
				return BLOCKED;
			}
			Admission existing = this.activeAdmissionByVehicle.get(vehicle.getID());
			if (existing != null) {
				return existing.vehicle == vehicle && existing.connector == connector
						&& existing.connectorPath == connectorPath
						? ALREADY_ADMITTED : BLOCKED;
			}
			if (requireClearPath) {
				for (Admission occupied : this.activeAdmissionByVehicle.values()) {
					if (occupied.connector == connector
							&& occupied.connectorPath == connectorPath) {
						return BLOCKED;
					}
				}
			}
			if (GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) {
				if (!this.activeCollisionVehicleIDs.isEmpty()) return BLOCKED;
				for (Admission occupied : this.activeAdmissionByVehicle.values()) {
					if (connector != occupied.connector
							&& connector.conflictsWith(occupied.connector)) {
						return BLOCKED;
					}
				}
			}
			connector.registerVehicle(vehicle, tick);
			this.activeAdmissionByVehicle.put(vehicle.getID(),
					new Admission(vehicle, connector, connectorPath,
							++this.eventSequence, tick));
			this.recentlyDepartedStates.remove(vehicle.getID());
			return ADMITTED;
		}

		void update(ConnectorRoad connector, Vehicle vehicle, int tick) {
			synchronized (this) {
				if (!ownsAdmission(connector, vehicle)) return;
			}
			connector.updateVehicle(vehicle, tick);
		}

		synchronized boolean restoreAdmission(ConnectorRoad connector, Vehicle vehicle,
				int tick) {
			if (connector == null || vehicle == null || !this.connectors.contains(connector)) {
				throw new IllegalArgumentException("Saved connector admission does not match topology");
			}
			Admission existing = this.activeAdmissionByVehicle.get(vehicle.getID());
			if (existing != null) {
				if (existing.vehicle == vehicle && existing.connector == connector) return false;
				throw new IllegalStateException("Duplicate saved connector vehicle ID "
						+ vehicle.getID());
			}
			ConnectorRoad.ConnectorPath connectorPath = vehicle.getCurrentConnectorPath();
			if (connectorPath == null || connector.getLane(connectorPath) == null) {
				throw new IllegalStateException("Saved connector vehicle " + vehicle.getID()
						+ " has no valid connector path");
			}
			connector.registerVehicle(vehicle, tick);
			this.activeAdmissionByVehicle.put(vehicle.getID(),
					new Admission(vehicle, connector, connectorPath,
							++this.eventSequence, tick));
			this.activeCollisionVehicleIDs.remove(vehicle.getID());
			this.recentlyDepartedStates.remove(vehicle.getID());
			return true;
		}

		synchronized boolean release(ConnectorRoad connector, Vehicle vehicle,
				boolean retainFinalSweep) {
			if (connector == null || vehicle == null) return false;
			Admission admission = this.activeAdmissionByVehicle.get(vehicle.getID());
			if (admission == null || admission.vehicle != vehicle
					|| admission.connector != connector) return false;
			ConnectorRoad.ConnectorVehicleState finalState = retainFinalSweep
					&& GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK
					? connector.getVehicleState(vehicle) : null;
			this.activeAdmissionByVehicle.remove(vehicle.getID());
			this.activeCollisionVehicleIDs.remove(vehicle.getID());
			connector.unregisterVehicle(vehicle);
			if (finalState != null) {
				this.recentlyDepartedStates.put(vehicle.getID(),
						new DepartedVehicleState(finalState, admission.admissionSequence,
								++this.eventSequence, admission.admissionTick));
			}
			return true;
		}

		private boolean ownsAdmission(ConnectorRoad connector, Vehicle vehicle) {
			if (connector == null || vehicle == null) return false;
			Admission admission = this.activeAdmissionByVehicle.get(vehicle.getID());
			return admission != null && admission.vehicle == vehicle
					&& admission.connector == connector;
		}

		synchronized boolean hasWork() {
			return !this.activeAdmissionByVehicle.isEmpty()
					|| !this.recentlyDepartedStates.isEmpty();
		}

		synchronized int getVehicleCount() {
			return this.activeAdmissionByVehicle.size();
		}

		IntersectionSnapshot getSnapshot() {
			return this.snapshot;
		}

		synchronized void clearRuntimeState() {
			this.activeAdmissionByVehicle.clear();
			this.recentlyDepartedStates.clear();
			this.activeCollisionVehicleIDs.clear();
			this.snapshot = IntersectionSnapshot.empty(this.intersectionID);
			this.snapshotVersion = 0L;
			this.eventSequence = 0L;
		}

		synchronized void processCollisionCheck(int tick) {
			if (!GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) {
				this.activeCollisionVehicleIDs.clear();
				this.snapshot = new IntersectionSnapshot(this.intersectionID, tick,
						++this.snapshotVersion, this.activeAdmissionByVehicle.size(), false,
						connectorVehicleCounts(), Collections.<String>emptyList());
				this.recentlyDepartedStates.clear();
				return;
			}
			ArrayList<CollisionParticipant> participants =
					new ArrayList<CollisionParticipant>();
			for (Admission admission : this.activeAdmissionByVehicle.values()) {
				ConnectorRoad.ConnectorVehicleState state =
						admission.connector.getVehicleState(admission.vehicle);
				if (state != null) {
					participants.add(CollisionParticipant.active(state,
							admission.admissionSequence, admission.admissionTick));
				}
			}
			for (DepartedVehicleState departed : this.recentlyDepartedStates.values()) {
				participants.add(CollisionParticipant.departed(departed));
			}
			participants.sort((first, second) -> {
				int idComparison = Integer.compare(first.state.getVehicleID(),
						second.state.getVehicleID());
				return idComparison != 0 ? idComparison
						: Long.compare(first.admissionSequence, second.admissionSequence);
			});

			double anchorLon = this.anchorLongitude;
			double anchorLat = this.anchorLatitude;
			if ((!Double.isFinite(anchorLon) || !Double.isFinite(anchorLat))
					&& !participants.isEmpty()) {
				anchorLon = participants.get(0).state.getLongitude();
				anchorLat = participants.get(0).state.getLatitude();
			}

			ArrayList<String> collisionPairs = new ArrayList<String>();
			double width = Math.max(0.0, GlobalVariables.DEFAULT_VEHICLE_WIDTH)
					+ 2.0 * INTERSECTION_CLEARANCE_MARGIN_METERS;
			this.activeCollisionVehicleIDs.clear();
			for (int i = 0; i < participants.size(); i++) {
				CollisionParticipant firstParticipant = participants.get(i);
				ConnectorRoad.ConnectorVehicleState first = firstParticipant.state;
				if (!finiteState(first)) continue;
				for (int j = i + 1; j < participants.size(); j++) {
					CollisionParticipant secondParticipant = participants.get(j);
					if (!firstParticipant.overlapsLifetime(secondParticipant)) continue;
					ConnectorRoad.ConnectorVehicleState second = secondParticipant.state;
					if (!finiteState(second)) continue;
					// Connector admissions are committed after road movement. If
					// either participant entered this tick, the pair only coexisted
					// at their current poses; do not replay another vehicle's
					// pre-admission sweep against the newcomer.
					boolean postMotionAdmission =
							firstParticipant.admissionTick == tick
							|| secondParticipant.admissionTick == tick;
					boolean firstCurrentTick =
							first.getTick() == tick && !postMotionAdmission;
					boolean secondCurrentTick =
							second.getTick() == tick && !postMotionAdmission;
					double[] firstPrevious = localMeters(
							firstCurrentTick ? first.getPreviousLongitude() : first.getLongitude(),
							firstCurrentTick ? first.getPreviousLatitude() : first.getLatitude(),
							anchorLon, anchorLat);
					double[] firstCurrent = localMeters(first.getLongitude(),
							first.getLatitude(), anchorLon, anchorLat);
					double[] secondPrevious = localMeters(
							secondCurrentTick ? second.getPreviousLongitude() : second.getLongitude(),
							secondCurrentTick ? second.getPreviousLatitude() : second.getLatitude(),
							anchorLon, anchorLat);
					double[] secondCurrent = localMeters(second.getLongitude(),
							second.getLatitude(), anchorLon, anchorLat);
					if (ConnectorRoad.sweptFootprintsOverlap(
							firstPrevious[0], firstPrevious[1],
							firstCurrentTick ? first.getPreviousBearing() : first.getBearing(),
							firstCurrent[0], firstCurrent[1],
							first.getBearing(), first.getLength(), width,
							secondPrevious[0], secondPrevious[1],
							secondCurrentTick ? second.getPreviousBearing() : second.getBearing(),
							secondCurrent[0], secondCurrent[1],
							second.getBearing(), second.getLength(), width)) {
						collisionPairs.add(first.getVehicleID() + "_" + second.getVehicleID());
						if (firstParticipant.active) {
							this.activeCollisionVehicleIDs.add(first.getVehicleID());
						}
						if (secondParticipant.active) {
							this.activeCollisionVehicleIDs.add(second.getVehicleID());
						}
					}
				}
			}

			boolean collision = !collisionPairs.isEmpty();
			this.snapshot = new IntersectionSnapshot(this.intersectionID, tick,
					++this.snapshotVersion, this.activeAdmissionByVehicle.size(), collision,
					connectorVehicleCounts(), collisionPairs);
			this.recentlyDepartedStates.clear();
		}

		private LinkedHashMap<String, Integer> connectorVehicleCounts() {
			LinkedHashMap<String, Integer> connectorCounts =
					new LinkedHashMap<String, Integer>();
			ArrayList<ConnectorRoad> orderedConnectors =
					new ArrayList<ConnectorRoad>(this.connectors);
			orderedConnectors.sort(Comparator.comparing(ConnectorRoad::getOrigID));
			for (ConnectorRoad connector : orderedConnectors) {
				connectorCounts.put(connector.getOrigID(), connector.getVehicleNum());
			}
			return connectorCounts;
		}

		private static final class Admission {
			final Vehicle vehicle;
			final ConnectorRoad connector;
			final ConnectorRoad.ConnectorPath connectorPath;
			final long admissionSequence;
			final int admissionTick;

			Admission(Vehicle vehicle, ConnectorRoad connector,
					ConnectorRoad.ConnectorPath connectorPath,
					long admissionSequence, int admissionTick) {
				this.vehicle = vehicle;
				this.connector = connector;
				this.connectorPath = connectorPath;
				this.admissionSequence = admissionSequence;
				this.admissionTick = admissionTick;
			}
		}

		private static final class DepartedVehicleState {
			final ConnectorRoad.ConnectorVehicleState state;
			final long admissionSequence;
			final long releaseSequence;
			final int admissionTick;

			DepartedVehicleState(ConnectorRoad.ConnectorVehicleState state,
					long admissionSequence, long releaseSequence, int admissionTick) {
				this.state = state;
				this.admissionSequence = admissionSequence;
				this.releaseSequence = releaseSequence;
				this.admissionTick = admissionTick;
			}
		}

		private static final class CollisionParticipant {
			final ConnectorRoad.ConnectorVehicleState state;
			final long admissionSequence;
			final long releaseSequence;
			final int admissionTick;
			final boolean active;

			private CollisionParticipant(ConnectorRoad.ConnectorVehicleState state,
					long admissionSequence, long releaseSequence, int admissionTick,
					boolean active) {
				this.state = state;
				this.admissionSequence = admissionSequence;
				this.releaseSequence = releaseSequence;
				this.admissionTick = admissionTick;
				this.active = active;
			}

			static CollisionParticipant active(ConnectorRoad.ConnectorVehicleState state,
					long admissionSequence, int admissionTick) {
				return new CollisionParticipant(state, admissionSequence, Long.MAX_VALUE,
						admissionTick, true);
			}

			static CollisionParticipant departed(DepartedVehicleState departed) {
				return new CollisionParticipant(departed.state, departed.admissionSequence,
						departed.releaseSequence, departed.admissionTick, false);
			}

			boolean overlapsLifetime(CollisionParticipant other) {
				return this.releaseSequence >= other.admissionSequence
						&& other.releaseSequence >= this.admissionSequence;
			}
		}

		private boolean finiteState(ConnectorRoad.ConnectorVehicleState state) {
			return state != null
					&& Double.isFinite(state.getPreviousLongitude())
					&& Double.isFinite(state.getPreviousLatitude())
					&& Double.isFinite(state.getLongitude())
					&& Double.isFinite(state.getLatitude())
					&& Double.isFinite(state.getLength());
		}

		private double[] localMeters(double longitude, double latitude,
				double anchorLongitude, double anchorLatitude) {
			double anchorLatRadians = Math.toRadians(anchorLatitude);
			double x = EARTH_RADIUS_METERS * Math.cos(anchorLatRadians)
					* Math.toRadians(longitude - anchorLongitude);
			double y = EARTH_RADIUS_METERS
					* Math.toRadians(latitude - anchorLatitude);
			return new double[] { x, y };
		}
	}

	/** Coherent, immutable result of one intersection collision pass. */
	public static final class IntersectionSnapshot {
		private final int intersectionID;
		private final int tick;
		private final long version;
		private final int activeVehicleCount;
		private final boolean collision;
		private final Map<String, Integer> connectorVehicleCounts;
		private final List<String> collisionVehiclePairs;

		private IntersectionSnapshot(int intersectionID, int tick, long version,
				int activeVehicleCount, boolean collision,
				Map<String, Integer> connectorVehicleCounts,
				List<String> collisionVehiclePairs) {
			this.intersectionID = intersectionID;
			this.tick = tick;
			this.version = version;
			this.activeVehicleCount = activeVehicleCount;
			this.collision = collision;
			this.connectorVehicleCounts = Collections.unmodifiableMap(
					new LinkedHashMap<String, Integer>(connectorVehicleCounts));
			this.collisionVehiclePairs = Collections.unmodifiableList(
					new ArrayList<String>(collisionVehiclePairs));
		}

		static IntersectionSnapshot empty(int intersectionID) {
			return new IntersectionSnapshot(intersectionID, -1, 0L, 0, false,
					Collections.<String, Integer>emptyMap(),
					Collections.<String>emptyList());
		}

		public int getIntersectionID() { return this.intersectionID; }
		public int getTick() { return this.tick; }
		public long getVersion() { return this.version; }
		public int getActiveVehicleCount() { return this.activeVehicleCount; }
		public boolean hasCollision() { return this.collision; }
		public Map<String, Integer> getConnectorVehicleCounts() {
			return this.connectorVehicleCounts;
		}
		public List<String> getCollisionVehiclePairs() {
			return this.collisionVehiclePairs;
		}
	}

	@Override
	public void remove(int ID) {
		Road road = super.get(ID);
		if (this.activeRoadIDs.remove(ID) != null) {
			this.activeRoadMarkVersion.incrementAndGet();
		}
		for (ConcurrentHashMap<Integer, Boolean> roadIDs : this.enteringVehicleRoadIDs.values()) {
			roadIDs.remove(ID);
		}
		this.travelTimeEstimatorRoads.remove(ID);
		this.routingMetricRoadVersions.remove(ID);
		if (road != null) road.clearTravelTimeEstimatorRegistration();
		super.remove(ID);
		if (road != null) {
			this.physicalTopologyVersion.incrementAndGet();
			this.routingMetricVersion.incrementAndGet();
		}
	}
}
