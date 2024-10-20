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

package galois.partition;

import galois.objects.graph.IntGraph;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

public class MetisPartition {
	private int nPartition;
	private ArrayList<ArrayList<Road>> partitionedInRoads;
	private ArrayList<Road> partitionedBwRoads;
	private ArrayList<ArrayList<Zone>> partitionedZones;
	private ArrayList<ArrayList<ChargingStation>> partitionedChargingStation;
	private ArrayList<ArrayList<Signal>> partitionedSignals;
	private ArrayList<Integer> backgroundLoads;
	private int partitionDuration; // how old is the current partition when next partitioning occurs

	public MetisPartition(int nparts) {
		this.nPartition = nparts;
	}

	public ArrayList<ArrayList<Road>> getPartitionedInRoads() {
		return this.partitionedInRoads;
	}

	public ArrayList<Road> getPartitionedBwRoads() {
		return this.partitionedBwRoads;
	}

	public ArrayList<ArrayList<Zone>> getpartitionedZones() {
		return this.partitionedZones;
	}

	public ArrayList<ArrayList<ChargingStation>> getpartitionedChargingStations() {
		return this.partitionedChargingStation;
	}
	
	public ArrayList<ArrayList<Signal>> getpartitionedSignals(){
		return this.partitionedSignals;
	}

	public void first_run() throws NumberFormatException, ExecutionException {
		this.partitionedInRoads = new ArrayList<ArrayList<Road>>();
		this.partitionedBwRoads = new ArrayList<Road>();
		this.partitionedZones = new ArrayList<ArrayList<Zone>>();
		this.partitionedChargingStation = new ArrayList<ArrayList<ChargingStation>>();
		this.partitionedSignals = new ArrayList<ArrayList<Signal>>();
		for (int i = 0; i < this.nPartition; i++) {
			partitionedZones.add(new ArrayList<Zone>());
			partitionedChargingStation.add(new ArrayList<ChargingStation>());
			partitionedSignals.add(new ArrayList<Signal>());
		}
		this.partitionDuration = 0;
		this.backgroundLoads = new ArrayList<Integer>();

		// Partition Zone by prospect demand
		ArrayList<Double> totRequest = new ArrayList<Double>();
		for (int i = 0; i < this.nPartition; i++) {
			totRequest.add(0.0);
		}
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			// Find the partition with the lowest weight
			double minWeight = GlobalVariables.FLT_INF;
			int targetInd = 0;
			for (int i = 0; i < this.nPartition; i++) {
				if (minWeight > totRequest.get(i)) {
					minWeight = totRequest.get(i);
					targetInd = i;
				}
			}
			// Add the zone to the target partition
			this.partitionedZones.get(targetInd).add(z);
			// Update the weight of the partition
			totRequest.set(targetInd, (totRequest.get(targetInd)
					+ ContextCreator.demand_per_zone.get(z.getIntegerID())/GlobalVariables.HOUR_OF_DEMAND));
		}

