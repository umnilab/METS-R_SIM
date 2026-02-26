package mets_r.data.input;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import au.com.bytecode.opencsv.CSVReader;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;

import java.util.*;
import java.util.Map.Entry;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * Read hourly demand into treemap with origin, destination zone indexes as the keys
 * @author: Zengxiang Lei 
 **/

public class TravelDemand {
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Integer>>> privateEVTravelDemand; // The outer key is departure time, the inner key is vid
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Integer>>> privateGVTravelDemand;
	private TreeMap<Integer, ArrayList<Double>> privateEVProfile;
	
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> publicTravelDemand; // The outer key is the origin and the
																				// inner key is the destination
	private List<Integer> waitingThreshold;
	private TreeMap<Integer, TreeMap<Integer, ArrayList<Double>>> sharePercentage;
	
	private BufferedReader privateEVTripReader;
	private BufferedReader privateGVTripReader;
	private BufferedReader privateEVProfileReader;
	
	private int hour;

	public TravelDemand() {
		ContextCreator.logger.info("Read demand.");
		privateEVTravelDemand = new TreeMap<Integer, TreeMap<Integer, ArrayList<Integer>>>();
		privateGVTravelDemand = new TreeMap<Integer, TreeMap<Integer, ArrayList<Integer>>>();
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
			
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}

	@SuppressWarnings("unchecked")
	public void readPublicDemandFile() {
		JSONParser parser = new JSONParser();
		try {
			Object obj = parser.parse(new FileReader(GlobalVariables.RH_DEMAND_FILE));
			JSONObject jsonObject = (JSONObject) obj;

			for (String OD : (Set<String>) jsonObject.keySet()) {
				ArrayList<Double> value = (ArrayList<Double>) jsonObject.get(OD);

				String[] inds = OD.split(",");
				int originInd = Integer.parseInt(inds[0].replace("(", "").trim()) + 1;
				;
				int destInd = Integer.parseInt(inds[1].replace(")", "").trim()) + 1;

				if (!publicTravelDemand.containsKey(originInd)) {
					publicTravelDemand.put(originInd, new TreeMap<Integer, ArrayList<Double>>());
				}

				publicTravelDemand.get(originInd).put(destInd, value);
			}
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void loadPrivateDemandChunk() { // For loading the next hour demand
		// Add the logger
		int ev_trip_number = 0;
		int gv_trip_number = 0;
		
		// clear the previous chunk
		Iterator<Map.Entry<Integer, TreeMap<Integer, ArrayList<Integer>>>> iter = this.privateGVTravelDemand.entrySet().iterator();
	    while (iter.hasNext()) {
	        if (iter.next().getKey() < hour * 60) {
	            iter.remove();
	        }
	    }
	    Iterator<Map.Entry<Integer,TreeMap<Integer, ArrayList<Integer>>>> iter2 = this.privateEVTravelDemand.entrySet().iterator();
	    while (iter2.hasNext()) {
	        if (iter2.next().getKey() < hour * 60) {
	            iter2.remove();
	        }
	    }
		// read the new chunk
	    try {
	    	boolean flag = true;
		    while(flag) {
		    	String line = privateEVTripReader.readLine();
		    	if(line != null) {
		    		String[] result = line.split(",");
		    		int vid = Integer.parseInt(result[0]);
		    		int time_ind = Integer.parseInt(result[1]);
		    		int origin = Integer.parseInt(result[2]) + 1;
		    		int dest = Integer.parseInt(result[3]) + 1;
		    		
		    		// add it to the privateEVTravelDemand
		    		if(!privateEVTravelDemand.containsKey(time_ind)) {
		    			privateEVTravelDemand.put(time_ind, new TreeMap<Integer, ArrayList<Integer>>());
		    		}
		    		ArrayList<Integer> od = new ArrayList<Integer>();
		    		od.add(origin);
		    		od.add(dest);
		    		privateEVTravelDemand.get(time_ind).put(vid, od);
		    		
		    		ev_trip_number += 1;
		    		
		    		// if this demand happens earlier/within than this hour, continue the reading
		    		if(time_ind <= (hour+1) * 60 ) continue;
		    	}
		    	flag = false;
		    }
		    flag = true;
		    while(flag) {
		    	String line = privateGVTripReader.readLine();
		    	if(line != null) {
		    		String[] result = line.split(",");
		    		int vid = Integer.parseInt(result[0]);
		    		int time_ind = Integer.parseInt(result[1]);
		    		int origin = Integer.parseInt(result[2]) + 1;
		    		int dest = Integer.parseInt(result[3]) + 1;
		    		
		    		// add it to the privateEVTravelDemand
		    		if(!privateGVTravelDemand.containsKey(time_ind)) {
		    			privateGVTravelDemand.put(time_ind, new TreeMap<Integer, ArrayList<Integer>>());
		    		}
		    		ArrayList<Integer> od = new ArrayList<Integer>();
		    		od.add(origin);
		    		od.add(dest);
		    		privateGVTravelDemand.get(time_ind).put(vid, od);
		    		
		    		gv_trip_number += 1;
		    		
		    		// if this demand happens earlier/within than this hour, continue the reading
		    		if(time_ind <= (hour+1) * 60 ) continue;
		    	}
		    	flag = false;
		    }
		    
		    ContextCreator.logger.info("Private trips at hour " + hour + " has been loaded, EV trip number: " + ev_trip_number + ", GV trip number: " + gv_trip_number);
	    }
	    catch (IOException e) {
	    	ContextCreator.logger.error(
					"Fail to load the private trip demand chunk with error " + e.toString());
	    	e.printStackTrace();
	    }
	    
	    hour += 1;
		
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
	
	public TreeMap<Integer, Integer> getPrivateEVTravelDemand(int timeIndex, int originID) {
		// return a list of vid, dest
		TreeMap<Integer, Integer> result = new TreeMap<Integer, Integer>();
		if (this.privateEVTravelDemand.containsKey(timeIndex)) {
			for(Entry<Integer, ArrayList<Integer>> item: this.privateEVTravelDemand.get(timeIndex).entrySet()) {
				int vid = item.getKey();
				int origin = item.getValue().get(0);
				int dest = item.getValue().get(1);
				if(origin == originID) {
					result.put(vid, dest);
				}
			}
		}
		return result;
	}
	
	public ArrayList<Double> getPrivateEVProfile(int vehID){
		if(privateEVProfile.containsKey(vehID))
			return privateEVProfile.get(vehID);
		else
			return null;
	}
	
	public TreeMap<Integer, Integer> getPrivateGVTravelDemand(int timeIndex, int originID) {
		// return a list of vid, dest
		TreeMap<Integer, Integer> result = new TreeMap<Integer, Integer>();
		if (this.privateGVTravelDemand.containsKey(timeIndex)) {
			for(Entry<Integer, ArrayList<Integer>> item: this.privateGVTravelDemand.get(timeIndex).entrySet()) {
				int vid = item.getKey();
				int origin = item.getValue().get(0);
				int dest = item.getValue().get(1);
				if(origin == originID) {
					result.put(vid, dest);
				}
			}
		}
		return result;
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
	
	public void close() {
		try {
			this.privateEVTripReader.close();
			this.privateGVTripReader.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}