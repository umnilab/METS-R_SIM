package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

import org.geotools.geometry.jts.JTS;
import org.json.simple.JSONObject;
import repast.simphony.space.graph.RepastEdge;
import org.opengis.referencing.operation.TransformException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.facility.ChargingStation;
import mets_r.facility.ConnectorRoad;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Node;
import mets_r.facility.Road;
import mets_r.facility.RoadContext;
import mets_r.facility.Signal;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Request;
import mets_r.mobility.Vehicle;
import mets_r.mobility.VehicleContext;
import mets_r.routing.RouteContext;
import mets_r.communication.MessageClass.*;

public class QueryMessageHandler extends MessageHandler {
	private static final double MILE_IN_METERS = 1609.344;
	private static final double DEFAULT_ALMOST_FINISHED_TAXI_MILES = 5.0;
	private Random rand_route = new Random(GlobalVariables.RandomGenerator.nextInt());
	private HashMap<String, Integer> roadIndexByOrigIDCache = null;
	private int roadIndexCacheRoadCount = -1;
	private Object roadIndexCacheRoadContext = null;
	private HashMap<String, RoutingGraphRoadState> routingGraphRoadStates = null;
	private Object routingGraphRoadContext = null;
	private long routingGraphMetricVersion = 0L;
	private long routingGraphTopologyVersion = 0L;
	private long routingGraphMetricCursor = 0L;
	private long routingGraphBaselineTopologyVersion = 0L;
	private static final double ROUTING_GRAPH_EPSILON = 1e-9;
	private static final Object ROUTING_TOPOLOGY_CACHE_LOCK = new Object();
	private static RoadContext cachedRoutingTopologyContext = null;
	private static long cachedRoutingTopologyVersion = Long.MIN_VALUE;
	private static List<RoutingTopologyEntry> cachedRoutingTopology =
			Collections.emptyList();

	public QueryMessageHandler() {
		messageHandlers.put("tick", this::getTick);
		messageHandlers.put("stepStatus", this::getStepStatus);
		messageHandlers.put("capabilities", this::getCapabilities);
        // =============================================================
        // Vehicles
        // =============================================================
        messageHandlers.put("vehicle", this::getVehicle);
		messageHandlers.put("vehicleRoute", this::getVehicleRoute);
		messageHandlers.put("vehicle_route", this::getVehicleRoute);
        messageHandlers.put("onRoadVehicles", this::getOnRoadVehicles);
        messageHandlers.put("coSimVehicle", this::getCoSimVehicle);
        messageHandlers.put("taxi", this::getTaxi);
        messageHandlers.put("bus", this::getBus);

        // =============================================================
        // Roads & geometry
        // =============================================================
        messageHandlers.put("road", this::getRoad);
		messageHandlers.put("connectorPath", this::getConnectorPath);
		messageHandlers.put("connector_path", this::getConnectorPath);
        messageHandlers.put("coSimRoad", this::getCoSimRoad);
        messageHandlers.put("activeRoads", this::getActiveRoad);
        messageHandlers.put("enteringVehicleQueue", this::getEnteringVehicleQueue);
        messageHandlers.put("coSimEnteringVehicleQueue", this::getCoSimEnteringVehicleQueue);
        messageHandlers.put("centerLine", this::getCenterLine);

        // =============================================================
        // Routes & routing weights
        // =============================================================
        messageHandlers.put("routesBwCoords", this::getRoutesBwCoords);
        messageHandlers.put("routesBwRoads", this::getRoutesBwRoads);
        messageHandlers.put("multiRoutesBwCoords", this::getKRoutesBwCoords);
        messageHandlers.put("multiRoutesBwRoads", this::getKRoutesBwRoads);
        messageHandlers.put("edgeWeight", this::getEdgeWeight);
		messageHandlers.put("routingGraphUpdates", this::getRoutingGraphUpdates);
		messageHandlers.put("routingTopology", this::getRoutingTopology);

        // =============================================================
        // Zones
        // =============================================================
        messageHandlers.put("zone", this::getZone);

        // =============================================================
        // Charging stations
        // =============================================================
        messageHandlers.put("chargingStation", this::getChargingStation);

        // =============================================================
        // Bus routes
        // =============================================================
        messageHandlers.put("busRoute", this::getBusRoute);
        messageHandlers.put("busWithRoute", this::getBusWithRoute);

        // =============================================================
        // Traffic signals
        // =============================================================
        messageHandlers.put("signal", this::getSignal);
        messageHandlers.put("signalGroup", this::getSignalGroup);
        messageHandlers.put("signalForConnection", this::getSignalForConnection);

        // =============================================================
        // Ride-hailing requests
        // =============================================================
        messageHandlers.put("pendingRequests", this::getPendingRequests);
        messageHandlers.put("request", this::getRequest);
        messageHandlers.put("availableTaxis", this::getAvailableTaxis);
        messageHandlers.put("almostFinishedTaxis", this::getAlmostFinishedTaxis);
        messageHandlers.put("pickupTaxiInfo", this::getPickupTaxiInfo);
        messageHandlers.put("occupiedTaxiInfo", this::getOccupiedTaxiInfo);
    }

