package addsEVs.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//Jiawei Xue, July 2020.
//This class is proposed to achieve the eco-routing for the bus.
//The bus will use the following historical data to make the eco-routing decision.
public class MabBus extends Mab{
	//path_info, valid_path, visit_energy, visit_count are inherited from the parent class.
	private HashMap<Integer,ArrayList<Double>> visit_speed_vehicle; //the historical speed information transfered from the EV.
	private HashMap<Integer,Integer> visit_count_vehicle; //the count of links that is visited by EV.
	private HashMap<Integer,Double> generated_visit_energy; //combination of historical data from bus and vehicle
	private HashMap<Integer,Integer> generated_visit_count; // combination of historical data from bus and vehicle.
	
	//constructor
	public MabBus(HashMap<String, ArrayList<ArrayList<Integer>>> path_info, HashMap<String, ArrayList<Integer>> valid_path){
		super(path_info, valid_path);
		this.visit_speed_vehicle = new HashMap<Integer,ArrayList<Double>>();
		this.visit_count_vehicle = new HashMap<Integer,Integer>();
		this.generated_visit_energy = new HashMap<Integer,Double>();
		this.generated_visit_count =  new HashMap<Integer,Integer>();
	}
	
	//override the play() in Mab.java
	public void playBus(String od){
		if(time_od.containsKey(od)){
			time_od.put(od, time_od.get(od)+1);
		}
		else{
			time_od.put(od, 1);
		}
		HashMap<Integer, Double> UCB = new HashMap<Integer, Double>();
        HashMap<Integer, Double> mean_award =  new HashMap<Integer, Double>();
        for (int i = 0 ; i < valid_path.get(od).size(); i++){ 
        		int path_id = valid_path.get(od).get(i);
        		ArrayList<Integer> path = path_info.get(od).get(path_id);
        		double A = 0.0;  // The first item in the UCB algorithm
        		double B = 0.0;  // The second item in the UCB algorithm
            int path_length = path.size();
            for (int j = 0 ; j < path_length; j++){
            		A += (generated_visit_energy.get(path.get(j)))/(generated_visit_count.get(path.get(j)));
            		B += -Math.sqrt((1.5*(Math.log(time_od.get(od))))/(generated_visit_count.get(path.get(j))))/path_length;
            }
            UCB.put(path_id, A+B);
            mean_award.put(path_id, A);
        }
        ArrayList<Integer> key_list = new ArrayList<Integer>();
        ArrayList<Double> val_list = new ArrayList<Double>();
        for (Map.Entry<Integer, Double> me : UCB.entrySet()){
        		key_list.add(me.getKey());
            val_list.add(me.getValue());
        }
        //double minEnergy = Arrays.stream(val_list).min().getAsDouble();
        double minEnergy = 1000000000;
        int minIndex = -1;
        for (int j = 0 ; j < val_list.size(); j++){
        		if (val_list.get(j) < minEnergy){
        			minEnergy = val_list.get(j);
        			minIndex = j;
            }
        }
        this.action = key_list.get(minIndex);   //return the valid_path.get(od).get(i) 
        this.minEnergy = minEnergy; //A+B
        this.mean_award = val_list; //(path_id,A)
	}
	
	// Discuss the information update from the real-time bus energy consumption.
	// 1) visit_energy, visit_count
	// 2) visit_speed_vehicle, visit_count_vehicle
	// 3) generated_visit_energy, generated_visit_count
	public void updateLinkUCBBus(Map<String, ArrayList<Double>> linkUCB){
		for (String IDhour : linkUCB.keySet()){
			int ID = Integer.parseInt(IDhour.split(";")[0]);
			int energyRecordAdd = linkUCB.get(ID).size() - visit_count.get(ID);
			if (energyRecordAdd > 0){
				visit_count.put(ID, visit_count.get(ID) + energyRecordAdd);                       // update 1).count
				generated_visit_count.put(ID, generated_visit_count.get(ID) + energyRecordAdd);   // update 3).count
				double energyAdd = 0.0;
				for (int j = 0; j < energyRecordAdd; j++) {
					energyAdd += linkUCB.get(ID).get(linkUCB.get(ID).size()-1-j);
				}
				visit_energy.put(ID, visit_energy.get(ID) + energyAdd);                           // update 1).energy
				generated_visit_energy.put(ID, generated_visit_energy.get(ID) + energyAdd);       // update 3).energy
			}
		}
	}
	
