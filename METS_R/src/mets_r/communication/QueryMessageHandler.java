package mets_r.communication;

import java.util.HashMap;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;
import mets_r.mobility.Vehicle;

public class QueryMessageHandler extends MessageHandler {
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		long startTime = System.currentTimeMillis();
		String answer = "KO"; // default message
		if(msgType.equals("vehicle")) {
			answer = getVehicle(jsonMsg);
		}
		count++;
		time += System.currentTimeMillis() - startTime;
		return answer;
	}
	
	public String getVehicle(JSONObject jsonMsg) {
		// vid, vtype, x, y, bearing, acc, speed, currRoad, currLane, o, d, oroad, droad, roadlists
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		int vid = (int) jsonMsg.get("vid");
		Vehicle vehicle = (Vehicle) ContextCreator.getVehicleContext().getTaxi(vid);
		if(vehicle == null) {
			vehicle = (Vehicle)  ContextCreator.getVehicleContext().getBus(vid);
		}
		if(vehicle != null) {
			jsonObj.put("MSG_TYPE", "ANSWER_vehicle");
			jsonObj.put("vid", vehicle.getID());
			jsonObj.put("vType", vehicle.getVehicleClass());
			jsonObj.put("state", vehicle.getState());
			jsonObj.put("x", vehicle.getCurrentCoord().x);
			jsonObj.put("y", vehicle.getCurrentCoord().y);
			jsonObj.put("bearing", vehicle.getBearing());
			jsonObj.put("acc", vehicle.currentAcc());
			jsonObj.put("acc", vehicle.currentSpeed());
			jsonObj.put("road", vehicle.getRoad());
			jsonObj.put("lane", vehicle.getLane());
			jsonObj.put("origin", vehicle.getOriginID());
			jsonObj.put("dest", vehicle.getDestID());
			jsonObj.put("route", vehicle.getRoute());
			String answer = JSONObject.toJSONString(jsonObj);
			return answer;
		}
		else return "KO";
	}
	
	public void getBus(JSONObject jsonMsg) {
		// Ruichen, vid, route, currentStop, passNum, batteryState
		
	}
	
	public void getTaxi(JSONObject jsonMsg) {
		// Zengxiang
	}
	
	public void getRoad(JSONObject jsonMsg) {
		// Ruichen, numVeh, speedlimit, avg_travel_time, length, energy_consumed
	}
	
	public void getZone(JSONObject jsonMsg) {
		// Zengxiang
	}
	
	public void getSignal(JSONObject jsonMsg) {
		// Zengxiang
	}
	
	public void getChargingStation(JSONObject jsonMsg) {
		// Ruichen, num of available charger, location
	}
}
