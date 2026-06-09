package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.geotools.geometry.jts.JTS;
import org.json.simple.JSONObject;
import org.opengis.referencing.operation.TransformException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.SnapshotUtil;
import mets_r.communication.MessageClass.BusIDReqID;
import mets_r.communication.MessageClass.BusIDRouteNameStopIndex;
import mets_r.communication.MessageClass.BusIDRouteNameZoneRoadStopIndex;
import mets_r.communication.MessageClass.ChargerIDChargerTypeWeight;
import mets_r.communication.MessageClass.SignalIDPhase;
import mets_r.communication.MessageClass.SignalIDPhaseTiming;
import mets_r.communication.MessageClass.SignalPhasePlan;
import mets_r.communication.MessageClass.SignalPhasePlanTicks;
import mets_r.communication.MessageClass.OrigRoadDestRoadNumMaxW;
import mets_r.communication.MessageClass.OriginDestNumMaxW;
import mets_r.communication.MessageClass.RoadParkingCapacity;
import mets_r.communication.MessageClass.RoadIDWeight;
import mets_r.communication.MessageClass.RouteNameDepartTime;
import mets_r.communication.MessageClass.RouteNameZonesRoads;
import mets_r.communication.MessageClass.RouteNameZonesRoadsPath;
import mets_r.communication.MessageClass.VehIDOrigDestNum;
import mets_r.communication.MessageClass.VehIDOrigRoadDestRoadNum;
import mets_r.communication.MessageClass.VehIDReqID;
import mets_r.communication.MessageClass.VehIDZoneID;
import mets_r.communication.MessageClass.VehIDZoneRoad;
import mets_r.communication.MessageClass.VehIDVehType;
import mets_r.communication.MessageClass.VehIDVehTypeAcc;
import mets_r.communication.MessageClass.VehIDVehTypeRoad;
import mets_r.communication.MessageClass.VehIDVehTypeRoadLaneDist;
import mets_r.communication.MessageClass.VehIDVehTypeRoute;
import mets_r.communication.MessageClass.VehIDVehTypeSensorType;
import mets_r.communication.MessageClass.VehIDVehTypeTranBearingXYSpeed;
import mets_r.communication.MessageClass.AddTaxiToZone;
import mets_r.communication.MessageClass.ChargingStationParams;
import mets_r.communication.MessageClass.RouteNameNum;
import mets_r.communication.MessageClass.VehIDVehTypeChargerTypeCSID;
import mets_r.communication.MessageClass.RoadParams;
import mets_r.communication.MessageClass.ZoneParams;
import mets_r.facility.ZoneContext;
import mets_r.data.input.SumoXML;
import mets_r.facility.ChargingStation;
import mets_r.facility.Lane;
import mets_r.facility.Node;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Plan;
import mets_r.mobility.Request;
import mets_r.mobility.Vehicle;
import mets_r.routing.RouteContext;

public class ControlMessageHandler extends MessageHandler {
	
