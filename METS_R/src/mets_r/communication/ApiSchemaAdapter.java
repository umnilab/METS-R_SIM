package mets_r.communication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import mets_r.ContextCreator;
import mets_r.facility.Road;

/**
 * Wire-schema compatibility at the WebSocket boundary.
 *
 * <p>Simulation handlers continue to consume and produce the original v1
 * representation. Version 2 requests are normalized to v1 before dispatch and
 * v1 responses are serialized to the compact, readable v2 representation.
 * This keeps versioning out of simulation business logic.</p>
 */
public final class ApiSchemaAdapter {
	public static final int DEFAULT_VERSION = 1;
	public static final int VERSION_2 = 2;

	private ApiSchemaAdapter() {
	}

	public static boolean isSupported(int version) {
		return version == DEFAULT_VERSION || version == VERSION_2;
	}

	public static Integer requestedVersion(JSONObject message) {
		if (message == null) return null;
		Object value = message.containsKey("schemaVersion")
				? message.get("schemaVersion") : message.get("schema_version");
		if (value == null) return null;
		if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
		try {
			return Integer.valueOf(Integer.parseInt(value.toString().trim()));
		} catch (RuntimeException ex) {
			return Integer.valueOf(Integer.MIN_VALUE);
		}
	}

	public static String requestedMessageType(JSONObject message) {
		if (message == null) return null;
		Object value = message.containsKey("messageType")
				? message.get("messageType") : message.get("TYPE");
		return value == null ? null : value.toString().trim();
	}

	/** Map readable v2 operation aliases to the existing handler names. */
	public static String dispatchOperation(String operation) {
		if (operation == null) return null;
		if ("releaseCoSimRoad".equals(operation)) return "releaseCosimRoad";
		if ("generateTripsByRoad".equals(operation)) return "genTripBwRoads";
		if ("teleportTraceReplayVeh".equals(operation)) return "teleportDigitalTwinVeh";
		if ("allowRoadVehicleEnter".equals(operation)
				|| "releaseEnteringVehicle".equals(operation)) return "enterRoadFromQueue";
		if ("generatePrivateTrips".equals(operation)) return "generateTrip";
		return operation;
	}

	public static String publicOperation(String operation) {
		if (operation == null) return null;
		if ("releaseCosimRoad".equals(operation)) return "releaseCoSimRoad";
		if ("genTripBwRoads".equals(operation)) return "generateTripsByRoad";
		if ("teleportTraceReplayVeh".equals(operation)) return "teleportDigitalTwinVeh";
		if ("allowRoadVehicleEnter".equals(operation)
				|| "releaseEnteringVehicle".equals(operation)) return "enterRoadFromQueue";
		if ("generatePrivateTrips".equals(operation)) return "generateTrip";
		return operation;
	}

	/** Normalize a v2 request into the representation existing handlers expect. */
	@SuppressWarnings("unchecked")
	public static JSONObject normalizeRequest(JSONObject request, int version,
			String category, String operation) {
		if (request == null || version != VERSION_2) return request;
		JSONObject normalized = new JSONObject();
		normalized.putAll(request);
		normalized.put("TYPE", category + "_" + operation);
		if (request.containsKey("data")) {
			normalized.put("DATA", normalizeRequestValue(request.get("data"), operation));
		} else if (request.containsKey("DATA")) {
			normalized.put("DATA", normalizeRequestValue(request.get("DATA"), operation));
		}
		if (request.containsKey("fields") && !normalized.containsKey("FIELDS")) {
			normalized.put("FIELDS", request.get("fields"));
		}
		normalizeRequestAliases(normalized, operation);
		return normalized;
	}

