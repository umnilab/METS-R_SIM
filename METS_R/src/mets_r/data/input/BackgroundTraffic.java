package mets_r.data.input;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;

import au.com.bytecode.opencsv.CSVReader;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;

import java.util.*;

/**
 *  Read background traffic mean/std into treemap with roadid as key and hourly link speed as arraylist
 *  @author: Wenbo Zhang, Zengxiang Lei
 **/

public class BackgroundTraffic {
	private TreeMap<Integer, ArrayList<Double>> backgroundSpeed;
	private TreeMap<Integer, ArrayList<Double>> backgroundSpeedStd;

	public BackgroundTraffic() {
		backgroundSpeed = new TreeMap<Integer, ArrayList<Double>>();
		backgroundSpeedStd = new TreeMap<Integer, ArrayList<Double>>();
		readEventFile();
		readStdFile();
	}

	// Read and parse CSV files
	public void readEventFile() {
		File bteventFile = new File(GlobalVariables.BT_EVENT_FILE);
		CSVReader csvreader = null;
		String[] nextLine;
		int roadID = 0;

		try {
			csvreader = new CSVReader(new FileReader(bteventFile));
			// This is used to avoid reading the header (Data is assumed to
			// start from the second row)
			boolean readingheader = true;
			// This while loop is used to read the CSV iterating through the row
			while ((nextLine = csvreader.readNext()) != null) {
				// Do not read the first row (header)
				if (readingheader) {
					readingheader = false;
				} else {
					ArrayList<Double> value = new ArrayList<Double>(
							Collections.nCopies(GlobalVariables.HOUR_OF_SPEED, 0.0d));
					roadID = (int) (Float.parseFloat(nextLine[0]));
					for (int i = 0; i < GlobalVariables.HOUR_OF_SPEED; i++) {
						value.set(i, Double.parseDouble(nextLine[i + 1]));
					}
					this.backgroundSpeed.put(roadID, value);
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void readStdFile() {
		File bteventFile = new File(GlobalVariables.BT_STD_FILE);
		CSVReader csvreader = null;
		String[] nextLine;
		int roadID = 0;
		try {
			csvreader = new CSVReader(new FileReader(bteventFile));
			// This is used to avoid reading the header (Data is assumed to
			// start from the second row)
			boolean readingheader = true;
			// This while loop is used to read the CSV iterating through the row
			while ((nextLine = csvreader.readNext()) != null) {
				// Do not read the first row (header)
				if (readingheader) {
					readingheader = false;
				} else {
					ArrayList<Double> value = new ArrayList<Double>(
							Collections.nCopies(GlobalVariables.HOUR_OF_SPEED, 0.0d));
					roadID = (int) (Float.parseFloat(nextLine[0]));
					for (int i = 0; i < GlobalVariables.HOUR_OF_SPEED; i++) {
						value.set(i, Double.parseDouble(nextLine[i + 1]));
					}
					this.backgroundSpeedStd.put(roadID, value);
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public double getBackgroundTraffic(int ID, int hour) {
		if(backgroundSpeed.containsKey(ID)) {
			if(backgroundSpeed.get(ID).size()>hour)
				return backgroundSpeed.get(ID).get(hour); 
		}
		ContextCreator.logger.error("Could not find the background speed for link " +
		ID + " at hour " + hour + ", using the default value (30).");
		return 30;
	}

	public double getBackgroundTrafficStd(int ID, int hour) {
		if(backgroundSpeedStd.containsKey(ID)) {
			if(backgroundSpeedStd.get(ID).size()>hour)
				return backgroundSpeedStd.get(ID).get(hour); 
		}
		ContextCreator.logger.error("Could not find the background speed std for link " +
		ID + " at hour " + hour + ", using the default value (30).");
		return 30;
	}
}