package mets_r.communication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ConnectorRoad;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.mobility.Vehicle;

/**
 * Deterministic map matching for lane-less co-simulation poses.
 *
 * <p>The external pose is never snapped or shifted. Geometry is used only to
 * infer METS-R membership. A supplied segment is authoritative: geometry is
 * still projected for internal lane/path bookkeeping and diagnostics, but it
 * cannot veto the observation. Without a supplied segment, geometry determines
 * which controlled segment owns the pose; retained topology is only a ranking
 * preference and never a hard filter.</p>
 */
final class CoSimMapMatcher {
	private static final double EARTH_RADIUS_METERS = 6371008.8;
	private static final double MIN_LATERAL_TOLERANCE_METERS = 2.5;
	private static final double MAP_SLACK_METERS = 0.75;
	private static final double MAX_HEADING_ERROR_DEGREES = 100.0;
	private static final double ENDPOINT_TOLERANCE_METERS = 5.0;
	private static final double SCORE_EPSILON = 1.0e-9;

	private CoSimMapMatcher() {
	}

	static List<Match> candidates(Vehicle vehicle, Coordinate pose, double bearing,
			String segmentHint) {
		return candidates(vehicle, pose, bearing, segmentHint, null);
	}

	static List<Match> candidates(Vehicle vehicle, Coordinate pose, double bearing,
			String segmentHint, Integer connectorPathHint) {
		if (!finitePose(pose) || !Double.isFinite(bearing)) {
			return Collections.emptyList();
		}
		Road hintedSegment = null;
		String normalizedHint = segmentHint == null ? null : segmentHint.trim();
		if (normalizedHint != null && !normalizedHint.isEmpty()) {
			hintedSegment = ContextCreator.getRoadContext().getQueryableRoad(normalizedHint);
			if (hintedSegment == null || hintedSegment.getControlType() != Road.COSIM) {
				return Collections.emptyList();
			}
		}

		LinkedHashSet<Road> eligibleSegments = new LinkedHashSet<Road>(
				ContextCreator.getRoadContext().getCoSimSegmentsSnapshot());
		if (hintedSegment != null && !eligibleSegments.contains(hintedSegment)) {
			return Collections.emptyList();
		}
		boolean authoritativeSegment = hintedSegment != null;
		ConnectorRoad.ConnectorPath hintedConnectorPath = null;
		if (connectorPathHint != null) {
			if (!(hintedSegment instanceof ConnectorRoad)) return Collections.emptyList();
			hintedConnectorPath = ((ConnectorRoad) hintedSegment)
					.getPathByID(connectorPathHint.intValue());
			if (hintedConnectorPath == null) return Collections.emptyList();
		} else if (hintedSegment instanceof ConnectorRoad && normalizedHint != null) {
			// An exact SUMO via-lane alias identifies one path even though it resolves
			// to the movement-level connector for ownership.
			hintedConnectorPath = ((ConnectorRoad) hintedSegment).getPath(normalizedHint);
		}
		ArrayList<Match> matches = new ArrayList<Match>();
		for (Road segment : eligibleSegments) {
			if (hintedSegment != null && segment != hintedSegment) continue;
			if (segment instanceof ConnectorRoad) {
				addConnectorCandidates(matches, vehicle, (ConnectorRoad) segment,
						pose, bearing, authoritativeSegment, hintedConnectorPath);
			} else {
				addPhysicalRoadCandidates(matches, vehicle, segment, pose, bearing,
						authoritativeSegment);
			}
		}
		matches.sort(new Comparator<Match>() {
			@Override
			public int compare(Match first, Match second) {
				int byScore = Double.compare(first.score, second.score);
				if (byScore != 0) return byScore;
				int bySegment = first.segment.getOrigID().compareTo(second.segment.getOrigID());
				if (bySegment != 0) return bySegment;
				return Integer.compare(first.laneSortKey(), second.laneSortKey());
			}
		});
		return Collections.unmodifiableList(matches);
	}

