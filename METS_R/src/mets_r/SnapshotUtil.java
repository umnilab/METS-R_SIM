package mets_r;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.communication.BSMDataStream;
import mets_r.facility.*;
import mets_r.mobility.*;

/**
 * Utility class for serializing and deserializing the simulation state
 * into/from a zip archive. The road network, zones, and charging stations
 * are rebuilt from data.properties; only dynamic runtime state (vehicles,
 * queues, metrics, random seeds, etc.) is persisted.
 */
public class SnapshotUtil {

	private static final Gson gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();

	public static class SimulationSnapshot {
		HashMap<String, Object> globalState;
		ArrayList<HashMap<String, Object>> vehicleSnapshots;
		ArrayList<HashMap<String, Object>> zoneSnapshots;
		ArrayList<HashMap<String, Object>> chargingStationSnapshots;
		ArrayList<HashMap<String, Object>> signalSnapshots;
		ArrayList<HashMap<String, Object>> roadSnapshots;
		ArrayList<HashMap<String, Object>> laneSnapshots;
	}

	// ------------------------------------------------------------------ //
	//                          SAVE helpers                               //
	// ------------------------------------------------------------------ //

	/** Serialize a java.util.Random to a Base64 string. */
	public static String serializeRandom(Random rand) {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
			 ObjectOutputStream oos = new ObjectOutputStream(baos)) {
			oos.writeObject(rand);
			oos.flush();
			return Base64.getEncoder().encodeToString(baos.toByteArray());
		} catch (IOException e) {
			ContextCreator.logger.error("Failed to serialize Random: " + e.getMessage());
			return "";
		}
	}

	/** Deserialize a java.util.Random from a Base64 string. */
	public static Random deserializeRandom(String base64) {
		if (base64 == null || base64.isEmpty()) return new Random();
		try (ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
			 ObjectInputStream ois = new ObjectInputStream(bais)) {
			return (Random) ois.readObject();
		} catch (IOException | ClassNotFoundException e) {
			ContextCreator.logger.error("Failed to deserialize Random: " + e.getMessage());
			return new Random();
		}
	}

	// --------------- Request snapshot ---------------
	public static HashMap<String, Object> snapshotRequest(Request r) {
		HashMap<String, Object> m = new HashMap<>();
		m.put("id", r.getID());
		m.put("origin", r.getOriginZone());
		m.put("dest", r.getDestZone());
		m.put("originRoad", r.getOriginRoad());
		m.put("destRoad", r.getDestRoad());
		m.put("numPeople", r.getNumPeople());
		m.put("maxWaitingTime", r.getMaxWaitingTime());
		m.put("currentWaitingTime", r.getCurrentWaitingTime());
		m.put("willingToShare", r.isShareable());
		m.put("busRouteID", r.getBusRoute());
		m.put("generationTime", r.generationTime);
		m.put("matchedTime", r.matchedTime);
		m.put("pickupTime", r.pickupTime);
		m.put("arriveTime", r.arriveTIme);
		// Activity plan
		ArrayList<HashMap<String, Object>> plans = new ArrayList<>();
		if (r.getActivityPlan() != null) {
			for (Plan p : r.getActivityPlan()) {
				HashMap<String, Object> pm = new HashMap<>();
				pm.put("zoneID", p.getDestZoneID());
				pm.put("roadID", p.getDestRoadID());
				pm.put("departureTime", p.getDepartureTime());
				plans.add(pm);
			}
		}
		m.put("activityPlan", plans);
		return m;
	}

	public static ArrayList<HashMap<String, Object>> snapshotRequestQueue(Queue<Request> q) {
		ArrayList<HashMap<String, Object>> list = new ArrayList<>();
		if (q != null) {
			for (Request r : q) {
				list.add(snapshotRequest(r));
			}
		}
		return list;
	}

	// --------------- Vehicle snapshot ---------------
	public static HashMap<String, Object> snapshotVehicle(Vehicle v) {
		HashMap<String, Object> m = new HashMap<>();
		m.put("id", v.getID());
		m.put("vehicleClass", v.getVehicleClass());
		m.put("vehicleSensorType", v.getVehicleSensorType());
		m.put("vehicleState", v.getState());
		m.put("coordX", v.getCurrentCoord().x);
		m.put("coordY", v.getCurrentCoord().y);
		m.put("coordZ", v.getCurrentCoord().z);
		m.put("roadID", v.getRoad() != null ? v.getRoad().getID() : -1);
		m.put("laneIndex", (v.getLane() != null && v.getRoad() != null) ? v.getRoad().getLaneIndex(v.getLane()) : -1);
		m.put("distance", v.getDistanceToNextJunction());
		m.put("speed", v.currentSpeed());
		m.put("accRate", v.currentAcc());
		m.put("bearing", v.getBearing());
		m.put("originID", v.getOriginID());
		m.put("destID", v.getDestID());
		m.put("depTime", v.getDepTime());
		m.put("accumulatedDistance", v.getAccummulatedDistance());
		m.put("numTrips", v.getNumTrips());
		m.put("onRoad", v.isOnRoad());
		m.put("onLane", v.isOnLane());
		m.put("movingFlag", v.getMovingFlag());

		// Origin road
		try {
			m.put("originRoadID", v.getOriginRoad());
		} catch (Exception e) {
			m.put("originRoadID", -1);
		}

		// Dest road
		try {
			m.put("destRoadID", v.getDestRoad());
		} catch (Exception e) {
			m.put("destRoadID", -1);
		}

		// Routing state
		ArrayList<Integer> roadPathIDs = new ArrayList<>();
		if (v.getRoadPath() != null) {
			for (Road r : v.getRoadPath()) {
				roadPathIDs.add(r.getID());
			}
		}
		m.put("roadPath", roadPathIDs);
		m.put("distToTravel", v.getDistToTravel());
		m.put("atOrigin", v.isAtOrigin());
		m.put("isReachDest", v.isReachDest());
		m.put("linkTravelTime", v.getLinkTravelTime());
		m.put("nextRoadID", v.getNextRoad() != null ? v.getNextRoad().getID() : -1);

		// Activity plan
		ArrayList<HashMap<String, Object>> plans = new ArrayList<>();
		if (v.getPlan() != null) {
			for (Plan p : v.getPlan()) {
				HashMap<String, Object> pm = new HashMap<>();
				pm.put("zoneID", p.getDestZoneID());
				pm.put("roadID", p.getDestRoadID());
				pm.put("departureTime", p.getDepartureTime());
				plans.add(pm);
			}
		}
		m.put("plans", plans);

		// Random seeds
		m.put("rand", serializeRandom(v.getRandom()));
		m.put("randRoute", serializeRandom(v.getRandomRoute()));
		m.put("randRelocate", serializeRandom(v.getRandomRelocate()));
		m.put("randCarFollow", serializeRandom(v.getRandomCarFollow()));

		// EV-specific fields
		if (v instanceof ElectricVehicle) {
			ElectricVehicle ev = (ElectricVehicle) v;
			m.put("batteryLevel", ev.getBatteryLevel());
			m.put("batteryCapacity", ev.getBatteryCapacity());
			m.put("onChargingRoute", ev.isOnChargingRoute());
			m.put("totalConsume", ev.getTotalConsume());
			m.put("tripConsume", ev.getTripConsume());
			m.put("linkConsume", ev.getLinkConsume());
			m.put("mass", ev.getMass_());
			m.put("lowerRechargeLevel", ev.getLowerRechargeLevel());
			m.put("higherRechargeLevel", ev.getHigherRechargeLevel());
			m.put("metersPerKwh", ev.getMetersPerKwh());
			m.put("chargingTime", ev.getChargingTime());
			m.put("chargingWaitingTime", ev.getChargingWaitingTime());
			m.put("initialChargingState", ev.getInitialChargingState());
		}

		// Taxi-specific
		if (v instanceof ElectricTaxi) {
			ElectricTaxi taxi = (ElectricTaxi) v;
			m.put("passNum", taxi.getPassNum());
			m.put("currentZone", taxi.getCurrentZone());
			m.put("cruisingTime", taxi.getCruisingTime());
			m.put("matchedRequests", taxi.getMatchedRequests());
			m.put("matchedPassengers", taxi.getMatchedPassengers());
			m.put("pickupRequests", taxi.getPickupRequests());
			m.put("pickupPassengers", taxi.getPickupPassengers());
			m.put("dropoffRequests", taxi.getDropoffRequests());
			m.put("dropoffPassengers", taxi.getDropoffPassengers());
			m.put("toBoardRequests", snapshotRequestQueue(taxi.getToBoardRequests()));
			m.put("onBoardRequests", snapshotRequestQueue(taxi.getOnBoardRequests()));
		}

		// Bus-specific
		if (v instanceof ElectricBus) {
			ElectricBus bus = (ElectricBus) v;
			m.put("routeID", bus.getRouteID());
			m.put("passNum", bus.getPassNum());
			m.put("nextStop", bus.getNextStop());
			m.put("matchedRequests", bus.getMatchedRequests());
			m.put("matchedPassengers", bus.getMatchedPassengers());
			m.put("pickupRequests", bus.getPickupRequests());
			m.put("pickupPassengers", bus.getPickupPassengers());
			m.put("dropoffRequests", bus.getDropoffRequests());
			m.put("dropoffPassengers", bus.getDropoffPassengers());
			m.put("stopZones", bus.getStopZones());
			m.put("departureTimes", bus.getDepartureTimes());
			// Bus request queues (per-stop)
			ArrayList<Object> tbr = new ArrayList<>();
			for (Queue<Request> q : bus.getToBoardRequests()) {
				tbr.add(snapshotRequestQueue(q));
			}
			m.put("busToBoardRequests", tbr);
			ArrayList<Object> obr = new ArrayList<>();
			for (Queue<Request> q : bus.getOnBoardRequests()) {
				obr.add(snapshotRequestQueue(q));
			}
			m.put("busOnBoardRequests", obr);
		}

		return m;
	}

	// --------------- Zone snapshot (dynamic state only) ---------------
	public static HashMap<String, Object> snapshotZone(Zone z) {
		HashMap<String, Object> m = new HashMap<>();
		m.put("id", z.getID());
		m.put("publicTripTimeIndex", z.getPublicTripTimeIndex());
		m.put("privateTripTimeIndex", z.getPrivateTripTimeIndex());
		m.put("lastDemandUpdateHour", z.getLastDemandUpdateHour());
		m.put("parkingVehicleStock", z.getParkingVehicleStock());
		m.put("futureDemand", z.getFutureDemand());
		m.put("futureSupply", z.getFutureSupply());
		m.put("vehicleSurplus", z.getVehicleSurplus());
		m.put("vehicleDeficiency", z.getVehicleDeficiency());

		// Metrics
		m.put("numberOfGeneratedTaxiRequest", z.numberOfGeneratedTaxiRequest);
		m.put("numberOfGeneratedBusRequest", z.numberOfGeneratedBusRequest);
		m.put("numberOfGeneratedPrivateEVTrip", z.numberOfGeneratedPrivateEVTrip);
		m.put("numberOfGeneratedPrivateGVTrip", z.numberOfGeneratedPrivateGVTrip);
		m.put("arrivedPrivateEVTrip", z.arrivedPrivateEVTrip);
		m.put("arrivedPrivateGVTrip", z.arrivedPrivateGVTrip);
		m.put("taxiPickupRequest", z.taxiPickupRequest);
		m.put("busPickupRequest", z.busPickupRequest);
		m.put("taxiServedRequest", z.taxiServedRequest);
		m.put("busServedRequest", z.busServedRequest);
		m.put("numberOfLeavedTaxiRequest", z.numberOfLeavedTaxiRequest);
		m.put("numberOfLeavedBusRequest", z.numberOfLeavedBusRequest);
		m.put("numberOfLeavedTaxiPassengers", z.numberOfLeavedTaxiPassengers);
		m.put("numberOfLeavedBusPassengers", z.numberOfLeavedBusPassengers);
		m.put("numberOfRelocatedVehicles", z.numberOfRelocatedVehicles);
		m.put("taxiServedPassWaitingTime", z.taxiServedPassWaitingTime);
		m.put("busServedPassWaitingTime", z.busServedPassWaitingTime);
		m.put("taxiLeavedPassWaitingTime", z.taxiLeavedPassWaitingTime);
		m.put("busLeavedPassWaitingTime", z.busLeavedPassWaitingTime);
		m.put("taxiParkingTime", z.taxiParkingTime);
		m.put("taxiCruisingTime", z.taxiCruisingTime);
		m.put("nRequestForTaxi", z.getTaxiRequestNum());
		m.put("nRequestForBus", z.getBusRequestNum());

		// Passenger queues
		m.put("taxiRequests", snapshotRequestQueue(z.getTaxiRequestQueue()));
		m.put("busRequests", snapshotRequestQueue(z.getBusRequestQueue()));
		m.put("pendingTaxiRequests", snapshotRequestQueue(z.getToAddTaxiRequestQueue()));
		m.put("pendingBusRequests", snapshotRequestQueue(z.getToAddBusRequestQueue()));

		// Sharable requests
		HashMap<String, Object> sharableMap = new HashMap<>();
		if (z.getSharableRequestForTaxi() != null) {
			for (Map.Entry<Integer, Queue<Request>> entry : z.getSharableRequestForTaxi().entrySet()) {
				sharableMap.put(String.valueOf(entry.getKey()), snapshotRequestQueue(entry.getValue()));
			}
		}
		m.put("sharableRequests", sharableMap);

		// Random seeds
		m.put("rand", serializeRandom(z.getRandom()));
		m.put("randDemand", serializeRandom(z.getRandomDemand()));
		m.put("randDiffusion", serializeRandom(z.getRandomDiffusion()));
		m.put("randMode", serializeRandom(z.getRandomMode()));
		m.put("randShare", serializeRandom(z.getRandomShare()));
		m.put("randRelocate", serializeRandom(z.getRandomRelocate()));

		return m;
	}

	// --------------- ChargingStation snapshot ---------------
	public static HashMap<String, Object> snapshotChargingStation(ChargingStation cs) {
		HashMap<String, Object> m = new HashMap<>();
		m.put("id", cs.getID());
		m.put("priceL2", cs.getPriceL2());
		m.put("priceL3", cs.getPriceL3());
		m.put("numChargedCar", cs.numChargedCar);
		m.put("numChargedBus", cs.numChargedBus);
		m.put("rand", serializeRandom(cs.getRandom()));

		// Queues: store vehicle IDs
		m.put("queueL2", vehicleIDList(cs.getQueueL2()));
		m.put("queueL3", vehicleIDList(cs.getQueueL3()));
		m.put("queueBus", vehicleIDList(cs.getQueueBus()));
		m.put("chargingL2", vehicleIDList(cs.getChargingL2()));
		m.put("chargingL3", vehicleIDList(cs.getChargingL3()));
		m.put("chargingBus", vehicleIDList(cs.getChargingBus()));

		return m;
	}

	private static ArrayList<Integer> vehicleIDList(Collection<? extends Vehicle> vehicles) {
		ArrayList<Integer> ids = new ArrayList<>();
		if (vehicles != null) {
			for (Vehicle v : vehicles) {
				ids.add(v.getID());
			}
		}
		return ids;
	}

	// --------------- Signal snapshot ---------------
	public static HashMap<String, Object> snapshotSignal(Signal s) {
		HashMap<String, Object> m = new HashMap<>();
		m.put("id", s.getID());
		m.put("state", s.getState());
		m.put("nextUpdateTick", s.getNextUpdateTick());
		m.put("phaseTick", s.getPhaseTick());
		return m;
	}

	// --------------- Road snapshot (dynamic state only) ---------------
	public static HashMap<String, Object> snapshotRoad(Road r) {
		HashMap<String, Object> m = new HashMap<>();
		m.put("id", r.getID());
		m.put("travelTime", r.getTravelTime());
		m.put("speedLimit", r.getSpeedLimit());
		m.put("controlType", r.getControlType());
		m.put("currentEnergy", r.currentEnergy);
		m.put("totalEnergy", r.totalEnergy);
		m.put("currentFlow", r.currentFlow);
		m.put("totalFlow", r.totalFlow);
		m.put("prevFlow", r.prevFlow);
		ArrayList<Integer> enteringVehicleIDs = new ArrayList<>();
		for (Vehicle v : r.getEnteringVehicleQueueSnapshot()) {
			enteringVehicleIDs.add(v.getID());
		}
		m.put("enteringVehicleQueue", enteringVehicleIDs);
		return m;
	}

	public static HashMap<String, Object> snapshotLane(Lane l) {
		HashMap<String, Object> m = new HashMap<>();
		m.put("id", l.getID());
		m.put("speed", l.getSpeed());
		m.put("rand", serializeRandom(l.getRandom()));
		return m;
	}

	public static SimulationSnapshot captureToMemory() {
		SimulationSnapshot snapshot = new SimulationSnapshot();

		HashMap<String, Object> globalState = new HashMap<>();
		globalState.put("currentTick", ContextCreator.getCurrentTick());
		globalState.put("agentID", getAgentIDCounter());
		globalState.put("globalRandom", serializeRandom(GlobalVariables.RandomGenerator));
		globalState.put("bsmRandom", serializeRandom(BSMDataStream.getRandom()));
		globalState.put("initTick", ContextCreator.initTick);
		globalState.put("zoneNum", ContextCreator.getZoneContext().ZONE_NUM);
		globalState.put("hubIndexes", new ArrayList<Integer>(ContextCreator.getZoneContext().HUB_INDEXES));
		snapshot.globalState = globalState;

		snapshot.vehicleSnapshots = new ArrayList<>();
		for (ElectricTaxi taxi : ContextCreator.getVehicleContext().getTaxis()) {
			snapshot.vehicleSnapshots.add(snapshotVehicle(taxi));
		}
		for (ElectricBus bus : ContextCreator.getVehicleContext().getBuses()) {
			snapshot.vehicleSnapshots.add(snapshotVehicle(bus));
		}
		for (int vid : ContextCreator.getVehicleContext().getPrivateVehicleIDList()) {
			Vehicle v = ContextCreator.getVehicleContext().getPrivateVehicle(vid);
			if (v != null) {
				HashMap<String, Object> snap = snapshotVehicle(v);
				snap.put("privateVID", vid);
				snapshot.vehicleSnapshots.add(snap);
			}
		}

		snapshot.zoneSnapshots = new ArrayList<>();
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			snapshot.zoneSnapshots.add(snapshotZone(z));
		}

		snapshot.chargingStationSnapshots = new ArrayList<>();
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			snapshot.chargingStationSnapshots.add(snapshotChargingStation(cs));
		}

		snapshot.signalSnapshots = new ArrayList<>();
		for (Signal s : ContextCreator.getSignalContext().getAll()) {
			snapshot.signalSnapshots.add(snapshotSignal(s));
		}

		snapshot.roadSnapshots = new ArrayList<>();
		for (Road r : ContextCreator.getRoadContext().getAll()) {
			snapshot.roadSnapshots.add(snapshotRoad(r));
		}

		snapshot.laneSnapshots = new ArrayList<>();
		for (Lane l : ContextCreator.getLaneContext().getAll()) {
			snapshot.laneSnapshots.add(snapshotLane(l));
		}

		return snapshot;
	}

	public static boolean matchesCurrentFacilityMembership(SimulationSnapshot snapshot) {
		return sameIDs(snapshot.roadSnapshots, ContextCreator.getRoadContext().getIDList())
				&& sameIDs(snapshot.laneSnapshots, ContextCreator.getLaneContext().getIDList())
				&& sameIDs(snapshot.zoneSnapshots, ContextCreator.getZoneContext().getIDList())
				&& sameIDs(snapshot.chargingStationSnapshots, ContextCreator.getChargingStationContext().getIDList())
				&& sameIDs(snapshot.signalSnapshots, ContextCreator.getSignalContext().getIDList());
	}

	private static boolean sameIDs(ArrayList<HashMap<String, Object>> snapshots, List<Integer> currentIDs) {
		if (snapshots == null || currentIDs == null || snapshots.size() != currentIDs.size()) {
			return false;
		}
		HashSet<Integer> expected = new HashSet<>();
		for (HashMap<String, Object> snap : snapshots) {
			expected.add(toInt(snap.get("id")));
		}
		return expected.equals(new HashSet<Integer>(currentIDs));
	}

	private static void restoreZoneSnapshot(Zone z, HashMap<String, Object> zs) {
		z.setPublicTripTimeIndex(zs.containsKey("publicTripTimeIndex") ? toInt(zs.get("publicTripTimeIndex")) : -1);
		z.setPrivateTripTimeIndex(zs.containsKey("privateTripTimeIndex") ? toInt(zs.get("privateTripTimeIndex")) : 0);
		z.setLastDemandUpdateHour(zs.containsKey("lastDemandUpdateHour") ? toInt(zs.get("lastDemandUpdateHour")) : -1);
		z.invalidateModeSplitCache();

		z.setParkingVehicleStock(toInt(zs.get("parkingVehicleStock")));
		z.setFutureDemand(toDouble(zs.get("futureDemand")));
		z.setFutureSupply(toInt(zs.get("futureSupply")));
		z.setVehicleSurplus(toDouble(zs.get("vehicleSurplus")));
		z.setVehicleDeficiency(toDouble(zs.get("vehicleDeficiency")));

		z.numberOfGeneratedTaxiRequest = toInt(zs.get("numberOfGeneratedTaxiRequest"));
		z.numberOfGeneratedBusRequest = toInt(zs.get("numberOfGeneratedBusRequest"));
		z.numberOfGeneratedPrivateEVTrip = toInt(zs.get("numberOfGeneratedPrivateEVTrip"));
		z.numberOfGeneratedPrivateGVTrip = toInt(zs.get("numberOfGeneratedPrivateGVTrip"));
		z.arrivedPrivateEVTrip = toInt(zs.get("arrivedPrivateEVTrip"));
		z.arrivedPrivateGVTrip = toInt(zs.get("arrivedPrivateGVTrip"));
		z.taxiPickupRequest = toInt(zs.get("taxiPickupRequest"));
		z.busPickupRequest = toInt(zs.get("busPickupRequest"));
		z.taxiServedRequest = toInt(zs.get("taxiServedRequest"));
		z.busServedRequest = toInt(zs.get("busServedRequest"));
		z.numberOfLeavedTaxiRequest = toInt(zs.get("numberOfLeavedTaxiRequest"));
		z.numberOfLeavedBusRequest = toInt(zs.get("numberOfLeavedBusRequest"));
		z.numberOfLeavedTaxiPassengers = toInt(zs.get("numberOfLeavedTaxiPassengers"));
		z.numberOfLeavedBusPassengers = toInt(zs.get("numberOfLeavedBusPassengers"));
		z.numberOfRelocatedVehicles = toInt(zs.get("numberOfRelocatedVehicles"));
		z.taxiServedPassWaitingTime = toInt(zs.get("taxiServedPassWaitingTime"));
		z.busServedPassWaitingTime = toInt(zs.get("busServedPassWaitingTime"));
		z.taxiLeavedPassWaitingTime = toInt(zs.get("taxiLeavedPassWaitingTime"));
		z.busLeavedPassWaitingTime = toInt(zs.get("busLeavedPassWaitingTime"));
		z.taxiParkingTime = toInt(zs.get("taxiParkingTime"));
		z.taxiCruisingTime = toInt(zs.get("taxiCruisingTime"));
		z.setNRequestForTaxi(toInt(zs.get("nRequestForTaxi")));
		z.setNRequestForBus(toInt(zs.get("nRequestForBus")));

		restoreRequestQueue(z.getTaxiRequestQueue(), (List<?>) zs.get("taxiRequests"));
		restoreRequestQueue(z.getBusRequestQueue(), (List<?>) zs.get("busRequests"));
		restoreRequestQueue(z.getToAddTaxiRequestQueue(), (List<?>) zs.get("pendingTaxiRequests"));
		restoreRequestQueue(z.getToAddBusRequestQueue(), (List<?>) zs.get("pendingBusRequests"));

		Map<?, ?> sharableMap = (Map<?, ?>) zs.get("sharableRequests");
		if (sharableMap != null) {
			for (Map.Entry<?, ?> entry : sharableMap.entrySet()) {
				int destZone = toInt(entry.getKey());
				Queue<Request> queue = z.getSharableRequestForTaxi().get(destZone);
				if (queue == null) {
					queue = new LinkedList<Request>();
					z.getSharableRequestForTaxi().put(destZone, queue);
				}
				restoreRequestQueue(queue, (List<?>) entry.getValue());
			}
		}

		z.setRandom(deserializeRandom((String) zs.get("rand")));
		z.setRandomDemand(deserializeRandom((String) zs.get("randDemand")));
		z.setRandomDiffusion(deserializeRandom((String) zs.get("randDiffusion")));
		z.setRandomMode(deserializeRandom((String) zs.get("randMode")));
		z.setRandomShare(deserializeRandom((String) zs.get("randShare")));
		z.setRandomRelocate(deserializeRandom((String) zs.get("randRelocate")));
	}

	public static void restoreToCurrentContexts(SimulationSnapshot snapshot) {
		if (snapshot == null) {
			throw new IllegalArgumentException("Cannot restore a null simulation snapshot");
		}

		String globalRandomStr = (String) snapshot.globalState.get("globalRandom");
		String bsmRandomStr = (String) snapshot.globalState.get("bsmRandom");
		int savedAgentID = toInt(snapshot.globalState.get("agentID"));

		HashMap<Integer, HashMap<String, Object>> laneStateMap = mapByID(snapshot.laneSnapshots);
		for (Lane l : ContextCreator.getLaneContext().getAll()) {
			HashMap<String, Object> ls = laneStateMap.get(l.getID());
			if (ls != null) {
				l.restoreRuntimeState(toDouble(ls.get("speed")), deserializeRandom((String) ls.get("rand")));
			} else {
				l.restoreRuntimeState(l.getSpeed(), null);
			}
		}

		HashMap<Integer, HashMap<String, Object>> roadStateMap = mapByID(snapshot.roadSnapshots);
		ContextCreator.coSimRoads.clear();
		for (Road r : ContextCreator.getRoadContext().getAll()) {
			HashMap<String, Object> rs = roadStateMap.get(r.getID());
			if (rs != null) {
				r.restoreRuntimeState(
						toDouble(rs.get("travelTime")),
						toDouble(rs.get("speedLimit")),
						toDouble(rs.get("currentEnergy")),
						toDouble(rs.get("totalEnergy")),
						toInt(rs.get("currentFlow")),
						toInt(rs.get("totalFlow")),
						toInt(rs.get("prevFlow")),
						rs.containsKey("controlType") ? toInt(rs.get("controlType")) : Road.NONE_OF_THE_ABOVE);
				if (r.getControlType() == Road.COSIM) {
					ContextCreator.coSimRoads.put(r.getOrigID(), r);
				}
			}
		}
		ContextCreator.getCityContext().refreshRoadNetworkWeights();

		HashMap<Integer, HashMap<String, Object>> signalStateMap = mapByID(snapshot.signalSnapshots);
		for (Signal s : ContextCreator.getSignalContext().getAll()) {
			HashMap<String, Object> ss = signalStateMap.get(s.getID());
			if (ss != null) {
				s.restoreState(toInt(ss.get("state")), toInt(ss.get("nextUpdateTick")),
						toIntList((List<?>) ss.get("phaseTick")));
			}
		}

		HashMap<Integer, HashMap<String, Object>> zoneStateMap = mapByID(snapshot.zoneSnapshots);
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			z.clearRuntimeState();
			HashMap<String, Object> zs = zoneStateMap.get(z.getID());
			if (zs == null) continue;
			restoreZoneSnapshot(z, zs);
		}

		ContextCreator.getZoneContext().ZONE_NUM = toInt(snapshot.globalState.get("zoneNum"));
		ContextCreator.getZoneContext().HUB_INDEXES.clear();
		ContextCreator.getZoneContext().HUB_INDEXES.addAll(toIntList((List<?>) snapshot.globalState.get("hubIndexes")));

		HashMap<Integer, HashMap<String, Object>> csStateMap = mapByID(snapshot.chargingStationSnapshots);
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			cs.clearRuntimeState();
			HashMap<String, Object> css = csStateMap.get(cs.getID());
			if (css == null) continue;
			cs.setPrice(ChargingStation.L2, toDouble(css.get("priceL2")));
			cs.setPrice(ChargingStation.L3, toDouble(css.get("priceL3")));
			cs.numChargedCar.set(toInt(css.get("numChargedCar")));
			cs.numChargedBus.set(toInt(css.get("numChargedBus")));
			cs.setRandom(deserializeRandom((String) css.get("rand")));
		}

		HashMap<Integer, Vehicle> restoredVehicleMap = restoreVehicles(snapshot.vehicleSnapshots);
		restoreRoadEnteringQueues(snapshot.roadSnapshots, restoredVehicleMap);

		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			HashMap<String, Object> css = csStateMap.get(cs.getID());
			if (css == null) continue;
			restoreChargingQueue(cs.getQueueL2(), (List<?>) css.get("queueL2"), restoredVehicleMap, false);
			restoreChargingQueue(cs.getQueueL3(), (List<?>) css.get("queueL3"), restoredVehicleMap, false);
			restoreChargingBusQueue(cs.getQueueBus(), (List<?>) css.get("queueBus"), restoredVehicleMap);
			restoreChargingList(cs.getChargingL2(), (List<?>) css.get("chargingL2"), restoredVehicleMap, false);
			restoreChargingList(cs.getChargingL3(), (List<?>) css.get("chargingL3"), restoredVehicleMap, false);
			restoreChargingBusList(cs.getChargingBus(), (List<?>) css.get("chargingBus"), restoredVehicleMap);
			cs.updateCapacity();
		}

		GlobalVariables.RandomGenerator = deserializeRandom(globalRandomStr);
		BSMDataStream.setRandom(deserializeRandom(bsmRandomStr));
		setAgentIDCounter(savedAgentID);
	}

	private static HashMap<Integer, HashMap<String, Object>> mapByID(ArrayList<HashMap<String, Object>> snapshots) {
		HashMap<Integer, HashMap<String, Object>> stateMap = new HashMap<>();
		if (snapshots != null) {
			for (HashMap<String, Object> snap : snapshots) {
				stateMap.put(toInt(snap.get("id")), snap);
			}
		}
		return stateMap;
	}

	// ------------------------------------------------------------------ //
	//                     Top-level SAVE                                  //
	// ------------------------------------------------------------------ //
	public static void saveToZip(String zipPath) throws IOException {
		ContextCreator.logger.info("Saving simulation state to: " + zipPath);

		// 1. Build global state
		HashMap<String, Object> globalState = new HashMap<>();
		globalState.put("currentTick", ContextCreator.getCurrentTick());
		globalState.put("agentID", getAgentIDCounter());
		globalState.put("globalRandom", serializeRandom(GlobalVariables.RandomGenerator));
		globalState.put("bsmRandom", serializeRandom(BSMDataStream.getRandom()));
		globalState.put("initTick", ContextCreator.initTick);
		globalState.put("zoneNum", ContextCreator.getZoneContext().ZONE_NUM);
		globalState.put("hubIndexes", new ArrayList<Integer>(ContextCreator.getZoneContext().HUB_INDEXES));

		// 2. Collect all vehicle snapshots
		ArrayList<HashMap<String, Object>> vehicleSnapshots = new ArrayList<>();
		// Taxis
		for (ElectricTaxi taxi : ContextCreator.getVehicleContext().getTaxis()) {
			vehicleSnapshots.add(snapshotVehicle(taxi));
		}
		// Buses
		for (ElectricBus bus : ContextCreator.getVehicleContext().getBuses()) {
			vehicleSnapshots.add(snapshotVehicle(bus));
		}
		// Private EVs and GVs
		for (int vid : ContextCreator.getVehicleContext().getPrivateVehicleIDList()) {
			Vehicle v = ContextCreator.getVehicleContext().getPrivateVehicle(vid);
			if (v != null) {
				HashMap<String, Object> snap = snapshotVehicle(v);
				snap.put("privateVID", vid);
				vehicleSnapshots.add(snap);
			}
		}

		// 3. Zone snapshots
		ArrayList<HashMap<String, Object>> zoneSnapshots = new ArrayList<>();
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			zoneSnapshots.add(snapshotZone(z));
		}

		// 4. Charging station snapshots
		ArrayList<HashMap<String, Object>> csSnapshots = new ArrayList<>();
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			csSnapshots.add(snapshotChargingStation(cs));
		}

		// 5. Signal snapshots
		ArrayList<HashMap<String, Object>> signalSnapshots = new ArrayList<>();
		for (Signal s : ContextCreator.getSignalContext().getAll()) {
			signalSnapshots.add(snapshotSignal(s));
		}

		// 6. Road snapshots (dynamic state only)
		ArrayList<HashMap<String, Object>> roadSnapshots = new ArrayList<>();
		for (Road r : ContextCreator.getRoadContext().getAll()) {
			roadSnapshots.add(snapshotRoad(r));
		}

		// 7. Lane snapshots (dynamic state only)
		ArrayList<HashMap<String, Object>> laneSnapshots = new ArrayList<>();
		for (Lane l : ContextCreator.getLaneContext().getAll()) {
			laneSnapshots.add(snapshotLane(l));
		}

		// 8. Write everything to a zip
		File zipFile = new File(zipPath);
		if (zipFile.getParentFile() != null) {
			zipFile.getParentFile().mkdirs();
		}

		try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
			// data.properties
			String propsPath = System.getProperty("user.dir") + "/data/Data.properties";
			writeZipEntry(zos, "Data.properties", Files.readAllBytes(Paths.get(propsPath)));

			// simulation_state.json
			writeZipEntry(zos, "simulation_state.json", gson.toJson(globalState).getBytes("UTF-8"));

			// vehicles.json
			writeZipEntry(zos, "vehicles.json", gson.toJson(vehicleSnapshots).getBytes("UTF-8"));

			// zones.json
			writeZipEntry(zos, "zones.json", gson.toJson(zoneSnapshots).getBytes("UTF-8"));

			// charging_stations.json
			writeZipEntry(zos, "charging_stations.json", gson.toJson(csSnapshots).getBytes("UTF-8"));

			// signals.json
			writeZipEntry(zos, "signals.json", gson.toJson(signalSnapshots).getBytes("UTF-8"));

			// roads.json
			writeZipEntry(zos, "roads.json", gson.toJson(roadSnapshots).getBytes("UTF-8"));

			// lanes.json
			writeZipEntry(zos, "lanes.json", gson.toJson(laneSnapshots).getBytes("UTF-8"));
		}

		ContextCreator.logger.info("Simulation state saved successfully to: " + zipPath);
	}

	private static void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
		zos.putNextEntry(new ZipEntry(name));
		zos.write(data);
		zos.closeEntry();
	}

	private static int getAgentIDCounter() {
		// Access the static agentID field via reflection since it's private
		try {
			java.lang.reflect.Field f = ContextCreator.class.getDeclaredField("agentID");
			f.setAccessible(true);
			return f.getInt(null);
		} catch (Exception e) {
			ContextCreator.logger.error("Failed to read agentID: " + e.getMessage());
			return 0;
		}
	}

	private static void setAgentIDCounter(int value) {
		try {
			java.lang.reflect.Field f = ContextCreator.class.getDeclaredField("agentID");
			f.setAccessible(true);
			f.setInt(null, value);
		} catch (Exception e) {
			ContextCreator.logger.error("Failed to set agentID: " + e.getMessage());
		}
	}

	// ------------------------------------------------------------------ //
	//                     Top-level LOAD                                  //
	// ------------------------------------------------------------------ //
	public static void loadFromZip(String zipPath) throws IOException {
		ContextCreator.logger.info("Loading simulation state from: " + zipPath);

		// Read all entries from the zip
		HashMap<String, byte[]> entries = new HashMap<>();
		try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				byte[] buffer = new byte[8192];
				int len;
				while ((len = zis.read(buffer)) > 0) {
					baos.write(buffer, 0, len);
				}
				entries.put(entry.getName(), baos.toByteArray());
				zis.closeEntry();
			}
		}

		// 1. Restore data.properties
		if (entries.containsKey("Data.properties")) {
			String propsPath = System.getProperty("user.dir") + "/data/Data.properties";
			Files.write(Paths.get(propsPath), entries.get("Data.properties"));
			// Reload GlobalVariables config
			GlobalVariables.config = null;
			ContextCreator.logger.info("Data.properties restored");
		}
		
		ContextCreator.logger.info("Loaded properties");

		// 2. Parse JSON data
		HashMap<String, Object> globalState = gson.fromJson(
				new String(entries.get("simulation_state.json"), "UTF-8"),
				new TypeToken<HashMap<String, Object>>() {}.getType());
		
		

		ArrayList<HashMap<String, Object>> vehicleSnapshots = gson.fromJson(
				new String(entries.get("vehicles.json"), "UTF-8"),
				new TypeToken<ArrayList<HashMap<String, Object>>>() {}.getType());
		
		

		ArrayList<HashMap<String, Object>> zoneSnapshots = gson.fromJson(
				new String(entries.get("zones.json"), "UTF-8"),
				new TypeToken<ArrayList<HashMap<String, Object>>>() {}.getType());
		
		

		ArrayList<HashMap<String, Object>> csSnapshots = gson.fromJson(
				new String(entries.get("charging_stations.json"), "UTF-8"),
				new TypeToken<ArrayList<HashMap<String, Object>>>() {}.getType());
		
		
		ArrayList<HashMap<String, Object>> signalSnapshots = gson.fromJson(
				new String(entries.get("signals.json"), "UTF-8"),
				new TypeToken<ArrayList<HashMap<String, Object>>>() {}.getType());

		ArrayList<HashMap<String, Object>> roadSnapshots = gson.fromJson(
				new String(entries.get("roads.json"), "UTF-8"),
				new TypeToken<ArrayList<HashMap<String, Object>>>() {}.getType());

		ArrayList<HashMap<String, Object>> laneSnapshots = entries.containsKey("lanes.json")
				? gson.fromJson(new String(entries.get("lanes.json"), "UTF-8"),
						new TypeToken<ArrayList<HashMap<String, Object>>>() {}.getType())
				: new ArrayList<HashMap<String, Object>>();

		// 3. Restore global state
		int savedTick = toInt(globalState.get("currentTick"));
		int savedAgentID = toInt(globalState.get("agentID"));
		int savedInitTick = toInt(globalState.get("initTick"));
		String globalRandomStr = (String) globalState.get("globalRandom");

		// Restore the global random generator
		GlobalVariables.RandomGenerator = deserializeRandom(globalRandomStr);
		BSMDataStream.setRandom(deserializeRandom((String) globalState.get("bsmRandom")));
		
