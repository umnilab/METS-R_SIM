package addsEVs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.measure.unit.NonSI;
import javax.measure.unit.SI;

import repast.simphony.context.Context;
import repast.simphony.dataLoader.ContextBuilder;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.ScheduleParameters;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.graph.Network;

import org.apache.log4j.Logger;
import org.geotools.referencing.GeodeticCalculator;
import org.json.simple.JSONObject;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.GlobalVariables;
import addsEVs.citycontext.*;
import addsEVs.data.*;
import addsEVs.partition.*;
import addsEVs.routing.RouteV;
import addsEVs.vehiclecontext.*;

/*
 * This is the entrance point of the METS-R system, which includes:
 * 0. Loading data
 * 1. The initialization of different objects
 * 2. Start, pause and the stop of the simulation
 * 3. Event scheduling
 * 4. Data communication/logs
 */


public class ContextCreator implements ContextBuilder<Object> {
	private static Context<Object> mainContext; // Useful to keep a reference to the main context
	/* Loggers */
	// Loggers for formatted data (csv data)
	public static BufferedWriter ev_logger; // Vehicle Trip logger
	public static BufferedWriter bus_logger; // Vehicle Trip logger
	public static BufferedWriter link_logger; // Link energy logger
	public static BufferedWriter network_logger; // Road network vehicle logger
	public static BufferedWriter zone_logger; // Zone logger
	public static BufferedWriter charger_logger; // Charger logger
	// A general logger for console outputs
	public static Logger logger = Logger.getLogger(ContextCreator.class);
	
	/* Simulation data */
	private static int agentID = 0; // Used to generate unique agent ids
	public static double startTime; // Start time of the simulation
	private static int duration_ = (int) (3600 / GlobalVariables.SIMULATION_STEP_SIZE); // Refreshing the background speed per hour (3600s)
	private static int duration02_ = GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL; // Refreshing the network for routing
	private static int duration03_ = GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL; // Time gap to repartition the network
	
	/* Simulation objects */
	public static MetisPartition partitioner = new MetisPartition(GlobalVariables.N_Partition); // Creating the network partitioning object
	public static NetworkEventHandler eventHandler = new NetworkEventHandler(); // Creating the event handler object
	public static BackgroundTraffic backgroundTraffic = new BackgroundTraffic(); // Reading background traffic file into treemap
	public static BackgroundDemand backgroundDemand = new BackgroundDemand(); // Reading travel demand into treemap
	public static BusSchedule busSchedule = new BusSchedule(); // Reading the default bus schedule
	CityContext cityContext;
	VehicleContext vehicleContext;
	DataCollectionContext dataContext;
	
    /* Data communication */
	// Candidate path sets for eco-routing, id: origin-destination pair, value: n paths, each path is a list of LinkID
	public static HashMap<String, List<List<Integer>>> route_UCB = new HashMap<String, List<List<Integer>>>(); 
	// Candidate path sets for transit eco-routing, id: origin-destination pari, value: n paths, each path is a list of LinkID
	public static HashMap<String, List<List<Integer>>> route_UCB_bus = new HashMap<String, List<List<Integer>>>(); 
	// A volatile (thread-read-safe) flag to check if the route_UCB is fully populated
	public static volatile boolean isRouteUCBPopulated = false;
	public static volatile boolean isRouteUCBBusPopulated = false;
	// Route results received from RemoteDataClient
	public static ConcurrentHashMap<String, Integer> routeResult_received = new ConcurrentHashMap<String, Integer>();
	public static ConcurrentHashMap<String, Integer> routeResult_received_bus = new ConcurrentHashMap<String, Integer>();
	
	/* Functions */
	// Reading simulation properties stored at data/Data.properties
	public Properties readPropertyFile() {
		Properties propertiesFile = new Properties();
		String working_dir = System.getProperty("user.dir");
		try {
			propertiesFile.load(new FileInputStream(working_dir + "/data/Data.properties"));
		} catch (Exception e) {
			e.printStackTrace();
		}
		return propertiesFile;
	}
	