	private static void addPhysicalRoadCandidates(List<Match> matches, Vehicle vehicle,
			Road road, Coordinate pose, double bearing, boolean authoritativeSegment) {
		for (Lane lane : road.getLanes()) {
			Projection projection = project(pose, lane.getCoords());
			if (projection == null
					|| !authoritativeSegment && !usableProjection(projection, bearing)) continue;
			double continuity = authoritativeSegment
					? 0.0 : continuityBonus(vehicle, road, lane, null);
			double headingError = headingError(bearing, projection.bearing);
			double score = projection.lateralDistanceMeters
					+ 0.03 * headingError
					+ continuity;
			matches.add(new Match(road, lane, null, projection.downstreamDistance,
					projection.lateralDistanceMeters, headingError,
					projection.endpointOvershootMeters, score));
		}
	}

	private static void addConnectorCandidates(List<Match> matches, Vehicle vehicle,
			ConnectorRoad connector, Coordinate pose, double bearing,
			boolean authoritativeSegment,
			ConnectorRoad.ConnectorPath hintedConnectorPath) {
		for (ConnectorRoad.ConnectorPath path : connector.getPaths()) {
			if (hintedConnectorPath != null && path != hintedConnectorPath) continue;
			// A geometry-only fallback connector is useful for visualization, but it
			// cannot provide the lane pair required for a safe state transition.
			if (path.getSourceLane() == null || path.getTargetLane() == null) continue;
			Projection projection = project(pose, path.getCenterLine());
			if (projection == null
					|| !authoritativeSegment && !usableProjection(projection, bearing)) continue;
			double continuity = authoritativeSegment
					? 0.0 : continuityBonus(vehicle, connector, null, path);
			double headingError = headingError(bearing, projection.bearing);
			double score = projection.lateralDistanceMeters
					+ 0.03 * headingError
					+ continuity;
			matches.add(new Match(connector, null, path, projection.downstreamDistance,
					projection.lateralDistanceMeters, headingError,
					projection.endpointOvershootMeters, score));
		}
	}

	private static double continuityBonus(Vehicle vehicle, Road segment, Lane lane,
			ConnectorRoad.ConnectorPath path) {
		if (vehicle == null) return 0.0;
		if (vehicle.isExternalRoadTransition()) {
			if (segment == vehicle.getCurrentConnector()) {
				return path != null && path == vehicle.getCurrentConnectorPath()
						? -4.0 : -2.0;
			}
			if (segment == vehicle.getExternalTransitionTargetRoad()
					&& lane == vehicle.getExternalTransitionTargetLane()) return -3.0;
			return 0.0;
		}
		if (segment == vehicle.getRoad()) {
			return lane == vehicle.getLane() ? -4.0 : -2.0;
		}
		if (segment instanceof ConnectorRoad && path != null
				&& path.getSourceLane() == vehicle.getLane()) return -3.0;
		if (segment == vehicle.getNextRoad()) return -2.0;
		return 0.0;
	}

	private static boolean usableProjection(Projection projection, double observedBearing) {
		if (projection == null) return false;
		return projection.lateralDistanceMeters <= lateralToleranceMeters()
				&& projection.endpointOvershootMeters <= ENDPOINT_TOLERANCE_METERS
				&& headingError(observedBearing, projection.bearing)
						<= MAX_HEADING_ERROR_DEGREES;
	}

	private static double lateralToleranceMeters() {
		return Math.max(MIN_LATERAL_TOLERANCE_METERS,
				Math.max(0.0, GlobalVariables.LANE_WIDTH) * 0.5 + MAP_SLACK_METERS);
	}

