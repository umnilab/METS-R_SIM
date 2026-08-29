package mets_r.facility;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.Vehicle;

import org.geotools.referencing.GeodeticCalculator;

/**
 * 
 * Inherit from A-RESCUE with modification
 * 
 * @author Samiul Hasan, Binh Luong, and Zengxiang Lei
 * 
 **/

public class Lane {
	private static final AtomicLong ARC_GEOMETRY_EPOCH = new AtomicLong();
	/* Private variables */
	private int ID; // From shape file
	private String origID;
	private int index;
	private ArrayList<Coordinate> coords;
	private volatile ArcGeometry arcGeometry;
	private double length; // Logical longitudinal length used by traffic dynamics.
	private double declaredLength;
	private double geometricLength;
	// False only when the lane has no usable physical direction/shape for safely
	// inserting a vehicle. A declared-vs-drawn length difference is not by itself
	// unusable: SUMO permits a logical length that differs from the centerline.
	private boolean departureGeometryUsable;
	private double[] segmentSlopes; // slope[i] = dz/horizontal for segment coords[i]→coords[i+1]
	
	// Connection with other facilities
	private ArrayList<Integer> upStreamLanes;// Upstream lanes that connect to this
	private ArrayList<Integer> downStreamLanes;// Down stream lanes that connect to
	
	private HashMap<Integer, ArrayList<Coordinate>> turningCoords;
	private HashMap<Integer, Double> turningDists;
	private HashSet<Integer> explicitTurningTargets;
	
	private int road; // ID of the road who contains this lane
	
	// For vehicle movement
	private AtomicInteger nVehicles_; // Number of vehicle in the lane
	private Vehicle firstVehicle_; // The first vehicle on a lane
	private Vehicle lastVehicle_; // The last vehicle vehicle on a lane
	private Random rand; // Random seed for lane changing
	private double freeSpeed_; // Target speed for vehicles on this lane

	public Lane(int id) {
		this.ID = id;
		this.origID = "";
		this.rand = new Random(GlobalVariables.RandomGenerator.nextInt());
		this.nVehicles_ = new AtomicInteger(0);
		this.lastVehicle_ = null;
		this.upStreamLanes = new ArrayList<Integer>();
		this.downStreamLanes = new ArrayList<Integer>();
		this.turningCoords = new HashMap<Integer, ArrayList<Coordinate>>();
		this.turningDists = new HashMap<Integer, Double>();
		this.explicitTurningTargets = new HashSet<Integer>();
		this.departureGeometryUsable = true;
		this.declaredLength = Double.NaN;
		this.geometricLength = Double.NaN;
	}

	public int getID() {
		return ID;
	}
	

	public Coordinate getStartCoord() {
		Coordinate coord = new Coordinate();
		Coordinate first_coord = this.coords.get(0);
		coord.x = first_coord.x;
		coord.y = first_coord.y;
		coord.z = first_coord.z;
		return coord;
	}
	
	public Coordinate getEndCoord() {
		Coordinate coord = new Coordinate();
		Coordinate first_coord = this.coords.get(this.coords.size()-1);
		coord.x = first_coord.x;
		coord.y = first_coord.y;
		coord.z = first_coord.z;
		return coord;
	}
	
	public synchronized void setCoords(Coordinate[] coordinates) {
		this.coords = new ArrayList<Coordinate>(Arrays.asList(coordinates));
		this.arcGeometry = null;
		this.geometricLength = Double.NaN;
		ARC_GEOMETRY_EPOCH.incrementAndGet();
	}
	
	public synchronized void setCoords(ArrayList<Coordinate> coords) {
		this.coords = new ArrayList<Coordinate>(coords);
		this.arcGeometry = null;
		this.geometricLength = Double.NaN;
		ARC_GEOMETRY_EPOCH.incrementAndGet();
	}

	
	public ArrayList<Coordinate> getCoords() {
		// Deep copy to avoid being modified somewhere
		ArrayList<Coordinate> res = new ArrayList<Coordinate>();
		for(Coordinate coord: this.coords) {
			Coordinate coord2 = new Coordinate();
			coord2.x = coord.x;
			coord2.y = coord.y;
			coord2.z = coord.z;
			res.add(coord2);
		}
		return res;
	}
	
