package mets_r;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.RoadContext;
import mets_r.facility.Signal;
import mets_r.facility.Zone;

/** Parallel scheduler with a barrier between the two road phases. */
public class ThreadedScheduler {
	private final ExecutorService executor;
	private final int nPartitions;
	private final int workerCount;
	private final boolean profilingEnabled;
	private final Phaser workerBarrier;
	private final Throwable[] workerFailures;
	private final RoadPartitionTask[] roadPart1Tasks;
	private final RoadPartitionTask[] roadPart2Tasks;
	private final IntersectionPartitionTask[] intersectionTasks;
	private final ZonePartitionTask[] zonePart2Tasks;
	private final ChargingPartitionTask[] chargingPart1Tasks;
	private final SignalPartitionTask[] signalTasks;
	private volatile Runnable[] activeTasks;
	private volatile boolean workersShutdown;

	private volatile String activeStage = "idle";
	private volatile int activeStageTick = -1;
	private volatile long activeStageStartMs = 0;
	private volatile String lastFinishedStage = "none";
	private volatile int lastFinishedStageTick = -1;
	private volatile long lastFinishedStageMs = 0;

	private int lastRoadStepTick = -1;
	private int lastZoneStepTick = -1;
	private int lastChargingStationStepTick = -1;
	private int lastSignalStepTick = -1;

	private volatile long roadPart1Nanos;
	private volatile long roadPart2Nanos;
	private volatile long intersectionNanos;
	private volatile long activeRoadPartitionNanos;
	private volatile long activeRoadRefreshNanos;
	private volatile long globalTransferNanos;
	private volatile long zoneNanos;
	private volatile long signalNanos;
	private volatile long chargingNanos;
	private volatile long roadStepCount;
	private volatile long zoneStepCount;
	private volatile long signalStepCount;
	private volatile long chargingStepCount;
	private volatile RoadMetricsSnapshot latestRoadMetricsSnapshot;

	public ThreadedScheduler(int nThreads) {
		this.nPartitions = Math.max(1, GlobalVariables.N_Partition);
		this.workerCount = Math.min(Math.max(1, nThreads), this.nPartitions);
		this.executor = Executors.newFixedThreadPool(this.workerCount);
		this.profilingEnabled = GlobalVariables.ENABLE_SCHEDULER_PROFILING;
		this.workerBarrier = new Phaser(this.workerCount + 1);
		this.workerFailures = new Throwable[this.workerCount];
		this.roadPart1Tasks = new RoadPartitionTask[this.nPartitions];
		this.roadPart2Tasks = new RoadPartitionTask[this.nPartitions];
		this.intersectionTasks = GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK
				? new IntersectionPartitionTask[this.nPartitions]
				: new IntersectionPartitionTask[0];
		this.zonePart2Tasks = new ZonePartitionTask[this.nPartitions];
		this.chargingPart1Tasks = new ChargingPartitionTask[this.nPartitions];
		this.signalTasks = new SignalPartitionTask[this.nPartitions];
		for (int i = 0; i < this.nPartitions; i++) {
			this.roadPart1Tasks[i] = new RoadPartitionTask(i, true);
			this.roadPart2Tasks[i] = new RoadPartitionTask(i, false);
			if (GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) {
				this.intersectionTasks[i] = new IntersectionPartitionTask(i);
			}
			this.zonePart2Tasks[i] = new ZonePartitionTask(i);
			this.chargingPart1Tasks[i] = new ChargingPartitionTask(i);
			this.signalTasks[i] = new SignalPartitionTask(i);
		}
		for (int i = 0; i < this.workerCount; i++) {
			this.executor.execute(new PersistentPartitionWorker(i));
		}
	}

	public synchronized void resetTickGuards() {
		this.lastRoadStepTick = -1;
		this.lastZoneStepTick = -1;
		this.lastChargingStationStepTick = -1;
		this.lastSignalStepTick = -1;
		this.roadPart1Nanos = 0L;
		this.roadPart2Nanos = 0L;
		this.intersectionNanos = 0L;
		this.activeRoadPartitionNanos = 0L;
		this.activeRoadRefreshNanos = 0L;
		this.globalTransferNanos = 0L;
		this.zoneNanos = 0L;
		this.signalNanos = 0L;
		this.chargingNanos = 0L;
		this.roadStepCount = 0L;
		this.zoneStepCount = 0L;
		this.signalStepCount = 0L;
		this.chargingStepCount = 0L;
		this.latestRoadMetricsSnapshot = null;
	}

