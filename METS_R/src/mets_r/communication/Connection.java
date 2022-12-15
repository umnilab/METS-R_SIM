package mets_r.communication;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.NetworkEventObject;
import mets_r.data.DataCollector;
import mets_r.data.DataConsumer;
import mets_r.data.TickSnapshot;
import mets_r.data.VehicleSnapshot;

/**
 * When a request from a remote program for a network connection is received,
 * the network connection manager will produce a new WebSocket connection to be
 * managed by a new instance of this class. This class provides both the
 * WebSocket annotated methods the Jetty library will use to handle processing
 * control of this particular socket and the delivery of messages received from
 * the remote program and the interface methods of a data consumer which uses
 * the data collection buffer built into the simulation. 
 * 
 * @author: Christopher Thompson, Zengxiang Lei
**/

@WebSocket
public class Connection implements DataConsumer {
	private int id;
	private static int COUNTER = 0; // Number of instances of the connection created so far

	// A local convenience reference to manager for this connection
	@SuppressWarnings("unused")
	private ConnectionManager manager = ConnectionManager.getInstance();

	// A local convenience reference to the data collection buffer for the simulation
	private DataCollector collector = DataCollector.getInstance();
	
	// A "heartbeat" thread which pings remote end often to keep it alive. 
	@SuppressWarnings("unused")
	private Thread heartbeatThread;

	private Session session; // The websocket session object for the connection. 
	private InetAddress ip; // The address of the remote host. 
	private Thread sendingThread; // A thread which periodically sends buffer contents over the socket.
	private boolean consuming; // Whether or not the connection is currently consuming buffer data. 
	private boolean paused;
	private int currentTick;
	private static int prevhour = -1;

	public Connection() {
		// increment our instance counter and save this object's number
		this.id = ++Connection.COUNTER;

		// set the current tick to zero.
		this.currentTick = 0;

		// set the flags to the pre-running state
		this.consuming = false;
		this.paused = false;

		// the heartbeat thread will be created and destroyed by
		// the annotated socket open and close methods
		this.heartbeatThread = null;

		// the data sending thread will be created each time the
		// data consumption start signal is sent and stopped when
		// the data consumption stop signal is sent. Note the heartbeat
		// thread operates independently of this and remains running
		// whether or not the data consumption is active so long as
		// the socket is open.
		this.sendingThread = null;

		ConnectionManager.printDebug(this.id + "-CTRL", "Connection object created.");
	}

	// Starts consuming data from the data buffer. 
	@Override
	public void startConsumer() throws Throwable {
		if (this.consuming) {
			// the consumer is already running, so just resume if necessary
			if (this.paused) {
				this.paused = false;
			}
			return;
		}

		// set the flags to the running state
		this.consuming = true;
		this.paused = false;

		// start the data consumption thread
		this.sendingThread = new Thread(new SendingRunnable());
		this.sendingThread.start();
	}

	// Stops the data consumption 
	@Override
	public void stopConsumer() throws Throwable {
		if (!this.consuming) {
			return;
		}

		// set the flags to the stopped state
		this.paused = false;
		this.consuming = false;

		// wait for the sending thread to halt
		this.sendingThread.interrupt();
		this.sendingThread.join();
		this.sendingThread = null;

		// reset the tick counter to the initial position
		this.currentTick = 0;
	}

	// Signals the pausing of consumption of new items from the buffer.
	public void pauseConsumer() throws Throwable {
		// check we are even running
		if (!this.consuming) {
			return;
		}

		// set the flags to tell the consumer to stop consuming new items
		this.paused = true;
		this.consuming = true;
	}

	// Stops the consumption of new items from the buffer
	@Override
	public void resetConsumer() throws Throwable {
		// stop the current network sending if it is running already
		this.stopConsumer();

		// reset the flags to the initial state
		this.paused = false;
		this.consuming = false;

		// reset the current tick counter back to the start
		this.currentTick = 0;
	}

	// Returns the current tick being processed (or just processed).
	@Override
	public int getTick() {
		return this.currentTick;
	}