//		ContextCreator.logger.info("Loaded global states");

		// 4. Rebuild the road network and zone/charging station from data.properties
		// (similar to reset, but we'll restore dynamic state afterwards)
		ContextCreator.rebuildForLoad(savedInitTick, savedTick);

		// 5. Restore agent ID counter
		setAgentIDCounter(savedAgentID);
		if (globalState.containsKey("zoneNum")) {
			ContextCreator.getZoneContext().ZONE_NUM = toInt(globalState.get("zoneNum"));
		}
		if (globalState.containsKey("hubIndexes")) {
			ContextCreator.getZoneContext().HUB_INDEXES.clear();
			ContextCreator.getZoneContext().HUB_INDEXES.addAll(toIntList((List<?>) globalState.get("hubIndexes")));
		}

		// 6. Restore lane dynamic state
		HashMap<Integer, HashMap<String, Object>> laneStateMap = new HashMap<>();
		for (HashMap<String, Object> ls : laneSnapshots) {
			laneStateMap.put(toInt(ls.get("id")), ls);
		}
		for (Lane l : ContextCreator.getLaneContext().getAll()) {
			HashMap<String, Object> ls = laneStateMap.get(l.getID());
			if (ls != null) {
				l.restoreRuntimeState(toDouble(ls.get("speed")), deserializeRandom((String) ls.get("rand")));
			}
		}

		// 7. Restore road dynamic state
		HashMap<Integer, HashMap<String, Object>> roadStateMap = new HashMap<>();
		for (HashMap<String, Object> rs : roadSnapshots) {
			roadStateMap.put(toInt(rs.get("id")), rs);
		}
		ContextCreator.coSimRoads.clear();
		for (Road r : ContextCreator.getRoadContext().getAll()) {
			HashMap<String, Object> rs = roadStateMap.get(r.getID());
			if (rs != null) {
				r.restoreRuntimeState(
						toDouble(rs.get("travelTime")),
						toDouble(rs.get("speedLimit")),
						toDouble(rs.get("currentEnergy")),
						toDouble(rs.get("totalEnergy")),
						toInt(rs.get("currentFlow")),
						toInt(rs.get("totalFlow")),
						toInt(rs.get("prevFlow")),
						rs.containsKey("controlType") ? toInt(rs.get("controlType")) : Road.NONE_OF_THE_ABOVE);
				if (r.getControlType() == Road.COSIM) {
					ContextCreator.coSimRoads.put(r.getOrigID(), r);
				}
			}
		}
		ContextCreator.getCityContext().refreshRoadNetworkWeights();
		
