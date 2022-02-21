package addsEVs.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;


@WebSocket(maxTextMessageSize = 64 * 1024)
public class RemoteDataClient
{
    private final CountDownLatch closeLatch;
    @SuppressWarnings("unused")
    private Session session;
    // all received energy values are stored in this map
    private ConcurrentHashMap<String, ArrayList<Double>> link_UCB_received;
    // all OD pairs and road lists received
    private ConcurrentHashMap<String, List<List<Integer>>> route_UCB_received;
    
    // July, 2020 
    // all received energy values for bus 
    private ConcurrentHashMap<String, ArrayList<Double>> link_UCB_bus_received;    // transfer multiple time
    //all OD pairs and road lists received for bus
    private ConcurrentHashMap<String, List<List<Integer>>> route_UCB_bus_received;  // transfer one time
    //all received vehicle speed value, which is used for bus routing
    private ConcurrentHashMap<String, ArrayList<Double>> speed_vehicle_received;   // transfer multiple time, similar to link_UCB
    // July, 2020
    private volatile boolean isConnected;
    private int debug_msg_len = 50;
    
    public RemoteDataClient()
    {
        this.closeLatch = new CountDownLatch(1);
        this.link_UCB_received = new ConcurrentHashMap<String, ArrayList<Double>>(); // Collections.synchronizedMap(new HashMap<Integer, ArrayList<Double>>());
        this.route_UCB_received = new ConcurrentHashMap<String, List<List<Integer>>>(); // Collections.synchronizedMap(new HashMap<String, List<List<Integer>>>());
        //July,2020
        this.link_UCB_bus_received = new ConcurrentHashMap<String, ArrayList<Double>>();// Collections.synchronizedMap(new HashMap<Integer, ArrayList<Double>>());
        this.route_UCB_bus_received = new ConcurrentHashMap<String, List<List<Integer>>>();// Collections.synchronizedMap(new HashMap<String, List<List<Integer>>>());
        this.speed_vehicle_received = new ConcurrentHashMap<String, ArrayList<Double>>(); //Collections.synchronizedMap(new HashMap<Integer, ArrayList<Double>>());
        //July,2020
        this.isConnected = false;
    }
    
    public boolean isConnected() {
    	return this.isConnected;
    }
    
