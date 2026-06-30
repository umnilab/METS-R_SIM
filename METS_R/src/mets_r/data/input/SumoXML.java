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
import java.util.LinkedHashMap;
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
	
	public LinkedHashMap<Integer, Road> getRoad(){
		return this.handler.getRoad();
	}
	
	public LinkedHashMap<Integer, Lane> getLane(){
		return this.handler.getLane();
	}
	
	public LinkedHashMap<Integer, LinkedHashMap<Integer, Signal>> getSignal(){
		return this.handler.getSignal();
	}
	
	public LinkedHashMap<Integer, Junction> getJunction(){
		return this.handler.getJunction();
	}
	
	public LinkedHashMap<Integer, List<List<Integer>>>  getRoadConnection(){
		return this.handler.getRoadConnection();
	}
	
	public List<List<Integer>>  getRoadConnection(int junction_id) {
		return this.handler.getRoadConnection(junction_id);
	}
	
	private class SumoXMLHandler extends DefaultHandler{
		double x_offs = 0;
		double y_offs = 0;
		ArrayList<Double> boundary = new ArrayList<Double>();
		
		LinkedHashMap<Integer, Road> roads;
		LinkedHashMap<Integer,Junction> junctions;
		LinkedHashMap<Integer,Lane> lanes;
		LinkedHashMap<Integer, LinkedHashMap<Integer, Signal>> signals;
		
		
		LinkedHashMap<Integer, List<List<Integer>>> roadConnections; // road connection within junctions
		LinkedHashMap<Integer, List<List<Integer>>> laneConnections; // lane connection within junctions
		
		LinkedHashMap<Integer, List<Integer>> roadLane;
		ArrayList<String> tmpPhaseState;
		ArrayList<Integer> tmpPhaseTime;
		LinkedHashMap<String, List<Signal>> signalIDMap; 
		LinkedHashMap<String, Integer> roadIDMap; //SUMO use string as the road ID, need to translate it to integer
		LinkedHashMap<String, Integer> laneIDMap;
		LinkedHashMap<String, Integer> junctionIDMap;
		
		LinkedHashMap<String, String> incLaneJunctionMap; // which lane ends (incoming) to which junction
		LinkedHashMap<String, String> intLaneJunctionMap; // which lane starts (initializing) from which junction
		LinkedHashMap<String, Integer> junctionsRoadMap; // which road ends (incoming) to which junction
		LinkedHashMap<String, String> laneRoadMap;
		
		// Handle internal lanes, since METS-R SIM does not consider inner intersection movement
		// we need to translate the internal connection back into the lane connection
		LinkedHashMap<String, Boolean> isInternalRoadMap;
		LinkedHashMap<String, Boolean> isInternalLaneMap;
		
		LinkedHashMap<String, String> internalFromLaneConnections;  
		LinkedHashMap<String, String> internalToLaneConnections; 
		LinkedHashMap<String, List<String>> internalFromToLaneConnections;
		
		Road currentRoad;
		Junction currentJunction;
		Lane currentLane;
		String currentSignal; // only use the id of the tlLogic
		String currentRoadID; // for building the land-road map
		
		String currentFromJunctionID = null; 
		String currentToJunctionID = null;
		
		double currentMaxSpeed = 0;
		String currentRoadType = null;
		
		boolean inRoad = false;
		boolean inInternalRoad = false;
		boolean inSignal = false;
		
		ArrayList<Coordinate> coords;
		double startX = 0;
		double startY = 0;
		double startZ = 0;
		double endX = 0;
		double endY = 0;
		double endZ = 0;
		int nLane = 0;
		
		int roadNum = 0;
		int junctionNum = 0;
		int signalNum = 0;
		
		public LinkedHashMap<Integer,Road> getRoad() {return roads;};
		public LinkedHashMap<Integer,Junction> getJunction() {return junctions;};
		public LinkedHashMap<Integer,Lane> getLane() {return lanes;};
		public LinkedHashMap<Integer, LinkedHashMap<Integer, Signal>> getSignal() {return signals;}
		public LinkedHashMap<Integer, List<List<Integer>>>  getRoadConnection() {return roadConnections;}
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

		private Coordinate copyCoordinate(Coordinate coord) {
			return new Coordinate(coord.x, coord.y, coord.z);
		}

		private double zOrZero(Coordinate coord) {
			return Double.isNaN(coord.z) ? 0.0 : coord.z;
		}

		private double squaredDistance2D(Coordinate c1, Coordinate c2) {
			double dx = c1.x - c2.x;
			double dy = c1.y - c2.y;
			return dx * dx + dy * dy;
		}

		private Coordinate interpolateCoordinate(Coordinate c1, Coordinate c2, double t) {
			double clamped = Math.max(0.0, Math.min(1.0, t));
			double z1 = zOrZero(c1);
			double z2 = zOrZero(c2);
			return new Coordinate(
					c1.x + clamped * (c2.x - c1.x),
					c1.y + clamped * (c2.y - c1.y),
					z1 + clamped * (z2 - z1));
		}

		private boolean transitionStartIsBehindUpstreamLane(Coordinate upstreamPrev, Coordinate upstreamEnd,
				Coordinate downstreamStart) {
			double dirX = upstreamEnd.x - upstreamPrev.x;
			double dirY = upstreamEnd.y - upstreamPrev.y;
			double dirLengthSq = dirX * dirX + dirY * dirY;
			if (dirLengthSq <= 1e-24) return false;

			double relX = downstreamStart.x - upstreamEnd.x;
			double relY = downstreamStart.y - upstreamEnd.y;
			double relLengthSq = relX * relX + relY * relY;
			if (relLengthSq <= 1e-24) return false;

			return (relX * dirX + relY * dirY) < -1e-12 * Math.sqrt(dirLengthSq);
		}

		private double projectionAlongSegment(Coordinate point, Coordinate segmentStart, Coordinate segmentEnd) {
			double segX = segmentEnd.x - segmentStart.x;
			double segY = segmentEnd.y - segmentStart.y;
			double segLengthSq = segX * segX + segY * segY;
			if (segLengthSq <= 1e-24) return Double.NaN;

			return ((point.x - segmentStart.x) * segX
					+ (point.y - segmentStart.y) * segY) / segLengthSq;
		}

		private boolean projectsInsideSegment(Coordinate point, Coordinate segmentStart, Coordinate segmentEnd) {
			double projection = projectionAlongSegment(point, segmentStart, segmentEnd);
			if (Double.isNaN(projection) || Double.isInfinite(projection)) return false;
			return projection > 1e-9 && projection <= 1.0 + 1e-9;
		}

		private boolean firstControlPointIsBehindCurrent(Coordinate currentCoord, ArrayList<Coordinate> coords) {
			if (coords.size() < 2) return false;
			return projectsInsideSegment(currentCoord, coords.get(0), coords.get(1));
		}

		private double turnAngleDegrees(Coordinate previous, Coordinate current, Coordinate next) {
			double inX = current.x - previous.x;
			double inY = current.y - previous.y;
			double outX = next.x - current.x;
			double outY = next.y - current.y;
			double inLength = Math.sqrt(inX * inX + inY * inY);
			double outLength = Math.sqrt(outX * outX + outY * outY);
			if (inLength <= 1e-12 || outLength <= 1e-12) return 0.0;

			double cosTheta = (inX * outX + inY * outY) / (inLength * outLength);
			cosTheta = Math.max(-1.0, Math.min(1.0, cosTheta));
			return Math.toDegrees(Math.acos(cosTheta));
		}

		private boolean hasSharpTransitionAngle(Coordinate previous, Coordinate current, Coordinate next) {
			return turnAngleDegrees(previous, current, next) > 150.0;
		}

		private Coordinate adjustedControlPoint(Coordinate incomingCoord, Coordinate controlPoint,
				Coordinate followingPoint) {
			if (squaredDistance2D(controlPoint, followingPoint) <= 1e-24) {
				return copyCoordinate(incomingCoord);
			}

			double projection = projectionAlongSegment(incomingCoord, controlPoint, followingPoint);
			if (Double.isNaN(projection) || Double.isInfinite(projection)) {
				projection = 0.0;
			}
			// Move just past the closest point on the next segment so the
			// generated transition curve does not collapse into a zero-length loop.
			double targetProjection = Math.max(0.001, Math.min(0.999, projection + 0.001));
			return interpolateCoordinate(controlPoint, followingPoint, targetProjection);
		}

		private boolean setAdjustedControlPoint(ArrayList<Coordinate> coords, int index, Coordinate adjustedCoord) {
			if (index < 0 || index >= coords.size()) return false;
			if (squaredDistance2D(coords.get(index), adjustedCoord) <= 1e-24) return false;
			coords.set(index, copyCoordinate(adjustedCoord));
			return true;
		}

		private void refreshRoadCenterlinesFromLanes() {
			for (Map.Entry<Integer, List<Integer>> entry : roadLane.entrySet()) {
				Road road = roads.get(entry.getKey());
				if (road == null) continue;

				double startX = 0;
				double startY = 0;
				double startZ = 0;
				double endX = 0;
				double endY = 0;
				double endZ = 0;
				int laneCount = 0;

				for (int laneID : entry.getValue()) {
					Lane lane = lanes.get(laneID);
					if (lane == null) continue;
					ArrayList<Coordinate> laneCoords = lane.getCoords();
					if (laneCoords.isEmpty()) continue;

					Coordinate start = laneCoords.get(0);
					Coordinate end = laneCoords.get(laneCoords.size() - 1);
					startX += start.x;
					startY += start.y;
					startZ += zOrZero(start);
					endX += end.x;
					endY += end.y;
					endZ += zOrZero(end);
					laneCount++;
				}

				if (laneCount == 0) continue;
				ArrayList<Coordinate> roadCoords = new ArrayList<Coordinate>();
				roadCoords.add(new Coordinate(startX / laneCount, startY / laneCount, startZ / laneCount));
				roadCoords.add(new Coordinate(endX / laneCount, endY / laneCount, endZ / laneCount));
				road.setCoords(roadCoords);
			}
		}

		private void prescanTransitionControlPoints() {
			int adjustedCount = 0;
			int skippedCount = 0;
			int maxControlPointsToScan = 4;

			for (Lane fromLane : lanes.values()) {
				ArrayList<Coordinate> fromCoords = fromLane.getCoords();
				if (fromCoords.size() < 2) continue;

				Coordinate upstreamPrev = fromCoords.get(fromCoords.size() - 2);
				Coordinate upstreamEnd = fromCoords.get(fromCoords.size() - 1);

				for (int toLaneID : fromLane.getDownStreamLanes()) {
					Lane toLane = lanes.get(toLaneID);
					if (toLane == null) continue;
					ArrayList<Coordinate> toCoords = toLane.getCoords();
					if (toCoords.size() < 2) continue;

					boolean adjustedLane = false;
					while (toCoords.size() > 2 && firstControlPointIsBehindCurrent(upstreamEnd, toCoords)) {
						toCoords.remove(0);
						skippedCount++;
						adjustedLane = true;
					}

					int scanLimit = Math.min(maxControlPointsToScan, toCoords.size() - 1);
					int guard = Math.max(1, toCoords.size() * 2);
					for (int attempts = 0; attempts < guard; attempts++) {
						boolean adjustedThisPass = false;
						for (int i = 0; i < scanLimit && i + 1 < toCoords.size(); i++) {
							Coordinate previous = (i == 0) ? upstreamEnd : toCoords.get(i - 1);
							Coordinate current = toCoords.get(i);
							Coordinate next = toCoords.get(i + 1);
							if (!hasSharpTransitionAngle(previous, current, next)) continue;

							if (i == 0 && transitionStartIsBehindUpstreamLane(upstreamPrev, upstreamEnd, current)) {
								Coordinate adjustedCurrent = adjustedControlPoint(upstreamEnd, current, next);
								if (setAdjustedControlPoint(toCoords, i, adjustedCurrent)) {
									adjustedCount++;
									adjustedLane = true;
									adjustedThisPass = true;
									break;
								}
							} else if (i + 2 < toCoords.size()) {
								Coordinate following = toCoords.get(i + 2);
								Coordinate adjustedNext = adjustedControlPoint(current, next, following);
								if (setAdjustedControlPoint(toCoords, i + 1, adjustedNext)) {
									adjustedCount++;
									adjustedLane = true;
									adjustedThisPass = true;
									break;
								}
							}
						}

						if (!adjustedThisPass) break;
						scanLimit = Math.min(maxControlPointsToScan, toCoords.size() - 1);
					}
					if (adjustedLane) {
						toLane.setCoords(toCoords);
					}
				}
			}

			if (adjustedCount > 0 || skippedCount > 0) {
				refreshRoadCenterlinesFromLanes();
				ContextCreator.logger.info("SUMO transition prescan skipped " + skippedCount
						+ " downstream lane control points behind upstream endpoints and adjusted " + adjustedCount
						+ " downstream lane control points with transition angles sharper than 150 degrees.");
			}
		}

		private Map<String, String> parseProjParameters(String proj4) {
			Map<String, String> result = new HashMap<String, String>();
			if(proj4 == null) {
				return result;
			}
			for(String token: proj4.trim().split("\\s+")) {
				if(token.length() == 0) {
					continue;
				}
				if(token.charAt(0) == '+') {
					token = token.substring(1);
				}
				int equalsIndex = token.indexOf('=');
				if(equalsIndex < 0) {
					result.put(token, "");
				}
				else {
					result.put(token.substring(0, equalsIndex), token.substring(equalsIndex + 1));
				}
			}
			return result;
		}

		private double getProjDouble(Map<String, String> projParams, String key, double defaultValue) {
			if(!projParams.containsKey(key) || projParams.get(key).length() == 0) {
				return defaultValue;
			}
			return Double.parseDouble(projParams.get(key));
		}

		private int getProjInt(Map<String, String> projParams, String key, int defaultValue) {
			if(!projParams.containsKey(key) || projParams.get(key).length() == 0) {
				return defaultValue;
			}
			return Integer.parseInt(projParams.get(key));
		}

		private MathTransform createTransverseMercatorTransform(String name, double centralMeridian,
				double latitudeOfOrigin, double scaleFactor, double falseEasting, double falseNorthing)
				throws FactoryException {
			MathTransformFactory mtFactory = ReferencingFactoryFinder.getMathTransformFactory(null);
			ReferencingFactoryContainer factories = new ReferencingFactoryContainer(null);
			GeographicCRS geoCRS = org.geotools.referencing.crs.DefaultGeographicCRS.WGS84;
			CartesianCS cartCS = org.geotools.referencing.cs.DefaultCartesianCS.GENERIC_2D;
			ParameterValueGroup parameters = mtFactory.getDefaultParameters("Transverse_Mercator");
			parameters.parameter("central_meridian").setValue(centralMeridian);
			parameters.parameter("latitude_of_origin").setValue(latitudeOfOrigin);
			parameters.parameter("scale_factor").setValue(scaleFactor);
			parameters.parameter("false_easting").setValue(falseEasting);
			parameters.parameter("false_northing").setValue(falseNorthing);
			Map<String, String> properties = Collections.singletonMap("name", name);
			ProjectedCRS sourceCRS = factories.createProjectedCRS(properties, geoCRS, null, parameters, cartCS);
			CoordinateReferenceSystem targetlatlong = CRS.decode("EPSG:4326", true);
			return CRS.findMathTransform(sourceCRS, targetlatlong, false);
		}

		private MathTransform createTransformFromProjParameters(String proj4) throws FactoryException {
			Map<String, String> projParams = parseProjParameters(proj4);
			String projection = projParams.get("proj");
			if("utm".equalsIgnoreCase(projection)) {
				int zoneNumber = getProjInt(projParams, "zone", 0);
				if(zoneNumber == 0) {
					throw new IllegalArgumentException("SUMO UTM projParameter is missing +zone: " + proj4);
				}
				double falseNorthing = projParams.containsKey("south") ? 10000000.0 : 0.0;
				return createTransverseMercatorTransform("WGS 84 / UTM Zone " + zoneNumber,
						zoneNumber * 6 - 183, 0.0, 0.9996, 500000.0, falseNorthing);
			}
			if("tmerc".equalsIgnoreCase(projection)) {
				double centralMeridian = getProjDouble(projParams, "lon_0", 0.0);
				double latitudeOfOrigin = getProjDouble(projParams, "lat_0", 0.0);
				double scaleFactor = projParams.containsKey("k") ? getProjDouble(projParams, "k", 1.0)
						: getProjDouble(projParams, "k_0", 1.0);
				double falseEasting = getProjDouble(projParams, "x_0", 0.0);
				double falseNorthing = getProjDouble(projParams, "y_0", 0.0);
				return createTransverseMercatorTransform("WGS 84 / Transverse Mercator",
						centralMeridian, latitudeOfOrigin, scaleFactor, falseEasting, falseNorthing);
			}
			return null;
		}
		
		@Override
		public void startDocument() {
			roads = new LinkedHashMap<Integer,Road>();
			junctions = new LinkedHashMap<Integer,Junction>();
			lanes = new LinkedHashMap<Integer,Lane>();
			
			roadConnections = new LinkedHashMap<Integer, List<List<Integer>>>();
			laneConnections = new LinkedHashMap<Integer, List<List<Integer>>>();
			
			internalFromLaneConnections = new LinkedHashMap<String, String>();
			internalToLaneConnections = new LinkedHashMap<String, String>();
			internalFromToLaneConnections = new LinkedHashMap<String, List<String>>();
			
			roadLane = new LinkedHashMap<Integer, List<Integer>>();
			signals = new LinkedHashMap<Integer, LinkedHashMap<Integer, Signal>>();
			
			roadIDMap = new LinkedHashMap<String, Integer>();
			laneIDMap = new LinkedHashMap<String, Integer>();
			signalIDMap = new LinkedHashMap<String, List<Signal>>();
			junctionIDMap = new LinkedHashMap<String, Integer>();
			
			incLaneJunctionMap = new LinkedHashMap<String, String>();
			intLaneJunctionMap = new LinkedHashMap<String, String>();
			junctionsRoadMap = new LinkedHashMap<String, Integer>();
			
			laneRoadMap = new LinkedHashMap<String, String>();
			isInternalRoadMap = new LinkedHashMap<String, Boolean>();
			isInternalLaneMap = new LinkedHashMap<String, Boolean>();
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
				if(proj4 != null) {
					try {
						transform = createTransformFromProjParameters(proj4);
					}
					catch(Exception e){
				         throw new RuntimeException(e);
				   }    
				}
				
				// Handle the case when proj is not provided, in this case, we assume this is Euclidean coordiate system
				if(transform == null) {
					try {
						ContextCreator.logger.warn("Did not find a valid projParameter in SUMO map file, used the default setting.");
						transform = createTransverseMercatorTransform("WGS 84 / Default",
								0.0, 0.0, 1.0, 0.0, 0.0);
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
						currentRoad.setOrigID(attributes.getValue("id"));
						currentFromJunctionID = attributes.getValue("from");
						currentToJunctionID = attributes.getValue("to");
						junctionsRoadMap.put(currentFromJunctionID+"!"+currentToJunctionID, road_id);
						ArrayList<Integer> oneRoadLane = new ArrayList<Integer>();
						roadLane.put(road_id, oneRoadLane);
						roads.put(road_id, currentRoad);
						startX = 0;
						startY = 0;
						startZ = 0;
						endX = 0;
						endY = 0;
						endZ = 0;
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
					    currentLane.setOrigID(attributes.getValue("id"));
					    currentLane.setRoad(currentRoad.getID());    
					    roadLane.get(currentRoad.getID()).add(lane_id);
					    // currentLane.setLength(Double.parseDouble(attributes.getValue("length")));
					    currentLane.setSpeed(Double.parseDouble(attributes.getValue("speed")));
					    currentMaxSpeed = Math.max(currentLane.getSpeed(), currentMaxSpeed);
					    // get coords
				    coords = new ArrayList<Coordinate>();
				    for(String one_coord: attributes.getValue("shape").split(" ")) {
				    	Coordinate coord = new Coordinate();
				    	String[] parts = one_coord.split(",");
				    	coord.x = Double.parseDouble(parts[0]) - x_offs;
				    	coord.y = Double.parseDouble(parts[1]) - y_offs;
				    	coord.z = (parts.length > 2) ? Double.parseDouble(parts[2]) : 0.0;
				    	try {
							JTS.transform(coord, coord, transform);
						} catch (TransformException e) {
							e.printStackTrace();
						}
				    	coords.add(coord);
					    }
					    currentLane.setCoords(coords);
					    
					    intLaneJunctionMap.put(attributes.getValue("id"), currentFromJunctionID);
					    incLaneJunctionMap.put(attributes.getValue("id"), currentToJunctionID);
					    
					    startX += coords.get(0).x;
					    startY += coords.get(0).y;
					    startZ += coords.get(0).z;
					    endX += coords.get(coords.size()-1).x;
					    endY += coords.get(coords.size()-1).y;
					    endZ += coords.get(coords.size()-1).z;
					    nLane++;
					    lanes.put(currentLane.getID(), currentLane);
					    currentRoad.addLane(currentLane, 0); // Add lane to the road, lane from the rightmost to the centeriod.
					}
				    
				}
				if(inInternalRoad) {
					laneRoadMap.put(attributes.getValue("id"), currentRoadID);
					isInternalLaneMap.put(attributes.getValue("id"), true);
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
		    	coord.z = (attributes.getValue("z") != null) ? Double.parseDouble(attributes.getValue("z")) : 0.0;
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
								if (!roadConnections.get(junction_id).contains(Arrays.asList(from_road_id,to_road_id))) {
									roadConnections.get(junction_id).add(Arrays.asList(from_road_id,to_road_id));
									if ((attributes.getValue("tl") != null) && signalIDMap.containsKey(attributes.getValue("tl"))) {
										// has signal control
										if(!signals.containsKey(from_road_id)) {
											signals.put(from_road_id, new LinkedHashMap<Integer, Signal>());
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
						// update the internal from-lane-connection
						internalFromLaneConnections.put(to_lane, from_lane);
					}
					else { // Case 4, from and to roads are internal
						// check whether from_lane is already used
						if(internalFromToLaneConnections.containsKey(from_lane)) {
							internalFromToLaneConnections.get(from_lane).add(to_lane);
						}
						else {
							// update the internal from-to-lane-connection
							ArrayList<String> to_lane_list = new ArrayList<String>();
							to_lane_list.add(to_lane);
							internalFromToLaneConnections.put(from_lane, to_lane_list);
						}
					}		
				}
				else { // Case 5, one of the road does not occur in the edge, do nothing
					
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
		    	coord1.z = startZ/nLane;
		    	coords.add(coord1);
		    	Coordinate coord2 = new Coordinate();
		    	coord2.x = endX/nLane;
		    	coord2.y = endY/nLane;
		    	coord2.z = endZ/nLane;
		    	coords.add(coord2);
		    	currentRoad.setRoadType(deduceRoadType(currentRoadType, currentMaxSpeed));
				currentRoad.setCoords(coords);
				currentRoad.setSpeedLimit(currentMaxSpeed);
				currentMaxSpeed = 0;
			    currentRoad = null;
			    currentRoadType = null;
			    currentFromJunctionID = null;
			    currentToJunctionID = null;
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
					Signal one_signal = new Signal(signalNum, currentSignal + "_" + i, Arrays.asList(green,yellow,red), start);
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
							if (!roadConnections.get(junction_id).contains(Arrays.asList(from_road_id,to_road_id))) {
								roadConnections.get(junction_id).add(Arrays.asList(from_road_id,to_road_id));
							}
							if (!laneConnections.get(junction_id).contains(Arrays.asList(from_lane_id, to_lane_id))) {
								laneConnections.get(junction_id).add(Arrays.asList(from_lane_id, to_lane_id));
							}
						}
						else {
							ContextCreator.logger.error("Postprocessing cannot find junction from lane: " + from_lane + " to: "+ to_lane);
						}
					}
					else if(internalFromToLaneConnections.containsKey(internal_lane)) {
						String from_lane = internalFromLaneConnections.get(internal_lane);
						List<String> to_lanes = this.findEndLane(internalFromToLaneConnections.get(internal_lane));
						
						for(String to_lane: to_lanes) {
							String from_road = from_lane.substring(0, from_lane.lastIndexOf("_")); 
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
								if (!roadConnections.get(junction_id).contains(Arrays.asList(from_road_id,to_road_id))) {
									roadConnections.get(junction_id).add(Arrays.asList(from_road_id,to_road_id));
								}
								if (!laneConnections.get(junction_id).contains(Arrays.asList(from_lane_id, to_lane_id))) {
									laneConnections.get(junction_id).add(Arrays.asList(from_lane_id, to_lane_id));
								}
							}
							else {
								ContextCreator.logger.error("Postprocessing cannot find junction from lane: " + from_lane + " to: "+ to_lane);
							}
						}
					}
				}
				
				// road connection
				for(List<List<Integer>> rcs: roadConnections.values()) {
					for(List<Integer> rc: rcs) {
						roads.get(rc.get(0)).addDownStreamRoad(rc.get(1));
					}
				}
				
				// Add U-turn if road has not downstream road
				for(String junctions: junctionsRoadMap.keySet()) {
					String junctions2 = junctions.split("!")[1] + "!" +junctions.split("!")[0];
					if(junctionsRoadMap.containsKey(junctions2)) {
						int r = junctionsRoadMap.get(junctions);
						int r2 = junctionsRoadMap.get(junctions2);
						if(roads.get(r2).getDownStreamRoads().size() == 0) {
							int junction_id = junctionIDMap.get(junctions2.split("!")[1]);
							// get the left most lane
							int to_road_id = r;
							int from_road_id = r2;
							int to_lane_id = roads.get(r).getLane(0).getID();
							int from_lane_id = roads.get(r2).getLane(0).getID();
							if (!roadConnections.containsKey(junction_id)) {
								roadConnections.put(junction_id, new ArrayList<List<Integer>>());
								laneConnections.put(junction_id, new ArrayList<List<Integer>>());
							}
							if (!roadConnections.get(junction_id).contains(Arrays.asList(from_road_id,to_road_id))) {
								roadConnections.get(junction_id).add(Arrays.asList(from_road_id,to_road_id));
							}
							if (!laneConnections.get(junction_id).contains(Arrays.asList(from_lane_id, to_lane_id))) {
								laneConnections.get(junction_id).add(Arrays.asList(from_lane_id, to_lane_id));
							}
							roads.get(r2).addDownStreamRoad(r);
						}
						if(roads.get(r).getDownStreamRoads().size() == 0) {
							int junction_id = junctionIDMap.get(junctions.split("!")[1]);
							int from_road_id = r;
							int to_road_id = r2;
							int from_lane_id = roads.get(r).getLane(0).getID();
							int to_lane_id = roads.get(r2).getLane(0).getID();
							if (!roadConnections.containsKey(junction_id)) {
								roadConnections.put(junction_id, new ArrayList<List<Integer>>());
								laneConnections.put(junction_id, new ArrayList<List<Integer>>());
							}
							if (!roadConnections.get(junction_id).contains(Arrays.asList(from_road_id,to_road_id))) {
								roadConnections.get(junction_id).add(Arrays.asList(from_road_id,to_road_id));
							}
							if (!laneConnections.get(junction_id).contains(Arrays.asList(from_lane_id, to_lane_id))) {
								laneConnections.get(junction_id).add(Arrays.asList(from_lane_id, to_lane_id));
							}
							roads.get(r).addDownStreamRoad(r2);
						}
					}
				}
				
				// lane connection
				for(List<List<Integer>> lcs: laneConnections.values()) {
					for(List<Integer> lc: lcs) {
						lanes.get(lc.get(0)).addDownStreamLane(lc.get(1));
						lanes.get(lc.get(1)).addUpStreamLane(lc.get(0));
					}
				}

				prescanTransitionControlPoints();
			}
		}
		
		private List<String> findEndLane(List<String> from_lanes){
			ArrayList<String> result = new ArrayList<String>();
			
			for(String intermediate_from_lane: from_lanes) {
				if(internalToLaneConnections.containsKey(intermediate_from_lane)) {
					result.add(internalToLaneConnections.get(intermediate_from_lane));
				}
				if(internalFromToLaneConnections.containsKey(intermediate_from_lane)) {
					List<String> to_lanes = findEndLane(internalFromToLaneConnections.get(intermediate_from_lane));
					for(String to_lane: to_lanes) {
						result.add(to_lane);
					}
				}
			}
			
			return result;
		}
	}
	
	public void print() {
		System.out.println("Finished loading, there are " + this.getRoad().size() + " roads, " + this.getLane().size()+
				" lanes, " + this.getJunction().size()+ " junctions");
	}
	
	public static void main(String[] args) {
		SumoXML sxml = new SumoXML("data/Birmingham/facility/road/birmingham.net.xml");
//		SumoXML sxml = new SumoXML("data/UA/facility/road/nema.net.xml");
//		SumoXML sxml = new SumoXML("data/study_region.net.xml");
//		SumoXML sxml = new SumoXML("data/IN/facility/road/indianametsr.net.xml");
//		SumoXML sxml = new SumoXML("data/CARLA/Town05/facility/road/Town05.net.xml");
		sxml.print();
	}
	
}
