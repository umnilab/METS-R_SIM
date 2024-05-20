package mets_r;

import java.util.Random;

import mets_r.data.input.NetworkEventObject;

/**
 * Central location for any useful variables (e.g. filenames).
 * 
 * @author Nick Malleson
 * @author Samiul Hasan (SH)
 * @author Xianyuan Zhan
 * @author Xinwu Qian
 * @author Hemant Gehlot
 * Above are the authors for A-RESCUE 1.0 and 2.0
 * @author Zengxiang Lei
 * @author Jiawei Xue
 */

/*
 * How to use Config File (Data.properties in config folder)
 * All the value of global variables are reading from the config file (Data.properties), and all data retrieved from the config file is string type.
 * To add a new global variable: 
 * 1. Define the variable's name and value in the Data.properties
 * 2. Adding corresponding global variable in the GlobalVariables.java
 * e.g.: To add int type new variable "test", first give value for test in Data.properties, as
 * 		 test = 0.5
 * 		 Then, create corresponding declaration for variable test, as
 * 		 public static final int test = Integer.valueOf(loadConfig("test"))
 * The loadConfig method is used to load property's value from the config file, and data type transform is required. Useful transform method is list below:
 * To integer: Integer.valueOf(String s)
 * To Float: Float.valueOf(String s)
 * To Double: Double.valueOf(String s)
 * To Boolean: Boolean.valueOf(String s)
 * to single char: .atChar(0), e.g.: public static final char whosRunning = loadConfig("whosRunning").charAt(0);
 * 
 * Note: 1. The value for each property in Config File should only include numeric data or string, such content of "/", "0.05f", "0.05 " cannot be reconized
 *       2. To add comment in any content, use "#", instead of "//" or "/*...". e.g. : "#Input Files"
 */

import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Properties;

public class GlobalVariables {
	private static Properties config;

