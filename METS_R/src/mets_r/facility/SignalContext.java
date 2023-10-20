package mets_r.facility;

import mets_r.ContextCreator;

/**
 * 
 * Context which holds signal objects
 * 
 * @author Zengxiang Lei
 *
 */

public class SignalContext extends FacilityContext<Signal> {
	public SignalContext() {
		super("SignalContext");
		ContextCreator.logger.info("SignalContext creation");
	}
}
