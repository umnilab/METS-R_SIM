package mets_r.communication;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import org.eclipse.jetty.websocket.api.Session;
import org.json.simple.JSONObject;

import mets_r.ContextCreator;

public class AnswerMessageSender extends MessageSender{
	// Format the candidate routes information as a message string
	public void sendCandidateRoutesForTaxi(Session session) throws IOException {
		for (String OD : ContextCreator.route_UCB.keySet()) {
			HashMap<String, Object> jsonObj = new HashMap<String, Object>();
			jsonObj.put("TYPE", "ANS_TaxiUCB");
			jsonObj.put("OD", OD);
			jsonObj.put("SIZE", ContextCreator.route_UCB.size());
			List<List<Integer>> roadLists = ContextCreator.route_UCB.get(OD);
			jsonObj.put("road_lists", roadLists);
			String message = JSONObject.toJSONString(jsonObj);
			super.sendMessage(session, message);
		}
	}

	// Format the bus candidate routes information as a message string
	public void sendCandidateRoutesForBus(Session session) throws IOException {
		for (String OD :  ContextCreator.route_UCB_bus.keySet()) {
			HashMap<String, Object> jsonObj = new HashMap<String, Object>();
			jsonObj.put("TYPE", "ANS_BusUCB");
			jsonObj.put("BOD", OD);
			jsonObj.put("SIZE", ContextCreator.route_UCB_bus.size());
			List<List<Integer>> roadLists = ContextCreator.route_UCB_bus.get(OD);
			jsonObj.put("road_lists", roadLists);
			String message = JSONObject.toJSONString(jsonObj);
			super.sendMessage(session, message);
		}
	}
	
	// Format the message to generate veh information in CARLA, not used
	public void createCarlaVeh(Session session, String vid, double x, double y, double bearing, List<List<Integer>> roadLists) throws IOException{
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("TYPE", "ANS_genVeh");
		jsonObj.put("ID", vid);
		jsonObj.put("x", x);
		jsonObj.put("y", y);
		jsonObj.put("bearing", bearing);
		jsonObj.put("road_list", roadLists);
		String message = JSONObject.toJSONString(jsonObj);
		super.sendMessage(session, message);
	}
	
	// Format the message to remove veh information in CARLA, not used
	public void removeCarlaVeh(Session session, String vid) throws IOException{
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("TYPE", "ANS_removeVeh");
		jsonObj.put("ID", vid);
		String message = JSONObject.toJSONString(jsonObj);
		super.sendMessage(session, message);
	}
	
	// Format message to inform that the simulation has completed initialization
	public void sendReadyMessage(Session session) throws IOException{
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("TYPE", "ANS_ready");
		String message = JSONObject.toJSONString(jsonObj);
		super.sendMessage(session, message);
	}
	
	public void sendStopMessage(Session session) throws IOException{
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("TYPE", "CTRL_stop");
		jsonObj.put("CODE", "OK");
		String message = JSONObject.toJSONString(jsonObj);
		super.sendMessage(session, message);
	}
}
