package mets_r.data.output;

import java.io.IOException;

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

	public synchronized void stopCollecting() {
		JsonOutputWriter jsonWriter = this.jsonOutputWriter;
		BinaryTrajectoryOutputWriter binaryWriter = this.binaryTrajectoryOutputWriter;
		// Keep consumers registered until they finish so an in-flight buffer
		// cleanup cannot discard snapshots that the writers still need.
		ContextCreator.dataCollector.stopDataCollection();
		boolean interrupted = false;
		IOException failure = null;
		try {
			// Drain both writers even when one fails. Preserve their failures so
			// the controller never receives a successful end acknowledgement.
			for (int writerIndex = 0; writerIndex < 2; writerIndex++) {
				while (true) {
					try {
						if (writerIndex == 0 && jsonWriter != null) {
							jsonWriter.awaitCompletion();
						}
						if (writerIndex == 1 && binaryWriter != null) {
							binaryWriter.awaitCompletion();
						}
						break;
					} catch (InterruptedException e) {
						// Restore interruption only after both writers finish.
						interrupted = true;
					} catch (IOException e) {
						if (failure == null) failure = e;
						else failure.addSuppressed(e);
						break;
					}
				}
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		if (failure != null) {
			throw new IllegalStateException("Failed to finalize trajectory output", failure);
		}
		if (jsonWriter != null) {
			ContextCreator.dataCollector.deregisterDataConsumer(jsonWriter);
			this.jsonOutputWriter = null;
		}
		if (binaryWriter != null) {
			ContextCreator.dataCollector.deregisterDataConsumer(binaryWriter);
			this.binaryTrajectoryOutputWriter = null;
		}
	}

	public void startTick() {
		ContextCreator.dataCollector.startTickCollection(ContextCreator.getCurrentTick());
	}

	public void stopTick() {
		ContextCreator.dataCollector.stopTickCollection();
	}
}