	@SuppressWarnings("unchecked")
	private static Object normalizeRequestValue(Object value, String operation) {
		if (value instanceof Map<?, ?>) {
			JSONObject result = new JSONObject();
			result.putAll((Map<String, Object>) value);
			normalizeRequestAliases(result, operation);
			for (Object keyObject : new ArrayList<Object>(result.keySet())) {
				Object item = result.get(keyObject);
				if (item instanceof Map<?, ?> || item instanceof Collection<?>) {
					result.put(keyObject, normalizeRequestValue(item, operation));
				}
			}
			return result;
		}
		if (value instanceof Collection<?>) {
			JSONArray result = new JSONArray();
			for (Object item : (Collection<?>) value) {
				result.add(normalizeRequestValue(item, operation));
			}
			return result;
		}
		return value;
	}

	private static void normalizeRequestAliases(Map<String, Object> record, String operation) {
		copyAlias(record, "commandId", "commandID");
		copyAlias(record, "startTick", "startingTick");
		copyAlias(record, "tickCount", "numberOfTicks");
		copyAlias(record, "taxiIds", "taxiIDs");
		copyAlias(record, "vehicleId", "vehID");
		copyAlias(record, "internalVehicleId", "internalVehicleID");
		copyAlias(record, "isPrivate", "vehType");
		copyAlias(record, "transformCoordinates", "transformCoord");
		copyAlias(record, "laneIndex", "laneID");
		copyAlias(record, "acceleration", "acc");
		copyAlias(record, "attackEnabled", "isAttack");
		copyAlias(record, "vehicleLength", "length");
		copyAlias(record, "destinationRoadId", "destinationRoadID");
		copyAlias(record, "distanceToSegmentEnd", "dist");
		copyAlias(record, "distanceToRoadEnd", "dist");
		copyAlias(record, "requestId", "reqID");
		copyAlias(record, "zoneId", "zoneID");
		copyAlias(record, "busId", "busID");
		copyAlias(record, "routeRoadIds", "route");
		copyAlias(record, "routingWeight", "weight");
		copyAlias(record, "targetSpeed", "targetSpeed");
		copyAlias(record, "parkingCapacity", "parkingCapacity");
		copyAlias(record, "maxWaitTicks", "maxWaitingTime");
		copyAlias(record, "passengerCount", "num");
		copyAlias(record, "routeName", "routeName");

		if (record.containsKey("segmentId")) {
			String target = "initializeCoSimVeh".equals(operation)
					|| "teleportCoSimVeh".equals(operation) ? "segmentID" : "roadID";
			copyAlias(record, "segmentId", target);
		}
		if (record.containsKey("roadId")) {
			copyAlias(record, "roadId", "addRoads".equals(operation) ? "origID" : "roadID");
		}

		if ("generateTrip".equals(operation)) {
			copyAlias(record, "originZoneId", "orig");
			copyAlias(record, "destinationZoneId", "dest");
		} else if ("genTripBwRoads".equals(operation)) {
			copyAlias(record, "originRoadId", "orig");
			copyAlias(record, "destinationRoadId", "dest");
		} else if ("addTaxiRequests".equals(operation)
				|| "addBusRequests".equals(operation)) {
			copyAlias(record, "originZoneId", "zoneID");
			copyAlias(record, "destinationZoneId", "dest");
		} else if ("addTaxiReqBwRoads".equals(operation)) {
			copyAlias(record, "originRoadId", "orig");
			copyAlias(record, "destinationRoadId", "dest");
		} else if ("dispatchTaxi".equals(operation)) {
			copyAlias(record, "originZoneId", "originZoneID");
		} else if ("repositionTaxi".equals(operation)) {
			copyAlias(record, "destinationZoneId", "zoneID");
		} else if ("goParking".equals(operation)) {
			copyAlias(record, "destinationZoneId", "zoneID");
			copyAlias(record, "destinationRoadId", "roadID");
		}

		if ("addTaxi".equals(operation) || "addBus".equals(operation)) {
			copyAlias(record, "vehicleCount", "num");
		}
		if ("assignRequestToBus".equals(operation)) copyAlias(record, "vehicleId", "vehID");

		if ("updateChargingPrice".equals(operation)) {
			copyAlias(record, "chargingStationId", "chargerID");
		} else if ("goCharging".equals(operation)) {
			copyAlias(record, "chargingStationId", "csID");
		}
		copyAlias(record, "chargerLevel", "chargerType");

		if ("addBusRoute".equals(operation) || "addBusRouteWithPath".equals(operation)) {
			copyAlias(record, "stopZoneIds", "zones");
			copyAlias(record, "stopRoadIds", "roads");
			copyAlias(record, "pathRoadIds", "paths");
		}
		if ("addBusRun".equals(operation)) copyAlias(record, "departureTicks", "departTime");
		if ("insertStopToRoute".equals(operation)) {
			copyAlias(record, "stopZoneId", "zone");
			copyAlias(record, "stopRoadId", "road");
		}

		copyAlias(record, "signalId", "signalID");
		if ("updateSignal".equals(operation)) {
			copyAlias(record, "phase", "targetPhase");
			copyAlias(record, "phaseOffsetSeconds", "phaseTime");
		} else if ("updateSignalTiming".equals(operation)
				|| "setSignalPhasePlan".equals(operation)) {
			copyAlias(record, "greenSeconds", "greenTime");
			copyAlias(record, "yellowSeconds", "yellowTime");
			copyAlias(record, "redSeconds", "redTime");
			copyAlias(record, "phaseOffsetSeconds", "phaseOffset");
		}
		if ("setSignalPhasePlanTicks".equals(operation)) {
			copyAlias(record, "phaseOffsetTicks", "tickOffset");
		}

		if ("addRoads".equals(operation)) {
			copyAlias(record, "upstreamRoadIds", "upstreamRoadOrigIDs");
			copyAlias(record, "downstreamRoadIds", "downstreamRoadOrigIDs");
			copyAlias(record, "laneCount", "numLanes");
			copyAlias(record, "centerline", "centerline");
			copyAlias(record, "roadType", "roadType");
			copyAlias(record, "laneWidth", "laneWidth");
			if (record.containsKey("controlMode") && !record.containsKey("controlType")) {
				record.put("controlType", legacyControlType(record.get("controlMode")));
			}
		}

		copyAlias(record, "level2ChargerCount", "numL2");
		copyAlias(record, "level3ChargerCount", "numL3");
		copyAlias(record, "busChargerCount", "numBus");
		copyAlias(record, "level2Price", "priceL2");
		copyAlias(record, "level3Price", "priceL3");

		if (record.containsKey("tick") && !record.containsKey("TICK")) {
			record.put("TICK", record.get("tick"));
		}
		if (record.containsKey("tickCount") && !record.containsKey("NUM")) {
			record.put("NUM", record.get("tickCount"));
		}
	}

