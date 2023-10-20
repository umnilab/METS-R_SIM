package mets_r.facility;

import java.util.ArrayList;
import java.util.List;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;

public class Signal {
	public final static int Green = 0;
	public final static int Yellow = 1;
	public final static int Red = 2;
	
	private int ID;
	private int state; // state of the signal light
	private ArrayList<Integer> phaseTime;  // GreenYellowRed, note this is cumulative
	// e.g., 21 step of green, 3s of yellow, 20s of red
	// will be 21, 3, 20
    private int currentTime;
    private int nextUpdateTime;
    
    public Signal(int id, List<Integer> phaseTime, int currentTime) {
    	this.ID = id;
    	this.phaseTime = new ArrayList<Integer>();
    	this.currentTime = (int) (currentTime/GlobalVariables.SIMULATION_STEP_SIZE);
    	
    	int tmp = 0;
    	int index = 0;
    	this.state = -1;
    	for(int onePhaseTime: phaseTime) {
    		int onePhaseTick = (int) (onePhaseTime/GlobalVariables.SIMULATION_STEP_SIZE);
    		tmp += onePhaseTick;
    		this.phaseTime.add(onePhaseTick);
    		if((tmp>this.currentTime) && this.state == -1) {
    			this.state = index;
    			this.nextUpdateTime = tmp - this.currentTime;
    		}
    		index += 1;
    	}
    }
    
    // API's for update the phase
    // TODO: data collector + customizable signal
    public void step() {
        this.currentTime = this.nextUpdateTime;
        this.state = (this.state + 1) % 3;
        this.nextUpdateTime = this.currentTime + this.phaseTime.get(this.state);
        ContextCreator.scheduleNextEvent(this, this.getNextUpdateTime());
    }
    
    // Another API's for update the signal time, used for multi-thread mode
    public void step2() {
    	this.currentTime += GlobalVariables.SIMULATION_SIGNAL_REFRESH_INTERVAL;
    	if(this.currentTime >= this.nextUpdateTime) {
    		this.state = (this.state + 1) % 3;
    		this.nextUpdateTime += this.phaseTime.get(this.state);
    	}
    }

	public int getID() {
		return ID;
	}

	public int getState() {
		return state;
	}
    
	public int getNextUpdateTime() {
		return this.nextUpdateTime;
	}
	
	public int getNextState() {
		return (this.state + 1) % 3;
	}
    
}
