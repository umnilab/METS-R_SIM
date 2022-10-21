package addsEVs.network;

//import evacSim.NetworkEventHandler;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import repast.simphony.essentials.RepastEssentials;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.NetworkEventObject;
import addsEVs.data.DataCollector;
import addsEVs.data.DataConsumer;
import addsEVs.data.TickSnapshot;
import addsEVs.data.VehicleSnapshot;

/**
 * evacSim.network.Connection
 * 
 * When a request from a remote program for a network connection is received,
 * the network connection manager will produce a new WebSocket connection to be
 * managed by a new instance of this class. This class provides both the
 * WebSocket annotated methods the Jetty library will use to handle processing
 * control of this particular socket and the delivery of messages received from
 * the remote program and the interface methods of a data consumer which uses
 * the data collection buffer built into the simulation. A thread
 * 
 * @author Christopher Thompson (thompscs@purdue.edu)
 * @version 1.0
 * @date 17 October 2017
 * @author Zengxiang Lei (lei67@purdue.edu)
 * @version 2.0
 * @date 4 August 2021
 * 
 */
@WebSocket
public class Connection implements DataConsumer {

	/** The number of instances of the connection class created so far. */
	private static int COUNTER = 0;

	/** An id number for referring to this particular connection in logs. */
	private int id;

	/** A local convenience reference to manager for this connection. */
	@SuppressWarnings("unused")
	private ConnectionManager manager = ConnectionManager.getInstance();

	/** A local convenience reference to the collection buffer for the sim. */
	private DataCollector collector = DataCollector.getInstance();

	/** The websocket session object for the connection this object serves. */
	private Session session;

	/** The address of the remote host for this connection. */
	private InetAddress ip;

	/** A thread which periodically sends buffer contents over the socket. */
	private Thread sendingThread;

	/** A "heartbeat" thread which pings remote end often to keep it alive. */
	@SuppressWarnings("unused")
	private Thread heartbeatThread;

	/** Whether or not the connection is currently consuming buffer data. */
	private boolean consuming;

	/** Whether or not the connection is paused for consuming more data. */
	private boolean paused;

	/** The current tick number being processed (or just processed). */
	private double currentTick;
	
	/** The current hour of the simulation. */
	private static int prevhour = -1;

	/**
	 * Performs any preparation needed for this object to be ready to receive a new
	 * socket connection and begin processing the data in the simulation data
	 * buffer.
	 */
	public Connection() {
		// increment our instance counter and save this object's number
		this.id = ++Connection.COUNTER;

		// set the current tick to zero.
		this.currentTick = 0.0;

		// set the flags to the pre-running state
		this.consuming = false;
		this.paused = false;

		// the heartbeat thread will be created and destroyed by
		// the annotated socket open and close methods so that it
		// runs not when this object is created or destroyed but
		// when the actual socket is created or destroyed
		this.heartbeatThread = null;

		// the data sending thread will be created each time the
		// data consumption start signal is sent and stopped when
		// the data consumption stop signal is sent. the heartbeat
		// thread operates independently of this and remains running
		// whether or not the data consumption is active so long as
		// the socket is open.
		this.sendingThread = null;

		ConnectionManager.printDebug(this.id + "-CTRL", "Connection object created.");
	}

	/**
	 * Starts consuming data from the data buffer. This starts the thread for
	 * sending data across the socket. Calling this method if the consumer is paused
	 * will do nothing but resume it. If the signal to start consuming arrives
	 * before the socket is opened yet, the sending thread will wait for the socket
	 * to be open.
	 * 
	 * @throws Throwable if any problem occurred starting the data sending.
	 */
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

	/**
	 * Stops the data consumption and sending (after finishing any currently
	 * processing item) and waits for the sending thread to halt. Stopping a data
	 * consumer should only be done if immediately halting of its own operations is
	 * desired. If the simulation is simply stopping, it is best to allow data
	 * consumers to run to completion to make sure they have finished processing any
	 * remaining items in the buffer. This will block until the sending thread has
	 * finished running and as such should be called from its own thread so as to
	 * not block the entire simulation.
	 * 
	 * @throws Throwable if any problems occurred stopping the data sender.
	 */
	@Override
	public void stopConsumer() throws Throwable {
		if (!this.consuming) {
			return;
		}

		// set the flags to the stopped state
		this.paused = false;
		this.consuming = false;

		// wait for the sending thread to halt. setting the flags above
		// should tell the thread to stop processing new items and return
		// on its own, but we will go ahead and explicitly interrupt the
		// thread to speed this process along, as well. then we will wait
		// for it to complete.
		this.sendingThread.interrupt();
		this.sendingThread.join();
		this.sendingThread = null;

		// reset the tick counter to the initial position
		this.currentTick = 0;
	}

