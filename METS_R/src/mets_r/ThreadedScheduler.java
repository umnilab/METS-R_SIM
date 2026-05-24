package mets_r;

import java.util.concurrent.*;

import mets_r.ContextCreator;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;

import java.util.*;

public class ThreadedScheduler {
	private ExecutorService executor;
	private int N_Partition;
	private int N_threads;

	private int min_para_time;
	private int max_para_time;
	private int avg_para_time;
	private int seq_time;
	private volatile String activeStage = "idle";
	private volatile int activeStageTick = -1;
	private volatile long activeStageStartMs = 0;
	private volatile String lastFinishedStage = "none";
	private volatile int lastFinishedStageTick = -1;
	private volatile long lastFinishedStageMs = 0;

	// Per-tick idempotency guards: Repast's schedule.removeAction() may silently
	// leave recurring actions in its internal "on-deck" queue across reset(),
	// so the same scheduled method can fire multiple times per tick (once from
	// the new registration, once from each orphaned registration). Without these
	// guards, the roads/zones/charging/signals would be advanced multiple times
	// per tick, accelerating vehicle movement and inflating trip completions
	// across successive resets.
	private int lastRoadStepTick = -1;
	private int lastZoneStepTick = -1;
	private int lastChargingStationStepTick = -1;
	private int lastSignalStepTick = -1;

	public ThreadedScheduler(int N_threads) {
		this.N_threads = Math.max(1, N_threads);
		this.executor = Executors.newFixedThreadPool(this.N_threads);
		this.N_Partition = GlobalVariables.N_Partition;

		this.min_para_time = 0;
		this.max_para_time = 0;
		this.avg_para_time = 0;
		this.seq_time = 0;
	}

	/**
	 * Called from ContextCreator.reset() so orphaned scheduled actions from a
	 * previous run cannot fire-and-skip new actions that happen to share the
	 * same tick value across resets (getCurrentTick() is relative to initTick).
	 */
	public synchronized void resetTickGuards() {
		this.lastRoadStepTick = -1;
		this.lastZoneStepTick = -1;
		this.lastChargingStationStepTick = -1;
		this.lastSignalStepTick = -1;
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
		if (GlobalVariables.ACTIVE_ROAD_STEPPING && ContextCreator.getRoadContext() != null) {
			status.put("activeRoadCount", ContextCreator.getRoadContext().getActiveRoadCount());
		}
		return status;
	}

	public void paraRoadStep() {
		synchronized (this) {
			int currentTick = ContextCreator.getCurrentTick();
			if (this.lastRoadStepTick == currentTick) {
				return; // orphaned recurring action from a previous reset
			}
			this.lastRoadStepTick = currentTick;
		}
		// Load the partitions, each partition is a subgraph of the road network
		ArrayList<ArrayList<Road>> partitionedInRoads = getRoadStepPartitions();
		// Creates tasks to run road.step() function in each partition 
		List<PartitionRoadThreadPart1> tasks = new ArrayList<PartitionRoadThreadPart1>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks.add(new PartitionRoadThreadPart1(partitionedInRoads.get(i), i));
		}

		beginStage("road.part1");
		try {
			List<Future<Integer>> futures = executor.invokeAll(tasks);
			ArrayList<Integer> time_stat = new ArrayList<Integer>();
			for (int i = 0; i < N_Partition; i++)
				time_stat.add(futures.get(i).get());
			ArrayList<Integer> time_result = minMaxAvg(time_stat);
			min_para_time = min_para_time + time_result.get(0);
			max_para_time = max_para_time + time_result.get(1);
			avg_para_time = avg_para_time + time_result.get(2);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler road.part1 failed", ex);
		} finally {
			endStage("road.part1");
		}
		
		List<PartitionRoadThreadPart2> tasks2 = new ArrayList<PartitionRoadThreadPart2>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks2.add(new PartitionRoadThreadPart2(partitionedInRoads.get(i), i));
		}

		beginStage("road.part2");
		try {
			List<Future<Integer>> futures = executor.invokeAll(tasks2);
			ArrayList<Integer> time_stat = new ArrayList<Integer>();
			for (int i = 0; i < N_Partition; i++)
				time_stat.add(futures.get(i).get());
			ArrayList<Integer> time_result = minMaxAvg(time_stat);
			min_para_time = min_para_time + time_result.get(0);
			max_para_time = max_para_time + time_result.get(1);
			avg_para_time = avg_para_time + time_result.get(2);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler road.part2 failed", ex);
		} finally {
			endStage("road.part2");
		}
		