	private boolean claimRoadTick() {
		synchronized (this) {
			int tick = ContextCreator.getCurrentTick();
			if (this.lastRoadStepTick == tick) return false;
			this.lastRoadStepTick = tick;
			return true;
		}
	}

	private boolean claimZoneTick() {
		synchronized (this) {
			int tick = ContextCreator.getCurrentTick();
			if (this.lastZoneStepTick == tick) return false;
			this.lastZoneStepTick = tick;
			return true;
		}
	}

	private boolean claimChargingTick() {
		synchronized (this) {
			int tick = ContextCreator.getCurrentTick();
			if (this.lastChargingStationStepTick == tick) return false;
			this.lastChargingStationStepTick = tick;
			return true;
		}
	}

	private boolean claimSignalTick() {
		synchronized (this) {
			int tick = ContextCreator.getCurrentTick();
			if (this.lastSignalStepTick == tick) return false;
			this.lastSignalStepTick = tick;
			return true;
		}
	}

	private void beginStage(String stage) {
		this.activeStage = stage;
		this.activeStageTick = ContextCreator.getCurrentTick();
		this.activeStageStartMs = System.currentTimeMillis();
	}

	private void endStage(String stage) {
		long now = System.currentTimeMillis();
		long duration = this.activeStageStartMs == 0 ? -1 : now - this.activeStageStartMs;
		if (duration > 30000) {
			ContextCreator.logger.warn("ThreadedScheduler slow stage " + stage
					+ " tick=" + this.activeStageTick + " durationMs=" + duration);
		}
		this.lastFinishedStage = stage;
		this.lastFinishedStageTick = this.activeStageTick;
		this.lastFinishedStageMs = now;
		this.activeStage = "idle";
		this.activeStageStartMs = 0;
	}

	private long profileStart() {
		return this.profilingEnabled ? System.nanoTime() : 0L;
	}

	private long elapsed(long start) {
		return this.profilingEnabled ? System.nanoTime() - start : 0L;
	}

	public LinkedHashMap<String, Object> getStatus() {
		LinkedHashMap<String, Object> status = new LinkedHashMap<String, Object>();
		long now = System.currentTimeMillis();
		status.put("activeStage", this.activeStage);
		status.put("activeStageTick", this.activeStageTick);
		status.put("activeStageAgeMs", this.activeStageStartMs == 0 ? -1 : now - this.activeStageStartMs);
		status.put("lastFinishedStage", this.lastFinishedStage);
		status.put("lastFinishedStageTick", this.lastFinishedStageTick);
		status.put("lastFinishedStageAgeMs", this.lastFinishedStageMs == 0 ? -1 : now - this.lastFinishedStageMs);
		status.put("activeRoadStepping", GlobalVariables.ACTIVE_ROAD_STEPPING);
		status.put("profilingEnabled", this.profilingEnabled);
		if (GlobalVariables.ACTIVE_ROAD_STEPPING && ContextCreator.getRoadContext() != null) {
			status.put("activeRoadCount", ContextCreator.getRoadContext().getActiveRoadCount());
		}
		if (this.profilingEnabled) {
			LinkedHashMap<String, Object> nanos = new LinkedHashMap<String, Object>();
			nanos.put("roadPart1", this.roadPart1Nanos);
			nanos.put("roadPart2", this.roadPart2Nanos);
			nanos.put("intersections", this.intersectionNanos);
			nanos.put("activeRoadPartitioning", this.activeRoadPartitionNanos);
			nanos.put("activeRoadRefresh", this.activeRoadRefreshNanos);
			nanos.put("globalTransfers", this.globalTransferNanos);
			nanos.put("zones", this.zoneNanos);
			nanos.put("signals", this.signalNanos);
			nanos.put("charging", this.chargingNanos);
			status.put("cumulativeNanos", nanos);
			LinkedHashMap<String, Object> counts = new LinkedHashMap<String, Object>();
			counts.put("road", this.roadStepCount);
			counts.put("zone", this.zoneStepCount);
			counts.put("signal", this.signalStepCount);
			counts.put("charging", this.chargingStepCount);
			status.put("profiledCalls", counts);
		}
		return status;
	}

