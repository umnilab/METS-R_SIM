package addsEVs.network;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import com.sun.xml.internal.bind.v2.runtime.unmarshaller.XsiNilLoader.Array;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.routing.MabManager;
import repast.simphony.essentials.RepastEssentials;
import util.Pair;

public class RemoteDataClientManager
{
	public static boolean isServerListening(String host, int port)
    {
        Socket s = null;
        try
        {
            s = new Socket(host, port);
            
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
        finally
        {
            if(s != null)
                try {s.close();}
                catch(Exception e){}
        }
    }
	
	private static class ClientExecuter implements Runnable {
		private WebSocketClient client;
		private RemoteDataClient socket;
		private String addr;
		
		public ClientExecuter(String addr) {
			// TODO Auto-generated constructor stub
			this.client = new WebSocketClient();
	        this.socket = new RemoteDataClient();
	        this.addr = addr;
	        
		}
		
		public String getURI(){
			return this.addr;
		}
		
		public ConcurrentHashMap<String, ArrayList<Double>> getLinkUCBMap() {
			return socket.getLinkUCBData();
		}
		
		//July, 2020
		public ConcurrentHashMap<String, ArrayList<Double>> getLinkUCBMapBus() {
			return socket.getLinkUCBDataBus();
		}
		
		//July, 2020
		public ConcurrentHashMap<String, ArrayList<Double>> getSpeedVehicle(){
			return socket.getSpeedVehicle();
		}
		
		public void printUCBLinkMap() {
			socket.printLinkUCBData();
		}
		
	
		public ConcurrentHashMap<String, List<List<Integer>>> getRouteUCBMap(){
			return socket.getRouteUCBData();
		}
		
		//July, 2020
		public ConcurrentHashMap<String, List<List<Integer>>> getRouteUCBMapBus(){
			return socket.getRouteUCBDataBus();
		}
		
		
		public void printRouteUCBMap() {
			socket.printRouteUCBData();
		}
		
		public void sendRouteResult(Map<String, Integer> routeResult) {
			String msg = this.convertRouteResultToMsg(routeResult);
			this.socket.sendMsgToRemote(msg);
		}
		
		//July, 2020
		public void sendRouteResultBus(Map<String, Integer> routeResultBus) {
			String msg = this.convertRouteResultToMsgBus(routeResultBus);
			this.socket.sendMsgToRemote(msg);
		}
		
		public String convertRouteResultToMsg(Map<String, Integer> routeResult) {
			String s = new String("");
			s += "RR,";
			//System.out.println("Within convertRouteResultToMsg:"+routeResult);
			for(String OD : routeResult.keySet()) {
				int result = routeResult.get(OD);
				s+= OD+","+result+",";
			}
			return s;
		}
		
		//July,2020
		public String convertRouteResultToMsgBus(Map<String, Integer> routeResultBus) {
			String s = new String("");
			s += "BRR,";
			//System.out.println("Within convertRouteResultToMsg:"+routeResult);
			for(String OD : routeResultBus.keySet()) {
				int result = routeResultBus.get(OD);
				s+= OD+","+result+",";
			}
			return s;
		}
		
		@Override
		public void run() {
			// TODO Auto-generated method stub
			try
	        {
	            client.start();

	            URI uri = new URI(this.addr);
	           
	            // wait until the server is live
//	            System.out.println("Waiting until the server is up at : "+ this.addr);
//	            while(!isServerListening(uri.getHost(), uri.getPort())); // ZL: This is always true, so the server would not wait wait
//	            System.out.println("Server UP!");
//	            Thread.sleep(500);
	            ClientUpgradeRequest request = new ClientUpgradeRequest(); 
	            System.out.printf("Connecting to : %s%n", uri);
	            client.connect(socket, uri, request);
	            
	            // wait until the socket is closed
	            this.socket.waitUntilClose();
	        }
	        catch (Throwable t)
	        { 
	            t.printStackTrace();
	        }
	        
            try
            {
            	System.out.println("Stopping websocket client");
                client.stop();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
	        
		}
	}
	
	public static BufferedWriter bw3;
	
	public static ArrayList<String> ReadConfigFile(String fname ) {
		ArrayList<String> destURIs = new ArrayList<String>();
		
		String webSocketPrefix = "ws://127.0.0.1:";
		String workingDir = System.getProperty("user.dir");
		String configFilePath = workingDir + fname;
		String socketStr= "";
		
		BufferedReader reader;
		try {
			reader = new BufferedReader(new FileReader(configFilePath));
			String line = reader.readLine();
			while (line != null) {
//				System.out.println(line);
				// read next line
				line = reader.readLine();
				if(!line.startsWith("#") & line.contains("socket_port_numbers")) {
					System.out.println(line);
					socketStr = line.split("=")[1].replaceAll("\\s","");
					break;
					
				}
			}
			reader.close();
		} catch (IOException e) {
			System.out.println("Can not read the config file at : " + configFilePath);
			e.printStackTrace();
			System.exit(-1);
		}
		
		String[] sockets = socketStr.split(",");
		for(String socket : sockets) {
			String destURI = webSocketPrefix + Integer.parseInt(socket);
			destURIs.add(destURI);
		}
		
		
		return destURIs;
	}
	
    public static void main(String[] args) throws InterruptedException{	
    	
    	// get the hpc config file from args
    	String config_fname =  "";
    	ArrayList<String> destURIs = new ArrayList<String>();
    	if(args.length > 0) {
    		config_fname = args[0];
    		// all IP:socket pairs for simulation instances we are tracking
            destURIs = ReadConfigFile(config_fname);
    	}
    	else {
    		//System.out.println("Please specify the location of run.config file relative to source root directory");
    		//System.exit(-1);
    		System.out.println("Debug mode, using the configuration in data.properties.");
    		destURIs.add("ws://127.0.0.1:" + GlobalVariables.NETWORK_LISTEN_PORT);
    	}
    	
    	try{
			FileWriter fw = new FileWriter("HPCLogger.csv", false);
			bw3 = new BufferedWriter(fw);
			bw3.write("tick,ev_records,bus_records,speed_records");
			bw3.newLine();
			bw3.flush();
			System.out.println("HPC logger created!");
		} catch (IOException e){
			e.printStackTrace();
			System.out.println("HPC logger failed.");
		}

        System.out.println("Working Directory = " + System.getProperty("user.dir"));
        
        // all clients : each client will connect to simulation instance in destURI. one for each address
        ArrayList<ClientExecuter> clients = new ArrayList<ClientExecuter>();
        
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(20);
        
        // xjw: Initialize the MabManager;
        MabManager mabManager = new MabManager();
        
        Map<String, Integer> currentHours = new HashMap<String, Integer>();
        
        // This part is still problematic, ZL 20200626
        // for each instance initiate a data client
        for(String destURI : destURIs){
        	URI uri;
			try {
				uri = new URI(destURI);
				
				// wait until the server is live
	            System.out.println("Waiting until the server is up at -> "
	            		+ "host : " + uri.getHost() + " port : "+ uri.getPort());
	            while(!isServerListening(uri.getHost(), uri.getPort())); // ZL: This is always true, so the server would not wait wait
	            System.out.println("Server UP!");
			} catch (URISyntaxException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			Thread.sleep(100);
        	// create new client
        	ClientExecuter client = new ClientExecuter(destURI);
        	
        	// execute the client in a thread
        	executor.execute(client);
        	
        	// wait until all client has connected to simulation instance
        	while(!client.socket.isConnected());
        	
        	//  add to client list
        	clients.add(client);
        	
        	// initialize the sim hours
        	currentHours.put(destURI, 0);
        }

        System.out.print("All Connected!");

        // Initialize the RouteUCB;
        //client.printRouteUCBMap(); 
        ConcurrentHashMap<String, List<List<Integer>>> routeUCBMap = new ConcurrentHashMap<String, List<List<Integer>>>();
        
        // LZ: wait until UCB route is received, only do this once
        // Chairtha : TODO in this loop we are receiving the map from all clients
        // but copy it to same map. this is redundant!
        int index = 0;
        while (routeUCBMap.isEmpty()){ 
        	// Charitha : wait some time until the routeUCBMap is recieved
        	// adjust this wait time if routeUCBMap is not received
        	routeUCBMap = clients.get(index).getRouteUCBMap();
        	index += 1;
        	index = index % clients.size();
        	Thread.sleep(500);
        	System.out.println("Here!");
        }
        
        System.out.println("routeUCBMap received");
        
        //July, 2020
        ConcurrentHashMap<String, List<List<Integer>>> routeUCBMapBus = new ConcurrentHashMap<String, List<List<Integer>>>();
        index = 0;
        // Chairtha : TODO in this loop we are receiving the map from all clients
        // but copy it to same map. this is redundant!
        while (routeUCBMapBus.isEmpty()){ 
        	routeUCBMapBus = clients.get(index).getRouteUCBMapBus();
        	index += 1;
        	index = index % clients.size();
        	Thread.sleep(500);

        }
        System.out.println("routeUCBBusMap received");
    
        mabManager.refreshRouteUCB(routeUCBMap);
        mabManager.refreshRouteUCBBus(routeUCBMapBus);
        mabManager.initializeLinkEnergy1(); // initialize the link average speed
        mabManager.initializeLinkEnergy2(); // initialize the link length
        ConcurrentHashMap<Integer, Double> roadLength = mabManager.getRoadLengthMap();  // get the roadLength  

        // Initialize the route result
        ArrayList<Map<String, Integer>> routeResult = new ArrayList<>();
        // Initialize the route result for bus
        ArrayList<Map<String, Integer>> routeResultBus = new ArrayList<>();
        for(int hour=0; hour<GlobalVariables.SIMULATION_STOP_TIME*GlobalVariables.SIMULATION_STEP_SIZE/3600; hour++){
        	Map<String, Integer> oneResult = new HashMap<String, Integer>();
        	for (String i: routeUCBMap.keySet()) {
        		oneResult.put(i,-1);   // -1 is the default value. The route number starts from 0.	
            }
        	routeResult.add(oneResult);
        	
        	Map<String, Integer> oneResultBus = new HashMap<String, Integer>();
        	for (String i: routeUCBMapBus.keySet()) {
        		oneResultBus.put(i,-1);   // -1 is the default value. The route number starts from 0.	
            }
        	routeResultBus.add(oneResultBus);
        }
        System.out.println("Route result maps initialized");
        
        //July, 2020
        // UCB  stuff should go here
        // for each client in clients use getLinkUCBMap to get the linkUCB map
        int tick = 0;
        while(true) {

			for (ClientExecuter client : clients) {
				//System.out.print("THIS2.5");
				if(client.socket.isConnected()){
					// client.printUCBLinkMap();
//					System.out.print("THIS3");
					// xjw: step 1: refresh the linkUCB
					ConcurrentHashMap<String, ArrayList<Double>> linkUCBMap = client.getLinkUCBMap();
				    currentHours.put(client.getURI(), mabManager.refreshLinkUCB(linkUCBMap));
					// July,2020 step1 for bus
					// refresh the energy data
					ConcurrentHashMap<String, ArrayList<Double>> linkUCBMapBus = client.getLinkUCBMapBus();
					mabManager.refreshLinkUCBBus(linkUCBMapBus);
					// refresh the speed vehicle 
					ConcurrentHashMap<String, ArrayList<Double>> speedVehicle = client.getSpeedVehicle();
					mabManager.refreshLinkUCBShadow(speedVehicle, roadLength);
					long timestamp = System.currentTimeMillis() / 1000;
					String formated_msg = tick + "," + timestamp + "," + linkUCBMap.size() + ","+ linkUCBMapBus.size()+","+ speedVehicle.size();
					try{
						bw3.write(formated_msg);
						bw3.newLine();
					} catch(IOException e){
						e.printStackTrace();
					}
					//July,2020
					Thread.sleep(500);
				}
			}
			
			for(int hour: currentHours.values()){
				// xjw: step 2: make the decision for od
				for (String i : routeResult.get(hour).keySet()) {
					int routeAction = mabManager.ucbRouting(i, hour);
					routeResult.get(hour).put(i, routeAction);
				}
				
				//July,2020 step2: make the decision for bus
				for (String i : routeResultBus.get(hour).keySet()) {
					int routeAction = mabManager.ucbRoutingBus(i, hour);
					routeResultBus.get(hour).put(i, routeAction);
				}	
			}
					
			
			// xjw: step 3: return the routing result.
			for (ClientExecuter client : clients) {
				
				if(client.socket.isConnected()){
				    
//					System.out.println("Within main loop:"+routeResult);
//					System.out.println("Within main loop2:"+routeResultBus);
					// Charitha : use follwing to send the route result
					System.out.print(routeResult.get(currentHours.get(client.addr)));
					client.sendRouteResult(routeResult.get(currentHours.get(client.addr)));
					//July, 2020
					client.sendRouteResultBus(routeResultBus.get(currentHours.get(client.addr)));
					Thread.sleep(500);
				}
			}
			try {
				bw3.flush();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			tick+=1;
			
			// check if at least one client is connected to server
			boolean allDisconnected = true;
			
			for(ClientExecuter client : clients) {
				if(client.socket.isConnected()) {
					allDisconnected = false;
					break;
				}
			}
			if(allDisconnected) {
				System.out.println("All clients are disconnected. Terminating!");
				break;
			}
        }
        
        // exit the program
        System.exit(0);
    }
    
  
}