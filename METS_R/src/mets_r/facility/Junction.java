package mets_r.facility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;


public class Junction {
	/* Constants */
	public final static int NoControl = 0;
	public final static int Yield = 1;
	public final static int StopSign = 2;
	public final static int StaticSignal = 3;
	public final static int DynamicSignal = 4; // Placeholder for intelligent signal control 
	public final static int Priority = 5;
	
	/* Private variables */
	private int ID;
	private Coordinate coord;
	private ArrayList<Integer> upStreamRoads;
	private ArrayList<Integer> downStreamRoads;
	
	// Key1: Map<Road1_ID, Key 2: Road2_ID>;
	// Value: estimated routing delay in simulation ticks. This is deliberately
	// separate from mandatoryStopDelay: routing costs must not force a vehicle to
	// stop at a yield or priority movement.
	private Map<Integer, Map<Integer, Integer>> delay;
	// Value: minimum stationary time in simulation ticks required before the
	// movement may enter the junction (for example, a stop sign).
	private Map<Integer, Map<Integer, Integer>> mandatoryStopDelay;
	// Value: Signal
	private Map<Integer, Map<Integer, Signal>> signals;
	private int controlType;
	
	public Junction(int id) {
		this.ID = id;
		this.upStreamRoads = new ArrayList<Integer>();
		this.downStreamRoads = new ArrayList<Integer>();
		this.delay = new HashMap<Integer, Map<Integer, Integer>>();
		this.mandatoryStopDelay = new HashMap<Integer, Map<Integer, Integer>>();
		this.signals = new HashMap<Integer, Map<Integer, Signal>>();
		this.controlType = Junction.NoControl; // no control by default
	}

	@Override
	public String toString() {
		return "Junction " + this.ID + " at: " + this.coord.toString();
	}

	public int getID() {
		return ID;
	}

	public void setID(int id) {
		this.ID = id;
	}
	
	public int getControlType() {
		return this.controlType;
	}

	public void setControlType(int control) {
		this.controlType = control;
	}

	public Coordinate getCoord() {
		return this.coord;
	}
	
	public void setCoord(Coordinate coord) {
		this.coord = coord;
	}

	public ArrayList<Integer> getUpStreamRoads() {
		return this.upStreamRoads;
	}
	
	public ArrayList<Integer> getDownStreamRoads() {
		return this.downStreamRoads;
	}

	public void addUpStreamRoad(int road) {
		if(!ContextCreator.getRoadContext().contains(road)) ContextCreator.logger.error("The to-add upstream road does not exist.");
		if(!this.upStreamRoads.contains(road)) {
			this.upStreamRoads.add(road);
		}
	}
	
	public void addDownStreamRoad(int road) {
		if(!ContextCreator.getRoadContext().contains(road)) ContextCreator.logger.error("The to-add downstream road does not exist.");
		if(!this.downStreamRoads.contains(road)) {
			this.downStreamRoads.add(road);
		}
	}
	
	public int getDelay(int upStreamRoadID, int downStreamRoadID) {
		Map<Integer, Integer> downstreamDelays = this.delay.get(upStreamRoadID);
		Integer delayValue = downstreamDelays == null
				? null : downstreamDelays.get(downStreamRoadID);
		if (delayValue != null) return delayValue.intValue();
//    	ContextCreator.logger.warn("No link found in junction: "+ this.getID() +
//    			" between road: "+ upStreamRoadID + "," + downStreamRoadID);
    	return 0;
	}
	
	public Map<Integer, Map<Integer, Integer>> getDelay(){
		return this.delay;
	}
	
	public void setDelay(int upStreamRoadID, int downStreamRoadID, int delay) {
		Map<Integer, Integer> downstreamDelays = this.delay.get(upStreamRoadID);
		if (downstreamDelays == null) {
			downstreamDelays = new HashMap<Integer,Integer>();
			this.delay.put(upStreamRoadID, downstreamDelays);
		}
		downstreamDelays.put(downStreamRoadID, delay);
	}

	public int getMandatoryStopDelay(int upStreamRoadID, int downStreamRoadID) {
		Map<Integer, Integer> downstreamDelays =
				this.mandatoryStopDelay.get(upStreamRoadID);
		Integer delayValue = downstreamDelays == null
				? null : downstreamDelays.get(downStreamRoadID);
		return delayValue == null ? 0 : delayValue.intValue();
	}

	public Map<Integer, Map<Integer, Integer>> getMandatoryStopDelay() {
		return this.mandatoryStopDelay;
	}

	public void setMandatoryStopDelay(int upStreamRoadID, int downStreamRoadID,
			int delayTicks) {
		int normalizedDelay = Math.max(0, delayTicks);
		if (this.mandatoryStopDelay.containsKey(upStreamRoadID)) {
			this.mandatoryStopDelay.get(upStreamRoadID)
					.put(downStreamRoadID, normalizedDelay);
		} else {
			Map<Integer, Integer> downstreamDelays = new HashMap<Integer, Integer>();
			downstreamDelays.put(downStreamRoadID, normalizedDelay);
			this.mandatoryStopDelay.put(upStreamRoadID, downstreamDelays);
		}
	}
	
	public int getSignalState(int upStreamRoadID, int downStreamRoadID) {
		Signal signal = getSignal(upStreamRoadID, downStreamRoadID);
		return signal == null ? 0 : signal.getState();
	}
	
	public Signal getSignal(int upStreamRoadID, int downStreamRoadID) {
		Map<Integer, Signal> downstreamSignals = this.signals.get(upStreamRoadID);
		return downstreamSignals == null ? null
				: downstreamSignals.get(downStreamRoadID);
	}
	
	public void setSignal(int upStreamRoadID, int downStreamRoadID, Signal signal) {
		if(this.signals.containsKey(upStreamRoadID)) {
	    	this.signals.get(upStreamRoadID).put(downStreamRoadID, signal);
	    }
		else {
			Map<Integer, Signal> tmpSignal = new HashMap<Integer,Signal>();
			tmpSignal.put(downStreamRoadID, signal);
			this.signals.put(upStreamRoadID, tmpSignal);
		}
	}
}
