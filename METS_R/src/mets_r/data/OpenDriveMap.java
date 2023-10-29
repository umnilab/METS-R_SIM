package mets_r.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.w3c.dom.Document;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.vividsolutions.jts.geom.Coordinate;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Road;

/**
 * Convert xodr to METS-R classes
 * Remark: all roadIDs need to be positive, each road has at most 20 lanes
 * 
 * @author Zengxiang Lei
 *
 */

public class OpenDriveMap {
	public String proj4 = "plane";
	public double x_offs = 0;
	public double y_offs = 0;
	public String xodr_file = "";
	public Document xml_doc;
	
	public OpenDRIVEMapHandler handler;
	
	public OpenDriveMap(String xodr_file) {
		this.xodr_file = xodr_file;
		
		// center_map, with_road_objects, with_lateral_profile, with_lane_height, 
		// abs_z_for_local_road_obj_outline, fix_spiral_edge_cases

		SAXParserFactory factory = SAXParserFactory.newInstance();

		try {
			// https://rules.sonarsource.com/java/RSPEC-2755
			// prevent XXE, completely disable DOCTYPE declaration:
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

			SAXParser saxParser = factory.newSAXParser();

			handler = new OpenDRIVEMapHandler();

			saxParser.parse(this.xodr_file, handler);
			
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
	
	public HashMap<Integer, Junction> getJunction(){
		return this.handler.getJunction();
	}
	
	public HashMap<Integer, List<List<Integer>>>  getRoadConnection(){
		return this.handler.getRoadConnection();
	}
	
	public List<List<Integer>>  getRoadConnection(int junction_id) {
		return this.handler.getRoadConnection(junction_id);
	}
	
	private class OpenDRIVEMapHandler extends DefaultHandler {
		HashMap<Integer, Road> roads;
		HashMap<Integer,Junction> junctions;
		HashMap<Integer,Lane> lanes;
		HashMap<Integer, List<List<Integer>>> roadConnections; // road connection within junctions
		HashMap<Integer, List<List<Integer>>> laneConnections; // lane connection within junctions
		
		ArrayList<Integer> tmpConnection;
		
		Road currentRoad;
		Road opposeRoad;
		Junction currentJunction;
		Lane currentLane;
		
		int predecessorRoad = 0;
		int successorRoad = 0;
		
		boolean inRoad = false;
		boolean inConnectingRoad = false;
		boolean inLane = false;
		boolean inJunction = false;
		
		double s;
		double x;
		double y;
		double hdg;
		double length;
		
		ArrayList<Coordinate> coords;
		
		int incomingRoad;
		int connectingRoad;
		String contactPoint;
		
		public HashMap<Integer,Road> getRoad() {return roads;};
		public HashMap<Integer,Junction> getJunction() {return junctions;};
		public HashMap<Integer,Lane> getLane() {return lanes;};
		public HashMap<Integer, List<List<Integer>>>  getRoadConnection() {return roadConnections;}
		public List<List<Integer>>  getRoadConnection(int junction_id) {return roadConnections.get(junction_id);}
		
		@Override
		public void startDocument() {
			roads = new HashMap<Integer,Road>();
			junctions = new HashMap<Integer,Junction>();
			lanes = new HashMap<Integer,Lane>();
			roadConnections = new HashMap<Integer, List<List<Integer>>>();
			laneConnections = new HashMap<Integer, List<List<Integer>>>();
		}
		
		@Override
		public void startElement(
	            String uri,
	            String localName,
	            String qName,
	            Attributes attributes) {
			
			if (qName.equalsIgnoreCase("road")) {
				int junction_id = Integer.valueOf(attributes.getValue("junction"));
				if(junction_id == -1) { // generating the road instances
					int road_id = Math.abs(Integer.valueOf(attributes.getValue("id")));
					double length = Double.parseDouble(attributes.getValue("length"));
					currentRoad = new Road(road_id, length);
					roads.put(road_id, currentRoad);
					coords = new ArrayList<Coordinate>();
					inRoad = true;
				}
				else { // this is a connecting road within the junction
					if(junctions.containsKey(junction_id)) {
						currentJunction = junctions.get(junction_id);
					}
					else { // generating the junction instances
						currentJunction = new Junction(junction_id);
						junctions.put(junction_id, currentJunction);
					}
					inConnectingRoad = true;
				}
			}
			if (qName.equalsIgnoreCase("lane")) {
				if(inRoad) {
					int lane_id = Integer.valueOf(attributes.getValue("id"));
					if(lane_id != 0) {
						if(lane_id < 0) { // right
							currentLane = new Lane(this.generateLaneID(currentRoad.getID(), lane_id));
							currentLane.setRoad(currentRoad.getID());
						}
						else if(lane_id > 0) { // left
							if(roads.containsKey(-currentRoad.getID())) 
								opposeRoad = roads.get(-currentRoad.getID());
							else 
								opposeRoad = new Road(-currentRoad.getID());
							    roads.put(opposeRoad.getID(), opposeRoad);
							currentLane = new Lane(this.generateLaneID(currentRoad.getID(), lane_id));
							currentLane.setRoad(opposeRoad.getID());
						}
						lanes.put(currentLane.getID(), currentLane);
						inLane = true;
					}
				}
				else if(inConnectingRoad){
					inLane = true;
				}
			}
			
			if (qName.equalsIgnoreCase("junction")) { 
				// do something for generating junctions
				int junction_id = Integer.valueOf(attributes.getValue("id"));
				
				if(junctions.containsKey(junction_id)) {
					currentJunction = junctions.get(junction_id);
				}
				else {
					currentJunction = new Junction(junction_id);
					junctions.put(junction_id, currentJunction);
				}
				inJunction = true;
			}
			
			if (qName.equalsIgnoreCase("controller")) {
				if(inJunction) {
				    currentJunction.setControlType(Junction.StaticSignal);
				}
			}
			
			// Add post-processing to establish the connection
			if (qName.equalsIgnoreCase("predecessor")) {
				if(inLane) {
					int id = Integer.valueOf(attributes.getValue("id"));
					int laneID = this.generateLaneID(predecessorRoad, id);
					if(inRoad) {
						currentLane.addUpStreamLane(laneID);
					}
					else if(inConnectingRoad) {
						tmpConnection = new ArrayList<Integer>();
						tmpConnection.add(laneID);
					}
				}
				else if(inRoad) {
					String elementType = attributes.getValue("elementType");
					int elementId = Integer.valueOf(attributes.getValue("elementId"));
					
					String contactPoint = attributes.getValue("contactPoint");
					if(elementType.equalsIgnoreCase("junction")) { 
						// do nothing
					}
					else if(elementType.equalsIgnoreCase("road")) {
						if(contactPoint.equalsIgnoreCase("start")) {
							predecessorRoad = elementId;
						}
						else {
							predecessorRoad = -elementId;
						}
					}
				}
				else if(inConnectingRoad) {
					String elementType = attributes.getValue("elementType");
					int elementId = Integer.valueOf(attributes.getValue("elementId"));
					String contactPoint = attributes.getValue("contactPoint");
					if(elementType.equalsIgnoreCase("road")) {
						tmpConnection = new ArrayList<Integer>();
						if(contactPoint.equalsIgnoreCase("start")) {
							predecessorRoad = elementId;
							tmpConnection.add(elementId);
						}
						else {
							predecessorRoad = -elementId;
							tmpConnection.add(-elementId);
						}
					}
				}
			}
			
			if (qName.equalsIgnoreCase("successor")) {
				if(inLane) {
					if(inRoad) {
						int id = Integer.valueOf(attributes.getValue("id"));
						int laneID = this.generateLaneID(successorRoad, id);
						if(inRoad) {
							currentLane.addDownStreamLane(laneID);
						}
						else if(inConnectingRoad) {
							tmpConnection.add(laneID);
							if(!this.laneConnections.containsKey(currentJunction.getID())) {
								ArrayList<List<Integer>> tmp = new ArrayList<List<Integer>>();
								this.laneConnections.put(currentJunction.getID(), tmp);
							}
							this.laneConnections.get(currentJunction.getID()).add(tmpConnection);
						}
					}
				}
				else if(inRoad) {
					String elementType = attributes.getValue("elementType");
					int elementId = Integer.valueOf(attributes.getValue("elementId"));
					String contactPoint = attributes.getValue("contactPoint");
					
					if(elementType.equalsIgnoreCase("junction")) { 
						// do nothing
					}
					else if(elementType.equalsIgnoreCase("road")) {
						if(contactPoint.equalsIgnoreCase("start")) {
							successorRoad = elementId;
						}
						else {
							successorRoad = -elementId;
						}
					}
				}
				else if(inConnectingRoad) {
					String elementType = attributes.getValue("elementType");
					int elementId = Integer.valueOf(attributes.getValue("elementId"));
					String contactPoint = attributes.getValue("contactPoint");
					if(elementType.equalsIgnoreCase("road")) {
						if(contactPoint.equalsIgnoreCase("start")) {
							successorRoad = elementId;
							tmpConnection.add(elementId);
						}
						else {
							successorRoad = -elementId;
							tmpConnection.add(-elementId);
						}
						if(!this.roadConnections.containsKey(currentJunction.getID())) {
							ArrayList<List<Integer>> tmp = new ArrayList<List<Integer>>();
							this.roadConnections.put(currentJunction.getID(), tmp);
						}
						this.roadConnections.get(currentJunction.getID()).add(tmpConnection);
					}
				}
			}
			
			// Add post-processing to get geometry
			if (qName.equalsIgnoreCase("geometry")) {
				// record the geometry information
				if(inRoad) {
					s = Double.valueOf(attributes.getValue("s"));
					x = Double.valueOf(attributes.getValue("x"));
					y = Double.valueOf(attributes.getValue("y"));
					hdg = Double.valueOf(attributes.getValue("hdg"));
					length = Double.valueOf(attributes.getValue("length"));
				}
			}
			
            if (qName.equalsIgnoreCase("line")) {
            	if(inRoad) {
				    // Create two coordinates, add it to road coord
            		Coordinate coord = new Coordinate();
            		coord.x = x;
            		coord.y = y;
            		coords.add(coord);
            		
            		Coordinate coord2 = new Coordinate();
            		coord2.x = x + length * Math.cos(hdg);
            		coord2.y = y + length * Math.sin(hdg);
            		coords.add(coord2);
//            		System.out.println("Line: "+ s + "," + x + "," + y +"," + hdg + "," + length);
            	}
			}
            
            if (qName.equalsIgnoreCase("spiral")) {
            	if(inRoad) {
            		// TODO: realized the spiral and add it to current Road
//                	double curvStart = Double.valueOf(attributes.getValue("curvStart"));
//                	double curvEnd = Double.valueOf(attributes.getValue("curvEnd"));
//                	// currentRoad.addSpiralGeometry(s, x, y, hdg, length, curvStart, curvEnd);
                	Coordinate coord = new Coordinate();
            		coord.x = x;
            		coord.y = y;
            		coords.add(coord);
//                	System.out.println("Spiral: "+ s + "," + x + "," + y +"," + hdg + "," + length);
            	}
            }
            
            if (qName.equalsIgnoreCase("arc")) {
            	if(inRoad) {
	            	// TODO: realized the spiral and add it to current Road
//	            	double curvature = Double.valueOf(attributes.getValue("curvature"));
	            	// currentRoad.addArcGeometry(s, x, y, hdg, length, curvature);
	            	Coordinate coord = new Coordinate();
            		coord.x = x;
            		coord.y = y;
            		coords.add(coord);
//	            	System.out.println("Arc: "+ s + "," + x + "," + y +"," + hdg + "," + length);
            	}
			}
            
            // TODO: add support for elevation
//            if (qName.equalsIgnoreCase("elevation")) {
//            	if(inRoad) {
//					double s = Double.valueOf(attributes.getValue("s"));
//					double a = Double.valueOf(attributes.getValue("a"));
//					double b = Double.valueOf(attributes.getValue("b"));
//					double c = Double.valueOf(attributes.getValue("c"));
//					double d = Double.valueOf(attributes.getValue("d"));
//					// currentRoad.setElevation(s, a, b, c, d);
////					System.out.println("Elevation");
//            	}
//			}
//            
//            if (qName.equalsIgnoreCase("superelevation")) {
//            	if(inRoad) {
//					double s = Double.valueOf(attributes.getValue("s"));
//					double a = Double.valueOf(attributes.getValue("a"));
//					double b = Double.valueOf(attributes.getValue("b"));
//					double c = Double.valueOf(attributes.getValue("c"));
//					double d = Double.valueOf(attributes.getValue("d"));
//					// currentRoad.setSuperElevation(s, a, b, c, d);
////					System.out.println("Superelelevation");
//            	}
//			}
//            
//            if (qName.equalsIgnoreCase("width")) {
//            	if(inLane) {
//					double sOffset = Double.valueOf(attributes.getValue("sOffset"));
//					double a = Double.valueOf(attributes.getValue("a"));
//					double b = Double.valueOf(attributes.getValue("b"));
//					double c = Double.valueOf(attributes.getValue("c"));
//					double d = Double.valueOf(attributes.getValue("d"));
//					// currentLane.setWidth(sOffset, a, b, c, d);
////					System.out.println("Lane geometry");
//            	}
//			}
//            
//            if (qName.equalsIgnoreCase("connection")) {
//            	if(inJunction) {
//            		incomingRoad = Integer.valueOf(attributes.getValue("incomingRoad"));
//                	connectingRoad = Integer.valueOf(attributes.getValue("connectingRoad"));
//                	// currentJunction.setConnection(incomingRoad, connectingRoad);
//                	contactPoint = String.valueOf(attributes.getValue("contactPoint"));
//                	if(contactPoint.equalsIgnoreCase("start")) {
//                		Road road = roads.get(incomingRoad);
//                		road.addDownStreamRoad(connectingRoad);
//                	}
//                	else {
//                		Road road = roads.get(incomingRoad);
//                		road.addDownStreamRoad(-connectingRoad);
//                	}
////                	System.out.println("Connection");
//            	}
//            }
            
            if (qName.equalsIgnoreCase("laneLink")) {
            	if(inJunction) {
	            	int from = Integer.valueOf(attributes.getValue("from"));
					int to = Integer.valueOf(attributes.getValue("to"));
//					System.out.println("Lane link");
            	}
			}
		}
		
		@Override
		public void endElement(
				String uri,
                String localName,
                String qName)  {
			if (qName.equalsIgnoreCase("road")) {
				if(this.inRoad) {
					if(opposeRoad != null) {
						ArrayList<Coordinate> coords2 = new ArrayList<Coordinate>();
						for(Coordinate p : coords) {
							coords2.add(p);
						}
						Collections.reverse(coords2);
					    opposeRoad.setCoords(coords2);
					}
				}
				currentRoad = null;
				opposeRoad = null;
				inRoad = false;
				inConnectingRoad = false;
				predecessorRoad = 0;
				successorRoad = 0;
			}
			
			if (qName.equalsIgnoreCase("planView")) {
				// add the last coordinate of the current road
				if(this.inRoad) {
					Coordinate coord = new Coordinate();
					coords.add(coord);
					currentRoad.setCoords(coords);
				}
			}
			
			if (qName.equalsIgnoreCase("lane")) {
				inLane = false;
			}
			if (qName.equalsIgnoreCase("junction")) {
				inJunction = false;
			}
			 
			// Postprocessing
			if (qName.equalsIgnoreCase("OpenDRIVE")) {
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
				// geometry of all lanes
				for (Lane l: lanes.values()) {
					l.setCoords(roads.get(l.getRoad()).getCoords());
				}
				// junction connection
				// we deal with the road connection (together with the signal in cityContext
			}
		}
		
		public int generateLaneID(int roadID, int laneID) {
			roadID = Math.abs(roadID);
			if(laneID < 0) {
				return (roadID*100 - laneID);
			}
			else { // Opposite road
				return (-roadID*100 - laneID);
			}
		}
	}
	
	public void print() {
		System.out.println("Finished loading, there are " + this.getRoad().size() + " roads, " + this.getLane().size()+
				" lanes, " + this.getJunction().size()+ " junctions");
//		for(int roadID: this.getRoad().keySet()) {
//			System.out.println("Road ID is: " + roadID);
//		}
//		for(int laneID: this.getLane().keySet()) {
//			System.out.println("Lane ID is: " + laneID);
//		}
	}
	
	public static void main(String[] args) {
		OpenDriveMap odm = new OpenDriveMap("data/test.xodr");
		odm.print();
	}
	
}