	public ArrayList<Coordinate> getTurningCoords(int targetLaneID){
		ArrayList<Coordinate> res = new ArrayList<Coordinate>();
		if(this.turningCoords.containsKey(targetLaneID)) {
			int i = 0;
			for(Coordinate coord: this.turningCoords.get(targetLaneID)) {
				if(i > 0) { // Skip the first coordinate
					Coordinate coord2 = new Coordinate();
					coord2.x = coord.x;
					coord2.y = coord.y;
					coord2.z = coord.z;
					res.add(coord2);
				}
				i += 1;
			}
		}
		return res;
	}

	/**
	 * Immutable, lazily built lane geometry for normalized-arc interpolation.
	 * The defensive coordinate copy and geodetic segment lengths are computed
	 * once per installed lane polyline and invalidated by either setCoords method.
	 */
	public ArcGeometry getArcGeometry() {
		ArcGeometry cached = this.arcGeometry;
		if (cached != null) return cached;
		synchronized (this) {
			cached = this.arcGeometry;
			if (cached != null) return cached;
			if (this.coords == null || this.coords.size() < 2) return null;

			ArrayList<Coordinate> coordinates = new ArrayList<Coordinate>(this.coords.size());
			for (Coordinate coordinate : this.coords) {
				coordinates.add(new Coordinate(coordinate));
			}
			double[] cumulative = new double[coordinates.size()];
			double total = 0.0;
			GeodeticCalculator calculator =
					new GeodeticCalculator(ContextCreator.getLaneGeography().getCRS());
			for (int i = 1; i < coordinates.size(); i++) {
				Coordinate start = coordinates.get(i - 1);
				Coordinate end = coordinates.get(i);
				calculator.setStartingGeographicPoint(start.x, start.y);
				calculator.setDestinationGeographicPoint(end.x, end.y);
				double segmentLength = calculator.getOrthodromicDistance();
				if (!Double.isFinite(segmentLength) || segmentLength < 0.0) segmentLength = 0.0;
				total += segmentLength;
				cumulative[i] = total;
			}
			if (!Double.isFinite(total) || total <= 0.001) return null;
			cached = new ArcGeometry(this, coordinates, cumulative, total);
			this.arcGeometry = cached;
			return cached;
		}
	}

	public static long getArcGeometryEpoch() {
		return ARC_GEOMETRY_EPOCH.get();
	}

	public static final class ArcGeometry {
		private final Lane lane;
		private final List<Coordinate> coordinates;
		private final double[] cumulative;
		private final double geometricLength;

		private ArcGeometry(Lane lane, ArrayList<Coordinate> coordinates,
				double[] cumulative, double geometricLength) {
			this.lane = lane;
			this.coordinates = Collections.unmodifiableList(coordinates);
			this.cumulative = cumulative;
			this.geometricLength = geometricLength;
		}

		public Lane getLane() {
			return this.lane;
		}

		public int size() {
			return this.coordinates.size();
		}

		public Coordinate coordinateAt(int index) {
			return this.coordinates.get(index);
		}

		public double cumulativeAt(int index) {
			return this.cumulative[index];
		}

		public double getGeometricLength() {
			return this.geometricLength;
		}
	}
	
	public void setTurningCoords(int targetLaneID, ArrayList<Coordinate> turningCoords) {
		this.turningCoords.put(targetLaneID, turningCoords);
		this.explicitTurningTargets.remove(targetLaneID);
	}

	/** Store connector geometry loaded directly from the network source. */
	public void setExplicitTurningCoords(int targetLaneID,
			ArrayList<Coordinate> turningCoords) {
		this.turningCoords.put(targetLaneID, turningCoords);
		this.explicitTurningTargets.add(targetLaneID);
	}

	public boolean hasExplicitTurningCoords(int targetLaneID) {
		return this.explicitTurningTargets.contains(targetLaneID);
	}
	