	/**
	 * Signals the pausing of consumption of new items from the buffer. Any items
	 * currently being sent will finish, and the thread which performs the actual
	 * pull of data from the buffer will check this state before grabbing the next
	 * tick snapshot to know to stop. The thread is left running as this is only the
	 * paused state so it is expected to resume at some point in the future.
	 * 
	 * @throws Throwable if any error occurs preventing the pause.
	 */
	@Override
	public void pauseConsumer() throws Throwable {
		// check we are even running
		if (!this.consuming) {
			return;
		}

		// set the flags to tell the consumer to stop consuming new items
		this.paused = true;
		this.consuming = true;

		// we do nothing to the thread or connection directly here.
		// the thread on its next loop will see the paused state and
		// start checking (with a delay) for this state to change back
		// to normal running before resuming its work.
	}

	/**
	 * Stops the consumption of new items from the buffer, stops the data sending
	 * after the current item has been processed, and resets the buffer position to
	 * start again fresh at the front of the buffer if the sending of data to this
	 * connection is reestablished. This will block and wait for the sending thread
	 * to complete its current task, so it should not be called from the main
	 * program thread or the whole simulation could be blocked temporarily.
	 * 
	 * @throws Throwable if a problem occurred resetting the consumer.
	 */
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

	/**
	 * Returns the current tick being processed (or just processed).
	 * 
	 * @return the current tick being processed (or just processed).
	 */
	@Override
	public double getTick() {
		return this.currentTick;
	}

	/**
	 * Sets the next tick to process from the data buffer. The next tick retrieved
	 * from the data buffer will be the first found to have a value equal to or
	 * greater than this value. If the model has not yet reached this tick index
	 * value, nothing will be returned until the simulation has advanced to this
	 * point.
	 * 
	 * @param tick the next tick to process from the data buffer.
	 */
	@Override
	public void setTick(double tick) throws Throwable {
		this.currentTick = tick;
	}

