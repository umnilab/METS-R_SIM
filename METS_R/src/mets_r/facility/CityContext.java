package mets_r.facility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.geotools.referencing.GeodeticCalculator;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import mets_r.*;
import mets_r.routing.RouteContext;
import repast.simphony.context.DefaultContext;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;

/**
 * Inherent from A-RESCUE
 * 
 * Initialize and maintain facility agents
 **/
public class CityContext extends DefaultContext<Object> {
	private HashMap<RepastEdge<?>, Road> edgeRoad_KeyEdge; // Store the linkIDs of Repast edges (Edge as key)
	private HashMap<RepastEdge<?>, Integer> edgeIdNum_KeyEdge; // Store the TOIDs of edges (Edge as key)
	private HashMap<Integer, RepastEdge<?>> edgeIDs_KeyIDNum; // Store the TOIDs of edges (TOID as key)
	private HashMap<Integer, Lane> lane_KeyLaneID;
	private HashMap<Integer, Road> road_KeyLinkID;
	private HashMap<Integer, Junction> junction_KeyJunctionID;
	private HashMap<Coordinate, Road> road_KeyCoord; // Cache the closest road
	private HashMap<Integer, HashMap<Integer, Road>> road_KeyJunctionID;
          
	public CityContext() {
		super("CityContext"); // Very important otherwise repast complains
		this.edgeRoad_KeyEdge = new HashMap<RepastEdge<?>, Road>();
		this.edgeIdNum_KeyEdge = new HashMap<RepastEdge<?>, Integer>();
		this.edgeIDs_KeyIDNum = new HashMap<Integer, RepastEdge<?>>();
		this.lane_KeyLaneID = new HashMap<Integer, Lane>();
		this.junction_KeyJunctionID = new HashMap<Integer, Junction>();
		this.road_KeyLinkID = new HashMap<Integer, Road>();
		this.road_KeyCoord = new HashMap<Coordinate, Road>();
		this.road_KeyJunctionID = new HashMap<Integer, HashMap<Integer, Road>>();
	}

	public void createSubContexts() {
		this.addSubContext(new RoadContext());
		this.addSubContext(new JunctionContext());
		this.addSubContext(new LaneContext());
		this.initializeLaneDistance();
		this.addSubContext(new ZoneContext());
		this.addSubContext(new ChargingStationContext());
	}
	
	// Calculate the length of each length based on their geometries
	private void initializeLaneDistance() {
		for (Lane lane : ContextCreator.getLaneGeography().getAllObjects()) {
			Coordinate[] coords = ContextCreator.getLaneGeography().getGeometry(lane).getCoordinates();
			float distance = 0;
			for (int i = 0; i < coords.length - 1; i++) {
				distance += getDistance(coords[i], coords[i + 1]);
			}
			lane.setLength(distance);
		}
	}
	
	// Set the neighboring links/zones
	public void setNeighboringGraph() {
		Geography<Road> roadGeography = ContextCreator.getRoadGeography();
		for (Zone z1 : ContextCreator.getZoneContext().getAllObjects()) {
			int threshold = 1000; // initial threshold as 1 km
			boolean flag = true;
			while (flag) {
				for (Zone z2 : ContextCreator.getZoneContext().getAllObjects()) {
					if (this.getDistance(z1.getCoord(), z2.getCoord()) < threshold && z1 != z2) {
						z1.addNeighboringZone(z2);
					}
				}
				if (z1.getNeighboringZoneSize() < ContextCreator.getZoneGeography().size() - 1) {
					threshold = threshold + 1000;
				} else {
					flag = false;
				}
			}
			GeometryFactory geomFac = new GeometryFactory();
			Point point = geomFac.createPoint(z1.getCoord());
			Geometry buffer = point.buffer(GlobalVariables.SEARCHING_BUFFER); 
			for (Road r : roadGeography.getObjectsWithin(buffer.getEnvelopeInternal(), Road.class)) {
				double dist = this.getDistance(z1.getCoord(), r.getCoord());
				if(dist < r.distToZone) {
					r.neighboringZone = z1;
					r.distToZone = dist;
				}
			}
		}
		
		for (Road r: roadGeography.getAllObjects()) {
			if(r.neighboringZone != null) {
				r.neighboringZone.addNeighboringLink(r);
			}
		}
		
		for (Zone z: ContextCreator.getZoneContext().getAllObjects()) {
			int i = 1;
			while (z.getNeighboringLinkSize() < 10) { // Take at least neighboring 10 links
				GeometryFactory geomFac = new GeometryFactory();
				Point point = geomFac.createPoint(z.getCoord());
				Geometry buffer = point.buffer(GlobalVariables.SEARCHING_BUFFER); 
				for (Road r : roadGeography.getObjectsWithin(buffer.getEnvelopeInternal(), Road.class)) {
					if(this.getDistance(z.getCoord(), r.getCoord()) < i * GlobalVariables.CRUISING_BUFFER) {
						z.addNeighboringLink(r);
					}
				}
				i++;
			}
		}
		
	}
	
