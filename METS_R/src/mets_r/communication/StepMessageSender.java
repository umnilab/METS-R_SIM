package mets_r.communication;

import java.io.IOException;
import java.util.HashMap;

import org.eclipse.jetty.websocket.api.Session;
import org.json.simple.JSONObject;

public class StepMessageSender extends MessageSender{
	public void sendMessage(Session session, int tick) throws IOException{
		HashMap<String, Object> stepMsg = new HashMap<String, Object>();
		stepMsg.put("messageType", "step");
		stepMsg.put("status", "ok");
		stepMsg.put("tick", tick);
		String message = JSONObject.toJSONString(stepMsg);
		super.sendMessage(session, message);
	}

}
