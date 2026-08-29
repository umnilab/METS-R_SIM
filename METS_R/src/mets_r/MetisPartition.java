package mets_r;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.RoadContext;
import mets_r.facility.Signal;
import mets_r.facility.Zone;

/**
 * Lightweight deterministic partitioner for METS-R simulation agents.
 *
 * Roads and connector segments receive deterministic owners. Active membership
 * changes only filter segments into those existing owners; weighted greedy
 * balancing changes ownership exclusively at the configured refresh interval.
 */
public class MetisPartition {
	private final int nPartition;
	private ArrayList<ArrayList<Road>> partitionedInRoads;
	private ArrayList<Road> partitionedBwRoads;
	private ArrayList<ArrayList<Zone>> partitionedZones;
	private ArrayList<ArrayList<ChargingStation>> partitionedChargingStation;
	private List<List<Zone>> partitionedZonesSnapshot;
	private List<List<ChargingStation>> partitionedChargingStationSnapshot;
	private ArrayList<ArrayList<Signal>> partitionedSignals;
	private ArrayList<Integer> backgroundLoads;
	private final ArrayList<ArrayList<Road>> activeRoadPartitions;
	private final ArrayList<RoadStepLoad> activeRoadLoads;
	private final long[] activePartitionLoads;
	private final Map<Integer, Integer> roadPartitionOwners;
	private long lastActiveRoadVersion = Long.MIN_VALUE;
	private int lastActiveRoadRebalanceTick = Integer.MIN_VALUE;

	public MetisPartition(int nparts) {
		this.nPartition = Math.max(1, nparts);
		this.initializeEmptyPartitions();
		this.activeRoadPartitions = newRoadPartitions();
		this.activeRoadLoads = new ArrayList<RoadStepLoad>();
		this.activePartitionLoads = new long[this.nPartition];
		this.roadPartitionOwners = new HashMap<Integer, Integer>();
	}

	public ArrayList<ArrayList<Road>> getPartitionedInRoads() {
		return this.partitionedInRoads;
	}

	public ArrayList<ArrayList<Road>> partitionRoadsForCurrentPartitions(Collection<Road> roads) {
		return partitionRoadsByCurrentLoad(roads);
	}

	public synchronized ArrayList<ArrayList<Road>> getActiveRoadPartitions(RoadContext roadContext, int currentTick) {
		long activeVersion = roadContext == null ? Long.MIN_VALUE : roadContext.getActiveRoadVersion();
		int refreshInterval = GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL;
		if (this.lastActiveRoadRebalanceTick == Integer.MIN_VALUE
				|| currentTick < this.lastActiveRoadRebalanceTick) {
			this.lastActiveRoadRebalanceTick = currentTick;
		}
		boolean periodicRebalance = refreshInterval > 0
				&& currentTick - this.lastActiveRoadRebalanceTick >= refreshInterval;
		if (activeVersion == this.lastActiveRoadVersion && !periodicRebalance) {
			return this.activeRoadPartitions;
		}

		for (ArrayList<Road> partition : this.activeRoadPartitions) {
			partition.clear();
		}
		if (roadContext != null) {
			List<Road> activeRoads = roadContext.getActiveRoadsSnapshot();
			if (periodicRebalance) {
				this.activeRoadLoads.clear();
				java.util.Arrays.fill(this.activePartitionLoads, 0L);
				for (Road road : activeRoads) {
					if (road != null) {
						this.activeRoadLoads.add(new RoadStepLoad(road, road.getStepLoadWeight()));
					}
				}
				this.activeRoadLoads.sort((a, b) -> {
					int weightCompare = Integer.compare(b.weight, a.weight);
					return weightCompare != 0 ? weightCompare
							: Integer.compare(a.road.getID(), b.road.getID());
				});
				for (RoadStepLoad roadLoad : this.activeRoadLoads) {
					int partition = lightestPartition(this.activePartitionLoads);
					Integer previousOwner = this.roadPartitionOwners.get(roadLoad.road.getID());
					if (previousOwner != null && previousOwner.intValue() >= 0
							&& previousOwner.intValue() < this.nPartition
							&& this.activePartitionLoads[previousOwner.intValue()]
									== this.activePartitionLoads[partition]) {
						partition = previousOwner.intValue();
					}
					this.activeRoadPartitions.get(partition).add(roadLoad.road);
					this.activePartitionLoads[partition] += roadLoad.weight;
					this.roadPartitionOwners.put(roadLoad.road.getID(), partition);
				}
				this.lastActiveRoadRebalanceTick = currentTick;
			} else {
				for (Road road : activeRoads) {
					if (road == null) continue;
					Integer owner = this.roadPartitionOwners.get(road.getID());
					if (owner == null || owner.intValue() < 0
							|| owner.intValue() >= this.nPartition) {
						owner = Integer.valueOf(Math.floorMod(road.getID(), this.nPartition));
						this.roadPartitionOwners.put(road.getID(), owner);
					}
					this.activeRoadPartitions.get(owner.intValue()).add(road);
				}
			}
		}
		this.lastActiveRoadVersion = activeVersion;
		return this.activeRoadPartitions;
	}

