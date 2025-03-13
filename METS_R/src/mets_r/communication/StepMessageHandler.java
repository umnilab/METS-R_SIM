package mets_r.communication;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;

public class StepMessageHandler extends MessageHandler{
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		int tick = ((Long) jsonMsg.get("TICK")).intValue();
		int step_num = ((Long) jsonMsg.get("NUM")).intValue();
		step_num = Math.max(step_num, 1);
		if(tick == ContextCreator.getCurrentTick()) {
			ContextCreator.waitNextStepCommand = step_num;
			return "OK";
		}
		return "KO";
	}
}
