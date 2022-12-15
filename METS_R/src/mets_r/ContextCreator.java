package mets_r;

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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import javax.measure.unit.NonSI;
import javax.measure.unit.SI;

import repast.simphony.context.Context;
import repast.simphony.dataLoader.ContextBuilder;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.ScheduleParameters;
import repast.simphony.essentials.RepastEssentials;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.graph.Network;

import org.apache.log4j.Logger;
import org.json.simple.JSONObject;
import galois.partition.*;
import mets_r.GlobalVariables;
import mets_r.communication.Connection;
import mets_r.data.*;
import mets_r.facility.*;
import mets_r.mobility.*;
import mets_r.routing.RouteContext;

/**
 * This is the class with the main function which includes:
 * 1. Loading data
 * 2. Initializing different types of agents (We named them "context")
 * 3. Scheduling the start, pause and the stop of the simulation
 * 4. Data communication/logs
**/

public class ContextCreator implements ContextBuilder<Object> {
	private static Context<Object> mainContext; // Keep a reference to the main context
	
	/* Loggers */
	// Loggers for aggregated metrics
	public static BufferedWriter ev_logger; // Vehicle Trip logger
	public static BufferedWriter bus_logger; // Vehicle Trip logger
	public static BufferedWriter link_logger; // Link energy logger
	public static BufferedWriter network_logger; // Road network vehicle logger
	public static BufferedWriter zone_logger; // Zone logger
	public static BufferedWriter charger_logger; // Charger logger
	public static BufferedWriter traj_logger; // Trajectory logger
	// Logger for console outputs
	public static Logger logger = Logger.getLogger(ContextCreator.class);

	/* Simulation data */
	private static int agentID = 0; // Used to generate unique agent id
	public static double startTime; // Start time of the simulation
	public static HashMap<Integer, Double> demand_per_zone = new HashMap<Integer, Double>();

	/* Simulation objects */
	public static MetisPartition partitioner = new MetisPartition(GlobalVariables.N_Partition); 
	public static NetworkEventHandler eventHandler = new NetworkEventHandler(); 
	public static BackgroundTraffic backgroundTraffic = new BackgroundTraffic();
	public static BackgroundDemand backgroundDemand = new BackgroundDemand();
	public static BusSchedule busSchedule = new BusSchedule();
	CityContext cityContext;
	VehicleContext vehicleContext;
	DataCollectionContext dataContext;

	/* Data communication */
	public static Connection connection = null;
	// Candidate path sets for eco-routing, 
	// id: origin-destination pair, value: npaths
	public static HashMap<String, List<List<Integer>>> route_UCB = new HashMap<String, List<List<Integer>>>();
	public static HashMap<String, List<List<Integer>>> route_UCB_bus = new HashMap<String, List<List<Integer>>>();
	// Volatile (thread-read-safe) flags to check if the operational data is prepared 
	public static volatile boolean isRouteUCBPopulated = false;
	public static volatile boolean isRouteUCBBusPopulated = false;
	public static volatile boolean receiveNewBusSchedule = false;
	// Route results received from RemoteDataClient
	public static HashMap<String, Integer> routeResult_received = new HashMap<String, Integer>();
	public static HashMap<String, Integer> routeResult_received_bus = new HashMap<String, Integer>();

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

