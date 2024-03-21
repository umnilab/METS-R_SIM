package mets_r.communication;

import org.json.simple.JSONObject;

public class QueryMessageHandler extends MessageHandler {
	@Override
	public void handleMessage(String msgType, JSONObject jsonMsg) {
		long startTime = System.currentTimeMillis();
		count++;
		time += System.currentTimeMillis() - startTime;
	}
	
	public void getVehicle(JSONObject jsonMsg) {
		
	}
	
	public void getBus(JSONObject jsonMsg) {
		
	}
	
	public void getTaxi(JSONObject jsonMsg) {
		
	}
	
	public void getRoad(JSONObject jsonMsg) {
		
	}
	
	public void getZone(JSONObject jsonMsg) {
		
	}
	
	public void getSignal(JSONObject jsonMsg) {
		
	}
	
	public void getChargingStation(JSONObject jsonMsg) {
		
	}
	
	
	
	
	
}