//		ContextCreator.logger.info("Loaded road snapshots");


		// 8. Restore signal state
		HashMap<Integer, HashMap<String, Object>> signalStateMap = new HashMap<>();
		for (HashMap<String, Object> ss : signalSnapshots) {
			signalStateMap.put(toInt(ss.get("id")), ss);
		}
		for (Signal s : ContextCreator.getSignalContext().getAll()) {
			HashMap<String, Object> ss = signalStateMap.get(s.getID());
			if (ss != null) {
				int state = toInt(ss.get("state"));
				int nextUpdateTick = toInt(ss.get("nextUpdateTick"));
				ArrayList<Integer> phaseTick = toIntList((List<?>) ss.get("phaseTick"));
				s.restoreState(state, nextUpdateTick, phaseTick);
			}
		}
		
//		ContextCreator.logger.info("Loaded signal snapshots");


		// 9. Restore zone dynamic state
		HashMap<Integer, HashMap<String, Object>> zoneStateMap = new HashMap<>();
		for (HashMap<String, Object> zs : zoneSnapshots) {
			zoneStateMap.put(toInt(zs.get("id")), zs);
		}
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			z.clearRuntimeState();
			HashMap<String, Object> zs = zoneStateMap.get(z.getID());
			if (zs == null) continue;
			restoreZoneSnapshot(z, zs);
		}

