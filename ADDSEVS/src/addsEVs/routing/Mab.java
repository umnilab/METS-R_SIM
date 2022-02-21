package addsEVs.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Arrays;
import java.util.Arrays.*;
import java.util.Collections;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.Plan;
import addsEVs.citycontext.Road;
import addsEVs.vehiclecontext.ElectricVehicle;

import java.math.*;

import gov.nasa.worldwind.render.Path;
import util.Pair;

// We use the multi-armed bandit (Mab) to decide the routing for the EVs.
// It is an energy-optimal decision process.
public class Mab{
	protected HashMap<String, ArrayList<ArrayList<Integer>>> path_info; //record the K-shortest paths for n ODs.
	protected HashMap<String, ArrayList<Integer>> valid_path; //record the valid od id for each path.
	private int npath;
	private int tT;
	private int T;
	protected HashMap<Integer,Double> visit_energy; // the cumulative energy consumption for each link since time 0.
	protected HashMap<Integer,Integer> visit_count;  // the cumulative visited time for each link since time 0.
	private int eliminate_threshold; // a threshold in action elimination.
	private int nEdges; // the number of edges in the network.
	protected int action; // the current action;
	protected double minEnergy; // the current minEnergy;
	protected ArrayList<Double> mean_award;
	protected HashMap<String, Integer> time_od; // the times that the od has been routed.
		
	public Mab(HashMap<String, ArrayList<ArrayList<Integer>>> path_info, HashMap<String, ArrayList<Integer>> valid_path){
		this.path_info = path_info;
		this.valid_path = valid_path;
		for (String i: path_info.keySet()){
			this.npath = path_info.get(i).size();
			break;	
		}
		this.tT = 0;
		this.T = 0;
		this.visit_energy = new HashMap<Integer,Double>();
		this.visit_count = new HashMap<Integer,Integer>();
	    this.eliminate_threshold = 5;
	    this.action = 0;
	    this.minEnergy = 0.0;
	    this.mean_award = new ArrayList<Double>();
	    this.time_od = new HashMap<String, Integer>();
	}
	
	//public void play(ArrayList<Double> link_energy, int od){
	public void play(String od){
        //this.tT += 1;
        //this.T = this.tT/10 + 1;
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
            	A += (visit_energy.get(path.get(j)))/(visit_count.get(path.get(j))); // Average energy
            	B += -Math.sqrt((1.5*(Math.log(time_od.get(od))))/(visit_count.get(path.get(j))))/path_length; 
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
        this.action = key_list.get(minIndex);
        this.minEnergy = minEnergy;
        this.mean_award = val_list;
	}
	
	//public void play_independent(ArrayList<Double> link_energy, int od){
	//public void play_independent(int od){
    //    this.tT += 1;
    //    this.T = this.tT;
    //    HashMap<Integer, Double> UCB = new HashMap<>();
    //   HashMap<Integer, Double> mean_award =  new HashMap<>();
    //    for (int i = 0 ; i < valid_path.get(od).size(); i++){ 
    //    	int path_id = valid_path.get(od).get(i);
    //    	ArrayList<Integer> path = path_info.get(od).get(path_id);
    //        double A = 0.0;  // The first item in the UCB algorithm
    //       double B = 0.0;  // The second item in the UCB algorithm
    //        int path_length = path.size();
    //        for (int j = 0 ; j < path_length; j++){
    //        	A += (visit_energy.get(path.get(j)))/(visit_count.get(path.get(j)));
    //        	B += -Math.sqrt((1.5*(Math.log(T)))/(visit_count.get(path.get(j))))/path_length;
    //        }
    //        UCB.put(path_id, A+B);
    //        mean_award.put(path_id, A);
    //    }
    //    ArrayList<Integer> key_list = new ArrayList<Integer>();
    //    ArrayList<Double> val_list = new ArrayList<Double>();
    //    for (Map.Entry<Integer, Double> me : UCB.entrySet()){
    //    	key_list.add(me.getKey());
    //        val_list.add(me.getValue());
    //    }
    //    //double minEnergy = Arrays.stream(val_list).min().getAsDouble();
    //    double minEnergy = 1000000000;
    //    int minIndex = -1;
    //    for (int j = 0 ; j < val_list.size(); j++){
    //    	if (val_list.get(j) < minEnergy ){
    //    		minEnergy = val_list.get(j);
    //    		minIndex = j;
    //        }
    //    }
    //    this.action = key_list.get(minIndex);
    //    this.minEnergy = minEnergy;
    //    this.mean_award = val_list;
	//}
	
