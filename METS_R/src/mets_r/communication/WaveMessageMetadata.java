package mets_r.communication;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;

import mets_r.mobility.Vehicle;

final class WaveMessageMetadata {
	private static final int BSM_CHANNEL = 172;
	private static final int CONTROL_CHANNEL = 178;
	private static final int PROBE_SERVICE_CHANNEL = 174;
	private static final double CHANNEL_BANDWIDTH_MHZ = 10.0;
	private static final double DATA_RATE_MBPS = 6.0;
	private static final double TX_POWER_DBM = 20.0;
	private static final double ANTENNA_GAIN_DBI = 3.0;
	private static final double NOISE_FIGURE_DB = 9.0;
	private static final double BSM_RANGE_M = 300.0;
	private static final double PROBE_RANGE_M = 200.0;

	private WaveMessageMetadata() {
	}

	static int bsmChannel() {
		return BSM_CHANNEL;
	}

	static int probeServiceChannel() {
		return PROBE_SERVICE_CHANNEL;
	}

	static double bsmRangeMeters() {
		return BSM_RANGE_M;
	}

	static double probeRangeMeters() {
		return PROBE_RANGE_M;
	}

	static HashMap<String, Object> buildCommunicationLayer(int sensorType, String messageFamily, String psid,
			int serviceChannel, int payloadBytes, int userPriority, double rangeMeters) {
		HashMap<String, Object> layer = new LinkedHashMap<String, Object>();
		HashMap<String, Object> channel = new LinkedHashMap<String, Object>();
		HashMap<String, Object> phy = new LinkedHashMap<String, Object>();
		HashMap<String, Object> mac = new LinkedHashMap<String, Object>();

		double channelBusyRatio = clamp(0.04 + randomDouble() * 0.32 + Math.min(0.20, payloadBytes / 7500.0),
				0.01, 0.95);
		double frequencyMhz = 5000.0 + serviceChannel * 5.0;
		double thermalNoiseDbm = -174.0 + 10.0 * Math.log10(CHANNEL_BANDWIDTH_MHZ * 1_000_000.0) + NOISE_FIGURE_DB;
		double interferenceDbm = -103.0 + 28.0 * channelBusyRatio + randomDouble() * 4.0;
		double rxPowerDbm = receivedPowerDbm(frequencyMhz, rangeMeters);
		double noiseAndInterferenceDbm = addDbm(thermalNoiseDbm, interferenceDbm);
		double sinrDb = rxPowerDbm - noiseAndInterferenceDbm;
		double packetErrorRate = clamp(0.003 + channelBusyRatio * 0.08 + Math.max(0.0, 12.0 - sinrDb) * 0.015,
				0.0, 0.95);
		int contentionWindowMin = userPriority <= 2 ? 3 : 7;
		int backoffSlots = (int) Math.round(randomDouble() * contentionWindowMin * (1.0 + channelBusyRatio));
		double airtimeMs = payloadBytes / (DATA_RATE_MBPS * 125.0);
		double macLatencyMs = backoffSlots * 0.013 + channelBusyRatio * 4.0 + airtimeMs;

		channel.put("multi_channel_operation", true);
		channel.put("control_channel", CONTROL_CHANNEL);
		channel.put("service_channel", serviceChannel);
		channel.put("channel_role", serviceChannel == BSM_CHANNEL ? "safety_bsm" : "service_probe");
		channel.put("sync_interval_ms", 100);
		channel.put("channel_interval_ms", 50);
		channel.put("psid", psid);

		phy.put("radio_access", radioAccessName(sensorType));
		phy.put("carrier_frequency_mhz", round3(frequencyMhz));
		phy.put("bandwidth_mhz", CHANNEL_BANDWIDTH_MHZ);
		phy.put("data_rate_mbps", DATA_RATE_MBPS);
		phy.put("tx_power_dbm", TX_POWER_DBM);
		phy.put("antenna_gain_dbi", ANTENNA_GAIN_DBI);
		phy.put("range_m", round3(rangeMeters));
		phy.put("thermal_noise_dbm", round3(thermalNoiseDbm));
		phy.put("interference_dbm", round3(interferenceDbm));
		phy.put("rx_power_dbm_at_range", round3(rxPowerDbm));
		phy.put("sinr_db_at_range", round3(sinrDb));

		mac.put("edca_user_priority", userPriority);
		mac.put("access_category", userPriority <= 2 ? "AC_VO" : "AC_VI");
		mac.put("channel_busy_ratio", round3(channelBusyRatio));
		mac.put("backoff_slots", backoffSlots);
		mac.put("airtime_ms", round3(airtimeMs));
		mac.put("mac_latency_ms", round3(macLatencyMs));
		mac.put("packet_error_rate", round3(packetErrorRate));
		mac.put("delivery_probability", round3(1.0 - packetErrorRate));

		layer.put("stack", "DSRC/WAVE");
		layer.put("message_family", messageFamily);
		layer.put("standards", Arrays.asList("IEEE 802.11p PHY/MAC", "IEEE 1609.4 multi-channel"));
		layer.put("channel", channel);
		layer.put("phy", phy);
		layer.put("mac", mac);
		return layer;
	}

