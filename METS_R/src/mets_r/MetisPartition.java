package mets_r;

import java.util.ArrayList;
import java.util.Collection;

import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;

/**
 * Lightweight deterministic partitioner for METS-R simulation agents.
 *
 * The original implementation used the bundled Galois/METIS graph partitioner.
 * Road stepping now benefits more from dynamic load balancing than from static
 * graph locality, so this class keeps the old public API while assigning work
 * with weighted greedy partitioning.
 */
public class MetisPartition {
	private final int nPartition;
	private ArrayList<ArrayList<Road>> partitionedInRoads;
	private ArrayList<Road> partitionedBwRoads;
	private ArrayList<ArrayList<Zone>> partitionedZones;
	private ArrayList<ArrayList<ChargingStation>> partitionedChargingStation;
	private ArrayList<ArrayList<Signal>> partitionedSignals;
	private ArrayList<Integer> backgroundLoads;

	public MetisPartition(int nparts) {
		this.nPartition = Math.max(1, nparts);
		this.initializeEmptyPartitions();
	}

	public ArrayList<ArrayList<Road>> getPartitionedInRoads() {
		return this.partitionedInRoads;
	}

	public ArrayList<ArrayList<Road>> partitionRoadsForCurrentPartitions(Collection<Road> roads) {
		return partitionRoadsByCurrentLoad(roads);
	}

	public ArrayList<Road> getPartitionedBwRoads() {
		return this.partitionedBwRoads;
	}

	public synchronized ArrayList<ArrayList<Zone>> getpartitionedZones() {
		ArrayList<ArrayList<Zone>> snapshot = new ArrayList<ArrayList<Zone>>();
		for (ArrayList<Zone> partition : this.partitionedZones) {
			snapshot.add(new ArrayList<Zone>(partition));
		}
		return snapshot;
	}

	public synchronized void addZone(Zone zone) {
		if (zone == null) return;
		if (this.partitionedZones == null || this.partitionedZones.isEmpty()) {
			this.partitionedZones = newZonePartitions();
		}
		for (ArrayList<Zone> partition : this.partitionedZones) {
			if (partition.contains(zone)) {
				return;
			}
		}
		int bestPartition = 0;
		int bestLoad = Integer.MAX_VALUE;
		for (int i = 0; i < this.partitionedZones.size(); i++) {
			int load = this.partitionedZones.get(i).size();
			if (load < bestLoad) {
				bestPartition = i;
				bestLoad = load;
			}
		}
		this.partitionedZones.get(bestPartition).add(zone);
		this.backgroundLoads = computeBackgroundLoads();
	}

	public synchronized void removeZone(Zone zone) {
		if (zone == null || this.partitionedZones == null) return;
		for (ArrayList<Zone> partition : this.partitionedZones) {
			partition.remove(zone);
		}
		this.backgroundLoads = computeBackgroundLoads();
	}

	public synchronized ArrayList<ArrayList<ChargingStation>> getpartitionedChargingStations() {
		ArrayList<ArrayList<ChargingStation>> snapshot = new ArrayList<ArrayList<ChargingStation>>();
		for (ArrayList<ChargingStation> partition : this.partitionedChargingStation) {
			snapshot.add(new ArrayList<ChargingStation>(partition));
		}
		return snapshot;
	}

	public synchronized void addChargingStation(ChargingStation chargingStation) {
		if (chargingStation == null) return;
		if (this.partitionedChargingStation == null || this.partitionedChargingStation.isEmpty()) {
			this.partitionedChargingStation = newChargingStationPartitions();
		}
		for (ArrayList<ChargingStation> partition : this.partitionedChargingStation) {
			if (partition.contains(chargingStation)) {
				return;
			}
		}
		int bestPartition = 0;
		int bestLoad = Integer.MAX_VALUE;
		for (int i = 0; i < this.partitionedChargingStation.size(); i++) {
			int load = this.partitionedChargingStation.get(i).size();
			if (load < bestLoad) {
				bestPartition = i;
				bestLoad = load;
			}
		}
		this.partitionedChargingStation.get(bestPartition).add(chargingStation);
		this.backgroundLoads = computeBackgroundLoads();
	}

	public synchronized void removeChargingStation(ChargingStation chargingStation) {
		if (chargingStation == null || this.partitionedChargingStation == null) return;
		for (ArrayList<ChargingStation> partition : this.partitionedChargingStation) {
			partition.remove(chargingStation);
		}
		this.backgroundLoads = computeBackgroundLoads();
	}

	public ArrayList<ArrayList<Signal>> getpartitionedSignals() {
		return this.partitionedSignals;
	}

	public void first_run() {
		rebuildAllPartitions();
	}

	public void check_run() {
		run();
	}

	public void run() {
		this.partitionedInRoads = partitionRoadsByCurrentLoad(ContextCreator.getRoadContext().getAll());
		this.partitionedBwRoads = new ArrayList<Road>();
	}

	public int getBackgroundLoad(int i) {
		if (this.backgroundLoads == null || i < 0 || i >= this.backgroundLoads.size()) {
			return 0;
		}
		return this.backgroundLoads.get(i);
	}

