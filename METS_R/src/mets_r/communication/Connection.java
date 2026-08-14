package mets_r.communication;

import java.io.IOException;
import java.net.InetAddress;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;

/**
 * WebSocket connection for the single public API schema.
 */
@WebSocket
public class Connection {
	private int id;
	private static int COUNTER = 0;

	private Session session;
	private InetAddress ip;
	private QueryMessageHandler queryHandler;
	private StepMessageSender stepSender;
	private AnswerMessageSender answerSender;

	public Connection() {
		this.id = ++Connection.COUNTER;
		this.queryHandler = new QueryMessageHandler();
		this.stepSender = new StepMessageSender();
		this.answerSender = new AnswerMessageSender();
		ContextCreator.logger.info("Connection object created.");
	}

	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		ConnectionManager.activeSession = null;
		ContextCreator.logger.info(statusCode + ": " + reason);
		ContextCreator.connection = null;
		this.session = null;
	}

	@OnWebSocketError
	public void onError(Throwable t) {
		ContextCreator.logger.info(this.id + "-ERR " + t.getMessage());
	}

	@OnWebSocketConnect
	public void onConnect(Session session) {
		if (session == null) return;
		session.setIdleTimeout(0);

		if (ConnectionManager.activeSession == null) {
			ConnectionManager.activeSession = session;
		} else {
			session.close(4000, "Only one connection allowed");
			return;
		}

		this.ip = session.getRemoteAddress().getAddress();
		ContextCreator.logger.info("Connected to " + this.ip.toString() + ".");
		this.session = session;
		ContextCreator.connection = this;
	}

	@OnWebSocketMessage
	public void onMessage(String message) {
		if (ContextCreator.logger.isDebugEnabled()) {
			ContextCreator.logger.debug("Received message " + message);
		}

		String operation = "error";
		try {
			Object parsed = new JSONParser().parse(message);
			if (!(parsed instanceof JSONObject)) {
				sendBoundaryError(operation, "INVALID_REQUEST",
						"Request must be a JSON object");
				return;
			}
			JSONObject jsonMsg = (JSONObject) parsed;
			Object messageType = jsonMsg.get("messageType");
			String requestedType = messageType == null ? null : messageType.toString().trim();
			if (requestedType == null || requestedType.isEmpty()) {
				sendBoundaryError(operation, "MISSING_MESSAGE_TYPE",
						"messageType is required");
				return;
			}

			String[] target = resolveTarget(requestedType);
			if (target == null) {
				sendBoundaryError(requestedType, "UNKNOWN_MESSAGE_TYPE",
						"Unknown message type: " + requestedType);
				return;
			}
			String category = target[0];
			operation = target[1];

			if ("STEP".equals(category)) {
				String answer = ContextCreator.stepHandler.handleMessage("step", jsonMsg);
				sendHandlerResponse(category, operation, answer);
			} else if ("CTRL".equals(category)) {
				String answer = ContextCreator.controlHandler.handleMessage(operation, jsonMsg);
				sendHandlerResponse(category, operation, answer);
				if (answer != null && shouldSendStepAfterControl(operation, answer)) {
					sendStepPayload(ContextCreator.getCurrentTick());
				}
			} else {
				String answer = this.queryHandler.handleMessage(operation, jsonMsg);
				sendHandlerResponse(category, operation, answer);
			}
		} catch (Exception e) {
			ContextCreator.logger.error("Standard Exception caught: " + e.getMessage());
			try {
				sendBoundaryError(operation, "INVALID_REQUEST", e.getMessage());
			} catch (IOException sendError) {
				ContextCreator.logger.error("Could not send request error: " + sendError.getMessage());
			}
		} catch (Throwable t) {
			ContextCreator.logger.error("FATAL JVM ERROR: " + t.toString(), t);
		}
	}

	private String[] resolveTarget(String requestedType) {
		if ("step".equals(requestedType)) return new String[] { "STEP", "step" };
		if (ContextCreator.controlHandler.supports(requestedType)) {
			return new String[] { "CTRL", requestedType };
		}
		if (this.queryHandler.supports(requestedType)) {
			return new String[] { "QUERY", requestedType };
		}
		return null;
	}

	private void sendHandlerResponse(String category, String operation, String answer)
			throws IOException {
		if (session == null) {
			ContextCreator.logger.warn(category + "_" + operation
					+ ": cannot send answer, session is null");
			return;
		}
		if (answer == null) {
			ContextCreator.logger.warn(category + "_" + operation
					+ ": handler returned null answer, sending error");
			sendBoundaryError(operation, "HANDLER_NO_RESPONSE",
					"Handler did not produce a response");
			return;
		}
		this.answerSender.sendMessage(session, answer);
	}

	@SuppressWarnings("unchecked")
	private void sendBoundaryError(String operation, String errorCode,
			String message) throws IOException {
		if (session == null) return;
		JSONObject result = new JSONObject();
		result.put("messageType", operation == null ? "error" : operation);
		result.put("status", "error");
		result.put("errorCode", errorCode);
		result.put("message", message == null ? "Invalid request" : message);
		this.answerSender.sendMessage(session, JSONObject.toJSONString(result));
	}

	private boolean shouldSendStepAfterControl(String controlType, String answer) {
		if (!GlobalVariables.SYNCHRONIZED
				|| (!"reset".equals(controlType) && !"load".equals(controlType))) {
			return false;
		}
		try {
			JSONObject jsonAns = (JSONObject) new JSONParser().parse(answer);
			return "ok".equals(jsonAns.get("status"));
		} catch (Exception e) {
			ContextCreator.logger.warn("CTRL_" + controlType
					+ ": could not parse answer before ready check: " + e.getMessage());
			return false;
		}
	}

	public void sendStepMessage(int tick) {
		try {
			sendStepPayload(tick);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void sendStepPayload(int tick) throws IOException {
		stepSender.sendMessage(session, tick);
	}

	public void sendReadyMessage() {
		try {
			answerSender.sendReadyMessage(session);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void sendStopMessage() {
		try {
			answerSender.sendStopMessage(session);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
