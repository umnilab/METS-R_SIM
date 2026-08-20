package mets_r.facility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.GlobalVariables;
import mets_r.mobility.Vehicle;

/**
 * Queryable road facade for one legal movement through an intersection.
 *
 * <p>A connector deliberately has no {@link Lane}. Native vehicle physics keeps
 * using the destination lane's linked lists, while this facade supplies explicit
 * connector identity, geometry, membership, and intersection ownership. It is
 * stored in RoadContext's connector indexes, not in the physical road dictionary
 * used by routing and Repast scheduling.</p>
 */
public final class ConnectorRoad extends Road {
	public static final int NO_LANE = -1;
	private static final double GEOMETRY_EPSILON = 1.0e-9;

	/** Movement-level right-of-way decoded from SUMO connection state. */
	public enum MovementPriority {
		UNKNOWN,
		MAJOR,
		MINOR,
		EQUAL,
		BLOCKED
	}

	private final Road sourceRoad;
	private final Road targetRoad;
	private final int intersectionID;
	private final long movementKey;
	private final List<ConnectorPath> paths;
	private final List<List<Coordinate>> centerLines;
	private final Set<String> aliases;
	private final int configuredControlType;
	private final ConcurrentHashMap<Integer, Vehicle> activeVehicles =
			new ConcurrentHashMap<Integer, Vehicle>();
	private final ConcurrentHashMap<Integer, ConnectorVehicleState> vehicleStates =
			new ConcurrentHashMap<Integer, ConnectorVehicleState>();
	private volatile Set<Integer> conflictingConnectorIDs = Collections.emptySet();

	ConnectorRoad(int id, long movementKey, Road sourceRoad, Road targetRoad,
			int intersectionID, List<ConnectorPath> paths) {
		this(id, movementKey, sourceRoad, targetRoad, intersectionID,
				sourceRoad.getOrigID() + "_" + targetRoad.getOrigID(),
				Collections.<String>emptySet(), Road.NONE_OF_THE_ABOVE, paths);
	}

	ConnectorRoad(int id, long movementKey, Road sourceRoad, Road targetRoad,
			int intersectionID, String connectorOrigID, Set<String> aliases,
			List<ConnectorPath> paths) {
		this(id, movementKey, sourceRoad, targetRoad, intersectionID,
				connectorOrigID, aliases, Road.NONE_OF_THE_ABOVE, paths);
	}

	ConnectorRoad(int id, long movementKey, Road sourceRoad, Road targetRoad,
			int intersectionID, String connectorOrigID, Set<String> aliases,
			int configuredControlType, List<ConnectorPath> paths) {
		super(id);
		if (sourceRoad == null || targetRoad == null) {
			throw new IllegalArgumentException("Connector roads require source and target roads");
		}
		this.movementKey = movementKey;
		this.sourceRoad = sourceRoad;
		this.targetRoad = targetRoad;
		this.intersectionID = intersectionID;
		this.configuredControlType = configuredControlType;
		this.paths = immutablePaths(paths, sourceRoad, targetRoad);
		LinkedHashSet<String> connectorAliases = new LinkedHashSet<String>();
		if (aliases != null) {
			for (String alias : aliases) {
				if (alias != null && !alias.trim().isEmpty()) connectorAliases.add(alias.trim());
			}
		}
		this.aliases = Collections.unmodifiableSet(connectorAliases);
		ArrayList<List<Coordinate>> centerLines = new ArrayList<List<Coordinate>>();
		for (ConnectorPath path : this.paths) centerLines.add(path.getCenterLine());
		this.centerLines = Collections.unmodifiableList(centerLines);
		String fallbackOrigID = sourceRoad.getOrigID() + "_" + targetRoad.getOrigID();
		this.setOrigID(connectorOrigID == null || connectorOrigID.trim().isEmpty()
				? fallbackOrigID : connectorOrigID.trim());
		this.setRoadType(sourceRoad.getRoadType());
		this.setControlType(Road.NONE_OF_THE_ABOVE);
		this.setUpStreamJunction(intersectionID);
		this.setDownStreamJunction(intersectionID);
		this.addDownStreamRoad(targetRoad.getID());
		this.setSpeedLimit(Math.min(sourceRoad.getSpeedLimit(), targetRoad.getSpeedLimit()));
		double maxLength = 0.0;
		double explicitSpeed = Double.POSITIVE_INFINITY;
		for (ConnectorPath path : this.paths) {
			List<Coordinate> line = path.getCenterLine();
			double pathLength = Double.isFinite(path.getDeclaredLength())
					&& path.getDeclaredLength() >= 0.0
							? path.getDeclaredLength()
							: polylineLengthMeters(line, intersectionAnchor(line));
			maxLength = Math.max(maxLength, pathLength);
			if (Double.isFinite(path.getSpeed()) && path.getSpeed() >= 0.0) {
				explicitSpeed = Math.min(explicitSpeed, path.getSpeed());
			}
		}
		this.setLength(maxLength);
		this.setCoords(new ArrayList<Coordinate>(this.centerLines.get(0)));
		if (Double.isFinite(explicitSpeed)) {
			this.setSpeedLimit(Math.min(this.getSpeedLimit(), explicitSpeed));
		}
		this.updateTravelTimeEstimation();
		this.setCanBeOrigin(false);
		this.setCanBeDest(false);
	}