		// Partition Charging stations by num of chargers
		ArrayList<Integer> totCharger = new ArrayList<Integer>();
		for (int i = 0; i < this.nPartition; i++) {
			totCharger.add(0);
		}
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			// Find the partition with the lowest weight
			double minWeight = GlobalVariables.FLT_INF;
			int targetInd = 0;
			for (int i = 0; i < this.nPartition; i++) {
				if (minWeight > totCharger.get(i)) {
					minWeight = totCharger.get(i);
					targetInd = i;
				}
			}
			// Add the zone to the target partition
			this.partitionedChargingStation.get(targetInd).add(cs);
			// Update the weight of the partition
			totCharger.set(targetInd, totCharger.get(targetInd) + cs.capacity());
		}
		
		// Partition Signals by num of signals
		ArrayList<Integer> totSignal = new ArrayList<Integer>();
		for (int i = 0; i< this.nPartition; i++) {
			totSignal.add(0);
		}
		
		for (Signal s : ContextCreator.getSignalContext().getAll()) {
			// Find the partition with the lowest weight
			double minWeight = GlobalVariables.FLT_INF;
			int targetInd = 0;
			for (int i = 0; i < this.nPartition; i++) {
				if (minWeight > totSignal.get(i)) {
					minWeight = totSignal.get(i);
					targetInd = i;
				}
			}
			// Add the zone to the target partition
			this.partitionedSignals.get(targetInd).add(s);
			// Update the weight of the partition
			totSignal.set(targetInd, totSignal.get(targetInd) + 1);
		}
		
		for (int i = 0; i < this.nPartition; i++) {
			backgroundLoads.add(totRequest.get(i).intValue() + totCharger.get(i) + totSignal.get(i));
		}
		
		GaliosGraphConverter<?> graphConverter = new GaliosGraphConverter<Object>();
		MetisGraph metisGraph = graphConverter.RepastToGaliosGraph(true);
		System.gc(); // For garbage collection
		IntGraph<MetisNode> resultGraph = partition(metisGraph, nPartition);
		
		// Calling GaliosToRepastGraph method for testing
		graphConverter.GaliosToRepastGraph(resultGraph, nPartition);

		// Testing retrieving the partitioned results
		this.partitionedInRoads = graphConverter.getPartitionedInRoads();
		this.partitionedBwRoads = graphConverter.getPartitionedBwRoads();
		
		this.partitionDuration = GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL;
	}

	public void check_run() throws NumberFormatException, ExecutionException {
		if (this.partitionDuration <= GlobalVariables.SIMULATION_MAX_PARTITION_REFRESH_INTERVAL) {
			/* Get the total number of vehicles in the network */
			int TotVehNum = 0;
			for (Road road : ContextCreator.getRoadContext().getAll()) {
				TotVehNum += road.getVehicleNum();
			}

			if (TotVehNum >= GlobalVariables.THRESHOLD_VEHICLE_NUMBER) {
				this.run();
			} else {
				this.partitionDuration += GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL;
			}
		} else {
			this.run();
		}
	}

	public void run() throws NumberFormatException, ExecutionException {
		GaliosGraphConverter<?> graphConverter = new GaliosGraphConverter<Object>();
		MetisGraph metisGraph = graphConverter.RepastToGaliosGraph(false);
		System.gc(); // For gabage collection

		IntGraph<MetisNode> resultGraph = partition(metisGraph, nPartition);
		graphConverter.GaliosToRepastGraph(resultGraph, nPartition);

		// Retrieving the partitioned results
		this.partitionedInRoads = graphConverter.getPartitionedInRoads();
		this.partitionedBwRoads = graphConverter.getPartitionedBwRoads();
		this.partitionDuration = GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL;
	}

	/**
	 * KMetis Algorithm
	 */
	public IntGraph<MetisNode> partition(MetisGraph metisGraph, int nparts)
			throws ExecutionException, ArrayIndexOutOfBoundsException {
		int coarsenTo =  (int) Math.max(metisGraph.getGraph().size() / (40 * Math.log(nparts)), 20 * (nparts)); // Number of coarsen nodes
		int maxVertexWeight = (int) (1.5 * ((metisGraph.getGraph().size()) / (double) coarsenTo));
		Coarsener coarsener = new Coarsener(false, coarsenTo, maxVertexWeight);
		MetisGraph mcg = coarsener.coarsen(metisGraph);
		MetisGraph.nparts = nparts;
		float[] totalPartitionWeights = new float[nparts];
		Arrays.fill(totalPartitionWeights, 1 / (float) nparts);
		maxVertexWeight = (int) (1.5 * ((mcg.getGraph().size()) / Coarsener.COARSEN_FRACTION));
		PMetis pmetis = new PMetis(20, maxVertexWeight);
		pmetis.mlevelRecursiveBisection(mcg, nparts, totalPartitionWeights, 0, 0);
		
//		mcg.getGraph().map(new LambdaVoid<GNode<MetisNode>>() {
//			@Override
//			public void call(GNode<MetisNode> node) {
//				System.out.println(node.getData().getNodeId() +","+ node.getData().getPartition());
//			}
//		});
		
		// We can just run KWayRefiner for future steps
		KWayRefiner refiner = new KWayRefiner();
		Arrays.fill(totalPartitionWeights, 1 / (float) nparts);
		refiner.refineKWay(mcg, metisGraph, totalPartitionWeights, (float) 1.03, nparts);
		
//		metisGraph.getGraph().map(new LambdaVoid<GNode<MetisNode>>() {
//			@Override
//			public void call(GNode<MetisNode> node) {
//				System.out.println(node.getData().getNodeId() +","+ node.getData().getPartition());
//			}
//		});
		return metisGraph.getGraph();
	}

	public void verify(MetisGraph metisGraph) {
		if (!metisGraph.verify()) {
			ContextCreator.logger.error("KMetis verify not passed!");
		}
		ContextCreator.logger.debug("KMetis okay");
	}
	
	public int getBackgroundLoad(int i) {
		return this.backgroundLoads.get(i);
	}
}