	public void waitForNewBusSchedule() {
		int num_tried = 0;
		while (!receiveNewBusSchedule) {
			try {
				Thread.sleep(1000);
				logger.info("Simulation pausing for waiting bus schedules");
				if (num_tried > 3 && connection != null) {
					try {
						int tick = (int) Math.round((0.0+ContextCreator.getCurrentTick())
								/ GlobalVariables.SIMULATION_BUS_REFRESH_INTERVAL);
						logger.info("Send request for the " + tick + "-th schedule.");
						tick = tick * GlobalVariables.SIMULATION_BUS_REFRESH_INTERVAL;
						if (tick >= GlobalVariables.SIMULATION_STOP_TIME)
							break; // The end of the simulation
						connection.sendTickSnapshot(new TickSnapshot(tick));
						num_tried = 0;
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				num_tried++;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		receiveNewBusSchedule = false;
	}

	// Initializing simulation agents
	public void buildSubContexts() {
		// Initialize facilities
		this.cityContext = new CityContext();
		mainContext.addSubContext(cityContext);
		this.cityContext.createSubContexts();
		this.cityContext.buildRoadNetwork();
		this.cityContext.setNeighboringGraph();

		// Calculate the demand of each zone
		double demand_total = 0;

		for (Zone z : getZoneContext().getAllObjects()) {
			double demand_from_zone = 0;
			int i = z.getIntegerID();
			for (int j = 0; j < GlobalVariables.NUM_OF_ZONE; j++) {
				demand_from_zone += sumOfArray(ContextCreator.getTravelDemand(i, j),
						GlobalVariables.HOUR_OF_DEMAND - 1);
			}
			demand_total += demand_from_zone;
			demand_per_zone.put(z.getIntegerID(), demand_from_zone);
		}
		ContextCreator.logger
				.info("Vehicle Generation: total demand " + demand_total * GlobalVariables.PASSENGER_DEMAND_FACTOR);

		// Initialize vehicles
		this.vehicleContext = new VehicleContext();
		mainContext.addSubContext(vehicleContext);

		// Create data collector
		this.dataContext = new DataCollectionContext();
		mainContext.addSubContext(dataContext);

		// Initialize operational parameters 
		// Set the bus info in each zone
		for (ArrayList<Integer> route : busSchedule.busRoute) {
			int i = 0;
			for (int zoneID : route) {
				Zone zone = ContextCreator.getCityContext().findZoneWithIntegerID(zoneID);
				if (zone.getZoneClass() == 0) { // normal zone, the destination should be hub
					for (int destinationID : route) {
						if (GlobalVariables.HUB_INDEXES.contains(destinationID)) {
							zone.setBusInfo(destinationID, busSchedule.busGap.get(i));
						}
					}
				} else if (zone.getZoneClass() == 1) { // hub, the destination should be other zones (can be another
														// hub)
					for (int destinationID : route) {
						if (zone.getIntegerID() != destinationID) {
							zone.setBusInfo(destinationID, busSchedule.busGap.get(i));
						}

					}
				}
				i += 1;
			}
		}
		
		cityContext.modifyRoadNetwork(); // This initializes data for path calculation, DO NOT remove it
		if(GlobalVariables.ENABLE_ECO_ROUTING_EV) {
			createUCBRoutes(); // generate eco-routing routes
		}
		else {
			ContextCreator.isRouteUCBPopulated = true;
		}
		if(GlobalVariables.ENABLE_ECO_ROUTING_BUS) {
			createUCBBusRoutes(); // generate Bus eco-routing routes
		}
		else {
			ContextCreator.isRouteUCBBusPopulated = true;
		}
		
		
		
		// Create output files
		String outDir = GlobalVariables.AGG_DEFAULT_PATH;
		String timestamp = new SimpleDateFormat("YYYY-MM-dd-hh-mm-ss").format(new Date()); 
		String outpath = outDir + File.separatorChar + GlobalVariables.NUM_OF_EV + "_" + GlobalVariables.NUM_OF_BUS; 
		try {
			Files.createDirectories(Paths.get(outpath));
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		logger.info("Creating aggregate loggers...");
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
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "LinkLog-" + timestamp + ".csv", false);
			link_logger = new BufferedWriter(fw);
			link_logger.write("tick,linkID,flow,consumption");
			link_logger.newLine();
			link_logger.flush();
			logger.info("Link energy logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Link energy logger failed.");
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "NetworkLog-" + timestamp + ".csv", false);
			network_logger = new BufferedWriter(fw);
			network_logger.write(
					"tick,vehOnRoad,emptyTrip,chargingTrip,generatedTaxiPass,generatedBusPass,generatedCombinedPass,"
							+ "taxiPickupPass,busPickupPass,combinePickupPart1,combinePickupPart2,"
							+ "taxiServedPass,busServedPass," + "taxiLeavedPass,busLeavedPass,"
							+ "numWaitingTaxiPass,numWaitingBusPass," + "batteryMean,batteryStd,timeStamp");
			network_logger.newLine();
			network_logger.flush();
			logger.info("Network logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Network logger failed.");
		}
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "ZoneLog-" + timestamp + ".csv", false);
			zone_logger = new BufferedWriter(fw);
			zone_logger.write(
					"tick,zoneID,numTaxiPass,numBusPass,vehStock,taxiGeneratedPass,busGeneratedPass,generatedCombinedPass,"
							+ "taxiPickupPass,busPickupPass,combinePickupPart1,combinePickupPart2,"
							+ "taxiServedPass,busServedPass,taxiServedPassWaitingTime,busServedPassWaitingTime,"
							+ "taxiLeavedPass,busLeavedPass,taxiLeavedPassWaitingTime,busLeavedPassWaitingTime,"
							+ "taxiParkingTime,taxiCruisingTime,futureDemand,futureSupply");
			zone_logger.newLine();
			zone_logger.flush();
			logger.info("Zone logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Zone logger failed.");
		}
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
		try {
			FileWriter fw = new FileWriter(outpath + File.separatorChar + "TrajLog-" + timestamp + ".csv", false);
			traj_logger = new BufferedWriter(fw);
			traj_logger
					.write("tick,vehicleID,vehicleState,linkID,distToJunction,speed,acc,battery_level,tick_consume,passNum");
			traj_logger.newLine();
			traj_logger.flush();
			logger.info("Trajectory logger created!");
		} catch (IOException e) {
			e.printStackTrace();
			logger.error("Trajectory logger failed.");
		}
	}

