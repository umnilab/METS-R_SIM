package mets_r.communication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.google.gson.Gson;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.communication.MessageClass.DigitalTwinTeleportRequest;
import mets_r.communication.MessageClass.CoSimTeleportRequest;
import mets_r.facility.ConnectorRoad;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.routing.RouteContext;
import repast.simphony.context.DefaultContext;

/** Standalone regressions for teleport request contracts and exact selectors. */
public final class TeleportRequestContractRegression {
	private TeleportRequestContractRegression() {}

	public static void main(String[] args) throws Exception {
		installEmptyRepastContext();
		preservesMissingRequiredFieldsForValidation();
		doesNotAcceptTheRemovedDigitalTwinRoadIdAlias();
		selectsAnExactPhysicalLane();
		selectsAnExactConnectorPath();
		System.exit(0);
	}

	private static void installEmptyRepastContext() throws Exception {
		java.lang.reflect.Field mainContext = ContextCreator.class
				.getDeclaredField("mainContext");
		mainContext.setAccessible(true);
		mainContext.set(null, new DefaultContext<Object>());
	}

	private static void preservesMissingRequiredFieldsForValidation() {
		String quote = Character.toString((char) 34);
		CoSimTeleportRequest request = new Gson().fromJson(
				"{" + quote + "vehicleId" + quote + ":7," + quote
						+ "isPrivate" + quote + ":true," + quote + "x" + quote
						+ ":1," + quote + "y" + quote + ":2," + quote
						+ "bearing" + quote + ":90}",
				CoSimTeleportRequest.class);
		assertNull(request.speed, "missing CoSim speed");
		assertNull(request.z, "optional CoSim z");
		assertNull(request.transformCoordinates, "optional coordinate transform");
	}

	private static void doesNotAcceptTheRemovedDigitalTwinRoadIdAlias() {
		String quote = Character.toString((char) 34);
		DigitalTwinTeleportRequest request = new Gson().fromJson(
				"{" + quote + "vehicleId" + quote + ":8," + quote
						+ "isPrivate" + quote + ":false," + quote + "positionType"
						+ quote + ":" + quote + "segment" + quote + "," + quote
						+ "roadId" + quote + ":" + quote + "legacy" + quote + ","
						+ quote + "distanceToSegmentEnd" + quote + ":12}",
				DigitalTwinTeleportRequest.class);
		assertNull(request.segmentId, "removed roadId alias");
	}

	private static void selectsAnExactPhysicalLane() {
		Road road = physicalRoad(101, 40.0);
		Lane first = lane(1010, road, -86.0, 40.0);
		Lane second = lane(1011, road, -86.0, 40.0001);
		road.addLane(first);
		road.addLane(second);

		List<CoSimMapMatcher.Match> matches = CoSimMapMatcher.candidatesOnSegment(
				null, new Coordinate(-86.0, 40.00005, 0.0), 0.0,
				road, road.getOrigID(), Integer.valueOf(1), null);
		assertEquals(1, matches.size(), "physical lane match count");
		assertSame(second, matches.get(0).lane, "selected physical lane");
		assertTrue(CoSimMapMatcher.candidatesOnSegment(null,
				new Coordinate(-86.0, 40.0, 0.0), 0.0, road, road.getOrigID(),
				null, Integer.valueOf(0)).isEmpty(),
				"physical road rejects connectorPathId");
	}

	private static void selectsAnExactConnectorPath() throws Exception {
		Road source = physicalRoad(201, 40.0);
		Road target = physicalRoad(202, 40.0002);
		Lane sourceLane = lane(2010, source, -86.0, 40.0);
		Lane firstTarget = lane(2020, target, -85.9999, 40.0002);
		Lane secondTarget = lane(2021, target, -86.0001, 40.0002);
		source.addLane(sourceLane);
		target.addLane(firstTarget);
		target.addLane(secondTarget);

		ConnectorRoad.ConnectorPath firstPath = path(sourceLane, firstTarget,
				":junction_0_0", -85.9999);
		ConnectorRoad.ConnectorPath secondPath = path(sourceLane, secondTarget,
				":junction_0_1", -86.0001);
		java.lang.reflect.Constructor<ConnectorRoad> constructor = ConnectorRoad.class
				.getDeclaredConstructor(int.class, long.class, Road.class, Road.class,
						int.class, List.class);
		constructor.setAccessible(true);
		ConnectorRoad connector = constructor.newInstance(-500, 1L, source, target, 9,
				Arrays.asList(firstPath, secondPath));

		List<CoSimMapMatcher.Match> byId = CoSimMapMatcher.candidatesOnSegment(
				null, new Coordinate(-86.0, 40.0001, 0.0), 0.0,
				connector, connector.getOrigID(), null, Integer.valueOf(1));
		assertEquals(1, byId.size(), "connector path-ID match count");
		assertEquals(1, byId.get(0).connectorPath.getConnectorPathID(),
				"selected connector path ID");

		List<CoSimMapMatcher.Match> byAlias = CoSimMapMatcher.candidatesOnSegment(
				null, new Coordinate(-86.0, 40.0001, 0.0), 0.0,
				connector, ":junction_0_0", null, null);
		assertEquals(1, byAlias.size(), "connector alias match count");
		assertEquals(0, byAlias.get(0).connectorPath.getConnectorPathID(),
				"selected connector alias path");
		assertTrue(CoSimMapMatcher.candidatesOnSegment(null,
				new Coordinate(-86.0, 40.0001, 0.0), 0.0, connector,
				connector.getOrigID(), Integer.valueOf(0), null).isEmpty(),
				"connector rejects laneIndex");

		List<Road> nativeRoute = RouteContext.shortestPathRoute(
				connector, target, new Random(1L));
		assertEquals(2, nativeRoute.size(), "connector-origin native route size");
		assertSame(connector, nativeRoute.get(0), "connector-origin route head");
		assertSame(target, nativeRoute.get(1), "connector-origin route target");
	}

	private static Road physicalRoad(int id, double latitude) {
		Road road = new Road(id);
		road.setCoords(new ArrayList<Coordinate>(Arrays.asList(
				new Coordinate(-86.0002, latitude, 0.0),
				new Coordinate(-85.9998, latitude, 0.0))));
		return road;
	}

	private static Lane lane(int id, Road road, double longitude, double latitude) {
		Lane lane = new Lane(id);
		lane.setRoad(road.getID());
		lane.setCoords(new ArrayList<Coordinate>(Arrays.asList(
				new Coordinate(longitude, latitude, 0.0),
				new Coordinate(longitude, latitude + 0.0001, 0.0))));
		lane.setGeometricLength(11.0);
		lane.setLength(11.0);
		return lane;
	}

	private static ConnectorRoad.ConnectorPath path(Lane source, Lane target,
			String alias, double longitude) {
		return new ConnectorRoad.ConnectorPath(source, target,
				Arrays.asList(new Coordinate(longitude, 40.00005, 0.0),
						new Coordinate(longitude, 40.00015, 0.0)),
				Collections.singletonList(alias), Collections.<String>emptyList(),
				Collections.<String, String>emptyMap(), null, null, null, null,
				Double.NaN, Double.NaN, true);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertNull(Object value, String label) {
		if (value != null) throw new AssertionError(label + " should be null");
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label);
	}

	private static void assertEquals(int expected, int actual, String label) {
		if (expected != actual) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}
}
