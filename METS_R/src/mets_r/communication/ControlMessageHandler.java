package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
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
import mets_r.communication.MessageClass.BusIDRouteNameStopIndex;
import mets_r.communication.MessageClass.BusIDRouteNameZoneRoadStopIndex;
import mets_r.communication.MessageClass.ChargerIDChargerTypeWeight;
import mets_r.communication.MessageClass.SignalIDPhase;
import mets_r.communication.MessageClass.SignalIDPhaseTiming;
import mets_r.communication.MessageClass.SignalPhasePlan;
import mets_r.communication.MessageClass.SignalPhasePlanTicks;
import mets_r.communication.MessageClass.OrigRoadDestRoadNum;
import mets_r.communication.MessageClass.RoadIDWeight;
import mets_r.communication.MessageClass.RouteNameDepartTime;
import mets_r.communication.MessageClass.RouteNameZonesRoads;
import mets_r.communication.MessageClass.RouteNameZonesRoadsPath;
import mets_r.communication.MessageClass.VehIDOrigDestNum;
import mets_r.communication.MessageClass.VehIDOrigRoadDestRoadNum;
import mets_r.communication.MessageClass.VehIDReqID;
import mets_r.communication.MessageClass.VehIDZoneID;
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
import mets_r.communication.MessageClass.ZoneIDOrigDestRouteNameNum;
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
import mets_r.mobility.Request;
import mets_r.mobility.Vehicle;

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
		messageHandlers.put("repositionTaxi", this::repositionTaxi);
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
		// Dynamic infrastructure & fleet additions
		// =============================================================
		messageHandlers.put("addZone", this::addZone);
		messageHandlers.put("addChargingStation", this::addChargingStation);
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
					ContextCreator.save(zipPath);
					jsonAns.put("CODE", "OK");
					jsonAns.put("path", zipPath);
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
	 * <p>Input DATA: {@code {"path": "<zip file path>"}}.
	 */
	private HashMap<String, Object> loadSim(JSONObject jsonMsg) {
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
					// Use the deferred variant so the scheduler has fully
					// completed the current tick (every recurring action
					// rescheduled into the main queue) before rebuildForLoad
					// removes them. Same on-deck-queue rationale as reset.
					ContextCreator.deferredLoad(zipPath);
					jsonAns.put("CODE", "OK");
					jsonAns.put("path", zipPath);
					jsonAns.put("tick", ContextCreator.getCurrentTick());
				}
			} catch (Exception e) {
				ContextCreator.logger.error("Error loading simulation: " + e.toString());
				jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
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
     * where {@code vehType=true} selects a private vehicle and
     * {@code vehType=false} selects a public one.
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
									// Update its location in the target link and target lane
									if (veh.getRoad() == road) {
										veh.removeFromCurrentLane(); // Just remove the vehicle from the current lane
									}
									else {
										veh.removeFromCurrentLane();
										veh.removeFromCurrentRoad();
									}
									
									road.teleportVehicle(veh, lane, vehIDVehTypeRoadLaneDist.dist);
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
	 * Dispatch a taxi to serve an already-pending request.
	 *
	 * <p>Input DATA: list of {@code {vehID, reqID}}. The request must have
	 * been added via {@code addTaxiRequests} or {@code addTaxiReqBwRoads}
	 * (or generated by the simulation itself) and must still be pending.
	 * This endpoint does NOT fabricate new requests &mdash; it only matches
	 * an available taxi to an existing pending request, takes the taxi out
	 * of its zone's available pool / parking stock, and starts its pickup
	 * trip via {@link ElectricTaxi#servePassenger(java.util.ArrayList)}.
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
				
				ElectricTaxi veh = (ElectricTaxi) ContextCreator.getVehicleContext().getPublicVehicle(entry.vehID);
				if (veh == null) {
					ContextCreator.logger.warn("dispatchTaxi: vehicle " + entry.vehID + " not found");
					record.put("STATUS", "KO");
					record.put("WARN", "vehicle not found");
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
				
				// Take the taxi out of its current zone's available pool and
				// parking stock (mirrors what Zone.servePassengerByTaxi does).
				int curZoneID = veh.getCurrentZone();
				ContextCreator.getVehicleContext().removeAvailableTaxi(veh, curZoneID);
				if (veh.getState() == Vehicle.PARKING) {
					Zone parkedZone = ContextCreator.getZoneContext().get(curZoneID);
					if (parkedZone != null) parkedZone.removeOneParkingVehicle();
				}
				
				Request p = found.request;
				p.matchedTime = ContextCreator.getCurrentTick();
				origZone.taxiPickupRequest += 1;
				origZone.taxiServedPassWaitingTime += p.getCurrentWaitingTime();
				destZone.addFutureSupply();
				
				ArrayList<Request> plist = new ArrayList<Request>();
				plist.add(p);
				veh.servePassenger(plist);
				
				record.put("origZone", origZone.getID());
				record.put("destZone", destZone.getID());
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
	
	/**
	 * Reposition a taxi to a destination zone.
	 *
	 * <p>Input DATA: list of {@code {vehID, zoneID}}. The taxi must
	 * currently be idle (state {@code PARKING}, {@code CRUISING_TRIP}, or
	 * {@code NONE_OF_THE_ABOVE}); it is removed from its current zone's
	 * available pool / parking stock and dispatched on an
	 * {@code INACCESSIBLE_RELOCATION_TRIP} to a road sampled from the
	 * destination zone. On arrival, the existing {@code reachDest} logic
	 * handles parking / cruising as usual.
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
				if (state != Vehicle.PARKING && state != Vehicle.CRUISING_TRIP && state != Vehicle.NONE_OF_THE_ABOVE) {
					ContextCreator.logger.warn("repositionTaxi: vehicle " + entry.vehID + " not in a relocatable state (state=" + state + ")");
					record.put("STATUS", "KO");
					record.put("WARN", "vehicle not idle");
					jsonData.add(record);
					continue;
				}
				
				int curZoneID = veh.getCurrentZone();
				Zone origZone = ContextCreator.getZoneContext().get(curZoneID);
				
				ContextCreator.getVehicleContext().removeAvailableTaxi(veh, curZoneID);
				if (state == Vehicle.PARKING && origZone != null) {
					origZone.removeOneParkingVehicle();
				}
				
				destZone.addFutureSupply();
				if (origZone != null) origZone.numberOfRelocatedVehicles += 1;
				
				// ElectricTaxi.relocation handles stopCruising if needed and
				// enters INACCESSIBLE_RELOCATION_TRIP state.
				veh.relocation(destZone.getID(), destZone.sampleRoad(true));
				
				record.put("origZone", curZoneID);
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
		for (Zone z : ContextCreator.getZoneContext().getAll()) {
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
	
	/**
	 * Override {@link Request} maximum waiting tolerance when the caller
	 * specifies a positive value ({@code maxWaitingTime} in ticks). Same units
	 * as {@link Request#getCurrentWaitingTime()} accumulation per zone refresh.
	 */
	private static void applyOptionalMaxWaitingTime(Request req, Integer maxWaitingTicks) {
		if (maxWaitingTicks != null && maxWaitingTicks > 0) {
			req.setMaxWaitingTime(maxWaitingTicks);
		}
	}
	
	// =============================================================
	// RIDE-HAILING: ADD PENDING REQUESTS / BUS ASSIGNMENT
	// (the only entry points that create Request objects)
	// =============================================================
	
	/**
	 * Add one or more pending taxi requests to a zone's pending queue
	 * (specified by zone IDs).
	 *
	 * <p>Input DATA: list of {@code {zoneID, dest, routeName, num,
	 * maxWaitingTime?}} where {@code zoneID} is the origin zone,
	 * {@code dest} is the destination zone, and {@code num} is the party
	 * size. Optional {@code maxWaitingTime}: positive integer, maximum wait
	 * before the passenger abandons the queue ({@link Request#setMaxWaitingTime(int)}
	 * in simulation ticks); if omitted or non-positive, the default tolerance
	 * applies (typically no abandonment before end of simulation).
	 *
	 * <p>The {@code routeName} field is kept for Gson compatibility but is unused
	 * for taxi inserts.
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
				TypeToken<Collection<ZoneIDOrigDestRouteNameNum>> collectionType = new TypeToken<Collection<ZoneIDOrigDestRouteNameNum>>() {};
				Collection<ZoneIDOrigDestRouteNameNum> zoneIDOrigDestNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(ZoneIDOrigDestRouteNameNum zoneIDOrigDestNum: zoneIDOrigDestNums) {
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
				TypeToken<Collection<OrigRoadDestRoadNum>> collectionType = new TypeToken<Collection<OrigRoadDestRoadNum>>() {};
				Collection<OrigRoadDestRoadNum> origRoadDestRoadNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(OrigRoadDestRoadNum origRoadDestRoadNum: origRoadDestRoadNums) {
					Road r1 = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoadNum.orig);
					Road r2 = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoadNum.dest);
					if(r1 != null && r2 != null) {
						Zone z1 = ContextCreator.getZoneContext().get(r1.getNeighboringZone(false));
						// generate request
						Request p = new Request(z1.getID(), r2.getNeighboringZone(true), r1.getID(), r2.getID(), origRoadDestRoadNum.num);
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
	 * Match a bus request to a specific bus along its current route.
	 * Searches the bus's remaining stops for one matching the requested
	 * origin and a later one matching the destination; if found, a new
	 * {@link Request} is created and added to the bus's to-board list.
	 *
	 * <p>Input DATA: list of {@code {vehID, orig, dest, num}} where
	 * {@code orig} and {@code dest} are zone IDs that must appear (in
	 * order) on the bus's route after its current stop.
	 *
	 * <p>Output DATA: list of {@code {ID: vehID, STATUS}} entries.
	 *
	 * <p>TODO: extend to consider arbitrary bus routes rather than only
	 * the bus's current assigned route.
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
				TypeToken<Collection<VehIDOrigDestNum>> collectionType = new TypeToken<Collection<VehIDOrigDestNum>>() {};
				Collection<VehIDOrigDestNum> vehIDOrigDestNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(VehIDOrigDestNum vehIDOrigDestNum: vehIDOrigDestNums) {
					ElectricBus veh = (ElectricBus) ContextCreator.getVehicleContext().getPublicVehicle(vehIDOrigDestNum.vehID);
					int rID = veh.getRouteID();
					ArrayList<Integer> stops = ContextCreator.bus_schedule.getStopZones(rID);
					int idx0 = -1;
					int idx1 = -1;
					for (int i = veh.getCurrentStop(); i < stops.size(); i++) {
				        if (!stops.get(i).equals(vehIDOrigDestNum.orig)) continue;
				        for (int j = i+1; j < stops.size(); j++) {
				        	if(!stops.get(j).equals(vehIDOrigDestNum.dest)) continue;
				        	idx0 = i;
				        	idx1 = j;
				        }
					}
					
					if(veh != null && idx0 >= 0 && idx1 > idx0 && rID != -1) {
						// generate request
						Zone z1 = ContextCreator.getZoneContext().get(vehIDOrigDestNum.orig);
						Request p = new Request(vehIDOrigDestNum.orig, vehIDOrigDestNum.dest, ContextCreator.bus_schedule.getStopRoad(rID, idx0).getID(),  ContextCreator.bus_schedule.getStopRoad(rID, idx1).getID(), vehIDOrigDestNum.num);
						p.matchedTime = ContextCreator.getCurrentTick();
						
						if(veh.addToBoardPass(p)) {
							z1.busPickupRequest += 1;
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehIDOrigDestNum.vehID);
				    		record2.put("STATUS", "OK");
							jsonData.add(record2);
							continue;
						}
					}
		    		HashMap<String, Object> record2 = new HashMap<String, Object>();
		    		record2.put("ID", vehIDOrigDestNum.vehID);
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
	 * Add one or more pending bus requests to a zone's bus queue. The
	 * origin and destination zones must both appear (in order) on the
	 * specified bus route.
	 *
	 * <p>Input DATA: list of {@code {zoneID, dest, routeName, num,
	 * maxWaitingTime?}}. Optional {@code maxWaitingTime}: positive integer,
	 * maximum wait before the passenger abandons the queue (simulation ticks);
	 * if omitted or non-positive, the default tolerance applies (typically bus
	 * passengers keep waiting until the end of simulation).
	 *
	 * <p>Output DATA: list of {@code {ID: zoneID, STATUS}} entries.
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
				TypeToken<Collection<ZoneIDOrigDestRouteNameNum>> collectionType = new TypeToken<Collection<ZoneIDOrigDestRouteNameNum>>() {};
				Collection<ZoneIDOrigDestRouteNameNum> zoneIDOrigDestRouteNameNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(ZoneIDOrigDestRouteNameNum zoneIDOrigDestRouteNameNum: zoneIDOrigDestRouteNameNums) {
					Zone z1 = ContextCreator.getZoneContext().get(zoneIDOrigDestRouteNameNum.zoneID);
					Zone z2 = ContextCreator.getZoneContext().get(zoneIDOrigDestRouteNameNum.dest);
					
					int rID = ContextCreator.bus_schedule.getRouteID(zoneIDOrigDestRouteNameNum.routeName);
					int idx0 = -1;
					int idx1 = -1;
					
					if(rID != -1) {
						ArrayList<Integer> stops = ContextCreator.bus_schedule.getStopZones(rID);
						
						for (int i = 0; i < stops.size(); i++) {
					        if (!stops.get(i).equals(zoneIDOrigDestRouteNameNum.zoneID)) continue;
					        for (int j = i+1; j < stops.size(); j++) {
					        	if(!stops.get(j).equals(zoneIDOrigDestRouteNameNum.dest)) continue;
					        	idx0 = i;
					        	idx1 = j;
					        }
						}
					}
					
					if(idx0 >= 0 && idx1 > idx0 && rID != -1) {
						// generate request
						Request p = new Request(z1.getID(), z2.getID(), ContextCreator.bus_schedule.getStopRoad(rID, idx0).getID(), ContextCreator.bus_schedule.getStopRoad(rID, idx1).getID(), zoneIDOrigDestRouteNameNum.num);
						applyOptionalMaxWaitingTime(p, zoneIDOrigDestRouteNameNum.maxWaitingTime);
						z1.insertBusPass(p);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestRouteNameNum.zoneID);
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
	// DYNAMIC INFRASTRUCTURE & FLEET ADDITIONS
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
			ChargingStation cs = new ChargingStation(csID, p.numL2, p.numL3, p.numBus, p.priceL2, p.priceL3);
			cs.setCoord(coord);
			ContextCreator.getChargingStationContext().put(csID, cs);
			ContextCreator.getChargingStationGeography().move(cs, geomFac.createPoint(coord));

				// Find and attach the nearest departure and arrival roads
				Road deptRoad = ContextCreator.getCityContext().findRoadAtCoordinates(coord, false);
				Road arrRoad  = ContextCreator.getCityContext().findRoadAtCoordinates(coord, true);
				if (deptRoad != null) {
					cs.setClosestRoad(deptRoad.getID(), false);
					cs.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, deptRoad.getStartCoord()), false);
				} else {
					ContextCreator.logger.warn("addChargingStation: no departure road found for station " + csID);
				}
				if (arrRoad != null) {
					cs.setClosestRoad(arrRoad.getID(), true);
					cs.setDistToRoad(ContextCreator.getCityContext().getDistance(coord, arrRoad.getEndCoord()), true);
				} else {
					ContextCreator.logger.warn("addChargingStation: no arrival road found for station " + csID);
				}

				// Schedule the station's tick steps so it actively charges vehicles
				// and produces ChargerLog entries identical to pre-loaded stations
				ContextCreator.scheduleNewChargingStation(cs);

				HashMap<String, Object> record = new HashMap<String, Object>();
				record.put("ID", csID);
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

				// Ensure taxi maps exist for this zone (may be a dynamically added zone)
				ContextCreator.getVehicleContext().initializeZoneMaps(req.zoneID);

				ArrayList<Integer> spawnedIDs = new ArrayList<Integer>();
				for (int i = 0; i < req.num; i++) {
					ElectricTaxi v = new ElectricTaxi();
					ContextCreator.getVehicleContext().add(v);
					v.initializePlan(req.zoneID, zone.getClosestRoad(false), (int) ContextCreator.getCurrentTick());
					v.setCurrentZone(req.zoneID);
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
	 *       (nearest/cheapest with fallback), or a negative station ID for
	 *       a specific station.</li>
	 * </ul>
	 *
	 * <p>For parking taxis the vehicle is removed from the available-taxi
	 * pool first and the return destination is set to its current zone so
	 * it comes back after charging.
	 */
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

				// For parking taxis: clean up pool membership before rerouting
				boolean isTaxiParking = !req.vehType && veh.getState() == Vehicle.PARKING;
				if (isTaxiParking) {
					ElectricTaxi taxi = (ElectricTaxi) veh;
					int parkingZone = taxi.getCurrentZone();
					Zone z = ContextCreator.getZoneContext().get(parkingZone);
					if (z != null) z.removeOneParkingVehicle();
					ContextCreator.getVehicleContext().removeAvailableTaxi(taxi, parkingZone);
				}

				if (req.csID != 0) {
					// Specific charging station requested — replicate goCharging logic manually
					ChargingStation cs = ContextCreator.getChargingStationContext().get(req.csID);
					if (cs == null) {
						ContextCreator.logger.warn("goCharging: charging station " + req.csID + " not found");
						record.put("STATUS", "KO");
						jsonData.add(record);
						continue;
					}
					if (cs.getClosestRoad(true) == null) {
						ContextCreator.logger.warn("goCharging: charging station " + req.csID + " has no arrival road");
						record.put("STATUS", "KO");
						jsonData.add(record);
						continue;
					}

					int returnZoneID;
					int returnRoadID;
					if (isTaxiParking) {
						returnZoneID = ((ElectricTaxi) veh).getCurrentZone();
						Zone rz = ContextCreator.getZoneContext().get(returnZoneID);
						returnRoadID = (rz != null && rz.getClosestRoad(true) != null)
								? rz.getClosestRoad(true)
								: veh.getDestRoad();
					} else {
						returnZoneID = veh.getDestID();
						returnRoadID = veh.getDestRoad();
					}

					if (returnZoneID < 0) {
						// Vehicle was already heading to a charging station; refuse
						ContextCreator.logger.warn("goCharging: vehicle " + req.vehID + " has no valid return destination");
						record.put("STATUS", "KO");
						jsonData.add(record);
						continue;
					}

					veh.setOnChargingRoute(true);
					veh.setState(Vehicle.CHARGING_TRIP);
					veh.addPlan(cs.getID(), cs.getClosestRoad(true), ContextCreator.getNextTick());
					veh.setNextPlan();
					veh.addPlan(returnZoneID, returnRoadID, ContextCreator.getNextTick());
					veh.departure();
					ContextCreator.logger.debug("goCharging: vehicle " + req.vehID + " dispatched to CS " + cs.getID());
				} else {
					// Auto-select: delegate to the vehicle's own goCharging logic
					if (isTaxiParking) {
						// For parked taxis, ensure the return destination is the current zone
						ElectricTaxi taxi = (ElectricTaxi) veh;
						int returnZoneID = taxi.getCurrentZone();
						Zone rz = ContextCreator.getZoneContext().get(returnZoneID);
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
						taxi.setOnChargingRoute(true);
						taxi.setState(Vehicle.CHARGING_TRIP);
						taxi.addPlan(cs.getID(), cs.getClosestRoad(true), ContextCreator.getNextTick());
						taxi.setNextPlan();
						taxi.addPlan(returnZoneID, returnRoadID, ContextCreator.getNextTick());
						taxi.departure();
					} else {
						veh.goCharging(req.chargerType);
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
