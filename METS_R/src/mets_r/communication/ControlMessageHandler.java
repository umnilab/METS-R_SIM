package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

import org.geotools.geometry.jts.JTS;
import org.json.simple.JSONObject;
import org.opengis.referencing.operation.TransformException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.SnapshotUtil;
import mets_r.communication.MessageClass.BusIDReqID;
import mets_r.communication.MessageClass.BusIDRouteNameStopIndex;
import mets_r.communication.MessageClass.BusIDRouteNameZoneRoadStopIndex;
import mets_r.communication.MessageClass.ChargerIDChargerTypeWeight;
import mets_r.communication.MessageClass.SignalIDPhase;
import mets_r.communication.MessageClass.SignalIDPhaseTiming;
import mets_r.communication.MessageClass.SignalPhasePlan;
import mets_r.communication.MessageClass.SignalPhasePlanTicks;
import mets_r.communication.MessageClass.OrigRoadDestRoadNumMaxW;
import mets_r.communication.MessageClass.OriginDestNumMaxW;
import mets_r.communication.MessageClass.RoadParkingCapacity;
import mets_r.communication.MessageClass.RoadIDTargetSpeed;
import mets_r.communication.MessageClass.RoadIDWeight;
import mets_r.communication.MessageClass.RouteNameDepartTime;
import mets_r.communication.MessageClass.RouteNameZonesRoads;
import mets_r.communication.MessageClass.RouteNameZonesRoadsPath;
import mets_r.communication.MessageClass.VehIDOrigDestNum;
import mets_r.communication.MessageClass.VehIDOrigRoadDestRoadNum;
import mets_r.communication.MessageClass.VehIDReqID;
import mets_r.communication.MessageClass.VehIDZoneID;
import mets_r.communication.MessageClass.VehIDZoneRoad;
import mets_r.communication.MessageClass.VehIDVehType;
import mets_r.communication.MessageClass.VehIDVehTypeAcc;
import mets_r.communication.MessageClass.VehIDVehTypeAttack;
import mets_r.communication.MessageClass.DigitalTwinTeleportRequest;
import mets_r.communication.MessageClass.VehIDVehTypeRoute;
import mets_r.communication.MessageClass.VehIDVehTypeSensorType;
import mets_r.communication.MessageClass.CoSimTeleportRequest;
import mets_r.communication.MessageClass.InitializeCoSimVehRequest;
import mets_r.communication.MessageClass.AddTaxiToZone;
import mets_r.communication.MessageClass.ChargingStationParams;
import mets_r.communication.MessageClass.RouteNameNum;
import mets_r.communication.MessageClass.VehIDVehTypeChargerTypeCSID;
import mets_r.communication.MessageClass.RoadParams;
import mets_r.communication.MessageClass.ZoneParams;
import mets_r.facility.ZoneContext;
import mets_r.data.input.SumoXML;
import mets_r.facility.ChargingStation;
import mets_r.facility.ConnectorRoad;
import mets_r.facility.Lane;
import mets_r.facility.Node;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Plan;
import mets_r.mobility.Request;
import mets_r.mobility.Vehicle;
import mets_r.mobility.VehicleContext;
import mets_r.routing.RouteContext;

public class ControlMessageHandler extends MessageHandler {
	private static final int MAX_COMPLETED_ADVANCE_COMMANDS = 128;
	private static final double COSIM_LARGE_DISPLACEMENT_WARNING_METERS = 25.0;
	private final Object advanceCommandLock = new Object();
	private final LinkedHashMap<String, AdvanceCommandRecord> advanceCommands =
			new LinkedHashMap<String, AdvanceCommandRecord>();
	private volatile long advanceCacheEpoch = 0L;

	public ControlMessageHandler() {
		// =============================================================
		// Simulation lifecycle
		// =============================================================
		messageHandlers.put("end", this::endSim);
		messageHandlers.put("reset", this::resetSim);
		messageHandlers.put("save", this::saveSim);
		messageHandlers.put("load", this::loadSim);
		messageHandlers.put("advanceAndSnapshot", this::advanceAndSnapshot);

		// =============================================================
		// Co-simulation: road handover & vehicle teleport
		// =============================================================
		messageHandlers.put("setCoSimRoad", this::setCoSimRoad);
		messageHandlers.put("releaseCoSimRoad", this::releaseCoSimRoad);
		messageHandlers.put("initializeCoSimVeh", this::initializeCoSimVeh);
		messageHandlers.put("teleportCoSimVeh", this::teleportCoSimVeh);
		messageHandlers.put("teleportDigitalTwinVeh", this::teleportDigitalTwinVeh);
		messageHandlers.put("enterRoadFromQueue", this::enterRoadFromQueue);

		// =============================================================
		// Vehicle runtime control
		// =============================================================
		messageHandlers.put("controlVeh", this::controlVeh);
		messageHandlers.put("setAttackVehicle", this::setAttackVehicle);
		messageHandlers.put("reachDest", this::reachDest);
		messageHandlers.put("updateVehicleSensorType", this::updateVehicleSensorType);
		messageHandlers.put("updateVehicleRoute", this::updateVehicleRoute);

		// =============================================================
		// Road speeds and routing weights
		// =============================================================
		messageHandlers.put("updateEdgeWeight", this::updateEdgeWeight);
		messageHandlers.put("updateTargetSpeed", this::updateTargetSpeed);
		messageHandlers.put("updateRoadParkingCapacity", this::updateRoadParkingCapacity);

		// =============================================================
		// Traffic signals
		// =============================================================
		messageHandlers.put("updateSignal", this::updateSignal);
		messageHandlers.put("updateSignalTiming", this::updateSignalTiming);
		messageHandlers.put("setSignalPhasePlan", this::setSignalPhasePlan);
		messageHandlers.put("setSignalPhasePlanTicks", this::setSignalPhasePlanTicks);

		// =============================================================
		// Charging
		// =============================================================
		messageHandlers.put("updateChargingPrice", this::updateChargingPrice);
		messageHandlers.put("goCharging", this::goCharging);

		// =============================================================
		// Private-vehicle trip generation
		// =============================================================
		messageHandlers.put("generateTrip", this::generateTrip);
		messageHandlers.put("generateTripsByRoad", this::generateTripsByRoad);

		// =============================================================
		// Ride-hailing: add pending requests
		// These are the ONLY entry points that create Request objects;
		// dispatch endpoints below only match a vehicle to an existing
		// pending request.
		// =============================================================
		messageHandlers.put("addTaxiRequests", this::addTaxiRequests);
		messageHandlers.put("addTaxiReqBwRoads", this::addTaxiReqBwRoads);
		messageHandlers.put("addBusRequests", this::addBusRequests);

		// =============================================================
		// Ride-hailing: dispatch & repositioning
		// =============================================================
		messageHandlers.put("dispatchTaxi", this::dispatchTaxi);
		messageHandlers.put("cancelRequests", this::cancelRequests);
		messageHandlers.put("repositionTaxi", this::repositionTaxi);
		messageHandlers.put("goParking", this::goParking);
		messageHandlers.put("assignRequestToBus", this::assignRequestToBus);

		// =============================================================
		// Bus routes & stops
		// =============================================================
		messageHandlers.put("addBusRoute", this::addBusRoute);
		messageHandlers.put("addBusRouteWithPath", this::addBusRouteWithPath);
		messageHandlers.put("addBusRun", this::addBusRun);
		messageHandlers.put("insertStopToRoute", this::insertStopToRoute);
		messageHandlers.put("removeStopFromRoute", this::removeStopFromRoute);

		// =============================================================
		// Dynamic infrastructure & fleet additions / removals
		// =============================================================
		messageHandlers.put("addZone", this::addZone);
		messageHandlers.put("addRoads", this::addRoads);
		messageHandlers.put("removeZone", this::removeZone);
		messageHandlers.put("removeRoad", this::removeRoad);
		messageHandlers.put("addChargingStation", this::addChargingStation);
		messageHandlers.put("removeChargingStation", this::removeChargingStation);
		messageHandlers.put("addTaxi", this::addTaxi);
		messageHandlers.put("addBus", this::addBus);
	}

	public String handleMessage(String msgType, JSONObject jsonMsg) {
		CustomizableHandler handler = messageHandlers.get(msgType);
		HashMap<String, Object> jsonAns = handler == null ? null : handler.handle(jsonMsg);
		if (jsonAns == null) {
			jsonAns = new HashMap<String, Object>();
			jsonAns.put("status", "error");
			jsonAns.put("errorCode", "UNKNOWN_CONTROL");
			jsonAns.put("message", "Unknown control: " + msgType);
		} else {
			if (!jsonAns.containsKey("status")) jsonAns.put("status", "ok");
			if ("ok".equals(jsonAns.get("status")) && hasRecordError(jsonAns.get("data"))) {
				jsonAns.put("status", "partial");
			}
		}
		jsonAns.put("messageType", msgType);
		count++;
		return JSONObject.toJSONString(jsonAns);
	}