//		ContextCreator.logger.info("Loaded zone snapshots");
		
		// 10. Restore charging station state (after vehicles are created)
		// We'll do this in two phases: first restore pricing/metrics/random, then queues

		HashMap<Integer, HashMap<String, Object>> csStateMap = new HashMap<>();
		for (HashMap<String, Object> css : csSnapshots) {
			csStateMap.put(toInt(css.get("id")), css);
		}
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			HashMap<String, Object> css = csStateMap.get(cs.getID());
			if (css == null) continue;
			cs.setPrice(ChargingStation.L2, toDouble(css.get("priceL2")));
			cs.setPrice(ChargingStation.L3, toDouble(css.get("priceL3")));
			cs.numChargedCar.set(toInt(css.get("numChargedCar")));
			cs.numChargedBus.set(toInt(css.get("numChargedBus")));
			cs.setRandom(deserializeRandom((String) css.get("rand")));
		}
		
//		ContextCreator.logger.info("Loaded cs snapshots");

		// 11. Restore vehicles
		// rebuildForLoad already created an empty VehicleContext, so no cleanup needed
		HashMap<Integer, Vehicle> restoredVehicleMap = new HashMap<>();
		VehicleContext vc = ContextCreator.getVehicleContext();

		// Now restore each vehicle from snapshot
		for (HashMap<String, Object> vs : vehicleSnapshots) {
			int vClass = toInt(vs.get("vehicleClass"));
			int vState = toInt(vs.get("vehicleState"));
			int vSensor = toInt(vs.get("vehicleSensorType"));
			int vID = toInt(vs.get("id"));

			Vehicle v;
			switch (vClass) {
				case Vehicle.ETAXI:
					v = restoreTaxi(vs);
					break;
				case Vehicle.EBUS:
					v = restoreBus(vs);
					break;
				case Vehicle.EV:
					v = restoreElectricVehicle(vs);
					break;
				default: // GV
					v = restoreBaseVehicle(vs);
					break;
			}

			// Set common fields
			v.setID(vID);
			v.setVehicleSensorType(vSensor);
			v.setState(vState);
			v.setCurrentCoord(new Coordinate(
					toDouble(vs.get("coordX")), toDouble(vs.get("coordY")),
					vs.containsKey("coordZ") ? toDouble(vs.get("coordZ")) : 0.0));
			v.setSpeed(toDouble(vs.get("speed")));
			v.setAccRate(toDouble(vs.get("accRate")));
			v.setBearing(toDouble(vs.get("bearing")));
			v.setOriginID(toInt(vs.get("originID")));
			v.setDestID(toInt(vs.get("destID")));
			v.setDepTime(toInt(vs.get("depTime")));
			v.setAccumulatedDistance(toDouble(vs.get("accumulatedDistance")));
			v.setNumTrips(toInt(vs.get("numTrips")));
			v.setMovingFlag(toBool(vs.get("movingFlag")));

			// Restore origin road
			int originRoadID = toInt(vs.get("originRoadID"));
			if (originRoadID >= 0) {
				Road originRoad = ContextCreator.getRoadContext().get(originRoadID);
				if (originRoad != null) v.setOriginRoad(originRoad);
			}

			// Resolve dest road (but don't set it yet -- setting it before
			// teleportVehicle would cause appendToRoad to trigger
			// rerouteAndSetNextRoad before the vehicle has a lane)
			int destRoadID = toInt(vs.get("destRoadID"));
			Road destRoad = null;
			if (destRoadID >= 0) {
				destRoad = ContextCreator.getRoadContext().get(destRoadID);
			}

			// Restore activity plan
			ArrayList<Plan> plans = new ArrayList<>();
			List<?> planList = (List<?>) vs.get("plans");
			if (planList != null) {
				for (Object po : planList) {
					Map<?, ?> pm = (Map<?, ?>) po;
					plans.add(new Plan(toInt(pm.get("zoneID")), toInt(pm.get("roadID")),
							toDouble(pm.get("departureTime"))));
				}
			}
			v.setActivityPlan(plans);

			// Restore random seeds
			v.setRandom(deserializeRandom((String) vs.get("rand")));
			v.setRandomRoute(deserializeRandom((String) vs.get("randRoute")));
			v.setRandomRelocate(deserializeRandom((String) vs.get("randRelocate")));
			v.setRandomCarFollow(deserializeRandom((String) vs.get("randCarFollow")));

			// Register vehicle in context
			vc.add(v);
			restoredVehicleMap.put(vID, v);

			// Register in appropriate maps
			if (v instanceof ElectricTaxi) {
				vc.registerTaxi((ElectricTaxi) v);
				ElectricTaxi taxi = (ElectricTaxi) v;
				if (vState == Vehicle.PARKING || vState == Vehicle.CRUISING_TRIP) {
					vc.addAvailableTaxi(taxi, taxi.getCurrentZone());
				} else if (vState == Vehicle.ACCESSIBLE_RELOCATION_TRIP) {
					vc.addRelocationTaxi(taxi, taxi.getCurrentZone());
				}
			} else if (v instanceof ElectricBus) {
				vc.registerBus((ElectricBus) v);
			} else if (vs.containsKey("privateVID")) {
				int privateVID = toInt(vs.get("privateVID"));
				if (v instanceof ElectricVehicle) {
					vc.registerPrivateEV(privateVID, (ElectricVehicle) v);
				} else {
					vc.registerPrivateGV(privateVID, v);
				}
			}

			// Place vehicle on road if it was on road
			boolean wasOnRoad = toBool(vs.get("onRoad"));
			int roadID = toInt(vs.get("roadID"));
			int laneIndex = toInt(vs.get("laneIndex"));
			double distance = toDouble(vs.get("distance"));

			if (wasOnRoad && roadID >= 0) {
				Road road = ContextCreator.getRoadContext().get(roadID);
				if (road != null) {
					Lane lane = null;
					if (laneIndex >= 0 && laneIndex < road.getNumberOfLanes()) {
						lane = road.getLane(laneIndex);
					} else if (road.getNumberOfLanes() > 0) {
						lane = road.getLane(0);
					}
					if (lane != null) {
						road.teleportVehicle(v, lane, Math.min(distance, lane.getLength()));
					}
				}
			}

			// Restore dest road
			if (destRoad != null) {
				v.setDestRoad(destRoad);
			}

			// Restore routing state
			v.setAtOrigin(toBool(vs.get("atOrigin")));
			v.setReachDest(toBool(vs.get("isReachDest")));
			v.setLinkTravelTime(toDouble(vs.get("linkTravelTime")));
			v.setDistToTravel(toDouble(vs.get("distToTravel")));

			// Restore road path
			List<?> roadPathData = (List<?>) vs.get("roadPath");
			if (roadPathData != null && !roadPathData.isEmpty()) {
				ArrayList<Road> restoredPath = new ArrayList<>();
				boolean pathValid = true;
				for (Object idObj : roadPathData) {
					int rpId = toInt(idObj);
					Road rpRoad = ContextCreator.getRoadContext().get(rpId);
					if (rpRoad != null) {
						restoredPath.add(rpRoad);
					} else {
						pathValid = false;
						break;
					}
				}
				if (pathValid && !restoredPath.isEmpty()) {
					v.setRoadPath(restoredPath);
					if (restoredPath.size() >= 2) {
						v.setNextRoadDirectly(restoredPath.get(1));
						v.assignNextLane();
					}
					v.setShadowImpact();
				} else if (v.isOnRoad() && destRoad != null) {
					v.rerouteAndSetNextRoad();
				}
			} else if (v.isOnRoad() && destRoad != null) {
				v.rerouteAndSetNextRoad();
			}
		}
