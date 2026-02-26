package mets_r.facility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mets_r.ContextCreator;

/**
 * 
 * Context which holds signal objects
 * 
 * @author Zengxiang Lei
 *
 */

public class SignalContext extends FacilityContext<Signal> {
	
	private Map<String, List<Integer>> signalGroup;
	
	public SignalContext() {
		super("SignalContext");
		signalGroup = new LinkedHashMap<String, List<Integer>>();
		ContextCreator.logger.info("SignalContext creation");
	}
	
	public void registerSignal(String origID, Signal signal) {
		if(!signalGroup.containsKey(origID)) {
			ArrayList<Integer> oneGroup = new ArrayList<Integer>();
			signalGroup.put(origID, oneGroup);
		}
		signalGroup.get(origID).add(signal.getID());
	}
	
	public List<Integer> getOneGroup(String origID) {
		if(signalGroup.containsKey(origID)) {
			return signalGroup.get(origID);
		}
		else {
			return null;
		}
	}
	
	public List<String> getAllGroupIDs(){
		List<String> res = new ArrayList<String>();
		for(String groupID: signalGroup.keySet()) {
			res.add(groupID);
		}
		return res;
	}
}