	private static Projection project(Coordinate pose, List<Coordinate> line) {
		if (!finitePose(pose) || line == null || line.size() < 2) return null;
		double longitudeScale = EARTH_RADIUS_METERS
				* Math.cos(Math.toRadians(pose.y));
		double latitudeScale = EARTH_RADIUS_METERS;
		double[] segmentLengths = new double[line.size() - 1];
		double totalLength = 0.0;
		for (int i = 0; i < line.size() - 1; i++) {
			double[] start = local(line.get(i), pose, longitudeScale, latitudeScale);
			double[] end = local(line.get(i + 1), pose, longitudeScale, latitudeScale);
			double localLength = Math.hypot(end[0] - start[0], end[1] - start[1]);
			double networkLength = ContextCreator.getCityContext().getDistance(
					line.get(i), line.get(i + 1));
			segmentLengths[i] = Double.isFinite(networkLength) && networkLength > 0.0
					? networkLength : localLength;
			totalLength += segmentLengths[i];
		}

		double bestLateral = Double.POSITIVE_INFINITY;
		double bestDownstream = Double.NaN;
		double bestBearing = Double.NaN;
		double bestOvershoot = Double.POSITIVE_INFINITY;
		double traversed = 0.0;
		for (int i = 0; i < line.size() - 1; i++) {
			double[] start = local(line.get(i), pose, longitudeScale, latitudeScale);
			double[] end = local(line.get(i + 1), pose, longitudeScale, latitudeScale);
			double dx = end[0] - start[0];
			double dy = end[1] - start[1];
			double length = segmentLengths[i];
			double localSquaredLength = dx * dx + dy * dy;
			if (length <= SCORE_EPSILON || localSquaredLength <= SCORE_EPSILON) continue;
			double raw = -(start[0] * dx + start[1] * dy) / localSquaredLength;
			double clamped = Math.max(0.0, Math.min(1.0, raw));
			double projectedX = start[0] + clamped * dx;
			double projectedY = start[1] + clamped * dy;
			double lateral = Math.hypot(projectedX, projectedY);
			double overshoot = raw < 0.0 ? -raw * length
					: raw > 1.0 ? (raw - 1.0) * length : 0.0;
			if (lateral + SCORE_EPSILON < bestLateral
					|| Math.abs(lateral - bestLateral) <= SCORE_EPSILON
							&& overshoot < bestOvershoot) {
				bestLateral = lateral;
				bestOvershoot = overshoot;
				bestDownstream = Math.max(0.0,
						totalLength - (traversed + clamped * length));
				bestBearing = normalizeBearing(Math.toDegrees(Math.atan2(dx, dy)));
			}
			traversed += length;
		}
		return Double.isFinite(bestDownstream)
				? new Projection(bestDownstream, bestLateral, bestBearing, bestOvershoot)
				: null;
	}

	private static double[] local(Coordinate coordinate, Coordinate anchor,
			double longitudeScale, double latitudeScale) {
		return new double[] {
				Math.toRadians(coordinate.x - anchor.x) * longitudeScale,
				Math.toRadians(coordinate.y - anchor.y) * latitudeScale
		};
	}

