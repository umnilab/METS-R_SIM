package mets_r.data.input;

import org.xml.sax.helpers.DefaultHandler;
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
 * Convert 
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
		MathTransform transform;
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
		HashMap<String, Integer> laneJunctionMap; // which lane belongs to which junction
		
		Road currentRoad;
		Junction currentJunction;
		Lane currentLane;
		String currentSignal; // only use the id of the tlLogic
		
		boolean inRoad = false;
		boolean inSignal = false;
		
		ArrayList<Coordinate> coords;
		double startX = 0;
		double startY = 0;
		double endX = 0;
		double endY = 0;
		int nLane = 0;
		
		HashMap<String,Double> roadType = new HashMap<String,Double>();
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
		
		@Override
		public void startDocument() {
			roads = new HashMap<Integer,Road>();
			junctions = new HashMap<Integer,Junction>();
			lanes = new HashMap<Integer,Lane>();
			roadConnections = new HashMap<Integer, List<List<Integer>>>();
			laneConnections = new HashMap<Integer, List<List<Integer>>>();
			roadLane = new HashMap<Integer, List<Integer>>();
			signals = new HashMap<Integer, HashMap<Integer, Signal>>();
			
			roadIDMap = new HashMap<String, Integer>();
			laneIDMap = new HashMap<String, Integer>();
			signalIDMap = new HashMap<String, List<Signal>>();
			junctionIDMap = new HashMap<String, Integer>();
			laneJunctionMap = new HashMap<String, Integer>();
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
			}
			
			// Handle the road type info
			if(qName.equalsIgnoreCase("type")) {
				roadType.put(attributes.getValue("id"), Double.parseDouble(attributes.getValue("speed")));
			}
			
			// Handle the road info
			if(qName.equalsIgnoreCase("edge")) {
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
			
			// Handle the lane info
			if(qName.equalsIgnoreCase("lane")) {
				if(inRoad) {
				    int lane_id = generateLaneID(currentRoad.getID(), attributes.getValue("id"));
				    currentLane = new Lane(lane_id);
				    currentLane.setRoad(currentRoad.getID());    
				    roadLane.get(currentRoad.getID()).add(lane_id);
				    currentLane.setLength(Double.parseDouble(attributes.getValue("length")));
				    currentRoad.updateFreeFlowSpeed(Double.parseDouble(attributes.getValue("speed")));
				    
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
			
			//Handle the junction
			if (qName.equalsIgnoreCase("junction")) {
				int junction_id = generateJunctionID(attributes.getValue("id"));
				currentJunction = new Junction(junction_id);
				// add lane to junction
				for(String intlane: attributes.getValue("incLanes").split(" ")) {
					laneJunctionMap.put(intlane, junction_id);
				}
				for(String intlane: attributes.getValue("intLanes").split(" ")) {
					laneJunctionMap.put(intlane, junction_id);
				}
				
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
				int from = roadIDMap.get(attributes.getValue("from"));
				int to = roadIDMap.get(attributes.getValue("to"));
				int via = 0; //the edge case: connection is for minor link
				if(attributes.getValue("via") != null) via = laneJunctionMap.get(attributes.getValue("via"));
				int from_lane = roadLane.get(from).get(Integer.parseInt(attributes.getValue("fromLane")));
				int to_lane = roadLane.get(to).get(Integer.parseInt(attributes.getValue("toLane")));
				if (!roadConnections.containsKey(via)) {
					roadConnections.put(via, new ArrayList<List<Integer>>());
					laneConnections.put(via, new ArrayList<List<Integer>>());
				}
				if(roadConnections.get(via).contains(Arrays.asList(from,to))) {
					laneConnections.get(via).add(Arrays.asList(from_lane, to_lane));
				}
				else {
					roadConnections.get(via).add(Arrays.asList(from,to));
					laneConnections.get(via).add(Arrays.asList(from_lane, to_lane));
					if (attributes.getValue("tl") != null) {
						// has signal control
						if(!signals.containsKey(from)) {
							signals.put(from, new HashMap<Integer, Signal>());
						}
						signals.get(from).put(to, signalIDMap.get(attributes.getValue("tl")).get(Integer.parseInt(attributes.getValue("linkIndex"))));
					}
					else {
						// no signal control, add delay
					}
				}
			}
			
		}
		public void endElement(
				String uri,
                String localName,
                String qName)  {
			if (qName.equalsIgnoreCase("edge")) {
				coords = new ArrayList<Coordinate>();
		    	Coordinate coord1 = new Coordinate();
		    	coord1.x = startX/nLane;
		    	coord1.y = startY/nLane;
		    	coords.add(coord1);
		    	Coordinate coord2 = new Coordinate();
		    	coord2.x = endX/nLane;
		    	coord2.y = endY/nLane;
		    	coords.add(coord2);
				currentRoad.setCoords(coords);
			    currentRoad = null;
			    inRoad = false;
			}
			
			if (qName.equalsIgnoreCase("tlLogic")) {
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
			
			if (qName.equalsIgnoreCase("net")) {
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
				
				// junction connection
				// we deal with the road connection (together with the signal in cityContext
			}
		}
	}
	
	public void print() {
		System.out.println("Finished loading, there are " + this.getRoad().size() + " roads, " + this.getLane().size()+
				" lanes, " + this.getJunction().size()+ " junctions");
	}
	
	public static void main(String[] args) {
		SumoXML sxml = new SumoXML("data/study_region.net.xml");
		sxml.print();
	}
	
}