	// Sets the next tick to process from the data buffer. 
	@Override
	public void setTick(int tick) throws Throwable {
		this.currentTick = tick;
	}

	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		ConnectionManager.printDebug(this.id + "-CTRL", statusCode + ": " + reason);
	}

	@OnWebSocketError
	public void onError(Throwable t) {
		ConnectionManager.printDebug(this.id + "-ERR", t.getMessage());
	}

	@OnWebSocketConnect
	public void onConnect(Session session) {
		if (session == null) {
			return;
		}

		this.ip = session.getRemoteAddress().getAddress();

		ConnectionManager.printDebug(this.id + "-CTRL", "Connected to " + this.ip.toString() + ".");

		// Save a reference to the session for this socket connection
		this.session = session;

		// Register the connection as a data consumer
		this.collector.registerDataConsumer(this);

		// Register the connection in ContextCreator
		ContextCreator.connection = this;

		try {
			for (String OD : ContextCreator.getUCBRouteODPairs()) {
				String msg = ContextCreator.getUCBRouteStringForOD(OD);
				session.getRemote().sendString(msg);
			}
			for (String OD : ContextCreator.getUCBRouteODPairsBus()) {
				String msg = ContextCreator.getUCBRouteStringForODBus(OD);
				session.getRemote().sendString(msg);
			}
			// Send the first tick message, important for getting the reply 
			// the bus schedules if dynamic bus planning is on 
			this.sendTickSnapshot(new TickSnapshot(0));
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	@OnWebSocketMessage
	public void onMessage(String message) {
		// Transfer to JSON here
		JSONObject jsonMsg = new JSONObject();
		try {
			JSONParser parser = new JSONParser();
			jsonMsg = (JSONObject) parser.parse(message);
			if (jsonMsg.get("MSG_TYPE").equals("OD_PAIR")) {
				HashMap<String, Integer> res = new HashMap<String, Integer>();
				ContextCreator.logger.info("Received route result!");
				JSONArray list_OD = (JSONArray) jsonMsg.get("OD");
				JSONArray list_result = (JSONArray) jsonMsg.get("result");
				for (int index = 0; index < list_OD.size(); index++) {
					Long result = (Long) list_result.get(index);
					int result_int = result.intValue();
					String OD = (String) list_OD.get(index);
					res.put(OD, result_int);
				}
				ContextCreator.routeResult_received = res;
			} else if (jsonMsg.get("MSG_TYPE").equals("BOD_PAIR")) {
				HashMap<String, Integer> res = new HashMap<String, Integer>();
				ContextCreator.logger.info("Received bus route result!");
				JSONArray list_OD = (JSONArray) jsonMsg.get("OD");
				JSONArray list_result = (JSONArray) jsonMsg.get("result");
				for (int index = 0; index < list_OD.size(); index++) {
					Long result = (Long) list_result.get(index);
					int result_int = result.intValue();
					String OD = (String) list_OD.get(index);
					res.put(OD, result_int);
				}
				ContextCreator.routeResult_received_bus = res;
			} else if (jsonMsg.get("MSG_TYPE").equals("BUS_SCHEDULE")) {
				ContextCreator.logger.info("Received bus schedules!");
				JSONArray list_routename = (JSONArray) jsonMsg.get("Bus_routename");
				JSONArray list_route = (JSONArray) jsonMsg.get("Bus_route");
				JSONArray list_gap = (JSONArray) jsonMsg.get("Bus_gap");
				JSONArray list_num = (JSONArray) jsonMsg.get("Bus_num");
				Long hour = Long.valueOf((String) jsonMsg.get("Bus_currenthour"));
				int newhour = hour.intValue();

				if (prevhour < newhour) { // New schedules
					prevhour = newhour;
					int array_size = list_num.size();
					ArrayList<Integer> newRouteName = new ArrayList<Integer>(array_size);
					ArrayList<Integer> newBusNum = new ArrayList<Integer>(array_size);
					ArrayList<Integer> newBusGap = new ArrayList<Integer>(array_size);
					ArrayList<ArrayList<Integer>> newRoutes = new ArrayList<ArrayList<Integer>>(array_size);
					for (int index = 0; index < list_num.size(); index++) {
						int bus_num_int = 0;
						if (list_num.get(index) instanceof Number) {
							bus_num_int = ((Number) list_num.get(index)).intValue();
						}
						if (bus_num_int > 0) {
							// Verify the data, the last stop ID should be the same as the first one
							@SuppressWarnings("unchecked")
							ArrayList<Long> route = (ArrayList<Long>) list_route.get(index);
							if (route.get(0).intValue() == route.get(route.size() - 1).intValue()) {
								newBusNum.add(bus_num_int);
								Double gap = (Double) list_gap.get(index);
								int gap_int = gap.intValue();
								// multiply by 60 for seconds
								newBusGap.add(gap_int);
								Long routename = (Long) list_routename.get(index);
								int list_routename_int = routename.intValue();
								newRouteName.add(list_routename_int);
								int route_size = route.size() - 1;
								ArrayList<Integer> route_int = new ArrayList<Integer>(route_size);
								for (int index_route = 0; index_route < route_size; index_route++) {
									int route_int_i = route.get(index_route).intValue();
									route_int.add(route_int_i - 1);
								}
								newRoutes.add(route_int);
							}
						}
					}
					ContextCreator.busSchedule.updateEvent(newhour, newRouteName, newRoutes, newBusNum, newBusGap);
					ContextCreator.receiveNewBusSchedule = true;
				}
			}
		} catch (ParseException e) {
			e.printStackTrace();
		}
	}

	// Sends the given tick snapshot over the connection as a message after
	// converting it into a String suitable for the body of a socket message.
	public void sendTickSnapshot(TickSnapshot tick) throws IOException {
		if (tick == null) {
			return;
		}

		// Get the message representation of this tick
		String message = Connection.createTickMessage(tick);

		if (message.trim().length() < 1) {
			return;
		}

		// Check the connection is open and ready for sending
		if (session == null || !session.isOpen()) {
			throw new IOException("Socket session is not open.");
		}

		// Send the message over the socket
		session.getRemote().sendString(message);
	}

	// Returns the socket message representation of the given tick.
	public static String createTickMessage(TickSnapshot tick) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("MSG_TYPE", "TICK_MSG");
		ArrayList<HashMap<String, Object>> entries = new ArrayList<HashMap<String, Object>>();
		int hour = (int) (tick.getTickNumber()/ (3600 / GlobalVariables.SIMULATION_STEP_SIZE));
		// Send the latest progress of the simulation
		if (hour > prevhour) {
			HashMap<String, Object> entryObj = new HashMap<String, Object>();
			entryObj.put("TYPE", "H");
			entryObj.put("hour", hour);
			entries.add(entryObj);
		}

		// Loop through all the link_UCB arraylist
		Collection<Integer> linkIDs = tick.getLinkIDList();

		for (Integer id : linkIDs) {

			if (id == null) {
				continue;
			}

			ArrayList<Double> energyList = tick.getLinkEnergyList(id);

			if (energyList != null) {
				HashMap<String, Object> entryObj = new HashMap<String, Object>();
				entryObj.put("values", energyList);
				entryObj.put("TYPE", "E");
				entryObj.put("hour", hour);
				entryObj.put("ID", id);
				entries.add(entryObj);
			}

			ArrayList<Double> linkSpeed = tick.getSpeedVehicle(id);

			if (linkSpeed != null) {
				HashMap<String, Object> entryObj = new HashMap<String, Object>();
				entryObj.put("values", linkSpeed);
				entryObj.put("TYPE", "V");
				entryObj.put("hour", hour);
				entryObj.put("ID", id);
				entries.add(entryObj);
			}
		}

		jsonObj.put("entries", entries);
		String line = JSONObject.toJSONString(jsonObj);
		return line;
	}


	// Returns the socket message representation of the given vehicle.
	public static String createVehicleMessage(VehicleSnapshot vehicle) {
		// Check if the vehicle snapshot even exists
		if (vehicle == null) {
			return null;
		}

		// Extract all the values from the vehicle snapshot
		int id = vehicle.getId();
		double prev_x = vehicle.getPrevX();
		double prev_y = vehicle.getPrevY();
		double x = vehicle.getX();
		double y = vehicle.getY();
		double speed = vehicle.getSpeed();
		double originalX = vehicle.getOriginX();
		double originalY = vehicle.getOriginY();
		double destX = vehicle.getDestX();
		double destY = vehicle.getDestY();
		int vehicleClass = vehicle.getvehicleClass();
		int roadID = vehicle.getRoadID();

		// Put them together into a string for the socket and return it
		return id + "," + prev_x + "," + prev_y + "," + x + "," + y + "," + speed + "," + originalX + "," + originalY
				+ "," + destX + "," + destY + "," + vehicleClass + "," + roadID;
	}

	// Returns the socket message representation of the given event.
	public static String createEventMessage(NetworkEventObject event) {
		// Check if the event even exists
		if (event == null) {
			return null;
		}

		// Extract all the values from the event
		int startTime = event.startTime;
		int endTime = event.endTime;
		int eventID = event.eventID;
		int roadID = event.roadID;

		// Put them together into a string for the socket and return it
		return startTime + "," + endTime + "," + eventID + "," + roadID;
	}

	private class SendingRunnable implements Runnable {

		public SendingRunnable() {
			ConnectionManager.printDebug(id + "-SEND", "Created stream thread.");
		}

		@Override
		public void run() {
			// Wait for session to exist if it hasn't been created yet
			while (session == null) {
				try {
					Thread.sleep(GlobalVariables.NETWORK_BUFFER_RERESH);
				} catch (InterruptedException ie) {
					ConnectionManager.printDebug(id + "-SEND", "Send thread stop.");
					return;
				}
			}

			// Loop and process buffered data until we are told to stop
			int totalCount = 0;
			int sendCount = 0;
			boolean running = true;
			while (running) {
				// Check if we are supposed to be done
				if (!Connection.this.consuming) {
					ConnectionManager.printDebug(id + "-SEND", "Not consuming.");
					break;
				}

				// Check if we are supposed to be paused
				if (Connection.this.paused) {
					ConnectionManager.printDebug(id + "-SEND", "Paused.");
					// We are currently paused, so we will wait our delay
					// before performing another poll on our running state
					try {
						Thread.sleep(GlobalVariables.NETWORK_BUFFER_RERESH);
					} catch (InterruptedException ie) {
						break;
					}
				}

				// Get the next item from the buffer. 
				int nextTick = Connection.this.currentTick + GlobalVariables.JSON_TICKS_BETWEEN_TWO_RECORDS;
				TickSnapshot snapshot = collector.getNextTick(nextTick);
				if (snapshot == null) {
					// The buffer has no more items for us at this time
					if (sendCount > 0) {
						String report = "Sent " + sendCount + " ticks to remote host (" + totalCount + " total)";
						ConnectionManager.printDebug(id + "-SEND", report);
						sendCount = 0;
					}

					// Is the data collection process finished?
					if (!collector.isCollecting() && !collector.isPaused()) {
						break;
					}

					// We will wait a little while before looping around
					// to check if anything new has arrived in the buffer
					try {
						Thread.sleep(GlobalVariables.NETWORK_BUFFER_RERESH);
					} catch (InterruptedException ie) {
						// While waiting, the thread was told to stop
						ConnectionManager.printDebug(id + "-SEND", "Stopped.");
						break;
					}

					// Loop around to try again
					continue;
				}

				// Update the currently processing tick index to this item
				Connection.this.currentTick = snapshot.getTickNumber();

				// Process the current item into a socket message and send it
				try {
					Connection.this.sendTickSnapshot(snapshot);
					totalCount++;
					sendCount++;
				} catch (Throwable t) {
					ConnectionManager.printDebug(id + "-SEND", t.toString());
				}

				// Wait a short delay (a few ms) to give java's thread
				// scheduler a chance to switch contexts
				try {
					Thread.sleep(5);
				} catch (InterruptedException ie) {
					// Thread has been told to stop writing network data
					ConnectionManager.printDebug(id + "-SEND", "Stopping.");
					break;
				}
			}

			// Set the data consumption flags as finished
			Connection.this.paused = false;
			Connection.this.consuming = false;
		}
	}
}
