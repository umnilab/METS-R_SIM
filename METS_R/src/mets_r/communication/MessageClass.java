package mets_r.communication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class MessageClass{
	class VehIDVehType {
	    int vehID;
	    boolean vehType;
	
	    // Constructor
	    public VehIDVehType(int vehID, boolean vehType) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	    }
	}
	
	class VehIDVehTypeTran {
	    int vehID;
	    boolean vehType;
	    boolean transformCoord;
	
	    // Constructor
	    public VehIDVehTypeTran(int vehID, boolean vehType, boolean transformCoord) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.transformCoord = transformCoord;
	    }
	}
	
	class VehIDVehTypeSensorType {
	    int vehID;
	    boolean vehType;
	    int sensorType;
	
	    // Constructor
	    public VehIDVehTypeSensorType(int vehID, boolean vehType, int sensorType) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.sensorType = sensorType;
	    }
	}
	
	class VehIDVehTypeAcc {
	    int vehID;
	    boolean vehType;
	    double acc;
	
	    // Constructor
	    public VehIDVehTypeAcc(int vehID, boolean vehType, double acc) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.acc = acc;
	    }
	}
	
	class VehIDVehTypeTranXY {
	    int vehID;
	    boolean vehType;
	    boolean transformCoord;
	    double x;
	    double y;
	    double z = 0.0;

	    // Constructor
	    public VehIDVehTypeTranXY(int vehID, boolean vehType, boolean transformCoord, 
	                                          double x, double y, double z) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.transformCoord = transformCoord;
	        this.x = x;
	        this.y = y;
	        this.z = z;
	    }
	}
	
	class VehIDVehTypeTranBearingXYSpeed {
	    int vehID;
	    boolean vehType;
	    boolean transformCoord;
	    double bearing;
	    double x;
	    double y;
	    double z = 0.0;
	    double speed;

	    // Constructor
	    public VehIDVehTypeTranBearingXYSpeed(int vehID, boolean vehType, boolean transformCoord, 
	                                         double bearing, double x, double y, double z, double speed) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.transformCoord = transformCoord;
	        this.bearing = bearing;
	        this.x = x;
	        this.y = y;
	        this.z = z;
	        this.speed = speed;
	    }
	}
	
	class VehIDVehTypeRoad {
	    int vehID;
	    boolean vehType;
	    String roadID;

	    // Constructor
	    public VehIDVehTypeRoad(int vehID, boolean vehType, String roadID) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.roadID = roadID;
	    }
	}
	
	class VehIDVehTypeRoadLaneDist {
	    int vehID;
	    boolean vehType;
	    String roadID;
	    int laneID;
	    double dist;

	    // Constructor
	    public VehIDVehTypeRoadLaneDist(int vehID, boolean vehType, String roadID, int laneID, double dist) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.roadID = roadID;
	        this.laneID = laneID;
	        this.dist = dist;
	    }
	}
	
    class VehIDOrigDestNum{
    	int vehID;
    	int orig;
    	int dest;
    	int num;
    	
    	// Constructor
    	public VehIDOrigDestNum(int vehID, int orig, int dest, int num) {
    		this.vehID = vehID;
    		this.orig = orig;
    		this.dest = dest;
    		this.num = num;
    	}
    }
    
    class VehIDOrigRoadDestRoadNum{
    	int vehID;
    	String orig;
    	String dest;
    	int num;
    	
    	// Constructor
    	public VehIDOrigRoadDestRoadNum(int vehID, String orig, String dest, int num) {
    		this.vehID = vehID;
    		this.orig = orig;
    		this.dest = dest;
    		this.num = num;
    	}
    }
    
    // For dispatchTaxi / dispTaxiBwRoads: pair an available taxi with an
    // already-pending request (added via addTaxiRequests or addTaxiReqBwRoads).
    class VehIDReqID{
    	int vehID;
    	int reqID;
    	
    	public VehIDReqID(int vehID, int reqID) {
    		this.vehID = vehID;
    		this.reqID = reqID;
    	}
    }
    
    // For repositionTaxi: send an idle/cruising taxi to a destination zone.
    class VehIDZoneID{
    	int vehID;
    	int zoneID;
    	
    	public VehIDZoneID(int vehID, int zoneID) {
    		this.vehID = vehID;
    		this.zoneID = zoneID;
    	}
    }
    
    /** Taxi request payload for {@code addTaxiRequests}. */
    class OriginDestNumMaxW{
        int zoneID;
        int dest;
        int num;
        int maxWaitingTime;

        public OriginDestNumMaxW(int zoneID, int dest, int num, int maxWaitingTime) {
            this.zoneID = zoneID;
            this.dest = dest;
            this.num = num;
            this.maxWaitingTime = maxWaitingTime;
        }
    }

    
    class OrigRoadDestRoadNumMaxW{
    	String orig;
    	String dest;
    	int num;
    	int maxWaitingTime;
    	
    	// Constructor
    	public OrigRoadDestRoadNumMaxW(String orig, String dest, int num, int maxWaitingTime) {
    		this.orig = orig;
    		this.dest = dest;
    		this.num = num;
    		this.maxWaitingTime = maxWaitingTime;
    	}
    }
    
    class OrigRoadDestRoad {
        String orig;
        String dest;

        // Constructor
        public OrigRoadDestRoad(String orig, String dest) {
            this.orig = orig;
            this.dest = dest;
        }
    }
    
    class OrigRoadDestRoadK {
        String orig;
        String dest;
        int K;

        // Constructor
        public OrigRoadDestRoadK(String orig, String dest, int K) {
            this.orig = orig;
            this.dest = dest;
            this.K = K;
        }
    }
    
    class OriginCoordDestCoordTransform {
        double origX;
        double origY;
        double origZ = 0.0;
        double destX;
        double destY;
        double destZ = 0.0;
        boolean transformCoord;

        // Constructor
        public OriginCoordDestCoordTransform(double origX, double origY, double origZ,
                                             double destX, double destY, double destZ,
                                             boolean transformCoord) {
            this.origX = origX;
            this.origY = origY;
            this.origZ = origZ;
            this.destX = destX;
            this.destY = destY;
            this.destZ = destZ;
            this.transformCoord = transformCoord;
        }
    }
    
    class OriginCoordDestCoordTransformK {
        double origX;
        double origY;
        double origZ = 0.0;
        double destX;
        double destY;
        double destZ = 0.0;
        boolean transformCoord;
        int K;

        // Constructor
        public OriginCoordDestCoordTransformK(double origX, double origY, double origZ,
                                              double destX, double destY, double destZ,
                                              boolean transformCoord, int K) {
            this.origX = origX;
            this.origY = origY;
            this.origZ = origZ;
            this.destX = destX;
            this.destY = destY;
            this.destZ = destZ;
            this.transformCoord = transformCoord;
            this.K = K;
        }
    }
    
    class VehIDVehTypeRoute{
    	int vehID;
	    boolean vehType;
	    List<String> route;
	
	    // Constructor
	    public VehIDVehTypeRoute(int vehID, boolean vehType, List<String> route) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.route = route;
	    }
    }
    
    class RoadIDWeight{
    	String roadID;
        double weight;
        
        // Constructor
	    public RoadIDWeight(String roadID, double weight) {
	        this.roadID = roadID;
	        this.weight = weight;
	    }
    }
    
    class RoadIDLaneIndexTransform{
    	String roadID;
        int laneIndex;
        boolean transformCoord;
        
        // Constructor
	    public RoadIDLaneIndexTransform(String roadID, int laneIndex, boolean transformCoord) {
	        this.roadID = roadID;
	        this.laneIndex = laneIndex;
	        this.transformCoord = transformCoord;
	    }
    }
    
    class RouteNameZonesRoadsPath{
    	String routeName;
    	ArrayList<Integer> zones;
    	ArrayList<String> roads;
    	ArrayList<List<String>> paths;
    	
    	// Constructor
    	public RouteNameZonesRoadsPath(String routeName, ArrayList<Integer> zones, ArrayList<String> roads, ArrayList<List<String>> paths) {
    		this.routeName = routeName;
    		this.zones = zones;
    		this.roads = roads;
    		this.paths = paths;
    	}
    }
    
    class RouteNameZonesRoads{
    	String routeName;
    	ArrayList<Integer> zones;
    	ArrayList<String> roads;
    	
    	// Constructor
    	public RouteNameZonesRoads(String routeName, ArrayList<Integer> zones, ArrayList<String> roads) {
    		this.routeName = routeName;
    		this.zones = zones;
    		this.roads = roads;
    	}
    }
    
    class BusIDRouteNameZoneRoadStopIndex{
    	int busID;
    	String routeName;
    	int zone;
    	String road;
    	int stopIndex;
    	
    	// Constructor
    	public BusIDRouteNameZoneRoadStopIndex(int busID, String routeName, int zone, String road, int stopIndex) {
    		this.routeName = routeName;
    		this.zone = zone;
    		this.road = road;
    		this.stopIndex = stopIndex;
    	}
    }
    
    class BusIDRouteNameStopIndex{
    	int busID;
    	String routeName;
    	int stopIndex;
    	
    	// Constructor
    	public BusIDRouteNameStopIndex(int busID, String routeName, int stopIndex) {
    		this.routeName = routeName;
    		this.stopIndex = stopIndex;
    	}
    }
    
    class RouteNameDepartTime{
    	String routeName;
    	ArrayList<Integer> departTime;
    	
    	// Constructor
    	public RouteNameDepartTime(String routeName, ArrayList<Integer> departTime) {
    		this.routeName = routeName;
    		this.departTime = departTime;
    	}
    }
    
    class ChargerIDChargerTypeWeight{
    	int chargerID;
    	int chargerType;
        double weight;
        
        // Constructor
	    public ChargerIDChargerTypeWeight(int chargerID, int chargerType, double weight) {
	        this.chargerID = chargerID;
	        this.chargerType = chargerType;
	        this.weight = weight;
	    }
    }
    
    // Message class for querying signal by road connection (upstream road -> downstream road)
    class UpStreamRoadDownStreamRoad {
        String upStreamRoad;
        String downStreamRoad;

        // Constructor
        public UpStreamRoadDownStreamRoad(String upStreamRoad, String downStreamRoad) {
            this.upStreamRoad = upStreamRoad;
            this.downStreamRoad = downStreamRoad;
        }
    }
    
    // Message class for updating signal phase
    // signalID: the ID of the signal
    // targetPhase: 0 (Green), 1 (Yellow), 2 (Red)
    // phaseTime: time offset in seconds from the start of the phase (optional, default 0)
    class SignalIDPhase {
        int signalID;
        int targetPhase;
        int phaseTime; // optional, defaults to 0 if not provided

        // Constructor
        public SignalIDPhase(int signalID, int targetPhase, int phaseTime) {
            this.signalID = signalID;
            this.targetPhase = targetPhase;
            this.phaseTime = phaseTime;
        }
    }
    
    // Message class for updating signal phase timing
    // signalID: the ID of the signal
    // greenTime, yellowTime, redTime: duration in seconds for each phase
    class SignalIDPhaseTiming {
        int signalID;
        int greenTime;
        int yellowTime;
        int redTime;

        // Constructor
        public SignalIDPhaseTiming(int signalID, int greenTime, int yellowTime, int redTime) {
            this.signalID = signalID;
            this.greenTime = greenTime;
            this.yellowTime = yellowTime;
            this.redTime = redTime;
        }
    }
    
    // Message class for setting a complete phase plan (phase timing + starting state)
    // signalID: the ID of the signal
    // greenTime, yellowTime, redTime: duration in seconds for each phase
    // startPhase: the phase to start from (0=Green, 1=Yellow, 2=Red)
    // phaseOffset: time offset in seconds from the start of the startPhase (optional, defaults to 0)
    class SignalPhasePlan {
        int signalID;
        int greenTime;
        int yellowTime;
        int redTime;
        int startPhase;
        int phaseOffset; // optional, defaults to 0

        // Constructor
        public SignalPhasePlan(int signalID, int greenTime, int yellowTime, int redTime, int startPhase, int phaseOffset) {
            this.signalID = signalID;
            this.greenTime = greenTime;
            this.yellowTime = yellowTime;
            this.redTime = redTime;
            this.startPhase = startPhase;
            this.phaseOffset = phaseOffset;
        }
    }
    
    // Message class for setting phase plan with tick-level precision
    // signalID: the ID of the signal
    // greenTicks, yellowTicks, redTicks: duration in simulation ticks for each phase
    // startPhase: the phase to start from (0=Green, 1=Yellow, 2=Red)
    // tickOffset: tick offset from the start of the startPhase (optional, defaults to 0)
    class SignalPhasePlanTicks {
        int signalID;
        int greenTicks;
        int yellowTicks;
        int redTicks;
        int startPhase;
        int tickOffset; // optional, defaults to 0

        // Constructor
        public SignalPhasePlanTicks(int signalID, int greenTicks, int yellowTicks, int redTicks, int startPhase, int tickOffset) {
            this.signalID = signalID;
            this.greenTicks = greenTicks;
            this.yellowTicks = yellowTicks;
            this.redTicks = redTicks;
            this.startPhase = startPhase;
            this.tickOffset = tickOffset;
        }
    }
	
    class ZoneParams {
    	double x;
    	double y;
    	double z = 0.0;
    	boolean transformCoord;
    	int capacity;
    	int type;

    	public ZoneParams(double x, double y, double z, boolean transformCoord, int capacity, int type) {
    		this.x = x;
    		this.y = y;
    		this.z = z;
    		this.transformCoord = transformCoord;
    		this.capacity = capacity;
    		this.type = type;
    	}
    }

    class ChargingStationParams {
    	double x;
    	double y;
    	double z = 0.0;
    	boolean transformCoord;
    	int numL2;
    	int numL3;
    	int numBus;
    	double priceL2;
    	double priceL3;

    	public ChargingStationParams(double x, double y, double z, boolean transformCoord, int numL2, int numL3, int numBus, double priceL2, double priceL3) {
    		this.x = x;
    		this.y = y;
    		this.z = z;
    		this.transformCoord = transformCoord;
    		this.numL2 = numL2;
    		this.numL3 = numL3;
    		this.numBus = numBus;
    		this.priceL2 = priceL2;
    		this.priceL3 = priceL3;
    	}
    }

    class AddTaxiToZone {
    	int zoneID;
    	int num;

    	public AddTaxiToZone(int zoneID, int num) {
    		this.zoneID = zoneID;
    		this.num = num;
    	}
    }

    class RouteNameNum {
    	String routeName;
    	int num;

    	public RouteNameNum(String routeName, int num) {
    		this.routeName = routeName;
    		this.num = num;
    	}
    }

    // vehType: true = private vehicle (EV/GV), false = public vehicle (taxi)
    // chargerType: 0 = L2, 1 = L3, 2 = BUS
    // csID: 0 = auto-select nearest/cheapest; negative integer = specific charging station ID
    class VehIDVehTypeChargerTypeCSID {
    	int vehID;
    	boolean vehType;
    	int chargerType;
    	int csID;

    	public VehIDVehTypeChargerTypeCSID(int vehID, boolean vehType, int chargerType, int csID) {
    		this.vehID = vehID;
    		this.vehType = vehType;
    		this.chargerType = chargerType;
    		this.csID = csID;
    	}
    }

	public static void main(String[] args) {
		Gson gson = new Gson();
		// IDs
		// Serialize and deserialize a collection of Integers
        Collection<Integer> IDs = Arrays.asList(1, 2, 3, 4, 5);
        String json = gson.toJson(IDs);  // Serialize to JSON
        System.out.println("Serialized IDs: " + json);
        
        // Deserialize back to collection
        TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
        Collection<Integer> IDs2 = gson.fromJson(json, collectionType.getType());
        System.out.println("Deserialized IDs: " + IDs2);

        // Create an instance of the outer class to access non-static inner classes
        MessageClass messageClass = new MessageClass();

        // Serialize and deserialize a collection of VehIDVehType objects
        Collection<VehIDVehType> vehIDVehTypes = new ArrayList<>();
        vehIDVehTypes.add(messageClass.new VehIDVehType(0, true));
        vehIDVehTypes.add(messageClass.new VehIDVehType(1, false));
        vehIDVehTypes.add(messageClass.new VehIDVehType(2, true));

        json = gson.toJson(vehIDVehTypes);  // Serialize to JSON
        System.out.println("Serialized VehIDVehTypes: " + json);

        TypeToken<Collection<VehIDVehType>> collectionType2 = new TypeToken<Collection<VehIDVehType>>() {};
        Collection<VehIDVehType> vehIDVehTypes2 = gson.fromJson(json, collectionType2.getType());  // Deserialize
        System.out.println("Deserialized VehIDVehTypes: " + vehIDVehTypes2);

        // Serialize and deserialize a collection of VehIDVehTypeTrans objects
        Collection<VehIDVehTypeTran> vehIDVehTypeTrans = new ArrayList<>();
        vehIDVehTypeTrans.add(messageClass.new VehIDVehTypeTran(0, true, true));
        vehIDVehTypeTrans.add(messageClass.new VehIDVehTypeTran(1, false, false));

        json = gson.toJson(vehIDVehTypeTrans);  // Serialize to JSON
        System.out.println("Serialized VehIDVehTypeTrans: " + json);

        TypeToken<Collection<VehIDVehTypeTran>> collectionType3 = new TypeToken<Collection<VehIDVehTypeTran>>() {};
        Collection<VehIDVehTypeTran> vehIDVehTypeTrans2 = gson.fromJson(json, collectionType3.getType());  // Deserialize
        System.out.println("Deserialized VehIDVehTypeTrans: " + vehIDVehTypeTrans2);

        // Serialize and deserialize a collection of VehIDVehTypeAcc objects
        Collection<VehIDVehTypeAcc> vehIDVehTypeAcc = new ArrayList<>();
        vehIDVehTypeAcc.add(messageClass.new VehIDVehTypeAcc(0, true, 9.8));
        vehIDVehTypeAcc.add(messageClass.new VehIDVehTypeAcc(1, false, 12.4));

        json = gson.toJson(vehIDVehTypeAcc);  // Serialize to JSON
        System.out.println("Serialized VehIDVehTypeAcc: " + json);

        TypeToken<Collection<VehIDVehTypeAcc>> collectionType4 = new TypeToken<Collection<VehIDVehTypeAcc>>() {};
        Collection<VehIDVehTypeAcc> vehIDVehTypeAcc2 = gson.fromJson(json, collectionType4.getType());  // Deserialize
        System.out.println("Deserialized VehIDVehTypeAcc: " + vehIDVehTypeAcc2);
        
        // VehIDVehTypeSensorType
        Collection<VehIDVehTypeSensorType> vehIDVehTypeSensorTypes = new ArrayList<>();
        vehIDVehTypeSensorTypes.add(messageClass.new VehIDVehTypeSensorType(0, true, 1));
        vehIDVehTypeSensorTypes.add(messageClass.new VehIDVehTypeSensorType(1, false, 2));

        json = gson.toJson(vehIDVehTypeSensorTypes);
        System.out.println("Serialized VehIDVehTypeSensorTypes: " + json);

        TypeToken<Collection<VehIDVehTypeSensorType>> collectionType5 = new TypeToken<Collection<VehIDVehTypeSensorType>>() {};
        Collection<VehIDVehTypeSensorType> vehIDVehTypeSensorTypes2 = gson.fromJson(json, collectionType5.getType());
        System.out.println("Deserialized VehIDVehTypeSensorTypes: " + vehIDVehTypeSensorTypes2);

        
        // VehIDVehTypeTranRoadIDXY
        Collection<VehIDVehTypeTranXY> vehIDVehTypeTranXYs = new ArrayList<>();
        vehIDVehTypeTranXYs.add(messageClass.new VehIDVehTypeTranXY(0, true, true, 12.34, 56.78, 0.0));
        vehIDVehTypeTranXYs.add(messageClass.new VehIDVehTypeTranXY(1, false, false, 90.12, 34.56, 0.0));

        json = gson.toJson(vehIDVehTypeTranXYs);
        System.out.println("Serialized VehIDVehTypeTranRoadIDXY: " + json);

        TypeToken<Collection<VehIDVehTypeTranXY>> collectionType6 = new TypeToken<Collection<VehIDVehTypeTranXY>>() {};
        Collection<VehIDVehTypeTranXY> vehIDVehTypeTranXYs2 = gson.fromJson(json, collectionType6.getType());
        System.out.println("Deserialized VehIDVehTypeTranRoadIDXY: " + vehIDVehTypeTranXYs2);

        // VehIDOrigDestNum
        Collection<VehIDOrigDestNum> vehIDOrigDestNums = new ArrayList<>();
        vehIDOrigDestNums.add(messageClass.new VehIDOrigDestNum(0, 100, 200, 10));
        vehIDOrigDestNums.add(messageClass.new VehIDOrigDestNum(1, 101, 201, 20));

        json = gson.toJson(vehIDOrigDestNums);
        System.out.println("Serialized VehIDOrigDestNum: " + json);

        TypeToken<Collection<VehIDOrigDestNum>> collectionType7 = new TypeToken<Collection<VehIDOrigDestNum>>() {};
        Collection<VehIDOrigDestNum> vehIDOrigDestNums2 = gson.fromJson(json, collectionType7.getType());
        System.out.println("Deserialized VehIDOrigDestNum: " + vehIDOrigDestNums2);

        // VehIDOrigRoadDestRoadNum
        Collection<VehIDOrigRoadDestRoadNum> vehIDOrigRoadDestRoadNums = new ArrayList<>();
        vehIDOrigRoadDestRoadNums.add(messageClass.new VehIDOrigRoadDestRoadNum(0, "A", "B", 5));
        vehIDOrigRoadDestRoadNums.add(messageClass.new VehIDOrigRoadDestRoadNum(1, "C", "D", 15));

        json = gson.toJson(vehIDOrigRoadDestRoadNums);
        System.out.println("Serialized VehIDOrigRoadDestRoadNum: " + json);

        TypeToken<Collection<VehIDOrigRoadDestRoadNum>> collectionType8 = new TypeToken<Collection<VehIDOrigRoadDestRoadNum>>() {};
        Collection<VehIDOrigRoadDestRoadNum> vehIDOrigRoadDestRoadNums2 = gson.fromJson(json, collectionType8.getType());
        System.out.println("Deserialized VehIDOrigRoadDestRoadNum: " + vehIDOrigRoadDestRoadNums2);
        
        // OrigRoadDestRoad
        Collection<OrigRoadDestRoad> origRoadDestRoadList = new ArrayList<OrigRoadDestRoad>();
        origRoadDestRoadList.add(messageClass.new OrigRoadDestRoad("Avenue1", "Boulevard1"));
        origRoadDestRoadList.add(messageClass.new OrigRoadDestRoad("StreetX", "StreetY"));

        json = gson.toJson(origRoadDestRoadList);
        System.out.println("Serialized OrigRoadDestRoad: " + json);

        TypeToken<Collection<OrigRoadDestRoad>> collectionType11 = new TypeToken<Collection<OrigRoadDestRoad>>() {};
        Collection<OrigRoadDestRoad> origRoadDestRoadList2 = gson.fromJson(json, collectionType11.getType());
        System.out.println("Deserialized OrigRoadDestRoad: " + origRoadDestRoadList2);

        // OriginCoordDestCoordTransform
        Collection<OriginCoordDestCoordTransform> coordTransformList = new ArrayList<>();
        coordTransformList.add(messageClass.new OriginCoordDestCoordTransform(10.5, 20.6, 0.0, 30.7, 40.8, 0.0, true));
        coordTransformList.add(messageClass.new OriginCoordDestCoordTransform(50.1, 60.2, 0.0, 70.3, 80.4, 0.0, false));

        json = gson.toJson(coordTransformList);
        System.out.println("Serialized OriginCoordDestCoordTransform: " + json);

        TypeToken<Collection<OriginCoordDestCoordTransform>> collectionType12 = new TypeToken<Collection<OriginCoordDestCoordTransform>>() {};
        Collection<OriginCoordDestCoordTransform> coordTransformList2 = gson.fromJson(json, collectionType12.getType());
        System.out.println("Deserialized OriginCoordDestCoordTransform: " + coordTransformList2);
    
	}
}
