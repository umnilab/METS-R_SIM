package addsEVs;

import java.util.concurrent.*;

import addsEVs.ContextCreator;
import addsEVs.citycontext.Road;
import addsEVs.partition.MetisPartition;

import java.util.*;

import repast.simphony.engine.environment.RunEnvironment;


public class ThreadedScheduler {
//	private boolean roadFinishedStepping;
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
	
	public void paraStep() {
		// Load the road partitions
		ArrayList<ArrayList<Road>> PartitionedInRoads = ContextCreator.partitioner.getPartitionedInRoads();
		ArrayList<Road> PartitionedBwRoads = ContextCreator.partitioner.getPartitionedBwRoads();
		
		// Creates an list of tasks
		List<PartitionThread> tasks = new ArrayList<PartitionThread>();
		for (int i = 0; i< this.N_Partition; i++) {
			tasks.add(new PartitionThread(PartitionedInRoads.get(i), i));
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
			
//			// Step over the boundary roads
//			double start_t = System.currentTimeMillis();
//			stepBwRoads();
//			seq_time = seq_time + (int)(System.currentTimeMillis() - start_t);
			
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
	public void stepBwRoads() {
		ArrayList<Road> PartitionedBwRoads = ContextCreator.partitioner
				.getPartitionedBwRoads();
//		try {
			for (Road r : PartitionedBwRoads) {
				r.step();
			}
		/*} catch (Exception ex) {
			ex.printStackTrace();
		}*/
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
		ContextCreator.logger.info("Tick:\t" + RunEnvironment.getInstance().getCurrentSchedule().getTickCount() + 
				"\tMin para time:\t" + min_para_time + "\tMax para time\t" + max_para_time
				+ "\tAvg para time:\t" + avg_para_time + "\tSequential time:\t" + seq_time);
		
		this.min_para_time = 0;
		this.max_para_time = 0;
		this.avg_para_time = 0;
		this.seq_time = 0;
	}	
}

/* Single thread to call road's step() method */
class PartitionThread implements Callable<Integer> {
	private ArrayList<Road> RoadSet;
	private int threadID;
	
	public PartitionThread(ArrayList<Road> roadPartition, int ID) {
		this.RoadSet = roadPartition;
		this.threadID = ID;
	}
	
	public int getThreadID(){
		return this.threadID;
	}
	
	public Integer call() {
		double start_t = System.currentTimeMillis();
		try {
			for (Road r : this.RoadSet) {
				r.step();
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
//		return this.threadID;
		return (int) (System.currentTimeMillis() - start_t);
	}
}