	public void paraRoadStep() {
		if (!claimRoadTick()) return;
		int currentTick = ContextCreator.getCurrentTick();
		boolean collectRoadMetrics = isRoadMetricsTick(currentTick);
		long partitionStart = profileStart();
		ArrayList<ArrayList<Road>> partitions = getRoadStepPartitions();
		this.activeRoadPartitionNanos += elapsed(partitionStart);
		for (int i = 0; i < this.nPartitions; i++) {
			List<Road> roads = i < partitions.size() ? partitions.get(i) : Collections.<Road>emptyList();
			this.roadPart1Tasks[i].setRoads(roads);
			this.roadPart2Tasks[i].setRoads(roads);
		}

		long stageStart = profileStart();
		beginStage("road.part1");
		try {
			submitAndAwait(this.roadPart1Tasks);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler road.part1 failed", ex);
		} finally {
			this.roadPart1Nanos += elapsed(stageStart);
			endStage("road.part1");
		}

		stageStart = profileStart();
		beginStage("road.part2");
		try {
			submitAndAwait(this.roadPart2Tasks);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler road.part2 failed", ex);
		} finally {
			this.roadPart2Nanos += elapsed(stageStart);
			endStage("road.part2");
		}

		stageStart = profileStart();
		beginStage("vehicle.globalTransfers");
		try {
			ContextCreator.getVehicleContext().executeGlobalTransfers();
		} catch (Throwable ex) {
			ContextCreator.logger.error("ThreadedScheduler vehicle.globalTransfers failed", ex);
		} finally {
			this.globalTransferNanos += elapsed(stageStart);
			endStage("vehicle.globalTransfers");
		}

		if (collectRoadMetrics) {
			this.latestRoadMetricsSnapshot = collectRoadMetricsForTick(currentTick);
		}

		if (GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) {
			stageStart = profileStart();
			beginStage("intersection.collision");
			try {
				ArrayList<ArrayList<Integer>> intersectionPartitions =
						ContextCreator.getRoadContext()
								.getActiveIntersectionPartitions(this.nPartitions);
				for (int i = 0; i < this.nPartitions; i++) {
					this.intersectionTasks[i].setIntersectionIDs(
							i < intersectionPartitions.size()
									? intersectionPartitions.get(i)
									: Collections.<Integer>emptyList());
				}
				submitAndAwait(this.intersectionTasks);
			} catch (Exception ex) {
				ContextCreator.logger.error(
						"ThreadedScheduler intersection.collision failed", ex);
			} finally {
				this.intersectionNanos += elapsed(stageStart);
				endStage("intersection.collision");
			}
		}

		if (GlobalVariables.ACTIVE_ROAD_STEPPING) {
			long refreshStart = profileStart();
			ContextCreator.getRoadContext().refreshActiveRoadPartitions(partitions);
			this.activeRoadRefreshNanos += elapsed(refreshStart);
		}
		if (this.profilingEnabled) this.roadStepCount++;
	}

	/** Return the active-road metrics accumulated by the partition workers. */
	public RoadMetricsSnapshot getRoadMetricsSnapshot(int currentTick) {
		RoadMetricsSnapshot snapshot = this.latestRoadMetricsSnapshot;
		if (snapshot != null && snapshot.tick == currentTick) return snapshot;
		return collectRoadMetricsForTick(currentTick);
	}

	private RoadMetricsSnapshot collectRoadMetricsForTick(int currentTick) {
		ArrayList<ArrayList<Road>> partitions = ContextCreator.partitioner
				.getActiveRoadPartitions(ContextCreator.getRoadContext(), currentTick);
		for (int i = 0; i < this.nPartitions; i++) {
			List<Road> roads = i < partitions.size() ? partitions.get(i)
					: Collections.<Road>emptyList();
			this.roadPart1Tasks[i].setRoads(roads);
			this.roadPart1Tasks[i].setMetricCollection(ContextCreator.agg_logger != null);
		}
		return collectRoadMetricsNow(currentTick);
	}

	private RoadMetricsSnapshot collectRoadMetricsNow(int currentTick) {
		try {
			submitAndAwait(this.roadPart1Tasks);
			this.latestRoadMetricsSnapshot = mergeRoadMetrics(currentTick, this.roadPart1Tasks);
			return this.latestRoadMetricsSnapshot;
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler road.metrics failed", ex);
			return RoadMetricsSnapshot.empty(currentTick);
		} finally {
			for (RoadPartitionTask task : this.roadPart1Tasks) {
				task.clearMetricCollection();
			}
		}
	}