	// HashMap<ArrayList<Integer>,Double> link_energy
	// HashMap<Integer,ArrayList<Integer>> edge_dic;
	//public void update(int action, int od, HashMap<ArrayList<Integer>,Double> link_energy, HashMap<Integer,ArrayList<Integer>> edge_dic){
	//	ArrayList<Integer> path = path_info.get(od).get(action);
	//	for (int i = 0; i< path.size(); i++) {
	//		visit_count.set(path.get(i), visit_count.get(path.get(i))+1);
	//		ArrayList<Integer> road = edge_dic.get(path.get(i));
	//		visit_energy.set(path.get(i), visit_energy.get(path.get(i)) + link_energy.get(road));
	//	}
	//}
	
	public void updateLinkUCB(ConcurrentHashMap<String, ArrayList<Double>> linkUCB){
		for (String IDhour : linkUCB.keySet()){
//			System.out.println(ID);
			int ID = Integer.parseInt(IDhour.split(";")[0]);
			int energyRecordAdd = linkUCB.get(ID).size() - visit_count.get(ID);  // the increased number of count of od 
			if (energyRecordAdd > 0){
				visit_count.put(ID, visit_count.get(ID) + energyRecordAdd);
				double energyAdd = 0.0;
				for (int j = 0; j < energyRecordAdd; j++) {
					energyAdd += linkUCB.get(ID).get(linkUCB.get(ID).size()-1-j);
				}
				visit_energy.put(ID, visit_energy.get(ID) + energyAdd); // the sum of energy 
			}
		}
	}
	
	public void updateRouteUCB(ConcurrentHashMap<String, List<List<Integer>>> routeUCB){
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
	
	public void warm_up_old(HashMap<ArrayList<Integer>,Double> link_energy,  HashMap<Integer, ArrayList<Integer>> edge_dic){
		 this.tT +=1;
		 for (int od = 0; od < 10; od++) {
			 for (int j =0 ; j < this.npath; j++) {
				 ArrayList<Integer> path = path_info.get(od).get(j);
				 if (visit_count.get(path.get(j)) == 0){
					visit_count.put(path.get(j), 1);
					ArrayList<Integer> road = edge_dic.get(path.get(j));
					visit_energy.put(path.get(j), link_energy.get(road));	 
				 }
			 }
		 }
	}
	
	// warm up the visite_count and visit_energy using the equation:
	// y = 0.00004x^3 - 0.0069x^2 + 0.3146x + 3.0933
	public void warm_up(HashMap<Integer, ArrayList<Double>> initialLinkSpeedLength){    //ArrayList = [speed (m/s), length(m)]
		for (int linkID : initialLinkSpeedLength.keySet()) {
			visit_count.put(linkID, 1);
			double velocity = initialLinkSpeedLength.get(linkID).get(0); // unit: m/s.
			double length = initialLinkSpeedLength.get(linkID).get(1); // unit: m.
			velocity = 3600.0/1609.34 * velocity;// m/s to mile/hour  
			// get the energy miles per kWh
			double energy = 0.00004*velocity*velocity*velocity - 0.0069*velocity*velocity + 0.3146*velocity + 3.0933; // unit: mile/kWh
			// get the energy per kWh
			energy = (length/1609.34)/energy;
			visit_energy.put(linkID, energy);
		}
	}
	
	public void m_eliminate(String od, ArrayList<Double> path_energy_list){
		double random_alpha = Math.exp(3*(this.T/100)-1); // 500 steps to full elimination
		if ((this.T >= this.eliminate_threshold) && (this.valid_path.get(od).size()>10) && (Math.random() < random_alpha)){
			double eliminate_alpha = percentile(path_energy_list, 90); // delete the worst 10%
			ArrayList<Integer> temp_valid_path = new ArrayList<Integer>();
			for (int i = 0; i < path_energy_list.size(); i++){
				if (path_energy_list.get(i) <= eliminate_alpha){
					temp_valid_path.add(this.valid_path.get(od).get(i));	
				}
			}
			this.valid_path.put(od, temp_valid_path);
		}
	}
	
	public static double percentile(ArrayList<Double> values, double percentile){ 
		Collections.sort(values); 
		int index = (int) Math.ceil((percentile / 100) * values.size()); 
		return values.get(index - 1); 
	}	
	
	public int getAction(){
		return this.action;
	}
}