	private static List<ConnectorPath> immutablePaths(List<ConnectorPath> paths,
			Road sourceRoad, Road targetRoad) {
		ArrayList<ConnectorPath> result = new ArrayList<ConnectorPath>();
		if (paths != null) {
			for (ConnectorPath path : paths) {
				if (path != null && path.getCenterLine().size() >= 2) result.add(path);
			}
		}
		if (result.isEmpty()) {
			ArrayList<Coordinate> fallback = new ArrayList<Coordinate>();
			fallback.add(sourceRoad.getEndCoord());
			fallback.add(targetRoad.getStartCoord());
			result.add(new ConnectorPath(sourceRoad.firstLane(), targetRoad.firstLane(), fallback));
		}
		return Collections.unmodifiableList(result);
	}

	private Coordinate intersectionAnchor(List<Coordinate> line) {
		if (line == null || line.isEmpty()) return new Coordinate(0.0, 0.0, 0.0);
		return line.get(0);
	}

	private static ArrayList<Coordinate> deepCopy(List<Coordinate> coordinates) {
		ArrayList<Coordinate> result = new ArrayList<Coordinate>();
		if (coordinates == null) return result;
		for (Coordinate coordinate : coordinates) {
			if (coordinate == null) continue;
			result.add(new Coordinate(coordinate.x, coordinate.y,
					Double.isNaN(coordinate.z) ? 0.0 : coordinate.z));
		}
		return result;
	}

	private static double polylineLengthMeters(List<Coordinate> line, Coordinate anchor) {
		if (line == null || line.size() < 2) return 0.0;
		double[][] local = toLocalMeters(line, anchor);
		double length = 0.0;
		for (int i = 0; i < local.length - 1; i++) {
			length += Math.hypot(local[i + 1][0] - local[i][0],
					local[i + 1][1] - local[i][1]);
		}
		return length;
	}

	static double[][] toLocalMeters(List<Coordinate> line, Coordinate anchor) {
		double[][] result = new double[line == null ? 0 : line.size()][2];
		if (line == null || anchor == null) return result;
		double earthRadius = 6371008.8;
		double latitudeRadians = Math.toRadians(anchor.y);
		double longitudeRadians = Math.toRadians(anchor.x);
		double longitudeScale = earthRadius * Math.cos(latitudeRadians);
		for (int i = 0; i < line.size(); i++) {
			Coordinate coordinate = line.get(i);
			result[i][0] = longitudeScale * (Math.toRadians(coordinate.x) - longitudeRadians);
			result[i][1] = earthRadius * (Math.toRadians(coordinate.y) - latitudeRadians);
		}
		return result;
	}

	public static boolean polylinesConflict(double[][] first, double[][] second,
			double clearanceMeters) {
		if (first == null || second == null || first.length == 0 || second.length == 0) {
			return false;
		}
		double clearance = Math.max(0.0, clearanceMeters);
		double clearanceSquared = clearance * clearance;
		if (first.length == 1 && second.length == 1) {
			return squaredDistance(first[0][0], first[0][1], second[0][0], second[0][1])
					<= clearanceSquared;
		}
		if (first.length == 1) {
			for (int j = 0; j < second.length - 1; j++) {
				if (pointSegmentDistanceSquared(first[0], second[j], second[j + 1])
						<= clearanceSquared) return true;
			}
			return false;
		}
		if (second.length == 1) {
			for (int i = 0; i < first.length - 1; i++) {
				if (pointSegmentDistanceSquared(second[0], first[i], first[i + 1])
						<= clearanceSquared) return true;
			}
			return false;
		}
		for (int i = 0; i < first.length - 1; i++) {
			for (int j = 0; j < second.length - 1; j++) {
				if (segmentDistanceSquared(first[i], first[i + 1], second[j], second[j + 1])
						<= clearanceSquared) return true;
			}
		}
		return false;
	}