    public void waitUntilClose() {
    	try {
			this.closeLatch.await();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
    
    public void sendMsgToRemote(String msg) {
    	try {

			this.session.getRemote().sendString(msg);
    	} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        System.out.printf("Connection closed: %d - %s%n", statusCode, reason);
        this.isConnected = false;
        this.session = null;
        this.closeLatch.countDown(); // trigger latch
    }

    @OnWebSocketConnect
    public void onConnect(Session session)
    {
        System.out.printf("%s::Connected to : %s%n", System.currentTimeMillis(), 
        		session.getRemote().getInetSocketAddress());
        this.session = session;
        this.isConnected = true;
        try
        {
            Future<Void> fut;
            fut = session.getRemote().sendStringByFuture("Connected to : " + this.session.getLocalAddress());
            fut.get(2, TimeUnit.SECONDS); // wait for send to complete.

        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }

    @OnWebSocketMessage
    public void onMessage(String msg)
    {
    	String debug_msg = msg.substring(0, Math.min(debug_msg_len, msg.length()));
        System.out.printf("%s::%s::%s%n", System.currentTimeMillis(), 
        		this.session.getRemote().getInetSocketAddress().toString(), debug_msg);
        // parse the message and update link_UCB_received map
        String[] lines = msg.split("\\r?\\n");
        for(String line : lines) {
        	// energy updates : only need to parse lines starting with 'E'
        		if (line.charAt(0) == 'E') {
        			updateLinkUCB(line); 
        		}
        		// route_UCB : parse lines with 'OD'
        		if(line.substring(0, 2).equals("OD") ) {
        			updateRouteUCB(line);
        		}
        		//July, 2020
        		// energy bus
        		if (line.substring(0, 2).equals("BE")) {
        			updateLinkUCBBus(line);                    
        		}
        		// route bus
        		if (line.substring(0, 3).equals("BOD")) {
        			updateRouteUCBBus(line); 
        		}
        		// velocity vehicle
        		if (line.charAt(0) == 'V') {
        			updateSpeedVehicle(line); 
        		}
        		//July, 2020
        }
    }

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        System.out.print("WebSocket Error: ");
//        cause.printStackTrace(System.out);
//        System.out.println(cause.getCause().toString());
        this.session = null;
        this.isConnected = false;
        this.closeLatch.countDown(); // trigger latch
    }
    
    //vehicle: routeUCB
    private void updateRouteUCB(String msg) {

    	String[] comma_splits = msg.split(",");
    	int origin = Integer.parseInt(comma_splits[1]);
    	int dest = Integer.parseInt(comma_splits[2]);

    	// create OD pair
    	String OD = Integer.toString(origin) + "," + dest;// Pair<Integer, Integer> OD = new Pair<Integer, Integer>(Origin, Dest);
    	List<List<Integer>> roadLists = new ArrayList<List<Integer>>();
    	
    	String[] RD_splits = msg.split("RD");
    	for(int i = 1; i < RD_splits.length; i++) {
    		String[] roads = RD_splits[i].substring(1,RD_splits[i].length()-1).split(",");
    		List<Integer> roadList = new ArrayList<Integer>();
    		for(String r : roads) {
    			roadList.add(Integer.parseInt(r));
    		}
    		roadLists.add(roadList);
    	}
//    	synchronized (this.route_UCB_received) {
//	    	if(!route_UCB_received.containsKey(OD)) {
//	    		this.route_UCB_received.put(OD, roadLists);
//				   		
//	    	}
//	    	else  {
//	    		System.out.println("ERROR : initial road list already found for this OD pair : "
//	    				+origin+","+dest);
//	    	}
//    	}
    	this.route_UCB_received.putIfAbsent(OD, roadLists);
    }
    
    //July,2020, bus: routeUCB
    private void updateRouteUCBBus(String msg) {

    	String[] comma_splits = msg.split(",");
    	int origin = Integer.parseInt(comma_splits[1]);
    	int dest = Integer.parseInt(comma_splits[2]);

    	// create OD pair
    	String OD = Integer.toString(origin) + "," + dest;// Pair<Integer, Integer> OD = new Pair<Integer, Integer>(Origin, Dest);
    	List<List<Integer>> roadLists = new ArrayList<List<Integer>>();
    	
    	String[] RD_splits = msg.split("RD");
    	for(int i = 1; i < RD_splits.length; i++) {
    		String[] roads = RD_splits[i].substring(1,RD_splits[i].length()-1).split(",");
    		List<Integer> roadList = new ArrayList<Integer>();
    		for(String r : roads) {
    			roadList.add(Integer.parseInt(r));
    		}
    		roadLists.add(roadList);
    	}
//    	synchronized (this.route_UCB_bus_received) {
//	    	if(!route_UCB_bus_received.containsKey(OD)) {
//	    		this.route_UCB_bus_received.put(OD, roadLists);
//	    	}
//	    	else  {
//	    		System.out.println("ERROR : initial road list already found for this Bus OD pair : "
//	    				+origin+","+dest);
//	    	}
//    	}
    	this.route_UCB_bus_received.putIfAbsent(OD, roadLists);
    	
    }
    
    
    // vehicle: linkUCB
    private void updateLinkUCB(String msg) {
    	String[] words = msg.split(",");

    	// get the id
    	String ID = words[1];
    	
    	// get energy values
    	ArrayList<Double> received_values = new ArrayList<Double>();
    	for(int i = 2; i < words.length; i++) {
    		received_values.add(Double.parseDouble(words[i]));
    	}
//    	synchronized (this.link_UCB_received) {
//    		if(this.link_UCB_received.containsKey(ID)){
//        		
//        		for(Double val : received_values) {
//        			this.link_UCB_received.get(ID).add(val);
//        		}
//        	}
//        	else {
//        		this.link_UCB_received.put(ID, received_values);
//        	}
//		}
    	if(this.link_UCB_received.containsKey(ID))
    	{
    		ArrayList<Double> updated_values = this.link_UCB_received.get(ID);
    		updated_values.addAll(received_values);
    		this.link_UCB_received.replace(ID, updated_values);
    	}
    	else {
    		this.link_UCB_received.putIfAbsent(ID, received_values);
    	}
    }
    
  //July,2020 bus: linkUCB
    private void updateLinkUCBBus(String msg) {
    	String[] words = msg.split(",");

    	// get the id
    	String ID = words[1];
    	
    	// get energy values
    	ArrayList<Double> received_values = new ArrayList<Double>();
    	for(int i = 2; i < words.length; i++) {
    		received_values.add(Double.parseDouble(words[i]));
    	}
//    	synchronized (this.link_UCB_bus_received) {
//    		if(this.link_UCB_bus_received.containsKey(ID)){
//        		
//        		for(Double val : received_values) {
//        			this.link_UCB_bus_received.get(ID).add(val);
//        		}
//        	}
//        	else {
//        		this.link_UCB_bus_received.put(ID, received_values);
//        	}
//		}
    	if(this.link_UCB_bus_received.containsKey(ID))
    	{
    		ArrayList<Double> updated_values = this.link_UCB_bus_received.get(ID);
    		updated_values.addAll(received_values);
    		this.link_UCB_bus_received.replace(ID, updated_values);
    	}
    	else {
    		this.link_UCB_bus_received.putIfAbsent(ID, received_values);
    	}
    	
    }
    
  //July,2020 bus: vehicle velocity
    private void updateSpeedVehicle(String msg) {
    	String[] words = msg.split(",");

    	// get the id
    	String ID = words[1];
    	
    	// get energy values
    	ArrayList<Double> received_values = new ArrayList<Double>();
    	for(int i = 2; i < words.length; i++) {
    		received_values.add(Double.parseDouble(words[i]));
    	}
//    	synchronized (this.speed_vehicle_received) {
//    		if(this.speed_vehicle_received.containsKey(ID)){
//        		
//        		for(Double val : received_values) {
//        			this.speed_vehicle_received.get(ID).add(val);
//        		}
//        	}
//        	else {
//        		this.speed_vehicle_received.put(ID, received_values);
//        	}
//		}
    	if(this.speed_vehicle_received.containsKey(ID))
    	{
    		ArrayList<Double> updated_values = this.speed_vehicle_received.get(ID);
    		updated_values.addAll(received_values);
    		this.speed_vehicle_received.replace(ID, updated_values);
    	}
    	else {
    		this.speed_vehicle_received.putIfAbsent(ID, received_values);
    	}
    	
    }
      
    public ConcurrentHashMap<String, ArrayList<Double>> getLinkUCBData() {
    	return this.link_UCB_received;
    }
    
    //July, 2020
    public ConcurrentHashMap<String, ArrayList<Double>> getLinkUCBDataBus() {
    	return link_UCB_bus_received;
    }
    
    //July, 2020
    public ConcurrentHashMap<String, ArrayList<Double>> getSpeedVehicle(){
    	return speed_vehicle_received;
    }
        
    public void printLinkUCBData() {
//    	synchronized (this.link_UCB_received) {
    	for(String ID : this.link_UCB_received.keySet()) {
    		System.out.println("ID : " + ID);
    		System.out.print("Values : ");
    		ArrayList<Double> values = link_UCB_received.get(ID);
    		if(values ==  null) {
    			continue;
    		}
    		else {
    			for(Double val : values) {
    				System.out.print(val + ",");
    			}
    		}
    		System.out.println();
    	}
//		}	
    }
    
    
    public void printRouteUCBData() {
    	
//    	synchronized (this.route_UCB_received) {
    	for(String OD : this.route_UCB_received.keySet()) {
    		System.out.println("OD : "+OD);
    		List<List<Integer>> RoadLists = this.route_UCB_received.get(OD);
    		for(List<Integer> RoadList : RoadLists) {
    			System.out.print("Roads : ");
    			for(Integer r : RoadList) {
    				System.out.print(r+",");
    			}
    			System.out.println();
    		}
    	}
//		}
    	
    }
    
    public ConcurrentHashMap<String, List<List<Integer>>> getRouteUCBData(){
    	return route_UCB_received;
    }
    
    //July, 2020
    public ConcurrentHashMap<String, List<List<Integer>>> getRouteUCBDataBus(){
    	return route_UCB_bus_received;
    }
}