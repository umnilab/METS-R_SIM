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
	
	// Get phase durations in ticks
	public ArrayList<Integer> getPhaseTick() {
		return this.phaseTick;
	}
	
	// Set the signal to a specific phase
	// phaseTime: time offset in seconds from the start of the phase (0 means start of the phase)
	public boolean setPhase(int targetPhase, int phaseTime) {
		if (targetPhase < 0 || targetPhase > 2) {
			return false;
		}
		
		int phaseTimeTick = (int) (phaseTime / GlobalVariables.SIMULATION_STEP_SIZE);
		int phaseDuration = this.phaseTick.get(targetPhase);
		
		// Validate phaseTime is within phase duration
		if (phaseTimeTick < 0 || phaseTimeTick >= phaseDuration) {
			phaseTimeTick = 0; // Default to start of phase if invalid
		}
		
		this.state = targetPhase;
		this.currentTick = ContextCreator.getCurrentTick();
		this.nextUpdateTick = this.currentTick + (phaseDuration - phaseTimeTick);
		
		return true;
	}
	
	// Update phase timing (green, yellow, red durations in seconds)
	public boolean updatePhaseTiming(List<Integer> phaseTime) {
		if (phaseTime == null || phaseTime.size() != 3) {
			return false;
		}
		
		this.phaseTick.clear();
		for (int onePhaseTime : phaseTime) {
			int onePhaseTick = (int) (onePhaseTime / GlobalVariables.SIMULATION_STEP_SIZE);
			if (onePhaseTick <= 0) {
				return false; // Invalid phase time
			}
			this.phaseTick.add(onePhaseTick);
		}
		
		// Recalculate nextUpdateTick based on current state
		this.currentTick = ContextCreator.getCurrentTick();
		this.nextUpdateTick = this.currentTick + this.phaseTick.get(this.state);
		
		return true;
	}
	
	// Get current tick
	public int getCurrentTick() {
		return this.currentTick;
	}
	
	// Set a complete new phase plan
	// phaseTime: list of [greenTime, yellowTime, redTime] in seconds
	// startPhase: the phase to start from (0=Green, 1=Yellow, 2=Red)
	// phaseOffset: time offset in seconds from the start of the startPhase
	public boolean setPhasePlan(List<Integer> phaseTime, int startPhase, int phaseOffset) {
		if (phaseTime == null || phaseTime.size() != 3) {
			return false;
		}
		if (startPhase < 0 || startPhase > 2) {
			return false;
		}
		
		// Update phase durations
		this.phaseTick.clear();
		for (int onePhaseTime : phaseTime) {
			int onePhaseTick = (int) (onePhaseTime / GlobalVariables.SIMULATION_STEP_SIZE);
			if (onePhaseTick <= 0) {
				return false; // Invalid phase time
			}
			this.phaseTick.add(onePhaseTick);
		}
		
		// Set starting state
		this.state = startPhase;
		
		// Calculate phase offset in ticks
		int phaseOffsetTick = (int) (phaseOffset / GlobalVariables.SIMULATION_STEP_SIZE);
		int phaseDuration = this.phaseTick.get(startPhase);
		
		// Validate phaseOffset is within phase duration
		if (phaseOffsetTick < 0 || phaseOffsetTick >= phaseDuration) {
			phaseOffsetTick = 0; // Default to start of phase if invalid
		}
		
		// Set current tick and next update tick
		this.currentTick = ContextCreator.getCurrentTick();
		this.nextUpdateTick = this.currentTick + (phaseDuration - phaseOffsetTick);
		
		return true;
	}
	
	// Set phase plan with phase durations in ticks directly (for more precise control)
	// phaseTickDurations: list of [greenTicks, yellowTicks, redTicks] in simulation ticks
	// startPhase: the phase to start from (0=Green, 1=Yellow, 2=Red)
	// tickOffset: tick offset from the start of the startPhase
	public boolean setPhasePlanInTicks(List<Integer> phaseTickDurations, int startPhase, int tickOffset) {
		if (phaseTickDurations == null || phaseTickDurations.size() != 3) {
			return false;
		}
		if (startPhase < 0 || startPhase > 2) {
			return false;
		}
		
		// Update phase durations directly in ticks
		this.phaseTick.clear();
		for (int onePhaseTick : phaseTickDurations) {
			if (onePhaseTick <= 0) {
				return false; // Invalid phase tick
			}
			this.phaseTick.add(onePhaseTick);
		}
		
		// Set starting state
		this.state = startPhase;
		
		int phaseDuration = this.phaseTick.get(startPhase);
		
		// Validate tickOffset is within phase duration
		if (tickOffset < 0 || tickOffset >= phaseDuration) {
			tickOffset = 0; // Default to start of phase if invalid
		}
		
		// Set current tick and next update tick
		this.currentTick = ContextCreator.getCurrentTick();
		this.nextUpdateTick = this.currentTick + (phaseDuration - tickOffset);
		
		return true;
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
    		
    		if((tmp > this.currentTick) && this.state == -1) {
    			this.state = index;
    			this.nextUpdateTick = tmp;
    		}
    		
    		index += 1;
    	}
	}
    
}
