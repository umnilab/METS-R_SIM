package mets_r.communication;

import java.util.Vector;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;

/**
 * METS_R.communication.ConnectionManager
 * 
 * The connection manager is created with the start of the simulation program
 * and monitors the network for any incoming connections to the simulation. It
 * creates an object to handle the interface between the simulation and a remote
 * listener through the use of a WebSocket-based connection and the data
 * collection buffer of the simulation.
 * 
 * @author Christopher Thompson (thompscs@purdue.edu)
 * @version 1.0
 * @date 17 October 2017
 */
public class ConnectionManager {

	/** This instance is the only connection manager object in the system. */
	private static ConnectionManager instance;

	/** The server which listens for incoming sockets to create. */
	private Server server;

	/** The list of connections currently open in the system. */
	@SuppressWarnings("unused")
	private Vector<Connection> connections;

	// Pick a permanent port number for the socket to use for listening.
	// This temporary value (33131) is the zip-code of downtown Miami. Jetty
	// default (8080) is a common web port which may be in use or blocked.

	/**
	 * Constructs the connection manager system which performs any steps needed to
	 * build and start the WebSocket listening server within the simulation program.
	 * 
	 * This constructor is private to prevent multiple instances of the manager
	 * being created within the program. All references to the manager should be
	 * made through ConnectionManager.getInstance() which will create the object the
	 * first time it is requested and return the same object each time after that to
	 * ensure only one socket server is ever created in the program.
	 */
	private ConnectionManager() {
		// create the list for holding all the open connections
		this.connections = new Vector<Connection>();

		// start the server listening for incoming connections
		if (GlobalVariables.ENABLE_NETWORK) {
			Runnable startRunnable = new Runnable() {
				@Override
				public void run() {
					try {
						Thread.sleep(5000);
					} catch (Throwable t) {
					}
					ConnectionManager.this.startServer();
				}
			};
			Thread startThread = new Thread(startRunnable);
			startThread.start();
		}
	}

	/**
	 * Returns the singleton instance of the connection manager.
	 * 
	 * @return the singleton instance of the connection manager.
	 */
	public static ConnectionManager getInstance() {
		if (ConnectionManager.instance == null) {
			ConnectionManager.instance = new ConnectionManager();
		}

		return ConnectionManager.instance;
	}

	/**
	 * This method starts the WebSocket server on the listening port to create new
	 * connection objects for incoming requests from remote listening programs. If
	 * the server is already accepting incoming connections, this method will do
	 * nothing to alter it.
	 */
	public void startServer() {
		if (this.server != null) {
			// The server already exists so check if it is running
			String serverState = this.server.getState();
			if (serverState == null) {
				// The state is unknown or something weird is happening, so
				// we will just destroy the existing server and replace it
				this.stopServer();
			} else {
				switch (serverState) {
				case AbstractLifeCycle.STARTING:
				case AbstractLifeCycle.STARTED:
				case AbstractLifeCycle.RUNNING:
					// The server is already running or will be soon
					return;
				case AbstractLifeCycle.STOPPING:
					// Server is still shutting down, so wait for it
					try {
						this.server.join();
					} catch (InterruptedException ie) {
						// If server stop was interrupted, do we care?
						ConnectionManager.printDebug("ERR", ie.getMessage());
					}
					// Now that it's stopped, fall through to...
				case AbstractLifeCycle.STOPPED:
				case AbstractLifeCycle.FAILED:
					// Server exists but is not running, so it throw away
					this.server = null;
					break;
				default:
					// What do we do if state string is unexpected value?
					break;
				}
			}
		}

		// Create the new server on our listening port
		this.server = new Server(GlobalVariables.NETWORK_LISTEN_PORT);
		System.out.println("Server created!");

		// Attach a handler for generating sockets to incoming connections
		int maxMsgSize = GlobalVariables.NETWORK_MAX_MESSAGE_SIZE;
		WebSocketHandler socketHandler = null;
		try {
			socketHandler = new WebSocketHandler() {
				@Override
				public void configure(WebSocketServletFactory factory) {
					// The default maximum size for a single message sent over
					// the socket is way too small (64K) so we bump it higher
					factory.getPolicy().setMaxTextMessageSize(maxMsgSize);

					// Tell the factory which class has our socket methods
					factory.register(Connection.class);
				}
			};
		} catch (Exception e) {
			// What do we do if socket handler couldn't be created?
			ConnectionManager.printDebug("ERR", e.getMessage());
		}
		this.server.setHandler(socketHandler);

		// Start the server listening
		try {
			// Wait until the route_UCB in ContextCreator is populated
			// otherwise route_UCB map will not be correctly received by the
			// RemoteDataClientManager
			while (!ContextCreator.isRouteUCBMapPopulated())
				;
			while (!ContextCreator.isRouteUCBBusMapPopulated())
				;
			this.server.start();
			ConnectionManager.printDebug("CTRL", "Started.");
		} catch (Exception e) {
			// Deal with exception thrown from server not starting...
			ConnectionManager.printDebug("ERR", e.getMessage());
		}
	}

	/**
	 * This method will stop the server from accepting new incoming socket
	 * connections and dispose of it, if it is already running or will be running
	 * soon. If the server is already stopped or failed, this method will do nothing
	 * but dispose of the server object.
	 */
	public void stopServer() {
		// Check the server object exists
		if (this.server == null) {
			return;
		}

		// Branch and handle the server stoppage depending on current state
		String serverState = this.server.getState();
		if (serverState == null) {
			// The state of the server could not be determined, so
			// we will just forcibly try to stop and destroy it
			try {
				this.server.stop();
				this.server.join();
				this.server = null;
			} catch (Exception e) {
				// An error happened stopping the server
			}
		} else {
			switch (serverState) {
			case AbstractLifeCycle.STOPPING:
			case AbstractLifeCycle.STOPPED:
			case AbstractLifeCycle.FAILED:
				// The server is already stopped or stopping
				this.server = null;
				return;
			case AbstractLifeCycle.STARTING:
				// The server is still starting, so wait for it to finish
				// How to wait for "starting" server state?
			case AbstractLifeCycle.STARTED:
			case AbstractLifeCycle.RUNNING:
			default:
				// Server is running (or unknown state) so stop it
				try {
					this.server.stop();
					this.server.join();
					this.server = null;
					ConnectionManager.printDebug("CTRL", "Stopped.");
				} catch (Exception e) {
					// An error happened stopping the server
					ConnectionManager.printDebug("ERR", e.getMessage());
				}
			}
		}
	}

	/**
	 * Logs the given message to the standard output if debugging of the network has
	 * been enabled (via a constant boolean flag in the ConnectionManager class). If
	 * debugging is not enabled, the message is ignored.
	 * 
	 * @param msg the debugging message to print to the standard output.
	 */
	public static void printDebug(String msg) {
		ConnectionManager.printDebug(null, msg);
	}

	/**
	 * Logs the given message to the standard output if debugging of the network has
	 * been enabled (via a constant boolean flag in the ConnectionManager class)
	 * with the given custom string in the prefix. If debugging is not enabled, the
	 * message is ignored.
	 * 
	 * @param prefix the custom prefix to include at the beginning.
	 * @param msg    the debugging message to print to the standard output.
	 */
	public static void printDebug(String prefix, String msg) {
		if (GlobalVariables.DEBUG_NETWORK) {
			String debugMessage = "<<NET";
			if (prefix != null && prefix.trim().length() > 0) {
				debugMessage += ":" + prefix;
			}
			debugMessage += ">> " + msg;

			ContextCreator.logger.debug(debugMessage);
		}
	}

}