	// Create eco-routing candidate path set
	@SuppressWarnings("unchecked")
	private void createUCBRoutes() {
		cityContext.modifyRoadNetwork(); // This initializes data for path calculation, DO NOT remove it
		try { // First try to read from the file. If the file does not exist, then create it.
			FileInputStream fileIn = new FileInputStream("data/NYC/candidate_routes.ser");
			ObjectInputStream in = new ObjectInputStream(fileIn);
			ContextCreator.route_UCB = (HashMap<String, List<List<Integer>>>) in.readObject();
			logger.info("Serialized data is loaded from data/candidate_routes.ser");
			in.close();
			fileIn.close();
		} catch (FileNotFoundException i) {
			logger.info("Candidate routes initialization ...");
			// Loop over all OD pairs, will take several hours
			for (Zone origin : getZoneContext().getAllObjects()) {
				for (Zone destination : getZoneContext().getAllObjects()) {
					if (origin.getIntegerID() != destination.getIntegerID()
							&& (GlobalVariables.HUB_INDEXES.contains(origin.getIntegerID())
									|| GlobalVariables.HUB_INDEXES.contains(destination.getIntegerID()))) {
						logger.info("Creating routes: " + origin.getIntegerID() + "," + destination.getIntegerID());
						try {
							List<List<Integer>> candidate_routes = RouteContext.UCBRoute(origin.getCoord(),
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
			try {
				FileOutputStream fileOut = new FileOutputStream("data/NYC/candidate_routes.ser");
				ObjectOutputStream out = new ObjectOutputStream(fileOut);
				out.writeObject(ContextCreator.route_UCB);
				out.close();
				fileOut.close();
				logger.info("Serialized data is saved in data/candidate_routes.ser");
			} catch (IOException o) {
				o.printStackTrace();
				return;
			}
		} catch (ClassNotFoundException c) {
			c.printStackTrace();
			return;
		} catch (IOException o) {
			o.printStackTrace();
			return;
		}
		ContextCreator.isRouteUCBPopulated = true;
	}

	// Create Bus eco-routing candidate path set
	@SuppressWarnings("unchecked")
	private void createUCBBusRoutes() {
		try { // First try to read from the file. If the file does not exist, then create it.
			FileInputStream fileIn = new FileInputStream("data/NYC/candidate_routes_bus.ser");
			ObjectInputStream in = new ObjectInputStream(fileIn);
			ContextCreator.route_UCB_bus = (HashMap<String, List<List<Integer>>>) in.readObject();
			logger.info("Serialized data is loaded from data/candidate_routes_bus.ser");
			in.close();
			fileIn.close();
		} catch (FileNotFoundException i) { 
			logger.info("Candidate routes initialization ...");
			for (Zone origin : getZoneContext().getAllObjects()) {
				for (Zone destination : getZoneContext().getAllObjects()) {
					if (origin.getIntegerID() != destination.getIntegerID()) {
						logger.info("Creating routes: " + origin.getIntegerID() + "," + destination.getIntegerID());
						try {
							ContextCreator.route_UCB_bus.put(
									Integer.toString(origin.getIntegerID()) + "," + destination.getIntegerID(),
									RouteContext.UCBRoute(origin.getCoord(), destination.getCoord()));
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			}
			try {
				FileOutputStream fileOut = new FileOutputStream("data/NYC/candidate_routes_bus.ser");
				ObjectOutputStream out = new ObjectOutputStream(fileOut);
				out.writeObject(ContextCreator.route_UCB_bus);
				out.close();
				fileOut.close();
				logger.info("Serialized data is saved in data/candidate_routes_bus.ser");
			} catch (IOException o) {
				o.printStackTrace();
				return;
			}
		} catch (ClassNotFoundException c) {
			c.printStackTrace();
			return;
		} catch (IOException o) {
			o.printStackTrace();
			return;
		}
		ContextCreator.isRouteUCBBusPopulated = true;
	}

	// Schedule the start and the end of the simulation
	public void scheduleStartAndEnd() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		RunEnvironment.getInstance().endAt(GlobalVariables.SIMULATION_STOP_TIME);
		logger.info("stop time =  " + GlobalVariables.SIMULATION_STOP_TIME);
		ScheduleParameters startParams = ScheduleParameters.createOneTime(ScheduleParameters.LAST_PRIORITY);
		schedule.schedule(startParams, this, "start");
		ScheduleParameters endParams = ScheduleParameters.createAtEnd(ScheduleParameters.LAST_PRIORITY);
		schedule.schedule(endParams, this, "end");

	}

	// Schedule the event of refreshing road information for routing
	public void scheduleRoadNetworkRefresh() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters agentParamsNW = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL, 3);
		schedule.schedule(agentParamsNW, cityContext, "modifyRoadNetwork");

	}

	// Schedule the event of updating background speeds/estimated travel time
	// For each link (per update), background speed serves as the target speed of vehicles, 
	// which follows a normal distribution.
	public void scheduleFreeFlowSpeedRefresh() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters speedProfileParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL, 4);
		for (Road r : getRoadContext().getObjects(Road.class)) {
			schedule.schedule(speedProfileParams, r, "updateFreeFlowSpeed");
		}
		schedule.schedule(speedProfileParams, cityContext, "refreshTravelTime");
		if (GlobalVariables.BUS_PLANNING) { // wait for new bus schedules if dynamic bus planning is enabled
			ScheduleParameters busScheduleParams = ScheduleParameters.createRepeating(0,
					GlobalVariables.SIMULATION_BUS_REFRESH_INTERVAL, 5);
			schedule.schedule(busScheduleParams, this, "waitForNewBusSchedule");
		}
	}

	// Schedule the event for link management or incidents, e.g., road closure
	public void scheduleNetworkEventHandling() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters supplySideEventParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.EVENT_CHECK_FREQUENCY, 1);
		schedule.schedule(supplySideEventParams, eventHandler, "checkEvents");
	}

	// Schedule the event for vehicle movements (multi-thread)
	public void scheduleMultiThreadedRoadStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ThreadedScheduler s = new ThreadedScheduler(GlobalVariables.N_Partition);
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(1, 1, 0);
		schedule.schedule(agentParaParams, s, "paraRoadStep");
		
		// Schedule shutting down the parallel thread pool
		ScheduleParameters endParallelParams = ScheduleParameters.createAtEnd(1);
		schedule.schedule(endParallelParams, s, "shutdownScheduler");

		// Schedule time counter
		ScheduleParameters timerParaParams = ScheduleParameters.createRepeating(1,
				GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL, 0);
		schedule.schedule(timerParaParams, s, "reportTime");

		// Schedule graph partitioning
		ScheduleParameters partitionParams = ScheduleParameters.createRepeating(
				GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL,
				GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL, 2);
		ScheduleParameters initialPartitionParams = ScheduleParameters.createOneTime(0, 2);
		schedule.schedule(initialPartitionParams, partitioner, "first_run");
		schedule.schedule(partitionParams, partitioner, "check_run");
	}

