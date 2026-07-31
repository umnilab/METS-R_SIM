package mets_r.data.output;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import repast.simphony.context.DefaultContext;

/**
 * Repast context for trajectory collection. Periodic aggregate and console
 * metrics live in {@link MetricsReporter} and are scheduled independently.
 */
public class DataCollectionContext extends DefaultContext<Object> {

	private JsonOutputWriter jsonOutputWriter;
	private BinaryTrajectoryOutputWriter binaryTrajectoryOutputWriter;

	public DataCollectionContext() {
		super("DataCollectionContext");
		if (ContextCreator.dataCollector == null) {
			throw new IllegalStateException("DataCollectionContext requires ENABLE_DATA_COLLECTION=true");
		}
		if (GlobalVariables.ENABLE_JSON_WRITE) {
			this.jsonOutputWriter = new JsonOutputWriter();
			ContextCreator.dataCollector.registerDataConsumer(this.jsonOutputWriter);
		}
		if (GlobalVariables.ENABLE_TRAJECTORY_BINARY_WRITE) {
			this.binaryTrajectoryOutputWriter = new BinaryTrajectoryOutputWriter();
			ContextCreator.dataCollector.registerDataConsumer(this.binaryTrajectoryOutputWriter);
		}
	}

	public void startCollecting() {
		ContextCreator.dataCollector.startDataCollection();
	}

	public void stopCollecting() {
		JsonOutputWriter jsonWriter = this.jsonOutputWriter;
		BinaryTrajectoryOutputWriter binaryWriter = this.binaryTrajectoryOutputWriter;
		if (jsonWriter != null) {
			ContextCreator.dataCollector.deregisterDataConsumer(jsonWriter);
			this.jsonOutputWriter = null;
		}
		if (binaryWriter != null) {
			ContextCreator.dataCollector.deregisterDataConsumer(binaryWriter);
			this.binaryTrajectoryOutputWriter = null;
		}
		ContextCreator.dataCollector.stopDataCollection();
		try {
			if (jsonWriter != null) {
				jsonWriter.awaitCompletion();
			}
			if (binaryWriter != null) {
				binaryWriter.awaitCompletion();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void startTick() {
		ContextCreator.dataCollector.startTickCollection(ContextCreator.getCurrentTick());
	}

	public void stopTick() {
		ContextCreator.dataCollector.stopTickCollection();
	}
}