	public ArrayList<ArrayList<Double>> getXYList(){
		ArrayList<ArrayList<Double>> res = new ArrayList<ArrayList<Double>>();
		for(Coordinate coord: this.coords) {
			ArrayList<Double> xy = new ArrayList<Double>();
			xy.add(coord.x);
			xy.add(coord.y);
			res.add(xy);
		}
		return res;
	}

	public void setLength(double length) {
		this.length = length;
	}

	public double getLength() {
		return length;
	}

	public void setDeclaredLength(double declaredLength) {
		this.declaredLength = Double.isFinite(declaredLength) && declaredLength > 0.0
				? declaredLength : Double.NaN;
	}

	public double getDeclaredLength() {
		return this.declaredLength;
	}

	public void setGeometricLength(double geometricLength) {
		this.geometricLength = Double.isFinite(geometricLength) && geometricLength >= 0.0
				? geometricLength : Double.NaN;
	}

	public double getGeometricLength() {
		double cachedLength = this.geometricLength;
		if (Double.isFinite(cachedLength)) return cachedLength;
		ArcGeometry geometry = this.getArcGeometry();
		if (geometry == null) return Double.NaN;
		cachedLength = geometry.getGeometricLength();
		this.geometricLength = cachedLength;
		return cachedLength;
	}

	/** Convert a distance measured on the centerline to this lane's logical scale. */
	public double toLogicalDistance(double geometricDistance) {
		if (!Double.isFinite(geometricDistance)) return geometricDistance;
		double centerlineLength = this.getGeometricLength();
		if (!Double.isFinite(centerlineLength) || centerlineLength <= 0.0
				|| !Double.isFinite(this.length) || this.length < 0.0) {
			return geometricDistance;
		}
		return geometricDistance * this.length / centerlineLength;
	}

	/** Convert a logical longitudinal distance to a centerline distance. */
	public double toGeometricDistance(double logicalDistance) {
		if (!Double.isFinite(logicalDistance)) return logicalDistance;
		double centerlineLength = this.getGeometricLength();
		if (!Double.isFinite(this.length) || this.length <= 0.0
				|| !Double.isFinite(centerlineLength) || centerlineLength < 0.0) {
			return logicalDistance;
		}
		return logicalDistance * centerlineLength / this.length;
	}

	public boolean isDepartureGeometryUsable() {
		return this.departureGeometryUsable;
	}

	public void setDepartureGeometryUsable(boolean usable) {
		this.departureGeometryUsable = usable;
	}

	public void setSegmentSlopes(double[] slopes) {
		this.segmentSlopes = slopes;
	}

	public double getSegmentSlope(int i) {
		if (segmentSlopes == null || i < 0 || i >= segmentSlopes.length) return 0.0;
		return segmentSlopes[i];
	}
	
	public double getTurningDist(int targetLaneID) {
		if(this.turningDists.containsKey(targetLaneID)) {
			return this.turningDists.get(targetLaneID);
		}
		else {
			return 0;
		}
	}
	
	public void setTurningDist(int targetLaneID, double dist) {
		this.turningDists.put(targetLaneID, dist);
	}
	
	public Road getRoad() {
		return ContextCreator.getRoadContext().get(road);
	}

	public void setRoad(int roadID) {
		this.road = roadID;
	}

	public double getSpeed() {
		return this.freeSpeed_;
	}
	
	// Assume the car-following speed is normally distributed based on
	// Wagner, P. (2012). Analyzing fluctuations in car-following. Transportation research part B: methodological, 46(10), 1384-1392.
	// From Figure 2 and 4, it can be seen that when speed is less than 30 m/s the speed distribution follows a normal pdf with std around 0.5
	public double getRandomFreeSpeed(double coef) {
		return  Math.max(
				this.freeSpeed_ + coef * 0.5, 0);
	}


	public void firstVehicle(Vehicle v) {
		if (v != null) {
			this.firstVehicle_ = v;
			v.leading(null);
		} else
			this.firstVehicle_ = null;
	}

