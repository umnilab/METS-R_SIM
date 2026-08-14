package mets_r.communication;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;

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
 * WebSocket connection and wire-schema boundary.
 *
 * <p>The selected API schema is connection scoped. Existing handlers continue
 * to use the v1 representation; {@link ApiSchemaAdapter} normalizes v2 requests
 * before dispatch and serializes handler responses back to v2.</p>
 */
@WebSocket
public class Connection {
	private int id;
	private static int COUNTER = 0;

	private Session session;
	private InetAddress ip;
	private volatile int schemaVersion = ApiSchemaAdapter.DEFAULT_VERSION;

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
		this.schemaVersion = ApiSchemaAdapter.DEFAULT_VERSION;
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
		this.schemaVersion = ApiSchemaAdapter.DEFAULT_VERSION;
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
						"Request must be a JSON object", this.schemaVersion);
				return;
			}
			JSONObject jsonMsg = (JSONObject) parsed;

			Integer requestedVersion = ApiSchemaAdapter.requestedVersion(jsonMsg);
			if (requestedVersion != null) {
				if (!ApiSchemaAdapter.isSupported(requestedVersion.intValue())) {
					sendBoundaryError(operation, "UNSUPPORTED_SCHEMA_VERSION",
							"Supported schema versions are 1 and 2", ApiSchemaAdapter.VERSION_2);
					return;
				}
				this.schemaVersion = requestedVersion.intValue();
			}

			String requestedType = ApiSchemaAdapter.requestedMessageType(jsonMsg);
			if (requestedType == null || requestedType.isEmpty()) {
				sendBoundaryError(operation, "MISSING_MESSAGE_TYPE",
						"TYPE (v1) or messageType (v2) is required", this.schemaVersion);
				return;
			}

			String[] target = resolveTarget(requestedType);
			if (target == null) {
				sendBoundaryError(requestedType, "UNKNOWN_MESSAGE_TYPE",
						"Unknown message type or operation: " + requestedType, this.schemaVersion);
				return;
			}
			String category = target[0];
			operation = target[1];
			JSONObject normalized = ApiSchemaAdapter.normalizeRequest(
					jsonMsg, this.schemaVersion, category, operation);

			if ("STEP".equals(category)) {
				String answer = ContextCreator.stepHandler.handleMessage("STEP", normalized);
				sendHandlerResponse(category, operation, answer);
			} else if ("CTRL".equals(category)) {
				String answer = ContextCreator.controlHandler.handleMessage(operation, normalized);
				sendHandlerResponse(category, operation, answer);
				if (answer != null && shouldSendStepAfterControl(operation, answer)) {
					sendStepPayload(ContextCreator.getCurrentTick());
				}
			} else {
				String answer = this.queryHandler.handleMessage(operation, normalized);
				sendHandlerResponse(category, operation, answer);
			}
		} catch (Exception e) {
			ContextCreator.logger.error("Standard Exception caught: " + e.getMessage());
			try {
				sendBoundaryError(operation, "INVALID_REQUEST", e.getMessage(), this.schemaVersion);
			} catch (IOException sendError) {
				ContextCreator.logger.error("Could not send request error: " + sendError.getMessage());
			}
		} catch (Throwable t) {
			ContextCreator.logger.error("FATAL JVM ERROR: " + t.toString(), t);
		}
	}

	/**
	 * Accept both prefixed v1 message types and prefix-free v2 operation names.
	 * Handler registration resolves the category for the latter.
	 */
	private String[] resolveTarget(String requestedType) {
		String category = null;
		String operation = requestedType;
		int separator = requestedType.indexOf('_');
		if (separator > 0) {
			String prefix = requestedType.substring(0, separator).toUpperCase();
			if ("CTRL".equals(prefix) || "QUERY".equals(prefix) || "STEP".equals(prefix)) {
				category = prefix;
				operation = requestedType.substring(separator + 1);
			}
		} else if ("STEP".equalsIgnoreCase(requestedType)) {
			category = "STEP";
			operation = "step";
		}

		operation = ApiSchemaAdapter.dispatchOperation(operation);
		if (category == null) {
			if ("step".equalsIgnoreCase(operation)) category = "STEP";
			else if (ContextCreator.controlHandler.supports(operation)) category = "CTRL";
			else if (this.queryHandler.supports(operation)) category = "QUERY";
			else return null;
		}
		if ("CTRL".equals(category) && !ContextCreator.controlHandler.supports(operation)) return null;
		if ("QUERY".equals(category) && !this.queryHandler.supports(operation)) return null;
		return new String[] { category, operation };
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
					"Handler did not produce a response", this.schemaVersion);
			return;
		}
		String response = ApiSchemaAdapter.formatResponse(
				category, operation, answer, this.schemaVersion);
		this.answerSender.sendMessage(session, response);
	}

	private void sendBoundaryError(String operation, String errorCode,
			String message, int version) throws IOException {
		if (session == null) return;
		this.answerSender.sendMessage(session,
				ApiSchemaAdapter.formatError(operation, errorCode, message, version));
	}

	private boolean shouldSendStepAfterControl(String controlType, String answer) {
		if (!GlobalVariables.SYNCHRONIZED
				|| (!"reset".equals(controlType) && !"load".equals(controlType))) {
			return false;
		}
		try {
			JSONObject jsonAns = (JSONObject) new JSONParser().parse(answer);
			return "OK".equals(jsonAns.get("CODE"));
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
		if (this.schemaVersion == ApiSchemaAdapter.DEFAULT_VERSION) {
			stepSender.sendMessage(session, tick);
			return;
		}
		HashMap<String, Object> stepMsg = new HashMap<String, Object>();
		stepMsg.put("TYPE", "STEP");
		stepMsg.put("TICK", tick);
		answerSender.sendMessage(session, ApiSchemaAdapter.formatResponse(
				"STEP", "step", JSONObject.toJSONString(stepMsg), this.schemaVersion));
	}

	public void sendReadyMessage() {
		try {
			if (this.schemaVersion == ApiSchemaAdapter.DEFAULT_VERSION) {
				answerSender.sendReadyMessage(session);
			} else {
				HashMap<String, Object> ready = new HashMap<String, Object>();
				ready.put("TYPE", "ANS_ready");
				answerSender.sendMessage(session, ApiSchemaAdapter.formatResponse(
						"ANS", "ready", JSONObject.toJSONString(ready), this.schemaVersion));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void sendStopMessage() {
		try {
			if (this.schemaVersion == ApiSchemaAdapter.DEFAULT_VERSION) {
				answerSender.sendStopMessage(session);
			} else {
				HashMap<String, Object> stop = new HashMap<String, Object>();
				stop.put("TYPE", "CTRL_end");
				stop.put("CODE", "OK");
				answerSender.sendMessage(session, ApiSchemaAdapter.formatResponse(
						"CTRL", "end", JSONObject.toJSONString(stop), this.schemaVersion));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
