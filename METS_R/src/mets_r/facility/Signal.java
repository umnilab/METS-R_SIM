package mets_r.facility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;

public class Signal {
	public final static int Green = 0;
	public final static int Yellow = 1;
	public final static int Red = 2;
	
	private int ID;
	private String groupID; // ID of the signal group
	
	private volatile int state; // state of the signal light
	private volatile List<Integer> phaseTick;  // GreenYellowRed, the unit is tick
	// e.g., 21s of green, 3s of yellow, 20s of red
	// will be 21, 3, 20
    private volatile int nextUpdateTick;
    
    public Signal(int id, String groupID, List<Integer> phaseTime, int offsetTime) {
    	this.ID = id;
    	this.groupID = groupID;
		this.phaseTick = Collections.emptyList();
		this.state = -1;
		this.initialization(phaseTime, offsetTime);
    }
    
    // Step function
    public synchronized void step() {
    	while(ContextCreator.getCurrentTick() >= this.nextUpdateTick) {
            this.goNextPhase();
            this.nextUpdateTick += this.phaseTick.get(this.state);
    	}
    }
    
    // API for update the phase
    public synchronized void goNextPhase() {
    	this.state = (this.state + 1) % 3;
    }
    
	public int getID() {
		return ID;
	}
	
	public String getGroupID() {
		return groupID;
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
		List<Integer> phases = this.phaseTick;
		int stop = phases.get(1) + phases.get(2);
		int total = phases.get(0) + stop;
		return (stop * stop) / (2 * total);
	}
	
	// Get phase durations in ticks
	public ArrayList<Integer> getPhaseTick() {
		return new ArrayList<Integer>(this.phaseTick);
	}
	
	// Set the signal to a specific phase
	// phaseTime: time offset in seconds from the start of the phase (0 means start of the phase)
	public synchronized boolean setPhase(int targetPhase, int phaseTime) {
		if (targetPhase < 0 || targetPhase > 2) {
			return false;
		}
		
		if (phaseTime < 0) return false;
		int phaseTimeTick = (int) Math.floor(phaseTime / GlobalVariables.SIMULATION_STEP_SIZE);
		int phaseDuration = this.phaseTick.get(targetPhase);
		if (phaseDuration <= 0) return false;
		phaseTimeTick = Math.floorMod(phaseTimeTick, phaseDuration);
		
		this.state = targetPhase;
		this.nextUpdateTick = ContextCreator.getCurrentTick() + (phaseDuration - phaseTimeTick);
		return true;
	}
	
	// Update phase timing (green, yellow, red durations in seconds)
	public synchronized boolean updatePhaseTiming(List<Integer> phaseTime) {
		List<Integer> validated = phaseSecondsToTicks(phaseTime);
		if (validated == null) return false;
		
		// Recalculate nextUpdateTick based on current state
		this.phaseTick = validated;
		this.nextUpdateTick = ContextCreator.getCurrentTick() + validated.get(this.state);
		return true;
	}
	
	// Set a complete new phase plan
	// phaseTime: list of [greenTime, yellowTime, redTime] in seconds
	// startPhase: the phase to start from (0=Green, 1=Yellow, 2=Red)
	// phaseOffset: time offset in seconds from the start of the startPhase
	public synchronized boolean setPhasePlan(List<Integer> phaseTime, int startPhase, int phaseOffset) {
		List<Integer> validated = phaseSecondsToTicks(phaseTime);
		if (validated == null) return false;
		if (startPhase < 0 || startPhase > 2) {
			return false;
		}
		if (phaseOffset < 0) return false;
		
		// Set starting state
		this.phaseTick = validated;
		this.state = startPhase;
		
		// Calculate phase offset in ticks
		int phaseOffsetTick = (int) Math.floor(phaseOffset / GlobalVariables.SIMULATION_STEP_SIZE);
		int phaseDuration = this.phaseTick.get(startPhase);
		phaseOffsetTick = Math.floorMod(phaseOffsetTick, phaseDuration);
		
		this.nextUpdateTick = ContextCreator.getCurrentTick() + (phaseDuration - phaseOffsetTick);
		return true;
	}
	
