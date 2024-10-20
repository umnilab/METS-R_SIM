package mets_r.communication;

import java.util.ArrayList;
import java.util.HashMap;

import org.geotools.geometry.jts.JTS;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Vehicle;

public class ControlMessageHandler extends MessageHandler {
	
	@Override
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		String answer = null;
		Boolean flag = false;
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		try {
			if(msgType.equals("teleportVeh")) flag = teleportVeh(jsonMsg, jsonAns);
			else if(msgType.equals("controlVeh")) flag = controlVeh(jsonMsg, jsonAns);
			else if(msgType.equals("enterNextRoad")) flag = enterNextRoad(jsonMsg, jsonAns);
			else if(msgType.equals("routingTaxi")) flag = routingTaxi(jsonMsg, jsonAns);
			else if(msgType.equals("routingBus")) flag = routingBus(jsonMsg, jsonAns);
			else if(msgType.equals("scheduleBus")) flag = scheduleBus(jsonMsg, jsonAns);
			else if(msgType.equals("scheduleTaxi")) flag = sheduleTaxi(jsonMsg, jsonAns);
			else if(msgType.equals("generateTrip")) flag = generateTrip(jsonMsg, jsonAns);
			else if(msgType.equals("setCoSimRoad")) flag = setCoSimRoad(jsonMsg, jsonAns);
			else if(msgType.equals("releaseCosimRoad")) flag = releaseCosimRoad(jsonMsg, jsonAns);
			else if(msgType.equals("reset")) flag = resetSim(jsonMsg, jsonAns);
			else if(msgType.equals("end")) flag = endSim(jsonMsg, jsonAns);
			if(flag) {
				jsonAns.put("TYPE", "CTRL_" + msgType);
				jsonAns.put("CODE", "OK");
			}
			else {
				jsonAns.put("TYPE", "CTRL_" + msgType);
				jsonAns.put("CODE", "KO");
			}
		}catch (Exception e) {
			jsonAns.put("TYPE", "CTRL_" + msgType);
			jsonAns.put("CODE", "KO");
			jsonAns.put("ERR", e.toString());
			
		}
		answer = JSONObject.toJSONString(jsonAns);
		count++;
		return answer;
	}
	
	private boolean resetSim(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
		// Get data
		String property_file = jsonMsg.get("propertyFile").toString();
		// Call the reset function
		ContextCreator.reset(property_file);
		return true;
	}
	
	private boolean endSim(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
		// Call the end function
		ContextCreator.connection.sendStopMessage();
		ContextCreator.end();
		return true;
	}
	
	private boolean setCoSimRoad(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
		// Get data
		String roadID = jsonMsg.get("roadID").toString();
		// Find road with the orig road ID
		for(Road r: ContextCreator.getRoadContext().getAll()) {
			if(r.getOrigID().equals(roadID)) {
				r.setControlType(Road.CoSim);
				// Add road to coSim HashMap in the road context
				ContextCreator.coSimRoads.put(roadID, r);
				return true;
			}
		}
		ContextCreator.logger.warn("Cannot find the road, road ID: " + roadID);
		return false;
	}
	
	private boolean releaseCosimRoad(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
		// Get data
		String roadID = jsonMsg.get("roadID").toString();
		// Find road with the orig road ID
		for(Road r: ContextCreator.getRoadContext().getAll()) {
			if(r.getOrigID().equals(roadID)) {
				r.setControlType(Road.NONE_OF_THE_ABOVE);
				// Add road to coSim HashMap in the road context
				ContextCreator.coSimRoads.remove(roadID);
				return true;
			}
		}
		ContextCreator.logger.warn("Cannot find the road, road ID: " + roadID);
		return false;
	}
	
    private boolean generateTrip(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
    	// Get data
    	int vehID = ((Long) jsonMsg.get("vehID")).intValue();
    	ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(vehID);
    	if(v != null) {
			if (v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
    			ContextCreator.logger.warn("The private EV: " + vehID + " is currently on the road, maybe there are two trips for the same vehicle that are too close?");
    			return false;
			}
    	}
		else { // If vehicle does not exists, create vehicle
			v = new ElectricVehicle(Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE);
			ContextCreator.getVehicleContext().registerPrivateEV(vehID, v);
		}
		
		// Find the origin and dest zones
		int originID = ((Long) jsonMsg.get("origin")).intValue();
		int destID = ((Long) jsonMsg.get("destination")).intValue();
		Zone originZone = null;
		Zone destZone = null;
		
		if(originID >= 0) {
			originZone = ContextCreator.getZoneContext().get(originID);
			if(originZone == null) {
				ContextCreator.logger.warn("Cannot find the origin with ID: " + originID);
				return false;
			}
		}
		else {
			// randomly select a zone as origin
			originID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM);
			originZone = ContextCreator.getZoneContext().get(originID);
		}
		
		if(destID >= 0) {
			destZone = ContextCreator.getZoneContext().get(destID);
			if(destZone == null) {
				ContextCreator.logger.warn("Cannot find the dest with ID: " + destID);
				return false;
			}
		}
		else {
			// randomly select a zone as destination
			destID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM);
			destZone = ContextCreator.getZoneContext().get(destID);
		}
    			
		// Assign trips
		v.initializePlan(originID, originZone.getCoord(), (int) ContextCreator.getCurrentTick());
		v.addPlan(destID, destZone.getCoord(), (int) ContextCreator.getNextTick());
		v.setNextPlan();
		v.setState(Vehicle.PRIVATE_TRIP);
		v.departure();
		jsonAns.put("vehID", vehID); // Note this vehID will be different from that obtained by veh.getID() which is generated by ContextCreator.generateAgentID();
		jsonAns.put("origin", originID);
		jsonAns.put("destination",destID);
		return true;
    }
	
	// This can be done at t or t+1, directly change the road properties
	// When perform at t+1 (wait == false), append takes effect immediately
	private boolean teleportVeh(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
		// Get data
		Vehicle veh = null;
		if((Boolean) jsonMsg.get("prv")) {
			veh = ContextCreator.getVehicleContext().getPrivateVehicle(((Long) jsonMsg.get("vehID")).intValue());
		}
		else {
			veh = ContextCreator.getVehicleContext().getPublicVehicle(((Long) jsonMsg.get("vehID")).intValue());
		}
		
		if(veh != null) {
			Road road = ContextCreator.getRoadContext().get(((Long) jsonMsg.get("roadID")).intValue());
			Lane lane = ContextCreator.getLaneContext().get(((Long) jsonMsg.get("laneID")).intValue());
			double dist = (Double) jsonMsg.get("dist");
			double x = (Double) jsonMsg.get("x");
			double y = (Double) jsonMsg.get("y");
			if(road != null && veh != null && lane != null) {
				// Integrity check
				if (lane.getRoad() != road.getID()) return false;
				
				// Transform coordinates if needed 
				if(jsonMsg.containsKey("TRAN") && (Boolean) jsonMsg.get("TRAN")) {
					Coordinate coord = new Coordinate();
					coord.x = x;
					coord.y = y;
					coord.z = 0;
					try {
						JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
						x = coord.x;
						y = coord.y;
					} catch (TransformException e) {
						ContextCreator.logger.error("Coordinates transformation failed, input x: " + x + " y:" + y);
						e.printStackTrace();
					}
				}
				
				// Update its location in the target link and target lane
				return road.insertVehicle(veh, lane, dist, x, y);
			}
		}
		// Send back the fail feedback message
		return false;
	}
	
	// This need to be made at t, and takes effect during t to t+1
	// This essentially alternate the acceleration decisions
	private boolean controlVeh(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
		// Get vehicle
		Vehicle veh = null;
		if((Boolean) jsonMsg.get("prv")) {
			veh = ContextCreator.getVehicleContext().getPrivateVehicle(((Long) jsonMsg.get("vehID")).intValue());
		}
		else {
			veh = ContextCreator.getVehicleContext().getPublicVehicle(((Long) jsonMsg.get("vehID")).intValue());
		}
		double acc = (Double) jsonMsg.get("acc");
		// Register its acceleration
		veh.controlVehicleAcc(acc);
		return true;
	}

	private boolean routingTaxi(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
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
	
	private boolean routingBus(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
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
	
	private boolean scheduleBus(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
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
			ContextCreator.bus_schedule.updateEvent(newhour, newRouteName, newRoutes, newBusNum, newBusGap);
			ContextCreator.receivedNewBusSchedule = true;
			return true;
		}
		return false;
	}
	
	private boolean enterNextRoad(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
		Vehicle veh = null;
		if((Boolean) jsonMsg.get("prv")) {
			veh = ContextCreator.getVehicleContext().getPrivateVehicle(((Long) jsonMsg.get("vehID")).intValue());
		}
		else {
			veh = ContextCreator.getVehicleContext().getPublicVehicle(((Long) jsonMsg.get("vehID")).intValue());
		}
		if(veh != null) {
			return veh.changeRoad();
		}
		else {
			return false;
		}
	}
	
	private boolean sheduleTaxi(JSONObject jsonMsg, HashMap<String, Object> jsonAns) {
		return true;
	}
}
