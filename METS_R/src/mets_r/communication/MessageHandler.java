package mets_r.communication;

import java.util.HashMap;
import java.util.Map;

import org.json.simple.JSONObject;

public abstract class MessageHandler {
	// Map to associate message types with their corresponding handler methods
    protected final Map<String, CustomizableHandler> messageHandlers = new HashMap<>();;
	protected int count; // Number of received messages
	
	@FunctionalInterface
    interface CustomizableHandler {
		HashMap<String, Object> handle(JSONObject jsonMsg);
	}
	
	// The refactored handleMessage method using the command map
    public abstract String handleMessage(String msgType, JSONObject jsonMsg);

	public int getCount() {
		return count;
	}
	
}
