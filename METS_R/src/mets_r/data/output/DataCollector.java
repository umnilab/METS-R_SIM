package mets_r.data.output;

import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.NetworkEventObject;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.Vehicle;
import repast.simphony.engine.environment.RunEnvironment;

/**
 * 
 * 
 * This class is a collection system and data buffer for capturing all the data
 * about an METS_R execution that other pieces of code within the program (such
 * as an output file saving code, a network connection code, or some other local
 * form of analysis) can use to easily gather the results of the model without
 * intrusively interrupting or impacting its operation.
 * 
 * @author Christopher Thompson
 **/

public class DataCollector {

	/** This instance is the only data collector object in the program. */
	private static final DataCollector instance = new DataCollector();

	/** A flag for knowing if the collector is running. */
	private boolean collecting;

	/** A flag for knowing if the collector has been paused. */
	private boolean paused;

	/** The time step number of the last tick stored in the buffer. */
	private double lastTick;

	/** The double-ended queue which is the data buffer for the simulation. */
	private ConcurrentLinkedQueue<TickSnapshot> buffer;

	/** The list of registered data consumers reading the data buffer. */
	private Vector<DataConsumer> registeredConsumers;

	/** The current tick snapshot into which new data is being stored. */
	private TickSnapshot currentSnapshot;

	/** This timer manages periodically cleaning the buffer of old data. */
	private Timer cleanupTimer;

	/** This task is called periodically to clean the buffer of old data. */
	private TimerTask cleanupTask;

	/** A count of how many times the buffer cleanup method has been called. */
	private int cleanupCount;

	/**
	 * Constructs the data collection system and performs any steps necessary to
	 * start it like initializing the data buffer.
	 * 
	 * This constructor is private to prevent multiple instances of the collector
	 * being created within the program. All references to the collector should be
	 * made through DataCollector.getInstance() which will create the object the
	 * first time it is needed.
	 */
	private DataCollector() {

		// Create the data buffer
		this.buffer = new ConcurrentLinkedQueue<TickSnapshot>();

		// Create the list of registered data consumers
		this.registeredConsumers = new Vector<DataConsumer>();

		// Initialize other class variables
		this.collecting = false;
		this.paused = false;
		this.currentSnapshot = null;
		this.lastTick = -1.0;
	}

	/**
	 * Returns a reference to the singleton instance of this class.
	 * 
	 * @return a reference to the singleton instance of this class.
	 */
	public static DataCollector getInstance() {
		return DataCollector.instance;
	}

	/**
	 * Returns whether or not the data collector is currently running.
	 * 
	 * @return whether or not the data collector is currently running.
	 */
	public boolean isCollecting() {
		return this.collecting;
	}

	/**
	 * Returns whether or not the data collector is currently paused.
	 * 
	 * @return whether or not the data collector is currently paused.
	 */
	public boolean isPaused() {
		return this.paused;
	}

	/**
	 * Start any tasks needed to begin collecting data from the simulation and reset
	 * any data structures used for buffering the data. Any registered data
	 * consumers are also started (and possibly told to reset if already running).
	 */
	public void startDataCollection() {
		DataCollector.printDebug("CTRL", "START COLLECTION");

		// Resuming a previously paused collection?
		if (!this.collecting && this.paused) {
			this.resumeDataCollection();
			return;
		}

		// set the running flags to running
		this.collecting = true;
		this.paused = false;

		// We are not paused, so this is a new run.
		this.buffer = new ConcurrentLinkedQueue<TickSnapshot>();
		this.lastTick = -1.0;
		this.currentSnapshot = null;

		// Start the cleanup thread
		if (this.cleanupTask != null) {
			this.cleanupTask.cancel();
		}
		this.cleanupTimer = new Timer(true);

		this.cleanupTask = new TimerTask() {
			@Override
			public void run() {
				DataCollector.this.cleanupBuffer();
			}
		};

		long cleanupTimeout = (long) GlobalVariables.DATA_CLEANUP_REFRESH;
		this.cleanupTimer.schedule(this.cleanupTask, // the cleanup method
				cleanupTimeout * 2, // delay until first run
				cleanupTimeout); // delay between runs

		// Tell each of the consumers to start
		for (DataConsumer dc : this.registeredConsumers) {
			if (dc != null) {
				try {
					dc.startConsumer();
				} catch (Throwable t) {
					// the data consumer could not start!
				}
			}
		}
	}

