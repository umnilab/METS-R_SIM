package mets_r.data.output;

import java.util.HashMap;
import java.util.Objects;

import org.json.simple.JSONObject;

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.mobility.Vehicle;

/**
 * CV2X data that mimics the format collected from OBU in Clemson 
 * 
 * @author Zengxiang Lei
 *
 */
public class CV2XSnapshot {
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

	public CV2XSnapshot(Vehicle vehicle, Coordinate coordinate) {
		this(vehicle.getID(), 3, coordinate.x, coordinate.y, 0, 42, 42, false, false, false, false, false,
				2.0, 2.0, 0, vehicle.getBearing(), vehicle.currentSpeed(), 0, 0, 0.5, 3, 18,
				ContextCreator.getCurrentTick());
	}

	public CV2XSnapshot(int vid, int utc_fix_mode, double latitude, double longitude, double altitude,
			int qty_SV_in_view, int qty_SV_used, boolean GNSS_unavailable, boolean GNSS_aPDOPofUnder5,
			boolean GNSS_inViewOfUnder5, boolean GNSS_localCorrectionsPresent, boolean GNSS_networkCorrectionsPresent,
			double SemiMajorAxisAccuracy, double SemiMinorAxisAccuracy, double SemiMajorAxisOrientation, double heading,
			double velocity, double climb, double time_confidence, double velocity_confidence,
			double elevation_confidence, int leap_seconds, double utc_time) {
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
	}

	// get methods
	public int getVid() {
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

	public double getUtc_time() {
		return utc_time;
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
        return JSONObject.toJSONString(jsonObj);
	}
	
	public int hashCode(){
        return Objects.hash(super.hashCode(), vid, latitude, longitude, altitude, heading, velocity, climb, utc_time);
	}
	
}
