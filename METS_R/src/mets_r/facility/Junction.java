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
	
	/* Private variables */
	private int ID;
	private Coordinate coord;
	private ArrayList<Integer> upStreamRoads;
	private ArrayList<Integer> downStreamRoads;
	
	// Key1: Map<Road1_ID, Key 2: Road2_ID>; 
	// Value: Seconds for vehicle to wait before the junction 
	private Map<Integer, Map<Integer, Integer>> delay;
	// Value: Signal
	private Map<Integer, Map<Integer, Signal>> signals;
	private int controlType;
	
	public Junction(int id) {
		this.ID = id;
		this.upStreamRoads = new ArrayList<Integer>();
		this.downStreamRoads = new ArrayList<Integer>();
		this.delay = new HashMap<Integer, Map<Integer, Integer>>();
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
	    if(this.delay.containsKey(upStreamRoadID)) {
	    	if(this.delay.get(upStreamRoadID).containsKey(downStreamRoadID)) {
	    		return this.delay.get(upStreamRoadID).get(downStreamRoadID);
	    	}
	    }
//    	ContextCreator.logger.warn("No link found in junction: "+ this.getID() +
//    			" between road: "+ upStreamRoadID + "," + downStreamRoadID);
    	return 0;
	}
	
	public Map<Integer, Map<Integer, Integer>> getDelay(){
		return this.delay;
	}
	
	public void setDelay(int upStreamRoadID, int downStreamRoadID, int delay) {
		if(this.delay.containsKey(upStreamRoadID)) {
	    	this.delay.get(upStreamRoadID).put(downStreamRoadID, delay);
	    }
		else {
			Map<Integer, Integer> tmpDelay = new HashMap<Integer,Integer>();
			tmpDelay.put(downStreamRoadID, delay);
			this.delay.put(upStreamRoadID, tmpDelay);
		}
	}
	
	public int getSignal(int upStreamRoadID, int downStreamRoadID) {
		if(this.signals.containsKey(upStreamRoadID)) {
	    	if(this.signals.get(upStreamRoadID).containsKey(downStreamRoadID)) {
	    		return this.signals.get(upStreamRoadID).get(downStreamRoadID).getState();
	    	}
	    }
    	return 0;
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
