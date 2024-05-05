package mets_r.data.input;

import org.xml.sax.helpers.DefaultHandler;

import mets_r.ContextCreator;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.facility.Signal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.ReferencingFactoryFinder;
import org.geotools.referencing.factory.ReferencingFactoryContainer;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.crs.ProjectedCRS;
import org.opengis.referencing.cs.CartesianCS;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.MathTransformFactory;
import org.opengis.referencing.operation.TransformException;
import org.w3c.dom.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import com.vividsolutions.jts.geom.Coordinate;

/**
 * SUMO xml loader
 * 
 * @author Zengxiang Lei
 *
 */


public class SumoXML {
	public double x_offs = 0;
	public double y_offs = 0;
	public ArrayList<Double> boundary;
	public String xml_file = "";
	public Document xml_doc;
	
	public SumoXMLHandler handler;
	public MathTransform transform;
	
	public static SumoXML data = null;
	
	public SumoXML(String xml_file) {
		
		this.xml_file = xml_file;
		
		SAXParserFactory factory = SAXParserFactory.newInstance();

		try {
			// https://rules.sonarsource.com/java/RSPEC-2755
			// prevent XXE, completely disable DOCTYPE declaration:
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

			SAXParser saxParser = factory.newSAXParser();

			handler = new SumoXMLHandler();

			saxParser.parse(this.xml_file, handler);
		} catch (ParserConfigurationException | SAXException | IOException e) {
			e.printStackTrace();
		}
		
	}
	
	public static SumoXML getData(String xml_file) {
		if(data == null) {
			data = new SumoXML(xml_file);
		}
		return data;
	}
	
	public HashMap<Integer, Road> getRoad(){
		return this.handler.getRoad();
	}
	
	public HashMap<Integer, Lane> getLane(){
		return this.handler.getLane();
	}
	
	public HashMap<Integer, HashMap<Integer, Signal>> getSignal(){
		return this.handler.getSignal();
	}
	
	public HashMap<Integer, Junction> getJunction(){
		return this.handler.getJunction();
	}
	
	public HashMap<Integer, List<List<Integer>>>  getRoadConnection(){
		return this.handler.getRoadConnection();
	}
	
	public List<List<Integer>>  getRoadConnection(int junction_id) {
		return this.handler.getRoadConnection(junction_id);
	}
	
	private class SumoXMLHandler extends DefaultHandler{
		double x_offs = 0;
		double y_offs = 0;
		ArrayList<Double> boundary = new ArrayList<Double>();
		
		HashMap<Integer, Road> roads;
		HashMap<Integer,Junction> junctions;
		HashMap<Integer,Lane> lanes;
		HashMap<Integer, HashMap<Integer, Signal>> signals;
		
		
		HashMap<Integer, List<List<Integer>>> roadConnections; // road connection within junctions
		HashMap<Integer, List<List<Integer>>> laneConnections; // lane connection within junctions
		
		HashMap<Integer, List<Integer>> roadLane;
		ArrayList<String> tmpPhaseState;
		ArrayList<Integer> tmpPhaseTime;
		HashMap<String, List<Signal>> signalIDMap; 
		HashMap<String, Integer> roadIDMap; //SUMO use string as the road ID, need to translate it to integer
		HashMap<String, Integer> laneIDMap;
		HashMap<String, Integer> junctionIDMap;
		
		HashMap<String, String> incLaneJunctionMap; // which lane ends (incoming) to which junction
		HashMap<String, String> intLaneJunctionMap; // which lane starts (initializing) from which junction
		HashMap<String, String> laneRoadMap;
		
		// Handle internal lanes, since METS-R SIM does not consider inner intersection movement
		// we need to translate the internal connection back into the lane connection
		HashMap<String, Boolean> isInternalRoadMap;
		HashMap<String, Boolean> isInternalLaneMap;
		
		HashMap<String, String> internalFromLaneConnections;  
		HashMap<String, String> internalToLaneConnections; 
		
		Road currentRoad;
		Junction currentJunction;
		Lane currentLane;
		String currentSignal; // only use the id of the tlLogic
		String currentRoadID; // for building the land-road map
		