	public ControlMessageHandler() {
		// =============================================================
		// Simulation lifecycle
		// =============================================================
		messageHandlers.put("end", this::endSim);
		messageHandlers.put("reset", this::resetSim);
		messageHandlers.put("save", this::saveSim);
		messageHandlers.put("load", this::loadSim);
		
		// =============================================================
		// Co-simulation: road handover & vehicle teleport
		// =============================================================
		messageHandlers.put("setCoSimRoad", this::setCoSimRoad);
		messageHandlers.put("releaseCosimRoad", this::releaseCosimRoad);
		messageHandlers.put("teleportCoSimVeh", this::teleportCoSimVeh);
		messageHandlers.put("teleportTraceReplayVeh", this::teleportTraceReplayVeh);
		messageHandlers.put("enterRoadFromQueue", this::enterRoadFromQueue);
		messageHandlers.put("allowRoadVehicleEnter", this::enterRoadFromQueue);
		messageHandlers.put("releaseEnteringVehicle", this::enterRoadFromQueue);
		
		// =============================================================
		// Vehicle runtime control
		// =============================================================
		messageHandlers.put("controlVeh", this::controlVeh);
		messageHandlers.put("enterNextRoad", this::enterNextRoad);
		messageHandlers.put("reachDest", this::reachDest);
		messageHandlers.put("updateVehicleSensorType", this::updateVehicleSensorType);
		messageHandlers.put("updateVehicleRoute", this::updateVehicleRoute);
		
		// =============================================================
		// Routing weights
		// =============================================================
		messageHandlers.put("updateEdgeWeight", this::updateEdgeWeight);
		messageHandlers.put("updateRoadParkingCapacity", this::updateRoadParkingCapacity);
		
		// =============================================================
		// Traffic signals
		// =============================================================
		messageHandlers.put("updateSignal", this::updateSignal);
		messageHandlers.put("updateSignalTiming", this::updateSignalTiming);
		messageHandlers.put("setSignalPhasePlan", this::setSignalPhasePlan);
		messageHandlers.put("setSignalPhasePlanTicks", this::setSignalPhasePlanTicks);
		
		// =============================================================
		// Charging
		// =============================================================
		messageHandlers.put("updateChargingPrice", this::updateChargingPrice);
		messageHandlers.put("goCharging", this::goCharging);
		
		// =============================================================
		// Private-vehicle trip generation
		// =============================================================
		messageHandlers.put("generateTrip", this::generateTrip);
		messageHandlers.put("genTripBwRoads", this::generateTripBwRoads);
		
		// =============================================================
		// Ride-hailing: add pending requests
		// These are the ONLY entry points that create Request objects;
		// dispatch endpoints below only match a vehicle to an existing
		// pending request.
		// =============================================================
		messageHandlers.put("addTaxiRequests", this::addTaxiRequests);
		messageHandlers.put("addTaxiReqBwRoads", this::addTaxiReqBwRoads);
		messageHandlers.put("addBusRequests", this::addBusRequests);
		
		// =============================================================
		// Ride-hailing: dispatch & repositioning
		// =============================================================
		messageHandlers.put("dispatchTaxi", this::dispatchTaxi);
		messageHandlers.put("cancelRequests", this::cancelRequests);
		messageHandlers.put("repositionTaxi", this::repositionTaxi);
		messageHandlers.put("goParking", this::goParking);
		messageHandlers.put("assignRequestToBus", this::assignRequestToBus);
		
		// =============================================================
		// Bus routes & stops
		// =============================================================
		messageHandlers.put("addBusRoute", this::addBusRoute);
		messageHandlers.put("addBusRouteWithPath", this::addBusRouteWithPath);
		messageHandlers.put("addBusRun", this::addBusRun);
		messageHandlers.put("insertStopToRoute", this::insertStopToRoute);
		messageHandlers.put("removeStopFromRoute", this::removeStopFromRoute);
		
		// =============================================================
		// Dynamic infrastructure & fleet additions / removals
		// =============================================================
		messageHandlers.put("addZone", this::addZone);
		messageHandlers.put("addRoads", this::addRoads);
		messageHandlers.put("removeZone", this::removeZone);
		messageHandlers.put("removeRoad", this::removeRoad);
		messageHandlers.put("addChargingStation", this::addChargingStation);
		messageHandlers.put("removeChargingStation", this::removeChargingStation);
		messageHandlers.put("addTaxi", this::addTaxi);
		messageHandlers.put("addBus", this::addBus);
	}
	
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		CustomizableHandler handler = messageHandlers.get(msgType); 
    	HashMap<String, Object> jsonAns = (handler != null) ? handler.handle(jsonMsg) : null;
    	jsonAns.put("TYPE", "CTRL_" + msgType);
    	count++;		
		return JSONObject.toJSONString(jsonAns);
	}
	
	// =============================================================
	// SIMULATION LIFECYCLE
	// =============================================================
	
	/**
	 * Reset the simulation back to its initial loaded state, cancelling
	 * every scheduled action and re-running the seed-and-load pipeline.
	 * Uses the deferred variant of reset to avoid leaking on-deck recurring
	 * actions that would otherwise fire on stale targets after the reset.
	 */
	private HashMap<String, Object> resetSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		
		try {
			// Use the deferred variant so the scheduler has fully completed
			// the current tick (every recurring action rescheduled into the
			// main queue) before we attempt to remove them. This eliminates
			// the on-deck-queue leak that previously left ~5 recurring
			// actions per reset firing on stale targets, which both pinned
			// per-run heap state and inflated trip-completion counts across
			// successive resets.
			ContextCreator.deferredReset();
			jsonAns.put("CODE", "OK");
			jsonAns.put("tick", ContextCreator.getCurrentTick());
			jsonAns.put("TICK", ContextCreator.getCurrentTick());
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing control" + e.toString());
			jsonAns.put("CODE", "KO");
		}
		
		return jsonAns;
	}
	
	/**
	 * Terminate the simulation cleanly, notifying any connected external
	 * controllers that the run is finishing before invoking
	 * {@link ContextCreator#end()}.
	 */
	private HashMap<String, Object> endSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		
		ContextCreator.connection.sendStopMessage();
		
		// Call the end function, cannot fail
		ContextCreator.end();
		jsonAns.put("CODE", "OK");
		
		return jsonAns;
	}
	
	/**
	 * Save a snapshot of the current simulation state to the specified
	 * zip archive.
	 *
	 * <p>Input DATA: {@code {"path": "<zip file path>"}}.
	 */
	private HashMap<String, Object> saveSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found. Expected: {\"path\": \"<zip file path>\"}");
			jsonAns.put("CODE", "KO");
		} else {
			try {
				Gson gson = new Gson();
				HashMap<String, String> data = gson.fromJson(
						jsonMsg.get("DATA").toString(),
						new com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.getType());
				String zipPath = data.get("path");
				if (zipPath == null || zipPath.isEmpty()) {
					jsonAns.put("WARN", "Missing 'path' in DATA");
					jsonAns.put("CODE", "KO");
				} else {
					jsonAns.put("path", zipPath);
					if (ContextCreator.save(zipPath)) {
						jsonAns.put("CODE", "OK");
					} else {
						jsonAns.put("WARN", "Save failed; check simulator logs for the underlying exception.");
						jsonAns.put("CODE", "KO");
					}
				}
			} catch (Exception e) {
				ContextCreator.logger.error("Error saving simulation: " + e.toString());
				jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Reload simulation state from a previously-saved zip archive,
	 * replacing the current run. Uses the deferred variant of load for
	 * the same on-deck-queue rationale as {@link #resetSim}.
	 *
	 * <p>Input DATA: {@code {"path": "<zip file path>", "reloadNetwork": false}}.
	 */
	private HashMap<String, Object> loadSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found. Expected: {\"path\": \"<zip file path>\", \"reloadNetwork\": false}");
			jsonAns.put("CODE", "KO");
		} else {
			try {
				Gson gson = new Gson();
				HashMap<String, Object> data = gson.fromJson(
						jsonMsg.get("DATA").toString(),
						new com.google.gson.reflect.TypeToken<HashMap<String, Object>>() {}.getType());
				String zipPath = data.get("path") == null ? null : data.get("path").toString();
				boolean reloadNetwork = optionalBoolean(data, false,
						"reloadNetwork", "reload_network", "rebuildNetwork", "rebuild_network");
				if (zipPath == null || zipPath.isEmpty()) {
					jsonAns.put("WARN", "Missing 'path' in DATA");
					jsonAns.put("CODE", "KO");
				} else {
					// Use the deferred variant so the scheduler has fully
					// completed the current tick (every recurring action
					// rescheduled into the main queue) before rebuildForLoad
					// removes them. Same on-deck-queue rationale as reset.
					jsonAns.put("path", zipPath);
					jsonAns.put("reloadNetwork", reloadNetwork);
					if (ContextCreator.deferredLoad(zipPath, reloadNetwork)) {
						jsonAns.put("CODE", "OK");
						jsonAns.put("tick", ContextCreator.getCurrentTick());
						jsonAns.put("TICK", ContextCreator.getCurrentTick());
						jsonAns.put("fastLoad", SnapshotUtil.wasLastLoadFastRestore());
					} else {
						jsonAns.put("WARN", "Load failed; check simulator logs for the underlying exception.");
						jsonAns.put("CODE", "KO");
					}
				}
			} catch (Exception e) {
				ContextCreator.logger.error("Error loading simulation: " + e.toString());
				jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}

	private boolean optionalBoolean(Map<String, Object> data, boolean defaultValue, String... keys) {
		for (String key : keys) {
			if (data.containsKey(key)) {
				return parseBoolean(data.get(key), defaultValue);
			}
		}
		return defaultValue;
	}

	private boolean parseBoolean(Object value, boolean defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof Number) {
			return ((Number) value).intValue() != 0;
		}
		String s = value.toString().trim();
		if (s.isEmpty()) {
			return defaultValue;
		}
		return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s);
	}
	
	// =============================================================
	// CO-SIMULATION: ROAD HANDOVER
	// =============================================================
	
	/**
	 * Mark one or more roads as co-simulation roads. Vehicles on these
	 * roads stop being stepped by METS-R's car-following logic; an
	 * external simulator is expected to drive them via {@link #teleportCoSimVeh}
	 * and {@link #enterNextRoad}.
	 *
	 * <p>Input DATA: list of original road IDs.
	 */
	private HashMap<String, Object> setCoSimRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else{
			try {
				Gson gson = new Gson();
				TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
			    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(String roadID: IDs) {
			    	Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadID);
			    	if(r != null) {
			    		r.setControlType(Road.COSIM);
						// Add road to coSim HashMap in the road context
						ContextCreator.coSimRoads.put(roadID, r);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", roadID);
						record2.put("STATUS", "OK");
						
						// Also output the lane information for computing the co-simulation area
//						ArrayList<Object> centerLines = new ArrayList<Object>();
//						for(Lane l: r.getLanes()) {
//							centerLines.add(l.getXYList());
//						}
//						
//						record2.put("center_lines", centerLines);
						jsonData.add(record2);
			    	}
			    	else {
			    		ContextCreator.logger.warn("Cannot find the road, road ID: " + roadID);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
					
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Revert one or more roads from co-simulation control back to native
	 * METS-R control.
	 *
	 * <p>Input DATA: list of original road IDs.
	 */
	private HashMap<String, Object> releaseCosimRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
			    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(String roadID: IDs) {
			    	Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadID);
			    	if(r != null) {
			    		r.setControlType(Road.NONE_OF_THE_ABOVE);
						// Remove road from coSim HashMap in the ContextCreator
						ContextCreator.coSimRoads.remove(roadID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
			    	}
			    	else {
			    		ContextCreator.logger.warn("Cannot find the road, road ID: " + roadID);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
			    }
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");;
			}
		}
		return jsonAns;
	}
	
	// =============================================================
	// PRIVATE-VEHICLE TRIP GENERATION
	// =============================================================
	
	/**
	 * Generate a one-shot private-EV trip between two zones. If a vehicle
	 * with the given {@code vehID} is not yet registered, a new
	 * {@link ElectricVehicle} is created on the fly.
	 *
	 * <p>Input DATA: list of {@code {vehID, orig, dest, num}} where
	 * {@code orig}/{@code dest} are zone IDs (use {@code <= 0} for random).
	 */
    private HashMap<String, Object> generateTrip(JSONObject jsonMsg) {
    	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
    	if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
    	else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigDestNum>> collectionType = new TypeToken<Collection<VehIDOrigDestNum>>() {};
			    Collection<VehIDOrigDestNum> vehIDVehTypeOrigDests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    for(VehIDOrigDestNum vehIDVehTypeOrigDest:  vehIDVehTypeOrigDests) {
			    	// Get data
			    	int vehID = vehIDVehTypeOrigDest.vehID;
			    	ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(vehID);
			    	if(v != null) {
						if (v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
			    			ContextCreator.logger.warn("The private EV: " + vehID + " is currently on the road, maybe there are two trips for the same vehicle that are too close?");
			    			HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
			    			continue;
						}
			    	}
					else { // If vehicle does not exists, create vehicle
						v = new ElectricVehicle(Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE);
						ContextCreator.getVehicleContext().registerPrivateEV(vehID, v);
					}
					
					// Find the origin and dest zones
					int originID = vehIDVehTypeOrigDest.orig;
					int destID = vehIDVehTypeOrigDest.dest;
					Zone originZone = null;
					Zone destZone = null;
					
					if(originID > 0) {
						originZone = ContextCreator.getZoneContext().get(originID);
						
					}
					else {
						if(ContextCreator.getZoneContext().ZONE_NUM == 1) {
							originID = 0;
							originZone = ContextCreator.getZoneContext().get(originID);
						}
						else {
							// randomly select a zone as origin
							originID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM - 1) + 1;
							originZone = ContextCreator.getZoneContext().get(originID);
						}
					}
					if(originZone == null) {
						ContextCreator.logger.warn("Cannot find the origin with ID: " + originID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
		    			continue;
					}
					if(originZone.getClosestRoad(false) == null) {
						ContextCreator.logger.warn("Origin zone " + originID + " has no departure road assigned yet");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", vehID);
						record2.put("STATUS", "KO");
						jsonData.add(record2);
						continue;
					}
					
					if(destID > 0) {
						destZone = ContextCreator.getZoneContext().get(destID);
					}
					else {
						if(ContextCreator.getZoneContext().ZONE_NUM == 1) {
							destID = 0;
							destZone = ContextCreator.getZoneContext().get(destID);
						}
						else {
							// randomly select a zone as destination
							destID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM - 1) + 1;
							destZone = ContextCreator.getZoneContext().get(destID);
						}
					} 
					if(destZone == null) {
						ContextCreator.logger.warn("Cannot find the dest with ID: " + destID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
		    			continue;
					}
					if(destZone.getClosestRoad(true) == null) {
						ContextCreator.logger.warn("Destination zone " + destID + " has no arrival road assigned yet");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", vehID);
						record2.put("STATUS", "KO");
						jsonData.add(record2);
						continue;
					}
			    			
					// Assign trips
					int origRoad = originZone.sampleRoad(false);
					v.initializePlan(originID, origRoad, (int) ContextCreator.getCurrentTick());
					v.addPlan(destID, destZone.sampleRoad(true), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure(origRoad);
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", vehID); // Note this vehID will be different from that obtained by veh.getID() which is generated by ContextCreator.generateAgentID();
					record2.put("STATUS", "OK");
					record2.put("origin", originID);
					record2.put("destination",destID);
					jsonData.add(record2);
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
    	}
    	return jsonAns;
    }
    
    /**
     * Like {@link #generateTrip} but with origin/destination specified as
     * road IDs instead of zone IDs.
     *
     * <p>Input DATA: list of {@code {vehID, orig, dest, num}} where
     * {@code orig} and {@code dest} are original road IDs.
     */
    private HashMap<String, Object> generateTripBwRoads(JSONObject jsonMsg) {
    	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
    	if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
    	else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigRoadDestRoadNum>> collectionType = new TypeToken<Collection<VehIDOrigRoadDestRoadNum>>() {};
			    Collection<VehIDOrigRoadDestRoadNum> vehIDVehTypeOrigDests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    for(VehIDOrigRoadDestRoadNum vehIDVehTypeOrigDest:  vehIDVehTypeOrigDests) {
			    	// Get data
			    	int vehID = vehIDVehTypeOrigDest.vehID;
			    	ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(vehID);
			    	if(v != null) {
						if (v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
			    			ContextCreator.logger.warn("The private EV: " + vehID + " is currently on the road, maybe there are two trips for the same vehicle that are too close?");
			    			HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
			    			continue;
						}
			    	}
					else { // If vehicle does not exists, create vehicle
						v = new ElectricVehicle(Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE);
						ContextCreator.getVehicleContext().registerPrivateEV(vehID, v);
					}
					
					// Find the origin and dest zones
					String originID = vehIDVehTypeOrigDest.orig;
					String destID = vehIDVehTypeOrigDest.dest;
					Road originRoad = null;
					Road destRoad = null;
					
					originRoad = ContextCreator.getCityContext().findRoadWithOrigID(originID);
					if(originRoad == null) {
						ContextCreator.logger.warn("Cannot find the origin road with ID: " + originID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
		    			continue;
					}
					
					destRoad = ContextCreator.getCityContext().findRoadWithOrigID(destID);
					if(destRoad == null) {
						ContextCreator.logger.warn("Cannot find the dest road with ID: " + destID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
		    			continue;
					}
		    			
		    		int originZoneID = originRoad.getNeighboringZone(false);
		    		int destZoneID = destRoad.getNeighboringZone(true);
		    		if(ContextCreator.getZoneContext().get(originZoneID) == null) {
						ContextCreator.logger.warn("Origin road " + originID + " has no neighboring zone assigned");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", vehID);
						record2.put("STATUS", "KO");
						jsonData.add(record2);
						continue;
					}
		    		if(ContextCreator.getZoneContext().get(destZoneID) == null) {
						ContextCreator.logger.warn("Destination road " + destID + " has no neighboring zone assigned");
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", vehID);
						record2.put("STATUS", "KO");
						jsonData.add(record2);
						continue;
					}

					// Assign trips
					v.initializePlan(originZoneID, originRoad.getID(), (int) ContextCreator.getCurrentTick());
					v.addPlan(destZoneID, destRoad.getID(), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure(originRoad);
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehID", vehID); // Note this vehID will be different from that obtained by veh.getID() which is generated by ContextCreator.generateAgentID();
		    		record2.put("STATUS", "OK");
		    		record2.put("origin", originID);
					record2.put("destination",destID);
					jsonData.add(record2);
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
    	}
    	return jsonAns;
    }
	
    // =============================================================
    // VEHICLE TELEPORT & RUNTIME CONTROL
    // =============================================================
    
    /**
     * Teleport an existing vehicle to a given lane and offset for trace
     * replay scenarios. The vehicle is removed from its current
     * lane/road and re-inserted at the target position.
     *
     * <p>Input DATA: list of {@code {vehID, vehType, roadID, laneID, dist}}
     * or {@code {vehID, vehType, roadID, laneID, x, y, transformCoord}} where
     * {@code vehType=true} selects a private vehicle and {@code vehType=false}
     * selects a public one. When {@code x} and {@code y} are provided, they are
     * projected onto the target lane to compute the distance to downstream.
     */
    private HashMap<String, Object> teleportTraceReplayVeh(JSONObject jsonMsg){
    	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) { 
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeRoadLaneDist>> collectionType = new TypeToken<Collection<VehIDVehTypeRoadLaneDist>>() {
				};
				Collection<VehIDVehTypeRoadLaneDist> vehIDVehTypeRoadLaneDists = gson
						.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (VehIDVehTypeRoadLaneDist vehIDVehTypeRoadLaneDist : vehIDVehTypeRoadLaneDists) {
					// Get data
					Vehicle veh = null;
					if (vehIDVehTypeRoadLaneDist.vehType) {
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeRoadLaneDist.vehID);
					} else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeRoadLaneDist.vehID);
					}
					
					if(veh == null) {
						ContextCreator.logger.error("Vehicle not found for ID: " + vehIDVehTypeRoadLaneDist.vehID);
					}
					else{
						Road road = ContextCreator.getCityContext().findRoadWithOrigID(vehIDVehTypeRoadLaneDist.roadID);
						if (road == null) {
			                ContextCreator.logger.error("Road not found for ID: " + vehIDVehTypeRoadLaneDist.roadID);
						}
		                else {
		                	if (vehIDVehTypeRoadLaneDist.laneID < 0 || vehIDVehTypeRoadLaneDist.laneID >= road.getNumberOfLanes()) {
		                		 ContextCreator.logger.error(
		                                 String.format("Invalid lane index %d for road %s (lanes: %d)",
		                                		 vehIDVehTypeRoadLaneDist.laneID, vehIDVehTypeRoadLaneDist.roadID, road.getNumberOfLanes()));
		                	}
		                	else{
		                		try {
									Lane lane = road.getLane(vehIDVehTypeRoadLaneDist.laneID);
									double teleportDistance = traceReplayTeleportDistance(vehIDVehTypeRoadLaneDist, lane);
									removeVehicleFromEnteringQueues(veh);
									// Update its location in the target link and target lane
									if (veh.getRoad() == road) {
										veh.removeFromCurrentLane(); // Just remove the vehicle from the current lane
									}
									else {
										veh.removeFromCurrentLane();
										veh.removeFromCurrentRoad();
									}
									
									road.teleportVehicle(veh, lane, teleportDistance);
									HashMap<String, Object> record2 = new HashMap<String, Object>();
									record2.put("ID", vehIDVehTypeRoadLaneDist.vehID);
									record2.put("STATUS", "OK");
									jsonData.add(record2);
									continue;
		                		} catch (Exception innerEx) {
		                            ContextCreator.logger.error("Teleport failure for vehicle " + vehIDVehTypeRoadLaneDist.vehID
		                                + " on road " + vehIDVehTypeRoadLaneDist.roadID + ": " + innerEx.getMessage(), innerEx);
		                        }

							}
						}
					}
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", vehIDVehTypeRoadLaneDist.vehID);
					record2.put("STATUS", "KO");
					jsonData.add(record2);
				}
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
    }

	private double traceReplayTeleportDistance(VehIDVehTypeRoadLaneDist request, Lane lane)
			throws TransformException {
		if (request.x != null && request.y != null) {
			Coordinate coord = new Coordinate(request.x, request.y);
			if (request.transformCoord) {
				JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
			}
			return laneDistanceFromCoordinate(lane, coord);
		}
		if (request.dist != null) {
			return request.dist.doubleValue();
		}
		throw new IllegalArgumentException("teleportTraceReplayVeh requires either dist or x/y");
	}

	private double laneDistanceFromCoordinate(Lane lane, Coordinate coord) {
		ArrayList<Coordinate> coords = lane.getCoords();
		if (coords == null || coords.size() < 2) {
			throw new IllegalArgumentException("Cannot project coordinate onto lane " + lane.getID()
					+ " because it has fewer than two coordinates");
		}

		double downstreamDistance = 0.0;
		double bestDistance = Double.NaN;
		double minProjectionError = Double.MAX_VALUE;
		for (int i = coords.size() - 1; i > 0; i--) {
			Coordinate downstream = coords.get(i);
			Coordinate upstream = coords.get(i - 1);
			double dx = downstream.x - upstream.x;
			double dy = downstream.y - upstream.y;
			double lenSq = dx * dx + dy * dy;
			double segmentLen = ContextCreator.getCityContext().getDistance(downstream, upstream);
			if (lenSq > 0) {
				double apx = coord.x - upstream.x;
				double apy = coord.y - upstream.y;
				double param = Math.max(0.0, Math.min(1.0, (apx * dx + apy * dy) / lenSq));
				double closestX = upstream.x + param * dx;
				double closestY = upstream.y + param * dy;
				double projectionError = Math.hypot(coord.x - closestX, coord.y - closestY);
				if (projectionError < minProjectionError) {
					minProjectionError = projectionError;
					bestDistance = downstreamDistance + (1.0 - param) * segmentLen;
				}
			}
			downstreamDistance += segmentLen;
		}

		if (Double.isNaN(bestDistance)) {
			throw new IllegalArgumentException("Cannot project coordinate onto lane " + lane.getID());
		}
		return Math.max(0.0, Math.min(lane.getLength(), bestDistance));
	}
    
	/**
	 * Teleport a co-simulation vehicle to an absolute (x, y, z) world
	 * coordinate, optionally transforming from the external simulator's
	 * coordinate system, and update its bearing and speed.
	 *
	 * <p>Input DATA: list of {@code {vehID, vehType, x, y, z, bearing,
	 * speed, transformCoord}}.
	 */
	private HashMap<String, Object> teleportCoSimVeh(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) { 
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeTranBearingXYSpeed>> collectionType = new TypeToken<Collection<VehIDVehTypeTranBearingXYSpeed>>() {
				};
				Collection<VehIDVehTypeTranBearingXYSpeed> vehIDVehTypeTranBearingXYSpeeds = gson
						.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (VehIDVehTypeTranBearingXYSpeed vehIDVehTypeTranBearingXYSpeed : vehIDVehTypeTranBearingXYSpeeds) {
					// Get data
					Vehicle veh = null;
					if (vehIDVehTypeTranBearingXYSpeed.vehType) {
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeTranBearingXYSpeed.vehID);
					} else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeTranBearingXYSpeed.vehID);
					}

				if (veh != null) {
					double x = vehIDVehTypeTranBearingXYSpeed.x;
					double y = vehIDVehTypeTranBearingXYSpeed.y;
					double z = vehIDVehTypeTranBearingXYSpeed.z;
					// Transform XY coordinates if needed (Z is in meters and not transformed)
					if (vehIDVehTypeTranBearingXYSpeed.transformCoord) {
						Coordinate coord = new Coordinate(x, y);
						try {
							JTS.transform(coord, coord,
									SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
							x = coord.x;
							y = coord.y;
						} catch (TransformException e) {
							ContextCreator.logger
									.error("Coordinates transformation failed, input x: " + x + " y:" + y);
							e.printStackTrace();
						}
					}
					
					removeVehicleFromEnteringQueues(veh);
					veh.setCurrentCoord(new Coordinate(x, y, z));
					veh.setSpeed(vehIDVehTypeTranBearingXYSpeed.speed);
					veh.setBearing(vehIDVehTypeTranBearingXYSpeed.bearing);

						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", vehIDVehTypeTranBearingXYSpeed.vehID);
						record2.put("STATUS", "OK");
						jsonData.add(record2);
						continue;
					}
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", vehIDVehTypeTranBearingXYSpeed.vehID);
					record2.put("STATUS", "KO");
					jsonData.add(record2);
				}
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Override a vehicle's acceleration for the next tick. Must be called
	 * at tick t to take effect during the t-to-t+1 interval; it bypasses
	 * the car-following model's acceleration decision.
	 *
	 * <p>Input DATA: list of {@code {vehID, vehType, acc}}.
	 */
	private HashMap<String, Object> controlVeh(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeAcc>> collectionType = new TypeToken<Collection<VehIDVehTypeAcc>>() {};
			    Collection<VehIDVehTypeAcc> vehIDVehTypeAccs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for (VehIDVehTypeAcc vehIDVehTypeAcc: vehIDVehTypeAccs) {
			    	// Get vehicle
					Vehicle veh = null;
					if(vehIDVehTypeAcc.vehType) {
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeAcc.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeAcc.vehID);
					}
					double acc = vehIDVehTypeAcc.acc;
					// Register its acceleration
					if(veh.controlVehicleAcc(acc)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeAcc.vehID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else{
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeAcc.vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
					}
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Force a vehicle to enter the specified next road, used by the
	 * co-simulation bridge when an external simulator authoritatively
	 * decides road transitions. If the standard {@code changeRoad()}
	 * gap check fails, the transition is forced anyway since the
	 * co-simulator is considered authoritative.
	 *
	 * <p>Input DATA: list of {@code {vehID, vehType, roadID}}. If
	 * {@code roadID} is empty the vehicle follows its existing route.
	 */
	private HashMap<String, Object> enterNextRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
	    		if (ContextCreator.getVehicleContext() == null) {
	    			ContextCreator.logger.error("enterNextRoad: VehicleContext is null — simulation may not be fully initialized");
	    			jsonAns.put("CODE", "KO");
	    			return jsonAns;
	    		}
	    		if (ContextCreator.getCityContext() == null) {
	    			ContextCreator.logger.error("enterNextRoad: CityContext is null — simulation may not be fully initialized");
	    			jsonAns.put("CODE", "KO");
	    			return jsonAns;
	    		}
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeRoad>> collectionType = new TypeToken<Collection<VehIDVehTypeRoad>>() {};
			    Collection<VehIDVehTypeRoad> vehIDVehTypeRoads = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(VehIDVehTypeRoad vehIDVehTypeRoad: vehIDVehTypeRoads) {
			    	Vehicle veh = null;
					if(vehIDVehTypeRoad.vehType) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeRoad.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeRoad.vehID);
					}
				if(veh != null) {
					if(vehIDVehTypeRoad.roadID != "") {
						Road r = ContextCreator.getCityContext().findRoadWithOrigID(vehIDVehTypeRoad.roadID);
						if(r != null) {
					if (veh.getRoad() == null) {
							ContextCreator.logger.warn("enterNextRoad: vehicle " + vehIDVehTypeRoad.vehID
									+ " has no current road, skipping");
						} else {
							// Always reroute so the stored path reflects the specified next road,
							// even when the current stored road and nextRoad are not adjacent (co-sim drift).
							veh.rerouteWithSpecifiedNextRoad(r);

							boolean entered = veh.changeRoad();
							if (!entered) {
								// changeRoad() failed (e.g. space/gap check); force the transition
								// because the external co-sim simulator is authoritative about road entry.
								Lane targetLane = veh.getNextLane();
								if (targetLane != null) {
									veh.executeRoadTransition(targetLane, r);
									entered = true;
								} else {
									ContextCreator.logger.warn("enterNextRoad: could not force transition for vehicle "
											+ vehIDVehTypeRoad.vehID + " to road " + vehIDVehTypeRoad.roadID
											+ " — nextLane is null");
								}
							}

							if (entered) {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
								record2.put("ID", vehIDVehTypeRoad.vehID);
								record2.put("STATUS", "OK");
								jsonData.add(record2);
								continue;
							}
						}
						}
					} else {
						if(veh.changeRoad()) {
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehIDVehTypeRoad.vehID);
				    		record2.put("STATUS", "OK");
							jsonData.add(record2);
							continue;
						}
					}
				}
					HashMap<String, Object> record2 = new HashMap<String, Object>();
		    		record2.put("ID", vehIDVehTypeRoad.vehID);
		    		record2.put("STATUS", "KO");
					jsonData.add(record2);
					
			    }
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    ContextCreator.logger.error("Error processing control enterNextRoad: " + e.getMessage(), e);
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
//	// Find the closest lane end coords in coSim Road, teleport the vehicle to the lane in METS-R SIM
//	// Trigger enterNextRoad and check whether it succeed or not
//	private HashMap<String, Object> exitCoSimRegion(JSONObject jsonMsg){
//		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
//		if(!jsonMsg.containsKey("DATA")) { 
//			jsonAns.put("WARN", "No DATA field found in the control message");
//			jsonAns.put("CODE", "KO");
//		}
//		else {
//	    	try {
//				Gson gson = new Gson();
//				TypeToken<Collection<VehIDVehTypeTranXY>> collectionType = new TypeToken<Collection<VehIDVehTypeTranXY>>() {
//				};
//				Collection<VehIDVehTypeTranXY> vehIDVehTypeTranXYs = gson
//						.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
//				ArrayList<Object> jsonData = new ArrayList<Object>();
//
//				for (VehIDVehTypeTranXY vehIDVehTypeTranXY : vehIDVehTypeTranXYs) {
//					// Get data
//					Vehicle veh = null;
//					if (vehIDVehTypeTranXY.vehType) {
//						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeTranXY.vehID);
//					} else {
//						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeTranXY.vehID);
//					}
//
//					if (veh != null) {
//						double x = vehIDVehTypeTranXY.x;
//						double y = vehIDVehTypeTranXY.y;
//						// Transform coordinates if needed
//						if (vehIDVehTypeTranXY.transformCoord) {
//							Coordinate coord = new Coordinate(x, y);
//							try {
//								JTS.transform(coord, coord,
//										SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
//								x = coord.x;
//								y = coord.y;
//							} catch (TransformException e) {
//								ContextCreator.logger
//										.error("Coordinates transformation failed, input x: " + x + " y:" + y);
//								e.printStackTrace();
//							}
//						}
//						
//						// Find the closest road
//						Coordinate coord2 = new Coordinate();
//						coord2.x = x;
//						coord2.y = y;
//						Road road = ContextCreator.getCityContext().findRoadAtCoordinates(coord2, true);
//						Lane lane = null;
//						if(road != null) {
//							// Find the current lane
//							double minDist = Double.MAX_VALUE;
//							for(Lane l: road.getLanes()) {
//								double currentDist = ContextCreator.getCityContext().getDistance(l.getEndCoord(), coord2);
//								if( currentDist < minDist) {
//									minDist = currentDist;
//									lane = l;
//								}
//							}
//							if(lane != null) {
//								// Insert vehicle to the end of lane
//								veh.removeFromCurrentLane();
//								veh.removeFromCurrentRoad();
//								veh.appendToRoad(road);
//								veh.teleportToLane(lane, 0);
//							
//								// Enter next road
//								if(veh.changeRoad()) {
//									HashMap<String, Object> record2 = new HashMap<String, Object>();
//						    		record2.put("ID", vehIDVehTypeTranXY.vehID);
//						    		record2.put("STATUS", "OK");
//									jsonData.add(record2);
//									continue;
//								}
//							}
//						}
//					}
//					HashMap<String, Object> record2 = new HashMap<String, Object>();
//					record2.put("ID", vehIDVehTypeTranXY.vehID);
//					record2.put("STATUS", "KO");
//					jsonData.add(record2);
//				}
//				jsonAns.put("DATA", jsonData);
//				jsonAns.put("CODE", "OK");
//			}
//			catch (Exception e) {
//			    // Log error and return KO in case of exception
//			    ContextCreator.logger.error("Error processing control: " + e.toString());
//			    jsonAns.put("CODE", "KO");
//			}
//		}
//		return jsonAns;	
//	}
//	
	/**
	 * Mark a vehicle as having reached its destination. Mainly used when
	 * the destination road is under co-simulation control, where METS-R
	 * cannot observe arrival natively.
	 *
	 * <p>Input DATA: list of {@code {vehID, vehType}}.
	 */
	private HashMap<String, Object> reachDest(JSONObject jsonMsg){
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehType>> collectionType = new TypeToken<Collection<VehIDVehType>>() {};
			    Collection<VehIDVehType> vehIDVehTypes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(VehIDVehType vehIDVehType: vehIDVehTypes) {
			    	Vehicle veh = null;
			    	if(vehIDVehType.vehType) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehType.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehType.vehID);
					}
			    	if(veh != null) {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		veh.reachDest(); 
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
			    	}
			    	else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehType.vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
		    
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Update the sensor-type tag of one or more vehicles. The tag is
	 * consumed by sensor-dependent logic such as perception models.
	 *
	 * <p>Input DATA: list of {@code {vehID, vehType, sensorType}}.
	 */
	private HashMap<String, Object> updateVehicleSensorType(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeSensorType>> collectionType = new TypeToken<Collection<VehIDVehTypeSensorType>>() {};
			    Collection<VehIDVehTypeSensorType> vehIDVehTypeSensorTypes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(VehIDVehTypeSensorType vehIDVehTypeSensorType: vehIDVehTypeSensorTypes) {
			    	Vehicle veh = null;
			    	if(vehIDVehTypeSensorType.vehType) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeSensorType.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeSensorType.vehID);
					}
			    	if(veh != null) {
			    		veh.setVehicleSensorType(vehIDVehTypeSensorType.sensorType);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeSensorType.vehID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
			    	}
			    	else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeSensorType.vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			    
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	// =============================================================
	// RIDE-HAILING: DISPATCH & REPOSITIONING
	// =============================================================
	
	/**
	 * Cancel taxi or bus requests by request ID and origin zone ID.
	 *
	 * <p>Input DATA: a list of objects with {@code reqID} and {@code zoneID}.
	 * Pending requests are removed from the specified zone queue and counted as
	 * passengers who left. Matched taxi pickup requests are removed from the
	 * taxi's pickup queue and trip plan; if the active pickup is removed, the
	 * taxi advances to its next queued trip with {@link Vehicle#setNextPlan()}.
	 * Occupied taxi requests are not cancellable.
	 */
	private HashMap<String, Object> cancelRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			ArrayList<CancelRequestEntry> entries = parseCancelRequestEntries(jsonMsg.get("DATA"));
			if (entries.isEmpty()) {
				jsonAns.put("WARN", "No request entries found in DATA");
				jsonAns.put("CODE", "KO");
				return jsonAns;
			}
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (CancelRequestEntry entry : entries) {
				Integer reqID = entry.reqID;
				Integer zoneID = entry.zoneID;
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("reqID", reqID);
				record.put("zoneID", zoneID);
				if (reqID == null) {
					record.put("STATUS", "KO");
					record.put("WARN", "request ID missing");
					jsonData.add(record);
					continue;
				}
				if (zoneID == null) {
					record.put("STATUS", "KO");
					record.put("WARN", "zone ID missing");
					jsonData.add(record);
					continue;
				}
				if (ContextCreator.getZoneContext().get(zoneID) == null) {
					record.put("STATUS", "KO");
					record.put("WARN", "zone not found");
					jsonData.add(record);
					continue;
				}

				PendingTaxiRequestRef pendingTaxi = findAndRemovePendingTaxiRequest(reqID, zoneID);
				if (pendingTaxi != null) {
					recordTaxiRequestLeft(pendingTaxi);
					record.put("mode", "taxi");
					record.put("requestState", "pending");
					record.put("action", "left");
					record.put("originZone", pendingTaxi.request.getOriginZone());
					record.put("destZone", pendingTaxi.request.getDestZone());
					record.put("STATUS", "OK");
					jsonData.add(record);
					continue;
				}

				PendingBusRequestRef pendingBus = findAndRemovePendingBusRequest(reqID, zoneID);
				if (pendingBus != null) {
					recordBusRequestLeft(pendingBus);
					record.put("mode", "bus");
					record.put("requestState", "pending");
					record.put("action", "left");
					record.put("originZone", pendingBus.request.getOriginZone());
					record.put("destZone", pendingBus.request.getDestZone());
					record.put("STATUS", "OK");
					jsonData.add(record);
					continue;
				}

				MatchedTaxiCancelResult matchedTaxi = cancelMatchedTaxiRequest(reqID, zoneID);
				if (matchedTaxi != null) {
					record.put("mode", "taxi");
					record.put("requestState", "matched");
					record.put("vehicleID", matchedTaxi.vehicleID);
					record.put("removedPickupTrip", matchedTaxi.removedPickupTrip);
					record.put("removedDropoffTrip", matchedTaxi.removedDropoffTrip);
					record.put("currentTripRemoved", matchedTaxi.currentTripRemoved);
					record.put("startedNextTrip", matchedTaxi.startedNextTrip);
					record.put("availableAfterCancellation", matchedTaxi.availableAfterCancellation);
					if (matchedTaxi.availableZone >= 0) {
						record.put("availableZone", matchedTaxi.availableZone);
					}
					record.put("originZone", matchedTaxi.request.getOriginZone());
					record.put("destZone", matchedTaxi.request.getDestZone());
					if (matchedTaxi.warn != null) {
						record.put("WARN", matchedTaxi.warn);
					}
					record.put("STATUS", matchedTaxi.statusOK ? "OK" : "KO");
					jsonData.add(record);
					continue;
				}

				MatchedBusCancelResult matchedBus = cancelMatchedBusRequest(reqID, zoneID);
				if (matchedBus != null) {
					record.put("mode", "bus");
					record.put("requestState", "matched");
					record.put("vehicleID", matchedBus.vehicleID);
					record.put("stopIndex", matchedBus.stopIndex);
					record.put("onBoard", matchedBus.onBoard);
					record.put("originZone", matchedBus.request.getOriginZone());
					record.put("destZone", matchedBus.request.getDestZone());
					if (matchedBus.warn != null) {
						record.put("WARN", matchedBus.warn);
					}
					record.put("STATUS", matchedBus.statusOK ? "OK" : "KO");
					jsonData.add(record);
					continue;
				}

				record.put("STATUS", "KO");
				record.put("WARN", "request not found");
				jsonData.add(record);
			}

			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing cancelRequests: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	/**
	 * Dispatch a taxi to serve an already-pending request.
	 *
	 * <p>Input DATA: list of {@code {vehID, reqID}}. The request must have
	 * been added via {@code addTaxiRequests} or {@code addTaxiReqBwRoads}
	 * (or generated by the simulation itself) and must still be pending.
	 * This endpoint does NOT fabricate new requests &mdash; it only matches
	 * a taxi to an existing pending request, takes the taxi out
	 * of any stale available / relocation taxi pool, and either starts its
	 * pickup trip or queues the pickup after the taxi's current trip.
	 *
	 * <p>Both {@code "dispatchTaxi"} and {@code "dispTaxiBwRoads"} message
	 * types route to this handler; the {@code BwRoads} suffix is preserved
	 * only as a backward-compatibility alias.
	 *
	 * <p>Output DATA: list of {@code {ID: vehID, reqID, origZone, destZone,
	 * STATUS, WARN?}} entries.
	 */
	private HashMap<String, Object> dispatchTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDReqID>> collectionType = new TypeToken<Collection<VehIDReqID>>() {};
			Collection<VehIDReqID> entries = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			
			for(VehIDReqID entry: entries) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("ID", entry.vehID);
				record.put("reqID", entry.reqID);
				
				Vehicle publicVehicle = ContextCreator.getVehicleContext().getPublicVehicle(entry.vehID);
				if (!(publicVehicle instanceof ElectricTaxi)) {
					ContextCreator.logger.warn("dispatchTaxi: taxi " + entry.vehID + " not found");
					record.put("STATUS", "KO");
					record.put("WARN", "taxi not found");
					jsonData.add(record);
					continue;
				}
				ElectricTaxi veh = (ElectricTaxi) publicVehicle;

				if (isTaxiChargingOrOnChargingTrip(veh)) {
					ContextCreator.logger.warn("dispatchTaxi: vehicle " + entry.vehID + " is charging or on a charging trip");
					removeTaxiFromDispatchPools(veh);
					record.put("STATUS", "KO");
					record.put("WARN", "vehicle is charging or on a charging trip");
					jsonData.add(record);
					continue;
				}
				
				PendingTaxiRequestRef found = findAndRemovePendingTaxiRequest(entry.reqID);
				if (found == null) {
					ContextCreator.logger.warn("dispatchTaxi: request " + entry.reqID + " not found in any pending taxi queue");
					record.put("STATUS", "KO");
					record.put("WARN", "request not pending");
					jsonData.add(record);
					continue;
				}
				
				Zone origZone = found.zone;
				Zone destZone = ContextCreator.getZoneContext().get(found.request.getDestZone());
				if (destZone == null) {
					ContextCreator.logger.warn("dispatchTaxi: destination zone " + found.request.getDestZone() + " for request " + entry.reqID + " not found; re-queueing request");
					reinsertPendingTaxiRequest(found);
					record.put("STATUS", "KO");
					record.put("WARN", "destination zone not found");
					jsonData.add(record);
					continue;
				}

				Request p = found.request;
				int remainingCapacity = veh.remainingCapacity();
				if (remainingCapacity < p.getNumPeople()) {
					ContextCreator.logger.warn("dispatchTaxi: vehicle " + entry.vehID + " remaining capacity "
							+ remainingCapacity + " is smaller than request " + entry.reqID + " passenger number "
							+ p.getNumPeople() + "; re-queueing request");
					reinsertPendingTaxiRequest(found);
					record.put("STATUS", "KO");
					record.put("WARN", "remaining capacity is smaller than request passenger number");
					record.put("remainingCapacity", remainingCapacity);
					record.put("requestPassengers", p.getNumPeople());
					jsonData.add(record);
					continue;
				}
				
				// Take the taxi out of its current zone's available pool and
				// relocation pool. Removing from all zones clears stale pool
				// membership left by external control decisions.
				int curZoneID = veh.getCurrentZone();
				int state = veh.getState();
				removeTaxiFromDispatchPools(veh);
				boolean releasedParkingReservation = false;
				if (veh.getState() == Vehicle.PARKING) {
					Zone parkedZone = ContextCreator.getZoneContext().get(curZoneID);
					veh.releaseParkingSpot(parkedZone);
				} else if (veh.isGoingToReservedParking()) {
					// The current leg can still finish, so its futureSupply is
					// consumed normally at arrival; release only the reserved capacity.
					Zone parkingZone = ContextCreator.getZoneContext().get(veh.getDestID());
					releasedParkingReservation = veh.releaseParkingSpot(parkingZone);
				}
				
				p.matchedTime = ContextCreator.getCurrentTick();
				origZone.taxiPickupRequest += 1;
				origZone.taxiPickupPassengers += p.getNumPeople();
				origZone.taxiServedPassWaitingTime += p.getCurrentWaitingTime();
				
				if (shouldStartDispatchImmediately(state)) {
					destZone.addFutureSupply();
					ArrayList<Request> plist = new ArrayList<Request>();
					plist.add(p);
					removeVehicleFromEnteringQueues(veh);
					veh.servePassenger(plist);
				} else {
					veh.queuePassengerAfterCurrentTrip(p);
				}
				
				record.put("origZone", origZone.getID());
				record.put("destZone", destZone.getID());
				if (releasedParkingReservation) {
					record.put("parkingReservationReleased", true);
				}
				record.put("STATUS", "OK");
				jsonData.add(record);
			}
			
			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing dispatchTaxi: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	private boolean isTaxiChargingOrOnChargingTrip(ElectricTaxi taxi) {
		return taxi.getState() == Vehicle.CHARGING_TRIP || taxi.isOnChargingRoute();
	}

	private boolean shouldStartDispatchImmediately(int taxiState) {
		return taxiState == Vehicle.PARKING
				|| taxiState == Vehicle.CRUISING_TRIP
				|| taxiState == Vehicle.NONE_OF_THE_ABOVE;
	}

	private void removeTaxiFromDispatchPools(ElectricTaxi taxi) {
		ContextCreator.getVehicleContext().removeAvailableTaxiFromAllZones(taxi);
		ContextCreator.getVehicleContext().removeRelocationTaxiFromAllZones(taxi);
	}

	private boolean cancelParkingReservationForRedirect(ElectricTaxi taxi) {
		if (taxi == null || !taxi.isGoingToReservedParking()) {
			return false;
		}
		int oldParkingZoneID = taxi.getDestID();
		Zone oldParkingZone = ContextCreator.getZoneContext().get(oldParkingZoneID);
		boolean released = taxi.releaseParkingSpot(oldParkingZone);
		if (oldParkingZone != null) {
			oldParkingZone.removeFutureSupply();
		}
		return released;
	}
	
	/**
	 * Reposition a taxi to a destination zone.
	 *
	 * <p>Input DATA: list of {@code {vehID, zoneID}}. The taxi must
	 * currently be idle (state {@code PARKING}, {@code CRUISING_TRIP}, or
	 * {@code NONE_OF_THE_ABOVE}) or already traveling to a reserved parking
	 * road; it is removed from its current zone's available pool / parking
	 * stock and dispatched on an {@code INACCESSIBLE_RELOCATION_TRIP} to a road sampled from the
	 * destination zone. On arrival, {@code reachDest} either parks/cruises
	 * normally or, when repositioning is API-controlled, leaves the taxi
	 * idle in state {@code NONE_OF_THE_ABOVE}.
	 *
	 * <p>Output DATA: list of {@code {ID: vehID, zoneID, origZone, STATUS,
	 * WARN?}} entries.
	 */
	private HashMap<String, Object> repositionTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDZoneID>> collectionType = new TypeToken<Collection<VehIDZoneID>>() {};
			Collection<VehIDZoneID> entries = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			
			for(VehIDZoneID entry: entries) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("ID", entry.vehID);
				record.put("zoneID", entry.zoneID);
				
				ElectricTaxi veh = (ElectricTaxi) ContextCreator.getVehicleContext().getPublicVehicle(entry.vehID);
				if (veh == null) {
					ContextCreator.logger.warn("repositionTaxi: vehicle " + entry.vehID + " not found");
					record.put("STATUS", "KO");
					record.put("WARN", "vehicle not found");
					jsonData.add(record);
					continue;
				}
				
				Zone destZone = ContextCreator.getZoneContext().get(entry.zoneID);
				if (destZone == null) {
					ContextCreator.logger.warn("repositionTaxi: destination zone " + entry.zoneID + " not found");
					record.put("STATUS", "KO");
					record.put("WARN", "destination zone not found");
					jsonData.add(record);
					continue;
				}
				if (destZone.getClosestRoad(true) == null) {
					ContextCreator.logger.warn("repositionTaxi: destination zone " + entry.zoneID + " has no road assigned yet");
					record.put("STATUS", "KO");
					record.put("WARN", "destination zone has no road");
					jsonData.add(record);
					continue;
				}
				
				int state = veh.getState();
				boolean goingToReservedParking = veh.isGoingToReservedParking();
				if (state != Vehicle.PARKING && state != Vehicle.CRUISING_TRIP && state != Vehicle.NONE_OF_THE_ABOVE
						&& !goingToReservedParking) {
					ContextCreator.logger.warn("repositionTaxi: vehicle " + entry.vehID + " not in a relocatable state (state=" + state + ")");
					record.put("STATUS", "KO");
					record.put("WARN", "vehicle not idle");
					jsonData.add(record);
					continue;
				}
				
				int curZoneID = veh.getCurrentZone();
				Zone origZone = ContextCreator.getZoneContext().get(curZoneID);
				
				removeTaxiFromDispatchPools(veh);
				boolean releasedParkingReservation = false;
				if (state == Vehicle.PARKING) {
					veh.releaseParkingSpot(origZone);
				} else if (goingToReservedParking) {
					releasedParkingReservation = cancelParkingReservationForRedirect(veh);
				}
				
				destZone.addFutureSupply();
				if (origZone != null) origZone.numberOfRelocatedVehicles += 1;
				
				// ElectricTaxi.relocation handles stopCruising if needed and
				// enters INACCESSIBLE_RELOCATION_TRIP state.
				veh.relocation(destZone.getID(), destZone.sampleRoad(true));
				
				record.put("origZone", curZoneID);
				if (releasedParkingReservation) {
					record.put("parkingReservationReleased", true);
				}
				record.put("STATUS", "OK");
				jsonData.add(record);
			}
			
			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing repositionTaxi: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	/**
	 * Reroute an idle taxi to a reserved parking destination.
	 *
	 * <p>Input DATA: list of {@code {vehID, zoneID?, roadID?}}. If
	 * {@code roadID} is omitted, zone parking is reserved and the taxi routes to
	 * the zone's closest destination road. If {@code zoneID} is omitted, it is
	 * inferred from the target road. When both are supplied, the road must
	 * belong to the zone.
	 */
	private HashMap<String, Object> goParking(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDZoneRoad>> collectionType = new TypeToken<Collection<VehIDZoneRoad>>() {};
			Collection<VehIDZoneRoad> entries = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (VehIDZoneRoad entry : entries) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("ID", entry.vehID);

				Vehicle publicVehicle = ContextCreator.getVehicleContext().getPublicVehicle(entry.vehID);
				if (!(publicVehicle instanceof ElectricTaxi)) {
					ContextCreator.logger.warn("goParking: taxi " + entry.vehID + " not found");
					record.put("STATUS", "KO");
					record.put("WARN", "taxi not found");
					jsonData.add(record);
					continue;
				}
				ElectricTaxi veh = (ElectricTaxi) publicVehicle;

				if (isTaxiChargingOrOnChargingTrip(veh)) {
					ContextCreator.logger.warn("goParking: taxi " + entry.vehID + " is charging or on a charging trip");
					record.put("STATUS", "KO");
					record.put("WARN", "vehicle is charging or on a charging trip");
					jsonData.add(record);
					continue;
				}
				int state = veh.getState();
				if (state != Vehicle.PARKING && state != Vehicle.CRUISING_TRIP && state != Vehicle.NONE_OF_THE_ABOVE) {
					ContextCreator.logger.warn("goParking: taxi " + entry.vehID + " not in a parkable state (state=" + state + ")");
					record.put("STATUS", "KO");
					record.put("WARN", "vehicle not idle");
					jsonData.add(record);
					continue;
				}
				if (veh.hasPassengerAssignments()) {
					ContextCreator.logger.warn("goParking: taxi " + entry.vehID + " has passenger assignments");
					record.put("STATUS", "KO");
					record.put("WARN", "vehicle has passenger assignments");
					jsonData.add(record);
					continue;
				}

				Integer zoneID = firstIntegerOrNull(entry.zoneID, entry.zone, entry.dest, entry.parkingZone);
				String roadID = firstNonBlank(entry.roadID, entry.origID, entry.orig_id, entry.ID);
				boolean roadSpecified = cleanString(roadID) != null;
				Zone targetZone = zoneID == null ? null : ContextCreator.getZoneContext().get(zoneID);
				Road targetRoad = roadSpecified ? findRoadByOrigOrInternalID(roadID) : null;

				if (zoneID != null && targetZone == null) {
					record.put("zoneID", zoneID);
					record.put("STATUS", "KO");
					record.put("WARN", "target zone not found");
					jsonData.add(record);
					continue;
				}
				if (roadID != null && targetRoad == null) {
					record.put("roadID", roadID);
					record.put("STATUS", "KO");
					record.put("WARN", "target road not found");
					jsonData.add(record);
					continue;
				}
				if (targetRoad == null && targetZone != null) {
					targetRoad = parkingRoadForZone(targetZone);
					if (targetRoad == null) {
						record.put("zoneID", targetZone.getID());
						record.put("STATUS", "KO");
						record.put("WARN", "target zone has no parking road");
						jsonData.add(record);
						continue;
					}
				}
				if (targetZone == null && targetRoad != null) {
					targetZone = parkingZoneForRoad(targetRoad);
				}
				if (targetZone == null) {
					record.put("STATUS", "KO");
					record.put("WARN", "target zone is required or must be inferable from road");
					jsonData.add(record);
					continue;
				}
				if (targetRoad == null) {
					record.put("zoneID", targetZone.getID());
					record.put("STATUS", "KO");
					record.put("WARN", "target road not found");
					jsonData.add(record);
					continue;
				}
				record.put("zoneID", targetZone.getID());
				record.put("roadID", targetRoad.getOrigID());
				if (!roadBelongsToZone(targetRoad, targetZone)) {
					record.put("STATUS", "KO");
					record.put("WARN", "target road does not belong to target zone");
					jsonData.add(record);
					continue;
				}
				if (!targetRoad.canBeDest()) {
					record.put("STATUS", "KO");
					record.put("WARN", "target road cannot be used as a parking destination");
					jsonData.add(record);
					continue;
				}
				boolean alreadyParkedThere = roadSpecified
						? state == Vehicle.PARKING && veh.getCurrentParkingRoad() == targetRoad.getID()
						: state == Vehicle.PARKING && veh.getCurrentZone() == targetZone.getID()
								&& veh.getCurrentParkingRoad() < 0;
				if (!alreadyParkedThere && roadSpecified && !targetRoad.hasParkingSpace()) {
					record.put("parking_capacity", targetRoad.getParkingCapacity());
					record.put("parked_num", targetRoad.getParkedNum());
					record.put("STATUS", "KO");
					record.put("WARN", "target road has no parking capacity");
					jsonData.add(record);
					continue;
				}
				if (!alreadyParkedThere && !roadSpecified && targetZone.getCapacity() <= 0) {
					record.put("parking_capacity", targetZone.getCapacity());
					record.put("STATUS", "KO");
					record.put("WARN", "target zone has no parking capacity");
					jsonData.add(record);
					continue;
				}

				boolean parkingDispatched = alreadyParkedThere
						|| (roadSpecified ? veh.goParking(targetRoad) : veh.goParking(targetZone));
				if (!parkingDispatched) {
					if (roadSpecified) {
						record.put("parking_capacity", targetRoad.getParkingCapacity());
						record.put("parked_num", targetRoad.getParkedNum());
					} else {
						record.put("parking_capacity", targetZone.getCapacity());
					}
					record.put("STATUS", "KO");
					record.put("WARN", roadSpecified ? "target road has no parking capacity"
							: "target zone has no parking capacity");
					jsonData.add(record);
					continue;
				}

				record.put("parking_capacity", roadSpecified ? targetRoad.getParkingCapacity() : targetZone.getCapacity());
				if (roadSpecified) {
					record.put("parked_num", targetRoad.getParkedNum());
				}
				record.put("STATUS", "OK");
				jsonData.add(record);
			}

			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing goParking: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	private Road findRoadByOrigOrInternalID(String roadID) {
		String cleanRoadID = cleanString(roadID);
		if (cleanRoadID == null) {
			return null;
		}
		Road road = ContextCreator.getCityContext().findRoadWithOrigID(cleanRoadID);
		if (road != null) {
			return road;
		}
		try {
			return ContextCreator.getRoadContext().get(Integer.valueOf(cleanRoadID));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private Zone parkingZoneForRoad(Road road) {
		if (road == null) {
			return null;
		}
		Zone zone = ContextCreator.getZoneContext().get(road.getNeighboringZone(true));
		if (zone != null) {
			return zone;
		}
		return ContextCreator.getZoneContext().get(road.getNeighboringZone(false));
	}

	private Road parkingRoadForZone(Zone zone) {
		if (zone == null) {
			return null;
		}
		Integer closestRoadID = zone.getClosestRoad(true);
		return closestRoadID == null ? null : ContextCreator.getRoadContext().get(closestRoadID);
	}

	private boolean roadBelongsToZone(Road road, Zone zone) {
		if (road == null || zone == null) {
			return false;
		}
		return road.getNeighboringZone(true) == zone.getID()
				|| road.getNeighboringZone(false) == zone.getID();
	}
	
	// Lightweight record locating a pending taxi request inside the
	// simulation. Used by dispatchTaxi to atomically remove a request from
	// its host queue and re-queue on failure.
	private static class PendingTaxiRequestRef {
		Zone zone;
		Request request;
		// One of: "queue", "sharable", "toAdd"
		String source;
		int sharableDestination;
	}
	
	/**
	 * Search every zone's pending-taxi structures for the given request ID,
	 * remove it from the first container that contains it, and adjust
	 * zone-level counters. Returns null if no pending request matches.
	 *
	 * Containers searched, in order:
	 *   - Zone.requestInQueueForTaxi (counted in nRequestForTaxi)
	 *   - Zone.sharableRequestForTaxi (counted in nRequestForTaxi)
	 *   - Zone.toAddRequestForTaxi (NOT yet counted; populated by
	 *     insertTaxiPass and drained by processToAddPassengers)
	 */
	private PendingTaxiRequestRef findAndRemovePendingTaxiRequest(int reqID) {
		return findAndRemovePendingTaxiRequest(reqID, null);
	}

	private PendingTaxiRequestRef findAndRemovePendingTaxiRequest(int reqID, Integer zoneID) {
		if (zoneID != null) {
			Zone z = ContextCreator.getZoneContext().get(zoneID);
			return z == null ? null : findAndRemovePendingTaxiRequestInZone(z, reqID);
		}
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			PendingTaxiRequestRef ref = findAndRemovePendingTaxiRequestInZone(z, reqID);
			if (ref != null) {
				return ref;
			}
		}
		return null;
	}

	private PendingTaxiRequestRef findAndRemovePendingTaxiRequestInZone(Zone z, int reqID) {
		Iterator<Request> it = z.getTaxiRequestQueue().iterator();
		while (it.hasNext()) {
			Request r = it.next();
			if (r.getID() == reqID) {
				it.remove();
				z.setNRequestForTaxi(z.getTaxiRequestNum() - 1);
				PendingTaxiRequestRef ref = new PendingTaxiRequestRef();
				ref.zone = z;
				ref.request = r;
				ref.source = "queue";
				return ref;
			}
		}
		for (Map.Entry<Integer, Queue<Request>> e : z.getSharableRequestForTaxi().entrySet()) {
			Iterator<Request> sit = e.getValue().iterator();
			while (sit.hasNext()) {
				Request r = sit.next();
				if (r.getID() == reqID) {
					sit.remove();
					z.setNRequestForTaxi(z.getTaxiRequestNum() - 1);
					PendingTaxiRequestRef ref = new PendingTaxiRequestRef();
					ref.zone = z;
					ref.request = r;
					ref.source = "sharable";
					ref.sharableDestination = e.getKey();
					return ref;
				}
			}
		}
		Iterator<Request> tit = z.getToAddTaxiRequestQueue().iterator();
		while (tit.hasNext()) {
			Request r = tit.next();
			if (r.getID() == reqID) {
				tit.remove();
				PendingTaxiRequestRef ref = new PendingTaxiRequestRef();
				ref.zone = z;
				ref.request = r;
				ref.source = "toAdd";
				return ref;
			}
		}
		return null;
	}
	
	/**
	 * Restore a previously-removed pending taxi request back to its
	 * original container. Used when dispatch fails after we've already
	 * pulled the request out, so the next dispatch attempt can still find it.
	 */
	private void reinsertPendingTaxiRequest(PendingTaxiRequestRef ref) {
		Zone z = ref.zone;
		Request r = ref.request;
		if ("sharable".equals(ref.source)) {
			Map<Integer, Queue<Request>> map = z.getSharableRequestForTaxi();
			Queue<Request> q = map.get(ref.sharableDestination);
			if (q == null) {
				q = new LinkedList<Request>();
				map.put(ref.sharableDestination, q);
			}
			q.add(r);
			z.setNRequestForTaxi(z.getTaxiRequestNum() + 1);
		} else if ("toAdd".equals(ref.source)) {
			z.getToAddTaxiRequestQueue().add(r);
		} else {
			z.getTaxiRequestQueue().add(r);
			z.setNRequestForTaxi(z.getTaxiRequestNum() + 1);
		}
	}
	
	private static class PendingBusRequestRef {
		Zone zone;
		Request request;
		// One of: "queue", "toAdd"
		String source;
	}

	private PendingBusRequestRef findAndRemovePendingBusRequest(int reqID) {
		return findAndRemovePendingBusRequest(reqID, null);
	}

	private PendingBusRequestRef findAndRemovePendingBusRequest(int reqID, Integer zoneID) {
		if (zoneID != null) {
			Zone z = ContextCreator.getZoneContext().get(zoneID);
			return z == null ? null : findAndRemovePendingBusRequestInZone(z, reqID);
		}
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			PendingBusRequestRef ref = findAndRemovePendingBusRequestInZone(z, reqID);
			if (ref != null) {
				return ref;
			}
		}
		return null;
	}

	private PendingBusRequestRef findAndRemovePendingBusRequestInZone(Zone z, int reqID) {
		Iterator<Request> it = z.getBusRequestQueue().iterator();
		while (it.hasNext()) {
			Request r = it.next();
			if (r.getID() == reqID) {
				it.remove();
				z.setNRequestForBus(z.getBusRequestNum() - 1);
				PendingBusRequestRef ref = new PendingBusRequestRef();
				ref.zone = z;
				ref.request = r;
				ref.source = "queue";
				return ref;
			}
		}
		Iterator<Request> tit = z.getToAddBusRequestQueue().iterator();
		while (tit.hasNext()) {
			Request r = tit.next();
			if (r.getID() == reqID) {
				tit.remove();
				PendingBusRequestRef ref = new PendingBusRequestRef();
				ref.zone = z;
				ref.request = r;
				ref.source = "toAdd";
				return ref;
			}
		}
		return null;
	}

	private void reinsertPendingBusRequest(PendingBusRequestRef ref) {
		Zone z = ref.zone;
		Request r = ref.request;
		if ("toAdd".equals(ref.source)) {
			z.getToAddBusRequestQueue().add(r);
		} else {
			z.getBusRequestQueue().add(r);
			z.setNRequestForBus(z.getBusRequestNum() + 1);
		}
	}

	private static class CancelRequestEntry {
		Integer reqID;
		Integer zoneID;
	}

	private ArrayList<CancelRequestEntry> parseCancelRequestEntries(Object data) {
		ArrayList<CancelRequestEntry> entries = new ArrayList<CancelRequestEntry>();
		appendCancelRequestEntry(entries, data);
		return entries;
	}

	private void appendCancelRequestEntry(ArrayList<CancelRequestEntry> entries, Object entry) {
		if (entry == null) return;
		if (entry instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) entry;
			CancelRequestEntry cancelEntry = new CancelRequestEntry();
			cancelEntry.reqID = integerValue(firstPresent(record, "reqID", "requestID", "requestId", "ID", "id"));
			cancelEntry.zoneID = integerValue(firstPresent(record,
					"zoneID", "zoneId", "zone", "originZone", "origZone", "origin"));
			if (cancelEntry.reqID != null || cancelEntry.zoneID != null) {
				entries.add(cancelEntry);
			}
		} else if (entry instanceof Iterable<?>) {
			for (Object value : (Iterable<?>) entry) {
				appendCancelRequestEntry(entries, value);
			}
		} else {
			CancelRequestEntry cancelEntry = new CancelRequestEntry();
			cancelEntry.reqID = integerValue(entry);
			if (cancelEntry.reqID != null) {
				entries.add(cancelEntry);
			}
		}
	}

	private void recordTaxiRequestLeft(PendingTaxiRequestRef ref) {
		if (ref == null || ref.request == null) return;
		Zone z = ref.zone != null ? ref.zone : ContextCreator.getZoneContext().get(ref.request.getOriginZone());
		if (z == null) return;
		z.taxiLeavedPassWaitingTime += ref.request.getCurrentWaitingTime();
		z.numberOfLeavedTaxiRequest += 1;
		z.numberOfLeavedTaxiPassengers += ref.request.getNumPeople();
	}

	private void recordBusRequestLeft(PendingBusRequestRef ref) {
		if (ref == null || ref.request == null) return;
		Zone z = ref.zone != null ? ref.zone : ContextCreator.getZoneContext().get(ref.request.getOriginZone());
		if (z == null) return;
		z.busLeavedPassWaitingTime += ref.request.getCurrentWaitingTime();
		z.numberOfLeavedBusRequest += 1;
		z.numberOfLeavedBusPassengers += ref.request.getNumPeople();
	}

	private static class RequestQueueRemoval {
		Request request;
		int index;

		RequestQueueRemoval(Request request, int index) {
			this.request = request;
			this.index = index;
		}
	}

	private RequestQueueRemoval removeRequestFromQueue(Queue<Request> requests, int reqID) {
		if (requests == null) return null;
		int index = 0;
		Iterator<Request> it = requests.iterator();
		while (it.hasNext()) {
			Request request = it.next();
			if (request != null && request.getID() == reqID) {
				it.remove();
				return new RequestQueueRemoval(request, index);
			}
			index++;
		}
		return null;
	}

	private Request findRequestInQueue(Queue<Request> requests, int reqID) {
		if (requests == null) return null;
		for (Request request : requests) {
			if (request != null && request.getID() == reqID) {
				return request;
			}
		}
		return null;
	}

	private boolean requestOriginMatchesZone(Request request, int zoneID) {
		return request != null && request.getOriginZone() == zoneID;
	}

	private static class MatchedTaxiCancelResult {
		int vehicleID;
		Request request;
		boolean statusOK;
		String warn;
		boolean removedPickupTrip;
		boolean removedDropoffTrip;
		boolean currentTripRemoved;
		boolean startedNextTrip;
		boolean availableAfterCancellation;
		int availableZone = -1;
	}

	private MatchedTaxiCancelResult cancelMatchedTaxiRequest(int reqID, int zoneID) {
		Vehicle pickupVehicle = ContextCreator.getVehicleContext().getPickupTaxiForRequest(reqID);
		if (pickupVehicle instanceof ElectricTaxi) {
			MatchedTaxiCancelResult result = cancelPickupTaxiRequest((ElectricTaxi) pickupVehicle, reqID, zoneID);
			if (result != null) return result;
		}

		Vehicle occupiedVehicle = ContextCreator.getVehicleContext().getOccupiedTaxiForRequest(reqID);
		if (occupiedVehicle instanceof ElectricTaxi) {
			MatchedTaxiCancelResult result = rejectOccupiedTaxiCancellation((ElectricTaxi) occupiedVehicle, reqID, zoneID);
			if (result != null) return result;
		}

		for (ElectricTaxi taxi : ContextCreator.getVehicleContext().getTaxis()) {
			MatchedTaxiCancelResult result = cancelPickupTaxiRequest(taxi, reqID, zoneID);
			if (result != null) return result;

			result = rejectOccupiedTaxiCancellation(taxi, reqID, zoneID);
			if (result != null) return result;
		}
		return null;
	}

	private MatchedTaxiCancelResult cancelPickupTaxiRequest(ElectricTaxi taxi, int reqID, int zoneID) {
		Request request = findRequestInQueue(taxi.getToBoardRequests(), reqID);
		if (request == null) {
			ContextCreator.getVehicleContext().removePickupTaxiRequest(reqID);
			return null;
		}

		MatchedTaxiCancelResult result = new MatchedTaxiCancelResult();
		result.vehicleID = taxi.getID();
		result.request = request;
		if (!requestOriginMatchesZone(request, zoneID)) {
			result.statusOK = false;
			result.warn = "request zone mismatch";
			return result;
		}

		RequestQueueRemoval pickup = removeRequestFromQueue(taxi.getToBoardRequests(), reqID);
		if (pickup == null) {
			ContextCreator.getVehicleContext().removePickupTaxiRequest(reqID);
			return null;
		}

		ContextCreator.getVehicleContext().removePickupTaxiRequest(reqID);
		result.request = pickup.request;
		result.statusOK = true;
		taxi.setPassNum(Math.max(0, taxi.getPassNum() - pickup.request.getNumPeople()));
		if (taxi.getState() == Vehicle.PICKUP_TRIP) {
			result.currentTripRemoved = pickup.index == 0;
			if (result.currentTripRemoved) {
				result.removedPickupTrip = true;
				removeFutureSupplyForRequest(pickup.request);
				TaxiAdvanceResult advance = advanceTaxiAfterCurrentCancellation(taxi);
				result.startedNextTrip = advance.advanced;
				result.availableAfterCancellation = advance.available;
				result.availableZone = advance.availableZone;
				if (advance.nextRequest != null) {
					addFutureSupplyForRequest(advance.nextRequest);
				}
			} else {
				result.removedPickupTrip = removePlanForRequest(taxi, pickup.request, true, pickup.index);
			}
		} else {
			result.removedPickupTrip = removePlanForRequest(taxi, pickup.request, true, pickup.index);
			if (!taxi.hasPassengerAssignments() && isIdleTaxiState(taxi)) {
				result.availableZone = makeTaxiAvailableAfterCancellation(taxi);
				result.availableAfterCancellation = result.availableZone >= 0;
			}
		}
		return result;
	}

	private MatchedTaxiCancelResult rejectOccupiedTaxiCancellation(ElectricTaxi taxi, int reqID, int zoneID) {
		Request request = findRequestInQueue(taxi.getOnBoardRequests(), reqID);
		if (request == null) {
			ContextCreator.getVehicleContext().removeOccupiedTaxiRequest(reqID);
			return null;
		}

		MatchedTaxiCancelResult result = new MatchedTaxiCancelResult();
		result.vehicleID = taxi.getID();
		result.request = request;
		result.statusOK = false;
		if (!requestOriginMatchesZone(request, zoneID)) {
			result.warn = "request zone mismatch";
		} else {
			result.warn = "request is on an occupied taxi trip and cannot be cancelled";
		}
		return result;
	}

	private static class TaxiAdvanceResult {
		Request nextRequest;
		boolean advanced;
		boolean available;
		int availableZone = -1;
	}

	private TaxiAdvanceResult advanceTaxiAfterCurrentCancellation(ElectricTaxi taxi) {
		TaxiAdvanceResult result = new TaxiAdvanceResult();
		Request nextPickup = taxi.getToBoardRequests().peek();
		if (nextPickup != null) {
			ensureTaxiPlanAtIndexOne(taxi, nextPickup, true);
			taxi.setState(Vehicle.PICKUP_TRIP);
			result.nextRequest = nextPickup;
		} else {
			Request nextDropoff = taxi.getOnBoardRequests().peek();
			if (nextDropoff != null) {
				ensureTaxiPlanAtIndexOne(taxi, nextDropoff, false);
				taxi.setState(Vehicle.OCCUPIED_TRIP);
				result.nextRequest = nextDropoff;
			}
		}

		if (result.nextRequest != null && taxi.getPlan().size() >= 2) {
			taxi.setNextPlan();
			if (taxi.isOnRoad()) {
				taxi.departure();
			}
			result.advanced = true;
		} else {
			if (!taxi.getPlan().isEmpty()) {
				taxi.getPlan().remove(0);
			}
			result.availableZone = makeTaxiAvailableAfterCancellation(taxi);
			result.available = result.availableZone >= 0;
		}
		return result;
	}

	private int makeTaxiAvailableAfterCancellation(ElectricTaxi taxi) {
		if (taxi == null) return -1;
		int zoneID = resolveTaxiAvailabilityZone(taxi);
		if (zoneID < 0) return -1;
		Zone zone = ContextCreator.getZoneContext().get(zoneID);
		if (zone == null) return -1;
		removeVehicleFromEnteringQueues(taxi);
		taxi.becomeAvailableForExternalControl(zone);
		return zoneID;
	}

	private int resolveTaxiAvailabilityZone(ElectricTaxi taxi) {
		if (taxi == null) return -1;
		Road road = taxi.getRoad();
		if (road != null) {
			int zoneID = road.getNeighboringZone(false);
			if (ContextCreator.getZoneContext().get(zoneID) != null) {
				return zoneID;
			}
			zoneID = road.getNeighboringZone(true);
			if (ContextCreator.getZoneContext().get(zoneID) != null) {
				return zoneID;
			}
		}
		if (ContextCreator.getZoneContext().get(taxi.getCurrentZone()) != null) {
			return taxi.getCurrentZone();
		}
		if (ContextCreator.getZoneContext().get(taxi.getDestID()) != null) {
			return taxi.getDestID();
		}
		if (ContextCreator.getZoneContext().get(taxi.getOriginID()) != null) {
			return taxi.getOriginID();
		}
		return -1;
	}

	private boolean isIdleTaxiState(ElectricTaxi taxi) {
		if (taxi == null || taxi.isOnChargingRoute()) return false;
		int state = taxi.getState();
		return state == Vehicle.PARKING
				|| state == Vehicle.CRUISING_TRIP
				|| state == Vehicle.NONE_OF_THE_ABOVE;
	}

	private void ensureTaxiPlanAtIndexOne(ElectricTaxi taxi, Request request, boolean pickup) {
		ArrayList<Plan> plans = taxi.getPlan();
		if (plans.isEmpty()) {
			plans.add(anchorPlanForTaxi(taxi, request, pickup));
		}
		if (plans.size() > 1 && planMatchesRequest(plans.get(1), request, pickup)) {
			return;
		}
		Plan nextPlan = planForRequest(request, pickup);
		if (plans.size() <= 1) {
			plans.add(nextPlan);
		} else {
			plans.add(1, nextPlan);
		}
	}

	private Plan anchorPlanForTaxi(ElectricTaxi taxi, Request request, boolean pickup) {
		int zoneID = taxi.getDestID() >= 0 ? taxi.getDestID()
				: (pickup ? request.getOriginZone() : request.getDestZone());
		int roadID = taxi.getDestRoad() >= 0 ? taxi.getDestRoad()
				: (pickup ? request.getOriginRoad() : request.getDestRoad());
		return new Plan(zoneID, roadID, ContextCreator.getNextTick());
	}

	private Plan planForRequest(Request request, boolean pickup) {
		if (pickup) {
			return new Plan(request.getOriginZone(), request.getOriginRoad(), ContextCreator.getNextTick());
		}
		return new Plan(request.getDestZone(), request.getDestRoad(), ContextCreator.getNextTick());
	}

	private boolean removePlanForRequest(Vehicle vehicle, Request request, boolean pickup, int expectedIndex) {
		if (vehicle == null || request == null) return false;
		ArrayList<Plan> plans = vehicle.getPlan();
		if (plans == null || plans.isEmpty()) return false;

		if (expectedIndex > 0 && expectedIndex < plans.size()
				&& planMatchesRequest(plans.get(expectedIndex), request, pickup)) {
			plans.remove(expectedIndex);
			return true;
		}

		int matchIndex = -1;
		for (int i = 1; i < plans.size(); i++) {
			if (planMatchesRequest(plans.get(i), request, pickup)) {
				if (matchIndex >= 0) {
					return false;
				}
				matchIndex = i;
			}
		}
		if (matchIndex >= 0) {
			plans.remove(matchIndex);
			return true;
		}
		return false;
	}

	private boolean planMatchesRequest(Plan plan, Request request, boolean pickup) {
		if (plan == null || request == null) return false;
		if (pickup) {
			return plan.getDestZoneID() == request.getOriginZone()
					&& plan.getDestRoadID() == request.getOriginRoad();
		}
		return plan.getDestZoneID() == request.getDestZone()
				&& plan.getDestRoadID() == request.getDestRoad();
	}

	private void removeFutureSupplyForRequest(Request request) {
		if (request == null) return;
		Zone z = ContextCreator.getZoneContext().get(request.getDestZone());
		if (z != null) {
			z.removeFutureSupply();
		}
	}

	private void addFutureSupplyForRequest(Request request) {
		if (request == null) return;
		Zone z = ContextCreator.getZoneContext().get(request.getDestZone());
		if (z != null) {
			z.addFutureSupply();
		}
	}

	private static class MatchedBusCancelResult {
		int vehicleID;
		int stopIndex;
		boolean onBoard;
		Request request;
		boolean statusOK;
		String warn;
	}

	private MatchedBusCancelResult cancelMatchedBusRequest(int reqID, int zoneID) {
		for (ElectricBus bus : ContextCreator.getVehicleContext().getBuses()) {
			ArrayList<Queue<Request>> toBoard = bus.getToBoardRequests();
			for (int i = 0; i < toBoard.size(); i++) {
				Request request = findRequestInQueue(toBoard.get(i), reqID);
				if (request != null) {
					MatchedBusCancelResult result = new MatchedBusCancelResult();
					result.vehicleID = bus.getID();
					result.stopIndex = i;
					result.onBoard = false;
					result.request = request;
					if (!requestOriginMatchesZone(request, zoneID)) {
						result.statusOK = false;
						result.warn = "request zone mismatch";
						return result;
					}
					RequestQueueRemoval removed = removeRequestFromQueue(toBoard.get(i), reqID);
					result.request = removed.request;
					result.statusOK = true;
					return result;
				}
			}

			ArrayList<Queue<Request>> onBoard = bus.getOnBoardRequests();
			for (int i = 0; i < onBoard.size(); i++) {
				Request request = findRequestInQueue(onBoard.get(i), reqID);
				if (request != null) {
					MatchedBusCancelResult result = new MatchedBusCancelResult();
					result.vehicleID = bus.getID();
					result.stopIndex = i;
					result.onBoard = true;
					result.request = request;
					if (!requestOriginMatchesZone(request, zoneID)) {
						result.statusOK = false;
						result.warn = "request zone mismatch";
						return result;
					}
					RequestQueueRemoval removed = removeRequestFromQueue(onBoard.get(i), reqID);
					bus.setPassNum(Math.max(0, bus.getPassNum() - removed.request.getNumPeople()));
					result.request = removed.request;
					result.statusOK = true;
					return result;
				}
			}
		}
		return null;
	}

	/**
	 * Override {@link Request} maximum waiting tolerance when the caller
	 * specifies a positive value ({@code max_waiting_time} in ticks). Same units
	 * as {@link Request#getCurrentWaitingTime()} accumulation per zone refresh.
	 */
	private static void applyOptionalMaxWaitingTime(Request req, Integer maxWaitingTicks) {
		if (maxWaitingTicks != null && maxWaitingTicks > 0) {
			req.setMaxWaitingTime(maxWaitingTicks);
		}
	}

	private static class BusRequestMatch {
		int routeID;
		int originStopIndex;
		int destStopIndex;

		BusRequestMatch(int routeID, int originStopIndex, int destStopIndex) {
			this.routeID = routeID;
			this.originStopIndex = originStopIndex;
			this.destStopIndex = destStopIndex;
		}
	}

	private static BusRequestMatch findBusRequestMatch(int originZoneID, int destZoneID) {
		ArrayList<Integer> routeIDs = new ArrayList<Integer>();
		Zone originZone = ContextCreator.getZoneContext().get(originZoneID);
		if (originZone != null && originZone.traversingBusRoutes.containsKey(destZoneID)) {
			routeIDs.addAll(originZone.traversingBusRoutes.get(destZoneID));
		} else {
			routeIDs.addAll(ContextCreator.bus_schedule.getRouteIDs());
		}

		for (int rID : routeIDs) {
			ArrayList<Integer> stops = ContextCreator.bus_schedule.getStopZones(rID);
			if (stops == null) continue;
			for (int i = 0; i < stops.size(); i++) {
				if (!stops.get(i).equals(originZoneID)) continue;
				for (int j = i + 1; j < stops.size(); j++) {
					if (!stops.get(j).equals(destZoneID)) continue;
					if (ContextCreator.bus_schedule.getStopRoad(rID, i) != null
							&& ContextCreator.bus_schedule.getStopRoad(rID, j) != null) {
						return new BusRequestMatch(rID, i, j);
					}
				}
			}
		}
		return null;
	}
	
	// =============================================================
	// RIDE-HAILING: ADD PENDING REQUESTS / BUS ASSIGNMENT
	// (the only entry points that create Request objects)
	// =============================================================
	
	/**
	 * Add one or more pending taxi requests to a zone's pending queue
	 * (specified by zone IDs).
	 *
	 * <p>Input DATA: list of {@code {zoneID, dest, num,
	 * max_waiting_time?}} where {@code zoneID} is the origin zone,
	 * {@code dest} is the destination zone, and {@code num} is the party
	 * size. Optional {@code max_waiting_time}: positive integer, maximum wait
	 * before the passenger abandons the queue ({@link Request#setMaxWaitingTime(int)}
	 * in simulation ticks); if omitted or non-positive, the zone's default
	 * taxi waiting tolerance applies.
	 *
	 * <p>Output DATA: list of {@code {ID: zoneID, reqID, STATUS}} entries.
	 * The returned {@code reqID} is the canonical handle the caller should
	 * use to dispatch the request later via {@link #dispatchTaxi} or to
	 * inspect it via the query API.
	 */
	private HashMap<String, Object> addTaxiRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<OriginDestNumMaxW>> collectionType = new TypeToken<Collection<OriginDestNumMaxW>>() {};
				Collection<OriginDestNumMaxW> zoneIDOrigDestNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(OriginDestNumMaxW zoneIDOrigDestNum: zoneIDOrigDestNums) {
					Zone z1 = ContextCreator.getZoneContext().get(zoneIDOrigDestNum.zoneID);
					Zone z2 = ContextCreator.getZoneContext().get(zoneIDOrigDestNum.dest);
					if(z1 != null && z2 != null) {
						// generate request
						Request p = new Request(z1.getID(), z2.getID(), z1.sampleRoad(false), z2.sampleRoad(true), zoneIDOrigDestNum.num);
						applyOptionalMaxWaitingTime(p, zoneIDOrigDestNum.maxWaitingTime);
						z1.insertTaxiPass(p);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestNum.zoneID);
			    		record2.put("reqID", p.getID());
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestNum.zoneID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Add one or more pending taxi requests, specified by origin and
	 * destination road IDs instead of zone IDs. The origin road's
	 * neighboring zone is used as the request's origin zone.
	 *
	 * <p>Input DATA: list of {@code {orig, dest, num}} where {@code orig}
	 * and {@code dest} are original road IDs.
	 *
	 * <p>Output DATA: list of {@code {ID: orig, reqID, STATUS}} entries.
	 */
	private HashMap<String, Object> addTaxiReqBwRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<OrigRoadDestRoadNumMaxW>> collectionType = new TypeToken<Collection<OrigRoadDestRoadNumMaxW>>() {};
				Collection<OrigRoadDestRoadNumMaxW> origRoadDestRoadNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(OrigRoadDestRoadNumMaxW origRoadDestRoadNum: origRoadDestRoadNums) {
					Road r1 = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoadNum.orig);
					Road r2 = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoadNum.dest);
					if(r1 != null && r2 != null) {
						Zone z1 = ContextCreator.getZoneContext().get(r1.getNeighboringZone(false));
						// generate request
						Request p = new Request(z1.getID(), r2.getNeighboringZone(true), r1.getID(), r2.getID(), origRoadDestRoadNum.num);
						applyOptionalMaxWaitingTime(p, origRoadDestRoadNum.maxWaitingTime);
						z1.insertTaxiPass(p);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", origRoadDestRoadNum.orig);
			    		record2.put("reqID", p.getID());
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", origRoadDestRoadNum.orig);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Match an existing pending bus request to a specific bus along its
	 * current route.
	 *
	 * <p>Input DATA: list of {@code {busID, reqID}}. The request must have
	 * already been created by {@link #addBusRequests}.
	 *
	 * <p>Output DATA: list of {@code {ID: busID, busID, reqID, STATUS}}
	 * entries.
	 */
	private HashMap<String, Object> assignRequestToBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<BusIDReqID>> collectionType = new TypeToken<Collection<BusIDReqID>>() {};
				Collection<BusIDReqID> busIDReqIDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(BusIDReqID busIDReqID: busIDReqIDs) {
					int busID = busIDReqID.getBusID();
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", busID);
					record2.put("busID", busID);
					record2.put("reqID", busIDReqID.reqID);

					if (busIDReqID.reqID == null) {
						record2.put("STATUS", "KO");
						record2.put("WARN", "request ID missing");
						jsonData.add(record2);
						continue;
					}

					ElectricBus veh = ContextCreator.getVehicleContext().getBus(busID);
					if (veh == null) {
						record2.put("STATUS", "KO");
						record2.put("WARN", "bus not found");
						jsonData.add(record2);
						continue;
					}
					
					PendingBusRequestRef ref = findAndRemovePendingBusRequest(busIDReqID.reqID);
					if (ref == null) {
						record2.put("STATUS", "KO");
						record2.put("WARN", "pending bus request not found");
						jsonData.add(record2);
						continue;
					}

					Request p = ref.request;
					if (!veh.servable(p)) {
						reinsertPendingBusRequest(ref);
						record2.put("STATUS", "KO");
						record2.put("WARN", "request not servable by bus route");
						jsonData.add(record2);
						continue;
					}

					p.setBusRoute(veh.getRouteID());
					p.matchedTime = ContextCreator.getCurrentTick();
					if(veh.addToBoardPass(p)) {
						ref.zone.busPickupRequest += 1;
						ref.zone.busPickupPassengers += p.getNumPeople();
						ref.zone.busServedPassWaitingTime += p.getCurrentWaitingTime();
						record2.put("STATUS", "OK");
					} else {
						reinsertPendingBusRequest(ref);
						record2.put("STATUS", "KO");
						record2.put("WARN", "request not added to bus");
					}
					jsonData.add(record2);
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Add one or more pending bus requests to a zone's bus queue. The
	 * origin and destination zones must both appear (in order) on the
	 * same bus route.
	 *
	 * <p>Input DATA: list of {@code {zoneID, dest, num,
	 * max_waiting_time?}}. Optional {@code max_waiting_time}: positive integer,
	 * maximum wait before the passenger abandons the queue (simulation ticks);
	 * if omitted or non-positive, the default bus tolerance applies.
	 *
	 * <p>Output DATA: list of {@code {ID: zoneID, reqID, STATUS}} entries.
	 */
	private HashMap<String, Object> addBusRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<OriginDestNumMaxW>> collectionType = new TypeToken<Collection<OriginDestNumMaxW>>() {};
				Collection<OriginDestNumMaxW> zoneIDOrigDestRouteNameNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(OriginDestNumMaxW zoneIDOrigDestRouteNameNum: zoneIDOrigDestRouteNameNums) {
					Zone z1 = ContextCreator.getZoneContext().get(zoneIDOrigDestRouteNameNum.zoneID);
					Zone z2 = ContextCreator.getZoneContext().get(zoneIDOrigDestRouteNameNum.dest);
					BusRequestMatch match = findBusRequestMatch(zoneIDOrigDestRouteNameNum.zoneID, zoneIDOrigDestRouteNameNum.dest);
					if(z1 != null && z2 != null && match != null) {
						// generate request
						Request p = new Request(z1.getID(), z2.getID(), ContextCreator.bus_schedule.getStopRoad(match.routeID, match.originStopIndex).getID(), ContextCreator.bus_schedule.getStopRoad(match.routeID, match.destStopIndex).getID(), zoneIDOrigDestRouteNameNum.num);
						p.setBusRoute(match.routeID);
						applyOptionalMaxWaitingTime(p, zoneIDOrigDestRouteNameNum.maxWaitingTime);
						z1.insertBusPass(p);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestRouteNameNum.zoneID);
						record2.put("reqID", p.getID());
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestRouteNameNum.zoneID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	 
	// =============================================================
	// BUS ROUTES, RUNS & STOPS
	// (insertStopToRoute / removeStopFromRoute live further down,
	// near updateVehicleRoute, but logically belong with this group.)
	// =============================================================
	
	/**
	 * Register a new named bus route by listing its ordered stops (zones)
	 * and the road segments connecting them.
	 *
	 * <p>Input DATA: list of {@code {routeName, zones, roads}}.
	 */
	private HashMap<String, Object> addBusRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameZonesRoads>> collectionType = new TypeToken<Collection<RouteNameZonesRoads>>() {};
				Collection<RouteNameZonesRoads> routeNameZonesRoads = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(RouteNameZonesRoads routeNameZonesRoad: routeNameZonesRoads) {
					if(ContextCreator.bus_schedule.insertNewRouteByRoadNames(routeNameZonesRoad.routeName, routeNameZonesRoad.zones, routeNameZonesRoad.roads)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("routeName", routeNameZonesRoad.routeName);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("routeName", routeNameZonesRoad.routeName);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
					}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		
		return jsonAns;
	}
	
	/**
	 * Like {@link #addBusRoute} but with explicitly-provided per-segment
	 * driving paths, so the system doesn't have to compute them.
	 *
	 * <p>Input DATA: list of {@code {routeName, zones, roads, paths}}.
	 */
	private HashMap<String, Object> addBusRouteWithPath(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameZonesRoadsPath>> collectionType = new TypeToken<Collection<RouteNameZonesRoadsPath>>() {};
				Collection<RouteNameZonesRoadsPath> routeNameZonesRoadsPaths = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(RouteNameZonesRoadsPath routeNameZonesRoadsPath: routeNameZonesRoadsPaths) {
					if(ContextCreator.bus_schedule.insertNewRouteByRoadNames(routeNameZonesRoadsPath.routeName, routeNameZonesRoadsPath.zones, routeNameZonesRoadsPath.roads, routeNameZonesRoadsPath.paths)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("routeName", routeNameZonesRoadsPath.routeName);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("routeName", routeNameZonesRoadsPath.routeName);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
					}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		
		return jsonAns;
	}
	
	/**
	 * Schedule one or more new bus runs (departures) on an existing
	 * named route.
	 *
	 * <p>Input DATA: list of {@code {routeName, departTime}} where
	 * {@code departTime} is in simulation ticks.
	 */
	private HashMap<String, Object> addBusRun(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameDepartTime>> collectionType = new TypeToken<Collection<RouteNameDepartTime>>() {};
				Collection<RouteNameDepartTime> routeNameDepartTimes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(RouteNameDepartTime routeNameDepartTime: routeNameDepartTimes) {
					if(ContextCreator.bus_schedule.insertBusRun(routeNameDepartTime.routeName, routeNameDepartTime.departTime)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("routeName", routeNameDepartTime.routeName);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("routeName", routeNameDepartTime.routeName);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
					}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		
		return jsonAns;
	}
	
	// =============================================================
	// TRAFFIC SIGNALS
	// =============================================================
	
	/**
	 * Force a traffic signal into a specified phase, optionally with a
	 * non-zero phase-time offset.
	 *
	 * <p>Input DATA: list of {@code {signalID, targetPhase, phaseTime?}}
	 * where {@code targetPhase} is {@code 0=Green / 1=Yellow / 2=Red} and
	 * {@code phaseTime} defaults to 0.
	 */
	private HashMap<String, Object> updateSignal(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message. Expected: [{signalID, targetPhase, phaseTime(optional)}, ...]");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalIDPhase>> collectionType = new TypeToken<Collection<SignalIDPhase>>() {};
				Collection<SignalIDPhase> signalIDPhases = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(SignalIDPhase signalIDPhase: signalIDPhases) {
					Signal signal = ContextCreator.getSignalContext().get(signalIDPhase.signalID);
					if(signal != null) {
						// Set the phase (phaseTime defaults to 0 if not provided)
						boolean success = signal.setPhase(signalIDPhase.targetPhase, signalIDPhase.phaseTime);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", signalIDPhase.signalID);
						if(success) {
							record2.put("STATUS", "OK");
							record2.put("new_state", signal.getState());
							record2.put("next_update_tick", signal.getNextUpdateTick());
						}
						else {
							record2.put("STATUS", "KO");
							record2.put("REASON", "Invalid target phase (must be 0=Green, 1=Yellow, 2=Red)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + signalIDPhase.signalID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", signalIDPhase.signalID);
						record2.put("STATUS", "KO");
						record2.put("REASON", "Signal not found");
						jsonData.add(record2);
					}
				}
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Update only the phase durations of a traffic signal, leaving its
	 * current phase and starting offset unchanged.
	 *
	 * <p>Input DATA: list of {@code {signalID, greenTime, yellowTime,
	 * redTime}} where times are in seconds.
	 */
	private HashMap<String, Object> updateSignalTiming(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message. Expected: [{signalID, greenTime, yellowTime, redTime}, ...]");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalIDPhaseTiming>> collectionType = new TypeToken<Collection<SignalIDPhaseTiming>>() {};
				Collection<SignalIDPhaseTiming> signalIDPhaseTimings = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(SignalIDPhaseTiming signalIDPhaseTiming: signalIDPhaseTimings) {
					Signal signal = ContextCreator.getSignalContext().get(signalIDPhaseTiming.signalID);
					if(signal != null) {
						ArrayList<Integer> phaseTime = new ArrayList<Integer>();
						phaseTime.add(signalIDPhaseTiming.greenTime);
						phaseTime.add(signalIDPhaseTiming.yellowTime);
						phaseTime.add(signalIDPhaseTiming.redTime);
						
						boolean success = signal.updatePhaseTiming(phaseTime);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", signalIDPhaseTiming.signalID);
						if(success) {
							record2.put("STATUS", "OK");
							record2.put("phase_ticks", signal.getPhaseTick());
						}
						else {
							record2.put("STATUS", "KO");
							record2.put("REASON", "Invalid phase timing (all durations must be positive)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + signalIDPhaseTiming.signalID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", signalIDPhaseTiming.signalID);
						record2.put("STATUS", "KO");
						record2.put("REASON", "Signal not found");
						jsonData.add(record2);
					}
				}
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Set a complete new phase plan for a signal: phase durations, the
	 * starting phase, and an optional offset into that phase.
	 *
	 * <p>Input DATA: list of {@code {signalID, greenTime, yellowTime,
	 * redTime, startPhase, phaseOffset?}} where times are in seconds.
	 * For tick-level precision use {@link #setSignalPhasePlanTicks}.
	 */
	private HashMap<String, Object> setSignalPhasePlan(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found. Expected: [{signalID, greenTime, yellowTime, redTime, startPhase, phaseOffset(optional)}, ...]");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalPhasePlan>> collectionType = new TypeToken<Collection<SignalPhasePlan>>() {};
				Collection<SignalPhasePlan> signalPhasePlans = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(SignalPhasePlan plan: signalPhasePlans) {
					Signal signal = ContextCreator.getSignalContext().get(plan.signalID);
					if(signal != null) {
						ArrayList<Integer> phaseTime = new ArrayList<Integer>();
						phaseTime.add(plan.greenTime);
						phaseTime.add(plan.yellowTime);
						phaseTime.add(plan.redTime);
						
						boolean success = signal.setPhasePlan(phaseTime, plan.startPhase, plan.phaseOffset);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", plan.signalID);
						if(success) {
							record2.put("STATUS", "OK");
							record2.put("phase_ticks", signal.getPhaseTick());
							record2.put("current_state", signal.getState());
							record2.put("next_update_tick", signal.getNextUpdateTick());
						}
						else {
							record2.put("STATUS", "KO");
							record2.put("REASON", "Invalid phase plan (check phase durations and startPhase)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + plan.signalID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", plan.signalID);
						record2.put("STATUS", "KO");
						record2.put("REASON", "Signal not found");
						jsonData.add(record2);
					}
				}
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Tick-precise variant of {@link #setSignalPhasePlan}: phase durations
	 * are given in simulation ticks rather than seconds.
	 *
	 * <p>Input DATA: list of {@code {signalID, greenTicks, yellowTicks,
	 * redTicks, startPhase, tickOffset?}}.
	 */
	private HashMap<String, Object> setSignalPhasePlanTicks(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found. Expected: [{signalID, greenTicks, yellowTicks, redTicks, startPhase, tickOffset(optional)}, ...]");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalPhasePlanTicks>> collectionType = new TypeToken<Collection<SignalPhasePlanTicks>>() {};
				Collection<SignalPhasePlanTicks> signalPhasePlansTicks = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(SignalPhasePlanTicks plan: signalPhasePlansTicks) {
					Signal signal = ContextCreator.getSignalContext().get(plan.signalID);
					if(signal != null) {
						ArrayList<Integer> phaseTicks = new ArrayList<Integer>();
						phaseTicks.add(plan.greenTicks);
						phaseTicks.add(plan.yellowTicks);
						phaseTicks.add(plan.redTicks);
						
						boolean success = signal.setPhasePlanInTicks(phaseTicks, plan.startPhase, plan.tickOffset);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", plan.signalID);
						if(success) {
							record2.put("STATUS", "OK");
							record2.put("phase_ticks", signal.getPhaseTick());
							record2.put("current_state", signal.getState());
							record2.put("next_update_tick", signal.getNextUpdateTick());
						}
						else {
							record2.put("STATUS", "KO");
							record2.put("REASON", "Invalid phase plan (check phase tick durations and startPhase)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + plan.signalID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", plan.signalID);
						record2.put("STATUS", "KO");
						record2.put("REASON", "Signal not found");
						jsonData.add(record2);
					}
				}
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	// =============================================================
	// ROUTING (per-vehicle reroute & per-bus stop edits)
	// =============================================================
	
	/**
	 * Override the remaining route of a vehicle's current trip with the
	 * specified ordered sequence of road names.
	 *
	 * <p>Input DATA: list of {@code {vehID, vehType, route}} where
	 * {@code route} is an array of original road IDs.
	 */
	private HashMap<String, Object> updateVehicleRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeRoute>> collectionType = new TypeToken<Collection<VehIDVehTypeRoute>>() {};
				Collection<VehIDVehTypeRoute> vehIDVehTypeRoutes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(VehIDVehTypeRoute vehIDVehTypeRoute: vehIDVehTypeRoutes) {
					Vehicle veh = null;
			    	if(vehIDVehTypeRoute.vehType) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeRoute.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeRoute.vehID);
					}
			    	if(veh != null) {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		if(veh.updateRouteByRoadName(vehIDVehTypeRoute.route)){
			    			record2.put("ID", vehIDVehTypeRoute.vehID);
				    		record2.put("STATUS", "OK");
			    		}
			    		else {
			    			record2.put("ID", vehIDVehTypeRoute.vehID);
				    		record2.put("STATUS", "KO");
			    		}
			    		jsonData.add(record2);
			    	}
			    	else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeRoute.vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Insert a new stop into a bus's remaining route at the given index.
	 *
	 * <p>Input DATA: list of {@code {busID, routeName, zone, road,
	 * stopIndex}} where {@code routeName} must match the bus's currently
	 * assigned route and {@code stopIndex} is 0-based, relative to the
	 * bus's remaining stops.
	 */
	private HashMap<String, Object> insertStopToRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<BusIDRouteNameZoneRoadStopIndex>> collectionType = new TypeToken<Collection<BusIDRouteNameZoneRoadStopIndex>>() {};
				Collection<BusIDRouteNameZoneRoadStopIndex> busIDRouteNameZoneRoadStopIndexes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(BusIDRouteNameZoneRoadStopIndex busIDRouteNameZoneRoadStopIndex: busIDRouteNameZoneRoadStopIndexes) {
					ElectricBus veh = (ElectricBus) ContextCreator.getVehicleContext().getPublicVehicle(busIDRouteNameZoneRoadStopIndex.busID);
					
					if(veh!= null) {
						int rID = veh.getRouteID();
						Road r = ContextCreator.getCityContext().findRoadWithOrigID(busIDRouteNameZoneRoadStopIndex.road);
						if(ContextCreator.bus_schedule.getRouteName(rID).equals(busIDRouteNameZoneRoadStopIndex.routeName) && r != null) {
							if (veh.insertStop(busIDRouteNameZoneRoadStopIndex.zone, r, busIDRouteNameZoneRoadStopIndex.stopIndex)) {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
					    		record2.put("ID", busIDRouteNameZoneRoadStopIndex.busID);
					    		record2.put("STATUS", "OK");
								jsonData.add(record2);
							}
							else {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
					    		record2.put("ID", busIDRouteNameZoneRoadStopIndex.busID);
					    		record2.put("STATUS", "KO");
								jsonData.add(record2);
							}
						}
						else {
							ContextCreator.logger.info("insertStopToRoute: bus route or road name incorrect.");
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", busIDRouteNameZoneRoadStopIndex.busID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
						}
					}
					else {
						ContextCreator.logger.info("insertStopToRoute: cannot find bus with ID: " +  busIDRouteNameZoneRoadStopIndex.busID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", busIDRouteNameZoneRoadStopIndex.busID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
					}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	/**
	 * Remove a stop at the given index from a bus's remaining route.
	 *
	 * <p>Input DATA: list of {@code {busID, routeName, stopIndex}}.
	 */
	private HashMap<String, Object> removeStopFromRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<BusIDRouteNameStopIndex>> collectionType = new TypeToken<Collection<BusIDRouteNameStopIndex>>() {};
				Collection<BusIDRouteNameStopIndex> busIDRouteNameStopIndexes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(BusIDRouteNameStopIndex busIDRouteNameStopIndex: busIDRouteNameStopIndexes) {
					ElectricBus veh = (ElectricBus) ContextCreator.getVehicleContext().getPublicVehicle(busIDRouteNameStopIndex.busID);
					
					if(veh!= null) {
						int rID = veh.getRouteID();
						if(ContextCreator.bus_schedule.getRouteName(rID).equals(busIDRouteNameStopIndex.routeName)) {
							if (veh.removeStop(busIDRouteNameStopIndex.stopIndex)) {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
					    		record2.put("ID", busIDRouteNameStopIndex.busID);
					    		record2.put("STATUS", "OK");
								jsonData.add(record2);
							}
							else {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
					    		record2.put("ID", busIDRouteNameStopIndex.busID);
					    		record2.put("STATUS", "KO");
								jsonData.add(record2);
							}
						}
						else {
							ContextCreator.logger.info("removeStopFromRoute: bus route or road name incorrect.");
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", busIDRouteNameStopIndex.busID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
						}
					}
					else {
						ContextCreator.logger.info("insertStopToRoute: cannot find bus with ID: " +  busIDRouteNameStopIndex.busID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", busIDRouteNameStopIndex.busID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
					}
				}
				
				jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	
	
	// =============================================================
	// ROUTING WEIGHTS
	// =============================================================
	
	/**
	 * Override the routing weight of one or more road edges. Used by
	 * external routing components to bias the on-the-fly shortest-path
	 * search. Negative weights are clamped to a small positive value.
	 *
	 * <p>Input DATA: list of {@code {roadID, weight}}.
	 */
	private HashMap<String, Object> updateEdgeWeight(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RoadIDWeight>> collectionType = new TypeToken<Collection<RoadIDWeight>>() {};
			    Collection<RoadIDWeight> roadIDWeights = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(RoadIDWeight roadIDWeight: roadIDWeights) {
			    	Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadIDWeight.roadID);
			    	if(r != null) {
			    		Node node1 = r.getUpStreamNode();
				    	Node node2 = r.getDownStreamNode();
				    	ContextCreator.getRoadNetwork().getEdge(node1, node2).setWeight(Math.max(roadIDWeight.weight, 1e-3)); // weight cannot be negative 
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadIDWeight.roadID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
			    	}
			    	else {
			    		ContextCreator.logger.warn("Cannot find the road, road ID: " + roadIDWeight.roadID);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadIDWeight.roadID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
			    }
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}

	/**
	 * Update one or more road-level parking capacities.
	 *
	 * <p>Input DATA: list of {@code {roadID, parking_capacity}}. Aliases
	 * {@code origID}, {@code orig_id}, {@code ID}, {@code parkingCapacity},
	 * and {@code capacity} are accepted.
	 */
	private HashMap<String, Object> updateRoadParkingCapacity(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RoadParkingCapacity>> collectionType =
						new TypeToken<Collection<RoadParkingCapacity>>() {};
				Collection<RoadParkingCapacity> entries =
						gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (RoadParkingCapacity entry : entries) {
					String roadID = firstNonBlank(entry.roadID, entry.origID, entry.orig_id, entry.ID);
					HashMap<String, Object> record = statusRecord(roadID, "KO");
					Integer parkingCapacity = firstIntegerOrNull(entry.parkingCapacity,
							entry.parking_capacity, entry.capacity);
					if (roadID == null) {
						record.put("WARN", "roadID is required");
						jsonData.add(record);
						continue;
					}
					if (parkingCapacity == null) {
						record.put("WARN", "parking_capacity is required");
						jsonData.add(record);
						continue;
					}

					Road road = ContextCreator.getCityContext().findRoadWithOrigID(roadID);
					if (road == null) {
						ContextCreator.logger.warn("Cannot find the road, road ID: " + roadID);
						record.put("WARN", "road not found");
						jsonData.add(record);
						continue;
					}

					road.setParkingCapacity(parkingCapacity);
					record.put("parking_capacity", road.getParkingCapacity());
					record.put("parked_num", road.getParkedNum());
					record.put("STATUS", "OK");
					jsonData.add(record);
				}
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}

	// =============================================================
	// CHARGING
	// (the goCharging handler lives at the very end of the file,
	// after the dynamic-infrastructure block.)
	// =============================================================
	
	/**
	 * Update the price of a specific charger type at a charging station.
	 * The price is used by the EV charging-station search heuristic.
	 *
	 * <p>Input DATA: list of {@code {chargerID, chargerType, weight}}
	 * where {@code chargerType} is one of {@code ChargingStation.L2 / L3 /
	 * BUS}.
	 */
	private HashMap<String, Object> updateChargingPrice(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<ChargerIDChargerTypeWeight>> collectionType = new TypeToken<Collection<ChargerIDChargerTypeWeight>>() {};
			    Collection<ChargerIDChargerTypeWeight> chargerIDChargerTypeWeights = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(ChargerIDChargerTypeWeight chargerIDChargerTypeWeight: chargerIDChargerTypeWeights) {
			    	ChargingStation cs = ContextCreator.getChargingStationContext().get(chargerIDChargerTypeWeight.chargerID);
			    	if(cs != null) {
			    		boolean success = cs.setPrice(chargerIDChargerTypeWeight.chargerType, chargerIDChargerTypeWeight.weight);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", chargerIDChargerTypeWeight.chargerID);
			    		if(success) {
			    			record2.put("STATUS", "OK");
			    		}
			    		else {
			    			record2.put("STATUS", "KO");
			    		}
						jsonData.add(record2);
			    	}
			    	else {
			    		ContextCreator.logger.warn("Cannot find the charging station, ID: " + chargerIDChargerTypeWeight.chargerID);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", chargerIDChargerTypeWeight.chargerID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
			    }
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}

	// =============================================================
	// DYNAMIC INFRASTRUCTURE & FLEET ADDITIONS / REMOVALS
	// =============================================================
	
	/**
	 * Dynamically adds one or more zones at given coordinates.
	 * <p>Input DATA: list of {@code {x, y, z, transformCoord, capacity,
	 * type}}.
	 * <p>Output DATA: list of {@code {ID, STATUS}} with the assigned
	 * zone IDs.
	 */
	private HashMap<String, Object> addZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<ZoneParams>> collectionType = new TypeToken<Collection<ZoneParams>>() {};
			Collection<ZoneParams> params = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			GeometryFactory geomFac = new GeometryFactory();
			ZoneContext zoneContext = ContextCreator.getZoneContext();
			boolean metaZonePresent = zoneContext.contains(0);

		for (ZoneParams p : params) {
			Coordinate coord = new Coordinate(p.x, p.y, p.z);
			if (p.transformCoord) {
				try {
					JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
				} catch (TransformException e) {
					ContextCreator.logger.error("addZone: coordinate transform failed at (" + p.x + "," + p.y + "): " + e.getMessage());
						jsonData.add("KO");
						continue;
					}
				}

				int zoneID = zoneContext.ZONE_NUM++;
				Zone zone = new Zone(zoneID, p.capacity, p.type);
				zone.setCoord(coord);
				zoneContext.put(zoneID, zone);
				ContextCreator.getZoneGeography().move(zone, geomFac.createPoint(coord));

				// Find and attach the nearest departure and arrival roads
				Road deptRoad = ContextCreator.getCityContext().findRoadAtCoordinates(coord, false);
				Road arrRoad  = ContextCreator.getCityContext().findRoadAtCoordinates(coord, true);
				if (deptRoad != null) {
					zone.setClosestRoad(deptRoad.getID(), false);
					zone.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, deptRoad.getStartCoord()), false);
					zone.addNeighboringLink(deptRoad.getID(), false);
				} else {
					ContextCreator.logger.warn("addZone: no departure road found for zone " + zoneID);
				}
				if (arrRoad != null) {
					zone.setClosestRoad(arrRoad.getID(), true);
					zone.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, arrRoad.getEndCoord()), true);
					zone.addNeighboringLink(arrRoad.getID(), true);
				} else {
					ContextCreator.logger.warn("addZone: no arrival road found for zone " + zoneID);
				}

				// Initialize taxi availability maps for the new zone
				ContextCreator.getVehicleContext().initializeZoneMaps(zoneID);

				if (p.type == Zone.HUB) {
					zoneContext.HUB_INDEXES.add(zoneID);
				}

				// Schedule the zone's tick steps so it actively processes demand
				// and its stats are included in ZoneLog just like pre-loaded zones
				ContextCreator.scheduleNewZone(zone);

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("ID", zoneID);
				record.put("STATUS", "OK");
				jsonData.add(record);
			}
			// Remove the meta zone once real zones have been successfully added.
			// The meta zone was a startup placeholder used when the zone CSV was empty.
			// refreshRoadZoneAssignment re-maps all roads (which defaulted to zone 0)
			// to the nearest real zone via spatial search.
			if (metaZonePresent && !jsonData.isEmpty()) {
				Zone metaZone = zoneContext.get(0);
				if (GlobalVariables.MULTI_THREADING && ContextCreator.partitioner != null) {
					ContextCreator.partitioner.removeZone(metaZone);
				}
				zoneContext.remove(0);
				ContextCreator.getVehicleContext().removeZoneMaps(0);
				ContextCreator.getCityContext().refreshRoadZoneAssignment();
				ContextCreator.logger.info("Meta zone 0 removed; roads reassigned to " + jsonData.size() + " real zone(s).");
			}

			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addZone: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	/**
	 * Dynamically adds one or more charging stations at given coordinates.
	 * <p>Input DATA: list of {@code {x, y, z, transformCoord, numL2,
	 * numL3, numBus, priceL2, priceL3}}.
	 * <p>Output DATA: list of {@code {ID, STATUS}} with the assigned
	 * (negative) station IDs.
	 */
	private HashMap<String, Object> addChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<ChargingStationParams>> collectionType = new TypeToken<Collection<ChargingStationParams>>() {};
			Collection<ChargingStationParams> params = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			GeometryFactory geomFac = new GeometryFactory();

			// IDs for charging stations are negative integers; find the next available one
			int nextID = ContextCreator.getChargingStationContext().getIDList().stream()
					.mapToInt(Integer::intValue).min().orElse(0) - 1;

		for (ChargingStationParams p : params) {
			Coordinate coord = new Coordinate(p.x, p.y, p.z);
			if (p.transformCoord) {
				try {
					JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
				} catch (TransformException e) {
					ContextCreator.logger.error("addChargingStation: coordinate transform failed at (" + p.x + "," + p.y + "): " + e.getMessage());
						jsonData.add("KO");
						continue;
					}
				}

				int csID = nextID--;
				Road deptRoad = resolveDynamicFacilityRoad(coord, false);
				Road arrRoad = resolveDynamicFacilityRoad(coord, true);
				if (deptRoad == null || arrRoad == null) {
					ContextCreator.logger.warn("addChargingStation: no usable "
							+ (deptRoad == null ? "departure" : "arrival")
							+ " road found for station " + csID);
					jsonData.add("KO");
					continue;
				}

				ChargingStation cs = new ChargingStation(csID, p.numL2, p.numL3, p.numBus, p.priceL2, p.priceL3);
				cs.setCoord(coord);
				cs.setClosestRoad(deptRoad.getID(), false);
				cs.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, deptRoad.getStartCoord()), false);
				cs.setClosestRoad(arrRoad.getID(), true);
				cs.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, arrRoad.getEndCoord()), true);
				ContextCreator.getChargingStationContext().put(csID, cs);
				ContextCreator.getChargingStationGeography().move(cs, geomFac.createPoint(coord));

				// Schedule the station's tick steps so it actively charges vehicles
				// and produces ChargerLog entries identical to pre-loaded stations
				ContextCreator.scheduleNewChargingStation(cs);

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("ID", csID);
				record.put("departureRoad", deptRoad.getID());
				record.put("arrivalRoad", arrRoad.getID());
				record.put("STATUS", "OK");
				jsonData.add(record);
			}
			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addChargingStation: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	/**
	 * Release a vehicle from a road's entering-network queue.
	 * Co-simulation roads do not process this queue automatically; the
	 * external simulator should call this API once it is ready to spawn a
	 * queued vehicle for the road. The preferred selector is vehicle ID so
	 * the external simulator can choose an order that differs from METS-R's
	 * departure queue order.
	 *
	 * <p>Input DATA: list of vehicle IDs, or records carrying
	 * {@code vehID}/{@code vehicleID}/{@code ID}, optional
	 * {@code vehType}/{@code v_type}, and optional {@code roadID}. If
	 * {@code roadID} is omitted the co-simulation road queues are searched.
	 * For backward compatibility, a road-only record releases that road's
	 * queue head.
	 */
	private HashMap<String, Object> enterRoadFromQueue(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				ArrayList<Object> jsonData = new ArrayList<Object>();
				for (EnterRoadQueueRequest request : parseEnterRoadQueueRequests(jsonMsg.get("DATA"))) {
					HashMap<String, Object> record = new HashMap<String, Object>();
					if (request.vehID != null) record.put("vehicleID", request.vehID);
					if (request.internalVehicleID != null) record.put("internalVehicleID", request.internalVehicleID);
					if (request.vehType != null) record.put("v_type", request.vehType);
					if (request.roadID != null) record.put("roadID", request.roadID);

					QueuedVehicleMatch match = findQueuedEnteringVehicle(request);
					Road road = match == null ? null : match.road;
					Vehicle vehicle = match == null ? null : match.vehicle;
					if (road == null) {
						record.put("STATUS", "KO");
						record.put("WARN", request.vehID != null || request.internalVehicleID != null
								? "vehicle not found in entering queues" : "road not found");
						jsonData.add(record);
						continue;
					}

					record.put("roadID", road.getOrigID());
					record.put("controlType", road.getControlType());

					if (vehicle == null) {
						record.put("STATUS", "EMPTY");
						record.put("queueSize", 0);
						jsonData.add(record);
						continue;
					}

					int visibleVehicleID = bridgeVehicleID(vehicle);
					record.put("vehicleID", visibleVehicleID);
					record.put("internalVehicleID", vehicle.getID());
					record.put("v_type", bridgeVehicleType(vehicle));
					record.put("departureTick", vehicle.getDepTime());

					int tick = ContextCreator.getCurrentTick();
					if (tick < vehicle.getDepTime()) {
						record.put("STATUS", "WAITING_DEPARTURE_TIME");
						record.put("queueSize", road.getEnteringVehicleQueueSnapshot().size());
						jsonData.add(record);
						continue;
					}

					Coordinate cur = vehicle.getCurrentCoord();
					Coordinate dst = vehicle.getDestCoord();
					boolean busTrip = (vehicle.getState() == Vehicle.BUS_TRIP);
					boolean originEqualsDest = (cur != null && dst != null && cur.equals2D(dst));
					boolean sameBusRoad = busTrip
							&& vehicle.getOriginRoad() >= 0
							&& vehicle.getOriginRoad() == vehicle.getDestRoad();
					if ((busTrip && originEqualsDest) || (busTrip && (vehicle.getOriginID() == vehicle.getDestID()))
							|| sameBusRoad) {
						road.removeVehicleFromNewQueue(vehicle.getDepTime(), vehicle);
						ContextCreator.getVehicleContext().addArrivalVehicles(vehicle);
						record.put("STATUS", "ARRIVED");
					} else if (vehicle.enterNetworkByControl(road)) {
						road.removeVehicleFromNewQueue(vehicle.getDepTime(), vehicle);
						record.put("STATUS", "OK");
					} else {
						record.put("STATUS", "BLOCKED");
						record.put("WARN", "vehicle could not enter road");
					}
					record.put("queueSize", road.getEnteringVehicleQueueSnapshot().size());
					jsonData.add(record);
				}
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    ContextCreator.logger.error("Error processing control enterRoadFromQueue: " + e.getMessage(), e);
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}

	/**
	 * Dynamically adds one or more roads, including generated lanes offset from
	 * the supplied centerline.
	 * <p>Input DATA: list of {@code {origID/orig_id, centerline, upStreamRoad,
	 * downStreamRoad, roadType, controlType, upStreamControlType,
	 * downStreamControlType, numLanes, laneWidth, transformCoord}}.
	 * Road references are original road IDs. Singular and plural upstream /
	 * downstream fields are accepted.
	 * <p>Output DATA: list of {@code {ID, internalID, laneIDs, STATUS}} records.
	 */
	private HashMap<String, Object> addRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<RoadParams>> collectionType = new TypeToken<Collection<RoadParams>>() {};
			Collection<RoadParams> params = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();
			GeometryFactory geomFac = new GeometryFactory();
			int nextRoadID = nextAvailableID(ContextCreator.getRoadContext().getIDList(), 1);
			int nextLaneID = nextAvailableID(ContextCreator.getLaneContext().getIDList(), 1);
			boolean anyRoadAdded = false;

			for (RoadParams p : params) {
				String origID = firstNonBlank(p.origID, p.orig_id);
				HashMap<String, Object> record = statusRecord(origID, "KO");

				ArrayList<Coordinate> centerline = null;
				try {
					centerline = parseRoadCenterline(p);
				} catch (TransformException e) {
					record.put("WARN", "Coordinate transform failed: " + e.getMessage());
					jsonData.add(record);
					continue;
				}
				if (centerline == null || centerline.size() < 2) {
					record.put("WARN", "Road centerline must contain at least two points");
					jsonData.add(record);
					continue;
				}

				int numLanes = firstInteger(1, p.numLanes, p.num_lanes, p.laneNum, p.lane_num, p.lanes);
				if (numLanes <= 0) {
					record.put("WARN", "numLanes must be positive");
					jsonData.add(record);
					continue;
				}

				nextRoadID = nextAvailableRoadID(nextRoadID);
				if (origID == null) {
					origID = "dynamic_road_" + nextRoadID;
					record.put("ID", origID);
				}
				if (ContextCreator.getCityContext().findRoadWithOrigID(origID) != null) {
					record.put("WARN", "Road orig_id already exists");
					jsonData.add(record);
					continue;
				}

				ArrayList<String> upstreamOrigIDs = normalizeUpstreamRoadOrigIDs(p);
				ArrayList<String> downstreamOrigIDs = normalizeDownstreamRoadOrigIDs(p);
				if (upstreamOrigIDs.isEmpty() || downstreamOrigIDs.isEmpty()) {
					record.put("WARN", "At least one upstream and one downstream road orig_id are required");
					jsonData.add(record);
					continue;
				}

				ArrayList<Road> upstreamRoads = new ArrayList<Road>();
				String missingRoad = resolveRoadOrigIDs(upstreamOrigIDs, upstreamRoads);
				if (missingRoad != null) {
					record.put("WARN", "Upstream road not found: " + missingRoad);
					jsonData.add(record);
					continue;
				}

				ArrayList<Road> downstreamRoads = new ArrayList<Road>();
				missingRoad = resolveRoadOrigIDs(downstreamOrigIDs, downstreamRoads);
				if (missingRoad != null) {
					record.put("WARN", "Downstream road not found: " + missingRoad);
					jsonData.add(record);
					continue;
				}

				String junctionWarning = validateConnectorJunctions(upstreamRoads, true);
				if (junctionWarning == null) {
					junctionWarning = validateConnectorJunctions(downstreamRoads, false);
				}
				if (junctionWarning != null) {
					record.put("WARN", junctionWarning);
					jsonData.add(record);
					continue;
				}

				double roadLength = polylineLength(centerline);
				if (roadLength <= 0) {
					record.put("WARN", "Road centerline length must be positive");
					jsonData.add(record);
					continue;
				}

				int roadID = nextRoadID;
				nextRoadID++;
				Road road = new Road(roadID);
				road.setOrigID(origID);
				road.setRoadType(firstInteger(Road.Street, p.roadType, p.road_type));
				road.setControlType(firstInteger(Road.NONE_OF_THE_ABOVE, p.controlType, p.control_type,
						p.roadControlType, p.road_control_type));
				Integer parkingCapacity = firstIntegerOrNull(p.parkingCapacity, p.parking_capacity);
				if (parkingCapacity != null) {
					road.setParkingCapacity(parkingCapacity);
				}
				road.setCoords(centerline);
				road.setLength(roadLength);
				road.updateTravelTimeEstimation();
				ContextCreator.getRoadContext().put(roadID, road);
				ContextCreator.getRoadGeography().move(road,
						geomFac.createLineString(centerline.toArray(new Coordinate[centerline.size()])));

				double laneWidth = firstDouble(3.5, p.laneWidth, p.lane_width);
				ArrayList<Integer> laneIDs = new ArrayList<Integer>();
				for (int laneIndex = 0; laneIndex < numLanes; laneIndex++) {
					nextLaneID = nextAvailableLaneID(nextLaneID);
					int laneID = nextLaneID;
					nextLaneID++;

					ArrayList<Coordinate> laneCoords = offsetCenterline(centerline, laneIndex, numLanes, laneWidth);
					Lane lane = new Lane(laneID);
					lane.setOrigID(origID + "_" + laneIndex);
					lane.setRoad(roadID);
					lane.setCoords(laneCoords);
					lane.setLength(polylineLength(laneCoords));
					lane.setSpeed(road.getSpeedLimit());
					ContextCreator.getLaneContext().put(laneID, lane);
					ContextCreator.getLaneGeography().move(lane,
							geomFac.createLineString(laneCoords.toArray(new Coordinate[laneCoords.size()])));
					road.addLane(lane);
					laneIDs.add(laneID);
				}
				road.sortLanes();
				for (Lane lane : road.getLanes()) {
					lane.setIndex();
				}

				ContextCreator.getCityContext().registerAddedRoad(road, upstreamRoads, downstreamRoads,
						firstIntegerOrNull(p.upStreamControlType, p.upstreamControlType, p.upstream_control_type,
								p.upControlType, p.up_control_type),
						firstIntegerOrNull(p.downStreamControlType, p.downstreamControlType, p.downstream_control_type,
								p.downControlType, p.down_control_type));
				updateFacilitiesAfterRoadAddition(road);
				ContextCreator.scheduleNewRoad(road);

				record.put("internalID", road.getID());
				record.put("laneIDs", laneIDs);
				record.put("STATUS", "OK");
				jsonData.add(record);
				anyRoadAdded = true;
			}

			if (anyRoadAdded) {
				RouteContext.createRoute();
				ContextCreator.getCityContext().refreshRoadNetworkWeights();
				if (GlobalVariables.MULTI_THREADING) {
					try {
						ContextCreator.partitioner.first_run();
					} catch (Exception e) {
						ContextCreator.logger.warn("addRoads: failed to refresh partitioner: " + e.getMessage());
					}
				}
			}

			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addRoads: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	/**
	 * Dynamically removes one or more zones.
	 * <p>Input DATA: list of integer zone IDs.
	 * <p>Output DATA: list of {@code {ID, STATUS}} records.
	 *
	 * <p>A zone is removed only when it is not the last remaining zone and no
	 * active vehicles, pending requests, or bus routes still reference it.
	 */
	private HashMap<String, Object> removeZone(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
			Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (int zoneID : IDs) {
				HashMap<String, Object> record = statusRecord(zoneID, "KO");
				Zone zone = ContextCreator.getZoneContext().get(zoneID);
				if (zone == null) {
					record.put("WARN", "Zone not found");
					jsonData.add(record);
					continue;
				}

				String blocker = zoneRemovalBlocker(zone);
				if (blocker != null) {
					record.put("WARN", blocker);
					jsonData.add(record);
					continue;
				}

				for (Road road : ContextCreator.getRoadContext().getAll()) {
					if (road.getNeighboringZone(false) == zoneID) {
						road.setNeighboringZone(0, false);
						road.setDistToZone(Double.MAX_VALUE, false);
					}
					if (road.getNeighboringZone(true) == zoneID) {
						road.setNeighboringZone(0, true);
						road.setDistToZone(Double.MAX_VALUE, true);
					}
				}

				ContextCreator.getZoneContext().HUB_INDEXES.remove(Integer.valueOf(zoneID));
				if (GlobalVariables.MULTI_THREADING && ContextCreator.partitioner != null) {
					ContextCreator.partitioner.removeZone(zone);
				}
				ContextCreator.getZoneContext().remove(zoneID);
				ContextCreator.getVehicleContext().removeZoneMaps(zoneID);

				for (Zone other : ContextCreator.getZoneContext().getAll()) {
					other.getNeighboringZones().remove(Integer.valueOf(zoneID));
					other.traversingBusRoutes.remove(zoneID);
				}

				ContextCreator.getCityContext().refreshRoadZoneAssignment();
				record.put("STATUS", "OK");
				jsonData.add(record);
			}

			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control removeZone: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	/**
	 * Dynamically removes one or more roads.
	 * <p>Input DATA: list of original road IDs.
	 * <p>Output DATA: list of {@code {ID, STATUS}} records.
	 *
	 * <p>A road is removed only when no vehicles, requests, bus schedules, or
	 * facility closest-road assignments would be stranded.
	 */
	private HashMap<String, Object> removeRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
			Collection<String> roadIDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (String roadID : roadIDs) {
				HashMap<String, Object> record = statusRecord(roadID, "KO");
				Road road = ContextCreator.getCityContext().findRoadWithOrigID(roadID);
				if (road == null) {
					record.put("WARN", "Road not found");
					jsonData.add(record);
					continue;
				}

				String blocker = roadRemovalBlocker(road);
				if (blocker != null) {
					record.put("WARN", blocker);
					jsonData.add(record);
					continue;
				}

				ContextCreator.coSimRoads.remove(road.getOrigID());
				ContextCreator.getCityContext().removeRoadReferences(road);
				removeRoadLanes(road);
				ContextCreator.getRoadContext().remove(road.getID());
				updateFacilitiesAfterRoadRemoval(road);
				RouteContext.createRoute();

				record.put("STATUS", "OK");
				jsonData.add(record);
			}

			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control removeRoad: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	/**
	 * Dynamically removes one or more charging stations.
	 * <p>Input DATA: list of integer charging-station IDs.
	 * <p>Output DATA: list of {@code {ID, STATUS}} records.
	 *
	 * <p>A station is removed only when no vehicle is queued, charging, or
	 * already en route to that station.
	 */
	private HashMap<String, Object> removeChargingStation(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<Integer>> collectionType = new TypeToken<Collection<Integer>>() {};
			Collection<Integer> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (int chargerID : IDs) {
				HashMap<String, Object> record = statusRecord(chargerID, "KO");
				ChargingStation cs = ContextCreator.getChargingStationContext().get(chargerID);
				if (cs == null) {
					record.put("WARN", "Charging station not found");
					jsonData.add(record);
					continue;
				}
				if (cs.hasChargingVehicles()) {
					record.put("WARN", "Charging station has queued or charging vehicles");
					jsonData.add(record);
					continue;
				}
				if (vehiclesReferenceChargingStation(chargerID)) {
					record.put("WARN", "A vehicle is en route to this charging station");
					jsonData.add(record);
					continue;
				}

				if (GlobalVariables.MULTI_THREADING && ContextCreator.partitioner != null) {
					ContextCreator.partitioner.removeChargingStation(cs);
				}
				ContextCreator.getChargingStationContext().remove(chargerID);
				record.put("STATUS", "OK");
				jsonData.add(record);
			}

			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control removeChargingStation: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	private HashMap<String, Object> statusRecord(Object id, String status) {
		HashMap<String, Object> record = new HashMap<String, Object>();
		record.put("ID", id);
		record.put("STATUS", status);
		return record;
	}

	private String zoneRemovalBlocker(Zone zone) {
		int zoneID = zone.getID();
		if (ContextCreator.getZoneContext().getIDList().size() <= 1) {
			return "Cannot remove the last zone";
		}
		if (ContextCreator.bus_schedule.referencesZone(zoneID)) {
			return "Zone is referenced by a bus route";
		}
		if (zone.getParkingVehicleStock() > 0
				|| !ContextCreator.getVehicleContext().getAvailableTaxisSorted(zoneID).isEmpty()
				|| ContextCreator.getVehicleContext().getNumOfRelocationTaxi(zoneID) > 0) {
			return "Zone still has parked or relocating taxis";
		}
		if (zoneHasPendingRequests(zone) || requestsReferenceZone(zoneID)) {
			return "Zone is referenced by pending or assigned requests";
		}
		if (vehiclesReferenceZone(zoneID)) {
			return "Zone is referenced by active vehicle plans";
		}
		return null;
	}

	private String roadRemovalBlocker(Road road) {
		if (road.hasActiveVehicles()) {
			return "Road still has active or queued vehicles";
		}
		if (road.getParkedNum() > 0) {
			return "Road still has parked vehicles";
		}
		if (ContextCreator.bus_schedule.referencesRoad(road)) {
			return "Road is referenced by a bus route";
		}
		if (requestsReferenceRoad(road.getID())) {
			return "Road is referenced by pending or assigned requests";
		}
		if (vehiclesReferenceRoad(road.getID())) {
			return "Road is referenced by active vehicle plans";
		}
		if (!hasAlternativeClosestRoads(road)) {
			return "At least one zone or charging station has no alternative closest road";
		}
		return null;
	}

	private boolean zoneHasPendingRequests(Zone zone) {
		if (!zone.getTaxiRequestQueue().isEmpty() || !zone.getBusRequestQueue().isEmpty()
				|| !zone.getToAddTaxiRequestQueue().isEmpty() || !zone.getToAddBusRequestQueue().isEmpty()) {
			return true;
		}
		for (Queue<Request> q : zone.getSharableRequestForTaxi().values()) {
			if (!q.isEmpty()) return true;
		}
		return false;
	}

	private ArrayList<Vehicle> allVehicles() {
		ArrayList<Vehicle> vehicles = new ArrayList<Vehicle>();
		vehicles.addAll(ContextCreator.getVehicleContext().getPrivateEVs());
		vehicles.addAll(ContextCreator.getVehicleContext().getPrivateGVs());
		vehicles.addAll(ContextCreator.getVehicleContext().getTaxis());
		vehicles.addAll(ContextCreator.getVehicleContext().getBuses());
		return vehicles;
	}

	private boolean vehiclesReferenceZone(int zoneID) {
		for (Vehicle v : allVehicles()) {
			if (v.getOriginID() == zoneID || v.getDestID() == zoneID) return true;
			if (v instanceof ElectricTaxi && ((ElectricTaxi) v).getCurrentZone() == zoneID) return true;
			if (v instanceof ElectricBus && ((ElectricBus) v).getBusStops().contains(zoneID)) return true;
			for (Plan p : v.getPlan()) {
				if (p.getDestZoneID() == zoneID) return true;
			}
		}
		return false;
	}

	private boolean vehiclesReferenceRoad(int roadID) {
		for (Vehicle v : allVehicles()) {
			if (v.getCurrentParkingRoad() == roadID) return true;
			Road currentRoad = v.getRoad();
			if (currentRoad != null && currentRoad.getID() == roadID) return true;
			Road nextRoad = v.getNextRoad();
			if (nextRoad != null && nextRoad.getID() == roadID) return true;
			List<Road> path = v.getRoadPath();
			if (path != null) {
				for (Road r : path) {
					if (r != null && r.getID() == roadID) return true;
				}
			}
			for (Plan p : v.getPlan()) {
				if (p.getDestRoadID() == roadID) return true;
			}
		}
		return false;
	}

	private boolean vehiclesReferenceChargingStation(int chargerID) {
		for (Vehicle v : allVehicles()) {
			if (v instanceof ElectricVehicle) {
				ElectricVehicle ev = (ElectricVehicle) v;
				if (ev.isOnChargingRoute() && ev.getDestID() == chargerID) return true;
			}
			for (Plan p : v.getPlan()) {
				if (p.getDestZoneID() == chargerID) return true;
			}
		}
		return false;
	}

	private boolean requestsReferenceZone(int zoneID) {
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (requestQueueReferencesZone(z.getTaxiRequestQueue(), zoneID)
					|| requestQueueReferencesZone(z.getBusRequestQueue(), zoneID)
					|| requestQueueReferencesZone(z.getToAddTaxiRequestQueue(), zoneID)
					|| requestQueueReferencesZone(z.getToAddBusRequestQueue(), zoneID)) {
				return true;
			}
			for (Queue<Request> q : z.getSharableRequestForTaxi().values()) {
				if (requestQueueReferencesZone(q, zoneID)) return true;
			}
		}
		for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
			if (requestQueueReferencesZone(t.getToBoardRequests(), zoneID)
					|| requestQueueReferencesZone(t.getOnBoardRequests(), zoneID)) {
				return true;
			}
		}
		for (ElectricBus b : ContextCreator.getVehicleContext().getBuses()) {
			for (Queue<Request> q : b.getToBoardRequests()) {
				if (requestQueueReferencesZone(q, zoneID)) return true;
			}
			for (Queue<Request> q : b.getOnBoardRequests()) {
				if (requestQueueReferencesZone(q, zoneID)) return true;
			}
		}
		return false;
	}

	private boolean requestsReferenceRoad(int roadID) {
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (requestQueueReferencesRoad(z.getTaxiRequestQueue(), roadID)
					|| requestQueueReferencesRoad(z.getBusRequestQueue(), roadID)
					|| requestQueueReferencesRoad(z.getToAddTaxiRequestQueue(), roadID)
					|| requestQueueReferencesRoad(z.getToAddBusRequestQueue(), roadID)) {
				return true;
			}
			for (Queue<Request> q : z.getSharableRequestForTaxi().values()) {
				if (requestQueueReferencesRoad(q, roadID)) return true;
			}
		}
		for (ElectricTaxi t : ContextCreator.getVehicleContext().getTaxis()) {
			if (requestQueueReferencesRoad(t.getToBoardRequests(), roadID)
					|| requestQueueReferencesRoad(t.getOnBoardRequests(), roadID)) {
				return true;
			}
		}
		for (ElectricBus b : ContextCreator.getVehicleContext().getBuses()) {
			for (Queue<Request> q : b.getToBoardRequests()) {
				if (requestQueueReferencesRoad(q, roadID)) return true;
			}
			for (Queue<Request> q : b.getOnBoardRequests()) {
				if (requestQueueReferencesRoad(q, roadID)) return true;
			}
		}
		return false;
	}

	private boolean requestQueueReferencesZone(Queue<Request> requests, int zoneID) {
		for (Request r : requests) {
			if (r.getOriginZone() == zoneID || r.getDestZone() == zoneID) return true;
		}
		return false;
	}

	private boolean requestQueueReferencesRoad(Queue<Request> requests, int roadID) {
		for (Request r : requests) {
			if (r.getOriginRoad() == roadID || r.getDestRoad() == roadID) return true;
		}
		return false;
	}

	private boolean hasAlternativeClosestRoads(Road removedRoad) {
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (z.getClosestRoad(false) != null && z.getClosestRoad(false) == removedRoad.getID()
					&& ContextCreator.getCityContext().findRoadAtCoordinates(z.getCoord(), false, removedRoad) == null) {
				return false;
			}
			if (z.getClosestRoad(true) != null && z.getClosestRoad(true) == removedRoad.getID()
					&& ContextCreator.getCityContext().findRoadAtCoordinates(z.getCoord(), true, removedRoad) == null) {
				return false;
			}
		}
		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			if (cs.getClosestRoad(false) != null && cs.getClosestRoad(false) == removedRoad.getID()
					&& ContextCreator.getCityContext().findRoadAtCoordinates(cs.getCoord(), false, removedRoad) == null) {
				return false;
			}
			if (cs.getClosestRoad(true) != null && cs.getClosestRoad(true) == removedRoad.getID()
					&& ContextCreator.getCityContext().findRoadAtCoordinates(cs.getCoord(), true, removedRoad) == null) {
				return false;
			}
		}
		return true;
	}

	private void removeRoadLanes(Road removedRoad) {
		ArrayList<Lane> removedLanes = new ArrayList<Lane>(removedRoad.getLanes());
		for (Lane removedLane : removedLanes) {
			for (Lane lane : ContextCreator.getLaneContext().getAll()) {
				if (lane == removedLane) continue;
				lane.getDownStreamLanes().remove(Integer.valueOf(removedLane.getID()));
				lane.getUpStreamLanes().remove(Integer.valueOf(removedLane.getID()));
			}
		}
		for (Lane removedLane : removedLanes) {
			ContextCreator.getLaneContext().remove(removedLane.getID());
		}
		removedRoad.getLanes().clear();
	}

	private void updateFacilitiesAfterRoadRemoval(Road removedRoad) {
		int roadID = removedRoad.getID();
		ContextCreator.getCityContext().clearRoadLookupCaches();

		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			z.getNeighboringLinks(false).remove(Integer.valueOf(roadID));
			z.getNeighboringLinks(true).remove(Integer.valueOf(roadID));
			if (z.getClosestRoad(false) != null && z.getClosestRoad(false) == roadID) {
				Road alt = ContextCreator.getCityContext().findRoadAtCoordinates(z.getCoord(), false);
				if (alt != null) {
					z.setClosestRoad(alt.getID(), false);
					z.setDistToRoad(ContextCreator.getCityContext().getDistance(z.getCoord(), alt.getStartCoord()), false);
					z.addNeighboringLink(alt.getID(), false);
				}
			}
			if (z.getClosestRoad(true) != null && z.getClosestRoad(true) == roadID) {
				Road alt = ContextCreator.getCityContext().findRoadAtCoordinates(z.getCoord(), true);
				if (alt != null) {
					z.setClosestRoad(alt.getID(), true);
					z.setDistToRoad(ContextCreator.getCityContext().getDistance(z.getCoord(), alt.getEndCoord()), true);
					z.addNeighboringLink(alt.getID(), true);
				}
			}
		}

		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			if (cs.getClosestRoad(false) != null && cs.getClosestRoad(false) == roadID) {
				Road alt = ContextCreator.getCityContext().findRoadAtCoordinates(cs.getCoord(), false);
				if (alt != null) {
					cs.setClosestRoad(alt.getID(), false);
					cs.setDistToRoad(ContextCreator.getCityContext().getDistance(cs.getCoord(), alt.getStartCoord()), false);
				}
			}
			if (cs.getClosestRoad(true) != null && cs.getClosestRoad(true) == roadID) {
				Road alt = ContextCreator.getCityContext().findRoadAtCoordinates(cs.getCoord(), true);
				if (alt != null) {
					cs.setClosestRoad(alt.getID(), true);
					cs.setDistToRoad(ContextCreator.getCityContext().getDistance(cs.getCoord(), alt.getEndCoord()), true);
				}
			}
		}
	}

	private int nextAvailableID(List<Integer> IDs, int minID) {
		int nextID = minID;
		for (Integer ID : IDs) {
			if (ID != null && ID >= nextID) {
				nextID = ID + 1;
			}
		}
		return Math.max(minID, nextID);
	}

	private int nextAvailableRoadID(int startID) {
		int nextID = Math.max(1, startID);
		while (ContextCreator.getRoadContext().contains(nextID)) {
			nextID++;
		}
		return nextID;
	}

	private int nextAvailableLaneID(int startID) {
		int nextID = Math.max(1, startID);
		while (ContextCreator.getLaneContext().contains(nextID)) {
			nextID++;
		}
		return nextID;
	}

	private static class EnterRoadQueueRequest {
		Integer vehID;
		Integer internalVehicleID;
		Boolean vehType;
		String roadID;
	}

	private static class QueuedVehicleMatch {
		Road road;
		Vehicle vehicle;

		QueuedVehicleMatch(Road road, Vehicle vehicle) {
			this.road = road;
			this.vehicle = vehicle;
		}
	}

	private ArrayList<EnterRoadQueueRequest> parseEnterRoadQueueRequests(Object data) {
		ArrayList<EnterRoadQueueRequest> requests = new ArrayList<EnterRoadQueueRequest>();
		if (data instanceof Map<?, ?>) {
			requests.add(enterRoadQueueRequestFromEntry(data));
		} else if (data instanceof Collection<?>) {
			for (Object entry : (Collection<?>) data) {
				requests.add(enterRoadQueueRequestFromEntry(entry));
			}
		} else if (data != null) {
			String value = data.toString().trim();
			if (value.startsWith("[")) {
				Gson gson = new Gson();
				TypeToken<Collection<Object>> collectionType = new TypeToken<Collection<Object>>() {};
				Collection<Object> parsed = gson.fromJson(value, collectionType.getType());
				if (parsed != null) {
					for (Object entry : parsed) {
						requests.add(enterRoadQueueRequestFromEntry(entry));
					}
				}
			} else if (value.startsWith("{")) {
				Gson gson = new Gson();
				Map<?, ?> parsed = gson.fromJson(value, Map.class);
				requests.add(enterRoadQueueRequestFromEntry(parsed));
			} else if (!value.isEmpty()) {
				requests.add(enterRoadQueueRequestFromEntry(value));
			}
		}
		return requests;
	}

	private EnterRoadQueueRequest enterRoadQueueRequestFromEntry(Object entry) {
		EnterRoadQueueRequest request = new EnterRoadQueueRequest();
		if (entry == null) return request;
		if (entry instanceof Map<?, ?>) {
			Map<?, ?> record = (Map<?, ?>) entry;
			request.vehID = integerValue(firstPresent(record, "vehID", "vehicleID"));
			request.internalVehicleID = integerValue(firstPresent(record, "internalVehicleID", "internalID"));
			request.vehType = booleanValue(firstPresent(record, "vehType", "v_type"));
			request.roadID = stringValue(firstPresent(record, "roadID", "road", "origID", "orig_id"));
			Object idValue = firstPresent(record, "ID");
			if (request.vehID == null && request.internalVehicleID == null) {
				Integer idAsVehicle = integerValue(idValue);
				if (idAsVehicle != null) {
					request.vehID = idAsVehicle;
				} else if (request.roadID == null) {
					request.roadID = stringValue(idValue);
				}
			} else if (request.roadID == null && idValue != null && integerValue(idValue) == null) {
				request.roadID = stringValue(idValue);
			}
		} else {
			Integer idAsVehicle = integerValue(entry);
			if (idAsVehicle != null) {
				request.vehID = idAsVehicle;
			} else {
				request.roadID = stringValue(entry);
			}
		}
		return request;
	}

	private QueuedVehicleMatch findQueuedEnteringVehicle(EnterRoadQueueRequest request) {
		if (request.roadID != null && !request.roadID.isEmpty()) {
			Road road = ContextCreator.getCityContext().findRoadWithOrigID(request.roadID);
			if (road == null) return null;
			road.addVehicleToDepartureMap();
			if (request.vehID == null && request.internalVehicleID == null) {
				return new QueuedVehicleMatch(road, road.departureVehicleQueueHead());
			}
			Vehicle vehicle = findVehicleInRoadQueue(road, request);
			return vehicle == null ? null : new QueuedVehicleMatch(road, vehicle);
		}

		if (request.vehID == null && request.internalVehicleID == null) return null;
		for (Road road : ContextCreator.coSimRoads.values()) {
			road.addVehicleToDepartureMap();
			Vehicle vehicle = findVehicleInRoadQueue(road, request);
			if (vehicle != null) return new QueuedVehicleMatch(road, vehicle);
		}
		return null;
	}

	private Vehicle findVehicleInRoadQueue(Road road, EnterRoadQueueRequest request) {
		for (Vehicle vehicle : road.getEnteringVehicleQueueSnapshot()) {
			if (matchesEnteringVehicle(vehicle, request)) return vehicle;
		}
		return null;
	}

	private boolean matchesEnteringVehicle(Vehicle vehicle, EnterRoadQueueRequest request) {
		if (request.internalVehicleID != null && request.internalVehicleID.intValue() != vehicle.getID()) {
			return false;
		}
		if (request.vehType != null && request.vehType.booleanValue() != bridgeVehicleType(vehicle)) {
			return false;
		}
		if (request.vehID != null && request.vehID.intValue() != bridgeVehicleID(vehicle)) {
			return false;
		}
		return request.vehID != null || request.internalVehicleID != null;
	}

	private int bridgeVehicleID(Vehicle vehicle) {
		if (bridgeVehicleType(vehicle)) {
			int privateID = ContextCreator.getVehicleContext().getPrivateVID(vehicle.getID());
			return privateID >= 0 ? privateID : vehicle.getID();
		}
		return vehicle.getID();
	}

	private boolean bridgeVehicleType(Vehicle vehicle) {
		return vehicle.getVehicleClass() == Vehicle.EV || vehicle.getVehicleClass() == Vehicle.GV;
	}

	private Object firstPresent(Map<?, ?> record, String... keys) {
		for (String key : keys) {
			if (record.containsKey(key)) return record.get(key);
		}
		return null;
	}

	private Integer integerValue(Object value) {
		if (value == null) return null;
		if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
		try {
			return Integer.valueOf(String.valueOf(value));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private Boolean booleanValue(Object value) {
		if (value == null) return null;
		if (value instanceof Boolean) return (Boolean) value;
		String text = String.valueOf(value);
		if ("true".equalsIgnoreCase(text)) return Boolean.TRUE;
		if ("false".equalsIgnoreCase(text)) return Boolean.FALSE;
		if ("1".equals(text)) return Boolean.TRUE;
		if ("0".equals(text)) return Boolean.FALSE;
		return null;
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private ArrayList<Coordinate> parseRoadCenterline(RoadParams p) throws TransformException {
		ArrayList<ArrayList<Double>> rawCenterline = p.centerline;
		if (rawCenterline == null) rawCenterline = p.centerLine;
		if (rawCenterline == null) rawCenterline = p.coords;
		if (rawCenterline == null) return null;

		ArrayList<Coordinate> centerline = new ArrayList<Coordinate>();
		for (ArrayList<Double> point : rawCenterline) {
			if (point == null || point.size() < 2 || point.get(0) == null || point.get(1) == null) {
				return null;
			}
			double z = (point.size() > 2 && point.get(2) != null) ? point.get(2) : 0.0;
			Coordinate coord = new Coordinate(point.get(0), point.get(1), z);
			if (p.transformCoord) {
				JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
			}
			centerline.add(coord);
		}
		return centerline;
	}

	private ArrayList<String> normalizeUpstreamRoadOrigIDs(RoadParams p) {
		ArrayList<String> IDs = new ArrayList<String>();
		appendRoadOrigID(IDs, p.upStreamRoadOrigID);
		appendRoadOrigID(IDs, p.upstreamRoadOrigID);
		appendRoadOrigID(IDs, p.upstream_orig_id);
		appendRoadOrigID(IDs, p.upstream);
		appendRoadOrigID(IDs, p.upStreamRoad);
		appendRoadOrigID(IDs, p.upstreamRoad);
		appendRoadOrigID(IDs, p.upStreamRoadID);
		appendRoadOrigID(IDs, p.upstreamRoadID);
		appendRoadOrigIDs(IDs, p.upStreamRoadOrigIDs);
		appendRoadOrigIDs(IDs, p.upstreamRoadOrigIDs);
		appendRoadOrigIDs(IDs, p.upstream_orig_ids);
		appendRoadOrigIDs(IDs, p.upstream_roads);
		appendRoadOrigIDs(IDs, p.upStreamRoads);
		appendRoadOrigIDs(IDs, p.upstreamRoads);
		return IDs;
	}

	private ArrayList<String> normalizeDownstreamRoadOrigIDs(RoadParams p) {
		ArrayList<String> IDs = new ArrayList<String>();
		appendRoadOrigID(IDs, p.downStreamRoadOrigID);
		appendRoadOrigID(IDs, p.downstreamRoadOrigID);
		appendRoadOrigID(IDs, p.downstream_orig_id);
		appendRoadOrigID(IDs, p.downstream);
		appendRoadOrigID(IDs, p.downStreamRoad);
		appendRoadOrigID(IDs, p.downstreamRoad);
		appendRoadOrigID(IDs, p.downStreamRoadID);
		appendRoadOrigID(IDs, p.downstreamRoadID);
		appendRoadOrigIDs(IDs, p.downStreamRoadOrigIDs);
		appendRoadOrigIDs(IDs, p.downstreamRoadOrigIDs);
		appendRoadOrigIDs(IDs, p.downstream_orig_ids);
		appendRoadOrigIDs(IDs, p.downstream_roads);
		appendRoadOrigIDs(IDs, p.downStreamRoads);
		appendRoadOrigIDs(IDs, p.downstreamRoads);
		return IDs;
	}

	private void appendRoadOrigIDs(ArrayList<String> IDs, ArrayList<String> values) {
		if (values == null) return;
		for (String value : values) {
			appendRoadOrigID(IDs, value);
		}
	}

	private void appendRoadOrigID(ArrayList<String> IDs, String value) {
		String cleanValue = cleanString(value);
		if (cleanValue != null && !IDs.contains(cleanValue)) {
			IDs.add(cleanValue);
		}
	}

	private String resolveRoadOrigIDs(ArrayList<String> origIDs, ArrayList<Road> roads) {
		for (String origID : origIDs) {
			Road road = ContextCreator.getCityContext().findRoadWithOrigID(origID);
			if (road == null) {
				return origID;
			}
			roads.add(road);
		}
		return null;
	}

	private String validateConnectorJunctions(ArrayList<Road> roads, boolean upstream) {
		if (roads.isEmpty()) return null;
		int junctionID = upstream ? roads.get(0).getDownStreamJunction() : roads.get(0).getUpStreamJunction();
		for (Road road : roads) {
			int roadJunctionID = upstream ? road.getDownStreamJunction() : road.getUpStreamJunction();
			if (roadJunctionID != junctionID) {
				return upstream ? "Upstream roads must share the same downstream junction"
						: "Downstream roads must share the same upstream junction";
			}
		}
		return null;
	}

	private ArrayList<Coordinate> offsetCenterline(ArrayList<Coordinate> centerline, int laneIndex, int laneCount,
			double laneWidth) {
		ArrayList<Coordinate> laneCoords = new ArrayList<Coordinate>();
		double offset = (laneIndex - ((laneCount - 1) / 2.0)) * laneWidth;
		for (int i = 0; i < centerline.size(); i++) {
			Coordinate coord = centerline.get(i);
			double[] tangent = centerlineTangent(centerline, i);
			double normalX = -tangent[1];
			double normalY = tangent[0];
			laneCoords.add(new Coordinate(coord.x + normalX * offset, coord.y + normalY * offset, coord.z));
		}
		return laneCoords;
	}

	private double[] centerlineTangent(ArrayList<Coordinate> centerline, int index) {
		int left = Math.max(0, index - 1);
		int right = Math.min(centerline.size() - 1, index + 1);
		double dx = centerline.get(right).x - centerline.get(left).x;
		double dy = centerline.get(right).y - centerline.get(left).y;
		double length = Math.sqrt(dx * dx + dy * dy);
		if (length == 0) {
			for (int i = 0; i < centerline.size() - 1; i++) {
				dx = centerline.get(i + 1).x - centerline.get(i).x;
				dy = centerline.get(i + 1).y - centerline.get(i).y;
				length = Math.sqrt(dx * dx + dy * dy);
				if (length > 0) break;
			}
		}
		if (length == 0) {
			return new double[] {1.0, 0.0};
		}
		return new double[] {dx / length, dy / length};
	}

	private double polylineLength(ArrayList<Coordinate> coords) {
		if (coords == null || coords.size() < 2) return 0;
		double length = 0;
		for (int i = 0; i < coords.size() - 1; i++) {
			length += ContextCreator.getCityContext().getDistance(coords.get(i), coords.get(i + 1));
		}
		return length;
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			String cleanValue = cleanString(value);
			if (cleanValue != null) return cleanValue;
		}
		return null;
	}

	private String cleanString(String value) {
		if (value == null) return null;
		String cleanValue = value.trim();
		return cleanValue.length() == 0 ? null : cleanValue;
	}

	private int firstInteger(int defaultValue, Integer... values) {
		Integer value = firstIntegerOrNull(values);
		return value == null ? defaultValue : value;
	}

	private Integer firstIntegerOrNull(Integer... values) {
		for (Integer value : values) {
			if (value != null) return value;
		}
		return null;
	}

	private double firstDouble(double defaultValue, Double... values) {
		for (Double value : values) {
			if (value != null) return value;
		}
		return defaultValue;
	}

	private void updateFacilitiesAfterRoadAddition(Road road) {
		ContextCreator.getCityContext().clearRoadLookupCaches();

		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			if (road.canBeOrigin()) {
				double dist = ContextCreator.getCityContext().getDistance(z.getCoord(), road.getStartCoord());
				if (z.getClosestRoad(false) == null || dist < z.getDistToRoad(false)
						|| (dist == z.getDistToRoad(false) && road.getID() < z.getClosestRoad(false))) {
					z.setClosestRoad(road.getID(), false);
					z.setDistToRoad(dist, false);
					z.addNeighboringLink(road.getID(), false);
				}
			}
			if (road.canBeDest()) {
				double dist = ContextCreator.getCityContext().getDistance(z.getCoord(), road.getEndCoord());
				if (z.getClosestRoad(true) == null || dist < z.getDistToRoad(true)
						|| (dist == z.getDistToRoad(true) && road.getID() < z.getClosestRoad(true))) {
					z.setClosestRoad(road.getID(), true);
					z.setDistToRoad(dist, true);
					z.addNeighboringLink(road.getID(), true);
				}
			}
		}

		for (ChargingStation cs : ContextCreator.getChargingStationContext().getAll()) {
			if (road.canBeOrigin()) {
				double dist = ContextCreator.getCityContext().getDistance(cs.getCoord(), road.getStartCoord());
				if (cs.getClosestRoad(false) == null || dist < cs.getDistToRoad(false)
						|| (dist == cs.getDistToRoad(false) && road.getID() < cs.getClosestRoad(false))) {
					cs.setClosestRoad(road.getID(), false);
					cs.setDistToRoad(dist, false);
				}
			}
			if (road.canBeDest()) {
				double dist = ContextCreator.getCityContext().getDistance(cs.getCoord(), road.getEndCoord());
				if (cs.getClosestRoad(true) == null || dist < cs.getDistToRoad(true)
						|| (dist == cs.getDistToRoad(true) && road.getID() < cs.getClosestRoad(true))) {
					cs.setClosestRoad(road.getID(), true);
					cs.setDistToRoad(dist, true);
				}
			}
		}

		updateRoadNeighboringZone(road, false);
		updateRoadNeighboringZone(road, true);
	}

	private void updateRoadNeighboringZone(Road road, boolean goDest) {
		if ((!goDest && !road.canBeOrigin()) || (goDest && !road.canBeDest())) return;

		Coordinate coord = goDest ? road.getEndCoord() : road.getStartCoord();
		Zone nearestZone = nearestZoneTo(coord);
		if (nearestZone != null) {
			double nearestDistance = ContextCreator.getCityContext().getDistance(nearestZone.getCoord(), coord);
			road.setNeighboringZone(nearestZone.getID(), goDest);
			road.setDistToZone(nearestDistance, goDest);
			nearestZone.addNeighboringLink(road.getID(), goDest);
		}
	}

	private Zone nearestZoneTo(Coordinate coord) {
		if (coord == null) return null;
		Zone nearestZone = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
			double dist = ContextCreator.getCityContext().getDistance(z.getCoord(), coord);
			if (dist < nearestDistance || (dist == nearestDistance && nearestZone != null && z.getID() < nearestZone.getID())) {
				nearestZone = z;
				nearestDistance = dist;
			}
		}
		return nearestZone;
	}

	private boolean isUsableRoadForFacility(Road road, boolean goDest) {
		if (road == null || road.firstLane() == null) return false;
		return goDest ? road.canBeDest() : road.canBeOrigin();
	}

	private Road roadFromZoneForFacility(Zone zone, boolean goDest) {
		if (zone == null) return null;
		Integer closestRoadID = zone.getClosestRoad(goDest);
		Road closestRoad = closestRoadID == null ? null : ContextCreator.getRoadContext().get(closestRoadID);
		if (isUsableRoadForFacility(closestRoad, goDest)) {
			return closestRoad;
		}
		for (Integer roadID : zone.getNeighboringLinks(goDest)) {
			Road road = roadID == null ? null : ContextCreator.getRoadContext().get(roadID);
			if (isUsableRoadForFacility(road, goDest)) {
				return road;
			}
		}
		return null;
	}

	private Road nearestRoadByFullScan(Coordinate coord, boolean goDest) {
		if (coord == null) return null;
		Road nearestRoad = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			if (!isUsableRoadForFacility(road, goDest)) continue;
			Coordinate roadCoord = goDest ? road.getEndCoord() : road.getStartCoord();
			double dist = ContextCreator.getCityContext().getDistance(coord, roadCoord);
			if (dist < nearestDistance || (dist == nearestDistance && nearestRoad != null
					&& road.getID() < nearestRoad.getID())) {
				nearestRoad = road;
				nearestDistance = dist;
			}
		}
		return nearestRoad;
	}

	private Road resolveDynamicFacilityRoad(Coordinate coord, boolean goDest) {
		Road road = ContextCreator.getCityContext().findRoadAtCoordinates(coord, goDest);
		if (isUsableRoadForFacility(road, goDest)) {
			return road;
		}
		road = roadFromZoneForFacility(nearestZoneTo(coord), goDest);
		if (isUsableRoadForFacility(road, goDest)) {
			return road;
		}
		return nearestRoadByFullScan(coord, goDest);
	}

	private int selectTaxiGenerationDepartureRoad(Zone zone) {
		if (zone == null) return -1;
		try {
			int sampledRoadID = zone.sampleRoad(false);
			Road sampledRoad = ContextCreator.getRoadContext().get(sampledRoadID);
			if (isUsableRoadForFacility(sampledRoad, false)) {
				return sampledRoadID;
			}
		} catch (RuntimeException ignored) {
			// Fall back below when a zone has no sampled departure candidates.
		}
		Road fallbackRoad = roadFromZoneForFacility(zone, false);
		if (fallbackRoad != null) {
			return fallbackRoad.getID();
		}
		fallbackRoad = nearestRoadByFullScan(zone.getCoord(), false);
		return fallbackRoad == null ? -1 : fallbackRoad.getID();
	}

	/**
	 * Dynamically spawns e-taxis parked at a specified zone.
	 * <p>Input DATA: list of {@code {zoneID, num}}.
	 * <p>Output DATA: list of {@code {zoneID, IDs, STATUS}} with the
	 * assigned vehicle IDs.
	 */
	private HashMap<String, Object> addTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<AddTaxiToZone>> collectionType = new TypeToken<Collection<AddTaxiToZone>>() {};
			Collection<AddTaxiToZone> requests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (AddTaxiToZone req : requests) {
				Zone zone = ContextCreator.getZoneContext().get(req.zoneID);
				if (zone == null) {
					ContextCreator.logger.warn("addTaxi: zone not found: " + req.zoneID);
					jsonData.add("KO");
					continue;
				}
				if (zone.getClosestRoad(false) == null) {
					ContextCreator.logger.warn("addTaxi: zone " + req.zoneID + " has no departure road, cannot spawn taxis");
					jsonData.add("KO");
					continue;
				}
				int departureRoadID = selectTaxiGenerationDepartureRoad(zone);
				Road departureRoad = departureRoadID >= 0 ? ContextCreator.getRoadContext().get(departureRoadID) : null;
				if (departureRoad == null) {
					ContextCreator.logger.warn("addTaxi: zone " + req.zoneID + " has no usable departure road, cannot spawn taxis");
					jsonData.add("KO");
					continue;
				}

				// Ensure taxi maps exist for this zone (may be a dynamically added zone)
				ContextCreator.getVehicleContext().initializeZoneMaps(req.zoneID);

				ArrayList<Integer> spawnedIDs = new ArrayList<Integer>();
				for (int i = 0; i < req.num; i++) {
					ElectricTaxi v = new ElectricTaxi();
					ContextCreator.getVehicleContext().add(v);
					v.initializePlan(req.zoneID, departureRoadID, (int) ContextCreator.getCurrentTick());
					v.getParked(zone);
					v.setCurrentZone(req.zoneID);
					v.setOriginID(req.zoneID);
					v.setDestID(req.zoneID);
					v.setOriginRoad(departureRoad);
					v.setDestRoad(departureRoad);
					v.setCurrentCoord(departureRoad.getStartCoord());
					ContextCreator.getVehicleContext().registerTaxi(v);
					ContextCreator.getVehicleContext().addAvailableTaxi(v, req.zoneID);
					zone.addParkingVehicleStock(1);
					spawnedIDs.add(v.getID());
				}

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("zoneID", req.zoneID);
				record.put("IDs", spawnedIDs);
				record.put("STATUS", "OK");
				jsonData.add(record);
			}
			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addTaxi: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	/**
	 * Dynamically spawns e-buses on an existing named route.
	 * <p>Input DATA: list of {@code {routeName, num}}.
	 * <p>Output DATA: list of {@code {routeName, IDs, STATUS}} with the
	 * assigned vehicle IDs.
	 */
	private HashMap<String, Object> addBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<RouteNameNum>> collectionType = new TypeToken<Collection<RouteNameNum>>() {};
			Collection<RouteNameNum> requests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (RouteNameNum req : requests) {
				int routeID = ContextCreator.bus_schedule.getRouteID(req.routeName);
				if (routeID == -1) {
					ContextCreator.logger.warn("addBus: unknown route name: " + req.routeName);
					jsonData.add("KO");
					continue;
				}

				ArrayList<Integer> stopZones = ContextCreator.bus_schedule.getStopZones(routeID);
				if (stopZones == null || stopZones.isEmpty()) {
					ContextCreator.logger.warn("addBus: route " + req.routeName + " has no stop zones");
					jsonData.add("KO");
					continue;
				}

				Zone startZone = ContextCreator.getZoneContext().get(stopZones.get(0));
				if (startZone == null || startZone.getClosestRoad(false) == null) {
					ContextCreator.logger.warn("addBus: start zone for route " + req.routeName + " is missing or has no departure road");
					jsonData.add("KO");
					continue;
				}

				ArrayList<Integer> departureTime = new ArrayList<Integer>();
				departureTime.add((int) (ContextCreator.getCurrentTick() + 60 / GlobalVariables.SIMULATION_STEP_SIZE));

				ArrayList<Integer> spawnedIDs = new ArrayList<Integer>();
				for (int i = 0; i < req.num; i++) {
					ElectricBus b = new ElectricBus(routeID, stopZones, departureTime);
					b.addPlan(startZone.getID(), startZone.getClosestRoad(false), ContextCreator.getCurrentTick());
					ContextCreator.getVehicleContext().add(b);
					b.setCurrentCoord(startZone.getCoord());
					b.addPlan(startZone.getID(), startZone.getClosestRoad(false), ContextCreator.getCurrentTick());
					b.setNextPlan();
					b.addPlan(startZone.getID(), startZone.getClosestRoad(false), ContextCreator.getCurrentTick());
					b.setNextPlan();
					b.departure();
					ContextCreator.getVehicleContext().registerBus(b);
					spawnedIDs.add(b.getID());
				}

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("routeName", req.routeName);
				record.put("IDs", spawnedIDs);
				record.put("STATUS", "OK");
				jsonData.add(record);
			}
			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control addBus: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}

	// =============================================================
	// CHARGING (continued)
	// =============================================================
	
	/**
	 * Command a vehicle to interrupt its current activity and go charge.
	 * After charging it returns to its pre-charging destination.
	 *
	 * <p>Input DATA: list of {@code {vehID, vehType, chargerType, csID}}
	 * where:
	 * <ul>
	 *   <li>{@code vehType} &mdash; {@code true} = private EV,
	 *       {@code false} = public taxi.</li>
	 *   <li>{@code chargerType} &mdash; {@code ChargingStation.L2 / L3 /
	 *       BUS}.</li>
	 *   <li>{@code csID} &mdash; {@code 0} for auto-select
	 *       (nearest/cheapest with fallback), or a nonzero station ID for
	 *       a specific station.</li>
	 * </ul>
	 *
	 * <p>For parking taxis the return destination is set to its current
	 * zone, and the vehicle is removed from the available-taxi pool only
	 * after a charging dispatch is successfully prepared.
	 */
	private ChargingStation selectChargingStationForControl(ElectricVehicle veh, int chargerType, int csID) {
		if (csID != 0) {
			ChargingStation cs = ContextCreator.getChargingStationContext().get(csID);
			if (cs == null || cs.getClosestRoad(true) == null) {
				return null;
			}
			Road arrivalRoad = ContextCreator.getRoadContext().get(cs.getClosestRoad(true));
			return arrivalRoad != null && arrivalRoad.canBeDest() ? cs : null;
		}

		ChargingStation cs;
		if (veh instanceof ElectricTaxi || veh instanceof ElectricBus) {
			cs = ContextCreator.getCityContext().findNearestChargingStation(veh.getCurrentCoord(), chargerType);
			if (cs == null && chargerType == ChargingStation.L3) {
				cs = ContextCreator.getCityContext().findNearestChargingStation(veh.getCurrentCoord(), ChargingStation.L2);
			}
			return cs;
		}

		cs = ContextCreator.getCityContext().findCheapestChargingStation(veh.getCurrentCoord(), chargerType);
		if (cs == null) {
			cs = ContextCreator.getCityContext().findNearestChargingStation(veh.getCurrentCoord(), chargerType);
		}
		if (cs == null && chargerType == ChargingStation.L3) {
			cs = ContextCreator.getCityContext().findCheapestChargingStation(veh.getCurrentCoord(), ChargingStation.L2);
			if (cs == null) {
				cs = ContextCreator.getCityContext().findNearestChargingStation(veh.getCurrentCoord(), ChargingStation.L2);
			}
		}
		return cs;
	}

	private Road resolveChargingDepartureRoad(ElectricVehicle veh, Zone parkingZoneObj) {
		if (veh.getRoad() != null && veh.getRoad().canBeOrigin()) {
			return veh.getRoad();
		}
		Road fallbackDepartureRoad = null;
		if (veh instanceof ElectricTaxi) {
			int parkingRoadID = ((ElectricTaxi) veh).getCurrentParkingRoad();
			Road parkingRoad = parkingRoadID >= 0 ? ContextCreator.getRoadContext().get(parkingRoadID) : null;
			if (isUsableDepartureRoad(parkingRoad)) {
				return parkingRoad;
			}
			fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, parkingRoad);
		}
		Road originRoad = veh.getOriginRoad() >= 0 ? ContextCreator.getRoadContext().get(veh.getOriginRoad()) : null;
		if (isUsableDepartureRoad(originRoad)) {
			return originRoad;
		}
		fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, originRoad);
		Road destRoad = veh.getDestRoad() >= 0 ? ContextCreator.getRoadContext().get(veh.getDestRoad()) : null;
		if (isUsableDepartureRoad(destRoad)) {
			return destRoad;
		}
		fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, destRoad);
		Road lastDeparturableRoad = veh.getLastDeparturableRoad() >= 0
				? ContextCreator.getRoadContext().get(veh.getLastDeparturableRoad()) : null;
		if (isUsableDepartureRoad(lastDeparturableRoad)) {
			return lastDeparturableRoad;
		}
		fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, lastDeparturableRoad);
		if (veh instanceof ElectricTaxi && parkingZoneObj != null) {
			int zoneDepartureRoadID = selectTaxiGenerationDepartureRoad(parkingZoneObj);
			Road zoneDepartureRoad = zoneDepartureRoadID >= 0
					? ContextCreator.getRoadContext().get(zoneDepartureRoadID) : null;
			if (isUsableDepartureRoad(zoneDepartureRoad)) {
				return zoneDepartureRoad;
			}
			fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, zoneDepartureRoad);
		}
		if (parkingZoneObj != null && parkingZoneObj.getClosestRoad(false) != null) {
			Road zoneDepartureRoad = ContextCreator.getRoadContext().get(parkingZoneObj.getClosestRoad(false));
			if (isUsableDepartureRoad(zoneDepartureRoad)) {
				return zoneDepartureRoad;
			}
			fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, zoneDepartureRoad);
			for (Integer roadID : parkingZoneObj.getNeighboringLinks(false)) {
				Road neighboringRoad = roadID == null ? null : ContextCreator.getRoadContext().get(roadID);
				if (isUsableDepartureRoad(neighboringRoad)) {
					return neighboringRoad;
				}
				fallbackDepartureRoad = firstDepartureFallback(fallbackDepartureRoad, neighboringRoad);
			}
		}
		Road nearbyRoad = ContextCreator.getCityContext().findRoadAtCoordinates(veh.getCurrentCoord(), false);
		if (isUsableDepartureRoad(nearbyRoad)) {
			return nearbyRoad;
		}
		return firstDepartureFallback(fallbackDepartureRoad, nearbyRoad);
	}

	private boolean isUsableDepartureRoad(Road road) {
		return road != null && road.canBeOrigin() && road.firstLane() != null;
	}

	private Road firstDepartureFallback(Road currentFallback, Road road) {
		if (currentFallback != null) return currentFallback;
		return isUsableDepartureRoad(road) ? road : null;
	}

	private int resolveChargingAnchorZone(ElectricVehicle veh, int parkingZone) {
		if (parkingZone >= 0) return parkingZone;
		if (veh.getOriginID() >= 0) return veh.getOriginID();
		if (veh.getDestID() >= 0) return veh.getDestID();
		Road road = veh.getRoad();
		if (road != null) {
			int zoneID = road.getNeighboringZone(false);
			if (ContextCreator.getZoneContext().get(zoneID) != null) return zoneID;
			zoneID = road.getNeighboringZone(true);
			if (ContextCreator.getZoneContext().get(zoneID) != null) return zoneID;
		}
		return -1;
	}

	private boolean ensureChargingPlanAnchor(ElectricVehicle veh, int anchorZoneID, Road departureRoad) {
		if (departureRoad == null || anchorZoneID < 0) return false;
		if (veh.isOnRoad()) {
			if (veh.getPlan().isEmpty()) {
				veh.setOriginID(anchorZoneID);
				veh.setDestID(anchorZoneID);
				veh.setOriginRoad(departureRoad);
				veh.setDestRoad(departureRoad);
				veh.addPlan(anchorZoneID, departureRoad.getID(), ContextCreator.getCurrentTick());
			}
			return true;
		}

		veh.getPlan().clear();
		veh.setOriginID(anchorZoneID);
		veh.setDestID(anchorZoneID);
		veh.setOriginRoad(departureRoad);
		veh.setDestRoad(departureRoad);
		veh.setCurrentCoord(departureRoad.getStartCoord());
		veh.addPlan(anchorZoneID, departureRoad.getID(), ContextCreator.getCurrentTick());
		return true;
	}

	private void removeVehicleFromEnteringQueues(Vehicle veh) {
		ContextCreator.getRoadContext().removeVehicleFromEnteringQueues(veh);
	}

	private void removeTaxiFromIdlePools(ElectricTaxi taxi, boolean releaseParking, Zone parkingZoneObj) {
		if (taxi == null) return;
		if (releaseParking) {
			taxi.releaseParkingSpot(parkingZoneObj);
		}
		ContextCreator.getVehicleContext().removeAvailableTaxiFromAllZones(taxi);
		ContextCreator.getVehicleContext().removeRelocationTaxiFromAllZones(taxi);
	}

	private boolean dispatchVehicleToCharging(ElectricVehicle veh, ChargingStation cs,
			int returnZoneID, int returnRoadID, Zone parkingZoneObj, int parkingZone) {
		if (cs == null || cs.getClosestRoad(true) == null || returnZoneID < 0 || returnRoadID < 0) {
			return false;
		}
		Road chargingArrivalRoad = ContextCreator.getRoadContext().get(cs.getClosestRoad(true));
		Road returnRoad = ContextCreator.getRoadContext().get(returnRoadID);
		if (chargingArrivalRoad == null || !chargingArrivalRoad.canBeDest()
				|| returnRoad == null || !returnRoad.canBeDest()) {
			return false;
		}

		Road departureRoad = resolveChargingDepartureRoad(veh, parkingZoneObj);
		int anchorZoneID = resolveChargingAnchorZone(veh, parkingZone);
		if (!ensureChargingPlanAnchor(veh, anchorZoneID, departureRoad)) {
			return false;
		}

		removeVehicleFromEnteringQueues(veh);
		veh.setOnChargingRoute(true);
		veh.setState(Vehicle.CHARGING_TRIP);
		List<Plan> plans = veh.getPlan();
		int chargeInsertIndex = Math.min(1, plans.size());
		plans.add(chargeInsertIndex, new Plan(cs.getID(), chargingArrivalRoad.getID(), ContextCreator.getNextTick()));
		plans.add(chargeInsertIndex + 1, new Plan(returnZoneID, returnRoadID, ContextCreator.getNextTick()));
		veh.setNextPlan();
		veh.departure(departureRoad);
		return true;
	}

	private HashMap<String, Object> goCharging(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
			return jsonAns;
		}
		try {
			Gson gson = new Gson();
			TypeToken<Collection<VehIDVehTypeChargerTypeCSID>> collectionType =
					new TypeToken<Collection<VehIDVehTypeChargerTypeCSID>>() {};
			Collection<VehIDVehTypeChargerTypeCSID> requests =
					gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			ArrayList<Object> jsonData = new ArrayList<Object>();

			for (VehIDVehTypeChargerTypeCSID req : requests) {
				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("ID", req.vehID);

				ElectricVehicle veh = req.vehType
						? ContextCreator.getVehicleContext().getPrivateEV(req.vehID)
						: (ElectricVehicle) ContextCreator.getVehicleContext().getPublicVehicle(req.vehID);

				if (veh == null) {
					ContextCreator.logger.warn("goCharging: vehicle " + req.vehID + " not found");
					record.put("STATUS", "KO");
					jsonData.add(record);
					continue;
				}

				if (veh.isOnChargingRoute()) {
					ContextCreator.logger.warn("goCharging: vehicle " + req.vehID + " is already on a charging route");
					record.put("STATUS", "KO");
					jsonData.add(record);
					continue;
				}

				// For idle taxis: defer pool cleanup until a charging dispatch succeeds.
				boolean isPublicTaxi = !req.vehType && veh instanceof ElectricTaxi;
				boolean isTaxiParking = isPublicTaxi && veh.getState() == Vehicle.PARKING;
				int parkingZone = -1;
				Zone parkingZoneObj = null;
				if (isPublicTaxi) {
					ElectricTaxi taxi = (ElectricTaxi) veh;
					parkingZone = taxi.getCurrentZone();
					parkingZoneObj = ContextCreator.getZoneContext().get(parkingZone);
				}

				if (req.csID != 0) {
					// Specific charging station requested - replicate goCharging logic manually
					ChargingStation cs = selectChargingStationForControl(veh, req.chargerType, req.csID);
					if (cs == null) {
						ContextCreator.logger.warn("goCharging: charging station " + req.csID
								+ " not found or has no usable arrival road");
						record.put("STATUS", "KO");
						jsonData.add(record);
						continue;
					}

					int returnZoneID;
					int returnRoadID;
					if (isTaxiParking) {
						returnZoneID = parkingZone;
						Zone rz = parkingZoneObj;
						returnRoadID = (rz != null && rz.getClosestRoad(true) != null)
								? rz.getClosestRoad(true)
								: veh.getDestRoad();
					} else {
						returnZoneID = veh.getDestID();
						returnRoadID = veh.getDestRoad();
					}

					if (returnZoneID < 0 || returnRoadID < 0) {
						Road anchorRoad = resolveChargingDepartureRoad(veh, parkingZoneObj);
						int anchorZoneID = resolveChargingAnchorZone(veh, parkingZone);
						Zone anchorZone = ContextCreator.getZoneContext().get(anchorZoneID);
						if (returnZoneID < 0) {
							returnZoneID = anchorZoneID;
						}
						if (returnRoadID < 0 && anchorZone != null && anchorZone.getClosestRoad(true) != null) {
							returnRoadID = anchorZone.getClosestRoad(true);
						}
						if (returnRoadID < 0 && anchorRoad != null && anchorRoad.canBeDest()) {
							returnRoadID = anchorRoad.getID();
						}
					}

					if (returnZoneID < 0 || returnRoadID < 0) {
						// Vehicle was already heading to a charging station; refuse
						ContextCreator.logger.warn("goCharging: vehicle " + req.vehID + " has no valid return destination");
						record.put("STATUS", "KO");
						jsonData.add(record);
						continue;
					}

					if (!dispatchVehicleToCharging(veh, cs, returnZoneID, returnRoadID, parkingZoneObj, parkingZone)) {
						ContextCreator.logger.warn("goCharging: vehicle " + req.vehID
								+ " has no valid departure or return road for charging dispatch");
						record.put("STATUS", "KO");
						jsonData.add(record);
						continue;
					}
					if (isTaxiParking) {
						ElectricTaxi taxi = (ElectricTaxi) veh;
						removeTaxiFromIdlePools(taxi, true, parkingZoneObj);
					} else if (isPublicTaxi) {
						removeTaxiFromIdlePools((ElectricTaxi) veh, false, parkingZoneObj);
					}
					ContextCreator.logger.debug("goCharging: vehicle " + req.vehID + " dispatched to CS " + cs.getID());
				} else {
					// Auto-select: dispatch through the control path so charging interrupts queued plans.
					if (isTaxiParking) {
						// For parked taxis, ensure the return destination is the current zone
						ElectricTaxi taxi = (ElectricTaxi) veh;
						int returnZoneID = parkingZone;
						Zone rz = parkingZoneObj;
						int returnRoadID = (rz != null && rz.getClosestRoad(true) != null)
								? rz.getClosestRoad(true)
								: taxi.getDestRoad();
						ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(
								taxi.getCurrentCoord(), req.chargerType);
						if (cs == null && req.chargerType == ChargingStation.L3) {
							cs = ContextCreator.getCityContext().findNearestChargingStation(
									taxi.getCurrentCoord(), ChargingStation.L2);
						}
						if (cs == null) {
							ContextCreator.logger.warn("goCharging: no suitable station found for taxi " + req.vehID);
							record.put("STATUS", "KO");
							jsonData.add(record);
							continue;
						}
						if (returnZoneID < 0) {
							ContextCreator.logger.warn("goCharging: vehicle " + req.vehID + " has no valid return destination");
							record.put("STATUS", "KO");
							jsonData.add(record);
							continue;
						}
						if (!dispatchVehicleToCharging(taxi, cs, returnZoneID, returnRoadID, parkingZoneObj, parkingZone)) {
							ContextCreator.logger.warn("goCharging: vehicle " + req.vehID
									+ " has no valid departure or return road for charging dispatch");
							record.put("STATUS", "KO");
							jsonData.add(record);
							continue;
						}
						removeTaxiFromIdlePools(taxi, true, parkingZoneObj);
					} else {
						ChargingStation cs = selectChargingStationForControl(veh, req.chargerType, 0);
						if (cs == null) {
							ContextCreator.logger.warn("goCharging: no suitable station found for vehicle " + req.vehID);
							record.put("STATUS", "KO");
							jsonData.add(record);
							continue;
						}
						int returnZoneID = veh.getDestID();
						int returnRoadID = veh.getDestRoad();
						if (returnZoneID < 0 || returnRoadID < 0) {
							Road anchorRoad = resolveChargingDepartureRoad(veh, parkingZoneObj);
							int anchorZoneID = resolveChargingAnchorZone(veh, parkingZone);
							Zone anchorZone = ContextCreator.getZoneContext().get(anchorZoneID);
							if (returnZoneID < 0) {
								returnZoneID = anchorZoneID;
							}
							if (returnRoadID < 0 && anchorZone != null && anchorZone.getClosestRoad(true) != null) {
								returnRoadID = anchorZone.getClosestRoad(true);
							}
							if (returnRoadID < 0 && anchorRoad != null && anchorRoad.canBeDest()) {
								returnRoadID = anchorRoad.getID();
							}
						}
						if (!dispatchVehicleToCharging(veh, cs, returnZoneID, returnRoadID, parkingZoneObj, parkingZone)) {
							ContextCreator.logger.warn("goCharging: vehicle " + req.vehID
									+ " has no valid departure or return road for charging dispatch");
							record.put("STATUS", "KO");
							jsonData.add(record);
							continue;
						}
						if (isPublicTaxi) {
							removeTaxiFromIdlePools((ElectricTaxi) veh, false, parkingZoneObj);
						}
					}
				}

				record.put("STATUS", "OK");
				jsonData.add(record);
			}

			jsonAns.put("DATA", jsonData);
			jsonAns.put("CODE", "OK");
		} catch (Exception e) {
			ContextCreator.logger.error("Error processing control goCharging: " + e.toString());
			jsonAns.put("CODE", "KO");
		}
		return jsonAns;
	}
}