	/** Fallback for explicitly single-threaded runs; still scans active roads only. */
	public static RoadMetricsSnapshot collectActiveRoadMetricsSequential(int currentTick) {
		RoadMetricsAccumulator metrics = new RoadMetricsAccumulator();
		RoadContext roadContext = ContextCreator.getRoadContext();
		if (roadContext != null) {
			for (Road road : roadContext.getActiveRoadsSnapshot()) {
				metrics.add(road, roadContext, ContextCreator.agg_logger != null);
			}
		}
		return RoadMetricsSnapshot.from(currentTick, metrics);
	}

	private static boolean isRoadMetricsTick(int currentTick) {
		int interval = GlobalVariables.METRICS_DISPLAY_INTERVAL;
		return (GlobalVariables.ENABLE_AGGREGATE_WRITE || GlobalVariables.ENABLE_METRICS_DISPLAY)
				&& interval > 0 && currentTick >= 0 && currentTick % interval == 0;
	}

	private static RoadMetricsSnapshot mergeRoadMetrics(int currentTick,
			RoadPartitionTask[] tasks) {
		RoadMetricsAccumulator merged = new RoadMetricsAccumulator();
		for (RoadPartitionTask task : tasks) {
			merged.merge(task.getMetrics());
		}
		return RoadMetricsSnapshot.from(currentTick, merged);
	}

	private ArrayList<ArrayList<Road>> getRoadStepPartitions() {
		if (!GlobalVariables.ACTIVE_ROAD_STEPPING) {
			return ContextCreator.partitioner.getPartitionedInRoads();
		}
		return ContextCreator.partitioner.getActiveRoadPartitions(
				ContextCreator.getRoadContext(), ContextCreator.getCurrentTick());
	}

	public void paraZoneStep() {
		if (!claimZoneTick()) return;
		long totalStart = profileStart();
		beginStage("zone.part1");
		try {
			for (Zone zone : ContextCreator.getZoneContext().getAll()) zone.stepPart1();
		} catch (Throwable ex) {
			ContextCreator.logger.error("ThreadedScheduler zone.part1 failed", ex);
		} finally {
			endStage("zone.part1");
		}

		ArrayList<ArrayList<Zone>> partitions = ContextCreator.partitioner.getpartitionedZones();
		for (int i = 0; i < this.nPartitions; i++) {
			this.zonePart2Tasks[i].setZones(i < partitions.size() ? partitions.get(i) : Collections.<Zone>emptyList());
		}
		beginStage("zone.part2");
		try {
			submitAndAwait(this.zonePart2Tasks);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler zone.part2 failed", ex);
		} finally {
			endStage("zone.part2");
			this.zoneNanos += elapsed(totalStart);
		}
		if (this.profilingEnabled) this.zoneStepCount++;
	}

	public void paraChargingStationStep() {
		if (!claimChargingTick()) return;
		long totalStart = profileStart();
		ArrayList<ArrayList<ChargingStation>> partitions = ContextCreator.partitioner.getpartitionedChargingStations();
		for (int i = 0; i < this.nPartitions; i++) {
			this.chargingPart1Tasks[i].setStations(i < partitions.size()
					? partitions.get(i) : Collections.<ChargingStation>emptyList());
		}
		beginStage("charging.part1");
		try {
			submitAndAwait(this.chargingPart1Tasks);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler charging.part1 failed", ex);
		} finally {
			endStage("charging.part1");
		}
		beginStage("charging.part2");
		try {
			for (ChargingStation station : ContextCreator.getChargingStationContext().getAll()) station.stepPart2();
		} catch (Throwable ex) {
			ContextCreator.logger.error("ThreadedScheduler charging.part2 failed", ex);
		} finally {
			endStage("charging.part2");
			this.chargingNanos += elapsed(totalStart);
		}
		if (this.profilingEnabled) this.chargingStepCount++;
	}

	public void paraSignalStep() {
		if (!claimSignalTick()) return;
		long totalStart = profileStart();
		ArrayList<ArrayList<Signal>> partitions = ContextCreator.partitioner.getpartitionedSignals();
		for (int i = 0; i < this.nPartitions; i++) {
			this.signalTasks[i].setSignals(i < partitions.size() ? partitions.get(i) : Collections.<Signal>emptyList());
		}
		beginStage("signal");
		try {
			submitAndAwait(this.signalTasks);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler signal failed", ex);
		} finally {
			this.signalNanos += elapsed(totalStart);
			endStage("signal");
		}
		if (this.profilingEnabled) this.signalStepCount++;
	}