	public static boolean sweptFootprintsOverlap(
			double firstPreviousFrontX, double firstPreviousFrontY,
			double firstCurrentFrontX, double firstCurrentFrontY,
			double firstBearingDegrees, double firstLength, double firstWidth,
			double secondPreviousFrontX, double secondPreviousFrontY,
			double secondCurrentFrontX, double secondCurrentFrontY,
			double secondBearingDegrees, double secondLength, double secondWidth) {
		return sweptFootprintsOverlap(firstPreviousFrontX, firstPreviousFrontY,
				firstBearingDegrees, firstCurrentFrontX, firstCurrentFrontY,
				firstBearingDegrees, firstLength, firstWidth,
				secondPreviousFrontX, secondPreviousFrontY, secondBearingDegrees,
				secondCurrentFrontX, secondCurrentFrontY, secondBearingDegrees,
				secondLength, secondWidth);
	}

	@Override
	public void setControlType(int controlType) {
		setControlTypeDirect(controlType);
	}

	public static boolean sweptFootprintsOverlap(
			double firstPreviousFrontX, double firstPreviousFrontY,
			double firstPreviousBearingDegrees,
			double firstCurrentFrontX, double firstCurrentFrontY,
			double firstCurrentBearingDegrees, double firstLength, double firstWidth,
			double secondPreviousFrontX, double secondPreviousFrontY,
			double secondPreviousBearingDegrees,
			double secondCurrentFrontX, double secondCurrentFrontY,
			double secondCurrentBearingDegrees, double secondLength, double secondWidth) {
		if (footprintsOverlap(firstPreviousFrontX, firstPreviousFrontY,
				firstPreviousBearingDegrees, firstLength, firstWidth,
				secondPreviousFrontX, secondPreviousFrontY,
				secondPreviousBearingDegrees, secondLength, secondWidth)
				|| footprintsOverlap(firstCurrentFrontX, firstCurrentFrontY,
						firstCurrentBearingDegrees, firstLength, firstWidth,
						secondCurrentFrontX, secondCurrentFrontY,
						secondCurrentBearingDegrees, secondLength, secondWidth)) {
			return true;
		}

		double[] firstPreviousCenter = footprintCenter(firstPreviousFrontX,
				firstPreviousFrontY, firstPreviousBearingDegrees, firstLength);
		double[] firstCurrentCenter = footprintCenter(firstCurrentFrontX,
				firstCurrentFrontY, firstCurrentBearingDegrees, firstLength);
		double[] secondPreviousCenter = footprintCenter(secondPreviousFrontX,
				secondPreviousFrontY, secondPreviousBearingDegrees, secondLength);
		double[] secondCurrentCenter = footprintCenter(secondCurrentFrontX,
				secondCurrentFrontY, secondCurrentBearingDegrees, secondLength);

		double relativeX = firstPreviousCenter[0] - secondPreviousCenter[0];
		double relativeY = firstPreviousCenter[1] - secondPreviousCenter[1];
		double velocityX = (firstCurrentCenter[0] - firstPreviousCenter[0])
				- (secondCurrentCenter[0] - secondPreviousCenter[0]);
		double velocityY = (firstCurrentCenter[1] - firstPreviousCenter[1])
				- (secondCurrentCenter[1] - secondPreviousCenter[1]);
		double velocitySquared = velocityX * velocityX + velocityY * velocityY;
		double time = velocitySquared <= GEOMETRY_EPSILON ? 0.0
				: -(relativeX * velocityX + relativeY * velocityY) / velocitySquared;
		time = Math.max(0.0, Math.min(1.0, time));
		double closestX = relativeX + time * velocityX;
		double closestY = relativeY + time * velocityY;
		double firstRadius = 0.5 * Math.hypot(Math.max(0.0, firstLength),
				Math.max(0.0, firstWidth));
		double secondRadius = 0.5 * Math.hypot(Math.max(0.0, secondLength),
				Math.max(0.0, secondWidth));
		double combinedRadius = firstRadius + secondRadius;
		if (closestX * closestX + closestY * closestY
				>= combinedRadius * combinedRadius - GEOMETRY_EPSILON) return false;

		double firstTravel = Math.hypot(firstCurrentFrontX - firstPreviousFrontX,
				firstCurrentFrontY - firstPreviousFrontY);
		double secondTravel = Math.hypot(secondCurrentFrontX - secondPreviousFrontX,
				secondCurrentFrontY - secondPreviousFrontY);
		double firstTurn = Math.abs(shortestAngleDelta(firstPreviousBearingDegrees,
				firstCurrentBearingDegrees));
		double secondTurn = Math.abs(shortestAngleDelta(secondPreviousBearingDegrees,
				secondCurrentBearingDegrees));
		int steps = (int) Math.ceil(Math.max(Math.max(firstTravel, secondTravel) / 0.25,
				Math.max(firstTurn, secondTurn) / 3.0));
		steps = Math.max(2, Math.min(256, steps));
		for (int i = 1; i < steps; i++) {
			double fraction = (double) i / (double) steps;
			if (footprintsOverlap(
					interpolate(firstPreviousFrontX, firstCurrentFrontX, fraction),
					interpolate(firstPreviousFrontY, firstCurrentFrontY, fraction),
					interpolateAngle(firstPreviousBearingDegrees,
							firstCurrentBearingDegrees, fraction),
					firstLength, firstWidth,
					interpolate(secondPreviousFrontX, secondCurrentFrontX, fraction),
					interpolate(secondPreviousFrontY, secondCurrentFrontY, fraction),
					interpolateAngle(secondPreviousBearingDegrees,
							secondCurrentBearingDegrees, fraction),
					secondLength, secondWidth)) return true;
		}
		return false;
	}