	private static void copyAlias(Map<String, Object> record, String source, String target) {
		if (record.containsKey(source) && !record.containsKey(target)) {
			record.put(target, record.get(source));
		}
	}

	private static int legacyControlType(Object value) {
		if (value instanceof Number) return ((Number) value).intValue();
		String mode = value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
		return "cosim".equals(mode) || "c".equals(mode) ? Road.COSIM : Road.NONE_OF_THE_ABOVE;
	}

	/** Serialize a legacy handler response using the selected wire schema. */
	@SuppressWarnings("unchecked")
	public static String formatResponse(String category, String operation,
			String legacyResponse, int version) {
		if (version != VERSION_2 || legacyResponse == null) return legacyResponse;
		try {
			Object parsed = new JSONParser().parse(legacyResponse);
			if (!(parsed instanceof Map<?, ?>)) {
				return formatError(publicOperation(operation),
						"INVALID_RESPONSE", "Handler returned a non-object response", VERSION_2);
			}

			Map<String, Object> source = (Map<String, Object>) parsed;
			boolean[] nestedError = new boolean[] { false };
			LinkedHashMap<String, Object> body = convertRoot(source, operation, nestedError);
			String status = statusFromCode(source.get("CODE"));
			if ("ok".equals(status) && nestedError[0]) status = "partial";

			LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
			result.put("schemaVersion", VERSION_2);
			result.put("messageType", publicOperation(operation));
			result.put("status", status);
			result.putAll(body);
			return JSONObject.toJSONString(result);
		} catch (Exception ex) {
			return formatError(publicOperation(operation), "INVALID_RESPONSE",
					"Could not translate handler response: " + ex.getMessage(), VERSION_2);
		}
	}

