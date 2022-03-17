package addsEVs.routing;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import addsEVs.citycontext.Junction;
import addsEVs.citycontext.Road;
import repast.simphony.space.graph.Network;

// This class facilitates the machine learning based routing
// class is extended from VehicleRouting and overrides the computeRoute method
// author : Charitha Saumya

public class VehicleRoutingML extends VehicleRouting {
	
	class RouteReader implements Runnable {
		
		public RouteMap data;
		private String inputFileName;
		public RouteReader(String fileName) {
			this.data = new RouteMap();
			this.inputFileName = fileName; 
		}
		
		
		@Override
		public void run() {
			while(true) {
				
				// Update routing info
				BufferedReader reader;
				try {
					reader = new BufferedReader(new FileReader(this.inputFileName));
					String line = reader.readLine();
					while (line != null) {
						
						this.data.updateRoute(line);
						// Read next line
						line = reader.readLine();
					}
					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				
				// Sleep for some time
				try {
					Thread.sleep(2000);
				}
				catch (InterruptedException e) {
				    e.printStackTrace();
				}
			}
		}
	}
	
	private String predictionFile;
	private RouteReader reader;

	
	public VehicleRoutingML(Network<Junction> network, String predFile) {
		super(network);
		this.predictionFile = predFile;
		this.reader = new RouteReader(this.predictionFile);
		
		// Create a new thread and run it
		Thread readerThread = new Thread(this.reader);
		readerThread.start();
		
	}
	
	@Override
	public List<Road> computeRoute(Road currentRoad, Road destRoad,
			Junction currJunc, Junction destJunc) {
	
		List<Road> roadPath;
		
		// Read the route from routeMap
		roadPath = new ArrayList<Road>();
		roadPath.add(currentRoad);
		
		// Get the route for the srcJunc,destJunc pair
		String key = currJunc.toString() + "," + destJunc.toString();
		ArrayList<Integer> pathLinks = this.reader.data.getRoute(key);
		
		for(Integer linkid : pathLinks) {
			Road road = this.cityContext.findRoadWithLinkID(linkid);
			roadPath.add(road);
		}
		
		return roadPath;
	}
	

}
