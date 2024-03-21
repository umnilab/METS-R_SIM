package mets_r.communication;

import java.io.IOException;
import java.util.HashMap;

import org.eclipse.jetty.websocket.api.Session;
import org.json.simple.JSONObject;

public class StepMessageSender extends MessageSender{
	public void sendMessage(Session session, int tick) throws IOException{
		// Prepare the tick message
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("MSG_TYPE", "STEP");
		jsonObj.put("TICK", tick);
		String message = JSONObject.toJSONString(jsonObj);
		super.sendMessage(session, message);
	}

}
