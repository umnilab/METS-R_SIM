package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

import org.geotools.geometry.jts.JTS;
import org.json.simple.JSONObject;
import org.opengis.referencing.operation.TransformException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.facility.ChargingStation;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Node;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Request;
import mets_r.mobility.Vehicle;
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
	private boolean routingGraphSnapshotRequired = false;
	private static final double ROUTING_GRAPH_EPSILON = 1e-9;
	
	public QueryMessageHandler() {
		messageHandlers.put("tick", this::getTick);
		messageHandlers.put("stepStatus", this::getStepStatus);
        // =============================================================
        // Vehicles
        // =============================================================
        messageHandlers.put("vehicle", this::getVehicle);
        messageHandlers.put("onRoadVehicles", this::getOnRoadVehicles);
        messageHandlers.put("onRoadVehicle", this::getOnRoadVehicles);
        messageHandlers.put("coSimVehicle", this::getCoSimVehicle);
        messageHandlers.put("taxi", this::getTaxi);
        messageHandlers.put("queryTaxi", this::getTaxi);
        messageHandlers.put("bus", this::getBus);
        
        // =============================================================
        // Roads & geometry
        // =============================================================
        messageHandlers.put("road", this::getRoad);
        messageHandlers.put("activeRoad", this::getActiveRoad);
        messageHandlers.put("activeRoads", this::getActiveRoad);
        messageHandlers.put("enteringVehicleQueue", this::getEnteringVehicleQueue);
        messageHandlers.put("coSimEnteringVehicleQueue", this::getCoSimEnteringVehicleQueue);
        messageHandlers.put("cosimEnteringVehicleQueue", this::getCoSimEnteringVehicleQueue);
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
        messageHandlers.put("queryRoutingGraphUpdates", this::getRoutingGraphUpdates);
        
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
        // Backward-compat aliases for earlier names
        messageHandlers.put("queryPendingRequests", this::getPendingRequests);
        messageHandlers.put("queryRequest", this::getRequest);
        messageHandlers.put("queryOnRoadVehicles", this::getOnRoadVehicles);
        messageHandlers.put("queryOnRoadVehicle", this::getOnRoadVehicles);
        messageHandlers.put("queryActiveRoad", this::getActiveRoad);
        messageHandlers.put("queryActiveRoads", this::getActiveRoad);
        messageHandlers.put("queryAvailableTaxis", this::getAvailableTaxis);
        messageHandlers.put("queryAlmostFinishedTaxis", this::getAlmostFinishedTaxis);
        messageHandlers.put("queryPickupTaxiInfo", this::getPickupTaxiInfo);
        messageHandlers.put("queryOccupiedTaxiInfo", this::getOccupiedTaxiInfo);
    }
	
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		CustomizableHandler handler = messageHandlers.get(msgType);
    	HashMap<String, Object> jsonAns = (handler != null) ? handler.handle(jsonMsg) : null;
    	jsonAns.put("TYPE", "ANS_" + msgType);
    	count++;
        return JSONObject.toJSONString(jsonAns);
	}
	
	public HashMap<String, Object> getTick(JSONObject jsonMsg) {
	    HashMap<String, Object> jsonObj = new HashMap<String, Object>();
	    jsonObj.put("CODE", "OK");
	    jsonObj.put("TICK", ContextCreator.getCurrentTick());
	    return jsonObj;
	}

	public HashMap<String, Object> getStepStatus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("CODE", "OK");
		jsonObj.putAll(ContextCreator.getStepStatus());
		addFrameSummaryFields(jsonObj);
		return jsonObj;
	}
	
	// =============================================================
	// VEHICLES
	// =============================================================
	
	/**
	 * Fetch live state for one or more vehicles.
	 *
	 * <p>Input DATA (optional): list of {@code {vehID, vehType,
	 * transformCoord}}. If omitted, returns {@code public_vids} and
	 * {@code private_vids} ID lists instead of per-vehicle records.
	 *
	 * <p>Output DATA: list of records carrying ID, vehicle class, state,
	 * (x, y, z) coords, bearing, acceleration, speed, explicit
	 * originRoadID/destRoadID and originZoneID/destZoneID fields, plus
	 * road / lane / distance-to-next-junction if the vehicle is on a road.
	 * Electric vehicles also include battery and totalEnergyConsumed.
	 */
	public HashMap<String, Object> getVehicle(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("public_vids", ContextCreator.getVehicleContext().getPublicVehicleIDList());
			jsonObj.put("private_vids", ContextCreator.getVehicleContext().getPrivateVehicleIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDVehTypeTran>> collectionType = new TypeToken<Collection<VehIDVehTypeTran>>() {};
			Collection<VehIDVehTypeTran> vehIDVehTypeTrans = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			// Process the query one by one
			for(VehIDVehTypeTran record: vehIDVehTypeTrans) {
				int id = record.vehID;
				Vehicle vehicle;
				if(record.vehType) {
					vehicle = ContextCreator.getVehicleContext().getPrivateVehicle(id);
				}
				else {
					vehicle = ContextCreator.getVehicleContext().getPublicVehicle(id);
				}
				if(vehicle != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					Coordinate coord = coordinateForQuery(vehicle.getCurrentCoord(), record.transformCoord);
    				record2.put("ID", vehicle.getID());
					record2.put("v_type", vehicle.getVehicleClass());
					record2.put("vehicleClass", vehicle.getVehicleClass());
					record2.put("state", vehicle.getState());
					record2.put("x", coord.x);
					record2.put("y", coord.y);
					record2.put("z", coord.z);
					record2.put("bearing", vehicle.getBearing());
					record2.put("acc", vehicle.currentAcc());
					record2.put("speed", vehicle.currentSpeed());
					addVehicleCoordinateFields(record2, vehicle, record.transformCoord);
					addVehicleRoadFields(record2, vehicle);
					record2.put("originZoneID", vehicle.getOriginID());
					record2.put("destZoneID", vehicle.getDestID());
					if (vehicle instanceof ElectricVehicle) {
						addElectricVehicleFields(record2, (ElectricVehicle) vehicle);
					}
					if(vehicle.isOnRoad()) {
						Lane lane = vehicle.getLane();
						if(vehicle.isOnLane() && lane != null) {
							record2.put("lane", lane.getIndex());
							record2.put("dist", vehicle.getDistanceToNextJunction());
						}
					}
					jsonData.add(record2);
				}
				else {
					jsonData.add("KO");
				}
			}
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query" + e.toString());
		    jsonObj.put("CODE", "KO");
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
					String roadID = road.getOrigID();
					activeRoadIDs.add(roadID);
					HashMap<String, Object> record = new HashMap<String, Object>();
					record.put("ID", roadID);
					record.put("roadID", roadID);
					record.put("num_veh", road.getVehicleNum());
					record.put("nVehicles", road.getVehicleNum());
					record.put("pendingDepartureVehicles", road.getPendingDepartureVehicleNum());
					record.put("STATUS", "OK");
					jsonData.add(record);
				}
			}
			jsonObj.put("id_list", activeRoadIDs);
			jsonObj.put("orig_id", activeRoadIDs);
			jsonObj.put("DATA", jsonData);
			jsonObj.put("CODE", "OK");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getActiveRoad: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}

	/**
	 * Return IDs for vehicles currently on roads.
	 *
	 * <p>Without DATA, output fields mirror {@link #getVehicle}'s no-DATA ID
	 * lists across all active roads. With DATA, input is a single original road
	 * ID, a list of original road IDs, or records carrying
	 * {@code roadID}/{@code ID}/{@code origID}; output DATA contains one record
	 * per requested road with {@code private_vids} and {@code public_vids}.
	 *
	 * <p>The no-DATA query walks only the active-road snapshot. A road-filtered
	 * query walks only each requested road's macro vehicle chain.
	 */
	public HashMap<String, Object> getOnRoadVehicles(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();

		try {
			if (jsonMsg.containsKey("DATA")) {
				ArrayList<Object> jsonData = new ArrayList<Object>();
				for (String roadID : parseRoadIDs(jsonMsg.get("DATA"))) {
					Road road = ContextCreator.getCityContext().findRoadWithOrigID(roadID);
					if (road != null) {
						jsonData.add(onRoadVehicleRecord(road));
					} else {
						HashMap<String, Object> record = new HashMap<String, Object>();
						record.put("ID", roadID);
						record.put("STATUS", "KO");
						jsonData.add(record);
					}
				}
				jsonObj.put("DATA", jsonData);
				jsonObj.put("CODE", "OK");
				return jsonObj;
			}

			ArrayList<Integer> privateVehicleIDs = new ArrayList<Integer>();
			ArrayList<Integer> publicVehicleIDs = new ArrayList<Integer>();
			if (ContextCreator.getRoadContext() != null) {
				for (Road road : ContextCreator.getRoadContext().getActiveRoadsSnapshot()) {
					appendOnRoadVehicleIDs(road, privateVehicleIDs, publicVehicleIDs);
				}
			}
			jsonObj.put("private_vids", privateVehicleIDs);
			jsonObj.put("public_vids", publicVehicleIDs);
			jsonObj.put("CODE", "OK");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getOnRoadVehicles: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}

	private HashMap<String, Object> onRoadVehicleRecord(Road road) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		ArrayList<Integer> privateVehicleIDs = new ArrayList<Integer>();
		ArrayList<Integer> publicVehicleIDs = new ArrayList<Integer>();
		appendOnRoadVehicleIDs(road, privateVehicleIDs, publicVehicleIDs);
		record.put("ID", road.getOrigID());
		record.put("roadID", road.getOrigID());
		record.put("private_vids", privateVehicleIDs);
		record.put("public_vids", publicVehicleIDs);
		record.put("STATUS", "OK");
		return record;
	}

	private void appendOnRoadVehicleIDs(Road road, ArrayList<Integer> privateVehicleIDs,
			ArrayList<Integer> publicVehicleIDs) {
		if (road == null) return;
		Vehicle vehicle = road.firstVehicle();
		while (vehicle != null) {
			Vehicle nextVehicle = vehicle.macroTrailing();
			if (vehicle.isOnRoad()) {
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
	 * Snapshot of every vehicle currently on a co-simulation road
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
		ArrayList<Object> jsonData = new ArrayList<Object>();
		for(Road r: ContextCreator.coSimRoads.values()) {
			Vehicle v = r.firstVehicle();
			while(v != null) {
				Vehicle nextVehicle = v.macroTrailing();
				int vid = -1;
				boolean vtype = false;
				if(v.getVehicleClass() == Vehicle.EV || v.getVehicleClass() == Vehicle.GV) { // private vehicle
					vid = ContextCreator.getVehicleContext().getPrivateVID(v.getID());
					vtype= true;
				}
				else { // public vehicle
					vid = v.getID();
					vtype = false;
				}
				if(vid != -1) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", vid);
					record2.put("v_type", vtype);
					record2.put("coord_map",v.getRecentCoordMap(6, true));
					record2.put("route", v.getRoute());
					jsonData.add(record2);
				}
				v = nextVehicle;
			}
		}
		
		jsonObj.put("DATA", jsonData);
		
		return jsonObj;
	}
	
	/**
	 * Fetch live status for one or more buses.
	 *
	 * <p>Input DATA (optional): list of integer bus IDs. If omitted,
	 * returns the {@code id_list} of all known buses.
	 *
	 * <p>Output DATA: list of {@code {ID, route, stopZones, current_stop,
	 * originRoadID, destRoadID, pass_num, matchedRequests, matchedPassengers, pickupRequests,
	 * pickupPassengers, dropoffRequests, dropoffPassengers, battery_state}}
	 * records.
	 */
	public  HashMap<String, Object> getBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getVehicleContext().getBusIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    
		    for(int id: IDs) {
		    	ElectricBus bus = ContextCreator.getVehicleContext().getBus(id);
				if(bus != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", bus.getID());
					int routeID = bus.getRouteID();
					String routeName = ContextCreator.bus_schedule.getRouteName(routeID);
					record2.put("route", routeName == null ? -1 : routeName);
					record2.put("routeId", routeID);
					record2.put("stopZones", bus.getBusStops());
					record2.put("stopZoneCount", bus.getBusStops().size());
					record2.put("current_stop",bus.getCurrentStop());
					record2.put("pass_num", bus.getPassNum());
					Coordinate currCoord = bus.getCurrentCoord();
					record2.put("x", currCoord.x);
					record2.put("y", currCoord.y);
					record2.put("z", currCoord.z);
					addVehicleCoordinateFields(record2, bus, false);
					record2.put("bearing", bus.getBearing());
					record2.put("speed", bus.currentSpeed());
					record2.put("battery", bus.getBatteryLevel());
					record2.put("energy", bus.getTotalConsume());
					record2.put("matchedRequests", bus.getMatchedRequests());
					record2.put("matchedPassengers", bus.getMatchedPassengers());
					record2.put("pickupRequests", bus.getPickupRequests());
					record2.put("pickupPassengers", bus.getPickupPassengers());
					record2.put("dropoffRequests", bus.getDropoffRequests());
					record2.put("dropoffPassengers", bus.getDropoffPassengers());
					record2.put("battery_state", bus.getBatteryLevel());
					addVehicleRoadFields(record2, bus);
					jsonData.add(record2);
				}
				else {
					jsonData.add("KO");
				}
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
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
	 * originRoadID, destRoadID, pass_num, matchedRequests, matchedPassengers, pickupRequests,
	 * pickupPassengers, dropoffRequests, dropoffPassengers}} records. Active
	 * trip states also include {@code remainingDistance} in meters and
	 * {@code remainingDistanceMiles}.
	 */
	public HashMap<String, Object> getTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getVehicleContext().getTaxiIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    
		    for(int id: IDs) {
		    	ElectricTaxi taxi = ContextCreator.getVehicleContext().getTaxi(id);
				if (taxi != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", taxi.getID());
					record2.put("state", taxi.getState());
					Coordinate currCoord = taxi.getCurrentCoord();
					record2.put("x", currCoord.x);
					record2.put("y", currCoord.y);
					record2.put("z", currCoord.z);
					record2.put("originZoneID", taxi.getOriginID());
					record2.put("destZoneID", taxi.getDestID());
					addVehicleCoordinateFields(record2, taxi, false);
					record2.put("bearing", taxi.getBearing());
					record2.put("speed", taxi.currentSpeed());
					record2.put("battery", taxi.getBatteryLevel());
					record2.put("energy", taxi.getTotalConsume());
					record2.put("pass_num", taxi.getPassNum());
					record2.put("matchedRequests", taxi.getMatchedRequests());
					record2.put("matchedPassengers", taxi.getMatchedPassengers());
					record2.put("pickupRequests", taxi.getPickupRequests());
					record2.put("pickupPassengers", taxi.getPickupPassengers());
					record2.put("dropoffRequests", taxi.getDropoffRequests());
					record2.put("dropoffPassengers", taxi.getDropoffPassengers());
					record2.put("toBoardReqIDs", requestIDs(taxi.getToBoardRequests()));
					record2.put("onBoardReqIDs", requestIDs(taxi.getOnBoardRequests()));
					addRemainingDistanceFields(record2, taxi);
					addVehicleRoadFields(record2, taxi);
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
		    return jsonObj;
		}
	}

	private void addRemainingDistanceFields(HashMap<String, Object> record, Vehicle vehicle) {
		if (!shouldReportRemainingDistance(vehicle)) return;
		double remainingDistance = remainingDistance(vehicle);
		record.put("remainingDistance", remainingDistance);
		record.put("remainingDistanceMiles", remainingDistance / MILE_IN_METERS);
	}

	private boolean shouldReportRemainingDistance(Vehicle vehicle) {
		if (vehicle == null) return false;
		int state = vehicle.getState();
		return state != Vehicle.PARKING
				&& state != Vehicle.CRUISING_TRIP
				&& state != Vehicle.NONE_OF_THE_ABOVE;
	}

	private double remainingDistance(Vehicle vehicle) {
		return vehicle == null ? Double.POSITIVE_INFINITY : Math.max(0.0, vehicle.getDistToTravel());
	}

	private void addVehicleRoadFields(HashMap<String, Object> record, Vehicle vehicle) {
		record.put("onRoad", vehicle.isOnRoad());
		Road currentRoad = vehicle.isOnRoad() ? vehicle.getRoad() : null;
		Road queuedRoad = vehicle.isOnRoad() ? null : findEnteringQueueRoad(vehicle);
		int originRoadID = firstAvailableRoadID(vehicle.getOriginRoad(), vehicle.getLastDeparturableRoad(),
				roadIDOrNegative(currentRoad), vehicle.getCurrentParkingRoad(), roadIDOrNegative(queuedRoad));
		int destRoadID = vehicle.getDestRoad();
		record.put("originRoadID", roadOrigIDOrNull(originRoadID));
		record.put("destRoadID", roadOrigIDOrNull(destRoadID));
		record.put("currentParkingRoadID", roadOrigIDOrNull(vehicle.getCurrentParkingRoad()));
		if (currentRoad != null) {
			record.put("roadID", currentRoad.getOrigID());
			record.put("roadControlType", currentRoad.getControlType());
			record.put("roadActive", ContextCreator.getRoadContext().isRoadActive(currentRoad.getID()));
			return;
		}

		if (queuedRoad != null) {
			record.put("queuedRoadID", queuedRoad.getOrigID());
			record.put("queuedRoadControlType", queuedRoad.getControlType());
			record.put("queuedRoadActive", ContextCreator.getRoadContext().isRoadActive(queuedRoad.getID()));
		}
	}

	private int firstAvailableRoadID(int... roadIDs) {
		for (int roadID : roadIDs) {
			if (roadID >= 0) return roadID;
		}
		return -1;
	}

	private int roadIDOrNegative(Road road) {
		return road == null ? -1 : road.getID();
	}

	private String roadOrigIDOrNull(int roadID) {
		if (roadID < 0) return null;
		if (ContextCreator.getRoadContext() == null) return null;
		Road road = ContextCreator.getRoadContext().get(roadID);
		return road == null ? null : road.getOrigID();
	}

	private Road findEnteringQueueRoad(Vehicle vehicle) {
		if (vehicle == null || vehicle.isOnRoad() || ContextCreator.getRoadContext() == null) return null;
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			if (road == null) continue;
			for (Vehicle queuedVehicle : road.getEnteringVehicleQueueSnapshot()) {
				if (queuedVehicle == vehicle) {
					return road;
				}
			}
		}
		return null;
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
        record.put("energy", live.privateEVEnergy + live.eTaxiEnergy + live.eBusEnergy);
        record.put("numVeh", live.numVeh);
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
            boolean transformCoord) {
        Coordinate prevCoord = coordinateForQuery(vehicle.getpreviousEpochCoord(), transformCoord);
        Coordinate originCoord = null;
        Coordinate destCoord = null;

        try {
            originCoord = coordinateForQuery(vehicle.getOriginCoord(), transformCoord);
        } catch (NullPointerException ignored) {
            originCoord = coordinateForQuery(vehicle.getCurrentCoord(), transformCoord);
        }

        try {
            destCoord = coordinateForQuery(vehicle.getDestCoord(), transformCoord);
        } catch (NullPointerException ignored) {
            destCoord = coordinateForQuery(vehicle.getCurrentCoord(), transformCoord);
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

    private Coordinate coordinateForQuery(Coordinate coord, boolean transformCoord) {
        if (coord == null) return null;
        Coordinate copy = new Coordinate();
        copy.x = coord.x;
        copy.y = coord.y;
        copy.z = coord.z;
        if (!transformCoord) return copy;
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
	 * <p>Output DATA: list of {@code {ID, roadID, roadIndex, r_type, num_veh,
	 * speed_limit, avg_travel_time, length, energy_consumed,
	 * avg_energy_consumption, parking_capacity, parked_num, down_stream_road}}
	 * records.
	 */
	public HashMap<String, Object> getRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			beginRoutingGraphSnapshotBaseline();
			List<String> roadIDs = ContextCreator.getRoadContext().getOrigIDList();
			jsonObj.put("id_list", roadIDs);
			jsonObj.put("CODE", "OK");
			addRoutingGraphMetadata(jsonObj, false);
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		      
		    for(String id: IDs) {
		    	Road road = ContextCreator.getCityContext().findRoadWithOrigID(id);
				if (road != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", road.getOrigID());
					record2.put("roadID", road.getOrigID());
					record2.put("roadIndex", getVisualizationRoadIndex(road));
					record2.put("r_type", road.getRoadType());
					record2.put("num_veh", road.getVehicleNum());
					record2.put("nVehicles", road.getVehicleNum());
					record2.put("speed", road.calcSpeed());
					addRoutingMetricFields(record2, road);
					record2.put("flow", road.getTotalFlow());
					record2.put("energy", road.getTotalEnergy());
					record2.put("parking_capacity", road.getParkingCapacity());
					record2.put("parkingCapacity", road.getParkingCapacity());
					record2.put("parked_num", road.getParkedNum());
					record2.put("parkedNum", road.getParkedNum());
					record2.put("down_stream_road", road.getDownStreamRoadOrigIDs());
					rememberRoutingGraphRoadSnapshot(road);
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			jsonObj.put("CODE", "OK");
			addRoutingGraphMetadata(jsonObj, false);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
		    return jsonObj;
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
			if (ContextCreator.getRoadContext() == null) {
				jsonObj.put("WARN", "Road context is not initialized");
				jsonObj.put("CODE", "KO");
				return jsonObj;
			}

			if (routingGraphRoadStates == null || routingGraphRoadContext != ContextCreator.getRoadContext()
					|| routingGraphRoadStates.size() != ContextCreator.getRoadContext().getAll().size()) {
				routingGraphSnapshotRequired = true;
				jsonObj.put("DATA", new ArrayList<Object>());
				jsonObj.put("removed", new ArrayList<String>());
				jsonObj.put("CODE", "OK");
				addRoutingGraphMetadata(jsonObj, true);
				return jsonObj;
			}

			HashMap<String, RoutingGraphRoadState> currentStates = new HashMap<String, RoutingGraphRoadState>();
			ArrayList<Object> jsonData = new ArrayList<Object>();
			ArrayList<String> removedRoads = new ArrayList<String>();
			boolean topologyChanged = false;

			for (Road road : ContextCreator.getRoadContext().getAll()) {
				if (road == null || road.getOrigID() == null) {
					continue;
				}
				RoutingGraphRoadState current = routingGraphRoadState(road);
				currentStates.put(current.roadID, current);
				RoutingGraphRoadState previous = routingGraphRoadStates.get(current.roadID);
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
			}

			for (String previousRoadID : routingGraphRoadStates.keySet()) {
				if (!currentStates.containsKey(previousRoadID)) {
					topologyChanged = true;
					removedRoads.add(previousRoadID);
				}
			}

			if (topologyChanged) {
				if (!routingGraphSnapshotRequired) {
					routingGraphTopologyVersion++;
				}
				routingGraphSnapshotRequired = true;
				jsonObj.put("DATA", new ArrayList<Object>());
				jsonObj.put("removed", removedRoads);
				jsonObj.put("CODE", "OK");
				addRoutingGraphMetadata(jsonObj, true);
				return jsonObj;
			}

			if (!jsonData.isEmpty()) {
				routingGraphMetricVersion++;
			}
			routingGraphRoadStates = currentStates;
			routingGraphRoadContext = ContextCreator.getRoadContext();
			routingGraphSnapshotRequired = false;
			jsonObj.put("DATA", jsonData);
			jsonObj.put("removed", removedRoads);
			jsonObj.put("CODE", "OK");
			addRoutingGraphMetadata(jsonObj, false);
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getRoutingGraphUpdates: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}

	private synchronized void beginRoutingGraphSnapshotBaseline() {
		if (ContextCreator.getRoadContext() == null) {
			routingGraphRoadStates = null;
			routingGraphRoadContext = null;
			routingGraphSnapshotRequired = true;
			return;
		}
		if (routingGraphRoadContext != null && routingGraphRoadContext != ContextCreator.getRoadContext()) {
			routingGraphTopologyVersion++;
		}
		routingGraphRoadStates = new HashMap<String, RoutingGraphRoadState>();
		routingGraphRoadContext = ContextCreator.getRoadContext();
		routingGraphSnapshotRequired = false;
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
		record.put("ID", state.roadID);
		record.put("roadID", state.roadID);
		record.put("roadIndex", getVisualizationRoadIndex(road));
		record.put("r_type", road.getRoadType());
		record.put("num_veh", road.getVehicleNum());
		record.put("parked_num", road.getParkedNum());
		addRoutingMetricFields(record, state);
		record.put("STATUS", "OK");
		return record;
	}

	private void addRoutingMetricFields(HashMap<String, Object> record, Road road) {
		addRoutingMetricFields(record, routingGraphRoadState(road));
	}

	private void addRoutingMetricFields(HashMap<String, Object> record, RoutingGraphRoadState state) {
		record.put("speed_limit", state.speedLimit);
		record.put("avg_travel_time", state.travelTime);
		record.put("length", state.distance);
		record.put("energy_consumed", state.energyConsumed);
		record.put("avg_energy_consumption", state.avgEnergyConsumption);
	}

	private RoutingGraphRoadState routingGraphRoadState(Road road) {
		double distance = finiteDouble(road.getLength(), 0.0);
		double travelTime = finiteDouble(road.getTravelTime(), 0.0);
		double speedLimit = finiteDouble(road.getSpeedLimit(), 0.0);
		if (travelTime <= 0.0 && distance > 0.0 && speedLimit > 0.0) {
			travelTime = distance / speedLimit;
		}
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
				finiteDouble(road.getTotalEnergy(), 0.0), finiteDouble(road.getAvgEnergyConsumption(), 0.0),
				speedLimit, downstreamRoads);
	}

	private double finiteDouble(double value, double fallback) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return fallback;
		}
		return value;
	}

	private class RoutingGraphRoadState {
		final String roadID;
		final double distance;
		final double travelTime;
		final double energyConsumed;
		final double avgEnergyConsumption;
		final double speedLimit;
		final ArrayList<String> downstreamRoads;

		RoutingGraphRoadState(String roadID, double distance, double travelTime,
				double energyConsumed, double avgEnergyConsumption, double speedLimit,
				ArrayList<String> downstreamRoads) {
			this.roadID = roadID;
			this.distance = distance;
			this.travelTime = travelTime;
			this.energyConsumed = energyConsumed;
			this.avgEnergyConsumption = avgEnergyConsumption;
			this.speedLimit = speedLimit;
			this.downstreamRoads = downstreamRoads == null ? new ArrayList<String>() : downstreamRoads;
		}

		boolean sameRoutingMetrics(RoutingGraphRoadState other) {
			return sameDouble(this.distance, other.distance)
					&& sameDouble(this.travelTime, other.travelTime)
					&& sameDouble(this.energyConsumed, other.energyConsumed)
					&& sameDouble(this.avgEnergyConsumption, other.avgEnergyConsumption)
					&& sameDouble(this.speedLimit, other.speedLimit);
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
	 * {@code roadID}/{@code ID}/{@code origID}. If omitted, all road IDs are
	 * returned.
	 */
	public HashMap<String, Object> getEnteringVehicleQueue(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			jsonObj.put("orig_id", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (String roadID : parseRoadIDs(jsonMsg.get("DATA"))) {
				Road road = ContextCreator.getCityContext().findRoadWithOrigID(roadID);
				if (road != null) {
					jsonData.add(roadEnteringQueueRecord(road));
				} else {
					HashMap<String, Object> record = new HashMap<String, Object>();
					record.put("ID", roadID);
					record.put("STATUS", "KO");
					jsonData.add(record);
				}
			}
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("CODE", "KO");
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
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("CODE", "KO");
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
			vehicleRecord.put("ID", visibleID);
			vehicleRecord.put("internalID", vehicle.getID());
			vehicleRecord.put("v_type", bridgeVehicleType(vehicle));
			vehicleRecord.put("vehicleClass", vehicle.getVehicleClass());
			vehicleRecord.put("departureTick", vehicle.getDepTime());
			vehicleRecord.put("ready", tick >= vehicle.getDepTime());
			queue.add(vehicleRecord);
		}
		record.put("ID", road.getOrigID());
		record.put("controlType", road.getControlType());
		record.put("enteringVehicleIDs", ids);
		record.put("queue", queue);
		record.put("STATUS", "OK");
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
			String roadID = roadIDFromEntry(data);
			if (roadID != null && !roadID.isEmpty()) roadIDs.add(roadID);
		} else if (data instanceof Collection<?>) {
			for (Object entry : (Collection<?>) data) {
				String roadID = roadIDFromEntry(entry);
				if (roadID != null && !roadID.isEmpty()) roadIDs.add(roadID);
			}
		} else if (data != null) {
			String value = data.toString();
			if (value.startsWith("[")) {
				Gson gson = new Gson();
				TypeToken<Collection<Object>> collectionType = new TypeToken<Collection<Object>>() {};
				Collection<Object> parsed = gson.fromJson(value, collectionType.getType());
				if (parsed != null) {
					for (Object entry : parsed) {
						String roadID = roadIDFromEntry(entry);
						if (roadID != null && !roadID.isEmpty()) roadIDs.add(roadID);
					}
				}
			} else if (value.startsWith("{")) {
				Gson gson = new Gson();
				Map<?, ?> parsed = gson.fromJson(value, Map.class);
				String roadID = roadIDFromEntry(parsed);
				if (roadID != null && !roadID.isEmpty()) roadIDs.add(roadID);
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
			Object value = firstPresent(record, "roadID", "ID", "origID", "orig_id");
			return value == null ? null : String.valueOf(value);
		}
		return String.valueOf(entry);
	}

	private ArrayList<IDTransformQuery> parseIDTransformQueries(Object data, String... idKeys) {
		ArrayList<IDTransformQuery> requests = new ArrayList<IDTransformQuery>();
		appendIDTransformQueries(requests, data, false, idKeys);
		return requests;
	}

	private void appendIDTransformQueries(ArrayList<IDTransformQuery> requests, Object entry,
			boolean defaultTransformCoord, String... idKeys) {
		if (entry instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) entry;
			Boolean transform = parseBoolean(firstPresent(record, "transformCoord", "transform"));
			boolean transformCoord = transform == null ? defaultTransformCoord : transform.booleanValue();
			Object idValue = firstPresent(record, idKeys);
			Object idsValue = firstPresent(record, "ids", "IDs", "id_list", "IDList", "zoneIDs",
					"zoneIds", "stationIDs", "stationIds", "chargingStationIDs", "chargingStationIds", "chargerIDs", "chargerIds");
			if (idValue != null) {
				requests.add(new IDTransformQuery(parseInteger(idValue), transformCoord));
				return;
			}
			if (idsValue != null) {
				appendIDTransformQueries(requests, idsValue, transformCoord, idKeys);
				return;
			}
			requests.add(new IDTransformQuery(null, transformCoord));
			return;
		}
		if (entry instanceof Iterable<?>) {
			for (Object value : (Iterable<?>) entry) {
				appendIDTransformQueries(requests, value, defaultTransformCoord, idKeys);
			}
			return;
		}
		requests.add(new IDTransformQuery(parseInteger(entry), defaultTransformCoord));
	}

	private static class IDTransformQuery {
		Integer id;
		boolean transformCoord;

		IDTransformQuery(Integer id, boolean transformCoord) {
			this.id = id;
			this.transformCoord = transformCoord;
		}
	}

	private Object firstPresent(Map<?, ?> record, String... keys) {
		for (String key : keys) {
			if (record.containsKey(key)) return record.get(key);
		}
		return null;
	}
	
	/**
	 * Fetch the polyline of a road or one of its lanes.
	 *
	 * <p>Input DATA: list of {@code {roadID, laneIndex, transformCoord}}.
	 * Use {@code laneIndex = -1} for the road's start/end coordinates;
	 * otherwise the coordinates of the specified lane are returned. If
	 * {@code transformCoord} is {@code true} the coordinates are
	 * back-transformed into the network file's source CRS.
	 *
	 * <p>Output DATA: list of {@code {ID, centerline}} records where
	 * {@code centerline} is an array of {@code [x, y, z]} points.
	 */
	public HashMap<String, Object> getCenterLine(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			jsonObj.put("orig_id", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<RoadIDLaneIndexTransform>> collectionType = new TypeToken<Collection<RoadIDLaneIndexTransform>>() {};
		    Collection<RoadIDLaneIndexTransform> roadIDLaneIndexTransforms = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		      
		    for(RoadIDLaneIndexTransform roadIDLaneIndexTransform: roadIDLaneIndexTransforms) {
		    	Road road = ContextCreator.getCityContext().findRoadWithOrigID(roadIDLaneIndexTransform.roadID);
				if (road != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", road.getOrigID());
					ArrayList<ArrayList<Double>> res = new ArrayList<ArrayList<Double>>();
					int laneIndex = roadIDLaneIndexTransform.laneIndex;
					boolean transformCoord = roadIDLaneIndexTransform.transformCoord;
					if(laneIndex < 0) {
						Coordinate startCoord = road.getStartCoord();
						Coordinate endCoord = road.getEndCoord();
						if(transformCoord) {
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
						if(transformCoord) {
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
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
		    return jsonObj;
		}
	}
	
	// =============================================================
	// ZONES
	// =============================================================
	
	/**
	 * Fetch live state for one or more zones.
	 *
	 * <p>Input DATA (optional): list of integer zone IDs, records carrying
	 * {@code ID}/{@code id}/{@code zoneID} plus optional
	 * {@code transformCoord}, or an object carrying {@code ids} plus optional
	 * {@code transformCoord}. If omitted, returns the {@code id_list} of all zones.
	 *
	 * <p>Output DATA: list of {@code {ID, z_type, taxi_demand, bus_demand,
	 * veh_stock, x, y, z, leftTaxiRequests, leftTaxiPassengers,
	 * leftBusRequests, leftBusPassengers}} records. The {@code left*} fields
	 * are cumulative since simulation start at that zone: requests that
	 * abandoned the taxi or bus queue after exceeding maximum wait time, and
	 * passenger totals (sum of {@link Request#getNumPeople()} per abandoned
	 * request). With {@code transformCoord = true}, {@code x/y/z} are returned in
	 * the same transformed coordinate frame used by vehicle queries.
	 */
	public HashMap<String, Object> getZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getZoneContext().getIDList());
			return jsonObj;
		}
		try {
			ArrayList<IDTransformQuery> requests = parseIDTransformQueries(jsonMsg.get("DATA"),
					"zoneID", "zoneId", "zone", "ID", "id");
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    
		    for(IDTransformQuery request: requests) {
				Zone zone = request.id == null ? null : ContextCreator.getZoneContext().get(request.id.intValue());
				if (zone != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", zone.getID());
					record2.put("id", zone.getID());
					record2.put("z_type", zone.getZoneType());
					record2.put("zoneType", zone.getZoneType());
					record2.put("capacity", zone.getCapacity());
					record2.put("taxi_demand", zone.getTaxiRequestNum());
					record2.put("taxiRequest", zone.getTaxiRequestNum());
					record2.put("bus_demand", zone.getBusRequestNum());
					record2.put("busRequest", zone.getBusRequestNum());
					record2.put("veh_stock", zone.getVehicleStock());
					record2.put("vehicleStock", zone.getVehicleStock());
					Coordinate coord = coordinateForQuery(zone.getCoord(), request.transformCoord);
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
					record2.put("taxiMatchedRequests", zone.taxiPickupRequest);
					record2.put("busMatchedRequests", zone.busPickupRequest);
					record2.put("pickupTaxiRequests", zone.taxiPickedUpRequest);
					record2.put("pickupTaxiPassengers", zone.taxiPickedUpPassengers);
					record2.put("pickupBusRequests", zone.busPickedUpRequest);
					record2.put("pickupBusPassengers", zone.busPickedUpPassengers);
					record2.put("dropoffTaxiRequests", zone.taxiServedRequest);
					record2.put("dropoffTaxiPassengers", zone.taxiServedPassengers);
					record2.put("dropoffBusRequests", zone.busServedRequest);
					record2.put("dropoffBusPassengers", zone.busServedPassengers);
					record2.put("taxiDropoffRequests", zone.taxiServedRequest);
					record2.put("busDropoffRequests", zone.busServedRequest);
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
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
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
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getSignalContext().getIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    
		    for(int id: IDs) {
		    	Signal signal = ContextCreator.getSignalContext().get(id);
				if (signal != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", signal.getID());
					record2.put("groupID", signal.getGroupID());
					record2.put("state", signal.getState());
					record2.put("nex_state", signal.getNextState());
					record2.put("next_update_time", signal.getNextUpdateTick());
					record2.put("phase_ticks", signal.getPhaseTick());
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
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
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getSignalContext().getAllGroupIDs());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		      
		    for(String id: IDs) {
		    	List<Integer> signalGroup = ContextCreator.getSignalContext().getOneGroup(id);
				if (signalGroup != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("groupID", id);
					record2.put("signalIDs", signalGroup);
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
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
	 * signalID, state, next_state, next_update_tick, phase_ticks,
	 * junction_id, STATUS, REASON?}} records.
	 */
	public HashMap<String, Object> getSignalForConnection(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("WARN", "No DATA field found. Expected: [{upStreamRoad, downStreamRoad}, ...]");
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<UpStreamRoadDownStreamRoad>> collectionType = new TypeToken<Collection<UpStreamRoadDownStreamRoad>>() {};
			Collection<UpStreamRoadDownStreamRoad> connections = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			
			for(UpStreamRoadDownStreamRoad connection: connections) {
				// Find roads by their original IDs
				Road upStreamRoad = ContextCreator.getCityContext().findRoadWithOrigID(connection.upStreamRoad);
				Road downStreamRoad = ContextCreator.getCityContext().findRoadWithOrigID(connection.downStreamRoad);
				
				if (upStreamRoad == null || downStreamRoad == null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("upStreamRoad", connection.upStreamRoad);
					record2.put("downStreamRoad", connection.downStreamRoad);
					record2.put("STATUS", "KO");
					record2.put("REASON", upStreamRoad == null ? "Upstream road not found" : "Downstream road not found");
					jsonData.add(record2);
					continue;
				}
				
				// Get the junction at the downstream end of the upstream road
				int junctionID = upStreamRoad.getDownStreamJunction();
				Junction junction = ContextCreator.getJunctionContext().get(junctionID);
				
				if (junction == null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("upStreamRoad", connection.upStreamRoad);
					record2.put("downStreamRoad", connection.downStreamRoad);
					record2.put("STATUS", "KO");
					record2.put("REASON", "No junction found at the connection");
					jsonData.add(record2);
					continue;
				}
				
				// Get the signal for this connection
				Signal signal = junction.getSignal(upStreamRoad.getID(), downStreamRoad.getID());
				
				if (signal != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("upStreamRoad", connection.upStreamRoad);
					record2.put("downStreamRoad", connection.downStreamRoad);
					record2.put("signalID", signal.getID());
					record2.put("state", signal.getState());
					record2.put("next_state", signal.getNextState());
					record2.put("next_update_tick", signal.getNextUpdateTick());
					record2.put("phase_ticks", signal.getPhaseTick());
					record2.put("junction_id", junctionID);
					record2.put("STATUS", "OK");
					jsonData.add(record2);
				}
				else {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("upStreamRoad", connection.upStreamRoad);
					record2.put("downStreamRoad", connection.downStreamRoad);
					record2.put("STATUS", "KO");
					record2.put("REASON", "No signal found for this connection (junction control type: " + junction.getControlType() + ")");
					jsonData.add(record2);
				}
			}
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
			// Log error and return KO in case of exception
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("CODE", "KO");
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
	 * {@code ID}/{@code id}/{@code stationID}/{@code csID} plus optional
	 * {@code transformCoord}, or an object carrying {@code ids} plus optional
	 * {@code transformCoord}. If omitted, returns the {@code id_list} of all stations.
	 *
	 * <p>Output DATA: list of {@code {ID, l2_charger, dcfc_charger,
	 * l2_price, dcfc_price, bus_charger, num_available_l2,
	 * num_available_dcfc, departureRoad, arrivalRoad, pending_ev,
	 * queue_l2, queue_dcfc, charging_l2, charging_dcfc, x, y, z}} records.
	 * With {@code transformCoord = true}, {@code x/y/z} are returned in the
	 * same transformed coordinate frame used by vehicle queries.
	 */
	public HashMap<String, Object> getChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getChargingStationContext().getIDList());
			return jsonObj;
		}
		try {
			ArrayList<IDTransformQuery> requests = parseIDTransformQueries(jsonMsg.get("DATA"),
					"chargingStationID", "chargingStationId", "chargerID", "chargerId", "stationID", "stationId", "station", "csID", "csId", "ID", "id");
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    
		    for(IDTransformQuery request: requests) {
				ChargingStation cs = request.id == null ? null : ContextCreator.getChargingStationContext().get(request.id.intValue());
				if (cs != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", cs.getID());
					record2.put("id", cs.getID());
					record2.put("l2_charger", cs.numCharger(ChargingStation.L2));
					record2.put("numL2", cs.numCharger(ChargingStation.L2));
					record2.put("dcfc_charger", cs.numCharger(ChargingStation.L3));
					record2.put("numL3", cs.numCharger(ChargingStation.L3));
					record2.put("l2_price", cs.getPrice(ChargingStation.L2));
					record2.put("priceL2", cs.getPriceL2());
					record2.put("dcfc_price", cs.getPrice(ChargingStation.L3));
					record2.put("priceL3", cs.getPriceL3());
					record2.put("bus_charger", cs.numCharger(ChargingStation.BUS));
					record2.put("numBus", cs.numBusCharger());
					record2.put("num_available_l2", cs.capacity(ChargingStation.L2));
					record2.put("freeL2", cs.capacity(ChargingStation.L2));
					record2.put("num_available_dcfc", cs.capacity(ChargingStation.L3));
					record2.put("freeL3", cs.capacity(ChargingStation.L3));
					record2.put("freeBus", cs.capacityBus());
					record2.put("departureRoad", cs.getClosestRoad(false));
					record2.put("arrivalRoad", cs.getClosestRoad(true));
					record2.put("pending_ev", cs.getPendingEVCount());
					record2.put("pending_bus", cs.getPendingBusCount());
					record2.put("queue_l2", cs.getQueuedL2Count());
					record2.put("queueL2", cs.getQueueL2().size());
					record2.put("queue_dcfc", cs.getQueuedL3Count());
					record2.put("queueL3", cs.getQueueL3().size());
					record2.put("queue_bus", cs.getQueuedBusCount());
					record2.put("queueBus", cs.getQueueBus().size());
					record2.put("charging_l2", cs.getChargingL2Count());
					record2.put("chargingL2", cs.getChargingL2().size());
					record2.put("charging_dcfc", cs.getChargingL3Count());
					record2.put("chargingL3", cs.getChargingL3().size());
					record2.put("charging_bus", cs.getChargingBusCount());
					record2.put("chargingBus", cs.getChargingBus().size());
					record2.put("chargedCar", cs.numChargedCar.get());
					record2.put("chargedBus", cs.numChargedBus.get());
					record2.put("waitingTimeL2", cs.waitingTimeL2());
					record2.put("waitingTimeL3", cs.waitingTimeL3());
					record2.put("active", cs.hasChargingVehicles() ? 1 : 0);
					Coordinate coord = coordinateForQuery(cs.getCoord(), request.transformCoord);
					if (coord != null) {
						record2.put("x", coord.x);
						record2.put("y", coord.y);
						record2.put("z", coord.z);
					}
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
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
	 * destZ, transformCoord}}. With {@code transformCoord = true} the
	 * coordinates are first transformed from the network file's source
	 * CRS into METS-R's internal CRS.
	 *
	 * <p>Output DATA: list of {@code {road_list}} records (original road
	 * IDs along the path), or {@code "KO"} for unreachable pairs.
	 */
	public HashMap<String, Object> getRoutesBwCoords(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			jsonObj.put("orig_id", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<OriginCoordDestCoordTransform>> collectionType = new TypeToken<Collection<OriginCoordDestCoordTransform>>() {};
			Collection<OriginCoordDestCoordTransform> originCoordDestCoordTransforms = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			
			// Obtain the query results
			for (OriginCoordDestCoordTransform originCoordDestCoordTransform: originCoordDestCoordTransforms) {
		    	// Get orig and dest road
			Coordinate orig = new Coordinate(originCoordDestCoordTransform.origX, originCoordDestCoordTransform.origY, originCoordDestCoordTransform.origZ);
			Coordinate dest = new Coordinate(originCoordDestCoordTransform.destX, originCoordDestCoordTransform.destY, originCoordDestCoordTransform.destZ);
				
				// Transform coordinate if the input is from plain x y coord system
				if(originCoordDestCoordTransform.transformCoord) {
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
						List<String>  roadIDList = new ArrayList<String>();
						for(Road r: roadList) {
							roadIDList.add(r.getOrigID());
						}
						record2.put("road_list", roadIDList);
						jsonData.add(record2);
					}
					else {
						jsonData.add("KO");
					}
					
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData); 
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
		    return jsonObj;
		}
	}
	
	/**
	 * Like {@link #getRoutesBwCoords} but with origin/destination
	 * specified as road IDs.
	 *
	 * <p>Input DATA: list of {@code {orig, dest}}.
	 * <p>Output DATA: list of {@code {road_list}} records.
	 */
	public HashMap<String, Object> getRoutesBwRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			jsonObj.put("orig_id", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<OrigRoadDestRoad>> collectionType = new TypeToken<Collection<OrigRoadDestRoad>>() {};
			Collection<OrigRoadDestRoad> origRoadDestRoads = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			
			// Obtain the query results
			for (OrigRoadDestRoad origRoadDestRoad: origRoadDestRoads) {
		    	// Get orig and dest road
				Road origRoad = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoad.orig);
				Road destRoad = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoad.dest);
				if(origRoad!=null && destRoad!=null) {
		    		// Get the list of road ID (route)
					List<Road> roadList = RouteContext.shortestPathRoute(origRoad, destRoad, this.rand_route);
					if(roadList != null) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						List<String>  roadIDList = new ArrayList<String>();
						for(Road r: roadList) {
							roadIDList.add(r.getOrigID());
						}
						record2.put("road_list", roadIDList);
						jsonData.add(record2);
					}
					else {
						jsonData.add("KO");
					}
					
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData); 
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
		    return jsonObj;
		}
	}
	
	/**
	 * Top-K shortest paths between two world coordinates.
	 *
	 * <p>Input DATA: list of {@code {origX, origY, origZ, destX, destY,
	 * destZ, transformCoord, K}}.
	 * <p>Output DATA: list of {@code {road_lists}} records where
	 * {@code road_lists} is an array of K route arrays.
	 */
	public HashMap<String, Object> getKRoutesBwCoords(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			jsonObj.put("orig_id", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<OriginCoordDestCoordTransformK>> collectionType = new TypeToken<Collection<OriginCoordDestCoordTransformK>>() {};
			Collection<OriginCoordDestCoordTransformK> requests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (OriginCoordDestCoordTransformK req: requests) {
			Coordinate orig = new Coordinate(req.origX, req.origY, req.origZ);
			Coordinate dest = new Coordinate(req.destX, req.destY, req.destZ);

				if(req.transformCoord) {
					try {
						JTS.transform(orig, orig, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
						JTS.transform(dest, dest, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
					} catch (TransformException e) {
						ContextCreator.logger.error("Coordinates transformation failed, origin x: " + req.origX + " y:" + req.origY + " dest x:" + req.destX + " y:" + req.destY);
						e.printStackTrace();
					}
				}

				List<List<Road>> kRoadLists = RouteContext.kShortestPathRoute(req.K, orig, dest);
				if(kRoadLists != null && !kRoadLists.isEmpty()) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					ArrayList<List<String>> allRoutes = new ArrayList<List<String>>();
					for(List<Road> roadList: kRoadLists) {
						List<String> roadIDList = new ArrayList<String>();
						for(Road r: roadList) {
							roadIDList.add(r.getOrigID());
						}
						allRoutes.add(roadIDList);
					}
					record2.put("road_lists", allRoutes);
					jsonData.add(record2);
				}
				else {
					jsonData.add("KO");
				}
			}
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}

	/**
	 * Top-K shortest paths between two road IDs.
	 *
	 * <p>Input DATA: list of {@code {orig, dest, K}}.
	 * <p>Output DATA: list of {@code {road_lists}} records.
	 */
	public HashMap<String, Object> getKRoutesBwRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			jsonObj.put("orig_id", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<OrigRoadDestRoadK>> collectionType = new TypeToken<Collection<OrigRoadDestRoadK>>() {};
			Collection<OrigRoadDestRoadK> requests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (OrigRoadDestRoadK req: requests) {
				Road origRoad = ContextCreator.getCityContext().findRoadWithOrigID(req.orig);
				Road destRoad = ContextCreator.getCityContext().findRoadWithOrigID(req.dest);
				if(origRoad != null && destRoad != null) {
					List<List<Road>> kRoadLists = RouteContext.kShortestPathRoute(req.K, origRoad, destRoad);
					if(kRoadLists != null && !kRoadLists.isEmpty()) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						ArrayList<List<String>> allRoutes = new ArrayList<List<String>>();
						for(List<Road> roadList: kRoadLists) {
							List<String> roadIDList = new ArrayList<String>();
							for(Road r: roadList) {
								roadIDList.add(r.getOrigID());
							}
							allRoutes.add(roadIDList);
						}
						record2.put("road_lists", allRoutes);
						jsonData.add(record2);
					}
					else {
						jsonData.add("KO");
					}
				}
				else jsonData.add("KO");
			}
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing query: " + e.toString());
			jsonObj.put("CODE", "KO");
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
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			jsonObj.put("orig_id", ContextCreator.getRoadContext().getOrigIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		      
		    for(String id: IDs) {
		    	Road road = ContextCreator.getCityContext().findRoadWithOrigID(id);
				if (road != null) {
					Node node1 = road.getUpStreamNode();
			    	Node node2 = road.getDownStreamNode();
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", road.getOrigID());
					record2.put("r_type", road.getRoadType());
					record2.put("avg_travel_time", road.getTravelTime());
					record2.put("length", road.getLength());
					record2.put("weight", ContextCreator.getRoadNetwork().getEdge(node1, node2).getWeight());
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
		    return jsonObj;
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
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.bus_schedule.getRouteIDs());
			jsonObj.put("orig_id", ContextCreator.bus_schedule.getRouteNames());
			return jsonObj;
		}
		
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		     
		    for(String routeName: IDs) {
		    	int rID = ContextCreator.bus_schedule.getRouteID(routeName);
		    	
				if (rID != -1) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("routeName", routeName);
					record2.put("routeID", rID);
					record2.put("routeId", rID);
					record2.put("stopZones", ContextCreator.bus_schedule.getStopZones(rID));
					record2.put("stopRoads", ContextCreator.bus_schedule.getStopRoadNames(rID));
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
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
	 * <p>Input DATA (optional): a single integer zoneID. If omitted,
	 * pending requests from all zones are returned.
	 *
	 * <p>Output DATA: list of request summaries; each entry includes the
	 * request ID, origin/destination zone &amp; road, party size, generation
	 * &amp; current waiting time, and a {@code "status"} tag indicating which
	 * queue the request was found in (one of {@code "pending_taxi"},
	 * {@code "pending_taxi_sharable"}, {@code "pending_taxi_toAdd"},
	 * {@code "pending_bus"}, or {@code "pending_bus_toAdd"}).
	 */
	public HashMap<String, Object> getPendingRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			Collection<Zone> zonesToCheck;
			if (!jsonMsg.containsKey("DATA")) {
				zonesToCheck = ContextCreator.getZoneContext().getAll();
			} else {
				int zoneID;
				try {
					zoneID = Integer.parseInt(jsonMsg.get("DATA").toString().trim());
				} catch (NumberFormatException nfe) {
					jsonObj.put("WARN", "DATA must be a zone ID (integer)");
					jsonObj.put("CODE", "KO");
					return jsonObj;
				}
				Zone z = ContextCreator.getZoneContext().get(zoneID);
				if (z == null) {
					jsonObj.put("WARN", "Zone " + zoneID + " not found");
					jsonObj.put("CODE", "KO");
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
					jsonData.add(requestSummary(r, "pending_bus", z.getID()));
				}
				for (Request r : z.getToAddBusRequestQueue()) {
					jsonData.add(requestSummary(r, "pending_bus_toAdd", z.getID()));
				}
			}
			jsonObj.put("DATA", jsonData);
			jsonObj.put("CODE", "OK");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getPendingRequests: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}
	
	/**
	 * Fetch the current info for one or more requests by ID. Searches
	 * pending queues across all zones plus on-board / to-board passenger
	 * lists on active taxis and buses, so requests can be tracked through
	 * their full lifecycle. Returns {@code {"ID": reqID, "STATUS": "KO"}}
	 * for any unknown ID.
	 *
	 * <p>Registered keys: {@code "request"} (canonical),
	 * {@code "queryRequest"} (backward-compat alias).
	 *
	 * <p>Input DATA: collection of integer request IDs.
	 */
	public HashMap<String, Object> getRequest(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonObj.put("WARN", "No DATA field found. Expected a list of request IDs.");
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
			Collection<Integer> reqIDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			
			for (int reqID : reqIDs) {
				HashMap<String, Object> rec = findRequestInfo(reqID);
				if (rec != null) {
					jsonData.add(rec);
				} else {
					HashMap<String, Object> ko = new HashMap<String, Object>();
					ko.put("ID", reqID);
					ko.put("STATUS", "KO");
					jsonData.add(ko);
				}
			}
			jsonObj.put("DATA", jsonData);
			jsonObj.put("CODE", "OK");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getRequest: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}
	
	// Build a uniform JSON-friendly view of a Request. zoneID is the
	// containing zone for pending requests, or -1 once the request has
	// been picked up by a vehicle (vehID is added by the caller in that case).
	private HashMap<String, Object> requestSummary(Request r, String status, int zoneID) {
		HashMap<String, Object> rec = new HashMap<String, Object>();
		rec.put("ID", r.getID());
		rec.put("originZoneID", r.getOriginZone());
		rec.put("destZoneID", r.getDestZone());
		rec.put("originRoadID", ContextCreator.getRoadContext().get(r.getOriginRoad()).getOrigID());
		rec.put("destRoadID", ContextCreator.getRoadContext().get(r.getDestRoad()).getOrigID());
		rec.put("numPeople", r.getNumPeople());
		rec.put("generationTime", r.generationTime);
		rec.put("matchedTime", r.matchedTime);
		rec.put("pickupTime", r.pickupTime);
		rec.put("arriveTime", r.arriveTIme);
		rec.put("maxWaitingTime", r.getMaxWaitingTime());
		rec.put("currentWaitingTime", r.getCurrentWaitingTime());
		rec.put("shareable", r.isShareable());
		rec.put("busRoute", r.getBusRoute());
		rec.put("status", status);
		rec.put("zoneID", zoneID);
		rec.put("STATUS", "OK");
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
				record.put("reqID", entry.getKey());
				record.put("vehID", vehicle == null ? -1 : vehicle.getID());
				record.put("requestState", requestState);
				if (vehicle != null) {
					record.put("state", vehicle.getState());
				}
				record.put("STATUS", vehicle == null ? "KO" : "OK");
				jsonData.add(record);
			}
			jsonObj.put("DATA", jsonData);
			jsonObj.put("CODE", "OK");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getTaxiRequestInfo: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}

	private List<Integer> parseOptionalReqIDs(JSONObject jsonMsg) {
		if (!jsonMsg.containsKey("DATA")) {
			return null;
		}
		ArrayList<Integer> ids = new ArrayList<Integer>();
		Object data = jsonMsg.get("DATA");
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
	 * <p>Input DATA (optional): a single integer zoneID. If omitted,
	 * available taxis across every zone are returned.
	 *
	 * <p>Output DATA: flat list of taxi info entries, each carrying
	 * {@code {ID, zoneID, state, x, y, z, battery, hasEnoughBattery,
	 * passNum}}. The {@code zoneID} on each entry is the zone whose
	 * available-taxi pool the taxi belongs to (i.e. the same key used by
	 * {@code removeAvailableTaxi}).
	 */
	public HashMap<String, Object> getAvailableTaxis(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		try {
			Collection<Zone> zonesToCheck;
			if (!jsonMsg.containsKey("DATA")) {
				zonesToCheck = ContextCreator.getZoneContext().getAll();
			} else {
				int zoneID;
				try {
					zoneID = Integer.parseInt(jsonMsg.get("DATA").toString().trim());
				} catch (NumberFormatException nfe) {
					jsonObj.put("WARN", "DATA must be a zone ID (integer)");
					jsonObj.put("CODE", "KO");
					return jsonObj;
				}
				Zone z = ContextCreator.getZoneContext().get(zoneID);
				if (z == null) {
					jsonObj.put("WARN", "Zone " + zoneID + " not found");
					jsonObj.put("CODE", "KO");
					return jsonObj;
				}
				zonesToCheck = Collections.singletonList(z);
			}
			
			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (Zone z : zonesToCheck) {
				for (ElectricTaxi t : ContextCreator.getVehicleContext().getAvailableTaxisSorted(z.getID())) {
					HashMap<String, Object> rec = new HashMap<String, Object>();
					rec.put("ID", t.getID());
					rec.put("zoneID", z.getID());
					rec.put("state", t.getState());
					Coordinate c = t.getCurrentCoord();
					if (c != null) {
						rec.put("x", c.x);
						rec.put("y", c.y);
						rec.put("z", c.z);
					}
					rec.put("battery", t.getBatteryLevel());
					rec.put("hasEnoughBattery", t.hasEnoughBattery());
					rec.put("passNum", t.getPassNum());
					jsonData.add(rec);
				}
			}
			jsonObj.put("DATA", jsonData);
			jsonObj.put("CODE", "OK");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getAvailableTaxis: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}

	/**
	 * Exhaustively query occupied taxis that are expected to become available
	 * soon.
	 *
	 * <p>A taxi is included when it is on an occupied trip, has exactly one
	 * onboard request, has no queued pickup, and its remaining trip distance from
	 * {@code Vehicle.getDistToTravel()} is less than the requested distance
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
			if (jsonMsg.containsKey("DATA")) {
				Object data = jsonMsg.get("DATA");
				if (data instanceof Map<?, ?>) {
					Map<?, ?> params = (Map<?, ?>) data;
					Object metersValue = firstParamValue(params, "distanceThresholdMeters", "thresholdMeters");
					Object milesValue = firstParamValue(params, "distanceThresholdMiles", "thresholdMiles",
							"distanceThreshold", "threshold");
					if (metersValue != null) {
						Double parsed = parseDouble(metersValue);
						if (parsed == null) {
							jsonObj.put("WARN", "distance threshold in meters must be numeric");
							jsonObj.put("CODE", "KO");
							return jsonObj;
						}
						thresholdMeters = parsed.doubleValue();
					} else if (milesValue != null) {
						Double parsed = parseDouble(milesValue);
						if (parsed == null) {
							jsonObj.put("WARN", "distance threshold in miles must be numeric");
							jsonObj.put("CODE", "KO");
							return jsonObj;
						}
						thresholdMeters = parsed.doubleValue() * MILE_IN_METERS;
					}

					Object zoneValue = firstParamValue(params, "zoneID", "zone", "destZone");
					if (zoneValue != null) {
						zoneFilter = parseInteger(zoneValue);
						if (zoneFilter == null) {
							jsonObj.put("WARN", "zoneID must be an integer");
							jsonObj.put("CODE", "KO");
							return jsonObj;
						}
						if (ContextCreator.getZoneContext().get(zoneFilter.intValue()) == null) {
							jsonObj.put("WARN", "Zone " + zoneFilter + " not found");
							jsonObj.put("CODE", "KO");
							return jsonObj;
						}
					}
				} else {
					Double parsed = parseDouble(data);
					if (parsed == null) {
						jsonObj.put("WARN", "DATA must be a distance threshold in miles or an object");
						jsonObj.put("CODE", "KO");
						return jsonObj;
					}
					thresholdMeters = parsed.doubleValue() * MILE_IN_METERS;
				}
			}
			if (thresholdMeters < 0 || Double.isNaN(thresholdMeters) || Double.isInfinite(thresholdMeters)) {
				jsonObj.put("WARN", "distance threshold must be a finite non-negative number");
				jsonObj.put("CODE", "KO");
				return jsonObj;
			}

			ArrayList<Object> jsonData = new ArrayList<Object>();
			for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
				if (!isAlmostFinishedTaxi(t, thresholdMeters)) {
					continue;
				}
				Request lastRequest = t.getOnBoardRequests().peek();
				int zoneID = lastRequest == null ? t.getDestID() : lastRequest.getDestZone();
				if (zoneFilter != null && zoneID != zoneFilter.intValue()) {
					continue;
				}
				if (ContextCreator.getZoneContext().get(zoneID) == null) {
					continue;
				}
				HashMap<String, Object> rec = new HashMap<String, Object>();
				rec.put("ID", t.getID());
				rec.put("zoneID", zoneID);
				rec.put("state", t.getState());
				rec.put("destZoneID", t.getDestID());
				rec.put("destRoadID", roadOrigIDOrNull(t.getDestRoad()));
				addRemainingDistanceFields(rec, t);
				if (lastRequest != null) {
					rec.put("reqID", lastRequest.getID());
					rec.put("originZoneID", lastRequest.getOriginZone());
					rec.put("requestDestZoneID", lastRequest.getDestZone());
					rec.put("numPeople", lastRequest.getNumPeople());
				}
				Coordinate c = t.getCurrentCoord();
				if (c != null) {
					rec.put("x", c.x);
					rec.put("y", c.y);
					rec.put("z", c.z);
				}
				rec.put("battery", t.getBatteryLevel());
				rec.put("passNum", t.getPassNum());
				jsonData.add(rec);
			}
			jsonObj.put("DATA", jsonData);
			jsonObj.put("distanceThreshold", thresholdMeters);
			jsonObj.put("distanceThresholdMiles", thresholdMeters / MILE_IN_METERS);
			jsonObj.put("CODE", "OK");
			return jsonObj;
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing getAlmostFinishedTaxis: " + e.toString());
			jsonObj.put("CODE", "KO");
			return jsonObj;
		}
	}

	private boolean isAlmostFinishedTaxi(ElectricTaxi taxi, double thresholdMeters) {
		if (taxi == null || taxi.getState() != Vehicle.OCCUPIED_TRIP) return false;
		if (!taxi.getToBoardRequests().isEmpty() || taxi.getOnBoardRequests().size() != 1) return false;
		double remainingDistance = taxi.getDistToTravel();
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
	
	private HashMap<String, Object> findRequestInfo(int reqID) {
		// Pending in zones
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			for (Request r : z.getTaxiRequestQueue()) {
				if (r.getID() == reqID) return requestSummary(r, "pending_taxi", z.getID());
			}
			for (Queue<Request> sq : z.getSharableRequestForTaxi().values()) {
				for (Request r : sq) {
					if (r.getID() == reqID) return requestSummary(r, "pending_taxi_sharable", z.getID());
				}
			}
			for (Request r : z.getToAddTaxiRequestQueue()) {
				if (r.getID() == reqID) return requestSummary(r, "pending_taxi_toAdd", z.getID());
			}
			for (Request r : z.getBusRequestQueue()) {
				if (r.getID() == reqID) return requestSummary(r, "pending_bus", z.getID());
			}
			for (Request r : z.getToAddBusRequestQueue()) {
				if (r.getID() == reqID) return requestSummary(r, "pending_bus_toAdd", z.getID());
			}
		}
		// Active taxis (matched, en route to pickup, or carrying passengers)
		for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
			for (Request r : t.getToBoardRequests()) {
				if (r.getID() == reqID) {
					HashMap<String, Object> rec = requestSummary(r, "matched_to_taxi", -1);
					rec.put("vehID", t.getID());
					return rec;
				}
			}
			for (Request r : t.getOnBoardRequests()) {
				if (r.getID() == reqID) {
					HashMap<String, Object> rec = requestSummary(r, "on_board_taxi", -1);
					rec.put("vehID", t.getID());
					return rec;
				}
			}
		}
		// Active buses
		for (ElectricBus b : ContextCreator.getVehicleContext().getBuses()) {
			for (Queue<Request> sq : b.getToBoardRequests()) {
				for (Request r : sq) {
					if (r.getID() == reqID) {
						HashMap<String, Object> rec = requestSummary(r, "matched_to_bus", -1);
						rec.put("vehID", b.getID());
						return rec;
					}
				}
			}
			for (Queue<Request> sq : b.getOnBoardRequests()) {
				for (Request r : sq) {
					if (r.getID() == reqID) {
						HashMap<String, Object> rec = requestSummary(r, "on_board_bus", -1);
						rec.put("vehID", b.getID());
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
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.bus_schedule.getRouteIDs());
			jsonObj.put("orig_id", ContextCreator.bus_schedule.getRouteNames());
			return jsonObj;
		}
		
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
		    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
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
					record2.put("routeID", rID);
					record2.put("busIDs", busIDs);
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			return jsonObj;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    jsonObj.put("CODE", "KO");
		    return jsonObj;
		}
	}
}