	public double getDistance(Coordinate c1, Coordinate c2) {
		GeodeticCalculator calculator = new GeodeticCalculator(ContextCreator.getLaneGeography().getCRS());
		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		double distance = calculator.getOrthodromicDistance();
		return distance;
	}
	
	public void buildRoadNetwork() {
		// Get lane geography
		Geography<Lane> laneGeography = ContextCreator.getLaneGeography();
		Iterable<Lane> laneIt = laneGeography.getAllObjects();

		// Get road geography
		Geography<Road> roadGeography = ContextCreator.getRoadGeography();
		Iterable<Road> roadIt = roadGeography.getAllObjects();
		Geography<Junction> junctionGeography = ContextCreator.getJunctionGeography();
		JunctionContext junctionContext = ContextCreator.getJunctionContext();
		Network<Junction> roadNetwork = ContextCreator.getRoadNetwork();

		GeometryFactory geomFac = new GeometryFactory();
		
		for (Road road : roadIt) {
			this.road_KeyLinkID.put(road.getLinkid(), road);
			Geometry roadGeom = roadGeography.getGeometry(road);
			Coordinate c1 = roadGeom.getCoordinates()[0];
			Coordinate c2 = roadGeom.getCoordinates()[roadGeom.getNumPoints() - 1];
			// Create Junctions from these coordinates and add them to the
			// JunctionGeography (if they haven't been created already)
			Junction junc1, junc2;
			if (!junction_KeyJunctionID.containsKey(road.getFn())) {
				junc1 = new Junction(c1, road.getFn());
				this.junction_KeyJunctionID.put(road.getFn(), junc1);
				junctionContext.add(junc1);
				Point p1 = geomFac.createPoint(c1);
				junctionGeography.move(junc1, p1);
			} else
				junc1 = junction_KeyJunctionID.get(road.getFn());

			if (!junction_KeyJunctionID.containsKey(road.getTn())) {
				junc2 = new Junction(c2, road.getTn());
				this.junction_KeyJunctionID.put(road.getTn(), junc2);
				junctionContext.add(junc2);
				Point p2 = geomFac.createPoint(c2);
				junctionGeography.move(junc2, p2);
			} else
				junc2 = junction_KeyJunctionID.get(road.getTn());

			// Tell the road object who its junctions are
			road.addJunction(junc1);
			road.addJunction(junc2);

			// Tell the junctions about their road
			junc1.addExitingRoad(road);
			junc2.addIncomingRoad(road);

			RepastEdge<Junction> edge = new RepastEdge<Junction>(junc1, junc2, true, road.getLength()); 

			// Store the road's TOID in a dictionary (one with edges as keys,
			try {
				this.road_KeyCoord.put(junc1.getCoord(), road);
				this.edgeRoad_KeyEdge.put(edge, road);
				this.edgeIdNum_KeyEdge.put(edge, road.getID());
				this.edgeIDs_KeyIDNum.put(road.getID(), edge);

				if (road_KeyJunctionID.containsKey(junc1.getJunctionID())) {
					this.road_KeyJunctionID.get(junc1.getJunctionID()).put(junc2.getJunctionID(), road);
				} else {
					HashMap<Integer, Road> tmp = new HashMap<Integer, Road>();
					tmp.put(junc2.getJunctionID(), road);
					this.road_KeyJunctionID.put(junc1.getJunctionID(), tmp);
				}
			} catch (Exception e) {
				ContextCreator.logger.error(e.getMessage());
			}
			if (!roadNetwork.containsEdge(edge)) {
				roadNetwork.addEdge(edge);
			} else {
				ContextCreator.logger
						.error("CityContext: buildRoadNetwork: for some reason this edge that has just been created "
								+ "already exists in the RoadNetwork!");
			}

		}
		roadIt = roadGeography.getAllObjects();
		ContextCreator.logger.info("Junction initialized!");
		// Assign the lanes to each road
		for (Lane lane : laneIt) {
			this.lane_KeyLaneID.put(lane.getLaneid(), lane);
			Road road = this.findRoadWithLinkID(lane.getLink());
			lane.setRoad(road);
		}
		for (Road r : roadIt) {
			r.sortLanes();
			roadMovementFromShapeFile(r);
			laneConnectionsFromShapeFile(r);
		}
		for (Junction j: junctionGeography.getAllObjects()) {
			ArrayList<Integer> roadTypes = new ArrayList<Integer>();
			for(Road r: j.getIncomingRoads()) {
				roadTypes.add(r.getRoadType());
			}
			Collections.sort(roadTypes);
			
			if(j.getIncomingRoads().size()>=2) {
				if((roadTypes.get(0) == Road.Street) && 
						(roadTypes.get(j.getIncomingRoads().size()-1) <=  Road.Highway)) {
					if(j.getIncomingRoads().size() >= 3) {
						int delay = (int) Math.ceil(30/GlobalVariables.SIMULATION_STEP_SIZE);
						Road firstRoad = j.getIncomingRoads().get(0);
						firstRoad.setDelay(delay, true, 0, 2);
						Road secondRoad = firstRoad.getConnectedRoads().get(0).getOppositeRoad();
						if(secondRoad != null) {
							secondRoad.setDelay(delay, true, 0, 2);
						}
						for(Road r: j.getIncomingRoads()) {
							if(r!= firstRoad && r!=secondRoad) {
								r.setDelay(delay, true, 1, 2);
							}
						}
					}
				}
				else if((roadTypes.get(0) == Road.Street) && 
				(roadTypes.get(j.getIncomingRoads().size()-1) ==  Road.Driveway)){
					for(Road r: j.getIncomingRoads()) {
						if(r.getRoadType() == Road.Driveway) {
							int delay = (int) Math.ceil(3/GlobalVariables.SIMULATION_STEP_SIZE);
							r.setDelay(delay, false, 0, 0);
						}
					}
				}
			}
			
		}
	}

