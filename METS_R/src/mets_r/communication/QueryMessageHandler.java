package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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
import mets_r.facility.Node;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.Vehicle;
import mets_r.routing.RouteContext;
import mets_r.communication.MessageClass.*;

public class QueryMessageHandler extends MessageHandler {
	private Random rand_route = new Random(GlobalVariables.RandomGenerator.nextInt());
	
	public QueryMessageHandler() {
        messageHandlers.put("vehicle", this::getVehicle);
        messageHandlers.put("coSimVehicle", this::getCoSimVehicle);
        messageHandlers.put("taxi", this::getTaxi);
        messageHandlers.put("bus", this::getBus);
        messageHandlers.put("road", this::getRoad);
        messageHandlers.put("zone", this::getZone);
        messageHandlers.put("signal", this::getSignal);
        messageHandlers.put("signalGroup", this::getSignalGroup);
        messageHandlers.put("signalForConnection", this::getSignalForConnection);
        messageHandlers.put("chargingStation", this::getChargingStation);
        messageHandlers.put("routesBwCoords", this::getRoutesBwCoords);
        messageHandlers.put("routesBwRoads", this::getRoutesBwRoads);
        messageHandlers.put("multiRoutesBwCoords", this::getKRoutesBwCoords);
        messageHandlers.put("multiRoutesBwRoads", this::getKRoutesBwRoads);
        messageHandlers.put("edgeWeight", this::getEdgeWeight);
        messageHandlers.put("busRoute", this::getBusRoute);
        messageHandlers.put("busWithRoute", this::getBusWithRoute);
        messageHandlers.put("centerLine", this::getCenterLine);  
    }
	
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		CustomizableHandler handler = messageHandlers.get(msgType);
    	HashMap<String, Object> jsonAns = (handler != null) ? handler.handle(jsonMsg) : null;
    	jsonAns.put("TYPE", "ANS_" + msgType);
    	count++;
        return JSONObject.toJSONString(jsonAns);
	}
	
	public HashMap<String, Object> getVehicle(JSONObject jsonMsg) {
		// vid, vtype, x, y, bearing, acc, speed, currRoad, currLane, o, d, oroad, droad, roadlists
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
					Coordinate coord;
					if(record.transformCoord) {
						coord= vehicle.getCurrentCoord(SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
					}
					else {
						coord= vehicle.getCurrentCoord();
					}			
					record2.put("ID", vehicle.getID());
					record2.put("v_type", vehicle.getVehicleClass());
					record2.put("state", vehicle.getState());
					record2.put("x", coord.x);
					record2.put("y", coord.y);
					record2.put("z", coord.z);
					record2.put("bearing", vehicle.getBearing());
					record2.put("acc", vehicle.currentAcc());
					record2.put("speed", vehicle.currentSpeed());
					// if vehicle is on road
					if(vehicle.isOnRoad()) {
						record2.put("road", vehicle.getRoad().getOrigID());
						if(vehicle.isOnLane()) {
							record2.put("lane", vehicle.getLane().getIndex());
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
	
	// 0. Mapping CARLA ROAD to METS-R Road (coSimRoads, id: CARLA/SUMO road ID, value: METS-R road)
	// 1. Get in road vehicle
	// 2. Send the vehicle id in two lists
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
					record2.put("route", ContextCreator.bus_schedule.getRouteName(bus.getRouteID()));
					record2.put("current_stop",bus.getCurrentStop());
					record2.put("pass_num", bus.getPassNum());
					record2.put("battery_state", bus.getBatteryLevel());
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
					record2.put("origin", taxi.getOriginID());
					record2.put("dest", taxi.getDestID());
					record2.put("pass_num", taxi.getPassNum());
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
	
	public HashMap<String, Object> getRoad(JSONObject jsonMsg) {
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
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", road.getOrigID());
					record2.put("r_type", road.getRoadType());
					record2.put("num_veh", road.getVehicleNum());
					record2.put("speed_limit", road.getSpeedLimit());
					record2.put("avg_travel_time", road.getTravelTime());
					record2.put("length", road.getLength());
					record2.put("energy_consumed", road.getTotalEnergy());
					record2.put("down_stream_road", road.getDownStreamRoadOrigIDs());
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
	
	public HashMap<String, Object> getZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getZoneContext().getIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    
		    for(int id: IDs) {
		    	Zone zone = ContextCreator.getZoneContext().get(id);
				if (zone != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", zone.getID());
					record2.put("z_type", zone.getZoneType());
					record2.put("taxi_demand", zone.getTaxiRequestNum());
					record2.put("bus_demand", zone.getBusRequestNum());
					record2.put("veh_stock", zone.getVehicleStock());
					Coordinate coord = zone.getCoord();
					record2.put("x", coord.x);
					record2.put("y", coord.y);
					record2.put("z", coord.z);
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
	
	// Query signals based on its origID (in the SUMO xml)
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
	
	// Query signal ID for a connection between two consecutive roads
	// Input: upstream road ID, downstream road ID (using original road IDs)
	// Returns: signal ID, current state, next state, next update tick, phase timing
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
	
	public HashMap<String, Object> getChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("id_list", ContextCreator.getChargingStationContext().getIDList());
			return jsonObj;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    
		    for(int id: IDs) {
		    	ChargingStation cs = ContextCreator.getChargingStationContext().get(id);
				if (cs != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", cs.getID());
					record2.put("l2_charger", cs.numCharger(ChargingStation.L2));
					record2.put("dcfc_charger", cs.numCharger(ChargingStation.L3));
					record2.put("l2_price", cs.getPrice(ChargingStation.L2));
					record2.put("dcfc_price", cs.getPrice(ChargingStation.L3));
					record2.put("bus_charger", cs.numCharger(ChargingStation.BUS));
					record2.put("num_available_l2", cs.capacity(ChargingStation.L2));
					record2.put("num_available_dcfc", cs.capacity(ChargingStation.L3));
					Coordinate coord = cs.getCoord();
					record2.put("x", coord.x);
					record2.put("y", coord.y);
					record2.put("z", coord.z);
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
	
	// Place holders for APIs to be implemented
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