	public static boolean footprintsOverlap(double firstFrontX, double firstFrontY,
			double firstBearingDegrees, double firstLength, double firstWidth,
			double secondFrontX, double secondFrontY, double secondBearingDegrees,
			double secondLength, double secondWidth) {
		double firstBearing = Math.toRadians(finiteOrZero(firstBearingDegrees));
		double secondBearing = Math.toRadians(finiteOrZero(secondBearingDegrees));
		double firstForwardX = Math.sin(firstBearing);
		double firstForwardY = Math.cos(firstBearing);
		double firstLateralX = Math.cos(firstBearing);
		double firstLateralY = -Math.sin(firstBearing);
		double secondForwardX = Math.sin(secondBearing);
		double secondForwardY = Math.cos(secondBearing);
		double secondLateralX = Math.cos(secondBearing);
		double secondLateralY = -Math.sin(secondBearing);

		double firstHalfLength = Math.max(0.0, firstLength) / 2.0;
		double secondHalfLength = Math.max(0.0, secondLength) / 2.0;
		double firstHalfWidth = Math.max(0.0, firstWidth) / 2.0;
		double secondHalfWidth = Math.max(0.0, secondWidth) / 2.0;
		double firstCenterX = firstFrontX - firstForwardX * firstHalfLength;
		double firstCenterY = firstFrontY - firstForwardY * firstHalfLength;
		double secondCenterX = secondFrontX - secondForwardX * secondHalfLength;
		double secondCenterY = secondFrontY - secondForwardY * secondHalfLength;
		double deltaX = secondCenterX - firstCenterX;
		double deltaY = secondCenterY - firstCenterY;

		double[][] axes = {
				{ firstForwardX, firstForwardY },
				{ firstLateralX, firstLateralY },
				{ secondForwardX, secondForwardY },
				{ secondLateralX, secondLateralY }
		};
		for (double[] axis : axes) {
			double separation = Math.abs(deltaX * axis[0] + deltaY * axis[1]);
			double firstRadius = firstHalfLength
					* Math.abs(firstForwardX * axis[0] + firstForwardY * axis[1])
					+ firstHalfWidth
					* Math.abs(firstLateralX * axis[0] + firstLateralY * axis[1]);
			double secondRadius = secondHalfLength
					* Math.abs(secondForwardX * axis[0] + secondForwardY * axis[1])
					+ secondHalfWidth
					* Math.abs(secondLateralX * axis[0] + secondLateralY * axis[1]);
			if (separation >= firstRadius + secondRadius - GEOMETRY_EPSILON) return false;
		}
		return true;
	}

	private static double interpolate(double start, double end, double fraction) {
		return start + (end - start) * fraction;
	}

	private static double interpolateAngle(double start, double end, double fraction) {
		return finiteOrZero(start) + shortestAngleDelta(start, end) * fraction;
	}

	private static double shortestAngleDelta(double start, double end) {
		double delta = finiteOrZero(end) - finiteOrZero(start);
		while (delta > 180.0) delta -= 360.0;
		while (delta < -180.0) delta += 360.0;
		return delta;
	}