	static HashMap<String, Object> buildSecurityLayer(int vid, String messageFamily, int payloadBytes) {
		HashMap<String, Object> security = new LinkedHashMap<String, Object>();
		int signatureBytes = 64;
		int certificateDigestBytes = 8;
		int generationLocationBytes = 10;
		int securityOverheadBytes = signatureBytes + certificateDigestBytes + generationLocationBytes;

		security.put("standard", "IEEE 1609.2");
		security.put("profile", "signed_wsm_simplified");
		security.put("signed", true);
		security.put("certificate_strategy", "pseudonym_digest");
		security.put("certificate_id", certificateId(vid, messageFamily));
		security.put("signature_algorithm", "ECDSA_NIST_P256_SIM");
		security.put("payload_bytes", payloadBytes);
		security.put("signature_bytes", signatureBytes);
		security.put("certificate_digest_bytes", certificateDigestBytes);
		security.put("generation_location_bytes", generationLocationBytes);
		security.put("security_overhead_bytes", securityOverheadBytes);
		security.put("secured_message_bytes", payloadBytes + securityOverheadBytes);
		security.put("signing_latency_ms", round3(0.8 + payloadBytes * 0.003 + randomDouble() * 0.25));
		security.put("verification_latency_ms", round3(1.1 + payloadBytes * 0.004 + randomDouble() * 0.35));
		security.put("scms_model", "simplified_no_revocation_lookup");
		return security;
	}

	static HashMap<String, Object> buildQualityLayer(int payloadBytes, double latencyMs, double confidence) {
		HashMap<String, Object> quality = new LinkedHashMap<String, Object>();
		quality.put("payload_bytes", payloadBytes);
		quality.put("estimated_end_to_end_latency_ms", round3(latencyMs));
		quality.put("confidence", round3(clamp(confidence, 0.0, 1.0)));
		quality.put("simulation_only", true);
		return quality;
	}

	static int j2735MessageCount(double utcTime) {
		return Math.floorMod((int) Math.round(utcTime), 128);
	}

	static int dSecond(double utcTime) {
		return Math.floorMod((int) Math.round(utcTime * 1000.0), 60000);
	}

	static int temporaryId(int vid, double utcTime) {
		int fiveMinuteWindow = (int) Math.floor(Math.max(0.0, utcTime) / 300.0);
		return Objects.hash(vid, fiveMinuteWindow, "j2735-temporary-id");
	}

	static int latitudeE7(double latitude) {
		return (int) Math.round(clamp(latitude, -90.0, 90.0) * 10_000_000.0);
	}

	static int longitudeE7(double longitude) {
		return (int) Math.round(clamp(longitude, -180.0, 180.0) * 10_000_000.0);
	}

	static int elevationDecimeters(double altitude) {
		return (int) Math.round(altitude * 10.0);
	}

	static int speedRaw(double speedMetersPerSecond) {
		return (int) Math.round(Math.max(0.0, speedMetersPerSecond) / 0.02);
	}

	static int headingRaw(double headingDegrees) {
		return (int) Math.round(normalizeHeading(headingDegrees) / 0.0125);
	}

	static double normalizeHeading(double headingDegrees) {
		double heading = headingDegrees % 360.0;
		if (heading < 0.0) {
			heading += 360.0;
		}
		return heading;
	}

	static double round3(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String radioAccessName(int sensorType) {
		if (sensorType == Vehicle.DSRC) {
			return "DSRC_IEEE_802_11p";
		}
		if (sensorType == Vehicle.CV2X) {
			return "C_V2X_MODELED_WITH_WAVE_ENVELOPE";
		}
		if (sensorType == Vehicle.MOBILEDEVICE) {
			return "MOBILE_PROBE_MODELED_WITH_WAVE_ENVELOPE";
		}
		return "UNKNOWN_V2X_ACCESS";
	}

	private static double receivedPowerDbm(double frequencyMhz, double rangeMeters) {
		double distanceKm = Math.max(0.001, rangeMeters / 1000.0);
		double pathLossDb = 32.44 + 20.0 * Math.log10(frequencyMhz) + 20.0 * Math.log10(distanceKm);
		double shadowingDb = (randomDouble() - 0.5) * 6.0;
		return TX_POWER_DBM + ANTENNA_GAIN_DBI - pathLossDb + shadowingDb;
	}

	private static double addDbm(double firstDbm, double secondDbm) {
		double firstMw = Math.pow(10.0, firstDbm / 10.0);
		double secondMw = Math.pow(10.0, secondDbm / 10.0);
		return 10.0 * Math.log10(firstMw + secondMw);
	}

	private static String certificateId(int vid, String messageFamily) {
		return String.format("cert-%08x", Objects.hash(vid, messageFamily));
	}

	private static double randomDouble() {
		return BSMDataStream.RANDOM.nextDouble();
	}
}