	/**
	 * This method is invoked when the WebSocket closes and should handle any
	 * necessary tasks for gracefully stopping connection related tasks or attempt
	 * to restart the connection.
	 * 
	 * @param statusCode the status code for why the connection closed.
	 * @param reason     a text description of why the connection closed.
	 */
	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		// TODO: handle any tasks needed when socket closes.
		ConnectionManager.printDebug(this.id + "-CTRL", statusCode + ": " + reason);
	}

	/**
	 * This method is called whenever the connection experiences an error and should
	 * handle any problems that would be consequences of the exception.
	 * 
	 * @param t the error or exception thrown because of the problem.
	 */
	@OnWebSocketError
	public void onError(Throwable t) {
		// TODO: gracefully handle socket errors
		ConnectionManager.printDebug(this.id + "-ERR", t.getMessage());
	}

	/**
	 * This method is called when the WebSocket connection is established to handle
	 * any tasks needing to happen when the connection is started.
	 * 
	 * @param session the WebSocket session object for this connection.
	 */
	@OnWebSocketConnect
	public void onConnect(Session session) {

		if (session == null) {
			// is there any way the jetty library would do this?
			return;
		}

		this.ip = session.getRemoteAddress().getAddress();
		// ConnectionManager.printDebug("CTRL", "New connection from: " + ip);

		ConnectionManager.printDebug(this.id + "-CTRL", "Connected to " + this.ip.toString() + ".");

		// Save a reference to the session for this socket connection
		this.session = session;

		// Register the connection as a data consumer
		this.collector.registerDataConsumer(this);

		// Create the heartbeat thread to keep the connection open
		// Deprecated as we are looking for a reactive server
        //this.heartbeatThread = new Thread(new HeartbeatRunnable());
        //this.heartbeatThread.start();
		
		// Register the connection in ContextCreator
		ContextCreator.connection = this;

		try {
			/*
			 * for (String OD : ContextCreator.getUCBRouteODPairs()) {
			 * System.out.println("Send EV OD"); String msg =
			 * ContextCreator.getUCBRouteStringForOD(OD);
			 * session.getRemote().sendString(msg); }
			 */
			for (String OD : ContextCreator.getUCBRouteODPairs()) {
				// JSONString
				String msg = ContextCreator.getUCBRouteStringForOD(OD);
				session.getRemote().sendString(msg);
			}
			// July,2020,JiaweiXue
			// commented due to conflicting with bus planning (the OD would change if the 
			// bus routes change
//			for (String OD : ContextCreator.getUCBRouteODPairsBus()) {
//				String msg = ContextCreator.getUCBRouteStringForODBus(OD);
//				session.getRemote().sendString(msg);
//			}
			
			// Send the first tick message
			this.sendTickSnapshot(new TickSnapshot(0));
			

		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/**
	 * This method is called whenever the remote hosts sends a message through the
	 * WebSocket to the simulation. This should be a command from the remote viewer
	 * to alter the simulation in some fashion or perhaps a request for a more
	 * detailed or specific piece of data from the simulation. This method should
	 * perform any minimal processing required to decide the intended recipient of
	 * this message within the simulation program and then hand the message over to
	 * that component.
	 * 
	 * @param message the contents of the message from the remote host.
	 * @throws JSONException
	 */
	@OnWebSocketMessage
	public void onMessage(String message) {
		// Transfer to JSON here
		JSONObject jsonMsg = new JSONObject();
		//System.out.println("Created json object");
		try {
			JSONParser parser = new JSONParser();
			jsonMsg = (JSONObject) parser.parse(message);
			if (jsonMsg.get("MSG_TYPE").equals("OD_PAIR")) {
				ContextCreator.logger.info("Received route result!");
				// Clear the current map
				// ContextCreator.routeResult_received.clear();
				JSONArray list_OD = (JSONArray) jsonMsg.get("OD");
				JSONArray list_result = (JSONArray) jsonMsg.get("result");
				int index = 0; // skip prefix
				while (index < list_OD.size()) {
					Long result = (Long) list_result.get(index);
					int result_int = result.intValue();
					String OD = (String) list_OD.get(index);
					ContextCreator.routeResult_received.put(OD, result_int);
					index +=1;
				}
			} else if (jsonMsg.get("MSG_TYPE").equals("BOD_PAIR")) {
				ContextCreator.logger.info("Received bus route result!");
				
				// Clear the current map
				// ContextCreator.routeResult_received.clear();
				JSONArray list_OD = (JSONArray) jsonMsg.get("OD");
				JSONArray list_result = (JSONArray) jsonMsg.get("result");
				int index = 0; // skip prefix
				while (index < list_OD.size()) {
					Long result = (Long) list_result.get(index);
					int result_int = result.intValue();
					String OD = (String) list_OD.get(index);
					ContextCreator.routeResult_received_bus.put(OD, result_int);
					index +=1;
				}
			} else if (jsonMsg.get("MSG_TYPE").equals("BUS_SCHEDULE")){
				ContextCreator.logger.info("Received bus schedules!");
				JSONArray list_routename = (JSONArray) jsonMsg.get("Bus_routename");
				JSONArray list_route = (JSONArray) jsonMsg.get("Bus_route");
				JSONArray list_gap = (JSONArray) jsonMsg.get("Bus_gap");
				JSONArray list_num = (JSONArray) jsonMsg.get("Bus_num");
				Long hour = Long.valueOf((String) jsonMsg.get("Bus_currenthour"));
				int newhour=hour.intValue();
				
				if (prevhour < newhour) { // New schedules
					prevhour = newhour;
					// Json array to array list
					int array_size=list_num.size();
					ArrayList<Integer> newRouteName = new ArrayList<Integer>(array_size);
					ArrayList<Integer> newBusNum = new ArrayList<Integer>(array_size);
					ArrayList<Integer> newBusGap = new ArrayList<Integer>(array_size);
					ArrayList<ArrayList<Integer>> newRoutes = new ArrayList<ArrayList<Integer>>(array_size);
					int index = 0; // skip prefix                               
	                while (index < list_num.size()) {  
	                	int bus_num_int = 0;
	                	if (list_num.get(index) instanceof Number) {
	                		bus_num_int = ((Number)list_num.get(index)).intValue();
	                	}
						if (bus_num_int>0) {
							// Verify the data, the last stop is the same as the start
							@SuppressWarnings("unchecked")
							ArrayList<Long> route = (ArrayList<Long>)list_route.get(index);
							if(route.get(0).intValue() == route.get(route.size() - 1).intValue()) {
								newBusNum.add(bus_num_int);
							    Double gap = (Double) list_gap.get(index);
							    int gap_int = gap.intValue();
							    // multiply by 60 for seconds
							    newBusGap.add(gap_int);
							    Long routename = (Long) list_routename.get(index);
							    int list_routename_int = routename.intValue();
							    newRouteName.add(list_routename_int);
							    int route_size=route.size() - 1;
							    ArrayList<Integer> route_int = new ArrayList<Integer>(route_size);
						        int index_route=0;
						        while (index_route < route_size) {
						         int route_int_i = route.get(index_route).intValue();
						         route_int.add(route_int_i-1);
						         index_route+=1;
						        }
							    newRoutes.add(route_int);
							}
						}
						index +=1;
					}
					ContextCreator.busSchedule.updateEvent(newhour,newRouteName, newRoutes, newBusNum, newBusGap);
					ContextCreator.receiveNewBusSchedule = true;
				}
			}
		} catch (ParseException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Sends the given tick snapshot over the connection as a message after
	 * converting it into a String suitable for the body of a socket message.
	 * 
	 * @param tick the tick snapshot to be written to the output file.
	 * @throws IOException if any error occurred sending the tick.
	 */
	public void sendTickSnapshot(TickSnapshot tick) throws IOException {
		if (tick == null) {
			return;
		}

		// Get the message representation of this tick
		String message = Connection.createTickMessage(tick);

		if (message.trim().length() < 1) {
			// There was no data in the tick to send
			return;
		}

		// Check the connection is open and ready for sending
		if (session == null || !session.isOpen()) {
			throw new IOException("Socket session is not open.");
		}

		// Send the message over the socket
		session.getRemote().sendString(message);
	}

	/**
	 * Returns the socket message representation of the given tick.
	 * 
	 * @param tick the snapshot of a tick to convert to a socket message.
	 * @return the socket message representation of the given tick.
	 */
	public static String createTickMessage(TickSnapshot tick) {
		HashMap<String,Object> jsonObj = new HashMap<String,Object>();
		jsonObj.put("MSG_TYPE", "TICK_MSG");
		ArrayList<HashMap<String, Object>> entries = new ArrayList<HashMap<String, Object>>();
		int hour = (int) (RepastEssentials.GetTickCount() / 3600 * GlobalVariables.SIMULATION_STEP_SIZE);
		
		// Send the latest progress of the simulation
		if (hour > prevhour) {
			HashMap<String, Object> entryObj = new HashMap<String, Object> ();
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
				HashMap<String, Object> entryObj = new HashMap<String, Object> ();
				entryObj.put("values", Connection.createEnergyMesssage(energyList));
				entryObj.put("TYPE", "E");
				entryObj.put("hour", hour);
				entryObj.put("ID", id);
				entries.add(entryObj);
			}

			ArrayList<Double> linkSpeed = tick.getSpeedVehicle(id);

			if (linkSpeed != null) {
				HashMap<String, Object> entryObj = new HashMap<String, Object> ();
				entryObj.put("values", Connection.createEnergyMesssage(linkSpeed));
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

	public static List<Float> createEnergyMesssage(ArrayList<Double> energyList) {
		List<Float> value_list = new ArrayList<>();

		for (Double val : energyList) {
		    value_list.add(val.floatValue());
		}

		return value_list;
	}

	/**
	 * Returns the socket message representation of the given vehicle.
	 * 
	 * @param vehicle the snapshot of a vehicle to convert into a message.
	 * @return the socket message representation of the given vehicle.
	 */
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
		float speed = vehicle.getSpeed();
		double originalX = vehicle.getOriginX();
		double originalY = vehicle.getOriginY();
		double destX = vehicle.getDestX();
		double destY = vehicle.getDestY();
		int nearlyArrived = vehicle.getNearlyArrived();
		int vehicleClass = vehicle.getvehicleClass();
		int roadID = vehicle.getRoadID();

		// Put them together into a string for the socket and return it
		return id + "," + prev_x + "," + prev_y + "," + x + "," + y + "," + speed + "," + originalX + "," + originalY
				+ "," + destX + "," + destY + "," + nearlyArrived + "," + vehicleClass + "," + roadID;
	}

	/**
	 * Returns the socket message representation of the given vehicle.
	 * 
	 * @param vehicle the snapshot of a vehicle to convert into a message.
	 * @return the socket message representation of the given vehicle.
	 */
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

	/**
	 * This is the control portion of the body of the thread which runs periodically
	 * to pull items from the data collection buffer and send them over the socket
	 * to the remote program.
	 */
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
				 
				// Get the next item from the buffer. HGehlot: I have changed from 1 to
				// GlobalVariables.FREQ_RECORD_VEH_SNAPSHOT_FORVIZ to only send the data at the
				// new frequency for viz interpolation
				double nextTick = Connection.this.currentTick + GlobalVariables.FREQ_RECORD_VEH_SNAPSHOT_FORVIZ;
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
						// the collector is stopped so no more are coming
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
				// scheduler a chance to switch contexts if necessary
				// before we loop around and grab the next buffer item
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
