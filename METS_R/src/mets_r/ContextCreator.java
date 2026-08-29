package mets_r;

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
import mets_r.GlobalVariables;
import mets_r.communication.BSMDataStream;
import mets_r.communication.Connection;
import mets_r.communication.ConnectionManager;
import mets_r.communication.ControlMessageHandler;
import mets_r.communication.KafkaDataStreamProducer;
import mets_r.communication.StepMessageHandler;
import mets_r.communication.SimulationEventJournal;
import mets_r.data.input.BackgroundTraffic;
import mets_r.data.input.BusSchedule;
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
	public static AggregatedLogger agg_logger = GlobalVariables.ENABLE_AGGREGATE_WRITE
			? new AggregatedLogger() : null;
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
	public static ThreadedScheduler tscheduler = GlobalVariables.MULTI_THREADING?new ThreadedScheduler(GlobalVariables.N_THREADS):null;
	
	/* Simulation objects */
	public static CityContext cityContext;
	public static VehicleContext vehicleContext;
	public static DataCollectionContext dataContext;

	/* Data communication */
	// Connection manager maintains the socket server for remote programs
	// set to be final to avoid further modifications
	public static final ConnectionManager manager = GlobalVariables.ENABLE_NETWORK
			? new ConnectionManager() : null;
	public static Connection connection = null;
	public static final StepMessageHandler stepHandler = new StepMessageHandler();
	public static final ControlMessageHandler controlHandler = new ControlMessageHandler();
	// Kafka manager maintains the resources for sending message to Kafka
	public static final KafkaDataStreamProducer kafkaManager = GlobalVariables.V2X
			? new KafkaDataStreamProducer() : null;
	// Data collector gather tick by tick tickSnapshot and provide it to data consumers
	public static final DataCollector dataCollector = GlobalVariables.ENABLE_DATA_COLLECTION
			? new DataCollector() : null;
	// Periodic aggregate and console metrics do not depend on trajectory collection.
	public static final MetricsReporter metricsReporter = new MetricsReporter();
	
	// Road collections for co-simulation
	public static LinkedHashMap<String, Road> coSimRoads = new LinkedHashMap<String, Road>();
	
	/* Synchronize mode flags */
	// Volatile for thread-read-safe 
	public static volatile int waitNextStepCommand = GlobalVariables.SYNCHRONIZED?0:-1;
	private static volatile int nextStepTargetTick = Integer.MIN_VALUE;
	private static final Object stepCommandLock = new Object();
	private static volatile boolean schedulerAtStepGate = false;
	private static volatile long lastStepGateEnterMs = 0;
	private static volatile long lastStepGateReleaseMs = 0;
	private static volatile int lastStepGateTick = Integer.MIN_VALUE;
	private static volatile long stepGateLoopCount = 0;
	private static volatile long lastStepGateLoopMs = 0;
	private static volatile long lastStepCommandMs = 0;
	private static volatile int lastStepCommandRequestTick = Integer.MIN_VALUE;
	private static volatile int lastStepCommandAcceptedNum = 0;
	
	/**
	 * Number of ticks for which waitForNextStepCommand() has fired (i.e. ticks
	 * whose LAST_PRIORITY action has been reached). The connection thread uses
	 * this to know when a full tick has completed and every recurring action
	 * has been rescheduled, so that reset() can remove them cleanly.
	 */
	public static volatile long completedTickCount = 0;
	private static volatile long runEpoch = 0L;

	public static class StepCommandResult {
		public final boolean accepted;
		public final int currentTick;
		public final int acceptedStepNum;
		public final int targetTick;

		private StepCommandResult(boolean accepted, int currentTick, int acceptedStepNum, int targetTick) {
			this.accepted = accepted;
			this.currentTick = currentTick;
			this.acceptedStepNum = acceptedStepNum;
			this.targetTick = targetTick;
		}
	}

	public static StepCommandResult setNextStepCommand(int requestTick, int stepNum) {
		synchronized (stepCommandLock) {
			int currentTick = getCurrentTick();
			if (requestTick != currentTick) {
				return new StepCommandResult(false, currentTick, 0, currentTick);
			}
			int requestedStepNum = Math.max(stepNum, 1);
			// The Python client retries with the remaining steps to its same
			// absolute target, so normal STEP must replace the target.
			nextStepTargetTick = currentTick + requestedStepNum;
			waitNextStepCommand = requestedStepNum;
			lastStepCommandMs = System.currentTimeMillis();
			lastStepCommandRequestTick = requestTick;
			lastStepCommandAcceptedNum = requestedStepNum;
			if (!schedulerAtStepGate && lastStepGateReleaseMs > 0
					&& lastStepCommandMs - lastStepGateReleaseMs > 30000) {
				logger.warn("STEP accepted while scheduler has not returned to the step gate for "
						+ (lastStepCommandMs - lastStepGateReleaseMs) + " ms; status="
						+ getStepStatus());
			}
			stepCommandLock.notifyAll();
			return new StepCommandResult(true, currentTick, requestedStepNum, nextStepTargetTick);
		}
	}

	private static void setWaitNextStepCommand(int stepCommand) {
		synchronized (stepCommandLock) {
			waitNextStepCommand = stepCommand;
			int currentTick = getCurrentTick();
			nextStepTargetTick = stepCommand > 0 ? currentTick + stepCommand : currentTick;
			stepCommandLock.notifyAll();
		}
	}

	public static LinkedHashMap<String, Object> getStepStatus() {
		LinkedHashMap<String, Object> status = new LinkedHashMap<String, Object>();
		long now = System.currentTimeMillis();
		synchronized (stepCommandLock) {
			status.put("currentTick", getCurrentTick());
			status.put("completedTickCount", completedTickCount);
			status.put("waitNextStepCommand", waitNextStepCommand);
			status.put("nextStepTargetTick", nextStepTargetTick);
			status.put("schedulerAtStepGate", schedulerAtStepGate);
			status.put("lastStepGateTick", lastStepGateTick);
			status.put("lastStepGateAgeMs", lastStepGateEnterMs == 0 ? -1 : now - lastStepGateEnterMs);
			status.put("lastStepGateReleaseAgeMs", lastStepGateReleaseMs == 0 ? -1 : now - lastStepGateReleaseMs);
			status.put("stepGateLoopCount", stepGateLoopCount);
			status.put("lastStepGateLoopAgeMs", lastStepGateLoopMs == 0 ? -1 : now - lastStepGateLoopMs);
			status.put("lastStepCommandAgeMs", lastStepCommandMs == 0 ? -1 : now - lastStepCommandMs);
			status.put("lastStepCommandRequestTick", lastStepCommandRequestTick);
			status.put("lastStepCommandAcceptedNum", lastStepCommandAcceptedNum);
			status.put("stepGateActionTracked", stepGateAction != null);
			status.put("scheduledActionCount", scheduledActions.size());
		}
		if (tscheduler != null) {
			status.put("threadedScheduler", tscheduler.getStatus());
		}
		if (GlobalVariables.ENABLE_SCHEDULER_PROFILING) {
			LinkedHashMap<String, Object> scheduledNanos = new LinkedHashMap<String, Object>();
			scheduledNanos.put("roadNetworkRefresh", roadNetworkRefreshNanos);
			scheduledNanos.put("freeFlowRefresh", freeFlowRefreshNanos);
			status.put("scheduledRefreshCumulativeNanos", scheduledNanos);
			LinkedHashMap<String, Object> scheduledCounts = new LinkedHashMap<String, Object>();
			scheduledCounts.put("roadNetworkRefresh", roadNetworkRefreshCount);
			scheduledCounts.put("freeFlowRefresh", freeFlowRefreshCount);
			status.put("scheduledRefreshCounts", scheduledCounts);
		}
		return status;
	}

	public static LinkedHashMap<String, Object> getCapabilities() {
		LinkedHashMap<String, Object> capabilities = new LinkedHashMap<String, Object>();
		boolean headless = kafkaManager == null && dataCollector == null && agg_logger == null
				&& !GlobalVariables.ENABLE_METRICS_DISPLAY && !GlobalVariables.DEBUG_NETWORK
				&& !logger.isDebugEnabled();
		capabilities.put("headless", headless);
		capabilities.put("networkEnabled", GlobalVariables.ENABLE_NETWORK);
		capabilities.put("synchronized", GlobalVariables.SYNCHRONIZED);
		capabilities.put("v2x", kafkaManager != null);
		capabilities.put("kafkaProducer", kafkaManager != null);
		capabilities.put("dataCollection", dataCollector != null);
		capabilities.put("jsonTrajectoryWrite", dataCollector != null && GlobalVariables.ENABLE_JSON_WRITE);
		capabilities.put("binaryTrajectoryWrite",
				dataCollector != null && GlobalVariables.ENABLE_TRAJECTORY_BINARY_WRITE);
		capabilities.put("aggregateWrite", agg_logger != null);
		capabilities.put("metricsDisplay", GlobalVariables.ENABLE_METRICS_DISPLAY);
		capabilities.put("metricScanning",
				GlobalVariables.ENABLE_METRICS_DISPLAY || GlobalVariables.ENABLE_AGGREGATE_WRITE);
		capabilities.put("schedulerProfiling", GlobalVariables.ENABLE_SCHEDULER_PROFILING);
		capabilities.put("activeRoadStepping", GlobalVariables.ACTIVE_ROAD_STEPPING);
		capabilities.put("multiThreading", GlobalVariables.MULTI_THREADING);
		capabilities.put("debugNetwork", GlobalVariables.DEBUG_NETWORK);
		capabilities.put("inboundDebugLogging", logger.isDebugEnabled());
		capabilities.put("runEpoch", runEpoch);
		return capabilities;
	}

	public static long getRunEpoch() {
		return runEpoch;
	}

	private static void beginNewRunEpoch() {
		runEpoch++;
		SimulationEventJournal.reset(runEpoch);
		controlHandler.resetRunEpoch(runEpoch);
	}

	public static boolean awaitStepTarget(int targetTick, long timeoutMs) {
		long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
		synchronized (stepCommandLock) {
			while (!(schedulerAtStepGate && lastStepGateTick >= targetTick)) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0L) return false;
				try {
					stepCommandLock.wait(Math.min(remaining, 100L));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return true;
		}
	}
	
	/* For enable the reset function*/
	public static int initTick = 0;
	private static List<ISchedulableAction> scheduledActions = new ArrayList<ISchedulableAction>();
	private static ContextCreator scheduleOwner = null;
	private static ISchedulableAction stepGateAction = null;
	private static SnapshotUtil.SimulationSnapshot initialSnapshot = null;
	private static volatile long roadNetworkRefreshNanos = 0L;
	private static volatile long freeFlowRefreshNanos = 0L;
	private static volatile long roadNetworkRefreshCount = 0L;
	private static volatile long freeFlowRefreshCount = 0L;
	
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

		createAndStartDataContext();

		// Initialize operational parameters 
		cityContext.modifyRoadNetwork(); // This initializes data for path calculation, DO NOT remove it
		
		if(GlobalVariables.MULTI_THREADING) {
			try {
				partitioner.first_run();
				if (GlobalVariables.ACTIVE_ROAD_STEPPING) {
					getRoadContext().rebuildActiveRoadsFromState();
				}
				ContextCreator.logger.info("Reset partitioner");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
	}
	
	// Schedule simulation events
	public static void scheduleEvents() {
		scheduleEvents(0);
	}

	private static void scheduleEvents(int backgroundSpeedRefreshDelay) {
		if (GlobalVariables.SYNCHRONIZED) {
			scheduleNextStepUpdating();
		}
		schedulePrivateTripLoader();
		scheduleRoadNetworkRefresh();
		scheduleFreeFlowSpeedRefresh(backgroundSpeedRefreshDelay);
		
		// Set up data collection
		if (GlobalVariables.ENABLE_DATA_COLLECTION) {
			scheduleDataCollection();
		}
		if (GlobalVariables.ENABLE_AGGREGATE_WRITE || GlobalVariables.ENABLE_METRICS_DISPLAY) {
			scheduleMetricsReporting();
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
		if (!travel_demand.hasAnyPrivateDemand()) {
			logger.info("Private trip loader skipped: no private EV/GV demand records.");
			return;
		}
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
		scheduledActions.add(schedule.schedule(agentParamsNW, scheduleOwner, "refreshRoadNetwork"));
	}

	// Schedule the event of updating background speeds/estimated travel time
	// For each link (per update), background speed serves as the target speed of vehicles, 
	// which follows a normal distribution.
	public static void scheduleFreeFlowSpeedRefresh() {
		scheduleFreeFlowSpeedRefresh(0);
	}

	private static void scheduleFreeFlowSpeedRefresh(int delay) {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters speedProfileParams = ScheduleParameters.createRepeating(initTick + delay,
				GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL, 4);
		scheduledActions.add(schedule.schedule(speedProfileParams, scheduleOwner, "refreshFreeFlowSpeeds"));
	}

	private static int backgroundSpeedRefreshDelayForSavedTick(int savedTick) {
		int interval = Math.max(1, GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL);
		int phase = Math.floorMod(Math.max(0, savedTick), interval);
		return phase == 0 ? 0 : interval - phase;
	}

	public void refreshRoadNetwork() {
		long start = GlobalVariables.ENABLE_SCHEDULER_PROFILING ? System.nanoTime() : 0L;
		cityContext.modifyRoadNetwork();
		if (GlobalVariables.ENABLE_SCHEDULER_PROFILING) {
			roadNetworkRefreshNanos += System.nanoTime() - start;
			roadNetworkRefreshCount++;
		}
	}

	public void refreshFreeFlowSpeeds() {
		long start = GlobalVariables.ENABLE_SCHEDULER_PROFILING ? System.nanoTime() : 0L;
		cityContext.updateBackgroundSpeeds();
		if (GlobalVariables.ENABLE_SCHEDULER_PROFILING) {
			freeFlowRefreshNanos += System.nanoTime() - start;
			freeFlowRefreshCount++;
		}
	}

	// Schedule the event for synchronized update
	public static void scheduleNextStepUpdating() {
		if (!GlobalVariables.SYNCHRONIZED) {
			return;
		}
		if (scheduleOwner == null) {
			logger.warn("Synchronized step gate cannot be scheduled before ContextCreator.build() sets the owner.");
			return;
		}
		if (stepGateAction != null) {
			return;
		}
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		int firstGateTick = initTick;
		synchronized (stepCommandLock) {
			if (schedulerAtStepGate) {
				firstGateTick = initTick + 1;
			}
		}
		ScheduleParameters nextStepParams = ScheduleParameters.createRepeating(firstGateTick, 1, ScheduleParameters.LAST_PRIORITY);
		stepGateAction = schedule.schedule(nextStepParams, scheduleOwner, "waitForNextStepCommand");
		scheduledActions.add(stepGateAction);
	}
	
	// Schedule the event for vehicle movements (multi-thread)
	public static void scheduleMultiThreadedRoadStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		
		ScheduleParameters agentParaParams = ScheduleParameters.createRepeating(initTick + 1, 1, 0);
		scheduledActions.add(schedule.schedule(agentParaParams, tscheduler, "paraRoadStep"));

		if (GlobalVariables.ENABLE_SCHEDULER_PROFILING) {
			ScheduleParameters timerParaParams = ScheduleParameters.createRepeating(initTick + 1,
					GlobalVariables.SIMULATION_PARTITION_REFRESH_INTERVAL, 0);
			scheduledActions.add(schedule.schedule(timerParaParams, tscheduler, "reportTime"));
		}

		// Segment membership stays fixed until the road/connector topology changes.
	}

	// Schedule the event for vehicle movements (single-thread)
	public static void scheduleSequentialRoadStep() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters part1Params = ScheduleParameters.createRepeating(initTick + 1, 1, 0.3);
		ScheduleParameters part2Params = ScheduleParameters.createRepeating(initTick + 1, 1, 0.2);
		ScheduleParameters transferParams = ScheduleParameters.createRepeating(initTick + 1, 1, 0.1);
		for (Road r : getRoadContext().getAllSteppableRoads()) {
			scheduledActions.add(schedule.schedule(part1Params, r, "stepPart1"));
		}
		for (Road r : getRoadContext().getAllSteppableRoads()) {
			scheduledActions.add(schedule.schedule(part2Params, r, "stepPart2"));
		}
		scheduledActions.add(schedule.schedule(transferParams,
				ContextCreator.getVehicleContext(), "executeGlobalTransfers"));
		if (GlobalVariables.ENABLE_INTERSECTION_SWEPT_COLLISION_CHECK) {
			ScheduleParameters intersectionParams =
					ScheduleParameters.createRepeating(initTick + 1, 1, 0.0);
			scheduledActions.add(schedule.schedule(intersectionParams,
					ContextCreator.getRoadContext(), "stepIntersections"));
		}
	}

	/**
	 * Schedule recurring road actions for a road created at runtime.
	 *
	 * In single-threaded mode road movement is scheduled per road, so the new road
	 * needs stepPart1 and stepPart2 actions. In multi-threaded mode movement is
	 * driven by ThreadedScheduler partitions. Free-flow speed refresh is batched
	 * for all roads in CityContext.
	 */
	public static void scheduleNewRoad(Road r) {
		if (!GlobalVariables.MULTI_THREADING) {
			ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
			ScheduleParameters part1Params = ScheduleParameters.createRepeating(
					getCurrentTick() + 1, 1, 0.3);
			ScheduleParameters part2Params = ScheduleParameters.createRepeating(
					getCurrentTick() + 1, 1, 0.2);
			scheduledActions.add(schedule.schedule(part1Params, r, "stepPart1"));
			scheduledActions.add(schedule.schedule(part2Params, r, "stepPart2"));
		}
		// Background speed refresh is batched in cityContext.updateBackgroundSpeeds(),
		// so newly added roads are picked up automatically at the next refresh.
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
	 * object returned by getAll(), and the new zone is inserted into the live
	 * stepPart2 partition list immediately.
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
		} else if (partitioner != null) {
			partitioner.addZone(z);
		}
		// Multi-threading: stepPart1 covered by getAll() in paraZoneStep;
		// stepPart2 is added to the live partition list above.
	}

	/**
	 * Schedule stepPart1 and stepPart2 for a single charging station created at
	 * runtime (via the addChargingStation control message).
	 *
	 * In single-threaded mode every station must have its own scheduled actions.
	 * In multi-threaded mode paraChargingStationStep() calls stepPart2 serially on
	 * getAll(), and the new station is inserted into the live stepPart1 partition
	 * list immediately.
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
		} else if (partitioner != null) {
			partitioner.addChargingStation(cs);
		}
		// Multi-threading: stepPart2 covered by getAll() in paraChargingStationStep;
		// stepPart1 is added to the live partition list above.
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
		if (dataContext != null) {
			ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
			ScheduleParameters tickStartParams = ScheduleParameters.createRepeating(initTick, tickDuration,
					ScheduleParameters.FIRST_PRIORITY);
			scheduledActions.add(schedule.schedule(tickStartParams, dataContext, "startTick"));

			ScheduleParameters tickEndParams = ScheduleParameters.createRepeating(initTick, tickDuration,
					-1);
			scheduledActions.add(schedule.schedule(tickEndParams, dataContext, "stopTick"));
		}
	}

	public static void scheduleMetricsReporting() {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		ScheduleParameters recordRuntimeParams = ScheduleParameters.createRepeating(initTick,
				GlobalVariables.METRICS_DISPLAY_INTERVAL, -0.5);
		scheduledActions.add(schedule.schedule(recordRuntimeParams, metricsReporter, "report"));
	}

	private static void createAndStartDataContext() {
		dataContext = null;
		if (!GlobalVariables.ENABLE_DATA_COLLECTION) {
			return;
		}
		dataContext = new DataCollectionContext();
		mainContext.addSubContext(dataContext);
		dataContext.startCollecting();
	}

	private static void stopAndRemoveDataContext() {
		if (dataContext == null) {
			return;
		}
		dataContext.stopCollecting();
		mainContext.removeSubContext(dataContext);
		dataContext = null;
	}

	private static void closeAggregateLogger(boolean recordUnfinishedTrips) {
		if (agg_logger == null) {
			return;
		}
		if (recordUnfinishedTrips) {
			agg_logger.recordUnfinishedTrips();
		}
		agg_logger.close();
		agg_logger = null;
	}

	private static void recreateAggregateLogger() {
		closeAggregateLogger(false);
		agg_logger = GlobalVariables.ENABLE_AGGREGATE_WRITE ? new AggregatedLogger() : null;
	}

	private static void resetScheduledProfiling() {
		roadNetworkRefreshNanos = 0L;
		freeFlowRefreshNanos = 0L;
		roadNetworkRefreshCount = 0L;
		freeFlowRefreshCount = 0L;
	}

	// The main function
	public Context<Object> build(Context<Object> context) {
		GlobalVariables.validateNetworkConfiguration();
		start_time = System.currentTimeMillis(); // Record the start time of the simulation
		
		mainContext = context;
		scheduleOwner = this;
		
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
	 * scheduler state and pin per-run heap state
	 * (cityContext, dataContext, partitioner, ...) for garbage collection.
	 *
	 * After this method returns, every previously-recurring action has been
	 * rescheduled into the main queue with a future nextTime, so a subsequent
	 * removeAction() will succeed for every entry in scheduledActions.
	 *
	 * Cost: up to one extra simulation tick if the scheduler is already
	 * parked. In SYNCHRONIZED mode that tick runs normally on the
	 * soon-to-be-discarded contexts and then the schedule is torn down by the
	 * caller.
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
		long targetTickCount;
		synchronized (stepCommandLock) {
			targetTickCount = completedTickCount + 1;
			// Wake the parked scheduler so it reaches a LAST_PRIORITY slot.
			// If a normal STEP is already in flight, do not overwrite its
			// remaining credits; reaching the next LAST_PRIORITY slot is enough
			// to make the schedule quiescent for reset/load removal.
			if (waitNextStepCommand == 0) {
				nextStepTargetTick = getCurrentTick() + 1;
				waitNextStepCommand = 1;
			}
			stepCommandLock.notifyAll();
		}
		// Block until that tick has reached its LAST_PRIORITY slot, which
		// means every other action of the tick has executed AND been
		// rescheduled into the queue with a future nextTime.
		long startWait = System.currentTimeMillis();
		while (completedTickCount < targetTickCount) {
			try {
				synchronized (stepCommandLock) {
					if (completedTickCount < targetTickCount) {
						stepCommandLock.wait(100);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.warn(callerLabel + " interrupted while waiting for tick to complete");
				return false;
			}
			if (System.currentTimeMillis() - startWait > 30000) {
				logger.error(callerLabel + " timed out waiting for tick to complete; "
						+ "target completedTickCount=" + targetTickCount
						+ ", current completedTickCount=" + completedTickCount
						+ ", waitNextStepCommand=" + waitNextStepCommand
						+ ", nextStepTargetTick=" + nextStepTargetTick
						+ "; proceeding inline (some scheduled actions may leak).");
				return false;
			}
		}
		return true;
	}

	/**
	 * Performs a reset that is safe with respect to Repast's scheduler.
	 *
	 * See {@link #waitForScheduleQuiescence(String)} for why this is needed.
	 * Cost: up to one additional simulation tick per call.
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
	 * Cost: up to one additional simulation tick per call.
	 *
	 * Thread safety: must be called from the connection (control) thread.
	 */
	public static boolean deferredLoad(String zipPath) {
		return deferredLoad(zipPath, false);
	}

	public static boolean deferredLoad(String zipPath, boolean reloadNetwork) {
		waitForScheduleQuiescence("deferredLoad");
		// Schedule queue is now quiescent; rebuildForLoad() will remove
		// every tracked action cleanly.
		return load(zipPath, reloadNetwork);
	}

	// The reset function
	private static int clearScheduledActions(String logLabel) {
		ISchedule schedule = RunEnvironment.getInstance().getCurrentSchedule();
		int sizeBefore = scheduledActions.size();
		int actuallyRemoved = 0;
		ArrayList<ISchedulableAction> remainingActions = new ArrayList<ISchedulableAction>();
		for (ISchedulableAction scheduledAction : scheduledActions) {
			if (schedule.removeAction(scheduledAction)) {
				actuallyRemoved++;
			} else {
				remainingActions.add(scheduledAction);
			}
		}
		logger.info(logLabel + ": tracked=" + sizeBefore + " removed=" + actuallyRemoved);
		scheduledActions = remainingActions;
		if (stepGateAction != null && !scheduledActions.contains(stepGateAction)) {
			stepGateAction = null;
		}
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
		beginNewRunEpoch();
		clearScheduledActions("RESET-SCHED");
		stopAndRemoveDataContext();
		if (vehicleContext != null) {
			mainContext.removeSubContext(vehicleContext);
		}
		closeAggregateLogger(false);
		travel_demand.close();

		coSimRoads.clear();
		initTick = (int) Math.max(RepastEssentials.GetTickCount(), 0);

		GlobalVariables.RandomGenerator = new java.util.Random(GlobalVariables.RANDOM_SEED);
		BusSchedule.rand_route_only = new Random(GlobalVariables.RandomGenerator.nextInt());
		BSMDataStream.setRandom(new Random(GlobalVariables.RandomGenerator.nextInt()));
		BusSchedule.route_num = 0;

		recreateAggregateLogger();
		background_traffic = new BackgroundTraffic();
		travel_demand = new TravelDemand();
		bus_schedule = new BusSchedule();
		for (Zone z : getZoneContext().getAll()) {
			z.traversingBusRoutes.clear();
		}
		bus_schedule.postProcessing();

		partitioner = new MetisPartition(GlobalVariables.N_Partition);
		setWaitNextStepCommand(GlobalVariables.SYNCHRONIZED ? 0 : -1);
		if (tscheduler != null) {
			tscheduler.resetTickGuards();
		}
		resetScheduledProfiling();

		vehicleContext = new VehicleContext(true);
		mainContext.addSubContext(vehicleContext);
		createAndStartDataContext();

		SnapshotUtil.restoreToCurrentContexts(initialSnapshot);

		if (GlobalVariables.MULTI_THREADING) {
			try {
				partitioner.first_run();
				if (GlobalVariables.ACTIVE_ROAD_STEPPING) {
					getRoadContext().rebuildActiveRoadsFromState();
				}
				ContextCreator.logger.info("Reset partitioner");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		scheduleEvents();

		logger.info("FAST RESET OK: restored tick-0 state without rebuilding facilities"
				+ " (initTick=" + initTick + ")");
	}

	private static void resetByRebuild() {
		logger.info("Restart the simulation!");
		beginNewRunEpoch();
		
		// 0. Clear scheduled actions and variables
		clearScheduledActions("RESET-SCHED");
		stopAndRemoveDataContext();
		closeAggregateLogger(false);
		travel_demand.close();
		
		mainContext.removeSubContext(cityContext);
		mainContext.removeSubContext(vehicleContext);
		
		// Release stale Road references so query_coSimVehicle cannot return
		// vehicle IDs from the previous run before set_cosim_road is called again.
		coSimRoads.clear();
		
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
		
		recreateAggregateLogger();
		background_traffic = new BackgroundTraffic();
		travel_demand = new TravelDemand();
		bus_schedule = new BusSchedule();
		partitioner = new MetisPartition(GlobalVariables.N_Partition); 
		setWaitNextStepCommand(GlobalVariables.SYNCHRONIZED ? 0 : -1);
		// Clear per-tick idempotency guards on the singleton schedulers so that
		// the first tick of the new run is never treated as a duplicate call
		// from an orphaned scheduled action.
		if (tscheduler != null) {
			tscheduler.resetTickGuards();
		}
		resetScheduledProfiling();
		
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
	public static boolean save(String zipPath) {
		try {
			SnapshotUtil.saveToZip(zipPath);
			return true;
		} catch (Exception e) {
			logger.error("Failed to save simulation state: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}
	
	// The load function: restores simulation from a zip archive
	public static boolean load(String zipPath) {
		return load(zipPath, false);
	}

	public static boolean load(String zipPath, boolean reloadNetwork) {
		try {
			SnapshotUtil.loadFromZip(zipPath, reloadNetwork);
			return true;
		} catch (Exception e) {
			logger.error("Failed to load simulation state: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	public static void restoreForLoadWithoutNetworkRebuild(SnapshotUtil.SimulationSnapshot snapshot, int savedTick) {
		logger.info("Fast-loading simulation state without rebuilding network facilities...");
		beginNewRunEpoch();
		clearScheduledActions("LOAD-FAST-SCHED");
		stopAndRemoveDataContext();
		if (vehicleContext != null) {
			mainContext.removeSubContext(vehicleContext);
		}
		closeAggregateLogger(false);
		if (travel_demand != null) {
			travel_demand.close();
		}

		coSimRoads.clear();

		int currentRepastTick = (int) Math.max(RepastEssentials.GetTickCount(), 0);
		initTick = currentRepastTick;

		recreateAggregateLogger();
		background_traffic = new BackgroundTraffic();
		travel_demand = new TravelDemand();
		BusSchedule.rand_route_only = new Random(GlobalVariables.RandomGenerator.nextInt());
		BusSchedule.route_num = 0;
		bus_schedule = new BusSchedule();
		for (Zone z : getZoneContext().getAll()) {
			z.traversingBusRoutes.clear();
		}
		bus_schedule.postProcessing();

		partitioner = new MetisPartition(GlobalVariables.N_Partition);
		setWaitNextStepCommand(GlobalVariables.SYNCHRONIZED ? 0 : -1);
		if (tscheduler != null) {
			tscheduler.resetTickGuards();
		}
		resetScheduledProfiling();

		vehicleContext = new VehicleContext(true);
		mainContext.addSubContext(vehicleContext);
		createAndStartDataContext();

		SnapshotUtil.restoreToCurrentContexts(snapshot);

		if (GlobalVariables.MULTI_THREADING) {
			try {
				partitioner.first_run();
				if (GlobalVariables.ACTIVE_ROAD_STEPPING) {
					getRoadContext().rebuildActiveRoadsFromState();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		scheduleEvents(backgroundSpeedRefreshDelayForSavedTick(savedTick));
		initTick = currentRepastTick - savedTick;

		logger.info("FAST LOAD OK: restored saved tick " + savedTick
				+ " without rebuilding facilities (initTick=" + initTick + ")");
	}
	
	/**
	 * Rebuild the simulation infrastructure for load.
	 * Similar to reset() but creates a VehicleContext without auto-generating vehicles,
	 * since those will be restored from the snapshot.
	 */
	public static void rebuildForLoad(int savedInitTick, int savedTick) {
		logger.info("Rebuilding simulation infrastructure for load...");
		beginNewRunEpoch();
		
		// Clear scheduled actions (mirror of reset(): track removal so the
		// on-deck-queue leak is visible if deferredLoad ever fails to land in
		// a quiescent state)
		clearScheduledActions("LOAD-SCHED");
		stopAndRemoveDataContext();
		closeAggregateLogger(false);
		travel_demand.close();
		
		mainContext.removeSubContext(cityContext);
		mainContext.removeSubContext(vehicleContext);
		
		// Release stale Road references so query_coSimVehicle cannot return
		// vehicle IDs from the previous run before set_cosim_road is called again.
		coSimRoads.clear();
		
		// Drop the SumoXML singleton (see reset() for rationale): forces a fresh
		// parse so RoadContext/LaneContext/CityContext do NOT reuse facility
		// objects (Road/Lane/Junction/Signal) carrying state from the prior run.
		SumoXML.data = null;
		
		int currentRepastTick = (int) Math.max(RepastEssentials.GetTickCount(), 0);
		
		// Use current Repast tick for scheduling so events start from "now",
		// not from the past. We'll adjust initTick for getCurrentTick() after scheduling.
		initTick = currentRepastTick;
		
		// Reinitialize data structures
		recreateAggregateLogger();
		background_traffic = new BackgroundTraffic();
		travel_demand = new TravelDemand();
		BusSchedule.rand_route_only = new Random(GlobalVariables.RandomGenerator.nextInt());
		BusSchedule.route_num = 0;
		bus_schedule = new BusSchedule();
		partitioner = new MetisPartition(GlobalVariables.N_Partition);
		setWaitNextStepCommand(GlobalVariables.SYNCHRONIZED ? 0 : -1);
		// Clear per-tick idempotency guards on the singleton schedulers so the
		// first tick after load is never treated as a duplicate call from an
		// orphaned scheduled action (defense-in-depth; mirrors reset()).
		if (tscheduler != null) {
			tscheduler.resetTickGuards();
		}
		resetScheduledProfiling();
		
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
		
		// Rebuild data context only when trajectory collection is enabled.
		createAndStartDataContext();
		
		// Initialize operational parameters
		cityContext.modifyRoadNetwork();
		
		if (GlobalVariables.MULTI_THREADING) {
			try {
				partitioner.first_run();
				if (GlobalVariables.ACTIVE_ROAD_STEPPING) {
					getRoadContext().rebuildActiveRoadsFromState();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		// Reschedule events (initTick == currentRepastTick, so events start now)
		scheduleEvents(backgroundSpeedRefreshDelayForSavedTick(savedTick));
		
		// Now set the correct offset so getCurrentTick() returns the saved logical tick
		initTick = currentRepastTick - savedTick;
		
		logger.info("Infrastructure rebuilt. Tick offset set to match saved tick: " + savedTick);
	}
	
	
	// Called by sched.executeEndActions()
	public static void end() {
		logger.info("Finished sim: " + (System.currentTimeMillis() - start_time));
		if (tscheduler != null) {
			tscheduler.shutdownScheduler();
		}
		closeAggregateLogger(true);
		if (dataContext != null) {
			dataContext.stopCollecting();
		}
		if (kafkaManager != null) {
			kafkaManager.close();
		}
		if (travel_demand != null) {
			travel_demand.close();
		}
		// Close the user interface
		System.exit(0);
	}
	
	public void waitForNextStepCommand() {
		// Mark the current tick's LAST_PRIORITY slot as reached. Every other
		// scheduled action for this tick has finished executing and Repast has
		// already put its repeating instance back into the schedule queue, so
		// from this moment until waitNextStepCommand is bumped, the schedule is
		// in a fully quiescent state where removeAction() works reliably.
		synchronized (stepCommandLock) {
			completedTickCount++;
			schedulerAtStepGate = true;
			lastStepGateTick = ContextCreator.getCurrentTick();
			lastStepGateEnterMs = System.currentTimeMillis();
			stepCommandLock.notifyAll();
		}
		long prevTime = -10001; // for the first tick
		while(true) {
			int currentTick;
			synchronized (stepCommandLock) {
				currentTick = ContextCreator.getCurrentTick();
				if (waitNextStepCommand != 0 && currentTick < nextStepTargetTick) {
					waitNextStepCommand = nextStepTargetTick - currentTick;
					schedulerAtStepGate = false;
					lastStepGateReleaseMs = System.currentTimeMillis();
					stepCommandLock.notifyAll();
					return;
				}
				waitNextStepCommand = 0;
				nextStepTargetTick = currentTick;
				stepGateLoopCount++;
				lastStepGateLoopMs = System.currentTimeMillis();
			}
			try{
				Thread.sleep(1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				ContextCreator.logger.warn("waitForNextStepCommand interrupted at tick " + currentTick, e);
				return;
			}
			if ((System.currentTimeMillis()-prevTime)>10000 && connection != null) {
				connection.sendStepMessage(ContextCreator.getCurrentTick());
				prevTime = System.currentTimeMillis();
			}
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
