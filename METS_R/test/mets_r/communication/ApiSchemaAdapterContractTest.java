package mets_r.communication;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Dependency-free wire-contract checks for schema compatibility.
 *
 * <p>Run with assertions enabled or invoke {@link #main(String[])}; failures
 * throw {@link AssertionError} so this also works in lightweight HPC builds
 * without a JUnit runner.</p>
 */
public final class ApiSchemaAdapterContractTest {
	private ApiSchemaAdapterContractTest() {
	}

	public static void main(String[] args) throws Exception {
		v1RemainsByteCompatible();
		v2NormalizesReadableRequests();
		v2UsesOperationSpecificTripAliases();
		v2RemovesDuplicatesAndReportsPartialResults();
		v2SeparatesStateFromOutcome();
		System.out.println("ApiSchemaAdapterContractTest: PASS");
	}

	@SuppressWarnings("unchecked")
	private static void v1RemainsByteCompatible() {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
		payload.put("TYPE", "ANS_tick");
		payload.put("CODE", "OK");
		payload.put("TICK", 7L);
		String legacy = JSONObject.toJSONString(payload);
		equal(legacy, ApiSchemaAdapter.formatResponse(
				"QUERY", "tick", legacy, ApiSchemaAdapter.DEFAULT_VERSION), "v1 response");

		JSONObject request = new JSONObject();
		request.put("TYPE", "QUERY_tick");
		same(request, ApiSchemaAdapter.normalizeRequest(
				request, ApiSchemaAdapter.DEFAULT_VERSION, "QUERY", "tick"), "v1 request");
	}

	@SuppressWarnings("unchecked")
	private static void v2NormalizesReadableRequests() {
		JSONObject vehicle = new JSONObject();
		vehicle.put("vehicleId", 31L);
		vehicle.put("segmentId", "r-12::r-13");
		vehicle.put("laneIndex", 2L);
		vehicle.put("acceleration", 0.4);

		JSONArray data = new JSONArray();
		data.add(vehicle);
		JSONObject request = new JSONObject();
		request.put("schemaVersion", 2L);
		request.put("messageType", "initializeCoSimVeh");
		request.put("data", data);

		JSONObject normalized = ApiSchemaAdapter.normalizeRequest(
				request, 2, "CTRL", "initializeCoSimVeh");
		equal("CTRL_initializeCoSimVeh", normalized.get("TYPE"), "normalized TYPE");
		Map<?, ?> item = (Map<?, ?>) ((JSONArray) normalized.get("DATA")).get(0);
		equal(31L, item.get("vehID"), "vehicleId alias");
		equal("r-12::r-13", item.get("segmentID"), "segmentId alias");
		equal(2L, item.get("laneID"), "laneIndex alias");
		equal(0.4, item.get("acc"), "acceleration alias");
	}

	@SuppressWarnings("unchecked")
	private static void v2UsesOperationSpecificTripAliases() {
		JSONObject trip = new JSONObject();
		trip.put("originRoadId", "r1");
		trip.put("destinationRoadId", "r2");
		trip.put("vehicleCount", 1L);
		JSONObject request = new JSONObject();
		request.put("data", Arrays.asList(trip));

		JSONObject normalized = ApiSchemaAdapter.normalizeRequest(
				request, 2, "CTRL", "genTripBwRoads");
		Map<?, ?> item = (Map<?, ?>) ((JSONArray) normalized.get("DATA")).get(0);
		equal("r1", item.get("orig"), "origin road alias");
		equal("r2", item.get("dest"), "destination road alias");
	}

	@SuppressWarnings("unchecked")
	private static void v2RemovesDuplicatesAndReportsPartialResults() throws Exception {
		LinkedHashMap<String, Object> good = new LinkedHashMap<String, Object>();
		good.put("ID", "r1");
		good.put("roadID", "r1");
		good.put("num_veh", 2L);
		good.put("nVehicles", 2L);
		good.put("controlType", 1L);
		good.put("roadControlType", 1L);
		good.put("isConnector", false);
		good.put("laneID", -1L);
		good.put("STATUS", "OK");

		LinkedHashMap<String, Object> bad = new LinkedHashMap<String, Object>();
		bad.put("ID", "missing");
		bad.put("STATUS", "KO");
		bad.put("REASON", "NOT_FOUND");

		LinkedHashMap<String, Object> legacy = new LinkedHashMap<String, Object>();
		legacy.put("TYPE", "ANS_road");
		legacy.put("CODE", "OK");
		legacy.put("DATA", Arrays.asList(good, bad));

		JSONObject v2 = parse(ApiSchemaAdapter.formatResponse(
				"QUERY", "road", JSONObject.toJSONString(legacy), 2));
		equal(2L, v2.get("schemaVersion"), "schema version");
		equal("road", v2.get("messageType"), "message type");
		equal("partial", v2.get("status"), "partial result");
		isFalse(v2.containsKey("TYPE") || v2.containsKey("CODE")
				|| v2.containsKey("id_list"), "legacy envelope removed");

		Map<?, ?> first = (Map<?, ?>) ((JSONArray) v2.get("data")).get(0);
		equal("r1", first.get("segmentId"), "deduplicated segment ID");
		equal(2L, first.get("vehicleCount"), "deduplicated vehicle count");
		equal("cosim", first.get("controlMode"), "control mode");
		equal("road", first.get("segmentType"), "segment type");
		isTrue(first.containsKey("laneIndex") && first.get("laneIndex") == null,
				"connector-less lane represented as null");
		isFalse(first.containsKey("ID") || first.containsKey("roadID")
				|| first.containsKey("num_veh") || first.containsKey("nVehicles"),
				"legacy record aliases removed");
	}

	@SuppressWarnings("unchecked")
	private static void v2SeparatesStateFromOutcome() throws Exception {
		LinkedHashMap<String, Object> record = new LinkedHashMap<String, Object>();
		record.put("ID", 9L);
		record.put("STATUS", "WAITING_DEPARTURE_TIME");
		record.put("v_type", true);

		LinkedHashMap<String, Object> legacy = new LinkedHashMap<String, Object>();
		legacy.put("CODE", "OK");
		legacy.put("DATA", Arrays.asList(record));

		JSONObject v2 = parse(ApiSchemaAdapter.formatResponse(
				"CTRL", "enterRoadFromQueue", JSONObject.toJSONString(legacy), 2));
		Map<?, ?> item = (Map<?, ?>) ((JSONArray) v2.get("data")).get(0);
		equal("ok", item.get("status"), "record outcome");
		equal("waitingDepartureTime", item.get("state"), "record state");
		equal(true, item.get("isPrivate"), "boolean vehicle type");
	}

	private static JSONObject parse(String json) throws Exception {
		return (JSONObject) new JSONParser().parse(json);
	}

	private static void equal(Object expected, Object actual, String label) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}

	private static void same(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": object identity changed");
	}

	private static void isTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void isFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
