package mets_r.communication;

import java.util.HashMap;
import java.util.Objects;
import java.util.Random;

import org.json.simple.JSONObject;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.mobility.Vehicle;

/**
 * CV2X data that mimics the format collected from OBU in Clemson 
 * 
 * @author Zengxiang Lei
 *
 */
public class BSMDataStream {
	/** Default 1-sigma GPS position error in meters (typical consumer-grade GNSS). */
	public static final double DEFAULT_COORDINATE_RANDOMNESS = 2.0;

	public static Random RANDOM = new Random(GlobalVariables.RandomGenerator.nextInt());

	public int vid;
	public int utc_fix_mode;
	public double latitude;
	public double longitude;
	public double altitude;
	public int qty_SV_in_view;
	public int qty_SV_used;
	public boolean GNSS_unavailable;
	public boolean GNSS_aPDOPofUnder5;
	public boolean GNSS_inViewOfUnder5;
	public boolean GNSS_localCorrectionsPresent;
	public boolean GNSS_networkCorrectionsPresent;
	public double SemiMajorAxisAccuracy;
	public double SemiMinorAxisAccuracy;
	public double SemiMajorAxisOrientation;
	public double heading;
	public double velocity;
	public double climb;
	public double time_confidence;
	public double velocity_confidence;
	public double elevation_confidence;
	public int leap_seconds;
	public double utc_time;
	public int type; // how this record is generated, 0 represents the DSRC, 1 represents C-V2X
	public double true_x; // for deciding who will see this message and should never be used in operation!
	public double true_y; // for deciding who will see this message and should never be used in operation!
	public double true_z; // for deciding who will see this message and should never be used in operation!


	public BSMDataStream(int vid, Vehicle vehicle, Coordinate coordinate, int type) {
		this(vid, vehicle, coordinate, type, DEFAULT_COORDINATE_RANDOMNESS);
	}

	/**
	 * Constructs a BSMDataStream with simulated GNSS noise.
	 *
	 * All derived fields (SV counts, accuracy ellipse, GNSS status flags, and
	 * confidence values) are computed from {@code coordinateRandomness} so that the
	 * message is internally consistent: lower randomness implies better satellite
	 * geometry, tighter error ellipse, and higher confidence.
	 *
	 * @param coordinateRandomness 1-sigma position error in meters; use
	 *                             {@link #DEFAULT_COORDINATE_RANDOMNESS} for a
	 *                             typical consumer-grade GNSS receiver.
	 */
	public BSMDataStream(int vid, Vehicle vehicle, Coordinate coordinate, int type, double coordinateRandomness) {
		this(vid,
				3,                                          // utc_fix_mode: 3D fix
				noisyLat(coordinate.x, coordinateRandomness),
				noisyLon(coordinate.x, coordinate.y, coordinateRandomness),
				0,                                          // altitude
				svInView(coordinateRandomness),
				svUsed(coordinateRandomness),
				false,                                      // GNSS_unavailable
				coordinateRandomness < 5.0,                 // GNSS_aPDOPofUnder5: good geometry when error is low
				svInView(coordinateRandomness) < 5,         // GNSS_inViewOfUnder5: few SVs when accuracy is poor
				false,                                      // GNSS_localCorrectionsPresent
				false,                                      // GNSS_networkCorrectionsPresent
				coordinateRandomness,                       // SemiMajorAxisAccuracy mirrors 1-sigma error
				coordinateRandomness * 0.8,                 // SemiMinorAxisAccuracy (minor axis slightly tighter)
				RANDOM.nextDouble() * 360.0,                // SemiMajorAxisOrientation: random ellipse heading
				vehicle.getBearing(),
				vehicle.currentSpeed(),
				0,                                          // climb
				0,                                          // time_confidence
				velocityConfidence(coordinateRandomness),
				elevationConfidence(coordinateRandomness),
				18,                                         // leap_seconds
				ContextCreator.getCurrentTick(),
				type,
				coordinate.x, coordinate.y, coordinate.z);
	}