	public String handleMessage(String msgType, JSONObject jsonMsg) {
		CustomizableHandler handler = messageHandlers.get(msgType);
		HashMap<String, Object> jsonAns = handler == null ? null : handler.handle(jsonMsg);
		if (jsonAns == null) {
			jsonAns = new HashMap<String, Object>();
			jsonAns.put("status", "error");
			jsonAns.put("errorCode", "UNKNOWN_QUERY");
			jsonAns.put("message", "Unknown query: " + msgType);
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

	public HashMap<String, Object> getTick(JSONObject jsonMsg) {
	HashMap<String, Object> jsonObj = new HashMap<String, Object>();
	jsonObj.put("status", "ok");
	jsonObj.put("tick", ContextCreator.getCurrentTick());
	return jsonObj;
	}

	public HashMap<String, Object> getStepStatus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("status", "ok");
		jsonObj.putAll(ContextCreator.getStepStatus());
		Map<String, Object> capabilities = ContextCreator.getCapabilities();
		jsonObj.put("capabilities", capabilities);
		boolean effectiveHeadless = Boolean.TRUE.equals(capabilities.get("headless"));
		boolean includeFrameSummary = jsonMsg.containsKey("includeFrameSummary")
				? Boolean.TRUE.equals(jsonMsg.get("includeFrameSummary")) : !effectiveHeadless;
		if (includeFrameSummary) {
			addFrameSummaryFields(jsonObj);
		}
		return jsonObj;
	}

	public HashMap<String, Object> getCapabilities(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("status", "ok");
		jsonObj.putAll(ContextCreator.getCapabilities());
		return jsonObj;
	}

	// =============================================================
	// VEHICLES
	// =============================================================

	/**
	* Fetch live state for one or more vehicles.
	*
	* <p>Input DATA (optional): list of {@code {vehicleId, isPrivate,
	* transformCoordinates}}. If omitted, returns {@code public_vids} and
	* {@code private_vids} ID lists instead of per-vehicle records.
	*
	* <p>Output DATA: list of records carrying visible ID (privateVID for private vehicles),
	* vehicle class, state,
	* (x, y, z) coords, bearing, acceleration, speed, explicit
	* originRoadID/destinationRoadId and originZoneID/destZoneID fields, plus
	* road / lane / distance-to-next-junction if the vehicle is on a road.
	* Electric vehicles also include battery and totalEnergyConsumed.
	*/
	public HashMap<String, Object> getVehicle(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("publicVehicleIds", ContextCreator.getVehicleContext().getPublicVehicleIDList());
			jsonObj.put("privateVehicleIds", ContextCreator.getVehicleContext().getPrivateVehicleIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDVehTypeTran>> collectionType = new TypeToken<Collection<VehIDVehTypeTran>>() {};
			Collection<VehIDVehTypeTran> vehIDVehTypeTrans = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			// Process the query one by one
			for(VehIDVehTypeTran record: vehIDVehTypeTrans) {
				int id = record.vehicleId;
				Vehicle vehicle;
				if(record.isPrivate) {
					vehicle = ContextCreator.getVehicleContext().getPrivateVehicle(id);
				}
				else {
					vehicle = ContextCreator.getVehicleContext().getPublicVehicle(id);
				}
				if(vehicle != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					Coordinate coord = coordinateForQuery(vehicle.getCurrentCoord(), record.transformCoordinates);
					record2.put("vehicleId", bridgeVehicleID(vehicle));
					record2.put("vehicleClass", vehicle.getVehicleClass());
					record2.put("state", vehicle.getState());
					record2.put("x", coord.x);
					record2.put("y", coord.y);
					record2.put("z", coord.z);
					record2.put("bearing", vehicle.getBearing());
					record2.put("acceleration", vehicle.currentAcc());
					record2.put("speed", vehicle.currentSpeed());
					addVehicleCoordinateFields(record2, vehicle, record.transformCoordinates);
					addVehicleRoadFields(record2, vehicle);
					record2.put("originZoneId", vehicle.getOriginID());
					record2.put("destinationZoneId", vehicle.getDestID());
					if (vehicle instanceof ElectricVehicle) {
						addElectricVehicleFields(record2, (ElectricVehicle) vehicle);
					}
					if(vehicle.isOnRoad() && !vehicle.isOnConnector()) {
						Lane lane = vehicle.getLane();
						if(vehicle.isOnLane() && lane != null) {
							Road laneRoad = lane.getRoad();
							int laneIndex = laneRoad == null ? -1 : laneRoad.getLaneIndex(lane);
							record2.put("laneIndex", laneIndex);
							record2.put("distanceToSegmentEnd", vehicle.getDistanceToNextJunction());
						}
					}
					jsonData.add(record2);
				}
				else {
					jsonData.add(errorRecord("vehicleId", id));
				}
			}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query" + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}

	}

	/**
	* Return active roads from the active-road index.
	*
	* <p>Output fields: {@code id_list}/{@code orig_id} contain active road
	* original IDs. {@code DATA} contains one compact record per active road.
	*/
	public HashMap<String, Object> getActiveRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		ArrayList<String> activeRoadIDs = new ArrayList<String>();
		ArrayList<Object> jsonData = new ArrayList<Object>();

		try {
			if (ContextCreator.getRoadContext() != null) {
				for (Road road : ContextCreator.getRoadContext().getActiveRoadsSnapshot()) {
					if (road == null) continue;
					String roadId = road.getOrigID();
					HashMap<String, Object> record = new HashMap<String, Object>();
					record.put("segmentId", roadId);
					int vehicleCount =
							ContextCreator.getRoadContext().getQueryableVehicleCount(road);
					if (vehicleCount == 0 && road.getPendingDepartureVehicleNum() == 0) continue;
					activeRoadIDs.add(roadId);
					record.put("vehicleCount", vehicleCount);
					record.put("queuedVehicleCount", road.getPendingDepartureVehicleNum());
					record.put("segmentType", "road");
					record.put("status", "ok");
					jsonData.add(record);
				}
				for (ConnectorRoad connector
						: ContextCreator.getRoadContext().getActiveConnectorsSnapshot()) {
					String connectorID = connector.getOrigID();
					activeRoadIDs.add(connectorID);
					HashMap<String, Object> record = connectorRoadRecord(connector);
					record.put("status", "ok");
					jsonData.add(record);
				}
			}
			addSegmentIdLists(jsonObj, activeRoadIDs);
			jsonObj.put("data", jsonData);
			jsonObj.put("status", "ok");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getActiveRoad: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	/**
	* Return IDs for vehicles currently on roads.
	*
	* <p>Without DATA, output fields mirror {@link #getVehicle}'s no-DATA ID
	* lists across all active roads. With DATA, input is a single original road
	* ID, a list of original road IDs, or records carrying
	* {@code roadId}/{@code ID}/{@code origID}; output DATA contains one record
	* per requested road with {@code private_vids} and {@code public_vids}.
	*
	* <p>The no-DATA query walks only the active-road snapshot. A road-filtered
	* query walks only each requested road's macro vehicle chain.
	*/
	public HashMap<String, Object> getOnRoadVehicles(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();

		try {
			if (jsonMsg.containsKey("data")) {
				ArrayList<Object> jsonData = new ArrayList<Object>();
				for (String roadId : parseRoadIDs(jsonMsg.get("data"))) {
					Road road = findQueryableRoad(roadId);
					if (road != null) {
						jsonData.add(onRoadVehicleRecord(road));
					} else {
						HashMap<String, Object> record = new HashMap<String, Object>();
						record.put("segmentId", roadId);
						record.put("status", "error");
						jsonData.add(record);
					}
				}
				jsonObj.put("data", jsonData);
				jsonObj.put("status", "ok");
				return jsonObj;
			}

			ArrayList<Integer> privateVehicleIDs = new ArrayList<Integer>();
			ArrayList<Integer> publicVehicleIDs = new ArrayList<Integer>();
			if (ContextCreator.getRoadContext() != null) {
				for (Road road : ContextCreator.getRoadContext().getActiveRoadsSnapshot()) {
					appendOnRoadVehicleIDs(road, privateVehicleIDs, publicVehicleIDs);
				}
				for (ConnectorRoad connector
						: ContextCreator.getRoadContext().getActiveConnectorsSnapshot()) {
					appendOnRoadVehicleIDs(connector, privateVehicleIDs, publicVehicleIDs);
				}
			}
			jsonObj.put("privateVehicleIds", privateVehicleIDs);
			jsonObj.put("publicVehicleIds", publicVehicleIDs);
			jsonObj.put("status", "ok");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getOnRoadVehicles: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	private HashMap<String, Object> onRoadVehicleRecord(Road road) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		ArrayList<Integer> privateVehicleIDs = new ArrayList<Integer>();
		ArrayList<Integer> publicVehicleIDs = new ArrayList<Integer>();
		appendOnRoadVehicleIDs(road, privateVehicleIDs, publicVehicleIDs);
		record.put("segmentId", road.getOrigID());
		record.put("privateVehicleIds", privateVehicleIDs);
		record.put("publicVehicleIds", publicVehicleIDs);
		record.put("segmentType", road instanceof ConnectorRoad ? "connector" : "road");
		if (road instanceof ConnectorRoad) record.put("laneIndex", null);
		record.put("status", "ok");
		return record;
	}

	private void appendOnRoadVehicleIDs(Road road, ArrayList<Integer> privateVehicleIDs,
			ArrayList<Integer> publicVehicleIDs) {
		if (road == null) return;
		if (road instanceof ConnectorRoad) {
			for (Vehicle vehicle : ((ConnectorRoad) road).getActiveVehiclesSnapshot()) {
				if (!vehicle.isOnConnector()) continue;
				if (bridgeVehicleType(vehicle)) {
					privateVehicleIDs.add(bridgeVehicleID(vehicle));
				} else {
					publicVehicleIDs.add(vehicle.getID());
				}
			}
			return;
		}
		Vehicle vehicle = road.firstVehicle();
		while (vehicle != null) {
			Vehicle nextVehicle = vehicle.macroTrailing();
			if (vehicle.isOnRoad() && !vehicle.isOnConnector()) {
				if (bridgeVehicleType(vehicle)) {
					privateVehicleIDs.add(bridgeVehicleID(vehicle));
				} else {
					publicVehicleIDs.add(vehicle.getID());
				}
			}
			vehicle = nextVehicle;
		}
	}
	/**
	* Snapshot of every vehicle currently on a co-simulation road, plus vehicles
	* still externally controlled on a connector touching a controlled road
	* (i.e. roads previously marked via the {@code setCoSimRoad} control
	* API). Used by the bridge to a CARLA / SUMO simulator.
	*
	* <p>Output DATA: list of {@code {ID, v_type, coord_map, route}} for
	* each vehicle currently inhabiting a co-sim road, where
	* {@code coord_map} is a short trail of recent coordinates and
	* {@code v_type} is {@code true} for private, {@code false} for
	* public.
	*/
	public  HashMap<String, Object> getCoSimVehicle(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();

//		List<Integer> vehicleIDList = new ArrayList<Integer>();
//		List<Boolean> vehicleTypeList = new ArrayList<Boolean>();
		LinkedHashMap<Integer, Vehicle> vehiclesByID =
				new LinkedHashMap<Integer, Vehicle>();
		RoadContext roadContext = ContextCreator.getRoadContext();
		for (Road road : roadContext.getCoSimPhysicalRoadsSnapshot()) {
			Vehicle vehicle = road.firstVehicle();
			while (vehicle != null) {
				Vehicle nextVehicle = vehicle.macroTrailing();
				vehiclesByID.put(vehicle.getID(), vehicle);
				vehicle = nextVehicle;
			}
		}
		for (ConnectorRoad connector : roadContext.getCoSimConnectorsSnapshot()) {
			for (Vehicle vehicle : connector.getActiveVehiclesSnapshot()) {
				// A newly controlled connector also transfers a native vehicle whose
				// front is still inside it, even when that vehicle is macro-attached to
				// a native target road. Rear-only connector reservations stay native.
				// The vehicle-ID map deduplicates connector and physical-road scans.
				if (vehicle.isOnConnector()) {
					vehiclesByID.put(vehicle.getID(), vehicle);
				}
			}
		}

		ArrayList<Object> jsonData = new ArrayList<Object>(vehiclesByID.size());
		for (Vehicle vehicle : vehiclesByID.values()) {
			appendCoSimVehicleRecord(jsonData, vehicle);
		}

		jsonObj.put("data", jsonData);

		return jsonObj;
	}

	public HashMap<String, Object> getCoSimRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		ArrayList<Object> jsonData = new ArrayList<Object>();
		ArrayList<String> roadIDs = new ArrayList<String>();
		for (Road road : ContextCreator.getRoadContext().getCoSimSegmentsSnapshot()) {
			roadIDs.add(road.getOrigID());
			if (road instanceof ConnectorRoad) {
				jsonData.add(connectorRoadRecord((ConnectorRoad) road));
			} else {
				jsonData.add(physicalRoadRecord(road));
			}
		}
		addSegmentIdLists(jsonObj, roadIDs);
		jsonObj.put("data", jsonData);
		jsonObj.put("status", "ok");
		return jsonObj;
	}

	private void appendCoSimVehicleRecord(ArrayList<Object> jsonData, Vehicle vehicle) {
		if (vehicle == null) return;
		int bridgeVehicleID;
		boolean privateVehicle;
		if (vehicle.getVehicleClass() == Vehicle.EV || vehicle.getVehicleClass() == Vehicle.GV) {
			bridgeVehicleID = ContextCreator.getVehicleContext().getPrivateVID(vehicle.getID());
			privateVehicle = true;
		} else {
			bridgeVehicleID = vehicle.getID();
			privateVehicle = false;
		}
		if (bridgeVehicleID == -1) return;

		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("vehicleId", bridgeVehicleID);
		record.put("isPrivate", privateVehicle);
		record.put("coordinateTrail", vehicle.getRecentCoordMap(6, true));
		record.put("routeRoadIds", vehicle.getRoute());
		addVehicleRoadFields(record, vehicle);
		jsonData.add(record);
	}

	/**
	* Fetch live status for one or more buses.
	*
	* <p>Input DATA (optional): list of integer bus IDs. If omitted,
	* returns the {@code id_list} of all known buses.
	*
	* <p>Output DATA: list of {@code {ID, route, stopZones, current_stop,
	* originRoadID, destinationRoadId, pass_num, matchedRequests, matchedPassengers, pickupRequests,
	* pickupPassengers, dropoffRequests, dropoffPassengers, battery_state}}
	* records.
	*/
	public  HashMap<String, Object> getBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("busIds", ContextCreator.getVehicleContext().getBusIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		Collection<Integer> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(int id: IDs) {
			ElectricBus bus = ContextCreator.getVehicleContext().getBus(id);
				if(bus != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("busId", bus.getID());
					int routeID = bus.getRouteID();
					String routeName = ContextCreator.bus_schedule.getRouteName(routeID);
					record2.put("routeName", routeName);
					record2.put("routeId", routeID);
					record2.put("stopZoneIds", bus.getBusStops());
					record2.put("stopZoneCount", bus.getBusStops().size());
					record2.put("currentStopIndex", bus.getCurrentStop());
					record2.put("passengerCount", bus.getPassNum());
					Coordinate currCoord = bus.getCurrentCoord();
					record2.put("x", currCoord.x);
					record2.put("y", currCoord.y);
					record2.put("z", currCoord.z);
					addVehicleCoordinateFields(record2, bus, false);
					record2.put("bearing", bus.getBearing());
					record2.put("speed", bus.currentSpeed());
					record2.put("battery", bus.getBatteryLevel());
					record2.put("energyConsumed", bus.getTotalConsume());
					record2.put("matchedRequests", bus.getMatchedRequests());
					record2.put("matchedPassengers", bus.getMatchedPassengers());
					record2.put("pickupRequests", bus.getPickupRequests());
					record2.put("pickupPassengers", bus.getPickupPassengers());
					record2.put("dropoffRequests", bus.getDropoffRequests());
					record2.put("dropoffPassengers", bus.getDropoffPassengers());
					addVehicleRoadFields(record2, bus);
					jsonData.add(record2);
				}
				else {
					jsonData.add(errorRecord("busId", id));
				}
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	/**
	* Fetch live status for one or more taxis (any state).
	* For only-idle taxis use {@link #getAvailableTaxis} instead.
	*
	* <p>Input DATA (optional): list of integer taxi IDs. If omitted,
	* returns the {@code id_list} of all known taxis.
	*
	* <p>Output DATA: list of {@code {ID, state, x, y, z, originZoneID, destZoneID,
	* originRoadID, destinationRoadId, pass_num, matchedRequests, matchedPassengers, pickupRequests,
	* pickupPassengers, dropoffRequests, dropoffPassengers}} records. Active
	* trip states also include connector-inclusive {@code remainingDistance} in
	* meters, {@code remainingDistanceMiles}, and connector-only distance /
	* travel-time breakdowns (meters / seconds).
	*/
	public HashMap<String, Object> getTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("taxiIds", ContextCreator.getVehicleContext().getTaxiIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		Collection<Integer> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(int id: IDs) {
			ElectricTaxi taxi = ContextCreator.getVehicleContext().getTaxi(id);
				if (taxi != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("taxiId", taxi.getID());
					record2.put("state", taxi.getState());
					Coordinate currCoord = taxi.getCurrentCoord();
					record2.put("x", currCoord.x);
					record2.put("y", currCoord.y);
					record2.put("z", currCoord.z);
					record2.put("originZoneId", taxi.getOriginID());
					record2.put("destinationZoneId", taxi.getDestID());
					addVehicleCoordinateFields(record2, taxi, false);
					record2.put("bearing", taxi.getBearing());
					record2.put("speed", taxi.currentSpeed());
					record2.put("battery", taxi.getBatteryLevel());
					record2.put("energyConsumed", taxi.getTotalConsume());
					record2.put("passengerCount", taxi.getPassNum());
					record2.put("matchedRequests", taxi.getMatchedRequests());
					record2.put("matchedPassengers", taxi.getMatchedPassengers());
					record2.put("pickupRequests", taxi.getPickupRequests());
					record2.put("pickupPassengers", taxi.getPickupPassengers());
					record2.put("dropoffRequests", taxi.getDropoffRequests());
					record2.put("dropoffPassengers", taxi.getDropoffPassengers());
					record2.put("toBoardRequestIds", requestIDs(taxi.getToBoardRequests()));
					record2.put("onBoardRequestIds", requestIDs(taxi.getOnBoardRequests()));
					addRemainingDistanceFields(record2, taxi);
					addVehicleRoadFields(record2, taxi);
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("taxiId", id));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	private void addRemainingDistanceFields(HashMap<String, Object> record, Vehicle vehicle) {
		if (!shouldReportRemainingDistance(vehicle)) return;
		double remainingDistance = remainingDistance(vehicle);
		double connectorDistance = vehicle.getRemainingConnectorDistance();
		record.put("remainingDistance", remainingDistance);
		record.put("remainingDistanceMiles", remainingDistance / MILE_IN_METERS);
		record.put("remainingConnectorDistance", connectorDistance);
		record.put("remainingConnectorDistanceMiles", connectorDistance / MILE_IN_METERS);
		record.put("remainingConnectorTravelTime",
				vehicle.getRemainingConnectorTravelTime());
	}

	private boolean shouldReportRemainingDistance(Vehicle vehicle) {
		if (vehicle == null) return false;
		int state = vehicle.getState();
		return state != Vehicle.PARKING
				&& state != Vehicle.CRUISING_TRIP
				&& state != Vehicle.NONE_OF_THE_ABOVE;
	}

	private double remainingDistance(Vehicle vehicle) {
		return vehicle == null ? Double.POSITIVE_INFINITY
				: Math.max(0.0, vehicle.getDistToTravelIncludingConnectors());
	}

	private void addVehicleRoadFields(HashMap<String, Object> record, Vehicle vehicle) {
		record.put("onRoad", vehicle.isOnRoad());
		record.put("onConnector", vehicle.isOnConnector());
		record.put("connectorOccupancyActive", vehicle.hasActiveConnectorReservation());
		Road currentRoad = vehicle.isOnRoad() ? vehicle.getRoad() : null;
		Road queuedRoad = vehicle.isOnRoad() ? null : findEnteringQueueRoad(vehicle);
		int originRoadID = firstAvailableRoadID(vehicle.getOriginRoad(), vehicle.getLastDeparturableRoad(),
				roadIDOrNegative(currentRoad), vehicle.getCurrentParkingRoad(), roadIDOrNegative(queuedRoad));
		int destinationRoadId = vehicle.getDestRoad();
		record.put("originRoadId", roadOrigIDOrNull(originRoadID));
		record.put("destinationRoadId", roadOrigIDOrNull(destinationRoadId));
		record.put("currentParkingRoadId", roadOrigIDOrNull(vehicle.getCurrentParkingRoad()));
		ConnectorRoad connector = vehicle.isOnConnector()
				? vehicle.getCurrentConnector() : null;
		if (connector != null) {
			record.put("segmentId", connector.getOrigID());
			record.put("segmentType", "connector");
			record.put("connectorId", connector.getOrigID());
			record.put("internalEdgeIds", connector.getInternalEdgeIDs());
			ConnectorRoad.ConnectorPath connectorPath = vehicle.getCurrentConnectorPath();
			if (connectorPath != null) {
				record.put("connectorPathId", connectorPath.getConnectorPathID());
				record.put("connectorPathInternalEdgeIds",
						connectorPath.getInternalEdgeIDs());
				record.put("connectorPathViaLaneIds", connectorPath.getViaLaneIDs());
			}
			record.put("laneIndex", null);
			double connectorDistanceRemaining =
					vehicle.getEstimatedConnectorDistanceRemaining();
			double connectorTravelTimeRemaining =
					vehicle.getEstimatedConnectorTravelTimeRemaining();
			if (shouldEmitConnectorDistance(true, connectorDistanceRemaining)) {
				record.put("distanceToSegmentEnd", connectorDistanceRemaining);
				record.put("connectorDistanceRemaining", connectorDistanceRemaining);
			}
			if (Double.isFinite(connectorTravelTimeRemaining)) {
				record.put("connectorTravelTimeRemaining",
						connectorTravelTimeRemaining);
			}
			record.put("connectorDistance", connector.getLength());
			record.put("connectorTravelTime", connector.getTravelTime());
			record.put("connectorTravelTimeP90", connector.getTravelTimeP90());
			record.put("controlMode", controlModeName(connector.getControlType()));
			record.put("segmentActive", connector.hasActiveVehicles());
			record.put("sourceRoadId",
					connector.getSourceRoad().getOrigID());
			record.put("targetRoadId",
					connector.getTargetRoad().getOrigID());
			record.put("intersectionId", connector.getIntersectionID());
			record.put("intersectionCollision",
					ContextCreator.getRoadContext().connectorHasCollision(connector));
			return;
		}
		if (currentRoad != null) {
			record.put("segmentId", currentRoad.getOrigID());
			record.put("segmentType", "road");
			record.put("controlMode", controlModeName(currentRoad.getControlType()));
			record.put("segmentActive", ContextCreator.getRoadContext().isRoadActive(currentRoad.getID()));
			Lane currentLane = vehicle.isOnLane() ? vehicle.getLane() : null;
			int laneIndex = currentLane == null ? -1
					: currentRoad.getLaneIndex(currentLane);
			record.put("laneIndex", laneIndex);
			if (laneIndex >= 0) {
				record.put("distanceToSegmentEnd", vehicle.getDistanceToNextJunction());
			}
			return;
		}

		if (queuedRoad != null) {
			record.put("queuedRoadId", queuedRoad.getOrigID());
			record.put("queuedRoadControlMode", controlModeName(queuedRoad.getControlType()));
			record.put("queuedRoadActive", ContextCreator.getRoadContext().isRoadActive(queuedRoad.getID()));
		}
	}

	static boolean shouldEmitConnectorDistance(boolean hasUsableEstimate,
			double connectorDistance) {
		return hasUsableEstimate && Double.isFinite(connectorDistance);
	}

	private int firstAvailableRoadID(int... roadIDs) {
		for (int roadId : roadIDs) {
			if (roadId >= 0) return roadId;
		}
		return -1;
	}

	private int roadIDOrNegative(Road road) {
		return road == null ? -1 : road.getID();
	}

	private String roadOrigIDOrNull(int roadId) {
		if (roadId < 0) return null;
		if (ContextCreator.getRoadContext() == null) return null;
		Road road = ContextCreator.getRoadContext().get(roadId);
		return road == null ? null : road.getOrigID();
	}

	private Road findEnteringQueueRoad(Vehicle vehicle) {
		if (vehicle == null || vehicle.isOnRoad() || ContextCreator.getRoadContext() == null) return null;
		return ContextCreator.getRoadContext().getEnteringRoadForVehicle(vehicle);
	}


    private void addFrameSummaryFields(HashMap<String, Object> record) {
        int matchedRequests = 0;
        int matchedPassengers = 0;
        int pickupRequests = 0;
        int pickupPassengers = 0;
        int dropoffRequests = 0;
        int dropoffPassengers = 0;
        int leftRequests = 0;
        int leftPassengers = 0;
        if (ContextCreator.getZoneContext() != null) {
        for (Zone zone : ContextCreator.getZoneContext().getAll()) {
            matchedRequests += zone.taxiPickupRequest + zone.busPickupRequest;
            matchedPassengers += zone.taxiPickupPassengers + zone.busPickupPassengers;
            pickupRequests += zone.taxiPickedUpRequest + zone.busPickedUpRequest;
            pickupPassengers += zone.taxiPickedUpPassengers + zone.busPickedUpPassengers;
            dropoffRequests += zone.taxiServedRequest + zone.busServedRequest;
            dropoffPassengers += zone.taxiServedPassengers + zone.busServedPassengers;
            leftRequests += zone.numberOfLeavedTaxiRequest + zone.numberOfLeavedBusRequest;
            leftPassengers += zone.numberOfLeavedTaxiPassengers + zone.numberOfLeavedBusPassengers;
        }
        }

        LiveFrameSummary live = liveFrameSummary();
        record.put("matchedRequests", matchedRequests);
        record.put("matchedPassengers", matchedPassengers);
        record.put("pickupRequests", pickupRequests);
        record.put("pickupPassengers", pickupPassengers);
        record.put("dropoffRequests", dropoffRequests);
        record.put("dropoffPassengers", dropoffPassengers);
        record.put("leftRequests", leftRequests);
        record.put("leftPassengers", leftPassengers);
		record.put("energyConsumed", live.privateEVEnergy + live.eTaxiEnergy + live.eBusEnergy);
		record.put("vehicleCount", live.numVeh);
        record.put("meanSpeed", live.speedCount == 0 ? 0.0 : live.speedSum / live.speedCount);
        record.put("energyPrivateEV", live.privateEVEnergy);
        record.put("energyETaxi", live.eTaxiEnergy);
        record.put("energyEBus", live.eBusEnergy);
    }

    private LiveFrameSummary liveFrameSummary() {
        LiveFrameSummary summary = new LiveFrameSummary();
        summary.numVeh = liveRoadVehicleCount();
        if (ContextCreator.getVehicleContext() == null) return summary;
        for (ElectricVehicle vehicle : ContextCreator.getVehicleContext().getPrivateEVs()) {
            if (vehicle == null) continue;
            summary.privateEVEnergy += vehicle.getTotalConsume();
            accumulateSpeed(summary, vehicle);
        }
        for (Vehicle vehicle : ContextCreator.getVehicleContext().getPrivateGVs()) {
            accumulateSpeed(summary, vehicle);
        }
        for (ElectricTaxi taxi : ContextCreator.getVehicleContext().getTaxis()) {
            if (taxi == null) continue;
            summary.eTaxiEnergy += taxi.getTotalConsume();
            accumulateSpeed(summary, taxi);
        }
        for (ElectricBus bus : ContextCreator.getVehicleContext().getBuses()) {
            if (bus == null) continue;
            summary.eBusEnergy += bus.getTotalConsume();
            accumulateSpeed(summary, bus);
        }
        return summary;
    }

    private int liveRoadVehicleCount() {
        int count = 0;
        if (ContextCreator.getRoadContext() == null) return count;
        for (Road road : ContextCreator.getRoadContext().getAll()) {
            if (road != null) count += road.getVehicleNum();
        }
        return count;
    }

    private void accumulateSpeed(LiveFrameSummary summary, Vehicle vehicle) {
        if (vehicle == null || !vehicle.isOnRoad()) return;
        summary.speedSum += vehicle.currentSpeed();
        summary.speedCount++;
    }

    private void addVehicleCoordinateFields(HashMap<String, Object> record, Vehicle vehicle,
            boolean transformCoordinates) {
        Coordinate prevCoord = coordinateForQuery(vehicle.getpreviousEpochCoord(), transformCoordinates);
        Coordinate originCoord = null;
        Coordinate destCoord = null;

        try {
            originCoord = coordinateForQuery(vehicle.getOriginCoord(), transformCoordinates);
        } catch (NullPointerException ignored) {
            originCoord = coordinateForQuery(vehicle.getCurrentCoord(), transformCoordinates);
        }

        try {
            destCoord = coordinateForQuery(vehicle.getDestCoord(), transformCoordinates);
        } catch (NullPointerException ignored) {
            destCoord = coordinateForQuery(vehicle.getCurrentCoord(), transformCoordinates);
        }

        putXY(record, "prev", prevCoord);
        putXY(record, "origin", originCoord);
        putXY(record, "dest", destCoord);
    }

    private void addElectricVehicleFields(HashMap<String, Object> record, ElectricVehicle vehicle) {
        record.put("battery", vehicle.getBatteryLevel());
        record.put("totalEnergyConsumed", vehicle.getTotalConsume());
        record.put("tripNumber", vehicle.getNumTrips());
    }

    private Coordinate coordinateForQuery(Coordinate coord, boolean transformCoordinates) {
        if (coord == null) return null;
        Coordinate copy = new Coordinate();
        copy.x = coord.x;
        copy.y = coord.y;
        copy.z = coord.z;
        if (!transformCoordinates) return copy;
        try {
            JTS.transform(copy, copy, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform.inverse());
        } catch (TransformException e) {
            ContextCreator.logger.error("Coordinates transformation failed, input x: " + coord.x + " y:" + coord.y);
        }
        return copy;
    }

    private void putXY(HashMap<String, Object> record, String prefix, Coordinate coord) {
        if (coord == null) return;
        record.put(prefix + "X", coord.x);
        record.put(prefix + "Y", coord.y);
    }

    private static class LiveFrameSummary {
        double privateEVEnergy = 0.0;
        double eTaxiEnergy = 0.0;
        double eBusEnergy = 0.0;
        int numVeh = 0;
        double speedSum = 0.0;
        int speedCount = 0;
    }

	// =============================================================
	// ROADS & GEOMETRY
	// =============================================================

	/**
	* Fetch live state for one or more roads.
	*
	* <p>Input DATA (optional): list of original road IDs. If omitted,
	* returns {@code id_list} / {@code orig_id} as original road IDs.
	*
	* <p>Output DATA: list of {@code {ID, roadId, roadIndex, r_type, num_veh,
	* speed_limit, avg_travel_time, length, energy_consumed,
	* avg_energy_consumption, parking_capacity, parked_num, down_stream_road}}
	* records.
	*/
	public HashMap<String, Object> getRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			beginRoutingGraphSnapshotBaseline();
			List<String> roadIDs =
					ContextCreator.getRoadContext().getQueryableOrigIDList();
			addSegmentIdLists(jsonObj, roadIDs);
			jsonObj.put("status", "ok");
			addRoutingGraphMetadata(jsonObj, false);
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		Collection<String> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(String id: IDs) {
			Road road = findQueryableRoad(id);
				if (road != null) {
					if (road instanceof ConnectorRoad) {
						jsonData.add(connectorRoadRecord((ConnectorRoad) road));
						continue;
					}
					rememberRoutingGraphRoadSnapshot(road);
					jsonData.add(physicalRoadRecord(road));
				}
				else jsonData.add(errorRecord("segmentId", id));
		}
			jsonObj.put("data", jsonData);
			jsonObj.put("status", "ok");
			addRoutingGraphMetadata(jsonObj, false);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	/**
	 * Fetch the currently assigned remaining route for one or more vehicles.
	 *
	 * <p>Input DATA: one record or a list of {@code {vehicleId, isPrivate}}.
	 * The route is read from the vehicle; this query does not calculate or change
	 * a route. Physical-road IDs, connector IDs, the interleaved segment path,
	 * connector-aware distance, and mean/P90 travel-time metrics are returned.
	 */
	public HashMap<String, Object> getVehicleRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("tick", ContextCreator.getCurrentTick());
		if (!jsonMsg.containsKey("data")) {
			jsonObj.put("publicVehicleIds", ContextCreator.getVehicleContext().getPublicVehicleIDList());
			jsonObj.put("privateVehicleIds", ContextCreator.getVehicleContext().getPrivateVehicleIDList());
			return jsonObj;
		}

		try {
			Gson gson = new Gson();
			String dataJson = jsonMsg.get("data").toString();
			ArrayList<VehIDVehType> requests = new ArrayList<VehIDVehType>();
			if (dataJson.trim().startsWith("[")) {
				TypeToken<Collection<VehIDVehType>> collectionType =
						new TypeToken<Collection<VehIDVehType>>() {};
				Collection<VehIDVehType> parsed = gson.fromJson(dataJson, collectionType.getType());
				if (parsed != null) requests.addAll(parsed);
			}
			else {
				VehIDVehType parsed = gson.fromJson(dataJson, VehIDVehType.class);
				if (parsed != null) requests.add(parsed);
			}

			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (VehIDVehType request : requests) {
				int requestedID = request.vehicleId;
				Vehicle vehicle = request.isPrivate
						? ContextCreator.getVehicleContext().getPrivateVehicle(requestedID)
						: ContextCreator.getVehicleContext().getPublicVehicle(requestedID);
				if (vehicle == null) {
					HashMap<String, Object> error = errorRecord("vehicleId", requestedID);
					error.put("isPrivate", request.isPrivate);
					error.put("errorCode", "VEHICLE_NOT_FOUND");
					error.put("message", "Vehicle was not found.");
					jsonData.add(error);
					continue;
				}

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("vehicleId", bridgeVehicleID(vehicle));
				record.put("internalVehicleId", vehicle.getID());
				record.put("isPrivate", request.isPrivate);
				record.put("vehicleClass", vehicle.getVehicleClass());
				record.put("state", vehicle.getState());
				record.put("originZoneId", vehicle.getOriginID());
				record.put("destinationZoneId", vehicle.getDestID());
				addVehicleRoadFields(record, vehicle);

				List<Road> route = vehicle.getRoadPathSnapshot();
				if (route.isEmpty()) {
					record.put("status", "error");
					record.put("errorCode", "ROUTE_NOT_AVAILABLE");
					record.put("message", "The vehicle currently has no assigned route.");
					jsonData.add(record);
					continue;
				}

				record.put("routeScope", "remainingAssignedRoute");
				addRouteMetrics(record, route);
				Object segmentIds = record.get("segmentIds");
				record.put("routeSegmentCount", segmentIds instanceof Collection<?>
						? ((Collection<?>) segmentIds).size() : route.size());
				addRemainingDistanceFields(record, vehicle);
				record.put("status", "ok");
				jsonData.add(record);
			}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing vehicle-route query: " + e.toString());
			jsonObj.put("status", "error");
			jsonObj.put("errorCode", "INVALID_VEHICLE_ROUTE_QUERY");
			return jsonObj;
		}
	}

	/**
	 * Query lane-to-lane paths belonging to a movement-level connector.
	 *
	 * <p>Input DATA is one record or a list of records containing required
	 * {@code connectorId} and optional {@code connectorPathId}. Omitting the path
	 * ID returns every path on the connector; supplying its zero-based connector-local
	 * index returns that path and its exact {@code internalEdgeIds}.</p>
	 */
	public HashMap<String, Object> getConnectorPath(JSONObject jsonMsg) {
		HashMap<String, Object> response = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			response.put("status", "error");
			response.put("errorCode", "MISSING_DATA");
			response.put("message", "connectorPath query requires DATA with connectorId");
			return response;
		}
		ArrayList<Object> data = new ArrayList<Object>();
		try {
			for (ConnectorPathQuery request : parseConnectorPathQueries(jsonMsg.get("data"))) {
				if (request.connectorId == null || request.connectorId.isEmpty()) {
					HashMap<String, Object> error = errorRecord("connectorId", null);
					error.put("errorCode", "MISSING_CONNECTOR_ID");
					error.put("message", "connectorId is required");
					data.add(error);
					continue;
				}
				ConnectorRoad connector = ContextCreator.getRoadContext()
						.getConnector(request.connectorId);
				if (connector == null) {
					HashMap<String, Object> error = errorRecord(
							"connectorId", request.connectorId);
					error.put("errorCode", "CONNECTOR_NOT_FOUND");
					data.add(error);
					continue;
				}
				if (request.connectorPathId != null) {
					ConnectorRoad.ConnectorPath path = connector.getPathByID(
							request.connectorPathId);
					if (path == null) {
						HashMap<String, Object> error = errorRecord(
								"connectorPathId", request.connectorPathId);
						error.put("connectorId", connector.getOrigID());
						error.put("errorCode", "CONNECTOR_PATH_NOT_FOUND");
						data.add(error);
						continue;
					}
					HashMap<String, Object> record = connectorPathRecord(connector, path);
					record.put("status", "ok");
					data.add(record);
					continue;
				}

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("connectorId", connector.getOrigID());
				ArrayList<Object> paths = new ArrayList<Object>();
				for (ConnectorRoad.ConnectorPath path : connector.getPaths()) {
					paths.add(connectorPathRecord(connector, path));
				}
				record.put("paths", paths);
				record.put("status", "ok");
				data.add(record);
			}
			response.put("data", data);
			response.put("status", "ok");
		} catch (Exception ex) {
			ContextCreator.logger.error("Error processing connectorPath query: "
					+ ex.toString(), ex);
			response.put("status", "error");
			response.put("errorCode", "INVALID_QUERY");
			response.put("message", ex.getMessage());
		}
		return response;
	}

	private ArrayList<ConnectorPathQuery> parseConnectorPathQueries(Object raw) {
		ArrayList<ConnectorPathQuery> result = new ArrayList<ConnectorPathQuery>();
		appendConnectorPathQueries(result, raw);
		return result;
	}

	private void appendConnectorPathQueries(ArrayList<ConnectorPathQuery> result, Object raw) {
		if (raw instanceof Collection<?>) {
			for (Object item : (Collection<?>) raw) appendConnectorPathQueries(result, item);
			return;
		}
		if (raw instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) raw;
			result.add(new ConnectorPathQuery(normalizedID(record.get("connectorId")),
					normalizedConnectorPathID(record.get("connectorPathId"))));
			return;
		}
		if (raw == null) {
			result.add(new ConnectorPathQuery(null, null));
			return;
		}
		String value = raw.toString().trim();
		if (value.startsWith("[") || value.startsWith("{")) {
			appendConnectorPathQueries(result, new Gson().fromJson(value, Object.class));
		} else {
			result.add(new ConnectorPathQuery(normalizedID(value), null));
		}
	}

	private String normalizedID(Object raw) {
		if (raw == null) return null;
		String value = String.valueOf(raw).trim();
		return value.isEmpty() ? null : value;
	}

	private Integer normalizedConnectorPathID(Object raw) {
		if (raw == null) return null;
		String value = String.valueOf(raw).trim();
		if (value.isEmpty()) return null;
		try {
			double numericValue = raw instanceof Number
					? ((Number) raw).doubleValue() : Double.parseDouble(value);
			if (!Double.isFinite(numericValue) || numericValue < 0.0
					|| numericValue != Math.rint(numericValue)
					|| numericValue > Integer.MAX_VALUE) {
				throw new NumberFormatException();
			}
			return Integer.valueOf((int) numericValue);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(
					"connectorPathId must be a non-negative integer", ex);
		}
	}

	private static final class ConnectorPathQuery {
		final String connectorId;
		final Integer connectorPathId;

		ConnectorPathQuery(String connectorId, Integer connectorPathId) {
			this.connectorId = connectorId;
			this.connectorPathId = connectorPathId;
		}
	}

	private HashMap<String, Object> connectorRoadRecord(ConnectorRoad connector) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("segmentId", connector.getOrigID());
		record.put("segmentType", "connector");
		record.put("vehicleCount", connector.getVehicleNum());
		record.put("speed", connector.calcSpeed());
		record.put("speedLimit", connector.getSpeedLimit());
		record.put("travelTime", connector.getTravelTime());
		record.put("travelTimeP90", connector.getTravelTimeP90());
		record.put("routingWeight", connector.getTravelTime());
		record.put("length", connector.getLength());
		record.put("downstreamIds", connector.getDownStreamRoadOrigIDs());
		record.put("controlMode", controlModeName(connector.getControlType()));
		record.put("configuredControlMode",
				controlModeName(connector.getConfiguredControlType()));
		record.put("laneIndex", null);
		record.put("sourceRoadId", connector.getSourceRoad().getOrigID());
		record.put("targetRoadId", connector.getTargetRoad().getOrigID());
		record.put("intersectionId", connector.getIntersectionID());
		record.put("internalEdgeIds", connector.getInternalEdgeIDs());
		record.put("aliases", new ArrayList<String>(connector.getAliases()));
		ArrayList<Object> paths = new ArrayList<Object>();
		for (ConnectorRoad.ConnectorPath path : connector.getPaths()) {
			paths.add(connectorPathRecord(connector, path));
		}
		record.put("paths", paths);
		record.put("conflictingConnectorIds",
				connectorOrigIDs(connector.getConflictingConnectorIDs()));
		RoadContext.IntersectionSnapshot snapshot =
				ContextCreator.getRoadContext()
						.getIntersectionSnapshot(connector.getIntersectionID());
		record.put("intersectionCollision", snapshot.hasCollision());
		record.put("intersectionStateVersion", snapshot.getVersion());
		record.put("status", "ok");
		return record;
	}

	private HashMap<String, Object> connectorPathRecord(ConnectorRoad connector,
			ConnectorRoad.ConnectorPath path) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("connectorId", connector.getOrigID());
		record.put("connectorPathId", path.getConnectorPathID());
		record.put("sourceLaneId", path.getSourceLane() == null
				? null : path.getSourceLane().getOrigID());
		record.put("targetLaneId", path.getTargetLane() == null
				? null : path.getTargetLane().getOrigID());
		record.put("sourceLaneIndex", path.getSourceLane() == null ? null
				: connector.getSourceRoad().getLaneIndex(path.getSourceLane()));
		record.put("targetLaneIndex", path.getTargetLane() == null ? null
				: connector.getTargetRoad().getLaneIndex(path.getTargetLane()));
		record.put("viaLaneIds", path.getViaLaneIDs());
		record.put("internalEdgeIds", path.getInternalEdgeIDs());
		record.put("explicitGeometry", path.hasExplicitGeometry());
		record.put("declaredLength", Double.isFinite(path.getDeclaredLength())
				? path.getDeclaredLength() : null);
		record.put("speed", Double.isFinite(path.getSpeed()) ? path.getSpeed() : null);
		record.put("direction", path.getDirection());
		record.put("state", path.getState());
		record.put("trafficLightId", path.getTrafficLightID());
		record.put("linkIndex", path.getLinkIndex());
		record.put("parameters", path.getParameters());
		return record;
	}

	private HashMap<String, Object> physicalRoadRecord(Road road) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("segmentId", road.getOrigID());
		record.put("segmentType", "road");
		record.put("visualizationIndex", getVisualizationRoadIndex(road));
		record.put("roadType", road.getRoadType());
		int vehicleCount = ContextCreator.getRoadContext().getQueryableVehicleCount(road);
		record.put("vehicleCount", vehicleCount);
		record.put("controlMode", controlModeName(road.getControlType()));
		record.put("speed", road.calcSpeed());
		addRoutingMetricFields(record, road);
		record.put("flow", road.getTotalFlow());
		record.put("energyConsumed", road.getTotalEnergy());
		record.put("parkingCapacity", road.getParkingCapacity());
		record.put("parkedVehicleCount", road.getParkedNum());
		record.put("downstreamIds", road.getDownStreamRoadOrigIDs());
		record.put("status", "ok");
		return record;
	}

	private void addSegmentIdLists(Map<String, Object> response, Collection<String> segmentIds) {
		ArrayList<String> roadIds = new ArrayList<String>();
		ArrayList<String> connectorIds = new ArrayList<String>();
		if (segmentIds != null) {
			for (String segmentId : segmentIds) {
				if (ContextCreator.getRoadContext().getConnector(segmentId) == null) {
					roadIds.add(segmentId);
				} else {
					connectorIds.add(segmentId);
				}
			}
		}
		response.put("roadIds", roadIds);
		response.put("connectorIds", connectorIds);
	}

	private HashMap<String, Object> errorRecord(String idField, Object id) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		if (idField != null) record.put(idField, id);
		record.put("status", "error");
		return record;
	}

