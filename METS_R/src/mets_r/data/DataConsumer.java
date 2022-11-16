package mets_r.data;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 
 * Classes wishing to read data from the METS_R data collection buffer should
 * implement the methods of this interface and be registered with the
 * DataCollector instance within the program.
 * 
 * Data consumers are required to respond to simple control signals (start,
 * stop, reset, etc.) as well as provide the current tick number of the
 * simulation they are processing from the buffer. The data collection buffer
 * will attempt to manage the buffer and purge any unnecessary data. Once all
 * registered consumers have signaled they are past a particular tick in the
 * buffer (which is stored sequentially), the data collector is free to discard
 * that data item to free memory within the simulation.
 * 
 * @author Christopher Thompson (thompscs@purdue.edu)
 * @version 1.0
 * @date 28 June 2017
 */
public interface DataConsumer {

	/**
	 * Starts the consumer processing data from the buffer. If this is the first
	 * time the consumer has started or the first start of the consumer after a
	 * reset, it will begin from the first item in the buffer at that time. If the
	 * consumer is paused, it should continue operation with a call to this method
	 * from the point in the buffer where it was paused.
	 * 
	 * @throws Throwable if the consumer cannot be started.
	 */
	public void startConsumer() throws Throwable;

	/**
	 * Stops the consumer and signals that there is no intention to restart it from
	 * the current position. A stopped consumer should perform any steps necessary
	 * to finalize its operation such as closing files or other data streams and
	 * halting any internal threads it may be utilizing.
	 * 
	 * @throws Throwable if the consumer cannot be stopped.
	 */
	public void stopConsumer() throws Throwable;

	/**
	 * Temporarily stops the consumer from reading more data from the buffer after
	 * processing it's current item but does not reset it's position so that if can
	 * be started again from the position where it was halted.
	 * 
	 * This differs from a "stop" in that the position is not reset, internal data
	 * structures or state values are not discarded, and another call to "start" the
	 * consumer will resume previous operations at the current position. A consumer
	 * that has been paused should report its current position as the point at which
	 * it will continue operation as if it were still actively running.
	 * 
	 * @throws Throwable if the consumer cannot be paused.
	 */
	public void pauseConsumer() throws Throwable;

	/**
	 * Stops the data consumer if it is running and returns it to a state where it
	 * will begin processing data from the start of the buffer if it is restarted.
	 * 
	 * @throws Throwable if any problems occur reseting the consumer.
	 */
	public void resetConsumer() throws Throwable;

	/**
	 * Returns the current tick the data consumer is processing.
	 * 
	 * The value returned should be a guarantee the consumer has finished processing
	 * any buffer data with an earlier simulation tick so that the data collection
	 * system knows it is safe to delete this data from the viewpoint of this data
	 * consumer. A consumer that has not yet been started is expected to return zero
	 * or a negative value. A paused consumer should report the position at which is
	 * paused until it has been stopped as the buffer will need to know how much
	 * data to retain if it is to be restarted.
	 * 
	 * @return the current tick the data consumer is processing.
	 */
	public double getTick();

	/**
	 * Sets the current tick of the consumer to the value specified so that it will
	 * be the next value read from the buffer.
	 * 
	 * @param tick the value of the tick in the simulation to read next.
	 * @throws Throwable if the given position within the buffer is invalid.
	 */
	public void setTick(double tick) throws Throwable;

	/**
	 * Returns a collection of all available types of data consumers within the
	 * METS_R program.
	 * 
	 * @return a collection of all available types of data consumers.
	 */
	@SuppressWarnings("rawtypes")
	public static Collection<Class> getAllConsumerTypes() {

		// Create the list for holding all the class references for consumers
		ArrayList<Class> consumerList = new ArrayList<Class>();

		// Add each consumer class in the program to the list
		consumerList.add(mets_r.data.CsvOutputWriter.class);

		// Return the list of classes which are consumers
		return consumerList;
	}

}