//		List<PartitionRoadThreadPart3> tasks3 = new ArrayList<PartitionRoadThreadPart3>();
//		for (int i = 0; i < this.N_Partition; i++) {
//			tasks3.add(new PartitionRoadThreadPart3(partitionedInRoads.get(i), i));
//		}
//
//		try {
//			List<Future<Integer>> futures = executor.invokeAll(tasks3);
//			ArrayList<Integer> time_stat = new ArrayList<Integer>();
//			for (int i = 0; i < N_Partition; i++)
//				time_stat.add(futures.get(i).get());
//			ArrayList<Integer> time_result = minMaxAvg(time_stat);
//			min_para_time = min_para_time + time_result.get(0);
//			max_para_time = max_para_time + time_result.get(1);
//			avg_para_time = avg_para_time + time_result.get(2);
//		} catch (Exception ex) {
//			ex.printStackTrace();
//		}
		
		beginStage("vehicle.globalTransfers");
		try {
			ContextCreator.getVehicleContext().executeGlobalTransfers();
		} catch (Throwable ex) {
			ContextCreator.logger.error("ThreadedScheduler vehicle.globalTransfers failed", ex);
		} finally {
			endStage("vehicle.globalTransfers");
		}

		if (GlobalVariables.ACTIVE_ROAD_STEPPING) {
			ContextCreator.getRoadContext().refreshActiveRoads(flattenRoadPartitions(partitionedInRoads));
		}
		
	}

	private ArrayList<ArrayList<Road>> getRoadStepPartitions() {
		if (!GlobalVariables.ACTIVE_ROAD_STEPPING) {
			return ContextCreator.partitioner.getPartitionedInRoads();
		}
		return ContextCreator.partitioner.partitionRoadsForCurrentPartitions(
				ContextCreator.getRoadContext().getActiveRoadsSnapshot());
	}

	private ArrayList<Road> flattenRoadPartitions(ArrayList<ArrayList<Road>> partitions) {
		ArrayList<Road> roads = new ArrayList<Road>();
		if (partitions == null) {
			return roads;
		}
		for (ArrayList<Road> partition : partitions) {
			if (partition != null) {
				roads.addAll(partition);
			}
		}
		return roads;
	}

	public void paraZoneStep() {
		synchronized (this) {
			int currentTick = ContextCreator.getCurrentTick();
			if (this.lastZoneStepTick == currentTick) {
				return;
			}
			this.lastZoneStepTick = currentTick;
		}
		beginStage("zone.part1");
		try {
			for (Zone z : ContextCreator.getZoneContext().getAll()) {
				z.stepPart1();
			}
		} catch (Throwable ex) {
			ContextCreator.logger.error("ThreadedScheduler zone.part1 failed", ex);
		} finally {
			endStage("zone.part1");
		}
		
		
		// Load the partitions, each partition is a subset of Zones
		ArrayList<ArrayList<Zone>> partitionedZones = ContextCreator.partitioner.getpartitionedZones();
//		// Creates tasks to run zone.step() function in each partition
//		List<PartitionZoneThread1> tasks1 = new ArrayList<PartitionZoneThread1>();
//		for (int i = 0; i < this.N_Partition; i++) {
//			tasks1.add(new PartitionZoneThread1(partitionedZones.get(i), i));
//		}
//		try {
//			List<Future<Integer>> futures = executor.invokeAll(tasks1);
//			ArrayList<Integer> time_stat = new ArrayList<Integer>();
//			for (int i = 0; i < N_Partition; i++)
//				time_stat.add(futures.get(i).get());
//			ArrayList<Integer> time_result = minMaxAvg(time_stat);
//			min_para_time = min_para_time + time_result.get(0);
//			max_para_time = max_para_time + time_result.get(1);
//			avg_para_time = avg_para_time + time_result.get(2);
//		} catch (Exception ex) {
//			ex.printStackTrace();
//		}
		
		// Creates tasks to run zone.step() function in each partition
		List<PartitionZoneThread2> tasks2 = new ArrayList<PartitionZoneThread2>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks2.add(new PartitionZoneThread2(partitionedZones.get(i), i));
		}
		beginStage("zone.part2");
		try {
			List<Future<Integer>> futures = executor.invokeAll(tasks2);
			ArrayList<Integer> time_stat = new ArrayList<Integer>();
			for (int i = 0; i < N_Partition; i++)
				time_stat.add(futures.get(i).get());
			ArrayList<Integer> time_result = minMaxAvg(time_stat);
			min_para_time = min_para_time + time_result.get(0);
			max_para_time = max_para_time + time_result.get(1);
			avg_para_time = avg_para_time + time_result.get(2);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler zone.part2 failed", ex);
		} finally {
			endStage("zone.part2");
		}
		
	}

	public void paraChargingStationStep() {
		synchronized (this) {
			int currentTick = ContextCreator.getCurrentTick();
			if (this.lastChargingStationStepTick == currentTick) {
				return;
			}
			this.lastChargingStationStepTick = currentTick;
		}
		// Load the partitions, each partition is a subset of Charging stations
		ArrayList<ArrayList<ChargingStation>> patitionChargingStations = ContextCreator.partitioner
				.getpartitionedChargingStations();
		// Creates tasks to run chargingStation.step() function in each partition
		List<PartitionChargingStationThread> tasks = new ArrayList<PartitionChargingStationThread>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks.add(new PartitionChargingStationThread(patitionChargingStations.get(i), i));
		}

		beginStage("charging.part1");
		try {
			List<Future<Integer>> futures = executor.invokeAll(tasks);
			ArrayList<Integer> time_stat = new ArrayList<Integer>();
			for (int i = 0; i < N_Partition; i++)
				time_stat.add(futures.get(i).get());
			ArrayList<Integer> time_result = minMaxAvg(time_stat);
			min_para_time = min_para_time + time_result.get(0);
			max_para_time = max_para_time + time_result.get(1);
			avg_para_time = avg_para_time + time_result.get(2);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler charging.part1 failed", ex);
		} finally {
			endStage("charging.part1");
		}
		
		beginStage("charging.part2");
		try {
			for(ChargingStation cs: ContextCreator.getChargingStationContext().getAll()) {
				cs.stepPart2();
			}
		} catch (Throwable ex) {
			ContextCreator.logger.error("ThreadedScheduler charging.part2 failed", ex);
		} finally {
			endStage("charging.part2");
		}
	}
	
	public void paraSignalStep() {
		synchronized (this) {
			int currentTick = ContextCreator.getCurrentTick();
			if (this.lastSignalStepTick == currentTick) {
				return;
			}
			this.lastSignalStepTick = currentTick;
		}
		// Load the partitions, each partition is a subset of Charging stations
		ArrayList<ArrayList<Signal>> patitionSignals = ContextCreator.partitioner
				.getpartitionedSignals();
		// Creates tasks to run the first chargingStation.step() function in each partition
		List<PartitionSignalThread> tasks = new ArrayList<PartitionSignalThread>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks.add(new PartitionSignalThread(patitionSignals.get(i), i));
		}
		beginStage("signal");
		try {
			List<Future<Integer>> futures = executor.invokeAll(tasks);
			ArrayList<Integer> time_stat = new ArrayList<Integer>();
			for (int i = 0; i < N_Partition; i++)
				time_stat.add(futures.get(i).get());
			ArrayList<Integer> time_result = minMaxAvg(time_stat);
			min_para_time = min_para_time + time_result.get(0);
			max_para_time = max_para_time + time_result.get(1);
			avg_para_time = avg_para_time + time_result.get(2);
		} catch (Exception ex) {
			ContextCreator.logger.error("ThreadedScheduler signal failed", ex);
		} finally {
			endStage("signal");
		}
	}

	public void shutdownScheduler() {
		executor.shutdown();
	}

	public ArrayList<Integer> minMaxAvg(ArrayList<Integer> values) {
		int min = values.get(0);
		int max = values.get(0);
		int sum = 0;

		for (int value : values) {
			min = Math.min(value, min);
			max = Math.max(value, max);
			sum += value;
		}

		int avg = sum / values.size();

		ArrayList<Integer> results = new ArrayList<Integer>();
		results.add(min);
		results.add(max);
		results.add(avg);

		return results;
	}

	public void reportTime() {
		ContextCreator.logger.info("Tick:\t" + ContextCreator.getCurrentTick()
				+ "\tMin para time:\t" + min_para_time + "\tMax para time\t" + max_para_time + "\tAvg para time:\t"
				+ avg_para_time + "\tSequential time:\t" + seq_time);

		this.min_para_time = 0;
		this.max_para_time = 0;
		this.avg_para_time = 0;
		this.seq_time = 0;
	}
}

