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
			jsonObj.put("MSG_TYPE", "ANS_TAXIUCB");
			jsonObj.put("OD", OD);
			List<List<Integer>> roadLists = ContextCreator.route_UCB.get(OD);
			jsonObj.put("road_lists", roadLists);
			String line = JSONObject.toJSONString(jsonObj);
			super.sendMessage(session, line);
		}
	}

	// Format the bus candidate routes information as a message string
	public void sendCandidateRoutesForBus(Session session) throws IOException {
		for (String OD :  ContextCreator.route_UCB_bus.keySet()) {
			HashMap<String, Object> jsonObj = new HashMap<String, Object>();
			jsonObj.put("MSG_TYPE", "ANS_BUSUCB");
			jsonObj.put("BOD", OD);
			List<List<Integer>> roadLists = ContextCreator.route_UCB_bus.get(OD);
			jsonObj.put("road_lists", roadLists);
			String line = JSONObject.toJSONString(jsonObj);
			super.sendMessage(session, line);
		}
	}
}