	private static double[] footprintCenter(double frontX, double frontY,
			double bearingDegrees, double length) {
		double bearing = Math.toRadians(finiteOrZero(bearingDegrees));
		double halfLength = Math.max(0.0, length) / 2.0;
		return new double[] {
				frontX - Math.sin(bearing) * halfLength,
				frontY - Math.cos(bearing) * halfLength
		};
	}

	private static double finiteOrZero(double value) {
		return Double.isFinite(value) ? value : 0.0;
	}

	private static double segmentDistanceSquared(double[] a, double[] b,
			double[] c, double[] d) {
		if (segmentsIntersect(a, b, c, d)) return 0.0;
		return Math.min(Math.min(pointSegmentDistanceSquared(a, c, d),
				pointSegmentDistanceSquared(b, c, d)),
				Math.min(pointSegmentDistanceSquared(c, a, b),
						pointSegmentDistanceSquared(d, a, b)));
	}

	private static boolean segmentsIntersect(double[] a, double[] b,
			double[] c, double[] d) {
		double o1 = orientation(a, b, c);
		double o2 = orientation(a, b, d);
		double o3 = orientation(c, d, a);
		double o4 = orientation(c, d, b);
		if (((o1 > GEOMETRY_EPSILON && o2 < -GEOMETRY_EPSILON)
				|| (o1 < -GEOMETRY_EPSILON && o2 > GEOMETRY_EPSILON))
				&& ((o3 > GEOMETRY_EPSILON && o4 < -GEOMETRY_EPSILON)
				|| (o3 < -GEOMETRY_EPSILON && o4 > GEOMETRY_EPSILON))) {
			return true;
		}
		return Math.abs(o1) <= GEOMETRY_EPSILON && onSegment(a, b, c)
				|| Math.abs(o2) <= GEOMETRY_EPSILON && onSegment(a, b, d)
				|| Math.abs(o3) <= GEOMETRY_EPSILON && onSegment(c, d, a)
				|| Math.abs(o4) <= GEOMETRY_EPSILON && onSegment(c, d, b);
	}

	private static double orientation(double[] a, double[] b, double[] c) {
		return (b[0] - a[0]) * (c[1] - a[1])
				- (b[1] - a[1]) * (c[0] - a[0]);
	}

	private static boolean onSegment(double[] a, double[] b, double[] point) {
		return point[0] >= Math.min(a[0], b[0]) - GEOMETRY_EPSILON
				&& point[0] <= Math.max(a[0], b[0]) + GEOMETRY_EPSILON
				&& point[1] >= Math.min(a[1], b[1]) - GEOMETRY_EPSILON
				&& point[1] <= Math.max(a[1], b[1]) + GEOMETRY_EPSILON;
	}

	private static double pointSegmentDistanceSquared(double[] point,
			double[] start, double[] end) {
		double dx = end[0] - start[0];
		double dy = end[1] - start[1];
		double lengthSquared = dx * dx + dy * dy;
		if (lengthSquared <= GEOMETRY_EPSILON) {
			return squaredDistance(point[0], point[1], start[0], start[1]);
		}
		double fraction = ((point[0] - start[0]) * dx
				+ (point[1] - start[1]) * dy) / lengthSquared;
		fraction = Math.max(0.0, Math.min(1.0, fraction));
		return squaredDistance(point[0], point[1],
				start[0] + fraction * dx, start[1] + fraction * dy);
	}

	private static double squaredDistance(double x1, double y1, double x2, double y2) {
		double dx = x1 - x2;
		double dy = y1 - y2;
		return dx * dx + dy * dy;
	}

	public Road getSourceRoad() {
		return this.sourceRoad;
	}

	public Road getTargetRoad() {
		return this.targetRoad;
	}

	public int getIntersectionID() {
		return this.intersectionID;
	}

	public long getMovementKey() {
		return this.movementKey;
	}

	public Set<String> getAliases() {
		return this.aliases;
	}

	public int getConfiguredControlType() {
		return this.configuredControlType;
	}

	public List<List<Coordinate>> getCenterLines() {
		ArrayList<List<Coordinate>> result = new ArrayList<List<Coordinate>>();
		for (List<Coordinate> line : this.centerLines) {
			result.add(Collections.unmodifiableList(deepCopy(line)));
		}
		return Collections.unmodifiableList(result);
	}

	public List<ConnectorPath> getPaths() {
		return this.paths;
	}

