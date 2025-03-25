package mets_r.communication;

import java.io.IOException;
import java.util.HashMap;
import org.eclipse.jetty.websocket.api.Session;
import org.json.simple.JSONObject;

public class AnswerMessageSender extends MessageSender{
	// Format message to inform that the simulation has completed initialization
	public void sendReadyMessage(Session session) throws IOException{
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("TYPE", "ANS_ready");
		String message = JSONObject.toJSONString(jsonObj);
		super.sendMessage(session, message);
	}
	
	public void sendStopMessage(Session session) throws IOException{
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("TYPE", "CTRL_end");
		jsonObj.put("CODE", "OK");
		String message = JSONObject.toJSONString(jsonObj);
		super.sendMessage(session, message);
	}
}
