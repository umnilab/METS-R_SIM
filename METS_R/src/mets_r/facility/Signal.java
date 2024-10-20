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
	private ArrayList<Integer> phaseTick;  // GreenYellowRed, the unit is tick
	// e.g., 21s of green, 3s of yellow, 20s of red
	// will be 21, 3, 20
    private int currentTick;
    private int nextUpdateTick;
    
    public Signal(int id, List<Integer> phaseTime, int currentTime) {
    	this.ID = id;
    	this.phaseTick = new ArrayList<Integer>();
    	this.currentTick = (int) (currentTime/GlobalVariables.SIMULATION_STEP_SIZE);
    	this.state = -1;
    	this.initialization(phaseTime);
    }
    
    // Step function
    public void step() {
    	this.currentTick = ContextCreator.getCurrentTick();
    	if (this.currentTick > this.nextUpdateTick) {
    		ContextCreator.logger.warn("The signal update is called in a wrong tick, Signal ID:" + this.getID() + " currentTIck:" + this.currentTick + " nextUpdateTick: "+ this.nextUpdateTick);
    	}
    	if(this.currentTick >= this.nextUpdateTick) {
            this.goNextPhase();
            this.nextUpdateTick = this.currentTick + this.phaseTick.get(this.state);
    	}
    }
    
    // Step function for parallel update
    public void step2() {
    	this.currentTick += GlobalVariables.SIMULATION_SIGNAL_REFRESH_INTERVAL;
    	if(this.currentTick >= this.nextUpdateTick) {
    		this.goNextPhase();
    		this.nextUpdateTick = this.currentTick + this.phaseTick.get(this.state);
    	}
    }
    
    // API for update the phase
    public void goNextPhase() {
    	this.state = (this.state + 1) % 3;
    }
    
	public int getID() {
		return ID;
	}

	public int getState() {
		ContextCreator.logger.info("Signal " + this.getID() + " tick: " + this.currentTick + " nextUpdateTick: " + this.nextUpdateTick + " phaseTick: "+ this.phaseTick);
		return state;
	}
    
	public int getNextUpdateTick() {
		return this.nextUpdateTick;
	}
	
	public int getNextState() {
		return (this.state + 1) % 3;
	}
	
	// Delay estimation assuming uniform arrival
	public int getDelay() {
		int stop = this.phaseTick.get(1) + this.phaseTick.get(2);
		int total = this.phaseTick.get(0) + stop;
		return (stop * stop) / (2 * total);
	}
	
	private void initialization(List<Integer> phaseTime) {
		int index = 0;
		int tmp = 0;
		for(int onePhaseTime: phaseTime) {
    		int onePhaseTick = (int) (onePhaseTime/GlobalVariables.SIMULATION_STEP_SIZE);
    		tmp += onePhaseTick;
    		if(tmp == 0) {
    			ContextCreator.logger.warn("The signal " + this.ID + " has no green phase!");
    		}
    		this.phaseTick.add(onePhaseTick);
    		
    		if((tmp >= this.currentTick) && this.state == -1) {
    			this.state = index;
    			this.nextUpdateTick = tmp;
    		}
    		
    		index += 1;
    	}
	}
    
}