	public static MovementPriority movementPriorityForState(String state) {
		if (state == null || state.trim().isEmpty()) return MovementPriority.UNKNOWN;
		switch (state.trim().charAt(0)) {
			case 'M':
			case 'O':
			case 'G':
			case 'Y':
				return MovementPriority.MAJOR;
			case 'm':
			case 'o':
			case 'g':
			case 'y':
				return MovementPriority.MINOR;
			case '=':
				return MovementPriority.EQUAL;
			case 'r':
			case 'u':
				return MovementPriority.BLOCKED;
			default:
				return MovementPriority.UNKNOWN;
		}
	}

	/** Resolve the most specific lane-to-lane movement state available. */
	public MovementPriority getMovementPriority(Lane sourceLane, Lane targetLane) {
		MovementPriority result = MovementPriority.UNKNOWN;
		for (ConnectorPath path : this.paths) {
			if (sourceLane != null && path.getSourceLane() != sourceLane) continue;
			if (targetLane != null && path.getTargetLane() != targetLane) continue;
			result = moreRestrictive(result,
					movementPriorityForState(path.getState()));
		}
		return result;
	}

	public MovementPriority getMovementPriority() {
		return getMovementPriority(null, null);
	}

	private static MovementPriority moreRestrictive(MovementPriority first,
			MovementPriority second) {
		return movementPriorityRank(second) > movementPriorityRank(first)
				? second : first;
	}

	private static int movementPriorityRank(MovementPriority priority) {
		if (priority == null) return 0;
		switch (priority) {
			case MAJOR:
				return 1;
			case EQUAL:
				return 2;
			case MINOR:
				return 3;
			case BLOCKED:
				return 4;
			default:
				return 0;
		}
	}

	@Override
	public int getControlType() {
		return this.configuredControlType == Road.COSIM
				|| super.getControlType() == Road.COSIM
				|| this.sourceRoad.getControlType() == Road.COSIM
				|| this.targetRoad.getControlType() == Road.COSIM ? Road.COSIM
						: Road.NONE_OF_THE_ABOVE;
	}

	public static final class ConnectorPath {
		private final Lane sourceLane;
		private final Lane targetLane;
		private final List<Coordinate> centerLine;
		private final List<String> viaLaneIDs;
		private final Map<String, String> parameters;
		private final String direction;
		private final String state;
		private final String trafficLightID;
		private final Integer linkIndex;
		private final double declaredLength;
		private final double speed;
		private final boolean explicitGeometry;

		public ConnectorPath(Lane sourceLane, Lane targetLane, List<Coordinate> centerLine) {
			this(sourceLane, targetLane, centerLine, Collections.<String>emptyList(),
					Collections.<String, String>emptyMap(), null, null, null, null,
					Double.NaN, Double.NaN, false);
		}