	// Loading properties from configuration files, initialized used in ARESCUE
	// credit to Xianyuan Zhan and Christopher Thompson.
	private static String loadConfig(String property) {
		if (config == null) {
			config = new Properties();
			try {
				String working_dir = System.getProperty("user.dir");
				config.load(new FileInputStream(working_dir + "/data/Data.properties"));
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
		return config.getProperty(property);
	}
	
	// Whether the simulation is ran in the synchronized mode
	public static final boolean SYNCHRONIZED = Boolean.valueOf(loadConfig("SYNCHRONIZED"));
	
	// Whether the simulation is ran in the standalone mode
	public static final boolean STANDALONE = Boolean.valueOf(loadConfig("STANDALONE"));
	
	
	// Whether the simulation is ran with V2X enabled
	public static final boolean V2X = Boolean.valueOf(loadConfig("V2X"));
	
	/* Simulation setup */
	public static final int RANDOM_SEED = Integer
			.valueOf(loadConfig("RANDOM_SEED"));
	public static final Random RandomGenerator = new Random(RANDOM_SEED);
	
	public static final float SIMULATION_STEP_SIZE = Float.valueOf(loadConfig("SIMULATION_STEP_SIZE"));
	
	public static final int SIMULATION_ZONE_REFRESH_INTERVAL = (int) (Integer
			.valueOf(loadConfig("SIMULATION_ZONE_REFRESH_INTERVAL"))/SIMULATION_STEP_SIZE);
	public static final int SIMULATION_SIGNAL_REFRESH_INTERVAL = (int) (Integer
			.valueOf(loadConfig("SIMULATION_SIGNAL_REFRESH_INTERVAL"))/SIMULATION_STEP_SIZE);
	public static final int SIMULATION_DEMAND_REFRESH_INTERVAL = (int) (Integer
			.valueOf(loadConfig("SIMULATION_DEMAND_REFRESH_INTERVAL"))/SIMULATION_STEP_SIZE);
	public static final int SIMULATION_SPEED_REFRESH_INTERVAL = (int) (Integer
			.valueOf(loadConfig("SIMULATION_SPEED_REFRESH_INTERVAL"))/SIMULATION_STEP_SIZE);
	public static final int SIMULATION_BUS_REFRESH_INTERVAL = (int) (Integer
			.valueOf(loadConfig("SIMULATION_BUS_REFRESH_INTERVAL"))/SIMULATION_STEP_SIZE);
	public static final int SIMULATION_CHARGING_STATION_REFRESH_INTERVAL = (int) (Integer
			.valueOf(loadConfig("SIMULATION_CHARGING_STATION_REFRESH_INTERVAL"))/SIMULATION_STEP_SIZE);
	public static final int SIMULATION_RH_MATCHING_WINDOW = (int) (Integer
			.valueOf(loadConfig("SIMULATION_RH_MATCHING_WINDOW")) / SIMULATION_STEP_SIZE);
	public static int SIMULATION_RH_MAX_CRUISING_TIME = (int) (Integer
			.valueOf(loadConfig("SIMULATION_RH_MAX_CRUISING_TIME")) / SIMULATION_STEP_SIZE);

	public static final int SIMULATION_STOP_TIME = (int) (60 * Integer.valueOf(loadConfig("SIMULATION_STOP_TIME"))
			/ SIMULATION_STEP_SIZE);
	
	public static final boolean DEMAND_DIFFUSION = Boolean.valueOf(loadConfig("DEMAND_DIFFUSION"));
	
	public static final int HOUR_OF_DEMAND = SIMULATION_STOP_TIME / SIMULATION_DEMAND_REFRESH_INTERVAL;
	public static final int HOUR_OF_SPEED = SIMULATION_STOP_TIME / SIMULATION_SPEED_REFRESH_INTERVAL;
	
	// Road Network
	public static final String ROADS_SHAPEFILE = loadConfig("ROADS_SHAPEFILE");
	public static final String ROADS_CSV = loadConfig("ROADS_CSV");
	public static final String LANES_SHAPEFILE = loadConfig("LANES_SHAPEFILE");
	public static final String LANES_CSV = loadConfig("LANES_CSV");
	public static final String NETWORK_FILE = loadConfig("NETWORK_FILE");
	
	public static final String ZONES_SHAPEFILE = loadConfig("ZONES_SHAPEFILE");
	public static final String ZONE_CSV = loadConfig("ZONE_CSV");
	public static final String CHARGER_SHAPEFILE = loadConfig("CHARGER_SHAPEFILE");
	public static final String CHARGER_CSV = loadConfig("CHARGER_CSV");

	public static double INITIAL_X = Double.valueOf(loadConfig("INITIAL_X"));
	public static double INITIAL_Y = Double.valueOf(loadConfig("INITIAL_Y"));
	
	// Background traffic
	public static final String BT_EVENT_FILE = loadConfig("BT_EVENT_FILE");
	public static final String BT_STD_FILE = loadConfig("BT_STD_FILE");

	// Travel demand
	public static final String EV_DEMAND_FILE = loadConfig("EV_DEMAND_FILE");
	public static final String GV_DEMAND_FILE = loadConfig("GV_DEMAND_FILE");
	public static final String RH_DEMAND_FILE = loadConfig("RH_DEMAND_FILE");
	public static final String RH_WAITING_TIME = loadConfig("RH_WAITING_TIME");
	public static final String RH_SHARE_PERCENTAGE = loadConfig("RH_SHARE_PERCENTAGE");
	public static final boolean RH_DEMAND_SHARABLE = Boolean.valueOf(loadConfig("RH_DEMAND_SHARABLE"));
	public static final double RH_DEMAND_FACTOR = Double.valueOf(loadConfig("RH_DEMAND_FACTOR"));
	
	// Default bus schedule
	public static final String BUS_SCHEDULE = loadConfig("BUS_SCHEDULE");

	// Number of vehicles
	public static int NUM_OF_EV = Integer.valueOf(loadConfig("NUM_OF_EV"));
	public static int NUM_OF_BUS = Integer.valueOf(loadConfig("NUM_OF_BUS"));
	
	// EV batteries
	public static int EV_BATTERY = Integer.valueOf(loadConfig("EV_BATTERY"));
	public static int BUS_BATTERY = Integer.valueOf(loadConfig("BUS_BATTERY"));

	// Event file, placeholder for future extension
	public static final String EVENT_FILE = loadConfig("EVENT_FILE");
	public static final int EVENT_CHECK_FREQUENCY = Integer.valueOf(loadConfig("EVENT_CHECK_FREQUENCY"));
	

	/* Operation Options */
	public static final boolean K_SHORTEST_PATH = Boolean.valueOf(loadConfig("K_SHORTEST_PATH"));
	public static final boolean COLLABORATIVE_EV = Boolean.valueOf(loadConfig("COLLABORATIVE_EV"));
	public static final boolean BUS_PLANNING = Boolean.valueOf(loadConfig("BUS_PLANNING"));
	public static final boolean PROACTIVE_RELOCATION = Boolean.valueOf(loadConfig("PROACTIVE_RELOCATION"));

	// Eco-routing Parameters
	public static final boolean ENABLE_ECO_ROUTING_EV = Boolean.valueOf(loadConfig("ECO_ROUTING_EV"));
	public static final boolean ENABLE_ECO_ROUTING_BUS = Boolean.valueOf(loadConfig("ECO_ROUTING_BUS"));
	public static int NUM_CANDIDATE_ROUTES = Integer.valueOf(loadConfig("NUM_CANDIDATE_ROUTES"));

	// Vehicle charging
	public static final boolean PROACTIVE_CHARGING = Boolean.valueOf(loadConfig("PROACTIVE_CHARGING"));
	public static final double RECHARGE_LEVEL_LOW = Double.valueOf(loadConfig("RECHARGE_LEVEL_LOW"));
	public static final double RECHARGE_LEVEL_HIGH = Double.valueOf(loadConfig("RECHARGE_LEVEL_HIGH"));
	public static final double BUS_RECHARGE_LEVEL_LOW = Double.valueOf(loadConfig("BUS_RECHARGE_LEVEL_LOW"));
	public static final double BUS_RECHARGE_LEVEL_HIGH = Double.valueOf(loadConfig("BUS_RECHARGE_LEVEL_HIGH"));
	
	
	/* Network Partitioning */
	public static final boolean MULTI_THREADING = Boolean.valueOf(loadConfig("MULTI_THREADING"));
	// Load the number of partitions from the config file
	public static final int N_Partition = Integer.valueOf(loadConfig("N_PARTITION"));
	public static final int N_THREADS = Integer.valueOf(loadConfig("N_THREADS"));
	public static final int SIMULATION_NETWORK_REFRESH_INTERVAL = Integer
			.valueOf(loadConfig("SIMULATION_NETWORK_REFRESH_INTERVAL"));
	public static final int SIMULATION_PARTITION_REFRESH_INTERVAL = Integer
			.valueOf(loadConfig("SIMULATION_PARTITION_REFRESH_INTERVAL"));
	// Maximum network partitioning interval
	public static final int SIMULATION_MAX_PARTITION_REFRESH_INTERVAL = Integer
			.valueOf(loadConfig("SIMULATION_MAX_PARTITION_REFRESH_INTERVAL"));
	// Threshold amount of vehicles that requires more frequent network partitioning
	public static final int THRESHOLD_VEHICLE_NUMBER = Integer.valueOf(loadConfig("THRESHOLD_VEHICLE_NUMBER"));
	
	/* Data collection */
	public static final boolean ENABLE_DATA_COLLECTION = Boolean.valueOf(loadConfig("ENABLE_DATA_COLLECTION"));
	public static final boolean DEBUG_DATA_BUFFER = Boolean.valueOf(loadConfig("DEBUG_DATA_BUFFER"));
	public static final int DATA_CLEANUP_REFRESH = Integer.valueOf(loadConfig("DATA_CLEANUP_REFRESH"));

	// Parameters for the JSON output file writer (similar to as the csv parameters
	// except JSON_TICK_LIMIT_PER_FILE which represents the number of ticks are
	// written in a json file)
	public static final boolean ENABLE_JSON_WRITE = Boolean.valueOf(loadConfig("ENABLE_JSON_WRITE"));
	public static final String JSON_DEFAULT_FILENAME = loadConfig("JSON_DEFAULT_FILENAME");
	public static final String JSON_DEFAULT_EXTENSION = loadConfig("JSON_DEFAULT_EXTENSION");
	public static final String JSON_DEFAULT_PATH = loadConfig("JSON_DEFAULT_PATH");
	public static final int JSON_TICKS_BETWEEN_TWO_RECORDS = Integer
			.valueOf(loadConfig("JSON_TICKS_BETWEEN_TWO_RECORDS"));
	public static final int JSON_FREQ_RECORD_LINK_SNAPSHOT = Integer
			.valueOf(loadConfig("JSON_FREQ_RECORD_LINK_SNAPSHOT"));
	public static final int JSON_BUFFER_REFRESH = Integer.valueOf(loadConfig("JSON_BUFFER_REFRESH"));
	public static final int JSON_TICK_LIMIT_PER_FILE = Integer.valueOf(loadConfig("JSON_TICK_LIMIT_PER_FILE"));
	
	// Parameters for aggregated report writer
	public static final String AGG_DEFAULT_PATH = loadConfig("AGG_DEFAULT_PATH");

	// Parameters for handling network connections to remote programs
	public static final boolean DEBUG_NETWORK = Boolean.valueOf(loadConfig("DEBUG_NETWORK"));
	public static final int NETWORK_BUFFER_RERESH = Integer.valueOf(loadConfig("NETWORK_BUFFER_REFRESH"));
	public static final int NETWORK_STATUS_REFRESH = Integer.valueOf(loadConfig("NETWORK_STATUS_REFRESH"));
	public static final int NETWORK_LISTEN_PORT = Integer.valueOf(loadConfig("NETWORK_LISTEN_PORT"));
	public static final int NETWORK_MAX_MESSAGE_SIZE = Integer.valueOf(loadConfig("NETWORK_MAX_MESSAGE_SIZE"));
		
	// Displaying useful metrics
	public static final boolean ENABLE_METRICS_DISPLAY = Boolean.valueOf(loadConfig("ENABLE_METRICS_DISPLAY"));
	public static final int METRICS_DISPLAY_INTERVAL = Integer.valueOf(loadConfig("METRICS_DISPLAY_INTERVAL"));
	
	/* Constants */
	// For primitive move
    public static final double TRAVEL_PER_TURN = Double.valueOf(loadConfig("TRAVEL_PER_TURN"));
	
	// For searching nearby facilities
	public static final double SEARCHING_BUFFER = Double.valueOf(loadConfig("SEARCHING_BUFFER")); 
	// For cruising nearby links
	public static final double CRUISING_BUFFER = Double.valueOf(loadConfig("CRUISING_BUFFER")); 
    
	// Car following status
	public static final int STATUS_REGIME_FREEFLOWING = 0x00000000; // 0
	public static final int STATUS_REGIME_CARFOLLOWING = 0x00000080; // 128
	public static final int STATUS_REGIME_EMERGENCY = 0x00000100; // 256
	
	// For car following and lane changing
	public static final float ALPHA_DEC = Float.valueOf(loadConfig("ALPHA_DEC"));
	public static final float BETA_DEC = Float.valueOf(loadConfig("BETA_DEC"));
	public static final float GAMMA_DEC = Float.valueOf(loadConfig("GAMMA_DEC"));
	public static final float ALPHA_ACC = Float.valueOf(loadConfig("ALPHA_ACC"));
	public static final float BETA_ACC = Float.valueOf(loadConfig("BETA_ACC"));
	public static final float GAMMA_ACC = Float.valueOf(loadConfig("GAMMA_ACC"));
	
	// For K_SHORTEST_PATH
	public static final int K_VALUE = Integer.valueOf(loadConfig("K_VALUE"));
	public static final double THETA_LOGIT = Double.valueOf(loadConfig("THETA_LOGIT"));
	
	// For global variables of the adaptive network weighting
	public static final int PART_ALPHA = Integer.valueOf(loadConfig("PART_ALPHA"));
	public static final int PART_BETA = Integer.valueOf(loadConfig("PART_BETA"));
	public static final int PART_GAMMA = Integer.valueOf(loadConfig("PART_GAMMA"));
	
	// Number of times that the partition interval is larger than the network
	// refresh interval
	public static final int PART_REFRESH_MULTIPLIER = (int) (SIMULATION_PARTITION_REFRESH_INTERVAL
			/ SIMULATION_NETWORK_REFRESH_INTERVAL);
	public static final boolean SIMULATION_MULTIPLE_DEMAND_INPUTS = Boolean
			.valueOf(loadConfig("SIMULATION_MULTIPLE_DEMAND_INPUTS"));// If this is true then we input multiple demand
																		// files for batch runs, else we run single
	// Number of future road segments to be considered in counting shadow vehicles
	public static final int N_SHADOW = Integer.valueOf(loadConfig("N_SHADOW"));
	
	// For microscopic vehicle movement
	public static final double MIN_LEAD = Float.valueOf(loadConfig("MIN_LEAD"));; // (m/sec)
	public static final double MIN_LAG = Float.valueOf(loadConfig("MIN_LAG"));; // (m/sec)
	public static final float DEFAULT_VEHICLE_WIDTH = Float.valueOf(loadConfig("DEFAULT_VEHICLE_WIDTH")); // meters
	public static final float DEFAULT_VEHICLE_LENGTH = Float.valueOf(loadConfig("DEFAULT_VEHICLE_LENGTH")); // meters
	public static final float NO_LANECHANGING_LENGTH = Float.valueOf(loadConfig("NO_LANECHANGING_LENGTH")); // meters
	public static final float LANE_WIDTH = Float.valueOf(loadConfig("LANE_WIDTH"));
	public static final float LANE_CHANGING_PROB_PART1 = Float.valueOf(loadConfig("LANE_CHANGING_PROB_PART1"));
	public static final float LANE_CHANGING_PROB_PART2 = Float.valueOf(loadConfig("LANE_CHANGING_PROB_PART1"));
	public static final float H_UPPER = Float.valueOf(loadConfig("H_UPPER"));
	public static final float H_LOWER = Float.valueOf(loadConfig("H_LOWER"));
	public static final double FLT_INF = Float.MAX_VALUE;
	public static final double FLT_EPSILON = 1.0 / FLT_INF;
	
	public static final double STREET_SPEED = Float.valueOf(loadConfig("STREET_SPEED")); // mph
	public static final double HIGHWAY_SPEED = Float.valueOf(loadConfig("HIGHWAY_SPEED")); // mph
	public static final double BRIDGE_SPEED = Float.valueOf(loadConfig("BRIDGE_SPEED")); // mph
	public static final double TUNNEL_SPEED = Float.valueOf(loadConfig("TUNNEL_SPEED")); // mph
	public static final double DRIVEWAY_SPEED = Float.valueOf(loadConfig("DRIVEWAY_SPEED")); // mph
	public static final double RAMP_SPEED = Float.valueOf(loadConfig("RAMP_SPEED")); // mph
	public static final double UTURN_SPEED = Float.valueOf(loadConfig("UTURN_SPEED")); // mph
	
	// Parameters for MLC
	public static final double betaLeadMLC01 = 0.05;
	public static final double betaLeadMLC02 = 0.15;
	public static final double betaLagMLC01 = 0.15;
	public static final double betaLagMLC02 = 0.40;
	public static final double gama = 2.5e-5;
	public static final double critDisFraction = 0.6;
	
	// Parameters for DLC
	public static final double betaLeadDLC01 = 0.05;
	public static final double betaLeadDLC02 = 0.15;
	public static final double betaLagDLC01 = 0.15;
	public static final double betaLagDLC02 = 0.40;
	public static final double minLeadDLC = 0.05;
	public static final double minLagDLC = 0.05;
	
	/* DB storage, currently is a placeholder for future extention */
	public static final String DB_URL = String.valueOf(loadConfig("DB_URL"));
	// Hemant Gehlot: Parameter to determine how frequently (in terms of ticks) we
	// should separately record the snapshot (for visualization interpolation
	// purposes) of each vehicle in the network irrespective of whether it moves or
	// not.
	// Note that this is independent of the snapshot that is getting collected from
	// the movement of vehicles. If a vehicle moves in a tick and it happens that in
	// this
	// tick we had also recorded the snapshot for visualization purposes before then
	// finally the snapshot corresponding to the movement will have priority and it
	// will
	// override the snapshot written for visualization purposes (everything will be
	// overwritten except the coordinated for previous epoch when same recording was
	// done).
	// This variable is also useful for sending the data at this frequency.
	public static double datacollection_start = 0.0;
	public static double datacollection_total = 0.0;
	public static final double BLOCKAGE_SPEED_FOREVENTS = 2.0; // In miles per hour
	public static LinkedList<NetworkEventObject> newEventQueue = new LinkedList<NetworkEventObject>();// Global queue
																										// for storing	
	// Parameters for mode split
	public static final double BUS_TICKET_PRICE = Double.valueOf(loadConfig("BUS_TICKET_PRICE"));
	public static final double MS_ALPHA = Double.valueOf(loadConfig("MS_ALPHA"));
	public static final double MS_BETA = Double.valueOf(loadConfig("MS_BETA"));
	public static final double BASE_PRICE_TAXI = Double.valueOf(loadConfig("BASE_PRICE_TAXI"));
	public static final double INITIAL_PRICE_TAXI = Double.valueOf(loadConfig("INITIAL_PRICE_TAXI"));
	public static final double TAXI_BASE = Double.valueOf(loadConfig("TAXI_BASE"));
	public static final double BUS_BASE = Double.valueOf(loadConfig("BUS_BASE"));
	
	// Addressing the gridlock in the parallel mode
	public static final int MAX_STUCK_TIME = (int) (Integer.valueOf(loadConfig("MAX_STUCK_TIME"))/SIMULATION_STEP_SIZE);
}