	public BSMDataStream(int vid, int utc_fix_mode, double latitude, double longitude, double altitude,
			int qty_SV_in_view, int qty_SV_used, boolean GNSS_unavailable, boolean GNSS_aPDOPofUnder5,
			boolean GNSS_inViewOfUnder5, boolean GNSS_localCorrectionsPresent, boolean GNSS_networkCorrectionsPresent,
			double SemiMajorAxisAccuracy, double SemiMinorAxisAccuracy, double SemiMajorAxisOrientation, double heading,
			double velocity, double climb, double time_confidence, double velocity_confidence,
			double elevation_confidence, int leap_seconds, double utc_time, int type, double true_x, double true_y, double true_z) {
		this.vid = vid;
		this.utc_fix_mode = utc_fix_mode;
		this.latitude = latitude;
		this.longitude = longitude;
		this.altitude = altitude;
		this.qty_SV_in_view = qty_SV_in_view;
		this.qty_SV_used = qty_SV_used;
		this.GNSS_unavailable = GNSS_unavailable;
		this.GNSS_aPDOPofUnder5 = GNSS_aPDOPofUnder5;
		this.GNSS_inViewOfUnder5 = GNSS_inViewOfUnder5;
		this.GNSS_localCorrectionsPresent = GNSS_localCorrectionsPresent;
		this.GNSS_networkCorrectionsPresent = GNSS_networkCorrectionsPresent;
		this.SemiMajorAxisAccuracy = SemiMajorAxisAccuracy;
		this.SemiMinorAxisAccuracy = SemiMinorAxisAccuracy;
		this.SemiMajorAxisOrientation = SemiMajorAxisOrientation;
		this.heading = heading;
		this.velocity = velocity;
		this.climb = climb;
		this.time_confidence = time_confidence;
		this.velocity_confidence = velocity_confidence;
		this.elevation_confidence = elevation_confidence;
		this.leap_seconds = leap_seconds;
		this.utc_time = utc_time;
		this.type = type;
		this.true_x = true_x;
		this.true_y = true_y;
		this.true_z = true_z;
	}

	// static random getter/setter for snapshot save/restore
	public static Random getRandom() {
		return RANDOM;
	}

	public static void setRandom(Random r) {
		RANDOM = r;
	}

	// get methods
	public int getVID() {
		return vid;
	}

	public int getUtc_fix_mode() {
		return utc_fix_mode;
	}
	
	public double getLongitude() {
		return longitude;
	}

	public double getLatitude() {
		return latitude;
	}

	public double getAltitude() {
		return altitude;
	}

	public int getQty_SV_in_view() {
		return qty_SV_in_view;
	}

	public int getQty_SV_used() {
		return qty_SV_used;
	}

	public boolean isGNSS_unavailable() {
		return GNSS_unavailable;
	}

	public boolean isGNSS_aPDOPofUnder5() {
		return GNSS_aPDOPofUnder5;
	}

	public boolean isGNSS_inViewOfUnder5() {
		return GNSS_inViewOfUnder5;
	}

	public boolean isGNSS_localCorrectionsPresent() {
		return GNSS_localCorrectionsPresent;
	}

	public boolean isGNSS_networkCorrectionsPresent() {
		return GNSS_networkCorrectionsPresent;
	}

	public double getSemiMajorAxisAccuracy() {
		return SemiMajorAxisAccuracy;
	}

	public double getSemiMinorAxisAccuracy() {
		return SemiMinorAxisAccuracy;
	}

	public double getSemiMajorAxisOrientation() {
		return SemiMajorAxisOrientation;
	}

	public double getHeading() {
		return heading;
	}

	public double getVelocity() {
		return velocity;
	}

	public double getClimb() {
		return climb;
	}

	public double getTime_confidence() {
		return time_confidence;
	}

	public double getVelocity_confidence() {
		return velocity_confidence;
	}

	public double getElevation_confidence() {
		return elevation_confidence;
	}

	public int getLeap_seconds() {
		return leap_seconds;
	}

	public double getUTC_time() {
		return utc_time;
	}

	public double getTrue_x() {
		return true_x;
	}

	public double getTrue_y() {
		return true_y;
	}

	public double getTrue_z() {
		return true_z;
	}
	
	// --- helpers for deriving fields from coordinateRandomness ---

	/** Meters of arc per degree of latitude (constant). */
	private static final double METERS_PER_LAT_DEG = 111_320.0;

	/**
	 * Applies Gaussian noise (σ = {@code randomnessMeters}) to a WGS-84 latitude.
	 * The noise is converted from metres to degrees using the fixed
	 * {@value #METERS_PER_LAT_DEG} m/° approximation.
	 */
	private static double noisyLat(double latDeg, double randomnessMeters) {
		return latDeg + RANDOM.nextGaussian() * (randomnessMeters / METERS_PER_LAT_DEG);
	}

