package mets_r.communication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.json.simple.JSONObject;

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
		if(!jsonMsg.containsKey("ID")) {
			jsonObj.put("TYPE", "ANS_vehicle");
			jsonObj.put("public_vids", ContextCreator.getVehicleContext().getPublicVehicleIDList());
			jsonObj.put("private_vids", ContextCreator.getVehicleContext().getPrivateVehicleIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
			
		int id = ((Long) jsonMsg.get("ID")).intValue();
		if(jsonMsg.containsKey("PRV")) {
			Vehicle vehicle;
			if((Boolean) jsonMsg.get("PRV")) {
				vehicle = ContextCreator.getVehicleContext().getPrivateVehicle(id);
			}
			else {
				vehicle = ContextCreator.getVehicleContext().getPublicVehicle(id);
			}
			if(vehicle != null) {
				Coordinate coord;
				if(jsonMsg.containsKey("TRAN") && (Boolean) jsonMsg.get("TRAN")) {
					coord= vehicle.getCurrentCoord(SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
				}
				else {
					coord= vehicle.getCurrentCoord();
				}
				jsonObj.put("TYPE", "ANS_vehicle");
				jsonObj.put("ID", vehicle.getID());
				jsonObj.put("v_type", vehicle.getVehicleClass());
				jsonObj.put("state", vehicle.getState());
				jsonObj.put("x", coord.x);
				jsonObj.put("y", coord.y);
				jsonObj.put("bearing", vehicle.getBearing());
				jsonObj.put("acc", vehicle.currentAcc());
				jsonObj.put("speed", vehicle.currentSpeed());
				jsonObj.put("origin", vehicle.getOriginID());
				jsonObj.put("dest", vehicle.getDestID());
				jsonObj.put("on_road", vehicle.isOnRoad());
				jsonObj.put("on_lane", vehicle.isOnLane());
				// if vehicle is on road
				if(vehicle.isOnLane()) {
//					jsonObj.put("current_route", vehicle.getRoute());
					jsonObj.put("road", vehicle.getRoad().getID());
					jsonObj.put("lane", vehicle.getLane().getID());
					jsonObj.put("speed_limit", vehicle.getRoad().getSpeedLimit());
					jsonObj.put("dist", vehicle.getDistance());
				}
				
				String answer = JSONObject.toJSONString(jsonObj);
				return answer;
			}
		}
	    return "KO";
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
	
	public String getPrivateVehicle(JSONObject jsonMsg) {
		// vid, vtype, x, y, bearing, acc, speed, currRoad, currLane, o, d, oroad, droad, roadlists
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("ID")) {
			jsonObj.put("TYPE", "ANS_privateVehicle");
			jsonObj.put("id_list", ContextCreator.getVehicleContext().getPrivateVehicleIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
			
		int id = ((Long) jsonMsg.get("ID")).intValue();
		Vehicle vehicle = ContextCreator.getVehicleContext().getPrivateVehicle(id);

		if(vehicle != null) {
			jsonObj.put("TYPE", "ANS_privateVehicle");
			jsonObj.put("ID", id); // Note here the id should not be vehicle.getID()
			jsonObj.put("v_type", vehicle.getVehicleClass());
			jsonObj.put("state", vehicle.getState());
			jsonObj.put("x", vehicle.getCurrentCoord().x);
			jsonObj.put("y", vehicle.getCurrentCoord().y);
			jsonObj.put("bearing", vehicle.getBearing());
			jsonObj.put("acc", vehicle.currentAcc());
			jsonObj.put("speed", vehicle.currentSpeed());
			jsonObj.put("origin", vehicle.getOriginID());
			jsonObj.put("dest", vehicle.getDestID());
			jsonObj.put("on_road", vehicle.isOnRoad());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		else return "KO";
	}
	
	public String getBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("ID")) {
			jsonObj.put("TYPE", "ANS_bus");
			jsonObj.put("id_list", ContextCreator.getVehicleContext().getBusIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		int id = ((Long) jsonMsg.get("ID")).intValue();
		ElectricBus bus = ContextCreator.getVehicleContext().getBus(id);
		if(bus != null) {
			jsonObj.put("TYPE", "ANS_bus");
			jsonObj.put("ID", bus.getID());
			jsonObj.put("route", bus.getRouteID());
			jsonObj.put("current_stop",bus.getCurrentStop());
			jsonObj.put("pass_num", bus.getPassNum());
			jsonObj.put("battery_state", bus.getBatteryLevel());	
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		else return "KO";
	}
	
	public String getTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("ID")) {
			jsonObj.put("TYPE", "ANS_taxi");
			jsonObj.put("id_list", ContextCreator.getVehicleContext().getTaxiIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		int id = ((Long) jsonMsg.get("ID")).intValue();
		ElectricTaxi taxi = ContextCreator.getVehicleContext().getTaxi(id);
		if (taxi != null) {
			jsonObj.put("TYPE", "ANS_taxi");
			jsonObj.put("ID", taxi.getID());
			jsonObj.put("state", taxi.getState());
			jsonObj.put("x", taxi.getCurrentCoord().x);
			jsonObj.put("y", taxi.getCurrentCoord().y);
			jsonObj.put("origin", taxi.getOriginID());
			jsonObj.put("dest", taxi.getDestID());
			jsonObj.put("pass_num", taxi.getNumPeople());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		else return "KO";
	}
	
	public String getRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("ID")) {
			jsonObj.put("TYPE", "ANS_road");
			jsonObj.put("id_list", ContextCreator.getRoadContext().getIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		int id = ((Long) jsonMsg.get("ID")).intValue();
		Road road = ContextCreator.getRoadContext().get(id);
		if (road != null) {
			jsonObj.put("TYPE", "ANS_road");
			jsonObj.put("ID", road.getID());
			jsonObj.put("r_type", road.getRoadType());
			jsonObj.put("num_veh", road.getVehicleNum());
			jsonObj.put("speed_limit", road.getSpeedLimit());
			jsonObj.put("avg_travel_time", road.getTravelTime());
			jsonObj.put("length", road.getLength());
			jsonObj.put("energy_consumed", road.getTotalEnergy());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		else return "KO";
	}
	
	public String getZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("ID")) {
			jsonObj.put("TYPE", "ANS_zone");
			jsonObj.put("id_list", ContextCreator.getZoneContext().getIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		int id = ((Long) jsonMsg.get("ID")).intValue();
		Zone zone = ContextCreator.getZoneContext().get(id);
		if (zone != null) {
			jsonObj.put("TYPE", "ANS_zone");
			jsonObj.put("ID", zone.getID());
			jsonObj.put("z_type", zone.getZoneType());
			jsonObj.put("taxi_demand", zone.getTaxiPassengerNum());
			jsonObj.put("bus_demand", zone.getBusPassengerNum());
			jsonObj.put("veh_stock", zone.getVehicleStock());
			jsonObj.put("x", zone.getCoord().x);
			jsonObj.put("y", zone.getCoord().y);
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		else return "KO";
	}
	
	public String getSignal(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("ID")) {
			jsonObj.put("TYPE", "ANS_signal");
			jsonObj.put("id_list", ContextCreator.getSignalContext().getIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		int id = ((Long) jsonMsg.get("ID")).intValue();
		Signal signal = ContextCreator.getSignalContext().get(id);
		if (signal != null) {
			jsonObj.put("TYPE", "ANS_signal");
			jsonObj.put("ID", signal.getID());
			jsonObj.put("state", signal.getState());
			jsonObj.put("nex_state", signal.getNextState());
			jsonObj.put("next_update_time", signal.getNextUpdateTick());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		else return "KO";
	}
	
	public String getChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("ID")) {
			jsonObj.put("TYPE", "ANS_chargingStation");
			jsonObj.put("id_list", ContextCreator.getChargingStationContext().getIDList());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		int id = ((Long) jsonMsg.get("ID")).intValue();
		ChargingStation cs = ContextCreator.getChargingStationContext().get(id);
		if (cs != null) {
			jsonObj.put("TYPE", "ANS_chargingStation");	
			jsonObj.put("ID", cs.getID());
			jsonObj.put("num_available_charger", cs.capacity());	
			jsonObj.put("x", cs.getCoord().x);
			jsonObj.put("y", cs.getCoord().y);
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		else return "KO";
	}
}
