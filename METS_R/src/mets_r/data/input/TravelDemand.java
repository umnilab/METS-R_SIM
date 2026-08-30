package mets_r.data.input;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import au.com.bytecode.opencsv.CSVReader;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;

import java.util.*;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Read hourly demand into treemap with origin, destination zone indexes as the keys
 * @author: Zengxiang Lei 
 **/

public class TravelDemand {
	private static final int DEFAULT_WAITING_THRESHOLD_SECONDS = 10 * 60;

	private TreeMap<Integer, TreeMap<Integer, TreeMap<Integer, Integer>>> privateEVTravelDemandByOrigin;
	private TreeMap<Integer, TreeMap<Integer, TreeMap<Integer, Integer>>> privateGVTravelDemandByOrigin;
	private TreeMap<Integer, ArrayList<Double>> privateEVProfile;
	
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> publicTravelDemand; // The outer key is the origin and the
																				// inner key is the destination
	private List<Integer> waitingThreshold;
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> sharePercentage;
	
	private BufferedReader privateEVTripReader;
	private BufferedReader privateGVTripReader;
	private BufferedReader privateEVProfileReader;
	private String pendingPrivateEVTripLine;
	private String pendingPrivateGVTripLine;
	private boolean privateEVTripReaderExhausted;
	private boolean privateGVTripReaderExhausted;
	private volatile boolean privateDemandActive;
	
	private volatile boolean closed = false;
	private int hour;

	public TravelDemand() {
		ContextCreator.logger.info("Read demand.");
		privateEVTravelDemandByOrigin = new TreeMap<Integer, TreeMap<Integer, TreeMap<Integer, Integer>>>();
		privateGVTravelDemandByOrigin = new TreeMap<Integer, TreeMap<Integer, TreeMap<Integer, Integer>>>();
		privateEVProfile = new TreeMap<Integer, ArrayList<Double>>();
		publicTravelDemand = new TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>>();
		waitingThreshold = new ArrayList<Integer>();
		sharePercentage = new TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>>();
		readPublicDemandFile();
		readWaitTimeFile();
		if(GlobalVariables.RH_DEMAND_SHARABLE) {
			readSharePercentFile();
		}
		
		readPrivateDemandFile();
		
		
		hour = 0;
	}
	
	public void readPrivateDemandFile() {
		try {
			privateEVTripReader = new BufferedReader(new FileReader(GlobalVariables.EV_DEMAND_FILE));
			privateGVTripReader = new BufferedReader(new FileReader(GlobalVariables.GV_DEMAND_FILE));
			privateEVProfileReader =  new BufferedReader(new FileReader(GlobalVariables.EV_CHARGING_PREFERENCE));
			
			// skip the first line
			privateEVTripReader.readLine();
			privateGVTripReader.readLine();
			privateEVProfileReader.readLine();

			this.pendingPrivateEVTripLine = privateEVTripReader.readLine();
			this.pendingPrivateGVTripLine = privateGVTripReader.readLine();
			this.privateEVTripReaderExhausted = this.pendingPrivateEVTripLine == null;
			this.privateGVTripReaderExhausted = this.pendingPrivateGVTripLine == null;
			this.privateDemandActive = !this.privateEVTripReaderExhausted || !this.privateGVTripReaderExhausted;
			if (!this.privateDemandActive) {
				closePrivateTripReaders();
			}
			
			String line = privateEVProfileReader.readLine();
			while(line != null) {
				String[] result = line.split(",");
				int vehID = Integer.parseInt(result[0]);
				ArrayList<Double> vehConfig = new ArrayList<Double>();
				vehConfig.add(Double.parseDouble(result[1]));
				vehConfig.add(Double.parseDouble(result[2]));
				vehConfig.add(Double.parseDouble(result[3]));
				vehConfig.add(Double.parseDouble(result[4]));
				
				privateEVProfile.put(vehID, vehConfig);
				
				line = privateEVProfileReader.readLine();
			}
			privateEVProfileReader.close();
			
		} catch (Exception e) {
			this.privateEVTripReaderExhausted = true;
			this.privateGVTripReaderExhausted = true;
			this.privateDemandActive = false;
			try {
				if (this.privateEVProfileReader != null) this.privateEVProfileReader.close();
			} catch (IOException closeFailure) {
				e.addSuppressed(closeFailure);
			}
			closePrivateTripReaders();
			throw new IllegalStateException("Failed to load private travel demand inputs", e);
		}
		
	}

