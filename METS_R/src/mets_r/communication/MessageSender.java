package mets_r.communication;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.Session;

public abstract class MessageSender {
	protected int count; // Number of received messages
	
	public void sendMessage(Session session, String message) throws IOException {
		if (message == null || message.trim().length() < 1) {
			return;
		}

		if (session == null) {
			throw new IOException("Socket session is not open.");
		}
		synchronized (session) {
			// Jetty's blocking RemoteEndpoint allows only one pending write per
			// session, while METS-R can send replies and step notifications from
			// different threads.
			if (!session.isOpen()) {
				throw new IOException("Socket session is not open.");
			}
			session.getRemote().sendString(message);
			count ++;
		}
	}
	
	public int getCount() {
		return count;
	}
	
}
