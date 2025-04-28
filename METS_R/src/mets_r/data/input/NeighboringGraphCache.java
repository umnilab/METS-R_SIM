package mets_r.data.input;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mets_r.ContextCreator;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Zone;

public class NeighboringGraphCache {
    public Map<Integer, ZoneNeighbors> zones = new HashMap<>();
    public Map<Integer, RoadNeighbors> roads = new HashMap<>();
    public Map<Integer, ChargingStationNeighbors> chargingStations = new HashMap<>();
    
    public void load() {
    	for (Zone z : ContextCreator.getZoneContext().getAll()) {
            ZoneNeighbors neighbors = zones.get(z.getID());
            if (neighbors != null) {
            	for(int zid: neighbors.neighboringZoneIDs) z.addNeighboringZone(zid);
            	for(int rid: neighbors.neighboringDepartureLinkIDs) z.addNeighboringLink(rid, false);
            	for(int rid: neighbors.neighboringArrivalLinkIDs) z.addNeighboringLink(rid, true);
            	z.setClosestRoad(neighbors.closestDepartureLinkID, false);
            	z.setClosestRoad(neighbors.closestArrivalLinkID, true);
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
    
    public void saveZoneNeighbor(int zid, List<Integer> neighboringZoneIDs, List<Integer> neighboringArrivalLinkIDs, List<Integer> neighboringDepartureLinkIDs, Integer closestDepartureLinkID, Integer closestArrivalLinkID) {
    	if(!zones.containsKey(zid)) {
    		ZoneNeighbors zn = new ZoneNeighbors(neighboringZoneIDs, neighboringArrivalLinkIDs, neighboringDepartureLinkIDs, closestDepartureLinkID, closestArrivalLinkID);
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
    public ZoneNeighbors(List<Integer> neighboringZoneIDs, List<Integer> neighboringArrivalLinkIDs2, List<Integer> neighboringDepartureLinkIDs, int closestDepartureLinkID, int closestArrivalLinkID) {
    	this.neighboringZoneIDs = neighboringZoneIDs;
        this.neighboringArrivalLinkIDs = neighboringArrivalLinkIDs2;
        this.neighboringDepartureLinkIDs = neighboringDepartureLinkIDs;
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
