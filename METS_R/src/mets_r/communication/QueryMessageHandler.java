package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import org.json.simple.JSONObject;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.Vehicle;
import mets_r.communication.MessageClass.*;

public class QueryMessageHandler extends MessageHandler {
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		String answer = null; // default message
		if(msgType.equals("vehicle")) {
			answer = getVehicle(jsonMsg);
		}
		else if(msgType.equals("coSimVehicle")) {
			answer = getCoSimVehicle(jsonMsg);
		}
		else if(msgType.equals("taxi")) {
			answer = getTaxi(jsonMsg);
		}
		else if(msgType.equals("bus")) {
			answer = getBus(jsonMsg);
		}
		else if(msgType.equals("road")) {
			answer = getRoad(jsonMsg);
		}
		else if(msgType.equals("zone")) {
			answer = getZone(jsonMsg);
		}
		else if(msgType.equals("signal")) {
			answer = getSignal(jsonMsg);
		}
		else if(msgType.equals("chargingStation")) {
			answer = getChargingStation(jsonMsg);
		}
		count++;
		return answer;
	}
	
	public String getVehicle(JSONObject jsonMsg) {
		// vid, vtype, x, y, bearing, acc, speed, currRoad, currLane, o, d, oroad, droad, roadlists
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("TYPE", "ANS_vehicle");
			jsonObj.put("public_vids", ContextCreator.getVehicleContext().getPublicVehicleIDList());
			jsonObj.put("private_vids", ContextCreator.getVehicleContext().getPrivateVehicleIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDVehTypeTran>> collectionType = new TypeToken<Collection<VehIDVehTypeTran>>() {};
			Collection<VehIDVehTypeTran> vehIDVehTypeTrans = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			jsonObj.put("TYPE", "ANS_vehicle");
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
					record2.put("bearing", vehicle.getBearing());
					record2.put("acc", vehicle.currentAcc());
					record2.put("speed", vehicle.currentSpeed());
					// if vehicle is on road
					if(vehicle.isOnLane()) {
						record2.put("road", vehicle.getRoad().getID());
						record2.put("lane", vehicle.getLane().getID());
						record2.put("dist", vehicle.getDistance());
					}
					jsonData.add(record2);
				}
				else {
					jsonData.add("KO");
				}
			}
			jsonObj.put("DATA", jsonData);
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query" + e.toString());
		    return "KO";
		}
	    
	}
	
	// 0. Mapping CARLA ROAD to METS-R Road (coSimRoads, id: CARLA/SUMO road ID, value: METS-R road)
	// 1. Get in road vehicle
	// 2. Send the vehicle id in two lists
	public String getCoSimVehicle(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("TYPE", "ANS_coSimVehicle");
		
		List<Integer> vehicleIDList = new ArrayList<Integer>();
		List<Boolean> vehicleTypeList = new ArrayList<Boolean>();
		
		for(Road r: ContextCreator.coSimRoads.values()) {
			Vehicle v = r.firstVehicle();
			while(v != null) {
				Vehicle nextVehicle = v.macroTrailing();
				if(v.getVehicleClass() == Vehicle.EV || v.getVehicleClass() == Vehicle.GV) { // private vehicle
					vehicleIDList.add(ContextCreator.getVehicleContext().getPrivateVID(v.getID()));
					vehicleTypeList.add(true);
				}
				else { // public vehicle
					vehicleIDList.add(v.getID());
					vehicleTypeList.add(false);
				}
				v = nextVehicle;
			}
		}
		
		jsonObj.put("vid_list", vehicleIDList); // ID for private vehicles
		jsonObj.put("vtype_list", vehicleTypeList); // ID for public vehicles
		
		String answer = JSONObject.toJSONString(jsonObj);
		return answer;
	}
	
	public String getBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("TYPE", "ANS_bus");
			jsonObj.put("id_list", ContextCreator.getVehicleContext().getBusIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    jsonObj.put("TYPE", "ANS_bus");
		    
		    for(int id: IDs) {
		    	ElectricBus bus = ContextCreator.getVehicleContext().getBus(id);
				if(bus != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", bus.getID());
					record2.put("route", bus.getRouteID());
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
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    return "KO";
		}		
	}
	
	public String getTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("TYPE", "ANS_taxi");
			jsonObj.put("id_list", ContextCreator.getVehicleContext().getTaxiIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    jsonObj.put("TYPE", "ANS_taxi");
		    
		    for(int id: IDs) {
		    	ElectricTaxi taxi = ContextCreator.getVehicleContext().getTaxi(id);
				if (taxi != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", taxi.getID());
					record2.put("state", taxi.getState());
					record2.put("x", taxi.getCurrentCoord().x);
					record2.put("y", taxi.getCurrentCoord().y);
					record2.put("origin", taxi.getOriginID());
					record2.put("dest", taxi.getDestID());
					record2.put("pass_num", taxi.getNumPeople());
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    return "KO";
		}
	}
	
	public String getRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("TYPE", "ANS_road");
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    jsonObj.put("TYPE", "ANS_road");
		    
		    
		    for(int id: IDs) {
		    	Road road = ContextCreator.getRoadContext().get(id);
				if (road != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("TYPE", "ANS_road");
					record2.put("ID", road.getID());
					record2.put("r_type", road.getRoadType());
					record2.put("num_veh", road.getVehicleNum());
					record2.put("speed_limit", road.getSpeedLimit());
					record2.put("avg_travel_time", road.getTravelTime());
					record2.put("length", road.getLength());
					record2.put("energy_consumed", road.getTotalEnergy());
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    return "KO";
		}
	}
	
	public String getZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("TYPE", "ANS_zone");
			jsonObj.put("id_list", ContextCreator.getZoneContext().getIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    jsonObj.put("TYPE", "ANS_zone");
		    
		    for(int id: IDs) {
		    	Zone zone = ContextCreator.getZoneContext().get(id);
				if (zone != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", zone.getID());
					record2.put("z_type", zone.getZoneType());
					record2.put("taxi_demand", zone.getTaxiPassengerNum());
					record2.put("bus_demand", zone.getBusPassengerNum());
					record2.put("veh_stock", zone.getVehicleStock());
					record2.put("x", zone.getCoord().x);
					record2.put("y", zone.getCoord().y);
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    return "KO";
		}
	}
	
	public String getSignal(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("TYPE", "ANS_signal");
			jsonObj.put("id_list", ContextCreator.getSignalContext().getIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    jsonObj.put("TYPE", "ANS_signal");
		    
		    for(int id: IDs) {
		    	Signal signal = ContextCreator.getSignalContext().get(id);
				if (signal != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", signal.getID());
					record2.put("state", signal.getState());
					record2.put("nex_state", signal.getNextState());
					record2.put("next_update_time", signal.getNextUpdateTick());
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    return "KO";
		}
	}
	
	public String getChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonObj.put("TYPE", "ANS_chargingStation");
			jsonObj.put("id_list", ContextCreator.getChargingStationContext().getIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
		    Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
		    ArrayList<Object> jsonData = new ArrayList<Object>();
		    jsonObj.put("TYPE", "ANS_chargingStation");
		    
		    for(int id: IDs) {
		    	ChargingStation cs = ContextCreator.getChargingStationContext().get(id);
				if (cs != null) {
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", cs.getID());
					record2.put("num_available_charger", cs.capacity());	
					record2.put("x", cs.getCoord().x);
					record2.put("y", cs.getCoord().y);
					jsonData.add(record2);
				}
				else jsonData.add("KO");
		    }
			jsonObj.put("DATA", jsonData);
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		catch (Exception e) {
		    // Log error and return KO in case of exception
		    ContextCreator.logger.error("Error processing query: " + e.toString());
		    return "KO";
		}
	}
}