	private synchronized void submitAndAwait(Runnable[] tasks) throws Exception {
		if (this.workersShutdown) throw new IllegalStateException();
		if (tasks == null || tasks.length < this.nPartitions) {
			throw new IllegalArgumentException();
		}
		boolean interrupted = Thread.interrupted();
		for (int i = 0; i < this.workerCount; i++) this.workerFailures[i] = null;
		this.activeTasks = tasks;
		this.workerBarrier.arriveAndAwaitAdvance();
		this.workerBarrier.arriveAndAwaitAdvance();
		this.activeTasks = null;
		interrupted |= Thread.interrupted();
		if (interrupted) {
			Thread.currentThread().interrupt();
			throw new InterruptedException();
		}
		throwWorkerFailure();
	}

	private void throwWorkerFailure() throws Exception {
		for (int i = 0; i < this.workerCount; i++) {
			Throwable failure = this.workerFailures[i];
			if (failure instanceof Exception) throw (Exception) failure;
			if (failure instanceof Error) throw (Error) failure;
			if (failure != null) throw new RuntimeException(failure);
		}
	}

	public synchronized void shutdownScheduler() {
		if (this.workersShutdown) return;
		this.workersShutdown = true;
		this.activeTasks = null;
		this.workerBarrier.arriveAndAwaitAdvance();
		this.workerBarrier.arriveAndAwaitAdvance();
		this.executor.shutdown();
	}

	private final class PersistentPartitionWorker implements Runnable {
		private final int workerID;

		PersistentPartitionWorker(int workerID) {
			this.workerID = workerID;
		}

		public void run() {
			while (true) {
				workerBarrier.arriveAndAwaitAdvance();
				if (workersShutdown) {
					workerBarrier.arriveAndAwaitAdvance();
					return;
				}
				Runnable[] tasks = activeTasks;
				Throwable failure = null;
				for (int i = this.workerID; i < nPartitions; i += workerCount) {
					try {
						tasks[i].run();
					} catch (Throwable ex) {
						if (failure == null) failure = ex;
					}
				}
				workerFailures[this.workerID] = failure;
				workerBarrier.arriveAndAwaitAdvance();
			}
		}
	}

	public void reportTime() {
		if (this.profilingEnabled) {
			ContextCreator.logger.info("ThreadedScheduler cumulative profile: " + getStatus().get("cumulativeNanos"));
		}
	}

	private static class RoadPartitionTask implements Runnable {
		private final int partitionID;
		private final boolean part1;
		private List<Road> roads = Collections.emptyList();
		private boolean collectLinkMetrics;
		private boolean metricsOnly;
		private RoadMetricsAccumulator metrics;

		RoadPartitionTask(int partitionID, boolean part1) {
			this.partitionID = partitionID;
			this.part1 = part1;
		}

		void setRoads(List<Road> roads) { this.roads = roads; }

		void setMetricCollection(boolean collectLinkMetrics) {
			this.collectLinkMetrics = collectLinkMetrics;
			this.metricsOnly = true;
			this.metrics = new RoadMetricsAccumulator();
		}

		void clearMetricCollection() {
			this.collectLinkMetrics = false;
			this.metricsOnly = false;
			this.metrics = null;
		}

		RoadMetricsAccumulator getMetrics() { return this.metrics; }

		public void run() {
			if (this.metricsOnly) {
				RoadContext roadContext = ContextCreator.getRoadContext();
				for (Road road : this.roads) {
					try {
						this.metrics.add(road, roadContext, this.collectLinkMetrics);
					} catch (Throwable ex) {
						int roadID = road == null ? -1 : road.getID();
						ContextCreator.logger.error("road.metrics partition " + this.partitionID
								+ " failed on road " + roadID, ex);
					}
				}
				return;
			}
			for (Road road : this.roads) {
				try {
					if (this.part1) road.stepPart1(); else road.stepPart2();
				} catch (Throwable ex) {
					int roadID = road == null ? -1 : road.getID();
					int vehicleCount = road == null ? -1 : road.getVehicleNum();
					ContextCreator.logger.error("road.part" + (this.part1 ? "1" : "2")
							+ " partition " + this.partitionID + " failed on road " + roadID
							+ " vehicles=" + vehicleCount, ex);
				}
			}
		}
	}

	private static final class RoadMetricsAccumulator {
		int vehicleOnRoad;
		final ArrayList<RoadMetricRecord> roadRecords = new ArrayList<RoadMetricRecord>();

