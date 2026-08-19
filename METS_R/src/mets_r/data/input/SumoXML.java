package mets_r.data.input;

import org.xml.sax.helpers.DefaultHandler;

import mets_r.ContextCreator;
import mets_r.facility.Junction;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.facility.Signal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
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
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
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
	private static final double MIN_CONTROL_POINT_SEPARATION_METERS = 0.001;
	private static final double XODR_DIRECT_BOUNDARY_TOLERANCE_METERS = 0.25;
	private static final double XODR_CORRECTION_TOLERANCE_METERS = 0.25;
	private static final double XODR_CONNECTOR_LENGTH_METERS = 0.10;
	private static final double XODR_PROPOSAL_CONFLICT_TOLERANCE_METERS = 0.02;
	private static final double XODR_BOUNDARY_EPSILON_METERS = 1.0e-4;
	private static final double MIN_CONTROL_POINT_SEPARATION_SQUARED =
			MIN_CONTROL_POINT_SEPARATION_METERS * MIN_CONTROL_POINT_SEPARATION_METERS;

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
		LinkedHashMap<String, InternalLaneMovement> directViaMovements;
		LinkedHashMap<String, Double> internalLaneLengthMap;
		
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
		int removedCoincidentLaneControlPoints = 0;
		int zeroLengthLaneShapes = 0;
		
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

		private double geographicDistanceMeters(Coordinate c1, Coordinate c2) {
			double lat1 = Math.toRadians(c1.y);
			double lat2 = Math.toRadians(c2.y);
			double deltaLat = lat2 - lat1;
			double deltaLon = Math.toRadians(c2.x - c1.x);
			double sinLat = Math.sin(deltaLat / 2.0);
			double sinLon = Math.sin(deltaLon / 2.0);
			double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
			a = Math.max(0.0, Math.min(1.0, a));
			return 6371008.8 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
		}

		private ArrayList<Coordinate> cleanTransformedLaneControlPoints(
				ArrayList<Coordinate> source, String laneID) {
			ArrayList<Coordinate> cleaned = new ArrayList<Coordinate>();
			if (source.isEmpty()) return cleaned;

			cleaned.add(copyCoordinate(source.get(0)));
			for (int i = 1; i < source.size() - 1; i++) {
				Coordinate point = source.get(i);
				Coordinate previous = cleaned.get(cleaned.size() - 1);
				if (geographicDistanceMeters(previous, point)
						> MIN_CONTROL_POINT_SEPARATION_METERS) {
					cleaned.add(copyCoordinate(point));
				} else {
					rejectVerticalOnlyLaneSegment(previous, point, laneID);
				}
			}

			Coordinate finalPoint = source.get(source.size() - 1);
			while (cleaned.size() > 1
					&& geographicDistanceMeters(cleaned.get(cleaned.size() - 1), finalPoint)
							<= MIN_CONTROL_POINT_SEPARATION_METERS) {
				rejectVerticalOnlyLaneSegment(
						cleaned.get(cleaned.size() - 1), finalPoint, laneID);
				cleaned.remove(cleaned.size() - 1);
			}
			cleaned.add(copyCoordinate(finalPoint));
			return cleaned;
		}

		private boolean hasUsableLaneControlSegment(ArrayList<Coordinate> points) {
			for (int i = 0; i < points.size() - 1; i++) {
				if (geographicDistanceMeters(points.get(i), points.get(i + 1))
						> MIN_CONTROL_POINT_SEPARATION_METERS) {
					return true;
				}
			}
			return false;
		}

		private ArrayList<Coordinate> cleanLaneControlPoints(
				ArrayList<Coordinate> source, String laneID) {
			ArrayList<Coordinate> cleaned = new ArrayList<Coordinate>();
			for (Coordinate point : source) {
				if (!Double.isFinite(point.x) || !Double.isFinite(point.y)
						|| !Double.isFinite(point.z)) {
					throw new IllegalArgumentException(
							"SUMO lane " + laneID + " has a non-finite shape coordinate");
				}
			}
			if (source.isEmpty()) {
				throw new IllegalArgumentException("SUMO lane " + laneID + " has an empty shape");
			}

			cleaned.add(copyCoordinate(source.get(0)));
			for (int i = 1; i < source.size() - 1; i++) {
				Coordinate point = source.get(i);
				Coordinate previous = cleaned.get(cleaned.size() - 1);
				if (squaredDistance2D(previous, point)
						> MIN_CONTROL_POINT_SEPARATION_SQUARED) {
					cleaned.add(copyCoordinate(point));
				} else {
					rejectVerticalOnlyLaneSegment(previous, point, laneID);
				}
			}

			Coordinate finalPoint = source.get(source.size() - 1);
			while (cleaned.size() > 1
					&& squaredDistance2D(cleaned.get(cleaned.size() - 1), finalPoint)
							<= MIN_CONTROL_POINT_SEPARATION_SQUARED) {
				rejectVerticalOnlyLaneSegment(
						cleaned.get(cleaned.size() - 1), finalPoint, laneID);
				cleaned.remove(cleaned.size() - 1);
			}
			// Preserve the final anchor. When the entire shape is coincident this
			// intentionally leaves the two points required by JTS without
			// inventing a heading.
			cleaned.add(copyCoordinate(finalPoint));
			removedCoincidentLaneControlPoints += Math.max(0, source.size() - cleaned.size());
			if (cleaned.size() == 2
					&& squaredDistance2D(cleaned.get(0), cleaned.get(1))
							<= MIN_CONTROL_POINT_SEPARATION_SQUARED) {
				if (Math.abs(cleaned.get(0).z - cleaned.get(1).z)
						> MIN_CONTROL_POINT_SEPARATION_METERS) {
					throw new IllegalArgumentException("SUMO lane " + laneID
							+ " has vertical-only geometry with no usable bearing");
				}
				zeroLengthLaneShapes++;
			}
			return cleaned;
		}

		private void rejectVerticalOnlyLaneSegment(
				Coordinate first, Coordinate second, String laneID) {
			if (Math.abs(first.z - second.z) > MIN_CONTROL_POINT_SEPARATION_METERS) {
				throw new IllegalArgumentException("SUMO lane " + laneID
						+ " has vertical-only geometry with no usable bearing");
			}
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
					ArrayList<Coordinate> originalToCoords = toLane.getCoords();
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
						ArrayList<Coordinate> cleanedCandidate;
						try {
							cleanedCandidate = cleanTransformedLaneControlPoints(
									toCoords, toLane.getOrigID());
						} catch (IllegalArgumentException invalidCandidate) {
							ContextCreator.logger.warn("SUMO transition prescan discarded geometry "
									+ "for lane " + toLane.getOrigID() + ": "
									+ invalidCandidate.getMessage());
							continue;
						}
						if (hasUsableLaneControlSegment(cleanedCandidate)
								|| !hasUsableLaneControlSegment(originalToCoords)) {
							toLane.setCoords(cleanedCandidate);
						} 
//						else {
//							ContextCreator.logger.warn("SUMO transition prescan discarded a degenerate "
//									+ "geometry adjustment for lane " + toLane.getOrigID());
//						}
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


		private class OpenDriveMap {
			private final LinkedHashMap<Integer, Element> roads =
					new LinkedHashMap<Integer, Element>();

			OpenDriveMap(Document document) {
				for (Element road : directChildren(document.getDocumentElement(), "road")) {
					roads.put(Integer.parseInt(road.getAttribute("id")), road);
				}
			}

			boolean containsRoad(int roadID) {
				return roads.containsKey(roadID);
			}

			double roadLength(int roadID) {
				Element road = roads.get(roadID);
				return road == null ? Double.NaN
						: Double.parseDouble(road.getAttribute("length"));
			}

			ArrayList<Integer> laneIDsAt(int roadID, double s, int expectedSign) {
				ArrayList<Integer> result = new ArrayList<Integer>();
				Element road = roads.get(roadID);
				if (road == null) return result;
				Element lanesElement = directChild(road, "lanes");
				Element section = recordAt(
						directChildren(lanesElement, "laneSection"), s, "s");
				if (section == null) return result;
				Element side = directChild(section, expectedSign > 0 ? "left" : "right");
				if (side == null) return result;
				for (Element lane : directChildren(side, "lane")) {
					int laneID = Integer.parseInt(lane.getAttribute("id"));
					if (laneID * expectedSign > 0
						&& "driving".equalsIgnoreCase(lane.getAttribute("type"))) {
					result.add(laneID);
				}
				}
				Collections.sort(result);
				return result;
			}

			Coordinate laneCenter(int roadID, int laneID, double s) {
				Element road = roads.get(roadID);
				if (road == null) return null;
				double roadLength = Double.parseDouble(road.getAttribute("length"));
				s = Math.max(0.0, Math.min(s, roadLength));

				Element lanesElement = directChild(road, "lanes");
				Element section = recordAt(
						directChildren(lanesElement, "laneSection"), s, "s");
				if (section == null) return null;
				double sectionS = attributeDouble(section, "s", 0.0);
				double sectionOffset = s - sectionS;
				Element side = directChild(section, laneID > 0 ? "left" : "right");
				if (side == null) return null;

				Element targetLane = null;
				double innerWidth = 0.0;
				for (Element lane : directChildren(side, "lane")) {
					int candidateID = Integer.parseInt(lane.getAttribute("id"));
					if (candidateID == laneID) targetLane = lane;
					else if (candidateID * laneID > 0
							&& Math.abs(candidateID) < Math.abs(laneID)) {
						innerWidth += laneWidth(lane, sectionOffset);
					}
				}
				if (targetLane == null) return null;

				Element laneOffsetRecord = recordAt(
						directChildren(lanesElement, "laneOffset"), s, "s");
				double laneOffset = polynomialAt(
						laneOffsetRecord,
						laneOffsetRecord == null ? 0.0
								: s - attributeDouble(laneOffsetRecord, "s", 0.0));
				double targetWidth = laneWidth(targetLane, sectionOffset);
				double lateral = laneOffset + Math.copySign(
						innerWidth + targetWidth / 2.0, laneID);

				double[] reference = referencePose(road, s);
				Element elevationProfile = directChild(road, "elevationProfile");
				Element elevationRecord = recordAt(
						directChildren(elevationProfile, "elevation"), s, "s");
				double elevation = polynomialAt(
						elevationRecord,
						elevationRecord == null ? 0.0
								: s - attributeDouble(elevationRecord, "s", 0.0));
				Element lateralProfile = directChild(road, "lateralProfile");
				Element superelevationRecord = recordAt(
						directChildren(lateralProfile, "superelevation"), s, "s");
				double roll = polynomialAt(
						superelevationRecord,
						superelevationRecord == null ? 0.0
								: s - attributeDouble(superelevationRecord, "s", 0.0));
				double horizontalLateral = lateral * Math.cos(roll);
				return new Coordinate(
						reference[0] - Math.sin(reference[2]) * horizontalLateral,
						reference[1] + Math.cos(reference[2]) * horizontalLateral,
						elevation + lateral * Math.sin(roll));
			}

			private double laneWidth(Element lane, double sectionOffset) {
				Element width = recordAt(
						directChildren(lane, "width"), sectionOffset, "sOffset");
				if (width == null) return 0.0;
				return Math.max(0.0, polynomialAt(width,
						sectionOffset - attributeDouble(width, "sOffset", 0.0)));
			}

			private double[] referencePose(Element road, double s) {
				Element planView = directChild(road, "planView");
				Element geometry = recordAt(
						directChildren(planView, "geometry"), s, "s");
				if (geometry == null) {
					throw new IllegalArgumentException("OpenDRIVE road "
							+ road.getAttribute("id")
							+ " has no plan-view geometry at s=" + s);
				}
				double geometryS = attributeDouble(geometry, "s", 0.0);
				double length = attributeDouble(geometry, "length", 0.0);
				double delta = Math.max(0.0, Math.min(s - geometryS, length));
				double x = attributeDouble(geometry, "x", 0.0);
				double y = attributeDouble(geometry, "y", 0.0);
				double heading = attributeDouble(geometry, "hdg", 0.0);
				if (directChild(geometry, "line") != null) {
					return new double[] {
							x + delta * Math.cos(heading),
							y + delta * Math.sin(heading),
							heading
					};
				}
				Element arc = directChild(geometry, "arc");
				if (arc != null) {
					double curvature = attributeDouble(arc, "curvature", 0.0);
					if (Math.abs(curvature) <= 1.0e-15) {
						return new double[] {
								x + delta * Math.cos(heading),
								y + delta * Math.sin(heading),
								heading
						};
					}
					double endHeading = heading + curvature * delta;
					return new double[] {
							x + (Math.sin(endHeading) - Math.sin(heading)) / curvature,
							y - (Math.cos(endHeading) - Math.cos(heading)) / curvature,
							endHeading
					};
				}
				Element spiral = directChild(geometry, "spiral");
				if (spiral != null) {
					double curvatureStart = attributeDouble(
							spiral, "curvStart", 0.0);
					double curvatureEnd = attributeDouble(
							spiral, "curvEnd", curvatureStart);
					if (delta <= 0.0 || length <= 0.0) {
						return new double[] {x, y, heading};
					}
					double curvatureRate =
							(curvatureEnd - curvatureStart) / length;
					int steps = Math.max(8, (int) Math.ceil(delta / 0.5));
					if ((steps & 1) != 0) steps++;
					double step = delta / steps;
					double integralX = 0.0;
					double integralY = 0.0;
					for (int i = 0; i <= steps; i++) {
						double distance = i * step;
						double tangent = heading
								+ curvatureStart * distance
								+ 0.5 * curvatureRate * distance * distance;
						int weight = (i == 0 || i == steps)
								? 1 : ((i & 1) == 0 ? 2 : 4);
						integralX += weight * Math.cos(tangent);
						integralY += weight * Math.sin(tangent);
					}
					integralX *= step / 3.0;
					integralY *= step / 3.0;
					double endHeading = heading
							+ curvatureStart * delta
							+ 0.5 * curvatureRate * delta * delta;
					return new double[] {
							x + integralX, y + integralY, endHeading
					};
				}
				Element poly3 = directChild(geometry, "poly3");
				if (poly3 != null) {
					double localX = delta;
					double localY = polynomialAt(poly3, delta);
					double b = attributeDouble(poly3, "b", 0.0);
					double c = attributeDouble(poly3, "c", 0.0);
					double d = attributeDouble(poly3, "d", 0.0);
					double localHeading = Math.atan2(
							b + 2.0 * c * delta + 3.0 * d * delta * delta,
							1.0);
					return new double[] {
							x + localX * Math.cos(heading)
								- localY * Math.sin(heading),
							y + localX * Math.sin(heading)
								+ localY * Math.cos(heading),
							heading + localHeading
					};
				}
				Element paramPoly3 = directChild(geometry, "paramPoly3");
				if (paramPoly3 != null) {
					boolean normalized = !"arcLength".equalsIgnoreCase(
							paramPoly3.getAttribute("pRange"));
					double parameter = normalized && length > 0.0
							? delta / length : delta;
					double aU = attributeDouble(paramPoly3, "aU", 0.0);
					double bU = attributeDouble(paramPoly3, "bU", 0.0);
					double cU = attributeDouble(paramPoly3, "cU", 0.0);
					double dU = attributeDouble(paramPoly3, "dU", 0.0);
					double aV = attributeDouble(paramPoly3, "aV", 0.0);
					double bV = attributeDouble(paramPoly3, "bV", 0.0);
					double cV = attributeDouble(paramPoly3, "cV", 0.0);
					double dV = attributeDouble(paramPoly3, "dV", 0.0);
					double localX = aU + parameter
							* (bU + parameter * (cU + parameter * dU));
					double localY = aV + parameter
							* (bV + parameter * (cV + parameter * dV));
					double derivativeX = bU + 2.0 * cU * parameter
							+ 3.0 * dU * parameter * parameter;
					double derivativeY = bV + 2.0 * cV * parameter
							+ 3.0 * dV * parameter * parameter;
					double localHeading =
							Math.abs(derivativeX) + Math.abs(derivativeY) <= 1.0e-15
									? 0.0 : Math.atan2(derivativeY, derivativeX);
					return new double[] {
							x + localX * Math.cos(heading)
								- localY * Math.sin(heading),
							y + localX * Math.sin(heading)
								+ localY * Math.cos(heading),
							heading + localHeading
					};
				}
				Element primitive = firstElementChild(geometry);
				throw new IllegalArgumentException("Unsupported OpenDRIVE geometry "
						+ (primitive == null ? "missing" : primitive.getTagName())
						+ " on road " + road.getAttribute("id")
						+ "; automatic alignment supports line, arc, spiral, "
						+ "poly3, and paramPoly3");
			}
		}

		private class XodrLaneMatch {
			final Coordinate exactRaw;
			final Coordinate insideTransformed;
			final double oldErrorMeters;

			XodrLaneMatch(Coordinate exactRaw, Coordinate insideTransformed,
					double oldErrorMeters) {
				this.exactRaw = exactRaw;
				this.insideTransformed = insideTransformed;
				this.oldErrorMeters = oldErrorMeters;
			}
		}

		private class XodrCorrection {
			final Coordinate sourcePoint;
			final Coordinate targetPoint;
			final boolean needsCorrection;

			XodrCorrection(Coordinate sourcePoint, Coordinate targetPoint,
					boolean needsCorrection) {
				this.sourcePoint = sourcePoint;
				this.targetPoint = targetPoint;
				this.needsCorrection = needsCorrection;
			}
		}

		private class InternalLaneMovement {
			final String sourceLaneID;
			final String targetLaneID;
			final String internalLaneID;
			XodrCorrection correction;
			boolean direct;

			InternalLaneMovement(String sourceLaneID, String targetLaneID,
					String internalLaneID) {
				this.sourceLaneID = sourceLaneID;
				this.targetLaneID = targetLaneID;
				this.internalLaneID = internalLaneID;
			}
		}

		private ArrayList<Element> directChildren(Element parent, String tagName) {
			ArrayList<Element> result = new ArrayList<Element>();
			if (parent == null) return result;
			NodeList children = parent.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				Node node = children.item(i);
				if (node.getNodeType() == Node.ELEMENT_NODE
						&& (tagName == null || tagName.equals(node.getNodeName()))) {
					result.add((Element) node);
				}
			}
			return result;
		}

		private Element directChild(Element parent, String tagName) {
			ArrayList<Element> children = directChildren(parent, tagName);
			return children.isEmpty() ? null : children.get(0);
		}

		private Element firstElementChild(Element parent) {
			ArrayList<Element> children = directChildren(parent, null);
			return children.isEmpty() ? null : children.get(0);
		}

		private double attributeDouble(Element element, String name,
				double defaultValue) {
			if (element == null || !element.hasAttribute(name)
					|| element.getAttribute(name).length() == 0) {
				return defaultValue;
			}
			return Double.parseDouble(element.getAttribute(name));
		}

		private Element recordAt(List<Element> records, double position,
				String positionAttribute) {
			Element selected = null;
			double selectedPosition = -Double.MAX_VALUE;
			for (Element record : records) {
				double recordPosition = attributeDouble(
						record, positionAttribute, 0.0);
				if (recordPosition <= position + 1.0e-9
						&& recordPosition >= selectedPosition) {
					selected = record;
					selectedPosition = recordPosition;
				}
			}
			return selected;
		}

		private double polynomialAt(Element record, double delta) {
			if (record == null) return 0.0;
			double a = attributeDouble(record, "a", 0.0);
			double b = attributeDouble(record, "b", 0.0);
			double c = attributeDouble(record, "c", 0.0);
			double d = attributeDouble(record, "d", 0.0);
			return a + delta * (b + delta * (c + delta * d));
		}

		private File findCompanionOpenDriveFile() {
			File networkFile = new File(SumoXML.this.xml_file).getAbsoluteFile();
			File directory = networkFile.getParentFile();
			if (directory == null || !directory.isDirectory()) return null;
			String networkName = networkFile.getName();
			String lowerName = networkName.toLowerCase(Locale.ROOT);
			String expectedName;
			if (lowerName.endsWith(".net.xml")) {
				expectedName = networkName.substring(
						0, networkName.length() - ".net.xml".length()) + ".xodr";
			} else {
				int extension = networkName.lastIndexOf('.');
				expectedName = (extension < 0
						? networkName : networkName.substring(0, extension)) + ".xodr";
			}

			ArrayList<File> candidates = new ArrayList<File>();
			File exact = null;
			File[] siblings = directory.listFiles();
			if (siblings == null) return null;
			for (File sibling : siblings) {
				if (!sibling.isFile()
						|| !sibling.getName().toLowerCase(Locale.ROOT).endsWith(".xodr")) {
					continue;
				}
				candidates.add(sibling);
				if (sibling.getName().equalsIgnoreCase(expectedName)) exact = sibling;
			}
			if (exact != null) return exact;
			if (candidates.size() == 1) return candidates.get(0);
			if (candidates.isEmpty()) return null;
			StringBuilder names = new StringBuilder();
			for (File candidate : candidates) {
				if (names.length() > 0) names.append(", ");
				names.append(candidate.getName());
			}
			throw new IllegalArgumentException("Cannot choose an OpenDRIVE companion for "
					+ networkFile + ": found " + names
					+ ". Rename the matching file to share the SUMO basename.");
		}

		private OpenDriveMap loadOpenDrive(File xodrFile) throws Exception {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(
					"http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature(
					"http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature(
					"http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature(
					"http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			return new OpenDriveMap(builder.parse(xodrFile));
		}

		private Coordinate transformOpenDriveCoordinate(
				Coordinate raw, String description) {
			Coordinate transformed = copyCoordinate(raw);
			try {
				JTS.transform(raw, transformed, transform);
			} catch (TransformException e) {
				throw new IllegalArgumentException(
						"Failed to transform OpenDRIVE " + description, e);
			}
			if (!Double.isFinite(transformed.x)
					|| !Double.isFinite(transformed.y)
					|| !Double.isFinite(transformed.z)) {
				throw new IllegalArgumentException(
						"OpenDRIVE " + description
						+ " transformed to a non-finite coordinate");
			}
			return transformed;
		}

		private double rawDistance2D(Coordinate first, Coordinate second) {
			return Math.hypot(first.x - second.x, first.y - second.y);
		}

		private double boundaryS(double length, boolean negative, boolean source) {
			if (source) {
				return negative ? length - XODR_BOUNDARY_EPSILON_METERS
						: XODR_BOUNDARY_EPSILON_METERS;
			}
			return negative ? XODR_BOUNDARY_EPSILON_METERS
					: length - XODR_BOUNDARY_EPSILON_METERS;
		}

		private double insideS(double length, boolean negative, boolean source) {
			double halfSpan = Math.min(XODR_CONNECTOR_LENGTH_METERS / 2.0,
					Math.max(XODR_BOUNDARY_EPSILON_METERS, length / 4.0));
			if (source) return negative ? length - halfSpan : halfSpan;
			return negative ? halfSpan : length - halfSpan;
		}

		private XodrLaneMatch matchOpenDriveBoundary(
				OpenDriveMap xodr, Lane sumoLane, int roadID,
				boolean negative, boolean source) {
			double length = xodr.roadLength(roadID);
			if (!Double.isFinite(length)) return null;
			int expectedSign = negative ? -1 : 1;
			double boundaryS = boundaryS(length, negative, source);
			Coordinate oldPoint = source
					? sumoLane.getEndCoord() : sumoLane.getStartCoord();
			XodrLaneMatch best = null;
			int bestLaneID = 0;
			for (int laneID : xodr.laneIDsAt(roadID, boundaryS, expectedSign)) {
				Coordinate exactRaw = xodr.laneCenter(roadID, laneID, boundaryS);
				if (exactRaw == null) continue;
				Coordinate exactTransformed = transformOpenDriveCoordinate(
						exactRaw, "road " + roadID + " lane " + laneID + " boundary");
				double error = geographicDistanceMeters(oldPoint, exactTransformed);
				if (best == null || error < best.oldErrorMeters
						|| (error == best.oldErrorMeters && laneID < bestLaneID)) {
					Coordinate insideRaw = xodr.laneCenter(
							roadID, laneID, insideS(length, negative, source));
					if (insideRaw == null) continue;
					Coordinate insideTransformed = transformOpenDriveCoordinate(
							insideRaw, "road " + roadID + " lane " + laneID + " interior");
					best = new XodrLaneMatch(
							copyCoordinate(exactRaw), insideTransformed, error);
					bestLaneID = laneID;
				}
			}
			return best;
		}

		private Integer numericOpenDriveRoadID(String sumoRoadID) {
			if (sumoRoadID == null || !sumoRoadID.matches("-?\\d+")) return null;
			try {
				return Math.abs(Integer.parseInt(sumoRoadID));
			} catch (NumberFormatException e) {
				return null;
			}
		}

		private XodrCorrection evaluateOpenDriveMovement(
				OpenDriveMap xodr, InternalLaneMovement movement,
				Map<String, XodrLaneMatch> sourceMatchCache,
				Map<String, XodrLaneMatch> targetMatchCache,
				Set<String> missingSourceMatches,
				Set<String> missingTargetMatches) {
			String sourceRoadName = laneRoadMap.get(movement.sourceLaneID);
			String targetRoadName = laneRoadMap.get(movement.targetLaneID);
			Integer sourceRoadID = numericOpenDriveRoadID(sourceRoadName);
			Integer targetRoadID = numericOpenDriveRoadID(targetRoadName);
			if (sourceRoadID == null || targetRoadID == null
					|| !xodr.containsRoad(sourceRoadID)
					|| !xodr.containsRoad(targetRoadID)
					|| !laneIDMap.containsKey(movement.sourceLaneID)
					|| !laneIDMap.containsKey(movement.targetLaneID)) {
				return null;
			}
			Lane sourceLane = lanes.get(laneIDMap.get(movement.sourceLaneID));
			Lane targetLane = lanes.get(laneIDMap.get(movement.targetLaneID));
			if (sourceLane == null || targetLane == null) return null;

			XodrLaneMatch sourceMatch = sourceMatchCache.get(movement.sourceLaneID);
			if (sourceMatch == null
					&& !missingSourceMatches.contains(movement.sourceLaneID)) {
				sourceMatch = matchOpenDriveBoundary(
						xodr, sourceLane, sourceRoadID,
						sourceRoadName.startsWith("-"), true);
				if (sourceMatch == null) {
					missingSourceMatches.add(movement.sourceLaneID);
				} else {
					sourceMatchCache.put(movement.sourceLaneID, sourceMatch);
				}
			}
			XodrLaneMatch targetMatch = targetMatchCache.get(movement.targetLaneID);
			if (targetMatch == null
					&& !missingTargetMatches.contains(movement.targetLaneID)) {
				targetMatch = matchOpenDriveBoundary(
						xodr, targetLane, targetRoadID,
						targetRoadName.startsWith("-"), false);
				if (targetMatch == null) {
					missingTargetMatches.add(movement.targetLaneID);
				} else {
					targetMatchCache.put(movement.targetLaneID, targetMatch);
				}
			}
			if (sourceMatch == null || targetMatch == null
					|| rawDistance2D(sourceMatch.exactRaw, targetMatch.exactRaw)
							> XODR_DIRECT_BOUNDARY_TOLERANCE_METERS) {
				return null;
			}
			double connectorLength = internalLaneLengthMap.containsKey(
					movement.internalLaneID)
							? internalLaneLengthMap.get(movement.internalLaneID) : 0.0;
			boolean needsCorrection = Math.max(connectorLength,
					Math.max(sourceMatch.oldErrorMeters, targetMatch.oldErrorMeters))
							> XODR_CORRECTION_TOLERANCE_METERS;
			return new XodrCorrection(
					sourceMatch.insideTransformed,
					targetMatch.insideTransformed,
					needsCorrection);
		}

		private ArrayList<InternalLaneMovement> internalLaneMovements() {
			LinkedHashMap<String, InternalLaneMovement> movements =
					new LinkedHashMap<String, InternalLaneMovement>();
			movements.putAll(directViaMovements);
			for (String internalLane : internalFromLaneConnections.keySet()) {
				String sourceLane = internalFromLaneConnections.get(internalLane);
				ArrayList<String> targetLanes = new ArrayList<String>();
				if (internalToLaneConnections.containsKey(internalLane)) {
					targetLanes.add(internalToLaneConnections.get(internalLane));
				} else if (internalFromToLaneConnections.containsKey(internalLane)) {
					targetLanes.addAll(findEndLane(
							internalFromToLaneConnections.get(internalLane)));
				}
				for (String targetLane : targetLanes) {
					String key = sourceLane + "\u0000" + targetLane;
					if (!movements.containsKey(key)) {
						movements.put(key, new InternalLaneMovement(
								sourceLane, targetLane, internalLane));
					}
				}
			}
			return new ArrayList<InternalLaneMovement>(movements.values());
		}

		private void proposeLanePoint(Map<String, Coordinate> proposals,
				Set<String> conflicts, String laneID, Coordinate point) {
			Coordinate existing = proposals.get(laneID);
			if (existing != null
					&& geographicDistanceMeters(existing, point)
							> XODR_PROPOSAL_CONFLICT_TOLERANCE_METERS) {
				conflicts.add(laneID);
				return;
			}
			if (existing == null) proposals.put(laneID, copyCoordinate(point));
		}

		private void replaceLaneEndpoint(
				String laneOrigID, Coordinate point, boolean sourceEndpoint) {
			Integer laneID = laneIDMap.get(laneOrigID);
			Lane lane = laneID == null ? null : lanes.get(laneID);
			if (lane == null) {
				throw new IllegalArgumentException(
						"Cannot align missing SUMO lane " + laneOrigID);
			}
			ArrayList<Coordinate> laneCoords = lane.getCoords();
			if (laneCoords.isEmpty()) {
				throw new IllegalArgumentException(
						"Cannot align empty SUMO lane " + laneOrigID);
			}
			int index = sourceEndpoint ? laneCoords.size() - 1 : 0;
			laneCoords.set(index, copyCoordinate(point));
			ArrayList<Coordinate> cleaned = cleanTransformedLaneControlPoints(
					laneCoords, laneOrigID);
			if (cleaned.size() < 2 || !hasUsableLaneControlSegment(cleaned)) {
				throw new IllegalArgumentException(
						"XODR alignment made SUMO lane " + laneOrigID
						+ " geometrically degenerate");
			}
			lane.setCoords(cleaned);
		}

		private void alignLaneGeometryWithCompanionOpenDrive() {
			File xodrFile = findCompanionOpenDriveFile();
			if (xodrFile == null) return;
			try {
				OpenDriveMap xodr = loadOpenDrive(xodrFile);
				ArrayList<InternalLaneMovement> movements = internalLaneMovements();
				LinkedHashMap<String, Coordinate> sourceProposals =
						new LinkedHashMap<String, Coordinate>();
				LinkedHashMap<String, Coordinate> targetProposals =
						new LinkedHashMap<String, Coordinate>();
				Set<String> unsafeSources = new HashSet<String>();
				Set<String> unsafeTargets = new HashSet<String>();
				Map<String, XodrLaneMatch> sourceMatchCache =
						new HashMap<String, XodrLaneMatch>();
				Map<String, XodrLaneMatch> targetMatchCache =
						new HashMap<String, XodrLaneMatch>();
				Set<String> missingSourceMatches = new HashSet<String>();
				Set<String> missingTargetMatches = new HashSet<String>();
				int directMovements = 0;

				for (InternalLaneMovement movement : movements) {
					movement.correction = evaluateOpenDriveMovement(
							xodr, movement, sourceMatchCache, targetMatchCache,
							missingSourceMatches, missingTargetMatches);
					movement.direct = movement.correction != null;
					if (!movement.direct) continue;
					directMovements++;
					proposeLanePoint(sourceProposals, unsafeSources,
							movement.sourceLaneID, movement.correction.sourcePoint);
					proposeLanePoint(targetProposals, unsafeTargets,
							movement.targetLaneID, movement.correction.targetPoint);
				}
				for (InternalLaneMovement movement : movements) {
					if (movement.direct) continue;
					if (sourceProposals.containsKey(movement.sourceLaneID)) {
						unsafeSources.add(movement.sourceLaneID);
					}
					if (targetProposals.containsKey(movement.targetLaneID)) {
						unsafeTargets.add(movement.targetLaneID);
					}
				}

				LinkedHashMap<String, Coordinate> activeSources =
						new LinkedHashMap<String, Coordinate>();
				LinkedHashMap<String, Coordinate> activeTargets =
						new LinkedHashMap<String, Coordinate>();
				int correctedMovements = 0;
				for (InternalLaneMovement movement : movements) {
					if (!movement.direct || !movement.correction.needsCorrection
							|| unsafeSources.contains(movement.sourceLaneID)
							|| unsafeTargets.contains(movement.targetLaneID)) {
						continue;
					}
					correctedMovements++;
					activeSources.put(movement.sourceLaneID,
							movement.correction.sourcePoint);
					activeTargets.put(movement.targetLaneID,
							movement.correction.targetPoint);
				}
				for (Map.Entry<String, Coordinate> entry : activeSources.entrySet()) {
					replaceLaneEndpoint(entry.getKey(), entry.getValue(), true);
				}
				for (Map.Entry<String, Coordinate> entry : activeTargets.entrySet()) {
					replaceLaneEndpoint(entry.getKey(), entry.getValue(), false);
				}
				if (!activeSources.isEmpty() || !activeTargets.isEmpty()) {
					refreshRoadCenterlinesFromLanes();
				}
				System.out.println("Automatic XODR alignment used "
						+ xodrFile.getAbsolutePath() + ": internal movements="
						+ movements.size() + ", direct=" + directMovements
						+ ", corrected=" + correctedMovements
						+ ", source lanes=" + activeSources.size()
						+ ", target lanes=" + activeTargets.size() + ".");
			} catch (Exception e) {
				throw new IllegalArgumentException(
						"Failed to align SUMO network " + SumoXML.this.xml_file
						+ " with companion OpenDRIVE map "
						+ xodrFile.getAbsolutePath(), e);
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
			@SuppressWarnings("deprecation")
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
			internalLaneLengthMap = new LinkedHashMap<String, Double>();
			directViaMovements = new LinkedHashMap<String, InternalLaneMovement>();
			
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
			removedCoincidentLaneControlPoints = 0;
			zeroLengthLaneShapes = 0;
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
				    String laneShape = attributes.getValue("shape");
				    for(String one_coord: laneShape.trim().split(" +")) {
				    	Coordinate coord = new Coordinate();
				    	String[] parts = one_coord.split(",");
				    	coord.x = Double.parseDouble(parts[0]) - x_offs;
				    	coord.y = Double.parseDouble(parts[1]) - y_offs;
				    	coord.z = (parts.length > 2) ? Double.parseDouble(parts[2]) : 0.0;
						coords.add(coord);
				    }
				    coords = cleanLaneControlPoints(coords, attributes.getValue("id"));
				    for (Coordinate coord : coords) {
				    	try {
							JTS.transform(coord, coord, transform);
						} catch (TransformException e) {
							throw new IllegalArgumentException(
									"Failed to transform SUMO lane " + attributes.getValue("id"), e);
						}
						if (!Double.isFinite(coord.x) || !Double.isFinite(coord.y)
								|| !Double.isFinite(coord.z)) {
							throw new IllegalArgumentException("SUMO lane " + attributes.getValue("id")
									+ " transformed to a non-finite coordinate");
						}
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
					String internalLength = attributes.getValue("length");
					if (internalLength != null) {
						internalLaneLengthMap.put(attributes.getValue("id"),
								Double.parseDouble(internalLength));
					}
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
						String viaLane = attributes.getValue("via");
						if (viaLane != null
								&& Boolean.TRUE.equals(isInternalLaneMap.get(viaLane))) {
							String movementKey = from_lane + "\u0000" + to_lane;
							directViaMovements.put(movementKey,
									new InternalLaneMovement(
											from_lane, to_lane, viaLane));
						}
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

				alignLaneGeometryWithCompanionOpenDrive();
				prescanTransitionControlPoints();
				if (removedCoincidentLaneControlPoints > 0 || zeroLengthLaneShapes > 0) {
					ContextCreator.logger.info("SUMO lane control-point cleanup removed "
							+ removedCoincidentLaneControlPoints + " coincident points and retained "
							+ zeroLengthLaneShapes
							+ " irreducible zero-length lane shapes as degenerate two-point lines.");
				}
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
//		SumoXML sxml = new SumoXML("data/Birmingham/facility/road/birmingham.net.xml");
//		SumoXML sxml = new SumoXML("data/UA/facility/road/nema.net.xml");
//		SumoXML sxml = new SumoXML("data/study_region.net.xml");
//		SumoXML sxml = new SumoXML("data/IN/facility/road/indianametsr.net.xml");
		SumoXML sxml = new SumoXML("data/CARLA/Town05/facility/road/Town05.net.xml");
		sxml.print();
	}
	
}

