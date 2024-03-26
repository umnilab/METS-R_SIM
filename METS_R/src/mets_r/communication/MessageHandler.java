package mets_r.communication;

import org.json.simple.JSONObject;

public abstract class MessageHandler {
	protected int count; // Number of received messages
	
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		count ++;
		String message = "OK";
		return message;
	}
	
	public int getCount() {
		return count;
	}
	
}