//		ContextCreator.logger.info("Loaded vehicle snapshots");

		restoreRoadEnteringQueues(roadSnapshots, restoredVehicleMap);

		// 12. Restore charging station queues (now that vehicles are created)
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			HashMap<String, Object> css = csStateMap.get(cs.getID());
			if (css == null) continue;

			restoreChargingQueue(cs.getQueueL2(), (List<?>) css.get("queueL2"), restoredVehicleMap, false);
			restoreChargingQueue(cs.getQueueL3(), (List<?>) css.get("queueL3"), restoredVehicleMap, false);
			restoreChargingBusQueue(cs.getQueueBus(), (List<?>) css.get("queueBus"), restoredVehicleMap);
			restoreChargingList(cs.getChargingL2(), (List<?>) css.get("chargingL2"), restoredVehicleMap, false);
			restoreChargingList(cs.getChargingL3(), (List<?>) css.get("chargingL3"), restoredVehicleMap, false);
			restoreChargingBusList(cs.getChargingBus(), (List<?>) css.get("chargingBus"), restoredVehicleMap);
		}

		setAgentIDCounter(savedAgentID);
		ContextCreator.logger.info("Simulation state loaded successfully from: " + zipPath);
	}

	// ------------------------------------------------------------------ //
	//                     Vehicle restore helpers                         //
	// ------------------------------------------------------------------ //

	private static HashMap<Integer, Vehicle> restoreVehicles(ArrayList<HashMap<String, Object>> vehicleSnapshots) {
		HashMap<Integer, Vehicle> restoredVehicleMap = new HashMap<>();
		VehicleContext vc = ContextCreator.getVehicleContext();
		if (vehicleSnapshots == null) return restoredVehicleMap;

		for (HashMap<String, Object> vs : vehicleSnapshots) {
			int vClass = toInt(vs.get("vehicleClass"));
			int vState = toInt(vs.get("vehicleState"));
			int vSensor = toInt(vs.get("vehicleSensorType"));
			int vID = toInt(vs.get("id"));

			Vehicle v;
			switch (vClass) {
				case Vehicle.ETAXI:
					v = restoreTaxi(vs);
					break;
				case Vehicle.EBUS:
					v = restoreBus(vs);
					break;
				case Vehicle.EV:
					v = restoreElectricVehicle(vs);
					break;
				default:
					v = restoreBaseVehicle(vs);
					break;
			}

			v.setID(vID);
			v.setVehicleSensorType(vSensor);
			v.setState(vState);
			v.setCurrentCoord(new Coordinate(
					toDouble(vs.get("coordX")), toDouble(vs.get("coordY")),
					vs.containsKey("coordZ") ? toDouble(vs.get("coordZ")) : 0.0));
			v.setSpeed(toDouble(vs.get("speed")));
			v.setAccRate(toDouble(vs.get("accRate")));
			v.setBearing(toDouble(vs.get("bearing")));
			v.setOriginID(toInt(vs.get("originID")));
			v.setDestID(toInt(vs.get("destID")));
			v.setDepTime(toInt(vs.get("depTime")));
			v.setAccumulatedDistance(toDouble(vs.get("accumulatedDistance")));
			v.setNumTrips(toInt(vs.get("numTrips")));
			v.setMovingFlag(toBool(vs.get("movingFlag")));

			int originRoadID = toInt(vs.get("originRoadID"));
			if (originRoadID >= 0) {
				Road originRoad = ContextCreator.getRoadContext().get(originRoadID);
				if (originRoad != null) v.setOriginRoad(originRoad);
			}

			int destRoadID = toInt(vs.get("destRoadID"));
			Road destRoad = null;
			if (destRoadID >= 0) {
				destRoad = ContextCreator.getRoadContext().get(destRoadID);
			}

			ArrayList<Plan> plans = new ArrayList<>();
			List<?> planList = (List<?>) vs.get("plans");
			if (planList != null) {
				for (Object po : planList) {
					Map<?, ?> pm = (Map<?, ?>) po;
					plans.add(new Plan(toInt(pm.get("zoneID")), toInt(pm.get("roadID")),
							toDouble(pm.get("departureTime"))));
				}
			}
			v.setActivityPlan(plans);

			v.setRandom(deserializeRandom((String) vs.get("rand")));
			v.setRandomRoute(deserializeRandom((String) vs.get("randRoute")));
			v.setRandomRelocate(deserializeRandom((String) vs.get("randRelocate")));
			v.setRandomCarFollow(deserializeRandom((String) vs.get("randCarFollow")));

			vc.add(v);
			restoredVehicleMap.put(vID, v);

			if (v instanceof ElectricTaxi) {
				vc.registerTaxi((ElectricTaxi) v);
				ElectricTaxi taxi = (ElectricTaxi) v;
				if (vState == Vehicle.PARKING || vState == Vehicle.CRUISING_TRIP) {
					vc.addAvailableTaxi(taxi, taxi.getCurrentZone());
				} else if (vState == Vehicle.ACCESSIBLE_RELOCATION_TRIP) {
					vc.addRelocationTaxi(taxi, taxi.getCurrentZone());
				}
			} else if (v instanceof ElectricBus) {
				vc.registerBus((ElectricBus) v);
			} else if (vs.containsKey("privateVID")) {
				int privateVID = toInt(vs.get("privateVID"));
				if (v instanceof ElectricVehicle) {
					vc.registerPrivateEV(privateVID, (ElectricVehicle) v);
				} else {
					vc.registerPrivateGV(privateVID, v);
				}
			}

			boolean wasOnRoad = toBool(vs.get("onRoad"));
			int roadID = toInt(vs.get("roadID"));
			int laneIndex = toInt(vs.get("laneIndex"));
			double distance = toDouble(vs.get("distance"));

			if (wasOnRoad && roadID >= 0) {
				Road road = ContextCreator.getRoadContext().get(roadID);
				if (road != null) {
					Lane lane = null;
					if (laneIndex >= 0 && laneIndex < road.getNumberOfLanes()) {
						lane = road.getLane(laneIndex);
					} else if (road.getNumberOfLanes() > 0) {
						lane = road.getLane(0);
					}
					if (lane != null) {
						road.teleportVehicle(v, lane, Math.min(distance, lane.getLength()));
					}
				}
			}

			if (destRoad != null) {
				v.setDestRoad(destRoad);
			}

			v.setAtOrigin(toBool(vs.get("atOrigin")));
			v.setReachDest(toBool(vs.get("isReachDest")));
			v.setLinkTravelTime(toDouble(vs.get("linkTravelTime")));
			v.setDistToTravel(toDouble(vs.get("distToTravel")));

			List<?> roadPathData = (List<?>) vs.get("roadPath");
			if (roadPathData != null && !roadPathData.isEmpty()) {
				ArrayList<Road> restoredPath = new ArrayList<>();
				boolean pathValid = true;
				for (Object idObj : roadPathData) {
					int rpId = toInt(idObj);
					Road rpRoad = ContextCreator.getRoadContext().get(rpId);
					if (rpRoad != null) {
						restoredPath.add(rpRoad);
					} else {
						pathValid = false;
						break;
					}
				}
				if (pathValid && !restoredPath.isEmpty()) {
					v.setRoadPath(restoredPath);
					if (restoredPath.size() >= 2) {
						v.setNextRoadDirectly(restoredPath.get(1));
						v.assignNextLane();
					}
					v.setShadowImpact();
				} else if (v.isOnRoad() && destRoad != null) {
					v.rerouteAndSetNextRoad();
				}
			} else if (v.isOnRoad() && destRoad != null) {
				v.rerouteAndSetNextRoad();
			}
		}

		return restoredVehicleMap;
	}

	private static ElectricTaxi restoreTaxi(HashMap<String, Object> vs) {
		ElectricTaxi taxi = new ElectricTaxi();
		restoreEVFields(taxi, vs);
		taxi.setPassNum(toInt(vs.get("passNum")));
		taxi.setCurrentZone(toInt(vs.get("currentZone")));
		taxi.setCruisingTime(toInt(vs.get("cruisingTime")));
		taxi.setMatchedRequests(toInt(vs.get("matchedRequests")));
		taxi.setMatchedPassengers(vs.containsKey("matchedPassengers")
				? toInt(vs.get("matchedPassengers")) : toInt(vs.get("servedPass")));
		taxi.setPickupRequests(toInt(vs.get("pickupRequests")));
		taxi.setPickupPassengers(toInt(vs.get("pickupPassengers")));
		taxi.setDropoffRequests(toInt(vs.get("dropoffRequests")));
		taxi.setDropoffPassengers(toInt(vs.get("dropoffPassengers")));

		// Restore request queues
		LinkedList<Request> toBoard = new LinkedList<>();
		restoreRequestQueue(toBoard, (List<?>) vs.get("toBoardRequests"));
		taxi.setToBoardRequests(toBoard);

		LinkedList<Request> onBoard = new LinkedList<>();
		restoreRequestQueue(onBoard, (List<?>) vs.get("onBoardRequests"));
		taxi.setOnBoardRequests(onBoard);

		return taxi;
	}

	private static ElectricBus restoreBus(HashMap<String, Object> vs) {
		ArrayList<Integer> stopZones = toIntList((List<?>) vs.get("stopZones"));
		ArrayList<Integer> departureTimes = toIntList((List<?>) vs.get("departureTimes"));
		int routeID = toInt(vs.get("routeID"));

		ElectricBus bus = new ElectricBus(routeID, stopZones, departureTimes);
		restoreEVFields(bus, vs);
		bus.setPassNum(toInt(vs.get("passNum")));
		bus.setNextStop(toInt(vs.get("nextStop")));
		bus.setMatchedRequests(toInt(vs.get("matchedRequests")));
		bus.setMatchedPassengers(vs.containsKey("matchedPassengers")
				? toInt(vs.get("matchedPassengers")) : toInt(vs.get("servedPass")));
		bus.setPickupRequests(toInt(vs.get("pickupRequests")));
		bus.setPickupPassengers(toInt(vs.get("pickupPassengers")));
		bus.setDropoffRequests(toInt(vs.get("dropoffRequests")));
		bus.setDropoffPassengers(toInt(vs.get("dropoffPassengers")));

		// Restore per-stop request queues
		List<?> busTBR = (List<?>) vs.get("busToBoardRequests");
		if (busTBR != null) {
			ArrayList<Queue<Request>> tbr = bus.getToBoardRequests();
			for (int i = 0; i < Math.min(busTBR.size(), tbr.size()); i++) {
				restoreRequestQueue(tbr.get(i), (List<?>) busTBR.get(i));
			}
		}
		List<?> busOBR = (List<?>) vs.get("busOnBoardRequests");
		if (busOBR != null) {
			ArrayList<Queue<Request>> obr = bus.getOnBoardRequests();
			for (int i = 0; i < Math.min(busOBR.size(), obr.size()); i++) {
				restoreRequestQueue(obr.get(i), (List<?>) busOBR.get(i));
			}
		}

		return bus;
	}

	private static ElectricVehicle restoreElectricVehicle(HashMap<String, Object> vs) {
		ElectricVehicle ev = new ElectricVehicle(Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE);
		restoreEVFields(ev, vs);
		return ev;
	}

	private static Vehicle restoreBaseVehicle(HashMap<String, Object> vs) {
		return new Vehicle(Vehicle.GV, Vehicle.NONE_OF_THE_ABOVE);
	}

	private static void restoreEVFields(ElectricVehicle ev, HashMap<String, Object> vs) {
		if (vs.containsKey("batteryLevel")) ev.setBatteryLevelDirectly(toDouble(vs.get("batteryLevel")));
		if (vs.containsKey("batteryCapacity")) ev.setBatteryCapacity(toDouble(vs.get("batteryCapacity")));
		if (vs.containsKey("onChargingRoute")) ev.setOnChargingRoute(toBool(vs.get("onChargingRoute")));
		if (vs.containsKey("totalConsume")) ev.setTotalConsume(toDouble(vs.get("totalConsume")));
		if (vs.containsKey("tripConsume")) ev.setTripConsume(toDouble(vs.get("tripConsume")));
		if (vs.containsKey("linkConsume")) ev.setLinkConsume(toDouble(vs.get("linkConsume")));
		if (vs.containsKey("mass")) ev.setMass_(toDouble(vs.get("mass")));
		if (vs.containsKey("lowerRechargeLevel")) ev.setLowerRechargeLevel(toDouble(vs.get("lowerRechargeLevel")));
		if (vs.containsKey("higherRechargeLevel")) ev.setHigherRechargeLevel(toDouble(vs.get("higherRechargeLevel")));
		if (vs.containsKey("metersPerKwh")) ev.setMetersPerKwh(toDouble(vs.get("metersPerKwh")));
		if (vs.containsKey("chargingTime")) ev.setChargingTime(toInt(vs.get("chargingTime")));
		if (vs.containsKey("chargingWaitingTime")) ev.setChargingWaitingTime(toInt(vs.get("chargingWaitingTime")));
		if (vs.containsKey("initialChargingState")) ev.setInitialChargingState(toDouble(vs.get("initialChargingState")));
	}

	private static void restoreRoadEnteringQueues(ArrayList<HashMap<String, Object>> roadSnapshots,
			HashMap<Integer, Vehicle> restoredVehicleMap) {
		if (roadSnapshots == null || restoredVehicleMap == null) return;
		for (HashMap<String, Object> rs : roadSnapshots) {
			if (rs == null) continue;
			Road road = ContextCreator.getRoadContext().get(toInt(rs.get("id")));
			if (road == null) continue;
			ArrayList<Vehicle> queue = new ArrayList<Vehicle>();
			for (int vehicleID : toIntList((List<?>) rs.get("enteringVehicleQueue"))) {
				Vehicle vehicle = restoredVehicleMap.get(vehicleID);
				if (vehicle != null) {
					queue.add(vehicle);
				}
			}
			road.restoreEnteringVehicleQueue(queue);
		}
	}

	// ------------------------------------------------------------------ //
	//               Request and queue restore helpers                     //
	// ------------------------------------------------------------------ //

	private static void restoreRequestQueue(Queue<Request> queue, List<?> data) {
		if (data == null || queue == null) return;
		for (Object obj : data) {
			Map<?, ?> rm = (Map<?, ?>) obj;
			Request r = new Request(
					toInt(rm.get("origin")),
					toInt(rm.get("dest")),
					toInt(rm.get("originRoad")),
					toInt(rm.get("destRoad")),
					toInt(rm.get("numPeople")));
			r.setID(toInt(rm.get("id")));
			r.setMaxWaitingTime(toInt(rm.get("maxWaitingTime")));
			r.setCurrentWaitingTime(toInt(rm.get("currentWaitingTime")));
			r.setWillingToShare(toBool(rm.get("willingToShare")));
			r.setBusRoute(toInt(rm.get("busRouteID")));
			r.generationTime = toInt(rm.get("generationTime"));
			r.matchedTime = toInt(rm.get("matchedTime"));
			r.pickupTime = toInt(rm.get("pickupTime"));
			r.arriveTIme = toInt(rm.get("arriveTime"));

			// Restore activity plan for requests
			List<?> planList = (List<?>) rm.get("activityPlan");
			if (planList != null && !planList.isEmpty()) {
				LinkedList<Plan> plans = new LinkedList<>();
				for (Object po : planList) {
					Map<?, ?> pm = (Map<?, ?>) po;
					plans.add(new Plan(toInt(pm.get("zoneID")), toInt(pm.get("roadID")),
							toDouble(pm.get("departureTime"))));
				}
				r.clearActivityPlan();
				r.getActivityPlan().addAll(plans);
			}

			queue.add(r);
		}
	}

	private static void restoreChargingQueue(LinkedList<ElectricVehicle> queue, List<?> idList,
			HashMap<Integer, Vehicle> vehicleMap, boolean dummy) {
		if (idList == null) return;
		for (Object idObj : idList) {
			int vid = toInt(idObj);
			Vehicle v = vehicleMap.get(vid);
			if (v instanceof ElectricVehicle) {
				queue.add((ElectricVehicle) v);
			}
		}
	}

	private static void restoreChargingBusQueue(LinkedList<ElectricBus> queue, List<?> idList,
			HashMap<Integer, Vehicle> vehicleMap) {
		if (idList == null) return;
		for (Object idObj : idList) {
			int vid = toInt(idObj);
			Vehicle v = vehicleMap.get(vid);
			if (v instanceof ElectricBus) {
				queue.add((ElectricBus) v);
			}
		}
	}

	private static void restoreChargingList(ArrayList<ElectricVehicle> list, List<?> idList,
			HashMap<Integer, Vehicle> vehicleMap, boolean dummy) {
		if (idList == null) return;
		for (Object idObj : idList) {
			int vid = toInt(idObj);
			Vehicle v = vehicleMap.get(vid);
			if (v instanceof ElectricVehicle) {
				list.add((ElectricVehicle) v);
			}
		}
	}

	private static void restoreChargingBusList(ArrayList<ElectricBus> list, List<?> idList,
			HashMap<Integer, Vehicle> vehicleMap) {
		if (idList == null) return;
		for (Object idObj : idList) {
			int vid = toInt(idObj);
			Vehicle v = vehicleMap.get(vid);
			if (v instanceof ElectricBus) {
				list.add((ElectricBus) v);
			}
		}
	}

	// ------------------------------------------------------------------ //
	//                     Type conversion helpers                         //
	// ------------------------------------------------------------------ //

	private static int toInt(Object obj) {
		if (obj == null) return 0;
		if (obj instanceof Number) return ((Number) obj).intValue();
		return Integer.parseInt(obj.toString());
	}

	private static double toDouble(Object obj) {
		if (obj == null) return 0.0;
		if (obj instanceof Number) return ((Number) obj).doubleValue();
		return Double.parseDouble(obj.toString());
	}

	private static boolean toBool(Object obj) {
		if (obj == null) return false;
		if (obj instanceof Boolean) return (Boolean) obj;
		return Boolean.parseBoolean(obj.toString());
	}

	private static ArrayList<Integer> toIntList(List<?> list) {
		ArrayList<Integer> result = new ArrayList<>();
		if (list != null) {
			for (Object obj : list) {
				result.add(toInt(obj));
			}
		}
		return result;
	}
}
