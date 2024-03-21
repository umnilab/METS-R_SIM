package mets_r.communication;

import java.util.HashMap;
import java.util.Objects;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;

public class LinkEnergyDataStream {
	public int vid;
	public int vehType;
	public int roadID;
	public double linkEnergy;
	public double utc_time;
	
	public LinkEnergyDataStream(int vid, int vehType, int roadID, double linkEnergy) {
		this(vid, vehType, roadID, linkEnergy, ContextCreator.getCurrentTick());
	}
	
	public LinkEnergyDataStream(int vid, int vehType, int roadID, double linkEnergy, double utc_time) {
		this.vid = vid;
		this.vehType = vehType;
		this.roadID = roadID;
		this.linkEnergy = linkEnergy;
		this.utc_time = utc_time;
	}

	public int getVID() {
		return vid;
	}

	public int getVehType() {
		return vehType;
	}

	public int getRoadID() {
		return roadID;
	}

	public double getLinkEnergy() {
		return linkEnergy;
	}

	public double getUTC_time() {
		return utc_time;
	}
	
	@Override
	public String toString() {
		// to json string
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();		
		jsonObj.put("vid", vid);
		jsonObj.put("veh_type", vehType);
		jsonObj.put("road_type", roadID);
		jsonObj.put("link_energy", linkEnergy);
		jsonObj.put("utc_time", utc_time);
		return JSONObject.toJSONString(jsonObj);
	}
	
	public int hashCode(){
        return Objects.hash(super.hashCode(), vid, roadID, linkEnergy, utc_time);
	}
}