	/**
	 * Applies Gaussian noise (σ = {@code randomnessMeters}) to a WGS-84 longitude.
	 * The metres-to-degrees conversion accounts for the latitude-dependent arc
	 * length: 1° longitude = {@value #METERS_PER_LAT_DEG} × cos(lat) metres.
	 */
	private static double noisyLon(double latDeg, double lonDeg, double randomnessMeters) {
		double cosLat = Math.cos(Math.toRadians(latDeg));
		double metersPerLonDeg = METERS_PER_LAT_DEG * Math.max(cosLat, 1e-6); // guard against poles
		return lonDeg + RANDOM.nextGaussian() * (randomnessMeters / metersPerLonDeg);
	}

	/**
	 * Number of satellites in view. Decreases from 12 (ideal) as position error
	 * grows; floored at 5.
	 */
	private static int svInView(double randomness) {
		return Math.max(5, (int)(12.0 - randomness * 1.5));
	}

	/**
	 * Number of satellites used in the fix. Always at least 4 (minimum for 3D) and
	 * at most svInView - 2.
	 */
	private static int svUsed(double randomness) {
		return Math.max(4, svInView(randomness) - 2);
	}

	/**
	 * Velocity confidence in [0, 1]. Derived from position error: a larger
	 * coordinate randomness indicates noisier speed estimates, so confidence
	 * decreases monotonically. At {@link #DEFAULT_COORDINATE_RANDOMNESS} (2 m) the
	 * confidence is 0.5; at 0 m it reaches 1.0.
	 */
	private static double velocityConfidence(double randomness) {
		return 1.0 / (1.0 + randomness * 0.5);
	}

	/**
	 * Elevation confidence on a 0–15 scale (analogous to SAE J2735). Elevation
	 * accuracy is typically worse than horizontal, so this is scaled at 80 % of the
	 * horizontal confidence and mapped to [0, 15]. At
	 * {@link #DEFAULT_COORDINATE_RANDOMNESS} (2 m) the value is approximately 3.
	 */
	private static double elevationConfidence(double randomness) {
		return Math.max(0.0, 15.0 * 0.8 * velocityConfidence(randomness * 1.25));
	}

	@Override
	public String toString() {
		// to json string
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();		
		jsonObj.put("vid", vid);
		jsonObj.put("utc_fix_mode", utc_fix_mode);
		jsonObj.put("latitude", latitude);
		jsonObj.put("longitude", longitude);
		jsonObj.put("altitude", altitude);
		jsonObj.put("qty_SV_in_view", qty_SV_in_view);
		jsonObj.put("qty_SV_used", qty_SV_used);
		jsonObj.put("GNSS_unavailable", GNSS_unavailable);
		jsonObj.put("GNSS_aPDOPofUnder5", GNSS_aPDOPofUnder5);
		jsonObj.put("GNSS_inViewOfUnder5", GNSS_inViewOfUnder5);
		jsonObj.put("GNSS_localCorrectionsPresent", GNSS_localCorrectionsPresent);
		jsonObj.put("GNSS_networkCorrectionsPresent", GNSS_networkCorrectionsPresent);
		jsonObj.put("SemiMajorAxisAccuracy", SemiMajorAxisAccuracy);
		jsonObj.put("SemiMinorAxisAccuracy", SemiMinorAxisAccuracy);
		jsonObj.put("SemiMajorAxisOrientation", SemiMajorAxisOrientation);
		jsonObj.put("heading", heading);
		jsonObj.put("velocity", velocity);
		jsonObj.put("climb", climb);
		jsonObj.put("time_confidence", time_confidence);
		jsonObj.put("velocity_confidence", velocity_confidence);
		jsonObj.put("elevation_confidence", elevation_confidence);
		jsonObj.put("leap_seconds", leap_seconds);
		jsonObj.put("utc_time", utc_time);
		jsonObj.put("true_x", true_x);
		jsonObj.put("true_y", true_y);
		jsonObj.put("true_z", true_z);
        return JSONObject.toJSONString(jsonObj);
	}
	
	public int hashCode(){
        return Objects.hash(super.hashCode(), vid, latitude, longitude, altitude, heading, velocity, climb, utc_time);
	}
	
}