		void add(Road road, RoadContext roadContext, boolean collectLinkMetrics) {
			if (road == null || roadContext == null) return;
			if (collectLinkMetrics) {
				int currentFlow = road.getAndResetCurrentFlow();
				if (currentFlow > 0) {
					this.roadRecords.add(new RoadMetricRecord(road.getID(), currentFlow,
							road.calcSpeed(), road.getAndResetCurrentEnergy()));
				}
			}
			if (!road.hasActiveVehicles()) return;
			this.vehicleOnRoad += roadContext.getQueryableVehicleCount(road);
		}

		void merge(RoadMetricsAccumulator other) {
			if (other == null) return;
			this.vehicleOnRoad += other.vehicleOnRoad;
			this.roadRecords.addAll(other.roadRecords);
		}
	}

	public static final class RoadMetricRecord {
		public final int roadID;
		public final int currentFlow;
		public final double speed;
		public final double currentEnergy;

		private RoadMetricRecord(int roadID, int currentFlow, double speed,
				double currentEnergy) {
			this.roadID = roadID;
			this.currentFlow = currentFlow;
			this.speed = speed;
			this.currentEnergy = currentEnergy;
		}
	}

	public static final class RoadMetricsSnapshot {
		public final int tick;
		public final int vehicleOnRoad;
		public final List<RoadMetricRecord> roadRecords;

		private RoadMetricsSnapshot(int tick, RoadMetricsAccumulator metrics) {
			this.tick = tick;
			this.vehicleOnRoad = metrics.vehicleOnRoad;
			metrics.roadRecords.sort((a, b) -> Integer.compare(a.roadID, b.roadID));
			this.roadRecords = Collections.unmodifiableList(
					new ArrayList<RoadMetricRecord>(metrics.roadRecords));
		}

		private static RoadMetricsSnapshot from(int tick, RoadMetricsAccumulator metrics) {
			return new RoadMetricsSnapshot(tick, metrics);
		}

		private static RoadMetricsSnapshot empty(int tick) {
			return new RoadMetricsSnapshot(tick, new RoadMetricsAccumulator());
		}
	}

	private static class IntersectionPartitionTask implements Runnable {
		private final int partitionID;
		private List<Integer> intersectionIDs = Collections.emptyList();

		IntersectionPartitionTask(int partitionID) {
			this.partitionID = partitionID;
		}

		void setIntersectionIDs(List<Integer> intersectionIDs) {
			this.intersectionIDs = intersectionIDs;
		}

		public void run() {
			for (Integer intersectionID : this.intersectionIDs) {
				try {
					if (intersectionID != null) {
						ContextCreator.getRoadContext()
								.processIntersectionState(intersectionID.intValue());
					}
				} catch (Throwable ex) {
					ContextCreator.logger.error("intersection.collision partition "
							+ this.partitionID + " failed on intersection "
							+ intersectionID, ex);
				}
			}
		}
	}

	private static class ZonePartitionTask implements Runnable {
		private final int partitionID;
		private List<Zone> zones = Collections.emptyList();
		ZonePartitionTask(int partitionID) { this.partitionID = partitionID; }
		void setZones(List<Zone> zones) { this.zones = zones; }
		public void run() {
			try { for (Zone zone : this.zones) zone.stepPart2(); }
			catch (Throwable ex) { ContextCreator.logger.error("zone.part2 partition " + this.partitionID + " failed", ex); }
		}
	}

	private static class ChargingPartitionTask implements Runnable {
		private final int partitionID;
		private List<ChargingStation> stations = Collections.emptyList();
		ChargingPartitionTask(int partitionID) { this.partitionID = partitionID; }
		void setStations(List<ChargingStation> stations) { this.stations = stations; }
		public void run() {
			try { for (ChargingStation station : this.stations) station.stepPart1(); }
			catch (Throwable ex) { ContextCreator.logger.error("charging.part1 partition " + this.partitionID + " failed", ex); }
		}
	}

	private static class SignalPartitionTask implements Runnable {
		private final int partitionID;
		private List<Signal> signals = Collections.emptyList();
		SignalPartitionTask(int partitionID) { this.partitionID = partitionID; }
		void setSignals(List<Signal> signals) { this.signals = signals; }
		public void run() {
			try { for (Signal signal : this.signals) signal.step(); }
			catch (Throwable ex) { ContextCreator.logger.error("signal partition " + this.partitionID + " failed", ex); }
		}
	}
}
