package mets_r.communication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

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
	
	class VehIDVehTypeTranRoadIDXY {
	    int vehID;
	    boolean vehType;
	    boolean transformCoord;
	    String roadID;
	    double x;
	    double y;

	    // Constructor
	    public VehIDVehTypeTranRoadIDXY(int vehID, boolean vehType, boolean transformCoord, 
	                                          String roadID, double x, double y) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.transformCoord = transformCoord;
	        this.roadID = roadID;
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
    
    class ZoneIDOrigDestNum{
    	int zoneID;
    	int dest;
    	int num;
    	
    	// Constructor
    	public ZoneIDOrigDestNum(int zoneID, int dest, int num) {
    		this.zoneID = zoneID;
    		this.dest = dest;
    		this.num = num;
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
        Collection<VehIDVehTypeTranRoadIDXY> vehIDVehTypeTranRoadIDXYs = new ArrayList<>();
        vehIDVehTypeTranRoadIDXYs.add(messageClass.new VehIDVehTypeTranRoadIDXY(0, true, true, "101", 12.34, 56.78));
        vehIDVehTypeTranRoadIDXYs.add(messageClass.new VehIDVehTypeTranRoadIDXY(1, false, false, "102", 90.12, 34.56));

        json = gson.toJson(vehIDVehTypeTranRoadIDXYs);
        System.out.println("Serialized VehIDVehTypeTranRoadIDXY: " + json);

        TypeToken<Collection<VehIDVehTypeTranRoadIDXY>> collectionType6 = new TypeToken<Collection<VehIDVehTypeTranRoadIDXY>>() {};
        Collection<VehIDVehTypeTranRoadIDXY> vehIDVehTypeTranRoadIDXYs2 = gson.fromJson(json, collectionType6.getType());
        System.out.println("Deserialized VehIDVehTypeTranRoadIDXY: " + vehIDVehTypeTranRoadIDXYs2);

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
        Collection<ZoneIDOrigDestNum> zoneIDOrigDestNums = new ArrayList<>();
        zoneIDOrigDestNums.add(messageClass.new ZoneIDOrigDestNum(10, 300, 3));
        zoneIDOrigDestNums.add(messageClass.new ZoneIDOrigDestNum(11, 301, 4));

        json = gson.toJson(zoneIDOrigDestNums);
        System.out.println("Serialized ZoneIDOrigDestNum: " + json);

        TypeToken<Collection<ZoneIDOrigDestNum>> collectionType9 = new TypeToken<Collection<ZoneIDOrigDestNum>>() {};
        Collection<ZoneIDOrigDestNum> zoneIDOrigDestNums2 = gson.fromJson(json, collectionType9.getType());
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
	}
}
