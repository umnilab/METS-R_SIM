package mets_r.communication;

import java.util.HashMap;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;
import mets_r.ContextCreator.StepCommandResult;

public class StepMessageHandler extends MessageHandler {
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		int requestTick = ((Number) jsonMsg.get("tick")).intValue();
		int stepNum = ((Number) jsonMsg.get("tickCount")).intValue();
		stepNum = Math.max(stepNum, 1);

		StepCommandResult stepCommand = ContextCreator.setNextStepCommand(requestTick, stepNum);
		int currentTick = stepCommand.currentTick;

		HashMap<String, Object> ans = new HashMap<String, Object>();
		ans.put("messageType", "step");
		ans.put("tick", currentTick);
		ans.put("requestTick", requestTick);
		ans.put("tickCount", stepNum);

		if (stepCommand.accepted) {
			ans.put("status", "ok");
			ans.put("acceptedTickCount", stepCommand.acceptedStepNum);
			ans.put("targetTick", stepCommand.targetTick);
		} else {
			ans.put("status", "error");
			ans.put("errorCode", "TICK_MISMATCH");
			ans.put("message", "Step tick mismatch");
		}

		return JSONObject.toJSONString(ans);
	}
}