	// Set phase plan with phase durations in ticks directly (for more precise control)
	// phaseTickDurations: list of [greenTicks, yellowTicks, redTicks] in simulation ticks
	// startPhase: the phase to start from (0=Green, 1=Yellow, 2=Red)
	// tickOffset: tick offset from the start of the startPhase
	public synchronized boolean setPhasePlanInTicks(List<Integer> phaseTickDurations, int startPhase, int tickOffset) {
		List<Integer> validated = validatePhaseTicks(phaseTickDurations);
		if (validated == null) return false;
		if (startPhase < 0 || startPhase > 2) {
			return false;
		}
		if (tickOffset < 0) return false;
		
		// Set starting state
		this.phaseTick = validated;
		this.state = startPhase;
		
		int phaseDuration = this.phaseTick.get(startPhase);
		tickOffset = Math.floorMod(tickOffset, phaseDuration);
		
		this.nextUpdateTick = ContextCreator.getCurrentTick() + (phaseDuration - tickOffset);
		return true;
	}
	
	/* Setters for save/load support */
	public synchronized void restoreState(int state, int nextUpdateTick, ArrayList<Integer> phaseTick) {
		List<Integer> validated = validatePhaseTicks(phaseTick, true);
		if (state < 0 || state > 2 || validated == null) {
			throw new IllegalArgumentException("Invalid restored signal state for signal " + this.ID);
		}
		this.state = state;
		this.nextUpdateTick = nextUpdateTick;
		this.phaseTick = validated;
	}
	
	private synchronized void initialization(List<Integer> phaseTime, int offsetTime) {
		List<Integer> validated = phaseSecondsToTicks(phaseTime, true);
		if (validated == null) {
			throw new IllegalArgumentException("Invalid phase plan for signal " + this.ID);
		}
		this.phaseTick = validated;
		int cycle = validated.get(0) + validated.get(1) + validated.get(2);
		int offsetTick = (int) Math.floor(Math.max(0, offsetTime)
				/ GlobalVariables.SIMULATION_STEP_SIZE);
		offsetTick = Math.floorMod(offsetTick, cycle);
		int cumulative = 0;
		for (int index = 0; index < validated.size(); index++) {
			cumulative += validated.get(index);
			if (offsetTick < cumulative) {
				this.state = index;
				this.nextUpdateTick = ContextCreator.getCurrentTick()
						+ cumulative - offsetTick;
				return;
			}
		}
		throw new IllegalStateException("Could not initialize signal " + this.ID);
	}

	private static List<Integer> phaseSecondsToTicks(List<Integer> phaseTime) {
		return phaseSecondsToTicks(phaseTime, false);
	}

	private static List<Integer> phaseSecondsToTicks(
			List<Integer> phaseTime, boolean allowZero) {
		if (phaseTime == null || phaseTime.size() != 3) return null;
		ArrayList<Integer> ticks = new ArrayList<Integer>(3);
		int cycle = 0;
		for (Integer seconds : phaseTime) {
			if (seconds == null || seconds < 0 || (!allowZero && seconds == 0)) return null;
			int phaseTicks = seconds == 0 ? 0 : Math.max(1, (int) Math.ceil(
					seconds / GlobalVariables.SIMULATION_STEP_SIZE));
			ticks.add(phaseTicks);
			cycle += phaseTicks;
		}
		if (cycle <= 0) return null;
		return Collections.unmodifiableList(ticks);
	}

	private static List<Integer> validatePhaseTicks(List<Integer> phaseTicks) {
		return validatePhaseTicks(phaseTicks, false);
	}

	private static List<Integer> validatePhaseTicks(
			List<Integer> phaseTicks, boolean allowZero) {
		if (phaseTicks == null || phaseTicks.size() != 3) return null;
		ArrayList<Integer> validated = new ArrayList<Integer>(3);
		int cycle = 0;
		for (Integer ticks : phaseTicks) {
			if (ticks == null || ticks < 0 || (!allowZero && ticks == 0)) return null;
			validated.add(ticks);
			cycle += ticks;
		}
		if (cycle <= 0) return null;
		return Collections.unmodifiableList(validated);
	}

}
