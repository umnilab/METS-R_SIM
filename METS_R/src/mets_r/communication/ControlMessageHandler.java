package mets_r.communication;

import java.util.ArrayList;
import java.util.HashMap;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import mets_r.ContextCreator;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.mobility.Vehicle;

public class ControlMessageHandler extends MessageHandler {
	
	@Override
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		String answer = "KO";
		Boolean flag = false;
		try {
			if(msgType.equals("teleportVeh")) flag = teleportVeh(jsonMsg);
			if(msgType.equals("controlVeh")) flag = controlVeh(jsonMsg);
			if(msgType.equals("routingTaxi")) flag = routingTaxi(jsonMsg);
			if(msgType.equals("routingBus")) flag = routingBus(jsonMsg);
			if(msgType.equals("scheduleBus")) flag = scheduleBus(jsonMsg);
			if(msgType.equals("scheduleTaxi")) flag = sheduleTaxi(jsonMsg);
			if(flag) {
				HashMap<String, Object> jsonObj = new HashMap<String, Object>();
				jsonObj.put("TYPE", msgType);
				jsonObj.put("CODE", "OK");
				answer = JSONObject.toJSONString(jsonObj);
			}
			else {
				HashMap<String, Object> jsonObj = new HashMap<String, Object>();
				jsonObj.put("TYPE", msgType);
				jsonObj.put("CODE", "KO");
				answer = JSONObject.toJSONString(jsonObj);
			}
		}catch (Exception e) {
			HashMap<String, Object> jsonObj = new HashMap<String, Object>();
			jsonObj.put("TYPE", msgType);
			jsonObj.put("CODE", "KO");
			jsonObj.put("ERR", e.toString());
			answer = JSONObject.toJSONString(jsonObj);
		}
		count++;
		return answer;
	}
	
	// This can be done at t or t+1, directly change the road properties
	// When perform at t+1 (wait == false), append takes effect immediately
	private boolean teleportVeh(JSONObject jsonMsg) {
		// Get data
		Vehicle veh = ContextCreator.getVehicleContext().getVehicle(((Long) jsonMsg.get("vehID")).intValue());
		Road road = ContextCreator.getRoadContext().get(((Long) jsonMsg.get("roadID")).intValue());
		Lane lane = ContextCreator.getLaneContext().get(((Long) jsonMsg.get("laneID")).intValue());
		double dist = (Double) jsonMsg.get("dist");
		double x = (Double) jsonMsg.get("x");
		double y = (Double) jsonMsg.get("y");
		if(road != null && veh != null && lane != null) {
			// integrity check
			if (lane.getRoad() != road.getID()) return false;
			// Update its location in the target link and target lane
			return road.insertVehicle(veh, lane, dist, x, y);
		}
		// Send back the fail feedback message
		return false;
	}
	
	// This need to be made at t, and takes effect during t to t+1
	// This essentially alternate the acceleration decisions
	private boolean controlVeh(JSONObject jsonMsg) {
		// Get vehicle
		Vehicle veh = ContextCreator.getVehicleContext().getVehicle(((Long) jsonMsg.get("vehID")).intValue());
		double acc = (Double) jsonMsg.get("acc");
		// Register its acceleration
		veh.controlVehicleAcc(acc);
		return true;
	}

	private boolean routingTaxi(JSONObject jsonMsg) {
		HashMap<String, Integer> res = new HashMap<String, Integer>();
		ContextCreator.logger.info("Received route result!");
		JSONArray list_OD = (JSONArray) jsonMsg.get("OD");
		JSONArray list_result = (JSONArray) jsonMsg.get("result");
		for (int index = 0; index < list_OD.size(); index++) {
			Long result = (Long) list_result.get(index);
			int result_int = result.intValue();
			String OD = (String) list_OD.get(index);
			res.put(OD, result_int);
		}
		ContextCreator.routeResult_received = res;
		return true;
	}
	
	private boolean routingBus(JSONObject jsonMsg) {
		HashMap<String, Integer> res = new HashMap<String, Integer>();
		ContextCreator.logger.info("Received bus route result!");
		JSONArray list_OD = (JSONArray) jsonMsg.get("BOD");
		JSONArray list_result = (JSONArray) jsonMsg.get("result");
		for (int index = 0; index < list_OD.size(); index++) {
			Long result = (Long) list_result.get(index);
			int result_int = result.intValue();
			String OD = (String) list_OD.get(index);
			res.put(OD, result_int);
		}
		ContextCreator.routeResult_received_bus = res;
		return true;
	}
	
	private boolean scheduleBus(JSONObject jsonMsg) {
		ContextCreator.logger.info("Received bus schedules!");
		JSONArray list_routename = (JSONArray) jsonMsg.get("Bus_routename");
		JSONArray list_route = (JSONArray) jsonMsg.get("Bus_route");
		JSONArray list_gap = (JSONArray) jsonMsg.get("Bus_gap");
		JSONArray list_num = (JSONArray) jsonMsg.get("Bus_num");
		Long hour = Long.valueOf((String) jsonMsg.get("Bus_currenthour"));
		int newhour = hour.intValue();

		if (Connection.prevHour < newhour) { // New schedules
			Connection.prevHour = newhour;
			int array_size = list_num.size();
			ArrayList<Integer> newRouteName = new ArrayList<Integer>(array_size);
			ArrayList<Integer> newBusNum = new ArrayList<Integer>(array_size);
			ArrayList<Integer> newBusGap = new ArrayList<Integer>(array_size);
			ArrayList<ArrayList<Integer>> newRoutes = new ArrayList<ArrayList<Integer>>(array_size);
			for (int index = 0; index < list_num.size(); index++) {
				int bus_num_int = 0;
				if (list_num.get(index) instanceof Number) {
					bus_num_int = ((Number) list_num.get(index)).intValue();
				}
				if (bus_num_int > 0) {
					// Verify the data, the last stop ID should be the same as the first one
					@SuppressWarnings("unchecked")
					ArrayList<Long> route = (ArrayList<Long>) list_route.get(index);
					if (route.get(0).intValue() == route.get(route.size() - 1).intValue()) {
						newBusNum.add(bus_num_int);
						Double gap = (Double) list_gap.get(index);
						int gap_int = gap.intValue(); // in minutes
						newBusGap.add(gap_int);
						Long routename = (Long) list_routename.get(index);
						int list_routename_int = routename.intValue();
						newRouteName.add(list_routename_int);
						int route_size = route.size() - 1;
						ArrayList<Integer> route_int = new ArrayList<Integer>(route_size);
						for (int index_route = 0; index_route < route_size; index_route++) {
							int route_int_i = route.get(index_route).intValue();
							route_int.add(route_int_i);
						}
						newRoutes.add(route_int);
					}
				}
			}
			ContextCreator.busSchedule.updateEvent(newhour, newRouteName, newRoutes, newBusNum, newBusGap);
			ContextCreator.receivedNewBusSchedule = true;
			return true;
		}
		return false;
	}
	
	private boolean sheduleTaxi(JSONObject jsonMsg) {
		return true;
	}
	
	
}
