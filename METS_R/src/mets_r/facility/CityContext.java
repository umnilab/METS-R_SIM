package mets_r.facility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.geotools.referencing.GeodeticCalculator;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;

import mets_r.*;
import mets_r.data.input.SumoXML;
import mets_r.routing.RouteContext;

import repast.simphony.context.DefaultContext;
import repast.simphony.context.space.graph.NetworkFactory;
import repast.simphony.context.space.graph.NetworkFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;

/**
 * Inherent from A-RESCUE
 * 
 * Initialize and maintain facility agents
 **/
public class CityContext extends DefaultContext<Object> {
	private HashMap<RepastEdge<?>, Integer> edgeRoadID_KeyEdge; // Store the TOIDs of edges (Edge as key)
	private HashMap<Integer, RepastEdge<?>> edgeIDEdge_KeyID; // Store the TOIDs of edges (TOID as key)
	private HashMap<Coordinate, Road> coordOrigRoad_KeyCoord; // Cache the closest road
	private HashMap<Coordinate, Road> coordDestRoad_KeyCoord; // Cache the closest road
	private HashMap<Integer, HashMap<Integer, Road>> nodeIDRoad_KeyNodeID;
	private HashMap<String, Integer> origRoadID_RoadID;
	
	private Boolean networkInitialized = false;
          
	public CityContext() {
		super("CityContext"); // Very important otherwise repast complains
		this.edgeRoadID_KeyEdge = new HashMap<RepastEdge<?>, Integer>();
		this.edgeIDEdge_KeyID = new HashMap<Integer, RepastEdge<?>>();
		this.coordOrigRoad_KeyCoord = new HashMap<Coordinate, Road>();
		this.coordDestRoad_KeyCoord = new HashMap<Coordinate, Road>();
		this.nodeIDRoad_KeyNodeID = new HashMap<Integer, HashMap<Integer, Road>>();
		this.origRoadID_RoadID = new HashMap<String, Integer>();
		
		/* Create a Network projection for the road network--->Network Projection */
		NetworkFactory netFac = NetworkFactoryFinder.createNetworkFactory(new HashMap<String, Object>());
		netFac.createNetwork("RoadNetwork", this, true);
	}

	public void createSubContexts() {
		this.addSubContext(new JunctionContext());
		this.addSubContext(new NodeContext());
		this.addSubContext(new SignalContext());
		this.addSubContext(new RoadContext());
		this.addSubContext(new LaneContext());
		this.addSubContext(new ZoneContext());
		this.addSubContext(new ChargingStationContext());
	}
	
	// Calculate the length of each lane based on their geometries
	private void initializeLaneDistance() {
		for (Lane lane : ContextCreator.getLaneContext().getAll()) {
			ArrayList<Coordinate> coords = lane.getCoords();
			double distance = 0;
			for (int i = 0; i < coords.size() - 1; i++) {
				distance += getDistance(coords.get(i), coords.get(i+1));
			}
			lane.setLength(distance);
		}
	}
	