	static boolean overlapsAnyVehicle(Vehicle subject, Coordinate pose, double bearing,
			List<PlannedPose> alreadyAccepted) {
		if (!finitePose(pose)) return true;
		Set<Vehicle> visited = new HashSet<Vehicle>();
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			Vehicle vehicle = road.firstVehicle();
			while (vehicle != null && visited.add(vehicle)) {
				Vehicle next = vehicle.macroTrailing();
				if (vehicle != subject && overlaps(subject, pose, bearing,
						vehicle, vehicle.getCurrentCoord(), vehicle.getBearing())) return true;
				vehicle = next;
			}
		}
		for (ConnectorRoad connector : ContextCreator.getRoadContext().getAllConnectors()) {
			for (Vehicle vehicle : connector.getActiveVehiclesSnapshot()) {
				if (!visited.add(vehicle) || vehicle == subject) continue;
				if (overlaps(subject, pose, bearing,
						vehicle, vehicle.getCurrentCoord(), vehicle.getBearing())) return true;
			}
		}
		if (alreadyAccepted != null) {
			for (PlannedPose planned : alreadyAccepted) {
				if (planned.vehicle == subject) continue;
				if (overlaps(subject, pose, bearing, planned.vehicle,
						planned.pose, planned.bearing)) return true;
			}
		}
		return false;
	}

	private static boolean overlaps(Vehicle first, Coordinate firstPose, double firstBearing,
			Vehicle second, Coordinate secondPose, double secondBearing) {
		if (first == null || second == null || !finitePose(firstPose)
				|| !finitePose(secondPose)) return false;
		double longitudeScale = EARTH_RADIUS_METERS
				* Math.cos(Math.toRadians(firstPose.y));
		double latitudeScale = EARTH_RADIUS_METERS;
		double[] secondLocal = local(secondPose, firstPose, longitudeScale, latitudeScale);
		double width = Math.max(0.1, GlobalVariables.DEFAULT_VEHICLE_WIDTH) + 0.02;
		return ConnectorRoad.footprintsOverlap(0.0, 0.0, firstBearing,
				first.length(), width, secondLocal[0], secondLocal[1], secondBearing,
				second.length(), width);
	}

	private static boolean finitePose(Coordinate pose) {
		return pose != null && Double.isFinite(pose.x) && Double.isFinite(pose.y)
				&& Double.isFinite(pose.z);
	}

	private static double headingError(double first, double second) {
		double difference = Math.abs(normalizeBearing(first) - normalizeBearing(second));
		return difference > 180.0 ? 360.0 - difference : difference;
	}

	private static double normalizeBearing(double bearing) {
		double result = bearing % 360.0;
		return result < 0.0 ? result + 360.0 : result;
	}

	static final class Match {
		final Road segment;
		final Lane lane;
		final ConnectorRoad.ConnectorPath connectorPath;
		final double downstreamDistance;
		final double lateralDistanceMeters;
		final double headingErrorDegrees;
		final double endpointOvershootMeters;
		final double score;

		Match(Road segment, Lane lane, ConnectorRoad.ConnectorPath connectorPath,
				double downstreamDistance, double lateralDistanceMeters,
				double headingErrorDegrees, double endpointOvershootMeters, double score) {
			this.segment = segment;
			this.lane = lane;
			this.connectorPath = connectorPath;
			this.downstreamDistance = downstreamDistance;
			this.lateralDistanceMeters = lateralDistanceMeters;
			this.headingErrorDegrees = headingErrorDegrees;
			this.endpointOvershootMeters = endpointOvershootMeters;
			this.score = score;
		}

		boolean hasGeometryDiscrepancy() {
			return !Double.isFinite(this.lateralDistanceMeters)
					|| this.lateralDistanceMeters > lateralToleranceMeters()
					|| !Double.isFinite(this.headingErrorDegrees)
					|| this.headingErrorDegrees > MAX_HEADING_ERROR_DEGREES
					|| !Double.isFinite(this.endpointOvershootMeters)
					|| this.endpointOvershootMeters > ENDPOINT_TOLERANCE_METERS;
		}

		boolean isConnector() {
			return this.segment instanceof ConnectorRoad;
		}

		int laneSortKey() {
			if (this.lane != null) return this.lane.getID();
			if (this.connectorPath != null && this.connectorPath.getTargetLane() != null) {
				return this.connectorPath.getTargetLane().getID();
			}
			return Integer.MAX_VALUE;
		}
	}

	static final class PlannedPose {
		final Vehicle vehicle;
		final Coordinate pose;
		final double bearing;

		PlannedPose(Vehicle vehicle, Coordinate pose, double bearing) {
			this.vehicle = vehicle;
			this.pose = new Coordinate(pose);
			this.bearing = bearing;
		}
	}

	private static final class Projection {
		final double downstreamDistance;
		final double lateralDistanceMeters;
		final double bearing;
		final double endpointOvershootMeters;

		Projection(double downstreamDistance, double lateralDistanceMeters,
				double bearing, double endpointOvershootMeters) {
			this.downstreamDistance = downstreamDistance;
			this.lateralDistanceMeters = lateralDistanceMeters;
			this.bearing = bearing;
			this.endpointOvershootMeters = endpointOvershootMeters;
		}
	}
}