	// Get road movement from shapefile and put in an array list
	public void roadMovementFromShapeFile(Road road) {
		ArrayList<Integer> dsLinkIds = new ArrayList<Integer>();
		dsLinkIds.add(road.getLeft());
		dsLinkIds.add(road.getThrough());
		dsLinkIds.add(road.getRight());
		dsLinkIds.add(road.getTlinkid());
		Road opRoad = this.road_KeyLinkID.get(road.getTlinkid());
		road.setOppositeRoad(opRoad);

		for (int dsRoadId : dsLinkIds) {
			if (dsRoadId != 0) {
				Road dsRoad = this.road_KeyLinkID.get(dsRoadId);
				road.addDownStreamMovement(dsRoad);
			}
		}
	}

	public Lane getLanefromID(int laneID) {
		// Lane lane = null;
		Geography<Lane> laneGeography = ContextCreator.getLaneGeography();
		Iterable<Lane> laneIt = laneGeography.getAllObjects();
		for (Lane lane : laneIt) {
			if (lane.getLaneid() == laneID) {
				return lane;
			}
		}
		return null;
	}

	public void laneConnectionsFromShapeFile(Road road) {
		ArrayList<Integer> dsLaneIds = new ArrayList<Integer>();
		int nLanes = road.getnLanes(); // number of lanes in current road
		Lane curLane, dsLane;
		for (int i = 0; i < nLanes; i++) {
			curLane = road.getLanes().get(i);
			dsLaneIds.clear();
			dsLaneIds.add(curLane.getLeft());
			dsLaneIds.add(curLane.getThrough());
			dsLaneIds.add(curLane.getRight());
			ContextCreator.logger.debug("Lane: " + curLane.getLaneid() + " from road " + curLane.getRoad().getIdentifier()
					+ " has downstream connections: ");
			for (double dsLaneId : dsLaneIds) {
				if (dsLaneId != 0) {
					dsLane = this.lane_KeyLaneID.get((int) dsLaneId);
					curLane.addDnLane(dsLane);
					dsLane.addUpLane(curLane);
				}
			}
		}
		// Add u-connected lanes
		if (road.getOppositeRoad() != null) {
			curLane = road.getLanes().get(0);
			if (curLane.getLength() > GlobalVariables.MIN_UTURN_LENGTH) {
				dsLane = road.getOppositeRoad().getLanes().get(0);
				curLane.addDnLane(dsLane);
				dsLane.addUpLane(curLane);
			}
		}
	}

