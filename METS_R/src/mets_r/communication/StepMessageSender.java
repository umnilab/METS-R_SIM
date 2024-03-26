package mets_r.communication;

import java.io.IOException;
import java.util.HashMap;

import org.eclipse.jetty.websocket.api.Session;
import org.json.simple.JSONObject;

public class StepMessageSender extends MessageSender{
	private HashMap<String, Object> stepMsg =  new HashMap<String, Object>();
	
	public StepMessageSender() {
		stepMsg = new HashMap<String, Object>();
		stepMsg.put("TYPE", "STEP");
	}
	
	public void sendMessage(Session session, int tick) throws IOException{
		// Prepare the tick message
		stepMsg.put("TICK", tick);
		String message = JSONObject.toJSONString(stepMsg);
		super.sendMessage(session, message);
	}

}