	public void lastVehicle(Vehicle v) {
		if (v != null) {
			this.lastVehicle_ = v;
			v.trailing(null);
		} else
			this.lastVehicle_ = null;

	}

	public void setSpeed(double speed) {
		this.freeSpeed_ = speed;
	}

	public Vehicle firstVehicle() {
		return firstVehicle_;
	}

	public Vehicle lastVehicle() {
		return lastVehicle_;
	}

	public int getIndex() {
		return this.index;
	}

	public void setIndex() {
		this.index = ContextCreator.getRoadContext().get(road).getLaneIndex(this);
	}

	/*
	 * -------------------------------------------------------------------- Returns
	 * the last vehicle in the downstream lanes. The vehicle closest to the upstream
	 * end is returned.
	 * --------------------------------------------------------------------
	 */
	public Vehicle lastInDnLane() {
		Vehicle last = null;
		Vehicle pv;
		double mindis = GlobalVariables.FLT_INF;
		double dis;
		Lane dlane;
		int i;
		for (i = 0; i < downStreamLanes.size(); i++) {
			dlane = ContextCreator.getLaneContext().get(downStreamLanes.get(i));
			pv = dlane.lastVehicle_;
			if (pv != null) {
				dis = dlane.getLength() - (pv.getDistanceToNextJunction() + pv.length());
				if (dis < mindis) {
					mindis = dis;
					last = pv;
				}
			}
		}
		return (last);
	}
	
	public ArrayList<Integer> getDownStreamLanes() {
		return this.downStreamLanes;
	}
	
	public void addDownStreamLane(int l) {
		if (l > 0) {
			if (!this.downStreamLanes.contains(l))
				this.downStreamLanes.add(l);
			else
				ContextCreator.logger.error("Cannot register the down stream lane since it is already added");
		}
	}
	
	public ArrayList<Integer> getUpStreamLanes() {
		return upStreamLanes;
	}
	
	public void addUpStreamLane(int l) {
		if(!this.upStreamLanes.contains(l))
			this.upStreamLanes.add(l);
		else
			ContextCreator.logger.error("Cannot register the up stream lane since it is already added");
	}

	public Lane getUpStreamLaneInRoad(Road pr) {
		Lane connectLane = null;
		for (int lane : this.getUpStreamLanes()) {
			if (ContextCreator.getLaneContext().get(lane).getRoad() == pr) {
				connectLane = ContextCreator.getLaneContext().get(lane);
				break;
			}
		}
		return connectLane;
	}

	// Return number of vehicles
	public int nVehicles() {
		return nVehicles_.get();
	}

	public void restoreRuntimeState(double speed, Random restoredRandom) {
		this.nVehicles_.set(0);
		this.firstVehicle_ = null;
		this.lastVehicle_ = null;
		this.freeSpeed_ = speed;
		if (restoredRandom != null) {
			this.rand = restoredRandom;
		}
	}

	public Random getRandom() {
		return this.rand;
	}

	// This add only the number of vehicle to lane, while addVehicle in road and a
	// vehicle to arrayList.
	public void addOneVehicle() {
		nVehicles_.addAndGet(1);
	}

	public void removeOneVehicle() {
		this.nVehicles_.addAndGet(-1);
	}

	// Following are functions dedicated for discretionary lane changing
	public boolean isConnectToLane(Lane pl) {
		boolean connectFlag = false;
		if (pl != null) {
			for (int ul : pl.getUpStreamLanes()) {
				if (ul == this.getID())
					connectFlag = true;
			}
		}
		return connectFlag;
	}

	// Find the lane with less vehicles
	public Lane betterLane(Lane plane) {
		if (this != null && plane != null) {
			if (this.nVehicles_.get() < plane.nVehicles_.get()) {
				return this;
			} else if (this.nVehicles_.get() > plane.nVehicles_.get()) {
				return plane;
			} else {
				if (rand.nextDouble() > 0.5)
					return this;
				else
					return plane;
			}
		} else {
			return this;
		}
	}
	
	public String getOrigID() {
		return this.origID;
	}
	
	public void setOrigID(String newID) {
		this.origID = newID;
	}

}
