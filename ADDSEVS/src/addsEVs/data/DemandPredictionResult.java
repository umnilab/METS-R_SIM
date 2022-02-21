package addsEVs.data;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.lang3.StringUtils;

import addsEVs.GlobalVariables;
import au.com.bytecode.opencsv.CSVReader;

public class DemandPredictionResult {

	private static final HashMap<String, String> hubToPredictionFile = new HashMap<String, String>();

	// this is a taxi_zone --> predicted_demand map for a given hour
	private class HourPrediction {
		public HashMap<Integer, Double> taxiZoneToPrediction = new HashMap<Integer, Double>();
		public String date = "";
		public Integer hour = 0;
	}

	// this map stores predictions for each hour in a given day
	private class DayPrediction {
		public HashMap<Integer, HourPrediction> dayPrediction = new HashMap<Integer, DemandPredictionResult.HourPrediction>();
		public String date = "";
	}

	// This map stores prediction for each day of a given year
	private class YearPrediction {
		public HashMap<String, DayPrediction> yearPrediction = new HashMap<String, DemandPredictionResult.DayPrediction>();
	}

	// This store prediction for a each hub for a given year
	private static HashMap<String, YearPrediction> hubResults = new HashMap<String, DemandPredictionResult.YearPrediction>();

	public DemandPredictionResult() {
		String[] hubs = GlobalVariables.DEMAND_PREDICTION_HUBS.split(",");
		String resultsPath = GlobalVariables.DEMAND_PREDICTION_RESULTS_PATH;
		for (String hub : hubs) {
			String fname = resultsPath + "/" + hub + "PCA6.csv";
			hubToPredictionFile.put(hub, fname);
		}
		RefreshResult();
	}

	public double GetResultFor(String hub, String date, int hour, int taxiZone) {
		if (hubResults.containsKey(hub)) {
			YearPrediction yearPred = hubResults.get(hub);
			if (yearPred.yearPrediction.containsKey(date)) {
				DayPrediction dayPred = yearPred.yearPrediction.get(date);
				if (dayPred.dayPrediction.containsKey(hour)) {
					HourPrediction hourPred = dayPred.dayPrediction.get(hour);
					if (hourPred.taxiZoneToPrediction.containsKey(taxiZone)) {
						return hourPred.taxiZoneToPrediction.get(taxiZone);
					} else {
						System.out.println("ERROR : prediction for hub " + hub + ", date " + date + ", hour " + hour
								+ ", taxi zone " + taxiZone + " not found in prediction result. returning 0.0!");
					}
				} else {
					System.out.println("ERROR : prediction for hub " + hub + ", date " + date + ", hour " + hour
							+ " not found in prediction result. returning 0.0!");
				}

			} else {
				System.out.println("ERROR : prediction for hub " + hub + ", date " + date
						+ " not found in prediction result. returning 0.0!");
			}
		} else {
			System.out.println("ERROR : prediction for hub " + hub + " not found.Returning 0.0!");
		}

		return 0.0;
	}

	public void RefreshResult() {
		// read each prediction file
		for (String hub : hubToPredictionFile.keySet()) {
			String csvName = hubToPredictionFile.get(hub);
			YearPrediction yearPred = new YearPrediction();

			try {
				CSVReader reader = new CSVReader(new FileReader(csvName), ',');
				String[] record = null;
				Boolean columnFound = false;
				HashMap<Integer, Integer> indexToTaxiZone = new HashMap<Integer, Integer>();
				int dayIndex = 0, hourIndex = 0;

				record = reader.readNext();
				for (int i = 0; i < record.length; i++) {
					if (StringUtils.isNumeric(record[i])) {
						indexToTaxiZone.put(i, Integer.parseInt(record[i]));
					} else if (record[i].equals("Date")) {
						dayIndex = i;
					} else if (record[i].equals("Hour")) {
						hourIndex = i;
					}
				}

				ArrayList<HourPrediction> hPredList = new ArrayList<HourPrediction>();

				while ((record = reader.readNext()) != null) {

					HourPrediction hPred = new HourPrediction();

					for (int i = 0; i < record.length; i++) {
						if (i == dayIndex) {
							hPred.date = record[i];
						} else if (i == hourIndex) {
							hPred.hour = Integer.parseInt(record[i]);
						} else {
							hPred.taxiZoneToPrediction.put(indexToTaxiZone.get(i), Double.parseDouble(record[i]));
						}
					}
					hPredList.add(hPred);

				}

				DayPrediction dayPred = new DayPrediction();
				String currDate = hPredList.get(0).date;
				for (HourPrediction hPred : hPredList) {
					if (!hPred.date.equals(currDate)) {
						yearPred.yearPrediction.put(currDate, dayPred);
						dayPred = new DayPrediction();
						currDate = hPred.date;
					}
					dayPred.dayPrediction.put(hPred.hour, hPred);
				}

				// add the last dayPred
				yearPred.yearPrediction.put(currDate, dayPred);

			} catch (IOException e) {
				// TODO Auto-generated catch block
				System.out.println("Reading prediction input file " + hub + " failed");
				e.printStackTrace();
			}

			hubResults.put(hub, yearPred);
		}
		// testing
//		System.out.println(GetResultFor("Penn", "2018-12-31", 23, 1));
//		System.out.println(GetResultFor("LGA", "2018-12-31", 23, 1));
//		System.out.println(GetResultFor("JFK", "2018-12-31", 23, 1));
	}

}