	private String controlModeName(int controlType) {
		return controlType == Road.COSIM ? "cosim" : "native";
	}

	private ArrayList<String> connectorOrigIDs(Collection<Integer> connectorInternalIDs) {
		ArrayList<String> result = new ArrayList<String>();
		if (connectorInternalIDs == null) return result;
		for (Integer connectorID : connectorInternalIDs) {
			ConnectorRoad connector = connectorID == null ? null
					: ContextCreator.getRoadContext().getConnector(connectorID.intValue());
			if (connector != null) result.add(connector.getOrigID());
		}
		Collections.sort(result);
		return result;
	}

	private Road findQueryableRoad(String origID) {
		if (origID == null) return null;
		Road road = ContextCreator.getCityContext().findRoadWithOrigID(origID);
		if (road != null) return road;
		return ContextCreator.getRoadContext() == null ? null
				: ContextCreator.getRoadContext().getConnector(origID);
	}

	public HashMap<String, Object> getRoutingTopology(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			RoadContext roadContext = ContextCreator.getRoadContext();
			if (roadContext == null) {
				throw new IllegalStateException("Road context is not initialized");
			}
			List<RoutingTopologyEntry> roads = routingTopologySnapshot(roadContext);

			/*
			 * Topology and metric versions are maintained at mutation sites; paging no
			 * longer recomputes a whole-network fingerprint.
			 */
			int offset = Math.min(nonNegativeInt(jsonMsg.get("offset"), 0), roads.size());
			int requestedLimit = nonNegativeInt(jsonMsg.get("limit"), roads.size());
			int end = (int) Math.min((long) roads.size(), (long) offset + requestedLimit);
			boolean includeCenter = Boolean.TRUE.equals(jsonMsg.get("includeCenter"));
			boolean compact = Boolean.TRUE.equals(jsonMsg.get("compact"));
			ArrayList<Object> data = new ArrayList<Object>(Math.max(0, end - offset));
			for (int i = offset; i < end; i++) {
				RoutingTopologyEntry road = roads.get(i);
				if (compact) {
					ArrayList<Object> record = new ArrayList<Object>();
					record.add(road.roadID);
					record.add(road.downstreamRoadIDs);
					record.add(road.length);
					if (includeCenter) {
						record.add(road.centerX);
						record.add(road.centerY);
					}
					data.add(record);
				} else {
					HashMap<String, Object> record = new HashMap<String, Object>();
					record.put("roadId", road.roadID);
					record.put("downstreamRoadId", road.downstreamRoadIDs);
					record.put("length", road.length);
					if (includeCenter && road.centerX != null && road.centerY != null) {
						record.put("centerX", road.centerX);
						record.put("centerY", road.centerY);
					}
					data.add(record);
				}
			}
			jsonObj.put("data", data);
			jsonObj.put("offset", offset);
			jsonObj.put("count", data.size());
			jsonObj.put("total", roads.size());
			jsonObj.put("hasMore", end < roads.size());
			jsonObj.put("topologyVersion", roadContext.getPhysicalTopologyVersion());
			jsonObj.put("metricVersion", roadContext.getRoutingMetricVersion());
			jsonObj.put("tick", ContextCreator.getCurrentTick());
			jsonObj.put("compact", compact);
			if (compact) {
				jsonObj.put("schema", includeCenter
						? new String[] { "roadId", "downstreamRoadId", "length", "centerX", "centerY" }
						: new String[] { "roadId", "downstreamRoadId", "length" });
			}
			jsonObj.put("status", "ok");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing getRoutingTopology: " + e.toString());
			jsonObj.put("status", "error");
		}
		return jsonObj;
	}

	private List<RoutingTopologyEntry> routingTopologySnapshot(RoadContext roadContext) {
		long requestedVersion = roadContext.getPhysicalTopologyVersion();
		if (cachedRoutingTopologyContext == roadContext
				&& cachedRoutingTopologyVersion == requestedVersion) {
			return cachedRoutingTopology;
		}
		synchronized (ROUTING_TOPOLOGY_CACHE_LOCK) {
			requestedVersion = roadContext.getPhysicalTopologyVersion();
			if (cachedRoutingTopologyContext == roadContext
					&& cachedRoutingTopologyVersion == requestedVersion) {
				return cachedRoutingTopology;
			}
			ArrayList<Road> orderedRoads = new ArrayList<Road>(roadContext.getAll());
			orderedRoads.sort((left, right) -> String.valueOf(left.getOrigID())
					.compareTo(String.valueOf(right.getOrigID())));
			ArrayList<RoutingTopologyEntry> rebuilt =
					new ArrayList<RoutingTopologyEntry>(orderedRoads.size());
			for (Road road : orderedRoads) {
				if (road == null || road.getOrigID() == null) continue;
				Coordinate center = roadCenter(road);
				rebuilt.add(new RoutingTopologyEntry(road.getOrigID(),
						road.getDownStreamRoadOrigIDs(), road.getLength(),
						center == null ? null : Double.valueOf(center.x),
						center == null ? null : Double.valueOf(center.y)));
			}
			cachedRoutingTopologyContext = roadContext;
			cachedRoutingTopologyVersion = requestedVersion;
			cachedRoutingTopology = Collections.unmodifiableList(rebuilt);
			return cachedRoutingTopology;
		}
	}

	private int nonNegativeInt(Object value, int defaultValue) {
		if (value == null) return Math.max(0, defaultValue);
		try { return Math.max(0, Integer.parseInt(String.valueOf(value))); }
		catch (NumberFormatException e) { return Math.max(0, defaultValue); }
	}

	private Coordinate roadCenter(Road road) {
		ArrayList<Coordinate> coordinates = road.getCoords();
		if (coordinates == null || coordinates.isEmpty()) return null;
		Coordinate first = coordinates.get(0);
		Coordinate last = coordinates.get(coordinates.size() - 1);
		return new Coordinate((first.x + last.x) / 2.0, (first.y + last.y) / 2.0);
	}

	private static final class RoutingTopologyEntry {
		final String roadID;
		final List<String> downstreamRoadIDs;
		final double length;
		final Double centerX;
		final Double centerY;

		RoutingTopologyEntry(String roadID, Collection<String> downstreamRoadIDs,
				double length, Double centerX, Double centerY) {
			this.roadID = roadID;
			this.downstreamRoadIDs = Collections.unmodifiableList(
					new ArrayList<String>(downstreamRoadIDs));
			this.length = length;
			this.centerX = centerX;
			this.centerY = centerY;
		}
	}
	/**
	* Return road-level routing metric updates since the last full road snapshot
	* (loaded through {@link #getRoad}) or the last update query.
	*
	* <p>The Python client builds the full NetworkX graph from {@code road}; this
	* endpoint keeps a per-connection baseline and only emits changed road metric
	* records after that. Topology changes require a fresh full snapshot because
	* callers may need to rebuild downstream edges.
	*/
	public synchronized HashMap<String, Object> getRoutingGraphUpdates(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			RoadContext roadContext = ContextCreator.getRoadContext();
			if (roadContext == null) {
				jsonObj.put("message", "Road context is not initialized");
				jsonObj.put("status", "error");
				return jsonObj;
			}

			if (routingGraphRoadStates == null || routingGraphRoadContext != roadContext
					|| routingGraphBaselineTopologyVersion
							!= roadContext.getPhysicalTopologyVersion()
					|| routingGraphRoadStates.size() != roadContext.getAll().size()) {
				jsonObj.put("data", new ArrayList<Object>());
				jsonObj.put("removed", new ArrayList<String>());
				jsonObj.put("status", "ok");
				addRoutingGraphMetadata(jsonObj, true);
				return jsonObj;
			}

			RoadContext.RoutingMetricRefreshSnapshot refresh =
					roadContext.getRoutingMetricRefreshSnapshot(routingGraphMetricCursor);
			if (refresh.snapshotRequired) {
				jsonObj.put("data", new ArrayList<Object>());
				jsonObj.put("removed", new ArrayList<String>());
				jsonObj.put("status", "ok");
				addRoutingGraphMetadata(jsonObj, true);
				return jsonObj;
			}
			ArrayList<Object> jsonData = new ArrayList<Object>();
			ArrayList<String> removedRoads = new ArrayList<String>();
			boolean topologyChanged = false;

			for (Road road : refresh.roads) {
				if (road == null || road.getOrigID() == null) {
					continue;
				}
				RoutingGraphRoadState current = routingGraphRoadState(road);
				RoutingGraphRoadState previous = routingGraphRoadStates.get(current.roadId);
				if (previous == null) {
					topologyChanged = true;
					continue;
				}
				if (!current.sameTopology(previous)) {
					topologyChanged = true;
					continue;
				}
				if (!current.sameRoutingMetrics(previous)) {
					jsonData.add(routingGraphUpdateRecord(road, current));
				}
				routingGraphRoadStates.put(current.roadId, current);
			}

			if (topologyChanged) {
				routingGraphTopologyVersion =
						roadContext.getPhysicalTopologyVersion();
				jsonObj.put("data", new ArrayList<Object>());
				jsonObj.put("removed", removedRoads);
				jsonObj.put("status", "ok");
				addRoutingGraphMetadata(jsonObj, true);
				return jsonObj;
			}

			routingGraphMetricCursor = refresh.throughVersion;
			routingGraphMetricVersion = refresh.throughVersion;
			routingGraphRoadContext = roadContext;
			jsonObj.put("data", jsonData);
			jsonObj.put("removed", removedRoads);
			jsonObj.put("status", "ok");
			addRoutingGraphMetadata(jsonObj, false);
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getRoutingGraphUpdates: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	private synchronized void beginRoutingGraphSnapshotBaseline() {
		RoadContext roadContext = ContextCreator.getRoadContext();
		if (roadContext == null) {
			routingGraphRoadStates = null;
			routingGraphRoadContext = null;
			routingGraphMetricCursor = 0L;
			routingGraphBaselineTopologyVersion = 0L;
			return;
		}
		routingGraphRoadStates = new HashMap<String, RoutingGraphRoadState>();
		routingGraphRoadContext = roadContext;
		routingGraphMetricCursor = roadContext.enableRoutingMetricTracking();
		routingGraphMetricVersion = routingGraphMetricCursor;
		routingGraphBaselineTopologyVersion = roadContext.getPhysicalTopologyVersion();
		routingGraphTopologyVersion = routingGraphBaselineTopologyVersion;
	}

	private synchronized void rememberRoutingGraphRoadSnapshot(Road road) {
		if (road == null || road.getOrigID() == null || ContextCreator.getRoadContext() == null) {
			return;
		}
		if (routingGraphRoadStates == null || routingGraphRoadContext != ContextCreator.getRoadContext()) {
			beginRoutingGraphSnapshotBaseline();
		}
		routingGraphRoadStates.put(road.getOrigID(), routingGraphRoadState(road));
	}

	private void addRoutingGraphMetadata(HashMap<String, Object> jsonObj, boolean snapshotRequired) {
		int tick = ContextCreator.getCurrentTick();
		jsonObj.put("tick", tick);
		jsonObj.put("topologyVersion", routingGraphTopologyVersion);
		jsonObj.put("version", routingGraphMetricVersion);
		jsonObj.put("snapshotRequired", snapshotRequired);
	}

	private HashMap<String, Object> routingGraphUpdateRecord(Road road, RoutingGraphRoadState state) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("segmentId", state.roadId);
		record.put("roadId", state.roadId);
		record.put("visualizationIndex", getVisualizationRoadIndex(road));
		record.put("roadType", road.getRoadType());
		record.put("vehicleCount", road.getVehicleNum());
		record.put("parkedVehicleCount", road.getParkedNum());
		addRoutingMetricFields(record, state);
		record.put("status", "ok");
		return record;
	}

	private void addRoutingMetricFields(HashMap<String, Object> record, Road road) {
		addRoutingMetricFields(record, routingGraphRoadState(road));
	}

	private void addTravelTimeEstimatorFields(HashMap<String, Object> record, Road road) {
		record.put("travelTimeP90", road.getTravelTimeP90());
		record.put("travelTimeConfidence", road.getTravelTimeConfidence());
		record.put("travelTimeEffectiveSampleCount", road.getTravelTimeEffectiveSampleCount());
		record.put("travelTimeSampleAgeSeconds", road.getTravelTimeSampleAgeSeconds());
		record.put("travelTimeLiveVehicleCount", road.getTravelTimeLiveVehicleCount());
		record.put("travelTimeStoppedFraction", road.getTravelTimeStoppedFraction());
		record.put("travelTimeLiveLowerBound", road.getTravelTimeLiveLowerBound());
		record.put("travelTimeLiveMeanSpeed", road.getTravelTimeLiveMeanSpeed());
		record.put("travelTimeEstimateSource", road.getTravelTimeEstimateSource());
	}

	private void addRoutingMetricFields(HashMap<String, Object> record, RoutingGraphRoadState state) {
		record.put("speedLimit", state.speedLimit);
		record.put("travelTime", state.travelTime);
		record.put("travelTimeP90", state.travelTimeP90);
		record.put("routingWeight", state.routingWeight);
		record.put("length", state.distance);
		record.put("energyConsumed", state.energyConsumed);
		record.put("averageEnergyConsumption", state.avgEnergyConsumption);
		record.put("travelTimeConfidence", state.travelTimeConfidence);
		record.put("travelTimeEffectiveSampleCount", state.effectiveSampleCount);
		record.put("travelTimeSampleAgeSeconds", state.sampleAgeSeconds);
		record.put("travelTimeLiveVehicleCount", state.liveVehicleCount);
		record.put("travelTimeStoppedFraction", state.stoppedFraction);
		record.put("travelTimeLiveLowerBound", state.liveLowerBound);
		record.put("travelTimeLiveMeanSpeed", state.liveMeanSpeed);
		record.put("travelTimeEstimateSource", state.estimateSource);
	}

	private RoutingGraphRoadState routingGraphRoadState(Road road) {
		double distance = finiteDouble(road.getLength(), 0.0);
		double travelTime = finiteDouble(road.getTravelTime(), 0.0);
		double speedLimit = finiteDouble(road.getSpeedLimit(), 0.0);
		if (travelTime <= 0.0 && distance > 0.0 && speedLimit > 0.0) {
			travelTime = distance / speedLimit;
		}
		double routingWeight = routingWeightForRoad(road, travelTime);
		ArrayList<String> downstreamRoads = new ArrayList<String>();
		try {
			ArrayList<String> downstream = road.getDownStreamRoadOrigIDs();
			if (downstream != null) {
				downstreamRoads.addAll(downstream);
			}
		}
		catch (Exception e) {
			// Missing downstream references are handled as a topology change by callers.
		}
		return new RoutingGraphRoadState(road.getOrigID(), distance, travelTime,
				finiteDouble(road.getTravelTimeP90(), travelTime), routingWeight,
				finiteDouble(road.getTotalEnergy(), 0.0),
				finiteDouble(road.getAvgEnergyConsumption(), 0.0), speedLimit,
				finiteDouble(road.getTravelTimeConfidence(), 0.0),
				finiteDouble(road.getTravelTimeEffectiveSampleCount(), 0.0),
				finiteDouble(road.getTravelTimeSampleAgeSeconds(), -1.0),
				road.getTravelTimeLiveVehicleCount(),
				finiteDouble(road.getTravelTimeStoppedFraction(), 0.0),
				finiteDouble(road.getTravelTimeLiveLowerBound(), 0.0),
				finiteDouble(road.getTravelTimeLiveMeanSpeed(), 0.0),
				road.getTravelTimeEstimateSource(), downstreamRoads);
	}

	private double finiteDouble(double value, double fallback) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return fallback;
		}
		return value;
	}

	private double routingWeightForRoad(Road road, double fallback) {
		if (road == null || ContextCreator.getRoadNetwork() == null) {
			return fallback;
		}
		Node node1 = road.getUpStreamNode();
		Node node2 = road.getDownStreamNode();
		if (node1 == null || node2 == null) {
			return fallback;
		}
		RepastEdge<Node> edge = ContextCreator.getRoadNetwork().getEdge(node1, node2);
		return edge == null ? fallback : finiteDouble(edge.getWeight(), fallback);
	}

	private class RoutingGraphRoadState {
		final String roadId;
		final double distance;
		final double travelTime;
		final double travelTimeP90;
		final double routingWeight;
		final double energyConsumed;
		final double avgEnergyConsumption;
		final double speedLimit;
		final double travelTimeConfidence;
		final double effectiveSampleCount;
		final double sampleAgeSeconds;
		final int liveVehicleCount;
		final double stoppedFraction;
		final double liveLowerBound;
		final double liveMeanSpeed;
		final String estimateSource;
		final ArrayList<String> downstreamRoads;

		RoutingGraphRoadState(String roadId, double distance, double travelTime,
				double travelTimeP90, double routingWeight, double energyConsumed,
				double avgEnergyConsumption,
				double speedLimit, double travelTimeConfidence, double effectiveSampleCount,
				double sampleAgeSeconds, int liveVehicleCount, double stoppedFraction,
				double liveLowerBound, double liveMeanSpeed, String estimateSource,
				ArrayList<String> downstreamRoads) {
			this.roadId = roadId;
			this.distance = distance;
			this.travelTime = travelTime;
			this.travelTimeP90 = travelTimeP90;
			this.routingWeight = routingWeight;
			this.energyConsumed = energyConsumed;
			this.avgEnergyConsumption = avgEnergyConsumption;
			this.speedLimit = speedLimit;
			this.travelTimeConfidence = travelTimeConfidence;
			this.effectiveSampleCount = effectiveSampleCount;
			this.sampleAgeSeconds = sampleAgeSeconds;
			this.liveVehicleCount = liveVehicleCount;
			this.stoppedFraction = stoppedFraction;
			this.liveLowerBound = liveLowerBound;
			this.liveMeanSpeed = liveMeanSpeed;
			this.estimateSource = estimateSource;
			this.downstreamRoads = downstreamRoads == null ? new ArrayList<String>() : downstreamRoads;
		}

		boolean sameRoutingMetrics(RoutingGraphRoadState other) {
			return sameDouble(this.distance, other.distance)
					&& sameDouble(this.travelTime, other.travelTime)
					&& sameDouble(this.travelTimeP90, other.travelTimeP90)
					&& sameDouble(this.routingWeight, other.routingWeight)
					&& sameDouble(this.energyConsumed, other.energyConsumed)
					&& sameDouble(this.avgEnergyConsumption, other.avgEnergyConsumption)
					&& sameDouble(this.speedLimit, other.speedLimit)
					&& sameDouble(this.travelTimeConfidence, other.travelTimeConfidence)
					&& sameDouble(this.effectiveSampleCount, other.effectiveSampleCount)
					&& this.liveVehicleCount == other.liveVehicleCount
					&& sameDouble(this.stoppedFraction, other.stoppedFraction)
					&& sameDouble(this.liveLowerBound, other.liveLowerBound)
					&& sameDouble(this.liveMeanSpeed, other.liveMeanSpeed)
					&& (this.estimateSource == null ? other.estimateSource == null
							: this.estimateSource.equals(other.estimateSource));
		}

		boolean sameTopology(RoutingGraphRoadState other) {
			if (other == null || this.downstreamRoads.size() != other.downstreamRoads.size()) {
				return false;
			}
			for (int i = 0; i < this.downstreamRoads.size(); i++) {
				String current = this.downstreamRoads.get(i);
				String previous = other.downstreamRoads.get(i);
				if (current == null ? previous != null : !current.equals(previous)) {
					return false;
				}
			}
			return true;
		}

		private boolean sameDouble(double left, double right) {
			return Math.abs(left - right) <= ROUTING_GRAPH_EPSILON;
		}
	}

	private synchronized int getVisualizationRoadIndex(Road road) {
		if (road == null || road.getOrigID() == null || ContextCreator.getRoadContext() == null) {
			return -1;
		}
		int roadCount = ContextCreator.getRoadContext().getAll().size();
		if (this.roadIndexByOrigIDCache == null || this.roadIndexCacheRoadContext != ContextCreator.getRoadContext()
				|| this.roadIndexCacheRoadCount != roadCount
				|| !this.roadIndexByOrigIDCache.containsKey(road.getOrigID())) {
			rebuildVisualizationRoadIndexCache(roadCount);
		}
		Integer index = this.roadIndexByOrigIDCache.get(road.getOrigID());
		return index == null ? -1 : index.intValue();
	}

	private void rebuildVisualizationRoadIndexCache(int roadCount) {
		ArrayList<String> roadIds = new ArrayList<String>();
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			if (road == null || road.getOrigID() == null) {
				continue;
			}
			roadIds.add(road.getOrigID());
		}
		Collections.sort(roadIds);
		HashMap<String, Integer> roadIndexByOrigID = new HashMap<String, Integer>();
		for (int i = 0; i < roadIds.size(); i++) {
			roadIndexByOrigID.put(roadIds.get(i), i);
		}
		this.roadIndexByOrigIDCache = roadIndexByOrigID;
		this.roadIndexCacheRoadCount = roadCount;
		this.roadIndexCacheRoadContext = ContextCreator.getRoadContext();
	}

	/**
	* Query vehicles waiting to enter one or more roads from the road departure
	* queue. For co-simulation roads, this is the queue that is intentionally
	* held until the external simulator releases the head vehicle.
	*
	* <p>Input DATA: list of original road IDs, or records carrying
	* {@code roadId}/{@code ID}/{@code origID}. If omitted, all road IDs are
	* returned.
	*/
	public HashMap<String, Object> getEnteringVehicleQueue(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			addSegmentIdLists(jsonObj, ContextCreator.getRoadContext().getQueryableOrigIDList());
			jsonObj.put("status", "ok");
			return jsonObj;
		}
		try {
			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (String roadId : parseRoadIDs(jsonMsg.get("data"))) {
				Road road = findQueryableRoad(roadId);
				if (road != null) {
					jsonData.add(roadEnteringQueueRecord(road));
				} else {
					HashMap<String, Object> record = new HashMap<String, Object>();
					record.put("segmentId", roadId);
					record.put("status", "error");
					jsonData.add(record);
				}
			}
			jsonObj.put("data", jsonData);
			jsonObj.put("status", "ok");
			return jsonObj;
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	private ArrayList<Integer> requestIDs(Queue<Request> requests) {
		ArrayList<Integer> ids = new ArrayList<Integer>();
		if (requests != null) {
			for (Request request : requests) {
				if (request != null) {
					ids.add(request.getID());
				}
			}
		}
		return ids;
	}

	/**
	* Convenience query for every co-simulation road's entering queue.
	*/
	public HashMap<String, Object> getCoSimEnteringVehicleQueue(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (Road road : ContextCreator.coSimRoads.values()) {
				jsonData.add(roadEnteringQueueRecord(road));
			}
			jsonObj.put("data", jsonData);
			jsonObj.put("status", "ok");
			return jsonObj;
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	private HashMap<String, Object> roadEnteringQueueRecord(Road road) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		ArrayList<Integer> ids = new ArrayList<Integer>();
		ArrayList<Object> queue = new ArrayList<Object>();
		int tick = ContextCreator.getCurrentTick();
		for (Vehicle vehicle : road.getEnteringVehicleQueueSnapshot()) {
			int visibleID = bridgeVehicleID(vehicle);
			ids.add(visibleID);
			HashMap<String, Object> vehicleRecord = new HashMap<String, Object>();
			vehicleRecord.put("vehicleId", visibleID);
			vehicleRecord.put("internalVehicleId", vehicle.getID());
			vehicleRecord.put("isPrivate", bridgeVehicleType(vehicle));
			vehicleRecord.put("vehicleClass", vehicle.getVehicleClass());
			vehicleRecord.put("departureTick", vehicle.getDepTime());
			vehicleRecord.put("ready", tick >= vehicle.getDepTime());
			queue.add(vehicleRecord);
		}
		record.put("segmentId", road.getOrigID());
		record.put("controlMode", controlModeName(road.getControlType()));
		record.put("segmentType", road instanceof ConnectorRoad ? "connector" : "road");
		if (road instanceof ConnectorRoad) {
			record.put("laneIndex", null);
		}
		record.put("enteringVehicleIds", ids);
		record.put("queue", queue);
		record.put("status", "ok");
		return record;
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

	private ArrayList<String> parseRoadIDs(Object data) {
		ArrayList<String> roadIDs = new ArrayList<String>();
		if (data instanceof Map<?, ?>) {
			String roadId = roadIDFromEntry(data);
			if (roadId != null && !roadId.isEmpty()) roadIDs.add(roadId);
		} else if (data instanceof Collection<?>) {
			for (Object entry : (Collection<?>) data) {
				String roadId = roadIDFromEntry(entry);
				if (roadId != null && !roadId.isEmpty()) roadIDs.add(roadId);
			}
		} else if (data != null) {
			String value = data.toString();
			if (value.startsWith("[")) {
				Gson gson = new Gson();
				TypeToken<Collection<Object>> collectionType = new TypeToken<Collection<Object>>() {};
				Collection<Object> parsed = gson.fromJson(value, collectionType.getType());
				if (parsed != null) {
					for (Object entry : parsed) {
						String roadId = roadIDFromEntry(entry);
						if (roadId != null && !roadId.isEmpty()) roadIDs.add(roadId);
					}
				}
			} else if (value.startsWith("{")) {
				Gson gson = new Gson();
				Map<?, ?> parsed = gson.fromJson(value, Map.class);
				String roadId = roadIDFromEntry(parsed);
				if (roadId != null && !roadId.isEmpty()) roadIDs.add(roadId);
			} else if (!value.isEmpty()) {
				roadIDs.add(value);
			}
		}
		return roadIDs;
	}

	private String roadIDFromEntry(Object entry) {
		if (entry == null) return null;
		if (entry instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) entry;
			Object value = record.get("segmentId");
			return value == null ? null : String.valueOf(value);
		}
		return String.valueOf(entry);
	}

	private ArrayList<IDTransformQuery> parseIDTransformQueries(Object data, String idKey) {
		ArrayList<IDTransformQuery> requests = new ArrayList<IDTransformQuery>();
		appendIDTransformQueries(requests, data, false, idKey);
		return requests;
	}

	private void appendIDTransformQueries(ArrayList<IDTransformQuery> requests, Object entry,
			boolean defaultTransformCoord, String idKey) {
		if (entry instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) entry;
			Boolean transform = parseBoolean(record.get("transformCoordinates"));
			boolean transformCoordinates = transform == null ? defaultTransformCoord : transform.booleanValue();
			Object idValue = record.get(idKey);
			Object idsValue = record.get(idKey + "s");
			if (idValue != null) {
				requests.add(new IDTransformQuery(parseInteger(idValue), transformCoordinates));
				return;
			}
			if (idsValue != null) {
				appendIDTransformQueries(requests, idsValue, transformCoordinates, idKey);
				return;
			}
			requests.add(new IDTransformQuery(null, transformCoordinates));
			return;
		}
		if (entry instanceof Iterable<?>) {
			for (Object value : (Iterable<?>) entry) {
				appendIDTransformQueries(requests, value, defaultTransformCoord, idKey);
			}
			return;
		}
		requests.add(new IDTransformQuery(parseInteger(entry), defaultTransformCoord));
	}

	private static class IDTransformQuery {
		Integer id;
		boolean transformCoordinates;

		IDTransformQuery(Integer id, boolean transformCoordinates) {
			this.id = id;
			this.transformCoordinates = transformCoordinates;
		}
	}

	/**
	* Fetch the polyline of a road or one of its lanes.
	*
	* <p>Input DATA: list of {@code {roadId, laneIndex, transformCoordinates}}.
	* Use {@code laneIndex = -1} for the road's start/end coordinates;
	* otherwise the coordinates of the specified lane are returned. If
	* {@code transformCoordinates} is {@code true} the coordinates are
	* back-transformed into the network file's source CRS.
	*
	* <p>Output DATA: list of {@code {ID, centerline}} records where
	* {@code centerline} is an array of {@code [x, y, z]} points.
	*/
	public HashMap<String, Object> getCenterLine(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			addSegmentIdLists(jsonObj, ContextCreator.getRoadContext().getQueryableOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<RoadIDLaneIndexTransform>> collectionType = new TypeToken<Collection<RoadIDLaneIndexTransform>>() {};
		Collection<RoadIDLaneIndexTransform> roadIDLaneIndexTransforms = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(RoadIDLaneIndexTransform roadIDLaneIndexTransform: roadIDLaneIndexTransforms) {
			Road road = findQueryableRoad(roadIDLaneIndexTransform.segmentId);
				if (road != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("segmentId", road.getOrigID());
					ArrayList<ArrayList<Double>> res = new ArrayList<ArrayList<Double>>();
					int laneIndex = roadIDLaneIndexTransform.laneIndex;
					boolean transformCoordinates = roadIDLaneIndexTransform.transformCoordinates;
					if (road instanceof ConnectorRoad) {
						ConnectorRoad connector = (ConnectorRoad) road;
						record2.put("segmentType", "connector");
						record2.put("laneIndex", null);
						res = coordinatesForQuery(
								connector.getRepresentativeCenterLine(), transformCoordinates);
						ArrayList<Object> allCenterLines = new ArrayList<Object>();
						for (List<Coordinate> centerLine : connector.getCenterLines()) {
							allCenterLines.add(coordinatesForQuery(
									centerLine, transformCoordinates));
						}
						record2.put("centerlines", allCenterLines);
					}
					else if(laneIndex < 0) {
						record2.put("segmentType", "road");
						Coordinate startCoord = road.getStartCoord();
						Coordinate endCoord = road.getEndCoord();
						if(transformCoordinates) {
							try {
								JTS.transform(startCoord, startCoord,
										SumoXML.getData(GlobalVariables.NETWORK_FILE).transform.inverse());
								JTS.transform(endCoord, endCoord,
										SumoXML.getData(GlobalVariables.NETWORK_FILE).transform.inverse());
							} catch (TransformException e) {
								ContextCreator.logger
										.error("Coordinates transformation failed, start x: " + startCoord.x + " y:" + startCoord.y);
								e.printStackTrace();
							}
						}
						ArrayList<Double> startXYZ = new ArrayList<Double>();
						startXYZ.add(startCoord.x);
						startXYZ.add(startCoord.y);
						startXYZ.add(startCoord.z);
						res.add(startXYZ);
						ArrayList<Double> endXYZ = new ArrayList<Double>();
						endXYZ.add(endCoord.x);
						endXYZ.add(endCoord.y);
						endXYZ.add(endCoord.z);
						res.add(endXYZ);
					}
					else if(laneIndex < road.getLanes().size()) {
						record2.put("segmentType", "road");
						record2.put("laneIndex", laneIndex);
						if(transformCoordinates) {
							for(Coordinate coord: road.getLane(laneIndex).getCoords()) {
								if(coord != null) {
									Coordinate coord2 = new Coordinate();
									coord2.x = coord.x;
									coord2.y = coord.y;
									coord2.z = coord.z;
									ArrayList<Double> xyz = new ArrayList<Double>();
									try {
										JTS.transform(coord2, coord2,
												SumoXML.getData(GlobalVariables.NETWORK_FILE).transform.inverse());
										xyz.add(coord2.x);
										xyz.add(coord2.y);
										xyz.add(coord2.z);
									} catch (TransformException e) {
										ContextCreator.logger
												.error("Coordinates transformation failed, input x: " + coord.x + " y:" + coord.y);
										e.printStackTrace();
									}
									res.add(xyz);
								}
							}
						}
						else {
							for(Coordinate coord: road.getLane(laneIndex).getCoords()) {
								ArrayList<Double> xyz = new ArrayList<Double>();
								xyz.add(coord.x);
								xyz.add(coord.y);
								xyz.add(coord.z);
								res.add(xyz);
							}
						}
					}
					record2.put("centerline", res);
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("segmentId", roadIDLaneIndexTransform.segmentId));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	private ArrayList<ArrayList<Double>> coordinatesForQuery(
			List<Coordinate> coordinates, boolean transformCoordinates) {
		ArrayList<ArrayList<Double>> result = new ArrayList<ArrayList<Double>>();
		if (coordinates == null) return result;
		for (Coordinate coordinate : coordinates) {
			if (coordinate == null) continue;
			Coordinate output = coordinateForQuery(coordinate, transformCoordinates);
			if (output == null) continue;
			ArrayList<Double> xyz = new ArrayList<Double>();
			xyz.add(output.x);
			xyz.add(output.y);
			xyz.add(output.z);
			result.add(xyz);
		}
		return result;
	}

	// =============================================================
	// ZONES
	// =============================================================

	/**
	* Fetch live state for one or more zones.
	*
	* <p>Input DATA (optional): list of integer zone IDs, records carrying
	* {@code ID}/{@code id}/{@code zoneId} plus optional
	* {@code transformCoordinates}, or an object carrying {@code ids} plus optional
	* {@code transformCoordinates}. If omitted, returns the {@code id_list} of all zones.
	*
	* <p>Output DATA: list of {@code {ID, z_type, taxi_demand, bus_demand,
	* veh_stock, x, y, z, leftTaxiRequests, leftTaxiPassengers,
	* leftBusRequests, leftBusPassengers}} records. The {@code left*} fields
	* are cumulative since simulation start at that zone: requests that
	* abandoned the taxi or bus queue after exceeding maximum wait time, and
	* passenger totals (sum of {@link Request#getNumPeople()} per abandoned
	* request). With {@code transformCoordinates = true}, {@code x/y/z} are returned in
	* the same transformed coordinate frame used by vehicle queries.
	*/
	public HashMap<String, Object> getZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("zoneIds", ContextCreator.getZoneContext().getIDList());
			return jsonObj;
		}
		try {
			ArrayList<IDTransformQuery> requests = parseIDTransformQueries(jsonMsg.get("data"), "zoneId");
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(IDTransformQuery request: requests) {
				Zone zone = request.id == null ? null : ContextCreator.getZoneContext().get(request.id.intValue());
				if (zone != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("zoneId", zone.getID());
					record2.put("zoneType", zone.getZoneType());
					record2.put("capacity", zone.getCapacity());
					record2.put("taxiDemand", zone.getTaxiRequestNum());
					record2.put("busDemand", zone.getBusRequestNum());
					record2.put("vehicleStock", zone.getVehicleStock());
					Coordinate coord = coordinateForQuery(zone.getCoord(), request.transformCoordinates);
					if (coord != null) {
						record2.put("x", coord.x);
						record2.put("y", coord.y);
						record2.put("z", coord.z);
					}
					record2.put("generatedTaxi", zone.numberOfGeneratedTaxiRequest);
					record2.put("generatedBus", zone.numberOfGeneratedBusRequest);
					record2.put("generatedPrivateEV", zone.numberOfGeneratedPrivateEVTrip);
					record2.put("generatedPrivateGV", zone.numberOfGeneratedPrivateGVTrip);
					record2.put("arrivedPrivateEV", zone.arrivedPrivateEVTrip);
					record2.put("arrivedPrivateGV", zone.arrivedPrivateGVTrip);
					record2.put("matchedTaxiRequests", zone.taxiPickupRequest);
					record2.put("matchedTaxiPassengers", zone.taxiPickupPassengers);
					record2.put("matchedBusRequests", zone.busPickupRequest);
					record2.put("matchedBusPassengers", zone.busPickupPassengers);
					record2.put("pickupTaxiRequests", zone.taxiPickedUpRequest);
					record2.put("pickupTaxiPassengers", zone.taxiPickedUpPassengers);
					record2.put("pickupBusRequests", zone.busPickedUpRequest);
					record2.put("pickupBusPassengers", zone.busPickedUpPassengers);
					record2.put("dropoffTaxiRequests", zone.taxiServedRequest);
					record2.put("dropoffTaxiPassengers", zone.taxiServedPassengers);
					record2.put("dropoffBusRequests", zone.busServedRequest);
					record2.put("dropoffBusPassengers", zone.busServedPassengers);
					record2.put("leftTaxiRequests", zone.numberOfLeavedTaxiRequest);
					record2.put("leftTaxiPassengers", zone.numberOfLeavedTaxiPassengers);
					record2.put("leftBusRequests", zone.numberOfLeavedBusRequest);
					record2.put("leftBusPassengers", zone.numberOfLeavedBusPassengers);
					record2.put("relocatedVehicles", zone.numberOfRelocatedVehicles);
					record2.put("futureSupply", zone.getFutureSupply());
					record2.put("futureDemand", zone.getFutureDemand());
					record2.put("vehicleSurplus", zone.getVehicleSurplus());
					record2.put("vehicleDeficiency", zone.getVehicleDeficiency());
					record2.put("taxiDropoffWait", zone.taxiServedPassWaitingTime);
					record2.put("busDropoffWait", zone.busServedPassWaitingTime);
					record2.put("taxiLeftWait", zone.taxiLeavedPassWaitingTime);
					record2.put("busLeftWait", zone.busLeavedPassWaitingTime);
					record2.put("taxiParkingTime", zone.taxiParkingTime);
					record2.put("taxiCruisingTime", zone.taxiCruisingTime);
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("zoneId", request.id));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	// =============================================================
	// TRAFFIC SIGNALS
	// =============================================================

	/**
	* Fetch live state for one or more traffic signals.
	*
	* <p>Input DATA (optional): list of integer signal IDs. If omitted,
	* returns the {@code id_list} of all signals.
	*
	* <p>Output DATA: list of {@code {ID, groupID, state, nex_state,
	* next_update_time, phase_ticks}} records.
	*/
	public HashMap<String, Object> getSignal(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("signalIds", ContextCreator.getSignalContext().getIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		Collection<Integer> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(int id: IDs) {
			Signal signal = ContextCreator.getSignalContext().get(id);
				if (signal != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("signalId", signal.getID());
					record2.put("signalGroupId", signal.getGroupID());
					record2.put("state", signal.getState());
					record2.put("nextState", signal.getNextState());
					record2.put("nextUpdateTime", signal.getNextUpdateTick());
					record2.put("phaseTicks", signal.getPhaseTick());
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("signalId", id));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	/**
	* Resolve a signal group (the SUMO origID identifying a co-located
	* group of signal heads) to the internal METS-R signal IDs belonging
	* to it.
	*
	* <p>Input DATA (optional): list of signal group origIDs. If omitted,
	* returns the {@code id_list} of all known group origIDs.
	*
	* <p>Output DATA: list of {@code {groupID, signalIDs}} records.
	*/
	public HashMap<String, Object> getSignalGroup(JSONObject jsonMsg){
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("signalGroupIds", ContextCreator.getSignalContext().getAllGroupIDs());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		Collection<String> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(String id: IDs) {
			List<Integer> signalGroup = ContextCreator.getSignalContext().getOneGroup(id);
				if (signalGroup != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("signalGroupId", id);
					record2.put("signalIds", signalGroup);
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("signalGroupId", id));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	/**
	* Resolve the signal controlling a specific upstream-to-downstream
	* road connection (i.e. a turning movement at a junction).
	*
	* <p>Input DATA: list of {@code {upStreamRoad, downStreamRoad}} where
	* both are original road IDs.
	*
	* <p>Output DATA: list of {@code {upStreamRoad, downStreamRoad,
	* signalId, state, next_state, next_update_tick, phase_ticks,
	* junction_id, STATUS, REASON?}} records.
	*/
	public HashMap<String, Object> getSignalForConnection(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("message", "No DATA field found. Expected: [{upStreamRoad, downStreamRoad}, ...]");
			jsonObj.put("status", "error");
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<UpStreamRoadDownStreamRoad>> collectionType = new TypeToken<Collection<UpStreamRoadDownStreamRoad>>() {};
			Collection<UpStreamRoadDownStreamRoad> connections = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for(UpStreamRoadDownStreamRoad connection: connections) {
				// Find roads by their original IDs
				Road upStreamRoad = ContextCreator.getCityContext().findRoadWithOrigID(connection.upstreamRoadId);
				Road downStreamRoad = ContextCreator.getCityContext().findRoadWithOrigID(connection.downstreamRoadId);

				if (upStreamRoad == null || downStreamRoad == null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("upstreamRoadId", connection.upstreamRoadId);
					record2.put("downstreamRoadId", connection.downstreamRoadId);
					record2.put("status", "error");
					record2.put("errorCode", upStreamRoad == null ? "Upstream road not found" : "Downstream road not found");
					jsonData.add(record2);
					continue;
				}

				// Get the junction at the downstream end of the upstream road
				int junctionID = upStreamRoad.getDownStreamJunction();
				Junction junction = ContextCreator.getJunctionContext().get(junctionID);

				if (junction == null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("upstreamRoadId", connection.upstreamRoadId);
					record2.put("downstreamRoadId", connection.downstreamRoadId);
					record2.put("status", "error");
					record2.put("errorCode", "No junction found at the connection");
					jsonData.add(record2);
					continue;
				}

				// Get the signal for this connection
				Signal signal = junction.getSignal(upStreamRoad.getID(), downStreamRoad.getID());

				if (signal != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("upstreamRoadId", connection.upstreamRoadId);
					record2.put("downstreamRoadId", connection.downstreamRoadId);
					record2.put("signalId", signal.getID());
					record2.put("state", signal.getState());
					record2.put("nextState", signal.getNextState());
					record2.put("nextUpdateTick", signal.getNextUpdateTick());
					record2.put("phaseTicks", signal.getPhaseTick());
					record2.put("junctionId", junctionID);
					record2.put("status", "ok");
					jsonData.add(record2);
				}
				else {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("upstreamRoadId", connection.upstreamRoadId);
					record2.put("downstreamRoadId", connection.downstreamRoadId);
					record2.put("status", "error");
					record2.put("errorCode", "No signal found for this connection (junction control type: " + junction.getControlType() + ")");
					jsonData.add(record2);
				}
			}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	// =============================================================
	// CHARGING STATIONS
	// =============================================================

	/**
	* Fetch live state for one or more charging stations.
	*
	* <p>Input DATA (optional): list of integer station IDs, records carrying
	* {@code ID}/{@code id}/{@code stationID}/{@code chargingStationId} plus optional
	* {@code transformCoordinates}, or an object carrying {@code ids} plus optional
	* {@code transformCoordinates}. If omitted, returns the {@code id_list} of all stations.
	*
	* <p>Output DATA: list of {@code {ID, l2_charger, dcfc_charger,
	* l2_price, dcfc_price, bus_charger, num_available_l2,
	* num_available_dcfc, departureRoad, arrivalRoad, pending_ev,
	* queue_l2, queue_dcfc, charging_l2, charging_dcfc, x, y, z}} records.
	* With {@code transformCoordinates = true}, {@code x/y/z} are returned in the
	* same transformed coordinate frame used by vehicle queries.
	*/
	public HashMap<String, Object> getChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("chargingStationIds", ContextCreator.getChargingStationContext().getIDList());
			return jsonObj;
		}
		try {
			ArrayList<IDTransformQuery> requests = parseIDTransformQueries(
					jsonMsg.get("data"), "chargingStationId");
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(IDTransformQuery request: requests) {
				ChargingStation cs = request.id == null ? null : ContextCreator.getChargingStationContext().get(request.id.intValue());
				if (cs != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("chargingStationId", cs.getID());
					record2.put("level2ChargerCount", cs.numCharger(ChargingStation.L2));
					record2.put("level3ChargerCount", cs.numCharger(ChargingStation.L3));
					record2.put("level2Price", cs.getPrice(ChargingStation.L2));
					record2.put("level3Price", cs.getPrice(ChargingStation.L3));
					record2.put("busChargerCount", cs.numCharger(ChargingStation.BUS));
					record2.put("availableLevel2ChargerCount", cs.capacity(ChargingStation.L2));
					record2.put("availableLevel3ChargerCount", cs.capacity(ChargingStation.L3));
					record2.put("availableBusChargerCount", cs.capacityBus());
					record2.put("departureRoadId", cs.getClosestRoad(false));
					record2.put("arrivalRoadId", cs.getClosestRoad(true));
					record2.put("pendingEvCount", cs.getPendingEVCount());
					record2.put("pendingBusCount", cs.getPendingBusCount());
					record2.put("level2QueueCount", cs.getQueuedL2Count());
					record2.put("level3QueueCount", cs.getQueuedL3Count());
					record2.put("busQueueCount", cs.getQueuedBusCount());
					record2.put("chargingLevel2Count", cs.getChargingL2Count());
					record2.put("chargingLevel3Count", cs.getChargingL3Count());
					record2.put("chargingBusCount", cs.getChargingBusCount());
					record2.put("chargedVehicleCount", cs.numChargedCar.get());
					record2.put("chargedBusCount", cs.numChargedBus.get());
					record2.put("level2WaitingTime", cs.waitingTimeL2());
					record2.put("level3WaitingTime", cs.waitingTimeL3());
					record2.put("active", cs.hasChargingVehicles());
					Coordinate coord = coordinateForQuery(cs.getCoord(), request.transformCoordinates);
					if (coord != null) {
						record2.put("x", coord.x);
						record2.put("y", coord.y);
						record2.put("z", coord.z);
					}
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("chargingStationId", request.id));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	// =============================================================
	// ROUTES & ROUTING WEIGHTS
	// =============================================================

	/**
	* Single shortest-path route between two world coordinates.
	*
	* <p>Input DATA: list of {@code {origX, origY, origZ, destX, destY,
	* destZ, transformCoordinates}}. With {@code transformCoordinates = true} the
	* coordinates are first transformed from the network file's source
	* CRS into METS-R's internal CRS.
	*
	* <p>Output DATA: list of route records. {@code road_list} remains the
	* physical-road-only path; connector IDs, interleaved paths, and road /
	* connector / total distance and travel-time estimates are also returned.
	* Distances are meters and travel times are seconds.
	*/
	public HashMap<String, Object> getRoutesBwCoords(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("roadIds", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<OriginCoordDestCoordTransform>> collectionType = new TypeToken<Collection<OriginCoordDestCoordTransform>>() {};
			Collection<OriginCoordDestCoordTransform> originCoordDestCoordTransforms = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			// Obtain the query results
			for (OriginCoordDestCoordTransform originCoordDestCoordTransform: originCoordDestCoordTransforms) {
			// Get orig and dest road
			Coordinate orig = new Coordinate(originCoordDestCoordTransform.originX, originCoordDestCoordTransform.originY, originCoordDestCoordTransform.originZ);
			Coordinate dest = new Coordinate(originCoordDestCoordTransform.destinationX, originCoordDestCoordTransform.destinationY, originCoordDestCoordTransform.destinationZ);

				// Transform coordinate if the input is from plain x y coord system
				if(originCoordDestCoordTransform.transformCoordinates) {
					try {
						JTS.transform(orig, orig,
								SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
						JTS.transform(dest, dest,
								SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
					} catch (TransformException e) {
						ContextCreator.logger
								.error("Coordinates transformation failed, origin x: " + orig.x + " y:" + orig.y +  "dest x:" + dest.x + " y:" + dest.y);
						e.printStackTrace();
					}
				}

				if(orig!=null && dest!=null) {
				// Get the list of road ID (route)
					List<Road> roadList = RouteContext.shortestPathRoute(orig, dest, this.rand_route);
					if(roadList != null) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						addRouteMetrics(record2, roadList);
						jsonData.add(record2);
					}
					else {
						jsonData.add(errorRecord(null, null));
					}

				}
				else jsonData.add(errorRecord(null, null));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	/**
	* Like {@link #getRoutesBwCoords} but with origin/destination
	* specified as road IDs.
	*
	* <p>Input DATA: list of {@code {orig, dest}}.
	* <p>Output DATA: connector-aware route records; {@code road_list} remains
	* physical-road-only for backward compatibility.
	*/
	public HashMap<String, Object> getRoutesBwRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("roadIds", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<OrigRoadDestRoad>> collectionType = new TypeToken<Collection<OrigRoadDestRoad>>() {};
			Collection<OrigRoadDestRoad> origRoadDestRoads = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			// Obtain the query results
			for (OrigRoadDestRoad origRoadDestRoad: origRoadDestRoads) {
			// Get orig and dest road
				Road origRoad = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoad.originRoadId);
				Road destRoad = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoad.destinationRoadId);
				if(origRoad!=null && destRoad!=null) {
				// Get the list of road ID (route)
					List<Road> roadList = RouteContext.shortestPathRoute(origRoad, destRoad, this.rand_route);
					if(roadList != null) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						addRouteMetrics(record2, roadList);
						jsonData.add(record2);
					}
					else {
						jsonData.add(errorRecord("originRoadId", origRoadDestRoad.originRoadId));
					}

				}
				else jsonData.add(errorRecord("originRoadId", origRoadDestRoad.originRoadId));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	/**
	* Top-K shortest paths between two world coordinates.
	*
	* <p>Input DATA: list of {@code {origX, origY, origZ, destX, destY,
	* destZ, transformCoordinates, K}}.
	* <p>Output DATA: list of connector-aware K-route records where
	* {@code road_lists} remains an array of physical-road-only paths.
	*/
	public HashMap<String, Object> getKRoutesBwCoords(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("roadIds", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<OriginCoordDestCoordTransformK>> collectionType = new TypeToken<Collection<OriginCoordDestCoordTransformK>>() {};
			Collection<OriginCoordDestCoordTransformK> requests = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (OriginCoordDestCoordTransformK req: requests) {
			Coordinate orig = new Coordinate(req.originX, req.originY, req.originZ);
			Coordinate dest = new Coordinate(req.destinationX, req.destinationY, req.destinationZ);

				if(req.transformCoordinates) {
					try {
						JTS.transform(orig, orig, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
						JTS.transform(dest, dest, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
					} catch (TransformException e) {
						ContextCreator.logger.error("Coordinates transformation failed, origin x: " + req.originX + " y:" + req.originY + " dest x:" + req.destinationX + " y:" + req.destinationY);
						e.printStackTrace();
					}
				}

				List<List<Road>> kRoadLists = RouteContext.kShortestPathRoute(req.routeCount, orig, dest);
				if(kRoadLists != null && !kRoadLists.isEmpty()) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					addKRouteMetrics(record2, kRoadLists);
					jsonData.add(record2);
				}
				else {
					jsonData.add(errorRecord(null, null));
				}
			}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	/**
	* Top-K shortest paths between two road IDs.
	*
	* <p>Input DATA: list of {@code {orig, dest, K}}.
	* <p>Output DATA: connector-aware K-route records; {@code road_lists}
	* remains physical-road-only for backward compatibility.
	*/
	public HashMap<String, Object> getKRoutesBwRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("roadIds", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<OrigRoadDestRoadK>> collectionType = new TypeToken<Collection<OrigRoadDestRoadK>>() {};
			Collection<OrigRoadDestRoadK> requests = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (OrigRoadDestRoadK req: requests) {
				Road origRoad = ContextCreator.getCityContext().findRoadWithOrigID(req.originRoadId);
				Road destRoad = ContextCreator.getCityContext().findRoadWithOrigID(req.destinationRoadId);
				if(origRoad != null && destRoad != null) {
					List<List<Road>> kRoadLists = RouteContext.kShortestPathRoute(req.routeCount, origRoad, destRoad);
					if(kRoadLists != null && !kRoadLists.isEmpty()) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						addKRouteMetrics(record2, kRoadLists);
						jsonData.add(record2);
					}
					else {
						jsonData.add(errorRecord("originRoadId", req.originRoadId));
					}
				}
				else jsonData.add(errorRecord("originRoadId", req.originRoadId));
			}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	/**
	* Fetch the current routing weight of one or more road edges (i.e.
	* the value used by online shortest-path computation, which may have
	* been overridden via the {@code updateEdgeWeight} control API).
	*
	* <p>Input DATA (optional): list of original road IDs. If omitted,
	* returns {@code id_list} / {@code orig_id} of all roads.
	*
	* <p>Output DATA: list of {@code {ID, r_type, avg_travel_time, length,
	* weight}} records.
	*/
	public HashMap<String, Object> getEdgeWeight(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			addSegmentIdLists(jsonObj, ContextCreator.getRoadContext().getQueryableOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		Collection<String> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(String id: IDs) {
			Road road = findQueryableRoad(id);
				if (road != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("segmentId", road.getOrigID());
					record2.put("roadType", road.getRoadType());
					record2.put("travelTime", road.getTravelTime());
					record2.put("length", road.getLength());
					addTravelTimeEstimatorFields(record2, road);
					if (road instanceof ConnectorRoad) {
						record2.put("routingWeight", road.getTravelTime());
						record2.put("segmentType", "connector");
						record2.put("laneIndex", null);
					} else {
						Node node1 = road.getUpStreamNode();
						Node node2 = road.getDownStreamNode();
						record2.put("routingWeight",
								ContextCreator.getRoadNetwork().getEdge(node1, node2).getWeight());
						record2.put("segmentType", "road");
					}
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("segmentId", id));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	private void addRouteMetrics(HashMap<String, Object> record,
			List<Road> roadList) {
		RouteQueryMetrics metrics = routeMetrics(roadList);
		record.put("roadIds", metrics.roadIDs);
		record.put("connectorIds", metrics.connectorIDs);
		record.put("segmentIds", metrics.interleavedIDs);
		record.put("roadDistance", metrics.roadDistance);
		record.put("connectorDistance", metrics.connectorDistance);
		record.put("distance", metrics.roadDistance + metrics.connectorDistance);
		record.put("roadTravelTime", metrics.roadTravelTime);
		record.put("connectorTravelTime", metrics.connectorTravelTime);
		record.put("travelTime", metrics.roadTravelTime + metrics.connectorTravelTime);
		record.put("travelTimeP90", metrics.travelTimeP90());
		record.put("travelTimeConfidence", metrics.confidence());
		record.put("minimumSegmentTravelTimeConfidence", metrics.minimumConfidence());
		record.put("liveEvidenceSegmentCount", metrics.liveEvidenceSegmentCount);
		record.put("priorOnlySegmentCount", metrics.priorOnlySegmentCount);
	}

	private void addKRouteMetrics(HashMap<String, Object> record,
			List<List<Road>> roadLists) {
		ArrayList<List<String>> roads = new ArrayList<List<String>>();
		ArrayList<List<String>> connectors = new ArrayList<List<String>>();
		ArrayList<List<String>> interleaved = new ArrayList<List<String>>();
		ArrayList<Double> roadDistances = new ArrayList<Double>();
		ArrayList<Double> connectorDistances = new ArrayList<Double>();
		ArrayList<Double> distances = new ArrayList<Double>();
		ArrayList<Double> roadTravelTimes = new ArrayList<Double>();
		ArrayList<Double> connectorTravelTimes = new ArrayList<Double>();
		ArrayList<Double> travelTimes = new ArrayList<Double>();
		ArrayList<Double> travelTimeP90s = new ArrayList<Double>();
		ArrayList<Double> travelTimeConfidences = new ArrayList<Double>();
		ArrayList<Double> minimumSegmentConfidences = new ArrayList<Double>();
		ArrayList<Integer> liveEvidenceSegmentCounts = new ArrayList<Integer>();
		ArrayList<Integer> priorOnlySegmentCounts = new ArrayList<Integer>();
		for (List<Road> roadList : roadLists) {
			RouteQueryMetrics metrics = routeMetrics(roadList);
			roads.add(metrics.roadIDs);
			connectors.add(metrics.connectorIDs);
			interleaved.add(metrics.interleavedIDs);
			roadDistances.add(metrics.roadDistance);
			connectorDistances.add(metrics.connectorDistance);
			distances.add(metrics.roadDistance + metrics.connectorDistance);
			roadTravelTimes.add(metrics.roadTravelTime);
			connectorTravelTimes.add(metrics.connectorTravelTime);
			travelTimes.add(metrics.roadTravelTime + metrics.connectorTravelTime);
			travelTimeP90s.add(metrics.travelTimeP90());
			travelTimeConfidences.add(metrics.confidence());
			minimumSegmentConfidences.add(metrics.minimumConfidence());
			liveEvidenceSegmentCounts.add(metrics.liveEvidenceSegmentCount);
			priorOnlySegmentCounts.add(metrics.priorOnlySegmentCount);
		}
		record.put("roadIdLists", roads);
		record.put("connectorIdLists", connectors);
		record.put("segmentIdLists", interleaved);
		record.put("roadDistances", roadDistances);
		record.put("connectorDistances", connectorDistances);
		record.put("distances", distances);
		record.put("roadTravelTimes", roadTravelTimes);
		record.put("connectorTravelTimes", connectorTravelTimes);
		record.put("travelTimes", travelTimes);
		record.put("travelTimeP90s", travelTimeP90s);
		record.put("travelTimeConfidences", travelTimeConfidences);
		record.put("minimumSegmentTravelTimeConfidences", minimumSegmentConfidences);
		record.put("liveEvidenceSegmentCounts", liveEvidenceSegmentCounts);
		record.put("priorOnlySegmentCounts", priorOnlySegmentCounts);
	}

	private RouteQueryMetrics routeMetrics(List<Road> roadList) {
		RouteQueryMetrics result = new RouteQueryMetrics();
		if (roadList == null) return result;
		for (int i = 0; i < roadList.size(); i++) {
			Road road = roadList.get(i);
			if (road == null) continue;
			if (road instanceof ConnectorRoad) {
				ConnectorRoad explicitConnector = (ConnectorRoad) road;
				result.connectorIDs.add(explicitConnector.getOrigID());
				result.interleavedIDs.add(explicitConnector.getOrigID());
				result.connectorDistance += finiteDouble(explicitConnector.getLength(), 0.0);
				double connectorTravelTime = finiteDouble(
						explicitConnector.getTravelTime(), 0.0);
				result.connectorTravelTime += connectorTravelTime;
				result.addEvidence(explicitConnector, connectorTravelTime);
				continue;
			}
			result.roadIDs.add(road.getOrigID());
			result.interleavedIDs.add(road.getOrigID());
			result.roadDistance += finiteDouble(road.getLength(), 0.0);
			double roadTravelTime = finiteDouble(road.getTravelTime(), 0.0);
			result.roadTravelTime += roadTravelTime;
			result.addEvidence(road, roadTravelTime);
			if (i + 1 >= roadList.size()) continue;
			Road target = roadList.get(i + 1);
			ConnectorRoad connector = target == null ? null
					: ContextCreator.getRoadContext().getConnector(road, target);
			if (connector == null) continue;
			result.connectorIDs.add(connector.getOrigID());
			result.interleavedIDs.add(connector.getOrigID());
			result.connectorDistance += finiteDouble(connector.getLength(), 0.0);
			double connectorTravelTime = finiteDouble(connector.getTravelTime(), 0.0);
			result.connectorTravelTime += connectorTravelTime;
			result.addEvidence(connector, connectorTravelTime);
		}
		return result;
	}

	private static final class RouteQueryMetrics {
		final ArrayList<String> roadIDs = new ArrayList<String>();
		final ArrayList<String> connectorIDs = new ArrayList<String>();
		final ArrayList<String> interleavedIDs = new ArrayList<String>();
		double roadDistance = 0.0;
		double connectorDistance = 0.0;
		double roadTravelTime = 0.0;
		double connectorTravelTime = 0.0;
		double travelTimeVarianceProxy = 0.0;
		double confidenceLogWeight = 0.0;
		double confidenceTimeWeight = 0.0;
		double minimumSegmentConfidence = 1.0;
		int evidenceSegmentCount = 0;
		int liveEvidenceSegmentCount = 0;
		int priorOnlySegmentCount = 0;

		void addEvidence(Road road, double travelTime) {
			double p90 = Math.max(travelTime,
					finiteStatic(road.getTravelTimeP90(), travelTime));
			double standardDeviationProxy = (p90 - travelTime) / 1.2815515655446004;
			this.travelTimeVarianceProxy += standardDeviationProxy * standardDeviationProxy;
			double confidence = Math.max(0.001,
					Math.min(0.999, road.getTravelTimeConfidence()));
			double weight = Math.max(0.001, travelTime);
			this.confidenceLogWeight += weight * Math.log(confidence);
			this.confidenceTimeWeight += weight;
			this.minimumSegmentConfidence = Math.min(this.minimumSegmentConfidence, confidence);
			this.evidenceSegmentCount++;
			String source = road.getTravelTimeEstimateSource();
			if ("live_censored".equals(source) || "completed_and_live".equals(source)) {
				this.liveEvidenceSegmentCount++;
			}
			if ("free_flow_prior".equals(source)) this.priorOnlySegmentCount++;
		}

		double confidence() {
			return this.confidenceTimeWeight > 0.0
					? Math.exp(this.confidenceLogWeight / this.confidenceTimeWeight) : 0.0;
		}

		double minimumConfidence() {
			return this.evidenceSegmentCount > 0 ? this.minimumSegmentConfidence : 0.0;
		}

		double travelTimeP90() {
			double mean = this.roadTravelTime + this.connectorTravelTime;
			return mean + 1.2815515655446004
					* Math.sqrt(Math.max(0.0, this.travelTimeVarianceProxy));
		}

		private static double finiteStatic(double value, double fallback) {
			return Double.isFinite(value) ? value : fallback;
		}
	}

	// =============================================================
	// BUS ROUTES
	// =============================================================

	/**
	* Fetch the stop list of one or more bus routes.
	*
	* <p>Input DATA (optional): list of route names. If omitted, returns
	* {@code id_list} (route IDs) and {@code orig_id} (route names) of all
	* known routes.
	*
	* <p>Output DATA: list of {@code {routeName, routeID, stopZones,
	* stopRoads}} records.
	*/
	public HashMap<String, Object> getBusRoute(JSONObject jsonMsg){
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("routeIds", ContextCreator.bus_schedule.getRouteIDs());
			jsonObj.put("routeNames", ContextCreator.bus_schedule.getRouteNames());
			return jsonObj;
		}

		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		Collection<String> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(String routeName: IDs) {
			int rID = ContextCreator.bus_schedule.getRouteID(routeName);

				if (rID != -1) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("routeName", routeName);
					record2.put("routeId", rID);
					record2.put("stopZoneIds", ContextCreator.bus_schedule.getStopZones(rID));
					record2.put("stopRoadIds", ContextCreator.bus_schedule.getStopRoadNames(rID));
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("routeName", routeName));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}

	// =============================================================
	// RIDE-HAILING REQUESTS & AVAILABLE TAXIS
	// =============================================================

	/**
	* Query pending taxi/bus requests waiting in zone queues.
	*
	* <p>Registered keys: {@code "pendingRequests"} (canonical),
	* {@code "queryPendingRequests"} (backward-compat alias).
	*
	* <p>Input DATA (optional): a single integer zoneId. If omitted,
	* pending requests from all zones are returned.
	*
	* <p>Output DATA: list of request summaries; each entry includes the
	* request ID, origin/destination zone &amp; road, party size, generation
	* &amp; current waiting time, and a {@code "status"} tag indicating which
	* queue the request was found in (one of {@code "pending_taxi"},
	* {@code "pending_taxi_sharable"}, {@code "pending_taxi_toAdd"},
	* {@code "pendingBusCount"}, or {@code "pending_bus_toAdd"}).
	*/
	public HashMap<String, Object> getPendingRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			Collection<Zone> zonesToCheck;
			if (!jsonMsg.containsKey("data")) {
				zonesToCheck = ContextCreator.getZoneContext().getAll();
			} else {
				int zoneId;
				try {
					zoneId = Integer.parseInt(jsonMsg.get("data").toString().trim());
				} catch (NumberFormatException nfe) {
					jsonObj.put("message", "DATA must be a zone ID (integer)");
					jsonObj.put("status", "error");
					return jsonObj;
				}
				Zone z = ContextCreator.getZoneContext().get(zoneId);
				if (z == null) {
					jsonObj.put("message", "Zone " + zoneId + " not found");
					jsonObj.put("status", "error");
					return jsonObj;
				}
				zonesToCheck = Collections.singletonList(z);
			}

			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (Zone z : zonesToCheck) {
				for (Request r : z.getTaxiRequestQueue()) {
					jsonData.add(requestSummary(r, "pending_taxi", z.getID()));
				}
				for (Queue<Request> sq : z.getSharableRequestForTaxi().values()) {
					for (Request r : sq) {
						jsonData.add(requestSummary(r, "pending_taxi_sharable", z.getID()));
					}
				}
				// Requests inserted in this tick that haven't been drained
				// into requestInQueueForTaxi by processToAddPassengers yet
				for (Request r : z.getToAddTaxiRequestQueue()) {
					jsonData.add(requestSummary(r, "pending_taxi_toAdd", z.getID()));
				}
				for (Request r : z.getBusRequestQueue()) {
					jsonData.add(requestSummary(r, "pendingBusCount", z.getID()));
				}
				for (Request r : z.getToAddBusRequestQueue()) {
					jsonData.add(requestSummary(r, "pending_bus_toAdd", z.getID()));
				}
			}
			jsonObj.put("data", jsonData);
			jsonObj.put("status", "ok");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getPendingRequests: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	/**
	* Fetch the current info for one or more requests by ID. Searches
	* pending queues across all zones plus on-board / to-board passenger
	* lists on active taxis and buses, so requests can be tracked through
	* their full lifecycle. Returns {@code {"ID": requestId, "status": "error"}}
	* for any unknown ID.
	*
	* <p>Registered keys: {@code "request"} (canonical),
	* {@code "queryRequest"} (backward-compat alias).
	*
	* <p>Input DATA: collection of integer request IDs.
	*/
	public HashMap<String, Object> getRequest(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("data")) {
			jsonObj.put("message", "No DATA field found. Expected a list of request IDs.");
			jsonObj.put("status", "error");
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
			Collection<Integer> reqIDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (int requestId : reqIDs) {
				HashMap<String, Object> rec = findRequestInfo(requestId);
				if (rec != null) {
					jsonData.add(rec);
				} else {
					HashMap<String, Object> ko = new HashMap<String, Object>();
					ko.put("requestId", requestId);
					ko.put("status", "error");
					jsonData.add(ko);
				}
			}
			jsonObj.put("data", jsonData);
			jsonObj.put("status", "ok");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getRequest: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	// Build a uniform JSON-friendly view of a Request. zoneId is the
	// containing zone for pending requests, or -1 once the request has
	// been picked up by a vehicle (vehicleId is added by the caller in that case).
	private HashMap<String, Object> requestSummary(Request r, String status, int zoneId) {
		HashMap<String, Object> rec = new HashMap<String, Object>();
		rec.put("requestId", r.getID());
		rec.put("originZoneId", r.getOriginZone());
		rec.put("destinationZoneId", r.getDestZone());
		rec.put("originRoadId", ContextCreator.getRoadContext().get(r.getOriginRoad()).getOrigID());
		rec.put("destinationRoadId", ContextCreator.getRoadContext().get(r.getDestRoad()).getOrigID());
		rec.put("passengerCount", r.getNumPeople());
		rec.put("generationTime", r.generationTime);
		rec.put("matchedTime", r.matchedTime);
		rec.put("pickupTime", r.pickupTime);
		rec.put("arrivalTime", r.arriveTIme);
		rec.put("maxWaitTicks", r.getMaxWaitingTime());
		rec.put("currentWaitingTime", r.getCurrentWaitingTime());
		rec.put("shareable", r.isShareable());
		rec.put("busRoute", r.getBusRoute());
		rec.put("status", status);
		rec.put("zoneId", zoneId);
		rec.put("status", "ok");
		return rec;
	}

	public HashMap<String, Object> getPickupTaxiInfo(JSONObject jsonMsg) {
		return getTaxiRequestInfo(jsonMsg, ContextCreator.getVehicleContext().getPickupTaxiRequestMap(),
				"toBoard");
	}

	public HashMap<String, Object> getOccupiedTaxiInfo(JSONObject jsonMsg) {
		return getTaxiRequestInfo(jsonMsg, ContextCreator.getVehicleContext().getOccupiedTaxiRequestMap(),
				"onBoard");
	}

	private HashMap<String, Object> getTaxiRequestInfo(JSONObject jsonMsg, Map<Integer, Vehicle> taxiMap,
			String requestState) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			List<Integer> requestedIDs = parseOptionalReqIDs(jsonMsg);
			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (Map.Entry<Integer, Vehicle> entry : taxiMap.entrySet()) {
				if (requestedIDs != null && !requestedIDs.contains(entry.getKey())) {
					continue;
				}
				Vehicle vehicle = entry.getValue();
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("requestId", entry.getKey());
				record.put("vehicleId", vehicle == null ? -1 : vehicle.getID());
				record.put("requestState", requestState);
				if (vehicle != null) {
					record.put("state", vehicle.getState());
				}
				record.put("status", vehicle == null ? "error" : "ok");
				jsonData.add(record);
			}
			jsonObj.put("data", jsonData);
			jsonObj.put("status", "ok");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getTaxiRequestInfo: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	private List<Integer> parseOptionalReqIDs(JSONObject jsonMsg) {
		if (!jsonMsg.containsKey("data")) {
			return null;
		}
		ArrayList<Integer> ids = new ArrayList<Integer>();
		Object data = jsonMsg.get("data");
		if (data instanceof Iterable<?>) {
			for (Object value : (Iterable<?>) data) {
				Integer id = parseInteger(value);
				if (id != null) {
					ids.add(id);
				}
			}
		} else {
			Integer id = parseInteger(data);
			if (id != null) {
				ids.add(id);
			}
		}
		return ids;
	}

	private Integer parseInteger(Object value) {
		if (value == null) return null;
		if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
		try {
			return Integer.valueOf(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private Boolean parseBoolean(Object value) {
		if (value == null) return null;
		if (value instanceof Boolean) return (Boolean) value;
		if (value instanceof Number) return Boolean.valueOf(((Number) value).intValue() != 0);
		String text = String.valueOf(value).trim().toLowerCase();
		if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text)) {
			return Boolean.TRUE;
		}
		if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text)) {
			return Boolean.FALSE;
		}
		return null;
	}

	private Double parseDouble(Object value) {
		if (value == null) return null;
		if (value instanceof Number) return Double.valueOf(((Number) value).doubleValue());
		try {
			return Double.valueOf(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	* Query taxis that are currently in the available pool (parked or
	* cruising and waiting for a dispatch).
	*
	* <p>Registered keys: {@code "availableTaxis"} (canonical),
	* {@code "queryAvailableTaxis"} (backward-compat alias).
	*
	* <p>Input DATA (optional): a single integer zoneId. If omitted,
	* available taxis across every zone are returned.
	*
	* <p>Output DATA: flat list of taxi info entries, each carrying
	* {@code {ID, zoneId, state, x, y, z, battery, hasEnoughBattery,
	* passNum}}. The {@code zoneId} on each entry is the zone whose
	* available-taxi pool the taxi belongs to (i.e. the same key used by
	* {@code removeAvailableTaxi}).
	*/
	public HashMap<String, Object> getAvailableTaxis(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			Collection<Zone> zonesToCheck;
			if (!jsonMsg.containsKey("data")) {
				zonesToCheck = ContextCreator.getZoneContext().getAll();
			} else {
				int zoneId;
				try {
					zoneId = Integer.parseInt(jsonMsg.get("data").toString().trim());
				} catch (NumberFormatException nfe) {
					jsonObj.put("message", "DATA must be a zone ID (integer)");
					jsonObj.put("status", "error");
					return jsonObj;
				}
				Zone z = ContextCreator.getZoneContext().get(zoneId);
				if (z == null) {
					jsonObj.put("message", "Zone " + zoneId + " not found");
					jsonObj.put("status", "error");
					return jsonObj;
				}
				zonesToCheck = Collections.singletonList(z);
			}

			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (Zone z : zonesToCheck) {
				for (ElectricTaxi t : ContextCreator.getVehicleContext().getAvailableTaxisSorted(z.getID())) {
					HashMap<String, Object> rec = new HashMap<String, Object>();
					rec.put("taxiId", t.getID());
					rec.put("zoneId", z.getID());
					rec.put("state", t.getState());
					Coordinate c = t.getCurrentCoord();
					if (c != null) {
						rec.put("x", c.x);
						rec.put("y", c.y);
						rec.put("z", c.z);
					}
					rec.put("battery", t.getBatteryLevel());
					rec.put("hasEnoughBattery", t.hasEnoughBattery());
					rec.put("passengerCount", t.getPassNum());
					jsonData.add(rec);
				}
			}
			jsonObj.put("data", jsonData);
			jsonObj.put("status", "ok");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getAvailableTaxis: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	/**
	* Exhaustively query occupied taxis that are expected to become available
	* soon.
	*
	* <p>A taxi is included when it is on an occupied trip, has exactly one
	* onboard request, has no queued pickup, and its connector-inclusive remaining
	* trip distance is less than the requested distance
	* threshold. A bare {@code DATA} number is interpreted as miles; an object can
	* use {@code distanceThresholdMiles}/{@code thresholdMiles} or
	* {@code distanceThresholdMeters}/{@code thresholdMeters}. If omitted, the
	* default is 5 miles.
	*/
	public HashMap<String, Object> getAlmostFinishedTaxis(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			double thresholdMeters = DEFAULT_ALMOST_FINISHED_TAXI_MILES * MILE_IN_METERS;
			Integer zoneFilter = null;
			if (jsonMsg.containsKey("data")) {
				Object data = jsonMsg.get("data");
				if (data instanceof Map<?, ?>) {
					Map<?, ?> params = (Map<?, ?>) data;
					Object metersValue = firstParamValue(params, "distanceThresholdMeters", "thresholdMeters");
					Object milesValue = firstParamValue(params, "distanceThresholdMiles", "thresholdMiles",
							"distanceThreshold", "threshold");
					if (metersValue != null) {
						Double parsed = parseDouble(metersValue);
						if (parsed == null) {
							jsonObj.put("message", "distance threshold in meters must be numeric");
							jsonObj.put("status", "error");
							return jsonObj;
						}
						thresholdMeters = parsed.doubleValue();
					} else if (milesValue != null) {
						Double parsed = parseDouble(milesValue);
						if (parsed == null) {
							jsonObj.put("message", "distance threshold in miles must be numeric");
							jsonObj.put("status", "error");
							return jsonObj;
						}
						thresholdMeters = parsed.doubleValue() * MILE_IN_METERS;
					}

					Object zoneValue = firstParamValue(params, "zoneId", "zone", "destZone");
					if (zoneValue != null) {
						zoneFilter = parseInteger(zoneValue);
						if (zoneFilter == null) {
							jsonObj.put("message", "zoneId must be an integer");
							jsonObj.put("status", "error");
							return jsonObj;
						}
						if (ContextCreator.getZoneContext().get(zoneFilter.intValue()) == null) {
							jsonObj.put("message", "Zone " + zoneFilter + " not found");
							jsonObj.put("status", "error");
							return jsonObj;
						}
					}
				} else {
					Double parsed = parseDouble(data);
					if (parsed == null) {
						jsonObj.put("message", "DATA must be a distance threshold in miles or an object");
						jsonObj.put("status", "error");
						return jsonObj;
					}
					thresholdMeters = parsed.doubleValue() * MILE_IN_METERS;
				}
			}
			if (thresholdMeters < 0 || Double.isNaN(thresholdMeters) || Double.isInfinite(thresholdMeters)) {
				jsonObj.put("message", "distance threshold must be a finite non-negative number");
				jsonObj.put("status", "error");
				return jsonObj;
			}

			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
				if (!isAlmostFinishedTaxi(t, thresholdMeters)) {
					continue;
				}
				Request lastRequest = t.getOnBoardRequests().peek();
				int zoneId = lastRequest == null ? t.getDestID() : lastRequest.getDestZone();
				if (zoneFilter != null && zoneId != zoneFilter.intValue()) {
					continue;
				}
				if (ContextCreator.getZoneContext().get(zoneId) == null) {
					continue;
				}
				HashMap<String, Object> rec = new HashMap<String, Object>();
				rec.put("taxiId", t.getID());
				rec.put("zoneId", zoneId);
				rec.put("state", t.getState());
				rec.put("destinationZoneId", t.getDestID());
				rec.put("destinationRoadId", roadOrigIDOrNull(t.getDestRoad()));
				addRemainingDistanceFields(rec, t);
				if (lastRequest != null) {
					rec.put("requestId", lastRequest.getID());
					rec.put("originZoneId", lastRequest.getOriginZone());
					rec.put("requestDestinationZoneId", lastRequest.getDestZone());
					rec.put("requestPassengerCount", lastRequest.getNumPeople());
				}
				Coordinate c = t.getCurrentCoord();
				if (c != null) {
					rec.put("x", c.x);
					rec.put("y", c.y);
					rec.put("z", c.z);
				}
				rec.put("battery", t.getBatteryLevel());
				rec.put("passengerCount", t.getPassNum());
				jsonData.add(rec);
			}
			jsonObj.put("data", jsonData);
			jsonObj.put("distanceThreshold", thresholdMeters);
			jsonObj.put("distanceThresholdMiles", thresholdMeters / MILE_IN_METERS);
			jsonObj.put("status", "ok");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getAlmostFinishedTaxis: " + e.toString());
			jsonObj.put("status", "error");
			return jsonObj;
		}
	}

	private boolean isAlmostFinishedTaxi(ElectricTaxi taxi, double thresholdMeters) {
		if (taxi == null || taxi.getState() != Vehicle.OCCUPIED_TRIP) return false;
		if (!taxi.getToBoardRequests().isEmpty() || taxi.getOnBoardRequests().size() != 1) return false;
		double remainingDistance = taxi.getDistToTravelIncludingConnectors();
		return remainingDistance >= 0 && remainingDistance < thresholdMeters;
	}

	private Object firstParamValue(Map<?, ?> params, String... names) {
		for (String name : names) {
			if (params.containsKey(name)) {
				return params.get(name);
			}
		}
		return null;
	}

	private HashMap<String, Object> findRequestInfo(int requestId) {
		VehicleContext.PendingTaxiRequestEntry indexed =
				ContextCreator.getVehicleContext().getPendingTaxiRequest(requestId);
		if (indexed != null) {
			String status = "pending_taxi";
			if ("sharable".equals(indexed.queueKind)) status = "pending_taxi_sharable";
			else if ("toAdd".equals(indexed.queueKind)) status = "pending_taxi_toAdd";
			return requestSummary(indexed.request, status, indexed.zoneID);
		}
		// Pending in zones
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			for (Request r : z.getTaxiRequestQueue()) {
				if (r.getID() == requestId) return requestSummary(r, "pending_taxi", z.getID());
			}
			for (Queue<Request> sq : z.getSharableRequestForTaxi().values()) {
				for (Request r : sq) {
					if (r.getID() == requestId) return requestSummary(r, "pending_taxi_sharable", z.getID());
				}
			}
			for (Request r : z.getToAddTaxiRequestQueue()) {
				if (r.getID() == requestId) return requestSummary(r, "pending_taxi_toAdd", z.getID());
			}
			for (Request r : z.getBusRequestQueue()) {
				if (r.getID() == requestId) return requestSummary(r, "pendingBusCount", z.getID());
			}
			for (Request r : z.getToAddBusRequestQueue()) {
				if (r.getID() == requestId) return requestSummary(r, "pending_bus_toAdd", z.getID());
			}
		}
		// Active taxis (matched, en route to pickup, or carrying passengers)
		for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
			for (Request r : t.getToBoardRequests()) {
				if (r.getID() == requestId) {
					HashMap<String, Object> rec = requestSummary(r, "matched_to_taxi", -1);
					rec.put("vehicleId", t.getID());
					return rec;
				}
			}
			for (Request r : t.getOnBoardRequests()) {
				if (r.getID() == requestId) {
					HashMap<String, Object> rec = requestSummary(r, "on_board_taxi", -1);
					rec.put("vehicleId", t.getID());
					return rec;
				}
			}
		}
		// Active buses
		for (ElectricBus b : ContextCreator.getVehicleContext().getBuses()) {
			for (Queue<Request> sq : b.getToBoardRequests()) {
				for (Request r : sq) {
					if (r.getID() == requestId) {
						HashMap<String, Object> rec = requestSummary(r, "matched_to_bus", -1);
						rec.put("vehicleId", b.getID());
						return rec;
					}
				}
			}
			for (Queue<Request> sq : b.getOnBoardRequests()) {
				for (Request r : sq) {
					if (r.getID() == requestId) {
						HashMap<String, Object> rec = requestSummary(r, "on_board_bus", -1);
						rec.put("vehicleId", b.getID());
						return rec;
					}
				}
			}
		}
		return null;
	}

	/**
	* List the bus IDs currently assigned to each named bus route.
	*
	* <p>Input DATA (optional): list of route names. If omitted, returns
	* {@code id_list} / {@code orig_id} of all known routes.
	*
	* <p>Output DATA: list of {@code {routeName, routeID, busIDs}}
	* records.
	*/
	public HashMap<String, Object> getBusWithRoute(JSONObject jsonMsg){
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("data")) {
			jsonObj.put("routeIds", ContextCreator.bus_schedule.getRouteIDs());
			jsonObj.put("routeNames", ContextCreator.bus_schedule.getRouteNames());
			return jsonObj;
		}

		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		Collection<String> IDs = gson.fromJson(jsonMsg.get("data").toString(), collectionType.getType());
		ArrayList<Object> jsonData = new ArrayList<Object>();

		for(String routeName: IDs) {
			int rID = ContextCreator.bus_schedule.getRouteID(routeName);

				if (rID != -1) {
					List<Integer> busIDs = new ArrayList<Integer>();

					for(ElectricBus eb: ContextCreator.getVehicleContext().getBuses()) {
						if(eb.getRouteID() == rID) {
							busIDs.add(eb.getID());
						}
					}

					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("routeName", routeName);
					record2.put("routeId", rID);
					record2.put("busIds", busIDs);
					jsonData.add(record2);
				}
				else jsonData.add(errorRecord("routeName", routeName));
		}
			jsonObj.put("data", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		// Log error and return KO in case of exception
		ContextCreator.logger.error("Error processing query: " + e.toString());
		jsonObj.put("status", "error");
		return jsonObj;
		}
	}
}