	/** Build a schema-appropriate boundary error without invoking a handler. */
	public static String formatError(String operation, String errorCode,
			String message, int version) {
		if (version == VERSION_2) {
			LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
			result.put("schemaVersion", VERSION_2);
			result.put("messageType", operation == null ? "error" : publicOperation(operation));
			result.put("status", "error");
			result.put("errorCode", errorCode);
			result.put("message", message);
			return JSONObject.toJSONString(result);
		}
		LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
		result.put("TYPE", "ANS_error");
		result.put("CODE", "KO");
		result.put("REASON", errorCode);
		result.put("MSG", message);
		return JSONObject.toJSONString(result);
	}

	private static LinkedHashMap<String, Object> convertRoot(Map<String, Object> source,
			String operation, boolean[] nestedError) {
		LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			String key = entry.getKey();
			if ("TYPE".equals(key) || "CODE".equals(key)
					|| "id_list".equals(key) || "orig_id".equals(key)) continue;
			String target = responseKey(key, operation, true, entry.getValue(), source);
			if (target == null || result.containsKey(target)) continue;
			result.put(target, convertResponseValue(entry.getValue(), operation, nestedError));
		}
		addIdentifierLists(result, source, operation);
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Object convertResponseValue(Object value, String operation,
			boolean[] nestedError) {
		if (value instanceof Map<?, ?>) {
			return convertRecord((Map<String, Object>) value, operation, nestedError);
		}
		if (value instanceof Collection<?>) {
			JSONArray result = new JSONArray();
			for (Object item : (Collection<?>) value) {
				if (item instanceof String && "KO".equalsIgnoreCase(item.toString())) {
					LinkedHashMap<String, Object> error = new LinkedHashMap<String, Object>();
					error.put("status", "error");
					result.add(error);
					nestedError[0] = true;
				} else {
					result.add(convertResponseValue(item, operation, nestedError));
				}
			}
			return result;
		}
		return value;
	}

