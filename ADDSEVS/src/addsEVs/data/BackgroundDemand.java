package addsEVs.data;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import au.com.bytecode.opencsv.CSVReader;
import java.util.*;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * @author: Zengxiang Lei
 * Read hourly demand into treemap with OD index as key and hourly from hub and to hub travel demand as arraylists
 * 
 **/


public class BackgroundDemand{
	public TreeMap<Integer,TreeMap<Integer,ArrayList<Double>>> travelDemand; // The outer key is the origin and the inner key is the destination

	public BackgroundDemand(){
		ContextCreator.logger.info("Read demand.");
		travelDemand=new TreeMap<Integer,TreeMap<Integer, ArrayList<Double>>>();
		readDemandFile();
//		validPassNum(); // For debugging the demand generation process
	}
	
	// Read and parse the CSV data, for loading the demand CSV in the METS-R project
	public void readCsvFile() {
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
					int originInd ;
					int destInd;
					if((ind / GlobalVariables.NUM_OF_ZONE) % 2 == 0) { // from hub to other zones
						originInd = GlobalVariables.HUB_INDEXES.get(ind / GlobalVariables.NUM_OF_ZONE / 2);
						destInd = ind % GlobalVariables.NUM_OF_ZONE;
					}
					else { // from other zones to hub
						originInd = ind % GlobalVariables.NUM_OF_ZONE;
						destInd = GlobalVariables.HUB_INDEXES.get(ind / GlobalVariables.NUM_OF_ZONE / 2);
					}
					
					if(!travelDemand.containsKey(originInd)) {
						travelDemand.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
					}
					
					travelDemand.get(originInd).put(destInd, value);
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
	
	// For a more general usage case
	@SuppressWarnings("unchecked")
	public void readJsonFile() {
		JSONParser parser = new JSONParser();
		try{
			Object obj = parser.parse(new FileReader(GlobalVariables.DM_EVENT_FILE));
			JSONObject jsonObject = (JSONObject) obj;
			
			for (String OD: (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = (ArrayList<Double>) jsonObject.get(OD);
				
				String[] inds = OD.split(",");
				int originInd =  Integer.parseInt(inds[0].replace("(", "").trim());;
				int destInd =  Integer.parseInt(inds[1].replace(")", "").trim());
				
				if(!travelDemand.containsKey(originInd)) {
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
	
	public void readDemandFile() {
		if(GlobalVariables.DM_EVENT_FILE.endsWith("csv")) {
			readCsvFile();
		}
		else {
			readJsonFile();
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
							double numToGenerate = this.travelDemand.get(ind).get(j).get(hour) / 12.0;
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
							double numToGenerate = this.travelDemand.get(ind).get(destination).get(hour) / 12.0;
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