package mets_r.communication;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;
import mets_r.mobility.Vehicle;

public class LinkTravelTimeDataStream {
	public int vid;
	public int vehType;
	public int roadID;
	public double linkTravelTime;
	public double utc_time;
	public double length;
	public double averageSpeed;
	public double delayRatio;
	private HashMap<String, Object> messagingLayer;
	private HashMap<String, Object> communicationLayer;
	private HashMap<String, Object> securityLayer;
	private HashMap<String, Object> qualityLayer;
	
	public LinkTravelTimeDataStream(int vid, int vehType, int roadID, double linkTravelTime, double length) {
		this(vid, vehType, roadID, linkTravelTime, length, ContextCreator.getCurrentTick());
	}
	
	public LinkTravelTimeDataStream(int vid, int vehType, int roadID, double linkTravelTime, double length, double utc_time) {
		this.vid = vid;
		this.vehType = vehType;
		this.roadID = roadID;
		this.linkTravelTime = linkTravelTime;
		this.length = length;
		this.utc_time = utc_time;
		this.averageSpeed = linkTravelTime > 0.0 && length > 0.0 ? length / linkTravelTime : 0.0;
		this.delayRatio = length > 0.0 && averageSpeed > 0.0 ? linkTravelTime / Math.max(1.0, length / 13.4112) : 0.0;
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

	public double getLinkTravelTime() {
		return linkTravelTime;
	}
	
	public double getLength() {
		return length;
	}

	public double getUTC_time() {
		return utc_time;
	}

	public double getAverageSpeed() {
		return averageSpeed;
	}

	private void initializeMetadata() {
		int payloadBytes = 84;
		this.messagingLayer = this.buildProbeLayer();
		this.communicationLayer = WaveMessageMetadata.buildCommunicationLayer(Vehicle.MOBILEDEVICE,
				"METS_R_LINK_TRAVEL_TIME_PROBE", "0x8002", WaveMessageMetadata.probeServiceChannel(),
				payloadBytes, 3, WaveMessageMetadata.probeRangeMeters());
		this.securityLayer = WaveMessageMetadata.buildSecurityLayer(vid, "METS_R_LINK_TRAVEL_TIME_PROBE",
				payloadBytes);
		this.qualityLayer = WaveMessageMetadata.buildQualityLayer(payloadBytes,
				this.layerLatencyMs(this.communicationLayer, this.securityLayer),
				linkTravelTime > 0.0 && length > 0.0 ? 0.85 : 0.25);
	}

	private HashMap<String, Object> buildProbeLayer() {
		HashMap<String, Object> layer = new LinkedHashMap<String, Object>();
		HashMap<String, Object> probe = new LinkedHashMap<String, Object>();
		HashMap<String, Object> quality = new LinkedHashMap<String, Object>();

		probe.put("vehicle_id", vid);
		probe.put("vehicle_type", vehType);
		probe.put("road_id", roadID);
		probe.put("travel_time_s", WaveMessageMetadata.round3(linkTravelTime));
		probe.put("length_m", WaveMessageMetadata.round3(length));
		probe.put("average_speed_mps", WaveMessageMetadata.round3(averageSpeed));
		probe.put("average_speed_mph", WaveMessageMetadata.round3(averageSpeed * 2.2369362921));
		probe.put("delay_ratio_vs_30mph", WaveMessageMetadata.round3(delayRatio));
		probe.put("measurement_time", utc_time);

		quality.put("sample_type", "single_vehicle_link_probe");
		quality.put("source_sensor", "mobile_device");
		quality.put("valid_length", length > 0.0);
		quality.put("valid_travel_time", linkTravelTime > 0.0);

		layer.put("standard", "METS-R probe extension");
		layer.put("message_name", "LinkTravelTimeProbe");
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
		jsonObj.put("travel_time", linkTravelTime);
		jsonObj.put("length", length); 
		jsonObj.put("average_speed", averageSpeed);
		jsonObj.put("delay_ratio", delayRatio);
		jsonObj.put("utc_time", utc_time);
		jsonObj.put("messaging_layer", messagingLayer);
		jsonObj.put("communication_layer", communicationLayer);
		jsonObj.put("security_layer", securityLayer);
		jsonObj.put("quality_layer", qualityLayer);
		return JSONObject.toJSONString(jsonObj);
	}
	
	public int hashCode(){
        return Objects.hash(super.hashCode(), vid, roadID, linkTravelTime, utc_time);
	}
	
}