	public ArrayList<Road> getPartitionedBwRoads() {
		return this.partitionedBwRoads;
	}

	public synchronized List<List<Zone>> getpartitionedZones() {
		if (this.partitionedZonesSnapshot == null) {
			this.partitionedZonesSnapshot = copyPartitions(this.partitionedZones);
		}
		return this.partitionedZonesSnapshot;
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
		this.partitionedZonesSnapshot = null;
		this.backgroundLoads = computeBackgroundLoads();
	}

	public synchronized void removeZone(Zone zone) {
		if (zone == null || this.partitionedZones == null) return;
		for (ArrayList<Zone> partition : this.partitionedZones) {
			partition.remove(zone);
		}
		this.partitionedZonesSnapshot = null;
		this.backgroundLoads = computeBackgroundLoads();
	}

	public synchronized List<List<ChargingStation>> getpartitionedChargingStations() {
		if (this.partitionedChargingStationSnapshot == null) {
			this.partitionedChargingStationSnapshot =
					copyPartitions(this.partitionedChargingStation);
		}
		return this.partitionedChargingStationSnapshot;
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
		this.partitionedChargingStationSnapshot = null;
		this.backgroundLoads = computeBackgroundLoads();
	}

	public synchronized void removeChargingStation(ChargingStation chargingStation) {
		if (chargingStation == null || this.partitionedChargingStation == null) return;
		for (ArrayList<ChargingStation> partition : this.partitionedChargingStation) {
			partition.remove(chargingStation);
		}
		this.partitionedChargingStationSnapshot = null;
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

	public synchronized void run() {
		this.partitionedInRoads = partitionRoadsByCurrentLoad(
				ContextCreator.getRoadContext().getAllSteppableRoads());
		this.partitionedBwRoads = new ArrayList<Road>();
		this.roadPartitionOwners.clear();
		for (int partitionID = 0; partitionID < this.partitionedInRoads.size(); partitionID++) {
			for (Road road : this.partitionedInRoads.get(partitionID)) {
				if (road != null) this.roadPartitionOwners.put(road.getID(), partitionID);
			}
		}
		this.lastActiveRoadVersion = Long.MIN_VALUE;
		this.lastActiveRoadRebalanceTick = Integer.MIN_VALUE;
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
		this.partitionedZonesSnapshot = null;
		this.partitionedChargingStationSnapshot = null;
		this.partitionedSignals = newSignalPartitions();
		this.backgroundLoads = new ArrayList<Integer>(this.nPartition);
		for (int i = 0; i < this.nPartition; i++) {
			this.backgroundLoads.add(0);
		}
	}

	private void rebuildAllPartitions() {
		this.partitionedZones = partitionZonesByStock();
		this.partitionedChargingStation = partitionChargingStationsByCapacity();
		this.partitionedZonesSnapshot = null;
		this.partitionedChargingStationSnapshot = null;
		this.partitionedSignals = partitionSignalsEvenly();
		this.backgroundLoads = computeBackgroundLoads();
		run();
	}

	private static <T> List<List<T>> copyPartitions(
			ArrayList<ArrayList<T>> partitions) {
		ArrayList<List<T>> snapshot = new ArrayList<List<T>>(
				partitions == null ? 0 : partitions.size());
		if (partitions != null) {
			for (ArrayList<T> partition : partitions) {
				snapshot.add(Collections.unmodifiableList(
						new ArrayList<T>(partition)));
			}
		}
		return Collections.unmodifiableList(snapshot);
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
