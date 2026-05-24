package mets_r.communication;

import java.util.HashMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
	public int vehicle_class;
	public double vehicle_length_m;
	private HashMap<String, Object> messagingLayer;
	private HashMap<String, Object> communicationLayer;
	private HashMap<String, Object> securityLayer;
	private HashMap<String, Object> qualityLayer;


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
		this.vehicle_class = vehicle.getVehicleClass();
		this.vehicle_length_m = vehicle.length();
		this.initializeMetadata();
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
		this.vehicle_class = -1;
		this.vehicle_length_m = 5.0;
		this.initializeMetadata();
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

	private void initializeMetadata() {
		int payloadBytes = 96;
		this.messagingLayer = this.buildJ2735Layer();
		this.communicationLayer = WaveMessageMetadata.buildCommunicationLayer(type, "SAE_J2735_BSM", "0x20",
				WaveMessageMetadata.bsmChannel(), payloadBytes, 1, WaveMessageMetadata.bsmRangeMeters());
		this.securityLayer = WaveMessageMetadata.buildSecurityLayer(vid, "SAE_J2735_BSM", payloadBytes);
		this.qualityLayer = WaveMessageMetadata.buildQualityLayer(payloadBytes,
				this.layerLatencyMs(this.communicationLayer, this.securityLayer),
				WaveMessageMetadata.clamp(Math.min(velocity_confidence, elevation_confidence / 15.0), 0.0, 1.0));
	}

	private HashMap<String, Object> buildJ2735Layer() {
		HashMap<String, Object> layer = new LinkedHashMap<String, Object>();
		HashMap<String, Object> coreData = new LinkedHashMap<String, Object>();
		HashMap<String, Object> accuracy = new LinkedHashMap<String, Object>();
		HashMap<String, Object> accelSet = new LinkedHashMap<String, Object>();
		HashMap<String, Object> brakes = new LinkedHashMap<String, Object>();
		HashMap<String, Object> size = new LinkedHashMap<String, Object>();
		HashMap<String, Object> spoofingSurface = new LinkedHashMap<String, Object>();

		accuracy.put("semi_major_m", WaveMessageMetadata.round3(SemiMajorAxisAccuracy));
		accuracy.put("semi_minor_m", WaveMessageMetadata.round3(SemiMinorAxisAccuracy));
		accuracy.put("orientation_deg", WaveMessageMetadata.round3(SemiMajorAxisOrientation));

		accelSet.put("longitudinal_mps2", 0.0);
		accelSet.put("lateral_mps2", 0.0);
		accelSet.put("vertical_mps2", 0.0);
		accelSet.put("yaw_rate_deg_per_s", 0.0);

		brakes.put("wheel_brakes", "unavailable");
		brakes.put("traction_control", "unavailable");
		brakes.put("abs", "unavailable");
		brakes.put("brake_boost", "unavailable");

		size.put("length_cm", (int) Math.round(Math.max(0.0, vehicle_length_m) * 100.0));
		size.put("width_cm", vehicle_class == Vehicle.EBUS ? 260 : 200);

		coreData.put("msg_id", 2);
		coreData.put("msg_count", WaveMessageMetadata.j2735MessageCount(utc_time));
		coreData.put("temporary_id", WaveMessageMetadata.temporaryId(vid, utc_time));
		coreData.put("sec_mark", WaveMessageMetadata.dSecond(utc_time));
		coreData.put("latitude_e7", WaveMessageMetadata.latitudeE7(latitude));
		coreData.put("longitude_e7", WaveMessageMetadata.longitudeE7(longitude));
		coreData.put("elevation_dm", WaveMessageMetadata.elevationDecimeters(altitude));
		coreData.put("accuracy", accuracy);
		coreData.put("transmission_state", "unavailable");
		coreData.put("speed_mps", WaveMessageMetadata.round3(velocity));
		coreData.put("speed_raw_0_02mps", WaveMessageMetadata.speedRaw(velocity));
		coreData.put("heading_deg", WaveMessageMetadata.round3(WaveMessageMetadata.normalizeHeading(heading)));
		coreData.put("heading_raw_0_0125deg", WaveMessageMetadata.headingRaw(heading));
		coreData.put("angle_deg", 0.0);
		coreData.put("accel_set", accelSet);
		coreData.put("brakes", brakes);
		coreData.put("size", size);

		spoofingSurface.put("mutable_fields", Arrays.asList("latitude", "longitude", "altitude", "speed_mps",
				"heading_deg", "accel_set", "brakes", "size"));
		spoofingSurface.put("truth_reference_fields", Arrays.asList("true_x", "true_y", "true_z"));
		spoofingSurface.put("message_count_wrap", 128);

		layer.put("standard", "SAE J2735");
		layer.put("message_name", "BasicSafetyMessage");
		layer.put("core_data", coreData);
		layer.put("spoofing_surface", spoofingSurface);
		return layer;
	}

	private double layerLatencyMs(HashMap<String, Object> communication, HashMap<String, Object> security) {
		double latency = 0.0;
		Object macObj = communication.get("mac");
		if (macObj instanceof HashMap<?, ?>) {
			Object macLatency = ((HashMap<?, ?>) macObj).get("mac_latency_ms");
			if (macLatency instanceof Number) {
				latency += ((Number) macLatency).doubleValue();
			}
		}
		Object signLatency = security.get("signing_latency_ms");
		if (signLatency instanceof Number) {
			latency += ((Number) signLatency).doubleValue();
		}
		return latency;
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
		jsonObj.put("type", type);
		jsonObj.put("vehicle_class", vehicle_class);
		jsonObj.put("vehicle_length_m", vehicle_length_m);
		jsonObj.put("true_x", true_x);
		jsonObj.put("true_y", true_y);
		jsonObj.put("true_z", true_z);
		jsonObj.put("messaging_layer", messagingLayer);
		jsonObj.put("communication_layer", communicationLayer);
		jsonObj.put("security_layer", securityLayer);
		jsonObj.put("quality_layer", qualityLayer);
        return JSONObject.toJSONString(jsonObj);
	}
	
	public int hashCode(){
        return Objects.hash(super.hashCode(), vid, latitude, longitude, altitude, heading, velocity, climb, utc_time);
	}
	
}