	// Pausing the simulation
	public void handleSimulationSleep() {
		while (GlobalVariables.SIMULATION_SLEEPS == 0) {
			try {
				Thread.sleep(1000);
				logger.info("Simulation pausing");
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

		}
	}
	
	// Initializing simulation objects, including: city (network + spawn points including zone and charging stations), vehicle, data collector
	public void buildSubContexts() {
		/* Create city context */
		this.cityContext = new CityContext();
		mainContext.addSubContext(cityContext);
		this.cityContext.createSubContexts();
		this.cityContext.buildRoadNetwork();
		this.cityContext.createNearestRoadCoordCache();
		this.cityContext.setRelocationGraph();
		
		/* Create vehicle context */
		this.vehicleContext = new VehicleContext();
		mainContext.addSubContext(vehicleContext);
		
		/* Create data collector */
		this.dataContext = new DataCollectionContext();
		mainContext.addSubContext(dataContext);

		/* Initialize operational parameters */
		// Set the bus info in each zone
		for (ArrayList<Integer> route : busSchedule.busRoute) {
			int i = 0;
			for (int zoneID : route) {
				Zone zone = ContextCreator.getCityContext().findZoneWithIntegerID(zoneID);
				if(zone.getZoneClass() == 0) { // normal zone, the destination should be hub
					for (int destinationID: route) {
					    if(GlobalVariables.HUB_INDEXES.contains(destinationID)){
					    	zone.setBusInfo(destinationID, busSchedule.busGap.get(i));
					    }
					}
				}
				else if(zone.getZoneClass() == 1) { // hub, the destination should be other zones (can be another hub)
					for (int destinationID: route) {
					    if(zone.getIntegerID() != destinationID){
					    	zone.setBusInfo(destinationID, busSchedule.busGap.get(i));
					    }
						
					}
				}
				i += 1;
			}
		}
		createUCBRoutes(); // generate UCB routes before the simulation is started, store one copy of it to save time
		createUCBBusRoutes(); // generate Bus UCB routes before the simulation is started, store one copy of it to save time

		/* Create output files */
		String outDir = GlobalVariables.AGG_DEFAULT_PATH;
		String timestamp = new SimpleDateFormat("YYYY-MM-dd-hh-mm-ss").format(new Date()); // get the current time stamp
		String outpath = outDir + File.separatorChar + GlobalVariables.NUM_OF_EV + "_" + GlobalVariables.NUM_OF_BUS; // create the overall file path, named after the demand filename
		try {
			Files.createDirectories(Paths.get(outpath));
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		logger.info("Vehicle logger creating...");
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "EVLog-" + timestamp + ".csv", false);
			ev_logger = new BufferedWriter(fw);
			ev_logger.write("tick,vehicleID,tripType,originID,destID,distance,departureTime,cost,choice,passNum");
			ev_logger.newLine();
			ev_logger.flush();
			logger.info("EV logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("EV logger failed.");
		}
		logger.info("Bus logger creating...");
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "BusLog-" + timestamp + ".csv", false);
			bus_logger = new BufferedWriter(fw);
			bus_logger.write(
					"tick,vehicleID,routeID,tripType,originID,destID,distance,departureTime,cost,choice,passOnBoard");
			bus_logger.newLine();
			bus_logger.flush();
			logger.info("Bus logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Bus logger failed.");
		}
	    logger.info("Link energy logger creating...");
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "LinkLog-" + timestamp + ".csv", false);
			link_logger = new BufferedWriter(fw);
			link_logger.write("tick,linkID,flow,consumption");
			link_logger.newLine();
			link_logger.flush();
			logger.info("Energy logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Energy logger failed.");
		}
		logger.info("Network logger creating...");
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "NetworkLog-" + timestamp + ".csv", false);
			network_logger = new BufferedWriter(fw);
			network_logger.write(
					"tick,vehOnRoad,emptyTrip,chargingTrip,generatedTaxiPass,generatedBusPass,generatedCombinedPass,"
					+ "taxiPickupPass,busPickupPass,combinePickupPart1,combinePickupPart2,"
					+ "taxiServedPass,busServedPass,"
					+ "taxiLeavedPass,busLeavedPass,"
					+ "numWaitingTaxiPass,numWaitingBusPass,"
					+ "batteryMean,batteryStd");
			network_logger.newLine();
			network_logger.flush();
			logger.info("Network logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Network logger failed.");
		}
		logger.info("Zone logger creating...");
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "ZoneLog-" + timestamp + ".csv", false);
			zone_logger = new BufferedWriter(fw);
			zone_logger.write(
					"tick,zoneID,numTaxiPass,numBusPass,vehStock,taxiGeneratedPass,busGeneratedPass,generatedCombinedPass,"
					+ "taxiPickupPass,busPickupPass,combinePickupPart1,combinePickupPart2,"
					+ "taxiServedPass,busServedPass,taxiPassWaitingTime,busPassWaitingTime,"
					+ "taxiLeavedPass,busLeavedPass,taxiWaitingTime,futureDemand,futureSupply");
			zone_logger.newLine();
			zone_logger.flush();
			logger.info("Zone logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Zone logger failed.");
		}
		logger.info("Charger logger creating...");
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "ChargerLog-" + timestamp + ".csv", false);
			charger_logger = new BufferedWriter(fw);
			charger_logger
					.write("tick,chargerID,vehID,vehType,chargerType,waitingTime,chargingTime,initialBatteryLevel");
			charger_logger.newLine();
			charger_logger.flush();
			logger.info("Charger logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Charger logger failed.");
		}
	}

	// Update route_UCB
	@SuppressWarnings("unchecked")
	private void createUCBRoutes() {
		cityContext.modifyRoadNetwork(); // Refresh road status
		try { // First try to read from the file. If the file does not exist, then create it.
			FileInputStream fileIn = new FileInputStream("data/NYC/candidate_routes.ser");
			ObjectInputStream in = new ObjectInputStream(fileIn);
			ContextCreator.route_UCB = (HashMap<String, List<List<Integer>>>) in.readObject();
			logger.info("Serialized data is loaded from data/candidate_routes.ser");
			in.close();
			fileIn.close();
		} catch (FileNotFoundException i) { // File does not exist, let's create one
			logger.info("Candidate routes initialization ...");
			Geography<Zone> zoneGeography = ContextCreator.getZoneGeography();
			// Loop over all OD pairs, will take several minutes
			for (Zone origin : zoneGeography.getAllObjects()) {
				for (Zone destination : zoneGeography.getAllObjects()) {
					if (origin.getIntegerID() != destination.getIntegerID()
							&& (GlobalVariables.HUB_INDEXES.contains(origin.getIntegerID())
									|| GlobalVariables.HUB_INDEXES.contains(destination.getIntegerID()))) {
						logger.info(
								"Creating routes: " + origin.getIntegerID() + "," + destination.getIntegerID());
						try {
							List<List<Integer>> candidate_routes = RouteV.UCBRoute(origin.getCoord(),
									destination.getCoord());
							for (int k = 0; k < candidate_routes.size(); k++) {
								for (int j = 0; j < candidate_routes.get(k).size(); j++) {
									logger.info(candidate_routes.get(k).get(j));
								}
							}
							ContextCreator.route_UCB.put(
									Integer.toString(origin.getIntegerID()) + "," + destination.getIntegerID(),
									candidate_routes);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			}
			try { // Try to save the candidate paths between each OD pair
				FileOutputStream fileOut = new FileOutputStream("data/NYC/candidate_routes.ser");
				ObjectOutputStream out = new ObjectOutputStream(fileOut);
				out.writeObject(ContextCreator.route_UCB);
				out.close();
				fileOut.close();
				logger.info("Serialized data is saved in data/candidate_routes.ser");
			} catch (IOException o) { // Result cannot be save
				o.printStackTrace();
				return;
			}
		} catch (ClassNotFoundException c) { // Result cannot be load
			c.printStackTrace();
			return;
		} catch (IOException o) { // Result cannot be load
			o.printStackTrace();
			return;
		}

		// Mark route_UCB is populated. This flag is checked in remote data client manager before starting the data server
		ContextCreator.isRouteUCBPopulated = true;
	}

	// Update route_BusUCB
	@SuppressWarnings("unchecked")
	private void createUCBBusRoutes() {
		cityContext.modifyRoadNetwork(); // Refresh road status
		try { // First try to read from the file. If the file does not exist, then create it.
			FileInputStream fileIn = new FileInputStream("data/NYC/candidate_routes_bus.ser");
			ObjectInputStream in = new ObjectInputStream(fileIn);
			ContextCreator.route_UCB_bus = (HashMap<String, List<List<Integer>>>) in.readObject();
			logger.info("Serialized data is loaded from data/candidate_routes_bus.ser");
			in.close();
			fileIn.close();
		} catch (FileNotFoundException i) { // File does not exist, let's create one
			logger.info("Candidate routes initialization ...");
			Geography<Zone> zoneGeography = ContextCreator.getZoneGeography();
			for (Zone origin : zoneGeography.getAllObjects()) {
				for (Zone destination : zoneGeography.getAllObjects()) {
					if (origin.getIntegerID() != destination.getIntegerID()) {
						logger.info(
								"Creating routes: " + origin.getIntegerID() + "," + destination.getIntegerID());
						try {
							ContextCreator.route_UCB_bus.put(
									Integer.toString(origin.getIntegerID()) + "," + destination.getIntegerID(),
									RouteV.UCBRoute(origin.getCoord(), destination.getCoord()));
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			}
			try { // Save the calculation result
				FileOutputStream fileOut = new FileOutputStream("data/NYC/candidate_routes_bus.ser");
				ObjectOutputStream out = new ObjectOutputStream(fileOut);
				out.writeObject(ContextCreator.route_UCB_bus);
				out.close();
				fileOut.close();
				logger.info("Serialized data is saved in data/candidate_routes_bus.ser");
			} catch (IOException o) { // Result cannot be save
				o.printStackTrace();
				return;
			}
		} catch (ClassNotFoundException c) { // Result cannot be load
			c.printStackTrace();
			return;
		} catch (IOException o) { // Result cannot be load
			o.printStackTrace();
			return;
		}
		ContextCreator.isRouteUCBBusPopulated = true; // Mark route_UCBBus is populated
	}
	
	// Formatting the candidate routes information as a message string
	public static String getUCBRouteStringForOD(String OD) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("MSG_TYPE", "OD_PAIR");
		jsonObj.put("OD", OD);
		List<List<Integer>> roadLists = ContextCreator.route_UCB.get(OD);
		jsonObj.put("road_lists", roadLists);
		String line = JSONObject.toJSONString(jsonObj);
		return line;
	}
	
	// Formatting the bus candidate routes information as a message string
	public static String getUCBRouteStringForODBus(String OD) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("MSG_TYPE", "BOD_PAIR");
		jsonObj.put("BOD", OD);
		List<List<Integer>> roadLists = ContextCreator.route_UCB_bus.get(OD);
		jsonObj.put("road_lists", roadLists);
		String line = JSONObject.toJSONString(jsonObj);
		return line;
	}

	public static Set<String> getUCBRouteODPairs() {
		return ContextCreator.route_UCB.keySet();
	}

	public static Set<String> getUCBRouteODPairsBus() {
		return ContextCreator.route_UCB_bus.keySet();
	}

	public static boolean isRouteUCBMapPopulated() {
		return ContextCreator.isRouteUCBPopulated;
	}

	public static boolean isRouteUCBBusMapPopulated() {
		return ContextCreator.isRouteUCBPopulated;
	}
	
	// Schedule the start and the end of the simulation
	public void scheduleStartAndEnd() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		RunEnvironment.getInstance().endAt(GlobalVariables.SIMULATION_STOP_TIME);
		logger.info("stop time =  " + GlobalVariables.SIMULATION_STOP_TIME);
		// Schedule start of sim
		ScheduleParameters startParams = ScheduleParameters.createOneTime(1);
		schedule.schedule(startParams, this, "start");
		// Schedule end of sim
		ScheduleParameters endParams = ScheduleParameters.createAtEnd(ScheduleParameters.LAST_PRIORITY);
		schedule.schedule(endParams, this, "end");

	}
	
	// Schedule the event for refreshing network connectivity
	public void scheduleRoadNetworkRefresh() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters agentParamsNW = ScheduleParameters.createRepeating(0, duration02_, 3);
		schedule.schedule(agentParamsNW, cityContext, "modifyRoadNetwork");
	}
	
	// Schedule the event for updating link travel speed/time
	// Modified: also added the variance of the speed
	public void scheduleFreeFlowSpeedRefresh() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters speedProfileParams = ScheduleParameters.createRepeating(0, duration_, 4); 
		for (Road r : getRoadContext().getObjects(Road.class)) {
			schedule.schedule(speedProfileParams, r, "updateFreeFlowSpeed");
		}
		
		if(!GlobalVariables.BUS_PLANNING) {
			// if bus planning is enabled, update this when receiving new bus schedules to avoid confliction
		     schedule.schedule(speedProfileParams, cityContext, "refreshTravelTime"); // update the travel time estimation for taking Bus
		}
		
		for (Zone z : getZoneGeography().getAllObjects()) {
			schedule.schedule(speedProfileParams, z, "updateTravelEstimation"); // update the travel time estimation in each Zone
		    if(!GlobalVariables.BUS_PLANNING) {
		    	// if bus planning is enabled, update this when receiving new bus schedules to avoid confliction
		    	schedule.schedule(speedProfileParams, z, "updateCombinedTravelEstimation"); 
		    }
		}
		
	}

	
	// Schedule the event for link management or incidents, e.g., road closure
	public void scheduleNetworkEventHandling() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters supplySideEventParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.EVENT_CHECK_FREQUENCY, 1);
		schedule.schedule(supplySideEventParams, eventHandler, "checkEvents");
	}
	
	// Schedule the event for generating and serving passengers
	public void schedulePassengerArrivalAndServe() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		// Schedule the passenger serving events
		ScheduleParameters demandServeParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_PASSENGER_SERVE_INTERVAL, 1);
		for (Zone a : getZoneContext().getObjects(Zone.class)) {
			schedule.schedule(demandServeParams, a, "step");
		}
		// Schedule the passenger arrival events
		ScheduleParameters demandGenerationParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_PASSENGER_ARRIVAL_INTERVAL, 1);
		for (Zone a : getZoneContext().getObjects(Zone.class)) {
			schedule.schedule(demandGenerationParams, a, "generatePassenger");
		}
	}
	
	// Schedule the event for charging vehicles
	public void scheduleChargingStation() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters chargingServeParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL, 1);
		for (ChargingStation a : getChargingStationContext().getObjects(ChargingStation.class)) {
			schedule.schedule(chargingServeParams, a, "step");
		}

	}
	
	// Schedule the event for vehicle movements (multithread)
	public void scheduleMultiThreadedRoadStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ThreadedScheduler s = new ThreadedScheduler(GlobalVariables.N_Partition);
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(1, 1, 0);
		schedule.schedule(agentParaParams, s, "paraStep");
		// Schedule shutting down the parallel thread pool
		ScheduleParameters endParallelParams = ScheduleParameters.createAtEnd(1);
		schedule.schedule(endParallelParams, s, "shutdownScheduler");

		// Schedule the time counter
		ScheduleParameters timerParaParams = ScheduleParameters.createRepeating(1, duration03_, 0);
		schedule.schedule(timerParaParams, s, "reportTime");

		// Schedule Parameters for the graph partitioning
		ScheduleParameters partitionParams = ScheduleParameters.createRepeating(duration03_, duration03_, 2);
		ScheduleParameters initialPartitionParams = ScheduleParameters.createOneTime(0, 2);
		schedule.schedule(initialPartitionParams, partitioner, "first_run");
		schedule.schedule(partitionParams, partitioner, "check_run");
	}
	
	// Schedule the event for vehicle movements (single thread)
	public void scheuleSequentialRoadStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters agentParams = ScheduleParameters.createRepeating(1, 1, 0);
		for (Road r : getRoadContext().getObjects(Road.class)) {
			schedule.schedule(agentParams, r, "step");
		}
	}
	
	// Schedule the event for data collection
	public void scheduleDataCollection() {
		double tickDuration = 1.0d;

		if (GlobalVariables.ENABLE_DATA_COLLECTION) {
			ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
			ScheduleParameters dataStartParams = ScheduleParameters.createOneTime(0.0,
					ScheduleParameters.FIRST_PRIORITY);
			schedule.schedule(dataStartParams, dataContext, "startCollecting");

			ScheduleParameters dataEndParams = ScheduleParameters.createAtEnd(ScheduleParameters.LAST_PRIORITY);
			schedule.schedule(dataEndParams, dataContext, "stopCollecting");

			ScheduleParameters tickStartParams = ScheduleParameters.createRepeating(0.0d, tickDuration,
					ScheduleParameters.FIRST_PRIORITY);
			schedule.schedule(tickStartParams, dataContext, "startTick");

			ScheduleParameters tickEndParams = ScheduleParameters.createRepeating(0.0d, tickDuration,
					ScheduleParameters.LAST_PRIORITY);
			schedule.schedule(tickEndParams, dataContext, "stopTick");

			ScheduleParameters recordRuntimeParams = ScheduleParameters.createRepeating(0,
					GlobalVariables.METRICS_DISPLAY_INTERVAL, 6);
			schedule.schedule(recordRuntimeParams, dataContext, "displayMetrics");
		}
	}
	
	// The "main" function
	public Context<Object> build(Context<Object> context) {
		logger.info("Reading property files");
		readPropertyFile();
		ContextCreator.mainContext = context;
		handleSimulationSleep();
		logger.info("Building subcontexts");
		buildSubContexts();

		// Check if link length and geometry are consistent, fix the inconsistency if there is one.
		for (Lane lane : ContextCreator.getLaneGeography().getAllObjects()) {
			Coordinate[] coords = ContextCreator.getLaneGeography().getGeometry(lane).getCoordinates();
			double distance = 0;
			for (int i = 0; i < coords.length - 1; i++) {
				distance += getDistance(coords[i], coords[i + 1]);
			}
			lane.setLength(distance);
		}

		// Schedule all simulation functions
		scheduleStartAndEnd();
		scheduleRoadNetworkRefresh();
		scheduleFreeFlowSpeedRefresh();
		scheduleNetworkEventHandling();
		schedulePassengerArrivalAndServe();
		scheduleChargingStation();

		// Schedule parameters for both serial and parallel road updates
		if (GlobalVariables.MULTI_THREADING) {
			scheduleMultiThreadedRoadStep();

		} else {
			scheuleSequentialRoadStep();

		}

		// Set up data collection
		if (GlobalVariables.ENABLE_DATA_COLLECTION) {
			scheduleDataCollection();
		}

		agentID = 0;

		return context;
	}
	
	// Print current tick
	public void printTick() {
		logger.info("Tick: " + RunEnvironment.getInstance().getCurrentSchedule().getTickCount());
	}
	
	// Stop the simulation
	public static void stopSim(Exception ex, Class<?> clazz) {
		ISchedule sched = RunEnvironment.getInstance().getCurrentSchedule();
		sched.setFinishing(true);
		sched.executeEndActions();
		logger.info("ContextCreator has been told to stop by  " + clazz.getName() + ex);
	}
	
	// Call when finish
	public static void end() {
		logger.info("Finished sim: " + (System.currentTimeMillis() - startTime));
		try {
			ev_logger.flush();
			bus_logger.flush();
			link_logger.flush();
			network_logger.flush();
			zone_logger.flush();
			charger_logger.flush();
			ev_logger.close();
			bus_logger.close();
			link_logger.close();
			network_logger.close();
			zone_logger.close();
			charger_logger.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static void start() {
		startTime = System.currentTimeMillis();
	}

	public static int generateAgentID() {
		return ContextCreator.agentID++;
	}

	public static VehicleContext getVehicleContext() {
		return (VehicleContext) mainContext.findContext("VehicleContext");
	}

	@SuppressWarnings("unchecked")
	public static Geography<Vehicle> getVehicleGeography() {
		return (Geography<Vehicle>) ContextCreator.getVehicleContext().getProjection(Geography.class,
				"VehicleGeography");
	}

	public static JunctionContext getJunctionContext() {
		return (JunctionContext) mainContext.findContext("JunctionContext");
	}

	@SuppressWarnings("unchecked")
	public static Geography<Junction> getJunctionGeography() {
		return ContextCreator.getJunctionContext().getProjection(Geography.class, "JunctionGeography");
	}

	@SuppressWarnings("unchecked")
	public static Network<Junction> getRoadNetwork() {
		return ContextCreator.getJunctionContext().getProjection(Network.class, "RoadNetwork");
	}

	public static RoadContext getRoadContext() {
		return (RoadContext) mainContext.findContext("RoadContext");
	}

	@SuppressWarnings("unchecked")
	public static Geography<Road> getRoadGeography() {
		return (Geography<Road>) ContextCreator.getRoadContext().getProjection("RoadGeography");
	}

	public static LaneContext getLaneContext() {
		return (LaneContext) mainContext.findContext("LaneContext");
	}

	@SuppressWarnings("unchecked")
	public static Geography<Lane> getLaneGeography() {
		return (Geography<Lane>) ContextCreator.getLaneContext().getProjection("LaneGeography");
	}

	public static CityContext getCityContext() {
		return (CityContext) mainContext.findContext("CityContext");
	}

	public static ZoneContext getZoneContext() {
		return (ZoneContext) mainContext.findContext("ZoneContext");
	}

	@SuppressWarnings("unchecked")
	public static Geography<Zone> getZoneGeography() {
		return (Geography<Zone>) ContextCreator.getZoneContext().getProjection("ZoneGeography");
	}

	public static ChargingStationContext getChargingStationContext() {
		return (ChargingStationContext) mainContext.findContext("ChargingStationContext");
	}

	@SuppressWarnings("unchecked")
	public static Geography<ChargingStation> getChargingStationGeography() {
		return (Geography<ChargingStation>) ContextCreator.getChargingStationContext()
				.getProjection("ChargingStationGeography");
	}

	public static DataCollectionContext getDataCollectionContext() {
		return (DataCollectionContext) mainContext.findContext("DataCollectionContext");
	}

	public static double convertToMeters(double dist) {
		double distInMeters = NonSI.NAUTICAL_MILE.getConverterTo(SI.METER).convert(dist * 60);
		return distInMeters;
	}

	public static TreeMap<Integer, ArrayList<Double>> getBackgroundTraffic() {
		return backgroundTraffic.backgroundTraffic;
	}

	public static TreeMap<Integer, ArrayList<Double>> getBackgroundTrafficStd() {
		return backgroundTraffic.backgroundStd;
	}

	public static TreeMap<Integer, ArrayList<Double>> getTravelDemand() {
		return backgroundDemand.travelDemand;
	}

	public static ArrayList<ArrayList<Integer>> getBusSchedule() {
		return busSchedule.busRoute;
	}

	public static ArrayList<Integer> getBusNum() {
		return busSchedule.busNum;
	}

	public static ArrayList<Integer> getBusGap() {
		return busSchedule.busGap;
	}
	
	// Get distance between two coordinates
	private double getDistance(Coordinate c1, Coordinate c2) {
		GeodeticCalculator calculator = new GeodeticCalculator(ContextCreator.getLaneGeography().getCRS());
		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		double distance;
		try {
			distance = calculator.getOrthodromicDistance();
		} catch (AssertionError ex) {
			logger.error("Error with finding distance");
			distance = 0.0;
		}
		return distance;
	}

}