	private void initializeEmptyPartitions() {
		this.partitionedInRoads = newRoadPartitions();
		this.partitionedBwRoads = new ArrayList<Road>();
		this.partitionedZones = newZonePartitions();
		this.partitionedChargingStation = newChargingStationPartitions();
		this.partitionedSignals = newSignalPartitions();
		this.backgroundLoads = new ArrayList<Integer>(this.nPartition);
		for (int i = 0; i < this.nPartition; i++) {
			this.backgroundLoads.add(0);
		}
	}

	private void rebuildAllPartitions() {
		this.partitionedZones = partitionZonesByStock();
		this.partitionedChargingStation = partitionChargingStationsByCapacity();
		this.partitionedSignals = partitionSignalsEvenly();
		this.backgroundLoads = computeBackgroundLoads();
		run();
	}

	private ArrayList<ArrayList<Road>> partitionRoadsByCurrentLoad(Collection<Road> roads) {
		ArrayList<ArrayList<Road>> partitions = newRoadPartitions();
		if (roads == null) {
			return partitions;
		}

		ArrayList<RoadStepLoad> activeRoads = new ArrayList<RoadStepLoad>();
		for (Road road : roads) {
			if (road != null) {
				activeRoads.add(new RoadStepLoad(road, road.getStepLoadWeight()));
			}
		}
		activeRoads.sort((a, b) -> {
			int weightCompare = Integer.compare(b.weight, a.weight);
			return weightCompare != 0 ? weightCompare : Integer.compare(a.road.getID(), b.road.getID());
		});

		long[] loads = new long[this.nPartition];
		for (RoadStepLoad roadLoad : activeRoads) {
			int partition = lightestPartition(loads);
			partitions.get(partition).add(roadLoad.road);
			loads[partition] += roadLoad.weight;
		}
		return partitions;
	}

	private ArrayList<ArrayList<Zone>> partitionZonesByStock() {
		ArrayList<ArrayList<Zone>> partitions = newZonePartitions();
		long[] loads = new long[this.nPartition];
		for (Zone zone : ContextCreator.getZoneContext().getAll()) {
			int partition = lightestPartition(loads);
			partitions.get(partition).add(zone);
			loads[partition] += zone.getVehicleStock() + 1L;
		}
		return partitions;
	}

	private ArrayList<ArrayList<ChargingStation>> partitionChargingStationsByCapacity() {
		ArrayList<ArrayList<ChargingStation>> partitions = newChargingStationPartitions();
		long[] loads = new long[this.nPartition];
		for (ChargingStation station : ContextCreator.getChargingStationContext().getAll()) {
			int partition = lightestPartition(loads);
			partitions.get(partition).add(station);
			loads[partition] += station.capacity() + 1L;
		}
		return partitions;
	}

	private ArrayList<ArrayList<Signal>> partitionSignalsEvenly() {
		ArrayList<ArrayList<Signal>> partitions = newSignalPartitions();
		long[] loads = new long[this.nPartition];
		for (Signal signal : ContextCreator.getSignalContext().getAll()) {
			int partition = lightestPartition(loads);
			partitions.get(partition).add(signal);
			loads[partition] += 1L;
		}
		return partitions;
	}

	private ArrayList<Integer> computeBackgroundLoads() {
		ArrayList<Integer> loads = new ArrayList<Integer>(this.nPartition);
		for (int i = 0; i < this.nPartition; i++) {
			int zoneLoad = this.partitionedZones.get(i).size();
			int chargingLoad = this.partitionedChargingStation.get(i).size();
			int signalLoad = this.partitionedSignals.get(i).size();
			loads.add(zoneLoad + chargingLoad + signalLoad);
		}
		return loads;
	}

	private int lightestPartition(long[] loads) {
		int target = 0;
		for (int i = 1; i < loads.length; i++) {
			if (loads[i] < loads[target]) {
				target = i;
			}
		}
		return target;
	}

	private ArrayList<ArrayList<Road>> newRoadPartitions() {
		ArrayList<ArrayList<Road>> partitions = new ArrayList<ArrayList<Road>>(this.nPartition);
		for (int i = 0; i < this.nPartition; i++) {
			partitions.add(new ArrayList<Road>());
		}
		return partitions;
	}

	private ArrayList<ArrayList<Zone>> newZonePartitions() {
		ArrayList<ArrayList<Zone>> partitions = new ArrayList<ArrayList<Zone>>(this.nPartition);
		for (int i = 0; i < this.nPartition; i++) {
			partitions.add(new ArrayList<Zone>());
		}
		return partitions;
	}

	private ArrayList<ArrayList<ChargingStation>> newChargingStationPartitions() {
		ArrayList<ArrayList<ChargingStation>> partitions = new ArrayList<ArrayList<ChargingStation>>(this.nPartition);
		for (int i = 0; i < this.nPartition; i++) {
			partitions.add(new ArrayList<ChargingStation>());
		}
		return partitions;
	}

	private ArrayList<ArrayList<Signal>> newSignalPartitions() {
		ArrayList<ArrayList<Signal>> partitions = new ArrayList<ArrayList<Signal>>(this.nPartition);
		for (int i = 0; i < this.nPartition; i++) {
			partitions.add(new ArrayList<Signal>());
		}
		return partitions;
	}

	private static class RoadStepLoad {
		final Road road;
		final int weight;

		RoadStepLoad(Road road, int weight) {
			this.road = road;
			this.weight = weight;
		}
	}
}
