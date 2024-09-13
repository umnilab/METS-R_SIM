package mets_r.communication;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;

public class StepMessageHandler extends MessageHandler{
	@Override
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		int tick = ((Long) jsonMsg.get("TICK")).intValue();
		ContextCreator.logger.info("TICK " + tick + " Current tick " + ContextCreator.getCurrentTick());
		if(tick == ContextCreator.getCurrentTick()) {
			ContextCreator.receivedNextStepCommand = true;
			return "OK";
		}
		return "KO";
	}
}