		public ConnectorPath(Lane sourceLane, Lane targetLane, List<Coordinate> centerLine,
				List<String> viaLaneIDs, Map<String, String> parameters,
				String direction, String state, String trafficLightID, Integer linkIndex,
				double declaredLength, double speed, boolean explicitGeometry) {
			this.sourceLane = sourceLane;
			this.targetLane = targetLane;
			this.centerLine = Collections.unmodifiableList(deepCopy(centerLine));
			this.viaLaneIDs = Collections.unmodifiableList(new ArrayList<String>(
					viaLaneIDs == null ? Collections.<String>emptyList() : viaLaneIDs));
			this.parameters = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
					parameters == null ? Collections.<String, String>emptyMap() : parameters));
			this.direction = direction;
			this.state = state;
			this.trafficLightID = trafficLightID;
			this.linkIndex = linkIndex;
			this.declaredLength = declaredLength;
			this.speed = speed;
			this.explicitGeometry = explicitGeometry;
		}

		public Lane getSourceLane() {
			return this.sourceLane;
		}

		public Lane getTargetLane() {
			return this.targetLane;
		}

		public List<Coordinate> getCenterLine() {
			return this.centerLine;
		}

		public List<String> getViaLaneIDs() { return this.viaLaneIDs; }
		public Map<String, String> getParameters() { return this.parameters; }
		public String getParameter(String key) { return this.parameters.get(key); }
		public String getDirection() { return this.direction; }
		public String getState() { return this.state; }
		public String getTrafficLightID() { return this.trafficLightID; }
		public Integer getLinkIndex() { return this.linkIndex; }
		public double getDeclaredLength() { return this.declaredLength; }
		public double getSpeed() { return this.speed; }
		public boolean hasExplicitGeometry() { return this.explicitGeometry; }
	}

	public ArrayList<Coordinate> getRepresentativeCenterLine() {
		return deepCopy(this.centerLines.get(0));
	}

	/**
	 * Estimate remaining connector distance by projecting a pose onto the closest
	 * connector centerline. Ties use the larger remaining distance so query-side
	 * trip estimates do not prematurely discard a longer lane-pair movement.
	 */
	public double estimateRemainingDistance(Coordinate pose) {
		if (pose == null || !Double.isFinite(pose.x) || !Double.isFinite(pose.y)) {
			return Double.NaN;
		}
		double bestDistanceSquared = Double.POSITIVE_INFINITY;
		double bestRemaining = Double.NaN;
		for (List<Coordinate> line : this.centerLines) {
			if (line == null || line.size() < 2) continue;
			Coordinate anchor = intersectionAnchor(line);
			double[][] local = toLocalMeters(line, anchor);
			double[][] posePoint = toLocalMeters(
					Collections.singletonList(pose), anchor);
			double poseX = posePoint[0][0];
			double poseY = posePoint[0][1];
			double totalLength = 0.0;
			for (int i = 0; i < local.length - 1; i++) {
				totalLength += Math.hypot(local[i + 1][0] - local[i][0],
						local[i + 1][1] - local[i][1]);
			}
			double distanceBeforeSegment = 0.0;
			for (int i = 0; i < local.length - 1; i++) {
				double dx = local[i + 1][0] - local[i][0];
				double dy = local[i + 1][1] - local[i][1];
				double segmentLengthSquared = dx * dx + dy * dy;
				double fraction = segmentLengthSquared <= GEOMETRY_EPSILON ? 0.0
						: ((poseX - local[i][0]) * dx
								+ (poseY - local[i][1]) * dy) / segmentLengthSquared;
				fraction = Math.max(0.0, Math.min(1.0, fraction));
				double projectedX = local[i][0] + fraction * dx;
				double projectedY = local[i][1] + fraction * dy;
				double offsetX = poseX - projectedX;
				double offsetY = poseY - projectedY;
				double distanceSquared = offsetX * offsetX + offsetY * offsetY;
				double segmentLength = Math.sqrt(segmentLengthSquared);
				double remaining = Math.max(0.0,
						totalLength - distanceBeforeSegment - fraction * segmentLength);
				if (distanceSquared < bestDistanceSquared - GEOMETRY_EPSILON
						|| (Math.abs(distanceSquared - bestDistanceSquared)
								<= GEOMETRY_EPSILON
								&& (!Double.isFinite(bestRemaining)
										|| remaining > bestRemaining))) {
					bestDistanceSquared = distanceSquared;
					bestRemaining = remaining;
				}
				distanceBeforeSegment += segmentLength;
			}
		}
		return bestRemaining;
	}

	void setConflictingConnectorIDs(Set<Integer> connectorIDs) {
		LinkedHashSet<Integer> copy = new LinkedHashSet<Integer>();
		if (connectorIDs != null) copy.addAll(connectorIDs);
		copy.remove(this.getID());
		this.conflictingConnectorIDs = Collections.unmodifiableSet(copy);
	}

	public Set<Integer> getConflictingConnectorIDs() {
		LinkedHashSet<Integer> result = new LinkedHashSet<Integer>(this.conflictingConnectorIDs);
		result.remove(this.getID());
		return Collections.unmodifiableSet(result);
	}

	public boolean conflictsWith(ConnectorRoad other) {
		return other != null && other != this
				&& this.conflictingConnectorIDs.contains(other.getID());
	}

	void registerVehicle(Vehicle vehicle, int tick) {
		if (vehicle == null) return;
		this.activeVehicles.put(vehicle.getID(), vehicle);
		if (GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) {
			this.vehicleStates.put(vehicle.getID(), ConnectorVehicleState.capture(
					vehicle, this, tick, null));
		} else {
			this.vehicleStates.remove(vehicle.getID());
		}
	}

	void updateVehicle(Vehicle vehicle, int tick) {
		if (!GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) return;
		if (vehicle == null || this.activeVehicles.get(vehicle.getID()) != vehicle) return;
		this.vehicleStates.computeIfPresent(vehicle.getID(), (id, previous) ->
				ConnectorVehicleState.capture(vehicle, this, tick, previous));
	}

	void unregisterVehicle(Vehicle vehicle) {
		if (vehicle == null) return;
		if (this.activeVehicles.remove(vehicle.getID(), vehicle)) {
			ConnectorVehicleState state = this.vehicleStates.get(vehicle.getID());
			if (state != null && state.getVehicle() == vehicle) {
				this.vehicleStates.remove(vehicle.getID(), state);
			}
		}
	}

	ConnectorVehicleState getVehicleState(Vehicle vehicle) {
		if (vehicle == null || this.activeVehicles.get(vehicle.getID()) != vehicle) return null;
		ConnectorVehicleState state = this.vehicleStates.get(vehicle.getID());
		return state != null && state.getVehicle() == vehicle ? state : null;
	}

	void clearRuntimeState() {
		this.activeVehicles.clear();
		this.vehicleStates.clear();
	}

	public List<Vehicle> getActiveVehiclesSnapshot() {
		ArrayList<Vehicle> result = new ArrayList<Vehicle>(this.activeVehicles.values());
		result.sort(Comparator.comparingInt(Vehicle::getID));
		return result;
	}

	List<ConnectorVehicleState> getVehicleStatesSnapshot() {
		ArrayList<ConnectorVehicleState> result =
				new ArrayList<ConnectorVehicleState>(this.vehicleStates.values());
		result.sort(Comparator.comparingInt(ConnectorVehicleState::getVehicleID));
		return result;
	}

	@Override
	public int getVehicleNum() {
		int count = 0;
		for (Vehicle vehicle : this.activeVehicles.values()) {
			if (vehicle != null && vehicle.isOnConnector()) count++;
		}
		return count;
	}

	@Override
	public boolean hasActiveVehicles() {
		return !this.activeVehicles.isEmpty();
	}

	@Override
	public int getPendingDepartureVehicleNum() {
		return 0;
	}

	@Override
	public ArrayList<String> getDownStreamRoadOrigIDs() {
		return new ArrayList<String>(Collections.singletonList(this.targetRoad.getOrigID()));
	}

	/** Immutable per-tick connector state used by parallel intersection checks. */
	static final class ConnectorVehicleState {
		private final Vehicle vehicle;
		private final ConnectorRoad connector;
		private final int vehicleID;
		private final int tick;
		private final double previousLongitude;
		private final double previousLatitude;
		private final double previousBearing;
		private final double longitude;
		private final double latitude;
		private final double bearing;
		private final double speed;
		private final double length;

		private ConnectorVehicleState(Vehicle vehicle, ConnectorRoad connector, int tick,
				double previousLongitude, double previousLatitude, double previousBearing, double longitude,
				double latitude, double bearing, double speed, double length) {
			this.vehicle = vehicle;
			this.connector = connector;
			this.vehicleID = vehicle.getID();
			this.tick = tick;
			this.previousLongitude = previousLongitude;
			this.previousLatitude = previousLatitude;
			this.previousBearing = previousBearing;
			this.longitude = longitude;
			this.latitude = latitude;
			this.bearing = bearing;
			this.speed = speed;
			this.length = length;
		}

		static ConnectorVehicleState capture(Vehicle vehicle, ConnectorRoad connector,
				int tick, ConnectorVehicleState previous) {
			Coordinate coordinate = vehicle.getCurrentCoord();
			double longitude = coordinate == null ? Double.NaN : coordinate.x;
			double latitude = coordinate == null ? Double.NaN : coordinate.y;
			boolean sameTick = previous != null && previous.tick == tick;
			double previousLongitude = previous == null ? longitude
					: sameTick ? previous.previousLongitude : previous.longitude;
			double previousLatitude = previous == null ? latitude
					: sameTick ? previous.previousLatitude : previous.latitude;
			double bearing = vehicle.getBearing();
			double previousBearing = previous == null ? bearing
					: sameTick ? previous.previousBearing : previous.bearing;
			return new ConnectorVehicleState(vehicle, connector, tick, previousLongitude,
					previousLatitude, previousBearing, longitude, latitude, bearing,
					vehicle.currentSpeed(), vehicle.length());
		}

		Vehicle getVehicle() { return this.vehicle; }
		ConnectorRoad getConnector() { return this.connector; }
		int getVehicleID() { return this.vehicleID; }
		int getTick() { return this.tick; }
		double getPreviousLongitude() { return this.previousLongitude; }
		double getPreviousLatitude() { return this.previousLatitude; }
		double getPreviousBearing() { return this.previousBearing; }
		double getLongitude() { return this.longitude; }
		double getLatitude() { return this.latitude; }
		double getBearing() { return this.bearing; }
		double getSpeed() { return this.speed; }
		double getLength() { return this.length; }
	}
}