	@SuppressWarnings("unchecked")
	public void readPublicDemandFile() {
		JSONParser parser = new JSONParser();
		try {
			Object obj;
			try (FileReader demandReader =
					new FileReader(GlobalVariables.RH_DEMAND_FILE)) {
				obj = parser.parse(demandReader);
			}
			JSONObject jsonObject = (JSONObject) obj;

			for (String OD : (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = parseHourlyValues(
						jsonObject.get(OD), OD, false);
				int[] od = parseODKey(OD);
				int originInd = od[0];
				int destInd = od[1];

				if (!publicTravelDemand.containsKey(originInd)) {
					publicTravelDemand.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
				}

				publicTravelDemand.get(originInd).put(destInd, value);
			}
			
		} catch (Exception e) {
			throw new IllegalStateException(
					"Failed to load ride-hailing demand from "
							+ GlobalVariables.RH_DEMAND_FILE, e);
		}
	}
	
	public void loadPrivateDemandChunk() { // For loading the next hour demand
		if (closed || !privateDemandActive) return; // stale or exhausted call
		// Add the logger
		int ev_trip_number = 0;
		int gv_trip_number = 0;
		
		// clear the previous chunk
	    clearOldIndexedPrivateDemand(this.privateGVTravelDemandByOrigin, hour * 60);
	    clearOldIndexedPrivateDemand(this.privateEVTravelDemandByOrigin, hour * 60);
		// read the new chunk
	    try {
			int chunkEndMinute = (hour + 1) * 60;
		    ev_trip_number = loadPrivateEVTripsUntil(chunkEndMinute);
		    gv_trip_number = loadPrivateGVTripsUntil(chunkEndMinute);
		    updatePrivateDemandActive();
		    
		    if (ev_trip_number > 0 || gv_trip_number > 0) {
				ContextCreator.logger.info("Private trips at hour " + hour + " has been loaded, EV trip number: " + ev_trip_number + ", GV trip number: " + gv_trip_number);
		    }
	    }
	    catch (IOException e) {
			throw new IllegalStateException("Failed to load the private trip demand chunk", e);
	    }
	    
	    hour += 1;
		
	}

	private int loadPrivateEVTripsUntil(int chunkEndMinute) throws IOException {
		int tripNumber = 0;
		while (this.pendingPrivateEVTripLine != null) {
			PrivateTripRecord trip = parsePrivateTrip(this.pendingPrivateEVTripLine);
			if (trip == null) {
				this.pendingPrivateEVTripLine = privateEVTripReader.readLine();
				continue;
			}
			if (trip.timeIndex > chunkEndMinute) {
				break;
			}
			addPrivateDemand(privateEVTravelDemandByOrigin, trip.timeIndex, trip.vehicleID, trip.origin, trip.dest);
			tripNumber += 1;
			this.pendingPrivateEVTripLine = privateEVTripReader.readLine();
		}
		if (this.pendingPrivateEVTripLine == null) {
			this.privateEVTripReaderExhausted = true;
		}
		return tripNumber;
	}

	private int loadPrivateGVTripsUntil(int chunkEndMinute) throws IOException {
		int tripNumber = 0;
		while (this.pendingPrivateGVTripLine != null) {
			PrivateTripRecord trip = parsePrivateTrip(this.pendingPrivateGVTripLine);
			if (trip == null) {
				this.pendingPrivateGVTripLine = privateGVTripReader.readLine();
				continue;
			}
			if (trip.timeIndex > chunkEndMinute) {
				break;
			}
			addPrivateDemand(privateGVTravelDemandByOrigin, trip.timeIndex, trip.vehicleID, trip.origin, trip.dest);
			tripNumber += 1;
			this.pendingPrivateGVTripLine = privateGVTripReader.readLine();
		}
		if (this.pendingPrivateGVTripLine == null) {
			this.privateGVTripReaderExhausted = true;
		}
		return tripNumber;
	}

	private PrivateTripRecord parsePrivateTrip(String line) {
		if (line == null || line.isEmpty()) return null;
		String[] result = line.split(",");
		if (result.length < 4) return null;
		return new PrivateTripRecord(Integer.parseInt(result[0]), Integer.parseInt(result[1]),
				Integer.parseInt(result[2]) + 1, Integer.parseInt(result[3]) + 1);
	}

	private void addPrivateDemand(TreeMap<Integer, TreeMap<Integer, TreeMap<Integer, Integer>>> demandByTimeAndOrigin,
			int timeIndex, int vid, int origin, int dest) {
		if (!demandByTimeAndOrigin.containsKey(timeIndex)) {
			demandByTimeAndOrigin.put(timeIndex, new TreeMap<Integer, TreeMap<Integer, Integer>>());
		}
		TreeMap<Integer, TreeMap<Integer, Integer>> demandAtTime = demandByTimeAndOrigin.get(timeIndex);
		if (!demandAtTime.containsKey(origin)) {
			demandAtTime.put(origin, new TreeMap<Integer, Integer>());
		}
		demandAtTime.get(origin).put(vid, dest);
	}

	private void clearOldIndexedPrivateDemand(
			TreeMap<Integer, TreeMap<Integer, TreeMap<Integer, Integer>>> demandByTimeAndOrigin,
			int earliestTimeToKeep) {
		demandByTimeAndOrigin.headMap(earliestTimeToKeep).clear();
	}
	
	public void readWaitTimeFile() {
		File dataFile = new File(GlobalVariables.RH_WAITING_TIME);
		CSVReader csvreader = null;
		String[] nextLine;
		try {
			csvreader = new CSVReader(new FileReader(dataFile), ',');
			int row = 0;
			while ((nextLine = csvreader.readNext()) != null) {
				if (nextLine.length == 0 || nextLine[0] == null
						|| nextLine[0].trim().isEmpty()) {
					throw new IllegalArgumentException(
							"Empty waiting-time value at row " + (row + 1));
				}
				try {
					double threshold = Double.parseDouble(nextLine[0].trim());
					if (!Double.isFinite(threshold) || threshold < 0.0) {
						throw new IllegalArgumentException(
								"Invalid waiting-time value at row " + (row + 1));
					}
					waitingThreshold.add((int) Math.ceil(threshold));
				} catch (NumberFormatException nfe) {
					if (row != 0) throw nfe;
				}
				row++;
			}
			if (waitingThreshold.isEmpty()) {
				waitingThreshold.addAll(Collections.nCopies(
						GlobalVariables.HOUR_OF_DEMAND, DEFAULT_WAITING_THRESHOLD_SECONDS));
			} else if (waitingThreshold.size() < GlobalVariables.HOUR_OF_DEMAND) {
				throw new IllegalArgumentException("Waiting-time input contains "
						+ waitingThreshold.size() + " values; expected at least "
						+ GlobalVariables.HOUR_OF_DEMAND);
			}
		} catch (Exception e) {
			throw new IllegalStateException(
					"Failed to load ride-hailing waiting times from "
							+ GlobalVariables.RH_WAITING_TIME, e);
		} finally {
			if (csvreader != null) {
				try {
					csvreader.close();
				} catch (IOException ignored) {
					// The parse result or original read failure is more actionable.
				}
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	public void readSharePercentFile() {
		JSONParser parser = new JSONParser();
		try {
			Object obj;
			try (FileReader shareReader =
					new FileReader(GlobalVariables.RH_SHARE_PERCENTAGE)) {
				obj = parser.parse(shareReader);
			}
			JSONObject jsonObject = (JSONObject) obj;

			for (String OD : (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = parseHourlyValues(
						jsonObject.get(OD), OD, true);
				int[] od = parseODKey(OD);
				int originInd = od[0];
				int destInd = od[1];

				if (!sharePercentage.containsKey(originInd)) {
					sharePercentage.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
				}

				sharePercentage.get(originInd).put(destInd, value);
			}
		} catch (Exception e) {
			throw new IllegalStateException(
					"Failed to load ride-sharing percentages from "
							+ GlobalVariables.RH_SHARE_PERCENTAGE, e);
		}
	}
	
	public Map<Integer, Integer> getPrivateEVTravelDemand(int timeIndex, int originID) {
		return getPrivateTravelDemandByOrigin(this.privateEVTravelDemandByOrigin, timeIndex, originID);
	}
	
	public ArrayList<Double> getPrivateEVProfile(int vehID){
		if(privateEVProfile.containsKey(vehID))
			return privateEVProfile.get(vehID);
		else
			return null;
	}
	
	public Map<Integer, Integer> getPrivateGVTravelDemand(int timeIndex, int originID) {
		return getPrivateTravelDemandByOrigin(this.privateGVTravelDemandByOrigin, timeIndex, originID);
	}

	public boolean hasPrivateDemandAtTime(int timeIndex) {
		if (!privateDemandActive) return false;
		synchronized (this) {
			return this.privateEVTravelDemandByOrigin.containsKey(timeIndex)
					|| this.privateGVTravelDemandByOrigin.containsKey(timeIndex);
		}
	}

	public boolean hasAnyPrivateDemand() {
		return this.privateDemandActive;
	}

	private void updatePrivateDemandActive() {
		this.privateDemandActive = !this.privateEVTripReaderExhausted || !this.privateGVTripReaderExhausted
				|| !this.privateEVTravelDemandByOrigin.isEmpty() || !this.privateGVTravelDemandByOrigin.isEmpty();
		if (!this.privateDemandActive) {
			closePrivateTripReaders();
		}
	}

	private synchronized Map<Integer, Integer> getPrivateTravelDemandByOrigin(
			TreeMap<Integer, TreeMap<Integer, TreeMap<Integer, Integer>>> demandByTimeAndOrigin,
			int timeIndex, int originID) {
		TreeMap<Integer, TreeMap<Integer, Integer>> demandAtTime = demandByTimeAndOrigin.get(timeIndex);
		if (demandAtTime == null) {
			return Collections.emptyMap();
		}
		TreeMap<Integer, Integer> demandAtOrigin = demandAtTime.remove(originID);
		if (demandAtOrigin == null) {
			return Collections.emptyMap();
		}
		if (demandAtTime.isEmpty()) {
			demandByTimeAndOrigin.remove(timeIndex);
			updatePrivateDemandActive();
		}
		return demandAtOrigin;
	}
	
	public double getPublicTravelDemand(int originID, int destID, int hour) {
		if (publicTravelDemand.containsKey(originID)) {
			if (publicTravelDemand.get(originID).containsKey(destID)) {
				ArrayList<Double> values = publicTravelDemand.get(originID).get(destID);
				if (hour >= 0 && hour < values.size()) {
					return values.get(hour);
				}

			}
		}
		return 0d;
	}
	
	public ArrayList<Double> getPublicTravelDemand(int originID, int destID) {
		if (publicTravelDemand.containsKey(originID)) {
			if (publicTravelDemand.get(originID).containsKey(destID)) {
				return publicTravelDemand.get(originID).get(destID);
			}
		}
		return new ArrayList<Double>(Collections.nCopies(GlobalVariables.HOUR_OF_DEMAND, 0.0d));
	}
	
	public double getSharableRate(int originID, int destID, int hour) {
		TreeMap<Integer, ArrayList<Double>> destinations = sharePercentage.get(originID);
		if (destinations != null) {
			ArrayList<Double> values = destinations.get(destID);
			if (values != null && hour >= 0 && hour < values.size()) {
				return values.get(hour);
			}
		}
		return 0d;
	}
	
	public int getWaitingThreshold(int hour) {
		if (hour >= 0 && waitingThreshold.size() > hour) {
			return waitingThreshold.get(hour);
		}
		return DEFAULT_WAITING_THRESHOLD_SECONDS;
	}

	private static int[] parseODKey(String key) {
		if (key == null) throw new IllegalArgumentException("Null OD key");
		String normalized = key.replace("(", "").replace(")", "");
		String[] fields = normalized.split(",");
		if (fields.length != 2) {
			throw new IllegalArgumentException("Invalid OD key: " + key);
		}
		int origin = Integer.parseInt(fields[0].trim()) + 1;
		int destination = Integer.parseInt(fields[1].trim()) + 1;
		if (origin <= 0 || destination <= 0) {
			throw new IllegalArgumentException("Invalid OD zone IDs: " + key);
		}
		return new int[] { origin, destination };
	}

	private static ArrayList<Double> parseHourlyValues(
			Object rawValues, String odKey, boolean probability) {
		if (!(rawValues instanceof List<?>)) {
			throw new IllegalArgumentException("Hourly values are not an array for " + odKey);
		}
		List<?> input = (List<?>) rawValues;
		if (input.size() < GlobalVariables.HOUR_OF_DEMAND) {
			throw new IllegalArgumentException("OD " + odKey + " contains "
					+ input.size() + " hourly values; expected at least "
					+ GlobalVariables.HOUR_OF_DEMAND);
		}
		ArrayList<Double> values = new ArrayList<Double>(input.size());
		for (int hour = 0; hour < input.size(); hour++) {
			Object rawValue = input.get(hour);
			if (!(rawValue instanceof Number)) {
				throw new IllegalArgumentException(
						"Non-numeric hourly value for " + odKey + " at hour " + hour);
			}
			double value = ((Number) rawValue).doubleValue();
			if (!Double.isFinite(value) || value < 0.0
					|| (probability && value > 1.0)) {
				throw new IllegalArgumentException(
						"Invalid hourly value for " + odKey + " at hour " + hour
								+ ": " + value);
			}
			values.add(value);
		}
		return values;
	}

	private static class PrivateTripRecord {
		final int vehicleID;
		final int timeIndex;
		final int origin;
		final int dest;

		PrivateTripRecord(int vehicleID, int timeIndex, int origin, int dest) {
			this.vehicleID = vehicleID;
			this.timeIndex = timeIndex;
			this.origin = origin;
			this.dest = dest;
		}
	}
	
	public void close() {
		closed = true;
		closePrivateTripReaders();
	}

	private void closePrivateTripReaders() {
		try {
			if (this.privateEVTripReader != null) this.privateEVTripReader.close();
			if (this.privateGVTripReader != null) this.privateGVTripReader.close();
		} catch (IOException e) {
			ContextCreator.logger.warn("Failed to close private-demand input", e);
		}
	}
}
