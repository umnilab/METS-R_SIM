package mets_r;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import repast.simphony.essentials.RepastEssentials;
import au.com.bytecode.opencsv.CSVReader;
import mets_r.communication.ConnectionManager;
import mets_r.data.DataCollector;
import mets_r.facility.Road;

/**
 * @author: Xianyuan Zhan and Hemant Gehlot Schedules and handles the events to
 *          be executed Inherit from ARESCUE simulation
 * 
 **/
public class NetworkEventHandler {
	// Queue that store pending events, we use a treeMap and use the end time as the
	// key
	private TreeMap<Integer, ArrayList<NetworkEventObject>> runningQueue;

	// Connection manager maintains the socket server for remote programs
	@SuppressWarnings("unused")
	private static final ConnectionManager manager = ConnectionManager.getInstance();

	// Constructor: initialize everything
	public NetworkEventHandler() {
		runningQueue = new TreeMap<Integer, ArrayList<NetworkEventObject>>();
		readEventFile();
	}

	public void readEventFile() {
		// Note queue is first in first out, so the eventfile should order event start
		// time in ascending order, earliest comes first
		File eventFile = new File(GlobalVariables.EVENT_FILE);
		CSVReader csvreader = null;
		String[] nextLine;
		int startTime = 0;
		int endTime = 0;
		int eventID = 0;
		int roadID = 0;
		double value1 = 0.0d;
		double value2 = 0.0d;

		try {
			csvreader = new CSVReader(new FileReader(eventFile));
			// This is used to avoid reading the header (Data is assumed to start from the
			// second row)
			boolean readingheader = true;

			// This while loop is used to read the CSV iterating through the row
			while ((nextLine = csvreader.readNext()) != null) {
				// Do not read the first row (header)
				if (readingheader) {
					readingheader = false;

				} else {
					startTime = Math.round(Integer.parseInt(nextLine[0]) / GlobalVariables.SIMULATION_STEP_SIZE);
					endTime = Math.round(Integer.parseInt(nextLine[1]) / GlobalVariables.SIMULATION_STEP_SIZE);
					// Make StartTime and endTime divisable by EVENT_CHECK_FREQUENCY
					startTime = startTime - (startTime % GlobalVariables.EVENT_CHECK_FREQUENCY);
					endTime = endTime - (endTime % GlobalVariables.EVENT_CHECK_FREQUENCY);
					eventID = Integer.parseInt(nextLine[2]);
					roadID = Integer.parseInt(nextLine[3]);
					value1 = Double.parseDouble(nextLine[4]);
					value2 = Double.parseDouble(nextLine[5]);
					ContextCreator.logger.debug("starttime = " + startTime + "endtime =" + endTime + "eventID = "
							+ eventID + "roadID = " + roadID + "value1 =" + value1 + "value2 =" + value2);
					NetworkEventObject EventObject = new NetworkEventObject(startTime, endTime, eventID, roadID, value1,
							value2);
					GlobalVariables.newEventQueue.add(EventObject);
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// To be scheduled at every tick in Context Creator
	public void checkEvents() {
		// Get current tick
		int tickcount = (int) RepastEssentials.GetTickCount();
		// check new events
		checkNewEvents(tickcount);
		// terminate old events
		terminateRunningEvents(tickcount);
	}

	// Check new events from the event stack and add it into the running queue
	public void checkNewEvents(int tickcount) {
		boolean flag = true; // identify last usable event
		while (flag) {
			NetworkEventObject e = GlobalVariables.newEventQueue.peek();
			if (e != null) {
				if (e.startTime <= tickcount) {
					// Make the event happen
					NetworkEventObject event = this.setEvent(e, true);
					// Store event information in data buffer
					try {
						DataCollector.getInstance().recordEventSnapshot(e, 1);// Here 1 value denotes starting of event
					} catch (Throwable t) {
						// Could not log the event strating in data buffer!
						DataCollector.printDebug("ERR" + t.getMessage());
					}

					if (event != null) {
						// Add it into the running queue and remove the event from the newEventQueue and
						if (this.runningQueue.containsKey(e.endTime)) {
							// If running queue already contains a endTime entry, simply append it to the
							// arrayList
							this.runningQueue.get(e.endTime).add(e);
						} else {
							// Create a new arrayList that contains e
							ArrayList<NetworkEventObject> runningEvents = new ArrayList<NetworkEventObject>();
							runningEvents.add(e);
							this.runningQueue.put(e.endTime, runningEvents);
						}
					}
					GlobalVariables.newEventQueue.remove();
				} else {
					// If the event has start time later than current tick, no need to check the
					// rest
					flag = false;
				}
			} else {
				flag = false;
			}
		}

	}

	// Check the running events and remove it from the running queue
	public void terminateRunningEvents(int tickcount) {
		if (this.runningQueue.containsKey(tickcount)) {
			// If there are events to be ended at this tick, we terminate it
			ArrayList<NetworkEventObject> terminateEvents = this.runningQueue.get(tickcount);
			// We terminate every events in the set
			for (NetworkEventObject e : terminateEvents) {
				this.setEvent(e, false);
				// Store event information in data buffer
				try {
					DataCollector.getInstance().recordEventSnapshot(e, 2);// Here 2 value denotes ending of event
				} catch (Throwable t) {
					// could not log the event ending in data buffer!
					DataCollector.printDebug("ERR" + t.getMessage());
				}
			}
			this.runningQueue.remove(tickcount);
			terminateEvents.clear();
		}
	}

	// Make the event work in the simulation, do actual work. If mode = true, set
	// the event; if false, terminate the event
	public NetworkEventObject setEvent(NetworkEventObject event, boolean mode) {
		if (event == null)
			return null;
		// Iterate over the roads to identify the correct road object
		Iterable<Road> roadIt = ContextCreator.getRoadContext().getAllObjects();
		for (Road road : roadIt) {
			if (road.getLinkid() == event.roadID) {
				// Found the road, and we do the change
				if (mode) {
					// Set the event
					switch (event.eventID) {
					case 1: // Change speed limit
						if (!road.checkEventFlag()) {
							road.setDefaultFreeSpeed();
							road.updateFreeFlowSpeed(event.value1);
							road.setEventFlag();
							return event;
						} else {
							// If there is another event running on the same road, we need to terminate it
							NetworkEventObject conflictEvent = null;
							for (Map.Entry<Integer, ArrayList<NetworkEventObject>> entry : runningQueue.entrySet()) {
								ArrayList<NetworkEventObject> tempEventList = entry.getValue();
								// Iterate through the event list
								for (NetworkEventObject e : tempEventList) {
									if (e.roadID == event.roadID) {
										// We found a conflicting event
										conflictEvent = e;
										break;
									}
								}
							}
							ContextCreator.logger.error("Conflict events on road" + event.roadID + "\n"
									+ "Event 1: start: " + conflictEvent.startTime + " end: " + conflictEvent.endTime
									+ "\nEvent 2: start: " + event.startTime + " end: " + event.endTime);

							// Terminate the conflict event
							if (conflictEvent != null) {
								// Restore the event
								NetworkEventObject tempEvent = setEvent(conflictEvent, false);
								// Clear the running queue
								runningQueue.get(conflictEvent.endTime).remove(conflictEvent);
								// Set the new event
								tempEvent = setEvent(event, true);
								return tempEvent;
							}

						}
						// Other cases can be implemented here
					default:
						break;
					}
				} else {
					// Restore the event
					switch (event.eventID) {
					case 1: // restore speed limit
						road.updateFreeFlowSpeed(road.getDefaultFreeSpeed()); // To be moved into a buffer
																					// variable in the road
						road.restoreEventFlag();
						return event;
					// Other cases to be implemented here
					default:
						break;
					}
				}
				break;
			}
		}
		return null;
	}

}