	private static LinkedHashMap<String, Object> convertRecord(Map<String, Object> source,
			String operation, boolean[] nestedError) {
		LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if ("TYPE".equals(key) || "CODE".equals(key)) continue;
			if ("STATUS".equals(key)) {
				String legacyStatus = value == null ? "" : value.toString();
				if ("KO".equalsIgnoreCase(legacyStatus)) {
					result.put("status", "error");
					nestedError[0] = true;
				} else if ("OK".equalsIgnoreCase(legacyStatus)) {
					result.put("status", "ok");
				} else {
					result.put("status", "ok");
					result.put("state", readableState(legacyStatus));
				}
				continue;
			}
			String target = responseKey(key, operation, false, value, source);
			if (target == null || result.containsKey(target)) continue;
			Object converted = convertResponseValue(value, operation, nestedError);
			if ("laneIndex".equals(target) && value instanceof Number
					&& ((Number) value).intValue() < 0) converted = null;
			if ("controlMode".equals(target)) converted = controlMode(value);
			if ("segmentType".equals(target)) {
				converted = Boolean.TRUE.equals(value) ? "connector" : "road";
			}
			result.put(target, converted);
		}
		return result;
	}

	private static String responseKey(String key, String operation, boolean root,
			Object value, Map<String, Object> source) {
		if (key == null) return null;
		if ("DATA".equals(key)) return "data";
		if ("WARN".equals(key) || "ERROR".equals(key) || "MSG".equals(key)) return "message";
		if ("REASON".equals(key)) return "errorCode";
		if ("RETRYABLE".equals(key)) return "retryable";
		if ("TICK".equals(key) || "tick".equals(key)) return "tick";
		if ("REQUEST_TICK".equals(key)) return "requestTick";
		if ("TARGET_TICK".equals(key)) return "targetTick";
		if ("NUM".equals(key)) return "tickCount";
		if ("ACCEPTED_NUM".equals(key)) return "acceptedTickCount";
		if ("ID".equals(key)) return primaryIdKey(operation);
		if ("internalID".equals(key)) return internalIdKey(operation);
		if ("vehID".equals(key) || "vehicleID".equals(key)) return "vehicleId";
		if ("internalVehicleID".equals(key)) return "internalVehicleId";
		if ("roadID".equals(key) || "origID".equals(key)) {
			return isSegmentOperation(operation) ? "segmentId" : "roadId";
		}
		if ("segmentID".equals(key)) {
			if (source.containsKey("roadID")) return null;
			return "segmentId";
		}
		if ("signalID".equals(key) || "signal_id".equals(key)) return "signalId";
		if ("groupID".equals(key) || "group_id".equals(key)) return "signalGroupId";
		if ("zoneID".equals(key)) return "zoneId";
		if ("reqID".equals(key) || "requestID".equals(key)) return "requestId";
		if ("busID".equals(key)) return "busId";
		if ("taxiID".equals(key)) return "taxiId";
		if ("chargerID".equals(key) || "csID".equals(key)) return "chargingStationId";
		if ("routeID".equals(key)) return "routeId";
		if ("lane".equals(key) || "LANE".equals(key) || "laneID".equals(key)) return "laneIndex";
		if ("internalLaneID".equals(key)) return "internalLaneId";
		if ("laneIDs".equals(key)) return "internalLaneIds";
		if ("v_type".equals(key) || "vehType".equals(key)) {
			return value instanceof Boolean ? "isPrivate" : "vehicleClass";
		}
		if ("private_vids".equals(key)) return "privateVehicleIds";
		if ("public_vids".equals(key)) return "publicVehicleIds";
		if ("coord_map".equals(key)) return "coordinateTrail";
		if ("route".equals(key)) return isBusOperation(operation) ? "routeName" : "routeRoadIds";
		if ("road_list".equals(key)) return "segmentIds";
		if ("num_veh".equals(key) || "nVehicles".equals(key)) return "vehicleCount";
		if ("pendingDepartureVehicles".equals(key)) return "queuedVehicleCount";
		if ("controlType".equals(key) || "roadControlType".equals(key)) return "controlMode";
		if ("isConnector".equals(key)) return "segmentType";
		if ("routingEdge".equals(key)) return null;
		if ("r_type".equals(key)) return "roadType";
		if ("roadIndex".equals(key)) return "visualizationIndex";
		if ("speed_limit".equals(key)) return "speedLimit";
		if ("avg_travel_time".equals(key) || "travel_time".equals(key)) return "travelTime";
		if ("weight".equals(key)) return "routingWeight";
		if ("energy".equals(key) || "energy_consumed".equals(key)) return "energyConsumed";
		if ("avg_energy_consumption".equals(key)) return "averageEnergyConsumption";
		if ("parking_capacity".equals(key)) return "parkingCapacity";
		if ("parked_num".equals(key) || "parked_veh".equals(key)) return "parkedVehicleCount";
		if ("down_stream_road".equals(key) || "downstreamIDs".equals(key)) return "downstreamIds";
		if ("upstreamID".equals(key) || "sourceRoadID".equals(key)
				|| "sourceID".equals(key)) return "sourceRoadId";
		if ("downstreamID".equals(key) || "targetRoadID".equals(key)
				|| "targetID".equals(key)) return "targetRoadId";
		if ("dist".equals(key)) return "distanceToSegmentEnd";
		if ("acc".equals(key)) return "acceleration";
		if ("target_speed".equals(key)) return "targetSpeed";
		if ("length".equals(key) && isVehicleOperation(operation)) return "vehicleLength";
		if ("phase_time".equals(key) || "phaseTime".equals(key)) return "phaseOffsetSeconds";
		if ("green_time".equals(key) || "greenTime".equals(key)) return "greenSeconds";
		if ("yellow_time".equals(key) || "yellowTime".equals(key)) return "yellowSeconds";
		if ("red_time".equals(key) || "redTime".equals(key)) return "redSeconds";
		if ("junction_id".equals(key)) return "junctionId";
		if ("commandID".equals(key)) return "commandId";
		if ("startingTick".equals(key)) return "startTick";
		if ("numberOfTicks".equals(key)) return "tickCount";
		if ("taxiIDs".equals(key)) return "taxiIds";
		if ("IDs".equals(key)) return "ids";
		if ("stopZones".equals(key)) return "stopZoneIds";
		if ("stopRoads".equals(key)) return "stopRoadIds";
		if ("departTime".equals(key)) return "departureTicks";
		if ("numL2".equals(key)) return "level2ChargerCount";
		if ("numL3".equals(key)) return "level3ChargerCount";
		if ("numBus".equals(key)) return "busChargerCount";
		if ("priceL2".equals(key)) return "level2Price";
		if ("priceL3".equals(key)) return "level3Price";
		return toLowerCamel(key);
	}

	private static void addIdentifierLists(LinkedHashMap<String, Object> result,
			Map<String, Object> source, String operation) {
		Object internalIds = source.get("id_list");
		Object externalIds = source.get("orig_id");
		if (internalIds == null && externalIds == null) return;

		if (isRoadListOperation(operation)) {
			Object ids = externalIds == null ? internalIds : externalIds;
			addSegmentLists(result, ids);
			return;
		}
		if ("busRoute".equals(operation)) {
			if (internalIds != null) result.put("routeIds", internalIds);
			if (externalIds != null) result.put("routeNames", externalIds);
			return;
		}

		String key = identifierListKey(operation);
		Object ids = externalIds == null ? internalIds : externalIds;
		result.put(key, ids);
	}

	@SuppressWarnings("unchecked")
	private static void addSegmentLists(LinkedHashMap<String, Object> result, Object ids) {
		JSONArray roadIds = new JSONArray();
		JSONArray connectorIds = new JSONArray();
		if (ids instanceof Collection<?>) {
			for (Object id : (Collection<Object>) ids) {
				if (isConnectorId(id)) connectorIds.add(id);
				else roadIds.add(id);
			}
		}
		result.put("roadIds", roadIds);
		result.put("connectorIds", connectorIds);
	}

	private static boolean isConnectorId(Object id) {
		if (id == null) return false;
		try {
			return ContextCreator.getRoadContext() != null
					&& ContextCreator.getRoadContext().getConnector(id.toString()) != null;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static String identifierListKey(String operation) {
		if (contains(operation, "vehicle", "onRoadVehicles", "onRoadVehicle",
				"coSimVehicle", "getCoSimVehicle", "getCoSimVeh")) return "vehicleIds";
		if (contains(operation, "taxi", "queryTaxi", "availableTaxis",
				"almostFinishedTaxis")) return "taxiIds";
		if ("bus".equals(operation)) return "busIds";
		if ("zone".equals(operation)) return "zoneIds";
		if ("chargingStation".equals(operation)) return "chargingStationIds";
		if ("signal".equals(operation)) return "signalIds";
		if ("signalGroup".equals(operation)) return "signalGroupIds";
		return "ids";
	}

	private static boolean isRoadListOperation(String operation) {
		return contains(operation, "road", "activeRoad", "activeRoads", "queryActiveRoad",
				"queryActiveRoads", "coSimRoad", "getCoSimRoad", "enteringVehicleQueue",
				"coSimEnteringVehicleQueue", "cosimEnteringVehicleQueue", "centerLine",
				"edgeWeight", "routesBwCoords", "routesBwRoads", "multiRoutesBwCoords",
				"multiRoutesBwRoads", "routingTopology");
	}

	private static boolean isSegmentOperation(String operation) {
		return isRoadListOperation(operation) || contains(operation, "setCoSimRoad",
				"releaseCosimRoad", "initializeCoSimVeh", "teleportCoSimVeh",
				"enterRoadFromQueue", "coSimVehicle", "getCoSimVehicle", "getCoSimVeh");
	}

	private static boolean isVehicleOperation(String operation) {
		return contains(operation, "vehicle", "onRoadVehicles", "onRoadVehicle",
				"coSimVehicle", "getCoSimVehicle", "getCoSimVeh", "controlVeh",
				"initializeCoSimVeh", "teleportCoSimVeh", "teleportDigitalTwinVeh",
				"setAttackVehicle", "reachDest", "updateVehicleSensorType",
				"updateVehicleRoute", "addTaxi", "addBus");
	}

	private static boolean isBusOperation(String operation) {
		return operation != null && (operation.toLowerCase(Locale.ROOT).contains("bus")
				|| "assignRequestToBus".equals(operation));
	}

	private static String primaryIdKey(String operation) {
		if (isVehicleOperation(operation)) return "vehicleId";
		if (isSegmentOperation(operation) || contains(operation, "updateEdgeWeight",
				"updateTargetSpeed", "updateRoadParkingCapacity", "addRoads", "removeRoad")) {
			return isSegmentOperation(operation) ? "segmentId" : "roadId";
		}
		if (operation != null && operation.toLowerCase(Locale.ROOT).contains("signal")) return "signalId";
		if (operation != null && operation.toLowerCase(Locale.ROOT).contains("zone")) return "zoneId";
		if (operation != null && operation.toLowerCase(Locale.ROOT).contains("request")) return "requestId";
		if (operation != null && operation.toLowerCase(Locale.ROOT).contains("charging")) {
			return "chargingStationId";
		}
		return "id";
	}

	private static String internalIdKey(String operation) {
		if (isVehicleOperation(operation)) return "internalVehicleId";
		if (isSegmentOperation(operation) || "addRoads".equals(operation)) return "internalRoadId";
		return "internalId";
	}

	private static String statusFromCode(Object code) {
		if (code == null) return "ok";
		return "OK".equalsIgnoreCase(code.toString()) ? "ok" : "error";
	}

	private static String readableState(String value) {
		if (value == null || value.isEmpty()) return "unknown";
		String[] words = value.toLowerCase(Locale.ROOT).split("_");
		StringBuilder result = new StringBuilder(words[0]);
		for (int i = 1; i < words.length; i++) {
			if (!words[i].isEmpty()) {
				result.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
			}
		}
		return result.toString();
	}

	private static String controlMode(Object value) {
		if (value instanceof Number && ((Number) value).intValue() == Road.COSIM) return "cosim";
		String text = value == null ? "" : value.toString().trim();
		return "cosim".equalsIgnoreCase(text) ? "cosim" : "native";
	}

	private static String toLowerCamel(String key) {
		if (key == null || key.isEmpty() || key.indexOf('_') < 0) return key;
		String[] words = key.toLowerCase(Locale.ROOT).split("_");
		StringBuilder result = new StringBuilder(words[0]);
		for (int i = 1; i < words.length; i++) {
			if (!words[i].isEmpty()) {
				result.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
			}
		}
		return result.toString();
	}

	private static boolean contains(String value, String... candidates) {
		return value != null && Arrays.asList(candidates).contains(value);
	}
}
