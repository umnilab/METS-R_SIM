package mets_r.data.input;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import mets_r.ContextCreator;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Zone;

public class NeighboringGraphCache {
	public static final int CURRENT_SCHEMA_VERSION = 4;
	public int schemaVersion;
    public Map<Integer, ZoneNeighbors> zones = new HashMap<>();
    public Map<Integer, RoadNeighbors> roads = new HashMap<>();
    public Map<Integer, ChargingStationNeighbors> chargingStations = new HashMap<>();

	@JsonIgnore
	public boolean isCompatible() {
		return this.schemaVersion == CURRENT_SCHEMA_VERSION;
	}

	public void markCurrentSchemaVersion() {
		this.schemaVersion = CURRENT_SCHEMA_VERSION;
	}
    
	public void load() {
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			ZoneNeighbors neighbors = zones.get(z.getID());
			if (neighbors != null) {
				for (int zid : neighbors.neighboringZoneIDs) z.addNeighboringZone(zid);
				for (int rid : neighbors.neighboringDepartureLinkIDs) {
					Road road = ContextCreator.getRoadContext().get(rid);
					if (road != null && road.canBeTripOrigin()) z.addNeighboringLink(rid, false);
				}
				for (int rid : neighbors.neighboringArrivalLinkIDs) {
					Road road = ContextCreator.getRoadContext().get(rid);
					if (road != null && road.canBeTripDestination()) z.addNeighboringLink(rid, true);
				}
				Road closestDeparture = ContextCreator.getRoadContext()
						.get(neighbors.closestDepartureLinkID);
				Road closestArrival = ContextCreator.getRoadContext()
						.get(neighbors.closestArrivalLinkID);
				if (closestDeparture != null && closestDeparture.canBeTripOrigin()) {
					z.setClosestRoad(neighbors.closestDepartureLinkID, false);
				}
				if (closestArrival != null && closestArrival.canBeTripDestination()) {
					z.setClosestRoad(neighbors.closestArrivalLinkID, true);
				}
			}
		}
        for (Road r : ContextCreator.getRoadContext().getAll()) {
            RoadNeighbors neighbors = roads.get(r.getID());
            if (neighbors != null) {
                r.setNeighboringZone(neighbors.neighboringZoneOrigin, false);
                r.setNeighboringZone(neighbors.neighboringZoneDest, true);
            }
        }
        for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
            ChargingStationNeighbors neighbors = chargingStations.get(cs.getID());
            if (neighbors != null) {
                cs.setClosestRoad(neighbors.closestDepartureLinkID, false);
                cs.setClosestRoad(neighbors.closestArrivalLinkID, true);
            }
        }
    }
    
	public void saveZoneNeighbor(int zid, List<Integer> neighboringZoneIDs,
			List<Integer> neighboringDepartureLinkIDs,
			List<Integer> neighboringArrivalLinkIDs,
			Integer closestDepartureLinkID, Integer closestArrivalLinkID) {
		if (!zones.containsKey(zid)) {
			ZoneNeighbors zn = new ZoneNeighbors(neighboringZoneIDs,
					neighboringDepartureLinkIDs, neighboringArrivalLinkIDs,
					closestDepartureLinkID, closestArrivalLinkID);
			zones.put(zid, zn);
		}
		else {
			ContextCreator.logger.warn("Zone " + zid + " already exists in the zone nighbors.");
		}
	}
    
    public void saveRoadNeighbor(int rid, Integer neighboringZoneOrigin, Integer neighboringZoneDest) {
    	if(!roads.containsKey(rid)) {
    		RoadNeighbors rn = new RoadNeighbors(neighboringZoneOrigin, neighboringZoneDest);
    		roads.put(rid, rn);
    	}
    	else {
    		ContextCreator.logger.warn("Road " + rid + " already exists in the road nighbors.");
    	}
    }
    
    public void saveChargingStationNeighbor(int csid, int closestDepartureLinkID, int closestArrivalLinkID) {
    	if(!chargingStations.containsKey(csid)) {
    		ChargingStationNeighbors cn = new ChargingStationNeighbors(closestDepartureLinkID, closestArrivalLinkID);
    		chargingStations.put(csid, cn);
    	}
    	else {
    		ContextCreator.logger.warn("Charging station " + csid + " already exists in the charging station nighbors.");
    	}
    }
}

class ZoneNeighbors {
    public List<Integer> neighboringZoneIDs;
    public List<Integer> neighboringDepartureLinkIDs;
    public List<Integer> neighboringArrivalLinkIDs;
    public int closestDepartureLinkID;
    public int closestArrivalLinkID;
    public ZoneNeighbors() {
        // Default constructor needed by Jackson
    }
	public ZoneNeighbors(List<Integer> neighboringZoneIDs,
			List<Integer> neighboringDepartureLinkIDs,
			List<Integer> neighboringArrivalLinkIDs,
			int closestDepartureLinkID, int closestArrivalLinkID) {
    	this.neighboringZoneIDs = neighboringZoneIDs;
        this.neighboringDepartureLinkIDs = neighboringDepartureLinkIDs;
		this.neighboringArrivalLinkIDs = neighboringArrivalLinkIDs;
        this.closestDepartureLinkID = closestDepartureLinkID;
        this.closestArrivalLinkID = closestArrivalLinkID;
    }
}

class RoadNeighbors {
    public Integer neighboringZoneOrigin;
    public Integer neighboringZoneDest;
    public RoadNeighbors() {
        // Default constructor needed by Jackson
    }
    public RoadNeighbors(Integer neighboringZoneOrigin, Integer neighboringZoneDest) {
        this.neighboringZoneOrigin = neighboringZoneOrigin;
        this.neighboringZoneDest = neighboringZoneDest;
    }
}

class ChargingStationNeighbors {
    public int closestDepartureLinkID;
    public int closestArrivalLinkID;
    public ChargingStationNeighbors() {
        // Default constructor needed by Jackson
    }
    public ChargingStationNeighbors(int closestDepartureLinkID, int closestArrivalLinkID) {
        this.closestDepartureLinkID = closestDepartureLinkID;
        this.closestArrivalLinkID = closestArrivalLinkID;
    }
}