/* A thread to call road's step() method */
class PartitionRoadThreadPart1 implements Callable<Integer> {
	private ArrayList<Road> RoadSet;
	private int threadID;

	public PartitionRoadThreadPart1(ArrayList<Road> roadPartition, int ID) {
		this.RoadSet = roadPartition;
		this.threadID = ID;
	}

	public int getThreadID() {
		return this.threadID;
	}

	public Integer call() {
		double start_t = System.currentTimeMillis();
		for (Road r : this.RoadSet) {
			try {
				r.stepPart1();
			} catch (Throwable ex) {
				int roadID = r == null ? -1 : r.getID();
				int vehicleCount = r == null ? -1 : r.getVehicleNum();
				ContextCreator.logger.error("road.part1 partition " + this.threadID
						+ " failed on road " + roadID + " vehicles=" + vehicleCount, ex);
			}
		}
		return (int) (System.currentTimeMillis() - start_t);
	}
}

/* A thread to call road's step() method */
class PartitionRoadThreadPart2 implements Callable<Integer> {
	private ArrayList<Road> RoadSet;
	private int threadID;

	public PartitionRoadThreadPart2(ArrayList<Road> roadPartition, int ID) {
		this.RoadSet = roadPartition;
		this.threadID = ID;
	}

	public int getThreadID() {
		return this.threadID;
	}

