package mets_r.data;

import org.xml.sax.helpers.DefaultHandler;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.facility.Signal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.poi.util.SystemOutLogger;
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
	private String proj4 = "";
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
	
	// TODO: coordinate system transform
	// TODO: ignore connecting roads
	private class SumoXMLHandler extends DefaultHandler{
		double x_offs = 0;
		double y_offs = 0;
		String proj4 = "";
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
		boolean inJunction = false;
		boolean inSignal = false;
		
		ArrayList<Coordinate> coords;
		
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
				proj4 = attributes.getValue("projParameter"); // TODO: figure out how to transform
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
				    for(String one_coord: attributes.getValue("shape").split("_")) {
				    	Coordinate coord = new Coordinate();
				    	coord.x = Double.parseDouble(one_coord.split(",")[0]);
				    	coord.y = Double.parseDouble(one_coord.split(",")[0]);
				    	coords.add(coord);
				    }
				    currentLane.setCoords(coords);
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
		    	coord.x = Double.parseDouble(attributes.getValue("x"));
		    	coord.y = Double.parseDouble(attributes.getValue("y"));
				currentJunction.setCoord(coord);
				junctions.put(junction_id, currentJunction);
				inJunction = true;
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
			
			// handle the connection
			if (qName.equalsIgnoreCase("connection")) {
				int from = roadIDMap.get(attributes.getValue("from"));
				int to = roadIDMap.get(attributes.getValue("to"));
				int via = 0; //edge case, connection is for minor link
				if(attributes.getValue("via") != null) {
					via = laneJunctionMap.get(attributes.getValue("via"));
				}
				
				int from_lane = roadLane.get(from).get(Integer.parseInt(attributes.getValue("fromLane")));
				int to_lane = roadLane.get(to).get(Integer.parseInt(attributes.getValue("toLane")));
				
				if (!roadConnections.containsKey(via)) {
					roadConnections.put(via, new ArrayList<List<Integer>>());
					laneConnections.put(via, new ArrayList<List<Integer>>());
				}
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
		public void endElement(
				String uri,
                String localName,
                String qName)  {
			if (qName.equalsIgnoreCase("edge")) {
			    currentRoad = null;
			    inRoad = false;
			}
			if (qName.equalsIgnoreCase("junction")) {
				inJunction = false;
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
