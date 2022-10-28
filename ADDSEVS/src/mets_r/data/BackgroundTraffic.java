package mets_r.data;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;

import au.com.bytecode.opencsv.CSVReader;
import mets_r.GlobalVariables;

import java.util.*;

/**
 * @author: Wenbo Zhang Read background traffic into treemap with roadid as key
 *          and hourly link speed as arraylist
 *
 **/

public class BackgroundTraffic {
	public TreeMap<Integer, ArrayList<Double>> backgroundTraffic;
	public TreeMap<Integer, ArrayList<Double>> backgroundStd;

	public BackgroundTraffic() {
		backgroundTraffic = new TreeMap<Integer, ArrayList<Double>>();
		backgroundStd = new TreeMap<Integer, ArrayList<Double>>();
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
					this.backgroundTraffic.put(roadID, value);
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
					this.backgroundStd.put(roadID, value);
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}