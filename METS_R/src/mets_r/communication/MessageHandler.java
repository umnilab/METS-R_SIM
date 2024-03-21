package mets_r.communication;

import org.json.simple.JSONObject;

public abstract class MessageHandler {
	protected int count; // Number of received messages
	protected long time; // Time consumed for handle the messages
	
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		long startTime = System.currentTimeMillis();
		count ++;
		String message = "OK";
		time += System.currentTimeMillis() - startTime;
		return message;
	}
	
	public int getCount() {
		return count;
	}
	
	public long getTime() {
		return time;
	}
}
