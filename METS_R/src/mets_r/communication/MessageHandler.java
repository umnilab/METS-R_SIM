package mets_r.communication;

import org.json.simple.JSONObject;

public abstract class MessageHandler {
	protected int count; // Number of received messages
	protected long time; // Time consumed for handle the messages
	
	public void handleMessage(String msgType, JSONObject jsonMsg) {}
	
	public int getCount() {
		return count;
	}
	
	public long getTime() {
		return time;
	}
}
