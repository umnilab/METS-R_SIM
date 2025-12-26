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

	    // Constructor
	    public VehIDVehTypeTranXY(int vehID, boolean vehType, boolean transformCoord, 
	                                          double x, double y) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.transformCoord = transformCoord;
	        this.x = x;
	        this.y = y;
	    }
	}
	
	class VehIDVehTypeTranBearingXY {
	    int vehID;
	    boolean vehType;
	    boolean transformCoord;
	    double bearing;
	    double x;
	    double y;

	    // Constructor
	    public VehIDVehTypeTranBearingXY(int vehID, boolean vehType, boolean transformCoord, 
	                                         double bearing, double x, double y) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.transformCoord = transformCoord;
	        this.bearing = bearing;
	        this.x = x;
	        this.y = y;
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
    
    class ZoneIDOrigDestRouteNameNum{
    	int zoneID;
    	int dest;
    	int num;
    	String routeName;
    	
    	// Constructor
    	public ZoneIDOrigDestRouteNameNum(int zoneID, int dest, int num, String routeName) {
    		this.zoneID = zoneID;
    		this.dest = dest;
    		this.num = num;
    		this.routeName = routeName;
    	}
    }
    
    class OrigRoadDestRoadNum{
    	String orig;
    	String dest;
    	int num;
    	
    	// Constructor
    	public OrigRoadDestRoadNum(String orig, String dest, int num) {
    		this.orig = orig;
    		this.dest = dest;
    		this.num = num;
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
    
    class OriginCoordDestCoordTransform {
        double origX;
        double origY;
        double destX;
        double destY;
        boolean transformCoord;

        // Constructor
        public OriginCoordDestCoordTransform(double origX, double origY, double destX, double destY, boolean transformCoord) {
            this.origX = origX;
            this.origY = origY;
            this.destX = destX;
            this.destY = destY;
            this.transformCoord = transformCoord;
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
        vehIDVehTypeTranXYs.add(messageClass.new VehIDVehTypeTranXY(0, true, true, 12.34, 56.78));
        vehIDVehTypeTranXYs.add(messageClass.new VehIDVehTypeTranXY(1, false, false, 90.12, 34.56));

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

        // ZoneIDOrigDestNum
        Collection<ZoneIDOrigDestRouteNameNum> zoneIDOrigDestNums = new ArrayList<>();
        zoneIDOrigDestNums.add(messageClass.new ZoneIDOrigDestRouteNameNum(10, 300, 3, "101"));
        zoneIDOrigDestNums.add(messageClass.new ZoneIDOrigDestRouteNameNum(11, 301, 4, "102"));

        json = gson.toJson(zoneIDOrigDestNums);
        System.out.println("Serialized ZoneIDOrigDestNum: " + json);

        TypeToken<Collection<ZoneIDOrigDestRouteNameNum>> collectionType9 = new TypeToken<Collection<ZoneIDOrigDestRouteNameNum>>() {};
        Collection<ZoneIDOrigDestRouteNameNum> zoneIDOrigDestNums2 = gson.fromJson(json, collectionType9.getType());
        System.out.println("Deserialized ZoneIDOrigDestNum: " + zoneIDOrigDestNums2);

        // ZoneIDOrigRoadDestRoadNum
        Collection<OrigRoadDestRoadNum> zoneIDOrigRoadDestRoadNums = new ArrayList<>();
        zoneIDOrigRoadDestRoadNums.add(messageClass.new OrigRoadDestRoadNum("RoadA", "RoadB", 7));
        zoneIDOrigRoadDestRoadNums.add(messageClass.new OrigRoadDestRoadNum("RoadC", "RoadD", 8));

        json = gson.toJson(zoneIDOrigRoadDestRoadNums);
        System.out.println("Serialized ZoneIDOrigRoadDestRoadNum: " + json);

        TypeToken<Collection<OrigRoadDestRoadNum>> collectionType10 = new TypeToken<Collection<OrigRoadDestRoadNum>>() {};
        Collection<OrigRoadDestRoadNum> zoneIDOrigRoadDestRoadNums2 = gson.fromJson(json, collectionType10.getType());
        System.out.println("Deserialized ZoneIDOrigRoadDestRoadNum: " + zoneIDOrigRoadDestRoadNums2);
        
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
        coordTransformList.add(messageClass.new OriginCoordDestCoordTransform(10.5, 20.6, 30.7, 40.8, true));
        coordTransformList.add(messageClass.new OriginCoordDestCoordTransform(50.1, 60.2, 70.3, 80.4, false));

        json = gson.toJson(coordTransformList);
        System.out.println("Serialized OriginCoordDestCoordTransform: " + json);

        TypeToken<Collection<OriginCoordDestCoordTransform>> collectionType12 = new TypeToken<Collection<OriginCoordDestCoordTransform>>() {};
        Collection<OriginCoordDestCoordTransform> coordTransformList2 = gson.fromJson(json, collectionType12.getType());
        System.out.println("Deserialized OriginCoordDestCoordTransform: " + coordTransformList2);
    
	}
}