	// Update node based routing
	public void modifyRoadNetwork() {
		for (Road road : ContextCreator.getRoadContext().getAllObjects()) {
			if(road.setTravelTime()) {
				Junction junc1 = road.getJunctions().get(0);
				Junction junc2 = road.getJunctions().get(1);
				ContextCreator.getRoadNetwork().getEdge(junc1, junc2).setWeight(road.getTravelTime());
			}
		}

		// At beginning, initialize route object
		if (!ContextCreator.isRouteUCBMapPopulated()) {
			try {
				RouteContext.createRoute();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	// Gets the ID String associated with a given edge. 
	public Road getRoadFromEdge(RepastEdge<Junction> edge) {
		return this.edgeRoad_KeyEdge.get(edge);
	}

	public int getIdNumFromEdge(RepastEdge<Junction> edge) {
		int id = this.edgeIdNum_KeyEdge.get(edge);
		return id;
	}

	public RepastEdge<?> getEdgeFromIDNum(int id) {
		RepastEdge<?> edge = this.edgeIDs_KeyIDNum.get(id);
		return edge;
	}

	// Returns the closest charging station from the currentLocation
	public ChargingStation findNearestChargingStation(Coordinate coord) throws NullPointerException {
		if (coord == null) {
			throw new NullPointerException(
					"CityContext: findNearestChargingStation: ERROR: the input coordinate is null");
		}

		GeometryFactory geomFac = new GeometryFactory();
		Geography<ChargingStation> csGeography = ContextCreator.getChargingStationGeography();
		// Use a buffer for efficiency
		Point point = geomFac.createPoint(coord);
		Geometry buffer = point.buffer(GlobalVariables.SEARCHING_BUFFER);
		double minDist = Double.MAX_VALUE;
		ChargingStation nearestChargingStation = null;
		int num_tried = 0;
		while (nearestChargingStation == null && num_tried < 5) {
			for (ChargingStation cs : csGeography.getObjectsWithin(buffer.getEnvelopeInternal(), ChargingStation.class)) {
				double thisDist = this.getDistance(coord, cs.getCoord());
				if ((thisDist < minDist) && cs.capacity() > 0) {
					minDist = thisDist;
					nearestChargingStation = cs;
				}
			}
			num_tried += 1;
			buffer = point.buffer((num_tried + 1) * GlobalVariables.SEARCHING_BUFFER);
		}
		
		if (nearestChargingStation == null) { // Cannot find instant available charger, go the closest one and wait there
			for (ChargingStation cs : csGeography.getObjectsWithin(buffer.getEnvelopeInternal(),
					ChargingStation.class)) {
				double thisDist = this.getDistance(coord, cs.getCoord());
				if ((thisDist < minDist) && (cs.numL2() > 0 || cs.numL3() > 0)) {
					minDist = thisDist;
					nearestChargingStation = cs;
				}
			}
		}
		if (nearestChargingStation == null) {
			ContextCreator.logger.error(
					"CityContext: findNearestChargingStation (Coordinate coord): ERROR: couldn't find a charging station at these coordinates:\n\t"
							+ coord.toString());
		}

		return nearestChargingStation;
	}

	// Returns the closest charging station for bus from the currentLocation
	public ChargingStation findNearestBusChargingStation(Coordinate coord) throws NullPointerException {
		if (coord == null) {
			throw new NullPointerException(
					"CityContext: findNearestChargingStation: ERROR: the input coordinate is null");
		}
		GeometryFactory geomFac = new GeometryFactory();
		Geography<ChargingStation> csGeography = ContextCreator.getChargingStationGeography();
		// Use a buffer for efficiency
		Point point = geomFac.createPoint(coord);
		Geometry buffer = point.buffer(GlobalVariables.SEARCHING_BUFFER);
		double minDist = Double.MAX_VALUE;
		ChargingStation nearestChargingStation = null;
		for (ChargingStation cs : csGeography.getObjectsWithin(buffer.getEnvelopeInternal(), ChargingStation.class)) {
			double thisDist = this.getDistance(coord, cs.getCoord());
			if ((thisDist < minDist)) { // if thisDist < minDist
				minDist = thisDist;
				nearestChargingStation = cs;
			}
		}
		if (nearestChargingStation == null) {
			ContextCreator.logger.error(
					"CityContext: findNearestChargingStation (Coordinate coord): ERROR: couldn't find a charging station at these coordinates:\n\t"
							+ coord.toString());
		}
		return nearestChargingStation;
	}
	
	// Returns the closest road from the currentLocation 
	public Road findRoadAtCoordinates(Coordinate coord) throws NullPointerException {
		if (coord == null) {
			throw new NullPointerException("CityContext: findRoadAtCoordinates: ERROR: the input coordinate is null");
		}
		
		if(this.road_KeyCoord.containsKey(coord)) {
			return this.road_KeyCoord.get(coord);
		}
		else {
			GeometryFactory geomFac = new GeometryFactory();
			Geography<?> roadGeography = ContextCreator.getRoadGeography();
			// Use a buffer for efficiency
			Point point = geomFac.createPoint(coord);
			Geometry buffer = point.buffer(GlobalVariables.SEARCHING_BUFFER);
			double minDist = Double.MAX_VALUE;
			Road nearestRoad = null;
	
			// New code when nearest road was found based on distance from junction
			for (Road road : roadGeography.getObjectsWithin(buffer.getEnvelopeInternal(), Road.class)) {
				double thisDist = this.getDistance(coord, road.getCoord());
				if (thisDist < minDist) { 
					minDist = thisDist;
					nearestRoad = road;
				}
			}
			if (nearestRoad == null) { // for nearRoads
				ContextCreator.logger.error(
						"CityContext: findRoadAtCoordinates (Coordinate coord, boolean toDest): ERROR: couldn't find a road at these coordinates:\n\t"
								+ coord.toString());
			}
			else {
				this.road_KeyCoord.put(coord, nearestRoad);
			}
			return nearestRoad;
		}
	}

	public Road findRoadBetweenJunctionIDs(int junc1, int junc2) {
		if(this.road_KeyJunctionID.containsKey(junc1)) {
			if(this.road_KeyJunctionID.get(junc1).containsKey(junc2)) {
				return this.road_KeyJunctionID.get(junc1).get(junc2);
			}
		}
		ContextCreator.logger.error("CityContext: findRoadBetweenJunctionIDs: Error, couldn't find a road with id: "
				+ junc1 + " to id: " + junc2);
		return null;
	}

	public Road findRoadWithLinkID(int linkID) {
		if(road_KeyLinkID.containsKey(linkID))
			return road_KeyLinkID.get(linkID);
		else
			return null;
	}

	public Zone findZoneWithIntegerID(int integerID) {
		return ContextCreator.getZoneContext().findZoneWithIntegerID(integerID);
	}

	public ChargingStation findChargingStationWithID(int destid) {
		return ContextCreator.getChargingStationContext().findChargingStationWithIntegerID(destid);
	}

	public static double angle(Coordinate p0, Coordinate p1) {
		double dx = p1.x - p0.x;
		double dy = p1.y - p0.y;

		return Math.atan2(dy, dx);
	}

	public static double squaredEuclideanDistance(Coordinate p0, Coordinate p1) {
		return (p0.x - p1.x) * (p0.x - p1.x) + (p0.y - p1.y) * (p0.y - p1.y);
	}

	// Returns the closest zone from the currentLocation that has a bus available
	public Zone findNearestZoneWithBus(Coordinate coord, Integer destID) throws NullPointerException {
		if (coord == null) {
			throw new NullPointerException("CityContext: findNearestZoneWithBus: ERROR: the input coordinate is null");
		}
		// Use a buffer for efficiency
		double minDist = Double.MAX_VALUE;
		Zone nearestZone = null;
		for (Zone z : ContextCreator.getZoneContext().getAllObjects()) {
			if (z.busReachableZone.contains(destID)) {
				double thisDist = this.getDistance(coord, z.getCoord());
				if (thisDist < minDist) {
					minDist = thisDist;
					nearestZone = z;
				}
			}
		}
		return nearestZone;
	}

	public void refreshTravelTime() {
		ContextCreator.logger.info("Update the estimation of travel time...");
		// Reset the travel time and travel distance estimation
		for (Zone z1 : ContextCreator.getZoneContext().getAllObjects()) {
			z1.busTravelTime.clear();
			z1.busTravelDistance.clear();
			z1.nearestZoneWithBus.clear();
		}
		for (List<Integer> route : ContextCreator.busSchedule.busRoute) {
			ContextCreator.logger.info(route);
			// Retrieve stations in order, from hub to other places
			double travel_distance = 0;
			double travel_time = 0;

			for (int shift = 0; shift < route.size(); shift++) {
				if (GlobalVariables.HUB_INDEXES.contains(route.get(shift))) { // is hub
					Zone hub = this.findZoneWithIntegerID(route.get(shift));
					Zone z1 = hub;
					Zone z2;

					for (int i = 1; i < route.size(); i++) {
						int j = shift + i >= route.size() ? shift + i - route.size() : shift + i;
						z2 = this.findZoneWithIntegerID(route.get(j));
						List<Road> path = RouteContext.shortestPathRoute(z1.getCoord(), z2.getCoord());
						if (path != null) {
							for (Road r : path) {
								travel_distance += r.getLength();
								travel_time += r.getTravelTime();
							}
						}
						if (hub.busTravelDistance.containsKey(z2.getIntegerID())) {
							hub.busTravelDistance.put(z2.getIntegerID(),
									Math.min(hub.busTravelDistance.get(z2.getIntegerID()), (float) travel_distance));
							hub.busTravelTime.put(z2.getIntegerID(),
									Math.min(hub.busTravelTime.get(z2.getIntegerID()), (float) travel_time));
						} else {
							hub.busTravelDistance.put(z2.getIntegerID(), (float) travel_distance);
							hub.busTravelTime.put(z2.getIntegerID(), (float) travel_time);
						}
						z1 = z2;
					}
					ContextCreator.logger.debug(hub.busTravelDistance);
					ContextCreator.logger.debug(hub.busTravelTime);
					// Retrieve stations in back order, from other places to hub
					travel_distance = 0;
					travel_time = 0;
					z2 = hub;
					for (int i = route.size() - 1; i > 0; i--) {
						int j = shift + i >= route.size() ? shift + i - route.size() : shift + i;
						z1 = this.findZoneWithIntegerID(route.get(j));
						List<Road> path = RouteContext.shortestPathRoute(z1.getCoord(), z2.getCoord());
						if (path != null) {
							for (Road r : path) {
								travel_distance += r.getLength();
								travel_time += r.getTravelTime();
							}
						}
						if (z1.busTravelDistance.containsKey(hub.getIntegerID())) {
							z1.busTravelDistance.put(hub.getIntegerID(),
									Math.min(z1.busTravelDistance.get(hub.getIntegerID()), (float) travel_distance));
							z1.busTravelTime.put(hub.getIntegerID(),
									Math.min(z1.busTravelTime.get(hub.getIntegerID()), (float) travel_time));
						} else {
							z1.busTravelDistance.put(hub.getIntegerID(), (float) travel_distance);
							z1.busTravelTime.put(hub.getIntegerID(), (float) travel_time);
						}
						z2 = z1;
						ContextCreator.logger.debug(z1.busTravelDistance);
						ContextCreator.logger.debug(z1.busTravelTime);
					}
				}
			}
		}
	}
}