	public Integer call() {
		double start_t = System.currentTimeMillis();
		for (Road r : this.RoadSet) {
			try {
				r.stepPart2();
			} catch (Throwable ex) {
				int roadID = r == null ? -1 : r.getID();
				int vehicleCount = r == null ? -1 : r.getVehicleNum();
				ContextCreator.logger.error("road.part2 partition " + this.threadID
						+ " failed on road " + roadID + " vehicles=" + vehicleCount, ex);
			}
		}
		return (int) (System.currentTimeMillis() - start_t);
	}
}

/* A thread to call road's step() method */
//class PartitionRoadThreadPart3 implements Callable<Integer> {
//	private ArrayList<Road> RoadSet;
//	private int threadID;
//
//	public PartitionRoadThreadPart3(ArrayList<Road> roadPartition, int ID) {
//		this.RoadSet = roadPartition;
//		this.threadID = ID;
//	}
//
//	public int getThreadID() {
//		return this.threadID;
//	}
//
//	public Integer call() {
//		double start_t = System.currentTimeMillis();
//		try {
//			for (Road r : this.RoadSet) {
//				r.stepPart3();
//			}
//		} catch (Exception ex) {
//			ex.printStackTrace();
//		}
//		return (int) (System.currentTimeMillis() - start_t);
//	}
//}

/* A thread to call zones's step() method */
class PartitionZoneThread1 implements Callable<Integer> {
	private ArrayList<Zone> ZoneSet;
	private int threadID;

	public PartitionZoneThread1(ArrayList<Zone> zonePartition, int ID) {
		this.ZoneSet = zonePartition;
		this.threadID = ID;
	}

	public int getThreadID() {
		return this.threadID;
	}

	public Integer call() {
		double start_t = System.currentTimeMillis();
		try {
			for (Zone z : this.ZoneSet) {
				z.stepPart1();
			}
		} catch (Throwable ex) {
			ContextCreator.logger.error("zone.part1 partition " + this.threadID + " failed", ex);
		}
		return (int) (System.currentTimeMillis() - start_t);
	}
}

/* A thread to call zones's step() method */
class PartitionZoneThread2 implements Callable<Integer> {
	private ArrayList<Zone> ZoneSet;
	private int threadID;

	public PartitionZoneThread2(ArrayList<Zone> zonePartition, int ID) {
		this.ZoneSet = zonePartition;
		this.threadID = ID;
	}

	public int getThreadID() {
		return this.threadID;
	}

	public Integer call() {
		double start_t = System.currentTimeMillis();
		try {
			for (Zone z : this.ZoneSet) {
				z.stepPart2();
			}
		} catch (Throwable ex) {
			ContextCreator.logger.error("zone.part2 partition " + this.threadID + " failed", ex);
		}
		return (int) (System.currentTimeMillis() - start_t);
	}
}

/* A thread to call charging station's step() method */
class PartitionChargingStationThread implements Callable<Integer> {
	private ArrayList<ChargingStation> ChargingStationSet;
	private int threadID;

	public PartitionChargingStationThread(ArrayList<ChargingStation> chargingStationPartition, int ID) {
		this.ChargingStationSet = chargingStationPartition;
		this.threadID = ID;
	}

	public int getThreadID() {
		return this.threadID;
	}

	public Integer call() {
		double start_t = System.currentTimeMillis();
		try {
			for (ChargingStation cs : this.ChargingStationSet) {
				cs.stepPart1();
			}
		} catch (Throwable ex) {
			ContextCreator.logger.error("charging.part1 partition " + this.threadID + " failed", ex);
		}
		return (int) (System.currentTimeMillis() - start_t);
	}
}

/* A thread to call signal's step() method */
class PartitionSignalThread implements Callable<Integer> {
	private ArrayList<Signal> signalSet;
	private int threadID;

	public PartitionSignalThread(ArrayList<Signal> signalPartition, int ID) {
		this.signalSet = signalPartition;
		this.threadID = ID;
	}

	public int getThreadID() {
		return this.threadID;
	}

	public Integer call() {
		double start_t = System.currentTimeMillis();
		try {
			for (Signal s : this.signalSet) {
				s.step();
			}
		} catch (Throwable ex) {
			ContextCreator.logger.error("signal partition " + this.threadID + " failed", ex);
		}
		return (int) (System.currentTimeMillis() - start_t);
	}
}