	//Discuss the shadow bus energy consumption
	public void updateShadowBus(Map<String, ArrayList<Double>> speedUCB, Map<Integer, Double> lengthUCB){
		for (String IDhour : speedUCB.keySet()){
			int ID = Integer.parseInt(IDhour.split(";")[0]);
			int speedRecordAdd = speedUCB.get(ID).size() - visit_count_vehicle.get(ID);
			if (speedRecordAdd > 0){
				visit_count_vehicle.put(ID, visit_count_vehicle.get(ID) + speedRecordAdd);         // update 2).count
				generated_visit_count.put(ID, generated_visit_count.get(ID) + speedRecordAdd);     // update 3).count
				Double energyAdd =0.0;
				for (int j = 0; j < speedRecordAdd; j++) {
					Double newSpeed = speedUCB.get(ID).get(speedUCB.get(ID).size()-1-j);
					visit_speed_vehicle.get(ID).add(newSpeed);                                     // update 2).speed
					energyAdd += calculateBusEnergy(newSpeed,lengthUCB.get(ID));
				}
				generated_visit_energy.put(ID, generated_visit_energy.get(ID) + energyAdd);        // update 3).energy
			}
		}
	}
	
	public void updateRouteUCBBus(Map<String, List<List<Integer>>> routeUCB){
		for (String OD : routeUCB.keySet()){
			List<List<Integer>> Roads = routeUCB.get(OD);
			ArrayList<ArrayList<Integer>> RoadLists = new ArrayList<ArrayList<Integer>>();
			for (int i=0; i< Roads.size(); i++) {
				RoadLists.add((ArrayList<Integer>)Roads.get(i));
			}
			path_info.put(OD,RoadLists);
			ArrayList<Integer> vpath = new ArrayList<Integer>();
			for (int i=0; i < RoadLists.size(); i++) {
				vpath.add(i); // here, we assume all the path are valid.
			}
			valid_path.put(OD,vpath);
		}
	}
	// 1mph = 1609.3m/3600s = (1609.3/3600)m/s
	// the unit for input variable: 
	// speed: m/s; length: m
	// the unit for speed in the energy model: mph
	// output: kWh
	public double calculateBusEnergy(double speed, double length){
		double x = speed/(1609.3/3600.0);  //unit of x: mile/hour
		double energy = (length/1609.3)/(0.001*0.006*x*x*x - 0.001*x*x + 0.0402*x + 0.1121);  //unit: kWh
		return energy;
	}
	
	
	// July,2020
	// warm up the visite_count and visit_energy using the equation of bus:
	// y = 0.000006x^3 - 0.001x^2 + 0.0402x + 0.1121
	public void warm_up_bus(HashMap<Integer, ArrayList<Double>> initialLinkSpeedLength){    //ArrayList = [speed (m/s), length(m)]
		for (int linkID : initialLinkSpeedLength.keySet()) {
			visit_count.put(linkID, 1);
			visit_count_vehicle.put(linkID,0); //July 23, 2020
			generated_visit_count.put(linkID, 1);		
			double velocity = initialLinkSpeedLength.get(linkID).get(0); // unit: m/s.
			double length = initialLinkSpeedLength.get(linkID).get(1); // unit: m.
			double x = (3600.0/1609.34) * velocity;// m/s to mile/hour  
			// get the energy miles per kWh
			double energy = 0.000006*x*x*x - 0.001*x*x + 0.0402*x + 0.1121; // unit: mile/kWh
			// get the energy 
			energy = (length/1609.34)/energy ;
			visit_energy.put(linkID, energy); 
			visit_speed_vehicle.put(linkID, new ArrayList<Double>()); //July 23, 2020
			generated_visit_energy.put(linkID, energy);
		}
	}
}
