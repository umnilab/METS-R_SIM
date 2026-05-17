package mets_r;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

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
import mets_r.communication.BSMDataStream;
import mets_r.communication.Connection;
import mets_r.communication.ConnectionManager;
import mets_r.communication.ControlMessageHandler;
import mets_r.communication.KafkaDataStreamProducer;
import mets_r.communication.StepMessageHandler;
import mets_r.data.input.BackgroundTraffic;
import mets_r.data.input.BusSchedule;
import mets_r.data.input.NetworkEventHandler;
import mets_r.data.input.SumoXML;
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
	public static LinkedHashMap<String, Road> coSimRoads = new LinkedHashMap<String, Road>();
	
	/* Synchronize mode flags */
	// Volatile for thread-read-safe 
	public static volatile int waitNextStepCommand = GlobalVariables.SYNCHRONIZED?0:-1;
	
	/**
	 * Number of ticks for which waitForNextStepCommand() has fired (i.e. ticks
	 * whose LAST_PRIORITY action has been reached). The connection thread uses
	 * this to know when a full tick has completed and every recurring action
	 * has been rescheduled, so that reset() can remove them cleanly.
	 */
	public static volatile long completedTickCount = 0;
	
	/* For enable the reset function*/
	public static int initTick = 0;
	private static List<ISchedulableAction> scheduledActions = new ArrayList<ISchedulableAction>();
	private static SnapshotUtil.SimulationSnapshot initialSnapshot = null;
	
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
			scheduledActions.add(schedule.schedule(speedProfileParams, r, "updateFreeFlowSpeed"));
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
			scheduledActions.add(schedule.schedule(agentParams, r, "stepPart1"));
		}
		for (Road r : getRoadContext().getAll()) {
			scheduledActions.add(schedule.schedule(agentParams, r, "stepPart2"));
		}
		
		scheduledActions.add(schedule.schedule(agentParams, ContextCreator.getVehicleContext(), "executeGlobalTransfers"));
	}

	/**
	 * Schedule recurring road actions for a road created at runtime.
	 *
	 * In single-threaded mode road movement is scheduled per road, so the new road
	 * needs stepPart1 and stepPart2 actions. In multi-threaded mode movement is
	 * driven by ThreadedScheduler partitions, but the free-flow speed refresh is
	 * still scheduled per road in both modes.
	 */
	public static void scheduleNewRoad(Road r) {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		if (!GlobalVariables.MULTI_THREADING) {
			ScheduleParameters agentParams = ScheduleParameters.createRepeating(getCurrentTick() + 1, 1, 0);
			scheduledActions.add(schedule.schedule(agentParams, r, "stepPart1"));
			scheduledActions.add(schedule.schedule(agentParams, r, "stepPart2"));
		}

		double speedStartTick = Math.ceil((getCurrentTick() + 1.0)
				/ GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL)
				* GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL;
		ScheduleParameters speedProfileParams = ScheduleParameters.createRepeating(speedStartTick,
				GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL, 4);
		scheduledActions.add(schedule.schedule(speedProfileParams, r, "updateFreeFlowSpeed"));
	}

	// Schedule the event for zone updates (multi-thread)
	public static void scheduleMultiThreadedZoneStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL, 2);
		scheduledActions.add(schedule.schedule(agentParaParams, tscheduler, "paraZoneStep"));
	}

	// Schedule the event for zone updates (single-thread)
	public static void scheduleSequentialZoneStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		
		ScheduleParameters demandServeParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL, 2);
		
		for (Zone z : getZoneContext().getAll()) {
			scheduledActions.add(schedule.schedule(demandServeParams, z, "stepPart1"));
		}
		for (Zone z : getZoneContext().getAll()) {
			scheduledActions.add(schedule.schedule(demandServeParams, z, "stepPart2"));
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
			scheduledActions.add(schedule.schedule(chargingServeParams, cs, "stepPart1"));
		}
		for (ChargingStation cs : getChargingStationContext().getAll()) {
			scheduledActions.add(schedule.schedule(chargingServeParams, cs, "stepPart2"));
		}
	}
	
	/**
	 * Schedule stepPart1 and stepPart2 for a single zone that was created at
	 * runtime (via the addZone control message).
	 *
	 * In single-threaded mode every zone must have its own scheduled actions, so
	 * we add them here starting at the next zone-refresh-aligned tick.
	 * In multi-threaded mode paraZoneStep() already calls stepPart1 on every
	 * object returned by getAll(), so no extra scheduling is needed for part1.
	 * stepPart2 is driven by the partitioner; the new zone will be included in
	 * the partition lists after the next check_run() interval.
	 */
	public static void scheduleNewZone(Zone z) {
		if (!GlobalVariables.MULTI_THREADING) {
			ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
			double startTick = Math.ceil((getCurrentTick() + 1.0) / GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL)
					* GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL;
			ScheduleParameters params = ScheduleParameters.createRepeating(
					startTick, GlobalVariables.SIMULATION_ZONE_REFRESH_INTERVAL, 2);
			scheduledActions.add(schedule.schedule(params, z, "stepPart1"));
			scheduledActions.add(schedule.schedule(params, z, "stepPart2"));
		}
		// Multi-threading: stepPart1 covered by getAll() in paraZoneStep;
		// stepPart2 covered by partitioner at next check_run.
	}

	/**
	 * Schedule stepPart1 and stepPart2 for a single charging station created at
	 * runtime (via the addChargingStation control message).
	 *
	 * In single-threaded mode every station must have its own scheduled actions.
	 * In multi-threaded mode paraChargingStationStep() calls stepPart2 serially on
	 * getAll(), so that part is already covered. stepPart1 (via partitions) will
	 * include the new station after the next check_run() interval.
	 */
	public static void scheduleNewChargingStation(ChargingStation cs) {
		if (!GlobalVariables.MULTI_THREADING) {
			ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
			double startTick = Math.ceil((getCurrentTick() + 1.0) / GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL)
					* GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
			ScheduleParameters params = ScheduleParameters.createRepeating(
					startTick, GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL, 1);
			scheduledActions.add(schedule.schedule(params, cs, "stepPart1"));
			scheduledActions.add(schedule.schedule(params, cs, "stepPart2"));
		}
		// Multi-threading: stepPart2 covered by getAll() in paraChargingStationStep;
		// stepPart1 via partition will include cs at next check_run.
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
		initialSnapshot = SnapshotUtil.captureToMemory();
		
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
	
	/**
	 * Drives the scheduler forward by exactly one tick from the connection
	 * thread, blocking until that tick has reached its LAST_PRIORITY slot.
	 *
	 * Why: Repast's removeAction() can return false for actions sitting in the
	 * scheduler's mid-tick "on-deck" state (already polled out of the queue
	 * for execution this tick, not yet rescheduled). Tearing down the
	 * simulation while any actions are in that state leaves recurring actions
	 * orphaned in the schedule, which then keep firing on the static
	 * singletons (tscheduler, eventHandler) and pin per-run heap state
	 * (cityContext, dataContext, partitioner, ...) for garbage collection.
	 *
	 * After this method returns, every previously-recurring action has been
	 * rescheduled into the main queue with a future nextTime, so a subsequent
	 * removeAction() will succeed for every entry in scheduledActions.
	 *
	 * Cost: one extra simulation tick. In SYNCHRONIZED mode that tick runs
	 * normally on the soon-to-be-discarded contexts and then the schedule is
	 * torn down by the caller.
	 *
	 * Returns false if the wait timed out or was interrupted (caller may
	 * still proceed but should expect the on-deck leak).
	 *
	 * Thread safety: must be called from the connection (control) thread, not
	 * from inside a scheduled action. In free-running mode this is a no-op
	 * (returns false) since waitForNextStepCommand is not in the schedule.
	 */
	private static boolean waitForScheduleQuiescence(String callerLabel) {
		if (!GlobalVariables.SYNCHRONIZED) {
			return false;
		}
		long startTickCount = completedTickCount;
		// Wake the parked scheduler so it executes one more tick.
		waitNextStepCommand = 1;
		// Block until that tick has reached its LAST_PRIORITY slot, which
		// means every other action of the tick has executed AND been
		// rescheduled into the queue with a future nextTime.
		long startWait = System.currentTimeMillis();
		while (completedTickCount == startTickCount) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.warn(callerLabel + " interrupted while waiting for tick to complete");
				return false;
			}
			if (System.currentTimeMillis() - startWait > 30000) {
				logger.error(callerLabel + " timed out waiting for tick to complete; "
						+ "proceeding inline (some scheduled actions may leak).");
				return false;
			}
		}
		return true;
	}

	/**
	 * Performs a reset that is safe with respect to Repast's scheduler.
	 *
	 * See {@link #waitForScheduleQuiescence(String)} for why this is needed.
	 * Cost: one additional simulation tick per call.
	 *
	 * Thread safety: must be called from the connection (control) thread.
	 */
	public static void deferredReset() {
		waitForScheduleQuiescence("deferredReset");
		// Schedule queue is now quiescent; safe to remove actions cleanly.
		reset();
	}

	/**
	 * Performs a load that is safe with respect to Repast's scheduler.
	 *
	 * load() ultimately calls rebuildForLoad(), which (like reset()) removes
	 * every tracked recurring action and rebuilds all sub-contexts. Without
	 * deferral, the same on-deck-queue leak that affected reset would apply
	 * here: ~5 recurring actions per load remain in the schedule, pinning
	 * the prior run's heap state and firing every tick on the static
	 * singletons against the freshly-loaded contexts.
	 *
	 * Cost: one additional simulation tick per call.
	 *
	 * Thread safety: must be called from the connection (control) thread.
	 */
	public static void deferredLoad(String zipPath) {
		waitForScheduleQuiescence("deferredLoad");
		// Schedule queue is now quiescent; rebuildForLoad() will remove
		// every tracked action cleanly.
		load(zipPath);
	}

	// The reset function
	private static int clearScheduledActions(String logLabel) {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		int sizeBefore = scheduledActions.size();
		int actuallyRemoved = 0;
		for (ISchedulableAction scheduledAction : scheduledActions) {
			if (schedule.removeAction(scheduledAction)) {
				actuallyRemoved++;
			}
		}
		logger.info(logLabel + ": tracked=" + sizeBefore + " removed=" + actuallyRemoved);
		scheduledActions = new ArrayList<ISchedulableAction>();
		return actuallyRemoved;
	}

	public static void reset() {
		if (initialSnapshot == null) {
			logger.warn("Fast reset baseline is unavailable; falling back to full rebuild reset.");
			resetByRebuild();
			return;
		}
		if (!SnapshotUtil.matchesCurrentFacilityMembership(initialSnapshot)) {
			logger.warn("Facility membership changed since startup; falling back to full rebuild reset.");
			resetByRebuild();
			return;
		}

		logger.info("Restart the simulation from the in-memory tick-0 baseline!");
		clearScheduledActions("RESET-SCHED");
		if (dataContext != null) {
			dataContext.stopCollecting();
			mainContext.removeSubContext(dataContext);
		}
		if (vehicleContext != null) {
			mainContext.removeSubContext(vehicleContext);
		}
		agg_logger.close();
		travel_demand.close();

		coSimRoads.clear();
		eventHandler.reinitialize();
		initTick = (int) Math.max(RepastEssentials.GetTickCount(), 0);

		GlobalVariables.RandomGenerator = new java.util.Random(GlobalVariables.RANDOM_SEED);
		BusSchedule.rand_route_only = new Random(GlobalVariables.RandomGenerator.nextInt());
		BSMDataStream.setRandom(new Random(GlobalVariables.RandomGenerator.nextInt()));
		BusSchedule.route_num = 0;

		agg_logger = new AggregatedLogger();
		background_traffic = new BackgroundTraffic();
		travel_demand = new TravelDemand();
		bus_schedule = new BusSchedule();
		for (Zone z : getZoneContext().getAll()) {
			z.traversingBusRoutes.clear();
		}
		bus_schedule.postProcessing();

		partitioner = new MetisPartition(GlobalVariables.N_Partition);
		waitNextStepCommand = GlobalVariables.SYNCHRONIZED ? 0 : -1;
		if (tscheduler != null) {
			tscheduler.resetTickGuards();
		}

		vehicleContext = new VehicleContext(true);
		mainContext.addSubContext(vehicleContext);
		dataContext = new DataCollectionContext();
		mainContext.addSubContext(dataContext);

		SnapshotUtil.restoreToCurrentContexts(initialSnapshot);

		if (GlobalVariables.MULTI_THREADING) {
			try {
				partitioner.first_run();
				ContextCreator.logger.info("Reset partitioner");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		dataContext.startCollecting();
		scheduleEvents();

		logger.info("FAST RESET OK: restored tick-0 state without rebuilding facilities"
				+ " (initTick=" + initTick + ")");
	}

	private static void resetByRebuild() {
		logger.info("Restart the simulation!");
		
		// 0. Clear scheduled actions and variables
		clearScheduledActions("RESET-SCHED");
		dataContext.stopCollecting();
		agg_logger.close();
		travel_demand.close();
		
		mainContext.removeSubContext(cityContext);
		mainContext.removeSubContext(dataContext);
		mainContext.removeSubContext(vehicleContext);
		
		// Release stale Road references so query_coSimVehicle cannot return
		// vehicle IDs from the previous run before set_cosim_road is called again.
		coSimRoads.clear();
		
		// Re-populate the network event queue so events replay from tick 0
		eventHandler.reinitialize();
		
		// CRITICAL: drop the SumoXML singleton so the next getData() call re-parses
		// the network file. Without this, RoadContext/LaneContext/CityContext would
		// reuse the SAME Road/Lane/Junction/Signal objects from the previous run,
		// carrying over nVehicles_, firstVehicle_/lastVehicle_ macro-list pointers,
		// departureVehMap, toAddDepartureVeh, travelTime, currentFlow, etc. That is
		// what produced the cross-run vehicleOnRoad inflation (e.g. 766 -> 1418 ->
		// 1788 -> 2102 at tick=0 over successive resets even with zero fleet).
		SumoXML.data = null;
		
		// Reload variables 
		initTick = (int) Math.max(RepastEssentials.GetTickCount(), 0);
		
		agentID = 0;
		GlobalVariables.RandomGenerator = new java.util.Random(GlobalVariables.RANDOM_SEED);
		BusSchedule.rand_route_only = new Random(GlobalVariables.RandomGenerator.nextInt());
		BSMDataStream.RANDOM = new Random(GlobalVariables.RandomGenerator.nextInt()); 
		BusSchedule.route_num = 0;
		
		agg_logger = new AggregatedLogger();
		background_traffic = new BackgroundTraffic();
		travel_demand = new TravelDemand();
		bus_schedule = new BusSchedule();
		partitioner = new MetisPartition(GlobalVariables.N_Partition); 
		waitNextStepCommand = 0;
		// Clear per-tick idempotency guards on the singleton schedulers so that
		// the first tick of the new run is never treated as a duplicate call
		// from an orphaned scheduled action.
		if (tscheduler != null) {
			tscheduler.resetTickGuards();
		}
		
		// Regenerate the sub-contexts
		buildSubContexts();
		
		// Post-reset integrity check: verify the new road network is truly fresh.
		// If any road still reports a non-zero vehicle count immediately after
		// rebuilding, it means we are still pointing at stale Road objects from a
		// previous run (or removeSubContext did not actually drop the old subgraph).
		// This is the diagnostic the inter-run vehOnRoad inflation needs.
		int leakedRoads = 0;
		int leakedTotal = 0;
		int firstLeakedID = -1;
		int firstLeakedCount = 0;
		for (Road r : getRoadContext().getAll()) {
			int n = r.getVehicleNum();
			if (n != 0) {
				leakedRoads++;
				leakedTotal += n;
				if (firstLeakedID < 0) {
					firstLeakedID = r.getID();
					firstLeakedCount = n;
				}
			}
		}
		int privEV = getVehicleContext().getPrivateEVs().size();
		int privGV = getVehicleContext().getPrivateGVs().size();
		int taxis = getVehicleContext().getTaxis().size();
		int buses = getVehicleContext().getBuses().size();
		int roadCount = getRoadContext().getAll().size();
		if (leakedRoads > 0) {
			logger.warn("POST-RESET LEAK: " + leakedRoads + " road(s) still have nVehicles_>0; "
					+ "first road=" + firstLeakedID + " count=" + firstLeakedCount
					+ " total=" + leakedTotal + " over " + roadCount + " roads");
		} else {
			logger.info("POST-RESET OK: all " + roadCount + " roads have nVehicles_=0");
		}
		logger.info("POST-RESET fleet: privateEV=" + privEV + " privateGV=" + privGV
				+ " taxis=" + taxis + " buses=" + buses + " (initTick=" + initTick + ")");
		
		// Clear and reinitialize the scheduled actions
		scheduleEvents();
	}
	
	// The save function: captures all dynamic state into a zip archive
	public static void save(String zipPath) {
		try {
			SnapshotUtil.saveToZip(zipPath);
		} catch (IOException e) {
			logger.error("Failed to save simulation state: " + e.getMessage());
			e.printStackTrace();
		}
	}
	
	// The load function: restores simulation from a zip archive
	public static void load(String zipPath) {
		try {
			SnapshotUtil.loadFromZip(zipPath);
		} catch (IOException e) {
			logger.error("Failed to load simulation state: " + e.getMessage());
			e.printStackTrace();
		}
	}
	
	/**
	 * Rebuild the simulation infrastructure for load.
	 * Similar to reset() but creates a VehicleContext without auto-generating vehicles,
	 * since those will be restored from the snapshot.
	 */
	public static void rebuildForLoad(int savedInitTick, int savedTick) {
		logger.info("Rebuilding simulation infrastructure for load...");
		
		// Clear scheduled actions (mirror of reset(): track removal so the
		// on-deck-queue leak is visible if deferredLoad ever fails to land in
		// a quiescent state)
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		int sizeBefore = scheduledActions.size();
		int actuallyRemoved = 0;
		for (ISchedulableAction scheduledAction : scheduledActions) {
			if (schedule.removeAction(scheduledAction)) {
				actuallyRemoved++;
			}
		}
		logger.info("LOAD-SCHED: tracked=" + sizeBefore + " removed=" + actuallyRemoved);
		scheduledActions = new ArrayList<ISchedulableAction>();
		dataContext.stopCollecting();
		agg_logger.close();
		travel_demand.close();
		
		mainContext.removeSubContext(cityContext);
		mainContext.removeSubContext(dataContext);
		mainContext.removeSubContext(vehicleContext);
		
		// Release stale Road references so query_coSimVehicle cannot return
		// vehicle IDs from the previous run before set_cosim_road is called again.
		coSimRoads.clear();
		
		// Re-populate the network event queue so events replay correctly
		eventHandler.reinitialize();
		
		// Drop the SumoXML singleton (see reset() for rationale): forces a fresh
		// parse so RoadContext/LaneContext/CityContext do NOT reuse facility
		// objects (Road/Lane/Junction/Signal) carrying state from the prior run.
		SumoXML.data = null;
		
		int currentRepastTick = (int) Math.max(RepastEssentials.GetTickCount(), 0);
		
		// Use current Repast tick for scheduling so events start from "now",
		// not from the past. We'll adjust initTick for getCurrentTick() after scheduling.
		initTick = currentRepastTick;
		
		// Reinitialize data structures
		agg_logger = new AggregatedLogger();
		background_traffic = new BackgroundTraffic();
		travel_demand = new TravelDemand();
		bus_schedule = new BusSchedule();
		partitioner = new MetisPartition(GlobalVariables.N_Partition);
		waitNextStepCommand = GlobalVariables.SYNCHRONIZED ? 0 : -1;
		// Clear per-tick idempotency guards on the singleton schedulers so the
		// first tick after load is never treated as a duplicate call from an
		// orphaned scheduled action (defense-in-depth; mirrors reset()).
		if (tscheduler != null) {
			tscheduler.resetTickGuards();
		}
		
		// Rebuild city context (roads, zones, charging stations from data.properties)
		cityContext = new CityContext();
		mainContext.addSubContext(cityContext);
		cityContext.createSubContexts();
		cityContext.buildRoadNetwork();
		cityContext.setNeighboringGraph();
		bus_schedule.postProcessing();
		
		// Create empty vehicle context (vehicles will be restored from snapshot)
		vehicleContext = new VehicleContext(true);
		mainContext.addSubContext(vehicleContext);
		
		// Rebuild data context
		dataContext = new DataCollectionContext();
		mainContext.addSubContext(dataContext);
		
		// Initialize operational parameters
		cityContext.modifyRoadNetwork();
		
		if (GlobalVariables.MULTI_THREADING) {
			try {
				partitioner.first_run();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		dataContext.startCollecting();
		
		// Reschedule events (initTick == currentRepastTick, so events start now)
		scheduleEvents();
		
		// Now set the correct offset so getCurrentTick() returns the saved logical tick
		initTick = currentRepastTick - savedTick;
		
		logger.info("Infrastructure rebuilt. Tick offset set to match saved tick: " + savedTick);
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
		// Mark the current tick's LAST_PRIORITY slot as reached. Every other
		// scheduled action for this tick has finished executing and Repast has
		// already put its repeating instance back into the schedule queue, so
		// from this moment until waitNextStepCommand is bumped, the schedule is
		// in a fully quiescent state where removeAction() works reliably.
		completedTickCount++;
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
