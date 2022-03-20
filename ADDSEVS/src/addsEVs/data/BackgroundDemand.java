package addsEVs.data;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeMap;

import addsEVs.GlobalVariables;
import au.com.bytecode.opencsv.CSVReader;

import java.util.*;

/**
 * @author: Zengxiang Lei
 * Read hourly demand into treemap with roadid as key and hourly from hub and to hub travel demand as arraylists
 * 
 **/


public class BackgroundDemand{
	public TreeMap<Integer,ArrayList<Double>> travelDemand;
	//initialize everything
	public BackgroundDemand(){
		System.out.print("Read demand.");
		travelDemand=new TreeMap<Integer,ArrayList<Double>>();
		readEventFile();
	}
	// read and parse CSV files
	public void readEventFile() {
		File dmeventFile = new File(GlobalVariables.DM_EVENT_FILE);;
		CSVReader csvreader = null;
		String[] nextLine;
		try {
			csvreader = new CSVReader(new FileReader(dmeventFile),',');
			boolean readingheader = false;
			// This while loop is used to read the CSV iterating through the row
			int ind = 0;
			while ((nextLine = csvreader.readNext()) != null) {
				// skip the first row (header)
				if (readingheader) {
					readingheader = false;
					
				} else {
					ArrayList<Double> value = new ArrayList<Double>(Collections.nCopies(GlobalVariables.HOUR_OF_DEMAND,0.0d));
					for (int i=0 ; i<GlobalVariables.HOUR_OF_DEMAND ; i++ ){
						value.set(i, Double.parseDouble(nextLine[i]));
					}
					//ContextCreator.logger.debug("roadID = "+ roadID+"value =" + value);
					this.travelDemand.put(ind,value);
		            }
				ind++;
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