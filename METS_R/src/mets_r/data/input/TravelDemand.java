package mets_r.data.input;

import java.io.File;
import java.io.FileNotFoundException;
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
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> privateEVTravelDemand;
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> privateGVTravelDemand;
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> publicTravelDemand; // The outer key is the origin and the
																				// inner key is the destination
	private List<Integer> waitingThreshold;
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> sharePercentage;
	

	public TravelDemand() {
		ContextCreator.logger.info("Read demand.");
		privateEVTravelDemand = new TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>>();
		privateGVTravelDemand = new TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>>();
		publicTravelDemand = new TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>>();
		waitingThreshold = new ArrayList<Integer>();
		sharePercentage = new TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>>();
		readDemandFile();
		readWaitTimeFile();
		if(GlobalVariables.RH_DEMAND_SHARABLE) {
			readSharePercentFile();
		}
	}

	@SuppressWarnings("unchecked")
	public void readDemandFile() {
		JSONParser parser = new JSONParser();
		try {
			Object obj = parser.parse(new FileReader(GlobalVariables.RH_DEMAND_FILE));
			JSONObject jsonObject = (JSONObject) obj;

			for (String OD : (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = (ArrayList<Double>) jsonObject.get(OD);

				String[] inds = OD.split(",");
				int originInd = Integer.parseInt(inds[0].replace("(", "").trim());
				;
				int destInd = Integer.parseInt(inds[1].replace(")", "").trim());

				if (!publicTravelDemand.containsKey(originInd)) {
					publicTravelDemand.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
				}

				publicTravelDemand.get(originInd).put(destInd, value);
			}
			
			obj = parser.parse(new FileReader(GlobalVariables.EV_DEMAND_FILE));
			jsonObject = (JSONObject) obj;
			for (String OD : (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = (ArrayList<Double>) jsonObject.get(OD);

				String[] inds = OD.split(",");
				int originInd = Integer.parseInt(inds[0].replace("(", "").trim());
				;
				int destInd = Integer.parseInt(inds[1].replace(")", "").trim());

				if (!privateEVTravelDemand.containsKey(originInd)) {
					privateEVTravelDemand.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
				}

				privateEVTravelDemand.get(originInd).put(destInd, value);
			}
			
			obj = parser.parse(new FileReader(GlobalVariables.GV_DEMAND_FILE));
			jsonObject = (JSONObject) obj;
			for (String OD : (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = (ArrayList<Double>) jsonObject.get(OD);

				String[] inds = OD.split(",");
				int originInd = Integer.parseInt(inds[0].replace("(", "").trim());
				;
				int destInd = Integer.parseInt(inds[1].replace(")", "").trim());

				if (!privateGVTravelDemand.containsKey(originInd)) {
					privateGVTravelDemand.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
				}

				privateGVTravelDemand.get(originInd).put(destInd, value);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void readWaitTimeFile() {
		File dataFile = new File(GlobalVariables.RH_WAITING_TIME);
		CSVReader csvreader = null;
		String[] nextLine;
		try {
			csvreader = new CSVReader(new FileReader(dataFile), ',');
			boolean hasHeader = false;
			while ((nextLine = csvreader.readNext()) != null) {
				// skip the first row (header)
				if (hasHeader) {
					hasHeader = false;

				} else {
					waitingThreshold.add((int)Double.parseDouble(nextLine[0]));
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings("unchecked")
	public void readSharePercentFile() {
		JSONParser parser = new JSONParser();
		try {
			Object obj = parser.parse(new FileReader(GlobalVariables.RH_DEMAND_FILE));
			JSONObject jsonObject = (JSONObject) obj;

			for (String OD : (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = (ArrayList<Double>) jsonObject.get(OD);

				String[] inds = OD.split(",");
				int originInd = Integer.parseInt(inds[0].replace("(", "").trim());
				;
				int destInd = Integer.parseInt(inds[1].replace(")", "").trim());

				if (!sharePercentage.containsKey(originInd)) {
					sharePercentage.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
				}

				sharePercentage.get(originInd).put(destInd, value);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public double getPrivateEVTravelDemand(int originID, int destID, int hour) {
		if (privateEVTravelDemand.containsKey(originID)) {
			if (privateEVTravelDemand.get(originID).containsKey(destID)) {
				if (hour < GlobalVariables.HOUR_OF_DEMAND) {
					return privateEVTravelDemand.get(originID).get(destID).get(hour);
				}
			}
		}
		return 0d;
	}
	
	public double getPrivateGVTravelDemand(int originID, int destID, int hour) {
		if (privateGVTravelDemand.containsKey(originID)) {
			if (privateGVTravelDemand.get(originID).containsKey(destID)) {
				if (hour < GlobalVariables.HOUR_OF_DEMAND) {
					return privateGVTravelDemand.get(originID).get(destID).get(hour);
				}
			}
		}
		return 0d;
	}
	
	public double getPublicTravelDemand(int originID, int destID, int hour) {
		if (publicTravelDemand.containsKey(originID)) {
			if (publicTravelDemand.get(originID).containsKey(destID)) {
				if (hour < GlobalVariables.HOUR_OF_DEMAND) {
					return publicTravelDemand.get(originID).get(destID).get(hour);
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
		if (sharePercentage.containsKey(originID)) {
			if (sharePercentage.get(originID).containsKey(destID)) {
				if (hour < GlobalVariables.HOUR_OF_DEMAND) {
					return sharePercentage.get(originID).get(destID).get(hour);
				}

			}
		}
		return 0d;
	}
	
	public int getWaitingThreshold(int hour) {
		if (waitingThreshold.size() > hour) {
			return waitingThreshold.get(hour);
		}
		return 600;
	}
}