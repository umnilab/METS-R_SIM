/*
Galois, a framework to exploit amorphous data-parallelism in irregular
programs.

Copyright (C) 2010, The University of Texas at Austin. All rights reserved.
UNIVERSITY EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES CONCERNING THIS SOFTWARE
AND DOCUMENTATION, INCLUDING ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR ANY
PARTICULAR PURPOSE, NON-INFRINGEMENT AND WARRANTIES OF PERFORMANCE, AND ANY
WARRANTY THAT MIGHT OTHERWISE ARISE FROM COURSE OF DEALING OR USAGE OF TRADE.
NO WARRANTY IS EITHER EXPRESS OR IMPLIED WITH RESPECT TO THE USE OF THE
SOFTWARE OR DOCUMENTATION. Under no circumstances shall University be liable
for incidental, special, indirect, direct or consequential damages or loss of
profits, interruption of business, or related expenses which may arise from use
of Software or Documentation, including but not limited to those resulting from
defects in Software and/or Documentation, or loss or inaccuracy of data of any
kind.

File: AbstractMain.java 

 */

package addsEVs.partition;

import galois.objects.graph.IntGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.Road;
import util.Launcher;

public class MetisPartition {
	private int npartition;
	private ArrayList<ArrayList<Road>> PartitionedInRoads;
	private ArrayList<Road> PartitionedBwRoads;
	private ArrayList<Integer> PartitionWeights;
	private int partition_duration; // how old is the current partition when next partitioning occurs
	
	public MetisPartition(int nparts) {
		this.npartition = nparts;
		this.PartitionedInRoads = new ArrayList<ArrayList<Road>>();
		this.PartitionedBwRoads = new ArrayList<Road>();
		this.PartitionWeights = new ArrayList<Integer>();
		this.partition_duration = 0;
	}
	
	public ArrayList<ArrayList<Road>> getPartitionedInRoads() {
		return this.PartitionedInRoads;
	}
	
	public ArrayList<Road> getPartitionedBwRoads() {
		return this.PartitionedBwRoads;
	}
	
	public void first_run() throws NumberFormatException, ExecutionException {
		GaliosGraphConverter<?> graphConverter = new GaliosGraphConverter<Object>();
		MetisGraph metisGraph = graphConverter.RepastToGaliosGraph(true);
		ContextCreator.logger.debug("Metis Running...");
		
		if (Launcher.getLauncher().isFirstRun()) {
			ContextCreator.logger.debug("Configuration");
			ContextCreator.logger.debug("-------------");
			ContextCreator.logger.debug(" Num of partitions: " + this.npartition);
			ContextCreator.logger.debug("Graph size: " + metisGraph.getGraph().size() + " nodes and " + metisGraph.getNumEdges()
					+ " edges");
		}
		
		System.gc(); // For gabage collection
		
		long time = System.nanoTime();
		Launcher.getLauncher().startTiming();
		IntGraph<MetisNode> resultGraph = partition(metisGraph, npartition);
		Launcher.getLauncher().stopTiming();
		time = (System.nanoTime() - time) / 1000000;
		
		// Calling GaliosToRepastGraph method for testing
		graphConverter.GaliosToRepastGraph(resultGraph, npartition);
		
		// Testing retrieving the partitioned results
		this.PartitionedInRoads = graphConverter.getPartitionedInRoads();
		this.PartitionedBwRoads = graphConverter.getPartitionedBwRoads();
		this.PartitionWeights = graphConverter.getPartitionWeights();
		int i;
		for (i = 0; i < this.npartition; i++){
			ContextCreator.logger.debug("Partition:\t" + i + "\tNumber of element=\t" + PartitionedInRoads.get(i).size() + "\tTotal edge weight=\t" + PartitionWeights.get(i));
			// Compute number of vehicles currently in the partition
			int totNumVeh = 0;
			int totShadowVeh = 0;
			int totRoutingVeh = 0;
			for (Road road : PartitionedInRoads.get(i)){
				totNumVeh += road.getVehicleNum();
				totShadowVeh += road.getShadowVehicleNum();
				totRoutingVeh += road.getFutureRoutingVehNum();
				
			}
			System.err.println("\t#vehicles=\t" + totNumVeh+"\t#shadow vehciles=\t"+totShadowVeh+"\t#future routing vehicles\t"+totRoutingVeh);

		}
		
		System.err.print("Boundary Roads: Number of element=\t" + PartitionedBwRoads.size());
		// Compute number of vehicles currently in the partition
		int totNumVeh = 0;
		int totShadowVeh = 0;
		int totRoutingVeh = 0;
		for (Road road : PartitionedBwRoads){
			totNumVeh += road.getVehicleNum();
			totShadowVeh += road.getShadowVehicleNum();
			totRoutingVeh += road.getFutureRoutingVehNum();
		}
		System.err.println("\t#vehicles=\t" + totNumVeh+"\t#shadow vehciles=\t"+totShadowVeh+"\t#future routing vehicles\t"+totRoutingVeh);
		
		this.partition_duration = GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL;
	}
	
	
	public void check_run() throws NumberFormatException, ExecutionException {
		if (this.partition_duration <= GlobalVariables.SIMULATION_MAX_PARTITION_REFRESH_INTERVAL){
			/* Get the total number of vehicles in the network */
			int TotVehNum = 0;
			for (Road road : ContextCreator.getRoadGeography().getAllObjects()) {
				TotVehNum += road.getVehicleNum();
			}
			
			if (TotVehNum >= GlobalVariables.THRESHOLD_VEHICLE_NUMBER) {
				this.run();
			} else {
				this.partition_duration += GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL;;
			}
		} else {
			this.run();
		}
	} 
	
	
	public void run() throws NumberFormatException, ExecutionException {
		GaliosGraphConverter<?> graphConverter = new GaliosGraphConverter<Object>();
		MetisGraph metisGraph = graphConverter.RepastToGaliosGraph(false);		
		System.gc(); // For gabage collection
		
		long time = System.nanoTime();
		Launcher.getLauncher().startTiming();
		IntGraph<MetisNode> resultGraph = partition(metisGraph, npartition);
		
		Launcher.getLauncher().stopTiming();
		time = (System.nanoTime() - time) / 1000000;
		System.err.println("mincut: " + metisGraph.getMinCut());
		System.err.println("runtime: " + time + " ms");
		System.err.println();
		
		/*// For testing only, remove after done
		if (Launcher.getLauncher().isFirstRun()) {
			verify(metisGraph);
		}*/
		
		// Calling GaliosToRepastGraph method for testing
		graphConverter.GaliosToRepastGraph(resultGraph, npartition);
		
		// Testing retrieving the partitioned results
		this.PartitionedInRoads = graphConverter.getPartitionedInRoads();
		this.PartitionedBwRoads = graphConverter.getPartitionedBwRoads();
		this.PartitionWeights = graphConverter.getPartitionWeights();
		int i;
		for (i = 0; i < this.npartition; i++){
			ContextCreator.logger.debug("Partition:\t" + i + "\tNumber of element=\t" + PartitionedInRoads.get(i).size() + "\tTotal edge weight=\t" + PartitionWeights.get(i));
			// Compute number of vehicles currently in the partition
			int totNumVeh = 0;
			int totShadowVeh = 0;
			int totRoutingVeh = 0;
			for (Road road : PartitionedInRoads.get(i)){
				totNumVeh += road.getVehicleNum();
				totShadowVeh += road.getShadowVehicleNum();
				totRoutingVeh += road.getFutureRoutingVehNum();
				
			}
			ContextCreator.logger.debug("\t#vehicles=\t" + totNumVeh+"\t#shadow vehciles=\t"+totShadowVeh+"\t#future routing vehicles\t"+totRoutingVeh);

		}
		
		System.err.print("Boundary Roads: Number of element=\t" + PartitionedBwRoads.size());
		// Compute number of vehicles currently in the partition
		int totNumVeh = 0;
		int totShadowVeh = 0;
		int totRoutingVeh = 0;
		for (Road road : PartitionedBwRoads){
			totNumVeh += road.getVehicleNum();
			totShadowVeh += road.getShadowVehicleNum();
			totRoutingVeh += road.getFutureRoutingVehNum();
		}
		System.err.println("\t#vehicles=\t" + totNumVeh+"\t#shadow vehciles=\t"+totShadowVeh+"\t#future routing vehicles\t"+totRoutingVeh);
		
		this.partition_duration = GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL;
	}

