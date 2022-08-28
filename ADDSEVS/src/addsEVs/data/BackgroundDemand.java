package addsEVs.data;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import addsEVs.ContextCreator;
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

	public BackgroundDemand(){
		ContextCreator.logger.info("Read demand.");
		travelDemand=new TreeMap<Integer,ArrayList<Double>>();
		readEventFile();
//		validPassNum(); // For debugging the demand generation process
	}
	
	// Read and parse the CSV data
	public void readEventFile() {
		File dmeventFile = new File(GlobalVariables.DM_EVENT_FILE);;
		CSVReader csvreader = null;
		String[] nextLine;
		try {
			csvreader = new CSVReader(new FileReader(dmeventFile),',');
			boolean readingheader = false;
			// This while loop is used to read the CSV iterating through the row
			int ind = 0;
			int total = 0;
			while ((nextLine = csvreader.readNext()) != null) {
				// skip the first row (header)
				if (readingheader) {
					readingheader = false;
					
				} else {
					ArrayList<Double> value = new ArrayList<Double>(Collections.nCopies(GlobalVariables.HOUR_OF_DEMAND,0.0d));
					for (int i=0 ; i<GlobalVariables.HOUR_OF_DEMAND ; i++ ){
						value.set(i, Double.parseDouble(nextLine[i]));
						total += Double.parseDouble(nextLine[i]);
					}
					
					this.travelDemand.put(ind,value);
		            }
				ind++;
			}
			ContextCreator.logger.info("Total demand: "+total);
		} catch (FileNotFoundException e) {
		e.printStackTrace();
	} catch (IOException e) {
		e.printStackTrace();
	} catch (Exception e) {
		e.printStackTrace();
	}
	}
	
	// To valid the number of pass via random generator
	public void validPassNum(){
		int pass_num = 0;
		double pass_num2 = 0;
		for(int k = 0; k<12;k+=1) {
			for(int hour=0;hour<GlobalVariables.HOUR_OF_DEMAND;hour+=1) {
				for (int ind=0;ind < GlobalVariables.NUM_OF_ZONE;ind+=1) {
					if(GlobalVariables.HUB_INDEXES.contains(ind)) {
						int j = GlobalVariables.HUB_INDEXES.indexOf(ind);
						for (int i = 0; i < GlobalVariables.NUM_OF_ZONE; i++) {
							if(ind != i) {
							double numToGenerate = this.travelDemand.get(i+j*GlobalVariables.NUM_OF_ZONE*2).get(hour) / 12.0;
							numToGenerate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
							pass_num2 += numToGenerate;
			            	numToGenerate = Math.floor(numToGenerate) + (Math.random()<(numToGenerate-Math.floor(numToGenerate))?1:0);
			            	for (int h = 0; h < numToGenerate; h++) {
			            		pass_num+=1;
			            	}
							}
						}
					}
					else {
						int j = 0;
						for(int destination : GlobalVariables.HUB_INDEXES){
							if(j != destination) {
							double numToGenerate = this.travelDemand.get(ind+j*GlobalVariables.NUM_OF_ZONE*2+
			        				GlobalVariables.NUM_OF_ZONE).get(hour) / 12.0;
							j += 1;
							numToGenerate *= GlobalVariables.PASSENGER_DEMAND_FACTOR;
							pass_num2 += numToGenerate;
			            	numToGenerate = Math.floor(numToGenerate) + (Math.random()<(numToGenerate-Math.floor(numToGenerate))?1:0);
			            	for (int i = 0; i < numToGenerate; i++) {
			            		pass_num+=1;
			            	}
						}
						}
					}
				}
			}
		}
		ContextCreator.logger.info("Total pass likely to generate: " + pass_num);
		ContextCreator.logger.info("Total pass with fraction: " + pass_num2);
	}
}