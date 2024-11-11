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
	
	class VehIDVehTypeOrigDest {
	    int vehID;
	    boolean vehType;
	    int orig;
	    int dest;
	
	    // Constructor
	    public VehIDVehTypeOrigDest(int vehID, boolean vehType, int orig, int dest) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.orig = orig;
	        this.dest = dest;
	    }
	}
	
	class VehIDVehTypeTranRoadIDXYDist {
	    int vehID;
	    boolean vehType;
	    boolean transformCoord;
	    int roadID;
	    int laneID;
	    double x;
	    double y;
	    double dist;
	
	    // Constructor
	    public VehIDVehTypeTranRoadIDXYDist(int vehID, boolean vehType, boolean transformCoord, 
	                                          int roadID, int laneID, double x, double y, double dist) {
	        this.vehID = vehID;
	        this.vehType = vehType;
	        this.transformCoord = transformCoord;
	        this.roadID = roadID;
	        this.laneID = laneID;
	        this.x = x;
	        this.y = y;
	        this.dist = dist;
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

        // Serialize and deserialize a collection of VehIDVehTypeOrigDest objects
        Collection<VehIDVehTypeOrigDest> vehIDVehTypeOrigDest = new ArrayList<>();
        vehIDVehTypeOrigDest.add(messageClass.new VehIDVehTypeOrigDest(0, true, 1, 100));
        vehIDVehTypeOrigDest.add(messageClass.new VehIDVehTypeOrigDest(1, false, 200, 300));

        json = gson.toJson(vehIDVehTypeOrigDest);  // Serialize to JSON
        System.out.println("Serialized VehIDVehTypeOrigDest: " + json);

        TypeToken<Collection<VehIDVehTypeOrigDest>> collectionType5 = new TypeToken<Collection<VehIDVehTypeOrigDest>>() {};
        Collection<VehIDVehTypeOrigDest> vehIDVehTypeOrigDest2 = gson.fromJson(json, collectionType5.getType());  // Deserialize
        System.out.println("Deserialized VehIDVehTypeOrigDest: " + vehIDVehTypeOrigDest2);

        // Serialize and deserialize a collection of VehIDVehTypeTransRoadIDXYDist objects
        Collection<VehIDVehTypeTranRoadIDXYDist> vehIDVehTypeTransRoadIDXYDist = new ArrayList<>();
        vehIDVehTypeTransRoadIDXYDist.add(messageClass.new VehIDVehTypeTranRoadIDXYDist(0, true, false, 101, 1, 12.34, 56.78, 100.5));
        vehIDVehTypeTransRoadIDXYDist.add(messageClass.new VehIDVehTypeTranRoadIDXYDist(1, false, true, 102, 2, 23.45, 67.89, 200.5));

        json = gson.toJson(vehIDVehTypeTransRoadIDXYDist);  // Serialize to JSON
        System.out.println("Serialized VehIDVehTypeTransRoadIDXYDist: " + json);

        TypeToken<Collection<VehIDVehTypeTranRoadIDXYDist>> collectionType6 = new TypeToken<Collection<VehIDVehTypeTranRoadIDXYDist>>() {};
        Collection<VehIDVehTypeTranRoadIDXYDist> vehIDVehTypeTransRoadIDXYDist2 = gson.fromJson(json, collectionType6.getType());  // Deserialize
        System.out.println("Deserialized VehIDVehTypeTransRoadIDXYDist: " + vehIDVehTypeTransRoadIDXYDist2);
	}
}