	/**
	 * KMetis Algorithm 
	 */
	public IntGraph<MetisNode> partition(MetisGraph metisGraph, int nparts) throws ExecutionException, ArrayIndexOutOfBoundsException {
		IntGraph<MetisNode> graph = metisGraph.getGraph();
		// Zhan: Number of coarsen nodes
		int coarsenTo = (int) Math.max(graph.size() / (40 * Math.log(nparts)), 20 * (nparts));
		int maxVertexWeight = (int) (1.5 * ((graph.size()) / (double) coarsenTo));
		Coarsener coarsener = new Coarsener(false, coarsenTo, maxVertexWeight);
		long time = System.nanoTime();
		MetisGraph mcg = coarsener.coarsen(metisGraph);
		time = (System.nanoTime() - time) / 1000000;
		
		ContextCreator.logger.debug("Coarsening time: " + time + " ms");
 
		MetisGraph.nparts = 2;
		float[] totalPartitionWeights = new float[nparts];
		Arrays.fill(totalPartitionWeights, 1 / (float) nparts);
		time = System.nanoTime();
		maxVertexWeight = (int) (1.5 * ((mcg.getGraph().size()) / Coarsener.COARSEN_FRACTION));
		PMetis pmetis = new PMetis(20, maxVertexWeight);
		pmetis.mlevelRecursiveBisection(mcg, nparts, totalPartitionWeights, 0, 0);
		time = (System.nanoTime() - time) / 1000000;
		ContextCreator.logger.debug("Initial partition time: " + time + " ms");
		MetisGraph.nparts = nparts;
		time = System.nanoTime();
		Arrays.fill(totalPartitionWeights, 1 / (float) nparts);
		// We can just run KWayRefiner for future steps
		KWayRefiner refiner = new KWayRefiner();
		refiner.refineKWay(mcg, metisGraph, totalPartitionWeights, (float) 1.03, nparts);
		time = (System.nanoTime() - time) / 1000000;
		
		return metisGraph.getGraph();
	}

	public void verify(MetisGraph metisGraph) {
		if (!metisGraph.verify()) {
			ContextCreator.logger.error("KMetis verify not passed!");
//			throw new IllegalStateException("KMetis failed.");
		}
		ContextCreator.logger.debug("KMetis okay");
	}
}
