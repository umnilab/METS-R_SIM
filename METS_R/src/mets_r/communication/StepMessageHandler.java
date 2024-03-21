package mets_r.communication;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;

public class StepMessageHandler extends MessageHandler{
	@Override
	public void handleMessage(String msgType, JSONObject jsonMsg) {
		int tick = (int) jsonMsg.get("TICK");
		if(tick == ContextCreator.getCurrentTick());
			ContextCreator.receivedNextStepCommand = true;
	}
}
