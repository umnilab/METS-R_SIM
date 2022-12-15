package mets_r.data;

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

public class BackgroundDemand {
	public TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> travelDemand; // The outer key is the origin and the
																				// inner key is the destination
	public List<Integer> waitingThreshold;
	public TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> sharePercentage;
	

	public BackgroundDemand() {
		ContextCreator.logger.info("Read demand.");
		travelDemand = new TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>>();
		waitingThreshold = new ArrayList<Integer>();
		sharePercentage = new TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>>();
		readDemandFile();
		readWaitTimeFile();
		if(GlobalVariables.DEMAND_SHARABLE) {
			readSharePercentFile();
		}
	}

	@SuppressWarnings("unchecked")
	public void readDemandFile() {
		JSONParser parser = new JSONParser();
		try {
			Object obj = parser.parse(new FileReader(GlobalVariables.DM_EVENT_FILE));
			JSONObject jsonObject = (JSONObject) obj;

			for (String OD : (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = (ArrayList<Double>) jsonObject.get(OD);

				String[] inds = OD.split(",");
				int originInd = Integer.parseInt(inds[0].replace("(", "").trim());
				;
				int destInd = Integer.parseInt(inds[1].replace(")", "").trim());

				if (!travelDemand.containsKey(originInd)) {
					travelDemand.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
				}

				travelDemand.get(originInd).put(destInd, value);
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
		File dataFile = new File(GlobalVariables.DM_WAITING_TIME);
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
			Object obj = parser.parse(new FileReader(GlobalVariables.DM_EVENT_FILE));
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
}