	private boolean hasRecordError(Object value) {
		if (value instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) value;
			if ("error".equals(record.get("status"))) return true;
			for (Object item : record.values()) {
				if (hasRecordError(item)) return true;
			}
		} else if (value instanceof Collection<?>) {
			for (Object item : (Collection<?>) value) {
				if (hasRecordError(item)) return true;
			}
		}
		return false;
	}

	// =============================================================
	// SIMULATION LIFECYCLE
	// =============================================================

	/**
	* Reset the simulation back to its initial loaded state, cancelling
	* every scheduled action and re-running the seed-and-load pipeline.
	* Uses the deferred variant of reset to avoid leaking on-deck recurring
	* actions that would otherwise fire on stale targets after the reset.
	*/
	private HashMap<String, Object> resetSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();

		try {
			// Use the deferred variant so the scheduler has fully completed
			// the current tick (every recurring action rescheduled into the
			// main queue) before we attempt to remove them. This eliminates
			// the on-deck-queue leak that previously left ~5 recurring
			// actions per reset firing on stale targets, which both pinned
			// per-run heap state and inflated trip-completion counts across
			// successive resets.
			ContextCreator.deferredReset();
			jsonAns.put("status", "ok");
			jsonAns.put("tick", ContextCreator.getCurrentTick());
			jsonAns.put("tick", ContextCreator.getCurrentTick());
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing control" + e.toString());
			jsonAns.put("status", "error");
		}

		return jsonAns;
	}

	/**
	* Terminate the simulation cleanly, notifying any connected external
	* controllers that the run is finishing before invoking
	* {@link ContextCreator#end()}.
	*/
	private HashMap<String, Object> endSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();

		ContextCreator.connection.sendStopMessage();

		// Call the end function, cannot fail
		ContextCreator.end();
		jsonAns.put("status", "ok");

		return jsonAns;
	}

	/**
	* Save a snapshot of the current simulation state to the specified
	* zip archive.
	*
	* <p>Input DATA: {@code {"path": "<zip file path>"}}.
	*/
	private HashMap<String, Object> saveSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found. Expected: {\"path\": \"<zip file path>\"}");
			jsonAns.put("status", "error");
		} else {
			try {
				Gson gson = new Gson();
				HashMap<String, String> data = gson.fromJson(
						jsonMsg.get("data").toString(),
						new com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.getType());
				String zipPath = data.get("path");
				if (zipPath == null || zipPath.isEmpty()) {
					jsonAns.put("message", "Missing 'path' in DATA");
					jsonAns.put("status", "error");
				} else {
					jsonAns.put("path", zipPath);
					if (ContextCreator.save(zipPath)) {
						jsonAns.put("status", "ok");
					} else {
						jsonAns.put("message", "Save failed; check simulator logs for the underlying exception.");
						jsonAns.put("status", "error");
					}
				}
			} catch (Exception e) {
				ContextCreator.logger.error("Error saving simulation: " + e.toString());
				jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Reload simulation state from a previously-saved zip archive,
	* replacing the current run. Uses the deferred variant of load for
	* the same on-deck-queue rationale as {@link #resetSim}.
	*
	* <p>Input DATA: {@code {"path": "<zip file path>", "reloadNetwork": false}}.
	*/
	private HashMap<String, Object> loadSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found. Expected: {\"path\": \"<zip file path>\", \"reloadNetwork\": false}");
			jsonAns.put("status", "error");
		} else {
			try {
				Gson gson = new Gson();
				HashMap<String, Object> data = gson.fromJson(
						jsonMsg.get("data").toString(),
						new com.google.gson.reflect.TypeToken<HashMap<String, Object>>() {}.getType());
				String zipPath = data.get("path") == null ? null : data.get("path").toString();
				boolean reloadNetwork = optionalBoolean(data, false,
						"reloadNetwork", "reload_network", "rebuildNetwork", "rebuild_network");
				if (zipPath == null || zipPath.isEmpty()) {
					jsonAns.put("message", "Missing 'path' in DATA");
					jsonAns.put("status", "error");
				} else {
					// Use the deferred variant so the scheduler has fully
					// completed the current tick (every recurring action
					// rescheduled into the main queue) before rebuildForLoad
					// removes them. Same on-deck-queue rationale as reset.
					jsonAns.put("path", zipPath);
					jsonAns.put("reloadNetwork", reloadNetwork);
					if (ContextCreator.deferredLoad(zipPath, reloadNetwork)) {
						jsonAns.put("status", "ok");
						jsonAns.put("tick", ContextCreator.getCurrentTick());
						jsonAns.put("tick", ContextCreator.getCurrentTick());
						jsonAns.put("fastLoad", SnapshotUtil.wasLastLoadFastRestore());
					} else {
						jsonAns.put("message", "Load failed; check simulator logs for the underlying exception.");
						jsonAns.put("status", "error");
					}
				}
			} catch (Exception e) {
				ContextCreator.logger.error("Error loading simulation: " + e.toString());
				jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	public void resetRunEpoch(long runEpoch) {
		synchronized (this.advanceCommandLock) {
			this.advanceCacheEpoch = runEpoch;
			this.advanceCommands.clear();
		}
	}

	@SuppressWarnings("unchecked")
	private HashMap<String, Object> advanceAndSnapshot(JSONObject jsonMsg) {
		HashMap<String, Object> data;
		try {
			Object rawData = jsonMsg.get("data");
			if (rawData instanceof Map<?, ?>) {
				data = new HashMap<String, Object>((Map<String, Object>) rawData);
			} else if (rawData != null) {
				data = new Gson().fromJson(rawData.toString(),
						new TypeToken<HashMap<String, Object>>() {}.getType());
			} else {
				data = new HashMap<String, Object>((Map<String, Object>) jsonMsg);
			}
		} catch (Exception e) {
			return advanceError(null, "Invalid data payload: " + e.getMessage());
		}
		String[] advanceKeys = { "commandId", "startTick", "tickCount",
				"taxiIds", "fields", "includeDetails", "futureSupplyThresholds",
				"eventCursor", "timeoutMs" };
		for (String key : advanceKeys) {
			if (!data.containsKey(key) && jsonMsg.containsKey(key)) data.put(key, jsonMsg.get(key));
		}

		String commandID = data.get("commandId") == null ? null : String.valueOf(data.get("commandId"));
		if (commandID == null || commandID.trim().isEmpty()) {
			return advanceError(null, "commandId is required");
		}
		if (!GlobalVariables.SYNCHRONIZED) {
			return advanceError(commandID, "advanceAndSnapshot requires SYNCHRONIZED=true");
		}

		int startingTick = intValue(data.get("startTick"), ContextCreator.getCurrentTick());
		int numberOfTicks = Math.max(1, intValue(data.get("tickCount"), 1));
		long eventCursor = Math.max(0L, longValue(data.get("eventCursor"), 0L));
		long timeoutMs = Math.max(1000L, longValue(data.get("timeoutMs"), Math.max(120000L, numberOfTicks * 10000L)));
		Object requestedFields = data.get("fields");
		String fingerprint = String.valueOf(startingTick) + '|' + numberOfTicks + '|'
				+ String.valueOf(data.get("taxiIds")) + '|' + String.valueOf(requestedFields)
				+ '|' + String.valueOf(data.get("includeDetails"))
				+ '|' + String.valueOf(data.get("futureSupplyThresholds")) + '|' + eventCursor;

		synchronized (this.advanceCommandLock) {
			long epoch = ContextCreator.getRunEpoch();
			if (this.advanceCacheEpoch != epoch) resetRunEpoch(epoch);
			AdvanceCommandRecord command = this.advanceCommands.get(commandID);
			if (command != null && !command.fingerprint.equals(fingerprint)) {
				return advanceError(commandID, "commandId was already used with a different payload");
			}
			if (command != null && command.response != null) {
				HashMap<String, Object> replay = new HashMap<String, Object>(command.response);
				replay.put("replayed", true);
				return replay;
			}

			if (command == null) {
				ContextCreator.StepCommandResult accepted = ContextCreator.setNextStepCommand(startingTick, numberOfTicks);
				if (!accepted.accepted) {
					HashMap<String, Object> error = advanceError(commandID, "starting tick does not match current tick");
					error.put("currentTick", accepted.currentTick);
					return error;
				}
				command = new AdvanceCommandRecord(fingerprint, startingTick, accepted.targetTick);
				this.advanceCommands.put(commandID, command);
			}

			if (!ContextCreator.awaitStepTarget(command.targetTick, timeoutMs)) {
				HashMap<String, Object> timeout = advanceError(commandID, "timed out waiting for final quiescent tick");
				timeout.put("targetTick", command.targetTick);
				timeout.put("currentTick", ContextCreator.getCurrentTick());
				return timeout;
			}

			HashMap<String, Object> response = buildAdvanceSnapshot(commandID, command, data, eventCursor);
			command.response = response;
			trimCompletedAdvanceCommands();
			return new HashMap<String, Object>(response);
		}
	}

	private HashMap<String, Object> buildAdvanceSnapshot(String commandID, AdvanceCommandRecord command,
			Map<String, Object> data, long eventCursor) {
		HashMap<String, Object> response = new HashMap<String, Object>();
		response.put("status", "ok");
		response.put("commandId", commandID);
		response.put("runEpoch", ContextCreator.getRunEpoch());
		response.put("startTick", command.startingTick);
		response.put("finalTick", ContextCreator.getCurrentTick());
		response.put("advancedTicks", command.targetTick - command.startingTick);
		response.put("replayed", false);

		Set<String> fields = responseFieldMask(data);
		ArrayList<Object> taxis = new ArrayList<Object>();
		for (Integer taxiID : integerList(data.get("taxiIds"))) {
			HashMap<String, Object> taxiRecord = new HashMap<String, Object>();
			taxiRecord.put("taxiId", taxiID);
			ElectricTaxi taxi = ContextCreator.getVehicleContext().getTaxi(taxiID);
			if (taxi == null) {
				taxiRecord.put("status", "error");
			} else {
				addDispatchResponseFields(taxiRecord, taxi, fields);
				taxiRecord.put("status", "ok");
			}
			taxis.add(taxiRecord);
		}
		response.put("taxis", taxis);
		response.put("availableTaxiSummary", ContextCreator.getVehicleContext().getAvailableTaxiCounts());
		response.put("futureSupply", futureSupplySummary(data.get("futureSupplyThresholds")));

		SimulationEventJournal.Snapshot eventSnapshot = SimulationEventJournal.snapshotAfter(eventCursor);
		response.put("events", eventSnapshot.events);
		response.put("nextEventCursor", eventSnapshot.nextCursor);
		return response;
	}

	private ArrayList<Object> futureSupplySummary(Object rawThresholds) {
		ArrayList<Object> summaries = new ArrayList<Object>();
		for (Integer threshold : integerList(rawThresholds)) {
			ArrayList<Integer> zoneIDs = new ArrayList<Integer>();
			for (Zone zone : ContextCreator.getZoneContext().getAll()) {
				if (zone.getFutureSupply() <= threshold) zoneIDs.add(zone.getID());
			}
			java.util.Collections.sort(zoneIDs);
			HashMap<String, Object> summary = new HashMap<String, Object>();
			summary.put("threshold", threshold);
			summary.put("count", zoneIDs.size());
			summary.put("zoneIdsAtOrBelow", zoneIDs);
			summaries.add(summary);
		}
		return summaries;
	}

	private ArrayList<Integer> integerList(Object raw) {
		ArrayList<Integer> values = new ArrayList<Integer>();
		if (raw instanceof Collection<?>) {
			for (Object value : (Collection<?>) raw) {
				Integer parsed = integerValue(value);
				if (parsed != null) values.add(parsed);
			}
		}
		return values;
	}

	private int intValue(Object value, int defaultValue) {
		if (value == null) return defaultValue;
		if (value instanceof Number) return ((Number) value).intValue();
		try { return Integer.parseInt(String.valueOf(value)); }
		catch (NumberFormatException e) { return defaultValue; }
	}

	private long longValue(Object value, long defaultValue) {
		if (value == null) return defaultValue;
		if (value instanceof Number) return ((Number) value).longValue();
		try { return Long.parseLong(String.valueOf(value)); }
		catch (NumberFormatException e) { return defaultValue; }
	}

	private HashMap<String, Object> advanceError(String commandID, String warning) {
		HashMap<String, Object> error = new HashMap<String, Object>();
		error.put("status", "error");
		if (commandID != null) error.put("commandId", commandID);
		error.put("message", warning);
		error.put("runEpoch", ContextCreator.getRunEpoch());
		return error;
	}

	private void trimCompletedAdvanceCommands() {
		while (this.advanceCommands.size() > MAX_COMPLETED_ADVANCE_COMMANDS) {
			String removable = null;
			for (Map.Entry<String, AdvanceCommandRecord> entry : this.advanceCommands.entrySet()) {
				if (entry.getValue().response != null) { removable = entry.getKey(); break; }
			}
			if (removable == null) return;
			this.advanceCommands.remove(removable);
		}
	}

	private static class AdvanceCommandRecord {
		final String fingerprint;
		final int startingTick;
		final int targetTick;
		HashMap<String, Object> response;
		AdvanceCommandRecord(String fingerprint, int startingTick, int targetTick) {
			this.fingerprint = fingerprint;
			this.startingTick = startingTick;
			this.targetTick = targetTick;
		}
	}

	private boolean optionalBoolean(Map<String, Object> data, boolean defaultValue, String... keys) {
		for (String key : keys) {
			if (data.containsKey(key)) {
				return parseBoolean(data.get(key), defaultValue);
			}
		}
		return defaultValue;
	}

	private boolean parseBoolean(Object value, boolean defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof Number) {
			return ((Number) value).intValue() != 0;
		}
		String s = value.toString().trim();
		if (s.isEmpty()) {
			return defaultValue;
		}
		return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s);
	}

	// =============================================================
	// CO-SIMULATION: ROAD HANDOVER
	// =============================================================

	/**
	* Mark one or more roads as co-simulation roads. Vehicles on these
	* roads stop being stepped by METS-R's car-following logic; an
	* external simulator is expected to drive them via
	* {@link #teleportCoSimVeh}, which can place them directly on connector roads.
	*
	* <p>Input DATA: list of original road IDs.
	*/
	private String coSimTakeoverBlockReason(Road road) {
		if (road.isNativeReleaseInProgress()) {
			return "TRANSIENT: Road is still completing a native-control release";
		}
		int expectedVehicleCount = road.getVehicleNum();
		HashSet<Vehicle> visited = new HashSet<Vehicle>();
		Vehicle vehicle = road.firstVehicle();
		while (vehicle != null) {
			if (!visited.add(vehicle)) {
				return "Road macro vehicle list contains a cycle at vehicle " + vehicle.getID();
			}
			String reason = vehicle.coSimTakeoverBlockReason(road);
			if (reason != null) return reason;
			vehicle = vehicle.macroTrailing();
		}
		if (visited.size() != expectedVehicleCount) {
			return "Road macro vehicle count/list mismatch: count=" + expectedVehicleCount
					+ ", listed=" + visited.size();
		}
		return null;
	}

	private HashMap<String, Object> coSimTakeoverBlockedRecord(
			String roadId, Road road, String detail, boolean retryable) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("roadId", roadId);
		record.put("status", "error");
		record.put("errorCode", "FREEZE_BLOCKED");
		record.put("retryable", retryable);
		record.put("message", detail);

		Vehicle vehicle = road.firstVehicle();
		HashSet<Vehicle> visited = new HashSet<Vehicle>();
		while (vehicle != null && visited.add(vehicle)) {
			String reason = vehicle.coSimTakeoverBlockReason(road);
			if (reason != null) {
				record.put("blockingVehicleId", vehicle.getID());
				record.put("onRoad", vehicle.isOnRoad());
				record.put("onLane", vehicle.isOnLane());
				Road currentRoad = vehicle.getRoad();
				record.put("currentRoadId", currentRoad == null ? null : currentRoad.getOrigID());
				Lane lane = vehicle.getLane();
				if (lane != null) {
					record.put("laneIndex", road.getLaneIndex(lane));
					record.put("internalLaneId", lane.getID());
					record.put("laneLength", lane.getLength());
				}
				record.put("distance", vehicle.getDistanceToNextJunction());
				break;
			}
			vehicle = vehicle.macroTrailing();
		}
		return record;
	}

	private synchronized HashMap<String, Object> setCoSimRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else{
			try {
				Gson gson = new Gson();
				TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
			Collection<String> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for(String roadId: IDs) {
				Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadId);
				if(r != null) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						if (r.getControlType() != Road.COSIM) {
							String blockedReason = coSimTakeoverBlockReason(r);
							if (blockedReason != null) {
								boolean retryable = blockedReason.startsWith("TRANSIENT: ");
								if (retryable) blockedReason = blockedReason.substring("TRANSIENT: ".length());
								String detail = "Road " + roadId
										+ " cannot enter COSIM control yet: " + blockedReason;
								ContextCreator.logger.debug(detail);
								jsonData.add(coSimTakeoverBlockedRecord(
										roadId, r, detail, retryable));
								continue;
							}
							r.setControlType(Road.COSIM);
						}
						if (r.getControlType() != Road.COSIM) {
							String detail = "Road " + roadId + " did not accept COSIM control";
							jsonData.add(coSimTakeoverBlockedRecord(roadId, r, detail, false));
							continue;
						}
						// Publish bridge ownership only after the road accepts the takeover.
						ContextCreator.coSimRoads.put(roadId, r);
						refreshIncidentConnectorControlModes(r);
						record2.put("roadId", roadId);
						record2.put("status", "ok");
						record2.put("connectorIds", coSimConnectorIDsForRoad(r));
						record2.put("connectors", coSimConnectorRecordsForRoad(r));

						// Also output the lane information for computing the co-simulation area
//						ArrayList<Object> centerLines = new ArrayList<Object>();
//						for(Lane l: r.getLanes()) {
//							centerLines.add(l.getXYList());
//						}
//
//						record2.put("centerlines", centerLines);
						jsonData.add(record2);
				}
				else {
					ContextCreator.logger.warn("Cannot find the road, road ID: " + roadId);
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("roadId", roadId);
					record2.put("status", "error");
						jsonData.add(record2);
				}

			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Revert one or more roads from co-simulation control back to native
	* METS-R control.
	*
	* <p>Input DATA: list of original road IDs.
	*/
	private synchronized HashMap<String, Object> releaseCoSimRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
				Collection<String> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (String roadId : IDs) {
					Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadId);
					if (r != null) {
						ArrayList<ConnectorRoad> connectorsBefore = coSimConnectorsForRoad(r);
//						ArrayList<String> connectorIDsBefore = connectorIDs(connectorsBefore);
						r.setControlType(Road.NONE_OF_THE_ABOVE);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("roadId", roadId);
						if (r.getControlType() == Road.COSIM) {
							record2.put("status", "error");
							record2.put("errorCode", "RELEASE_BLOCKED");
							record2.put("retryable", true);
							record2.put("message", "Road release is temporarily blocked by vehicle placement");
						} else {
							// Bridge ownership ends only after the road actually accepts native control.
							ContextCreator.coSimRoads.remove(roadId);
							refreshIncidentConnectorControlModes(r);
							record2.put("status", "ok");
							record2.put("releasedConnectorIds",
									releasedConnectorIDs(connectorsBefore));
							record2.put("releasedConnectors",
									releasedConnectorRecords(connectorsBefore));
							record2.put("connectorIds", coSimConnectorIDsForRoad(r));
							record2.put("connectors", coSimConnectorRecordsForRoad(r));
						}
						jsonData.add(record2);
					} else {
						ContextCreator.logger.warn("Cannot find the road, road ID: " + roadId);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("roadId", roadId);
						record2.put("status", "error");
						jsonData.add(record2);
					}
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing releaseCoSimRoad", e);
			jsonAns.put("status", "error");;
			}
		}
		return jsonAns;
	}

	// =============================================================
	// PRIVATE-VEHICLE TRIP GENERATION
	// =============================================================

	private static boolean isValidOptionalVehicleLength(Double length) {
		return length == null || (Double.isFinite(length.doubleValue())
				&& length.doubleValue() > 0.0);
	}

	private static String invalidVehicleLengthWarning() {
		return "length must be a finite positive value in meters";
	}

	/**
	* Generate a one-shot private-EV trip between two zones. If a vehicle
	* with the given {@code vehicleId} is not yet registered, a new
	* {@link ElectricVehicle} is created on the fly.
	*
	* <p>Input DATA: list of {@code {vehicleId, orig, dest, num, length}} where
	* {@code orig}/{@code dest} are zone IDs (use {@code <= 0} for random) and
	* optional {@code length} is the vehicle length in meters. It is used only
	* when {@code vehicleId} is first created; later trips retain that vehicle's
	* original length.
	*/
    private HashMap<String, Object> generateTrip(JSONObject jsonMsg) {
	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
	if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
	else {
		try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigDestNum>> collectionType = new TypeToken<Collection<VehIDOrigDestNum>>() {};
			Collection<VehIDOrigDestNum> vehIDVehTypeOrigDests = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			for(VehIDOrigDestNum vehIDVehTypeOrigDest:  vehIDVehTypeOrigDests) {
				// Get data
				int vehicleId = vehIDVehTypeOrigDest.vehicleId;
					if (!isValidOptionalVehicleLength(vehIDVehTypeOrigDest.vehicleLength)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("vehicleId", vehicleId);
						record2.put("status", "error");
						record2.put("message", invalidVehicleLengthWarning());
						jsonData.add(record2);
						continue;
					}
				ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(vehicleId);
				if(v != null) {
						if (v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
						ContextCreator.logger.warn("The private EV: " + vehicleId + " is currently on the road, maybe there are two trips for the same vehicle that are too close?");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("vehicleId", vehicleId);
						record2.put("status", "error");
							jsonData.add(record2);
						continue;
						}
				}

					// Find the origin and dest zones
					int originID = vehIDVehTypeOrigDest.originZoneId;
					int destID = vehIDVehTypeOrigDest.destinationZoneId;
					Zone originZone = null;
					Zone destZone = null;

					if(originID > 0) {
						originZone = ContextCreator.getZoneContext().get(originID);

					}
					else {
						if(ContextCreator.getZoneContext().ZONE_NUM == 1) {
							originID = 0;
							originZone = ContextCreator.getZoneContext().get(originID);
						}
						else {
							// randomly select a zone as origin
							originID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM - 1) + 1;
							originZone = ContextCreator.getZoneContext().get(originID);
						}
					}
					if(originZone == null) {
						ContextCreator.logger.warn("Cannot find the origin with ID: " + originID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehicleId);
					record2.put("status", "error");
						jsonData.add(record2);
					continue;
					}
					if(originZone.getClosestRoad(false) == null) {
						ContextCreator.logger.warn("Origin zone " + originID + " has no departure road assigned yet");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("vehicleId", vehicleId);
						record2.put("status", "error");
						jsonData.add(record2);
						continue;
					}

					if(destID > 0) {
						destZone = ContextCreator.getZoneContext().get(destID);
					}
					else {
						if(ContextCreator.getZoneContext().ZONE_NUM == 1) {
							destID = 0;
							destZone = ContextCreator.getZoneContext().get(destID);
						}
						else {
							// randomly select a zone as destination
							destID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM - 1) + 1;
							destZone = ContextCreator.getZoneContext().get(destID);
						}
					}
					if(destZone == null) {
						ContextCreator.logger.warn("Cannot find the dest with ID: " + destID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehicleId);
					record2.put("status", "error");
						jsonData.add(record2);
					continue;
					}
					if(destZone.getClosestRoad(true) == null) {
						ContextCreator.logger.warn("Destination zone " + destID + " has no arrival road assigned yet");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("vehicleId", vehicleId);
						record2.put("status", "error");
						jsonData.add(record2);
						continue;
					}

					// Assign trips
					if (v == null) {
						double initialLength = vehIDVehTypeOrigDest.vehicleLength == null
								? GlobalVariables.DEFAULT_VEHICLE_LENGTH
								: vehIDVehTypeOrigDest.vehicleLength.doubleValue();
						v = new ElectricVehicle(Vehicle.EV,
								Vehicle.NONE_OF_THE_ABOVE, initialLength);
						ContextCreator.getVehicleContext().registerPrivateEV(vehicleId, v);
					}
					int origRoad = originZone.sampleRoad(false);
					v.initializePlan(originID, origRoad, (int) ContextCreator.getCurrentTick());
					v.addPlan(destID, destZone.sampleRoad(true), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure(origRoad);
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehicleId); // Note this vehicleId will be different from that obtained by veh.getID() which is generated by ContextCreator.generateAgentID();
					record2.put("status", "ok");
					record2.put("origin", originID);
					record2.put("destination",destID);
					record2.put("length", v.length());
					jsonData.add(record2);
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
	}
	return jsonAns;
    }

    /**
     * Like {@link #generateTrip} but with origin/destination specified as
     * road IDs instead of zone IDs.
     *
	* <p>Input DATA: list of {@code {vehicleId, orig, dest, num, length}} where
	* {@code orig} and {@code dest} are original road IDs and optional
	* {@code length} is the vehicle length in meters. It is used only when
	* {@code vehicleId} is first created.
     */
	private HashMap<String, Object> generateTripsByRoad(JSONObject jsonMsg) {
	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
	if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
	else {
		try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigRoadDestRoadNum>> collectionType = new TypeToken<Collection<VehIDOrigRoadDestRoadNum>>() {};
			Collection<VehIDOrigRoadDestRoadNum> vehIDVehTypeOrigDests = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			for(VehIDOrigRoadDestRoadNum vehIDVehTypeOrigDest:  vehIDVehTypeOrigDests) {
				// Get data
				int vehicleId = vehIDVehTypeOrigDest.vehicleId;
					if (!isValidOptionalVehicleLength(vehIDVehTypeOrigDest.vehicleLength)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("vehicleId", vehicleId);
						record2.put("status", "error");
						record2.put("message", invalidVehicleLengthWarning());
						jsonData.add(record2);
						continue;
					}
				ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(vehicleId);
				if(v != null) {
						if (v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
						ContextCreator.logger.warn("The private EV: " + vehicleId + " is currently on the road, maybe there are two trips for the same vehicle that are too close?");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("vehicleId", vehicleId);
						record2.put("status", "error");
							jsonData.add(record2);
						continue;
						}
				}

					// Find the origin and dest zones
					String originID = vehIDVehTypeOrigDest.originRoadId;
					String destID = vehIDVehTypeOrigDest.destinationRoadId;
					Road originRoad = null;
					Road destRoad = null;

					originRoad = ContextCreator.getCityContext().findRoadWithOrigID(originID);
					if(originRoad == null) {
						ContextCreator.logger.warn("Cannot find the origin road with ID: " + originID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehicleId);
					record2.put("status", "error");
						jsonData.add(record2);
					continue;
					}

					destRoad = ContextCreator.getCityContext().findRoadWithOrigID(destID);
					if(destRoad == null) {
						ContextCreator.logger.warn("Cannot find the dest road with ID: " + destID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehicleId);
					record2.put("status", "error");
						jsonData.add(record2);
					continue;
					}

				int originZoneID = originRoad.getNeighboringZone(false);
				int destZoneID = destRoad.getNeighboringZone(true);
				if(ContextCreator.getZoneContext().get(originZoneID) == null) {
						ContextCreator.logger.warn("Origin road " + originID + " has no neighboring zone assigned");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("vehicleId", vehicleId);
						record2.put("status", "error");
						jsonData.add(record2);
						continue;
					}
				if(ContextCreator.getZoneContext().get(destZoneID) == null) {
						ContextCreator.logger.warn("Destination road " + destID + " has no neighboring zone assigned");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("vehicleId", vehicleId);
						record2.put("status", "error");
						jsonData.add(record2);
						continue;
					}

					// Assign trips
					if (v == null) {
						double initialLength = vehIDVehTypeOrigDest.vehicleLength == null
								? GlobalVariables.DEFAULT_VEHICLE_LENGTH
								: vehIDVehTypeOrigDest.vehicleLength.doubleValue();
						v = new ElectricVehicle(Vehicle.EV,
								Vehicle.NONE_OF_THE_ABOVE, initialLength);
						ContextCreator.getVehicleContext().registerPrivateEV(vehicleId, v);
					}
					v.initializePlan(originZoneID, originRoad.getID(), (int) ContextCreator.getCurrentTick());
					v.addPlan(destZoneID, destRoad.getID(), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure(originRoad);
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehicleId); // Note this vehicleId will be different from that obtained by veh.getID() which is generated by ContextCreator.generateAgentID();
					record2.put("status", "ok");
					record2.put("origin", originID);
					record2.put("destination",destID);
					record2.put("length", v.length());
					jsonData.add(record2);
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
	}
	return jsonAns;
    }

    // =============================================================
    // VEHICLE TELEPORT & RUNTIME CONTROL
    // =============================================================

	/**
	 * Teleport an externally observed Digital Twin vehicle onto a native segment.
	 * Input DATA is an array containing exactly one position form per record:
	 *
	 * <ul>
	 * <li>{@code {vehicleId, isPrivate, positionType:"coordinate", x, y,
	 * z?, transformCoordinates?}} searches native roads and connectors.</li>
	 * <li>{@code {vehicleId, isPrivate, positionType:"segment", segmentId,
	 * distanceToSegmentEnd, laneIndex?}} uses an exact physical road and lane.</li>
	 * <li>A connector segment uses {@code connectorPathId?} instead of
	 * {@code laneIndex}; a single path, current path, or segment alias may infer it.</li>
	 * </ul>
	 *
	 * The distance is measured upstream from the segment end and is clamped to its
	 * lane/path. External observations are placed exactly without overlap checks.
	 */
	private synchronized HashMap<String, Object> teleportDigitalTwinVeh(JSONObject jsonMsg) {
		HashMap<String, Object> answer = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			answer.put("message", "No DATA field found in the control message");
			answer.put("status", "error");
			return answer;
		}
		ArrayList<Object> responseData = new ArrayList<Object>();
		int successCount = 0;
		try {
			Gson gson = new Gson();
			TypeToken<Collection<DigitalTwinTeleportRequest>> requestType =
					new TypeToken<Collection<DigitalTwinTeleportRequest>>() { };
			Collection<DigitalTwinTeleportRequest> requests = gson.fromJson(
					jsonMsg.get("data").toString(), requestType.getType());
			if (requests == null) {
				throw new IllegalArgumentException(
						"teleportDigitalTwinVeh data must be an array of objects");
			}
			if (requests.isEmpty()) {
				throw new IllegalArgumentException(
						"teleportDigitalTwinVeh data must contain at least one record");
			}
			for (DigitalTwinTeleportRequest request : requests) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", request == null ? null : request.vehicleId);
				try {
					if (request == null || request.vehicleId == null
							|| request.isPrivate == null || request.positionType == null) {
						throw new IllegalArgumentException(
								"vehicleId, isPrivate, and positionType are required");
					}
					Vehicle vehicle = request.isPrivate.booleanValue()
							? ContextCreator.getVehicleContext()
									.getPrivateVehicle(request.vehicleId.intValue())
							: ContextCreator.getVehicleContext()
									.getPublicVehicle(request.vehicleId.intValue());
					if (vehicle == null) {
						throw new IllegalArgumentException(
								"Vehicle not found for ID " + request.vehicleId);
					}
					Road currentRoad = vehicle.getRoad();
					ConnectorRoad currentConnector = vehicle.getCurrentConnector();
					if ((currentRoad != null && currentRoad.getControlType() == Road.COSIM)
							|| (currentConnector != null
									&& currentConnector.getControlType() == Road.COSIM)) {
						throw new IllegalArgumentException(
								"Digital Twin teleport cannot move a COSIM-owned vehicle");
					}

					String positionType = request.positionType.trim()
							.toLowerCase(Locale.ROOT);
					Road targetSegment;
					Lane targetLane;
					ConnectorRoad.ConnectorPath targetConnectorPath;
					double requestedDistance;
					double appliedDistance;
					CoSimMapMatcher.Match coordinateMatch = null;
					if ("coordinate".equals(positionType)) {
						if (request.x == null || request.y == null
								|| !Double.isFinite(request.x.doubleValue())
								|| !Double.isFinite(request.y.doubleValue())
								|| request.z != null && !Double.isFinite(request.z.doubleValue())) {
							throw new IllegalArgumentException(
									"Coordinate mode requires finite x and y; z is optional");
						}
						if (request.segmentId != null || request.distanceToSegmentEnd != null
								|| request.laneIndex != null || request.connectorPathId != null) {
							throw new IllegalArgumentException(
									"Coordinate mode cannot include segment, distance, lane, or connector-path fields");
						}
						Coordinate pose = new Coordinate(request.x.doubleValue(),
								request.y.doubleValue(), request.z == null
										? 0.0 : request.z.doubleValue());
						if (Boolean.TRUE.equals(request.transformCoordinates)) {
							JTS.transform(pose, pose,
									SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
						}
						List<CoSimMapMatcher.Match> matches =
								CoSimMapMatcher.nativeCandidates(vehicle, pose);
						if (matches.isEmpty()) {
							throw new IllegalArgumentException(
									"No native road or connector matches the coordinate");
						}
						coordinateMatch = matches.get(0);
						targetSegment = coordinateMatch.segment;
						targetLane = coordinateMatch.lane;
						targetConnectorPath = coordinateMatch.connectorPath;
						requestedDistance = coordinateMatch.downstreamDistance;
						appliedDistance = requestedDistance;
					} else if ("segment".equals(positionType)) {
						if (request.segmentId == null
								|| request.segmentId.trim().isEmpty()
								|| request.distanceToSegmentEnd == null
								|| !Double.isFinite(request.distanceToSegmentEnd.doubleValue())) {
							throw new IllegalArgumentException(
									"Segment mode requires segmentId and finite distanceToSegmentEnd");
						}
						if (request.x != null || request.y != null || request.z != null
								|| request.transformCoordinates != null) {
							throw new IllegalArgumentException(
									"Segment mode cannot include coordinate fields");
						}
						targetSegment = ContextCreator.getRoadContext()
								.getQueryableRoad(request.segmentId.trim());
						if (targetSegment == null) {
							throw new IllegalArgumentException(
									"Segment not found for ID " + request.segmentId);
						}
						if (targetSegment.getControlType() == Road.COSIM) {
							throw new IllegalArgumentException(
									"Digital Twin teleport requires a native target segment");
						}
						requestedDistance = request.distanceToSegmentEnd.doubleValue();
						targetConnectorPath = null;
						if (targetSegment instanceof ConnectorRoad) {
							if (request.laneIndex != null) {
								throw new IllegalArgumentException(
										"Use connectorPathId, not laneIndex, for a connector");
							}
							ConnectorRoad connector = (ConnectorRoad) targetSegment;
							if (request.connectorPathId != null) {
								targetConnectorPath = connector.getPathByID(
										request.connectorPathId.intValue());
							} else {
								targetConnectorPath = connector.getPath(request.segmentId.trim());
								if (targetConnectorPath == null
										&& vehicle.getCurrentConnector() == connector) {
									targetConnectorPath = vehicle.getCurrentConnectorPath();
								}
								if (targetConnectorPath == null && connector.getPaths().size() == 1) {
									targetConnectorPath = connector.getPaths().get(0);
								}
							}
							if (targetConnectorPath == null) {
								throw new IllegalArgumentException(
										"Connector path is invalid or ambiguous");
							}
							targetLane = connector.getLane(targetConnectorPath);
						} else {
							if (request.connectorPathId != null) {
								throw new IllegalArgumentException(
										"A physical road cannot specify connectorPathId");
							}
							int laneIndex;
							if (request.laneIndex != null) {
								laneIndex = request.laneIndex.intValue();
							} else if (vehicle.getRoad() == targetSegment
									&& vehicle.getLane() != null) {
								laneIndex = targetSegment.getLaneIndex(vehicle.getLane());
							} else {
								laneIndex = 0;
							}
							if (laneIndex < 0 || laneIndex >= targetSegment.getNumberOfLanes()) {
								throw new IllegalArgumentException("Invalid laneIndex " + laneIndex
										+ " for segment " + targetSegment.getOrigID());
							}
							targetLane = targetSegment.getLane(laneIndex);
						}
						double laneLength = targetLane == null ? Double.NaN : targetLane.getLength();
						if (!Double.isFinite(laneLength) || laneLength < 0.0) {
							throw new IllegalArgumentException("Target lane has no usable length");
						}
						appliedDistance = Math.max(0.0,
								Math.min(laneLength, requestedDistance));
					} else {
						throw new IllegalArgumentException(
								"positionType must be coordinate or segment");
					}
					vehicle.synchronizeNativeObservation(targetSegment, targetLane,
							targetConnectorPath, appliedDistance, null, null);
					record.put("inputMode", positionType);
					record.put("segmentId", targetSegment.getOrigID());
					record.put("segmentType", targetSegment instanceof ConnectorRoad
							? "connector" : "road");
					record.put("laneIndex", targetSegment instanceof ConnectorRoad
							? null : targetSegment.getLaneIndex(targetLane));
					if (targetConnectorPath != null) {
						record.put("connectorPathId",
								targetConnectorPath.getConnectorPathID());
					}
					record.put("requestedDistance", requestedDistance);
					record.put("distanceToSegmentEnd", appliedDistance);
					record.put("distanceClamped",
							Math.abs(requestedDistance - appliedDistance) > 1.0e-6);
					record.put("controlMode", "native");
					if (coordinateMatch != null) {
						record.put("lateralError", coordinateMatch.lateralDistanceMeters);
						record.put("endpointOvershoot",
								coordinateMatch.endpointOvershootMeters);
					}
					record.put("status", "ok");
					successCount++;
				} catch (Exception ex) {
					record.put("status", "error");
					record.put("message", ex.getMessage());
					ContextCreator.logger.error("Invalid Digital Twin teleport for vehicle "
							+ (request == null ? "unknown" : request.vehicleId)
							+ ": " + ex.getMessage(), ex);
				}
				responseData.add(record);
			}
			answer.put("data", responseData);
			answer.put("status", successCount == requests.size() ? "ok"
					: successCount == 0 ? "error" : "partial");
		} catch (Exception ex) {
			ContextCreator.logger.error("Error processing teleportDigitalTwinVeh: " + ex, ex);
			answer.put("message", ex.getMessage());
			answer.put("status", "error");
		}
		return answer;
	}

	/**
	* Create a private EV directly at an authoritative COSIM pose. The start lane
	* and, when applicable, connector lane pair are inferred from geometry.
	*
	* <p>Input DATA: list of {@code {vehicleId, x, y, z?, bearing, speed,
	* transformCoordinates?, length?, segmentId?/roadId?,
	* connectorPathId? (zero-based and connector-local),
	* destinationRoadId}}.
	* {@code isPrivate} may be omitted or {@code true}; public fleet vehicles require
	* their normal fleet initialization. The pose is the vehicle's front position
	* and is rejected, rather than shifted, when its footprint overlaps a vehicle.
	*/
	private synchronized HashMap<String, Object> initializeCoSimVeh(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		ArrayList<Object> jsonData = new ArrayList<Object>();
		try {
			Gson gson = new Gson();
			TypeToken<Collection<InitializeCoSimVehRequest>> collectionType =
					new TypeToken<Collection<InitializeCoSimVehRequest>>() {};
			Collection<InitializeCoSimVehRequest> requests = gson.fromJson(
					jsonMsg.get("data").toString(), collectionType.getType());
			if (requests == null) {
				throw new IllegalArgumentException("initializeCoSimVeh DATA must be an array");
			}
			for (InitializeCoSimVehRequest request : requests) {
				if (request == null) {
					jsonData.add(coSimTeleportFailure(-1, "INVALID_REQUEST",
							"Initialization record must not be null"));
					continue;
				}
				if (Boolean.FALSE.equals(request.isPrivate)) {
					jsonData.add(coSimTeleportFailure(request.vehicleId,
							"UNSUPPORTED_VEHICLE_TYPE",
							"initializeCoSimVeh currently creates private vehicles only"));
					continue;
				}
				if (ContextCreator.getVehicleContext().getPrivateVehicle(request.vehicleId) != null
						|| ContextCreator.getVehicleContext().getPublicVehicle(request.vehicleId) != null) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "VEHICLE_ALREADY_EXISTS",
							"Vehicle ID is already registered"));
					continue;
				}
				if (request.x == null || request.y == null || request.bearing == null
						|| request.speed == null || !Double.isFinite(request.x)
						|| !Double.isFinite(request.y) || !Double.isFinite(request.bearing)
						|| request.z != null && !Double.isFinite(request.z)
						|| !Double.isFinite(request.speed) || request.speed < 0.0) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "INVALID_POSE",
							"x, y, bearing, and non-negative speed must be finite"));
					continue;
				}
				if (!isValidOptionalVehicleLength(request.vehicleLength)) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "INVALID_LENGTH",
							invalidVehicleLengthWarning()));
					continue;
				}
				String destinationID = firstNonBlank(
						request.destinationRoadId, request.destinationRoadId);
				Road destinationRoad = destinationID == null ? null
						: ContextCreator.getCityContext().findRoadWithOrigID(destinationID);
				if (destinationRoad == null) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "DESTINATION_ROAD_NOT_FOUND",
							"destinationRoadId must identify a physical METS-R road"));
					continue;
				}
				if (ContextCreator.getZoneContext().get(
						destinationRoad.getNeighboringZone(true)) == null) {
					jsonData.add(coSimTeleportFailure(request.vehicleId,
							"DESTINATION_ZONE_NOT_FOUND",
							"destinationRoadId has no METS-R destination zone"));
					continue;
				}

				Coordinate pose = new Coordinate(request.x.doubleValue(),
						request.y.doubleValue(), request.z == null ? 0.0 : request.z.doubleValue());
				if (request.transformCoordinates) {
					try {
						JTS.transform(pose, pose,
								SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
					} catch (TransformException ex) {
						jsonData.add(coSimTeleportFailure(request.vehicleId,
								"COORDINATE_TRANSFORM_FAILED", ex.getMessage()));
						continue;
					}
				}
				String segmentHint = request.segmentId;
				List<CoSimMapMatcher.Match> matches = CoSimMapMatcher.candidates(
						null, pose, request.bearing.doubleValue(), segmentHint,
						request.connectorPathId);
				if (matches.isEmpty()) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "NO_MAP_MATCH",
							"No controlled road, lane, or connector matches the authoritative pose"));
					continue;
				}
				double vehicleLength = request.vehicleLength == null
						? GlobalVariables.DEFAULT_VEHICLE_LENGTH : request.vehicleLength.doubleValue();
				ElectricVehicle candidateVehicle = new ElectricVehicle(
						Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE, vehicleLength);
				if (CoSimMapMatcher.overlapsAnyVehicle(candidateVehicle, pose,
						request.bearing.doubleValue(), null)) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "POSE_OVERLAP",
							"Authoritative vehicle footprint overlaps an existing vehicle"));
					continue;
				}

				ElectricVehicle vehicle = null;
				CoSimMapMatcher.Match applied = null;
				String lastFailure = null;
				for (CoSimMapMatcher.Match match : matches) {
					try {
						initializeCoSimVehicleOnMatch(candidateVehicle, match, pose,
								request.bearing.doubleValue(), request.speed.doubleValue(),
								destinationRoad);
						vehicle = candidateVehicle;
						applied = match;
						break;
					} catch (RuntimeException ex) {
						lastFailure = ex.getMessage();
						if (candidateVehicle.isOnRoad()) candidateVehicle.leaveNetwork();
						candidateVehicle = new ElectricVehicle(
								Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE, vehicleLength);
					}
				}
				if (vehicle == null || applied == null) {
					jsonData.add(coSimTeleportFailure(request.vehicleId,
							"INITIALIZATION_FAILED", lastFailure));
					continue;
				}
				ContextCreator.getVehicleContext().registerPrivateEV(request.vehicleId, vehicle);
				recordCoSimTeleportSnapshot(vehicle);
				Road startRoad = applied.isConnector()
						? ((ConnectorRoad) applied.segment).getSourceRoad() : applied.segment;
				Lane startLane = applied.isConnector()
						? applied.connectorPath.getSourceLane() : applied.lane;
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", request.vehicleId);
				record.put("status", "ok");
				record.put("isPrivate", true);
				record.put("segmentId", applied.segment.getOrigID());
				record.put("segmentType", applied.isConnector() ? "connector" : "road");
				if (segmentHint != null) record.put("observedSegmentId", segmentHint);
				if (applied.isConnector()) {
					ConnectorRoad connector = (ConnectorRoad) applied.segment;
					record.put("connectorId", connector.getOrigID());
					record.put("connectorPathId",
							applied.connectorPath.getConnectorPathID());
					record.put("internalEdgeIds", connector.getInternalEdgeIDs());
				}
				record.put("laneIndex", applied.isConnector()
						? null : startRoad.getLaneIndex(startLane));
				record.put("inferredSourceLaneIndex", startRoad.getLaneIndex(startLane));
				record.put("lateralError", applied.lateralDistanceMeters);
				record.put("vehicleLength", vehicle.length());
				addConnectorState(record, vehicle);
				jsonData.add(record);
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception ex) {
			ContextCreator.logger.error("Error processing initializeCoSimVeh: "
					+ ex.toString(), ex);
			jsonAns.put("status", "error");
			jsonAns.put("message", ex.getMessage());
		}
		return jsonAns;
	}

	private void initializeCoSimVehicleOnMatch(ElectricVehicle vehicle,
			CoSimMapMatcher.Match match, Coordinate pose, double bearing, double speed,
			Road destinationRoad) {
		Road startRoad;
		Lane startLane;
		Road requiredNextRoad = null;
		Lane requiredNextLane = null;
		if (match.isConnector()) {
			ConnectorRoad connector = (ConnectorRoad) match.segment;
			startRoad = connector.getSourceRoad();
			startLane = match.connectorPath.getSourceLane();
			requiredNextRoad = connector.getTargetRoad();
			requiredNextLane = match.connectorPath.getTargetLane();
		} else {
			startRoad = match.segment;
			startLane = match.lane;
		}
		vehicle.initializeCoSimTripAt(startRoad, startLane,
				match.isConnector() ? 0.0 : match.downstreamDistance,
				pose, bearing, speed, destinationRoad, requiredNextRoad, requiredNextLane);
		if (!match.isConnector()) return;
		if (!vehicle.executeRoadTransition(requiredNextLane, requiredNextRoad)) {
			vehicle.leaveNetwork();
			throw new IllegalStateException(
					"Connector admission was not available for the initialized pose");
		}
		vehicle.positionInitializedCoSimConnectorVehicle(
				match.downstreamDistance, pose, bearing, speed);
	}

	private ArrayList<ConnectorRoad> coSimConnectorsForRoad(Road road) {
		ArrayList<ConnectorRoad> result = new ArrayList<ConnectorRoad>();
		for (ConnectorRoad connector : ContextCreator.getRoadContext().getAllConnectors()) {
			if ((connector.getSourceRoad() == road || connector.getTargetRoad() == road)
					&& connector.getControlType() == Road.COSIM) {
				result.add(connector);
			}
		}
		result.sort((a, b) -> a.getOrigID().compareTo(b.getOrigID()));
		return result;
	}

	private ArrayList<String> connectorIDs(Collection<ConnectorRoad> connectors) {
		ArrayList<String> result = new ArrayList<String>();
		if (connectors != null) {
			for (ConnectorRoad connector : connectors) {
				if (connector != null) result.add(connector.getOrigID());
			}
		}
		java.util.Collections.sort(result);
		return result;
	}

	private ArrayList<String> coSimConnectorIDsForRoad(Road road) {
		return connectorIDs(coSimConnectorsForRoad(road));
	}

	private HashMap<String, Object> connectorControlRecord(ConnectorRoad connector) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("connectorId", connector.getOrigID());
		record.put("sourceRoadId", connector.getSourceRoad().getOrigID());
		record.put("targetRoadId", connector.getTargetRoad().getOrigID());
		record.put("internalEdgeIds", connector.getInternalEdgeIDs());
		record.put("controlMode", controlModeName(connector.getControlType()));
		return record;
	}

	private ArrayList<Object> connectorRecords(Collection<ConnectorRoad> connectors) {
		ArrayList<Object> result = new ArrayList<Object>();
		if (connectors != null) {
			ArrayList<ConnectorRoad> ordered = new ArrayList<ConnectorRoad>(connectors);
			ordered.sort((a, b) -> a.getOrigID().compareTo(b.getOrigID()));
			for (ConnectorRoad connector : ordered) {
				if (connector != null) result.add(connectorControlRecord(connector));
			}
		}
		return result;
	}

	private ArrayList<Object> coSimConnectorRecordsForRoad(Road road) {
		return connectorRecords(coSimConnectorsForRoad(road));
	}

	private ArrayList<String> releasedConnectorIDs(List<ConnectorRoad> previouslyControlled) {
		ArrayList<ConnectorRoad> released = new ArrayList<ConnectorRoad>();
		for (ConnectorRoad connector : previouslyControlled) {
			if (connector != null && connector.getControlType() != Road.COSIM) {
				released.add(connector);
			}
		}
		return connectorIDs(released);
	}

	private ArrayList<Object> releasedConnectorRecords(List<ConnectorRoad> previouslyControlled) {
		ArrayList<ConnectorRoad> released = new ArrayList<ConnectorRoad>();
		for (ConnectorRoad connector : previouslyControlled) {
			if (connector != null && connector.getControlType() != Road.COSIM) {
				released.add(connector);
			}
		}
		return connectorRecords(released);
	}

	private void refreshIncidentConnectorControlModes(Road road) {
		if (road == null) return;
		for (ConnectorRoad connector : ContextCreator.getRoadContext().getAllConnectors()) {
			if (connector.getSourceRoad() != road && connector.getTargetRoad() != road) continue;
			boolean coSimOwned = connector.getSourceRoad().getControlType() == Road.COSIM
					|| connector.getTargetRoad().getControlType() == Road.COSIM;
			connector.setControlType(coSimOwned ? Road.COSIM : Road.NONE_OF_THE_ABOVE);
		}
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.trim().isEmpty()) return first.trim();
		if (second != null && !second.trim().isEmpty()) return second.trim();
		return null;
	}

	/**
	 * Apply an authoritative co-simulation observation. Input DATA is an array of
	 * {@code {vehicleId, isPrivate, x, y, z?, bearing, speed,
	 * transformCoordinates?, segmentId?, laneIndex?, connectorPathId?}}.
	 *
	 * <p>Without {@code segmentId}, coordinates are matched only against currently
	 * controlled COSIM roads and connectors. With {@code segmentId}, that segment
	 * is authoritative: {@code laneIndex} optionally selects a physical-road lane,
	 * while {@code connectorPathId} optionally selects a connector path. If the
	 * explicit segment is native, a currently COSIM-owned vehicle is released to
	 * native simulation and its route/lane state is rebuilt from that placement.
	 * Geometry discrepancies on authoritative segments are reported as warnings.
	 */
	private synchronized HashMap<String, Object> teleportCoSimVeh(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		ArrayList<Object> jsonData = new ArrayList<Object>();
		int successCount = 0;
		try {
			Gson gson = new Gson();
			TypeToken<Collection<CoSimTeleportRequest>> collectionType =
					new TypeToken<Collection<CoSimTeleportRequest>>() {};
			Collection<CoSimTeleportRequest> requests = gson.fromJson(
					jsonMsg.get("data").toString(), collectionType.getType());
			if (requests == null) {
				throw new IllegalArgumentException("teleportCoSimVeh DATA must be an array");
			}
			if (requests.isEmpty()) {
				throw new IllegalArgumentException(
						"teleportCoSimVeh DATA must contain at least one record");
			}
			for (CoSimTeleportRequest request : requests) {
				if (request == null || request.vehicleId == null || request.isPrivate == null
						|| request.x == null || request.y == null
						|| request.bearing == null || request.speed == null) {
					jsonData.add(coSimTeleportFailure(request == null ? null : request.vehicleId,
							"INVALID_REQUEST", "vehicleId, isPrivate, x, y, bearing, and speed are required"));
					continue;
				}
				double z = request.z == null ? 0.0 : request.z.doubleValue();
				if (!Double.isFinite(request.x.doubleValue())
						|| !Double.isFinite(request.y.doubleValue()) || !Double.isFinite(z)
						|| !Double.isFinite(request.bearing.doubleValue())
						|| !Double.isFinite(request.speed.doubleValue())
						|| request.speed.doubleValue() < 0.0) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "INVALID_POSE",
							"Pose, bearing, and non-negative speed must be finite"));
					continue;
				}
				String segmentHint = firstNonBlank(request.segmentId, null);
				if (segmentHint == null
						&& (request.laneIndex != null || request.connectorPathId != null)) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "INVALID_REQUEST",
							"laneIndex and connectorPathId require segmentId"));
					continue;
				}
				Vehicle vehicle = request.isPrivate.booleanValue()
						? ContextCreator.getVehicleContext().getPrivateVehicle(request.vehicleId.intValue())
						: ContextCreator.getVehicleContext().getPublicVehicle(request.vehicleId.intValue());
				if (vehicle == null) {
					jsonData.add(coSimTeleportFailure(request.vehicleId, "VEHICLE_NOT_FOUND",
							"Vehicle not found for ID " + request.vehicleId));
					continue;
				}
				Coordinate pose = new Coordinate(request.x.doubleValue(),
						request.y.doubleValue(), z);
				if (Boolean.TRUE.equals(request.transformCoordinates)) {
					try {
						JTS.transform(pose, pose,
								SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
					} catch (TransformException ex) {
						jsonData.add(coSimTeleportFailure(request.vehicleId,
								"COORDINATE_TRANSFORM_FAILED", ex.getMessage()));
						continue;
					}
				}
				if (!Double.isFinite(pose.x) || !Double.isFinite(pose.y)
						|| !Double.isFinite(pose.z)) {
					jsonData.add(coSimTeleportFailure(request.vehicleId,
							"COORDINATE_TRANSFORM_FAILED",
							"Coordinate transform produced a non-finite pose"));
					continue;
				}
				Road suppliedSegment = null;
				List<CoSimMapMatcher.Match> matches;
				if (segmentHint != null) {
					suppliedSegment = ContextCreator.getRoadContext()
							.getQueryableRoad(segmentHint);
					if (suppliedSegment == null) {
						jsonData.add(coSimTeleportFailure(request.vehicleId,
								"SEGMENT_NOT_FOUND",
								"segmentId does not identify a METS-R road or connector"));
						continue;
					}
					if (suppliedSegment instanceof ConnectorRoad) {
						if (request.laneIndex != null) {
							jsonData.add(coSimTeleportFailure(request.vehicleId,
									"INVALID_LANE_SELECTOR",
									"Use connectorPathId, not laneIndex, for a connector"));
							continue;
						}
						if (request.connectorPathId != null
								&& request.connectorPathId.intValue() < 0) {
							jsonData.add(coSimTeleportFailure(request.vehicleId,
									"INVALID_CONNECTOR_PATH", "connectorPathId must be non-negative"));
							continue;
						}
					} else {
						if (request.connectorPathId != null) {
							jsonData.add(coSimTeleportFailure(request.vehicleId,
									"INVALID_CONNECTOR_PATH",
									"A physical road cannot specify connectorPathId"));
							continue;
						}
						if (request.laneIndex != null
								&& (request.laneIndex.intValue() < 0
										|| request.laneIndex.intValue()
												>= suppliedSegment.getNumberOfLanes())) {
							jsonData.add(coSimTeleportFailure(request.vehicleId,
									"INVALID_LANE_SELECTOR", "laneIndex is outside the segment"));
							continue;
						}
					}
					matches = CoSimMapMatcher.candidatesOnSegment(vehicle, pose,
							request.bearing.doubleValue(), suppliedSegment, segmentHint,
							request.laneIndex, request.connectorPathId);
				} else {
					matches = CoSimMapMatcher.candidates(vehicle, pose,
							request.bearing.doubleValue(), null, null);
				}
				if (matches.isEmpty()) {
					String errorCode = segmentHint == null
							? "NO_MAP_MATCH" : "SEGMENT_GEOMETRY_UNAVAILABLE";
					String message = segmentHint == null
							? "No controlled road, lane, or connector can be associated with the pose"
							: "The authoritative segment has no usable lane or connector geometry";
					jsonData.add(coSimTeleportFailure(request.vehicleId, errorCode, message));
					continue;
				}
				CoSimMapMatcher.Match applied = matches.get(0);
				boolean targetNative = applied.segment.getControlType() != Road.COSIM;
				if (targetNative) {
					Road currentRoad = vehicle.getRoad();
					ConnectorRoad currentConnector = vehicle.getCurrentConnector();
					boolean currentlyCoSimOwned = (currentRoad != null
							&& currentRoad.getControlType() == Road.COSIM)
							|| (currentConnector != null
									&& currentConnector.getControlType() == Road.COSIM);
					if (!currentlyCoSimOwned) {
						jsonData.add(coSimTeleportFailure(request.vehicleId,
								"VEHICLE_NOT_COSIM",
								"A native target is only valid when releasing a COSIM-owned vehicle"));
						continue;
					}
				}
				Coordinate previousPose = vehicle.getCurrentCoord() == null
						? null : new Coordinate(vehicle.getCurrentCoord());
				boolean releasedFromCoSim = false;
				try {
					if (targetNative) {
						releasedFromCoSim = vehicle.synchronizeNativeObservation(
								applied.segment, applied.lane, applied.connectorPath,
								applied.downstreamDistance, request.bearing, request.speed);
					} else {
						vehicle.synchronizeAuthoritativeCoSimObservation(
								applied.segment, applied.lane, applied.connectorPath,
								applied.downstreamDistance, pose,
								request.bearing.doubleValue(), request.speed.doubleValue());
					}
				} catch (RuntimeException ex) {
					jsonData.add(coSimTeleportFailure(request.vehicleId,
							targetNative ? "NATIVE_HANDOFF_FAILED" : "MIRROR_UPDATE_FAILED",
							ex.getMessage()));
					continue;
				}

				recordCoSimTeleportSnapshot(vehicle);
				ArrayList<String> warnings = coSimObservationWarnings(
						previousPose, pose, applied);
				for (String warning : warnings) {
					ContextCreator.logger.warn("External pose vehicle="
							+ request.vehicleId + ": " + warning);
				}
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", request.vehicleId);
				record.put("status", "ok");
				record.put("segmentId", applied.segment.getOrigID());
				record.put("segmentType", applied.isConnector() ? "connector" : "road");
				if (segmentHint != null) record.put("observedSegmentId", segmentHint);
				if (applied.isConnector()) {
					ConnectorRoad connector = (ConnectorRoad) applied.segment;
					record.put("connectorId", connector.getOrigID());
					record.put("connectorPathId",
							applied.connectorPath.getConnectorPathID());
					record.put("internalEdgeIds", connector.getInternalEdgeIDs());
				}
				record.put("laneIndex", applied.isConnector()
						? null
						: applied.segment.getLaneIndex(applied.lane));
				record.put("segmentAuthoritative", segmentHint != null);
				record.put("segmentInferred", segmentHint == null);
				record.put("laneInferred", applied.isConnector()
						? request.connectorPathId == null : request.laneIndex == null);
				record.put("lateralError", applied.lateralDistanceMeters);
				record.put("headingError", applied.headingErrorDegrees);
				record.put("endpointOvershoot", applied.endpointOvershootMeters);
				record.put("distanceToSegmentEnd", applied.downstreamDistance);
				record.put("controlMode", targetNative ? "native" : "cosim");
				record.put("releasedFromCoSim", releasedFromCoSim);
				if (!warnings.isEmpty()) {
					record.put("warnings", warnings);
				}
				addConnectorState(record, vehicle);
				jsonData.add(record);
				successCount++;
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", successCount == requests.size() ? "ok"
					: successCount == 0 ? "error" : "partial");
		} catch (Exception ex) {
			ContextCreator.logger.error("Error processing teleportCoSimVeh: "
					+ ex.toString(), ex);
			jsonAns.put("status", "error");
			jsonAns.put("message", ex.getMessage());
		}
		return jsonAns;
	}

	private ArrayList<String> coSimObservationWarnings(
			Coordinate previousPose, Coordinate authoritativePose,
			CoSimMapMatcher.Match match) {
		ArrayList<String> warnings = new ArrayList<String>();
		if (previousPose != null && authoritativePose != null) {
			double displacement = ContextCreator.getCityContext()
					.getDistance(previousPose, authoritativePose);
			if (Double.isFinite(displacement)
					&& displacement > COSIM_LARGE_DISPLACEMENT_WARNING_METERS) {
				warnings.add("Large authoritative displacement of " + displacement
						+ " meters was accepted");
			}
		}
		if (match != null && match.hasGeometryDiscrepancy()) {
			warnings.add("Authoritative pose disagrees with segment geometry"
					+ " (lateralError=" + match.lateralDistanceMeters
					+ ", headingError=" + match.headingErrorDegrees
					+ ", endpointOvershoot=" + match.endpointOvershootMeters
					+ "); membership was accepted");
		}
		return warnings;
	}

	private HashMap<String, Object> coSimTeleportFailure(Integer vehicleID, String reason,
			String warning) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("vehicleId", vehicleID);
		record.put("status", "error");
		record.put("errorCode", reason);
		if (warning != null && !warning.isEmpty()) record.put("message", warning);
		return record;
	}

	private void recordCoSimTeleportSnapshot(Vehicle veh) {
		if (!GlobalVariables.ENABLE_DATA_COLLECTION || ContextCreator.dataCollector == null) {
			return;
		}
		try {
			ContextCreator.dataCollector.recordSnapshotIfTickActive(veh, veh.getCurrentCoord());
		} catch (Throwable t) {
			ContextCreator.logger.debug("Failed to record CoSim teleport snapshot for vehicle "
					+ veh.getID() + ": " + t.getMessage());
		}
	}

	/**
	* Override a vehicle's acceleration for the next tick. Must be called
	* at tick t to take effect during the t-to-t+1 interval; it bypasses
	* the car-following model's acceleration decision.
	*
	* <p>Input DATA: list of {@code {vehicleId, isPrivate, acceleration}}.
	*/
	private HashMap<String, Object> controlVeh(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
		try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeAcc>> collectionType = new TypeToken<Collection<VehIDVehTypeAcc>>() {};
			Collection<VehIDVehTypeAcc> vehIDVehTypeAccs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (VehIDVehTypeAcc vehIDVehTypeAcc: vehIDVehTypeAccs) {
				// Get vehicle
					Vehicle veh = null;
					if(vehIDVehTypeAcc.isPrivate) {
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeAcc.vehicleId);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeAcc.vehicleId);
					}
					double acceleration = vehIDVehTypeAcc.acceleration;
					// Register its acceleration
					if(veh.controlVehicleAcc(acceleration)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehIDVehTypeAcc.vehicleId);
					record2.put("status", "ok");
						jsonData.add(record2);
					}
					else{
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehIDVehTypeAcc.vehicleId);
					record2.put("status", "error");
						jsonData.add(record2);
					}
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Mark electric taxis as attack vehicles for trajectory visualization.
	* The designation lasts only for the current trip and is cleared when the
	* vehicle reaches its destination.
	*
	* <p>Input DATA: list of {@code {vehicleId, isPrivate, attackEnabled}}.
	*/
	private HashMap<String, Object> setAttackVehicle(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}

		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDVehTypeAttack>> collectionType =
					new TypeToken<Collection<VehIDVehTypeAttack>>() {};
			Collection<VehIDVehTypeAttack> requests =
					gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (VehIDVehTypeAttack request : requests) {
				Vehicle vehicle = request.isPrivate
						? ContextCreator.getVehicleContext().getPrivateVehicle(request.vehicleId)
						: ContextCreator.getVehicleContext().getPublicVehicle(request.vehicleId);
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", request.vehicleId);
				if (vehicle instanceof ElectricTaxi) {
					vehicle.setAttackVehicle(request.attackEnabled);
					record.put("status", "ok");
				} else {
					record.put("status", "error");
				}
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error setting attack vehicle state: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	private void addConnectorState(Map<String, Object> record, Vehicle vehicle) {
		ConnectorRoad connector = vehicle != null && vehicle.isOnConnector()
				? vehicle.getCurrentConnector() : null;
		if (connector != null) {
			record.put("roadId", connector.getOrigID());
			record.put("connectorId", connector.getOrigID());
			record.put("internalEdgeIds", connector.getInternalEdgeIDs());
			record.put("laneIndex", ConnectorRoad.NO_LANE);
			record.put("sourceRoadId", connector.getSourceRoad().getOrigID());
			record.put("targetRoadId", connector.getTargetRoad().getOrigID());
			record.put("intersectionId", connector.getIntersectionID());
			ConnectorRoad.ConnectorPath connectorPath = vehicle.getCurrentConnectorPath();
			if (connectorPath != null) {
				record.put("connectorPathId", connectorPath.getConnectorPathID());
				record.put("connectorPathInternalEdgeIds",
						connectorPath.getInternalEdgeIDs());
			}
		}
	}

//	// Find the closest lane end coords in coSim Road, teleport the vehicle to the lane in METS-R SIM
//	// Trigger the internal road-transition primitive and check whether it succeeds
//	private HashMap<String, Object> exitCoSimRegion(JSONObject jsonMsg){
//		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
//		if(!jsonMsg.containsKey("data")) {
//			jsonAns.put("message", "No DATA field found in the control message");
//			jsonAns.put("status", "error");
//		}
//		else {
//	    	try {
//				Gson gson = new Gson();
//				TypeToken<Collection<VehIDVehTypeTranXY>> collectionType = new TypeToken<Collection<VehIDVehTypeTranXY>>() {
//				};
//				Collection<VehIDVehTypeTranXY> vehIDVehTypeTranXYs = gson
//						.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
//				ArrayList<Object> jsonData = new ArrayList<Object>();
//
//				for (VehIDVehTypeTranXY vehIDVehTypeTranXY : vehIDVehTypeTranXYs) {
//					// Get data
//					Vehicle veh = null;
//					if (vehIDVehTypeTranXY.isPrivate) {
//						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeTranXY.vehicleId);
//					} else {
//						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeTranXY.vehicleId);
//					}
//
//					if (veh != null) {
//						double x = vehIDVehTypeTranXY.x;
//						double y = vehIDVehTypeTranXY.y;
//						// Transform coordinates if needed
//						if (vehIDVehTypeTranXY.transformCoordinates) {
//							Coordinate coord = new Coordinate(x, y);
//							try {
//								JTS.transform(coord, coord,
//										SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
//								x = coord.x;
//								y = coord.y;
//							} catch (TransformException e) {
//								ContextCreator.logger
//										.error("Coordinates transformation failed, input x: " + x + " y:" + y);
//								e.printStackTrace();
//							}
//						}
//
//						// Find the closest road
//						Coordinate coord2 = new Coordinate();
//						coord2.x = x;
//						coord2.y = y;
//						Road road = ContextCreator.getCityContext().findRoadAtCoordinates(coord2, true);
//						Lane lane = null;
//						if(road != null) {
//							// Find the current lane
//							double minDist = Double.MAX_VALUE;
//							for(Lane l: road.getLanes()) {
//								double currentDist = ContextCreator.getCityContext().getDistance(l.getEndCoord(), coord2);
//								if( currentDist < minDist) {
//									minDist = currentDist;
//									lane = l;
//								}
//							}
//							if(lane != null) {
//								// Insert vehicle to the end of lane
//								veh.removeFromCurrentLane();
//								veh.removeFromCurrentRoad();
//								veh.appendToRoad(road);
//								veh.teleportToLane(lane, 0);
//
//								// Enter next road
//								if(veh.changeRoad()) {
//									HashMap<String, Object> record2 = new HashMap<String, Object>();
//						    		record2.put("vehicleId", vehIDVehTypeTranXY.vehicleId);
//						    		record2.put("status", "ok");
//									jsonData.add(record2);
//									continue;
//								}
//							}
//						}
//					}
//					HashMap<String, Object> record2 = new HashMap<String, Object>();
//					record2.put("vehicleId", vehIDVehTypeTranXY.vehicleId);
//					record2.put("status", "error");
//					jsonData.add(record2);
//				}
//				jsonAns.put("data", jsonData);
//				jsonAns.put("status", "ok");
//			}
//			catch (Exception e) {
//			    // Log error and return KO in case of exception
//			    ContextCreator.logger.error("Error processing control: " + e.toString());
//			    jsonAns.put("status", "error");
//			}
//		}
//		return jsonAns;
//	}
//
	/**
	* Mark a vehicle as having reached its destination. Mainly used when
	* the destination road is under co-simulation control, where METS-R
	* cannot observe arrival natively.
	*
	* <p>Input DATA: list of {@code {vehicleId, isPrivate}}.
	*/
	private HashMap<String, Object> reachDest(JSONObject jsonMsg){
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehType>> collectionType = new TypeToken<Collection<VehIDVehType>>() {};
			Collection<VehIDVehType> vehIDVehTypes = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for(VehIDVehType vehIDVehType: vehIDVehTypes) {
				Vehicle veh = null;
				if(vehIDVehType.isPrivate) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehType.vehicleId);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehType.vehicleId);
					}
				if(veh != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					veh.reachDest();
					record2.put("status", "ok");
						jsonData.add(record2);
				}
				else {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehIDVehType.vehicleId);
					record2.put("status", "error");
						jsonData.add(record2);
				}
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");

			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Update the sensor-type tag of one or more vehicles. The tag is
	* consumed by sensor-dependent logic such as perception models.
	*
	* <p>Input DATA: list of {@code {vehicleId, isPrivate, sensorType}}.
	*/
	private HashMap<String, Object> updateVehicleSensorType(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeSensorType>> collectionType = new TypeToken<Collection<VehIDVehTypeSensorType>>() {};
			Collection<VehIDVehTypeSensorType> vehIDVehTypeSensorTypes = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for(VehIDVehTypeSensorType vehIDVehTypeSensorType: vehIDVehTypeSensorTypes) {
				Vehicle veh = null;
				if(vehIDVehTypeSensorType.isPrivate) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeSensorType.vehicleId);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeSensorType.vehicleId);
					}
				if(veh != null) {
					veh.setVehicleSensorType(vehIDVehTypeSensorType.sensorType);
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehIDVehTypeSensorType.vehicleId);
					record2.put("status", "ok");
						jsonData.add(record2);
				}
				else {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehIDVehTypeSensorType.vehicleId);
					record2.put("status", "error");
						jsonData.add(record2);
				}
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");

			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	// =============================================================
	// RIDE-HAILING: DISPATCH & REPOSITIONING
	// =============================================================

	/**
	* Cancel taxi or bus requests by request ID and origin zone ID.
	*
	* <p>Input DATA: a list of objects with {@code requestId} and {@code zoneId}.
	* Pending requests are removed from the specified zone queue and counted as
	* passengers who left. Matched taxi pickup requests are removed from the
	* taxi's pickup queue and trip plan; if the active pickup is removed, the
	* taxi advances to its next queued trip with {@link Vehicle#setNextPlan()}.
	* Occupied taxi requests are not cancellable.
	*/
	private HashMap<String, Object> cancelRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			ArrayList<CancelRequestEntry> entries = parseCancelRequestEntries(jsonMsg.get("data"));
			if (entries.isEmpty()) {
				jsonAns.put("message", "No request entries found in DATA");
				jsonAns.put("status", "error");
				return jsonAns;
			}
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (CancelRequestEntry entry : entries) {
				Integer requestId = entry.requestId;
				Integer zoneId = entry.zoneId;
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("requestId", requestId);
				record.put("zoneId", zoneId);
				if (requestId == null) {
					record.put("status", "error");
					record.put("message", "request ID missing");
					jsonData.add(record);
					continue;
				}
				if (zoneId == null) {
					record.put("status", "error");
					record.put("message", "zone ID missing");
					jsonData.add(record);
					continue;
				}
				if (ContextCreator.getZoneContext().get(zoneId) == null) {
					record.put("status", "error");
					record.put("message", "zone not found");
					jsonData.add(record);
					continue;
				}

				PendingTaxiRequestRef pendingTaxi = findAndRemovePendingTaxiRequest(requestId, zoneId);
				if (pendingTaxi != null) {
					recordTaxiRequestLeft(pendingTaxi);
					SimulationEventJournal.record("cancellation", -1, pendingTaxi.request,
							pendingTaxi.zone.getID());
					record.put("mode", "taxi");
					record.put("requestState", "pending");
					record.put("action", "left");
					record.put("originZoneId", pendingTaxi.request.getOriginZone());
					record.put("destinationZoneId", pendingTaxi.request.getDestZone());
					record.put("status", "ok");
					jsonData.add(record);
					continue;
				}

				PendingBusRequestRef pendingBus = findAndRemovePendingBusRequest(requestId, zoneId);
				if (pendingBus != null) {
					recordBusRequestLeft(pendingBus);
					record.put("mode", "bus");
					record.put("requestState", "pending");
					record.put("action", "left");
					record.put("originZoneId", pendingBus.request.getOriginZone());
					record.put("destinationZoneId", pendingBus.request.getDestZone());
					record.put("status", "ok");
					jsonData.add(record);
					continue;
				}

				MatchedTaxiCancelResult matchedTaxi = cancelMatchedTaxiRequest(requestId, zoneId);
				if (matchedTaxi != null) {
					if (matchedTaxi.statusOK) {
						SimulationEventJournal.record("cancellation", matchedTaxi.vehicleID,
								matchedTaxi.request, matchedTaxi.request.getOriginZone());
					}
					record.put("mode", "taxi");
					record.put("requestState", "matched");
					record.put("vehicleId", matchedTaxi.vehicleID);
					record.put("removedPickupTrip", matchedTaxi.removedPickupTrip);
					record.put("removedDropoffTrip", matchedTaxi.removedDropoffTrip);
					record.put("currentTripRemoved", matchedTaxi.currentTripRemoved);
					record.put("startedNextTrip", matchedTaxi.startedNextTrip);
					record.put("availableAfterCancellation", matchedTaxi.availableAfterCancellation);
					if (matchedTaxi.availableZone >= 0) {
						record.put("availableZone", matchedTaxi.availableZone);
					}
					record.put("originZoneId", matchedTaxi.request.getOriginZone());
					record.put("destinationZoneId", matchedTaxi.request.getDestZone());
					if (matchedTaxi.warn != null) {
						record.put("message", matchedTaxi.warn);
					}
					record.put("status", matchedTaxi.statusOK ? "ok" : "error");
					jsonData.add(record);
					continue;
				}

				MatchedBusCancelResult matchedBus = cancelMatchedBusRequest(requestId, zoneId);
				if (matchedBus != null) {
					record.put("mode", "bus");
					record.put("requestState", "matched");
					record.put("vehicleId", matchedBus.vehicleID);
					record.put("stopIndex", matchedBus.stopIndex);
					record.put("onBoard", matchedBus.onBoard);
					record.put("originZoneId", matchedBus.request.getOriginZone());
					record.put("destinationZoneId", matchedBus.request.getDestZone());
					if (matchedBus.warn != null) {
						record.put("message", matchedBus.warn);
					}
					record.put("status", matchedBus.statusOK ? "ok" : "error");
					jsonData.add(record);
					continue;
				}

				record.put("status", "error");
				record.put("message", "request not found");
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing cancelRequests: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	/**
	* Dispatch a taxi to serve an already-pending request.
	*
	* <p>Input DATA: list of {@code {vehicleId, requestId}}. The request must have
	* been added via {@code addTaxiRequests} or {@code addTaxiReqBwRoads}
	* (or generated by the simulation itself) and must still be pending.
	* This endpoint does NOT fabricate new requests &mdash; it only matches
	* a taxi to an existing pending request, takes the taxi out
	* of any stale available / relocation taxi pool, and either starts its
	* pickup trip or queues the pickup after the taxi's current trip.
	*
	* <p>Both {@code "dispatchTaxi"} and {@code "dispTaxiBwRoads"} message
	* types route to this handler; the {@code BwRoads} suffix is preserved
	* only as a backward-compatibility alias.
	*
	* <p>Output DATA: list of {@code {ID: vehicleId, requestId, origZone, destZone,
	* STATUS, WARN?}} entries.
	*/
	private HashMap<String, Object> dispatchTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			Set<String> responseFields = responseFieldMask(jsonMsg);
			TypeToken<Collection<VehIDReqID>> collectionType = new TypeToken<Collection<VehIDReqID>>() {};
			Collection<VehIDReqID> entries = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for(VehIDReqID entry: entries) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", entry.vehicleId);
				record.put("requestId", entry.requestId);

				Vehicle publicVehicle = ContextCreator.getVehicleContext().getPublicVehicle(entry.vehicleId);
				if (!(publicVehicle instanceof ElectricTaxi)) {
					ContextCreator.logger.warn("dispatchTaxi: taxi " + entry.vehicleId + " not found");
					record.put("status", "error");
					record.put("message", "taxi not found");
					jsonData.add(record);
					continue;
				}
				ElectricTaxi veh = (ElectricTaxi) publicVehicle;

				if (isTaxiChargingOrOnChargingTrip(veh)) {
					ContextCreator.logger.warn("dispatchTaxi: vehicle " + entry.vehicleId + " is charging or on a charging trip");
					removeTaxiFromDispatchPools(veh);
					record.put("status", "error");
					record.put("message", "vehicle is charging or on a charging trip");
					jsonData.add(record);
					continue;
				}

				PendingTaxiRequestRef found = findAndRemovePendingTaxiRequest(entry.requestId, entry.originZoneId);
				if (found == null) {
					ContextCreator.logger.warn("dispatchTaxi: request " + entry.requestId + " not found in any pending taxi queue");
					record.put("status", "error");
					record.put("message", "request not pending");
					jsonData.add(record);
					continue;
				}

				Zone origZone = found.zone;
				Zone destZone = ContextCreator.getZoneContext().get(found.request.getDestZone());
				if (destZone == null) {
					ContextCreator.logger.warn("dispatchTaxi: destination zone " + found.request.getDestZone() + " for request " + entry.requestId + " not found; re-queueing request");
					reinsertPendingTaxiRequest(found);
					record.put("status", "error");
					record.put("message", "destination zone not found");
					jsonData.add(record);
					continue;
				}

				Request p = found.request;
				int remainingCapacity = veh.remainingCapacity();
				if (remainingCapacity < p.getNumPeople()) {
					ContextCreator.logger.warn("dispatchTaxi: vehicle " + entry.vehicleId + " remaining capacity "
							+ remainingCapacity + " is smaller than request " + entry.requestId + " passenger number "
							+ p.getNumPeople() + "; re-queueing request");
					reinsertPendingTaxiRequest(found);
					record.put("status", "error");
					record.put("message", "remaining capacity is smaller than request passenger number");
					record.put("remainingCapacity", remainingCapacity);
					record.put("requestPassengers", p.getNumPeople());
					jsonData.add(record);
					continue;
				}

				// Take the taxi out of its current zone's available pool and
				// relocation pool. Removing from all zones clears stale pool
				// membership left by external control decisions.
				int curZoneID = veh.getCurrentZone();
				int state = veh.getState();
				removeTaxiFromDispatchPools(veh);
				boolean releasedParkingReservation = false;
				if (veh.getState() == Vehicle.PARKING) {
					Zone parkedZone = ContextCreator.getZoneContext().get(curZoneID);
					veh.releaseParkingSpot(parkedZone);
				} else if (veh.isGoingToReservedParking()) {
					// The current leg can still finish, so its futureSupply is
					// consumed normally at arrival; release only the reserved capacity.
					Zone parkingZone = ContextCreator.getZoneContext().get(veh.getDestID());
					releasedParkingReservation = veh.releaseParkingSpot(parkingZone);
				}

				p.matchedTime = ContextCreator.getCurrentTick();
				SimulationEventJournal.record("match", veh.getID(), p, origZone.getID());
				origZone.taxiPickupRequest += 1;
				origZone.taxiPickupPassengers += p.getNumPeople();
				origZone.taxiServedPassWaitingTime += p.getCurrentWaitingTime();

				if (shouldStartDispatchImmediately(state)) {
					destZone.addFutureSupply();
					ArrayList<Request> plist = new ArrayList<Request>();
					plist.add(p);
					removeVehicleFromEnteringQueues(veh);
					veh.servePassenger(plist);
				} else {
					veh.queuePassengerAfterCurrentTrip(p);
				}

				record.put("originZoneId", origZone.getID());
				record.put("destinationZoneId", destZone.getID());
				if (releasedParkingReservation) {
					record.put("parkingReservationReleased", true);
				}
				record.put("status", "ok");
				addDispatchResponseFields(record, veh, responseFields);
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing dispatchTaxi: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	private boolean isTaxiChargingOrOnChargingTrip(ElectricTaxi taxi) {
		return taxi.getState() == Vehicle.CHARGING_TRIP || taxi.isOnChargingRoute();
	}

	private boolean shouldStartDispatchImmediately(int taxiState) {
		return taxiState == Vehicle.PARKING
				|| taxiState == Vehicle.CRUISING_TRIP
				|| taxiState == Vehicle.NONE_OF_THE_ABOVE;
	}

	private void removeTaxiFromDispatchPools(ElectricTaxi taxi) {
		ContextCreator.getVehicleContext().removeAvailableTaxiFromAllZones(taxi);
		ContextCreator.getVehicleContext().removeRelocationTaxiFromAllZones(taxi);
	}

	private Set<String> responseFieldMask(Map<?, ?> jsonMsg) {
		HashSet<String> fields = new HashSet<String>();
		Object raw = jsonMsg.get("fields");
		if (raw instanceof Collection<?>) {
			for (Object value : (Collection<?>) raw) fields.add(String.valueOf(value));
		} else if (raw != null) {
			for (String value : String.valueOf(raw).split(",")) fields.add(value.trim());
		}
		if (Boolean.TRUE.equals(jsonMsg.get("includeDetails"))) fields.add("*");
		return fields;
	}

	private boolean wantsField(Set<String> fields, String field) {
		return fields.contains("*") || fields.contains(field);
	}

	private void addRequestResponseFields(HashMap<String, Object> record, Request request,
			Set<String> fields) {
		if (wantsField(fields, "originZoneId")) record.put("originZoneId", request.getOriginZone());
		if (wantsField(fields, "destinationZoneId")) record.put("destinationZoneId", request.getDestZone());
		if (wantsField(fields, "originRoadId")) {
			Road road = ContextCreator.getRoadContext().get(request.getOriginRoad());
			record.put("originRoadId", road == null ? request.getOriginRoad() : road.getOrigID());
		}
		if (wantsField(fields, "destinationRoadId")) {
			Road road = ContextCreator.getRoadContext().get(request.getDestRoad());
			record.put("destinationRoadId", road == null ? request.getDestRoad() : road.getOrigID());
		}
		if (wantsField(fields, "generationTick")) record.put("generationTick", request.generationTime);
		if (wantsField(fields, "waitingLimit")) record.put("waitingLimit", request.getMaxWaitingTime());
	}

	private void addDispatchResponseFields(HashMap<String, Object> record, ElectricTaxi taxi,
			Set<String> fields) {
		if (wantsField(fields, "state")) record.put("state", taxi.getState());
		if (wantsField(fields, "coordinates")) {
			Coordinate coordinate = taxi.getCurrentCoord();
			if (coordinate != null) {
				record.put("x", coordinate.x);
				record.put("y", coordinate.y);
				record.put("z", coordinate.z);
			}
		}
		if (wantsField(fields, "currentZoneId")) record.put("currentZoneId", taxi.getCurrentZone());
		if (wantsField(fields, "destinationZoneId")) record.put("destinationZoneId", taxi.getDestID());
		if (wantsField(fields, "remainingDistance")) {
			record.put("remainingDistance", Math.max(0.0, taxi.getDistToTravel()));
		}
		if (wantsField(fields, "requestIds")) {
			record.put("toBoardRequestIds", requestIDs(taxi.getToBoardRequests()));
			record.put("onBoardRequestIds", requestIDs(taxi.getOnBoardRequests()));
		}
	}

	private ArrayList<Integer> requestIDs(Queue<Request> requests) {
		ArrayList<Integer> ids = new ArrayList<Integer>();
		if (requests != null) {
			for (Request request : requests) {
				if (request != null) ids.add(request.getID());
			}
		}
		return ids;
	}

	private boolean cancelParkingReservationForRedirect(ElectricTaxi taxi) {
		if (taxi == null || !taxi.isGoingToReservedParking()) {
			return false;
		}
		int oldParkingZoneID = taxi.getDestID();
		Zone oldParkingZone = ContextCreator.getZoneContext().get(oldParkingZoneID);
		boolean released = taxi.releaseParkingSpot(oldParkingZone);
		if (oldParkingZone != null) {
			oldParkingZone.removeFutureSupply();
		}
		return released;
	}

	/**
	* Reposition a taxi to a destination zone.
	*
	* <p>Input DATA: list of {@code {vehicleId, zoneId}}. The taxi must
	* currently be idle (state {@code PARKING}, {@code CRUISING_TRIP}, or
	* {@code NONE_OF_THE_ABOVE}) or already traveling to a reserved parking
	* road; it is removed from its current zone's available pool / parking
	* stock and dispatched on an {@code INACCESSIBLE_RELOCATION_TRIP} to a road sampled from the
	* destination zone. On arrival, {@code reachDest} either parks/cruises
	* normally or, when repositioning is API-controlled, leaves the taxi
	* idle in state {@code NONE_OF_THE_ABOVE}.
	*
	* <p>Output DATA: list of {@code {ID: vehicleId, zoneId, origZone, STATUS,
	* WARN?}} entries.
	*/
	private HashMap<String, Object> repositionTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDZoneID>> collectionType = new TypeToken<Collection<VehIDZoneID>>() {};
			Collection<VehIDZoneID> entries = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for(VehIDZoneID entry: entries) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", entry.vehicleId);
				record.put("zoneId", entry.zoneId);

				ElectricTaxi veh = (ElectricTaxi) ContextCreator.getVehicleContext().getPublicVehicle(entry.vehicleId);
				if (veh == null) {
					ContextCreator.logger.warn("repositionTaxi: vehicle " + entry.vehicleId + " not found");
					record.put("status", "error");
					record.put("message", "vehicle not found");
					jsonData.add(record);
					continue;
				}

				Zone destZone = ContextCreator.getZoneContext().get(entry.zoneId);
				if (destZone == null) {
					ContextCreator.logger.warn("repositionTaxi: destination zone " + entry.zoneId + " not found");
					record.put("status", "error");
					record.put("message", "destination zone not found");
					jsonData.add(record);
					continue;
				}
				if (destZone.getClosestRoad(true) == null) {
					ContextCreator.logger.warn("repositionTaxi: destination zone " + entry.zoneId + " has no road assigned yet");
					record.put("status", "error");
					record.put("message", "destination zone has no road");
					jsonData.add(record);
					continue;
				}

				int state = veh.getState();
				boolean goingToReservedParking = veh.isGoingToReservedParking();
				if (state != Vehicle.PARKING && state != Vehicle.CRUISING_TRIP && state != Vehicle.NONE_OF_THE_ABOVE
						&& !goingToReservedParking) {
					ContextCreator.logger.warn("repositionTaxi: vehicle " + entry.vehicleId + " not in a relocatable state (state=" + state + ")");
					record.put("status", "error");
					record.put("message", "vehicle not idle");
					jsonData.add(record);
					continue;
				}

				int curZoneID = veh.getCurrentZone();
				Zone origZone = ContextCreator.getZoneContext().get(curZoneID);

				removeTaxiFromDispatchPools(veh);
				boolean releasedParkingReservation = false;
				if (state == Vehicle.PARKING) {
					veh.releaseParkingSpot(origZone);
				} else if (goingToReservedParking) {
					releasedParkingReservation = cancelParkingReservationForRedirect(veh);
				}

				destZone.addFutureSupply();
				if (origZone != null) origZone.numberOfRelocatedVehicles += 1;

				// ElectricTaxi.relocation handles stopCruising if needed and
				// enters INACCESSIBLE_RELOCATION_TRIP state.
				veh.relocation(destZone.getID(), destZone.sampleRoad(true));

				record.put("originZoneId", curZoneID);
				if (releasedParkingReservation) {
					record.put("parkingReservationReleased", true);
				}
				record.put("status", "ok");
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing repositionTaxi: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	/**
	* Reroute an idle taxi to a reserved parking destination.
	*
	* <p>Input DATA: list of {@code {vehicleId, zoneId?, roadId?}}. If
	* {@code roadId} is omitted, zone parking is reserved and the taxi routes to
	* the zone's closest destination road. If {@code zoneId} is omitted, it is
	* inferred from the target road. When both are supplied, the road must
	* belong to the zone.
	*/
	private HashMap<String, Object> goParking(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDZoneRoad>> collectionType = new TypeToken<Collection<VehIDZoneRoad>>() {};
			Collection<VehIDZoneRoad> entries = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (VehIDZoneRoad entry : entries) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", entry.vehicleId);

				Vehicle publicVehicle = ContextCreator.getVehicleContext().getPublicVehicle(entry.vehicleId);
				if (!(publicVehicle instanceof ElectricTaxi)) {
					ContextCreator.logger.warn("goParking: taxi " + entry.vehicleId + " not found");
					record.put("status", "error");
					record.put("message", "taxi not found");
					jsonData.add(record);
					continue;
				}
				ElectricTaxi veh = (ElectricTaxi) publicVehicle;

				if (isTaxiChargingOrOnChargingTrip(veh)) {
					ContextCreator.logger.warn("goParking: taxi " + entry.vehicleId + " is charging or on a charging trip");
					record.put("status", "error");
					record.put("message", "vehicle is charging or on a charging trip");
					jsonData.add(record);
					continue;
				}
				int state = veh.getState();
				if (state != Vehicle.PARKING && state != Vehicle.CRUISING_TRIP && state != Vehicle.NONE_OF_THE_ABOVE) {
					ContextCreator.logger.warn("goParking: taxi " + entry.vehicleId + " not in a parkable state (state=" + state + ")");
					record.put("status", "error");
					record.put("message", "vehicle not idle");
					jsonData.add(record);
					continue;
				}
				if (veh.hasPassengerAssignments()) {
					ContextCreator.logger.warn("goParking: taxi " + entry.vehicleId + " has passenger assignments");
					record.put("status", "error");
					record.put("message", "vehicle has passenger assignments");
					jsonData.add(record);
					continue;
				}

				Integer zoneId = entry.zoneId;
				String roadId = entry.roadId;
				boolean roadSpecified = cleanString(roadId) != null;
				Zone targetZone = zoneId == null ? null : ContextCreator.getZoneContext().get(zoneId);
				Road targetRoad = roadSpecified ? findRoadByOrigOrInternalID(roadId) : null;

				if (zoneId != null && targetZone == null) {
					record.put("zoneId", zoneId);
					record.put("status", "error");
					record.put("message", "target zone not found");
					jsonData.add(record);
					continue;
				}
				if (roadId != null && targetRoad == null) {
					record.put("roadId", roadId);
					record.put("status", "error");
					record.put("message", "target road not found");
					jsonData.add(record);
					continue;
				}
				if (targetRoad == null && targetZone != null) {
					targetRoad = parkingRoadForZone(targetZone);
					if (targetRoad == null) {
						record.put("zoneId", targetZone.getID());
						record.put("status", "error");
						record.put("message", "target zone has no parking road");
						jsonData.add(record);
						continue;
					}
				}
				if (targetZone == null && targetRoad != null) {
					targetZone = parkingZoneForRoad(targetRoad);
				}
				if (targetZone == null) {
					record.put("status", "error");
					record.put("message", "target zone is required or must be inferable from road");
					jsonData.add(record);
					continue;
				}
				if (targetRoad == null) {
					record.put("zoneId", targetZone.getID());
					record.put("status", "error");
					record.put("message", "target road not found");
					jsonData.add(record);
					continue;
				}
				record.put("zoneId", targetZone.getID());
				record.put("roadId", targetRoad.getOrigID());
				if (!roadBelongsToZone(targetRoad, targetZone)) {
					record.put("status", "error");
					record.put("message", "target road does not belong to target zone");
					jsonData.add(record);
					continue;
				}
				if (!targetRoad.canBeTripDestination()) {
					record.put("status", "error");
					record.put("message", "target road cannot be used as a parking destination");
					jsonData.add(record);
					continue;
				}
				boolean alreadyParkedThere = roadSpecified
						? state == Vehicle.PARKING && veh.getCurrentParkingRoad() == targetRoad.getID()
						: state == Vehicle.PARKING && veh.getCurrentZone() == targetZone.getID()
								&& veh.getCurrentParkingRoad() < 0;
				if (!alreadyParkedThere && roadSpecified && !targetRoad.hasParkingSpace()) {
					record.put("parkingCapacity", targetRoad.getParkingCapacity());
					record.put("parkedVehicleCount", targetRoad.getParkedNum());
					record.put("status", "error");
					record.put("message", "target road has no parking capacity");
					jsonData.add(record);
					continue;
				}
				if (!alreadyParkedThere && !roadSpecified && targetZone.getCapacity() <= 0) {
					record.put("parkingCapacity", targetZone.getCapacity());
					record.put("status", "error");
					record.put("message", "target zone has no parking capacity");
					jsonData.add(record);
					continue;
				}

				boolean parkingDispatched = alreadyParkedThere
						|| (roadSpecified ? veh.goParking(targetRoad) : veh.goParking(targetZone));
				if (!parkingDispatched) {
					if (roadSpecified) {
						record.put("parkingCapacity", targetRoad.getParkingCapacity());
						record.put("parkedVehicleCount", targetRoad.getParkedNum());
					} else {
						record.put("parkingCapacity", targetZone.getCapacity());
					}
					record.put("status", "error");
					record.put("message", roadSpecified ? "target road has no parking capacity"
							: "target zone has no parking capacity");
					jsonData.add(record);
					continue;
				}

				record.put("parkingCapacity", roadSpecified ? targetRoad.getParkingCapacity() : targetZone.getCapacity());
				if (roadSpecified) {
					record.put("parkedVehicleCount", targetRoad.getParkedNum());
				}
				record.put("status", "ok");
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing goParking: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	private Road findRoadByOrigOrInternalID(String roadId) {
		String cleanRoadID = cleanString(roadId);
		if (cleanRoadID == null) {
			return null;
		}
		Road road = ContextCreator.getCityContext().findRoadWithOrigID(cleanRoadID);
		if (road != null) {
			return road;
		}
		try {
			return ContextCreator.getRoadContext().get(Integer.valueOf(cleanRoadID));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private Zone parkingZoneForRoad(Road road) {
		if (road == null) {
			return null;
		}
		Zone zone = ContextCreator.getZoneContext().get(road.getNeighboringZone(true));
		if (zone != null) {
			return zone;
		}
		return ContextCreator.getZoneContext().get(road.getNeighboringZone(false));
	}

	private Road parkingRoadForZone(Zone zone) {
		if (zone == null) {
			return null;
		}
		Integer closestRoadID = zone.getClosestRoad(true);
		return closestRoadID == null ? null : ContextCreator.getRoadContext().get(closestRoadID);
	}

	private boolean roadBelongsToZone(Road road, Zone zone) {
		if (road == null || zone == null) {
			return false;
		}
		return road.getNeighboringZone(true) == zone.getID()
				|| road.getNeighboringZone(false) == zone.getID();
	}

	// Lightweight record locating a pending taxi request inside the
	// simulation. Used by dispatchTaxi to atomically remove a request from
	// its host queue and re-queue on failure.
	private static class PendingTaxiRequestRef {
		Zone zone;
		Request request;
		// One of: "queue", "sharable", "toAdd"
		String source;
		int sharableDestination;
	}

	/**
	* Search every zone's pending-taxi structures for the given request ID,
	* remove it from the first container that contains it, and adjust
	* zone-level counters. Returns null if no pending request matches.
	*
	* Containers searched, in order:
	*   - Zone.requestInQueueForTaxi (counted in nRequestForTaxi)
	*   - Zone.sharableRequestForTaxi (counted in nRequestForTaxi)
	*   - Zone.toAddRequestForTaxi (NOT yet counted; populated by
	*     insertTaxiPass and drained by processToAddPassengers)
	*/
	@SuppressWarnings("unused")
	private PendingTaxiRequestRef findAndRemovePendingTaxiRequest(int requestId) {
		return findAndRemovePendingTaxiRequest(requestId, null);
	}

	private PendingTaxiRequestRef findAndRemovePendingTaxiRequest(int requestId, Integer zoneId) {
		VehicleContext.PendingTaxiRequestEntry indexed =
				ContextCreator.getVehicleContext().getPendingTaxiRequest(requestId);
		if (indexed != null) {
			if (zoneId != null && indexed.zoneID != zoneId.intValue()) return null;
			Zone indexedZone = ContextCreator.getZoneContext().get(indexed.zoneID);
			PendingTaxiRequestRef indexedRef = indexedZone == null ? null
					: findAndRemovePendingTaxiRequestInZone(indexedZone, requestId);
			if (indexedRef != null) return indexedRef;
			ContextCreator.getVehicleContext().unregisterPendingTaxiRequest(requestId);
		}
		if (zoneId != null) {
			Zone z = ContextCreator.getZoneContext().get(zoneId);
			return z == null ? null : findAndRemovePendingTaxiRequestInZone(z, requestId);
		}
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			PendingTaxiRequestRef ref = findAndRemovePendingTaxiRequestInZone(z, requestId);
			if (ref != null) {
				return ref;
			}
		}
		return null;
	}

	private PendingTaxiRequestRef findAndRemovePendingTaxiRequestInZone(Zone z, int requestId) {
		Iterator<Request> it = z.getTaxiRequestQueue().iterator();
		while (it.hasNext()) {
			Request r = it.next();
			if (r.getID() == requestId) {
				it.remove();
				ContextCreator.getVehicleContext().unregisterPendingTaxiRequest(requestId);
				z.setNRequestForTaxi(z.getTaxiRequestNum() - 1);
				PendingTaxiRequestRef ref = new PendingTaxiRequestRef();
				ref.zone = z;
				ref.request = r;
				ref.source = "queue";
				return ref;
			}
		}
		for (Map.Entry<Integer, Queue<Request>> e : z.getSharableRequestForTaxi().entrySet()) {
			Iterator<Request> sit = e.getValue().iterator();
			while (sit.hasNext()) {
				Request r = sit.next();
				if (r.getID() == requestId) {
					sit.remove();
					ContextCreator.getVehicleContext().unregisterPendingTaxiRequest(requestId);
					z.setNRequestForTaxi(z.getTaxiRequestNum() - 1);
					PendingTaxiRequestRef ref = new PendingTaxiRequestRef();
					ref.zone = z;
					ref.request = r;
					ref.source = "sharable";
					ref.sharableDestination = e.getKey();
					return ref;
				}
			}
		}
		Iterator<Request> tit = z.getToAddTaxiRequestQueue().iterator();
		while (tit.hasNext()) {
			Request r = tit.next();
			if (r.getID() == requestId) {
				tit.remove();
				ContextCreator.getVehicleContext().unregisterPendingTaxiRequest(requestId);
				PendingTaxiRequestRef ref = new PendingTaxiRequestRef();
				ref.zone = z;
				ref.request = r;
				ref.source = "toAdd";
				return ref;
			}
		}
		return null;
	}

	/**
	* Restore a previously-removed pending taxi request back to its
	* original container. Used when dispatch fails after we've already
	* pulled the request out, so the next dispatch attempt can still find it.
	*/
	private void reinsertPendingTaxiRequest(PendingTaxiRequestRef ref) {
		Zone z = ref.zone;
		Request r = ref.request;
		if ("sharable".equals(ref.source)) {
			Map<Integer, Queue<Request>> map = z.getSharableRequestForTaxi();
			Queue<Request> q = map.get(ref.sharableDestination);
			if (q == null) {
				q = new LinkedList<Request>();
				map.put(ref.sharableDestination, q);
			}
			q.add(r);
			z.setNRequestForTaxi(z.getTaxiRequestNum() + 1);
			ContextCreator.getVehicleContext().registerPendingTaxiRequest(r, z.getID(), "sharable");
		} else if ("toAdd".equals(ref.source)) {
			z.getToAddTaxiRequestQueue().add(r);
			ContextCreator.getVehicleContext().registerPendingTaxiRequest(r, z.getID(), "toAdd");
		} else {
			z.getTaxiRequestQueue().add(r);
			z.setNRequestForTaxi(z.getTaxiRequestNum() + 1);
			ContextCreator.getVehicleContext().registerPendingTaxiRequest(r, z.getID(), "queue");
		}
	}

	private static class PendingBusRequestRef {
		Zone zone;
		Request request;
		// One of: "queue", "toAdd"
		String source;
	}

	private PendingBusRequestRef findAndRemovePendingBusRequest(int requestId) {
		return findAndRemovePendingBusRequest(requestId, null);
	}

	private PendingBusRequestRef findAndRemovePendingBusRequest(int requestId, Integer zoneId) {
		if (zoneId != null) {
			Zone z = ContextCreator.getZoneContext().get(zoneId);
			return z == null ? null : findAndRemovePendingBusRequestInZone(z, requestId);
		}
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			PendingBusRequestRef ref = findAndRemovePendingBusRequestInZone(z, requestId);
			if (ref != null) {
				return ref;
			}
		}
		return null;
	}

	private PendingBusRequestRef findAndRemovePendingBusRequestInZone(Zone z, int requestId) {
		Iterator<Request> it = z.getBusRequestQueue().iterator();
		while (it.hasNext()) {
			Request r = it.next();
			if (r.getID() == requestId) {
				it.remove();
				z.setNRequestForBus(z.getBusRequestNum() - 1);
				PendingBusRequestRef ref = new PendingBusRequestRef();
				ref.zone = z;
				ref.request = r;
				ref.source = "queue";
				return ref;
			}
		}
		Iterator<Request> tit = z.getToAddBusRequestQueue().iterator();
		while (tit.hasNext()) {
			Request r = tit.next();
			if (r.getID() == requestId) {
				tit.remove();
				PendingBusRequestRef ref = new PendingBusRequestRef();
				ref.zone = z;
				ref.request = r;
				ref.source = "toAdd";
				return ref;
			}
		}
		return null;
	}

	private void reinsertPendingBusRequest(PendingBusRequestRef ref) {
		Zone z = ref.zone;
		Request r = ref.request;
		if ("toAdd".equals(ref.source)) {
			z.getToAddBusRequestQueue().add(r);
		} else {
			z.getBusRequestQueue().add(r);
			z.setNRequestForBus(z.getBusRequestNum() + 1);
		}
	}

	private static class CancelRequestEntry {
		Integer requestId;
		Integer zoneId;
	}

	private ArrayList<CancelRequestEntry> parseCancelRequestEntries(Object data) {
		ArrayList<CancelRequestEntry> entries = new ArrayList<CancelRequestEntry>();
		appendCancelRequestEntry(entries, data);
		return entries;
	}

	private void appendCancelRequestEntry(ArrayList<CancelRequestEntry> entries, Object entry) {
		if (entry == null) return;
		if (entry instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) entry;
			CancelRequestEntry cancelEntry = new CancelRequestEntry();
			cancelEntry.requestId = integerValue(record.get("requestId"));
			cancelEntry.zoneId = integerValue(record.get("zoneId"));
			if (cancelEntry.requestId != null || cancelEntry.zoneId != null) {
				entries.add(cancelEntry);
			}
		} else if (entry instanceof Iterable<?>) {
			for (Object value : (Iterable<?>) entry) {
				appendCancelRequestEntry(entries, value);
			}
		} else {
			CancelRequestEntry cancelEntry = new CancelRequestEntry();
			cancelEntry.requestId = integerValue(entry);
			if (cancelEntry.requestId != null) {
				entries.add(cancelEntry);
			}
		}
	}

	private void recordTaxiRequestLeft(PendingTaxiRequestRef ref) {
		if (ref == null || ref.request == null) return;
		Zone z = ref.zone != null ? ref.zone : ContextCreator.getZoneContext().get(ref.request.getOriginZone());
		if (z == null) return;
		z.taxiLeavedPassWaitingTime += ref.request.getCurrentWaitingTime();
		z.numberOfLeavedTaxiRequest += 1;
		z.numberOfLeavedTaxiPassengers += ref.request.getNumPeople();
	}

	private void recordBusRequestLeft(PendingBusRequestRef ref) {
		if (ref == null || ref.request == null) return;
		Zone z = ref.zone != null ? ref.zone : ContextCreator.getZoneContext().get(ref.request.getOriginZone());
		if (z == null) return;
		z.busLeavedPassWaitingTime += ref.request.getCurrentWaitingTime();
		z.numberOfLeavedBusRequest += 1;
		z.numberOfLeavedBusPassengers += ref.request.getNumPeople();
	}

	private static class RequestQueueRemoval {
		Request request;
		int index;

		RequestQueueRemoval(Request request, int index) {
			this.request = request;
			this.index = index;
		}
	}

	private RequestQueueRemoval removeRequestFromQueue(Queue<Request> requests, int requestId) {
		if (requests == null) return null;
		int index = 0;
		Iterator<Request> it = requests.iterator();
		while (it.hasNext()) {
			Request request = it.next();
			if (request != null && request.getID() == requestId) {
				it.remove();
				return new RequestQueueRemoval(request, index);
			}
			index++;
		}
		return null;
	}

	private Request findRequestInQueue(Queue<Request> requests, int requestId) {
		if (requests == null) return null;
		for (Request request : requests) {
			if (request != null && request.getID() == requestId) {
				return request;
			}
		}
		return null;
	}

	private boolean requestOriginMatchesZone(Request request, int zoneId) {
		return request != null && request.getOriginZone() == zoneId;
	}

	private static class MatchedTaxiCancelResult {
		int vehicleID;
		Request request;
		boolean statusOK;
		String warn;
		boolean removedPickupTrip;
		boolean removedDropoffTrip;
		boolean currentTripRemoved;
		boolean startedNextTrip;
		boolean availableAfterCancellation;
		int availableZone = -1;
	}

	private MatchedTaxiCancelResult cancelMatchedTaxiRequest(int requestId, int zoneId) {
		Vehicle pickupVehicle = ContextCreator.getVehicleContext().getPickupTaxiForRequest(requestId);
		if (pickupVehicle instanceof ElectricTaxi) {
			MatchedTaxiCancelResult result = cancelPickupTaxiRequest((ElectricTaxi) pickupVehicle, requestId, zoneId);
			if (result != null) return result;
		}

		Vehicle occupiedVehicle = ContextCreator.getVehicleContext().getOccupiedTaxiForRequest(requestId);
		if (occupiedVehicle instanceof ElectricTaxi) {
			MatchedTaxiCancelResult result = rejectOccupiedTaxiCancellation((ElectricTaxi) occupiedVehicle, requestId, zoneId);
			if (result != null) return result;
		}

		for (ElectricTaxi taxi : ContextCreator.getVehicleContext().getTaxis()) {
			MatchedTaxiCancelResult result = cancelPickupTaxiRequest(taxi, requestId, zoneId);
			if (result != null) return result;

			result = rejectOccupiedTaxiCancellation(taxi, requestId, zoneId);
			if (result != null) return result;
		}
		return null;
	}

	private MatchedTaxiCancelResult cancelPickupTaxiRequest(ElectricTaxi taxi, int requestId, int zoneId) {
		Request request = findRequestInQueue(taxi.getToBoardRequests(), requestId);
		if (request == null) {
			ContextCreator.getVehicleContext().removePickupTaxiRequest(requestId);
			return null;
		}

		MatchedTaxiCancelResult result = new MatchedTaxiCancelResult();
		result.vehicleID = taxi.getID();
		result.request = request;
		if (!requestOriginMatchesZone(request, zoneId)) {
			result.statusOK = false;
			result.warn = "request zone mismatch";
			return result;
		}

		RequestQueueRemoval pickup = removeRequestFromQueue(taxi.getToBoardRequests(), requestId);
		if (pickup == null) {
			ContextCreator.getVehicleContext().removePickupTaxiRequest(requestId);
			return null;
		}

		ContextCreator.getVehicleContext().removePickupTaxiRequest(requestId);
		result.request = pickup.request;
		result.statusOK = true;
		taxi.setPassNum(Math.max(0, taxi.getPassNum() - pickup.request.getNumPeople()));
		if (taxi.getState() == Vehicle.PICKUP_TRIP) {
			result.currentTripRemoved = pickup.index == 0;
			if (result.currentTripRemoved) {
				result.removedPickupTrip = true;
				removeFutureSupplyForRequest(pickup.request);
				TaxiAdvanceResult advance = advanceTaxiAfterCurrentCancellation(taxi);
				result.startedNextTrip = advance.advanced;
				result.availableAfterCancellation = advance.available;
				result.availableZone = advance.availableZone;
				if (advance.nextRequest != null) {
					addFutureSupplyForRequest(advance.nextRequest);
				}
			} else {
				result.removedPickupTrip = removePlanForRequest(taxi, pickup.request, true, pickup.index);
			}
		} else {
			result.removedPickupTrip = removePlanForRequest(taxi, pickup.request, true, pickup.index);
			if (!taxi.hasPassengerAssignments() && isIdleTaxiState(taxi)) {
				result.availableZone = makeTaxiAvailableAfterCancellation(taxi);
				result.availableAfterCancellation = result.availableZone >= 0;
			}
		}
		return result;
	}

	private MatchedTaxiCancelResult rejectOccupiedTaxiCancellation(ElectricTaxi taxi, int requestId, int zoneId) {
		Request request = findRequestInQueue(taxi.getOnBoardRequests(), requestId);
		if (request == null) {
			ContextCreator.getVehicleContext().removeOccupiedTaxiRequest(requestId);
			return null;
		}

		MatchedTaxiCancelResult result = new MatchedTaxiCancelResult();
		result.vehicleID = taxi.getID();
		result.request = request;
		result.statusOK = false;
		if (!requestOriginMatchesZone(request, zoneId)) {
			result.warn = "request zone mismatch";
		} else {
			result.warn = "request is on an occupied taxi trip and cannot be cancelled";
		}
		return result;
	}

	private static class TaxiAdvanceResult {
		Request nextRequest;
		boolean advanced;
		boolean available;
		int availableZone = -1;
	}

	private TaxiAdvanceResult advanceTaxiAfterCurrentCancellation(ElectricTaxi taxi) {
		TaxiAdvanceResult result = new TaxiAdvanceResult();
		Request nextPickup = taxi.getToBoardRequests().peek();
		if (nextPickup != null) {
			ensureTaxiPlanAtIndexOne(taxi, nextPickup, true);
			taxi.setState(Vehicle.PICKUP_TRIP);
			result.nextRequest = nextPickup;
		} else {
			Request nextDropoff = taxi.getOnBoardRequests().peek();
			if (nextDropoff != null) {
				ensureTaxiPlanAtIndexOne(taxi, nextDropoff, false);
				taxi.setState(Vehicle.OCCUPIED_TRIP);
				result.nextRequest = nextDropoff;
			}
		}

		if (result.nextRequest != null && taxi.getPlan().size() >= 2) {
			taxi.setNextPlan();
			if (taxi.isOnRoad()) {
				taxi.departure();
			}
			result.advanced = true;
		} else {
			if (!taxi.getPlan().isEmpty()) {
				taxi.getPlan().remove(0);
			}
			result.availableZone = makeTaxiAvailableAfterCancellation(taxi);
			result.available = result.availableZone >= 0;
		}
		return result;
	}

	private int makeTaxiAvailableAfterCancellation(ElectricTaxi taxi) {
		if (taxi == null) return -1;
		int zoneId = resolveTaxiAvailabilityZone(taxi);
		if (zoneId < 0) return -1;
		Zone zone = ContextCreator.getZoneContext().get(zoneId);
		if (zone == null) return -1;
		removeVehicleFromEnteringQueues(taxi);
		taxi.becomeAvailableForExternalControl(zone);
		return zoneId;
	}

	private int resolveTaxiAvailabilityZone(ElectricTaxi taxi) {
		if (taxi == null) return -1;
		Road road = taxi.getRoad();
		if (road != null) {
			int zoneId = road.getNeighboringZone(false);
			if (ContextCreator.getZoneContext().get(zoneId) != null) {
				return zoneId;
			}
			zoneId = road.getNeighboringZone(true);
			if (ContextCreator.getZoneContext().get(zoneId) != null) {
				return zoneId;
			}
		}
		if (ContextCreator.getZoneContext().get(taxi.getCurrentZone()) != null) {
			return taxi.getCurrentZone();
		}
		if (ContextCreator.getZoneContext().get(taxi.getDestID()) != null) {
			return taxi.getDestID();
		}
		if (ContextCreator.getZoneContext().get(taxi.getOriginID()) != null) {
			return taxi.getOriginID();
		}
		return -1;
	}

	private boolean isIdleTaxiState(ElectricTaxi taxi) {
		if (taxi == null || taxi.isOnChargingRoute()) return false;
		int state = taxi.getState();
		return state == Vehicle.PARKING
				|| state == Vehicle.CRUISING_TRIP
				|| state == Vehicle.NONE_OF_THE_ABOVE;
	}

	private void ensureTaxiPlanAtIndexOne(ElectricTaxi taxi, Request request, boolean pickup) {
		ArrayList<Plan> plans = taxi.getPlan();
		if (plans.isEmpty()) {
			plans.add(anchorPlanForTaxi(taxi, request, pickup));
		}
		if (plans.size() > 1 && planMatchesRequest(plans.get(1), request, pickup)) {
			return;
		}
		Plan nextPlan = planForRequest(request, pickup);
		if (plans.size() <= 1) {
			plans.add(nextPlan);
		} else {
			plans.add(1, nextPlan);
		}
	}

	private Plan anchorPlanForTaxi(ElectricTaxi taxi, Request request, boolean pickup) {
		int zoneId = taxi.getDestID() >= 0 ? taxi.getDestID()
				: (pickup ? request.getOriginZone() : request.getDestZone());
		int roadId = taxi.getDestRoad() >= 0 ? taxi.getDestRoad()
				: (pickup ? request.getOriginRoad() : request.getDestRoad());
		return new Plan(zoneId, roadId, ContextCreator.getNextTick());
	}

	private Plan planForRequest(Request request, boolean pickup) {
		if (pickup) {
			return new Plan(request.getOriginZone(), request.getOriginRoad(), ContextCreator.getNextTick());
		}
		return new Plan(request.getDestZone(), request.getDestRoad(), ContextCreator.getNextTick());
	}

	private boolean removePlanForRequest(Vehicle vehicle, Request request, boolean pickup, int expectedIndex) {
		if (vehicle == null || request == null) return false;
		ArrayList<Plan> plans = vehicle.getPlan();
		if (plans == null || plans.isEmpty()) return false;

		if (expectedIndex > 0 && expectedIndex < plans.size()
				&& planMatchesRequest(plans.get(expectedIndex), request, pickup)) {
			plans.remove(expectedIndex);
			return true;
		}

		int matchIndex = -1;
		for (int i = 1; i < plans.size(); i++) {
			if (planMatchesRequest(plans.get(i), request, pickup)) {
				if (matchIndex >= 0) {
					return false;
				}
				matchIndex = i;
			}
		}
		if (matchIndex >= 0) {
			plans.remove(matchIndex);
			return true;
		}
		return false;
	}

	private boolean planMatchesRequest(Plan plan, Request request, boolean pickup) {
		if (plan == null || request == null) return false;
		if (pickup) {
			return plan.getDestZoneID() == request.getOriginZone()
					&& plan.getDestRoadID() == request.getOriginRoad();
		}
		return plan.getDestZoneID() == request.getDestZone()
				&& plan.getDestRoadID() == request.getDestRoad();
	}

	private void removeFutureSupplyForRequest(Request request) {
		if (request == null) return;
		Zone z = ContextCreator.getZoneContext().get(request.getDestZone());
		if (z != null) {
			z.removeFutureSupply();
		}
	}

	private void addFutureSupplyForRequest(Request request) {
		if (request == null) return;
		Zone z = ContextCreator.getZoneContext().get(request.getDestZone());
		if (z != null) {
			z.addFutureSupply();
		}
	}

	private static class MatchedBusCancelResult {
		int vehicleID;
		int stopIndex;
		boolean onBoard;
		Request request;
		boolean statusOK;
		String warn;
	}

	private MatchedBusCancelResult cancelMatchedBusRequest(int requestId, int zoneId) {
		for (ElectricBus bus : ContextCreator.getVehicleContext().getBuses()) {
			ArrayList<Queue<Request>> toBoard = bus.getToBoardRequests();
			for (int i = 0; i < toBoard.size(); i++) {
				Request request = findRequestInQueue(toBoard.get(i), requestId);
				if (request != null) {
					MatchedBusCancelResult result = new MatchedBusCancelResult();
					result.vehicleID = bus.getID();
					result.stopIndex = i;
					result.onBoard = false;
					result.request = request;
					if (!requestOriginMatchesZone(request, zoneId)) {
						result.statusOK = false;
						result.warn = "request zone mismatch";
						return result;
					}
					RequestQueueRemoval removed = removeRequestFromQueue(toBoard.get(i), requestId);
					result.request = removed.request;
					result.statusOK = true;
					return result;
				}
			}

			ArrayList<Queue<Request>> onBoard = bus.getOnBoardRequests();
			for (int i = 0; i < onBoard.size(); i++) {
				Request request = findRequestInQueue(onBoard.get(i), requestId);
				if (request != null) {
					MatchedBusCancelResult result = new MatchedBusCancelResult();
					result.vehicleID = bus.getID();
					result.stopIndex = i;
					result.onBoard = true;
					result.request = request;
					if (!requestOriginMatchesZone(request, zoneId)) {
						result.statusOK = false;
						result.warn = "request zone mismatch";
						return result;
					}
					RequestQueueRemoval removed = removeRequestFromQueue(onBoard.get(i), requestId);
					bus.setPassNum(Math.max(0, bus.getPassNum() - removed.request.getNumPeople()));
					result.request = removed.request;
					result.statusOK = true;
					return result;
				}
			}
		}
		return null;
	}

	/**
	* Override {@link Request} maximum waiting tolerance when the caller
	* specifies a positive value ({@code max_waiting_time} in ticks). Same units
	* as {@link Request#getCurrentWaitingTime()} accumulation per zone refresh.
	*/
	private static void applyOptionalMaxWaitingTime(Request req, Integer maxWaitingTicks) {
		if (maxWaitingTicks != null && maxWaitingTicks > 0) {
			req.setMaxWaitingTime(maxWaitingTicks);
		}
	}

	private static class BusRequestMatch {
		int routeID;
		int originStopIndex;
		int destStopIndex;

		BusRequestMatch(int routeID, int originStopIndex, int destStopIndex) {
			this.routeID = routeID;
			this.originStopIndex = originStopIndex;
			this.destStopIndex = destStopIndex;
		}
	}

	private static BusRequestMatch findBusRequestMatch(int originZoneID, int destZoneID) {
		ArrayList<Integer> routeIDs = new ArrayList<Integer>();
		Zone originZone = ContextCreator.getZoneContext().get(originZoneID);
		if (originZone != null && originZone.traversingBusRoutes.containsKey(destZoneID)) {
			routeIDs.addAll(originZone.traversingBusRoutes.get(destZoneID));
		} else {
			routeIDs.addAll(ContextCreator.bus_schedule.getRouteIDs());
		}

		for (int rID : routeIDs) {
			ArrayList<Integer> stops = ContextCreator.bus_schedule.getStopZones(rID);
			if (stops == null) continue;
			for (int i = 0; i < stops.size(); i++) {
				if (!stops.get(i).equals(originZoneID)) continue;
				for (int j = i + 1; j < stops.size(); j++) {
					if (!stops.get(j).equals(destZoneID)) continue;
					if (ContextCreator.bus_schedule.getStopRoad(rID, i) != null
							&& ContextCreator.bus_schedule.getStopRoad(rID, j) != null) {
						return new BusRequestMatch(rID, i, j);
					}
				}
			}
		}
		return null;
	}

	// =============================================================
	// RIDE-HAILING: ADD PENDING REQUESTS / BUS ASSIGNMENT
	// (the only entry points that create Request objects)
	// =============================================================

	/**
	* Add one or more pending taxi requests to a zone's pending queue
	* (specified by zone IDs).
	*
	* <p>Input DATA: list of {@code {zoneId, dest, num,
	* max_waiting_time?}} where {@code zoneId} is the origin zone,
	* {@code dest} is the destination zone, and {@code num} is the party
	* size. Optional {@code max_waiting_time}: positive integer, maximum wait
	* before the passenger abandons the queue ({@link Request#setMaxWaitingTime(int)}
	* in simulation ticks); if omitted or non-positive, the zone's default
	* taxi waiting tolerance applies.
	*
	* <p>Output DATA: list of {@code {ID: zoneId, requestId, STATUS}} entries.
	* The returned {@code requestId} is the canonical handle the caller should
	* use to dispatch the request later via {@link #dispatchTaxi} or to
	* inspect it via the query API.
	*/
	private HashMap<String, Object> addTaxiRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				Set<String> responseFields = responseFieldMask(jsonMsg);
				TypeToken<Collection<OriginDestNumMaxW>> collectionType = new TypeToken<Collection<OriginDestNumMaxW>>() {};
				Collection<OriginDestNumMaxW> zoneIDOrigDestNums = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(OriginDestNumMaxW zoneIDOrigDestNum: zoneIDOrigDestNums) {
					Zone z1 = ContextCreator.getZoneContext().get(zoneIDOrigDestNum.originZoneId);
					Zone z2 = ContextCreator.getZoneContext().get(zoneIDOrigDestNum.destinationZoneId);
					if(z1 != null && z2 != null) {
						// generate request
						Request p = new Request(z1.getID(), z2.getID(), z1.sampleRoad(false), z2.sampleRoad(true), zoneIDOrigDestNum.passengerCount);
						applyOptionalMaxWaitingTime(p, zoneIDOrigDestNum.maxWaitTicks);
						z1.insertTaxiPass(p);

						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("originZoneId", zoneIDOrigDestNum.originZoneId);
					record2.put("requestId", p.getID());
					addRequestResponseFields(record2, p, responseFields);
					record2.put("status", "ok");
						jsonData.add(record2);
					}
					else {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("originZoneId", zoneIDOrigDestNum.originZoneId);
					record2.put("status", "error");
						jsonData.add(record2);
				}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Add one or more pending taxi requests, specified by origin and
	* destination road IDs instead of zone IDs. The origin road's
	* neighboring zone is used as the request's origin zone.
	*
	* <p>Input DATA: list of {@code {orig, dest, num}} where {@code orig}
	* and {@code dest} are original road IDs.
	*
	* <p>Output DATA: list of {@code {ID: orig, requestId, STATUS}} entries.
	*/
	private HashMap<String, Object> addTaxiReqBwRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				Set<String> responseFields = responseFieldMask(jsonMsg);
				TypeToken<Collection<OrigRoadDestRoadNumMaxW>> collectionType = new TypeToken<Collection<OrigRoadDestRoadNumMaxW>>() {};
				Collection<OrigRoadDestRoadNumMaxW> origRoadDestRoadNums = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(OrigRoadDestRoadNumMaxW origRoadDestRoadNum: origRoadDestRoadNums) {
					Road r1 = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoadNum.originRoadId);
					Road r2 = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoadNum.destinationRoadId);
					if(r1 != null && r2 != null) {
						Zone z1 = ContextCreator.getZoneContext().get(r1.getNeighboringZone(false));
						// generate request
						Request p = new Request(z1.getID(), r2.getNeighboringZone(true), r1.getID(), r2.getID(), origRoadDestRoadNum.passengerCount);
						applyOptionalMaxWaitingTime(p, origRoadDestRoadNum.maxWaitTicks);
						z1.insertTaxiPass(p);

						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("originRoadId", origRoadDestRoadNum.originRoadId);
					record2.put("requestId", p.getID());
					addRequestResponseFields(record2, p, responseFields);
					record2.put("status", "ok");
						jsonData.add(record2);
					}
					else {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("originRoadId", origRoadDestRoadNum.originRoadId);
					record2.put("status", "error");
						jsonData.add(record2);
				}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Match an existing pending bus request to a specific bus along its
	* current route.
	*
	* <p>Input DATA: list of {@code {busId, requestId}}. The request must have
	* already been created by {@link #addBusRequests}.
	*
	* <p>Output DATA: list of {@code {ID: busId, busId, requestId, STATUS}}
	* entries.
	*/
	private HashMap<String, Object> assignRequestToBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<BusIDReqID>> collectionType = new TypeToken<Collection<BusIDReqID>>() {};
				Collection<BusIDReqID> busIDReqIDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(BusIDReqID busIDReqID: busIDReqIDs) {
					int busId = busIDReqID.getBusID();
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("busId", busId);
					record2.put("busId", busId);
					record2.put("requestId", busIDReqID.requestId);

					if (busIDReqID.requestId == null) {
						record2.put("status", "error");
						record2.put("message", "request ID missing");
						jsonData.add(record2);
						continue;
					}

					ElectricBus veh = ContextCreator.getVehicleContext().getBus(busId);
					if (veh == null) {
						record2.put("status", "error");
						record2.put("message", "bus not found");
						jsonData.add(record2);
						continue;
					}

					PendingBusRequestRef ref = findAndRemovePendingBusRequest(busIDReqID.requestId);
					if (ref == null) {
						record2.put("status", "error");
						record2.put("message", "pending bus request not found");
						jsonData.add(record2);
						continue;
					}

					Request p = ref.request;
					if (!veh.servable(p)) {
						reinsertPendingBusRequest(ref);
						record2.put("status", "error");
						record2.put("message", "request not servable by bus route");
						jsonData.add(record2);
						continue;
					}

					p.setBusRoute(veh.getRouteID());
					p.matchedTime = ContextCreator.getCurrentTick();
					if(veh.addToBoardPass(p)) {
						ref.zone.busPickupRequest += 1;
						ref.zone.busPickupPassengers += p.getNumPeople();
						ref.zone.busServedPassWaitingTime += p.getCurrentWaitingTime();
						record2.put("status", "ok");
					} else {
						reinsertPendingBusRequest(ref);
						record2.put("status", "error");
						record2.put("message", "request not added to bus");
					}
					jsonData.add(record2);
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Add one or more pending bus requests to a zone's bus queue. The
	* origin and destination zones must both appear (in order) on the
	* same bus route.
	*
	* <p>Input DATA: list of {@code {zoneId, dest, num,
	* max_waiting_time?}}. Optional {@code max_waiting_time}: positive integer,
	* maximum wait before the passenger abandons the queue (simulation ticks);
	* if omitted or non-positive, the default bus tolerance applies.
	*
	* <p>Output DATA: list of {@code {ID: zoneId, requestId, STATUS}} entries.
	*/
	private HashMap<String, Object> addBusRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<OriginDestNumMaxW>> collectionType = new TypeToken<Collection<OriginDestNumMaxW>>() {};
				Collection<OriginDestNumMaxW> zoneIDOrigDestRouteNameNums = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(OriginDestNumMaxW zoneIDOrigDestRouteNameNum: zoneIDOrigDestRouteNameNums) {
					Zone z1 = ContextCreator.getZoneContext().get(zoneIDOrigDestRouteNameNum.originZoneId);
					Zone z2 = ContextCreator.getZoneContext().get(zoneIDOrigDestRouteNameNum.destinationZoneId);
					BusRequestMatch match = findBusRequestMatch(zoneIDOrigDestRouteNameNum.originZoneId, zoneIDOrigDestRouteNameNum.destinationZoneId);
					if(z1 != null && z2 != null && match != null) {
						// generate request
						Request p = new Request(z1.getID(), z2.getID(), ContextCreator.bus_schedule.getStopRoad(match.routeID, match.originStopIndex).getID(), ContextCreator.bus_schedule.getStopRoad(match.routeID, match.destStopIndex).getID(), zoneIDOrigDestRouteNameNum.passengerCount);
						p.setBusRoute(match.routeID);
						applyOptionalMaxWaitingTime(p, zoneIDOrigDestRouteNameNum.maxWaitTicks);
						z1.insertBusPass(p);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("originZoneId", zoneIDOrigDestRouteNameNum.originZoneId);
						record2.put("requestId", p.getID());
					record2.put("status", "ok");
						jsonData.add(record2);
					}
					else {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("originZoneId", zoneIDOrigDestRouteNameNum.originZoneId);
					record2.put("status", "error");
						jsonData.add(record2);
				}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	// =============================================================
	// BUS ROUTES, RUNS & STOPS
	// (insertStopToRoute / removeStopFromRoute live further down,
	// near updateVehicleRoute, but logically belong with this group.)
	// =============================================================

	/**
	* Register a new named bus route by listing its ordered stops (zones)
	* and the road segments connecting them.
	*
	* <p>Input DATA: list of {@code {routeName, zones, roads}}.
	*/
	private HashMap<String, Object> addBusRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameZonesRoads>> collectionType = new TypeToken<Collection<RouteNameZonesRoads>>() {};
				Collection<RouteNameZonesRoads> routeNameZonesRoads = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(RouteNameZonesRoads routeNameZonesRoad: routeNameZonesRoads) {
					if(ContextCreator.bus_schedule.insertNewRouteByRoadNames(routeNameZonesRoad.routeName, routeNameZonesRoad.stopZoneIds, routeNameZonesRoad.stopRoadIds)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("routeName", routeNameZonesRoad.routeName);
					record2.put("status", "ok");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("routeName", routeNameZonesRoad.routeName);
					record2.put("status", "error");
						jsonData.add(record2);
					}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}

		return jsonAns;
	}

	/**
	* Like {@link #addBusRoute} but with explicitly-provided per-segment
	* driving paths, so the system doesn't have to compute them.
	*
	* <p>Input DATA: list of {@code {routeName, zones, roads, paths}}.
	*/
	private HashMap<String, Object> addBusRouteWithPath(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameZonesRoadsPath>> collectionType = new TypeToken<Collection<RouteNameZonesRoadsPath>>() {};
				Collection<RouteNameZonesRoadsPath> routeNameZonesRoadsPaths = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(RouteNameZonesRoadsPath routeNameZonesRoadsPath: routeNameZonesRoadsPaths) {
					if(ContextCreator.bus_schedule.insertNewRouteByRoadNames(routeNameZonesRoadsPath.routeName, routeNameZonesRoadsPath.stopZoneIds, routeNameZonesRoadsPath.stopRoadIds, routeNameZonesRoadsPath.pathRoadIds)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("routeName", routeNameZonesRoadsPath.routeName);
					record2.put("status", "ok");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("routeName", routeNameZonesRoadsPath.routeName);
					record2.put("status", "error");
						jsonData.add(record2);
					}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}

		return jsonAns;
	}

	/**
	* Schedule one or more new bus runs (departures) on an existing
	* named route.
	*
	* <p>Input DATA: list of {@code {routeName, departTime}} where
	* {@code departTime} is in simulation ticks.
	*/
	private HashMap<String, Object> addBusRun(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameDepartTime>> collectionType = new TypeToken<Collection<RouteNameDepartTime>>() {};
				Collection<RouteNameDepartTime> routeNameDepartTimes = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(RouteNameDepartTime routeNameDepartTime: routeNameDepartTimes) {
					if(ContextCreator.bus_schedule.insertBusRun(routeNameDepartTime.routeName, routeNameDepartTime.departureTicks)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("routeName", routeNameDepartTime.routeName);
					record2.put("status", "ok");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("routeName", routeNameDepartTime.routeName);
					record2.put("status", "error");
						jsonData.add(record2);
					}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}

		return jsonAns;
	}

	// =============================================================
	// TRAFFIC SIGNALS
	// =============================================================

	/**
	* Force a traffic signal into a specified phase, optionally with a
	* non-zero phase-time offset.
	*
	* <p>Input DATA: list of {@code {signalId, targetPhase, phaseTime?}}
	* where {@code targetPhase} is {@code 0=Green / 1=Yellow / 2=Red} and
	* {@code phaseTime} defaults to 0.
	*/
	private HashMap<String, Object> updateSignal(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message. Expected: [{signalId, targetPhase, phaseTime(optional)}, ...]");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalIDPhase>> collectionType = new TypeToken<Collection<SignalIDPhase>>() {};
				Collection<SignalIDPhase> signalIDPhases = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(SignalIDPhase signalIDPhase: signalIDPhases) {
					Signal signal = ContextCreator.getSignalContext().get(signalIDPhase.signalId);
					if(signal != null) {
						// Set the phase (phaseTime defaults to 0 if not provided)
						boolean success = signal.setPhase(signalIDPhase.phase, signalIDPhase.phaseOffsetSeconds);

						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("signalId", signalIDPhase.signalId);
						if(success) {
							record2.put("status", "ok");
							record2.put("newState", signal.getState());
							record2.put("nextUpdateTick", signal.getNextUpdateTick());
						}
						else {
							record2.put("status", "error");
							record2.put("errorCode", "Invalid target phase (must be 0=Green, 1=Yellow, 2=Red)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + signalIDPhase.signalId);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("signalId", signalIDPhase.signalId);
						record2.put("status", "error");
						record2.put("errorCode", "Signal not found");
						jsonData.add(record2);
					}
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Update only the phase durations of a traffic signal, leaving its
	* current phase and starting offset unchanged.
	*
	* <p>Input DATA: list of {@code {signalId, greenTime, yellowTime,
	* redTime}} where times are in seconds.
	*/
	private HashMap<String, Object> updateSignalTiming(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message. Expected: [{signalId, greenTime, yellowTime, redTime}, ...]");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalIDPhaseTiming>> collectionType = new TypeToken<Collection<SignalIDPhaseTiming>>() {};
				Collection<SignalIDPhaseTiming> signalIDPhaseTimings = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(SignalIDPhaseTiming signalIDPhaseTiming: signalIDPhaseTimings) {
					Signal signal = ContextCreator.getSignalContext().get(signalIDPhaseTiming.signalId);
					if(signal != null) {
						ArrayList<Integer> phaseTime = new ArrayList<Integer>();
						phaseTime.add(signalIDPhaseTiming.greenSeconds);
						phaseTime.add(signalIDPhaseTiming.yellowSeconds);
						phaseTime.add(signalIDPhaseTiming.redSeconds);

						boolean success = signal.updatePhaseTiming(phaseTime);

						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("signalId", signalIDPhaseTiming.signalId);
						if(success) {
							record2.put("status", "ok");
							record2.put("phaseTicks", signal.getPhaseTick());
						}
						else {
							record2.put("status", "error");
							record2.put("errorCode", "Invalid phase timing (all durations must be positive)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + signalIDPhaseTiming.signalId);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("signalId", signalIDPhaseTiming.signalId);
						record2.put("status", "error");
						record2.put("errorCode", "Signal not found");
						jsonData.add(record2);
					}
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Set a complete new phase plan for a signal: phase durations, the
	* starting phase, and an optional offset into that phase.
	*
	* <p>Input DATA: list of {@code {signalId, greenTime, yellowTime,
	* redTime, startPhase, phaseOffset?}} where times are in seconds.
	* For tick-level precision use {@link #setSignalPhasePlanTicks}.
	*/
	private HashMap<String, Object> setSignalPhasePlan(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found. Expected: [{signalId, greenTime, yellowTime, redTime, startPhase, phaseOffset(optional)}, ...]");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalPhasePlan>> collectionType = new TypeToken<Collection<SignalPhasePlan>>() {};
				Collection<SignalPhasePlan> signalPhasePlans = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(SignalPhasePlan plan: signalPhasePlans) {
					Signal signal = ContextCreator.getSignalContext().get(plan.signalId);
					if(signal != null) {
						ArrayList<Integer> phaseTime = new ArrayList<Integer>();
						phaseTime.add(plan.greenSeconds);
						phaseTime.add(plan.yellowSeconds);
						phaseTime.add(plan.redSeconds);

						boolean success = signal.setPhasePlan(phaseTime, plan.startPhase, plan.phaseOffsetSeconds);

						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("signalId", plan.signalId);
						if(success) {
							record2.put("status", "ok");
							record2.put("phaseTicks", signal.getPhaseTick());
							record2.put("currentState", signal.getState());
							record2.put("nextUpdateTick", signal.getNextUpdateTick());
						}
						else {
							record2.put("status", "error");
							record2.put("errorCode", "Invalid phase plan (check phase durations and startPhase)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + plan.signalId);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("signalId", plan.signalId);
						record2.put("status", "error");
						record2.put("errorCode", "Signal not found");
						jsonData.add(record2);
					}
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Tick-precise variant of {@link #setSignalPhasePlan}: phase durations
	* are given in simulation ticks rather than seconds.
	*
	* <p>Input DATA: list of {@code {signalId, greenTicks, yellowTicks,
	* redTicks, startPhase, tickOffset?}}.
	*/
	private HashMap<String, Object> setSignalPhasePlanTicks(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found. Expected: [{signalId, greenTicks, yellowTicks, redTicks, startPhase, tickOffset(optional)}, ...]");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalPhasePlanTicks>> collectionType = new TypeToken<Collection<SignalPhasePlanTicks>>() {};
				Collection<SignalPhasePlanTicks> signalPhasePlansTicks = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(SignalPhasePlanTicks plan: signalPhasePlansTicks) {
					Signal signal = ContextCreator.getSignalContext().get(plan.signalId);
					if(signal != null) {
						ArrayList<Integer> phaseTicks = new ArrayList<Integer>();
						phaseTicks.add(plan.greenTicks);
						phaseTicks.add(plan.yellowTicks);
						phaseTicks.add(plan.redTicks);

						boolean success = signal.setPhasePlanInTicks(phaseTicks, plan.startPhase, plan.phaseOffsetTicks);

						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("signalId", plan.signalId);
						if(success) {
							record2.put("status", "ok");
							record2.put("phaseTicks", signal.getPhaseTick());
							record2.put("currentState", signal.getState());
							record2.put("nextUpdateTick", signal.getNextUpdateTick());
						}
						else {
							record2.put("status", "error");
							record2.put("errorCode", "Invalid phase plan (check phase tick durations and startPhase)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + plan.signalId);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("signalId", plan.signalId);
						record2.put("status", "error");
						record2.put("errorCode", "Signal not found");
						jsonData.add(record2);
					}
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	// =============================================================
	// ROUTING (per-vehicle reroute & per-bus stop edits)
	// =============================================================

	/**
	* Override the remaining route of a vehicle's current trip with the
	* specified ordered sequence of road names.
	*
	* <p>Input DATA: list of {@code {vehicleId, isPrivate, route}} where
	* {@code route} is an array of original road IDs.
	*/
	private HashMap<String, Object> updateVehicleRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeRoute>> collectionType = new TypeToken<Collection<VehIDVehTypeRoute>>() {};
				Collection<VehIDVehTypeRoute> vehIDVehTypeRoutes = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(VehIDVehTypeRoute vehIDVehTypeRoute: vehIDVehTypeRoutes) {
					Vehicle veh = null;
				if(vehIDVehTypeRoute.isPrivate) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeRoute.vehicleId);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeRoute.vehicleId);
					}
				if(veh != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					if(veh.updateRouteByRoadName(vehIDVehTypeRoute.routeRoadIds)){
						record2.put("vehicleId", vehIDVehTypeRoute.vehicleId);
						record2.put("status", "ok");
					}
					else {
						record2.put("vehicleId", vehIDVehTypeRoute.vehicleId);
						record2.put("status", "error");
					}
					jsonData.add(record2);
				}
				else {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehicleId", vehIDVehTypeRoute.vehicleId);
					record2.put("status", "error");
						jsonData.add(record2);
				}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Insert a new stop into a bus's remaining route at the given index.
	*
	* <p>Input DATA: list of {@code {busId, routeName, zone, road,
	* stopIndex}} where {@code routeName} must match the bus's currently
	* assigned route and {@code stopIndex} is 0-based, relative to the
	* bus's remaining stops.
	*/
	private HashMap<String, Object> insertStopToRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<BusIDRouteNameZoneRoadStopIndex>> collectionType = new TypeToken<Collection<BusIDRouteNameZoneRoadStopIndex>>() {};
				Collection<BusIDRouteNameZoneRoadStopIndex> busIDRouteNameZoneRoadStopIndexes = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(BusIDRouteNameZoneRoadStopIndex busIDRouteNameZoneRoadStopIndex: busIDRouteNameZoneRoadStopIndexes) {
					ElectricBus veh = (ElectricBus) ContextCreator.getVehicleContext().getPublicVehicle(busIDRouteNameZoneRoadStopIndex.busId);

					if(veh!= null) {
						int rID = veh.getRouteID();
						Road r = ContextCreator.getCityContext().findRoadWithOrigID(busIDRouteNameZoneRoadStopIndex.stopRoadId);
						if(Objects.equals(ContextCreator.bus_schedule.getRouteName(rID),
								busIDRouteNameZoneRoadStopIndex.routeName) && r != null) {
							if (veh.insertStop(busIDRouteNameZoneRoadStopIndex.stopZoneId, r, busIDRouteNameZoneRoadStopIndex.stopIndex)) {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
							record2.put("busId", busIDRouteNameZoneRoadStopIndex.busId);
							record2.put("status", "ok");
								jsonData.add(record2);
							}
							else {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
							record2.put("busId", busIDRouteNameZoneRoadStopIndex.busId);
							record2.put("status", "error");
								jsonData.add(record2);
							}
						}
						else {
							ContextCreator.logger.info("insertStopToRoute: bus route or road name incorrect.");
							HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("busId", busIDRouteNameZoneRoadStopIndex.busId);
						record2.put("status", "error");
							jsonData.add(record2);
						}
					}
					else {
						ContextCreator.logger.info("insertStopToRoute: cannot find bus with ID: " +  busIDRouteNameZoneRoadStopIndex.busId);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("busId", busIDRouteNameZoneRoadStopIndex.busId);
					record2.put("status", "error");
						jsonData.add(record2);
					}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Remove a stop at the given index from a bus's remaining route.
	*
	* <p>Input DATA: list of {@code {busId, routeName, stopIndex}}.
	*/
	private HashMap<String, Object> removeStopFromRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<BusIDRouteNameStopIndex>> collectionType = new TypeToken<Collection<BusIDRouteNameStopIndex>>() {};
				Collection<BusIDRouteNameStopIndex> busIDRouteNameStopIndexes = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for(BusIDRouteNameStopIndex busIDRouteNameStopIndex: busIDRouteNameStopIndexes) {
					ElectricBus veh = (ElectricBus) ContextCreator.getVehicleContext().getPublicVehicle(busIDRouteNameStopIndex.busId);

					if(veh!= null) {
						int rID = veh.getRouteID();
						if(Objects.equals(ContextCreator.bus_schedule.getRouteName(rID),
								busIDRouteNameStopIndex.routeName)) {
							if (veh.removeStop(busIDRouteNameStopIndex.stopIndex)) {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
							record2.put("busId", busIDRouteNameStopIndex.busId);
							record2.put("status", "ok");
								jsonData.add(record2);
							}
							else {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
							record2.put("busId", busIDRouteNameStopIndex.busId);
							record2.put("status", "error");
								jsonData.add(record2);
							}
						}
						else {
							ContextCreator.logger.info("removeStopFromRoute: bus route or road name incorrect.");
							HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("busId", busIDRouteNameStopIndex.busId);
						record2.put("status", "error");
							jsonData.add(record2);
						}
					}
					else {
						ContextCreator.logger.info("insertStopToRoute: cannot find bus with ID: " +  busIDRouteNameStopIndex.busId);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("busId", busIDRouteNameStopIndex.busId);
					record2.put("status", "error");
						jsonData.add(record2);
					}
				}

				jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}



	// =============================================================
	// ROAD SPEEDS AND ROUTING WEIGHTS
	// =============================================================

	/**
	* Override the routing weight of one or more road edges. Used by
	* external routing components to bias the on-the-fly shortest-path
	* search. Negative weights are clamped to a small positive value.
	*
	* <p>Input DATA: list of {@code {roadId, weight}}.
	*/
	private HashMap<String, Object> updateEdgeWeight(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RoadIDWeight>> collectionType =
						new TypeToken<Collection<RoadIDWeight>>() {};
				Collection<RoadIDWeight> roadIDWeights =
						gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (RoadIDWeight roadIDWeight : roadIDWeights) {
					HashMap<String, Object> record =
							statusRecord("roadId", roadIDWeight.roadId, "error");
					Road road = findRoadByOrigOrInternalID(roadIDWeight.roadId);
					if (road == null) {
						ContextCreator.logger.warn("Cannot find the road, road ID: " + roadIDWeight.roadId);
						record.put("message", "road not found");
					}
					else if (!Double.isFinite(roadIDWeight.routingWeight)) {
						record.put("message", "weight must be finite");
					}
					else {
						double routingWeight = Math.max(roadIDWeight.routingWeight, 1.0e-3);
						if (ContextCreator.getCityContext().updateRoadRoutingWeight(road, routingWeight)) {
							record.put("routingWeight", routingWeight);
							record.put("status", "ok");
						}
						else {
							record.put("message", "road has no routing edge");
						}
					}
					jsonData.add(record);
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
				ContextCreator.logger.error("Error processing control: " + e.toString());
				jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Set the target speed of one or more roads in meters per second. Unlike
	* {@link #updateEdgeWeight(JSONObject)}, this updates both lane/vehicle behavior
	* and the road's free-flow routing cost.
	*
	* <p>Input DATA: list of {@code {roadId, target_speed}}. Aliases
	* {@code origID}, {@code orig_id}, {@code ID}, {@code targetSpeed}, and
	* {@code speed} are accepted.
	*/
	private HashMap<String, Object> updateTargetSpeed(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RoadIDTargetSpeed>> collectionType =
						new TypeToken<Collection<RoadIDTargetSpeed>>() {};
				Collection<RoadIDTargetSpeed> entries =
						gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (RoadIDTargetSpeed entry : entries) {
					String roadId = entry.roadId;
					Double targetSpeed = entry.targetSpeed;
					HashMap<String, Object> record = statusRecord("roadId", roadId, "error");
					if (roadId == null) {
						record.put("message", "roadId is required");
					}
					else if (targetSpeed == null || !Double.isFinite(targetSpeed) || targetSpeed <= 0.0) {
						record.put("message", "target_speed must be a finite positive value in m/s");
					}
					else {
						Road road = findRoadByOrigOrInternalID(roadId);
						if (road == null) {
							ContextCreator.logger.warn("Cannot find the road, road ID: " + roadId);
							record.put("message", "road not found");
						}
						else {
							Node node1 = road.getUpStreamNode();
							Node node2 = road.getDownStreamNode();
							if (node1 == null || node2 == null
									|| ContextCreator.getRoadNetwork().getEdge(node1, node2) == null) {
								record.put("message", "road has no routing edge");
							}
							else {
								road.setTargetSpeed(targetSpeed);
								ContextCreator.getCityContext()
										.updateRoadRoutingWeight(road, road.getTravelTime());
								record.put("targetSpeed", targetSpeed);
								record.put("speedUnit", "m/s");
								record.put("speedLimit", road.getSpeedLimit());
								record.put("travelTime", road.getTravelTime());
								record.put("routingWeight", ContextCreator.getRoadNetwork()
										.getEdge(node1, node2).getWeight());
								record.put("status", "ok");
							}
						}
					}
					jsonData.add(record);
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
				ContextCreator.logger.error("Error processing control: " + e.toString());
				jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Update one or more road-level parking capacities.
	*
	* <p>Input DATA: list of {@code {roadId, parking_capacity}}. Aliases
	* {@code origID}, {@code orig_id}, {@code ID}, {@code parkingCapacity},
	* and {@code capacity} are accepted.
	*/
	private HashMap<String, Object> updateRoadParkingCapacity(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RoadParkingCapacity>> collectionType =
						new TypeToken<Collection<RoadParkingCapacity>>() {};
				Collection<RoadParkingCapacity> entries =
						gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (RoadParkingCapacity entry : entries) {
					String roadId = entry.roadId;
					HashMap<String, Object> record = statusRecord("roadId", roadId, "error");
					Integer parkingCapacity = entry.parkingCapacity;
					if (roadId == null) {
						record.put("message", "roadId is required");
						jsonData.add(record);
						continue;
					}
					if (parkingCapacity == null) {
						record.put("message", "parking_capacity is required");
						jsonData.add(record);
						continue;
					}

					Road road = ContextCreator.getCityContext().findRoadWithOrigID(roadId);
					if (road == null) {
						ContextCreator.logger.warn("Cannot find the road, road ID: " + roadId);
						record.put("message", "road not found");
						jsonData.add(record);
						continue;
					}

					road.setParkingCapacity(parkingCapacity);
					record.put("parkingCapacity", road.getParkingCapacity());
					record.put("parkedVehicleCount", road.getParkedNum());
					record.put("status", "ok");
					jsonData.add(record);
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	// =============================================================
	// CHARGING
	// (the goCharging handler lives at the very end of the file,
	// after the dynamic-infrastructure block.)
	// =============================================================

	/**
	* Update the price of a specific charger type at a charging station.
	* The price is used by the EV charging-station search heuristic.
	*
	* <p>Input data: list of {@code {chargingStationId, chargerLevel, price}}
	* where {@code chargerLevel} is one of {@code ChargingStation.L2 / L3 /
	* BUS}.
	*/
	private HashMap<String, Object> updateChargingPrice(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<ChargerIDChargerTypeWeight>> collectionType = new TypeToken<Collection<ChargerIDChargerTypeWeight>>() {};
			Collection<ChargerIDChargerTypeWeight> chargerIDChargerTypeWeights = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for(ChargerIDChargerTypeWeight chargerIDChargerTypeWeight: chargerIDChargerTypeWeights) {
				ChargingStation cs = ContextCreator.getChargingStationContext().get(chargerIDChargerTypeWeight.chargingStationId);
				if(cs != null) {
					boolean success = cs.setPrice(chargerIDChargerTypeWeight.chargerLevel, chargerIDChargerTypeWeight.price);
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("chargingStationId", chargerIDChargerTypeWeight.chargingStationId);
					if(success) {
						record2.put("status", "ok");
					}
					else {
						record2.put("status", "error");
					}
						jsonData.add(record2);
				}
				else {
					ContextCreator.logger.warn("Cannot find the charging station, ID: " + chargerIDChargerTypeWeight.chargingStationId);
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("chargingStationId", chargerIDChargerTypeWeight.chargingStationId);
					record2.put("status", "error");
						jsonData.add(record2);
				}
			}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing control: " + e.toString());
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	// =============================================================
	// DYNAMIC INFRASTRUCTURE & FLEET ADDITIONS / REMOVALS
	// =============================================================

	/**
	* Dynamically adds one or more zones at given coordinates.
	* <p>Input DATA: list of {@code {x, y, z, transformCoordinates, capacity,
	* type}}.
	* <p>Output DATA: list of {@code {ID, STATUS}} with the assigned
	* zone IDs.
	*/
	private HashMap<String, Object> addZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<ZoneParams>> collectionType = new TypeToken<Collection<ZoneParams>>() {};
			Collection<ZoneParams> params = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			GeometryFactory geomFac = new GeometryFactory();
			ZoneContext zoneContext = ContextCreator.getZoneContext();
			boolean metaZonePresent = zoneContext.contains(0);

		for (ZoneParams p : params) {
			Coordinate coord = new Coordinate(p.x, p.y, p.z);
			if (p.transformCoordinates) {
				try {
					JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
				} catch (TransformException e) {
					ContextCreator.logger.error("addZone: coordinate transform failed at (" + p.x + "," + p.y + "): " + e.getMessage());
						HashMap<String, Object> error = statusRecord("zoneId", null, "error");
						error.put("message", "Coordinate transform failed");
						jsonData.add(error);
						continue;
					}
				}

				int zoneId = zoneContext.ZONE_NUM++;
				Zone zone = new Zone(zoneId, p.capacity, p.zoneType);
				zone.setCoord(coord);
				zoneContext.put(zoneId, zone);
				ContextCreator.getZoneGeography().move(zone, geomFac.createPoint(coord));

				// Find and attach the nearest departure and arrival roads
				Road deptRoad = nearestTaxiTripEndpointByFullScan(coord, false);
				Road arrRoad = nearestTaxiTripEndpointByFullScan(coord, true);
				if (deptRoad != null) {
					zone.setClosestRoad(deptRoad.getID(), false);
					zone.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, deptRoad.getStartCoord()), false);
					zone.addNeighboringLink(deptRoad.getID(), false);
				} else {
					ContextCreator.logger.warn("addZone: no departure road found for zone " + zoneId);
				}
				if (arrRoad != null) {
					zone.setClosestRoad(arrRoad.getID(), true);
					zone.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, arrRoad.getEndCoord()), true);
					zone.addNeighboringLink(arrRoad.getID(), true);
				} else {
					ContextCreator.logger.warn("addZone: no arrival road found for zone " + zoneId);
				}

				// Initialize taxi availability maps for the new zone
				ContextCreator.getVehicleContext().initializeZoneMaps(zoneId);

				if (p.zoneType == Zone.HUB) {
					zoneContext.HUB_INDEXES.add(zoneId);
				}

				// Schedule the zone's tick steps so it actively processes demand
				// and its stats are included in ZoneLog just like pre-loaded zones
				ContextCreator.scheduleNewZone(zone);

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("zoneId", zoneId);
				record.put("status", "ok");
				jsonData.add(record);
			}
			// Remove the meta zone once real zones have been successfully added.
			// The meta zone was a startup placeholder used when the zone CSV was empty.
			// refreshRoadZoneAssignment re-maps all roads (which defaulted to zone 0)
			// to the nearest real zone via spatial search.
			if (metaZonePresent && !jsonData.isEmpty()) {
				Zone metaZone = zoneContext.get(0);
				if (GlobalVariables.MULTI_THREADING && ContextCreator.partitioner != null) {
					ContextCreator.partitioner.removeZone(metaZone);
				}
				zoneContext.remove(0);
				ContextCreator.getVehicleContext().removeZoneMaps(0);
				ContextCreator.getCityContext().refreshRoadZoneAssignment();
				ContextCreator.logger.info("Meta zone 0 removed; roads reassigned to " + jsonData.size() + " real zone(s).");
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addZone: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	/**
	* Dynamically adds one or more charging stations at given coordinates.
	* <p>Input DATA: list of {@code {x, y, z, transformCoordinates, level2ChargerCount,
	* level3ChargerCount, busChargerCount, level2Price, level3Price}}.
	* <p>Output DATA: list of {@code {ID, STATUS}} with the assigned
	* (negative) station IDs.
	*/
	private HashMap<String, Object> addChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<ChargingStationParams>> collectionType = new TypeToken<Collection<ChargingStationParams>>() {};
			Collection<ChargingStationParams> params = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			GeometryFactory geomFac = new GeometryFactory();

			// IDs for charging stations are negative integers; find the next available one
			int nextID = ContextCreator.getChargingStationContext().getIDList().stream()
					.mapToInt(Integer::intValue).min().orElse(0) - 1;

		for (ChargingStationParams p : params) {
			Coordinate coord = new Coordinate(p.x, p.y, p.z);
			if (p.transformCoordinates) {
				try {
					JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
				} catch (TransformException e) {
					ContextCreator.logger.error("addChargingStation: coordinate transform failed at (" + p.x + "," + p.y + "): " + e.getMessage());
						HashMap<String, Object> error = statusRecord("chargingStationId", null, "error");
						error.put("message", "Coordinate transform failed");
						jsonData.add(error);
						continue;
					}
				}

				int chargingStationId = nextID--;
				Road deptRoad = resolveDynamicFacilityRoad(coord, false);
				Road arrRoad = resolveDynamicFacilityRoad(coord, true);
				if (deptRoad == null || arrRoad == null) {
					ContextCreator.logger.warn("addChargingStation: no usable "
							+ (deptRoad == null ? "departure" : "arrival")
							+ " road found for station " + chargingStationId);
					HashMap<String, Object> error = statusRecord("chargingStationId", chargingStationId, "error");
					error.put("message", "No usable departure or arrival road found");
					jsonData.add(error);
					continue;
				}

				ChargingStation cs = new ChargingStation(chargingStationId, p.level2ChargerCount, p.level3ChargerCount, p.busChargerCount, p.level2Price, p.level3Price);
				cs.setCoord(coord);
				cs.setClosestRoad(deptRoad.getID(), false);
				cs.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, deptRoad.getStartCoord()), false);
				cs.setClosestRoad(arrRoad.getID(), true);
				cs.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, arrRoad.getEndCoord()), true);
				ContextCreator.getChargingStationContext().put(chargingStationId, cs);
				ContextCreator.getChargingStationGeography().move(cs, geomFac.createPoint(coord));

				// Schedule the station's tick steps so it actively charges vehicles
				// and produces ChargerLog entries identical to pre-loaded stations
				ContextCreator.scheduleNewChargingStation(cs);

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("chargingStationId", chargingStationId);
				record.put("departureRoadId", deptRoad.getOrigID());
				record.put("arrivalRoadId", arrRoad.getOrigID());
				record.put("status", "ok");
				jsonData.add(record);
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addChargingStation: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	/**
	* Release a vehicle from a road's entering-network queue.
	* Co-simulation roads do not process this queue automatically; the
	* external simulator should call this API once it is ready to spawn a
	* queued vehicle for the road. The preferred selector is vehicle ID so
	* the external simulator can choose an order that differs from METS-R's
	* departure queue order.
	*
	* <p>Input DATA: list of vehicle IDs, or records carrying
	* {@code vehicleId}/{@code vehicleID}/{@code ID}, optional
	* {@code isPrivate}/{@code v_type}, and optional {@code roadId}. If
	* {@code roadId} is omitted the co-simulation road queues are searched.
	* For backward compatibility, a road-only record releases that road's
	* queue head.
	*/
	private HashMap<String, Object> enterRoadFromQueue(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
		}
		else {
			try {
				ArrayList<Object> jsonData = new ArrayList<Object>();
				for (EnterRoadQueueRequest request : parseEnterRoadQueueRequests(jsonMsg.get("data"))) {
					HashMap<String, Object> record = new HashMap<String, Object>();
					if (request.vehicleId != null) record.put("vehicleId", request.vehicleId);
					if (request.internalVehicleId != null) record.put("internalVehicleId", request.internalVehicleId);
					if (request.isPrivate != null) record.put("isPrivate", request.isPrivate);
					if (request.roadId != null) record.put("roadId", request.roadId);

					QueuedVehicleMatch match = findQueuedEnteringVehicle(request);
					Road road = match == null ? null : match.road;
					Vehicle vehicle = match == null ? null : match.vehicle;
					if (road == null) {
						record.put("status", "error");
						record.put("message", request.vehicleId != null || request.internalVehicleId != null
								? "vehicle not found in entering queues" : "road not found");
						jsonData.add(record);
						continue;
					}

					record.put("roadId", road.getOrigID());
					record.put("controlMode", controlModeName(road.getControlType()));

					if (vehicle == null) {
						record.put("status", "ok");
						record.put("state", "empty");
						record.put("queueSize", 0);
						jsonData.add(record);
						continue;
					}

					int visibleVehicleID = bridgeVehicleID(vehicle);
					record.put("vehicleId", visibleVehicleID);
					record.put("internalVehicleId", vehicle.getID());
					record.put("isPrivate", bridgeVehicleType(vehicle));
					record.put("departureTick", vehicle.getDepTime());

					int tick = ContextCreator.getCurrentTick();
					if (tick < vehicle.getDepTime()) {
						record.put("status", "ok");
						record.put("state", "waitingDepartureTime");
						record.put("queueSize", road.getEnteringVehicleQueueSnapshot().size());
						jsonData.add(record);
						continue;
					}

					Coordinate cur = vehicle.getCurrentCoord();
					Coordinate dst = vehicle.getDestCoord();
					boolean busTrip = (vehicle.getState() == Vehicle.BUS_TRIP);
					boolean originEqualsDest = (cur != null && dst != null && cur.equals2D(dst));
					boolean sameBusRoad = busTrip
							&& vehicle.getOriginRoad() >= 0
							&& vehicle.getOriginRoad() == vehicle.getDestRoad();
					if ((busTrip && originEqualsDest) || (busTrip && (vehicle.getOriginID() == vehicle.getDestID()))
							|| sameBusRoad) {
						removeVehicleFromEnteringQueues(vehicle);
						ContextCreator.getVehicleContext().addArrivalVehicles(vehicle);
						record.put("status", "ok");
						record.put("state", "arrived");
					} else {
						if (vehicle.enterNetworkByControl(road)) {
							road.removeVehicleFromNewQueue(vehicle.getDepTime(), vehicle);
							record.put("status", "ok");
							addConnectorState(record, vehicle);
						} else {
							record.put("status", "error");
							record.put("errorCode", "ENTRY_BLOCKED");
							record.put("retryable", true);
							record.put("message", "Road entry is temporarily blocked");
						}
					}
					record.put("queueSize", road.getEnteringVehicleQueueSnapshot().size());
					jsonData.add(record);
				}
				jsonAns.put("data", jsonData);
				jsonAns.put("status", "ok");
			}
			catch (Exception e) {
			ContextCreator.logger.error("Error processing control enterRoadFromQueue: " + e.getMessage(), e);
			jsonAns.put("status", "error");
			}
		}
		return jsonAns;
	}

	/**
	* Dynamically adds one or more roads, including generated lanes offset from
	* the supplied centerline.
	* <p>Input DATA: list of {@code {origID/orig_id, centerline, upStreamRoad,
	* downStreamRoad, roadType, controlType, upStreamControlType,
	* downStreamControlType, numLanes, laneWidth, transformCoordinates}}.
	* Road references are original road IDs. Singular and plural upstream /
	* downstream fields are accepted.
	* <p>Output DATA: list of {@code {ID, internalID, laneIDs, STATUS}} records.
	*/
	private HashMap<String, Object> addRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<RoadParams>> collectionType = new TypeToken<Collection<RoadParams>>() {};
			Collection<RoadParams> params = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			GeometryFactory geomFac = new GeometryFactory();
			int nextRoadID = nextAvailableID(ContextCreator.getRoadContext().getIDList(), 1);
			int nextLaneID = nextAvailableID(ContextCreator.getLaneContext().getIDList(), 1);
			boolean anyRoadAdded = false;

			for (RoadParams p : params) {
				String origID = cleanString(p.roadId);
				HashMap<String, Object> record = statusRecord("roadId", origID, "error");

				ArrayList<Coordinate> centerline = null;
				try {
					centerline = parseRoadCenterline(p);
				} catch (TransformException e) {
					record.put("message", "Coordinate transform failed: " + e.getMessage());
					jsonData.add(record);
					continue;
				}
				if (centerline == null || centerline.size() < 2) {
					record.put("message", "Road centerline must contain at least two points");
					jsonData.add(record);
					continue;
				}

				int numLanes = p.laneCount == null ? 1 : p.laneCount.intValue();
				if (numLanes <= 0) {
					record.put("message", "laneCount must be positive");
					jsonData.add(record);
					continue;
				}

				nextRoadID = nextAvailableRoadID(nextRoadID);
				if (origID == null) {
					origID = "dynamic_road_" + nextRoadID;
					record.put("roadId", origID);
				}
				if (ContextCreator.getCityContext().findRoadWithOrigID(origID) != null) {
					record.put("message", "roadId already exists");
					jsonData.add(record);
					continue;
				}

				ArrayList<String> upstreamOrigIDs = normalizeUpstreamRoadOrigIDs(p);
				ArrayList<String> downstreamOrigIDs = normalizeDownstreamRoadOrigIDs(p);
				if (upstreamOrigIDs.isEmpty() || downstreamOrigIDs.isEmpty()) {
					record.put("message", "upstreamRoadIds and downstreamRoadIds are required");
					jsonData.add(record);
					continue;
				}

				ArrayList<Road> upstreamRoads = new ArrayList<Road>();
				String missingRoad = resolveRoadOrigIDs(upstreamOrigIDs, upstreamRoads);
				if (missingRoad != null) {
					record.put("message", "Upstream road not found: " + missingRoad);
					jsonData.add(record);
					continue;
				}

				ArrayList<Road> downstreamRoads = new ArrayList<Road>();
				missingRoad = resolveRoadOrigIDs(downstreamOrigIDs, downstreamRoads);
				if (missingRoad != null) {
					record.put("message", "Downstream road not found: " + missingRoad);
					jsonData.add(record);
					continue;
				}

				String junctionWarning = validateConnectorJunctions(upstreamRoads, true);
				if (junctionWarning == null) {
					junctionWarning = validateConnectorJunctions(downstreamRoads, false);
				}
				if (junctionWarning != null) {
					record.put("message", junctionWarning);
					jsonData.add(record);
					continue;
				}

				double roadLength = polylineLength(centerline);
				if (roadLength <= 0) {
					record.put("message", "Road centerline length must be positive");
					jsonData.add(record);
					continue;
				}

				int roadId = nextRoadID;
				nextRoadID++;
				Road road = new Road(roadId);
				road.setOrigID(origID);
				road.setRoadType(p.roadType == null ? Road.Street : p.roadType.intValue());
				road.setControlType(controlModeValue(
						p.controlMode, Road.NONE_OF_THE_ABOVE));
				Integer parkingCapacity = p.parkingCapacity;
				if (parkingCapacity != null) {
					road.setParkingCapacity(parkingCapacity);
				}
				road.setCoords(centerline);
				road.setLength(roadLength);
				road.updateTravelTimeEstimation();
				ContextCreator.getRoadContext().put(roadId, road);
				ContextCreator.getRoadGeography().move(road,
						geomFac.createLineString(centerline.toArray(new Coordinate[centerline.size()])));

				double laneWidth = p.laneWidth == null ? 3.5 : p.laneWidth.doubleValue();
				ArrayList<Integer> laneIDs = new ArrayList<Integer>();
				for (int laneIndex = 0; laneIndex < numLanes; laneIndex++) {
					nextLaneID = nextAvailableLaneID(nextLaneID);
					int internalLaneId = nextLaneID;
					nextLaneID++;

					ArrayList<Coordinate> laneCoords = offsetCenterline(centerline, laneIndex, numLanes, laneWidth);
					Lane lane = new Lane(internalLaneId);
					lane.setOrigID(origID + "_" + internalLaneId);
					lane.setRoad(roadId);
					lane.setCoords(laneCoords);
					lane.setLength(polylineLength(laneCoords));
					lane.setSpeed(road.getSpeedLimit());
					ContextCreator.getLaneContext().put(internalLaneId, lane);
					ContextCreator.getLaneGeography().move(lane,
							geomFac.createLineString(laneCoords.toArray(new Coordinate[laneCoords.size()])));
					road.addLane(lane);
					laneIDs.add(internalLaneId);
				}
				road.sortLanes();
				for (Lane lane : road.getLanes()) {
					lane.setIndex();
				}

				ContextCreator.getCityContext().registerAddedRoad(road, upstreamRoads, downstreamRoads,
						controlModeValueOrNull(p.upstreamControlMode),
						controlModeValueOrNull(p.downstreamControlMode));
				updateFacilitiesAfterRoadAddition(road);
				ContextCreator.scheduleNewRoad(road);

				record.put("internalRoadId", road.getID());
				record.put("internalLaneIds", laneIDs);
				record.put("status", "ok");
				jsonData.add(record);
				anyRoadAdded = true;
			}

			if (anyRoadAdded) {
				RouteContext.createRoute();
				ContextCreator.getCityContext().refreshRoadNetworkWeights();
				if (GlobalVariables.MULTI_THREADING) {
					try {
						ContextCreator.partitioner.first_run();
					} catch (Exception e) {
						ContextCreator.logger.warn("addRoads: failed to refresh partitioner: " + e.getMessage());
					}
				}
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addRoads: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	/**
	* Dynamically removes one or more zones.
	* <p>Input DATA: list of integer zone IDs.
	* <p>Output DATA: list of {@code {ID, STATUS}} records.
	*
	* <p>A zone is removed only when it is not the last remaining zone and no
	* active vehicles, pending requests, or bus routes still reference it.
	*/
	private HashMap<String, Object> removeZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
			Collection<Integer> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (int zoneId : IDs) {
				HashMap<String, Object> record = statusRecord("zoneId", zoneId, "error");
				Zone zone = ContextCreator.getZoneContext().get(zoneId);
				if (zone == null) {
					record.put("message", "Zone not found");
					jsonData.add(record);
					continue;
				}

				String blocker = zoneRemovalBlocker(zone);
				if (blocker != null) {
					record.put("message", blocker);
					jsonData.add(record);
					continue;
				}

				for (Road road : ContextCreator.getRoadContext().getAll()) {
					if (road.getNeighboringZone(false) == zoneId) {
						road.setNeighboringZone(0, false);
						road.setDistToZone(Double.MAX_VALUE, false);
					}
					if (road.getNeighboringZone(true) == zoneId) {
						road.setNeighboringZone(0, true);
						road.setDistToZone(Double.MAX_VALUE, true);
					}
				}

				ContextCreator.getZoneContext().HUB_INDEXES.remove(Integer.valueOf(zoneId));
				if (GlobalVariables.MULTI_THREADING && ContextCreator.partitioner != null) {
					ContextCreator.partitioner.removeZone(zone);
				}
				ContextCreator.getZoneContext().remove(zoneId);
				ContextCreator.getVehicleContext().removeZoneMaps(zoneId);

				for (Zone other : ContextCreator.getZoneContext().getAll()) {
					other.getNeighboringZones().remove(Integer.valueOf(zoneId));
					other.traversingBusRoutes.remove(zoneId);
				}

				ContextCreator.getCityContext().refreshRoadZoneAssignment();
				record.put("status", "ok");
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control removeZone: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	/**
	* Dynamically removes one or more roads.
	* <p>Input DATA: list of original road IDs.
	* <p>Output DATA: list of {@code {ID, STATUS}} records.
	*
	* <p>A road is removed only when no vehicles, requests, bus schedules, or
	* facility closest-road assignments would be stranded.
	*/
	private HashMap<String, Object> removeRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
			Collection<String> roadIDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (String roadId : roadIDs) {
				HashMap<String, Object> record = statusRecord("roadId", roadId, "error");
				Road road = ContextCreator.getCityContext().findRoadWithOrigID(roadId);
				if (road == null) {
					record.put("message", "Road not found");
					jsonData.add(record);
					continue;
				}

				String blocker = roadRemovalBlocker(road);
				if (blocker != null) {
					record.put("message", blocker);
					jsonData.add(record);
					continue;
				}

				ContextCreator.coSimRoads.remove(road.getOrigID());
				ContextCreator.getCityContext().removeRoadReferences(road);
				removeRoadLanes(road);
				ContextCreator.getRoadContext().remove(road.getID());
				ContextCreator.getRoadContext().rebuildConnectorTopology();
				updateFacilitiesAfterRoadRemoval(road);
				RouteContext.createRoute();

				record.put("status", "ok");
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control removeRoad: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	/**
	* Dynamically removes one or more charging stations.
	* <p>Input DATA: list of integer charging-station IDs.
	* <p>Output DATA: list of {@code {ID, STATUS}} records.
	*
	* <p>A station is removed only when no vehicle is queued, charging, or
	* already en route to that station.
	*/
	private HashMap<String, Object> removeChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
			Collection<Integer> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (int chargingStationId : IDs) {
				HashMap<String, Object> record =
						statusRecord("chargingStationId", chargingStationId, "error");
				ChargingStation cs = ContextCreator.getChargingStationContext().get(chargingStationId);
				if (cs == null) {
					record.put("message", "Charging station not found");
					jsonData.add(record);
					continue;
				}
				if (cs.hasChargingVehicles()) {
					record.put("message", "Charging station has queued or charging vehicles");
					jsonData.add(record);
					continue;
				}
				if (vehiclesReferenceChargingStation(chargingStationId)) {
					record.put("message", "A vehicle is en route to this charging station");
					jsonData.add(record);
					continue;
				}

				if (GlobalVariables.MULTI_THREADING && ContextCreator.partitioner != null) {
					ContextCreator.partitioner.removeChargingStation(cs);
				}
				ContextCreator.getChargingStationContext().remove(chargingStationId);
				record.put("status", "ok");
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control removeChargingStation: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	private HashMap<String, Object> statusRecord(String idField, Object id, String status) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put(idField, id);
		record.put("status", status);
		return record;
	}

	private String zoneRemovalBlocker(Zone zone) {
		int zoneId = zone.getID();
		if (ContextCreator.getZoneContext().getIDList().size() <= 1) {
			return "Cannot remove the last zone";
		}
		if (ContextCreator.bus_schedule.referencesZone(zoneId)) {
			return "Zone is referenced by a bus route";
		}
		if (zone.getParkingVehicleStock() > 0
				|| !ContextCreator.getVehicleContext().getAvailableTaxisSorted(zoneId).isEmpty()
				|| ContextCreator.getVehicleContext().getNumOfRelocationTaxi(zoneId) > 0) {
			return "Zone still has parked or relocating taxis";
		}
		if (zoneHasPendingRequests(zone) || requestsReferenceZone(zoneId)) {
			return "Zone is referenced by pending or assigned requests";
		}
		if (vehiclesReferenceZone(zoneId)) {
			return "Zone is referenced by active vehicle plans";
		}
		return null;
	}

	private String roadRemovalBlocker(Road road) {
		if (ContextCreator.getRoadContext().hasActiveConnectorForRoad(road)) {
			return "Road belongs to a connector that is still occupied";
		}
		if (road.hasActiveVehicles()) {
			return "Road still has active or queued vehicles";
		}
		if (road.getParkedNum() > 0) {
			return "Road still has parked vehicles";
		}
		if (ContextCreator.bus_schedule.referencesRoad(road)) {
			return "Road is referenced by a bus route";
		}
		if (requestsReferenceRoad(road.getID())) {
			return "Road is referenced by pending or assigned requests";
		}
		if (vehiclesReferenceRoad(road.getID())) {
			return "Road is referenced by active vehicle plans";
		}
		if (!hasAlternativeClosestRoads(road)) {
			return "At least one zone or charging station has no alternative closest road";
		}
		return null;
	}

	private boolean zoneHasPendingRequests(Zone zone) {
		if (!zone.getTaxiRequestQueue().isEmpty() || !zone.getBusRequestQueue().isEmpty()
				|| !zone.getToAddTaxiRequestQueue().isEmpty() || !zone.getToAddBusRequestQueue().isEmpty()) {
			return true;
		}
		for (Queue<Request> q : zone.getSharableRequestForTaxi().values()) {
			if (!q.isEmpty()) return true;
		}
		return false;
	}

	private ArrayList<Vehicle> allVehicles() {
		ArrayList<Vehicle> vehicles = new ArrayList<Vehicle>();
		vehicles.addAll(ContextCreator.getVehicleContext().getPrivateEVs());
		vehicles.addAll(ContextCreator.getVehicleContext().getPrivateGVs());
		vehicles.addAll(ContextCreator.getVehicleContext().getTaxis());
		vehicles.addAll(ContextCreator.getVehicleContext().getBuses());
		return vehicles;
	}

	private boolean vehiclesReferenceZone(int zoneId) {
		for (Vehicle v : allVehicles()) {
			if (v.getOriginID() == zoneId || v.getDestID() == zoneId) return true;
			if (v instanceof ElectricTaxi && ((ElectricTaxi) v).getCurrentZone() == zoneId) return true;
			if (v instanceof ElectricBus && ((ElectricBus) v).getBusStops().contains(zoneId)) return true;
			for (Plan p : v.getPlan()) {
				if (p.getDestZoneID() == zoneId) return true;
			}
		}
		return false;
	}

	private boolean vehiclesReferenceRoad(int roadId) {
		for (Vehicle v : allVehicles()) {
			if (v.getCurrentParkingRoad() == roadId) return true;
			Road currentRoad = v.getRoad();
			if (currentRoad != null && currentRoad.getID() == roadId) return true;
			Road nextRoad = v.getNextRoad();
			if (nextRoad != null && nextRoad.getID() == roadId) return true;
			List<Road> path = v.getRoadPath();
			if (path != null) {
				for (Road r : path) {
					if (r != null && r.getID() == roadId) return true;
				}
			}
			for (Plan p : v.getPlan()) {
				if (p.getDestRoadID() == roadId) return true;
			}
		}
		return false;
	}

	private boolean vehiclesReferenceChargingStation(int chargingStationId) {
		for (Vehicle v : allVehicles()) {
			if (v instanceof ElectricVehicle) {
				ElectricVehicle ev = (ElectricVehicle) v;
				if (ev.isOnChargingRoute() && ev.getDestID() == chargingStationId) return true;
			}
			for (Plan p : v.getPlan()) {
				if (p.getDestZoneID() == chargingStationId) return true;
			}
		}
		return false;
	}

	private boolean requestsReferenceZone(int zoneId) {
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (requestQueueReferencesZone(z.getTaxiRequestQueue(), zoneId)
					|| requestQueueReferencesZone(z.getBusRequestQueue(), zoneId)
					|| requestQueueReferencesZone(z.getToAddTaxiRequestQueue(), zoneId)
					|| requestQueueReferencesZone(z.getToAddBusRequestQueue(), zoneId)) {
				return true;
			}
			for (Queue<Request> q : z.getSharableRequestForTaxi().values()) {
				if (requestQueueReferencesZone(q, zoneId)) return true;
			}
		}
		for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
			if (requestQueueReferencesZone(t.getToBoardRequests(), zoneId)
					|| requestQueueReferencesZone(t.getOnBoardRequests(), zoneId)) {
				return true;
			}
		}
		for (ElectricBus b : ContextCreator.getVehicleContext().getBuses()) {
			for (Queue<Request> q : b.getToBoardRequests()) {
				if (requestQueueReferencesZone(q, zoneId)) return true;
			}
			for (Queue<Request> q : b.getOnBoardRequests()) {
				if (requestQueueReferencesZone(q, zoneId)) return true;
			}
		}
		return false;
	}

	private boolean requestsReferenceRoad(int roadId) {
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (requestQueueReferencesRoad(z.getTaxiRequestQueue(), roadId)
					|| requestQueueReferencesRoad(z.getBusRequestQueue(), roadId)
					|| requestQueueReferencesRoad(z.getToAddTaxiRequestQueue(), roadId)
					|| requestQueueReferencesRoad(z.getToAddBusRequestQueue(), roadId)) {
				return true;
			}
			for (Queue<Request> q : z.getSharableRequestForTaxi().values()) {
				if (requestQueueReferencesRoad(q, roadId)) return true;
			}
		}
		for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
			if (requestQueueReferencesRoad(t.getToBoardRequests(), roadId)
					|| requestQueueReferencesRoad(t.getOnBoardRequests(), roadId)) {
				return true;
			}
		}
		for (ElectricBus b : ContextCreator.getVehicleContext().getBuses()) {
			for (Queue<Request> q : b.getToBoardRequests()) {
				if (requestQueueReferencesRoad(q, roadId)) return true;
			}
			for (Queue<Request> q : b.getOnBoardRequests()) {
				if (requestQueueReferencesRoad(q, roadId)) return true;
			}
		}
		return false;
	}

	private boolean requestQueueReferencesZone(Queue<Request> requests, int zoneId) {
		for (Request r : requests) {
			if (r.getOriginZone() == zoneId || r.getDestZone() == zoneId) return true;
		}
		return false;
	}

	private boolean requestQueueReferencesRoad(Queue<Request> requests, int roadId) {
		for (Request r : requests) {
			if (r.getOriginRoad() == roadId || r.getDestRoad() == roadId) return true;
		}
		return false;
	}

	private boolean hasAlternativeClosestRoads(Road removedRoad) {
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (z.getClosestRoad(false) != null && z.getClosestRoad(false) == removedRoad.getID()
					&& nearestTaxiTripEndpointByFullScan(z.getCoord(), false, removedRoad) == null) {
				return false;
			}
			if (z.getClosestRoad(true) != null && z.getClosestRoad(true) == removedRoad.getID()
					&& nearestTaxiTripEndpointByFullScan(z.getCoord(), true, removedRoad) == null) {
				return false;
			}
		}
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			if (cs.getClosestRoad(false) != null && cs.getClosestRoad(false) == removedRoad.getID()
					&& ContextCreator.getCityContext().findRoadAtCoordinates(cs.getCoord(), false, removedRoad) == null) {
				return false;
			}
			if (cs.getClosestRoad(true) != null && cs.getClosestRoad(true) == removedRoad.getID()
					&& ContextCreator.getCityContext().findRoadAtCoordinates(cs.getCoord(), true, removedRoad) == null) {
				return false;
			}
		}
		return true;
	}

	private void removeRoadLanes(Road removedRoad) {
		ArrayList<Lane> removedLanes = new ArrayList<Lane>(removedRoad.getLanes());
		for (Lane removedLane : removedLanes) {
			for (Lane lane : ContextCreator.getLaneContext().getAll()) {
				if (lane == removedLane) continue;
				lane.getDownStreamLanes().remove(Integer.valueOf(removedLane.getID()));
				lane.getUpStreamLanes().remove(Integer.valueOf(removedLane.getID()));
			}
		}
		for (Lane removedLane : removedLanes) {
			ContextCreator.getLaneContext().remove(removedLane.getID());
		}
		removedRoad.getLanes().clear();
	}

	private void updateFacilitiesAfterRoadRemoval(Road removedRoad) {
		int roadId = removedRoad.getID();
		ContextCreator.getCityContext().clearRoadLookupCaches();

		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			z.getNeighboringLinks(false).remove(Integer.valueOf(roadId));
			z.getNeighboringLinks(true).remove(Integer.valueOf(roadId));
			if (z.getClosestRoad(false) != null && z.getClosestRoad(false) == roadId) {
				Road alt = nearestTaxiTripEndpointByFullScan(z.getCoord(), false, removedRoad);
				if (alt != null) {
					z.setClosestRoad(alt.getID(), false);
					z.setDistToRoad(ContextCreator.getCityContext().getDistance(z.getCoord(), alt.getStartCoord()), false);
					z.addNeighboringLink(alt.getID(), false);
				}
			}
			if (z.getClosestRoad(true) != null && z.getClosestRoad(true) == roadId) {
				Road alt = nearestTaxiTripEndpointByFullScan(z.getCoord(), true, removedRoad);
				if (alt != null) {
					z.setClosestRoad(alt.getID(), true);
					z.setDistToRoad(ContextCreator.getCityContext().getDistance(z.getCoord(), alt.getEndCoord()), true);
					z.addNeighboringLink(alt.getID(), true);
				}
			}
		}

		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			if (cs.getClosestRoad(false) != null && cs.getClosestRoad(false) == roadId) {
				Road alt = ContextCreator.getCityContext().findRoadAtCoordinates(cs.getCoord(), false);
				if (alt != null) {
					cs.setClosestRoad(alt.getID(), false);
					cs.setDistToRoad(ContextCreator.getCityContext().getDistance(cs.getCoord(), alt.getStartCoord()), false);
				}
			}
			if (cs.getClosestRoad(true) != null && cs.getClosestRoad(true) == roadId) {
				Road alt = ContextCreator.getCityContext().findRoadAtCoordinates(cs.getCoord(), true);
				if (alt != null) {
					cs.setClosestRoad(alt.getID(), true);
					cs.setDistToRoad(ContextCreator.getCityContext().getDistance(cs.getCoord(), alt.getEndCoord()), true);
				}
			}
		}
	}

	private int nextAvailableID(List<Integer> IDs, int minID) {
		int nextID = minID;
		for (Integer ID : IDs) {
			if (ID != null && ID >= nextID) {
				nextID = ID + 1;
			}
		}
		return Math.max(minID, nextID);
	}

	private int nextAvailableRoadID(int startID) {
		int nextID = Math.max(1, startID);
		while (ContextCreator.getRoadContext().contains(nextID)) {
			nextID++;
		}
		return nextID;
	}

	private int nextAvailableLaneID(int startID) {
		int nextID = Math.max(1, startID);
		while (ContextCreator.getLaneContext().contains(nextID)) {
			nextID++;
		}
		return nextID;
	}

	private static class EnterRoadQueueRequest {
		Integer vehicleId;
		Integer internalVehicleId;
		Boolean isPrivate;
		String roadId;
	}

	private static class QueuedVehicleMatch {
		Road road;
		Vehicle vehicle;

		QueuedVehicleMatch(Road road, Vehicle vehicle) {
			this.road = road;
			this.vehicle = vehicle;
		}
	}

	private ArrayList<EnterRoadQueueRequest> parseEnterRoadQueueRequests(Object data) {
		ArrayList<EnterRoadQueueRequest> requests = new ArrayList<EnterRoadQueueRequest>();
		if (data instanceof Map<?, ?>) {
			requests.add(enterRoadQueueRequestFromEntry(data));
		} else if (data instanceof Collection<?>) {
			for (Object entry : (Collection<?>) data) {
				requests.add(enterRoadQueueRequestFromEntry(entry));
			}
		} else if (data != null) {
			String value = data.toString().trim();
			if (value.startsWith("[")) {
				Gson gson = new Gson();
				TypeToken<Collection<Object>> collectionType = new TypeToken<Collection<Object>>() {};
				Collection<Object> parsed = gson.fromJson(value, collectionType.getType());
				if (parsed != null) {
					for (Object entry : parsed) {
						requests.add(enterRoadQueueRequestFromEntry(entry));
					}
				}
			} else if (value.startsWith("{")) {
				Gson gson = new Gson();
				Map<?, ?> parsed = gson.fromJson(value, Map.class);
				requests.add(enterRoadQueueRequestFromEntry(parsed));
			} else if (!value.isEmpty()) {
				requests.add(enterRoadQueueRequestFromEntry(value));
			}
		}
		return requests;
	}

	private EnterRoadQueueRequest enterRoadQueueRequestFromEntry(Object entry) {
		EnterRoadQueueRequest request = new EnterRoadQueueRequest();
		if (entry == null) return request;
		if (entry instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) entry;
			request.vehicleId = integerValue(record.get("vehicleId"));
			request.internalVehicleId = integerValue(record.get("internalVehicleId"));
			request.isPrivate = booleanValue(record.get("isPrivate"));
			request.roadId = stringValue(record.get("roadId"));
		} else {
			Integer idAsVehicle = integerValue(entry);
			if (idAsVehicle != null) {
				request.vehicleId = idAsVehicle;
			} else {
				request.roadId = stringValue(entry);
			}
		}
		return request;
	}

	private QueuedVehicleMatch findQueuedEnteringVehicle(EnterRoadQueueRequest request) {
		if (request.roadId != null && !request.roadId.isEmpty()) {
			Road road = ContextCreator.getCityContext().findRoadWithOrigID(request.roadId);
			if (road == null) return null;
			road.addVehicleToDepartureMap();
			if (request.vehicleId == null && request.internalVehicleId == null) {
				return new QueuedVehicleMatch(road, road.departureVehicleQueueHead());
			}
			Vehicle vehicle = findVehicleInRoadQueue(road, request);
			return vehicle == null ? null : new QueuedVehicleMatch(road, vehicle);
		}

		if (request.vehicleId == null && request.internalVehicleId == null) return null;
		for (Road road : ContextCreator.coSimRoads.values()) {
			road.addVehicleToDepartureMap();
			Vehicle vehicle = findVehicleInRoadQueue(road, request);
			if (vehicle != null) return new QueuedVehicleMatch(road, vehicle);
		}
		return null;
	}

	private Vehicle findVehicleInRoadQueue(Road road, EnterRoadQueueRequest request) {
		for (Vehicle vehicle : road.getEnteringVehicleQueueSnapshot()) {
			if (matchesEnteringVehicle(vehicle, request)) return vehicle;
		}
		return null;
	}

	private boolean matchesEnteringVehicle(Vehicle vehicle, EnterRoadQueueRequest request) {
		if (request.internalVehicleId != null && request.internalVehicleId.intValue() != vehicle.getID()) {
			return false;
		}
		if (request.isPrivate != null && request.isPrivate.booleanValue() != bridgeVehicleType(vehicle)) {
			return false;
		}
		if (request.vehicleId != null && request.vehicleId.intValue() != bridgeVehicleID(vehicle)) {
			return false;
		}
		return request.vehicleId != null || request.internalVehicleId != null;
	}

	private int bridgeVehicleID(Vehicle vehicle) {
		if (bridgeVehicleType(vehicle)) {
			int privateID = ContextCreator.getVehicleContext().getPrivateVID(vehicle.getID());
			return privateID >= 0 ? privateID : vehicle.getID();
		}
		return vehicle.getID();
	}

	private boolean bridgeVehicleType(Vehicle vehicle) {
		return vehicle.getVehicleClass() == Vehicle.EV || vehicle.getVehicleClass() == Vehicle.GV;
	}

	private Integer integerValue(Object value) {
		if (value == null) return null;
		if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
		try {
			return Integer.valueOf(String.valueOf(value));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private Boolean booleanValue(Object value) {
		if (value == null) return null;
		if (value instanceof Boolean) return (Boolean) value;
		String text = String.valueOf(value);
		if ("true".equalsIgnoreCase(text)) return Boolean.TRUE;
		if ("false".equalsIgnoreCase(text)) return Boolean.FALSE;
		if ("1".equals(text)) return Boolean.TRUE;
		if ("0".equals(text)) return Boolean.FALSE;
		return null;
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private ArrayList<Coordinate> parseRoadCenterline(RoadParams p) throws TransformException {
		ArrayList<ArrayList<Double>> rawCenterline = p.centerline;
		if (rawCenterline == null) return null;

		ArrayList<Coordinate> centerline = new ArrayList<Coordinate>();
		for (ArrayList<Double> point : rawCenterline) {
			if (point == null || point.size() < 2 || point.get(0) == null || point.get(1) == null) {
				return null;
			}
			double z = (point.size() > 2 && point.get(2) != null) ? point.get(2) : 0.0;
			Coordinate coord = new Coordinate(point.get(0), point.get(1), z);
			if (p.transformCoordinates) {
				JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
			}
			centerline.add(coord);
		}
		return centerline;
	}

	private ArrayList<String> normalizeUpstreamRoadOrigIDs(RoadParams p) {
		ArrayList<String> IDs = new ArrayList<String>();
		appendRoadOrigIDs(IDs, p.upstreamRoadIds);
		return IDs;
	}

	private ArrayList<String> normalizeDownstreamRoadOrigIDs(RoadParams p) {
		ArrayList<String> IDs = new ArrayList<String>();
		appendRoadOrigIDs(IDs, p.downstreamRoadIds);
		return IDs;
	}

	private int controlModeValue(String mode, int defaultValue) {
		Integer value = controlModeValueOrNull(mode);
		return value == null ? defaultValue : value.intValue();
	}

	private String controlModeName(int controlType) {
		return controlType == Road.COSIM ? "cosim" : "native";
	}

	private Integer controlModeValueOrNull(String mode) {
		String value = cleanString(mode);
		if (value == null) return null;
		if ("native".equalsIgnoreCase(value)) return Integer.valueOf(Road.NONE_OF_THE_ABOVE);
		if ("cosim".equalsIgnoreCase(value)) return Integer.valueOf(Road.COSIM);
		throw new IllegalArgumentException("controlMode must be 'native' or 'cosim'");
	}

	private void appendRoadOrigIDs(ArrayList<String> IDs, ArrayList<String> values) {
		if (values == null) return;
		for (String value : values) {
			appendRoadOrigID(IDs, value);
		}
	}

	private void appendRoadOrigID(ArrayList<String> IDs, String value) {
		String cleanValue = cleanString(value);
		if (cleanValue != null && !IDs.contains(cleanValue)) {
			IDs.add(cleanValue);
		}
	}

	private String resolveRoadOrigIDs(ArrayList<String> origIDs, ArrayList<Road> roads) {
		for (String origID : origIDs) {
			Road road = ContextCreator.getCityContext().findRoadWithOrigID(origID);
			if (road == null) {
				return origID;
			}
			roads.add(road);
		}
		return null;
	}

	private String validateConnectorJunctions(ArrayList<Road> roads, boolean upstream) {
		if (roads.isEmpty()) return null;
		int junctionID = upstream ? roads.get(0).getDownStreamJunction() : roads.get(0).getUpStreamJunction();
		for (Road road : roads) {
			int roadJunctionID = upstream ? road.getDownStreamJunction() : road.getUpStreamJunction();
			if (roadJunctionID != junctionID) {
				return upstream ? "Upstream roads must share the same downstream junction"
						: "Downstream roads must share the same upstream junction";
			}
		}
		return null;
	}

	private ArrayList<Coordinate> offsetCenterline(ArrayList<Coordinate> centerline, int laneIndex, int laneCount,
			double laneWidth) {
		ArrayList<Coordinate> laneCoords = new ArrayList<Coordinate>();
		double offset = (laneIndex - ((laneCount - 1) / 2.0)) * laneWidth;
		for (int i = 0; i < centerline.size(); i++) {
			Coordinate coord = centerline.get(i);
			double[] tangent = centerlineTangent(centerline, i);
			double normalX = -tangent[1];
			double normalY = tangent[0];
			laneCoords.add(new Coordinate(coord.x + normalX * offset, coord.y + normalY * offset, coord.z));
		}
		return laneCoords;
	}

	private double[] centerlineTangent(ArrayList<Coordinate> centerline, int index) {
		int left = Math.max(0, index - 1);
		int right = Math.min(centerline.size() - 1, index + 1);
		double dx = centerline.get(right).x - centerline.get(left).x;
		double dy = centerline.get(right).y - centerline.get(left).y;
		double length = Math.sqrt(dx * dx + dy * dy);
		if (length == 0) {
			for (int i = 0; i < centerline.size() - 1; i++) {
				dx = centerline.get(i + 1).x - centerline.get(i).x;
				dy = centerline.get(i + 1).y - centerline.get(i).y;
				length = Math.sqrt(dx * dx + dy * dy);
				if (length > 0) break;
			}
		}
		if (length == 0) {
			return new double[] {1.0, 0.0};
		}
		return new double[] {dx / length, dy / length};
	}

	private double polylineLength(ArrayList<Coordinate> coords) {
		if (coords == null || coords.size() < 2) return 0;
		double length = 0;
		for (int i = 0; i < coords.size() - 1; i++) {
			length += ContextCreator.getCityContext().getDistance(coords.get(i), coords.get(i + 1));
		}
		return length;
	}

	private String cleanString(String value) {
		if (value == null) return null;
		String cleanValue = value.trim();
		return cleanValue.length() == 0 ? null : cleanValue;
	}
	
	private void updateFacilitiesAfterRoadAddition(Road road) {
		ContextCreator.getCityContext().clearRoadLookupCaches();

		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (road.canBeTripOrigin()) {
				double dist = ContextCreator.getCityContext().getDistance(z.getCoord(), road.getStartCoord());
				if (z.getClosestRoad(false) == null || dist < z.getDistToRoad(false)
						|| (dist == z.getDistToRoad(false) && road.getID() < z.getClosestRoad(false))) {
					z.setClosestRoad(road.getID(), false);
					z.setDistToRoad(dist, false);
					z.addNeighboringLink(road.getID(), false);
				}
			}
			if (road.canBeTripDestination()) {
				double dist = ContextCreator.getCityContext().getDistance(z.getCoord(), road.getEndCoord());
				if (z.getClosestRoad(true) == null || dist < z.getDistToRoad(true)
						|| (dist == z.getDistToRoad(true) && road.getID() < z.getClosestRoad(true))) {
					z.setClosestRoad(road.getID(), true);
					z.setDistToRoad(dist, true);
					z.addNeighboringLink(road.getID(), true);
				}
			}
		}

		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			if (road.canBeTripOrigin()) {
				double dist = ContextCreator.getCityContext().getDistance(cs.getCoord(), road.getStartCoord());
				if (cs.getClosestRoad(false) == null || dist < cs.getDistToRoad(false)
						|| (dist == cs.getDistToRoad(false) && road.getID() < cs.getClosestRoad(false))) {
					cs.setClosestRoad(road.getID(), false);
					cs.setDistToRoad(dist, false);
				}
			}
			if (road.canBeTripDestination()) {
				double dist = ContextCreator.getCityContext().getDistance(cs.getCoord(), road.getEndCoord());
				if (cs.getClosestRoad(true) == null || dist < cs.getDistToRoad(true)
						|| (dist == cs.getDistToRoad(true) && road.getID() < cs.getClosestRoad(true))) {
					cs.setClosestRoad(road.getID(), true);
					cs.setDistToRoad(dist, true);
				}
			}
		}

		updateRoadNeighboringZone(road, false);
		updateRoadNeighboringZone(road, true);
	}

	private void updateRoadNeighboringZone(Road road, boolean goDest) {
		if (goDest ? !road.canBeTripDestination() : !road.canBeTripOrigin()) return;

		Coordinate coord = goDest ? road.getEndCoord() : road.getStartCoord();
		Zone nearestZone = nearestZoneTo(coord);
		if (nearestZone != null) {
			double nearestDistance = ContextCreator.getCityContext().getDistance(nearestZone.getCoord(), coord);
			road.setNeighboringZone(nearestZone.getID(), goDest);
			road.setDistToZone(nearestDistance, goDest);
			nearestZone.addNeighboringLink(road.getID(), goDest);
		}
	}

	private Zone nearestZoneTo(Coordinate coord) {
		if (coord == null) return null;
		Zone nearestZone = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			double dist = ContextCreator.getCityContext().getDistance(z.getCoord(), coord);
			if (dist < nearestDistance || (dist == nearestDistance && nearestZone != null && z.getID() < nearestZone.getID())) {
				nearestZone = z;
				nearestDistance = dist;
			}
		}
		return nearestZone;
	}

	private boolean isUsableRoadForFacility(Road road, boolean goDest) {
		if (road == null || road.firstLane() == null) return false;
		return goDest ? road.canBeTripDestination() : road.canBeTripOrigin();
	}

	private Road roadFromZoneForFacility(Zone zone, boolean goDest) {
		if (zone == null) return null;
		Integer closestRoadID = zone.getClosestRoad(goDest);
		Road closestRoad = closestRoadID == null ? null : ContextCreator.getRoadContext().get(closestRoadID);
		if (isUsableRoadForFacility(closestRoad, goDest)) {
			return closestRoad;
		}
		for (Integer roadId : zone.getNeighboringLinks(goDest)) {
			Road road = roadId == null ? null : ContextCreator.getRoadContext().get(roadId);
			if (isUsableRoadForFacility(road, goDest)) {
				return road;
			}
		}
		return null;
	}

	private Road nearestRoadByFullScan(Coordinate coord, boolean goDest) {
		if (coord == null) return null;
		Road nearestRoad = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			if (!isUsableRoadForFacility(road, goDest)) continue;
			Coordinate roadCoord = goDest ? road.getEndCoord() : road.getStartCoord();
			double dist = ContextCreator.getCityContext().getDistance(coord, roadCoord);
			if (dist < nearestDistance || (dist == nearestDistance && nearestRoad != null
					&& road.getID() < nearestRoad.getID())) {
				nearestRoad = road;
				nearestDistance = dist;
			}
		}
		return nearestRoad;
	}

	private Road nearestTaxiTripEndpointByFullScan(Coordinate coord, boolean goDest) {
		return nearestTaxiTripEndpointByFullScan(coord, goDest, null);
	}

	private Road nearestTaxiTripEndpointByFullScan(
			Coordinate coord, boolean goDest, Road excludedRoad) {
		if (coord == null) return null;
		Road nearestRoad = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			if (road == excludedRoad
					|| (goDest ? !road.canBeTripDestination() : !road.canBeTripOrigin())
					|| road.firstLane() == null) continue;
			Coordinate roadCoord = goDest ? road.getEndCoord() : road.getStartCoord();
			double dist = ContextCreator.getCityContext().getDistance(coord, roadCoord);
			if (dist < nearestDistance || (dist == nearestDistance && nearestRoad != null
					&& road.getID() < nearestRoad.getID())) {
				nearestRoad = road;
				nearestDistance = dist;
			}
		}
		return nearestRoad;
	}

	private Road resolveDynamicFacilityRoad(Coordinate coord, boolean goDest) {
		Road road = ContextCreator.getCityContext().findRoadAtCoordinates(coord, goDest);
		if (isUsableRoadForFacility(road, goDest)) {
			return road;
		}
		road = roadFromZoneForFacility(nearestZoneTo(coord), goDest);
		if (isUsableRoadForFacility(road, goDest)) {
			return road;
		}
		return nearestRoadByFullScan(coord, goDest);
	}

	private int selectTaxiGenerationDepartureRoad(Zone zone) {
		if (zone == null) return -1;
		try {
			int sampledRoadID = zone.sampleRoad(false);
			Road sampledRoad = ContextCreator.getRoadContext().get(sampledRoadID);
			if (sampledRoad != null && sampledRoad.canBeTripOrigin()) {
				return sampledRoadID;
			}
		} catch (RuntimeException ignored) {
			// Fall back below when a zone has no sampled departure candidates.
		}
		Road fallbackRoad = roadFromZoneForFacility(zone, false);
		if (fallbackRoad != null && fallbackRoad.canBeTripOrigin()) {
			return fallbackRoad.getID();
		}
		fallbackRoad = nearestTaxiTripEndpointByFullScan(zone.getCoord(), false);
		return fallbackRoad == null ? -1 : fallbackRoad.getID();
	}

	/**
	* Dynamically spawns e-taxis parked at a specified zone.
	* <p>Input DATA: list of {@code {zoneId, num, length}}. Optional
	* {@code length} is applied to every spawned taxi in the record, in meters.
	* <p>Output DATA: list of {@code {zoneId, IDs, STATUS}} with the
	* assigned vehicle IDs.
	*/
	private HashMap<String, Object> addTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<AddTaxiToZone>> collectionType = new TypeToken<Collection<AddTaxiToZone>>() {};
			Collection<AddTaxiToZone> requests = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (AddTaxiToZone req : requests) {
				if (!isValidOptionalVehicleLength(req.vehicleLength)) {
					HashMap<String, Object> record = new HashMap<String, Object>();
					record.put("zoneId", req.zoneId);
					record.put("status", "error");
					record.put("message", invalidVehicleLengthWarning());
					jsonData.add(record);
					continue;
				}
				Zone zone = ContextCreator.getZoneContext().get(req.zoneId);
				if (zone == null) {
					ContextCreator.logger.warn("addTaxi: zone not found: " + req.zoneId);
					HashMap<String, Object> error = statusRecord("zoneId", req.zoneId, "error");
					error.put("message", "Zone not found");
					jsonData.add(error);
					continue;
				}
				if (zone.getClosestRoad(false) == null) {
					ContextCreator.logger.warn("addTaxi: zone " + req.zoneId + " has no departure road, cannot spawn taxis");
					HashMap<String, Object> error = statusRecord("zoneId", req.zoneId, "error");
					error.put("message", "Zone has no departure road");
					jsonData.add(error);
					continue;
				}
				int departureRoadID = selectTaxiGenerationDepartureRoad(zone);
				Road departureRoad = departureRoadID >= 0 ? ContextCreator.getRoadContext().get(departureRoadID) : null;
				if (departureRoad == null) {
					ContextCreator.logger.warn("addTaxi: zone " + req.zoneId + " has no usable departure road, cannot spawn taxis");
					HashMap<String, Object> error = statusRecord("zoneId", req.zoneId, "error");
					error.put("message", "Zone has no usable departure road");
					jsonData.add(error);
					continue;
				}

				// Ensure taxi maps exist for this zone (may be a dynamically added zone)
				ContextCreator.getVehicleContext().initializeZoneMaps(req.zoneId);

				ArrayList<Integer> spawnedIDs = new ArrayList<Integer>();
				for (int i = 0; i < req.vehicleCount; i++) {
					double length = req.vehicleLength == null
							? GlobalVariables.DEFAULT_VEHICLE_LENGTH : req.vehicleLength.doubleValue();
					ElectricTaxi v = new ElectricTaxi(length);
					ContextCreator.getVehicleContext().add(v);
					v.initializePlan(req.zoneId, departureRoadID, (int) ContextCreator.getCurrentTick());
					v.getParked(zone);
					v.setCurrentZone(req.zoneId);
					v.setOriginID(req.zoneId);
					v.setDestID(req.zoneId);
					v.setOriginRoad(departureRoad);
					v.setDestRoad(departureRoad);
					v.setCurrentCoord(departureRoad.getStartCoord());
					ContextCreator.getVehicleContext().registerTaxi(v);
					ContextCreator.getVehicleContext().addAvailableTaxi(v, req.zoneId);
					zone.addParkingVehicleStock(1);
					spawnedIDs.add(v.getID());
				}

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("zoneId", req.zoneId);
				record.put("vehicleIds", spawnedIDs);
				record.put("vehicleLength", req.vehicleLength == null
						? GlobalVariables.DEFAULT_VEHICLE_LENGTH : req.vehicleLength);
				record.put("status", "ok");
				jsonData.add(record);
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addTaxi: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	/**
	* Dynamically spawns e-buses on an existing named route.
	* <p>Input DATA: list of {@code {routeName, num, length}}. Optional
	* {@code length} is applied to every spawned bus in the record, in meters.
	* <p>Output DATA: list of {@code {routeName, IDs, STATUS}} with the
	* assigned vehicle IDs.
	*/
	private HashMap<String, Object> addBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<RouteNameNum>> collectionType = new TypeToken<Collection<RouteNameNum>>() {};
			Collection<RouteNameNum> requests = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (RouteNameNum req : requests) {
				if (!isValidOptionalVehicleLength(req.vehicleLength)) {
					HashMap<String, Object> record = new HashMap<String, Object>();
					record.put("routeName", req.routeName);
					record.put("status", "error");
					record.put("message", invalidVehicleLengthWarning());
					jsonData.add(record);
					continue;
				}
				int routeID = ContextCreator.bus_schedule.getRouteID(req.routeName);
				if (routeID == -1) {
					ContextCreator.logger.warn("addBus: unknown route name: " + req.routeName);
					HashMap<String, Object> error = statusRecord("routeName", req.routeName, "error");
					error.put("message", "Route not found");
					jsonData.add(error);
					continue;
				}

				ArrayList<Integer> stopZones = ContextCreator.bus_schedule.getStopZones(routeID);
				if (stopZones == null || stopZones.isEmpty()) {
					ContextCreator.logger.warn("addBus: route " + req.routeName + " has no stop zones");
					HashMap<String, Object> error = statusRecord("routeName", req.routeName, "error");
					error.put("message", "Route has no stop zones");
					jsonData.add(error);
					continue;
				}

				Zone startZone = ContextCreator.getZoneContext().get(stopZones.get(0));
				if (startZone == null || startZone.getClosestRoad(false) == null) {
					ContextCreator.logger.warn("addBus: start zone for route " + req.routeName + " is missing or has no departure road");
					HashMap<String, Object> error = statusRecord("routeName", req.routeName, "error");
					error.put("message", "Route start zone is unavailable");
					jsonData.add(error);
					continue;
				}

				ArrayList<Integer> departureTime = new ArrayList<Integer>();
				departureTime.add((int) (ContextCreator.getCurrentTick() + 60 / GlobalVariables.SIMULATION_STEP_SIZE));

				ArrayList<Integer> spawnedIDs = new ArrayList<Integer>();
				for (int i = 0; i < req.vehicleCount; i++) {
					double length = req.vehicleLength == null
							? GlobalVariables.DEFAULT_VEHICLE_LENGTH : req.vehicleLength.doubleValue();
					ElectricBus b = new ElectricBus(
							routeID, stopZones, departureTime, length);
					b.addPlan(startZone.getID(), startZone.getClosestRoad(false), ContextCreator.getCurrentTick());
					ContextCreator.getVehicleContext().add(b);
					b.setCurrentCoord(startZone.getCoord());
					b.addPlan(startZone.getID(), startZone.getClosestRoad(false), ContextCreator.getCurrentTick());
					b.setNextPlan();
					b.addPlan(startZone.getID(), startZone.getClosestRoad(false), ContextCreator.getCurrentTick());
					b.setNextPlan();
					b.departure();
					ContextCreator.getVehicleContext().registerBus(b);
					spawnedIDs.add(b.getID());
				}

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("routeName", req.routeName);
				record.put("vehicleIds", spawnedIDs);
				record.put("vehicleLength", req.vehicleLength == null
						? GlobalVariables.DEFAULT_VEHICLE_LENGTH : req.vehicleLength);
				record.put("status", "ok");
				jsonData.add(record);
			}
			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addBus: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}

	// =============================================================
	// CHARGING (continued)
	// =============================================================

	/**
	* Command a vehicle to interrupt its current activity and go charge.
	* After charging it returns to its pre-charging destination.
	*
	* <p>Input DATA: list of {@code {vehicleId, isPrivate, chargerLevel, chargingStationId}}
	* where:
	* <ul>
	*   <li>{@code isPrivate} &mdash; {@code true} = private EV,
	*       {@code false} = public taxi.</li>
	*   <li>{@code chargerLevel} &mdash; {@code ChargingStation.L2 / L3 /
	*       BUS}.</li>
	*   <li>{@code chargingStationId} &mdash; {@code 0} for auto-select
	*       (nearest/cheapest with fallback), or a nonzero station ID for
	*       a specific station.</li>
	* </ul>
	*
	* <p>For parking taxis the return destination is set to its current
	* zone, and the vehicle is removed from the available-taxi pool only
	* after a charging dispatch is successfully prepared.
	*/
	private ChargingStation selectChargingStationForControl(ElectricVehicle veh, int chargerLevel, int chargingStationId) {
		if (chargingStationId != 0) {
			ChargingStation cs = ContextCreator.getChargingStationContext().get(chargingStationId);
			if (cs == null || cs.getClosestRoad(true) == null) {
				return null;
			}
			Road arrivalRoad = ContextCreator.getRoadContext().get(cs.getClosestRoad(true));
			return arrivalRoad != null && arrivalRoad.canBeDest() ? cs : null;
		}

		ChargingStation cs;
		if (veh instanceof ElectricTaxi || veh instanceof ElectricBus) {
			cs = ContextCreator.getCityContext().findNearestChargingStation(veh.getCurrentCoord(), chargerLevel);
			if (cs == null && chargerLevel == ChargingStation.L3) {
				cs = ContextCreator.getCityContext().findNearestChargingStation(veh.getCurrentCoord(), ChargingStation.L2);
			}
			return cs;
		}

		cs = ContextCreator.getCityContext().findCheapestChargingStation(veh.getCurrentCoord(), chargerLevel);
		if (cs == null) {
			cs = ContextCreator.getCityContext().findNearestChargingStation(veh.getCurrentCoord(), chargerLevel);
		}
		if (cs == null && chargerLevel == ChargingStation.L3) {
			cs = ContextCreator.getCityContext().findCheapestChargingStation(veh.getCurrentCoord(), ChargingStation.L2);
			if (cs == null) {
				cs = ContextCreator.getCityContext().findNearestChargingStation(veh.getCurrentCoord(), ChargingStation.L2);
			}
		}
		return cs;
	}

	private Road resolveChargingDepartureRoad(ElectricVehicle veh, Zone parkingZoneObj) {
		if (veh.getRoad() != null && veh.getRoad().canBeTripOrigin()) {
			return veh.getRoad();
		}
		Road fallbackDepartureRoad = null;
		if (veh instanceof ElectricTaxi) {
			int parkingRoadID = ((ElectricTaxi) veh).getCurrentParkingRoad();
			Road parkingRoad = parkingRoadID >= 0 ? ContextCreator.getRoadContext().get(parkingRoadID) : null;
			if (isUsableDepartureRoad(parkingRoad)) {
				return parkingRoad;
			}
			fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, parkingRoad);
		}
		Road originRoad = veh.getOriginRoad() >= 0 ? ContextCreator.getRoadContext().get(veh.getOriginRoad()) : null;
		if (isUsableDepartureRoad(originRoad)) {
			return originRoad;
		}
		fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, originRoad);
		Road destRoad = veh.getDestRoad() >= 0 ? ContextCreator.getRoadContext().get(veh.getDestRoad()) : null;
		if (isUsableDepartureRoad(destRoad)) {
			return destRoad;
		}
		fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, destRoad);
		Road lastDeparturableRoad = veh.getLastDeparturableRoad() >= 0
				? ContextCreator.getRoadContext().get(veh.getLastDeparturableRoad()) : null;
		if (isUsableDepartureRoad(lastDeparturableRoad)) {
			return lastDeparturableRoad;
		}
		fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, lastDeparturableRoad);
		if (veh instanceof ElectricTaxi && parkingZoneObj != null) {
			int zoneDepartureRoadID = selectTaxiGenerationDepartureRoad(parkingZoneObj);
			Road zoneDepartureRoad = zoneDepartureRoadID >= 0
					? ContextCreator.getRoadContext().get(zoneDepartureRoadID) : null;
			if (isUsableDepartureRoad(zoneDepartureRoad)) {
				return zoneDepartureRoad;
			}
			fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, zoneDepartureRoad);
		}
		if (parkingZoneObj != null && parkingZoneObj.getClosestRoad(false) != null) {
			Road zoneDepartureRoad = ContextCreator.getRoadContext().get(parkingZoneObj.getClosestRoad(false));
			if (isUsableDepartureRoad(zoneDepartureRoad)) {
				return zoneDepartureRoad;
			}
			fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, zoneDepartureRoad);
			for (Integer roadId : parkingZoneObj.getNeighboringLinks(false)) {
				Road neighboringRoad = roadId == null ? null : ContextCreator.getRoadContext().get(roadId);
				if (isUsableDepartureRoad(neighboringRoad)) {
					return neighboringRoad;
				}
				fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, neighboringRoad);
			}
		}
		Road nearbyRoad = ContextCreator.getCityContext().findRoadAtCoordinates(veh.getCurrentCoord(), false);
		if (isUsableDepartureRoad(nearbyRoad)) {
			return nearbyRoad;
		}
		return firstDepartureFallback(fallbackDepartureRoad, nearbyRoad);
	}

	private boolean isUsableDepartureRoad(Road road) {
		return road != null && road.canBeTripOrigin() && road.firstLane() != null;
	}

	private Road firstDepartureFallback(Road currentFallback, Road road) {
		if (currentFallback != null) return currentFallback;
		return isUsableDepartureRoad(road) ? road : null;
	}

	private int resolveChargingAnchorZone(ElectricVehicle veh, int parkingZone) {
		if (parkingZone >= 0) return parkingZone;
		if (veh.getOriginID() >= 0) return veh.getOriginID();
		if (veh.getDestID() >= 0) return veh.getDestID();
		Road road = veh.getRoad();
		if (road != null) {
			int zoneId = road.getNeighboringZone(false);
			if (ContextCreator.getZoneContext().get(zoneId) != null) return zoneId;
			zoneId = road.getNeighboringZone(true);
			if (ContextCreator.getZoneContext().get(zoneId) != null) return zoneId;
		}
		return -1;
	}

	private boolean ensureChargingPlanAnchor(ElectricVehicle veh, int anchorZoneID, Road departureRoad) {
		if (departureRoad == null || anchorZoneID < 0) return false;
		if (veh.isOnRoad()) {
			if (veh.getPlan().isEmpty()) {
				veh.setOriginID(anchorZoneID);
				veh.setDestID(anchorZoneID);
				veh.setOriginRoad(departureRoad);
				veh.setDestRoad(departureRoad);
				veh.addPlan(anchorZoneID, departureRoad.getID(), ContextCreator.getCurrentTick());
			}
			return true;
		}

		veh.getPlan().clear();
		veh.setOriginID(anchorZoneID);
		veh.setDestID(anchorZoneID);
		veh.setOriginRoad(departureRoad);
		veh.setDestRoad(departureRoad);
		veh.setCurrentCoord(departureRoad.getStartCoord());
		veh.addPlan(anchorZoneID, departureRoad.getID(), ContextCreator.getCurrentTick());
		return true;
	}

	private void removeVehicleFromEnteringQueues(Vehicle veh) {
		ContextCreator.getRoadContext().removeVehicleFromEnteringQueues(veh);
	}

	private void removeTaxiFromIdlePools(ElectricTaxi taxi, boolean releaseParking, Zone parkingZoneObj) {
		if (taxi == null) return;
		if (releaseParking) {
			taxi.releaseParkingSpot(parkingZoneObj);
		}
		ContextCreator.getVehicleContext().removeAvailableTaxiFromAllZones(taxi);
		ContextCreator.getVehicleContext().removeRelocationTaxiFromAllZones(taxi);
	}

	private boolean dispatchVehicleToCharging(ElectricVehicle veh, ChargingStation cs,
			int returnZoneID, int returnRoadID, Zone parkingZoneObj, int parkingZone) {
		if (cs == null || cs.getClosestRoad(true) == null || returnZoneID < 0 || returnRoadID < 0) {
			return false;
		}
		Road chargingArrivalRoad = ContextCreator.getRoadContext().get(cs.getClosestRoad(true));
		Road returnRoad = ContextCreator.getRoadContext().get(returnRoadID);
		if (chargingArrivalRoad == null || !chargingArrivalRoad.canBeDest()
				|| returnRoad == null || !returnRoad.canBeDest()) {
			return false;
		}

		Road departureRoad = resolveChargingDepartureRoad(veh, parkingZoneObj);
		int anchorZoneID = resolveChargingAnchorZone(veh, parkingZone);
		if (!ensureChargingPlanAnchor(veh, anchorZoneID, departureRoad)) {
			return false;
		}

		removeVehicleFromEnteringQueues(veh);
		veh.setOnChargingRoute(true);
		veh.setState(Vehicle.CHARGING_TRIP);
		List<Plan> plans = veh.getPlan();
		int chargeInsertIndex = Math.min(1, plans.size());
		plans.add(chargeInsertIndex, new Plan(cs.getID(), chargingArrivalRoad.getID(), ContextCreator.getNextTick()));
		plans.add(chargeInsertIndex + 1, new Plan(returnZoneID, returnRoadID, ContextCreator.getNextTick()));
		veh.setNextPlan();
		veh.departure(departureRoad);
		return true;
	}

	private HashMap<String, Object> goCharging(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonAns.put("message", "No DATA field found in the control message");
			jsonAns.put("status", "error");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDVehTypeChargerTypeCSID>> collectionType =
					new TypeToken<Collection<VehIDVehTypeChargerTypeCSID>>() {};
			Collection<VehIDVehTypeChargerTypeCSID> requests =
					gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (VehIDVehTypeChargerTypeCSID req : requests) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", req.vehicleId);

				ElectricVehicle veh = req.isPrivate
						? ContextCreator.getVehicleContext().getPrivateEV(req.vehicleId)
						: (ElectricVehicle) ContextCreator.getVehicleContext().getPublicVehicle(req.vehicleId);

				if (veh == null) {
					ContextCreator.logger.warn("goCharging: vehicle " + req.vehicleId + " not found");
					record.put("status", "error");
					jsonData.add(record);
					continue;
				}

				if (veh.isOnChargingRoute()) {
					ContextCreator.logger.warn("goCharging: vehicle " + req.vehicleId + " is already on a charging route");
					record.put("status", "error");
					jsonData.add(record);
					continue;
				}

				// For idle taxis: defer pool cleanup until a charging dispatch succeeds.
				boolean isPublicTaxi = !req.isPrivate && veh instanceof ElectricTaxi;
				boolean isTaxiParking = isPublicTaxi && veh.getState() == Vehicle.PARKING;
				int parkingZone = -1;
				Zone parkingZoneObj = null;
				if (isPublicTaxi) {
					ElectricTaxi taxi = (ElectricTaxi) veh;
					parkingZone = taxi.getCurrentZone();
					parkingZoneObj = ContextCreator.getZoneContext().get(parkingZone);
				}

				if (req.chargingStationId != 0) {
					// Specific charging station requested - replicate goCharging logic manually
					ChargingStation cs = selectChargingStationForControl(veh, req.chargerLevel, req.chargingStationId);
					if (cs == null) {
						ContextCreator.logger.warn("goCharging: charging station " + req.chargingStationId
								+ " not found or has no usable arrival road");
						record.put("status", "error");
						jsonData.add(record);
						continue;
					}

					int returnZoneID;
					int returnRoadID;
					if (isTaxiParking) {
						returnZoneID = parkingZone;
						Zone rz = parkingZoneObj;
						returnRoadID = (rz != null && rz.getClosestRoad(true) != null)
								? rz.getClosestRoad(true)
								: veh.getDestRoad();
					} else {
						returnZoneID = veh.getDestID();
						returnRoadID = veh.getDestRoad();
					}

					if (returnZoneID < 0 || returnRoadID < 0) {
						Road anchorRoad = resolveChargingDepartureRoad(veh, parkingZoneObj);
						int anchorZoneID = resolveChargingAnchorZone(veh, parkingZone);
						Zone anchorZone = ContextCreator.getZoneContext().get(anchorZoneID);
						if (returnZoneID < 0) {
							returnZoneID = anchorZoneID;
						}
						if (returnRoadID < 0 && anchorZone != null && anchorZone.getClosestRoad(true) != null) {
							returnRoadID = anchorZone.getClosestRoad(true);
						}
						if (returnRoadID < 0 && anchorRoad != null && anchorRoad.canBeDest()) {
							returnRoadID = anchorRoad.getID();
						}
					}

					if (returnZoneID < 0 || returnRoadID < 0) {
						// Vehicle was already heading to a charging station; refuse
						ContextCreator.logger.warn("goCharging: vehicle " + req.vehicleId + " has no valid return destination");
						record.put("status", "error");
						jsonData.add(record);
						continue;
					}

					if (!dispatchVehicleToCharging(veh, cs, returnZoneID, returnRoadID, parkingZoneObj, parkingZone)) {
						ContextCreator.logger.warn("goCharging: vehicle " + req.vehicleId
								+ " has no valid departure or return road for charging dispatch");
						record.put("status", "error");
						jsonData.add(record);
						continue;
					}
					if (isTaxiParking) {
						ElectricTaxi taxi = (ElectricTaxi) veh;
						removeTaxiFromIdlePools(taxi, true, parkingZoneObj);
					} else if (isPublicTaxi) {
						removeTaxiFromIdlePools((ElectricTaxi) veh, false, parkingZoneObj);
					}
					ContextCreator.logger.debug("goCharging: vehicle " + req.vehicleId + " dispatched to CS " + cs.getID());
				} else {
					// Auto-select: dispatch through the control path so charging interrupts queued plans.
					if (isTaxiParking) {
						// For parked taxis, ensure the return destination is the current zone
						ElectricTaxi taxi = (ElectricTaxi) veh;
						int returnZoneID = parkingZone;
						Zone rz = parkingZoneObj;
						int returnRoadID = (rz != null && rz.getClosestRoad(true) != null)
								? rz.getClosestRoad(true)
								: taxi.getDestRoad();
						ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(
								taxi.getCurrentCoord(), req.chargerLevel);
						if (cs == null && req.chargerLevel == ChargingStation.L3) {
							cs = ContextCreator.getCityContext().findNearestChargingStation(
									taxi.getCurrentCoord(), ChargingStation.L2);
						}
						if (cs == null) {
							ContextCreator.logger.warn("goCharging: no suitable station found for taxi " + req.vehicleId);
							record.put("status", "error");
							jsonData.add(record);
							continue;
						}
						if (returnZoneID < 0) {
							ContextCreator.logger.warn("goCharging: vehicle " + req.vehicleId + " has no valid return destination");
							record.put("status", "error");
							jsonData.add(record);
							continue;
						}
						if (!dispatchVehicleToCharging(taxi, cs, returnZoneID, returnRoadID, parkingZoneObj, parkingZone)) {
							ContextCreator.logger.warn("goCharging: vehicle " + req.vehicleId
									+ " has no valid departure or return road for charging dispatch");
							record.put("status", "error");
							jsonData.add(record);
							continue;
						}
						removeTaxiFromIdlePools(taxi, true, parkingZoneObj);
					} else {
						ChargingStation cs = selectChargingStationForControl(veh, req.chargerLevel, 0);
						if (cs == null) {
							ContextCreator.logger.warn("goCharging: no suitable station found for vehicle " + req.vehicleId);
							record.put("status", "error");
							jsonData.add(record);
							continue;
						}
						int returnZoneID = veh.getDestID();
						int returnRoadID = veh.getDestRoad();
						if (returnZoneID < 0 || returnRoadID < 0) {
							Road anchorRoad = resolveChargingDepartureRoad(veh, parkingZoneObj);
							int anchorZoneID = resolveChargingAnchorZone(veh, parkingZone);
							Zone anchorZone = ContextCreator.getZoneContext().get(anchorZoneID);
							if (returnZoneID < 0) {
								returnZoneID = anchorZoneID;
							}
							if (returnRoadID < 0 && anchorZone != null && anchorZone.getClosestRoad(true) != null) {
								returnRoadID = anchorZone.getClosestRoad(true);
							}
							if (returnRoadID < 0 && anchorRoad != null && anchorRoad.canBeDest()) {
								returnRoadID = anchorRoad.getID();
							}
						}
						if (!dispatchVehicleToCharging(veh, cs, returnZoneID, returnRoadID, parkingZoneObj, parkingZone)) {
							ContextCreator.logger.warn("goCharging: vehicle " + req.vehicleId
									+ " has no valid departure or return road for charging dispatch");
							record.put("status", "error");
							jsonData.add(record);
							continue;
						}
						if (isPublicTaxi) {
							removeTaxiFromIdlePools((ElectricTaxi) veh, false, parkingZoneObj);
						}
					}
				}

				record.put("status", "ok");
				jsonData.add(record);
			}

			jsonAns.put("data", jsonData);
			jsonAns.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control goCharging: " + e.toString());
			jsonAns.put("status", "error");
		}
		return jsonAns;
	}
}
