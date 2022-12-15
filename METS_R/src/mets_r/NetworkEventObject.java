package mets_r;

/**
 * @author: Xianyuan Zhan Event object that holds event information Inherit from
 *          ARESCUE simulation
 * 
 **/
public class NetworkEventObject {
	// Fields in the event data file
	public int startTime;
	public int endTime;
	public int eventID;
	public int roadID;
	public double value1;
	public double value2;
	// Field to be loaded from simulation
	public double defaultValue;

	public NetworkEventObject(int startTime, int endTime, int eventID, int roadID, double value1, double value2) {
		this.startTime = startTime;
		this.endTime = endTime;
		this.eventID = eventID;
		this.roadID = roadID;
		this.value1 = value1;
		this.value2 = value2;
		// Set rest of the object to default
		this.defaultValue = -1.0;
	}
}