		double currentMaxSpeed = 0;
		String currentRoadType = null;
		
		boolean inRoad = false;
		boolean inInternalRoad = false;
		boolean inSignal = false;
		
		ArrayList<Coordinate> coords;
		double startX = 0;
		double startY = 0;
		double endX = 0;
		double endY = 0;
		int nLane = 0;
		
		int roadNum = 0;
		int junctionNum = 0;
		int signalNum = 0;
		
		public HashMap<Integer,Road> getRoad() {return roads;};
		public HashMap<Integer,Junction> getJunction() {return junctions;};
		public HashMap<Integer,Lane> getLane() {return lanes;};
		public HashMap<Integer, HashMap<Integer, Signal>> getSignal() {return signals;}
		public HashMap<Integer, List<List<Integer>>>  getRoadConnection() {return roadConnections;}
		public List<List<Integer>>  getRoadConnection(int junction_id) {return roadConnections.get(junction_id);}
		
		public int generateLaneID(int roadID, String strLaneID) {
			//translate string lane ID to integer one
			int laneID = Integer.parseInt(strLaneID.split("_")[strLaneID.split("_").length-1]);
			laneID += roadID * 100;
			laneIDMap.put(strLaneID, laneID);
			return laneID;
		}
		
		public int generateRoadID(String strRoadID) {
			//translate string road ID to integer one
			roadNum += 1;
			int roadID = roadNum;
			roadIDMap.put(strRoadID, roadID);
			return roadID;
		}
		
		public int generateJunctionID(String strJunctionID) {
			//translate string road ID to integer one
			junctionNum += 1;
			int junctionID = junctionNum;
			junctionIDMap.put(strJunctionID, junctionID);
			return junctionID;
		}
		
		public int deduceJunctionType(String junctionType) {
			switch(junctionType) {
				case "traffic_light":
					return Junction.StaticSignal;
				case "rail_crossing":
					return Junction.StopSign;
				case "priority":
					return Junction.Yield;
				default:
					return Junction.NoControl;
			}
		}
		
		public int deduceRoadType(String roadType, double speedLimit) {
			int res = Road.Street;
			
			
			return res;
		}
		
		@Override
		public void startDocument() {
			roads = new HashMap<Integer,Road>();
			junctions = new HashMap<Integer,Junction>();
			lanes = new HashMap<Integer,Lane>();
			
			roadConnections = new HashMap<Integer, List<List<Integer>>>();
			laneConnections = new HashMap<Integer, List<List<Integer>>>();
			
			internalFromLaneConnections = new HashMap<String, String>();
			internalToLaneConnections = new HashMap<String, String>();
			
			roadLane = new HashMap<Integer, List<Integer>>();
			signals = new HashMap<Integer, HashMap<Integer, Signal>>();
			
			roadIDMap = new HashMap<String, Integer>();
			laneIDMap = new HashMap<String, Integer>();
			signalIDMap = new HashMap<String, List<Signal>>();
			junctionIDMap = new HashMap<String, Integer>();
			
			incLaneJunctionMap = new HashMap<String, String>();
			intLaneJunctionMap = new HashMap<String, String>();
			
			laneRoadMap = new HashMap<String, String>();
			isInternalRoadMap = new HashMap<String, Boolean>();
			isInternalLaneMap = new HashMap<String, Boolean>();
		}
		
