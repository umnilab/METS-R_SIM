package mets_r;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javax.measure.unit.NonSI;
import javax.measure.unit.SI;

import repast.simphony.context.Context;
import repast.simphony.dataLoader.ContextBuilder;
import repast.simphony.engine.environment.RunEnvironment;
import repast.simphony.engine.schedule.ISchedulableAction;
import repast.simphony.engine.schedule.ISchedule;
import repast.simphony.engine.schedule.ScheduleParameters;
import repast.simphony.essentials.RepastEssentials;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.graph.Network;

import org.apache.log4j.Logger;
import galois.partition.*;
import mets_r.GlobalVariables;
import mets_r.communication.Connection;
import mets_r.communication.ConnectionManager;
import mets_r.communication.ControlMessageHandler;
import mets_r.communication.KafkaDataStreamProducer;
import mets_r.communication.StepMessageHandler;
import mets_r.data.input.BackgroundTraffic;
import mets_r.data.input.BusSchedule;
import mets_r.data.input.NetworkEventHandler;
import mets_r.data.input.TravelDemand;
import mets_r.data.output.*;
import mets_r.facility.*;
import mets_r.mobility.*;

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
	public static AggregatedLogger agg_logger = new AggregatedLogger();
	// Logger for console outputs
	public static Logger logger = Logger.getLogger(ContextCreator.class);

	/* Simulation data */
	private static int agentID = 0; // Used to generate unique agent id
	public static double start_time; // Start time of the simulation
	public static BackgroundTraffic background_traffic = new BackgroundTraffic();
	public static TravelDemand travel_demand = new TravelDemand();
	public static BusSchedule bus_schedule = new BusSchedule();
	public static MetisPartition partitioner = new MetisPartition(GlobalVariables.N_Partition); 
	
	/* Multi-thread scheduler */
	public static ThreadedScheduler tscheduler = GlobalVariables.MULTI_THREADING?new ThreadedScheduler(GlobalVariables.N_Partition):null;
	
	/* Simulation objects */
	public static CityContext cityContext;
	public static VehicleContext vehicleContext;
	public static DataCollectionContext dataContext;

	/* Data communication */
	// Connection manager maintains the socket server for remote programs
	// set to be final to avoid further modifications
	public static final ConnectionManager manager =  GlobalVariables.STANDALONE?null: new ConnectionManager();
	public static Connection connection = null;
	public static NetworkEventHandler eventHandler = new NetworkEventHandler(); 
	public static final StepMessageHandler stepHandler = new StepMessageHandler();
	public static final ControlMessageHandler controlHandler = new ControlMessageHandler();
	// Kafka manager maintains the resources for sending message to Kafka
	public static final KafkaDataStreamProducer kafkaManager = GlobalVariables.STANDALONE?null:new KafkaDataStreamProducer(); 
	// Data collector gather tick by tick tickSnapshot and provide it to data consumers
	public static final DataCollector dataCollector = new DataCollector();
	
	// Road collections for co-simulation
	public static HashMap<String, Road> coSimRoads = new HashMap<String, Road>();
	
	/* Synchronize mode flags */
	// Volatile for thread-read-safe 
	public static volatile int waitNextStepCommand = GlobalVariables.SYNCHRONIZED?0:-1;
	
	/* For enable the reset function*/
	public static int initTick = 0;
	private static List<ISchedulableAction> scheduledActions = new ArrayList<ISchedulableAction>();
	
	/* Functions */
	// Initializing simulation agents
	public static void buildSubContexts() {
		// Initialize facilities
		cityContext = new CityContext();
		mainContext.addSubContext(cityContext);
		
		cityContext.createSubContexts();
		cityContext.buildRoadNetwork();
		cityContext.setNeighboringGraph();
		
		bus_schedule.postProcessing();
		
		// Initialize vehicles
		vehicleContext = new VehicleContext();
		mainContext.addSubContext(vehicleContext);

		// Create data collector
		dataContext = new DataCollectionContext();
		mainContext.addSubContext(dataContext);

		// Initialize operational parameters 
		cityContext.modifyRoadNetwork(); // This initializes data for path calculation, DO NOT remove it
		
		if(GlobalVariables.MULTI_THREADING) {
			try {
				partitioner.first_run();
				ContextCreator.logger.info("Reset partitioner");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		dataContext.startCollecting();
	}
	
	// Schedule simulation events
	public static void scheduleEvents() {
		schedulePrivateTripLoader();
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
			scheduleMultiThreadedSignalStep();
			scheduleMultiThreadedChargingStationStep();
		} else {
			scheduleSequentialRoadStep();
			scheduleSequentialZoneStep();
			scheduleSequentialSignalStep();
			scheduleSequentialChargingStationStep();
		}
		
		logger.info("Events scheduled!");
	}

	// Schedule the start and the end of the simulation
	public void scheduleEnd() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
//		RunEnvironment.getInstance().endAt(GlobalVariables.SIMULATION_STOP_TIME);
		logger.info("stop time =  " + GlobalVariables.SIMULATION_STOP_TIME);
		ScheduleParameters endParams = ScheduleParameters.createOneTime(GlobalVariables.SIMULATION_STOP_TIME, ScheduleParameters.LAST_PRIORITY);
		schedule.schedule(endParams, this, "end");
	}
	
	// Schedule the event of loading the demand chunk
	public static void schedulePrivateTripLoader() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters privateTripLoaderParams = ScheduleParameters.createRepeating(initTick, 
				(int) 3600/GlobalVariables.SIMULATION_STEP_SIZE, 2);
		scheduledActions.add(schedule.schedule(privateTripLoaderParams, travel_demand, "loadPrivateDemandChunk"));
	}
	
	// Schedule the event of refreshing road information for routing
	public static void scheduleRoadNetworkRefresh() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters agentParamsNW = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_NETWORK_REFRESH_INTERVAL, 3);
		scheduledActions.add(schedule.schedule(agentParamsNW, cityContext, "modifyRoadNetwork"));
	}

	// Schedule the event of updating background speeds/estimated travel time
	// For each link (per update), background speed serves as the target speed of vehicles, 
	// which follows a normal distribution.
	public static void scheduleFreeFlowSpeedRefresh() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters speedProfileParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL, 4);
		for (Road r : getRoadContext().getObjects(Road.class)) {
			schedule.schedule(speedProfileParams, r, "updateFreeFlowSpeed");
		}
	}

	// Schedule the event for link management, transit scheduling, or incidents, e.g., road closure
	public static void scheduleNetworkEventHandling() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters supplySideEventParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.EVENT_CHECK_FREQUENCY, 1);
		scheduledActions.add(schedule.schedule(supplySideEventParams, eventHandler, "checkEvents"));
	}

	// Schedule the event for synchronized update
	public void scheduleNextStepUpdating() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters nextStepParams = ScheduleParameters.createRepeating(initTick, 1, ScheduleParameters.LAST_PRIORITY);
		schedule.schedule(nextStepParams, this, "waitForNextStepCommand");
	}
	
	// Schedule the event for vehicle movements (multi-thread)
	public static void scheduleMultiThreadedRoadStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(initTick + 1, 1, 0);
		scheduledActions.add(schedule.schedule(agentParaParams, tscheduler, "paraRoadStep"));

		// Schedule time counter
		ScheduleParameters timerParaParams = ScheduleParameters.createRepeating(initTick + 1,
				GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL, 0);
		scheduledActions.add(schedule.schedule(timerParaParams, tscheduler, "reportTime"));

		// Schedule graph partitioning
		ScheduleParameters partitionParams = ScheduleParameters.createRepeating(
				initTick + GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL,
				GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL, 2);
		
		scheduledActions.add(schedule.schedule(partitionParams, partitioner, "check_run"));
	}

	// Schedule the event for vehicle movements (single-thread)
	public static void scheduleSequentialRoadStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters agentParams = ScheduleParameters.createRepeating(initTick + 1, 1, 0);
		for (Road r : getRoadContext().getAll()) {
			scheduledActions.add(schedule.schedule(agentParams, r, "step"));
		}
	}

	// Schedule the event for zone updates (multi-thread)
	public static void scheduleMultiThreadedZoneStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL, 1);
		scheduledActions.add(schedule.schedule(agentParaParams, tscheduler, "paraZoneStep"));
		
		ScheduleParameters agentParaParams2 = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_RH_MATCHING_WINDOW, 2);
		scheduledActions.add(schedule.schedule(agentParaParams2, tscheduler, "paraZoneRidehailingStep"));
	}

	// Schedule the event for zone updates (single-thread)
	public static void scheduleSequentialZoneStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		
		// Schedule the passenger serving events
		ScheduleParameters demandServeParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL, 1);
		
		ScheduleParameters demandServeParams2 = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_RH_MATCHING_WINDOW, 2);
		
		for (Zone z : getZoneContext().getAll()) {
			scheduledActions.add(schedule.schedule(demandServeParams, z, "step"));
		}
		for (Zone z : getZoneContext().getAll()) {
			scheduledActions.add(schedule.schedule(demandServeParams2, z, "ridehailingStep"));
		}
	}

	// Schedule the event for charging station updates (multi-thread)
	public static void scheduleMultiThreadedChargingStationStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL, 1);
		scheduledActions.add(schedule.schedule(agentParaParams, tscheduler, "paraChargingStationStep"));
	}

	// Schedule the event for  charging station updates (single-thread)
	public static void scheduleSequentialChargingStationStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters chargingServeParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL, 1);
		for (ChargingStation cs : getChargingStationContext().getAll()) {
			scheduledActions.add(schedule.schedule(chargingServeParams, cs, "step"));
		}
	}
	
	// Schedule the event for signal updates (multi-thread)
	public static void scheduleMultiThreadedSignalStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(initTick, GlobalVariables.SIMULATION_SIGNAL_REFRESH_INTERVAL, 1); 
		scheduledActions.add(schedule.schedule(agentParaParams, tscheduler, "paraSignalStep"));
	}

	// Schedule the event for charging station updates (single-thread)
	public static void scheduleSequentialSignalStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		for (Signal s : getSignalContext().getAll()) {
			ScheduleParameters signalUpdateParams = ScheduleParameters.createRepeating(initTick, GlobalVariables.SIMULATION_SIGNAL_REFRESH_INTERVAL, 1);
			scheduledActions.add(schedule.schedule(signalUpdateParams, s, "step"));
		}
	}

	// Schedule the event for data collection
	public static void scheduleDataCollection() {
		int tickDuration = 1;
		if (GlobalVariables.ENABLE_DATA_COLLECTION) {
			ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
			ScheduleParameters tickStartParams = ScheduleParameters.createRepeating(initTick, tickDuration,
					ScheduleParameters.FIRST_PRIORITY);
			scheduledActions.add(schedule.schedule(tickStartParams, dataContext, "startTick"));

			ScheduleParameters tickEndParams = ScheduleParameters.createRepeating(initTick, tickDuration,
					-1);
			scheduledActions.add(schedule.schedule(tickEndParams, dataContext, "stopTick"));

			ScheduleParameters recordRuntimeParams = ScheduleParameters.createRepeating(initTick,
					GlobalVariables.METRICS_DISPLAY_INTERVAL, 6);
			scheduledActions.add(schedule.schedule(recordRuntimeParams, dataContext, "displayMetrics"));
		}
	}

	// The main function
	public Context<Object> build(Context<Object> context) {
		start_time = System.currentTimeMillis(); // Record the start time of the simulation
		
		mainContext = context;
		
		logger.info("Building subcontexts");
		buildSubContexts();

		logger.info("Scheduling events");
		scheduleEvents();

		agentID = 0;
		
		// Send a ready signal (tick 0) in the synchronized mode
		if(GlobalVariables.SYNCHRONIZED) {
			// Wait for the connection to be established, and all the pre-required data has been submitted
			while((connection == null)) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
			connection.sendReadyMessage(); 
			
			scheduleNextStepUpdating(); // Schedule synchronized updates
		}
		else {
			scheduleEnd();
		}
		
		return context;
	}
	
	// The reset function
	public static void reset() {
		logger.info("Restart the simulation!");
		
		// 0. Clear scheduled actions and variables
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		for(ISchedulableAction scheduledAction : scheduledActions) {
			schedule.removeAction(scheduledAction);
		}
		scheduledActions = new ArrayList<ISchedulableAction>();
		dataContext.stopCollecting();
		agg_logger.close();
		travel_demand.close();
		
		mainContext.removeSubContext(cityContext);
		mainContext.removeSubContext(dataContext);
		mainContext.removeSubContext(vehicleContext);
		
		// Reload variables 
		initTick = (int) Math.max(RepastEssentials.GetTickCount(), 0);
		
		agentID = 0;
		agg_logger = new AggregatedLogger();
		background_traffic = new BackgroundTraffic();
		travel_demand = new TravelDemand();
		bus_schedule = new BusSchedule();
		partitioner = new MetisPartition(GlobalVariables.N_Partition); 
		waitNextStepCommand = 0;
		
		// Regenerate the sub-contexts
		buildSubContexts();
		
		// Clear and reinitialize the scheduled actions
		scheduleEvents();
	}
	
	// The save function
	public static void save() {
		
	}
	
	// The load function
	public static void load() {
		
	}
	
	
	// Called by sched.executeEndActions()
	public static void end() {
		logger.info("Finished sim: " + (System.currentTimeMillis() - start_time));
		tscheduler.shutdownScheduler();
		dataContext.stopCollecting();
		agg_logger.close();
		travel_demand.close();
		// Close the user interface
		System.exit(0);
	}
	
	public void waitForNextStepCommand() {
		long prevTime = -10001; // for the first tick
		while(waitNextStepCommand == 0) {
			try{
				Thread.sleep(1);
				if ((System.currentTimeMillis()-prevTime)>10000 && connection != null) {
					connection.sendStepMessage(ContextCreator.getCurrentTick());
					prevTime = System.currentTimeMillis();
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		waitNextStepCommand -= 1;
	}
	
	public static int generateAgentID() {
		return ContextCreator.agentID++;
	}

	public static double convertToMeters(double dist) {
		double distInMeters = NonSI.NAUTICAL_MILE.getConverterTo(SI.METER).convert(dist * 60);
		return distInMeters;
	}

	public static double sumOfArray(ArrayList<Double> arrayList, int n) {
		double res = 0d;
		for(int i = 0; i <= n; i++) {
			res += arrayList.get(i);
		}
		return res;
	}
	
	public static VehicleContext getVehicleContext() {
		return (VehicleContext) mainContext.findContext("VehicleContext");
	}
	
	
	public static CityContext getCityContext() {
		return (CityContext) mainContext.findContext("CityContext");
	}

	public static ZoneContext getZoneContext() {
		return (ZoneContext) mainContext.findContext("ZoneContext");
	}
	
	public static ChargingStationContext getChargingStationContext() {
		return (ChargingStationContext) mainContext.findContext("ChargingStationContext");
	}

	
	public static RoadContext getRoadContext() {
		return (RoadContext) mainContext.findContext("RoadContext");
	}
	
	public static LaneContext getLaneContext() {
		return (LaneContext) mainContext.findContext("LaneContext");
	}
	
	public static JunctionContext getJunctionContext() {
		return (JunctionContext) mainContext.findContext("JunctionContext");
	}
	
	public static NodeContext getNodeContext() {
		return (NodeContext) mainContext.findContext("NodeContext");
	}
	
	public static SignalContext getSignalContext() {
		return (SignalContext) mainContext.findContext("SignalContext");
	}
	
	@SuppressWarnings("unchecked")
	public static Geography<Vehicle> getVehicleGeography() {
		return (Geography<Vehicle>) ContextCreator.getVehicleContext().getProjection(Geography.class,
				"VehicleGeography");
	}

	@SuppressWarnings("unchecked")
	public static Network<Node> getRoadNetwork() {
		return ContextCreator.getCityContext().getProjection(Network.class, "RoadNetwork");
	}

	@SuppressWarnings("unchecked")
	public static Geography<Zone> getZoneGeography() {
		return (Geography<Zone>) ContextCreator.getZoneContext().getProjection("ZoneGeography");
	}

	@SuppressWarnings("unchecked")
	public static Geography<ChargingStation> getChargingStationGeography() {
		return (Geography<ChargingStation>) ContextCreator.getChargingStationContext()
				.getProjection("ChargingStationGeography");
	}
	
	@SuppressWarnings("unchecked")
	public static Geography<Road> getRoadGeography() {
		return (Geography<Road>) ContextCreator.getRoadContext().getProjection("RoadGeography");
	}

	@SuppressWarnings("unchecked")
	public static Geography<Lane> getLaneGeography() {
		return (Geography<Lane>) ContextCreator.getLaneContext().getProjection("LaneGeography");
	}
	
	@SuppressWarnings("unchecked")
	public static Geography<Junction> getJunctionGeography() {
		return ContextCreator.getJunctionContext().getProjection(Geography.class, "JunctionGeography");
	}
	
	public static DataCollectionContext getDataCollectionContext() {
		return (DataCollectionContext) mainContext.findContext("DataCollectionContext");
	}
	
	public static int getCurrentTick() {
		return (int) RepastEssentials.GetTickCount() - initTick;
	}
	
	public static int getNextTick() {
		return (int) RepastEssentials.GetTickCount() + 1 - initTick;
	}
}