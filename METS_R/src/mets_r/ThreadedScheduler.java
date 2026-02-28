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

	public ThreadedScheduler(int N_threads) {
		this.N_threads = N_threads;
		this.executor = Executors.newFixedThreadPool(this.N_threads);
		this.N_Partition = GlobalVariables.N_Partition;

		this.min_para_time = 0;
		this.max_para_time = 0;
		this.avg_para_time = 0;
		this.seq_time = 0;
	}

	public void paraRoadStep() {		
		// Load the partitions, each partition is a subgraph of the road network
		ArrayList<ArrayList<Road>> partitionedInRoads = ContextCreator.partitioner.getPartitionedInRoads();
		// Creates tasks to run road.step() function in each partition 
		List<PartitionRoadThreadPart1> tasks = new ArrayList<PartitionRoadThreadPart1>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks.add(new PartitionRoadThreadPart1(partitionedInRoads.get(i), i));
		}

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
			ex.printStackTrace();
		}
		
		List<PartitionRoadThreadPart2> tasks2 = new ArrayList<PartitionRoadThreadPart2>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks2.add(new PartitionRoadThreadPart2(partitionedInRoads.get(i), i));
		}

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
			ex.printStackTrace();
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
		
		ContextCreator.getVehicleContext().executeGlobalTransfers();
		
	}

	public void paraZoneStep() {
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			z.stepPart1();
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
			ex.printStackTrace();
		}
		
	}

	public void paraChargingStationStep() {
		// Load the partitions, each partition is a subset of Charging stations
		ArrayList<ArrayList<ChargingStation>> patitionChargingStations = ContextCreator.partitioner
				.getpartitionedChargingStations();
		// Creates tasks to run chargingStation.step() function in each partition
		List<PartitionChargingStationThread> tasks = new ArrayList<PartitionChargingStationThread>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks.add(new PartitionChargingStationThread(patitionChargingStations.get(i), i));
		}

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
			ex.printStackTrace();
		}
		
		for(ChargingStation cs: ContextCreator.getChargingStationContext().getAll()) {
			cs.stepPart2();
		}
	}
	
	public void paraSignalStep() {
		// Load the partitions, each partition is a subset of Charging stations
		ArrayList<ArrayList<Signal>> patitionSignals = ContextCreator.partitioner
				.getpartitionedSignals();
		// Creates tasks to run the first chargingStation.step() function in each partition
		List<PartitionSignalThread> tasks = new ArrayList<PartitionSignalThread>();
		for (int i = 0; i < this.N_Partition; i++) {
			tasks.add(new PartitionSignalThread(patitionSignals.get(i), i));
		}
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
			ex.printStackTrace();
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
		try {
			for (Road r : this.RoadSet) {
				r.stepPart1();
			}
		} catch (Exception ex) {
			ex.printStackTrace();
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
		try {
			for (Road r : this.RoadSet) {
				r.stepPart2();
			}
		} catch (Exception ex) {
			ex.printStackTrace();
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
		} catch (Exception ex) {
			ex.printStackTrace();
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
		} catch (Exception ex) {
			ex.printStackTrace();
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
		} catch (Exception ex) {
			ex.printStackTrace();
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
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return (int) (System.currentTimeMillis() - start_t);
	}
}
