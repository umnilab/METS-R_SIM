package addsEVs.routing;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import addsEVs.GlobalVariables;
import addsEVs.citycontext.Road;
import addsEVs.network.RemoteDataClient;
import addsEVs.routing.Mab;
import repast.simphony.space.gis.ShapefileLoader;
import util.Pair;

public class MabManager{
	private HashMap<Integer, Mab> mab;
	private HashMap<Integer, MabBus> mabBus;
	private List<HashMap<Integer, ArrayList<Double>>> initialLinkSpeedLength;
	private ConcurrentHashMap<Integer, Double> roadLengthMap; //July,2020
	public String working_dir = System.getProperty("user.dir"); // find current working directory

	public MabManager(){
		HashMap<String, ArrayList<ArrayList<Integer>>> path_info = new HashMap<String, ArrayList<ArrayList<Integer>>>();
		HashMap<String, ArrayList<Integer>> valid_path = new HashMap<String, ArrayList<Integer>>();
		// July,2020
		HashMap<String, ArrayList<ArrayList<Integer>>> path_info_bus = new HashMap<String, ArrayList<ArrayList<Integer>>>();
		HashMap<String, ArrayList<Integer>> valid_path_bus = new HashMap<String, ArrayList<Integer>>();
		initialLinkSpeedLength = new ArrayList<>();
		mab = new HashMap<Integer, Mab>();
		mabBus = new HashMap<Integer, MabBus>();
		for(int hour=0; hour<GlobalVariables.SIMULATION_STOP_TIME*GlobalVariables.SIMULATION_STEP_SIZE/3600; hour++){
			mab.put(hour, new Mab(path_info, valid_path));
			mabBus.put(hour, new MabBus(path_info_bus,valid_path_bus));
			initialLinkSpeedLength.add(new HashMap<Integer, ArrayList<Double>>());
		}
		//July, 2020
		roadLengthMap = new ConcurrentHashMap<Integer,Double>();
	}

	// it should be called every time tick.  [t1,t2]
	// input is the OD.  t1.
	// output is the route.  t1. 
	// refresh the energy.  t2.
	// t1, t1.
	
	public int ucbRouting(String od, int hour){
		mab.get(hour).play(od);
		return mab.get(hour).getAction();
	}
	
	// t2.
	public int refreshLinkUCB(ConcurrentHashMap<String, ArrayList<Double>> linkUCBMap){
		int hour = 0;
		for (String IDhour : linkUCBMap.keySet()){
			hour= Integer.parseInt(IDhour.split(";")[1]);
		}
		mab.get(hour).updateLinkUCB(linkUCBMap);
		return hour;
	}	
	
	public void refreshRouteUCB(ConcurrentHashMap<String, List<List<Integer>>> routeUCBMap){
		for(int hour=0; hour<GlobalVariables.SIMULATION_STOP_TIME*GlobalVariables.SIMULATION_STEP_SIZE/3600; hour++){
			mab.get(hour).updateRouteUCB(routeUCBMap);
		}
	}
	
	//July, 2020
	public int ucbRoutingBus(String od, int hour){
		mabBus.get(hour).playBus(od);
		return mabBus.get(hour).getAction();
	}
	
	//July, 2020 routeUCB bus
	public void refreshRouteUCBBus(ConcurrentHashMap<String, List<List<Integer>>> routeUCBMapBus){
		for(int hour=0; hour<GlobalVariables.SIMULATION_STOP_TIME*GlobalVariables.SIMULATION_STEP_SIZE/3600; hour++){
			mabBus.get(hour).updateRouteUCBBus(routeUCBMapBus);
		}
	}
	//July, 2020
	public int refreshLinkUCBBus(ConcurrentHashMap<String, ArrayList<Double>> linkUCBMapBus){
		int hour = 0;
		for (String IDhour : linkUCBMapBus.keySet()){
			hour= Integer.parseInt(IDhour.split(";")[1]);
		}
		mabBus.get(hour).updateLinkUCBBus(linkUCBMapBus);
		return hour;
	}
	
	//July, 2020
	public int refreshLinkUCBShadow(ConcurrentHashMap<String, ArrayList<Double>> speedUCBMap, Map<Integer, Double> lengthUCB){
		int hour = 0;
		for (String IDhour : speedUCBMap.keySet()){
			hour= Integer.parseInt(IDhour.split(";")[1]);
		}
		mabBus.get(hour).updateShadowBus(speedUCBMap,lengthUCB);
		return hour;
	}	
	
	public void initializeLinkEnergy1() {
		try {
			/* CSV file for data attribute */
			String fileName1 = working_dir+"/data/NYC/background_traffic/background_traffic_NYC_one_week.csv";
			BufferedReader br = new BufferedReader(new FileReader(fileName1));
			//br.readLine();          //the first row is the title row
			String line = null; 
			br.readLine();
			while ((line = br.readLine())!=null) {
				//line = br.readLine();
				String[] result1 = line.split(",");
				int roadID = Integer.parseInt(result1[0]);
				double backgroundSpeed = 0.0;
				for (int i = 0; i < GlobalVariables.SIMULATION_STOP_TIME*GlobalVariables.SIMULATION_STEP_SIZE/3600; i++) {
					backgroundSpeed = Double.parseDouble(result1[i]);
					ArrayList<Double> speedLength = new ArrayList<Double>();
					speedLength.add(backgroundSpeed);
//					System.out.println(initialLinkSpeedLength);
					initialLinkSpeedLength.get(i).put(roadID, speedLength);	
				}
			}
			br.close();
		}catch (FileNotFoundException e){
			System.out.println("ContextCreator: No speed csv file found");
			e.printStackTrace();
		}catch (IOException e){
	        e.printStackTrace();
	    }
	}
	
	public void initializeLinkEnergy2(){
		try {
			/* CSV file for data attribute */
			String fileName2 = working_dir+"/data/NYC/background_traffic/background_traffic_NYC_one_week.csv";
			BufferedReader br = new BufferedReader(new FileReader(fileName2));
			br.readLine();          //the first row is the title row
			String line = null; 
			while ((line = br.readLine())!=null) {
				//line = br.readLine();
				String[] result=line.split(",");
				int roadID = Integer.parseInt(result[0]);
				double roadLength = Double.parseDouble(result[result.length-1]);
				for (int i = 0; i < GlobalVariables.SIMULATION_STOP_TIME*GlobalVariables.SIMULATION_STEP_SIZE/3600; i++) {
					ArrayList<Double> speedLength = new ArrayList<Double>();
					speedLength.add(initialLinkSpeedLength.get(i).get(roadID).get(0));
					speedLength.add(roadLength);
					initialLinkSpeedLength.get(i).put(roadID, speedLength);
					roadLengthMap.put(roadID,roadLength);  //July,2020
				}
			}
			br.close();
		}catch (FileNotFoundException e){
			System.out.println("ContextCreator: No road csv file found");
			e.printStackTrace();
		}catch (IOException e){
			e.printStackTrace();
		}
		
		for (int i = 0; i < GlobalVariables.SIMULATION_STOP_TIME*GlobalVariables.SIMULATION_STEP_SIZE/3600; i++) {
		    mab.get(i).warm_up(initialLinkSpeedLength.get(i));
		    mabBus.get(i).warm_up_bus(initialLinkSpeedLength.get(i));
		}
		
	}
	
	// July,2020
	public ConcurrentHashMap<Integer, Double> getRoadLengthMap(){
	    	return roadLengthMap;
	}
}
