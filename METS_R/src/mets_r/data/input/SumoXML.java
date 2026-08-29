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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	private static final double MIN_CONTROL_POINT_SEPARATION_METERS = 0.001;
	private static final double MIN_CONTROL_POINT_SEPARATION_SQUARED =
			MIN_CONTROL_POINT_SEPARATION_METERS * MIN_CONTROL_POINT_SEPARATION_METERS;
	private static final double MIN_LOGICAL_LENGTH_OVERRIDE_METERS = 5.0;
	private static final double MIN_LOGICAL_LENGTH_DIFFERENCE_METERS = 5.0;
	private static final double MAX_GEOMETRIC_TO_LOGICAL_LENGTH_RATIO = 0.25;

	private static long connectorPathKey(int sourceRoadID, int targetRoadID) {
		return ((long) sourceRoadID << 32) ^ (targetRoadID & 0xffffffffL);
	}

	public double x_offs = 0;
	public double y_offs = 0;
	public ArrayList<Double> boundary;
	public String xml_file = "";
	public Document xml_doc;
	
	public SumoXMLHandler handler;
	public MathTransform transform;
	
	public static SumoXML data = null;

	private static ArrayList<Coordinate> copyCoordinates(List<Coordinate> source) {
		ArrayList<Coordinate> result = new ArrayList<Coordinate>();
		if (source == null) return result;
		for (Coordinate coordinate : source) {
			if (coordinate == null) continue;
			result.add(new Coordinate(coordinate.x, coordinate.y,
					Double.isNaN(coordinate.z) ? 0.0 : coordinate.z));
		}
		return result;
	}

	/** One SUMO lane belonging to an {@code edge function="internal"}. */
	public static final class InternalLaneData {
		private final String edgeID;
		private final String laneID;
		private final double declaredLength;
		private final double speed;
		private final List<Coordinate> shape;

		InternalLaneData(String edgeID, String laneID, double declaredLength,
				double speed, List<Coordinate> shape) {
			this.edgeID = edgeID;
			this.laneID = laneID;
			this.declaredLength = declaredLength;
			this.speed = speed;
			this.shape = Collections.unmodifiableList(copyCoordinates(shape));
		}

		public String getEdgeID() { return this.edgeID; }
		public String getLaneID() { return this.laneID; }
		public double getDeclaredLength() { return this.declaredLength; }
		public double getSpeed() { return this.speed; }
		public List<Coordinate> getShape() {
			return Collections.unmodifiableList(copyCoordinates(this.shape));
		}
	}

	/**
	 * Explicit lane-to-lane connector definition recovered from a SUMO
	 * {@code connection} and its internal {@code via} lane chain.
	 */
	public static final class ConnectorPathData {
		private final int sourceRoadID;
		private final int targetRoadID;
		private final int sourceLaneID;
		private final int targetLaneID;
		private final int junctionID;
		private final String sourceRoadOrigID;
		private final String targetRoadOrigID;
		private final String sourceLaneOrigID;
		private final String targetLaneOrigID;
		private final List<String> viaLaneIDs;
		private final List<String> internalEdgeIDs;
		private final List<Coordinate> centerLine;
		private final Map<String, String> parameters;
		private final String direction;
		private final String state;
		private final String trafficLightID;
		private final Integer linkIndex;
		private final double declaredLength;
		private final double speed;
		private final boolean explicitGeometry;

		ConnectorPathData(int sourceRoadID, int targetRoadID, int sourceLaneID,
				int targetLaneID, int junctionID, String sourceRoadOrigID,
				String targetRoadOrigID, String sourceLaneOrigID,
				String targetLaneOrigID, List<String> viaLaneIDs,
				List<String> internalEdgeIDs,
				List<Coordinate> centerLine, Map<String, String> parameters,
				String direction, String state, String trafficLightID,
				Integer linkIndex, double declaredLength, double speed,
				boolean explicitGeometry) {
			this.sourceRoadID = sourceRoadID;
			this.targetRoadID = targetRoadID;
			this.sourceLaneID = sourceLaneID;
			this.targetLaneID = targetLaneID;
			this.junctionID = junctionID;
			this.sourceRoadOrigID = sourceRoadOrigID;
			this.targetRoadOrigID = targetRoadOrigID;
			this.sourceLaneOrigID = sourceLaneOrigID;
			this.targetLaneOrigID = targetLaneOrigID;
			this.viaLaneIDs = Collections.unmodifiableList(
					new ArrayList<String>(viaLaneIDs));
			this.internalEdgeIDs = Collections.unmodifiableList(
					new ArrayList<String>(internalEdgeIDs));
			this.centerLine = Collections.unmodifiableList(copyCoordinates(centerLine));
			this.parameters = Collections.unmodifiableMap(
					new LinkedHashMap<String, String>(parameters));
			this.direction = direction;
			this.state = state;
			this.trafficLightID = trafficLightID;
			this.linkIndex = linkIndex;
			this.declaredLength = declaredLength;
			this.speed = speed;
			this.explicitGeometry = explicitGeometry;
		}

		public int getSourceRoadID() { return this.sourceRoadID; }
		public int getTargetRoadID() { return this.targetRoadID; }
		public int getSourceLaneID() { return this.sourceLaneID; }
		public int getTargetLaneID() { return this.targetLaneID; }
		public int getJunctionID() { return this.junctionID; }
		public String getSourceRoadOrigID() { return this.sourceRoadOrigID; }
		public String getTargetRoadOrigID() { return this.targetRoadOrigID; }
		public String getSourceLaneOrigID() { return this.sourceLaneOrigID; }
		public String getTargetLaneOrigID() { return this.targetLaneOrigID; }
		public List<String> getViaLaneIDs() { return this.viaLaneIDs; }
		public List<String> getInternalEdgeIDs() { return this.internalEdgeIDs; }
		public List<Coordinate> getCenterLine() {
			return Collections.unmodifiableList(copyCoordinates(this.centerLine));
		}
		public Map<String, String> getParameters() { return this.parameters; }
		public String getParameter(String key) { return this.parameters.get(key); }
		public String getDirection() { return this.direction; }
		public String getState() { return this.state; }
		public String getTrafficLightID() { return this.trafficLightID; }
		public Integer getLinkIndex() { return this.linkIndex; }
		public double getDeclaredLength() { return this.declaredLength; }
		public double getSpeed() { return this.speed; }
		public boolean hasExplicitGeometry() { return this.explicitGeometry; }
	}
	
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

	public Map<String, InternalLaneData> getInternalLanes() {
		return this.handler.getInternalLanes();
	}

	public List<ConnectorPathData> getConnectorPaths() {
		return this.handler.getConnectorPaths();
	}

	public List<ConnectorPathData> getConnectorPaths(int sourceRoadID,
			int targetRoadID) {
		return this.handler.getConnectorPaths(sourceRoadID, targetRoadID);
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
		LinkedHashMap<String, InternalLaneData> internalLanes;
		ArrayList<RawConnection> rawConnections;
		ArrayList<ConnectorPathData> connectorPaths;
		Map<Long, List<ConnectorPathData>> connectorPathsByMovement;
		RawConnection currentConnection;
		
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
		int logicalLaneLengthOverrides = 0;
		
		public LinkedHashMap<Integer,Road> getRoad() {return roads;};
		public LinkedHashMap<Integer,Junction> getJunction() {return junctions;};
		public LinkedHashMap<Integer,Lane> getLane() {return lanes;};
		public LinkedHashMap<Integer, LinkedHashMap<Integer, Signal>> getSignal() {return signals;}
		public LinkedHashMap<Integer, List<List<Integer>>>  getRoadConnection() {return roadConnections;}
		public List<List<Integer>>  getRoadConnection(int junction_id) {return roadConnections.get(junction_id);}
		public Map<String, InternalLaneData> getInternalLanes() {
			return Collections.unmodifiableMap(
					new LinkedHashMap<String, InternalLaneData>(internalLanes));
		}
		public List<ConnectorPathData> getConnectorPaths() {
			return Collections.unmodifiableList(
					new ArrayList<ConnectorPathData>(connectorPaths));
		}
		public List<ConnectorPathData> getConnectorPaths(int sourceRoadID,
				int targetRoadID) {
			List<ConnectorPathData> paths = connectorPathsByMovement.get(
					connectorPathKey(sourceRoadID, targetRoadID));
			return paths == null ? Collections.<ConnectorPathData>emptyList() : paths;
		}

		private class RawConnection {
			final String fromRoadID;
			final String toRoadID;
			final String fromLaneID;
			final String toLaneID;
			final String viaLaneID;
			final String direction;
			final String state;
			final String trafficLightID;
			final Integer linkIndex;
			final LinkedHashMap<String, String> parameters =
					new LinkedHashMap<String, String>();

			RawConnection(Attributes attributes) {
				this.fromRoadID = attributes.getValue("from");
				this.toRoadID = attributes.getValue("to");
				this.fromLaneID = this.fromRoadID + "_" + attributes.getValue("fromLane");
				this.toLaneID = this.toRoadID + "_" + attributes.getValue("toLane");
				this.viaLaneID = attributes.getValue("via");
				this.direction = attributes.getValue("dir");
				this.state = attributes.getValue("state");
				this.trafficLightID = attributes.getValue("tl");
				String rawLinkIndex = attributes.getValue("linkIndex");
				this.linkIndex = rawLinkIndex == null || rawLinkIndex.trim().isEmpty()
						? null : Integer.valueOf(rawLinkIndex);
			}
		}
		
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
			if (junctionType == null) return Junction.NoControl;
			switch(junctionType) {
				case "traffic_light":
					return Junction.StaticSignal;
				case "rail_crossing":
					return Junction.StopSign;
				case "priority":
					return Junction.Priority;
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

		private double planarPolylineLength(ArrayList<Coordinate> points) {
			double length = 0.0;
			for (int i = 1; i < points.size(); i++) {
				Coordinate first = points.get(i - 1);
				Coordinate second = points.get(i);
				length += Math.hypot(second.x - first.x, second.y - first.y);
			}
			return length;
		}

		private boolean isSignificantLogicalLengthOverride(
				double declaredLength, ArrayList<Coordinate> points) {
			if (!Double.isFinite(declaredLength)
					|| declaredLength < MIN_LOGICAL_LENGTH_OVERRIDE_METERS) {
				return false;
			}
			double geometricLength = planarPolylineLength(points);
			return Double.isFinite(geometricLength)
					&& declaredLength - geometricLength
							>= MIN_LOGICAL_LENGTH_DIFFERENCE_METERS
					&& geometricLength
							<= MAX_GEOMETRIC_TO_LOGICAL_LENGTH_RATIO * declaredLength;
		}

		private void rejectVerticalOnlyLaneSegment(
				Coordinate first, Coordinate second, String laneID) {
			if (Math.abs(first.z - second.z) > MIN_CONTROL_POINT_SEPARATION_METERS) {
				throw new IllegalArgumentException("SUMO lane " + laneID
						+ " has vertical-only geometry with no usable bearing");
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

		private double attributeDouble(Attributes attributes, String name,
				double defaultValue) {
			String value = attributes.getValue(name);
			return value == null || value.trim().isEmpty()
					? defaultValue : Double.parseDouble(value);
		}

		private ArrayList<Coordinate> parseInternalLaneShape(Attributes attributes) {
			ArrayList<Coordinate> parsed = new ArrayList<Coordinate>();
			String laneShape = attributes.getValue("shape");
			if (laneShape == null || laneShape.trim().isEmpty()) return parsed;
			for (String oneCoord : laneShape.trim().split(" +")) {
				String[] parts = oneCoord.split(",");
				Coordinate coordinate = new Coordinate(
						Double.parseDouble(parts[0]) - x_offs,
						Double.parseDouble(parts[1]) - y_offs,
						parts.length > 2 ? Double.parseDouble(parts[2]) : 0.0);
				parsed.add(coordinate);
			}
			String laneID = attributes.getValue("id");
			parsed = cleanLaneControlPoints(parsed, laneID);
			for (Coordinate coordinate : parsed) {
				try {
					JTS.transform(coordinate, coordinate, transform);
				} catch (TransformException e) {
					throw new IllegalArgumentException(
							"Failed to transform SUMO internal lane " + laneID, e);
				}
				if (!Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)
						|| !Double.isFinite(coordinate.z)) {
					throw new IllegalArgumentException("SUMO internal lane " + laneID
							+ " transformed to a non-finite coordinate");
				}
			}
			return cleanTransformedLaneControlPoints(parsed, laneID);
		}

		private void addDistinctConnectorCoordinate(ArrayList<Coordinate> line,
				Coordinate coordinate) {
			if (coordinate == null) return;
			Coordinate copy = copyCoordinate(coordinate);
			if (!line.isEmpty() && geographicDistanceMeters(line.get(line.size() - 1), copy)
					<= MIN_CONTROL_POINT_SEPARATION_METERS) {
				return;
			}
			line.add(copy);
		}

		private double connectorLineDistance(List<Coordinate> line) {
			double distance = 0.0;
			for (int i = 0; i < line.size() - 1; i++) {
				double horizontal = geographicDistanceMeters(line.get(i), line.get(i + 1));
				double dz = zOrZero(line.get(i + 1)) - zOrZero(line.get(i));
				distance += Math.sqrt(horizontal * horizontal + dz * dz);
			}
			return distance;
		}

		private boolean resolveViaChain(String internalLaneID, String targetLaneID,
				Set<String> visited, ArrayList<String> result) {
			if (internalLaneID == null || internalLaneID.trim().isEmpty()
					|| !internalLanes.containsKey(internalLaneID)
					|| !visited.add(internalLaneID)) return false;
			result.add(internalLaneID);

			for (RawConnection connection : rawConnections) {
				if (!internalLaneID.equals(connection.fromLaneID)
						|| !targetLaneID.equals(connection.toLaneID)) continue;
				if (connection.viaLaneID == null
						|| connection.viaLaneID.trim().isEmpty()) return true;
				if (resolveViaChain(connection.viaLaneID, targetLaneID,
						visited, result)) return true;
			}

			for (RawConnection connection : rawConnections) {
				if (!internalLaneID.equals(connection.fromLaneID)) continue;
				String nextInternal = connection.viaLaneID;
				if ((nextInternal == null || nextInternal.trim().isEmpty())
						&& Boolean.TRUE.equals(isInternalRoadMap.get(connection.toRoadID))) {
					nextInternal = connection.toLaneID;
				}
				if (resolveViaChain(nextInternal, targetLaneID, visited, result)) return true;
			}

			result.remove(result.size() - 1);
			visited.remove(internalLaneID);
			return false;
		}

		private ArrayList<String> resolvedViaLaneIDs(RawConnection root) {
			ArrayList<String> result = new ArrayList<String>();
			if (root.viaLaneID == null || root.viaLaneID.trim().isEmpty()) return result;
			if (!resolveViaChain(root.viaLaneID, root.toLaneID,
					new LinkedHashSet<String>(), result)) {
				result.clear();
				if (internalLanes.containsKey(root.viaLaneID)) result.add(root.viaLaneID);
			}
			return result;
		}

		private void rebuildConnectorPathIndex() {
			LinkedHashMap<Long, ArrayList<ConnectorPathData>> mutableIndex =
					new LinkedHashMap<Long, ArrayList<ConnectorPathData>>();
			for (ConnectorPathData path : connectorPaths) {
				long movementKey = connectorPathKey(
						path.getSourceRoadID(), path.getTargetRoadID());
				mutableIndex.computeIfAbsent(movementKey,
						key -> new ArrayList<ConnectorPathData>()).add(path);
			}
			LinkedHashMap<Long, List<ConnectorPathData>> immutableIndex =
					new LinkedHashMap<Long, List<ConnectorPathData>>();
			for (Map.Entry<Long, ArrayList<ConnectorPathData>> entry
					: mutableIndex.entrySet()) {
				immutableIndex.put(entry.getKey(),
						Collections.unmodifiableList(entry.getValue()));
			}
			connectorPathsByMovement = Collections.unmodifiableMap(immutableIndex);
		}

		private void buildExplicitConnectorPaths() {
			connectorPaths.clear();
			LinkedHashSet<String> seen = new LinkedHashSet<String>();
			int explicitGeometryCount = 0;
			for (RawConnection root : rawConnections) {
				if (Boolean.TRUE.equals(isInternalRoadMap.get(root.fromRoadID))
						|| Boolean.TRUE.equals(isInternalRoadMap.get(root.toRoadID))) continue;
				Integer sourceRoadID = roadIDMap.get(root.fromRoadID);
				Integer targetRoadID = roadIDMap.get(root.toRoadID);
				Integer sourceLaneID = laneIDMap.get(root.fromLaneID);
				Integer targetLaneID = laneIDMap.get(root.toLaneID);
				if (sourceRoadID == null || targetRoadID == null
						|| sourceLaneID == null || targetLaneID == null) continue;
				String key = root.fromLaneID + "\u0000" + root.toLaneID
						+ "\u0000" + (root.viaLaneID == null ? "" : root.viaLaneID);
				if (!seen.add(key)) continue;

				Lane sourceLane = lanes.get(sourceLaneID);
				Lane targetLane = lanes.get(targetLaneID);
				if (sourceLane == null || targetLane == null) continue;
				ArrayList<String> viaLaneIDs = resolvedViaLaneIDs(root);
				ArrayList<String> internalEdgeIDs = new ArrayList<String>();
				ArrayList<Coordinate> centerLine = new ArrayList<Coordinate>();
				addDistinctConnectorCoordinate(centerLine, sourceLane.getEndCoord());
				double declaredLength = 0.0;
				boolean hasDeclaredLength = false;
				double speed = Double.POSITIVE_INFINITY;
				boolean explicitGeometry = false;
				for (String viaLaneID : viaLaneIDs) {
					InternalLaneData internalLane = internalLanes.get(viaLaneID);
					if (internalLane == null) continue;
					String internalEdgeID = internalLane.getEdgeID();
					if (internalEdgeID != null && !internalEdgeID.trim().isEmpty()
							&& (internalEdgeIDs.isEmpty() || !internalEdgeID.equals(
									internalEdgeIDs.get(internalEdgeIDs.size() - 1)))) {
						internalEdgeIDs.add(internalEdgeID);
					}
					List<Coordinate> shape = internalLane.getShape();
					if (shape.size() >= 2) explicitGeometry = true;
					for (Coordinate coordinate : shape) {
						addDistinctConnectorCoordinate(centerLine, coordinate);
					}
					if (Double.isFinite(internalLane.getDeclaredLength())
							&& internalLane.getDeclaredLength() >= 0.0) {
						declaredLength += internalLane.getDeclaredLength();
						hasDeclaredLength = true;
					}
					if (Double.isFinite(internalLane.getSpeed())
							&& internalLane.getSpeed() >= 0.0) {
						speed = Math.min(speed, internalLane.getSpeed());
					}
				}
				addDistinctConnectorCoordinate(centerLine, targetLane.getStartCoord());
				if (centerLine.size() == 1) {
					// A connection with coincident source/target endpoints is still a
					// real lane-to-lane movement. Preserve it as a zero-length path;
					// dropping it here loses the lane mapping and can gridlock every
					// vehicle whose lane is not selected by a later fallback.
					centerLine.add(copyCoordinate(centerLine.get(0)));
				}
				if (centerLine.size() < 2) continue;

				String junctionOrigID = incLaneJunctionMap.get(root.fromLaneID);
				Integer junctionID = junctionIDMap.get(junctionOrigID);
				ConnectorPathData path = new ConnectorPathData(
						sourceRoadID, targetRoadID, sourceLaneID, targetLaneID,
						junctionID == null ? -1 : junctionID.intValue(),
						root.fromRoadID, root.toRoadID, root.fromLaneID,
						root.toLaneID, viaLaneIDs, internalEdgeIDs, centerLine,
						root.parameters,
						root.direction, root.state, root.trafficLightID,
						root.linkIndex, hasDeclaredLength ? declaredLength : Double.NaN,
						Double.isFinite(speed) ? speed : Double.NaN, explicitGeometry);
				connectorPaths.add(path);
				if (explicitGeometry) {
					sourceLane.setExplicitTurningCoords(targetLaneID,
							new ArrayList<Coordinate>(centerLine));
					sourceLane.setTurningDist(targetLaneID,
							connectorLineDistance(centerLine));
					explicitGeometryCount++;
				}
			}
			rebuildConnectorPathIndex();
			ContextCreator.logger.info("Loaded " + connectorPaths.size()
					+ " SUMO connector paths; " + explicitGeometryCount
					+ " use explicit internal-lane geometry.");
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
			internalLanes = new LinkedHashMap<String, InternalLaneData>();
			rawConnections = new ArrayList<RawConnection>();
			connectorPaths = new ArrayList<ConnectorPathData>();
			connectorPathsByMovement = Collections.emptyMap();
			currentConnection = null;
			
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
			logicalLaneLengthOverrides = 0;
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
					    double declaredLaneLength = attributeDouble(attributes, "length", Double.NaN);
					    currentLane.setDeclaredLength(declaredLaneLength);
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
				    if (isSignificantLogicalLengthOverride(declaredLaneLength, coords)) {
					logicalLaneLengthOverrides++;
				    }
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
					String internalLaneID = attributes.getValue("id");
					laneRoadMap.put(internalLaneID, currentRoadID);
					isInternalLaneMap.put(internalLaneID, true);
					internalLanes.put(internalLaneID, new InternalLaneData(
							currentRoadID, internalLaneID,
							attributeDouble(attributes, "length", Double.NaN),
							attributeDouble(attributes, "speed", Double.NaN),
							parseInternalLaneShape(attributes)));
				}
			}
			
			//Handle the junction
			if (qName.equalsIgnoreCase("junction")) {
				if((attributes.getValue("type") == null) || !attributes.getValue("type").equalsIgnoreCase("internal")) {
					int junction_id = generateJunctionID(attributes.getValue("id"));
					// Generate specific type of junction
					currentJunction = new Junction(junction_id);
					int junction_type = deduceJunctionType(attributes.getValue("type"));
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
				currentConnection = new RawConnection(attributes);
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

			if (qName.equalsIgnoreCase("param") && currentConnection != null) {
				String key = attributes.getValue("key");
				String value = attributes.getValue("value");
				if (key != null && value != null) currentConnection.parameters.put(key, value);
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

			if (qName.equalsIgnoreCase("connection") && currentConnection != null) {
				rawConnections.add(currentConnection);
				currentConnection = null;
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

				buildExplicitConnectorPaths();
				// Keep physical lane geometry immutable after parsing. Transition-specific
				// cleanup belongs to connector/turning paths; changing a shared target
				// lane here can collapse its length for every incoming movement.
				if (removedCoincidentLaneControlPoints > 0 || zeroLengthLaneShapes > 0
						|| logicalLaneLengthOverrides > 0) {
					ContextCreator.logger.info("SUMO lane control-point cleanup removed "
							+ removedCoincidentLaneControlPoints + " coincident points and retained "
							+ zeroLengthLaneShapes
							+ " irreducible zero-length lane shapes as degenerate two-point lines; retained "
							+ logicalLaneLengthOverrides
							+ " significant SUMO logical-length overrides.");
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
		SumoXML sxml = new SumoXML("data/Birmingham/facility/road/birmingham.net.xml");
//		SumoXML sxml = new SumoXML("data/UA/facility/road/nema.net.xml");
//		SumoXML sxml = new SumoXML("data/study_region.net.xml");
//		SumoXML sxml = new SumoXML("data/IN/facility/road/indianametsr.net.xml");
//		SumoXML sxml = new SumoXML("data/CARLA/Town05/facility/road/Town05.net.xml");
		sxml.print();
	}
	
}