	/**
     * Catmull-Rom interpolation between four points.
     * Given points p0, p1, p2, p3 and parameter t in [0, 1], returns the interpolated point.
     */
    public ArrayList<Coordinate> catmullRomInterpolate(Coordinate p0, Coordinate p1, Coordinate p2,  Coordinate p3) {
    	ArrayList<Coordinate> coords = new ArrayList<Coordinate>();
    	for(double i = 0; i < 6; i++) {
    		double t = i/5.0;
	        double t2 = t * t;
	        double t3 = t2 * t;
	        
	        double x = 0.5 * ((2 * p1.x) +
	                          (-p0.x + p2.x) * t +
	                          (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 +
	                          (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
	        double y = 0.5 * ((2 * p1.y) +
	                          (-p0.y + p2.y) * t +
	                          (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 +
	                          (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
	        coords.add(new Coordinate(x, y));
    	}
        return coords;
    }
	
	// Calculate the turning coords and length based on two connected lanes
	private void initializeLaneTurningCurves(Lane lane1, Lane lane2) {
		Coordinate p0 = lane1.getCoords().get(lane1.getCoords().size()-2);
		Coordinate p1 = lane1.getCoords().get(lane1.getCoords().size()-1);
		Coordinate p2 = lane2.getCoords().get(0);
		Coordinate p3 = lane2.getCoords().get(1);
		ArrayList<Coordinate> coords = catmullRomInterpolate(p0, p1, p2, p3);
		double distance = 0;
		for (int i = 0; i < coords.size() - 1; i++) {
			distance += getDistance(coords.get(i), coords.get(i+1));
		}
		
		lane1.setTurningCoords(lane2.getID(), coords);
		lane1.setTurningDist(lane2.getID(), distance);
	}
	
	// Set the neighboring links/zones
	public void setNeighboringGraph() {
		ContextCreator.logger.info("Building neighboring graph.");
		Geography<Road> roadGeography = ContextCreator.getRoadGeography();
		Geography<Zone> zoneGeography = ContextCreator.getZoneGeography();
		GeometryFactory geomFac = new GeometryFactory();
		int minNeighbors = Math.min(ContextCreator.getZoneContext().size() - 1, 8);
		for (Zone z1 : ContextCreator.getZoneContext().getAll()) { 
			// Set up neighboring zones
			Point point = geomFac.createPoint(z1.getCoord());
			double searchBuffer = GlobalVariables.SEARCHING_BUFFER; // initial threshold as 5 km
			
			while (z1.getNeighboringZoneSize() < minNeighbors) {
				Geometry buffer = point.buffer(searchBuffer);
				for (Zone z2 : zoneGeography.getObjectsWithin(buffer.getEnvelopeInternal(), Zone.class)) {
					if (z1.getID() != z2.getID()) {
						z1.addNeighboringZone(z2.getID());
					}
				}
				searchBuffer *= 2;
			}
			// Add the transportation hubs if not shown in the neighboring list
			for(int hubID: ContextCreator.getZoneContext().HUB_INDEXES) {
				z1.addNeighboringZone(hubID);
			}
			// Set up neighboring roads
			searchBuffer = GlobalVariables.SEARCHING_BUFFER;
			while(z1.getClosestRoad(false) == null || z1.getClosestRoad(true) ==null) {
				double dist = Double.MAX_VALUE;
				double dist2 = Double.MAX_VALUE;
				Geometry buffer = point.buffer(searchBuffer);
				for (Road r : roadGeography.getObjectsWithin(buffer.getEnvelopeInternal(), Road.class)) {
					if(r.canBeOrigin()) {
						dist = this.getDistance(z1.getCoord(), r.getStartCoord());
						
						if((dist < r.getDistToZone(false))) {
							r.setNeighboringZone(z1.getID(),false);
							r.setDistToZone(dist, false);
						}
						if(dist < z1.getDistToRoad(false)) {
							z1.setClosestRoad(r.getID(), false);
							z1.setDistToRoad(dist, false);
						}
					}
					if(r.canBeDest()) {
						dist2 = this.getDistance(z1.getCoord(), r.getEndCoord());
						
						if(dist2 < r.getDistToZone(true)) {
							r.setNeighboringZone(z1.getID(),true);
							r.setDistToZone(dist2, true);
						}
						if(dist2 < z1.getDistToRoad(true)) {
							z1.setClosestRoad(r.getID(), true);
							z1.setDistToRoad(dist, true);
						}
					}
				}
				searchBuffer = searchBuffer * 2;
			}
			z1.addNeighboringLink(z1.getClosestRoad(false), false);
			z1.addNeighboringLink(z1.getClosestRoad(true), true);
		}
		
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) { 
			// Set up neighboring roads
			double searchBuffer = GlobalVariables.SEARCHING_BUFFER;
			Point point = geomFac.createPoint(cs.getCoord());
			while(cs.getClosestRoad(false) == null || cs.getClosestRoad(true) ==null) {
				double dist = Double.MAX_VALUE;
				double dist2 = Double.MAX_VALUE;
				Geometry buffer = point.buffer(searchBuffer);
				for (Road r : roadGeography.getObjectsWithin(buffer.getEnvelopeInternal(), Road.class)) {
					if(r.canBeOrigin()) {
						dist = this.getDistance(cs.getCoord(), r.getStartCoord());
						if(dist < cs.getDistToRoad(false)) {
							cs.setClosestRoad(r.getID(), false);
							cs.setDistToRoad(dist, false);
						}
					}
					if(r.canBeDest()) {
						dist2 = this.getDistance(cs.getCoord(), r.getEndCoord());
						if(dist2 < cs.getDistToRoad(true)) {
							cs.setClosestRoad(r.getID(), true);
							cs.setDistToRoad(dist, true);
						}
					}
				}
				searchBuffer = searchBuffer * 2;
			}
		}
		
		// Ensure every road has neighboring zone
		for (Road r: roadGeography.getAllObjects()) {
			Point point1 = geomFac.createPoint(r.getStartCoord());
			Point point2 = geomFac.createPoint(r.getEndCoord());
			double searchBuffer = GlobalVariables.SEARCHING_BUFFER;
			while(((r.getNeighboringZone(false) < 0) && r.canBeOrigin()) || ((r.getNeighboringZone(true) < 0) && r.canBeDest())) {
				double dist = Double.MAX_VALUE;
				double dist2 = Double.MAX_VALUE;
				Geometry buffer1 = point1.buffer(searchBuffer);
				Geometry buffer2 = point2.buffer(searchBuffer);
				for (Zone z : zoneGeography.getObjectsWithin(buffer1.getEnvelopeInternal(), Zone.class)) {
					dist = this.getDistance(z.getCoord(), r.getStartCoord());
					if(dist < r.getDistToZone(false)) {
						r.setNeighboringZone(z.getID(), false);
						r.setDistToZone(dist, false);
					}
				}
				for (Zone z : zoneGeography.getObjectsWithin(buffer2.getEnvelopeInternal(), Zone.class)) {
					dist2 = this.getDistance(z.getCoord(), r.getEndCoord());
					if(dist2 < r.getDistToZone(true)) {
						r.setNeighboringZone(z.getID(), true);
						r.setDistToZone(dist, true);
					}
				}
				searchBuffer = searchBuffer * 2;
			}
			this.coordOrigRoad_KeyCoord.put(r.getStartCoord(), r);
			this.coordDestRoad_KeyCoord.put(r.getEndCoord(), r);
			if(r.canBeOrigin())
				ContextCreator.getZoneContext().get(r.getNeighboringZone(false)).addNeighboringLink(r.getID(), false);
			if(r.canBeDest())
				ContextCreator.getZoneContext().get(r.getNeighboringZone(true)).addNeighboringLink(r.getID(), true);
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
		Geography<Road> roadGeography = ContextCreator.getRoadGeography();
		Geography<Junction> junctionGeography = ContextCreator.getJunctionGeography();
		JunctionContext junctionContext = ContextCreator.getJunctionContext();
		Network<Node> roadNetwork = ContextCreator.getRoadNetwork();
		GeometryFactory geomFac = new GeometryFactory();
		
		// Create a repast network for routing
		if (GlobalVariables.NETWORK_FILE.length() == 0) {
			// Case 1: junction is not provided so we infer it from roads and lanes, shp case
			for (Road road : ContextCreator.getRoadContext().getAll()) {
				Geometry roadGeom = roadGeography.getGeometry(road);
				Coordinate c1 = roadGeom.getCoordinates()[0];
				Coordinate c2 = roadGeom.getCoordinates()[roadGeom.getNumPoints() - 1];
				// Create Junctions from road coordinates and add them to the
				// JunctionGeography (if they haven't been created already)
				Junction junc1, junc2;
				if (!junctionContext.contains(road.getUpStreamJunction())) {
					junc1 = new Junction(road.getUpStreamJunction());
					junc1.setCoord(c1);
					junctionContext.put(road.getUpStreamJunction(), junc1);
					Point p1 = geomFac.createPoint(c1);
					junctionGeography.move(junc1, p1);
				} else
					junc1 = junctionContext.get(road.getUpStreamJunction());

				if (!junctionContext.contains(road.getDownStreamJunction())) {
					junc2 = new Junction(road.getDownStreamJunction());
					junc2.setCoord(c2);
					junctionContext.put(road.getDownStreamJunction(), junc2);
					Point p2 = geomFac.createPoint(c2);
					junctionGeography.move(junc2, p2);
				} else
					junc2 = junctionContext.get(road.getDownStreamJunction());
				
				// Tell the road object who its junctions are
				road.setUpStreamJunction(junc1.getID());
				road.setDownStreamJunction(junc2.getID());
				
				// Tell the junctions about their road
				junc1.addDownStreamRoad(road.getID());
				junc2.addUpStreamRoad(road.getID());
				
				Node node1, node2;
				node1 = new Node(100*road.getID()+1);
				node2 = new Node(100*road.getID()+2);
				ContextCreator.getNodeContext().put(node1.getID(), node1);
				ContextCreator.getNodeContext().put(node2.getID(), node2);
				// Tell the node about their junction
				node1.setJunction(junc1);
				node2.setJunction(junc2);
				
				// Tell the node about their road
				node1.setRoad(road);
				node2.setRoad(road);
				
				road.setUpStreamNode(node1);
				road.setDownStreamNode(node2);
			}
		} 
		else {
			// Case 2: junction is provided, SumoXML case
			SumoXML sxml = SumoXML.getData(GlobalVariables.NETWORK_FILE);
			for (Junction j : sxml.getJunction().values()) {
				junctionContext.put(j.getID(), j);
			}
			for (Road r: ContextCreator.getRoadContext().getAll()) {
				Node node1, node2;
				node1 = new Node(10*r.getID()+(r.getID()>0?1:-1));
				node2 = new Node(10*r.getID()+(r.getID()>0?2:-2));
				ContextCreator.getNodeContext().put(node1.getID(), node1);
				ContextCreator.getNodeContext().put(node2.getID(), node2);
				// Tell the node about their road
				node1.setRoad(r);
				node2.setRoad(r);
				// Tell road about their node
				r.setUpStreamNode(node1);
				r.setDownStreamNode(node2);
			}
			for (int jid: sxml.getRoadConnection().keySet()) {
				Junction j = junctionContext.get(jid);
				for (List<Integer> rc: sxml.getRoadConnection(jid)) {
					Road r1 = ContextCreator.getRoadContext().get(rc.get(0));
					Road r2 = ContextCreator.getRoadContext().get(rc.get(1));
					// Tell the road about their junction
					r1.setDownStreamJunction(jid);
					r2.setUpStreamJunction(jid);
					// Tell the junction about their road
					j.addUpStreamRoad(r1.getID());
					j.addDownStreamRoad(r2.getID());
					// Tell the node about their junction
					Node node1 = r1.getDownStreamNode();
					Node node2 = r2.getUpStreamNode();
					node1.setJunction(j);
					node2.setJunction(j); 
					// Set the signal
					if(j.getControlType() == Junction.StaticSignal) {
						if(sxml.getSignal().containsKey(r1.getID())) {
							if(sxml.getSignal().get(r1.getID()).containsKey(r2.getID())) {
								Signal s = sxml.getSignal().get(r1.getID()).get(r2.getID());
								// Add the signal to SignalContext
								ContextCreator.getSignalContext().put(s.getID(), s);
								j.setSignal(r1.getID(), r2.getID(), s);
								j.setDelay(r1.getID(), r2.getID(), s.getDelay());
							}
						}
						
					}
				}
		    }
			
		}
		
		this.initializeLaneDistance();
			
		for(Road road: ContextCreator.getRoadContext().getAll()) {
			Node node1 = road.getUpStreamNode();
			Node node2 = road.getDownStreamNode();
			RepastEdge<Node> edge = new RepastEdge<Node>(node1, node2, true,
					road.getLength() / road.getSpeedLimit());
			
			if (!roadNetwork.containsEdge(edge)) {
				roadNetwork.addEdge(edge);
				this.edgeRoadID_KeyEdge.put(edge, road.getID());
				this.edgeIDEdge_KeyID.put(road.getID(), edge);
				if (this.nodeIDRoad_KeyNodeID.containsKey(node1.getID())) {
					this.nodeIDRoad_KeyNodeID.get(node1.getID()).put(node2.getID(), road);
				} else {
					HashMap<Integer, Road> tmp = new HashMap<Integer, Road>();
					tmp.put(node2.getID(), road);
					this.nodeIDRoad_KeyNodeID.put(node1.getID(), tmp);
				}
			} else {
				ContextCreator.logger.error("buildRoadNetwork1: this edge that has just been created "
						+ "already exists in the RoadNetwork!");
			}
		}
		ContextCreator.logger.info("Junction initialized!");
		
		// Assign the lanes to each road
		for (Lane lane : ContextCreator.getLaneContext().getAll()) {
			Road road = ContextCreator.getRoadContext().get(lane.getRoad());
			road.addLane(lane);
		}
		for (Road r : ContextCreator.getRoadContext().getAll()) {
			r.sortLanes();
			origRoadID_RoadID.put(r.getOrigID(), r.getID());
		}
		
		// Complete the lane connection
		// 1. handle the case when road is connected but there is no lane connection (U turn)
		// 2. handle upStreamLanes and calculate the turning curves
		for (Road r1: ContextCreator.getRoadContext().getAll()) {
			for (int r2ID: r1.getDownStreamRoads()) {
				Road r2 = ContextCreator.getRoadContext().get(r2ID);
				boolean flag = true;
				for (Lane lane1: r1.getLanes()) {
					for (int lane2ID: lane1.getDownStreamLanes()) {
						Lane lane2 =  ContextCreator.getLaneContext().get(lane2ID);
						if(lane2.getRoad() == r2.getID()) {
							flag = false;
							break;
						}
					}
					if(!flag) break;
				}
				if(flag) {
					r1.getLane(0).addDownStreamLane(r2.getLane(0).getID());
				}
			}
		}
		for (Lane lane1 : ContextCreator.getLaneContext().getAll()) {
			for (int lane2ID: lane1.getDownStreamLanes()) {
				Lane lane2 = ContextCreator.getLaneContext().get(lane2ID);
				if(!lane2.getUpStreamLanes().contains(lane1.getID()))
					lane2.addUpStreamLane(lane1.getID());
				this.initializeLaneTurningCurves(lane1, lane2);
			}
		}
		
		if(GlobalVariables.NETWORK_FILE.contains("xml")) {
			// SUMO XML
			for (Junction j: junctionContext.getAll()) {
				if(j.getControlType()!=Junction.StaticSignal) {
					// Deduce the delay time for the stop sign and yield sign
					ArrayList<Integer> roadTypes = new ArrayList<Integer>();
					for(int r: j.getUpStreamRoads()) 
						roadTypes.add(ContextCreator.getRoadContext().get(r).getRoadType());
					for(int r: j.getDownStreamRoads()) 
						roadTypes.add(ContextCreator.getRoadContext().get(r).getRoadType());
					Collections.sort(roadTypes);
					if (roadTypes.size() >= 2) {
						if (j.getControlType() == Junction.Yield) {
							for(int r1: j.getUpStreamRoads()) {
								for (int r2: ContextCreator.getRoadContext().get(r1).getDownStreamRoads()) {
									if(ContextCreator.getRoadContext().get(r1).getRoadType() == Road.Highway) {
										j.setDelay(r1, r2, 0);
									}
									else {
										j.setDelay(r1, r2, (int) Math.ceil(3/GlobalVariables.SIMULATION_STEP_SIZE));
									}
								}
							}
						} else if (j.getControlType() == Junction.StopSign) {
							for(int r1: j.getUpStreamRoads()) {
								for (int r2: ContextCreator.getRoadContext().get(r1).getDownStreamRoads()) {
									j.setDelay(r1, r2, (int) Math.ceil(3/GlobalVariables.SIMULATION_STEP_SIZE));
								}
							}
						}
						else { // No control
							for(int r1: j.getUpStreamRoads()) {
								for (int r2: ContextCreator.getRoadContext().get(r1).getDownStreamRoads()) {
									j.setDelay(r1, r2, 0);
								}
							}
						}
					}
				}
			}			
		}
		else {
			for (Junction j: junctionContext.getAll()) {
				// Deduce the traffic light from road types, used when no traffic light info provided
				ArrayList<Integer> roadTypes = new ArrayList<Integer>();
				for(int r: j.getUpStreamRoads()) 
					roadTypes.add(ContextCreator.getRoadContext().get(r).getRoadType());
				for(int r: j.getDownStreamRoads()) 
					roadTypes.add(ContextCreator.getRoadContext().get(r).getRoadType());
				Collections.sort(roadTypes);
				
				// Establish control & signal & delay (delay for travel time estimation)
				if (roadTypes.size() >= 2) {
					if ((roadTypes.get(0) == Road.Street)
							&& (roadTypes.get(j.getUpStreamRoads().size() - 1) <= Road.Highway)) {
						if (j.getUpStreamRoads().size() > 2) {
							j.setControlType(Junction.StaticSignal);
							int signalIndex=0;
							int signalNumber=j.getUpStreamRoads().size();
							for(int r1: j.getUpStreamRoads()) {
								for (int r2: ContextCreator.getRoadContext().get(r1).getDownStreamRoads()) {
									Signal signal = new Signal(ContextCreator.generateAgentID(), 
											new ArrayList<Integer>(Arrays.asList(27,3,30*(signalNumber-1))), 
													signalIndex*30);
									ContextCreator.getSignalContext().put(signal.getID(), signal);
									j.setSignal(r1, r2, signal);
									j.setDelay(r1, r2, signal.getDelay());
								}
								signalIndex++;
							}
						}
						else {
							j.setControlType(Junction.Yield);
							for(int r1: j.getUpStreamRoads()) {
								for (int r2: ContextCreator.getRoadContext().get(r1).getDownStreamRoads()) {
									if(ContextCreator.getRoadContext().get(r1).getRoadType() == Road.Highway) {
										j.setDelay(r1, r2, 0);
									}
									else {
										j.setDelay(r1, r2, (int) Math.ceil(3/GlobalVariables.SIMULATION_STEP_SIZE));
									}
								}
							}
						}
					} else if ((roadTypes.get(0) == Road.Street)
							&& (roadTypes.get(j.getUpStreamRoads().size() - 1) == Road.Driveway)) {
						j.setControlType(Junction.StopSign);
						for(int r1: j.getUpStreamRoads()) {
							for (int r2: ContextCreator.getRoadContext().get(r1).getDownStreamRoads()) {
								j.setDelay(r1, r2, (int) Math.ceil(3/GlobalVariables.SIMULATION_STEP_SIZE));
							}
						}
					}
					else {
						j.setControlType(Junction.NoControl);
						for(int r1: j.getUpStreamRoads()) {
							for (int r2: ContextCreator.getRoadContext().get(r1).getDownStreamRoads()) {
								j.setDelay(r1, r2, 0);
							}
						}
					}
				}
				
			}
		}
		
		ContextCreator.logger.info("Signal initialized!");
		
		// Establish edges
		for (Junction j: junctionContext.getAll()) {
			for(int r1: j.getDelay().keySet()) {
				for(int r2: j.getDelay().get(r1).keySet()) {
					Node node1 = ContextCreator.getRoadContext().get(r1).getDownStreamNode();
					Node node2 = ContextCreator.getRoadContext().get(r2).getUpStreamNode();
					RepastEdge<Node> edge = new RepastEdge<Node>(node1, node2, true,j.getDelay(r1, r2));
					if (!roadNetwork.containsEdge(edge)) {
						roadNetwork.addEdge(edge);
						this.edgeRoadID_KeyEdge.put(edge, -1); // negative value to signal this is not a road
					} else {
						ContextCreator.logger
								.error("buildRoadNetwork2: this edge that has just been created "
										+ "already exists in the RoadNetwork!");
					}
				}
			}
		}
		
		ContextCreator.logger.info("City initialized!");
	}
	

	// Update node based routing
	public void modifyRoadNetwork() {
		// At beginning, initialize route object
		if (!this.networkInitialized) {
			try {
				RouteContext.createRoute();
				networkInitialized = true;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
				
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			if(road.updateTravelTimeEstimation()) {
				Node node1 = road.getUpStreamNode();
				Node node2 = road.getDownStreamNode();
				ContextCreator.getRoadNetwork().getEdge(node1, node2).setWeight(road.getTravelTime());
				RouteContext.setEdgeWeight(node1, node2, road.getTravelTime());
			}
		}
	}

	public int getRoadIDFromEdge(RepastEdge<Node> edge) {
		int id = this.edgeRoadID_KeyEdge.get(edge);
		return id;
	}

	public RepastEdge<?> getEdgeFromIDNum(int id) {
		RepastEdge<?> edge = this.edgeIDEdge_KeyID.get(id);
		return edge;
	}

	// Returns the closest charging station which has available chargers with specified charger type from the current location
	public ChargingStation findNearestChargingStation(Coordinate coord, int chargerType) throws NullPointerException {
		if (coord == null) {
			throw new NullPointerException(
					"CityContext: findNearestChargingStation: ERROR: the input coordinate is null");
		}
		
		if(ContextCreator.getChargingStationContext().numCharger(chargerType) == 0){ // No such charger
			switch(chargerType) {
			  case ChargingStation.L2:
				  if(ContextCreator.getChargingStationContext().numCharger(ChargingStation.L3) > 0){
					  chargerType = ChargingStation.L3;
					  break;
				  }
			  case ChargingStation.L3:
				  if(ContextCreator.getChargingStationContext().numCharger(ChargingStation.L2) > 0){
					  chargerType = ChargingStation.L2;
					  break;
				  }
			  default:
				  ContextCreator.logger.error("No charging station with type " + chargerType +" (0-L2, 1-DCFC, 2-BUS)");
				  return null;
			}
		}

		GeometryFactory geomFac = new GeometryFactory();
		Geography<ChargingStation> csGeography = ContextCreator.getChargingStationGeography();
		// Use a buffer for efficiency
		Point point = geomFac.createPoint(coord);
		double searchBuffer = GlobalVariables.SEARCHING_BUFFER;
		Geometry buffer = point.buffer(searchBuffer);
		double minDist = Double.MAX_VALUE;
		ChargingStation nearestChargingStation = null;
		int num_tried = 0;
		while (nearestChargingStation == null && num_tried < 5) {
			for (ChargingStation cs : csGeography.getObjectsWithin(buffer.getEnvelopeInternal(), ChargingStation.class)) {
				boolean hasCharger = cs.numCharger(chargerType) > 0;
				if (hasCharger && (cs.capacity() > 0)) {
					double thisDist = this.getDistance(coord, cs.getCoord());
					if(thisDist < minDist) {
						minDist = thisDist;
						nearestChargingStation = cs;
					}
				}
			}
			num_tried += 1;
			searchBuffer *= 2;
			buffer = point.buffer(searchBuffer);
		}
		
		if (nearestChargingStation == null) { // Cannot find instant available charger, go the closest one and wait there
			for (ChargingStation cs : csGeography.getObjectsWithin(buffer.getEnvelopeInternal(),
					ChargingStation.class)) {
				boolean hasCharger = cs.numCharger(chargerType) > 0;
				if (hasCharger) {
					double thisDist = this.getDistance(coord, cs.getCoord());
					if(thisDist < minDist) {
						minDist = thisDist;
						nearestChargingStation = cs;
					}
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
	
	// Returns the closest road from the currentLocation 
	public Road findRoadAtCoordinates(Coordinate coord, boolean toDest) throws NullPointerException {
		if (coord == null) {
			throw new NullPointerException("CityContext: findRoadAtCoordinates: ERROR: the input coordinate is null");
		}
		
		if(toDest) {
			if(this.coordDestRoad_KeyCoord.containsKey(coord)) {
				return this.coordDestRoad_KeyCoord.get(coord);
			}
			else {
				GeometryFactory geomFac = new GeometryFactory();
				Geography<?> roadGeography = ContextCreator.getRoadGeography();
				// Use a buffer for efficiency
				Point point = geomFac.createPoint(coord);
				Geometry buffer = point.buffer(GlobalVariables.SEARCHING_BUFFER);
				double minDist = Double.MAX_VALUE;
				Road nearestRoad = null;
		
				// Nearest road was found based on distance from junction
				int num_tried = 0;
				while (nearestRoad == null && num_tried < 5) {
					for (Road road : roadGeography.getObjectsWithin(buffer.getEnvelopeInternal(), Road.class)) {
						double thisDist = this.getDistance(coord, road.getEndCoord());
						if (thisDist < minDist) { 
							minDist = thisDist;
							nearestRoad = road;
						}
					}
					num_tried += 1;
					buffer = point.buffer((num_tried + 1) * GlobalVariables.SEARCHING_BUFFER);
				}
				if (nearestRoad == null) {
					ContextCreator.logger.error(
							"CityContext: findRoadAtCoordinates (Coordinate coord, boolean " + toDest+ "): ERROR: couldn't find a road at these coordinates"
									+ coord.toString());
				}
				else {
					this.coordDestRoad_KeyCoord.put(coord, nearestRoad);
				}
				return nearestRoad;
			}
		}
		else {
			if(this.coordOrigRoad_KeyCoord.containsKey(coord)) {
				return this.coordOrigRoad_KeyCoord.get(coord);
			}
			else {
				GeometryFactory geomFac = new GeometryFactory();
				Geography<?> roadGeography = ContextCreator.getRoadGeography();
				// Use a buffer for efficiency
				Point point = geomFac.createPoint(coord);
				Geometry buffer = point.buffer(GlobalVariables.SEARCHING_BUFFER);
				double minDist = Double.MAX_VALUE;
				Road nearestRoad = null;
		
				// Nearest road was found based on distance from junction
				int num_tried = 0;
				while (nearestRoad == null && num_tried < 5) {
					for (Road road : roadGeography.getObjectsWithin(buffer.getEnvelopeInternal(), Road.class)) {
						double thisDist = this.getDistance(coord, road.getStartCoord());
						if (thisDist < minDist) { 
							minDist = thisDist;
							nearestRoad = road;
						}
					}
					num_tried += 1;
					buffer = point.buffer((num_tried + 1) * GlobalVariables.SEARCHING_BUFFER);
				}
				
				if (nearestRoad == null) {
					ContextCreator.logger.error(
							"CityContext: findRoadAtCoordinates (Coordinate coord, boolean " + toDest+ "): ERROR: couldn't find a road at these coordinates:\n\t"
									+ coord.toString());
				}
				else {
					this.coordDestRoad_KeyCoord.put(coord, nearestRoad);
				}
				return nearestRoad;
			}
		}
	}

	public Road findRoadBetweenNodeIDs(int node1, int node2) {
		if(this.nodeIDRoad_KeyNodeID.containsKey(node1)) {
			if(this.nodeIDRoad_KeyNodeID.get(node1).containsKey(node2)) {
				return this.nodeIDRoad_KeyNodeID.get(node1).get(node2);
			}
		}
		return null;
	}
	
	public Road findRoadWithOrigID(String origID) {
		if(this.origRoadID_RoadID.containsKey(origID)) {
			return ContextCreator.getRoadContext().get(this.origRoadID_RoadID.get(origID));
		}
		return null;
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
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
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
		for (Zone z1 : ContextCreator.getZoneContext().getAll()) {
			z1.busTravelTime.clear();
			z1.busTravelDistance.clear();
			z1.nearestZoneWithBus.clear();
		}
		for (List<Integer> route : ContextCreator.bus_schedule.getBusSchedule()) {
			// Retrieve stations in order, from hub to other places
			double travel_distance = 0;
			double travel_time = 0;

			for (int shift = 0; shift < route.size(); shift++) {
				if ( ContextCreator.getZoneContext().HUB_INDEXES.contains(route.get(shift))) { // is hub
					Zone hub = ContextCreator.getZoneContext().get(route.get(shift));
					Zone z1 = hub;
					Zone z2;

					for (int i = 1; i < route.size(); i++) {
						int j = shift + i >= route.size() ? shift + i - route.size() : shift + i;
						z2 = ContextCreator.getZoneContext().get(route.get(j));
						List<Road> path = RouteContext.shortestPathRoute(z1.getCoord(), z2.getCoord(), null);
						if (path != null) {
							for (Road r : path) {
								travel_distance += r.getLength();
								travel_time += r.getTravelTime();
							}
						}
						if (hub.busTravelDistance.containsKey(z2.getID())) {
							hub.busTravelDistance.put(z2.getID(),
									Math.min(hub.busTravelDistance.get(z2.getID()), (float) travel_distance));
							hub.busTravelTime.put(z2.getID(),
									Math.min(hub.busTravelTime.get(z2.getID()), (float) travel_time));
						} else {
							hub.busTravelDistance.put(z2.getID(), (float) travel_distance);
							hub.busTravelTime.put(z2.getID(), (float) travel_time);
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
						z1 = ContextCreator.getZoneContext().get(route.get(j));
						List<Road> path = RouteContext.shortestPathRoute(z1.getCoord(), z2.getCoord(), null);
						if (path != null) {
							for (Road r : path) {
								travel_distance += r.getLength();
								travel_time += r.getTravelTime();
							}
						}
						if (z1.busTravelDistance.containsKey(hub.getID())) {
							z1.busTravelDistance.put(hub.getID(),
									Math.min(z1.busTravelDistance.get(hub.getID()), (float) travel_distance));
							z1.busTravelTime.put(hub.getID(),
									Math.min(z1.busTravelTime.get(hub.getID()), (float) travel_time));
						} else {
							z1.busTravelDistance.put(hub.getID(), (float) travel_distance);
							z1.busTravelTime.put(hub.getID(), (float) travel_time);
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