		@SuppressWarnings("deprecation")
		@Override
		public void startElement(
	            String uri,
	            String localName,
	            String qName,
	            Attributes attributes) {
			// Load the map info
			if(qName.equalsIgnoreCase("location")) {
				String offstr = attributes.getValue("netOffset");
				x_offs = Double.parseDouble(offstr.split(",")[0]);
				y_offs = Double.parseDouble(offstr.split(",")[1]);
				String boundstr = attributes.getValue("convBoundary");
				for(String one_bound: boundstr.split(",")) {
					boundary.add(Double.parseDouble(one_bound));
				}
				// This is for transforming the coordinate systems
				String proj4 = attributes.getValue("projParameter");
				if(proj4.contains("utm")) {
					try {
						int zone_number = 0;
						for(String substr: proj4.split(" ")) {
							if(substr.contains("zone")) {
								zone_number = Integer.parseInt(substr.substring(substr.indexOf("=")+1));
							}
						}
						MathTransformFactory mtFactory = ReferencingFactoryFinder.getMathTransformFactory(null);
						ReferencingFactoryContainer factories = new ReferencingFactoryContainer(null);
						GeographicCRS geoCRS = org.geotools.referencing.crs.DefaultGeographicCRS.WGS84;
						CartesianCS cartCS = org.geotools.referencing.cs.DefaultCartesianCS.GENERIC_2D;
						ParameterValueGroup parameters = mtFactory.getDefaultParameters("Transverse_Mercator");
						parameters.parameter("central_meridian").setValue(zone_number*6-183);
						parameters.parameter("latitude_of_origin").setValue(0.0);
						parameters.parameter("scale_factor").setValue(0.9996);
						parameters.parameter("false_easting").setValue(500000.0);
						parameters.parameter("false_northing").setValue(0.0);
						Map<String, String> properties = Collections.singletonMap("name", "WGS 84 / UTM Zone " + zone_number);
						ProjectedCRS sourceutm = factories.createProjectedCRS(properties, geoCRS, null, parameters, cartCS);
					    CoordinateReferenceSystem targetlatlong = CRS.decode("EPSG:4326", true);
					    transform = CRS.findMathTransform(sourceutm, targetlatlong, false);
					}
					catch(Exception e){
				         throw new RuntimeException(e);
				   }    
				}
				
				// Handle the case when proj is not provided, in this case, we assume this is Euclidean coordiate system
				if(transform == null) {
					try {
						ContextCreator.logger.warn("Did not find a valid projParameter in SUMO map file, used the default setting.");
						MathTransformFactory mtFactory = ReferencingFactoryFinder.getMathTransformFactory(null);
						ReferencingFactoryContainer factories = new ReferencingFactoryContainer(null);
						GeographicCRS geoCRS = org.geotools.referencing.crs.DefaultGeographicCRS.WGS84;
						CartesianCS cartCS = org.geotools.referencing.cs.DefaultCartesianCS.GENERIC_2D;
						ParameterValueGroup parameters = mtFactory.getDefaultParameters("Transverse_Mercator");
						parameters.parameter("central_meridian").setValue(0);
						parameters.parameter("latitude_of_origin").setValue(0.0);
						parameters.parameter("scale_factor").setValue(0.9996);
						parameters.parameter("false_easting").setValue(500000.0);
						parameters.parameter("false_northing").setValue(0.0);
						Map<String, String> properties = Collections.singletonMap("name", "WGS 84 / Default");
						ProjectedCRS sourceutm = factories.createProjectedCRS(properties, geoCRS, null, parameters, cartCS);
					    CoordinateReferenceSystem targetlatlong = CRS.decode("EPSG:4326", true);
					    transform = CRS.findMathTransform(sourceutm, targetlatlong, false);
					} 
					catch (FactoryException e) {
						e.printStackTrace();
					}
				}
			}
			
			// Handle the road info
			if(qName.equalsIgnoreCase("edge")) {
				if(attributes.getValue("function") == null || !attributes.getValue("function").equalsIgnoreCase("internal")) {
					if(attributes.getValue("type") != null && (attributes.getValue("type").contains("highway") || attributes.getValue("type").contains("driving"))) {
						currentRoadID = attributes.getValue("id");
						isInternalRoadMap.put(currentRoadID, false);
						int road_id = generateRoadID(attributes.getValue("id"));
						currentRoad = new Road(road_id);
						ArrayList<Integer> oneRoadLane = new ArrayList<Integer>();
						roadLane.put(road_id, oneRoadLane);
						roads.put(road_id, currentRoad);
						startX = 0;
						startY = 0;
						endX = 0;
						endY = 0;
						nLane = 0;
						inRoad = true;
					}
				}
				else { // Signal the start of the internal road
					currentRoadID = attributes.getValue("id");
					isInternalRoadMap.put(currentRoadID, true);
					inInternalRoad = true;
				}
			}
			
			// Handle the lane info
			if(qName.equalsIgnoreCase("lane")) {
				if(inRoad) {
					if(attributes.getValue("type") == null || attributes.getValue("type").equalsIgnoreCase("driving")) {
						laneRoadMap.put(attributes.getValue("id"), currentRoadID);
						isInternalLaneMap.put(attributes.getValue("id"), false);
					    int lane_id = generateLaneID(currentRoad.getID(), attributes.getValue("id"));
					    currentLane = new Lane(lane_id);
					    currentLane.setRoad(currentRoad.getID());    
					    roadLane.get(currentRoad.getID()).add(lane_id);
					    currentLane.setLength(Double.parseDouble(attributes.getValue("length")));
					    currentLane.setSpeed(Double.parseDouble(attributes.getValue("speed")));
					    currentMaxSpeed = Math.max(currentLane.getSpeed(), currentMaxSpeed);
					    // get coords
					    coords = new ArrayList<Coordinate>();
					    for(String one_coord: attributes.getValue("shape").split(" ")) {
					    	Coordinate coord = new Coordinate();
					    	coord.x = Double.parseDouble(one_coord.split(",")[0]) - x_offs;
					    	coord.y = Double.parseDouble(one_coord.split(",")[1]) - y_offs;
					    	try {
								JTS.transform(coord, coord, transform);
							} catch (TransformException e) {
								e.printStackTrace();
							}
					    	coords.add(coord);
					    }
					    currentLane.setCoords(coords);
					    
					    startX += coords.get(0).x;
					    startY += coords.get(0).y;
					    endX += coords.get(coords.size()-1).x;
					    endY += coords.get(coords.size()-1).y;
					    nLane++;
					    lanes.put(currentLane.getID(), currentLane);
					}
				    
				}
				if(inInternalRoad) {
					laneRoadMap.put(attributes.getValue("id"), currentRoadID);
					isInternalLaneMap.put(attributes.getValue("id"), false);
				}
			}
			
			//Handle the junction
			if (qName.equalsIgnoreCase("junction")) {
				if((attributes.getValue("type") == null) || !attributes.getValue("type").equalsIgnoreCase("internal")) {
					int junction_id = generateJunctionID(attributes.getValue("id"));
					// Generate specific type of junction
					currentJunction = new Junction(junction_id);
					int junction_type = deduceJunctionType(attributes.getValue("type"));
					currentJunction.setControlType(junction_id);
					currentJunction.setControlType(junction_type);
					
					Coordinate coord = new Coordinate();
			    	coord.x = Double.parseDouble(attributes.getValue("x")) - x_offs;
			    	coord.y = Double.parseDouble(attributes.getValue("y")) - y_offs;
			    	try {
						JTS.transform(coord, coord, transform);
					} catch (TransformException e) {
						e.printStackTrace();
					} 
					currentJunction.setCoord(coord);
					junctions.put(junction_id, currentJunction);
					// add lane to junction
					for(String inclane: attributes.getValue("incLanes").split(" ")) {
						incLaneJunctionMap.put(inclane, attributes.getValue("id"));
					}
					for(String intlane: attributes.getValue("intLanes").split(" ")) {
						intLaneJunctionMap.put(intlane, attributes.getValue("id"));
					}
				}
			}
			
			// Handle the request
			if (qName.equalsIgnoreCase("request")) {
				// do nothing
			}
			
			// Handle the signal
			if (qName.equalsIgnoreCase("tlLogic")) {
				currentSignal = attributes.getValue("id");
				ArrayList<Signal> tmpSignal = new ArrayList<Signal>();
				tmpPhaseState = new ArrayList<String>();
				tmpPhaseTime = new ArrayList<Integer>();
				signalIDMap.put(currentSignal, tmpSignal);
				inSignal = true;
			}
			
			// Handle the phase
			if (qName.equalsIgnoreCase("phase")) {
				if (inSignal) {
					tmpPhaseState.add(attributes.getValue("state"));
					tmpPhaseTime.add(Integer.parseInt(attributes.getValue("duration")));
				}
			}
			
			// handle the connection, note SUMO can include repetitive road connection to encode lane connection
			if (qName.equalsIgnoreCase("connection")) {
					String from_road = attributes.getValue("from");
					String to_road = attributes.getValue("to");
					
					String from_lane = from_road + "_" + attributes.getValue("fromLane"); // Important: here I assume the lane id would always be formed as roadid_index. 
					String to_lane = to_road + "_" + attributes.getValue("toLane");
					
					if(isInternalRoadMap.containsKey(from_road) && isInternalRoadMap.containsKey(to_road)) {
						if((!isInternalRoadMap.get(from_road)) && (!isInternalRoadMap.get(to_road))) { // Case 1, both from road and end road are not internal
							// find out the junction id, update the road connection and lane connection
							if(laneIDMap.containsKey(from_lane) && laneIDMap.containsKey(to_lane)) {
								String via_junction = incLaneJunctionMap.get(from_lane);
								if(junctionIDMap.containsKey(via_junction)) {
									int junction_id = junctionIDMap.get(via_junction);
									int from_road_id = roadIDMap.get(from_road);
									int to_road_id = roadIDMap.get(to_road);
									int from_lane_id = laneIDMap.get(from_lane);
									int to_lane_id = laneIDMap.get(to_lane);
									if (!roadConnections.containsKey(junction_id)) {
										roadConnections.put(junction_id, new ArrayList<List<Integer>>());
										laneConnections.put(junction_id, new ArrayList<List<Integer>>());
									}
									if (roadConnections.get(junction_id).contains(Arrays.asList(from_road_id,to_road_id))) {
										roadConnections.get(junction_id).add(Arrays.asList(from_road_id,to_road_id));
										if ((attributes.getValue("tl") != null) && signalIDMap.containsKey(attributes.getValue("tl"))) {
											// has signal control
											if(!signals.containsKey(from_road_id)) {
												signals.put(to_road_id, new HashMap<Integer, Signal>());
											}
											signals.get(from_road_id).put(to_road_id, signalIDMap.get(attributes.getValue("tl")).get(Integer.parseInt(attributes.getValue("linkIndex"))));
										}
									}
									laneConnections.get(junction_id).add(Arrays.asList(from_lane_id, to_lane_id));
								}
								else {
									ContextCreator.logger.error("Cannot find junction from lane: " + from_lane + " to: "+ to_lane);
								}
							}
						}
						else if(isInternalRoadMap.get(from_road) && (!isInternalRoadMap.get(to_road))) { // Case 2, from road is internal, to road is not internal
							// update the internal to-lane-connection
							internalToLaneConnections.put(from_lane, to_lane);
						}
						else if((!isInternalRoadMap.get(from_road)) && isInternalRoadMap.get(to_road)) { // Case 3, from road is not internal, to road is internal
							// update the internal from-road-connection and from-lane-connection
							internalFromLaneConnections.put(to_lane, from_lane);
						}
						else { // Case 4, from and to roads are internal, raise an error
							ContextCreator.logger.error("Both roads in the connection from: " + from_road + " to: "+ to_road + " are internal roads");
						}			
					}
					
			}
			
		}
		public void endElement(
				String uri,
                String localName,
                String qName)  {
			if (qName.equalsIgnoreCase("edge") && inRoad) {
				coords = new ArrayList<Coordinate>();
		    	Coordinate coord1 = new Coordinate();
		    	coord1.x = startX/nLane;
		    	coord1.y = startY/nLane;
		    	coords.add(coord1);
		    	Coordinate coord2 = new Coordinate();
		    	coord2.x = endX/nLane;
		    	coord2.y = endY/nLane;
		    	coords.add(coord2);
		    	currentRoad.setRoadType(deduceRoadType(currentRoadType, currentMaxSpeed));
				currentRoad.setCoords(coords);
				currentRoad.setSpeedLimit(currentMaxSpeed);
				currentMaxSpeed = 0;
			    currentRoad = null;
			    currentRoadType = null;
			    inRoad = false;
			}
			
			if (qName.equalsIgnoreCase("edge") && inInternalRoad) {
				inInternalRoad = false;
			}
			
			if (qName.equalsIgnoreCase("tlLogic") && inSignal) {
			    // create signals
				for (int i = 0; i < tmpPhaseState.get(0).length(); i++) {
					int green = 0;
					int yellow = 0;
					int red = 0;
					int start = 0;
					int total = 0;
					boolean flag = true;
					for (int j = 0; j < tmpPhaseTime.size(); j++) {
						if(tmpPhaseState.get(j).charAt(i)=='g' || tmpPhaseState.get(j).charAt(i)=='G') {
							green += tmpPhaseTime.get(j);
							flag = false;
						}
						else if(tmpPhaseState.get(j).charAt(i)=='r' || tmpPhaseState.get(j).charAt(i)=='R') {
							red += tmpPhaseTime.get(j);
							if(flag) {
								start += tmpPhaseTime.get(j);
							}
						}
						else {
							yellow += tmpPhaseTime.get(j);
							if(flag) {
								start += tmpPhaseTime.get(j);
							}
						}
						total += tmpPhaseTime.get(j);
					}
					start = (total - start) % total;
					signalNum += 1;
					Signal one_signal = new Signal(signalNum, Arrays.asList(green,yellow,red), start);
					signalIDMap.get(currentSignal).add(one_signal);
				}
				inSignal = false;
			}
			
			if (qName.equalsIgnoreCase("net")) { // end of the network file
				// post processing, add the connection established by internal roads
				for(String internal_lane: internalFromLaneConnections.keySet()) {
					if(internalToLaneConnections.containsKey(internal_lane)) {
						String from_lane = internalFromLaneConnections.get(internal_lane);
						String to_lane = internalToLaneConnections.get(internal_lane);
						
						String from_road = from_lane.substring(0, from_lane.lastIndexOf("_")); // Important: here I assume the lane id would always be formed as roadid_index. 
						String to_road = to_lane.substring(0, to_lane.lastIndexOf("_"));
						
						// find out the junction id, update the road connection and lane connection
						String via_junction = incLaneJunctionMap.get(from_lane);
						if(junctionIDMap.containsKey(via_junction)) {
							int junction_id = junctionIDMap.get(via_junction);
							int from_road_id = roadIDMap.get(from_road);
							int to_road_id = roadIDMap.get(to_road);
							int from_lane_id = laneIDMap.get(from_lane);
							int to_lane_id = laneIDMap.get(to_lane);
							if (!roadConnections.containsKey(junction_id)) {
								roadConnections.put(junction_id, new ArrayList<List<Integer>>());
								laneConnections.put(junction_id, new ArrayList<List<Integer>>());
							}
							if (roadConnections.get(junction_id).contains(Arrays.asList(from_road_id,to_road_id))) {
								roadConnections.get(junction_id).add(Arrays.asList(from_road_id,to_road_id));
							}
							if (laneConnections.get(junction_id).contains(Arrays.asList(from_lane_id, to_lane_id))) {
								laneConnections.get(junction_id).add(Arrays.asList(from_lane_id, to_lane_id));
							}
						}
						else {
							ContextCreator.logger.error("Postprocessing cannot find junction from lane: " + from_lane + " to: "+ to_lane);
						}
					}
				}
				
				// road connection
				for(List<List<Integer>> rcs: roadConnections.values()) {
					for(List<Integer> rc: rcs) {
						roads.get(rc.get(0)).addDownStreamRoad(rc.get(1));
					}
				}
				// lane connection
				for(List<List<Integer>> lcs: laneConnections.values()) {
					for(List<Integer> lc: lcs) {
						lanes.get(lc.get(0)).addDownStreamLane(lc.get(1));
						lanes.get(lc.get(1)).addUpStreamLane(lc.get(0));
					}
				}
			}
		}
	}
	
	public void print() {
		System.out.println("Finished loading, there are " + this.getRoad().size() + " roads, " + this.getLane().size()+
				" lanes, " + this.getJunction().size()+ " junctions");
	}
	
	public static void main(String[] args) {
//		SumoXML sxml = new SumoXML("data/study_region.net.xml");
//		SumoXML sxml = new SumoXML("data/IN/facility/road/indiana-35.net.xml");
		SumoXML sxml = new SumoXML("data/CARLA/facility/road/Town05.net.xml");
		sxml.print();
	}
	
}
