package mets_r.facility;

import mets_r.ContextCreator;

/**
 * 
 * Context which holds node objects
 * 
 * @author Zengxiang Lei
 *
 */

public class NodeContext extends FacilityContext<Node> {
	public NodeContext() {
		super("NodeContext");
		ContextCreator.logger.info("NodeContext creation");
	}
}
