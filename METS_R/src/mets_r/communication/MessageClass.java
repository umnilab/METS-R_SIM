package mets_r.communication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class MessageClass{
	class VehIDVehType {
	int vehicleId;
	boolean isPrivate;

	// Constructor
	public VehIDVehType(int vehicleId, boolean isPrivate) {
	this.vehicleId = vehicleId;
	this.isPrivate = isPrivate;
	}
	}

	class VehIDVehTypeAttack {
	int vehicleId;
	boolean isPrivate;
	boolean attackEnabled;

	public VehIDVehTypeAttack(int vehicleId, boolean isPrivate, boolean attackEnabled) {
	this.vehicleId = vehicleId;
	this.isPrivate = isPrivate;
	this.attackEnabled = attackEnabled;
	}
	}

	class VehIDVehTypeTran {
	int vehicleId;
	boolean isPrivate;
	boolean transformCoordinates;

	// Constructor
	public VehIDVehTypeTran(int vehicleId, boolean isPrivate, boolean transformCoordinates) {
	this.vehicleId = vehicleId;
	this.isPrivate = isPrivate;
	this.transformCoordinates = transformCoordinates;
	}
	}

	class VehIDVehTypeSensorType {
	int vehicleId;
	boolean isPrivate;
	int sensorType;

	// Constructor
	public VehIDVehTypeSensorType(int vehicleId, boolean isPrivate, int sensorType) {
	this.vehicleId = vehicleId;
	this.isPrivate = isPrivate;
	this.sensorType = sensorType;
	}
	}

	class VehIDVehTypeAcc {
	int vehicleId;
	boolean isPrivate;
	double acceleration;

	// Constructor
	public VehIDVehTypeAcc(int vehicleId, boolean isPrivate, double acceleration) {
	this.vehicleId = vehicleId;
	this.isPrivate = isPrivate;
	this.acceleration = acceleration;
	}
	}

	class VehIDVehTypeTranXY {
	int vehicleId;
	boolean isPrivate;
	boolean transformCoordinates;
	double x;
	double y;
	double z = 0.0;

	// Constructor
	public VehIDVehTypeTranXY(int vehicleId, boolean isPrivate, boolean transformCoordinates,
	double x, double y, double z) {
	this.vehicleId = vehicleId;
	this.isPrivate = isPrivate;
	this.transformCoordinates = transformCoordinates;
	this.x = x;
	this.y = y;
	this.z = z;
	}
	}

	class CoSimTeleportRequest {
	Integer vehicleId;
	Boolean isPrivate;
	Boolean transformCoordinates;
	Double bearing;
	Double x;
	Double y;
	Double z;
	Double speed;
	String segmentId;
	Integer connectorPathId;
	Integer laneIndex;
	}

	class InitializeCoSimVehRequest {
		int vehicleId;
		Boolean isPrivate;
		boolean transformCoordinates;
		Double x;
		Double y;
		Double z;
		Double bearing;
		Double speed;
		Double vehicleLength;
		String segmentId;
		Integer connectorPathId;
		String destinationRoadId;
	}

	class VehIDVehTypeRoad {
	int vehicleId;
	boolean isPrivate;
	String roadId;

	// Constructor
	public VehIDVehTypeRoad(int vehicleId, boolean isPrivate, String roadId) {
	this.vehicleId = vehicleId;
	this.isPrivate = isPrivate;
	this.roadId = roadId;
	}
	}

	class DigitalTwinTeleportRequest {
	Integer vehicleId;
	Boolean isPrivate;
	String positionType;
	String segmentId;
	Integer laneIndex;
	Integer connectorPathId;
	Double distanceToSegmentEnd;
	Double x;
	Double y;
	Double z;
	Boolean transformCoordinates;
	}

    class VehIDOrigDestNum{
	int vehicleId;
	int originZoneId;
	int destinationZoneId;
	int passengerCount;
		Double vehicleLength;

	// Constructor
	public VehIDOrigDestNum(int vehicleId, int originZoneId, int destinationZoneId,
			int passengerCount) {
			this(vehicleId, originZoneId, destinationZoneId, passengerCount, null);
		}

		public VehIDOrigDestNum(int vehicleId, int originZoneId, int destinationZoneId,
				int passengerCount, Double vehicleLength) {
		this.vehicleId = vehicleId;
		this.originZoneId = originZoneId;
		this.destinationZoneId = destinationZoneId;
		this.passengerCount = passengerCount;
			this.vehicleLength = vehicleLength;
	}
    }

    class VehIDOrigRoadDestRoadNum{
	int vehicleId;
	String originRoadId;
	String destinationRoadId;
	int passengerCount;
		Double vehicleLength;

	// Constructor
	public VehIDOrigRoadDestRoadNum(int vehicleId, String originRoadId,
			String destinationRoadId, int passengerCount) {
			this(vehicleId, originRoadId, destinationRoadId, passengerCount, null);
		}

		public VehIDOrigRoadDestRoadNum(int vehicleId, String originRoadId,
				String destinationRoadId, int passengerCount, Double vehicleLength) {
		this.vehicleId = vehicleId;
		this.originRoadId = originRoadId;
		this.destinationRoadId = destinationRoadId;
		this.passengerCount = passengerCount;
			this.vehicleLength = vehicleLength;
	}
    }

    // For dispatchTaxi / dispTaxiBwRoads: pair an available taxi with an
    // already-pending request (added via addTaxiRequests or addTaxiReqBwRoads).
    class VehIDReqID{
	int vehicleId;
	int requestId;
		Integer originZoneId;

	public VehIDReqID(int vehicleId, int requestId) {
		this.vehicleId = vehicleId;
		this.requestId = requestId;
		this.originZoneId = null;
	}
    }

    // For assignRequestToBus: pair a bus with an already-pending bus request.
    class BusIDReqID{
        Integer busId;
        Integer requestId;

        public BusIDReqID(Integer busId, Integer requestId) {
            this.busId = busId;
            this.requestId = requestId;
        }

        public int getBusID() {
            if (this.busId != null) return this.busId;
            return -1;
        }
    }

    // For repositionTaxi: send an idle/cruising taxi to a destination zone.
    class VehIDZoneID{
	int vehicleId;
	int zoneId;

	public VehIDZoneID(int vehicleId, int zoneId) {
		this.vehicleId = vehicleId;
		this.zoneId = zoneId;
	}
    }

    // For goParking: send an idle taxi to park on a target zone/road.
    class VehIDZoneRoad{
        int vehicleId;
        Integer zoneId;
        String roadId;

        public VehIDZoneRoad(int vehicleId, Integer zoneId, String roadId) {
            this.vehicleId = vehicleId;
            this.zoneId = zoneId;
            this.roadId = roadId;
        }
    }

    /** Taxi request payload for {@code addTaxiRequests}. */
    class OriginDestNumMaxW{
        int originZoneId;
        int destinationZoneId;
        int passengerCount;
        int maxWaitTicks;

        public OriginDestNumMaxW(int originZoneId, int destinationZoneId,
		int passengerCount, int maxWaitTicks) {
            this.originZoneId = originZoneId;
            this.destinationZoneId = destinationZoneId;
            this.passengerCount = passengerCount;
            this.maxWaitTicks = maxWaitTicks;
        }
    }


    class OrigRoadDestRoadNumMaxW{
	String originRoadId;
	String destinationRoadId;
	int passengerCount;
	int maxWaitTicks;

	// Constructor
	public OrigRoadDestRoadNumMaxW(String originRoadId, String destinationRoadId,
			int passengerCount, int maxWaitTicks) {
		this.originRoadId = originRoadId;
		this.destinationRoadId = destinationRoadId;
		this.passengerCount = passengerCount;
		this.maxWaitTicks = maxWaitTicks;
	}
    }

    class OrigRoadDestRoad {
        String originRoadId;
        String destinationRoadId;

        // Constructor
        public OrigRoadDestRoad(String originRoadId, String destinationRoadId) {
            this.originRoadId = originRoadId;
            this.destinationRoadId = destinationRoadId;
        }
    }

    class OrigRoadDestRoadK {
        String originRoadId;
        String destinationRoadId;
        int routeCount;

        // Constructor
        public OrigRoadDestRoadK(String originRoadId, String destinationRoadId, int routeCount) {
            this.originRoadId = originRoadId;
            this.destinationRoadId = destinationRoadId;
            this.routeCount = routeCount;
        }
    }

    class OriginCoordDestCoordTransform {
        double originX;
        double originY;
        double originZ = 0.0;
        double destinationX;
        double destinationY;
        double destinationZ = 0.0;
        boolean transformCoordinates;

        // Constructor
        public OriginCoordDestCoordTransform(double originX, double originY, double originZ,
                                             double destinationX, double destinationY, double destinationZ,
                                             boolean transformCoordinates) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.destinationX = destinationX;
            this.destinationY = destinationY;
            this.destinationZ = destinationZ;
            this.transformCoordinates = transformCoordinates;
        }
    }

    class OriginCoordDestCoordTransformK {
        double originX;
        double originY;
        double originZ = 0.0;
        double destinationX;
        double destinationY;
        double destinationZ = 0.0;
        boolean transformCoordinates;
        int routeCount;

        // Constructor
        public OriginCoordDestCoordTransformK(double originX, double originY, double originZ,
                                              double destinationX, double destinationY, double destinationZ,
                                              boolean transformCoordinates, int routeCount) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.destinationX = destinationX;
            this.destinationY = destinationY;
            this.destinationZ = destinationZ;
            this.transformCoordinates = transformCoordinates;
            this.routeCount = routeCount;
        }
    }

    class VehIDVehTypeRoute{
	int vehicleId;
	boolean isPrivate;
	List<String> routeRoadIds;

	// Constructor
	public VehIDVehTypeRoute(int vehicleId, boolean isPrivate, List<String> routeRoadIds) {
	this.vehicleId = vehicleId;
	this.isPrivate = isPrivate;
	this.routeRoadIds = routeRoadIds;
	}
    }

    class RoadIDWeight{
	String roadId;
		double routingWeight;

        // Constructor
	public RoadIDWeight(String roadId, double routingWeight) {
	this.roadId = roadId;
	this.routingWeight = routingWeight;
	}
    }

    class RoadIDTargetSpeed{
	String roadId;
	Double targetSpeed;

	public RoadIDTargetSpeed(String roadId, Double targetSpeed) {
	this.roadId = roadId;
	this.targetSpeed = targetSpeed;
	}
    }

    class RoadParkingCapacity{
	String roadId;
	Integer parkingCapacity;

	public RoadParkingCapacity(String roadId, Integer parkingCapacity) {
	this.roadId = roadId;
	this.parkingCapacity = parkingCapacity;
	}
    }

    class RoadIDLaneIndexTransform{
		String segmentId;
        int laneIndex;
        boolean transformCoordinates;

        // Constructor
	public RoadIDLaneIndexTransform(String segmentId, int laneIndex, boolean transformCoordinates) {
	this.segmentId = segmentId;
	this.laneIndex = laneIndex;
	this.transformCoordinates = transformCoordinates;
	}
    }

    class RouteNameZonesRoadsPath{
	String routeName;
	ArrayList<Integer> stopZoneIds;
	ArrayList<String> stopRoadIds;
	ArrayList<List<String>> pathRoadIds;

	// Constructor
	public RouteNameZonesRoadsPath(String routeName, ArrayList<Integer> stopZoneIds,
			ArrayList<String> stopRoadIds, ArrayList<List<String>> pathRoadIds) {
		this.routeName = routeName;
		this.stopZoneIds = stopZoneIds;
		this.stopRoadIds = stopRoadIds;
		this.pathRoadIds = pathRoadIds;
	}
    }

    class RouteNameZonesRoads{
	String routeName;
	ArrayList<Integer> stopZoneIds;
	ArrayList<String> stopRoadIds;

	// Constructor
	public RouteNameZonesRoads(String routeName, ArrayList<Integer> stopZoneIds,
			ArrayList<String> stopRoadIds) {
		this.routeName = routeName;
		this.stopZoneIds = stopZoneIds;
		this.stopRoadIds = stopRoadIds;
	}
    }

    class BusIDRouteNameZoneRoadStopIndex{
	int busId;
	String routeName;
	int stopZoneId;
	String stopRoadId;
	int stopIndex;

	// Constructor
	public BusIDRouteNameZoneRoadStopIndex(int busId, String routeName,
			int stopZoneId, String stopRoadId, int stopIndex) {
		this.busId = busId;
		this.routeName = routeName;
		this.stopZoneId = stopZoneId;
		this.stopRoadId = stopRoadId;
		this.stopIndex = stopIndex;
	}
    }

    class BusIDRouteNameStopIndex{
	int busId;
	String routeName;
	int stopIndex;

	// Constructor
	public BusIDRouteNameStopIndex(int busId, String routeName, int stopIndex) {
		this.busId = busId;
		this.routeName = routeName;
		this.stopIndex = stopIndex;
	}
    }

    class RouteNameDepartTime{
	String routeName;
	ArrayList<Integer> departureTicks;

	// Constructor
	public RouteNameDepartTime(String routeName, ArrayList<Integer> departureTicks) {
		this.routeName = routeName;
		this.departureTicks = departureTicks;
	}
    }

    class ChargerIDChargerTypeWeight{
	int chargingStationId;
	int chargerLevel;
		double price;

        // Constructor
	public ChargerIDChargerTypeWeight(int chargingStationId, int chargerLevel, double price) {
	this.chargingStationId = chargingStationId;
	this.chargerLevel = chargerLevel;
	this.price = price;
	}
    }

    // Message class for querying signal by road connection (upstream road -> downstream road)
    class UpStreamRoadDownStreamRoad {
        String upstreamRoadId;
        String downstreamRoadId;

        // Constructor
        public UpStreamRoadDownStreamRoad(String upstreamRoadId, String downstreamRoadId) {
            this.upstreamRoadId = upstreamRoadId;
            this.downstreamRoadId = downstreamRoadId;
        }
    }

    // Message class for updating signal phase
    // signalId: the ID of the signal
    // targetPhase: 0 (Green), 1 (Yellow), 2 (Red)
    // phaseTime: time offset in seconds from the start of the phase (optional, default 0)
    class SignalIDPhase {
        int signalId;
        int phase;
        int phaseOffsetSeconds;

        // Constructor
        public SignalIDPhase(int signalId, int phase, int phaseOffsetSeconds) {
            this.signalId = signalId;
            this.phase = phase;
            this.phaseOffsetSeconds = phaseOffsetSeconds;
        }
    }

    // Message class for updating signal phase timing
    // signalId: the ID of the signal
    // greenTime, yellowTime, redTime: duration in seconds for each phase
    class SignalIDPhaseTiming {
        int signalId;
        int greenSeconds;
        int yellowSeconds;
        int redSeconds;

        // Constructor
        public SignalIDPhaseTiming(int signalId, int greenSeconds, int yellowSeconds, int redSeconds) {
            this.signalId = signalId;
            this.greenSeconds = greenSeconds;
            this.yellowSeconds = yellowSeconds;
            this.redSeconds = redSeconds;
        }
    }

    // Message class for setting a complete phase plan (phase timing + starting state)
    // signalId: the ID of the signal
    // greenTime, yellowTime, redTime: duration in seconds for each phase
    // startPhase: the phase to start from (0=Green, 1=Yellow, 2=Red)
    // phaseOffset: time offset in seconds from the start of the startPhase (optional, defaults to 0)
    class SignalPhasePlan {
        int signalId;
        int greenSeconds;
        int yellowSeconds;
        int redSeconds;
        int startPhase;
        int phaseOffsetSeconds;

        // Constructor
        public SignalPhasePlan(int signalId, int greenSeconds, int yellowSeconds,
		int redSeconds, int startPhase, int phaseOffsetSeconds) {
            this.signalId = signalId;
            this.greenSeconds = greenSeconds;
            this.yellowSeconds = yellowSeconds;
            this.redSeconds = redSeconds;
            this.startPhase = startPhase;
            this.phaseOffsetSeconds = phaseOffsetSeconds;
        }
    }

    // Message class for setting phase plan with tick-level precision
    // signalId: the ID of the signal
    // greenTicks, yellowTicks, redTicks: duration in simulation ticks for each phase
    // startPhase: the phase to start from (0=Green, 1=Yellow, 2=Red)
    // tickOffset: tick offset from the start of the startPhase (optional, defaults to 0)
    class SignalPhasePlanTicks {
        int signalId;
        int greenTicks;
        int yellowTicks;
        int redTicks;
        int startPhase;
        int phaseOffsetTicks;

        // Constructor
        public SignalPhasePlanTicks(int signalId, int greenTicks, int yellowTicks,
		int redTicks, int startPhase, int phaseOffsetTicks) {
            this.signalId = signalId;
            this.greenTicks = greenTicks;
            this.yellowTicks = yellowTicks;
            this.redTicks = redTicks;
            this.startPhase = startPhase;
            this.phaseOffsetTicks = phaseOffsetTicks;
        }
    }

    class ZoneParams {
	double x;
	double y;
	double z = 0.0;
	boolean transformCoordinates;
	int capacity;
	int zoneType;

	public ZoneParams(double x, double y, double z, boolean transformCoordinates,
			int capacity, int zoneType) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.transformCoordinates = transformCoordinates;
		this.capacity = capacity;
		this.zoneType = zoneType;
	}
    }

    class ChargingStationParams {
	double x;
	double y;
	double z = 0.0;
	boolean transformCoordinates;
	int level2ChargerCount;
	int level3ChargerCount;
	int busChargerCount;
	double level2Price;
	double level3Price;

	public ChargingStationParams(double x, double y, double z, boolean transformCoordinates, int level2ChargerCount, int level3ChargerCount, int busChargerCount, double level2Price, double level3Price) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.transformCoordinates = transformCoordinates;
		this.level2ChargerCount = level2ChargerCount;
		this.level3ChargerCount = level3ChargerCount;
		this.busChargerCount = busChargerCount;
		this.level2Price = level2Price;
		this.level3Price = level3Price;
	}
    }

    class RoadParams {
        String roadId;
        ArrayList<ArrayList<Double>> centerline;
        boolean transformCoordinates;
        ArrayList<String> upstreamRoadIds;
        ArrayList<String> downstreamRoadIds;
        Integer roadType;
        String controlMode;
        String upstreamControlMode;
        String downstreamControlMode;
        Integer laneCount;
        Double laneWidth;
        Integer parkingCapacity;

        public RoadParams(String roadId, ArrayList<ArrayList<Double>> centerline,
		boolean transformCoordinates, ArrayList<String> upstreamRoadIds,
		ArrayList<String> downstreamRoadIds, Integer roadType, String controlMode,
		String upstreamControlMode, String downstreamControlMode,
		Integer laneCount, Double laneWidth) {
            this.roadId = roadId;
            this.centerline = centerline;
            this.transformCoordinates = transformCoordinates;
            this.upstreamRoadIds = upstreamRoadIds;
            this.downstreamRoadIds = downstreamRoadIds;
            this.roadType = roadType;
            this.controlMode = controlMode;
            this.upstreamControlMode = upstreamControlMode;
            this.downstreamControlMode = downstreamControlMode;
            this.laneCount = laneCount;
            this.laneWidth = laneWidth;
        }
    }

    class AddTaxiToZone {
	int zoneId;
	int vehicleCount;
		Double vehicleLength;

	public AddTaxiToZone(int zoneId, int vehicleCount) {
			this(zoneId, vehicleCount, null);
		}

		public AddTaxiToZone(int zoneId, int vehicleCount, Double vehicleLength) {
		this.zoneId = zoneId;
		this.vehicleCount = vehicleCount;
			this.vehicleLength = vehicleLength;
	}
    }

    class RouteNameNum {
	String routeName;
	int vehicleCount;
		Double vehicleLength;

	public RouteNameNum(String routeName, int vehicleCount) {
			this(routeName, vehicleCount, null);
		}

		public RouteNameNum(String routeName, int vehicleCount, Double vehicleLength) {
		this.routeName = routeName;
		this.vehicleCount = vehicleCount;
			this.vehicleLength = vehicleLength;
	}
    }

    // isPrivate: true = private vehicle (EV/GV), false = public vehicle (taxi)
    // chargerLevel: 0 = L2, 1 = L3, 2 = BUS
    // chargingStationId: 0 = auto-select nearest/cheapest; negative integer = specific charging station ID
    class VehIDVehTypeChargerTypeCSID {
	int vehicleId;
	boolean isPrivate;
	int chargerLevel;
	int chargingStationId;

	public VehIDVehTypeChargerTypeCSID(int vehicleId, boolean isPrivate, int chargerLevel, int chargingStationId) {
		this.vehicleId = vehicleId;
		this.isPrivate = isPrivate;
		this.chargerLevel = chargerLevel;
		this.chargingStationId = chargingStationId;
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
