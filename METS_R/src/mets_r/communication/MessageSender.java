package mets_r.communication;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.Session;

public abstract class MessageSender {
	protected int count; // Number of received messages
	protected long time; // Time consumed for handle the messages
	
	public void sendMessage(Session session, String message) throws IOException {
		long startTime = System.currentTimeMillis();
		if (message.trim().length() < 1) {
			return;
		}

		// Check the connection is open and ready for sending
		if (session == null || !session.isOpen()) {
			throw new IOException("Socket session is not open.");
		}
		session.getRemote().sendString(message);
		count ++;
		time += System.currentTimeMillis() - startTime;
	}
	
	public int getCount() {
		return count;
	}
	
	public long getTime() {
		return time;
	}
	
}