	// Schedule the event for vehicle movements (single-thread)
	public void scheduleSequentialRoadStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters agentParams = ScheduleParameters.createRepeating(1, 1, 0);
		for (Road r : getRoadContext().getObjects(Road.class)) {
			schedule.schedule(agentParams, r, "step");
		}
	}

	// Schedule the event for zone updates (multi-thread)
	public void scheduleMultiThreadedZoneStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ThreadedScheduler s = new ThreadedScheduler(GlobalVariables.N_Partition);
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL, 1);
		schedule.schedule(agentParaParams, s, "paraZoneStep");
		
		// Schedule shutting down the parallel thread pool
		ScheduleParameters endParallelParams = ScheduleParameters.createAtEnd(1);
		schedule.schedule(endParallelParams, s, "shutdownScheduler");

	}

	// Schedule the event for zone updates (single-thread)
	public void scheduleSequentialZoneStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		
		// Schedule the passenger serving events
		ScheduleParameters demandServeParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL, 1);
		for (Zone z : getZoneContext().getObjects(Zone.class)) {
			schedule.schedule(demandServeParams, z, "step");
		}
	}

	// Schedule the event for charging station updates (multi-thread)
	public void scheduleMultiThreadedChargingStationStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ThreadedScheduler s = new ThreadedScheduler(GlobalVariables.N_Partition);
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL, 1);
		schedule.schedule(agentParaParams, s, "paraChargingStationStep");
		
		// Schedule shutting down the parallel thread pool
		ScheduleParameters endParallelParams = ScheduleParameters.createAtEnd(1);
		schedule.schedule(endParallelParams, s, "shutdownScheduler");
	}

	// Schedule the event for  charging station updates (single-thread)
	public void scheduleSequentialChargingStationStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters chargingServeParams = ScheduleParameters.createRepeating(0,
				GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL, 1);
		for (ChargingStation cs : getChargingStationContext().getObjects(ChargingStation.class)) {
			schedule.schedule(chargingServeParams, cs, "step");
		}
	}

	// Schedule the event for data collection
	public void scheduleDataCollection() {
		int tickDuration = 1;

		if (GlobalVariables.ENABLE_DATA_COLLECTION) {
			ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
			ScheduleParameters dataStartParams = ScheduleParameters.createOneTime(0,
					ScheduleParameters.FIRST_PRIORITY);
			schedule.schedule(dataStartParams, dataContext, "startCollecting");

			ScheduleParameters dataEndParams = ScheduleParameters.createAtEnd(ScheduleParameters.LAST_PRIORITY);
			schedule.schedule(dataEndParams, dataContext, "stopCollecting");

			ScheduleParameters tickStartParams = ScheduleParameters.createRepeating(0, tickDuration,
					ScheduleParameters.FIRST_PRIORITY);
			schedule.schedule(tickStartParams, dataContext, "startTick");

			ScheduleParameters tickEndParams = ScheduleParameters.createRepeating(0, tickDuration,
					ScheduleParameters.LAST_PRIORITY);
			schedule.schedule(tickEndParams, dataContext, "stopTick");

			ScheduleParameters recordRuntimeParams = ScheduleParameters.createRepeating(0,
					GlobalVariables.METRICS_DISPLAY_INTERVAL, 6);
			schedule.schedule(recordRuntimeParams, dataContext, "displayMetrics");
		}
	}

	// The main function
	public Context<Object> build(Context<Object> context) {
		logger.info("Reading property files");
		readPropertyFile();
		ContextCreator.mainContext = context;
		logger.info("Building subcontexts");
		buildSubContexts();

		// Schedule start and end
		logger.info("Scheduling events");
		scheduleStartAndEnd();
		scheduleRoadNetworkRefresh();
		scheduleFreeFlowSpeedRefresh();
		scheduleNetworkEventHandling(); // For temporarily alter the link speed
		
		// Set up data collection
		if (GlobalVariables.ENABLE_DATA_COLLECTION) {
			scheduleDataCollection();
		}

		// Schedule agent movements
		if (GlobalVariables.MULTI_THREADING) {
			scheduleMultiThreadedRoadStep();
			scheduleMultiThreadedZoneStep();
			scheduleMultiThreadedChargingStationStep();
		} else {
			scheduleSequentialRoadStep();
			scheduleSequentialZoneStep();
			scheduleSequentialChargingStationStep();
		}
		
		logger.info("Events scheduled!");

		agentID = 0;

		return context;
	}

	public void printTick() {
		logger.info("Tick: " + RunEnvironment.getInstance().getCurrentSchedule().getTickCount());
	}

	public static void stopSim(Exception ex, Class<?> clazz) {
		ISchedule sched = RunEnvironment.getInstance().getCurrentSchedule();
		sched.setFinishing(true);
		sched.executeEndActions();
		logger.info("ContextCreator has been told to stop by  " + clazz.getName() + ex);
	}
	
	public static void start() {
		startTime = System.currentTimeMillis();
	}

	// Called by sched.executeEndActions()
	public static void end() {
		logger.info("Finished sim: " + (System.currentTimeMillis() - startTime));
		try {
			ev_logger.flush();
			bus_logger.flush();
			link_logger.flush();
			network_logger.flush();
			zone_logger.flush();
			charger_logger.flush();
			traj_logger.flush();
			ev_logger.close();
			bus_logger.close();
			link_logger.close();
			network_logger.close();
			zone_logger.close();
			charger_logger.close();
			traj_logger.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static int generateAgentID() {
		return ContextCreator.agentID++;
	}

	public static double convertToMeters(double dist) {
		double distInMeters = NonSI.NAUTICAL_MILE.getConverterTo(SI.METER).convert(dist * 60);
		return distInMeters;
	}

	public static double sumOfArray(ArrayList<Double> arrayList, int n) {
		if (n == 0)
			return arrayList.get(n);
		else
			return arrayList.get(n) + sumOfArray(arrayList, n - 1);
	}
	
	// Format the candidate routes information as a message string
	public static String getUCBRouteStringForOD(String OD) {
		HashMap<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("MSG_TYPE", "OD_PAIR");
		jsonObj.put("OD", OD);
		List<List<Integer>> roadLists = ContextCreator.route_UCB.get(OD);
		jsonObj.put("road_lists", roadLists);
		String line = JSONObject.toJSONString(jsonObj);
		return line;
	}

	// Format the bus candidate routes information as a message string
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
	
	public static TreeMap<Integer, ArrayList<Double>> getBackgroundTraffic() {
		return backgroundTraffic.backgroundTraffic;
	}

	public static TreeMap<Integer, ArrayList<Double>> getBackgroundTrafficStd() {
		return backgroundTraffic.backgroundStd;
	}

	public static ArrayList<Double> getTravelDemand(int originID, int destID) {
		if (backgroundDemand.travelDemand.containsKey(originID)) {
			if (backgroundDemand.travelDemand.get(originID).containsKey(destID)) {
				return backgroundDemand.travelDemand.get(originID).get(destID);
			}
		}
		return new ArrayList<Double>(Collections.nCopies(GlobalVariables.HOUR_OF_DEMAND, 0.0d));
	}

	public static Double getTravelDemand(int originID, int destID, int hour) {
		if (backgroundDemand.travelDemand.containsKey(originID)) {
			if (backgroundDemand.travelDemand.get(originID).containsKey(destID)) {
				if (hour < GlobalVariables.HOUR_OF_DEMAND) {
					return backgroundDemand.travelDemand.get(originID).get(destID).get(hour);
				}

			}
		}
		return 0d;
	}
	
	public static Double getSharableRate(int originID, int destID, int hour) {
		if (backgroundDemand.sharePercentage.containsKey(originID)) {
			if (backgroundDemand.sharePercentage.get(originID).containsKey(destID)) {
				if (hour < GlobalVariables.HOUR_OF_DEMAND) {
					return backgroundDemand.sharePercentage.get(originID).get(destID).get(hour);
				}

			}
		}
		return 0d;
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
	
	public static int getCurrentTick() {
		return (int) RepastEssentials.GetTickCount();
	}
	
	public static int getNextTick() {
		return (int) RepastEssentials.GetTickCount() + 1;
	}
}