	/**
	 * Tells the data collection system to stop accepting new data from the
	 * simulation and tells each of the data consumers to shut down once they have
	 * processed what remains of the buffer. The collection system continues to
	 * exist to allow the registered consumers to finish working upon what is left
	 * in the buffer. Once all the items in the buffer have been cleared, the
	 * collection system can finalize stopping.
	 */
	public void stopDataCollection() {
		DataCollector.printDebug("CTRL", "STOP COLLECTION");

		// set the running flags to stopped so no new data arrives
		this.paused = false;
		this.collecting = false;

		// Stop the cleanup thread ?
		this.cleanupTimer.cancel();

		// Tell each of the consumers to stop ?
		for (DataConsumer dc : this.registeredConsumers) {
			try {
				dc.stopConsumer();
			} catch (Throwable e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Tells the data collection system to stop accepting new data from the
	 * simulation but that data collection may continue in the future. The system
	 * only halts the acceptance of new data and does not dispose of any data
	 * structures or halt any other operations which may prevent it from continuing
	 * to collect data in the future. This means to pause the system, this method
	 * only has to change the value of two boolean flags.
	 */
	public void pauseDataCollection() {
		DataCollector.printDebug("CTRL", "PAUSE COLLECTION");
		// set the running flags to paused
		this.paused = true;
		this.collecting = false;
	}

	/**
	 * If the data collection is already running and has been paused, this method
	 * will tell the system to resume accepting new data.
	 */
	public void resumeDataCollection() {
		DataCollector.printDebug("CTRL", "RESUME COLLECTION");

		if (!this.collecting && this.paused) {
			// set the running flags to no longer paused
			this.paused = false;
			this.collecting = true;
		}
	}

	/**
	 * The data collection system prepares to begin collecting data into a new tick
	 * snapshot. All the data collected during a single tick of the simulation is
	 * collected into one snapshot object capturing the state of the model during
	 * that particular tick.
	 * 
	 * @param tickNumber the number of the time step of the simulation tick.
	 */
	public void startTickCollection(int tickNumber) {
		GlobalVariables.datacollection_start = System.currentTimeMillis();

//        if ((int)tickNumber % 100 == 0) {
//            // Print a periodic heart-beat debug statement from data buffer
//            String message = "TICK " + tickNumber + 
//                             " [" + this.buffer.size() + " ticks in buffer]";
//            DataCollector.printDebug("CTRL", message);
//        }

		// Verify the given tick number is valid
		if (tickNumber < 0 || tickNumber <= this.lastTick) {
			throw new IllegalArgumentException("Tick number invalid.");
		}

		// Create the tick snapshot object
		this.currentSnapshot = new TickSnapshot(tickNumber);

		GlobalVariables.datacollection_total += System.currentTimeMillis() - GlobalVariables.datacollection_start;
	}

	/**
	 * The data collection system finishes collecting data for the current tick and
	 * adds the object to the data buffer so that data consumers can read and begin
	 * to process it. The list of consumers is also signaled to know that a new
	 * piece of data is available.
	 */
	public void stopTickCollection() {
		// Place the current tick into the buffer if anything was recorded
		if (!this.currentSnapshot.isEmpty()) {
			this.buffer.add(this.currentSnapshot);
		}

		// Update the counter of the latest tick buffered
		this.lastTick = this.currentSnapshot.getTickNumber();

		// Remove the reference to the current tick snapshot object
		this.currentSnapshot = null;
	}

	/**
	 * Records the current state of the given vehicle object into the
	 * 
	 * @param vehicle    the vehicle for which a snapshot is being recorded.
	 * @param coordinate the current vehicle position in the simulation.
	 * @throws Throwable if an error occurs trying to record the vehicle.
	 */
	public void recordSnapshot(Vehicle vehicle, Coordinate coordinate) throws Throwable {
		// Make sure the given vehicle object is valid
		if (vehicle == null) {
			throw new IllegalArgumentException("No vehicle given.");
		}
		if (coordinate == null) {
			throw new IllegalArgumentException("no coordinate given.");
		}

		// Make sure a tick is currently being processed
		if (this.currentSnapshot == null) {
			throw new Exception("No tick snapshot being processed.");
		}

		if (vehicle.getVehicleClass() == 0) { // Normal vehicle
			this.currentSnapshot.logVehicle(vehicle, coordinate);
		}
		if (vehicle.getVehicleClass() == 1) { // EV
			// Add the vehicle to the current snapshot
			this.currentSnapshot.logEV((ElectricTaxi) vehicle, coordinate, vehicle.getState());
		}

		if (vehicle.getVehicleClass() == 2) { // Bus
			// Add the vehicle to the current snapshot
			this.currentSnapshot.logBus((ElectricBus) vehicle, coordinate);
		}

	}

	/**
	 * HG: Records starting and ending of events
	 * 
	 * @param event the event for which a snapshot is being recorded.
	 * @param type  whether it is starting or end of the event. 1: starting, 2:
	 *              ending
	 * @throws Throwable if an error occurs trying to record the event.
	 */
	public void recordEventSnapshot(NetworkEventObject event, int type) throws Throwable {
		// Make sure the given event object is valid
		if (event == null) {
			throw new IllegalArgumentException("No event given.");
		}

		// Make sure a tick is currently being processed
		if (this.currentSnapshot == null) {
			throw new Exception("No tick snapshot being processed.");
		}

		// Add the event to the current snapshot
		this.currentSnapshot.logEvent(event, type);
	}

	/**
	 * Adds the given data consumer to the list of registered data consumers. Each
	 * object in the program which needs to read data from the buffer should
	 * register a data consumer. Each data consumer processes the data buffer at its
	 * own rate, and the data buffer will keep items available in memory only as
	 * long as is necessary for all currently registered data consumers to process
	 * them.
	 * 
	 * @param consumer the data consumer to add to the list.
	 */
	public void registerDataConsumer(DataConsumer consumer) {
		// Check the data consumer exists
		if (consumer == null) {
			return;
		}

		// Check the list of consumers exists
		if (this.registeredConsumers == null) {
			this.registeredConsumers = new Vector<DataConsumer>();
		}

		// Check the consumer is not already registered in the list
		if (this.registeredConsumers.contains(consumer)) {
			return;
		}

		// Add the consumer to the list
		this.registeredConsumers.add(consumer);

		// If we are already collecting data, start the consumer, too
		if (this.isCollecting()) {
			try {
				consumer.startConsumer();
			} catch (Throwable t) {
				// The consumer not able to start consuming
			}
		}
	}

	/**
	 * Removes the given data consumer from the list of registered data consumers in
	 * the system. Each object in the program which needs to read data from the
	 * buffer should register a data consumer and should remove the consumer from
	 * the list when it has finished.
	 * 
	 * @returns whether or not the given consumer was removed from the list.
	 * @param consumer the data consumer to remove from the list.
	 */
	public boolean deregisterDataConsumer(DataConsumer consumer) {
		// Check the data consumer exists
		if (consumer == null) {
			return true;
		}

		// Check the consumer list exists
		if (this.registeredConsumers == null) {
			return false;
		}

		// Check if the consumer is in the list
		if (!this.registeredConsumers.contains(consumer)) {
			// the consumer wasn't even in the list
			return true;
		}

		// Remove the consumer from the list
		boolean removed = this.registeredConsumers.remove(consumer);

		// Return whether or not the consumer was removed from the list
		return removed;
	}

	/**
	 * Returns the tick number of the oldest item in the buffer. If no items have
	 * ever been stored in the buffer, a negative value is returned.
	 * 
	 * @return the tick number of the oldest item in the buffer.
	 */
	public double firstTickAvailable() {
		// Check the buffer even exists
		if (this.buffer == null) {
			return -1;
		}

		// Is the buffer empty?
		if (this.buffer.isEmpty()) {
			// The buffer may be empty, but has it ever collected anything?
			// If the data is still collecting data but just exhausted of
			// contents because all the consumers are working faster than
			// the simulation can produce values, return the latest tick #
			// as the next item added will be just after this and all the
			// collectors will see they've already processed this one.
			if (this.isCollecting() || this.isPaused()) {
				return this.lastTick;
			}
			// the buffer exists, it is empty, and it is not in a paused
			// state from which it can continue collecting. it is either
			// in an initial pre-collection state or a post-collection date
			// after all data has already been consumed.
			return -1;
		}

		// None of our special conditions exist, so now we can just do the
		// simple task of returning tick # of first item in buffer. whether
		// or not the buffer is currently collecting or done collecting, the
		// buffer has contents that still need to be consumed somewhere.
		TickSnapshot firstTick = this.buffer.peek();
		if (firstTick == null) {
			// This should never happen, but just in case...
			return -1;
		}
		return firstTick.getTickNumber();
	}

	/**
	 * Returns the tick number of the tick most recently added to the buffer.
	 * 
	 * @return the tick number of the tick most recently added to the buffer.
	 */
	public double lastTickAvailable() {
		return this.lastTick;
	}

	/**
	 * Returns the tick snapshot from the buffer matching the given number.
	 * 
	 * @param tickNumber the simulation time step of the snapshot desired.
	 * @return the tick snapshot from the buffer matching the given number.
	 */
	public TickSnapshot getTick(int tickNumber) {
		// Grab the closest tick we can find to the one requested (which
		// also performs basic sanity & range checks on this request).
		TickSnapshot foundTick = this.getNextTick(tickNumber);
		if (foundTick == null) {
			// The buffer has no tick at all from the requested index
			// through the end of the buffer's current contents
			return null;
		}

		// Did we find the exact tick requested
		if (foundTick.getTickNumber() == tickNumber) {
			// We found an exact match!
			return foundTick;
		} else {
			// The buffer does not contain the exact tick requested
			return null;
		}
	}

	/**
	 * Returns the tick snapshot from the buffer matching the given number if
	 * possible. If no matching tick snapshot is found, the next item in the list
	 * with the closest (greater) tick number is returned.
	 * 
	 * @param tickNumber the lowest tick number to retrieve from the list.
	 * @return the next tick in the list with at least the given number.
	 */
	public TickSnapshot getNextTick(int tickNumber) {
		// Make sure the requested tick is even in the current buffer range
		if (tickNumber > this.lastTickAvailable()) {
			return null;
		}
		// Make sure the buffer exists and is not empty
		if (this.buffer == null || this.buffer.isEmpty()) {
			return null;
		}

		// Walk through the list and look for the first matching
		// tick in the buffer which has a tick index equal to or
		// greater than the requested tick index
		for (TickSnapshot tick : this.buffer) {

			if (tick != null && tick.getTickNumber() == tickNumber) {
				return tick;
			}
		}

		// We exhausted the buffer of items without finding a tick that
		// has a time-step equal to or greater than the requested index
		TickSnapshot empty_tk = new TickSnapshot(tickNumber);
		return empty_tk;
	}

	/**
	 * This method is called periodically while the buffer has contents to check if
	 * any of the oldest items still in the buffer can be deleted yet. First the
	 * list of all registered data consumers will be polled to determine the minimum
	 * time index value still being processed from any data consumer. Then any item
	 * at the front of the buffer with an index that is less than this value is
	 * removd.
	 */
	private void cleanupBuffer() {
		// Check if the buffer exists and has any items before proceeding
		if (this.buffer == null || this.buffer.isEmpty()) {
			// If the buffer is already empty and has no possibility of
			// gaining new data items, we can go ahead and cancel the
			// timer. if the buffer restarts, the timer will be recreated.
			if (!this.isCollecting() && !this.isPaused()) {
				this.cleanupTimer.cancel();
				this.cleanupTimer = null;
				this.cleanupTask = null;
			}

			return;
		}

		// Figure out the minimum tick number to keep in the buffer. we need
		// to check the current position of each data consumer and keep track
		// of the slowest one so we know just enough buffer data to retain.
		int minimumTick = -1;
		for (DataConsumer dc : this.registeredConsumers) {
			if (dc == null) {
				continue;
			}

			if ((minimumTick == -1) || (minimumTick > dc.getTick())) {
				// This is the first consumer we have checked OR it is
				// a consumer with a tick smaller than any tick we have
				// seen yet in a previously checked consumer, so replace
				// the overall minimum tick number with this new one
				minimumTick = dc.getTick();
			}
		}

		if (minimumTick == -1) {
			// If this is still null, there were no registered data users.
			// we can safely discard anything in the buffer as we have the
			// policy that data consumers newly registered during the model
			// run will pick-up from the current simulation position, not
			// the start of the simulation data.
			minimumTick = (int) (RunEnvironment.getInstance().getCurrentSchedule().getTickCount() - 1);
		} else if (minimumTick < 0.0) {
			// At least one of the registered data consumers in the system
			// has not started yet, so we must keep all the data in the
			// buffer for when it finally finishes setup and can start
			String cleanupMsg = "Cleaning #" + ++(this.cleanupCount) + " Nothing removed.  " + this.buffer.size()
					+ " remain in buffer.";
			DataCollector.printDebug("CTRL", cleanupMsg);

			return;
		}

		// Remove items from the buffer until we get to the first tick
		// with a time-step that is equal to or greater than our minimum
		int removed = 0;
		for (TickSnapshot nextTick = this.buffer.peek(); nextTick != null; nextTick = this.buffer.peek()) {
			// If the tick is older than we need to retain, we delete it
			if (nextTick.getTickNumber() < minimumTick) {
				this.buffer.poll();
				removed++;
			} else {
				// We've reached a tick number in the buffer that at least
				// one data consumer has not yet processed so we are done
				break;
			}
		}

		// If we made it this far, we've processed through the whole
		// buffer and can go ahead and cancel these cleanup threads if
		// we already know that no more data will be collected
		if (!this.isCollecting() && !this.isPaused()) {
			this.cleanupTimer.cancel();
			this.cleanupTimer = null;
			this.cleanupTask = null;
		}

		String cleanupMsg = "Cleaning #" + ++(this.cleanupCount) + " removed " + removed + " items.";
		DataCollector.printDebug("CTRL", cleanupMsg);
	}

	/**
	 * Logs the given message to the standard output if data collection debugging
	 * has been enabled (via a constant boolean flag in the DataCollector class). If
	 * debugging is not enabled, the message is ignored.
	 * 
	 * @param msg the debugging message to print to the standard output.
	 */
	public static void printDebug(String msg) {
		DataCollector.printDebug(null, msg);
	}

	/**
	 * Logs the given message to the standard output if data collection debugging
	 * has been enabled (via a constant boolean blag in the DataCollector class)
	 * with the given custom string in the prefix. If debugging is not enabled, the
	 * message is ignored.
	 * 
	 * @param prefix the custom prefix to include at the beginning.
	 * @param msg    the debugging message to print to the standard output.
	 */
	public static void printDebug(String prefix, String msg) {
		if (GlobalVariables.DEBUG_DATA_BUFFER) {
			String debugMessage = "<<DATA";
			if (prefix != null && prefix.trim().length() > 0) {
				debugMessage += ":" + prefix;
			}
			debugMessage += ">> " + msg;

			ContextCreator.logger.debug(debugMessage);
		}
	}

	public void recordLinkSnapshot(int id, double linkConsume) {
		this.currentSnapshot.logLinkUCB(id, linkConsume);
	}

	public void recordLinkSnapshotBus(int id, double linkConsume) {
		this.currentSnapshot.logLinkUCBBus(id, linkConsume);
	}

	public void recordSpeedVehilce(int id, double linkSpeed) {
		this.currentSnapshot.logSpeedVehicle(id, linkSpeed);
	}
}
