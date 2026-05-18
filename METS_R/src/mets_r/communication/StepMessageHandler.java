package mets_r.communication;

import java.util.HashMap;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;

public class StepMessageHandler extends MessageHandler {
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		int requestTick = ((Long) jsonMsg.get("TICK")).intValue();
		int stepNum = ((Long) jsonMsg.get("NUM")).intValue();
		stepNum = Math.max(stepNum, 1);

		int currentTick = ContextCreator.getCurrentTick();

		HashMap<String, Object> ans = new HashMap<String, Object>();
		ans.put("TYPE", "STEP");
		ans.put("TICK", currentTick);
		ans.put("REQUEST_TICK", requestTick);
		ans.put("NUM", stepNum);

		if (requestTick == currentTick) {
			ContextCreator.waitNextStepCommand = stepNum;
			ans.put("CODE", "OK");
			ans.put("TARGET_TICK", currentTick + stepNum);
		} else {
			ans.put("CODE", "KO");
			ans.put("MSG", "STEP tick mismatch");
		}

		return JSONObject.toJSONString(ans);
	}
}