package mets_r.communication;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;
import mets_r.mobility.Vehicle;

public class LinkEnergyDataStream {
	public int vid;
	public int vehType;
	public int roadID;
	public double linkEnergy;
	public double utc_time;
	private HashMap<String, Object> messagingLayer;
	private HashMap<String, Object> communicationLayer;
	private HashMap<String, Object> securityLayer;
	private HashMap<String, Object> qualityLayer;
	
	public LinkEnergyDataStream(int vid, int vehType, int roadID, double linkEnergy) {
		this(vid, vehType, roadID, linkEnergy, ContextCreator.getCurrentTick());
	}
	
	public LinkEnergyDataStream(int vid, int vehType, int roadID, double linkEnergy, double utc_time) {
		this.vid = vid;
		this.vehType = vehType;
		this.roadID = roadID;
		this.linkEnergy = linkEnergy;
		this.utc_time = utc_time;
		this.initializeMetadata();
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

	private void initializeMetadata() {
		int payloadBytes = 76;
		this.messagingLayer = this.buildProbeLayer();
		this.communicationLayer = WaveMessageMetadata.buildCommunicationLayer(Vehicle.MOBILEDEVICE,
				"METS_R_LINK_ENERGY_PROBE", "0x8003", WaveMessageMetadata.probeServiceChannel(),
				payloadBytes, 3, WaveMessageMetadata.probeRangeMeters());
		this.securityLayer = WaveMessageMetadata.buildSecurityLayer(vid, "METS_R_LINK_ENERGY_PROBE",
				payloadBytes);
		this.qualityLayer = WaveMessageMetadata.buildQualityLayer(payloadBytes,
				this.layerLatencyMs(this.communicationLayer, this.securityLayer),
				Double.isNaN(linkEnergy) || Double.isInfinite(linkEnergy) ? 0.2 : 0.85);
	}

	private HashMap<String, Object> buildProbeLayer() {
		HashMap<String, Object> layer = new LinkedHashMap<String, Object>();
		HashMap<String, Object> probe = new LinkedHashMap<String, Object>();
		HashMap<String, Object> quality = new LinkedHashMap<String, Object>();

		probe.put("vehicle_id", vid);
		probe.put("vehicle_type", vehType);
		probe.put("road_id", roadID);
		probe.put("link_energy", WaveMessageMetadata.round3(linkEnergy));
		probe.put("energy_unit", "simulation_native");
		probe.put("measurement_time", utc_time);

		quality.put("sample_type", "single_vehicle_link_energy_probe");
		quality.put("source_sensor", "mobile_device");
		quality.put("valid_energy", !Double.isNaN(linkEnergy) && !Double.isInfinite(linkEnergy));
		quality.put("nonnegative_energy", linkEnergy >= 0.0);

		layer.put("standard", "METS-R probe extension");
		layer.put("message_name", "LinkEnergyProbe");
		layer.put("probe_data", probe);
		layer.put("quality", quality);
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
	
	@Override
	public String toString() {
		// to json string
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();		
		jsonObj.put("vid", vid);
		jsonObj.put("veh_type", vehType);
		jsonObj.put("road_id", roadID);
		jsonObj.put("link_energy", linkEnergy);
		jsonObj.put("utc_time", utc_time);
		jsonObj.put("messaging_layer", messagingLayer);
		jsonObj.put("communication_layer", communicationLayer);
		jsonObj.put("security_layer", securityLayer);
		jsonObj.put("quality_layer", qualityLayer);
		return JSONObject.toJSONString(jsonObj);
	}
	
	public int hashCode(){
        return Objects.hash(super.hashCode(), vid, roadID, linkEnergy, utc_time);
	}
}
